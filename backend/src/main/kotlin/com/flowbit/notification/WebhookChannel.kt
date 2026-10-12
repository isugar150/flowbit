package com.flowbit.notification

import com.flowbit.user.domain.UserRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What came of one call to a webhook's address. */
data class WebhookDelivery(
    /** The HTTP status, or null when no answer came back. */
    val status: Int?,
    val error: String?,
) {
    val ok: Boolean
        get() = status != null && status in 200..299
}

/** The one person a per-recipient call is about. */
data class WebhookRecipient(
    val id: UUID,
    val loginId: String,
    val name: String,
    val email: String?,
)

/**
 * Builds and sends the outgoing API call for a notification
 * (docs/features/M7a-notification-channels.md, "API 알림").
 */
@Component
class WebhookSender(
    private val jsonMapper: JsonMapper,
    private val texts: NotificationText,
    private val clock: Clock,
) {
    private val http = WebClient.create()

    /** The standard payload: what is sent when a webhook has no body template, and `{{payload}}` in one that has. */
    fun payload(
        notification: IssueNotification,
        deliveryId: UUID,
        recipient: WebhookRecipient? = null,
    ): Map<String, Any?> {
        val message = texts.of(notification)
        return mapOf(
            "event" to eventName(notification),
            "deliveryId" to deliveryId.toString(),
            "occurredAt" to OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(),
            "issue" to
                mapOf(
                    "key" to notification.issueKey,
                    "number" to notification.issueNumber,
                    "title" to notification.issueTitle,
                    "url" to message.url,
                    "projectId" to notification.projectId?.toString(),
                ),
            "actor" to mapOf("id" to notification.actorId.toString(), "name" to notification.actorName),
            "changes" to notification.events.map { mapOf("type" to it.type, "detail" to it.detail) },
            "recipientIds" to notification.recipientIds.map { it.toString() },
            // Only in a call made for one person.
            "recipient" to recipient?.let { mapOf("id" to it.id.toString(), "loginId" to it.loginId, "name" to it.name, "email" to it.email) },
            "title" to message.title,
            "headline" to message.headline,
            "lines" to message.lines,
            "text" to message.text,
        )
    }

    /** The address to call: the webhook's address with its `{{placeholders}}` filled in, URL-encoded. */
    fun url(
        webhook: Webhook,
        notification: IssueNotification,
        deliveryId: UUID,
        recipient: WebhookRecipient?,
    ): String {
        val values = values(notification, deliveryId, recipient)
        return PLACEHOLDER.replace(webhook.url) { match -> values[match.groupValues[1]]?.let(::urlEncode).orEmpty() }
    }

    /** The body to send: the standard JSON, or the template with its `{{placeholders}}` filled in. */
    fun body(
        webhook: Webhook,
        notification: IssueNotification,
        deliveryId: UUID,
        recipient: WebhookRecipient? = null,
    ): String {
        val json = jsonMapper.writeValueAsString(payload(notification, deliveryId, recipient))
        if (webhook.bodyTemplate.isBlank()) return json
        val values = values(notification, deliveryId, recipient)
        val escape = escaperFor(webhook.contentType)
        return PLACEHOLDER.replace(webhook.bodyTemplate) { match ->
            when (val name = match.groupValues[1]) {
                // Already JSON: goes in as it is, so a template can wrap the standard payload.
                "payload" -> json
                else -> values[name]?.let(escape).orEmpty()
            }
        }
    }

    private fun values(
        notification: IssueNotification,
        deliveryId: UUID,
        recipient: WebhookRecipient?,
    ): Map<String, String> {
        val message = texts.of(notification)
        return mapOf(
            "event" to eventName(notification),
            "deliveryId" to deliveryId.toString(),
            "title" to message.title,
            "headline" to message.headline,
            "changes" to message.lines.joinToString("\n"),
            "text" to message.text,
            "issue.key" to notification.issueKey,
            "issue.number" to notification.issueNumber.toString(),
            "issue.title" to notification.issueTitle,
            "issue.url" to message.url,
            "actor.name" to notification.actorName,
            "recipient.id" to recipient?.id?.toString().orEmpty(),
            "recipient.loginId" to recipient?.loginId.orEmpty(),
            "recipient.name" to recipient?.name.orEmpty(),
            "recipient.email" to recipient?.email.orEmpty(),
        )
    }

    /**
     * Calls the address once, or up to three times when [retry] is set and the failure looks
     * temporary (no answer, or a 5xx). Redirects are not followed. GET and DELETE carry no body:
     * everything they say is in the address.
     */
    suspend fun deliver(
        webhook: Webhook,
        notification: IssueNotification,
        recipient: WebhookRecipient?,
        retry: Boolean,
    ): WebhookDelivery {
        val deliveryId = UUID.randomUUID()
        val hasBody = webhook.method !in BODILESS
        val body = if (hasBody) body(webhook, notification, deliveryId, recipient) else ""
        val uri =
            try {
                // Already encoded above; handing WebClient a string would encode the % signs again.
                URI.create(url(webhook, notification, deliveryId, recipient))
            } catch (e: IllegalArgumentException) {
                return WebhookDelivery(null, "주소가 올바르지 않습니다: ${e.message.orEmpty().take(MAX_ERROR_LENGTH)}")
            }
        val spec =
            http
                .method(HttpMethod.valueOf(webhook.method))
                .uri(uri)
                .headers { headers ->
                    if (hasBody) headers.set("Content-Type", webhook.contentType)
                    headers.set("User-Agent", "Flowbit-Webhook")
                    headers.set("X-Flowbit-Event", eventName(notification))
                    headers.set("X-Flowbit-Delivery", deliveryId.toString())
                    if (hasBody && webhook.signingSecret.isNotEmpty()) headers.set("X-Flowbit-Signature", "sha256=" + sign(body, webhook.signingSecret))
                    // Last, so an administrator's own header (Authorization, a custom User-Agent) wins.
                    WebhookHeaders.parse(webhook.headers, jsonMapper).forEach { (name, value) -> headers.set(name, value) }
                }
        val request =
            (if (hasBody) spec.bodyValue(body) else spec)
                .exchangeToMono { response ->
                    val status = response.statusCode().value()
                    // A 5xx becomes an error only so that the retry below sees it.
                    response.releaseBody().then(if (status >= 500) Mono.error(ServerFailure(status)) else Mono.just(status))
                }.timeout(TIMEOUT)
        return try {
            val status = (if (retry) request.retryWhen(Retry.backoff(RETRIES, FIRST_BACKOFF)) else request).awaitSingle()
            WebhookDelivery(status, if (status in 200..299) null else "HTTP $status")
        } catch (e: Exception) {
            val failure = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ServerFailure>().firstOrNull()
            if (failure != null) {
                WebhookDelivery(failure.status, "HTTP ${failure.status}")
            } else {
                WebhookDelivery(null, (e.cause ?: e).let { it.message ?: it.javaClass.simpleName }.take(MAX_ERROR_LENGTH))
            }
        }
    }

    private class ServerFailure(
        val status: Int,
    ) : RuntimeException("HTTP $status")

    private fun eventName(notification: IssueNotification): String {
        val types = notification.events.map { it.type }
        return when {
            "CREATED" in types -> "issue.created"
            "DELETED" in types -> "issue.deleted"
            "RESTORED" in types -> "issue.restored"
            else -> "issue.updated"
        }
    }

    /** How a value is made safe inside a body of this type, so a title with a quote cannot break the JSON. */
    private fun escaperFor(contentType: String): (String) -> String =
        when {
            "json" in contentType.lowercase() -> { value -> jsonMapper.writeValueAsString(value).removeSurrounding("\"") }
            "x-www-form-urlencoded" in contentType.lowercase() -> ::urlEncode
            else -> { value -> value }
        }

    /** Percent-encoding for a value inside an address or a form body; a space becomes %20, which both accept. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun sign(
        body: String,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal(body.toByteArray()))
    }

    companion object {
        val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z.]+)\\s*}}")

        /** Methods that are sent without a body. */
        val BODILESS = setOf("GET", "DELETE")
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private val FIRST_BACKOFF: Duration = Duration.ofSeconds(1)
        private const val RETRIES = 2L
        private const val MAX_ERROR_LENGTH = 300
    }
}

