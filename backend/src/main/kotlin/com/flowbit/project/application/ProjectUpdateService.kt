package com.flowbit.project.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.content.ContentEvent
import com.flowbit.content.ContentEventType
import com.flowbit.content.ContentLog
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.project.domain.ProjectHealth
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ProjectUpdateView(
    val id: UUID,
    val health: ProjectHealth,
    val body: String,
    val author: ProjectPerson,
    val createdAt: Instant,
)

/**
 * Project updates: how the project is going (health) and a short note. The latest update's
 * health is the project's health in the project list. Members read them; editors post them.
 * An update can be taken back by whoever wrote it or by a manager of the project.
 */
@Service
class ProjectUpdateService(
    private val projectService: ProjectService,
    private val databaseClient: DatabaseClient,
    private val contentLog: ContentLog,
    private val clock: Clock,
) {
    /** Newest first, at most LIST_LIMIT. */
    suspend fun list(
        user: AuthenticatedUser,
        projectId: String,
    ): List<ProjectUpdateView> = query(projectService.access(user, projectId).project.id, null)

    @Transactional
    suspend fun post(
        user: AuthenticatedUser,
        projectId: String,
        health: ProjectHealth,
        rawBody: String?,
    ): ProjectUpdateView {
        val access = projectService.access(user, projectId)
        if (!access.role.canEdit) throw ForbiddenException("이 프로젝트에 업데이트를 쓸 권한이 없습니다")
        val body = rawBody?.trim().orEmpty()
        if (body.length > BODY_MAX_LENGTH) throw ValidationException("body", "TOO_LONG", "업데이트는 ${BODY_MAX_LENGTH}자 이하여야 합니다")

        val now = clock.instant()
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into project_updates (id, project_id, author_id, health, body, created_at)
                values (:id, :projectId, :authorId, :health, :body, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("projectId", access.project.id)
            .bind("authorId", user.id)
            .bind("health", health.name)
            .bind("body", body)
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        // The update is also a line of the project's activity, with the start of what it says.
        val detail = mapOf("health" to health.name, "text" to body.take(PREVIEW_LENGTH))
        contentLog.record(
            ContentTarget(ContentTargetType.PROJECT, access.project.id),
            user.id,
            listOf(ContentEvent(ContentEventType.UPDATE_POSTED, detail)),
            now,
        )
        return query(access.project.id, id).single()
    }

    /** Removes one update. The project's health falls back to the update before it. */
    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        projectId: String,
        updateId: UUID,
    ) {
        val access = projectService.access(user, projectId)
        val update =
            query(access.project.id, updateId).singleOrNull()
                ?: throw NotFoundException("PROJECT_UPDATE_NOT_FOUND", "업데이트를 찾을 수 없습니다")
        val own = update.author.id == user.id && access.role.canEdit
        if (!own && !access.role.canManage) throw ForbiddenException("이 업데이트를 지울 권한이 없습니다")
        databaseClient
            .sql("delete from project_updates where id = :id and project_id = :projectId")
            .bind("id", updateId)
            .bind("projectId", access.project.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun query(
        projectId: UUID,
        updateId: UUID?,
    ): List<ProjectUpdateView> {
        var spec =
            databaseClient
                .sql(
                    """
                    select u.id, u.health, u.body, u.created_at,
                           a.id as author_id, a.login_id, a.display_name, a.avatar_key
                    from project_updates u join users a on a.id = u.author_id
                    where u.project_id = :projectId ${if (updateId != null) "and u.id = :updateId" else ""}
                    order by u.created_at desc
                    limit $LIST_LIMIT
                    """.trimIndent(),
                ).bind("projectId", projectId)
        if (updateId != null) spec = spec.bind("updateId", updateId)
        return spec
            .map { row ->
                ProjectUpdateView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    health = ProjectHealth.valueOf(checkNotNull(row.get("health", String::class.java))),
                    body = checkNotNull(row.get("body", String::class.java)),
                    author =
                        ProjectPerson(
                            id = checkNotNull(row.get("author_id", UUID::class.java)),
                            loginId = checkNotNull(row.get("login_id", String::class.java)),
                            displayName = row.get("display_name", String::class.java),
                            avatarKey = row.get("avatar_key", String::class.java),
                        ),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    private companion object {
        const val BODY_MAX_LENGTH = 5000
        const val PREVIEW_LENGTH = 200
        const val LIST_LIMIT = 50
    }
}
