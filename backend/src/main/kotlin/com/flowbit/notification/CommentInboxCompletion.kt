package com.flowbit.notification

import com.flowbit.common.transaction.AfterCommit
import com.flowbit.realtime.RealtimeHub
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

enum class CommentConversation(
    val rootTable: String,
    val commentsTable: String,
    val condition: String,
) {
    ISSUE("issue_comments", "issue_comments", "id = :id or parent_id = :id"),
    CONTENT("content_comments", "content_comments", "id = :id or parent_id = :id"),
    THREAD("content_threads", "content_thread_comments", "thread_id = :id"),
}

/** Resolution is shared by the conversation; all its recipients can stop tracking its existing alerts. */
@Component
class CommentInboxCompletion(
    private val store: InboxStore,
    private val afterCommit: AfterCommit,
    private val hub: RealtimeHub,
    private val clock: Clock,
) {
    suspend fun complete(
        kind: CommentConversation,
        id: UUID,
    ) {
        val recipients = store.completeConversation(kind, id, clock.instant())
        afterCommit.run {
            recipients.distinct().forEach { hub.publish(RealtimeHub.inbox(it), RealtimeHub.NOBODY) }
        }
    }
}
