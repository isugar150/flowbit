package com.flowbit.milestone.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.content.ContentEvent
import com.flowbit.content.ContentEventType
import com.flowbit.content.ContentLog
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.media.application.ImageStorage
import com.flowbit.milestone.domain.Milestone
import com.flowbit.milestone.domain.MilestoneRepository
import com.flowbit.milestone.domain.MilestoneStatus
import com.flowbit.project.application.ProjectAccess
import com.flowbit.project.application.ProjectMemberStore
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

/** Someone a milestone is given to. */
data class MilestoneAssignee(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
)

/** A milestone with how far along its issues are. */
data class MilestoneView(
    val id: UUID,
    val name: String,
    val targetDate: LocalDate?,
    val status: MilestoneStatus,
    /** In the order they were given the milestone; empty when no one has it (D-185). */
    val assignees: List<MilestoneAssignee>,
    /** Issues in the milestone, not counting canceled ones: they are no longer part of the work. */
    val issueCount: Int,
    val doneCount: Int,
    val createdAt: Instant,
    /** The milestone's own document as editor JSON, or null when nothing was written. */
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
)

/**
 * Milestones of a project. Anyone who may read the project sees them; anyone who may edit it
 * creates, renames, dates, sets the status of and deletes them. Every call goes through ProjectService.access.
 */
