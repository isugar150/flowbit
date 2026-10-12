package com.flowbit.milestone.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.milestone.domain.Milestone
import com.flowbit.milestone.domain.MilestoneRepository
import com.flowbit.project.application.ProjectAccess
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A milestone with how far along its issues are. */
data class MilestoneView(
    val id: UUID,
    val name: String,
    val targetDate: LocalDate?,
    /** Issues in the milestone, not counting canceled ones: they are no longer part of the work. */
    val issueCount: Int,
    val doneCount: Int,
    val createdAt: Instant,
)

/**
 * Milestones of a project. Anyone who may read the project sees them; anyone who may edit it
 * creates, renames, dates and deletes them. Every call goes through ProjectService.access.
 */
@Service
class MilestoneService(
    private val projectService: ProjectService,
    private val repository: MilestoneRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {
    /** Dated milestones first, soonest first; undated ones after, in the order they were made. */
    suspend fun list(
        user: AuthenticatedUser,
        projectKey: String,
    ): List<MilestoneView> = views(projectService.access(user, projectKey).project.id)

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        projectKey: String,
        rawName: String,
        targetDate: LocalDate?,
    ): MilestoneView {
        val project = editable(projectService.access(user, projectKey)).project
        val now = clock.instant()
        val milestone = Milestone(UuidV7.generate(now), project.id, validName(rawName), targetDate, now, now)
        entityTemplate.insert(milestone).awaitSingle()
        return views(project.id).single { it.id == milestone.id }
    }

    /** Changes the fields present in `changes`: `name`, `targetDate` (null clears it). */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        projectKey: String,
        milestoneId: UUID,
        changes: Map<String, Any?>,
    ): MilestoneView {
        val project = editable(projectService.access(user, projectKey)).project
        val milestone = repository.findByIdAndProjectId(milestoneId, project.id) ?: throw notFound()
        if ("name" in changes) milestone.name = validName(changes["name"] as? String ?: "")
        if ("targetDate" in changes) {
            milestone.targetDate =
                changes["targetDate"]?.let { value ->
                    runCatching { LocalDate.parse(value as String) }.getOrElse {
                        throw ValidationException("targetDate", "INVALID", "값이 올바르지 않습니다")
                    }
                }
        }
        milestone.updatedAt = clock.instant()
        repository.save(milestone)
        return views(project.id).single { it.id == milestone.id }
    }

    /** The milestone goes; its issues stay and belong to no milestone. */
    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        projectKey: String,
        milestoneId: UUID,
    ) {
        val project = editable(projectService.access(user, projectKey)).project
        val milestone = repository.findByIdAndProjectId(milestoneId, project.id) ?: throw notFound()
        repository.delete(milestone)
    }

    /** For issues: a milestone can only be set from the issue's own project. */
    suspend fun belongsTo(
        projectId: UUID,
        milestoneId: UUID,
    ): Boolean = repository.findByIdAndProjectId(milestoneId, projectId) != null

    private suspend fun views(projectId: UUID): List<MilestoneView> =
        databaseClient
            .sql(
                """
                select m.id, m.name, m.target_date, m.created_at,
                       count(i.id) filter (where i.status <> 'CANCELED') as issue_count,
                       count(i.id) filter (where i.status = 'DONE') as done_count
                from milestones m
                left join issues i on i.milestone_id = m.id
                where m.project_id = :projectId
                group by m.id
                order by m.target_date nulls last, m.created_at
                """.trimIndent(),
            ).bind("projectId", projectId)
            .map { row ->
                MilestoneView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    name = checkNotNull(row.get("name", String::class.java)),
                    targetDate = row.get("target_date", LocalDate::class.java),
                    issueCount = checkNotNull(row.get("issue_count", Long::class.javaObjectType)).toInt(),
                    doneCount = checkNotNull(row.get("done_count", Long::class.javaObjectType)).toInt(),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private fun editable(access: ProjectAccess): ProjectAccess {
        if (!access.role.canEdit) throw ForbiddenException("이 프로젝트의 목표를 고칠 권한이 없습니다")
        return access
    }

    private fun validName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw ValidationException("name", "REQUIRED", "목표 이름을 입력하세요")
        if (name.length > Milestone.NAME_MAX_LENGTH) {
            throw ValidationException("name", "TOO_LONG", "목표 이름은 ${Milestone.NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        return name
    }

    private fun notFound() = NotFoundException("MILESTONE_NOT_FOUND", "목표를 찾을 수 없습니다")
}
