package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.application.IssueActivityStore
import com.flowbit.issue.application.IssueQueries
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationDispatcher
import com.flowbit.user.domain.UserRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

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
     * Who hears about a comment: the people tagged in it and, for a new comment, the page's followers (D-218); for a
     * reply, the others in the thread, and nobody else (D-166, D-167). Only the people tagged follow the page because of it.
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
        // A new comment is for everyone who follows the page; a reply is for the ones it is about (D-218).
        val told = tagged + if (reply) inConversation else directory.followers(context)
        announceTo(user, context, quote, document, tagged, told = told.distinct(), reply = reply)
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
