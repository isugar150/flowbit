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

    /** Connection id → the page it is on and who is on it. */
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

    fun leave(connectionId: String) {
        presence.remove(connectionId)?.let { (key, _) -> presenceChanges.emitNext(key, RETRY_WHEN_CONCURRENT) }
    }

    /** Everyone on the page, once each even when they have it open in several tabs. */
    fun viewers(key: String): List<Viewer> =
        presence.values
            .filter { it.first == key }
            .map { it.second }
            .distinctBy { it.id }
            .sortedBy { (it.displayName ?: it.loginId).lowercase() }

    private companion object {
        /** Several request threads may publish at once; a sink accepts one at a time, so retry briefly. */
        val RETRY_WHEN_CONCURRENT: Sinks.EmitFailureHandler = Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(200))
    }
}
