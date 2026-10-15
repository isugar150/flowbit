package com.flowbit.git

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A GitHub API call that did not work out; the message is shown to the administrator as it is. */
class GitHubException(
    message: String,
) : RuntimeException(message)

/** What GitHub answered. [body] is null for 304 (nothing changed since [etag]) and for an empty answer. */
data class GitHubResponse(
    val status: Int,
    val body: JsonNode?,
    val etag: String?,
    val rateLimitRemaining: Int?,
)

/** What GitHub hands over once an app has been made from a manifest. */
data class GitHubAppCredentials(
    val id: Long,
    val slug: String,
    val clientId: String?,
    val clientSecret: String?,
    val webhookSecret: String?,
    val pem: String,
)

/**
 * Calls the GitHub API for a GitHub App (docs/features/git-integration.md "GitHub: GitHub App 으로
 * 연결"): signs app JWTs with the app's private key, turns them into installation tokens (kept in
 * memory until shortly before they expire) and reads with those. github.com is reached at
 * api.github.com; a GitHub Enterprise Server at `<base>/api/v3`.
 */
@Component
class GitHubClient(
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    private val http = WebClient.create()
    private val tokens = ConcurrentHashMap<UUID, Pair<String, Instant>>()
    private val remaining = ConcurrentHashMap<UUID, Int>()

    /** What GitHub last said is left of the installation's hourly calls; null before any call. */
    fun rateLimitRemaining(connectionId: UUID): Int? = remaining[connectionId]

    /** Where the API of the GitHub at [baseUrl] is. */
    fun apiUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (runCatching { URI(base).host }.getOrNull() == "github.com") "https://api.github.com" else "$base/api/v3"
    }

    /** Exchanges the code GitHub sent back after the app was made for the app's id, keys and secrets. */
    suspend fun convertManifest(
        baseUrl: String,
        code: String,
    ): GitHubAppCredentials {
        val response = send(HttpMethod.POST, "${apiUrl(baseUrl)}/app-manifests/$code/conversions", null, null, "{}")
        val body = response.body
        if (response.status != 201 || body == null) throw GitHubException("GitHub 앱 정보를 받지 못했습니다 (HTTP ${response.status}). 1시간 안에 다시 만들어 보세요")
        return GitHubAppCredentials(
            id = body.path("id").asLong(0).takeIf { it > 0 } ?: throw GitHubException("GitHub 앱 ID 가 없습니다"),
            slug = text(body, "slug") ?: throw GitHubException("GitHub 앱 이름(slug)이 없습니다"),
            clientId = text(body, "client_id"),
            clientSecret = text(body, "client_secret"),
            webhookSecret = text(body, "webhook_secret"),
            pem = text(body, "pem") ?: throw GitHubException("GitHub 앱의 비공개 키가 없습니다"),
        )
    }

    /** The app's installation on the connection's organization; null when it is not installed there. */
    suspend fun organizationInstallation(connection: GitConnection): JsonNode? {
        val organization = connection.githubOrganization ?: return null
        val response = send(HttpMethod.GET, "${apiUrl(connection.baseUrl)}/orgs/$organization/installation", "Bearer ${appJwt(connection)}", null, null)
        return when (response.status) {
            200 -> response.body
            404 -> null
            else -> throw GitHubException("설치 정보를 읽지 못했습니다 (HTTP ${response.status})")
        }
    }

    /**
     * A GET with the installation's token. With [etag], GitHub answers 304 when nothing changed,
     * which does not count against the rate limit.
     */
    suspend fun get(
        connection: GitConnection,
        path: String,
        etag: String? = null,
    ): GitHubResponse {
        val url = if (path.startsWith("http")) path else "${apiUrl(connection.baseUrl)}$path"
        val response = send(HttpMethod.GET, url, "Bearer ${installationToken(connection)}", etag, null)
        response.rateLimitRemaining?.let { remaining[connection.id] = it }
        if (response.status == 401) tokens.remove(connection.id)
        if (response.status != 200 && response.status != 304) {
            val limited = response.status == 403 && response.rateLimitRemaining == 0
            throw GitHubException(if (limited) "GitHub API 호출 한도에 걸렸습니다. 다음 동기화 때 다시 시도합니다" else "GitHub API 오류 (HTTP ${response.status}): $path")
        }
        return response
    }

    /** Forgets the installation token, for a connection that changed or went away. */
    fun forget(connectionId: UUID) {
        tokens.remove(connectionId)
    }

    private suspend fun installationToken(connection: GitConnection): String {
        val now = clock.instant()
        tokens[connection.id]?.let { (token, expiresAt) -> if (expiresAt.isAfter(now.plus(TOKEN_MARGIN))) return token }
        val installationId = connection.githubInstallationId ?: throw GitHubException("GitHub 앱이 조직에 설치되지 않았습니다")
        val response =
            send(HttpMethod.POST, "${apiUrl(connection.baseUrl)}/app/installations/$installationId/access_tokens", "Bearer ${appJwt(connection)}", null, "{}")
        val body = response.body
        if (response.status != 201 || body == null) throw GitHubException("설치 토큰을 받지 못했습니다 (HTTP ${response.status})")
        val token = text(body, "token") ?: throw GitHubException("설치 토큰이 비어 있습니다")
        val expiresAt = instant(text(body, "expires_at")) ?: now.plus(Duration.ofMinutes(55))
        tokens[connection.id] = token to expiresAt
        return token
    }

    /** RS256, issued a minute in the past for clock drift and good for nine minutes (GitHub allows ten). */
    fun appJwt(connection: GitConnection): String {
        val issuer = connection.githubClientId ?: connection.githubAppId?.toString() ?: throw GitHubException("GitHub 앱이 아직 없습니다")
        val key = privateKey(connection.githubPrivateKey ?: throw GitHubException("GitHub 앱의 비공개 키가 없습니다"))
        val now = clock.instant().epochSecond
        val header = encode(jsonMapper.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT")))
        val payload = encode(jsonMapper.writeValueAsBytes(mapOf("iat" to now - 60, "exp" to now + 9 * 60, "iss" to issuer)))
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update("$header.$payload".toByteArray())
                sign()
            }
        return "$header.$payload.${encode(signature)}"
    }

    private suspend fun send(
        method: HttpMethod,
        url: String,
        authorization: String?,
        etag: String?,
        body: String?,
    ): GitHubResponse =
        try {
            http
                .method(method)
                .uri(URI(url))
                .accept(MediaType.parseMediaType("application/vnd.github+json"))
                .headers { headers ->
                    headers.set("X-GitHub-Api-Version", API_VERSION)
                    headers.set("User-Agent", "Flowbit")
                    authorization?.let { headers.set("Authorization", it) }
                    etag?.let { headers.set("If-None-Match", it) }
                }.let { spec -> if (body != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body) else spec }
                .exchangeToMono { response ->
                    response.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0)).map { bytes ->
                        GitHubResponse(
                            status = response.statusCode().value(),
                            body = if (bytes.isEmpty()) null else runCatching { jsonMapper.readTree(bytes) }.getOrNull(),
                            etag = response.headers().asHttpHeaders().getFirst("ETag"),
                            rateLimitRemaining =
                                response
                                    .headers()
                                    .asHttpHeaders()
                                    .getFirst("X-RateLimit-Remaining")
                                    ?.toIntOrNull(),
                        )
                    }
                }.timeout(TIMEOUT)
                .awaitSingle()
        } catch (e: GitHubException) {
            throw e
        } catch (e: Exception) {
            throw GitHubException("GitHub 에 연결하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val API_VERSION = "2022-11-28"
        private val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** A token is made again this long before it runs out. */
        private val TOKEN_MARGIN: Duration = Duration.ofMinutes(5)

        /**
         * Reads a PEM private key: PKCS#8 (`BEGIN PRIVATE KEY`) as Java wants it, or PKCS#1
         * (`BEGIN RSA PRIVATE KEY`), which is what GitHub hands out, wrapped into PKCS#8 here.
         */
        fun privateKey(pem: String): PrivateKey {
            val pkcs1 = "RSA PRIVATE KEY" in pem
            val der =
                Base64.getMimeDecoder().decode(
                    pem
                        .lineSequence()
                        .filterNot { it.startsWith("-----") }
                        .joinToString(""),
                )
            val pkcs8 = if (pkcs1) wrapPkcs1(der) else der
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw GitHubException("GitHub 앱의 비공개 키를 읽을 수 없습니다")
            }
        }

        /** PrivateKeyInfo { version 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1 }. */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
            val key = der(0x04, pkcs1)
            return der(0x30, version + algorithm + key)
        }

        private fun der(
            tag: Int,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            when {
                length < 0x80 -> out.write(length)
                length < 0x100 -> {
                    out.write(0x81)
                    out.write(length)
                }
                length < 0x10000 -> {
                    out.write(0x82)
                    out.write(length shr 8)
                    out.write(length and 0xff)
                }
                else -> {
                    out.write(0x83)
                    out.write(length shr 16)
                    out.write((length shr 8) and 0xff)
                    out.write(length and 0xff)
                }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
