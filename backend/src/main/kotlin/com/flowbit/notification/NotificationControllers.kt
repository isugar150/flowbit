package com.flowbit.notification

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.CredentialPolicy
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** The password itself is never sent back; only whether one is stored. */
data class SmtpResponse(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val username: String,
    val passwordSet: Boolean,
    val fromAddress: String,
    val fromName: String,
)

/** The service account key is never sent back; only whether one is stored and whose project it is. */
data class PushResponse(
    val enabled: Boolean,
    val serviceAccountSet: Boolean,
    val projectId: String?,
    val apiKey: String,
    val messagingSenderId: String,
    val appId: String,
    val vapidKey: String,
)

data class NotificationSettingsResponse(
    val smtp: SmtpResponse,
    val push: PushResponse,
)

data class SmtpRequest(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val password: String? = null,
    val fromAddress: String = "",
    val fromName: String = "",
    /** Only read by the test endpoint: where the test mail goes. */
    val testTo: String? = null,
) {
    fun toInput() = SmtpInput(enabled, host, port, security, username, password, fromAddress, fromName)
}

data class PushRequest(
    val enabled: Boolean = false,
    val serviceAccount: String? = null,
    val apiKey: String = "",
    val messagingSenderId: String = "",
    val appId: String = "",
    val vapidKey: String = "",
) {
    fun toInput() = PushInput(enabled, serviceAccount, apiKey, messagingSenderId, appId, vapidKey)
}

/** Header values are returned as stored (administrators only). The signing secret is not. */
data class WebhookResponse(
    val id: UUID,
    val name: String,
    val enabled: Boolean,
    val url: String,
    val method: String,
    val contentType: String,
    val headers: Map<String, String>,
    val bodyTemplate: String,
    val signingSecretSet: Boolean,
    val eventTypes: List<String>,
    val perRecipient: Boolean,
    val lastStatus: Int?,
    val lastError: String?,
    val lastAttemptAt: Instant?,
)

data class WebhookRequest(
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val method: String = "POST",
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String = "",
    val signingSecret: String? = null,
    val eventTypes: List<String> = emptyList(),
    val perRecipient: Boolean = false,
    /** Only read by the test endpoint: the saved webhook whose signing secret to use. */
    val id: UUID? = null,
) {
    fun toInput() = WebhookInput(name, enabled, url, method, contentType, headers, bodyTemplate, signingSecret, eventTypes, perRecipient)
}

