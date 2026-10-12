package com.flowbit.notification

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** Something happened to an issue that people and other systems should hear about. */
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
    /** Subscribers of the issue, without the person who made the change. May be empty. */
    val recipientIds: List<UUID>,
) {
    data class Event(
        val type: String,
        val detail: Map<String, Any?>,
    )
}

/**
 * One way of telling the world outside the app: mail (SMTP), web push (Firebase Cloud Messaging),
 * outgoing API calls (webhooks). To add one, implement this as a Spring bean; the dispatcher picks
 * it up. A channel checks for itself whether it is switched on, looks up what it needs for each
 * recipient (the address in `users.email`, the device tokens) and skips whom it cannot reach.
 */
interface NotificationChannel {
    suspend fun send(notification: IssueNotification)
}

/**
 * Hands a notification to every channel, in the background: a slow mail server must not slow down
 * the request that changed the issue. Called after the change is committed, so a channel never
 * announces something that was rolled back. A channel that fails is logged and does not affect
 * the others. Nothing is queued on disk: what is in flight when the server stops is lost.
 */
@Component
class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun dispatch(notification: IssueNotification) {
        if (notification.events.isEmpty()) return
        for (channel in channels) {
            scope.launch {
                try {
                    channel.send(notification)
                } catch (e: Exception) {
                    log.warn("Notification channel {} failed for {}", channel.javaClass.simpleName, notification.issueKey, e)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }
}
