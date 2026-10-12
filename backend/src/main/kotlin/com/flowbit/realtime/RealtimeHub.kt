package com.flowbit.realtime

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Someone who has a page open, as shown in the presence avatars. */
data class Viewer(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    /** The part of the page they are pointing at or typing in, as named by the page; null when none. */
    val focus: String? = null,
)

/** Something under `topic` changed; whoever shows it should read it again. */
data class TopicChanged(
    val topic: String,
    val actorId: UUID,
)

/**
 * The in-process event bus behind realtime (docs/02-architecture.md "실시간 동기화"): change
 * notices for topics such as `project:FLOW` or `issue:FLOW-12`, and who is looking at which page.
 * Flowbit runs as a single instance, so memory is enough; several instances would relay through
 * PostgreSQL LISTEN/NOTIFY.
 */
@Component
class RealtimeHub {
    private val changes = Sinks.many().multicast().directBestEffort<TopicChanged>()
    private val presenceChanges = Sinks.many().multicast().directBestEffort<String>()

    /** Connection id → the page it is on and who is on it (with what they are pointing at). */
    private val presence = ConcurrentHashMap<String, Pair<String, Viewer>>()

    /** Call after the change is committed, so that whoever reads again sees it. */
    fun publish(
        topic: String,
        actorId: UUID,
    ) {
        changes.emitNext(TopicChanged(topic, actorId), RETRY_WHEN_CONCURRENT)
    }

    fun changes(): Flux<TopicChanged> = changes.asFlux()

    /** Presence keys whose set of viewers changed. */
    fun presenceChanges(): Flux<String> = presenceChanges.asFlux()

    /** Puts a connection on a page (or on none), announcing the change to the old and the new page. */
    fun setPresence(
        connectionId: String,
        key: String?,
        viewer: Viewer,
    ) {
        val previous = if (key == null) presence.remove(connectionId) else presence.put(connectionId, key to viewer)
        previous?.first?.takeIf { it != key }?.let { presenceChanges.emitNext(it, RETRY_WHEN_CONCURRENT) }
        key?.let { presenceChanges.emitNext(it, RETRY_WHEN_CONCURRENT) }
    }

    /** Says which part of its page a connection is on. Ignored while the connection is on no page. */
    fun setFocus(
        connectionId: String,
        focus: String?,
    ) {
        val updated = presence.computeIfPresent(connectionId) { _, (key, viewer) -> key to viewer.copy(focus = focus) }
        updated?.let { (key, _) -> presenceChanges.emitNext(key, RETRY_WHEN_CONCURRENT) }
    }

    fun leave(connectionId: String) {
        presence.remove(connectionId)?.let { (key, _) -> presenceChanges.emitNext(key, RETRY_WHEN_CONCURRENT) }
    }

    /**
     * Everyone on the page, once each even when they have it open in several tabs (the tab that is
     * pointing at something speaks for the person).
     */
    fun viewers(key: String): List<Viewer> =
        presence.values
            .filter { it.first == key }
            .map { it.second }
            .sortedBy { it.focus == null }
            .distinctBy { it.id }
            .sortedBy { (it.displayName ?: it.loginId).lowercase() }

    companion object {
        /** Topic for "some issue changed", heard by the lists that span projects (전체 이슈, 내 이슈). */
        const val ISSUES = "issues"

        /** Several request threads may publish at once; a sink accepts one at a time, so retry briefly. */
        private val RETRY_WHEN_CONCURRENT: Sinks.EmitFailureHandler = Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(200))
    }
}