data class TestResponse(
    val ok: Boolean,
    val message: String,
)

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/notifications")
class AdminNotificationController(
    private val service: NotificationSettingsService,
    private val mailer: Mailer,
    private val pushChannel: PushNotificationChannel,
    private val webhookSender: WebhookSender,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping
    suspend fun settings(): NotificationSettingsResponse = response(service.settings())

    @PutMapping("/smtp")
    suspend fun updateSmtp(
        @RequestBody body: SmtpRequest,
    ): NotificationSettingsResponse = response(service.updateSmtp(body.toInput()))

    /** Sends one mail with the settings in the body, without saving them. Always 200; `ok` says how it went. */
    @PostMapping("/smtp/test")
    suspend fun testSmtp(
        @RequestBody body: SmtpRequest,
    ): TestResponse {
        val to = body.testTo?.let(CredentialPolicy::normalizeLoginId).orEmpty()
        if (!CredentialPolicy.isEmail(to)) throw ValidationException("testTo", "INVALID", "테스트 메일을 받을 이메일 주소를 입력하세요")
        val settings = service.smtpDraft(body.toInput())
        return try {
            mailer.send(settings, to, "Flowbit 테스트 메일", "Flowbit 의 메일 알림 설정이 동작합니다.", "<p>Flowbit 의 메일 알림 설정이 동작합니다.</p>")
            TestResponse(true, "$to 로 테스트 메일을 보냈습니다")
        } catch (e: Exception) {
            TestResponse(false, "메일을 보내지 못했습니다: ${(e.cause ?: e).message.orEmpty().take(MAX_MESSAGE_LENGTH)}")
        }
    }

    @PutMapping("/push")
    suspend fun updatePush(
        @RequestBody body: PushRequest,
    ): NotificationSettingsResponse = response(service.updatePush(body.toInput()))

    /** Pushes a test message to the browsers the calling administrator registered, with the saved settings. */
    @PostMapping("/push/test")
    suspend fun testPush(
        @AuthenticationPrincipal actor: AuthenticatedUser,
    ): TestResponse {
        val sent = pushChannel.pushTo(listOf(actor.id), "Flowbit 테스트 알림", "웹 푸시 설정이 동작합니다.", "flowbit-test", "/")
        return if (sent > 0) {
            TestResponse(true, "이 계정으로 등록한 브라우저 ${sent}곳에 보냈습니다")
        } else {
            TestResponse(false, "보내지 못했습니다. 푸시를 켜고 저장했는지, 내 설정에서 이 브라우저의 알림을 켰는지 확인하세요")
        }
    }

    @GetMapping("/webhooks")
    suspend fun webhooks(): List<WebhookResponse> = service.webhooks().map(::response)

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createWebhook(
        @RequestBody body: WebhookRequest,
    ): WebhookResponse = response(service.createWebhook(body.toInput()))

    @PutMapping("/webhooks/{id}")
    suspend fun updateWebhook(
        @PathVariable id: UUID,
        @RequestBody body: WebhookRequest,
    ): WebhookResponse = response(service.updateWebhook(id, body.toInput()))

    @DeleteMapping("/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteWebhook(
        @PathVariable id: UUID,
    ) {
        service.deleteWebhook(id)
    }

    /** Calls the address in the body once with a made-up issue change, without saving anything. Always 200. */
    @PostMapping("/webhooks/test")
    suspend fun testWebhook(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @RequestBody body: WebhookRequest,
    ): TestResponse {
        val webhook = service.draftWebhook(body.toInput(), body.id)
        val sample =
            IssueNotification(
                issueKey = "TEST-1",
                issueNumber = 1,
                issueTitle = "API 알림 테스트",
                projectId = null,
                actorId = actor.id,
                actorName = actor.loginId,
                events = listOf(IssueNotification.Event("STATUS_CHANGED", mapOf("from" to "BACKLOG", "to" to "IN_PROGRESS"))),
                recipientIds = emptyList(),
            )
        // A per-recipient webhook is tried on the administrator pressing the button.
        val recipient =
            if (webhook.perRecipient) WebhookRecipient(actor.id, actor.loginId, actor.loginId, service.mine(actor.id).email) else null
        val delivery = webhookSender.deliver(webhook, sample, recipient, retry = false)
        return if (delivery.ok) {
            TestResponse(true, "보냈습니다. 응답: HTTP ${delivery.status}")
        } else {
            TestResponse(false, "실패했습니다: ${delivery.error.orEmpty()}")
        }
    }

    private fun response(settings: NotificationSettings) =
        NotificationSettingsResponse(
            smtp =
                SmtpResponse(
                    enabled = settings.smtpEnabled,
                    host = settings.smtpHost,
                    port = settings.smtpPort,
                    security = settings.smtpSecurity,
                    username = settings.smtpUsername,
                    passwordSet = settings.smtpPassword.isNotEmpty(),
                    fromAddress = settings.smtpFromAddress,
                    fromName = settings.smtpFromName,
                ),
            push =
                PushResponse(
                    enabled = settings.pushEnabled,
                    serviceAccountSet = settings.fcmServiceAccount.isNotEmpty(),
                    projectId = service.projectIdOf(settings),
                    apiKey = settings.fcmApiKey,
                    messagingSenderId = settings.fcmMessagingSenderId,
                    appId = settings.fcmAppId,
                    vapidKey = settings.fcmVapidKey,
                ),
        )

    private fun response(webhook: Webhook) =
        WebhookResponse(
            id = webhook.id,
            name = webhook.name,
            enabled = webhook.enabled,
            url = webhook.url,
            method = webhook.method,
            contentType = webhook.contentType,
            headers = WebhookHeaders.parse(webhook.headers, jsonMapper),
            bodyTemplate = webhook.bodyTemplate,
            signingSecretSet = webhook.signingSecret.isNotEmpty(),
            eventTypes = webhook.eventTypeSet.toList(),
            perRecipient = webhook.perRecipient,
            lastStatus = webhook.lastStatus,
            lastError = webhook.lastError,
            lastAttemptAt = webhook.lastAttemptAt,
        )

    private companion object {
        const val MAX_MESSAGE_LENGTH = 300
    }
}

data class UpdateMyNotificationsRequest(
    val email: String? = null,
    val notifyEmail: Boolean = true,
)

data class PushTokenRequest(
    val token: String = "",
)

/** Each person's own notification choices. */
@RestController
@RequestMapping("/api/v1/me")
class MyNotificationController(
    private val service: NotificationSettingsService,
) {
    @GetMapping("/notifications")
    suspend fun mine(
        @AuthenticationPrincipal principal: AuthenticatedUser,
    ): MyNotificationSettings = service.mine(principal.id)

    /** Replaces both values: the address (blank or null clears it) and whether mail is wanted. */
    @PatchMapping("/notifications")
    suspend fun update(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @RequestBody body: UpdateMyNotificationsRequest,
    ): MyNotificationSettings = service.updateMine(principal.id, body.email, body.notifyEmail)

    /** Registers this browser for push. The token goes in the body, never in the address. */
    @PostMapping("/push-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun register(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @RequestBody body: PushTokenRequest,
        exchange: ServerWebExchange,
    ) {
        service.registerPushToken(principal.id, body.token, ClientInfo.from(exchange).userAgent)
    }

    @PostMapping("/push-tokens/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unregister(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @RequestBody body: PushTokenRequest,
    ) {
        service.unregisterPushToken(principal.id, body.token)
    }
}
