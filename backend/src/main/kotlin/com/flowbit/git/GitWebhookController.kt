package com.flowbit.git

import com.flowbit.common.error.ApiException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.issue.api.IssueAnnouncer
import com.flowbit.realtime.RealtimeHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Takes a call from a Git server (docs/features/git-integration.md "받는 방식"): finds the
 * connection, checks the signature, reads the events and links the issues they name.
 */
@Service
class GitWebhookService(
    private val connections: GitConnectionService,
    private val githubApps: GitHubAppService,
    private val providers: List<GitProvider>,
    private val links: GitLinkService,
    private val catalog: GitRepositoryCatalog,
    private val transactionalOperator: TransactionalOperator,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun receive(
        id: UUID,
        headers: HttpHeaders,
        body: ByteArray,
    ): GitApplyResult {
        val connection = connections.find(id)?.takeIf { it.enabled } ?: throw notFound()
        val provider = providers.firstOrNull { it.type == connection.provider } ?: throw notFound()
        val event = provider.eventName(headers)
        if (!provider.verify(headers, body, connection.secret)) {
            connections.recordReceipt(id, event, null, "서명이 맞지 않습니다. Git 서버의 비밀 값을 확인하세요")
            throw UnauthenticatedException("INVALID_SIGNATURE", "서명이 맞지 않습니다")
        }
        // A GitHub App was installed, removed or given other repositories: read where it is now.
        if (connection.provider == GitProviderType.GITHUB && event in GitHubProvider.INSTALLATION_EVENTS) {
            runCatching { githubApps.refresh(connection) }
                .onFailure { log.warn("Reading the installation of Git connection {} failed", id, it) }
        }
        val events =
            try {
                provider.parse(headers, body)
            } catch (e: GitPayloadException) {
                connections.recordReceipt(id, event, null, "본문을 읽을 수 없습니다: ${e.message}")
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "본문을 읽을 수 없습니다: ${e.message}")
            }
        if (events.isEmpty()) {
            // An event this connection was not set up for, or one Flowbit does not use: nothing to do.
            connections.recordReceipt(id, event, null, null)
            return GitApplyResult(GitReceiptResult(), emptySet(), emptySet())
        }
        val result =
            try {
                transactionalOperator.executeAndAwait {
                    links.apply(connection, events)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("A {} event for Git connection {} could not be applied", event, id, e)
                connections.recordReceipt(id, event, null, "처리하지 못했습니다. 서버 로그를 확인하세요")
                throw e
            }
        connections.recordReceipt(id, event, jsonMapper.writeValueAsString(result.receipt), null)
        catalog.remember(connection.id, events.map { it.repository })
        return result
    }

    private fun notFound() = NotFoundException("GIT_CONNECTION_NOT_FOUND", "Git 연결을 찾을 수 없습니다")
}

class PayloadTooLargeException : ApiException(HttpStatus.valueOf(413), "PAYLOAD_TOO_LARGE", "본문이 너무 큽니다")

/**
 * The address a Git server calls. There is no session: the signature is the authentication, so
 * SecurityConfig lets anyone POST here and leaves the path out of the CSRF check.
 */
@RestController
class GitWebhookController(
    private val service: GitWebhookService,
    private val rateLimiter: RateLimiter,
    private val announcer: IssueAnnouncer,
) {
    /** 200 with what was linked; an event Flowbit does not use is 200 too, with nothing in it. */
    @PostMapping("/api/v1/integrations/git/{id}/webhook")
    suspend fun receive(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): GitReceiptResult {
        val connectionId = runCatching { UUID.fromString(id) }.getOrNull() ?: throw NotFoundException("GIT_CONNECTION_NOT_FOUND", "Git 연결을 찾을 수 없습니다")
        rateLimiter.check("git-webhook", connectionId.toString(), REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        val result = service.receive(connectionId, exchange.request.headers, read(exchange))
        // Committed by now: status changes are told like changes by hand; pages with new links read again.
        announcer.changedFromGit(result.changes)
        (result.touched - result.changes.map { it.second.issue.number }.toSet()).forEach { announcer.touched(RealtimeHub.NOBODY, it) }
        return result.receipt
    }

    private suspend fun read(exchange: ServerWebExchange): ByteArray {
        if (exchange.request.headers.contentLength > MAX_BODY_BYTES) throw PayloadTooLargeException()
        val buffer =
            try {
                DataBufferUtils.join(exchange.request.body, MAX_BODY_BYTES).awaitSingleOrNull() ?: return ByteArray(0)
            } catch (_: DataBufferLimitException) {
                throw PayloadTooLargeException()
            }
        try {
            return ByteArray(buffer.readableByteCount()).also(buffer::read)
        } finally {
            DataBufferUtils.release(buffer)
        }
    }

    private companion object {
        /** 5MB (docs/features/git-integration.md "받는 방식"). */
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val REQUESTS_PER_MINUTE = 600
    }
}
