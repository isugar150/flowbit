package com.flowbit.git

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A repository a connection is known to have. */
data class KnownRepository(
    val connectionId: UUID,
    /** org/web, lower case. */
    val repository: String,
    val url: String?,
)

/**
 * The repositories each Git connection is known to have (D-151), so that a project's managers
 * pick from a list instead of typing names. A GitHub App's list is what it is installed on, read
 * from the GitHub API when the installation is checked, on every API round, and when someone asks
 * for it; it is replaced as a whole. Gitea and GitLab cannot be asked (Flowbit has no token for
 * them), so their list grows with every repository that sends an event.
 */
@Service
class GitRepositoryCatalog(
    private val databaseClient: DatabaseClient,
    private val client: GitHubClient,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** When each GitHub connection's list was last read from GitHub, so asking often costs nothing. */
    private val readAt = ConcurrentHashMap<UUID, Instant>()

    suspend fun list(connectionIds: Collection<UUID>): List<KnownRepository> {
        if (connectionIds.isEmpty()) return emptyList()
        return databaseClient
            .sql("select connection_id, repository, url from git_repositories where connection_id in (:ids) order by repository")
            .bind("ids", connectionIds)
            .map { row ->
                KnownRepository(
                    checkNotNull(row.get("connection_id", UUID::class.java)),
                    checkNotNull(row.get("repository", String::class.java)),
                    row.get("url", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    /** Adds repositories that just sent events; ones already known stay as they are. */
    suspend fun remember(
        connectionId: UUID,
        repositories: Collection<String>,
    ) {
        for (repository in repositories.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()) {
            databaseClient
                .sql(
                    """
                    insert into git_repositories (connection_id, repository, seen_at) values (:id, :repository, :now)
                    on conflict (connection_id, repository) do update set seen_at = excluded.seen_at
                    """.trimIndent(),
                ).bind("id", connectionId)
                .bind("repository", repository)
                .bind("now", clock.instant())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    /** Makes a GitHub App's list exactly the repositories in [installation] (GitHub's `repositories` objects). */
    suspend fun replace(
        connectionId: UUID,
        installation: List<JsonNode>,
    ) {
        val repositories =
            installation
                .mapNotNull { node -> text(node, "full_name")?.lowercase()?.let { it to text(node, "html_url") } }
                .distinctBy { it.first }
        transactionalOperator.executeAndAwait {
            databaseClient
                .sql("delete from git_repositories where connection_id = :id")
                .bind("id", connectionId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            for ((repository, url) in repositories) {
                databaseClient
                    .sql("insert into git_repositories (connection_id, repository, url, seen_at) values (:id, :repository, :url, :now)")
                    .bind("id", connectionId)
                    .bind("repository", repository)
                    .let { if (url == null) it.bindNull("url", String::class.java) else it.bind("url", url) }
                    .bind("now", clock.instant())
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
        }
        readAt[connectionId] = clock.instant()
    }

    /**
     * Reads a GitHub App's repositories from GitHub again, unless that was done in the last
     * minute. A failure leaves the list as it was and is only logged.
     */
    suspend fun refreshGitHub(
        connection: GitConnection,
        force: Boolean = false,
    ) {
        if (!connection.githubReady()) return
        val last = readAt[connection.id]
        if (!force && last != null && last.isAfter(clock.instant().minus(MIN_INTERVAL))) return
        try {
            replace(connection.id, installationRepositories(connection))
        } catch (e: GitHubException) {
            log.warn("Reading the repositories of Git connection {} failed: {}", connection.id, e.message)
        }
    }

    /** The repositories the app is installed on, page by page. */
    suspend fun installationRepositories(connection: GitConnection): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        for (page in 1..MAX_PAGES) {
            val repositories =
                client
                    .get(connection, "/installation/repositories?per_page=100&page=$page")
                    .body
                    ?.path("repositories")
                    ?.let(::items)
                    .orEmpty()
            all += repositories
            if (repositories.size < PAGE_SIZE) break
        }
        return all
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10
        val MIN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
