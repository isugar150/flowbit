package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.application.IssueActivityStore
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.IssueQueries
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationDispatcher
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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

/**
 * Comments on the blocks of a page, as threads (D-165). Anyone who may see the page, the view-only
 * role included, opens a thread, answers one, resolves and reopens it; a deleted issue or document
 * can only be read. The position is the block's number, kept apart from the text, so a thread outlives
 * its block: it is then "orphaned" until the block is back.
 */
@Service
class ContentThreadService(
    private val access: ContentAccess,
    private val store: ContentThreadStore,
    private val richText: RichText,
    private val directory: ContentDirectory,
    private val log: ContentLog,
    private val issueQueries: IssueQueries,
    private val issueActivities: IssueActivityStore,
    private val subscriptions: DocumentSubscriptionStore,
    private val notifications: NotificationDispatcher,
    private val clock: Clock,
) {
    suspend fun threads(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ThreadsResult {
        val context = access.resolve(user, target)
        return ThreadsResult(withRights(user, context, store.threads(target), context.target), context)
    }

    @Transactional
    suspend fun open(
        user: AuthenticatedUser,
        target: ContentTarget,
        blockId: String,
        rawContent: Any?,
    ): ThreadResult {
        val context = writable(user, target)
        val document = commentDocument(rawContent)
        val quote =
            richText.blockQuote(richText.load(target.type.table, target.id), blockId)
                ?: throw ConflictException("BLOCK_NOT_FOUND", "이 블록은 아직 저장되지 않았거나 지워졌습니다. 잠시 뒤 다시 시도하세요")
        val now = clock.instant()
        val threadId = store.create(target, blockId, quote, user.id, now)
        store.addComment(threadId, user.id, document, now)
        announce(user, context, quote, document, inConversation = emptyList())
        return ThreadResult(thread(user, context, threadId), context)
    }

    @Transactional
    suspend fun reply(
        user: AuthenticatedUser,
        threadId: UUID,
        rawContent: Any?,
    ): ThreadResult {
        val existing = store.find(threadId) ?: throw threadNotFound()
        val context = writable(user, existing.target)
        val document = commentDocument(rawContent)
        val inConversation =
            existing.comments
                .map { it.author.id }
                .distinct()
                .filter { it != user.id && directory.canReach(context, it) }
        store.addComment(threadId, user.id, document, clock.instant())
        announce(user, context, existing.quote, document, inConversation, reply = true)
        return ThreadResult(thread(user, context, threadId), context)
    }

    @Transactional
    suspend fun edit(
        user: AuthenticatedUser,
        commentId: UUID,
        rawContent: Any?,
    ): ThreadResult {
        val comment = store.findComment(commentId) ?: throw commentNotFound()
        val existing = store.find(comment.threadId) ?: throw threadNotFound()
        val context = writable(user, existing.target)
        if (comment.author.id != user.id) throw ForbiddenException("다른 사람의 댓글은 수정할 수 없습니다")
        val document = commentDocument(rawContent)
        store.updateComment(commentId, document, clock.instant())
        // Only the people newly tagged by the change are told.
        val tagged = taggedPeople(context, document.mentions - richText.mentionsIn(comment.content), user.id)
        if (tagged.isNotEmpty()) announceTo(user, context, existing.quote, document, tagged)
        return ThreadResult(thread(user, context, comment.threadId), context)
    }

    /** The comment that opened a thread takes the thread with it; an answer goes alone. Its author or a workspace admin. */
    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        commentId: UUID,
    ): ThreadsResult {
        val comment = store.findComment(commentId) ?: throw commentNotFound()
        val existing = store.find(comment.threadId) ?: throw threadNotFound()
        val context = writable(user, existing.target)
        if (comment.author.id != user.id && user.role != UserRole.ADMIN) throw ForbiddenException("다른 사람의 댓글은 지울 수 없습니다")
        if (store.firstCommentId(comment.threadId) == commentId) store.deleteThread(comment.threadId) else store.deleteComment(commentId, clock.instant())
        return ThreadsResult(emptyList(), context)
    }

    @Transactional
    suspend fun setResolved(
        user: AuthenticatedUser,
        threadId: UUID,
        resolved: Boolean,
    ): ThreadResult {
        val existing = store.find(threadId) ?: throw threadNotFound()
        val context = writable(user, existing.target)
        if (resolved) store.resolve(threadId, user.id, clock.instant()) else store.reopen(threadId)
        return ThreadResult(thread(user, context, threadId), context)
    }

    /** The page, once the person may see it and it can still take comments. */
    private suspend fun writable(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ContentContext {
        val context = access.resolve(user, target)
        if (context.readOnly) throw ForbiddenException("삭제된 티켓이나 문서에는 댓글을 달 수 없습니다")
        return context
    }

    private suspend fun thread(
        user: AuthenticatedUser,
        context: ContentContext,
        threadId: UUID,
    ): ThreadView = withRights(user, context, listOf(checkNotNull(store.find(threadId))), context.target).single()

    /** Marks threads whose block is gone, and what the person may do with each comment. */
    private suspend fun withRights(
        user: AuthenticatedUser,
        context: ContentContext,
        threads: List<ThreadView>,
        target: ContentTarget,
    ): List<ThreadView> {
        val blocks = richText.blockIdsIn(richText.load(target.type.table, target.id)).toSet()
        return threads.map { thread ->
            thread.copy(
                orphaned = thread.blockId !in blocks,
                comments =
                    thread.comments.map {
                        val mine = it.author.id == user.id
                        it.copy(canEdit = mine && !context.readOnly, canDelete = (mine || user.role == UserRole.ADMIN) && !context.readOnly)
                    },
            )
        }
    }

    private fun commentDocument(raw: Any?): RichTextDocument {
        val document = raw?.let { richText.parse(it, textOnly = true) }
        if (document == null || document.plainText.isBlank()) throw ValidationException("content", "REQUIRED", "댓글 내용을 입력하세요")
        return document
    }

    /**
     * Who hears about a comment: the people tagged in it and, for a reply, the others in the thread,
     * and nobody else (D-166, D-167). Only the people tagged follow the page because of it.
     */
    private suspend fun announce(
        user: AuthenticatedUser,
        context: ContentContext,
        quote: String,
        document: RichTextDocument,
        inConversation: List<UUID>,
        reply: Boolean = false,
    ) {
        val tagged = taggedPeople(context, document.mentions, user.id)
        announceTo(user, context, quote, document, tagged, told = (tagged + inConversation).distinct(), reply = reply)
    }

    private suspend fun taggedPeople(
        context: ContentContext,
        mentions: Collection<UUID>,
        actorId: UUID,
    ): List<UUID> = mentions.filter { it != actorId && directory.canReach(context, it) }

    private suspend fun announceTo(
        user: AuthenticatedUser,
        context: ContentContext,
        quote: String,
        document: RichTextDocument,
        tagged: List<UUID>,
        told: List<UUID> = tagged,
        reply: Boolean = false,
    ) {
        val names = tagged.map { directory.displayName(it) }
        val detail =
            mapOf("quote" to quote, "text" to document.plainText.take(PREVIEW_LENGTH), "mentions" to names) +
                (if (reply) mapOf("reply" to true) else emptyMap())
        if (context.issueId != null && context.target.type == ContentTargetType.ISSUE) {
            // A comment on a ticket's body is a comment on the ticket: the people in it follow the ticket, and the ones tagged are told.
            issueActivities.subscribe(context.issueId, user.id)
            tagged.forEach { issueActivities.subscribe(context.issueId, it) }
            if (told.isEmpty()) return
            val issue = issueQueries.find(checkNotNull(context.issueNumber)) ?: return
            notifications.dispatchTalk(
                IssueNotification(
                    issueKey = issue.key,
                    issueNumber = issue.number,
                    issueTitle = issue.title,
                    projectId = issue.project?.id,
                    actorId = user.id,
                    actorName = directory.displayName(user.id),
                    events = listOf(IssueNotification.Event("COMMENT_ADDED", detail)),
                    recipientIds = told,
                ),
            )
            return
        }
        // Being tagged in a comment on a document makes the person follow it; they can stop (D-166).
        if (context.target.type == ContentTargetType.DOCUMENT) tagged.forEach { subscriptions.subscribe(context.target.id, it) }
        log.announce(context.target, user.id, listOf(ContentEvent(ContentEventType.THREAD_COMMENT, detail)), told)
    }

    private fun threadNotFound() = NotFoundException("THREAD_NOT_FOUND", "댓글을 찾을 수 없습니다")

    private fun commentNotFound() = NotFoundException("COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다")

    private companion object {
        /** How much of a comment a notification quotes. */
        const val PREVIEW_LENGTH = 300
    }
}
