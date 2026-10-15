package com.flowbit.git

import com.flowbit.common.UuidV7
import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueEvent
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.domain.Issue
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What one call from a Git server did. */
data class GitApplyResult(
    val receipt: GitReceiptResult,
    /** Numbers of the issues whose pages should read again. */
    val touched: Set<Int>,
    /** The projects those issues are linked to, whose lists should read again. */
    val projectIds: Set<UUID>,
)

/**
 * Links issues to what a Git server reported (docs/features/git-integration.md "티켓 키 인식"):
 * the branches whose names hold a key, the pull requests that name one, and the commits. Each is
 * stored once, so a delivery that comes again changes nothing; the history gets an entry only for
 * what is new. Call inside a transaction.
 */
@Service
class GitLinkService(
    private val issueService: IssueService,
    private val userService: UserService,
    private val keyMatcher: IssueKeyMatcher,
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun apply(
        connection: GitConnection,
        projectIds: Set<UUID>?,
        events: List<GitEvent>,
    ): GitApplyResult {
        val run = Run(connection, projectIds, keyMatcher.keys())
        for (event in events) {
            when (event) {
                is GitPush -> push(run, event)
                is GitPullRequest -> pullRequest(run, event)
            }
        }
        return GitApplyResult(
            GitReceiptResult(run.linked.keys.map(run.keys::keyOf), emptyList(), run.skipped),
            run.linked.keys.toSet(),
            run.linked.values
                .mapNotNull { it.projectId }
                .toSet(),
        )
    }

    /**
     * A branch holding a key is linked to that issue; so is every commit pushed with it, and every
     * commit whose message names an issue.
     */
    private suspend fun push(
        run: Run,
        push: GitPush,
    ) {
        val branch = push.branch
        if (push.deleted || branch == null) return
        val keys = run.keys
        val branchNumbers = keys.numbers(branch)
        val commits = push.commits.take(MAX_COMMITS)
        val named = commits.associate { it.sha to keys.numbers(it.message) }
        val issues = run.targets(branchNumbers + named.values.flatten())
        if (issues.isEmpty()) return
        val actor = run.actorOf(push.pusher)
        for (issue in issues) {
            val events = mutableListOf<IssueEvent>()
            val onBranch = issue.number in branchNumbers
            if (onBranch && linkBranch(run.connection.id, issue.id, push.repository, branch, push.branchUrl.orEmpty())) {
                events += IssueEvent(IssueActivityType.GIT_BRANCH_LINKED, mapOf("repository" to push.repository, "branch" to branch, "url" to push.branchUrl))
            }
            val added =
                commits.filter { onBranch || issue.number in named[it.sha].orEmpty() }.filter {
                    addCommit(
                        run.connection.id,
                        issue.id,
                        push.repository,
                        it,
                    )
                }
            if (added.isNotEmpty()) {
                val shown =
                    added.takeLast(COMMITS_SHOWN).map { commit ->
                        mapOf("sha" to commit.sha.take(SHORT_SHA), "message" to subject(commit.message), "url" to commit.url)
                    }
                events +=
                    IssueEvent(
                        IssueActivityType.GIT_COMMITS_PUSHED,
                        mapOf("repository" to push.repository, "branch" to branch, "count" to added.size, "commits" to shown),
                    )
            }
            issueService.recordFromGit(issue.id, actor, events)
            run.linked[issue.number] = issue
        }
    }

    /**
     * A key in the branch or the title, or after "Fixes" in the body, makes a closing link; a key
     * anywhere else in the body only refers to the issue. A link that closes stays closing.
     */
    private suspend fun pullRequest(
        run: Run,
        pr: GitPullRequest,
    ) {
        val keys = run.keys
        val closing = (keys.numbers(pr.headBranch) + keys.numbers(pr.title) + keys.closingNumbers(pr.body)).toSet()
        val issues = run.targets(keys.numbers(pr.headBranch) + keys.numbers(pr.title) + keys.numbers(pr.body))
        if (issues.isEmpty()) return
        val reference = mapOf("repository" to pr.repository, "number" to pr.number, "title" to pr.title, "url" to pr.url)
        for (issue in issues) {
            val link = linkPullRequest(run.connection.id, issue.id, pr, issue.number in closing)
            if (link.created) {
                issueService.recordFromGit(
                    issue.id,
                    run.actorOf(pr.author),
                    listOf(
                        IssueEvent(
                            IssueActivityType.GIT_PR_LINKED,
                            reference + ("closes" to link.closes),
                        ),
                    ),
                )
            }
            val ended =
                when {
                    pr.state == GitPrState.MERGED && link.previousState != GitPrState.MERGED -> IssueActivityType.GIT_PR_MERGED
                    pr.state == GitPrState.CLOSED && link.previousState != GitPrState.CLOSED -> IssueActivityType.GIT_PR_CLOSED
                    else -> null
                }
            if (ended != null) issueService.recordFromGit(issue.id, run.actorOf(pr.actor), listOf(IssueEvent(ended, reference)))
            run.linked[issue.number] = issue
        }
    }

    // --- storing --------------------------------------------------------------------------------

    /** True when the branch was not linked to the issue before. */
    private suspend fun linkBranch(
        connectionId: UUID,
        issueId: UUID,
        repository: String,
        branch: String,
        url: String,
    ): Boolean {
        val now = clock.instant()
        return databaseClient
            .sql(
                """
                insert into issue_git_links (id, issue_id, connection_id, kind, repository, branch, url, closes, created_at, updated_at)
                values (:id, :issueId, :connectionId, 'BRANCH', :repository, :branch, :url, true, :now, :now)
                on conflict do nothing
                """.trimIndent(),
            ).bind("id", UuidV7.generate(now))
            .bind("issueId", issueId)
            .bind("connectionId", connectionId)
            .bind("repository", repository)
            .bind("branch", branch)
            .bind("url", url)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
    }

    private data class PullRequestLink(
        val created: Boolean,
        /** The state stored before this event; null for a new link. */
        val previousState: GitPrState?,
        val closes: Boolean,
    )

    /**
     * Stores the pull request's link to the issue, or brings it up to date. Its state is what the
     * event says, so events that arrive out of order still end with the server's latest word.
     */
    private suspend fun linkPullRequest(
        connectionId: UUID,
        issueId: UUID,
        pr: GitPullRequest,
        closes: Boolean,
    ): PullRequestLink {
        val now = clock.instant()
        val inserted =
            databaseClient
                .sql(
                    """
                    insert into issue_git_links (id, issue_id, connection_id, kind, repository, branch, pr_number, title, url, pr_state, closes, created_at, updated_at)
                    values (:id, :issueId, :connectionId, 'PULL_REQUEST', :repository, :branch, :number, :title, :url, :state, :closes, :now, :now)
                    on conflict do nothing
                    """.trimIndent(),
                ).bind("id", UuidV7.generate(now))
                .bind("issueId", issueId)
                .bind("connectionId", connectionId)
                .bind("repository", pr.repository)
                .bindNullable("branch", pr.headBranch)
                .bind("number", pr.number)
                .bind("title", pr.title.take(TITLE_MAX_LENGTH))
                .bind("url", pr.url)
                .bind("state", pr.state.name)
                .bind("closes", closes)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (inserted > 0) return PullRequestLink(created = true, previousState = null, closes = closes)

        val stored =
            databaseClient
                .sql(
                    """
                    select id, pr_state, closes from issue_git_links
                    where issue_id = :issueId and connection_id = :connectionId and repository = :repository
                      and kind = 'PULL_REQUEST' and pr_number = :number
                    for update
                    """.trimIndent(),
                ).bind("issueId", issueId)
                .bind("connectionId", connectionId)
                .bind("repository", pr.repository)
                .bind("number", pr.number)
                .map { row ->
                    Triple(
                        checkNotNull(row.get("id", UUID::class.java)),
                        row.get("pr_state", String::class.java)?.let(GitPrState::valueOf),
                        checkNotNull(row.get("closes", Boolean::class.javaObjectType)),
                    )
                }.one()
                .awaitSingleOrNull() ?: return PullRequestLink(created = false, previousState = pr.state, closes = closes)
        val (id, previousState, wasClosing) = stored
        databaseClient
            .sql("update issue_git_links set title = :title, url = :url, pr_state = :state, closes = :closes, updated_at = :now where id = :id")
            .bind("title", pr.title.take(TITLE_MAX_LENGTH))
            .bind("url", pr.url)
            .bind("state", pr.state.name)
            .bind("closes", wasClosing || closes)
            .bind("now", now)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return PullRequestLink(created = false, previousState = previousState, closes = wasClosing || closes)
    }

    /** True when the commit was not recorded on the issue before. */
    private suspend fun addCommit(
        connectionId: UUID,
        issueId: UUID,
        repository: String,
        commit: GitCommit,
    ): Boolean =
        databaseClient
            .sql(
                """
                insert into issue_git_commits (issue_id, connection_id, repository, sha, message, url, author_name, committed_at)
                values (:issueId, :connectionId, :repository, :sha, :message, :url, :author, :at)
                on conflict do nothing
                """.trimIndent(),
            ).bind("issueId", issueId)
            .bind("connectionId", connectionId)
            .bind("repository", repository)
            .bind("sha", commit.sha)
            .bind("message", subject(commit.message))
            .bind("url", commit.url)
            .bindNullable("author", commit.authorName)
            .let { if (commit.timestamp == null) it.bindNull("at", Instant::class.java) else it.bind("at", commit.timestamp) }
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // --- one call -------------------------------------------------------------------------------

    /** What one call has found so far, and the people it has already looked up. */
    private inner class Run(
        val connection: GitConnection,
        val projectIds: Set<UUID>?,
        val keys: IssueKeys,
    ) {
        /** By number: the same issue may come up in several events. */
        val linked = linkedMapOf<Int, Issue>()
        var skipped = 0
        private val actors = mutableMapOf<GitPerson, Actor>()

        /**
         * The issues the numbers name that this connection may touch, at most [MAX_ISSUES] for one
         * event; the rest are counted as skipped.
         */
        suspend fun targets(numbers: List<Int>): List<Issue> {
            val found = issueService.gitTargets(numbers.distinct(), projectIds)
            skipped += (found.size - MAX_ISSUES).coerceAtLeast(0)
            return found.take(MAX_ISSUES)
        }

        /** The Flowbit account of a Git user: by e-mail address, then by login id. */
        suspend fun actorOf(person: GitPerson): Actor =
            actors.getOrPut(person) {
                val via = mapOf("source" to "git", "name" to person.label)
                val user = userService.findActiveByEmailOrLoginId(person.email, person.username)
                if (user != null) Actor(user.id, user.displayName ?: user.loginId, via) else Actor(null, "${person.label}(Git)", via)
            }
    }

    private fun subject(message: String): String =
        message
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(SUBJECT_MAX_LENGTH)

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: String?,
    ) = if (value == null) bindNull(name, String::class.java) else bind(name, value)

    private companion object {
        /** Issues one event may link (docs/features/git-integration.md). */
        const val MAX_ISSUES = 20

        /** Commits of one push that are looked at. */
        const val MAX_COMMITS = 100

        /** Commits a history entry lists. */
        const val COMMITS_SHOWN = 5
        const val SHORT_SHA = 10
        const val SUBJECT_MAX_LENGTH = 300
        const val TITLE_MAX_LENGTH = 500
    }
}
