package com.flowbit.notification

import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.common.error.ValidationException
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.UserRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.util.UUID

/** A null password keeps the stored one. */
data class SmtpInput(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val username: String,
    val password: String?,
    val fromAddress: String,
    val fromName: String,
)

/** A null service account keeps the stored one. */
data class PushInput(
    val enabled: Boolean,
    val serviceAccount: String?,
    val apiKey: String,
    val messagingSenderId: String,
    val appId: String,
    val vapidKey: String,
)

/** A null signing secret keeps the stored one (for a new webhook: none). */
data class WebhookInput(
    val name: String,
    val enabled: Boolean,
    val url: String,
    val method: String,
    val contentType: String,
    val headers: Map<String, String>,
    val bodyTemplate: String,
    val signingSecret: String?,
    val eventTypes: List<String>,
)

/** What a browser needs to register for push: the Firebase web app's public configuration. */
data class PushClientConfig(
    val apiKey: String,
    val projectId: String,
    val messagingSenderId: String,
    val appId: String,
    val vapidKey: String,
)

data class MyNotificationSettings(
    val email: String?,
    val notifyEmail: Boolean,
    /** Whether an administrator switched mail on at all. */
    val mailAvailable: Boolean,
    /** Null when push is switched off. */
    val push: PushClientConfig?,
)

data class TestResult(
    val ok: Boolean,
    val message: String,
)

/**
 * Settings of the notification channels and each person's own choices
 * (docs/features/M7a-notification-channels.md). The administrator functions are only reached
 * through `/api/v1/admin`. Passwords, keys and signing secrets are stored as they are and never
 * returned to a client.
 */
