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
     * Sends a comment at once, to the people tagged in it (D-166). It is not held and not merged
     * with what else the same person did: another change's followers must not read the comment,
     * and one person tagged in a comment must not read the next one they were not tagged in.
     */
    fun dispatchTalk(notification: IssueNotification) {
        send(notification)
    }

    fun dispatchTalk(notification: ContentNotification) {
        if (notification.recipientIds.isEmpty()) return
        sendContent(notification)
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
