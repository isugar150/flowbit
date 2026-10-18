package com.flowbit.git

import com.flowbit.common.UuidV7
import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueChange
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
    /** What to tell once committed, with who did it: status changes, and Git entries for the API notifications that ask for them. */
    val changes: List<Pair<Actor, IssueChange>> = emptyList(),
)

/**
 * Links issues to what a Git server reported (docs/features/git-integration.md "티켓 키 인식"):
 * the branches whose names hold a key, the pull requests that name one, and the commits. Each is
 * stored once, so a delivery that comes again changes nothing; the history gets an entry only for
 * what is new. Then moves the issues along ("상태 자동 변경"): a branch pushed or a pull request
 * opened starts the work, a closing pull request merged finishes it. Never backwards. Call inside
 * a transaction.
 */
@Service
class GitLinkService(
    private val issueService: IssueService,
    private val userService: UserService,
    private val githubAccounts: GitHubAccountService,
    private val projectRepositoryService: GitProjectRepositoryService,
    private val keyMatcher: IssueKeyMatcher,
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun apply(
        connection: GitConnection,
        events: List<GitEvent>,
        /** Who did it, when Flowbit knows better than the event: someone making a branch here (D-159). */
        by: Actor? = null,
    ): GitApplyResult {
        val run = Run(connection, keyMatcher.keys(), by)
        for (event in events) {
            when (event) {
                is GitPush -> push(run, event)
                is GitPullRequest -> pullRequest(run, event)
            }
        }
        return GitApplyResult(
            GitReceiptResult(run.linked.keys.map(run.keys::keyOf), run.statusChanged.map(run.keys::keyOf), run.skipped),
            run.linked.keys.toSet(),
            run.linked.values
                .mapNotNull { it.projectId }
                .toSet(),
            run.changes.toList(),
        )
    }

    /**
     * A branch holding a key is linked to that issue; so is every commit pushed with it, and every
     * commit whose message names an issue. The first push of the branch starts the work. A commit
     * straight to the default branch that says "Fixes FB-12" finishes the issue, for teams that
     * work without pull requests.
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
        val issues = run.targets(branchNumbers + named.values.flatten(), push.repository)
        if (issues.isEmpty()) return
        val actor = run.actorOf(push.pusher)
        for (issue in issues) {
            val events = mutableListOf<IssueEvent>()
            // A branch someone took off the issue stays off (D-161); its commits only count when they name the issue.
            val onBranch = issue.number in branchNumbers && !branchUnlinked(run.connection.id, issue.id, push.repository, branch)
            val branchLinked = onBranch && linkBranch(run.connection.id, issue.id, push.repository, branch, push.branchUrl.orEmpty())
            if (branchLinked) {
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
            record(run, issue, actor, events)
            // Pushing to the issue's own branch makes the pusher one of its assignees (D-156).
            if (onBranch && events.isNotEmpty()) assign(run, issue, actor)
            if (branchLinked) {
                run.pickRepository(issue, push.repository)
                moveTo(run, issue, GitStage.BRANCH, actor)
            }
            if (push.toDefaultBranch && commits.any { issue.number in keys.closingNumbers(it.message) }) moveTo(run, issue, GitStage.CLOSING_COMMIT, actor)
            run.linked[issue.number] = issue
        }
    }

    /**
     * A key in the branch or the title, or after "Fixes" in the body, makes a closing link; a key
     * anywhere else in the body only refers to the issue. A link that closes stays closing.
     * Opening a closing pull request starts the work; merging it finishes the issue, unless another
     * closing pull request of the issue is still open.
     */
    private suspend fun pullRequest(
        run: Run,
        pr: GitPullRequest,
    ) {
        val keys = run.keys
        val closing = (keys.numbers(pr.headBranch) + keys.numbers(pr.title) + keys.closingNumbers(pr.body)).toSet()
        val issues = run.targets(keys.numbers(pr.headBranch) + keys.numbers(pr.title) + keys.numbers(pr.body), pr.repository)
        if (issues.isEmpty()) return
        val reference = mapOf("repository" to pr.repository, "number" to pr.number, "title" to pr.title, "url" to pr.url)
        for (issue in issues) {
            val link = linkPullRequest(run.connection.id, issue.id, pr, issue.number in closing)
            // Taken off the issue by someone (D-161): left alone, whatever happens to it.
            if (link.unlinked) continue
            if (link.created) run.pickRepository(issue, pr.repository)
            if (link.created) {
                record(
                    run,
                    issue,
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
            if (ended != null) record(run, issue, run.actorOf(pr.actor), listOf(IssueEvent(ended, reference)))
            if (link.closes) {
                // The stage the pull request has just reached (D-158); nothing when its state did not change.
                val changed = link.created || pr.state != link.previousState
                val stage =
                    when {
                        !changed -> null
                        pr.state == GitPrState.DRAFT -> GitStage.PR_DRAFT
                        pr.state == GitPrState.OPEN -> GitStage.PR_OPEN
                        pr.state == GitPrState.MERGED -> GitStage.PR_MERGED
                        else -> GitStage.PR_CLOSED
                    }
                // Merged or closed, the issue waits for another closing pull request that is still open.
                val ending = stage == GitStage.PR_MERGED || stage == GitStage.PR_CLOSED
                if (stage != null && !(ending && otherOpenClosingPullRequest(issue.id, link.id))) {
                    moveTo(run, issue, stage, run.actorOf(if (link.created) pr.author else pr.actor))
                }
            }
            run.linked[issue.number] = issue
        }
    }

    /** Keeps the entries in the issue's history, and tells API notifications that ask for Git's changes. */
    private suspend fun record(
        run: Run,
        issue: Issue,
        actor: Actor,
        events: List<IssueEvent>,
    ) {
        issueService.recordFromGit(issue.id, actor, events)
        issueService.gitNotice(issue.id, actor, events)?.let { run.changes += actor to it }
    }

    /**
     * Moves the issue to the status the connection gives [stage] (D-158), only forward: never
     * back, and never an issue that is finished or canceled.
     */
    private suspend fun moveTo(
        run: Run,
        issue: Issue,
        stage: GitStage,
        actor: Actor,
    ) {
        val status = run.rules.target(stage) ?: return
        val change = issueService.changeStatusFromGit(issue.id, status, GitStatusRules.movableFrom(status), actor) ?: return
        run.changes += actor to change
        run.statusChanged += issue.number
    }

    /** Adds the Flowbit account behind [actor] to the issue's assignees, unless it is there already. */
    private suspend fun assign(
        run: Run,
        issue: Issue,
        actor: Actor,
    ) {
        val change = issueService.addAssigneeFromGit(issue.id, actor) ?: return
        run.changes += actor to change
    }

    /** Whether the issue has an open (or draft) closing pull request besides [linkId], on any connection. */
    private suspend fun otherOpenClosingPullRequest(
        issueId: UUID,
        linkId: UUID?,
    ): Boolean =
        databaseClient
            .sql(
                """
                select exists(
                    select 1 from issue_git_links
                    where issue_id = :issueId and kind = 'PULL_REQUEST' and closes and pr_state in ('OPEN', 'DRAFT') and id <> :linkId
                      and unlinked_at is null
                )
                """.trimIndent(),
            ).bind("issueId", issueId)
            .bind("linkId", linkId ?: UUID(0, 0))
            .map { row -> checkNotNull(row.get(0, Boolean::class.javaObjectType)) }
            .one()
            .awaitSingle()

    // --- storing --------------------------------------------------------------------------------

    /** Whether someone took this branch off the issue (D-161). */
    private suspend fun branchUnlinked(
        connectionId: UUID,
        issueId: UUID,
        repository: String,
        branch: String,
    ): Boolean =
        databaseClient
            .sql(
                """
                select exists(
                    select 1 from issue_git_links
                    where issue_id = :issueId and connection_id = :connectionId and repository = :repository
                      and kind = 'BRANCH' and branch = :branch and unlinked_at is not null
                )
                """.trimIndent(),
            ).bind("issueId", issueId)
            .bind("connectionId", connectionId)
            .bind("repository", repository)
            .bind("branch", branch)
            .map { row -> checkNotNull(row.get(0, Boolean::class.javaObjectType)) }
            .one()
            .awaitSingle()

    /**
     * Takes a branch or pull request off the issue (D-161), with the other links of the same
     * branch: a pull request and the branch it comes from go together, or the one would show again
     * in place of the other. The rows stay, marked, so that Git cannot link them again. Returns
     * what was taken off; empty when [linkId] is not one of the issue's links.
     */
    suspend fun unlink(
        issueId: UUID,
        linkId: UUID,
    ): List<Map<String, Any?>> =
        databaseClient
            .sql(
                """
                update issue_git_links l set unlinked_at = :now
                from issue_git_links target
                where target.id = :linkId and target.issue_id = :issueId and target.unlinked_at is null
                  and l.issue_id = target.issue_id and l.unlinked_at is null
                  and (l.id = target.id or (target.branch is not null and l.connection_id = target.connection_id
                       and l.repository = target.repository and l.branch = target.branch))
                returning l.kind, l.repository, l.branch, l.pr_number, l.title, l.url
                """.trimIndent(),
            ).bind("now", clock.instant())
            .bind("linkId", linkId)
            .bind("issueId", issueId)
            .map { row ->
                mapOf(
                    "kind" to row.get("kind", String::class.java),
                    "repository" to row.get("repository", String::class.java),
                    "branch" to row.get("branch", String::class.java),
                    "number" to row.get("pr_number", Int::class.javaObjectType),
                    "title" to row.get("title", String::class.java),
                    "url" to row.get("url", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()

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
        /** Null only when the row went away between the insert and the read. */
        val id: UUID?,
        val created: Boolean,
        /** The state stored before this event; null for a new link. */
        val previousState: GitPrState?,
        val closes: Boolean,
        /** Someone took it off the issue (D-161). */
        val unlinked: Boolean = false,
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
        val newId = UuidV7.generate(now)
        val inserted =
            databaseClient
                .sql(
                    """
                    insert into issue_git_links (id, issue_id, connection_id, kind, repository, branch, pr_number, title, url, pr_state, closes, created_at, updated_at)
                    values (:id, :issueId, :connectionId, 'PULL_REQUEST', :repository, :branch, :number, :title, :url, :state, :closes, :now, :now)
                    on conflict do nothing
                    """.trimIndent(),
                ).bind("id", newId)
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
        if (inserted > 0) return PullRequestLink(newId, created = true, previousState = null, closes = closes)

        val stored =
            databaseClient
                .sql(
                    """
                    select id, pr_state, closes, unlinked_at is not null as unlinked from issue_git_links
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
                    ) to checkNotNull(row.get("unlinked", Boolean::class.javaObjectType))
                }.one()
                .awaitSingleOrNull() ?: return PullRequestLink(null, created = false, previousState = pr.state, closes = closes)
        val (id, previousState, wasClosing) = stored.first
        if (stored.second) return PullRequestLink(id, created = false, previousState = previousState, closes = wasClosing, unlinked = true)
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
        return PullRequestLink(id, created = false, previousState = previousState, closes = wasClosing || closes)
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
        val keys: IssueKeys,
        val by: Actor? = null,
    ) {
        /** What each stage moves an issue to (D-158). */
        val rules = connection.rules()

        /** By number: the same issue may come up in several events. */
        val linked = linkedMapOf<Int, Issue>()
        val statusChanged = linkedSetOf<Int>()
        val changes = mutableListOf<Pair<Actor, IssueChange>>()
        var skipped = 0
        private val actors = mutableMapOf<GitPerson, Actor>()

        /**
         * Makes [repository] the issue's repository when nobody picked one yet (D-152, D-153): the
         * first branch or pull request tells where the work is. In a project that chose its
         * repositories only one of those counts. Never changes a pick that exists.
         */
        suspend fun pickRepository(
            issue: Issue,
            repository: String,
        ) {
            val repositories = issue.projectId?.let { projectRepositories?.get(it) }
            val ref = GitRepositoryRef(connection.id, repository.lowercase())
            if (repositories == null || ref in repositories) projectRepositoryService.pickIfUnset(issue.id, ref, clock.instant())
        }

        /** Projects that chose their repositories (D-148), read once per call. */
        private var projectRepositories: Map<UUID, Set<GitRepositoryRef>>? = null

        /**
         * The issues the numbers name that an event from [repository] may touch, at most
         * [MAX_ISSUES] for one event; the rest are counted as skipped. An issue that picked its
         * repository is touched only from it, an issue of a project that chose its repositories
         * only from those (D-148); any other issue from every repository (D-149).
         */
        suspend fun targets(
            numbers: List<Int>,
            repository: String,
        ): List<Issue> {
            val chosen = projectRepositories ?: projectRepositoryService.all().also { projectRepositories = it }
            val from = GitRepositoryRef(connection.id, repository.lowercase())
            val candidates = issueService.gitTargets(numbers.distinct())
            val picked = projectRepositoryService.issueRepositories(candidates.map { it.id })
            val found =
                candidates.filter { issue ->
                    val repositories = issue.projectId?.let { chosen[it] }
                    // A pick counts while it is still one of the project's repositories.
                    val pick = picked[issue.id]?.takeIf { repositories != null && it in repositories }
                    when {
                        pick != null -> from == pick
                        repositories != null -> from in repositories
                        else -> true
                    }
                }
            skipped += (found.size - MAX_ISSUES).coerceAtLeast(0)
            return found.take(MAX_ISSUES)
        }

        /** The Flowbit account of a Git user. */
        suspend fun actorOf(person: GitPerson): Actor =
            by ?: actors.getOrPut(person) {
                // The server's name goes along, so the history can say "GitHub" when it names nobody (D-153).
                val via = mapOf("source" to "git", "provider" to connection.provider.label, "name" to person.label)
                // The e-mail address (against e-mail addresses and login ids), then the GitHub account someone
                // connected themselves (D-150), then the Git user name as a login id (D-152).
                val user =
                    person.email?.let { userService.findActiveByEmailOrLoginId(it, null) }
                        ?: person.username
                            ?.takeIf { connection.provider == GitProviderType.GITHUB }
                            ?.let { githubAccounts.userIdOf(connection, it) }
                            ?.let { userService.findActive(it) }
                        ?: person.username?.let { userService.findActiveByEmailOrLoginId(null, it) }
                when {
                    user != null -> Actor(user.id, user.displayName ?: user.loginId, via)
                    person.label != null -> Actor(null, "${person.label}(${connection.provider.label})", via)
                    else -> Actor(null, connection.provider.label, via)
                }
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