@Service
class NotificationSettingsService(
    private val repository: NotificationSettingsRepository,
    private val webhookRepository: WebhookRepository,
    private val pushTokens: PushTokenRepository,
    private val userRepository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    suspend fun settings(): NotificationSettings = checkNotNull(repository.findById(NotificationSettings.SINGLETON_ID))

    // --- mail -----------------------------------------------------------------------------------

    suspend fun updateSmtp(input: SmtpInput): NotificationSettings {
        val settings = settings().apply { apply(input) }
        if (settings.smtpEnabled) smtpErrors(settings).takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        settings.updatedAt = clock.instant()
        return repository.save(settings)
    }

    /** The stored settings with [input] laid over them, checked but not saved: what a test mail is sent with. */
    suspend fun smtpDraft(input: SmtpInput): NotificationSettings {
        val settings = settings().apply { apply(input) }
        smtpErrors(settings).takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        return settings
    }

    private fun NotificationSettings.apply(input: SmtpInput) {
        smtpEnabled = input.enabled
        smtpHost = input.host.trim()
        smtpPort = input.port
        smtpSecurity = input.security
        smtpUsername = input.username.trim()
        input.password?.let { smtpPassword = it }
        smtpFromAddress = input.fromAddress.trim().lowercase()
        smtpFromName = input.fromName.trim()
    }

    private fun smtpErrors(settings: NotificationSettings): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (!HOST.matches(settings.smtpHost)) errors += FieldError("host", "INVALID", "메일 서버 주소를 입력하세요 (예: smtp.example.com)")
        if (settings.smtpPort !in 1..65535) errors += FieldError("port", "INVALID", "포트는 1~65535 사이여야 합니다")
        if (!CredentialPolicy.isEmail(settings.smtpFromAddress)) errors += FieldError("fromAddress", "INVALID", "보내는 사람의 이메일 주소를 입력하세요")
        if (settings.smtpFromName.length > NAME_MAX_LENGTH || settings.smtpFromName.any { it == '\r' || it == '\n' }) {
            errors += FieldError("fromName", "INVALID", "보내는 사람 이름은 ${NAME_MAX_LENGTH}자 이하의 한 줄이어야 합니다")
        }
        return errors
    }

    // --- push -----------------------------------------------------------------------------------

    suspend fun updatePush(input: PushInput): NotificationSettings {
        val settings =
            settings().apply {
                pushEnabled = input.enabled
                input.serviceAccount?.let { fcmServiceAccount = it.trim() }
                fcmApiKey = input.apiKey.trim()
                fcmMessagingSenderId = input.messagingSenderId.trim()
                fcmAppId = input.appId.trim()
                fcmVapidKey = input.vapidKey.trim()
            }
        if (settings.pushEnabled) {
            val errors = mutableListOf<FieldError>()
            if (ServiceAccount.parse(settings.fcmServiceAccount, jsonMapper) == null) {
                errors += FieldError("serviceAccount", "INVALID", "Firebase 서비스 계정 키(JSON 파일의 내용 전체)를 붙여 넣으세요")
            }
            listOf(
                "apiKey" to settings.fcmApiKey,
                "messagingSenderId" to settings.fcmMessagingSenderId,
                "appId" to settings.fcmAppId,
                "vapidKey" to settings.fcmVapidKey,
            ).forEach { (field, value) -> if (value.isEmpty()) errors += FieldError(field, "REQUIRED", "값을 입력하세요") }
            if (errors.isNotEmpty()) throw ValidationException(errors)
        }
        settings.updatedAt = clock.instant()
        return repository.save(settings)
    }

    /** The project the stored service account belongs to, for showing next to the settings. */
    fun projectIdOf(settings: NotificationSettings): String? = ServiceAccount.parse(settings.fcmServiceAccount, jsonMapper)?.projectId

    private fun pushClientConfig(settings: NotificationSettings): PushClientConfig? {
        if (!settings.pushEnabled) return null
        val projectId = projectIdOf(settings) ?: return null
        return PushClientConfig(settings.fcmApiKey, projectId, settings.fcmMessagingSenderId, settings.fcmAppId, settings.fcmVapidKey)
    }

    // --- each person's own choices --------------------------------------------------------------

    suspend fun mine(userId: UUID): MyNotificationSettings {
        val user = userRepository.findById(userId) ?: throw UnauthenticatedException()
        val settings = settings()
        return MyNotificationSettings(user.email, user.notifyEmail, settings.smtpEnabled, pushClientConfig(settings))
    }

    /** Sets the address mail goes to (blank clears it) and whether mail is wanted at all. */
    suspend fun updateMine(
        userId: UUID,
        rawEmail: String?,
        notifyEmail: Boolean,
    ): MyNotificationSettings {
        val email = rawEmail?.let(CredentialPolicy::normalizeLoginId)?.takeIf { it.isNotEmpty() }
        if (email != null) {
            if (!CredentialPolicy.isEmail(email)) throw ValidationException("email", "INVALID", "이메일 주소 형식이 올바르지 않습니다")
            // An address is also something people sign in with, so it cannot be someone else's.
            if (userRepository.isUsedByAnother(email, userId)) throw ConflictException("EMAIL_TAKEN", "다른 구성원이 쓰고 있는 이메일 주소입니다")
        }
        userRepository.updateEmailSettings(userId, email, notifyEmail, clock.instant())
        return mine(userId)
    }

    suspend fun registerPushToken(
        userId: UUID,
        token: String,
        userAgent: String?,
    ) {
        if (token.isBlank() || token.length > TOKEN_MAX_LENGTH) throw ValidationException("token", "INVALID", "푸시 토큰이 올바르지 않습니다")
        pushTokens.upsert(token, userId, userAgent, clock.instant())
    }

    suspend fun unregisterPushToken(
        userId: UUID,
        token: String,
    ) {
        pushTokens.deleteOwned(token, userId)
    }

    // --- webhooks -------------------------------------------------------------------------------

    suspend fun webhooks(): List<Webhook> = webhookRepository.findAllOrdered().toList()

    suspend fun createWebhook(input: WebhookInput): Webhook = entityTemplate.insert(checked(input, null)).awaitSingle()

    suspend fun updateWebhook(
        id: UUID,
        input: WebhookInput,
    ): Webhook {
        val stored = webhookRepository.findById(id) ?: throw webhookNotFound()
        val draft = checked(input, stored)
        stored.apply {
            name = draft.name
            enabled = draft.enabled
            url = draft.url
            method = draft.method
            contentType = draft.contentType
            headers = draft.headers
            bodyTemplate = draft.bodyTemplate
            signingSecret = draft.signingSecret
            eventTypes = draft.eventTypes
            updatedAt = clock.instant()
        }
        return webhookRepository.save(stored)
    }

    suspend fun deleteWebhook(id: UUID) {
        if (!webhookRepository.existsById(id)) throw webhookNotFound()
        webhookRepository.deleteById(id)
    }

    /** [input] as a checked webhook that is not stored: what a test call is made with. */
    suspend fun draftWebhook(
        input: WebhookInput,
        storedId: UUID?,
    ): Webhook = checked(input, storedId?.let { webhookRepository.findById(it) ?: throw webhookNotFound() })

    /** Validates [input]. [stored] supplies the id, the dates and, when the input leaves it out, the signing secret. */
    private fun checked(
        input: WebhookInput,
        stored: Webhook?,
    ): Webhook {
        val errors = mutableListOf<FieldError>()
        val name = input.name.trim()
        if (name.isEmpty() || name.length > NAME_MAX_LENGTH) errors += FieldError("name", "INVALID", "이름은 1~${NAME_MAX_LENGTH}자여야 합니다")
        val url = input.url.trim()
        // Placeholders are filled in per call; what matters here is that the rest is an address.
        val uri = runCatching { URI(WebhookSender.PLACEHOLDER.replace(url, "x")) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || uri.scheme?.lowercase() !in setOf("http", "https")) {
            errors += FieldError("url", "INVALID", "http:// 또는 https:// 로 시작하는 주소를 입력하세요")
        }
        val method = input.method.trim().uppercase()
        if (method !in METHODS) errors += FieldError("method", "INVALID", "메서드는 GET, POST, PUT, PATCH, DELETE 중 하나여야 합니다")
        val contentType = input.contentType.trim().ifEmpty { DEFAULT_CONTENT_TYPE }
        if (!CONTENT_TYPE.matches(contentType)) errors += FieldError("contentType", "INVALID", "Content-Type 형식이 올바르지 않습니다 (예: application/json)")
        val headers = input.headers.mapKeys { it.key.trim() }.filterKeys { it.isNotEmpty() }
        WebhookHeaders.problem(headers)?.let { errors += FieldError("headers", "INVALID", it) }
        if (input.bodyTemplate.length > TEMPLATE_MAX_LENGTH) errors += FieldError("bodyTemplate", "TOO_LONG", "본문 템플릿이 너무 깁니다")
        val unknown = input.eventTypes.filter { it !in EVENT_TYPES }
        if (unknown.isNotEmpty()) errors += FieldError("eventTypes", "INVALID", "알 수 없는 변경 종류입니다: ${unknown.joinToString()}")
        if (errors.isNotEmpty()) throw ValidationException(errors)

        val now = clock.instant()
        return Webhook(
            id = stored?.id ?: UuidV7.generate(now),
            name = name,
            enabled = input.enabled,
            url = url,
            method = method,
            contentType = contentType,
            headers = jsonMapper.writeValueAsString(headers),
            bodyTemplate = input.bodyTemplate,
            signingSecret = input.signingSecret ?: stored?.signingSecret.orEmpty(),
            eventTypes = input.eventTypes.distinct().joinToString(","),
            lastStatus = stored?.lastStatus,
            lastError = stored?.lastError,
            lastAttemptAt = stored?.lastAttemptAt,
            createdAt = stored?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun webhookNotFound() = NotFoundException("WEBHOOK_NOT_FOUND", "API 알림을 찾을 수 없습니다")

    companion object {
        /** The kinds of change a webhook can ask for; the names of IssueActivityType. */
        val EVENT_TYPES =
            listOf(
                "CREATED",
                "TITLE_CHANGED",
                "DESCRIPTION_CHANGED",
                "STATUS_CHANGED",
                "PRIORITY_CHANGED",
                "ASSIGNEE_CHANGED",
                "DUE_DATE_CHANGED",
                "LABELS_CHANGED",
                "PROJECT_CHANGED",
                "PARENT_CHANGED",
                "ATTACHMENT_ADDED",
                "ATTACHMENT_REMOVED",
                "DOCUMENT_ADDED",
                "DOCUMENT_REMOVED",
                "DELETED",
                "RESTORED",
                "COMMENT_ADDED",
                "MENTIONED",
                ProjectAccessRequestNotification.TYPE,
            ) + IssueNotification.OPT_IN_TYPES
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private const val DEFAULT_CONTENT_TYPE = "application/json"
        private const val NAME_MAX_LENGTH = 60
        private const val TEMPLATE_MAX_LENGTH = 20_000
        private const val TOKEN_MAX_LENGTH = 4096
        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        private val CONTENT_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(\\s*;\\s*[A-Za-z0-9_-]+=[A-Za-z0-9_.\"-]+)*$")
    }
}
