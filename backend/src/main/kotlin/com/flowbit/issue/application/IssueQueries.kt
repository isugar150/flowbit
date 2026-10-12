package com.flowbit.issue.application

import com.flowbit.issue.domain.IssuePreset
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class IssuePerson(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarKey: String?,
)

/** An issue with the people on it resolved, ready to show. */
data class IssueView(
    val id: UUID,
    val projectKey: String,
    val number: Int,
    val title: String,
    val description: String,
    val preset: IssuePreset?,
    val status: IssueStatus,
    val priority: IssuePriority,
    val assignee: IssuePerson?,
    val dueDate: LocalDate?,
    val createdBy: IssuePerson,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** The body as raw editor JSON. Loaded for a single issue only, never in lists. */
    val content: String? = null,
) {
    val key: String
        get() = "$projectKey-$number"
}

/** Read side of issues: one query that joins the assignee and the author. */
@Component
class IssueQueries(
    private val databaseClient: DatabaseClient,
) {
    suspend fun list(
        projectId: UUID,
        projectKey: String,
    ): List<IssueView> = query(projectId, projectKey, null)

    suspend fun find(
        projectId: UUID,
        projectKey: String,
        number: Int,
    ): IssueView? = query(projectId, projectKey, number).firstOrNull()

    /** Hands out the next issue number of a project. One atomic statement, so two creators never get the same number. */
    suspend fun nextNumber(projectId: UUID): Int =
        databaseClient
            .sql("update projects set next_issue_number = next_issue_number + 1 where id = :id returning next_issue_number - 1 as number")
            .bind("id", projectId)
            .map { row -> checkNotNull(row.get("number", Int::class.javaObjectType)) }
            .one()
            .awaitSingle()

    suspend fun isProjectMember(
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

    private suspend fun query(
        projectId: UUID,
        projectKey: String,
        number: Int?,
    ): List<IssueView> {
        var spec =
            databaseClient
                .sql(
                    """
                    select i.id, i.number, i.title, i.description, i.preset, i.status, i.priority, i.due_date,
                           i.version, i.created_at, i.updated_at,
                           a.id as assignee_id, a.login_id as assignee_login_id,
                           a.display_name as assignee_display_name, a.avatar_key as assignee_avatar_key,
                           c.id as creator_id, c.login_id as creator_login_id,
                           c.display_name as creator_display_name, c.avatar_key as creator_avatar_key
                    from issues i
                    join users c on c.id = i.created_by
                    left join users a on a.id = i.assignee_id
                    where i.project_id = :projectId ${if (number != null) "and i.number = :number" else ""}
                    order by i.number desc
                    """.trimIndent(),
                ).bind("projectId", projectId)
        if (number != null) spec = spec.bind("number", number)
        return spec
            .map { row ->
                fun person(prefix: String): IssuePerson? =
                    row.get("${prefix}_id", UUID::class.java)?.let { id ->
                        IssuePerson(
                            id = id,
                            loginId = checkNotNull(row.get("${prefix}_login_id", String::class.java)),
                            displayName = row.get("${prefix}_display_name", String::class.java),
                            avatarKey = row.get("${prefix}_avatar_key", String::class.java),
                        )
                    }
                IssueView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    projectKey = projectKey,
                    number = checkNotNull(row.get("number", Int::class.javaObjectType)),
                    title = checkNotNull(row.get("title", String::class.java)),
                    description = checkNotNull(row.get("description", String::class.java)),
                    preset = row.get("preset", String::class.java)?.let(IssuePreset::valueOf),
                    status = IssueStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                    priority = IssuePriority.valueOf(checkNotNull(row.get("priority", String::class.java))),
                    assignee = person("assignee"),
                    dueDate = row.get("due_date", LocalDate::class.java),
                    createdBy = checkNotNull(person("creator")),
                    version = checkNotNull(row.get("version", Int::class.javaObjectType)),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }
}
