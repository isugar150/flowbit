package com.flowbit.project.application

import com.flowbit.project.domain.ProjectHealth
import com.flowbit.project.domain.ProjectPriority
import com.flowbit.project.domain.ProjectRole
import com.flowbit.project.domain.ProjectStatus
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ProjectSummaryRow(
    val id: UUID,
    val name: String,
    val description: String,
    /** File name of the uploaded icon in storage, see ImageStorage. */
    val iconKey: String?,
    /** Null when the reader is a workspace administrator who is not a member. */
    val memberRole: ProjectRole?,
    val memberCount: Int,
    val createdAt: Instant,
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val lead: ProjectPerson?,
    val startDate: LocalDate?,
    val targetDate: LocalDate?,
    val labels: List<String>,
    /** Issues of the project, canceled ones not counted. */
    val issueCount: Int,
    val doneCount: Int,
    /** The first milestone (in milestone order) that is not finished yet. */
    val currentMilestone: String?,
    /** Health of the latest project update, and when it was posted; null before the first update. */
    val health: ProjectHealth?,
    val healthUpdatedAt: Instant?,
    /** Whether the reader follows the project: is told about every change to its issues. */
    val subscribed: Boolean,
)

data class ProjectPerson(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
)

/** The property columns of a project, written together. */
data class ProjectProperties(
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val leadId: UUID?,
    val startDate: LocalDate?,
    val targetDate: LocalDate?,
    val labels: List<String>,
)

data class ProjectMemberRow(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
    val role: ProjectRole,
)

/**
 * SQL for project membership and project summaries. `project_members` has a two-column key and
 * `projects.labels` is an array, neither of which Spring Data repositories model, so they are
 * read and written here directly.
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

    /** Following twice is the same as once. */
    suspend fun subscribe(
        projectId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("insert into project_subscriptions (project_id, user_id) values (:projectId, :userId) on conflict do nothing")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun unsubscribe(
        projectId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("delete from project_subscriptions where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Whether the user is an active member of the project (the only people who can lead it). */
    suspend fun isActiveMember(
        projectId: UUID,
        userId: UUID,
    ): Boolean =
        databaseClient
            .sql(
                """
                select exists(
                    select 1 from project_members pm join users u on u.id = pm.user_id
                    where pm.project_id = :projectId and pm.user_id = :userId and u.status = 'ACTIVE'
                ) as member
                """.trimIndent(),
            ).bind("projectId", projectId)
            .bind("userId", userId)
            .map { row -> checkNotNull(row.get("member", Boolean::class.javaObjectType)) }
            .one()
            .awaitSingle()

    suspend fun updateProperties(
        projectId: UUID,
        properties: ProjectProperties,
        now: Instant,
    ) {
        var spec =
            databaseClient
                .sql(
                    """
                    update projects
                    set status = :status, priority = :priority, lead_id = :leadId, start_date = :startDate,
                        target_date = :targetDate, labels = :labels, updated_at = :now
                    where id = :id
                    """.trimIndent(),
                ).bind("id", projectId)
                .bind("status", properties.status.name)
                .bind("priority", properties.priority.name)
                .bind("labels", properties.labels.toTypedArray())
                .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        spec = properties.leadId?.let { spec.bind("leadId", it) } ?: spec.bindNull("leadId", UUID::class.java)
        spec = properties.startDate?.let { spec.bind("startDate", it) } ?: spec.bindNull("startDate", LocalDate::class.java)
        spec = properties.targetDate?.let { spec.bind("targetDate", it) } ?: spec.bindNull("targetDate", LocalDate::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
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

    /** Projects the user belongs to; every project when `includeAll`. */
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
                    select p.id, p.name, p.description, p.icon_key, p.created_at, pm.role as member_role,
                           (select count(*) from project_members m where m.project_id = p.id) as member_count,
                           p.status, p.priority, p.start_date, p.target_date, p.labels,
                           l.id as lead_id, l.login_id as lead_login_id,
                           l.display_name as lead_display_name, l.avatar_key as lead_avatar_key,
                           (select count(*) from issues i where i.project_id = p.id and i.deleted_at is null and i.status <> 'CANCELED') as issue_count,
                           (select count(*) from issues i where i.project_id = p.id and i.deleted_at is null and i.status = 'DONE') as done_count,
                           (
                               -- Finished means it has issues and all of them are done (or canceled).
                               select ms.name from milestones ms
                               where ms.project_id = p.id
                                 and (not exists (select 1 from issues i where i.milestone_id = ms.id and i.deleted_at is null and i.status <> 'CANCELED')
                                      or exists (select 1 from issues i where i.milestone_id = ms.id and i.deleted_at is null
                                                 and i.status not in ('DONE', 'CANCELED')))
                               order by ms.target_date nulls last, ms.created_at
                               limit 1
                           ) as current_milestone,
                           latest.health, latest.created_at as health_updated_at,
                           exists (select 1 from project_subscriptions ps where ps.project_id = p.id and ps.user_id = :userId) as subscribed
                    from projects p
                    left join lateral (
                        select u.health, u.created_at from project_updates u
                        where u.project_id = p.id
                        order by u.created_at desc
                        limit 1
                    ) latest on true
                    left join project_members pm on pm.project_id = p.id and pm.user_id = :userId
                    left join users l on l.id = p.lead_id
                    where p.archived_at is null and $condition
                    order by lower(p.name), p.id
                    """.trimIndent(),
                ).bind("userId", userId)
        if (includeAll != null) spec = spec.bind("includeAll", includeAll)
        if (projectId != null) spec = spec.bind("projectId", projectId)
        return spec
            .map { row ->
                ProjectSummaryRow(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    name = checkNotNull(row.get("name", String::class.java)),
                    description = checkNotNull(row.get("description", String::class.java)),
                    iconKey = row.get("icon_key", String::class.java),
                    memberRole = row.get("member_role", String::class.java)?.let(ProjectRole::valueOf),
                    memberCount = checkNotNull(row.get("member_count", Long::class.javaObjectType)).toInt(),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    status = ProjectStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                    priority = ProjectPriority.valueOf(checkNotNull(row.get("priority", String::class.java))),
                    lead =
                        row.get("lead_id", UUID::class.java)?.let { id ->
                            ProjectPerson(
                                id = id,
                                loginId = checkNotNull(row.get("lead_login_id", String::class.java)),
                                displayName = row.get("lead_display_name", String::class.java),
                                avatarKey = row.get("lead_avatar_key", String::class.java),
                            )
                        },
                    startDate = row.get("start_date", LocalDate::class.java),
                    targetDate = row.get("target_date", LocalDate::class.java),
                    labels = row.get("labels", Array<String>::class.java)?.toList().orEmpty(),
                    issueCount = checkNotNull(row.get("issue_count", Long::class.javaObjectType)).toInt(),
                    doneCount = checkNotNull(row.get("done_count", Long::class.javaObjectType)).toInt(),
                    currentMilestone = row.get("current_milestone", String::class.java),
                    health = row.get("health", String::class.java)?.let(ProjectHealth::valueOf),
                    healthUpdatedAt = row.get("health_updated_at", OffsetDateTime::class.java)?.toInstant(),
                    subscribed = checkNotNull(row.get("subscribed", Boolean::class.javaObjectType)),
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
