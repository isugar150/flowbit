package com.flowbit.realtime

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
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
 * notices for topics such as `project:FLOW` or `issue:12`, and who is looking at which page.
 * Flowbit runs as a single instance, so memory is enough; several instances would relay through
 * PostgreSQL LISTEN/NOTIFY.
 */
@Component
class RealtimeHub {
    private val changes = Sinks.many().multicast().directBestEffort<TopicChanged>()
    private val presenceChanges = Sinks.many().multicast().directBestEffort<String>()

    /** The one thread that hands signals to the sinks; see [emit]. */
    private val emitter = Schedulers.newSingle("realtime-hub", true)

    /** Connection id → the page it is on and who is on it (with what they are pointing at). */
    private val presence = ConcurrentHashMap<String, Pair<String, Viewer>>()

    /** Call after the change is committed, so that whoever reads again sees it. */
    fun publish(
        topic: String,
        actorId: UUID,
    ) {
        emit { changes.tryEmitNext(TopicChanged(topic, actorId)) }
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
        previous?.first?.takeIf { it != key }?.let(::presenceChanged)
        key?.let(::presenceChanged)
    }

    /** Says which part of its page a connection is on. Ignored while the connection is on no page. */
    fun setFocus(
        connectionId: String,
        focus: String?,
    ) {
        val updated = presence.computeIfPresent(connectionId) { _, (key, viewer) -> key to viewer.copy(focus = focus) }
        updated?.let { (key, _) -> presenceChanged(key) }
    }

    fun leave(connectionId: String) {
        presence.remove(connectionId)?.let { (key, _) -> presenceChanged(key) }
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

    private fun presenceChanged(key: String) = emit { presenceChanges.tryEmitNext(key) }

    /**
     * Hands a signal to the sinks from one thread, one at a time. A sink must not be given two
     * signals at once, and here they come from many request threads and even from inside the
     * handling of another signal (a connection that closes while it is being written to calls
     * [leave]). Emitting directly failed in exactly those cases and broke the connection that
     * was emitting, so who-is-here updates stopped arriving.
     */
    private fun emit(signal: () -> Unit) {
        emitter.schedule(signal)
    }

    companion object {
        /** Stands for whoever made a change no signed-in person made, such as a Git server's (D-141). */
        val NOBODY: UUID = UUID(0, 0)

        /** Topic for "some issue changed", heard by the lists that span projects (전체 티켓, 내 티켓). */
        const val ISSUES = "issues"

        /**
         * Topic for "the list of projects changed for someone": a project was made or renamed, or
         * somebody was let in, given another role or taken out. Heard by every page (the sidebar),
         * because someone who is not in a project yet cannot hear that project's own topic.
         */
        const val PROJECTS = "projects"
    }
}
