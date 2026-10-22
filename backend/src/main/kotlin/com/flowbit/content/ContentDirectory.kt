package com.flowbit.content

import com.flowbit.issue.application.IssueActivityStore
import com.flowbit.issue.application.IssueQueries
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Looks pages up without asking who is looking (what a notification needs, and what the activity
 * is written from). Who may see a page is [ContentAccess]'s to say, which stands on the services
 * that, in turn, write to the activity through this.
 */
@Component
class ContentDirectory(
    private val databaseClient: DatabaseClient,
    private val issueQueries: IssueQueries,
    private val issueActivities: IssueActivityStore,
) {
    /** Null when there is no such page. */
    suspend fun describe(target: ContentTarget): ContentContext? =
        when (target.type) {
            ContentTargetType.PROJECT -> project(target)
            ContentTargetType.DOCUMENT -> document(target)
            ContentTargetType.ISSUE -> issue(target)
        }

    private suspend fun project(target: ContentTarget): ContentContext? =
        databaseClient
            .sql("select name from projects where id = :id")
            .bind("id", target.id)
            .map { row -> checkNotNull(row.get("name", String::class.java)) }
            .one()
            .awaitSingleOrNull()
            ?.let { name ->
                ContentContext(
                    target = target,
                    title = name,
                    path = "/project/${target.id}",
                    projectId = target.id,
                    issueId = null,
                    issueNumber = null,
                    topics = listOf("project:${target.id}"),
                    readOnly = false,
                )
            }

    private data class DocumentRow(
        val title: String,
        val projectId: UUID?,
        val issueId: UUID?,
        val deleted: Boolean,
        val projectName: String?,
        val issueNumber: Int?,
    )

    private suspend fun document(target: ContentTarget): ContentContext? {
        val row =
            databaseClient
                .sql(
                    """
                    select d.title, coalesce(d.project_id, i.project_id) as project_id, d.issue_id, d.deleted_at, i.deleted_at as issue_deleted_at,
                           p.name as project_name, i.number as issue_number
                    from documents d
                    left join issues i on i.id = d.issue_id
                    left join projects p on p.id = d.project_id
                    where d.id = :id
                    """.trimIndent(),
                ).bind("id", target.id)
                .map { r ->
                    DocumentRow(
                        title = checkNotNull(r.get("title", String::class.java)),
                        projectId = r.get("project_id", UUID::class.java),
                        issueId = r.get("issue_id", UUID::class.java),
                        // A deleted document, and a document of a deleted issue, can only be read.
                        deleted = r.get("deleted_at", OffsetDateTime::class.java) != null || r.get("issue_deleted_at", OffsetDateTime::class.java) != null,
                        projectName = r.get("project_name", String::class.java),
                        issueNumber = r.get("issue_number", Int::class.javaObjectType),
                    )
                }.one()
                .awaitSingleOrNull() ?: return null
        val issueKey = row.issueNumber?.let { issueQueries.find(it)?.key }
        val where = row.projectName ?: issueKey.orEmpty()
        return ContentContext(
            target = target,
            title = (if (where.isEmpty()) "" else "[$where] ") + row.title.ifEmpty { "제목 없는 문서" },
            path = "/doc/${target.id}",
            projectId = row.projectId,
            issueId = row.issueId,
            issueNumber = row.issueNumber,
            topics = listOf(row.issueNumber?.let { "issue:$it" } ?: "project:${row.projectId}"),
            readOnly = row.deleted,
        )
    }

    private suspend fun issue(target: ContentTarget): ContentContext? {
        val number =
            databaseClient
                .sql("select number from issues where id = :id")
                .bind("id", target.id)
                .map { row -> checkNotNull(row.get("number", Int::class.javaObjectType)) }
                .one()
                .awaitSingleOrNull() ?: return null
        val issue = issueQueries.find(number) ?: return null
        return ContentContext(
            target = target,
            title = "[${issue.key}] ${issue.title}",
            path = "/issue/${issue.key}",
            projectId = issue.project?.id,
            issueId = issue.id,
            issueNumber = number,
            topics = listOf("issue:$number"),
            readOnly = issue.deletedAt != null,
        )
    }

    /**
     * Who is told about what happens to the page: the people who follow the document itself (and may
     * still open it, D-166); for a project, the people who follow the project (and may
     * still enter it); for an issue, the people who follow it or its project. Active people only.
     * Comments are not told to followers, only to the people tagged in them.
     */
    suspend fun followers(context: ContentContext): List<UUID> {
        if (context.target.type == ContentTargetType.DOCUMENT) return documentFollowers(context)
        context.issueId?.let { return issueActivities.recipientIds(it) }
        val projectId = context.projectId ?: return emptyList()
        return databaseClient
            .sql(
                """
                select u.id
                from users u
                join project_subscriptions ps on ps.user_id = u.id and ps.project_id = :projectId
                where u.status = 'ACTIVE'
                  and exists (select 1 from project_members pm where pm.project_id = :projectId and pm.user_id = u.id)
                order by u.login_id
                """.trimIndent(),
            ).bind("projectId", projectId)
            .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** The active people who follow the document and are still members of its project (anyone active when it has none). */
    private suspend fun documentFollowers(context: ContentContext): List<UUID> {
        val projectId = context.projectId
        val reach =
            if (projectId != null) {
                "and exists (select 1 from project_members pm where pm.project_id = :projectId and pm.user_id = u.id)"
            } else {
                ""
            }
        val spec =
            databaseClient
                .sql(
                    """
                    select u.id
                    from document_subscriptions s join users u on u.id = s.user_id
                    where s.document_id = :documentId and u.status = 'ACTIVE' $reach
                    order by u.login_id
                    """.trimIndent(),
                ).bind("documentId", context.target.id)
        return (if (projectId != null) spec.bind("projectId", projectId) else spec)
            .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Whether the person could open the page: an active member of its project, or anyone active for what is not in a project. */
    suspend fun canReach(
        context: ContentContext,
        userId: UUID,
    ): Boolean {
        val projectId = context.projectId
        val spec =
            if (projectId != null) {
                databaseClient
                    .sql(
                        """
                        select 1 from project_members pm join users u on u.id = pm.user_id
                        where pm.project_id = :projectId and pm.user_id = :userId and u.status = 'ACTIVE'
                        """.trimIndent(),
                    ).bind("projectId", projectId)
            } else {
                databaseClient.sql("select 1 from users u where u.id = :userId and u.status = 'ACTIVE'")
            }
        return spec
            .bind("userId", userId)
            .map { _ -> 1 }
            .first()
            .awaitSingleOrNull() != null
    }

    suspend fun displayName(userId: UUID): String = issueActivities.displayName(userId)
}
