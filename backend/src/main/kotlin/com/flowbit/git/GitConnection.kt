package com.flowbit.git

import kotlinx.coroutines.flow.Flow
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

/**
 * The kind of Git server a connection receives events from. Gitea covers Forgejo too. [label] is
 * how the server is named to people.
 */
enum class GitProviderType(
    val label: String,
) {
    GITEA("Gitea"),
    GITLAB("GitLab"),
    GITHUB("GitHub"),
}

/** How events reach Flowbit: the server calls the webhook, or Flowbit reads the GitHub API (GitHub only). */
enum class GitReceiveMode { WEBHOOK, POLL }

/** One Git server (or organization on it) whose webhook calls reach Flowbit (docs/features/git-integration.md). */
@Table("git_connections")
class GitConnection(
    @Id
    val id: UUID,
    var name: String,
    var provider: GitProviderType,
    /** Where the Git server is, for the screen and the setup guide. */
    var baseUrl: String,
    /** Signs every call from the Git server. Shown only when it is made. */
    var secret: String,
    var enabled: Boolean,
    /** Raw JSON: the status each stage moves an issue to (GitStatusRules, D-158). */
    var statusRules: String,
    var lastReceivedAt: Instant? = null,
    var lastEvent: String? = null,
    /** Raw JSON: `{"linkedIssues": [...], "statusChanged": [...], "skipped": 0}`. */
    var lastResult: String? = null,
    var lastError: String? = null,
    val createdAt: Instant,
    var updatedAt: Instant,
    var receiveMode: GitReceiveMode = GitReceiveMode.WEBHOOK,
    // --- GitHub App (D-146): the organization it is installed on and its credentials ---
    var githubOrganization: String? = null,
    var githubAppId: Long? = null,
    var githubAppSlug: String? = null,
    var githubClientId: String? = null,
    var githubClientSecret: String? = null,
    /** The app's private key (PEM) that signs its JWTs. */
    var githubPrivateKey: String? = null,
    var githubInstallationId: Long? = null,
    /** `all` or `selected`. */
    var githubRepositorySelection: String? = null,
    /** Ties GitHub's redirect after the app is made back to this connection; cleared once used. */
    var githubSetupState: String? = null,
    // --- reading the GitHub API ---
    var lastSyncedAt: Instant? = null,
    var syncError: String? = null,
    var rateLimitRemaining: Int? = null,
) {
    /** A GitHub connection whose app exists and is installed: one Flowbit can call the API for. */
    fun rules(): GitStatusRules = GitStatusRules.parse(statusRules)

    fun githubReady(): Boolean = provider == GitProviderType.GITHUB && githubAppId != null && githubPrivateKey != null && githubInstallationId != null

    companion object {
        const val NAME_MAX_LENGTH = 80
    }
}

interface GitConnectionRepository : CoroutineCrudRepository<GitConnection, UUID> {
    @Query("select * from git_connections order by created_at, id")
    fun findAllOrdered(): Flow<GitConnection>

    suspend fun findByGithubSetupState(state: String): GitConnection?
}
