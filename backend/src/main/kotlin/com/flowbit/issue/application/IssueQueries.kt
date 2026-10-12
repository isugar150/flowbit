package com.flowbit.issue.application

import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
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

data class IssueMilestone(
    val id: UUID,
    val name: String,
    /** When the milestone is meant to be reached, if a date was set. */
    val targetDate: LocalDate? = null,
)

data class IssueLabel(
    val id: UUID,
    val name: String,
    val color: String,
)

/** The project an issue is linked to. */
data class IssueProject(
    val key: String,
    val name: String,
)

/** The issue a sub-issue hangs under. */
data class IssueParent(
    val number: Int,
    val key: String,
    val title: String,
)

/** An issue with the people on it resolved, ready to show. */
data class IssueView(
    val id: UUID,
    /** For example FB-12: the workspace's issue key and the issue's number. */
    val key: String,
    val number: Int,
    /** Null when the issue is linked to no project. */
    val project: IssueProject?,
    val parent: IssueParent?,
    val title: String,
    val description: String,
    val status: IssueStatus,
    val priority: IssuePriority,
    val assignee: IssuePerson?,
    /** Set when the issue covers a span of days ending on [dueDate]. */
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val milestone: IssueMilestone?,
    val labels: List<IssueLabel>,
    /** How many sub-issues the issue has, and how many of them are done. */
    val subIssueCount: Int,
    val subIssueDoneCount: Int,
    val createdBy: IssuePerson,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When the issue was deleted; null for an issue in use. */
    val deletedAt: Instant? = null,
    /** The body as raw editor JSON. Loaded for a single issue only, never in lists. */
    val content: String? = null,
)

/** Which of a person's issues to list on "내 이슈". The column name never comes from input. */
enum class MyIssueScope(
    val column: String,
) {
    ASSIGNED("assignee_id"),
    CREATED("created_by"),
}

/** Whose eyes a list is for: administrators see every project, others the ones they are a member of. */
data class Viewer(
    val userId: UUID,
    val seesAllProjects: Boolean,
)

/** More than anyone reads in one list; keeps a page fast in a workspace with years of issues. */
private const val LIST_LIMIT = 1000

/**
 * An issue linked to no project is open to everyone; a linked one follows its project, which must
 * be in use and one the viewer may enter (docs/features/M3-projects-and-issues.md, "독립 이슈").
 */
private const val VISIBLE =
    """(i.project_id is null or (p.archived_at is null and (:seesAll or exists(
           select 1 from project_members pm where pm.project_id = i.project_id and pm.user_id = :viewerId))))"""

