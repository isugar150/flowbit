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
)

/** Comments on issues (V5__issue_comments.sql). A deleted comment stays as a row and is never read. */
@Component
class IssueCommentStore(
    private val databaseClient: DatabaseClient,
) {
    /** Oldest first. */
    suspend fun list(issueId: UUID): List<CommentView> = where("c.issue_id = :id order by c.created_at, c.id", issueId)

    suspend fun find(id: UUID): CommentView? = where("c.id = :id", id).firstOrNull()

    suspend fun add(
        issueId: UUID,
        authorId: UUID,
        document: RichTextDocument,
        now: Instant,
    ): UUID {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into issue_comments (id, issue_id, author_id, content, body, created_at, updated_at)
                values (:id, :issueId, :authorId, cast(:content as jsonb), :body, :now, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("issueId", issueId)
            .bind("authorId", authorId)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", now)
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

    private suspend fun where(
        where: String,
        id: UUID,
    ): List<CommentView> =
        databaseClient
            .sql(
                """
                select c.id, c.issue_id, c.content::text as content, c.created_at, c.updated_at,
                       u.id as author_id, u.login_id, u.display_name, u.avatar_key
                from issue_comments c join users u on u.id = c.author_id
                where c.deleted_at is null and $where
                """.trimIndent(),
            ).bind("id", id)
            .map { row ->
                CommentView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    issueId = checkNotNull(row.get("issue_id", UUID::class.java)),
                    author =
                        IssuePerson(
                            id = checkNotNull(row.get("author_id", UUID::class.java)),
                            loginId = checkNotNull(row.get("login_id", String::class.java)),
                            displayName = row.get("display_name", String::class.java),
                            avatarKey = row.get("avatar_key", String::class.java),
                        ),
                    content = checkNotNull(row.get("content", String::class.java)),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
}
