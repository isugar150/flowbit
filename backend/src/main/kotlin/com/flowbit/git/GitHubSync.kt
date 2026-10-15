package com.flowbit.git

import com.flowbit.issue.api.IssueAnnouncer
import com.flowbit.realtime.RealtimeHub
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** How far reading one repository has got (`git_sync_cursors`). */
private data class SyncCursor(
    val pullsEtag: String?,
    val pullsUpdatedAt: Instant?,
    val branchesEtag: String?,
    val branchHeads: Map<String, String>,
)

/**
 * Reads what happened on GitHub through the API, for a GitHub App connection whose events cannot
 * reach Flowbit as webhooks (docs/features/git-integration.md "받는 방식 B: 가져오기", D-146).
 * Each round reads, for every repository the app is installed on, the pull requests changed since
 * the last round and the branches whose heads moved, turns them into the same events a webhook
 * brings, and hands them to [GitLinkService]. Lists are read with their ETag, so a repository where
 * nothing changed costs no API calls against the rate limit.
 *
 * The first round of a repository only remembers where things are: what happened before the
 * connection is not brought in, so old merged pull requests do not finish today's issues.
 */
@Service
class GitHubSyncService(
    private val connections: GitConnectionService,
    private val repository: GitConnectionRepository,
    private val client: GitHubClient,
    private val links: GitLinkService,
    private val keyMatcher: IssueKeyMatcher,
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = Mutex()

    /** Every enabled GitHub connection that reads the API. */
    suspend fun syncAll(): List<GitApplyResult> =
        repository
            .findAllOrdered()
            .toList()
            .filter { it.enabled && it.receiveMode == GitReceiveMode.POLL && it.githubReady() }
            .mapNotNull { connection -> runCatching { sync(connection.id) }.getOrNull() }

    /** One round for one connection. A failure is kept on the connection for the administrator and thrown. */
    suspend fun sync(id: UUID): GitApplyResult =
        running.withLock {
            val view = connections.find(id) ?: throw GitHubException("Git 연결을 찾을 수 없습니다")
            val connection = view.connection
            if (!connection.githubReady()) throw GitHubException("GitHub 앱이 조직에 설치되지 않았습니다")
            try {
                val (events, cursors) = read(connection)
                val result =
                    transactionalOperator.executeAndAwait {
                        val applied =
                            if (events.isEmpty()) {
                                GitApplyResult(GitReceiptResult(), emptySet(), emptySet())
                            } else {
                                links.apply(connection, if (connection.allProjects) null else view.projectIds.toSet(), events)
                            }
                        cursors.forEach { (repositoryName, cursor) -> saveCursor(connection.id, repositoryName, cursor) }
                        applied
                    }
                record(connection.id, jsonMapper.writeValueAsString(result.receipt), null)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                record(connection.id, null, e.message)
                throw e
            } catch (e: Exception) {
                log.warn("Reading GitHub for connection {} failed", id, e)
                record(connection.id, null, "동기화하지 못했습니다. 서버 로그를 확인하세요")
                throw GitHubException("동기화하지 못했습니다")
            }
        }

    /** The events since the last round, with the cursors to keep once they are applied. */
    private suspend fun read(connection: GitConnection): Pair<List<GitEvent>, Map<String, SyncCursor>> {
        val keys = keyMatcher.keys()
        val events = mutableListOf<GitEvent>()
        val cursors = mutableMapOf<String, SyncCursor>()
        for (repo in repositories(connection)) {
            val name = text(repo, "full_name") ?: continue
            val defaultBranch = text(repo, "default_branch")
            val htmlUrl = text(repo, "html_url")
            // Null for a repository not read before: this round only notes where it is.
            val cursor = cursor(connection.id, name)
            var next = cursor ?: SyncCursor(null, null, null, emptyMap())

            val pulls = client.get(connection, "/repos/$name/pulls?state=all&sort=updated&direction=desc&per_page=$PULLS_PER_ROUND", cursor?.pullsEtag)
            val pullItems = pulls.body?.let(::items).orEmpty()
            if (pulls.status == 200) {
                val newest = pullItems.mapNotNull { instant(text(it, "updated_at")) }.maxOrNull()
                if (cursor != null) {
                    val since = cursor.pullsUpdatedAt ?: Instant.EPOCH
                    pullItems
                        .filter { (instant(text(it, "updated_at")) ?: Instant.EPOCH).isAfter(since) }
                        .sortedBy { instant(text(it, "updated_at")) }
                        .forEach { events += pullRequest(connection, name, it) }
                }
                next = next.copy(pullsEtag = pulls.etag, pullsUpdatedAt = listOfNotNull(next.pullsUpdatedAt, newest).maxOrNull() ?: clock.instant())
            }

            val branches = client.get(connection, "/repos/$name/branches?per_page=$BRANCHES_PER_ROUND", cursor?.branchesEtag)
            if (branches.status == 200) {
                val heads =
                    branches.body
                        ?.let(::items)
                        .orEmpty()
                        .mapNotNull { branch -> text(branch, "name")?.let { it to text(branch.path("commit"), "sha") } }
                        .mapNotNull { (branch, sha) -> sha?.let { branch to it } }
                        .toMap()
                if (cursor != null) {
                    for ((branch, sha) in heads) {
                        val before = cursor.branchHeads[branch]
                        if (before == sha) continue
                        val isDefault = branch == defaultBranch
                        if (!isDefault && keys.numbers(branch).isEmpty()) continue
                        // A default branch that is new to us has nothing to compare with.
                        val base = before ?: if (isDefault) continue else defaultBranch ?: continue
                        events += push(connection, name, branch, defaultBranch, htmlUrl, base, sha)
                    }
                }
                next = next.copy(branchesEtag = branches.etag, branchHeads = heads)
            }
            cursors[name] = next
        }
        return events to cursors
    }

    /** The repositories the app is installed on. */
    private suspend fun repositories(connection: GitConnection): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        for (page in 1..MAX_REPOSITORY_PAGES) {
            val response = client.get(connection, "/installation/repositories?per_page=100&page=$page")
            val repositories =
                response.body
                    ?.path("repositories")
                    ?.let(::items)
                    .orEmpty()
            all += repositories
            if (repositories.size < 100) break
        }
        return all
    }

    private suspend fun pullRequest(
        connection: GitConnection,
        repository: String,
        item: JsonNode,
    ): GitPullRequest {
        val number = item.path("number").asInt(0)
        val merged = text(item, "merged_at") != null
        val state =
            when {
                merged -> GitPrState.MERGED
                text(item, "state") == "closed" -> GitPrState.CLOSED
                item.path("draft").asBoolean(false) -> GitPrState.DRAFT
                else -> GitPrState.OPEN
            }
        val author = GitPerson(username = text(item.path("user"), "login"), email = null, name = null)
        // The list does not say who merged; the pull request itself does.
        val mergedBy =
            if (merged) {
                client
                    .get(connection, "/repos/$repository/pulls/$number")
                    .body
                    ?.path("merged_by")
                    ?.let { text(it, "login") }
            } else {
                null
            }
        return GitPullRequest(
            repository = repository,
            action = "synced",
            number = number,
            title = text(item, "title").orEmpty(),
            body = text(item, "body").orEmpty(),
            url = text(item, "html_url").orEmpty(),
            state = state,
            headBranch = text(item.path("head"), "ref"),
            author = author,
            actor = mergedBy?.let { GitPerson(username = it, email = null, name = null) } ?: author,
        )
    }

    /** The commits on [branch] from [base] to [head], as a push. */
    private suspend fun push(
        connection: GitConnection,
        repository: String,
        branch: String,
        defaultBranch: String?,
        htmlUrl: String?,
        base: String,
        head: String,
    ): GitPush {
        val compare = client.get(connection, "/repos/$repository/compare/${encode(base)}...${encode(head)}")
        val commits =
            compare.body
                ?.path("commits")
                ?.let(::items)
                .orEmpty()
                .mapNotNull { item ->
                    val sha = text(item, "sha") ?: return@mapNotNull null
                    val commit = item.path("commit")
                    GitCommit(
                        sha = sha,
                        message = text(commit, "message").orEmpty(),
                        url = text(item, "html_url").orEmpty(),
                        authorName = text(item.path("author"), "login") ?: text(commit.path("author"), "name"),
                        timestamp = instant(text(commit.path("author"), "date")),
                    )
                }
        // The API does not say who pushed; the author of the newest commit stands for them.
        val last =
            compare.body
                ?.path("commits")
                ?.let(::items)
                ?.lastOrNull()
        val pusher =
            GitPerson(
                username = last?.let { text(it.path("author"), "login") },
                email = last?.let { text(it.path("commit").path("author"), "email") },
                name = last?.let { text(it.path("commit").path("author"), "name") },
            )
        return GitPush(
            repository = repository,
            branch = branch,
            defaultBranch = defaultBranch,
            deleted = false,
            branchUrl = htmlUrl?.let { "$it/tree/$branch" },
            pusher = pusher,
            commits = commits,
        )
    }

    private fun encode(ref: String) = URLEncoder.encode(ref, Charsets.UTF_8).replace("%2F", "/")

    // --- storing --------------------------------------------------------------------------------

    private suspend fun cursor(
        connectionId: UUID,
        repository: String,
    ): SyncCursor? =
        databaseClient
            .sql(
                "select pulls_etag, pulls_updated_at, branches_etag, branch_heads from git_sync_cursors where connection_id = :id and repository = :repository",
            ).bind("id", connectionId)
            .bind("repository", repository)
            .map { row ->
                @Suppress("UNCHECKED_CAST")
                val heads = jsonMapper.readValue(checkNotNull(row.get("branch_heads", String::class.java)), Map::class.java) as Map<String, String>
                SyncCursor(
                    pullsEtag = row.get("pulls_etag", String::class.java),
                    pullsUpdatedAt = row.get("pulls_updated_at", java.time.OffsetDateTime::class.java)?.toInstant(),
                    branchesEtag = row.get("branches_etag", String::class.java),
                    branchHeads = heads,
                )
            }.one()
            .awaitSingleOrNull()

    private suspend fun saveCursor(
        connectionId: UUID,
        repository: String,
        cursor: SyncCursor,
    ) {
        databaseClient
            .sql(
                """
                insert into git_sync_cursors (connection_id, repository, pulls_etag, pulls_updated_at, branches_etag, branch_heads, updated_at)
                values (:id, :repository, :pullsEtag, :pullsUpdatedAt, :branchesEtag, :heads, :now)
                on conflict (connection_id, repository) do update set
                    pulls_etag = excluded.pulls_etag, pulls_updated_at = excluded.pulls_updated_at,
                    branches_etag = excluded.branches_etag, branch_heads = excluded.branch_heads, updated_at = excluded.updated_at
                """.trimIndent(),
            ).bind("id", connectionId)
            .bind("repository", repository)
            .let { if (cursor.pullsEtag == null) it.bindNull("pullsEtag", String::class.java) else it.bind("pullsEtag", cursor.pullsEtag) }
            .let { if (cursor.pullsUpdatedAt == null) it.bindNull("pullsUpdatedAt", Instant::class.java) else it.bind("pullsUpdatedAt", cursor.pullsUpdatedAt) }
            .let { if (cursor.branchesEtag == null) it.bindNull("branchesEtag", String::class.java) else it.bind("branchesEtag", cursor.branchesEtag) }
            .bind("heads", jsonMapper.writeValueAsString(cursor.branchHeads))
            .bind("now", clock.instant())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun record(
        connectionId: UUID,
        result: String?,
        error: String?,
    ) {
        databaseClient
            .sql(
                """
                update git_connections set last_synced_at = :now, sync_error = :error, rate_limit_remaining = coalesce(:remaining, rate_limit_remaining),
                       last_result = coalesce(:result, last_result)
                where id = :id
                """.trimIndent(),
            ).bind("now", clock.instant())
            .let { if (error == null) it.bindNull("error", String::class.java) else it.bind("error", error.take(ERROR_MAX_LENGTH)) }
            .let { spec ->
                client.rateLimitRemaining(connectionId)?.let { spec.bind("remaining", it) } ?: spec.bindNull("remaining", Int::class.javaObjectType)
            }.let { if (result == null) it.bindNull("result", String::class.java) else it.bind("result", result) }
            .bind("id", connectionId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private companion object {
        const val PULLS_PER_ROUND = 50
        const val BRANCHES_PER_ROUND = 100
        const val MAX_REPOSITORY_PAGES = 10
        const val ERROR_MAX_LENGTH = 500
    }
}

/**
 * Runs [GitHubSyncService.syncAll] every [interval] once the server is up, and announces what
 * changed like a webhook would. Integration tests switch it off and sync by hand.
 */
@Component
class GitHubSyncJob(
    private val sync: GitHubSyncService,
    private val announcer: IssueAnnouncer,
    @Value("\${flowbit.git.sync-enabled:true}") private val enabled: Boolean,
    @Value("\${flowbit.git.sync-interval:2m}") private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            delay(interval.toMillis())
            while (isActive) {
                try {
                    sync.syncAll().forEach { announce(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Reading GitHub failed; trying again in {}", interval, e)
                }
                delay(interval.toMillis())
            }
        }
    }

    /** What a round changed, told like a webhook's changes. */
    suspend fun announce(result: GitApplyResult) {
        result.changes.forEach { (actor, change) -> announcer.changed(actor, change) }
        (result.touched - result.changes.map { it.second.issue.number }.toSet()).forEach { announcer.touched(RealtimeHub.NOBODY, it) }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }
}
