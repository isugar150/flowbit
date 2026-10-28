package com.flowbit.issue.application

import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.project.application.ProjectAccessSql
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

data class IssueLabel(
    val id: UUID,
    val name: String,
    val color: String,
)

/** The project an issue is linked to. */
data class IssueProject(
    val id: UUID,
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
    /** Every issue above this one, the top-level one first and [parent] last; empty for a top-level issue. */
    val ancestors: List<IssueParent> = emptyList(),
    val title: String,
    val description: String,
    val status: IssueStatus,
    /** The status the issue is in and its name; [status] is the category it belongs to. */
    val statusId: UUID,
    val statusName: String,
    val priority: IssuePriority,
    /** In the order they were given the issue; empty when no one has it. */
    val assignees: List<IssuePerson>,
    /** Set when the issue covers a span of days ending on [dueDate]. */
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
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
    /**
     * When a done or canceled issue was finished: its last status change, or when it was made if
     * it was made finished. Null while it is open. Lists hide the ones finished long ago.
     */
    val completedAt: Instant? = null,
    /** The body as raw editor JSON. Loaded for a single issue only, never in lists. */
    val content: String? = null,
    /** Comments on the issue and on the blocks of its body, the deleted ones left out (D-219). */
    val commentCount: Int = 0,
    /** Entries of its history besides "created" (D-219). */
    val activityCount: Int = 0,
    /** Story points, or null. */
    val estimate: Int? = null,
    /** How many issues that block it are still open (D-230); above zero, the issue is waiting on something. */
    val openBlockers: Int = 0,
    /** Waiting in the triage queue (D-231). */
    val triage: Boolean = false,
    /** The cycle it is planned in, or null. */
    val cycle: IssueCycle? = null,
)

