package com.flowbit.project.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
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
 */
@Service
class ProjectUpdateService(
    private val projectService: ProjectService,
    private val databaseClient: DatabaseClient,
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
        return query(access.project.id, id).single()
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
        const val LIST_LIMIT = 50
    }
}
