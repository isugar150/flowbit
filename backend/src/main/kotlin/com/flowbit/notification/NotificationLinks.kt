package com.flowbit.notification

import com.flowbit.common.error.NotFoundException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

/** A routing token never grants access to the destination's content. */
@Service
class NotificationLinks(
    private val database: DatabaseClient,
    private val publicUrl: PublicUrl,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun prepare(content: WebhookContent): WebhookContent {
        val message = prepare(content.message)
        if (message === content.message) return content
        return WebhookContent(
            content.event,
            content.types,
            content.about,
            message,
            content.subject.mapValues { (_, value) ->
                if (value is Map<*, *> && value.containsKey("url")) value + mapOf("url" to message.url, "webUrl" to message.webUrl) else value
            },
            content.values.mapValues { (key, value) -> if (key.endsWith(".url")) message.url else value },
        )
    }

    suspend fun prepare(message: NotificationMessage): NotificationMessage {
        if (message.webUrl != null || !validTarget(message.path)) return message
        val base = publicUrl.base ?: return message
        val address = runCatching { URI(base) }.getOrNull() ?: return message
        if (address.scheme !in listOf("http", "https") ||
            address.host == null ||
            address.rawUserInfo != null ||
            address.rawQuery != null ||
            address.rawFragment != null
        ) {
            return message
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        database
            .sql("delete from notification_links where expires_at <= :now")
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        database
            .sql("insert into notification_links (token_hash, target_path, created_at, expires_at) values (:hash, :path, :now, :expires)")
            .bind("hash", hash(token))
            .bind("path", message.path)
            .bind("now", now)
            .bind("expires", now.plusDays(90))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return message.copy(url = publicUrl.of("/n/$token"), webUrl = message.url)
    }

    suspend fun resolve(token: String): String {
        if (!TOKEN.matches(token)) throw missing()
        val path =
            database
                .sql("select target_path from notification_links where token_hash = :hash and expires_at > :now")
                .bind("hash", hash(token))
                .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .map { row -> checkNotNull(row.get("target_path", String::class.java)) }
                .all()
                .collectList()
                .awaitSingle()
                .firstOrNull()
        return path?.takeIf(::validTarget) ?: throw missing()
    }

    private fun hash(token: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)))

    private fun missing() = NotFoundException("NOTIFICATION_LINK_NOT_FOUND", "알림 링크가 없거나 만료되었습니다")

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private const val ID = "[0-9a-fA-F-]{36}"
        private val TARGET = Regex("/(issue/[A-Za-z][A-Za-z0-9]*-[1-9][0-9]*|doc/$ID|project/$ID(/settings)?|my-issues(/created)?)(#access-requests)?")

        fun validTarget(path: String): Boolean = TARGET.matches(path)
    }
}

@RestController
class NotificationLinkController(
    private val links: NotificationLinks,
    private val limiter: RateLimiter,
) {
    @GetMapping("/api/v1/public/notification-links/{token}")
    suspend fun resolve(
        @PathVariable token: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, String>> {
        limiter.check("notification-link", ClientInfo.from(exchange).ipAddress, 120, Duration.ofMinutes(1))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(mapOf("path" to links.resolve(token)))
    }
}
