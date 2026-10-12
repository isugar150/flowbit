package com.flowbit.project.application

import com.flowbit.project.domain.ProjectRole
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ProjectSummaryRow(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    /** Null when the reader is a workspace administrator who is not a member. */
    val memberRole: ProjectRole?,
    val memberCount: Int,
    val createdAt: Instant,
)

data class ProjectMemberRow(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
    val role: ProjectRole,
)

/**
 * SQL for project membership. `project_members` has a two-column key, which Spring Data
 * repositories do not model, so it is read and written here directly.
 */
@Component
class ProjectMemberStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun roleOf(
        projectId: UUID,
        userId: UUID,
    ): ProjectRole? =
        databaseClient
            .sql("select role from project_members where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .map { row -> role(row, "role") }
            .one()
            .awaitSingleOrNull()

    /** Adds the member, or changes their role when they already belong to the project. */
    suspend fun put(
        projectId: UUID,
        userId: UUID,
        role: ProjectRole,
        now: Instant,
    ) {
        databaseClient
            .sql(
                """
                insert into project_members (project_id, user_id, role, created_at)
                values (:projectId, :userId, :role, :now)
                on conflict (project_id, user_id) do update set role = excluded.role
                """.trimIndent(),
            ).bind("projectId", projectId)
            .bind("userId", userId)
            .bind("role", role.name)
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun remove(
        projectId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("delete from project_members where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Managers of the project, locking their rows so a concurrent change cannot remove the last one. */
    suspend fun lockManagerIds(projectId: UUID): List<UUID> =
        databaseClient
            .sql("select user_id from project_members where project_id = :projectId and role = 'MANAGER' for update")
            .bind("projectId", projectId)
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun members(projectId: UUID): List<ProjectMemberRow> =
        databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key, pm.role
                from project_members pm join users u on u.id = pm.user_id
                where pm.project_id = :projectId
                order by case pm.role when 'MANAGER' then 0 when 'EDITOR' then 1 else 2 end,
                         lower(coalesce(u.display_name, u.login_id))
                """.trimIndent(),
            ).bind("projectId", projectId)
            .map { row ->
                ProjectMemberRow(
                    userId = checkNotNull(row.get("id", UUID::class.java)),
                    loginId = checkNotNull(row.get("login_id", String::class.java)),
                    displayName = row.get("display_name", String::class.java),
                    avatarKey = row.get("avatar_key", String::class.java),
                    role = role(row, "role"),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** Projects the user belongs to; every project when `includeAll` (workspace administrators). */
    suspend fun projectsVisibleTo(
        userId: UUID,
        includeAll: Boolean,
    ): List<ProjectSummaryRow> = summaries("(:includeAll or pm.user_id is not null)", userId, includeAll, null)

    suspend fun summary(
        projectId: UUID,
        userId: UUID,
    ): ProjectSummaryRow = summaries("p.id = :projectId", userId, null, projectId).single()

    private suspend fun summaries(
        condition: String,
        userId: UUID,
        includeAll: Boolean?,
        projectId: UUID?,
    ): List<ProjectSummaryRow> {
        var spec =
            databaseClient
                .sql(
                    """
                    select p.id, p.key, p.name, p.description, p.created_at, pm.role as member_role,
                           (select count(*) from project_members m where m.project_id = p.id) as member_count
                    from projects p
                    left join project_members pm on pm.project_id = p.id and pm.user_id = :userId
                    where p.archived_at is null and $condition
                    order by lower(p.name), p.key
                    """.trimIndent(),
                ).bind("userId", userId)
        if (includeAll != null) spec = spec.bind("includeAll", includeAll)
        if (projectId != null) spec = spec.bind("projectId", projectId)
        return spec
            .map { row ->
                ProjectSummaryRow(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    key = checkNotNull(row.get("key", String::class.java)),
                    name = checkNotNull(row.get("name", String::class.java)),
                    description = checkNotNull(row.get("description", String::class.java)),
                    memberRole = row.get("member_role", String::class.java)?.let(ProjectRole::valueOf),
                    memberCount = checkNotNull(row.get("member_count", Long::class.javaObjectType)).toInt(),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    private fun role(
        row: Readable,
        column: String,
    ): ProjectRole = ProjectRole.valueOf(checkNotNull(row.get(column, String::class.java)))
}
