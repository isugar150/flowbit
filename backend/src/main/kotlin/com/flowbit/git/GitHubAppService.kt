package com.flowbit.git

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ApiException
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import kotlinx.coroutines.flow.toList
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** What the browser posts to GitHub to make the app: the form's address and its `manifest` field. */
data class GitHubManifestForm(
    val action: String,
    val manifest: String,
)

/** GitHub could not be reached or refused; shown to the administrator. */
class GitHubUnavailableException(
    message: String,
) : ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", message)

/**
 * Making a GitHub App for a connection and finding where it is installed
 * (docs/features/git-integration.md "GitHub: GitHub App 으로 연결", D-146). The app is made from a
 * manifest: the administrator's browser posts it to GitHub, GitHub sends the browser back with a
 * code, and the code is exchanged here for the app's id and keys. Nothing of this needs Flowbit to
 * be reachable from the internet; only the webhook does.
 */
@Service
class GitHubAppService(
    private val repository: GitConnectionRepository,
    private val client: GitHubClient,
    private val publicUrl: PublicUrl,
    private val auditService: AuditService,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /**
     * The form that makes the app on GitHub. [origin] is the address the administrator's browser
     * has Flowbit at, used for GitHub's redirects when FLOWBIT_PUBLIC_URL is not set.
     */
    suspend fun manifestForm(
        id: UUID,
        origin: String?,
    ): GitHubManifestForm {
        val connection = github(id)
        if (connection.githubAppId != null) throw ConflictException("GITHUB_APP_EXISTS", "이 연결에는 이미 GitHub 앱이 있습니다")
        val base =
            publicUrl.base ?: origin?.trim()?.trimEnd('/')?.takeIf(::isHttpUrl) ?: throw ValidationException("origin", "REQUIRED", "Flowbit 주소를 알 수 없습니다")
        val organization = checkNotNull(connection.githubOrganization)
        val state = HexFormat.of().formatHex(ByteArray(STATE_BYTES).also(random::nextBytes))
        connection.githubSetupState = state
        connection.updatedAt = clock.instant()
        repository.save(connection)
        val webhook = connection.receiveMode == GitReceiveMode.WEBHOOK
        val manifest =
            mapOf(
                // GitHub app names are unique across GitHub and 34 characters at most; it can be changed on GitHub's page.
                "name" to "Flowbit ${connection.name}".take(APP_NAME_MAX_LENGTH),
                "url" to base,
                "hook_attributes" to mapOf("url" to base + GitConnectionController.webhookPath(connection.id), "active" to webhook),
                "redirect_url" to "$base/admin/git/github/created",
                "setup_url" to "$base/admin/git/github/installed",
                "setup_on_update" to true,
                "public" to false,
                "default_permissions" to mapOf("contents" to "read", "pull_requests" to "read", "metadata" to "read"),
                "default_events" to if (webhook) listOf("push", "pull_request") else emptyList(),
            )
        val web = connection.baseUrl.trimEnd('/')
        return GitHubManifestForm("$web/organizations/$organization/settings/apps/new?state=$state", jsonMapper.writeValueAsString(manifest))
    }

    /**
     * GitHub sent the browser back after the app was made: [code] is exchanged for the app's id
     * and keys, which are kept on the connection [state] belongs to. Then looks for an installation
     * (there is none yet, as a rule).
     */
    suspend fun complete(
        code: String,
        state: String,
        actorId: UUID,
        clientInfo: ClientInfo,
    ): GitConnection {
        val connection =
            state.takeIf { it.isNotBlank() }?.let { repository.findByGithubSetupState(it) }
                ?: throw ValidationException("state", "INVALID", "GitHub 앱 만들기 요청을 찾을 수 없습니다. 처음부터 다시 하세요")
        val credentials = unavailable { client.convertManifest(connection.baseUrl, code) }
        connection.apply {
            githubAppId = credentials.id
            githubAppSlug = credentials.slug
            githubClientId = credentials.clientId
            githubClientSecret = credentials.clientSecret
            githubPrivateKey = credentials.pem
            // Signs the app's webhook calls; without one (no webhook) the made-up secret stays.
            credentials.webhookSecret?.let { secret = it }
            githubSetupState = null
            updatedAt = clock.instant()
        }
        repository.save(connection)
        auditService.record(
            AuditEventType.GIT_CONNECTION_CHANGED,
            actorId,
            clientInfo,
            mapOf("id" to connection.id.toString(), "githubApp" to credentials.slug),
        )
        return refresh(connection)
    }

    /** Reads again where every GitHub connection's app is installed; for the page GitHub sends back to after installing. */
    suspend fun refreshAll(): List<GitConnection> =
        repository
            .findAllOrdered()
            .toList()
            .filter { it.provider == GitProviderType.GITHUB && it.githubAppId != null }
            .map { connection -> runCatching { refresh(connection) }.getOrDefault(connection) }

    suspend fun refresh(id: UUID): GitConnection = refresh(github(id))

    /** Asks GitHub whether the app is installed on the organization, and on which repositories. */
    suspend fun refresh(connection: GitConnection): GitConnection {
        if (connection.githubAppId == null) return connection
        val installation = unavailable { client.organizationInstallation(connection) }
        val installationId = installation?.path("id")?.asLong(0)?.takeIf { it > 0 }
        if (installationId != connection.githubInstallationId) client.forget(connection.id)
        connection.githubInstallationId = installationId
        connection.githubRepositorySelection = installation?.let { text(it, "repository_selection") }
        connection.updatedAt = clock.instant()
        return repository.save(connection)
    }

    private suspend fun github(id: UUID): GitConnection =
        repository.findById(id)?.takeIf { it.provider == GitProviderType.GITHUB }
            ?: throw NotFoundException("GIT_CONNECTION_NOT_FOUND", "GitHub 연결을 찾을 수 없습니다")

    private suspend fun <T> unavailable(call: suspend () -> T): T =
        try {
            call()
        } catch (e: GitHubException) {
            throw GitHubUnavailableException(e.message ?: "GitHub 에 연결하지 못했습니다")
        }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val STATE_BYTES = 24
        const val APP_NAME_MAX_LENGTH = 34
    }
}
