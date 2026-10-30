package com.flowbit.issue.application

import com.flowbit.common.UuidV7
import com.flowbit.common.richtext.RichTextDocument
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A comment on an issue as its page shows it. */
data class CommentView(
    val id: UUID,
    val issueId: UUID,
    val author: IssuePerson,
    /** Raw editor JSON. */
    val content: String,
    val createdAt: Instant,
    /** Later than `createdAt` once the comment has been edited. */
    val updatedAt: Instant,
    /** Whether the caller may change it: its author, while the issue can be edited. */
    val canEdit: Boolean = false,
    /** Whether the caller may delete it: its author or a workspace admin, while the issue can be edited. */
    val canDelete: Boolean = false,
    /** The emoji on it (D-164). */
    val reactions: List<ReactionSummary> = emptyList(),
    /** The files sent with it, in the order they were added (D-164). */
    val attachments: List<AttachmentView> = emptyList(),
    /** The comment this one answers; null for a comment of its own. A reply cannot be answered (D-167). */
    val parentId: UUID? = null,
    /**
     * A comment that was deleted while it has replies: only its place is kept, with no text, files or
     * emoji, so that the replies still have something to hang from (D-167).
     */
    val deleted: Boolean = false,
    val resolvedAt: Instant? = null,
    val resolvedBy: IssuePerson? = null,
)

/**
 * Comments on issues (`issue_comments`). A deleted comment stays as a row and is never read, except that one
 * with replies is listed as deleted (D-167).
 */
@Component
class IssueCommentStore(
    private val databaseClient: DatabaseClient,
) {
    /** Oldest first, replies among the rest (they belong under the comment their `parentId` names). */
    suspend fun list(issueId: UUID): List<CommentView> =
        where(
            "c.issue_id = :id order by c.created_at, c.id",
            issueId,
            // A deleted comment shows as long as someone has answered it.
            "(c.deleted_at is null or (c.parent_id is null and exists (select 1 from issue_comments r where r.parent_id = c.id and r.deleted_at is null)))",
        )

    /** A comment that has not been deleted. */
    suspend fun find(id: UUID): CommentView? = where("c.id = :id", id).firstOrNull()

    /** Who has written to the comment: the people whose replies to it are still there. */
    suspend fun replyAuthors(parentId: UUID): List<UUID> =
        databaseClient
            .sql("select distinct author_id from issue_comments where parent_id = :id and deleted_at is null")
            .bind("id", parentId)
            .map { row -> checkNotNull(row.get("author_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun add(
        issueId: UUID,
        authorId: UUID,
        document: RichTextDocument,
        now: Instant,
        parentId: UUID? = null,
    ): UUID {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into issue_comments (id, issue_id, author_id, content, body, created_at, updated_at, parent_id)
                values (:id, :issueId, :authorId, cast(:content as jsonb), :body, :now, :now, :parentId)
                """.trimIndent(),
            ).bind("id", id)
            .bind("issueId", issueId)
            .bind("authorId", authorId)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", now)
            .let { if (parentId != null) it.bind("parentId", parentId) else it.bindNull("parentId", UUID::class.java) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    suspend fun update(
        id: UUID,
        document: RichTextDocument,
        now: Instant,
    ) {
        databaseClient
            .sql("update issue_comments set content = cast(:content as jsonb), body = :body, updated_at = :now where id = :id")
            .bind("id", id)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun setResolved(
        id: UUID,
        userId: UUID,
        now: Instant,
        resolved: Boolean,
    ) {
        val sql =
            if (resolved) {
                "update issue_comments set resolved_at = :now, resolved_by = :userId where id = :id and deleted_at is null and resolved_at is null"
            } else {
                "update issue_comments set resolved_at = null, resolved_by = null where id = :id and deleted_at is null"
            }
        databaseClient
            .sql(sql)
            .bind("id", id)
            .let { if (resolved) it.bind("now", now.atOffset(java.time.ZoneOffset.UTC)).bind("userId", userId) else it }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(
        id: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update issue_comments set deleted_at = :now where id = :id")
            .bind("id", id)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** `live` is the condition for a comment to count; by default it is not deleted. */
    private suspend fun where(
        where: String,
        id: UUID,
        live: String = "c.deleted_at is null",
    ): List<CommentView> =
        databaseClient
            .sql(
                """
                select c.id, c.issue_id, c.parent_id, c.deleted_at, c.resolved_at, c.content::text as content, c.created_at, c.updated_at,
                       u.id as author_id, u.login_id, u.display_name, u.avatar_key,
                       r.id as resolver_id, r.login_id as resolver_login_id, r.display_name as resolver_display_name, r.avatar_key as resolver_avatar_key
                from issue_comments c join users u on u.id = c.author_id
                left join users r on r.id = c.resolved_by
                where $live and $where
                """.trimIndent(),
            ).bind("id", id)
            .map { row ->
                val deleted = row.get("deleted_at", OffsetDateTime::class.java) != null
                CommentView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    issueId = checkNotNull(row.get("issue_id", UUID::class.java)),
                    parentId = row.get("parent_id", UUID::class.java),
                    deleted = deleted,
                    resolvedAt = if (deleted) null else row.get("resolved_at", OffsetDateTime::class.java)?.toInstant(),
                    resolvedBy =
                        if (deleted) {
                            null
                        } else {
                            row.get("resolver_id", UUID::class.java)?.let {
                                IssuePerson(
                                    it,
                                    checkNotNull(row.get("resolver_login_id", String::class.java)),
                                    row.get("resolver_display_name", String::class.java),
                                    row.get("resolver_avatar_key", String::class.java),
                                )
                            }
                        },
                    author =
                        IssuePerson(
                            id = checkNotNull(row.get("author_id", UUID::class.java)),
                            loginId = checkNotNull(row.get("login_id", String::class.java)),
                            displayName = row.get("display_name", String::class.java),
                            avatarKey = row.get("avatar_key", String::class.java),
                        ),
                    // What a deleted comment said is not sent any more.
                    content = if (deleted) EMPTY_DOCUMENT else checkNotNull(row.get("content", String::class.java)),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private companion object {
        const val EMPTY_DOCUMENT = """{"type":"doc","content":[]}"""
    }
}