@Service
class MilestoneService(
    private val projectService: ProjectService,
    private val memberStore: ProjectMemberStore,
    private val repository: MilestoneRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val databaseClient: DatabaseClient,
    private val richText: RichText,
    private val contentLog: ContentLog,
    private val clock: Clock,
) {
    /** Dated milestones first, soonest first; undated ones after, in the order they were made. */
    suspend fun list(
        user: AuthenticatedUser,
        projectId: String,
    ): List<MilestoneView> = views(projectService.access(user, projectId).project.id)

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        projectId: String,
        rawName: String,
        targetDate: LocalDate?,
        status: MilestoneStatus = MilestoneStatus.PLANNED,
    ): MilestoneView {
        val project = editable(projectService.access(user, projectId)).project
        val now = clock.instant()
        val milestone = Milestone(UuidV7.generate(now), project.id, validName(rawName), targetDate, status, now, now)
        entityTemplate.insert(milestone).awaitSingle()
        contentLog.record(projectTarget(project.id), user.id, listOf(ContentEvent(ContentEventType.MILESTONE_ADDED, mapOf("name" to milestone.name))), now)
        return views(project.id).single { it.id == milestone.id }
    }

    private fun projectTarget(projectId: UUID) = ContentTarget(ContentTargetType.PROJECT, projectId)

    /**
     * Changes the fields present in `changes`: `name`, `targetDate`, `status`, `content` (null clears
     * the date or the document) and `assigneeIds` (the whole list; people of the project, up to ten).
     */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        projectId: String,
        milestoneId: UUID,
        changes: Map<String, Any?>,
    ): MilestoneView {
        val project = editable(projectService.access(user, projectId)).project
        val milestone = repository.findByIdAndProjectId(milestoneId, project.id) ?: throw notFound()
        // Read only when they are changed: the notes are saved every few seconds and need none of this.
        val assigneesBefore = if ("assigneeIds" in changes) assigneesOf(listOf(milestone.id))[milestone.id].orEmpty() else emptyList()
        val assigneeIds = if ("assigneeIds" in changes) requireAssignees(project.id, changes["assigneeIds"], assigneesBefore) else null
        val oldName = milestone.name
        val oldDate = milestone.targetDate
        val oldStatus = milestone.status
        if ("name" in changes) milestone.name = validName(changes["name"] as? String ?: "")
        if ("targetDate" in changes) {
            milestone.targetDate =
                changes["targetDate"]?.let { value ->
                    runCatching { LocalDate.parse(value as String) }.getOrElse {
                        throw ValidationException("targetDate", "INVALID", "값이 올바르지 않습니다")
                    }
                }
        }
        if ("status" in changes) {
            milestone.status =
                (changes["status"] as? String)?.let { value -> MilestoneStatus.entries.firstOrNull { it.name == value } }
                    ?: throw ValidationException("status", "INVALID", "값이 올바르지 않습니다")
        }
        // The milestone's document is a page whose blocks can be commented on: every top-level block has a number (D-165).
        val document = if ("content" in changes) changes["content"]?.let { richText.parse(it, blockIds = true) } else null
        val contentChanged = "content" in changes && !richText.same(richText.load(RichText.ContentTable.MILESTONES, milestone.id), document)
        milestone.updatedAt = clock.instant()
        repository.save(milestone)
        // The entity does not carry the document (it is not read with every milestone row).
        if ("content" in changes) richText.store(RichText.ContentTable.MILESTONES, milestone.id, document)
        if (assigneeIds != null) replaceAssignees(milestone.id, assigneeIds, milestone.updatedAt)

        val events = mutableListOf<ContentEvent>()
        if (milestone.name != oldName) events += ContentEvent(ContentEventType.NAME_CHANGED, mapOf("from" to oldName, "to" to milestone.name))
        if (contentChanged) events += ContentEvent(ContentEventType.CONTENT_EDITED)
        if (milestone.status != oldStatus) {
            events += ContentEvent(ContentEventType.STATUS_CHANGED, mapOf("from" to oldStatus.name, "to" to milestone.status.name))
        }
        if (milestone.targetDate != oldDate) {
            events += ContentEvent(ContentEventType.TARGET_DATE_CHANGED, mapOf("from" to oldDate?.toString(), "to" to milestone.targetDate?.toString()))
        }
        if (assigneeIds != null) {
            val added = assigneeIds.filter { id -> assigneesBefore.none { it.id == id } }.map { contentLog.nameOf(it) }
            val removed = assigneesBefore.filter { it.id !in assigneeIds }.map { it.displayName ?: it.loginId }
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                events += ContentEvent(ContentEventType.ASSIGNEE_CHANGED, mapOf("added" to added, "removed" to removed))
            }
        }
        contentLog.record(ContentTarget(ContentTargetType.MILESTONE, milestone.id), user.id, events, milestone.updatedAt)
        return views(project.id).single { it.id == milestone.id }
    }

    /**
     * The ids without repeats, once every one of them may be given the milestone: people of the
     * project. Those who already have it (`current`) are not checked again, so someone who has
     * since left the project can stay on, as with an issue.
     */
    private suspend fun requireAssignees(
        projectId: UUID,
        raw: Any?,
        current: List<MilestoneAssignee>,
    ): List<UUID> {
        fun invalid() = ValidationException("assigneeIds", "INVALID", "값이 올바르지 않습니다")
        val ids =
            (raw as? List<*> ?: throw invalid())
                .map { value -> runCatching { UUID.fromString(value as String) }.getOrElse { throw invalid() } }
                .distinct()
        if (ids.size > Milestone.MAX_ASSIGNEES) {
            throw ValidationException("assigneeIds", "TOO_MANY", "담당자는 ${Milestone.MAX_ASSIGNEES}명까지 지정할 수 있습니다")
        }
        for (id in ids - current.map { it.id }.toSet()) {
            if (!memberStore.isActiveMember(projectId, id)) {
                throw ValidationException("assigneeIds", "NOT_A_MEMBER", "담당자는 이 프로젝트의 구성원이어야 합니다")
            }
        }
        return ids
    }

    /**
     * Makes these people, in this order, the milestone's assignees. People who already had it keep
     * their place; the ones added are put after them.
     */
    private suspend fun replaceAssignees(
        milestoneId: UUID,
        userIds: List<UUID>,
        now: Instant,
    ) {
        databaseClient
            .sql("delete from milestone_assignees where milestone_id = :milestoneId and not (user_id = any(:userIds))")
            .bind("milestoneId", milestoneId)
            .bind("userIds", userIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        // A microsecond apart, so those added in one save keep the order they were sent in.
        userIds.forEachIndexed { index, userId ->
            databaseClient
                .sql(
                    "insert into milestone_assignees (milestone_id, user_id, created_at) values (:milestoneId, :userId, :at) on conflict do nothing",
                ).bind("milestoneId", milestoneId)
                .bind("userId", userId)
                .bind("at", now.plusNanos(index * 1000L))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    /** Who has each of these milestones, in the order they were given it. */
    private suspend fun assigneesOf(milestoneIds: Collection<UUID>): Map<UUID, List<MilestoneAssignee>> {
        if (milestoneIds.isEmpty()) return emptyMap()
        return databaseClient
            .sql(
                """
                select ma.milestone_id, u.id, u.login_id, u.display_name, u.avatar_key
                from milestone_assignees ma join users u on u.id = ma.user_id
                where ma.milestone_id = any(:milestoneIds)
                order by ma.created_at, u.login_id
                """.trimIndent(),
            ).bind("milestoneIds", milestoneIds.toTypedArray())
            .map { row ->
                checkNotNull(row.get("milestone_id", UUID::class.java)) to
                    MilestoneAssignee(
                        id = checkNotNull(row.get("id", UUID::class.java)),
                        loginId = checkNotNull(row.get("login_id", String::class.java)),
                        displayName = row.get("display_name", String::class.java),
                        avatarUrl = ImageStorage.urlOf(row.get("avatar_key", String::class.java)),
                    )
            }.all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })
    }

    /** The milestone goes; its issues stay and belong to no milestone. */
    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        projectId: String,
        milestoneId: UUID,
    ) {
        val project = editable(projectService.access(user, projectId)).project
        val milestone = repository.findByIdAndProjectId(milestoneId, project.id) ?: throw notFound()
        repository.delete(milestone)
        contentLog.record(
            projectTarget(project.id),
            user.id,
            listOf(ContentEvent(ContentEventType.MILESTONE_REMOVED, mapOf("name" to milestone.name))),
            clock.instant(),
        )
    }

    /** For issues: a milestone can only be set from the issue's own project. */
    suspend fun belongsTo(
        projectId: UUID,
        milestoneId: UUID,
    ): Boolean = repository.findByIdAndProjectId(milestoneId, projectId) != null

    private suspend fun views(projectId: UUID): List<MilestoneView> {
        val milestones = milestoneRows(projectId)
        val assignees = assigneesOf(milestones.map { it.id })
        return milestones.map { it.copy(assignees = assignees[it.id].orEmpty()) }
    }

    private suspend fun milestoneRows(projectId: UUID): List<MilestoneView> =
        databaseClient
            .sql(
                """
                select m.id, m.name, m.target_date, m.status, m.created_at, m.content::text as content,
                       count(i.id) filter (where i.status <> 'CANCELED') as issue_count,
                       count(i.id) filter (where i.status = 'DONE') as done_count
                from milestones m
                left join issues i on i.milestone_id = m.id and i.deleted_at is null
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
                    status = MilestoneStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                    assignees = emptyList(),
                    issueCount = checkNotNull(row.get("issue_count", Long::class.javaObjectType)).toInt(),
                    doneCount = checkNotNull(row.get("done_count", Long::class.javaObjectType)).toInt(),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    content = row.get("content", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private fun editable(access: ProjectAccess): ProjectAccess {
        if (!access.role.canEdit) throw ForbiddenException("이 프로젝트의 목표를 수정할 권한이 없습니다")
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
