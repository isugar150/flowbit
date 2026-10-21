package com.flowbit.content

import com.flowbit.common.UuidV7
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.application.IssuePerson
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One comment of a thread. */
data class ThreadCommentView(
    val id: UUID,
    val threadId: UUID,
    val author: IssuePerson,
    /** Raw editor JSON: paragraphs, text and tags. */
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
)

/** The comments on one block of a page, as the page shows them. */
data class ThreadView(
    val id: UUID,
    val target: ContentTarget,
    val blockId: String,
    /** The start of the block's text when the thread was opened. */
    val quote: String,
    val createdBy: IssuePerson,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val resolvedBy: IssuePerson?,
    val comments: List<ThreadCommentView>,
    /** The block is no longer in the page. */
    val orphaned: Boolean = false,
)

/** What a request about threads hands back: the threads, and where to announce the change. */
data class ThreadsResult(
    val threads: List<ThreadView>,
    val context: ContentContext,
)

/** One thread after a change, with where to announce it. */
data class ThreadResult(
    val thread: ThreadView,
    val context: ContentContext,
)

/** Threads of comments on blocks (`content_threads`, `content_thread_comments`). */
@Component
class ContentThreadStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun threads(target: ContentTarget): List<ThreadView> =
        load("t.target_type = :type and t.target_id = :id") { spec ->
            spec.bind("type", target.type.name).bind("id", target.id)
        }

    suspend fun find(id: UUID): ThreadView? = load("t.id = :id") { it.bind("id", id) }.firstOrNull()

    suspend fun create(
        target: ContentTarget,
        blockId: String,
        quote: String,
        authorId: UUID,
        now: Instant,
    ): UUID {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into content_threads (id, target_type, target_id, block_id, quote, created_by, created_at)
                values (:id, :type, :targetId, :blockId, :quote, :authorId, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("type", target.type.name)
            .bind("targetId", target.id)
            .bind("blockId", blockId)
            .bind("quote", quote)
            .bind("authorId", authorId)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    suspend fun addComment(
        threadId: UUID,
        authorId: UUID,
        document: RichTextDocument,
        now: Instant,
    ): UUID {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into content_thread_comments (id, thread_id, author_id, content, body, created_at, updated_at)
                values (:id, :threadId, :authorId, cast(:content as jsonb), :body, :now, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("threadId", threadId)
            .bind("authorId", authorId)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    /** A comment with the thread it is in; null for one that is gone. */
    suspend fun findComment(id: UUID): ThreadCommentView? =
        databaseClient
            .sql(
                """
                select tc.id, tc.thread_id, tc.content::text as content, tc.created_at, tc.updated_at,
                       u.id as author_id, u.login_id, u.display_name, u.avatar_key
                from content_thread_comments tc join users u on u.id = tc.author_id
                where tc.id = :id and tc.deleted_at is null
                """.trimIndent(),
            ).bind("id", id)
            .map { row -> comment(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun updateComment(
        id: UUID,
        document: RichTextDocument,
        now: Instant,
    ) {
        databaseClient
            .sql("update content_thread_comments set content = cast(:content as jsonb), body = :body, updated_at = :now where id = :id")
            .bind("id", id)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun deleteComment(
        id: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update content_thread_comments set deleted_at = :now where id = :id")
            .bind("id", id)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Takes the thread and all its comments away. */
    suspend fun deleteThread(id: UUID) {
        databaseClient
            .sql("delete from content_threads where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun resolve(
        id: UUID,
        userId: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update content_threads set resolved_at = :now, resolved_by = :userId where id = :id")
            .bind("id", id)
            .bind("userId", userId)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun reopen(id: UUID) {
        databaseClient
            .sql("update content_threads set resolved_at = null, resolved_by = null where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The first comment of the thread, which opened it. */
    suspend fun firstCommentId(threadId: UUID): UUID? =
        databaseClient
            .sql("select id from content_thread_comments where thread_id = :id and deleted_at is null order by created_at, id limit 1")
            .bind("id", threadId)
            .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
            .one()
            .awaitSingleOrNull()

    private suspend fun load(
        where: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<ThreadView> {
        val threads =
            bind(
                databaseClient.sql(
                    """
                    select t.id, t.target_type, t.target_id, t.block_id, t.quote, t.created_at, t.resolved_at,
                           c.id as author_id, c.login_id, c.display_name, c.avatar_key,
                           r.id as resolver_id, r.login_id as resolver_login_id, r.display_name as resolver_display_name,
                           r.avatar_key as resolver_avatar_key
                    from content_threads t
                    join users c on c.id = t.created_by
                    left join users r on r.id = t.resolved_by
                    where $where
                    order by t.created_at, t.id
                    """.trimIndent(),
                ),
            ).map { row ->
                ThreadView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    target =
                        ContentTarget(
                            ContentTargetType.valueOf(checkNotNull(row.get("target_type", String::class.java))),
                            checkNotNull(row.get("target_id", UUID::class.java)),
                        ),
                    blockId = checkNotNull(row.get("block_id", String::class.java)),
                    quote = checkNotNull(row.get("quote", String::class.java)),
                    createdBy = person(row, "author_id", "login_id", "display_name", "avatar_key"),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    resolvedAt = row.get("resolved_at", OffsetDateTime::class.java)?.toInstant(),
                    resolvedBy =
                        row.get("resolver_id", UUID::class.java)?.let {
                            person(row, "resolver_id", "resolver_login_id", "resolver_display_name", "resolver_avatar_key")
                        },
                    comments = emptyList(),
                )
            }.all()
                .collectList()
                .awaitSingle()
        if (threads.isEmpty()) return threads
        val comments =
            bind(
                databaseClient.sql(
                    """
                    select tc.id, tc.thread_id, tc.content::text as content, tc.created_at, tc.updated_at,
                           u.id as author_id, u.login_id, u.display_name, u.avatar_key
                    from content_thread_comments tc
                    join content_threads t on t.id = tc.thread_id
                    join users u on u.id = tc.author_id
                    where tc.deleted_at is null and $where
                    order by tc.created_at, tc.id
                    """.trimIndent(),
                ),
            ).map { row -> comment(row) }
                .all()
                .collectList()
                .awaitSingle()
                .groupBy { it.threadId }
        return threads.map { it.copy(comments = comments[it.id].orEmpty()) }
    }

    private fun comment(row: io.r2dbc.spi.Readable) =
        ThreadCommentView(
            id = checkNotNull(row.get("id", UUID::class.java)),
            threadId = checkNotNull(row.get("thread_id", UUID::class.java)),
            author = person(row, "author_id", "login_id", "display_name", "avatar_key"),
            content = checkNotNull(row.get("content", String::class.java)),
            createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
            updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
        )

    private fun person(
        row: io.r2dbc.spi.Readable,
        id: String,
        loginId: String,
        displayName: String,
        avatarKey: String,
    ) = IssuePerson(
        id = checkNotNull(row.get(id, UUID::class.java)),
        loginId = checkNotNull(row.get(loginId, String::class.java)),
        displayName = row.get(displayName, String::class.java),
        avatarKey = row.get(avatarKey, String::class.java),
    )

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
