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

/** The one person a call is about: someone who follows the issue or its project. */
data class WebhookRecipient(
    val id: UUID,
    val loginId: String,
    val name: String,
    val email: String?,
)

/**
 * What one webhook call is about, put into words once: the parts of the standard JSON and the
 * placeholders that say what happened. A change to an issue or a request to join a project (D-128).
 */
class WebhookContent(
    /** `issue.updated`, `project.access_requested` … */
    val event: String,
    /** The kinds of change in it, matched against the ones a webhook chose. */
    val types: Set<String>,
    /** What the log says it was about. */
    val about: String,
    val message: NotificationMessage,
    /** `issue`, `project`, `actor` and `changes` of the standard JSON. */
    val subject: Map<String, Any?>,
    /** The placeholders that say what happened; the rest are the same for every call. */
    val values: Map<String, String>,
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

    fun contentOf(notification: IssueNotification): WebhookContent {
        val message = texts.of(notification)
        return WebhookContent(
            event = eventName(notification),
            types = notification.events.map { it.type }.toSet(),
            about = notification.issueKey,
            message = message,
            subject =
                mapOf(
                    "issue" to
                        mapOf(
                            "key" to notification.issueKey,
                            "number" to notification.issueNumber,
                            "title" to notification.issueTitle,
                            "url" to message.url,
                            "appUrl" to message.appUrl,
                            "webUrl" to (message.webUrl ?: message.url),
                            "projectId" to notification.projectId?.toString(),
                        ),
                    "actor" to mapOf("id" to notification.actorId?.toString(), "name" to notification.actorName),
                    "changes" to notification.events.map { mapOf("type" to it.type, "detail" to it.detail) },
                ),
            values =
                mapOf(
                    "issue.key" to notification.issueKey,
                    "issue.number" to notification.issueNumber.toString(),
                    "issue.title" to notification.issueTitle,
                    "issue.url" to message.url,
                    "issue.appUrl" to message.appUrl.orEmpty(),
                    "actor.name" to notification.actorName,
                ),
        )
    }

    /** A change to a project or a document, or a comment on one (D-165): no issue, the page that changed instead. */
    fun contentOf(notification: ContentNotification): WebhookContent {
        val message = texts.of(notification)
        val kind = notification.targetType.lowercase()
        val commented = notification.events.any { it.type == "THREAD_COMMENT" || it.type == "COMMENT_ADDED" }
        return WebhookContent(
            event = "$kind.${if (commented) "commented" else "updated"}",
            types = notification.events.map { it.type }.toSet(),
            about = notification.title,
            message = message,
            subject =
                mapOf(
                    "issue" to null,
                    "target" to
                        mapOf(
                            "type" to notification.targetType,
                            "id" to notification.targetId.toString(),
                            "title" to notification.title,
                            "url" to message.url,
                            "projectId" to notification.projectId?.toString(),
                        ),
                    "actor" to mapOf("id" to notification.actorId.toString(), "name" to notification.actorName),
                    "changes" to notification.events.map { mapOf("type" to it.type, "detail" to it.detail) },
                ),
            values =
                mapOf(
                    "target.type" to notification.targetType,
                    "target.id" to notification.targetId.toString(),
                    "target.title" to notification.title,
                    "target.url" to message.url,
                    "actor.name" to notification.actorName,
                ),
        )
    }

    /** A request to join a project: no issue, the project instead, and the requester as the actor. */
    fun contentOf(request: ProjectAccessRequestNotification): WebhookContent {
        val message = texts.of(request)
        return WebhookContent(
            event = ProjectAccessRequestNotification.EVENT,
            types = setOf(ProjectAccessRequestNotification.TYPE),
            about = "the access request to ${request.projectId}",
            message = message,
            subject =
                mapOf(
                    "issue" to null,
                    "project" to mapOf("id" to request.projectId.toString(), "name" to request.projectName, "url" to message.url),
                    "actor" to mapOf("id" to request.requesterId.toString(), "name" to request.requesterName),
                    "changes" to listOf(mapOf("type" to ProjectAccessRequestNotification.TYPE, "detail" to mapOf("project" to request.projectName))),
                ),
            values =
                mapOf(
                    "project.name" to request.projectName,
                    "project.url" to message.url,
                    "actor.name" to request.requesterName,
                ),
        )
    }

    /** The daily summary of a member (D-216): no issue; the member is the recipient and the summary is the message. */
    fun contentOf(summary: DailySummaryNotification): WebhookContent =
        WebhookContent(
            event = DailySummaryNotification.EVENT,
            types = setOf(DailySummaryNotification.TYPE),
            about = "the daily summary of ${summary.recipientId}",
            message = summary.message,
            subject = mapOf("issue" to null, "changes" to emptyList<Any>()),
            values = mapOf("summary.url" to summary.message.url),
        )

    /** The standard payload: what is sent when a webhook has no body template, and `{{payload}}` in one that has. */
    fun payload(
        content: WebhookContent,
        deliveryId: UUID,
        recipient: WebhookRecipient,
    ): Map<String, Any?> {
        val message = content.message
        return mapOf(
            "event" to content.event,
            "deliveryId" to deliveryId.toString(),
            "occurredAt" to OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(),
        ) + content.subject +
            mapOf(
                "recipient" to mapOf("id" to recipient.id.toString(), "loginId" to recipient.loginId, "name" to recipient.name, "email" to recipient.email),
                "title" to message.title,
                "headline" to message.headline,
                "lines" to message.lines,
                "text" to message.text,
                "appUrl" to message.appUrl,
                "webUrl" to (message.webUrl ?: message.url),
            )
    }

    /** The address to call: the webhook's address with its `{{placeholders}}` filled in, URL-encoded. */
    fun url(
        webhook: Webhook,
        content: WebhookContent,
        deliveryId: UUID,
        recipient: WebhookRecipient,
    ): String {
        val values = values(content, deliveryId, recipient)
        return PLACEHOLDER.replace(webhook.url) { match -> values[match.groupValues[1]]?.let(::urlEncode).orEmpty() }
    }

    /** The body to send: the standard JSON, or the template with its `{{placeholders}}` filled in. */
    fun body(
        webhook: Webhook,
        content: WebhookContent,
        deliveryId: UUID,
        recipient: WebhookRecipient,
    ): String {
        val json = jsonMapper.writeValueAsString(payload(content, deliveryId, recipient))
        if (webhook.bodyTemplate.isBlank()) return json
        val values = values(content, deliveryId, recipient)
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
        content: WebhookContent,
        deliveryId: UUID,
        recipient: WebhookRecipient,
    ): Map<String, String> {
        val message = content.message
        return mapOf(
            "event" to content.event,
            "deliveryId" to deliveryId.toString(),
            "title" to message.title,
            "headline" to message.headline,
            "changes" to message.lines.joinToString("\n"),
            "text" to message.text,
            "appUrl" to message.appUrl.orEmpty(),
            "webUrl" to (message.webUrl ?: message.url),
            "recipient.id" to recipient.id.toString(),
            "recipient.loginId" to recipient.loginId,
            "recipient.name" to recipient.name,
            "recipient.email" to recipient.email.orEmpty(),
        ) + content.values
    }

    /**
     * Calls the address once, or up to three times when [retry] is set and the failure looks
     * temporary (no answer, or a 5xx). Redirects are not followed. GET and DELETE carry no body:
     * everything they say is in the address.
     */
    suspend fun deliver(
        webhook: Webhook,
        content: WebhookContent,
        recipient: WebhookRecipient,
        retry: Boolean,
    ): WebhookDelivery {
        val deliveryId = UUID.randomUUID()
        val hasBody = webhook.method !in BODILESS
        val body = if (hasBody) body(webhook, content, deliveryId, recipient) else ""
        val uri =
            try {
                // Already encoded above; handing WebClient a string would encode the % signs again.
                URI.create(url(webhook, content, deliveryId, recipient))
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
                    headers.set("X-Flowbit-Event", content.event)
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
            "COMMENT_ADDED" in types -> "issue.commented"
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

/** How many API notifications a daily summary reached, and how many did not answer well. */
data class WebhookTally(
    val reached: Int,
    val failed: Int,
)

/**
 * Calls every enabled webhook that asked for this kind of change, once for each person the change
 * is told to (an API that messages one person at a time). A change nobody follows calls nothing.
 */
@Component
class WebhookNotificationChannel(
    private val links: NotificationLinks,
    private val webhooks: WebhookRepository,
    private val userRepository: UserRepository,
    private val sender: WebhookSender,
    private val clock: Clock,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A webhook that chose no kinds gets every change but Git's links and commits; one that chose
     * kinds gets the whole notification when any of them is in it.
     */
    override suspend fun send(notification: IssueNotification) {
        val full = sender.contentOf(notification)
        val common = notification.forEveryChannel()?.let(sender::contentOf)
        deliverAll(notification.recipientIds) { webhook ->
            val types = webhook.eventTypeSet
            if (types.isEmpty()) common else full.takeIf { types.any(full.types::contains) }
        }
    }

    override suspend fun sendContent(notification: ContentNotification) {
        val content = sender.contentOf(notification)
        deliverAll(notification.recipientIds) { webhook -> content.takeIf { webhook.eventTypeSet.let { it.isEmpty() || it.any(content.types::contains) } } }
    }

    override suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {
        val content = sender.contentOf(request)
        deliverAll(request.recipientIds) { webhook -> content.takeIf { webhook.eventTypeSet.let { it.isEmpty() || it.any(content.types::contains) } } }
    }

    /**
     * Sends the member their daily summary through each enabled webhook that chose no kinds or chose [DailySummaryNotification.TYPE]
     * (D-216), and says how many answered and how many did not. A webhook addressed by e-mail skips a member without one.
     */
    suspend fun sendDailySummary(summary: DailySummaryNotification): WebhookTally {
        val content = links.prepare(sender.contentOf(summary))
        val wanted = webhooks.findEnabled().toList().filter { it.eventTypeSet.let { types -> types.isEmpty() || DailySummaryNotification.TYPE in types } }
        val user = userRepository.findActiveByIds(listOf(summary.recipientId)).toList().firstOrNull() ?: return WebhookTally(0, 0)
        val recipient = WebhookRecipient(user.id, user.loginId, user.displayName ?: user.loginId, user.email)
        var reached = 0
        var failed = 0
        for (webhook in wanted) {
            if (recipient.email == null && ("recipient.email" in webhook.url || "recipient.email" in webhook.bodyTemplate)) continue
            val delivery = sender.deliver(webhook, content, recipient, retry = true)
            record(webhook, content, delivery)
            if (delivery.ok) reached += 1 else failed += 1
        }
        return WebhookTally(reached, failed)
    }

    private suspend fun deliverAll(
        recipientIds: List<UUID>,
        contentFor: (Webhook) -> WebhookContent?,
    ) {
        val wanted = webhooks.findEnabled().toList().mapNotNull { webhook -> contentFor(webhook)?.let { webhook to it } }
        if (wanted.isEmpty() || recipientIds.isEmpty()) return
        val recipients =
            userRepository
                .findActiveByIds(recipientIds)
                .toList()
                .map { WebhookRecipient(it.id, it.loginId, it.displayName ?: it.loginId, it.email) }
        for ((webhook, original) in wanted) {
            val content = links.prepare(original)
            // Someone without an address cannot be reached through an API that is addressed by e-mail.
            val needsEmail = "recipient.email" in webhook.url || "recipient.email" in webhook.bodyTemplate
            for (recipient in recipients) {
                if (needsEmail && recipient.email == null) continue
                record(webhook, content, sender.deliver(webhook, content, recipient, retry = true))
            }
        }
    }

    private suspend fun record(
        webhook: Webhook,
        content: WebhookContent,
        delivery: WebhookDelivery,
    ) {
        if (!delivery.ok) log.warn("Webhook '{}' failed for {}: {}", webhook.name, content.about, delivery.error)
        webhooks.recordAttempt(webhook.id, delivery.status, delivery.error, clock.instant())
    }
}
