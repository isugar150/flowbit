package com.flowbit.git

import kotlinx.coroutines.flow.Flow
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

/** The kind of Git server a connection receives events from. Gitea covers Forgejo too. */
enum class GitProviderType { GITEA, GITLAB, GITHUB }

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
    /** False: only the issues of the chosen projects are linked and changed. */
    var allProjects: Boolean,
    /** Pushing a branch or opening a pull request moves the issue to "in progress". */
    var autoInProgress: Boolean,
    /** Merging a pull request that closes the issue moves it to "done". */
    var autoDone: Boolean,
    var lastReceivedAt: Instant? = null,
    var lastEvent: String? = null,
    /** Raw JSON: `{"linkedIssues": [...], "statusChanged": [...], "skipped": 0}`. */
    var lastResult: String? = null,
    var lastError: String? = null,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 80
    }
}

interface GitConnectionRepository : CoroutineCrudRepository<GitConnection, UUID> {
    @Query("select * from git_connections order by created_at, id")
    fun findAllOrdered(): Flow<GitConnection>
}
