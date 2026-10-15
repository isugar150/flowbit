package com.flowbit.git

import com.fasterxml.jackson.annotation.JsonInclude
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class GitConnectionRequest(
    val name: String = "",
    val enabled: Boolean = true,
    val provider: GitProviderType? = null,
    val baseUrl: String = "",
    val autoInProgress: Boolean = true,
    val autoDone: Boolean = true,
    val receiveMode: GitReceiveMode = GitReceiveMode.WEBHOOK,
    val githubOrganization: String? = null,
) {
    fun toInput() = GitConnectionInput(name, enabled, provider, baseUrl, autoInProgress, autoDone, receiveMode, githubOrganization)
}

/** The GitHub App of a connection, and where to go on GitHub for it (D-146). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class GitHubAppResponse(
    val organization: String?,
    /** Null until the app is made. */
    val appId: Long?,
    val appSlug: String?,
    /** The app's page on GitHub. */
    val appUrl: String?,
    /** Where the app is installed on the organization (and its repositories chosen). */
    val installUrl: String?,
    /** The app's settings on GitHub: its webhook, permissions and keys. */
    val settingsUrl: String?,
    /**
     * Where members come back after connecting their GitHub account (D-150). It must be one of the
     * app's Callback URLs; apps made from a manifest have it, older ones get it added on GitHub.
     */
    val callbackUrl: String,
    /** Null until it is installed on the organization. */
    val installationId: Long?,
    /** `all` or `selected`. */
    val repositorySelection: String?,
)

data class GitHubCreatedRequest(
    val code: String = "",
    val state: String = "",
)

