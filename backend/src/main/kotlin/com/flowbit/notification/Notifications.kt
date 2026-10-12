package com.flowbit.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** Something happened to an issue that its subscribers should hear about. */
data class IssueNotification(
    /** For example PAY-12. */
    val issueKey: String,
    val issueNumber: Int,
    val issueTitle: String,
    /** The project the issue is linked to, if any. */
    val projectId: UUID?,
    val actorId: UUID,
    val actorName: String,
    /** IssueActivityType names, such as STATUS_CHANGED, with the same details the activity list shows. */
    val events: List<Event>,
    /** Subscribers of the issue, without the person who made the change. */
    val recipientIds: List<UUID>,
) {
    data class Event(
        val type: String,
        val detail: Map<String, Any?>,
    )
}

/**
 * One way of reaching people outside the app: mail (SMTP), web push (Firebase Cloud Messaging) …
 * None exists yet. To add one, implement this as a Spring bean; the dispatcher picks it up. A
 * channel looks up what it needs for each recipient itself (the address in `users.email`, the
 * device tokens it stores) and skips the recipients it cannot reach.
 */
interface NotificationChannel {
    suspend fun send(notification: IssueNotification)
}

/**
 * Hands a notification to every channel. Called after the change is committed, so a channel never
 * announces something that was rolled back; a channel that fails is logged and does not fail the
 * request or the other channels.
 */
@Component
class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun dispatch(notification: IssueNotification) {
        if (notification.recipientIds.isEmpty() || notification.events.isEmpty()) return
        for (channel in channels) {
            try {
                channel.send(notification)
            } catch (e: Exception) {
                log.warn("Notification channel {} failed for {}", channel.javaClass.simpleName, notification.issueKey, e)
            }
        }
    }
}