/** Read side of issues: one query that joins the project, the assignee and the author. */
@Component
class IssueQueries(
    private val databaseClient: DatabaseClient,
    private val jsonMapper: JsonMapper,
) {
    /** Issues the viewer may see, newest first; of one project when `projectId` is given. */
    suspend fun list(
        viewer: Viewer,
        projectId: UUID?,
        deleted: Boolean,
    ): List<IssueView> =
        select(
            "where $VISIBLE and ${deletedIs(deleted)} ${if (projectId != null) "and i.project_id = :projectId" else ""} " +
                "order by i.number desc limit $LIST_LIMIT",
        ) {
            val spec = it.visibleTo(viewer)
            if (projectId != null) spec.bind("projectId", projectId) else spec
        }

    /** The viewer's own issues ("내 이슈"), most recently changed first. */
    suspend fun listForUser(
        viewer: Viewer,
        scope: MyIssueScope,
        deleted: Boolean,
    ): List<IssueView> =
        select("where $VISIBLE and ${deletedIs(deleted)} and i.${scope.column} = :viewerId order by i.updated_at desc limit $LIST_LIMIT") {
            it.visibleTo(viewer)
        }

    /** Lists show either the issues in use or the deleted ones, never a mix. */
    private fun deletedIs(deleted: Boolean) = if (deleted) "i.deleted_at is not null" else "i.deleted_at is null"

    /** The sub-issues of an issue that the viewer may see, oldest first. */
    suspend fun subIssues(
        viewer: Viewer,
        parentId: UUID,
    ): List<IssueView> =
        select("where $VISIBLE and i.deleted_at is null and i.parent_id = :parentId order by i.number limit $LIST_LIMIT") {
            it.visibleTo(viewer).bind("parentId", parentId)
        }

    /** One issue by number, whoever asks: the caller decides whether they may see it. */
    suspend fun find(number: Int): IssueView? = select("where i.number = :number") { it.bind("number", number) }.firstOrNull()

    /** Hands out the next issue number. One atomic statement, so two creators never get the same number. */
    suspend fun nextNumber(): Int =
        databaseClient
            .sql("update instance_settings set next_issue_number = next_issue_number + 1 where id = 1 returning next_issue_number - 1 as number")
            .map { row -> checkNotNull(row.get("number", Int::class.javaObjectType)) }
            .one()
            .awaitSingle()

    suspend fun isProjectMember(
        projectId: UUID,
        userId: UUID,
    ): Boolean =
        exists(
            """
            select exists(
                select 1 from project_members pm join users u on u.id = pm.user_id
                where pm.project_id = :projectId and pm.user_id = :userId and u.status = 'ACTIVE'
            ) as found
            """.trimIndent(),
        ) { it.bind("projectId", projectId).bind("userId", userId) }

    suspend fun isActiveUser(userId: UUID): Boolean =
        exists("select exists(select 1 from users where id = :userId and status = 'ACTIVE') as found") { it.bind("userId", userId) }

    /** How many of these label ids exist. */
    suspend fun countLabels(ids: Collection<UUID>): Int =
        databaseClient
            .sql("select count(*) as n from labels where id = any(:ids)")
            .bind("ids", ids.toTypedArray())
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)).toInt() }
            .one()
            .awaitSingle()

    /** Makes the issue carry exactly these labels. */
    suspend fun replaceLabels(
        issueId: UUID,
        labelIds: Collection<UUID>,
    ) {
        databaseClient
            .sql("delete from issue_labels where issue_id = :issueId")
            .bind("issueId", issueId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        for (labelId in labelIds) {
            databaseClient
                .sql("insert into issue_labels (issue_id, label_id) values (:issueId, :labelId)")
                .bind("issueId", issueId)
                .bind("labelId", labelId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun DatabaseClient.GenericExecuteSpec.visibleTo(viewer: Viewer) = bind("viewerId", viewer.userId).bind("seesAll", viewer.seesAllProjects)

    private suspend fun exists(
        sql: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): Boolean =
        bind(databaseClient.sql(sql))
            .map { row -> checkNotNull(row.get("found", Boolean::class.javaObjectType)) }
            .one()
            .awaitSingle()

    private suspend fun select(
        tail: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<IssueView> =
        bind(
            databaseClient.sql(
                """
                select i.id, i.number, i.title, i.description, i.status, i.priority, i.start_date, i.due_date,
                       i.version, i.created_at, i.updated_at, i.deleted_at,
                       (select workspace_key from instance_settings where id = 1) as issue_prefix,
                       p.key as project_key, p.name as project_name,
                       parent.number as parent_number, parent.title as parent_title,
                       (select count(*) from issues s where s.parent_id = i.id and s.deleted_at is null) as sub_total,
                       (select count(*) from issues s where s.parent_id = i.id and s.deleted_at is null and s.status = 'DONE') as sub_done,
                       a.id as assignee_id, a.login_id as assignee_login_id,
                       a.display_name as assignee_display_name, a.avatar_key as assignee_avatar_key,
                       c.id as creator_id, c.login_id as creator_login_id,
                       c.display_name as creator_display_name, c.avatar_key as creator_avatar_key,
                       ms.id as milestone_id, ms.name as milestone_name, ms.target_date as milestone_target_date,
                       (select coalesce(json_agg(json_build_object('id', l.id, 'name', l.name, 'color', l.color)
                                                 order by l.created_at, l.name), '[]')::text
                        from issue_labels il join labels l on l.id = il.label_id
                        where il.issue_id = i.id) as labels
                from issues i
                left join projects p on p.id = i.project_id
                left join issues parent on parent.id = i.parent_id
                join users c on c.id = i.created_by
                left join users a on a.id = i.assignee_id
                left join milestones ms on ms.id = i.milestone_id
                $tail
                """.trimIndent(),
            ),
        ).map { row ->
            fun person(prefix: String): IssuePerson? =
                row.get("${prefix}_id", UUID::class.java)?.let { id ->
                    IssuePerson(
                        id = id,
                        loginId = checkNotNull(row.get("${prefix}_login_id", String::class.java)),
                        displayName = row.get("${prefix}_display_name", String::class.java),
                        avatarKey = row.get("${prefix}_avatar_key", String::class.java),
                    )
                }
            val prefix = checkNotNull(row.get("issue_prefix", String::class.java))
            val number = checkNotNull(row.get("number", Int::class.javaObjectType))
            IssueView(
                id = checkNotNull(row.get("id", UUID::class.java)),
                key = "$prefix-$number",
                number = number,
                project =
                    row.get("project_key", String::class.java)?.let { key ->
                        IssueProject(key, checkNotNull(row.get("project_name", String::class.java)))
                    },
                parent =
                    row.get("parent_number", Int::class.javaObjectType)?.let { parentNumber ->
                        IssueParent(parentNumber, "$prefix-$parentNumber", checkNotNull(row.get("parent_title", String::class.java)))
                    },
                title = checkNotNull(row.get("title", String::class.java)),
                description = checkNotNull(row.get("description", String::class.java)),
                status = IssueStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                priority = IssuePriority.valueOf(checkNotNull(row.get("priority", String::class.java))),
                assignee = person("assignee"),
                startDate = row.get("start_date", LocalDate::class.java),
                dueDate = row.get("due_date", LocalDate::class.java),
                milestone =
                    row.get("milestone_id", UUID::class.java)?.let { id ->
                        IssueMilestone(
                            id,
                            checkNotNull(row.get("milestone_name", String::class.java)),
                            row.get("milestone_target_date", LocalDate::class.java),
                        )
                    },
                labels = jsonMapper.readValue(checkNotNull(row.get("labels", String::class.java)), Array<IssueLabel>::class.java).toList(),
                subIssueCount = checkNotNull(row.get("sub_total", Long::class.javaObjectType)).toInt(),
                subIssueDoneCount = checkNotNull(row.get("sub_done", Long::class.javaObjectType)).toInt(),
                createdBy = checkNotNull(person("creator")),
                version = checkNotNull(row.get("version", Int::class.javaObjectType)),
                createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                deletedAt = row.get("deleted_at", OffsetDateTime::class.java)?.toInstant(),
            )
        }.all()
            .collectList()
            .awaitSingle()
}