/** What the last call from the Git server did. */
data class GitReceiptResult(
    val linkedIssues: List<String> = emptyList(),
    val statusChanged: List<String> = emptyList(),
    /** Issues past the limit of one event, left alone. */
    val skipped: Int = 0,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GitConnectionResponse(
    val id: UUID,
    val name: String,
    val enabled: Boolean,
    val provider: GitProviderType,
    val baseUrl: String,
    val autoInProgress: Boolean,
    val autoDone: Boolean,
    /** Always true: a connection is made with a secret. The secret itself is only in [secret]. */
    val secretSet: Boolean,
    /** What the Git server calls. A path alone when FLOWBIT_PUBLIC_URL is not set. */
    val webhookUrl: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val lastReceivedAt: Instant?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val lastEvent: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val lastResult: GitReceiptResult?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val receiveMode: GitReceiveMode,
    /** GitHub connections only. */
    val github: GitHubAppResponse?,
    /** When the GitHub API was last read (receive mode POLL), and what went wrong then. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val lastSyncedAt: Instant?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val syncError: String?,
    /** What GitHub last said is left of the installation's hourly API calls. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val rateLimitRemaining: Int?,
    /** Only right after the connection is made. */
    val secret: String? = null,
)

data class GitSecretResponse(
    val secret: String,
)

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/git/connections")
class GitConnectionController(
    private val service: GitConnectionService,
    private val githubApps: GitHubAppService,
    private val sync: GitHubSyncService,
    private val syncJob: GitHubSyncJob,
    private val publicUrl: PublicUrl,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping
    suspend fun list(): List<GitConnectionResponse> = service.list().map(::response)

    /** The secret is in this response and nowhere else afterwards. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: GitConnectionRequest,
        exchange: ServerWebExchange,
    ): GitConnectionResponse {
        val connection = service.create(body.toInput(), user.id, ClientInfo.from(exchange))
        return response(connection).copy(secret = connection.secret)
    }

    @PutMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: GitConnectionRequest,
        exchange: ServerWebExchange,
    ): GitConnectionResponse = response(service.update(id, body.toInput(), user.id, ClientInfo.from(exchange)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ) {
        service.delete(id, user.id, ClientInfo.from(exchange))
    }

    /**
     * The form the browser posts to GitHub to make the connection's GitHub App. [origin] is the
     * address the browser has Flowbit at, for GitHub's redirects when FLOWBIT_PUBLIC_URL is not set.
     */
    @GetMapping("/{id}/github/manifest")
    suspend fun githubManifest(
        @PathVariable id: UUID,
        @RequestParam(required = false) origin: String?,
    ): GitHubManifestForm = githubApps.manifestForm(id, origin)

    /** Reads again where the connection's GitHub App is installed. */
    @PostMapping("/{id}/github/installation")
    suspend fun githubInstallation(
        @PathVariable id: UUID,
    ): GitConnectionResponse {
        githubApps.refresh(id)
        return response(checkNotNull(service.find(id)))
    }

    /** Reads the GitHub API now instead of waiting for the next round (receive mode POLL). */
    @PostMapping("/{id}/sync")
    suspend fun syncNow(
        @PathVariable id: UUID,
    ): GitReceiptResult {
        val result =
            try {
                sync.sync(id)
            } catch (e: GitHubException) {
                throw GitHubUnavailableException(e.message ?: "동기화하지 못했습니다")
            }
        syncJob.announce(result)
        return result.receipt
    }

    @PostMapping("/{id}/secret")
    suspend fun regenerateSecret(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ): GitSecretResponse = GitSecretResponse(service.regenerateSecret(id, user.id, ClientInfo.from(exchange)))

    /** One connection as the list shows it. */
    suspend fun find(id: UUID): GitConnectionResponse = response(checkNotNull(service.find(id)))

    private fun response(connection: GitConnection): GitConnectionResponse =
        GitConnectionResponse(
            id = connection.id,
            name = connection.name,
            enabled = connection.enabled,
            provider = connection.provider,
            baseUrl = connection.baseUrl,
            autoInProgress = connection.autoInProgress,
            autoDone = connection.autoDone,
            secretSet = connection.secret.isNotEmpty(),
            webhookUrl = publicUrl.of(webhookPath(connection.id)),
            lastReceivedAt = connection.lastReceivedAt,
            lastEvent = connection.lastEvent,
            lastResult = connection.lastResult?.let { runCatching { jsonMapper.readValue(it, GitReceiptResult::class.java) }.getOrNull() },
            lastError = connection.lastError,
            createdAt = connection.createdAt,
            updatedAt = connection.updatedAt,
            receiveMode = connection.receiveMode,
            github = if (connection.provider == GitProviderType.GITHUB) githubOf(connection) else null,
            lastSyncedAt = connection.lastSyncedAt,
            syncError = connection.syncError,
            rateLimitRemaining = connection.rateLimitRemaining,
        )

    private fun githubOf(connection: GitConnection): GitHubAppResponse {
        val web = connection.baseUrl.trimEnd('/')
        val slug = connection.githubAppSlug
        return GitHubAppResponse(
            organization = connection.githubOrganization,
            appId = connection.githubAppId,
            appSlug = slug,
            appUrl = slug?.let { "$web/apps/$it" },
            installUrl = slug?.let { "$web/apps/$it/installations/new" },
            settingsUrl = slug?.let { "$web/organizations/${connection.githubOrganization}/settings/apps/$it" },
            callbackUrl = publicUrl.of(GitHubAppService.GITHUB_ACCOUNT_CALLBACK),
            installationId = connection.githubInstallationId,
            repositorySelection = connection.githubRepositorySelection,
        )
    }

    companion object {
        fun webhookPath(id: UUID) = "/api/v1/integrations/git/$id/webhook"
    }
}

/** Where the admin pages GitHub sends the browser back to report what happened (D-146). */
@RestController
@RequestMapping("/api/v1/admin/git/github")
class GitHubSetupController(
    private val githubApps: GitHubAppService,
    private val connections: GitConnectionController,
) {
    /** The app was made: [GitHubCreatedRequest.code] becomes the app's id and keys. */
    @PostMapping("/created")
    suspend fun created(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: GitHubCreatedRequest,
        exchange: ServerWebExchange,
    ): GitConnectionResponse {
        val connection = githubApps.complete(body.code.trim(), body.state.trim(), user.id, ClientInfo.from(exchange))
        return connections.find(connection.id)
    }

    /** The app was installed (or its repositories changed): every GitHub connection reads where it is installed. */
    @PostMapping("/installations")
    suspend fun installations(): List<GitConnectionResponse> {
        githubApps.refreshAll()
        return connections.list()
    }
}
