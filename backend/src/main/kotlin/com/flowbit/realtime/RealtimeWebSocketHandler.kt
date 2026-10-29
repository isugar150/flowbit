package com.flowbit.realtime

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.SessionService
import com.flowbit.media.application.ImageStorage
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * `GET /api/v1/realtime` (WebSocket). Writes stay on REST; this only tells the browser that
 * something it shows has changed, and who else is looking at the same page.
 *
 * Browser → server: `{"type":"subscribe","topics":["project:FLOW","issue:12"],"presence":"issue:12"}`.
 * Every message replaces the previous subscription. Topics the user may not read are dropped.
 * `{"type":"focus","target":"description"}` says which part of that page the person is pointing
 * at or typing in (null for none); the others on the page see it in the presence list.
 *
 * Server → browser:
 * - `{"type":"changed","topic":"project:FLOW","actorId":"…"}`
 * - `{"type":"presence","key":"issue:12","viewers":[{id, loginId, displayName, avatarUrl, focus}]}`
 * - `{"type":"ping"}` every 20 seconds, so proxies with an idle timeout keep the connection.
 *
 * Arriving on a page and leaving it are noted as when the person last saw it ([PagePresence.seen]).
 *
 * The handshake is authenticated by the session cookie like any request (SecurityConfig). The
 * connection is closed when the session ends or the user is disabled.
 */
