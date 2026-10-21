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
    /** Null when a Git server made the change for someone without an account here (D-141). */
    val actorId: UUID?,
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

    /**
     * Without the changes only an API notification that names them gets: a Git server's links
     * and commits (D-141). Null when nothing else is left to tell.
     */
    fun forEveryChannel(): IssueNotification? = events.filterNot { it.type in OPT_IN_TYPES }.takeIf { it.isNotEmpty() }?.let { copy(events = it) }

    companion object {
        /** Sent only to API notifications that list them in their "보낼 변경"; never by mail or push. */
        val OPT_IN_TYPES = setOf("GIT_BRANCH_LINKED", "GIT_PR_LINKED", "GIT_PR_MERGED", "GIT_PR_CLOSED", "GIT_COMMITS_PUSHED")
    }
}

/**
 * Something happened to a project, a document or a milestone, or someone commented on one (D-165):
 * what [IssueNotification] is for an issue. The changes are held and merged the same way.
 */
data class ContentNotification(
    /** `PROJECT`, `DOCUMENT` or `MILESTONE`. */
    val targetType: String,
    val targetId: UUID,
    /** What the notification is headed with: `[결제 개편] 설계 문서`. */
    val title: String,
    /** Where it opens: `/doc/…`. */
    val path: String,
    val projectId: UUID?,
    val actorId: UUID,
    val actorName: String,
    /** ContentEventType names, with the same details the activity list shows. */
    val events: List<IssueNotification.Event>,
    /** The people who follow the project (or the issue the document is on) and whoever is tagged or in the thread, without the actor. */
    val recipientIds: List<UUID>,
)

/**
 * Someone asked to be let into a project they may not enter (D-128). Told at once, not held,
 * to the people who can let them in.
 */
data class ProjectAccessRequestNotification(
    val projectId: UUID,
    val projectName: String,
    val requesterId: UUID,
    val requesterName: String,
    /** The project's active managers, or the workspace administrators when it has none. */
    val recipientIds: List<UUID>,
) {
    companion object {
        /** The kind of change a webhook can choose, next to the IssueActivityType names. */
        const val TYPE = "ACCESS_REQUESTED"
        const val EVENT = "project.access_requested"
    }
}

/**
 * One way of telling the world outside the app: mail (SMTP), web push (Firebase Cloud Messaging),
 * outgoing API calls (webhooks). To add one, implement this as a Spring bean; the dispatcher picks
 * it up. A channel checks for itself whether it is switched on, looks up what it needs for each
 * recipient (the address in `users.email`, the device tokens) and skips whom it cannot reach.
 */
interface NotificationChannel {
    suspend fun send(notification: IssueNotification)

    /** A request to join a project. A channel that has nothing to say about it leaves this out. */
    suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {}

    /** A change to a project, a document or a milestone, or a comment on one (D-165). */
    suspend fun sendContent(notification: ContentNotification) {}
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
        val actorId: UUID?,
    )

    private class Pending(
        val notification: IssueNotification,
        /** System.nanoTime() of the first change held. */
        val since: Long,
        val generation: Long,
        val timer: Job,
    )

    private data class ContentKey(
        val targetId: UUID,
        val actorId: UUID,
    )

    private class PendingContent(
        val notification: ContentNotification,
        val since: Long,
        val generation: Long,
        val timer: Job,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = HashMap<Key, Pending>()
    private val pendingContent = HashMap<ContentKey, PendingContent>()
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

    /**
     * What one person does to a project, a document or a milestone is held and merged like what
     * they do to an issue (D-126, D-165): one notification once they leave it alone for
     * [batchDelay], or [batchMaxDelay] after the first change at the latest.
     */
    fun dispatch(notification: ContentNotification) {
        if (notification.events.isEmpty() || notification.recipientIds.isEmpty()) return
        if (batchDelay.isZero || batchDelay.isNegative) {
            sendContent(notification)
            return
        }
        val key = ContentKey(notification.targetId, notification.actorId)
        synchronized(pending) {
            val held = pendingContent[key]
            held?.timer?.cancel()
            val since = held?.since ?: System.nanoTime()
            val merged = held?.let { NotificationMerge.merge(it.notification, notification) } ?: notification
            val left = batchMaxDelay.toNanos() - (System.nanoTime() - since)
            val wait = minOf(batchDelay.toNanos(), left).coerceAtLeast(0)
            val generation = ++generations
            val timer =
                scope.launch {
                    delay(wait / 1_000_000)
                    releaseContent(key, generation)
                }
            pendingContent[key] = PendingContent(merged, since, generation, timer)
        }
    }

    private fun releaseContent(
        key: ContentKey,
        generation: Long,
    ) {
        val held =
            synchronized(pending) {
                pendingContent[key]?.takeIf { it.generation == generation }?.also { pendingContent.remove(key) }
            } ?: return
        sendContent(held.notification)
    }

    private fun sendContent(notification: ContentNotification): List<Job> {
        if (notification.events.isEmpty()) return emptyList()
        return channels.map { channel ->
            scope.launch {
                try {
                    channel.sendContent(notification)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Notification channel {} failed for {}", channel.javaClass.simpleName, notification.title, e)
                }
            }
        }
    }

    /**
     * Sends at once, with whatever the same actor did to the issue and is still held (D-155). For
     * what a Git server did: nobody is setting the issue up field by field, so there is nothing to
     * wait for, and one call from the server is already one notification.
     */
    fun dispatchNow(notification: IssueNotification) {
        if (notification.events.isEmpty()) return
        val held = synchronized(pending) { pending.remove(Key(notification.issueNumber, notification.actorId)) }
        held?.timer?.cancel()
        send(held?.let { NotificationMerge.merge(it.notification, notification) } ?: notification)
    }

    /** Tells about a request to join a project, at once: someone is waiting for the answer. */
    fun announce(request: ProjectAccessRequestNotification) {
        if (request.recipientIds.isEmpty()) return
        for (channel in channels) {
            scope.launch {
                try {
                    channel.sendAccessRequest(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Notification channel {} failed for the access request to {}", channel.javaClass.simpleName, request.projectId, e)
                }
            }
        }
    }

    /** Sends at once what `actorId` has done to the issue and is still held. Nothing happens when nothing is held. */
    fun flush(
        issueNumber: Int,
        actorId: UUID?,
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

    /** Drops everything held without sending it. For tests, which must not hear what an earlier one did. */
    fun reset() {
        synchronized(pending) {
            pending.values.forEach { it.timer.cancel() }
            pending.clear()
            pendingContent.values.forEach { it.timer.cancel() }
            pendingContent.clear()
        }
    }

    @PreDestroy
    fun stop() {
        val held = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        held.forEach { it.timer.cancel() }
        val heldContent = synchronized(pending) { pendingContent.values.toList().also { pendingContent.clear() } }
        heldContent.forEach { it.timer.cancel() }
        val sending = held.flatMap { send(it.notification) } + heldContent.flatMap { sendContent(it.notification) }
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
            // What a project, a document or a milestone has (D-165).
            "NAME_CHANGED",
            "LEAD_CHANGED",
            "DATES_CHANGED",
            "TARGET_DATE_CHANGED",
        )
    private val ONCE = setOf("CREATED", "DESCRIPTION_CHANGED", "CONTENT_EDITED")
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

    /** The same for what is done to a project, a document or a milestone. */
    fun merge(
        earlier: ContentNotification,
        later: ContentNotification,
    ): ContentNotification {
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
                // The newer change's other details (who did it through Git, say) stay.
                replace(events, index, event.copy(detail = event.detail + ("from" to held.detail["from"])))
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
