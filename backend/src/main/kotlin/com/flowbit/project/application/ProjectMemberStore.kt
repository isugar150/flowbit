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
    /** Health of the latest project update, and when it was posted; null before the first update. */
    val health: ProjectHealth?,
    val healthUpdatedAt: Instant?,
    /** Whether the reader follows the project: is told about every change to its issues. */
    val subscribed: Boolean,
    /** When the project was archived; null for a project in use. */
    val archivedAt: Instant? = null,
    /** Whether every member of the workspace enters the project as an editor (D-192). */
    val openToAll: Boolean = false,
    val estimatesEnabled: Boolean = false,
    val triageEnabled: Boolean = false,
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

/** Someone waiting to be let into a project (D-128). */
data class ProjectAccessRequestRow(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
    val requestedAt: Instant,
)

data class ProjectMemberRow(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
    val role: ProjectRole,
    /** False for someone who is in the list only because the project is open to everyone (D-192): no `project_members` row. */
    val direct: Boolean = true,
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

    /** Records a request to be let in. False when one was already waiting. */
    suspend fun requestAccess(
        projectId: UUID,
        userId: UUID,
        now: Instant,
    ): Boolean =
        databaseClient
            .sql(
                """
                insert into project_access_requests (project_id, user_id, created_at)
                values (:projectId, :userId, :now)
                on conflict do nothing
                """.trimIndent(),
            ).bind("projectId", projectId)
            .bind("userId", userId)
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /** When the user asked to be let in, if they are waiting. */
    suspend fun accessRequestedAt(
        projectId: UUID,
        userId: UUID,
    ): Instant? =
        databaseClient
            .sql("select created_at from project_access_requests where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .map { row -> checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant() }
            .one()
            .awaitSingleOrNull()

    /** The active people waiting to be let in, the longest waiting first. */
    suspend fun accessRequests(projectId: UUID): List<ProjectAccessRequestRow> =
        databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key, r.created_at
                from project_access_requests r join users u on u.id = r.user_id
                where r.project_id = :projectId and u.status = 'ACTIVE'
                order by r.created_at, u.login_id
                """.trimIndent(),
            ).bind("projectId", projectId)
            .map { row ->
                ProjectAccessRequestRow(
                    userId = checkNotNull(row.get("id", UUID::class.java)),
                    loginId = checkNotNull(row.get("login_id", String::class.java)),
                    displayName = row.get("display_name", String::class.java),
                    avatarKey = row.get("avatar_key", String::class.java),
                    requestedAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** Takes the request away (answered either way). False when there was none. */
    suspend fun removeAccessRequest(
        projectId: UUID,
        userId: UUID,
    ): Boolean =
        databaseClient
            .sql("delete from project_access_requests where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /** Drops every waiting request: opening the project to everyone answers them all (D-192). */
    suspend fun removeAccessRequests(projectId: UUID) {
        databaseClient
            .sql("delete from project_access_requests where project_id = :projectId")
            .bind("projectId", projectId)
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

    /**
     * Whether the user is active and belongs to the project: added to it, or it is open to everyone
     * (D-192). These are the people who can lead it or be given its issues.
     */
    suspend fun isActiveMember(
        projectId: UUID,
        userId: UUID,
    ): Boolean =
        databaseClient
            .sql(
                """
                select exists(
                    select 1 from users u
                    where u.id = :userId and u.status = 'ACTIVE' and ${ProjectAccessSql.mayEnter(":projectId", "u.id")}
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

    /**
     * The managers of the project who can sign in (the ones the people see), locking their rows so a concurrent change
     * cannot remove the last one. A manager whose account is disabled does not count: the project still needs someone
     * who can manage it.
     */
    suspend fun lockManagerIds(projectId: UUID): List<UUID> =
        databaseClient
            .sql(
                """
                select pm.user_id from project_members pm join users u on u.id = pm.user_id
                where pm.project_id = :projectId and pm.role = 'MANAGER' and u.status = 'ACTIVE'
                for update of pm
                """.trimIndent(),
            ).bind("projectId", projectId)
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * The people of a project. Someone whose account is disabled is not listed (they cannot sign in, so they cannot be
     * tagged or given an issue, and they would only be in the way); they stay members and come back when enabled again.
     * While the project is open to everyone (D-192) every active person is listed: the ones who were not added
     * have the editor role and are not `direct`.
     */
    suspend fun members(projectId: UUID): List<ProjectMemberRow> =
        databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key, coalesce(pm.role, 'EDITOR') as role,
                       pm.user_id is not null as is_direct
                from projects p
                join users u on u.status = 'ACTIVE'
                left join project_members pm on pm.project_id = p.id and pm.user_id = u.id
                where p.id = :projectId and (pm.user_id is not null or p.open_to_all)
                order by case coalesce(pm.role, 'EDITOR') when 'MANAGER' then 0 when 'EDITOR' then 1 else 2 end,
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
                    direct = checkNotNull(row.get("is_direct", Boolean::class.javaObjectType)),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** Projects the user belongs to; every project when `includeAll`. */
    suspend fun projectsVisibleTo(
        userId: UUID,
        includeAll: Boolean,
    ): List<ProjectSummaryRow> = summaries("(:includeAll or pm.user_id is not null)", userId, includeAll, null)

    /** Every archived project, for the list that shows what was put away. */
    suspend fun archivedProjects(userId: UUID): List<ProjectSummaryRow> = summaries("true", userId, null, null, archived = true)

    suspend fun summary(
        projectId: UUID,
        userId: UUID,
    ): ProjectSummaryRow = summaries("p.id = :projectId", userId, null, projectId).single()

    private suspend fun summaries(
        condition: String,
        userId: UUID,
        includeAll: Boolean?,
        projectId: UUID?,
        archived: Boolean = false,
    ): List<ProjectSummaryRow> {
        var spec =
            databaseClient
                .sql(
                    """
                    select p.id, p.name, p.description, p.icon_key, p.created_at, p.archived_at, p.open_to_all, p.estimates_enabled, p.triage_enabled, pm.role as member_role,
                           case when p.open_to_all then (select count(*) from users au where au.status = 'ACTIVE')
                                else (select count(*) from project_members m join users mu on mu.id = m.user_id where m.project_id = p.id and mu.status = 'ACTIVE')
                           end as member_count,
                           p.status, p.priority, p.start_date, p.target_date, p.labels,
                           l.id as lead_id, l.login_id as lead_login_id,
                           l.display_name as lead_display_name, l.avatar_key as lead_avatar_key,
                           (select count(*) from issues i where i.project_id = p.id and i.deleted_at is null and i.status <> 'CANCELED') as issue_count,
                           (select count(*) from issues i where i.project_id = p.id and i.deleted_at is null and i.status = 'DONE') as done_count,
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
                    where p.archived_at is ${if (archived) "not null" else "null"} and $condition
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
                    health = row.get("health", String::class.java)?.let(ProjectHealth::valueOf),
                    healthUpdatedAt = row.get("health_updated_at", OffsetDateTime::class.java)?.toInstant(),
                    subscribed = checkNotNull(row.get("subscribed", Boolean::class.javaObjectType)),
                    archivedAt = row.get("archived_at", OffsetDateTime::class.java)?.toInstant(),
                    openToAll = checkNotNull(row.get("open_to_all", Boolean::class.javaObjectType)),
                    estimatesEnabled = checkNotNull(row.get("estimates_enabled", Boolean::class.javaObjectType)),
                    triageEnabled = checkNotNull(row.get("triage_enabled", Boolean::class.javaObjectType)),
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