/** The extra request headers of a webhook, stored as a JSON object. */
object WebhookHeaders {
    private val NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    /** Set by the HTTP client or by Flowbit itself; an administrator's value would be ignored or break the call. */
    private val RESERVED = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")

    fun parse(
        json: String,
        mapper: JsonMapper,
    ): Map<String, String> = runCatching { mapper.readTree(json).properties().associate { it.key to it.value.asString() } }.getOrDefault(emptyMap())

    /** What is wrong with these headers, or null. */
    fun problem(headers: Map<String, String>): String? =
        when {
            headers.size > MAX_HEADERS -> "헤더는 ${MAX_HEADERS}개까지 넣을 수 있습니다"
            headers.keys.any { !NAME.matches(it) } -> "헤더 이름에 쓸 수 없는 문자가 있습니다"
            headers.keys.any { it.lowercase() in RESERVED } -> "Host, Content-Type, Content-Length 같은 헤더는 직접 정할 수 없습니다"
            headers.values.any { value -> value.any { it == '\r' || it == '\n' } } -> "헤더 값에 줄바꿈을 넣을 수 없습니다"
            else -> null
        }

    private const val MAX_HEADERS = 20
}

/**
 * Calls every enabled webhook that asked for this kind of change: once for the change, or, for a
 * webhook set to "per recipient", once for each subscriber (an API that messages one person at a time).
 */
@Component
class WebhookNotificationChannel(
    private val webhooks: WebhookRepository,
    private val userRepository: UserRepository,
    private val sender: WebhookSender,
    private val clock: Clock,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(notification: IssueNotification) {
        val types = notification.events.map { it.type }.toSet()
        val wanted = webhooks.findEnabled().toList().filter { webhook -> webhook.eventTypeSet.let { it.isEmpty() || it.any(types::contains) } }
        if (wanted.isEmpty()) return
        val recipients =
            if (wanted.any { it.perRecipient } && notification.recipientIds.isNotEmpty()) {
                userRepository
                    .findActiveByIds(
                        notification.recipientIds,
                    ).toList()
                    .map { WebhookRecipient(it.id, it.loginId, it.displayName ?: it.loginId, it.email) }
            } else {
                emptyList()
            }
        for (webhook in wanted) {
            if (!webhook.perRecipient) {
                record(webhook, notification, sender.deliver(webhook, notification, null, retry = true))
                continue
            }
            // Someone without an address cannot be reached through an API that is addressed by e-mail.
            val needsEmail = "recipient.email" in webhook.url || "recipient.email" in webhook.bodyTemplate
            for (recipient in recipients) {
                if (needsEmail && recipient.email == null) continue
                record(webhook, notification, sender.deliver(webhook, notification, recipient, retry = true))
            }
        }
    }

    private suspend fun record(
        webhook: Webhook,
        notification: IssueNotification,
        delivery: WebhookDelivery,
    ) {
        if (!delivery.ok) log.warn("Webhook '{}' failed for {}: {}", webhook.name, notification.issueKey, delivery.error)
        webhooks.recordAttempt(webhook.id, delivery.status, delivery.error, clock.instant())
    }
}
