package com.flowbit.notification

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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
    /** Subscribers of the issue and of its project, without the person who made the change. May be empty. */
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
 * Hands notifications to every channel, in the background: a slow mail server must not slow down
 * the request that changed the issue. Called after the change is committed, so a channel never
 * announces something that was rolled back. A channel that fails is logged and does not affect
 * the others.
 *
 * What one person does to one issue is held and merged (D-126): it goes out as one notification
 * once they have left the issue alone for [batchDelay], or [batchMaxDelay] after their first
 * change at the latest, or at once when they close the issue ([flush]). Someone setting up a new
 * issue field by field then sends one message, not five. A zero [batchDelay] sends every change
 * at once.
 *
 * Nothing is kept on disk: what is waiting when the server stops is given a few seconds to go out,
 * and what is in flight then is lost.
 */
@Component
class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
    @Value("\${flowbit.notifications.batch-delay:1m}") private val batchDelay: Duration,
    @Value("\${flowbit.notifications.batch-max-delay:10m}") private val batchMaxDelay: Duration,
) {
    private data class Key(
        val issueNumber: Int,
        val actorId: UUID,
    )

    private class Pending(
        val notification: IssueNotification,
        /** System.nanoTime() of the first change held. */
        val since: Long,
        val generation: Long,
        val timer: Job,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = HashMap<Key, Pending>()
    private var generations = 0L

    fun dispatch(notification: IssueNotification) {
        if (notification.events.isEmpty()) return
        if (batchDelay.isZero || batchDelay.isNegative) {
            send(notification)
            return
        }
        val key = Key(notification.issueNumber, notification.actorId)
        synchronized(pending) {
            val held = pending[key]
            held?.timer?.cancel()
            val since = held?.since ?: System.nanoTime()
            val merged = held?.let { NotificationMerge.merge(it.notification, notification) } ?: notification
            // Quiet for batchDelay, but never later than batchMaxDelay after the first change.
            val left = batchMaxDelay.toNanos() - (System.nanoTime() - since)
            val wait = minOf(batchDelay.toNanos(), left).coerceAtLeast(0)
            val generation = ++generations
            val timer =
                scope.launch {
                    delay(wait / 1_000_000)
                    release(key, generation)
                }
            pending[key] = Pending(merged, since, generation, timer)
        }
    }

    /** Sends at once what `actorId` has done to the issue and is still held. Nothing happens when nothing is held. */
    fun flush(
        issueNumber: Int,
        actorId: UUID,
    ) {
        val held = synchronized(pending) { pending.remove(Key(issueNumber, actorId)) } ?: return
        held.timer.cancel()
        send(held.notification)
    }

    /** The timer of a held notification ran out; a newer change has its own timer and generation. */
    private fun release(
        key: Key,
        generation: Long,
    ) {
        val held =
            synchronized(pending) {
                pending[key]?.takeIf { it.generation == generation }?.also { pending.remove(key) }
            } ?: return
        send(held.notification)
    }

    private fun send(notification: IssueNotification): List<Job> {
        // Changes that cancelled each other out leave nothing to say.
        if (notification.events.isEmpty()) return emptyList()
        return channels.map { channel ->
            scope.launch {
                try {
                    channel.send(notification)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Notification channel {} failed for {}", channel.javaClass.simpleName, notification.issueKey, e)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        val held = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        held.forEach { it.timer.cancel() }
        val sending = held.flatMap { send(it.notification) }
        if (sending.isNotEmpty()) runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE) { sending.joinAll() } }
        scope.cancel()
    }

    private companion object {
        val SHUTDOWN_GRACE = 5.seconds
    }
}

/**
 * How the changes held for one notification are put together (D-126). A field changed several
 * times becomes one change from its first value to its last, and drops out when it ends where it
 * began; labels and assignees keep what was added or taken off in the end. "Created" and "body
 * edited" are said once, tagged names are gathered, and comments, files and documents each stay.
 */
object NotificationMerge {
    /** Changes that hold `from` and `to`. */
    private val FROM_TO =
        setOf(
            "TITLE_CHANGED",
            "STATUS_CHANGED",
            "PRIORITY_CHANGED",
            "ASSIGNEE_CHANGED",
            "DUE_DATE_CHANGED",
            "MILESTONE_CHANGED",
            "PROJECT_CHANGED",
            "PARENT_CHANGED",
        )
    private val ONCE = setOf("CREATED", "DESCRIPTION_CHANGED")
    private val OPPOSITES = mapOf("DELETED" to "RESTORED", "RESTORED" to "DELETED")

    /** `earlier` with `later` on top: the newer title, and the recipients of both. */
    fun merge(
        earlier: IssueNotification,
        later: IssueNotification,
    ): IssueNotification {
        val events = earlier.events.toMutableList()
        later.events.forEach { add(events, it) }
        return later.copy(events = events, recipientIds = (earlier.recipientIds + later.recipientIds).distinct())
    }

    private fun add(
        events: MutableList<IssueNotification.Event>,
        event: IssueNotification.Event,
    ) {
        val type = event.type
        val index = events.indexOfFirst { it.type == type }
        val held = events.getOrNull(index)
        when {
            type in ONCE -> {
                if (held == null) events += event
            }
            type in OPPOSITES -> {
                // Deleted and restored again in one go: nothing happened.
                val opposite = events.indexOfFirst { it.type == OPPOSITES[type] }
                if (opposite >= 0) {
                    events.removeAt(opposite)
                } else if (held == null) {
                    events += event
                }
            }
            held == null -> {
                events += event
            }
            "added" in event.detail && "added" in held.detail -> {
                val added1 = names(held.detail["added"])
                val removed1 = names(held.detail["removed"])
                val added2 = names(event.detail["added"])
                val removed2 = names(event.detail["removed"])
                val added = (added1 - removed2.toSet()) + (added2 - removed1.toSet())
                val removed = (removed1 - added2.toSet()) + (removed2 - added1.toSet())
                replace(events, index, event.copy(detail = mapOf("added" to added.distinct(), "removed" to removed.distinct())))
            }
            type in FROM_TO && "to" in event.detail && "from" in held.detail -> {
                replace(events, index, event.copy(detail = mapOf("from" to held.detail["from"], "to" to event.detail["to"])))
            }
            type == "MENTIONED" -> {
                val mentions = (names(held.detail["mentions"]) + names(event.detail["mentions"])).distinct()
                replace(events, index, event.copy(detail = mapOf("mentions" to mentions)))
            }
            else -> {
                events += event
            }
        }
    }

    /** Puts the merged change in place of the held one, or takes it out when it no longer changes anything. */
    private fun replace(
        events: MutableList<IssueNotification.Event>,
        index: Int,
        merged: IssueNotification.Event,
    ) {
        val detail = merged.detail
        val empty =
            if ("added" in detail) {
                names(detail["added"]).isEmpty() && names(detail["removed"]).isEmpty()
            } else {
                "from" in detail && detail["from"] == detail["to"]
            }
        if (empty) events.removeAt(index) else events[index] = merged
    }

    private fun names(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it.toString() }
}
