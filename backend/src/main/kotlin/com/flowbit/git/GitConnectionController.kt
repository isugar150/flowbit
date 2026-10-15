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
    val allProjects: Boolean = true,
    val projectIds: List<UUID> = emptyList(),
    val autoInProgress: Boolean = true,
    val autoDone: Boolean = true,
) {
    fun toInput() = GitConnectionInput(name, enabled, provider, baseUrl, allProjects, projectIds, autoInProgress, autoDone)
}

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
    val allProjects: Boolean,
    val projectIds: List<UUID>,
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
        val view = service.create(body.toInput(), user.id, ClientInfo.from(exchange))
        return response(view).copy(secret = view.connection.secret)
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

    @PostMapping("/{id}/secret")
    suspend fun regenerateSecret(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ): GitSecretResponse = GitSecretResponse(service.regenerateSecret(id, user.id, ClientInfo.from(exchange)))

    private fun response(view: GitConnectionView): GitConnectionResponse {
        val connection = view.connection
        return GitConnectionResponse(
            id = connection.id,
            name = connection.name,
            enabled = connection.enabled,
            provider = connection.provider,
            baseUrl = connection.baseUrl,
            allProjects = connection.allProjects,
            projectIds = view.projectIds,
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
        )
    }

    companion object {
        fun webhookPath(id: UUID) = "/api/v1/integrations/git/$id/webhook"
    }
}