@Component
class RealtimeWebSocketHandler(
    private val hub: RealtimeHub,
    private val pages: PagePresence,
    private val sessionService: SessionService,
    private val userService: UserService,
    private val jsonMapper: JsonMapper,
) : WebSocketHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(session: WebSocketSession): Mono<Void> = mono { connect(session) }.flatMap { it }

    private suspend fun connect(session: WebSocketSession): Mono<Void> {
        val user =
            (session.handshakeInfo.principal.awaitSingleOrNull() as? Authentication)?.principal as? AuthenticatedUser
                ?: return session.close(CloseStatus.POLICY_VIOLATION)
        if (!sameOrigin(session)) {
            log.warn("Realtime connection refused: Origin {} does not match the host", session.handshakeInfo.headers.origin)
            return session.close(CloseStatus.POLICY_VIOLATION)
        }
        val profile = userService.findActive(user.id) ?: return session.close(SESSION_ENDED)
        return serve(session, user, Viewer(profile.id, profile.loginId, profile.displayName, ImageStorage.urlOf(profile.avatarKey)))
    }

    private fun serve(
        session: WebSocketSession,
        user: AuthenticatedUser,
        viewer: Viewer,
    ): Mono<Void> {
        val topics = AtomicReference<Set<String>>(emptySet())
        val presenceKey = AtomicReference<String?>(null)
        val watchedIssues = AtomicReference<Set<Int>>(emptySet())
        val snapshots = Sinks.many().unicast().onBackpressureBuffer<Map<String, Any?>>()

        suspend fun watch(numbers: Set<Int>) {
            watchedIssues.set(numbers)
            val issueViewers = hub.issueViewers(numbers).filter { (issueKey, _) -> pages.canRead(user, issueKey) }
            snapshots.tryEmitNext(mapOf("type" to "issue-presence-snapshot", "viewers" to issueViewers))
        }

        val input =
            session
                .receive()
                .map { it.payloadAsText }
                .concatMap { text ->
                    mono {
                        val request = runCatching { jsonMapper.readValue(text, ClientMessage::class.java) }.getOrNull()
                        if (request?.type == "subscribe") {
                            val readable =
                                request.topics
                                    .orEmpty()
                                    .take(MAX_TOPICS)
                                    .filter { pages.canRead(user, it) }
                                    .toSet()
                            val key = request.presence?.takeIf { pages.canRead(user, it) }
                            topics.set(readable)
                            val watching =
                                request.watchIssues
                                    .orEmpty()
                                    .filter { it > 0 }
                                    .distinct()
                                    .take(MAX_WATCHED_ISSUES)
                                    .toSet()
                            watch(watching)
                            val previous = presenceKey.getAndSet(key)
                            // Written before the others are told, so that whoever reads the
                            // page's people on hearing of the change finds the new times.
                            if (previous != key) listOfNotNull(previous, key).forEach { noteSeen(viewer, it) }
                            hub.setPresence(session.id, key, viewer)
                        } else if (request?.type == "watch-issues") {
                            val watching =
                                (watchedIssues.get() + request.watchIssues.orEmpty().filter { it > 0 })
                                    .take(MAX_WATCHED_ISSUES)
                                    .toSet()
                            watch(watching)
                        } else if (request?.type == "focus") {
                            hub.setFocus(session.id, request.target?.takeIf { it.length <= MAX_TARGET_LENGTH })
                        }
                    }
                }.then()

        val changed =
            hub
                .changes()
                .filter { it.topic in topics.get() }
                .map { mapOf("type" to "changed", "topic" to it.topic, "actorId" to it.actorId) }
        val presence =
            hub
                .presenceChanges()
                .filter { it == presenceKey.get() || (it.startsWith("issue:") && it.removePrefix("issue:").toIntOrNull() in watchedIssues.get()) }
                .concatMap { key ->
                    mono {
                        val viewers = if (pages.canRead(user, key)) hub.viewers(key) else emptyList()
                        mapOf("type" to "presence", "key" to key, "viewers" to viewers)
                    }
                }
        val pings = Flux.interval(PING_INTERVAL).map { mapOf("type" to "ping") }
        val output =
            session.send(
                Flux
                    .merge(changed, presence, pings, snapshots.asFlux())
                    .map { session.textMessage(jsonMapper.writeValueAsString(it)) },
            )

        // Signed out, session expired, or user disabled: stop telling this browser anything.
        val sessionEnded =
            Flux
                .interval(SESSION_CHECK_INTERVAL)
                .concatMap { mono { sessionService.isActive(user.sessionId) } }
                .filter { active -> !active }
                .next()
                .flatMap { session.close(SESSION_ENDED) }

        return Mono
            .firstWithSignal(input, output, sessionEnded)
            .doFinally {
                snapshots.tryEmitComplete()
                // The moment they left is when they last saw the page.
                mono { presenceKey.get()?.let { noteSeen(viewer, it) } }
                    .doFinally { hub.leave(session.id) }
                    .subscribe()
            }
    }

    /** A failure to note the time must not cost the person their connection. */
    private suspend fun noteSeen(
        viewer: Viewer,
        key: String,
    ) {
        runCatching { pages.seen(viewer.id, key) }.onFailure { log.warn("Could not note that {} saw {}", viewer.loginId, key, it) }
    }

    /**
     * Cookies are sent with WebSocket handshakes from any site, so the page that opened the
     * connection must be Flowbit itself. Behind the Next.js rewrite the browser's host arrives in
     * X-Forwarded-Host (which Reactor Netty also applies to the request URI).
     */
    private fun sameOrigin(session: WebSocketSession): Boolean {
        val headers = session.handshakeInfo.headers
        val origin = headers.origin ?: return true
        val originHost = runCatching { URI(origin).authority }.getOrNull() ?: return false
        val hosts =
            listOfNotNull(
                headers.getFirst("X-Forwarded-Host")?.substringBefore(',')?.trim(),
                headers.getFirst("Host"),
                session.handshakeInfo.uri.authority,
            )
        return hosts.any { it.equals(originHost, ignoreCase = true) }
    }

    data class ClientMessage(
        val type: String? = null,
        val topics: List<String>? = null,
        val presence: String? = null,
        val target: String? = null,
        val watchIssues: List<Int>? = null,
    )

    private companion object {
        const val MAX_TOPICS = 20
        const val MAX_WATCHED_ISSUES = 10_000
        const val MAX_TARGET_LENGTH = 100
        val PING_INTERVAL: Duration = Duration.ofSeconds(20)
        val SESSION_CHECK_INTERVAL: Duration = Duration.ofSeconds(60)
        val SESSION_ENDED = CloseStatus(4401, "session ended")
    }
}

@Configuration(proxyBeanMethods = false)
class RealtimeConfig {
    @Bean
    fun realtimeHandlerMapping(handler: RealtimeWebSocketHandler): HandlerMapping = SimpleUrlHandlerMapping(mapOf("/api/v1/realtime" to handler), -1)
}
