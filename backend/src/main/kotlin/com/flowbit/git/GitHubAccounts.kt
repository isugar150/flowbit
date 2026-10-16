package com.flowbit.git

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.PublicUrl
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The member's own GitHub account, as their settings show it. */
data class MyGitHubAccount(
    /** Whether there is a GitHub App to connect through; without one the settings show nothing (D-145). */
    val available: Boolean,
    /** The connected account's login, or null. */
    val login: String?,
    val linkedAt: Instant?,
)

data class GitHubAuthorizeResponse(
    /** Where the browser goes to let GitHub ask the member. */
    val url: String,
)

data class GitHubAccountCallbackRequest(
    val code: String = "",
    val state: String = "",
)

/**
 * Members connect their own GitHub account in their settings (D-150), through the GitHub App's
 * user authorization: GitHub asks them, sends the browser back with a code, and the code tells
 * who they are on GitHub. Only the login and id are kept; the user token is dropped at once.
 * GitHub events by that login are then theirs, when the e-mail address does not tell.
 */
@Service
class GitHubAccountService(
    private val connections: GitConnectionRepository,
    private val client: GitHubClient,
    private val databaseClient: DatabaseClient,
    private val publicUrl: PublicUrl,
    private val clock: Clock,
) {
    private data class Pending(
        val userId: UUID,
        val connectionId: UUID,
        val redirectUri: String,
        val expiresAt: Instant,
    )

    private val random = SecureRandom()

    /** States of authorizations under way. Kept in memory: Flowbit runs as a single instance. */
    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun mine(user: AuthenticatedUser): MyGitHubAccount {
        val account = accountOf(user.id)
        return MyGitHubAccount(appToConnectThrough() != null, account?.first, account?.second)
    }

    /** Where to send the browser; [origin] is where it has Flowbit, when FLOWBIT_PUBLIC_URL is not set. */
    suspend fun authorize(
        user: AuthenticatedUser,
        origin: String?,
    ): GitHubAuthorizeResponse {
        val connection = appToConnectThrough() ?: throw ConflictException("GITHUB_APP_MISSING", "연결할 GitHub 앱이 없습니다. 관리자에게 문의하세요")
        val base =
            publicUrl.base ?: origin?.trim()?.trimEnd('/')?.takeIf(::isHttpUrl) ?: throw ValidationException("origin", "REQUIRED", "Flowbit 주소를 알 수 없습니다")
        val redirectUri = base + GitHubAppService.GITHUB_ACCOUNT_CALLBACK
        val now = clock.instant()
        pending.entries.removeIf { it.value.expiresAt.isBefore(now) }
        val state = HexFormat.of().formatHex(ByteArray(STATE_BYTES).also(random::nextBytes))
        pending[state] = Pending(user.id, connection.id, redirectUri, now.plus(STATE_LIFETIME))
        val query =
            listOf("client_id" to checkNotNull(connection.githubClientId), "redirect_uri" to redirectUri, "state" to state, "allow_signup" to "false")
                .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return GitHubAuthorizeResponse("${connection.baseUrl.trimEnd('/')}/login/oauth/authorize?$query")
    }

    /** GitHub sent the browser back: keeps who the member is on GitHub. */
    suspend fun complete(
        user: AuthenticatedUser,
        code: String,
        state: String,
    ): MyGitHubAccount {
        val started =
            pending.remove(state)?.takeIf { it.userId == user.id && it.expiresAt.isAfter(clock.instant()) }
                ?: throw ValidationException("state", "INVALID", "GitHub 계정 연결 요청을 찾을 수 없습니다. 처음부터 다시 하세요")
        val connection = connections.findById(started.connectionId) ?: throw ValidationException("state", "INVALID", "GitHub 앱이 없어졌습니다")
        val github =
            try {
                client.authorizedUser(connection, code, started.redirectUri)
            } catch (e: GitHubException) {
                throw GitHubUnavailableException(e.message ?: "GitHub 계정을 확인하지 못했습니다")
            }
        try {
            databaseClient
                .sql(
                    """
                    insert into git_github_accounts (user_id, connection_id, github_login, github_user_id, linked_at)
                    values (:user, :connection, :login, :githubId, :now)
                    on conflict (user_id) do update set connection_id = excluded.connection_id, github_login = excluded.github_login,
                        github_user_id = excluded.github_user_id, linked_at = excluded.linked_at
                    """.trimIndent(),
                ).bind("user", user.id)
                .bind("connection", connection.id)
                .bind("login", github.login.lowercase())
                .bind("githubId", github.id)
                .bind("now", clock.instant())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("GITHUB_ACCOUNT_TAKEN", "이 GitHub 계정은 다른 구성원이 이미 연결했습니다")
        }
        return mine(user)
    }

    suspend fun unlink(user: AuthenticatedUser) {
        databaseClient
            .sql("delete from git_github_accounts where user_id = :id")
            .bind("id", user.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The member who connected this GitHub login on the server of [connection]; null when nobody did. */
    suspend fun userIdOf(
        connection: GitConnection,
        login: String,
    ): UUID? =
        databaseClient
            .sql(
                """
                select a.user_id from git_github_accounts a join git_connections c on c.id = a.connection_id
                where a.github_login = :login and c.base_url = :base
                limit 1
                """.trimIndent(),
            ).bind("login", login.trim().lowercase())
            .bind("base", connection.baseUrl)
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) }
            .one()
            .awaitSingleOrNull()

    /** The first switched-on GitHub App that can authorize users: it has its client id and secret. */
    private suspend fun appToConnectThrough(): GitConnection? =
        connections.findAllOrdered().toList().firstOrNull {
            it.enabled && it.provider == GitProviderType.GITHUB && it.githubClientId != null && it.githubClientSecret != null
        }

    private suspend fun accountOf(userId: UUID): Pair<String, Instant>? =
        databaseClient
            .sql("select github_login, linked_at from git_github_accounts where user_id = :id")
            .bind("id", userId)
            .map { row ->
                checkNotNull(row.get("github_login", String::class.java)) to
                    checkNotNull(row.get("linked_at", java.time.OffsetDateTime::class.java)).toInstant()
            }.one()
            .awaitSingleOrNull()

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val STATE_BYTES = 24
        val STATE_LIFETIME: Duration = Duration.ofMinutes(10)
    }
}

/** The signed-in member's own GitHub account. */
@RestController
@RequestMapping("/api/v1/me/git/github")
class MyGitHubAccountController(
    private val service: GitHubAccountService,
) {
    @GetMapping
    suspend fun mine(
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): MyGitHubAccount = service.mine(user)

    @PostMapping("/authorize")
    suspend fun authorize(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) origin: String?,
    ): GitHubAuthorizeResponse = service.authorize(user, origin)

    @PostMapping("/callback")
    suspend fun callback(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: GitHubAccountCallbackRequest,
    ): MyGitHubAccount = service.complete(user, body.code.trim(), body.state.trim())

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unlink(
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) {
        service.unlink(user)
    }
}
