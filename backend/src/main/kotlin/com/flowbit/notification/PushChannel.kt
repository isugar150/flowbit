package com.flowbit.notification

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** The parts of a Firebase service account key file that sending needs. */
class ServiceAccount(
    val projectId: String,
    val clientEmail: String,
    val privateKey: RSAPrivateKey,
    val tokenUri: String,
) {
    companion object {
        /** Null when [json] is not a usable service account key file. */
        fun parse(
            json: String,
            mapper: JsonMapper,
        ): ServiceAccount? =
            runCatching {
                val node = mapper.readTree(json)

                fun text(name: String) = node.get(name)?.asString()?.takeIf { it.isNotBlank() } ?: error("missing $name")
                val pem = text("private_key").replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
                ServiceAccount(text("project_id"), text("client_email"), key as RSAPrivateKey, text("token_uri"))
            }.getOrNull()
    }
}

enum class PushResult {
    SENT,

    /** The browser's registration is gone; the token should be forgotten. */
    TOKEN_GONE,
    FAILED,
}

/**
 * Sends web push messages through the Firebase Cloud Messaging HTTP v1 API. The server proves who
 * it is with a short-lived access token it gets by signing a JWT with the service account's key.
 */
@Component
class FcmClient(
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
    @Value("\${flowbit.notifications.fcm-url}") private val fcmUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = WebClient.create()

    private class AccessToken(
        val clientEmail: String,
        val value: String,
        val expiresAt: Instant,
    )

    @Volatile
    private var cached: AccessToken? = null

    suspend fun send(
        account: ServiceAccount,
        token: String,
        title: String,
        body: String,
        tag: String,
        path: String,
    ): PushResult {
        val message =
            mapOf(
                "message" to
                    mapOf(
                        "token" to token,
                        // The page's service worker (public/push-sw.js) shows this and opens `path` on click.
                        "webpush" to mapOf("notification" to mapOf("title" to title, "body" to body, "tag" to tag)),
                        "data" to mapOf("path" to path),
                    ),
            )
        val accessToken = accessToken(account)
        val status =
            http
                .post()
                .uri("${fcmUrl.trimEnd('/')}/v1/projects/${account.projectId}/messages:send")
                .headers { it.setBearerAuth(accessToken) }
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonMapper.writeValueAsString(message))
                .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
                .timeout(TIMEOUT)
                .awaitSingle()
        return when (status) {
            in 200..299 -> PushResult.SENT
            // 404 UNREGISTERED, 400 INVALID_ARGUMENT (a malformed token), 403 SENDER_ID_MISMATCH (another project's token).
            400, 403, 404 -> PushResult.TOKEN_GONE
            else -> PushResult.FAILED.also { log.warn("FCM answered {}", status) }
        }
    }

    private suspend fun accessToken(account: ServiceAccount): String {
        val now = clock.instant()
        cached?.takeIf { it.clientEmail == account.clientEmail && it.expiresAt.isAfter(now.plus(EXPIRY_MARGIN)) }?.let { return it.value }

        // The assertion's times are checked by Google against real time, not the application clock.
        val issuedAt = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(account.clientEmail)
                .audience(account.tokenUri)
                .claim("scope", SCOPE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(ASSERTION_LIFETIME)))
                .build()
        val assertion = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).apply { sign(RSASSASigner(account.privateKey)) }.serialize()
        val response =
            http
                .post()
                .uri(account.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", GRANT_TYPE).with("assertion", assertion))
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(TIMEOUT)
                .awaitSingle()
        val value = response["access_token"] as? String ?: error("The token response has no access_token")
        val lifetime = (response["expires_in"] as? Number)?.toLong() ?: ASSERTION_LIFETIME.seconds
        cached = AccessToken(account.clientEmail, value, now.plusSeconds(lifetime))
        return value
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val ASSERTION_LIFETIME: Duration = Duration.ofMinutes(55)
        val EXPIRY_MARGIN: Duration = Duration.ofMinutes(1)
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        val JSON_OBJECT = object : ParameterizedTypeReference<Map<String, Any?>>() {}
    }
}

/** Pushes to every browser the subscribers registered. Registering a browser is the opt-in. */
@Component
class PushNotificationChannel(
    private val settingsService: NotificationSettingsService,
    private val tokens: PushTokenRepository,
    private val fcm: FcmClient,
    private val texts: NotificationText,
    private val jsonMapper: JsonMapper,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(notification: IssueNotification) {
        if (notification.recipientIds.isEmpty()) return
        val message = texts.of(notification)
        val body = (listOf(message.headline) + message.lines).joinToString("\n")
        pushTo(notification.recipientIds, message.title, body, notification.issueKey, message.path)
    }

    override suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {
        val message = texts.of(request)
        if (request.recipientIds.isEmpty()) return
        val body = (listOf(message.headline) + message.lines).joinToString("\n")
        pushTo(request.recipientIds, message.title, body, "access-${request.projectId}", message.path)
    }

    /** Returns how many browsers were reached; forgets the ones whose registration is gone. */
    suspend fun pushTo(
        userIds: Collection<UUID>,
        title: String,
        body: String,
        tag: String,
        path: String,
    ): Int {
        val settings = settingsService.settings()
        if (!settings.pushEnabled) return 0
        val account = ServiceAccount.parse(settings.fcmServiceAccount, jsonMapper) ?: return 0
        var sent = 0
        for (token in tokens.findByUserIds(userIds).toList()) {
            try {
                when (fcm.send(account, token.token, title, body, tag, path)) {
                    PushResult.SENT -> sent += 1
                    PushResult.TOKEN_GONE -> tokens.deleteByToken(token.token)
                    PushResult.FAILED -> {}
                }
            } catch (e: Exception) {
                log.warn("Could not push the notification {}: {}", tag, e.message)
            }
        }
        return sent
    }
}