/** The cycle an issue is planned in (D-232). */
data class IssueCycle(
    val id: UUID,
    val number: Int,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

/** An issue above another, as the query reads it; the key is added from the workspace's issue key. */
private data class AncestorRow(
    val number: Int = 0,
    val title: String = "",
)

/** Which of a person's issues to list on "내 티켓". The condition never comes from input. */
enum class MyIssueScope(
    val condition: String,
) {
    ASSIGNED(
        """
        (
          exists(select 1 from issue_assignees ia where ia.issue_id = i.id and ia.user_id = :viewerId)
          or exists(
            select 1 from project_issue_fields f
            left join issue_field_values fv on fv.field_id = f.id and fv.issue_id = i.id
            where f.project_id = i.project_id and f.type = 'PERSON' and (
              (case when fv.issue_id is null then f.default_value else fv.value end) = to_jsonb(cast(:viewerId as text))
              or (case when fv.issue_id is null then f.default_value else fv.value end) @> jsonb_build_array(cast(:viewerId as text))
            )
          )
        )
        """.trimIndent(),
    ),
    CREATED("i.created_by = :viewerId"),
}

/** Whose eyes a list is for: administrators see every project, others the ones they are a member of. */
data class Viewer(
    val userId: UUID,
    val seesAllProjects: Boolean,
)

/** How many issues a list holds unless it asks for more; keeps a page fast in a workspace with years of issues. */
const val ISSUE_LIST_LIMIT = 1000

/** The most a list can ask for: the screens load a thousand more at a time (D-227). */
const val ISSUE_LIST_MAX = 10_000

private const val LIST_LIMIT = ISSUE_LIST_LIMIT

/**
 * An issue linked to no project is open to everyone; a linked one follows its project, which must
 * be in use and one the viewer may enter (docs/features/M3-projects-and-issues.md, "독립 티켓").
 */
private val VISIBLE =
    "(i.project_id is null or (p.archived_at is null and (:seesAll or ${ProjectAccessSql.mayEnter("i.project_id", ":viewerId")})))"

/** Read side of issues: one query that joins the project, the assignees and the author. */
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
        limit: Int = LIST_LIMIT,
    ): List<IssueView> =
        select(
            "where $VISIBLE and ${deletedIs(deleted)} ${if (projectId != null) "and i.project_id = :projectId" else ""} " +
                "order by i.number desc limit ${limit.coerceIn(1, ISSUE_LIST_MAX)}",
        ) {
            val spec = it.visibleTo(viewer)
            if (projectId != null) spec.bind("projectId", projectId) else spec
        }

    /** Active members who can classify requests in this project. */
    suspend fun triageManagers(projectId: UUID): List<UUID> =
        databaseClient
            .sql(
                "select pm.user_id from project_members pm join users u on u.id = pm.user_id where pm.project_id = :projectId and pm.role = 'MANAGER' and u.status = 'ACTIVE'",
            ).bind("projectId", projectId)
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    /** The issues waiting in the triage queue that the viewer may see, the oldest first (D-231). */
    suspend fun triageQueue(
        viewer: Viewer,
        rejected: Boolean = false,
    ): List<IssueView> =
        select(
            "where $VISIBLE and i.deleted_at is null and ${if (rejected) "i.triage_rejected" else "i.triage"} and i.project_id is not null order by i.created_at limit $LIST_LIMIT",
        ) {
            it.visibleTo(viewer)
        }

    /** The viewer's own issues ("내 티켓"), most recently changed first. */
    suspend fun listForUser(
        viewer: Viewer,
        scope: MyIssueScope,
        deleted: Boolean,
        limit: Int = LIST_LIMIT,
    ): List<IssueView> =
        select("where $VISIBLE and ${deletedIs(deleted)} and ${scope.condition} order by i.updated_at desc limit ${limit.coerceIn(1, ISSUE_LIST_MAX)}") {
            it.visibleTo(viewer)
        }

    /** One issue in use by number, if the viewer may see it. */
    suspend fun findVisible(
        viewer: Viewer,
        number: Int,
    ): IssueView? = select("where $VISIBLE and i.deleted_at is null and i.number = :number") { it.visibleTo(viewer).bind("number", number) }.firstOrNull()

    /** One issue in use by id, if the viewer may see it. */
    suspend fun findVisibleById(
        viewer: Viewer,
        id: UUID,
    ): IssueView? = select("where $VISIBLE and i.deleted_at is null and i.id = :id") { it.visibleTo(viewer).bind("id", id) }.firstOrNull()

    /**
     * Whether the text of an issue in use under [rootNumber] (itself and its sub-issues, however deep) or of one of their documents
     * in use holds [needle]. For deciding if a file may be served at a share link: only what the shared text points at.
     */
    suspend fun treeTextHolds(
        rootNumber: Int,
        needle: String,
    ): Boolean {
        // LIKE's own characters in the needle are plain characters here.
        val pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return exists(
            """
            with recursive tree (id) as (
                select i.id from issues i where i.number = :root and i.deleted_at is null
                union all
                select c.id from issues c join tree t on c.parent_id = t.id where c.deleted_at is null
            )
            select (
                exists(select 1 from issues i where i.id in (select id from tree) and i.content::text like :pattern)
                or exists(select 1 from documents d where d.issue_id in (select id from tree) and d.deleted_at is null and d.content::text like :pattern)
            ) as found
            """.trimIndent(),
        ) { it.bind("root", rootNumber).bind("pattern", pattern) }
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

    /** One issue by number, whoever asks: the caller decides whether they may see it. The only read that carries the issue's plain text (`description`). */
    suspend fun find(number: Int): IssueView? = select("where i.number = :number", withDescription = true) { it.bind("number", number) }.firstOrNull()

    /** Hands out the next issue number. One atomic statement, so two creators never get the same number. */
    suspend fun nextNumber(): Int =
        databaseClient
            .sql("update instance_settings set next_issue_number = next_issue_number + 1 where id = 1 returning next_issue_number - 1 as number")
            .map { row -> checkNotNull(row.get("number", Int::class.javaObjectType)) }
            .one()
            .awaitSingle()

    /** Whether the person is active and may enter the project: a member of it, or it is open to everyone (D-192). */
    suspend fun isProjectMember(
        projectId: UUID,
        userId: UUID,
    ): Boolean =
        exists(
            """
            select exists(
                select 1 from users u
                where u.id = :userId and u.status = 'ACTIVE' and ${ProjectAccessSql.mayEnter(":projectId", "u.id")}
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

    /**
     * Makes these people, in this order, the issue's assignees. People who already had it keep
     * their place; the ones added are put after them.
     */
    suspend fun replaceAssignees(
        issueId: UUID,
        userIds: List<UUID>,
        now: Instant,
    ) {
        databaseClient
            .sql("delete from issue_assignees where issue_id = :issueId and not (user_id = any(:userIds))")
            .bind("issueId", issueId)
            .bind("userIds", userIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        // A microsecond apart, so those added in one save keep the order they were sent in.
        userIds.forEachIndexed { index, userId ->
            databaseClient
                .sql("insert into issue_assignees (issue_id, user_id, created_at) values (:issueId, :userId, :at) on conflict do nothing")
                .bind("issueId", issueId)
                .bind("userId", userId)
                .bind("at", now.plusNanos(index * 1000L))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

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

    /**
     * The issues the `tail` picks. `description` (the text of the body, up to 50 000 characters) is read only with [withDescription]: a
     * list of a thousand issues does not use it, and carried it from the database to the browser, twice for every refresh (D-206).
     * Without it the issue's `description` is empty.
     */
    private suspend fun select(
        tail: String,
        withDescription: Boolean = false,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<IssueView> =
        bind(
            databaseClient.sql(
                """
                select i.id, i.number, i.title, ${if (withDescription) "i.description" else "'' as description"}, i.status, st.id as status_id, st.name as status_name, i.estimate, i.triage, cy.id as cycle_id, cy.number as cycle_number, cy.starts_on as cycle_starts_on, cy.ends_on as cycle_ends_on, i.priority, i.start_date, i.due_date,
                       i.version, i.created_at, i.updated_at, i.deleted_at,
                       case when i.status in ('DONE', 'CANCELED') then coalesce(
                           (select max(sa.created_at) from issue_activities sa where sa.issue_id = i.id and sa.type = 'STATUS_CHANGED'),
                           i.created_at) end as completed_at,
                       (select workspace_key from instance_settings where id = 1) as issue_prefix,
                       p.id as project_id, p.name as project_name,
                       parent.number as parent_number, parent.title as parent_title,
                       (with recursive up (id, parent_id, number, title, lvl) as (
                            select a.id, a.parent_id, a.number, a.title, 1 from issues a where a.id = i.parent_id
                            union all
                            select a.id, a.parent_id, a.number, a.title, up.lvl + 1
                            from issues a join up on a.id = up.parent_id
                            where up.lvl < 20)
                        select coalesce(json_agg(json_build_object('number', number, 'title', title) order by lvl desc), '[]')::text
                        from up) as ancestors,
                       (select count(*) from issue_comments cm where cm.issue_id = i.id and cm.deleted_at is null)
                         + (select count(*) from content_thread_comments tc join content_threads th on th.id = tc.thread_id
                            where th.target_type = 'ISSUE' and th.target_id = i.id and tc.deleted_at is null) as comment_count,
                       (select count(*) from issue_activities ac where ac.issue_id = i.id and ac.type <> 'CREATED') as activity_count,
                       (select count(*) from issues s where s.parent_id = i.id and s.deleted_at is null) as sub_total,
                       (select count(*) from issues s where s.parent_id = i.id and s.deleted_at is null and s.status = 'DONE') as sub_done,
                       (select count(*) from issue_relations br join issues bi on bi.id = br.issue_id
                        where br.related_id = i.id and br.type = 'BLOCKS' and bi.deleted_at is null
                          and bi.status not in ('DONE', 'CANCELED')) as open_blockers,
                       (select coalesce(json_agg(json_build_object('id', a.id, 'loginId', a.login_id,
                                                                   'displayName', a.display_name, 'avatarKey', a.avatar_key)
                                                 order by ia.created_at, a.login_id), '[]')::text
                        from issue_assignees ia join users a on a.id = ia.user_id
                        where ia.issue_id = i.id) as assignees,
                       c.id as creator_id, c.login_id as creator_login_id,
                       c.display_name as creator_display_name, c.avatar_key as creator_avatar_key,
                       (select coalesce(json_agg(json_build_object('id', l.id, 'name', l.name, 'color', l.color)
                                                 order by l.created_at, l.name), '[]')::text
                        from issue_labels il join labels l on l.id = il.label_id
                        where il.issue_id = i.id) as labels
                from issues i
                left join projects p on p.id = i.project_id
                left join issues parent on parent.id = i.parent_id
                join issue_statuses st on st.id = i.status_id
                left join cycles cy on cy.id = i.cycle_id
                join users c on c.id = i.created_by
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
                    row.get("project_id", UUID::class.java)?.let { projectId ->
                        IssueProject(projectId, checkNotNull(row.get("project_name", String::class.java)))
                    },
                parent =
                    row.get("parent_number", Int::class.javaObjectType)?.let { parentNumber ->
                        IssueParent(parentNumber, "$prefix-$parentNumber", checkNotNull(row.get("parent_title", String::class.java)))
                    },
                ancestors =
                    jsonMapper
                        .readValue(checkNotNull(row.get("ancestors", String::class.java)), Array<AncestorRow>::class.java)
                        .map { IssueParent(it.number, "$prefix-${it.number}", it.title) },
                title = checkNotNull(row.get("title", String::class.java)),
                description = checkNotNull(row.get("description", String::class.java)),
                status = IssueStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                statusId = checkNotNull(row.get("status_id", UUID::class.java)),
                statusName = checkNotNull(row.get("status_name", String::class.java)),
                estimate = row.get("estimate", Int::class.javaObjectType),
                triage = checkNotNull(row.get("triage", Boolean::class.javaObjectType)),
                cycle =
                    row.get("cycle_id", UUID::class.java)?.let { cycleId ->
                        IssueCycle(
                            cycleId,
                            checkNotNull(row.get("cycle_number", Int::class.javaObjectType)),
                            checkNotNull(row.get("cycle_starts_on", LocalDate::class.java)),
                            checkNotNull(row.get("cycle_ends_on", LocalDate::class.java)),
                        )
                    },
                openBlockers = checkNotNull(row.get("open_blockers", Long::class.javaObjectType)).toInt(),
                priority = IssuePriority.valueOf(checkNotNull(row.get("priority", String::class.java))),
                assignees = jsonMapper.readValue(checkNotNull(row.get("assignees", String::class.java)), Array<IssuePerson>::class.java).toList(),
                startDate = row.get("start_date", LocalDate::class.java),
                dueDate = row.get("due_date", LocalDate::class.java),
                labels = jsonMapper.readValue(checkNotNull(row.get("labels", String::class.java)), Array<IssueLabel>::class.java).toList(),
                subIssueCount = checkNotNull(row.get("sub_total", Long::class.javaObjectType)).toInt(),
                subIssueDoneCount = checkNotNull(row.get("sub_done", Long::class.javaObjectType)).toInt(),
                createdBy = checkNotNull(person("creator")),
                version = checkNotNull(row.get("version", Int::class.javaObjectType)),
                createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                deletedAt = row.get("deleted_at", OffsetDateTime::class.java)?.toInstant(),
                completedAt = row.get("completed_at", OffsetDateTime::class.java)?.toInstant(),
                commentCount = checkNotNull(row.get("comment_count", Long::class.javaObjectType)).toInt(),
                activityCount = checkNotNull(row.get("activity_count", Long::class.javaObjectType)).toInt(),
            )
        }.all()
            .collectList()
            .awaitSingle()
}
