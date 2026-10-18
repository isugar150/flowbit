package com.flowbit.git

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.api.IssueAnnouncer
import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueEvent
import com.flowbit.issue.application.IssueService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.util.UUID

/**
 * How a branch is made on a repository's server (D-159): through the GitHub API, on GitLab's
 * "new branch" page with the name filled in, or on Gitea, whose page cannot be filled in, by
 * copying the name and opening its branches.
 */
enum class GitBranchMode { API, PAGE, COPY }

/** A repository an issue's branch can be made in. */
data class GitBranchTarget(
    val connectionId: UUID,
    val connectionName: String,
    val provider: GitProviderType,
    /** org/web */
    val repository: String,
    /** The repository's page on the Git server. */
    val url: String,
    val mode: GitBranchMode,
    /** The repository the issue's work is in already (D-148, D-153); listed first. */
    val current: Boolean,
)

data class GitBranchRequest(
    val connectionId: UUID? = null,
    val repository: String = "",
    val branch: String = "",
)

data class GitBranchCreated(
    val repository: String,
    val branch: String,
    /** The branch on GitHub. */
    val url: String,
    /** The branch was there already; it is linked all the same. */
    val existed: Boolean,
)

/**
 * Makes an issue's branch from Flowbit (D-159). On GitHub the app makes it from the default
 * branch's head (it needs Contents write) and Flowbit links it at once, as if it had been pushed
 * by the person who asked: the branch entry, the repository, the assignee and the "branch" stage's
 * status. Elsewhere Flowbit has no token, so the screen sends the person to the Git server.
 */
@Service
class GitBranchService(
    private val connections: GitConnectionService,
    private val catalog: GitRepositoryCatalog,
    private val projectRepositories: GitProjectRepositoryService,
    private val issueService: IssueService,
    private val links: GitLinkService,
    private val keyMatcher: IssueKeyMatcher,
    private val client: GitHubClient,
    private val announcer: IssueAnnouncer,
    private val transactionalOperator: TransactionalOperator,
) {
    /**
     * Where the issue's branch can go: its project's repositories when it chose some, otherwise
     * every repository the connections know. The issue's own repository first. Nothing for an
     * issue on its own (D-162): which repository it belongs to is a project's to say.
     */
    suspend fun targets(
        user: AuthenticatedUser,
        number: Int,
    ): List<GitBranchTarget> {
        val issue = issueService.editableForGit(user, number)
        if (issue.projectId == null) return emptyList()
        val usable = connections.list().filter { it.enabled && (it.provider != GitProviderType.GITHUB || it.githubReady()) }.associateBy { it.id }
        val known = catalog.list(usable.keys).associateBy { GitRepositoryRef(it.connectionId, it.repository) }
        val chosen = issue.projectId?.let { projectRepositories.all()[it] }
        val refs = chosen?.filter { it.connectionId in usable } ?: known.keys.toList()
        val current = projectRepositories.issueRepositories(listOf(issue.id))[issue.id]
        return refs
            .map { ref ->
                val connection = usable.getValue(ref.connectionId)
                GitBranchTarget(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    provider = connection.provider,
                    repository = ref.repository,
                    url = known[ref]?.url ?: "${connection.baseUrl.trimEnd('/')}/${ref.repository}",
                    mode =
                        when (connection.provider) {
                            GitProviderType.GITHUB -> GitBranchMode.API
                            GitProviderType.GITLAB -> GitBranchMode.PAGE
                            GitProviderType.GITEA -> GitBranchMode.COPY
                        },
                    current = ref == current,
                )
            }.sortedWith(compareByDescending<GitBranchTarget> { it.current }.thenBy { it.repository })
    }

    /** Makes the branch on GitHub and links it to the issue. */
    suspend fun create(
        user: AuthenticatedUser,
        number: Int,
        request: GitBranchRequest,
    ): GitBranchCreated {
        val repository = request.repository.trim().lowercase()
        val branch = request.branch.trim()
        val target =
            targets(user, number).find { it.connectionId == request.connectionId && it.repository == repository }
                ?: throw ValidationException("repository", "NOT_AVAILABLE", "이 티켓의 브랜치를 만들 수 있는 저장소가 아닙니다")
        if (target.mode != GitBranchMode.API) throw ValidationException("repository", "UNSUPPORTED", "Flowbit 에서 바로 만들 수 있는 것은 GitHub 저장소입니다")
        val keys = keyMatcher.keys()
        val errors = mutableListOf<FieldError>()
        if (!validBranchName(branch)) {
            errors += FieldError("branch", "INVALID", "브랜치 이름으로 쓸 수 없습니다. 공백과 ~ ^ : ? * [ \\ 는 쓸 수 없습니다")
        } else if (number !in keys.numbers(branch)) {
            errors += FieldError("branch", "NO_KEY", "브랜치 이름에 ${keys.keyOf(number)} 가 있어야 이 티켓에 연결됩니다")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val connection = connections.find(target.connectionId) ?: throw NotFoundException("GIT_CONNECTION_NOT_FOUND", "Git 연결을 찾을 수 없습니다")

        val (defaultBranch, htmlUrl, existed) = makeOnGitHub(connection, repository, branch)
        // In a project that chose its repositories, the one the branch is in becomes the issue's, so
        // that its events reach it (D-148). Elsewhere linking the branch fills in an empty one.
        val projectId = issueService.editableForGit(user, number).projectId
        if (!target.current && projectId != null && projectRepositories.all()[projectId] != null) {
            issueService.setGitRepository(user, number, connection.id, repository)
        }
        val actor = Actor(user.id, issueService.actorName(user), mapOf("source" to "git", "provider" to connection.provider.label, "name" to user.loginId))
        val push =
            GitPush(
                repository = repository,
                branch = branch,
                defaultBranch = defaultBranch,
                deleted = false,
                branchUrl = "$htmlUrl/tree/$branch",
                pusher = GitPerson(username = user.loginId, email = null, name = null),
                commits = emptyList(),
            )
        val result = transactionalOperator.executeAndAwait { links.apply(connection, listOf(push), actor) }
        announcer.changedFromGit(result.changes)
        (result.touched - result.changes.map { it.second.issue.number }.toSet()).forEach { announcer.touched(user.id, it) }
        return GitBranchCreated(repository, branch, "$htmlUrl/tree/$branch", existed)
    }

    /**
     * Takes a branch or pull request off the issue (D-161): it stops showing, and Git can no longer
     * link it or move the issue through it. Needs the right to edit the issue. Recorded once, as
     * the pull request when one goes.
     */
    suspend fun unlink(
        user: AuthenticatedUser,
        number: Int,
        linkId: UUID,
    ) {
        val issue = issueService.editableForGit(user, number)
        val actor = Actor(user.id, issueService.actorName(user))
        transactionalOperator.executeAndAwait {
            val removed = links.unlink(issue.id, linkId)
            if (removed.isEmpty()) throw NotFoundException("GIT_LINK_NOT_FOUND", "연결된 브랜치나 PR 을 찾을 수 없습니다")
            val shown = removed.firstOrNull { it["kind"] == "PULL_REQUEST" } ?: removed.first()
            issueService.recordFromGit(issue.id, actor, listOf(IssueEvent(IssueActivityType.GIT_UNLINKED, shown)))
        }
        announcer.touched(user.id, number)
    }

    /** The default branch, the repository's page, and whether the branch was there already. */
    private suspend fun makeOnGitHub(
        connection: GitConnection,
        repository: String,
        branch: String,
    ): Triple<String, String, Boolean> {
        try {
            val repo = client.get(connection, "/repos/$repository").body
            val defaultBranch = repo?.let { text(it, "default_branch") } ?: throw GitHubException("저장소의 기본 브랜치를 알 수 없습니다")
            val htmlUrl = repo.let { text(it, "html_url") } ?: "${connection.baseUrl.trimEnd('/')}/$repository"
            val head = client.get(connection, "/repos/$repository/git/ref/heads/${encode(defaultBranch)}").body
            val sha = head?.path("object")?.let { text(it, "sha") } ?: throw GitHubException("기본 브랜치의 커밋을 알 수 없습니다")
            val ref = mapOf("ref" to "refs/heads/$branch", "sha" to sha)
            // An installation token keeps the permissions it was made with for its hour; after the
            // organization accepts Contents write, a refusal is tried once more with a new one.
            val made =
                client.post(connection, "/repos/$repository/git/refs", ref).let { first ->
                    if (first.status != 403 && first.status != 404) return@let first
                    client.forget(connection.id)
                    client.post(connection, "/repos/$repository/git/refs", ref)
                }
            return when {
                made.status == 201 -> Triple(defaultBranch, htmlUrl, false)
                made.status == 422 && client.messageOf(made).orEmpty().contains("already exists", ignoreCase = true) -> Triple(defaultBranch, htmlUrl, true)
                made.status == 403 || made.status == 404 ->
                    throw ConflictException(
                        "GITHUB_NO_WRITE",
                        "GitHub 앱에 저장소 쓰기 권한이 없습니다. 앱 설정 > Permissions 에서 Contents 를 'Read and write' 로 바꾼 뒤, 조직 소유자가 조직 설정 > GitHub Apps > 이 앱 > Configure 에서 'Review request' 를 눌러 새 권한을 승인해야 합니다",
                    )
                else -> throw GitHubException("브랜치를 만들지 못했습니다 (HTTP ${made.status}${client.messageOf(made)?.let { ": $it" }.orEmpty()})")
            }
        } catch (e: GitHubException) {
            throw GitHubUnavailableException(e.message ?: "GitHub 에 연결하지 못했습니다")
        }
    }

    private fun encode(ref: String) = URLEncoder.encode(ref, Charsets.UTF_8).replace("%2F", "/")

    companion object {
        private const val BRANCH_MAX_LENGTH = 200

        /** Git's rules for a branch name (git check-ref-format), the ones people run into. */
        fun validBranchName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= BRANCH_MAX_LENGTH &&
                name.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" } &&
                !name.startsWith("/") &&
                !name.endsWith("/") &&
                !name.startsWith("-") &&
                !name.endsWith(".") &&
                !name.endsWith(".lock") &&
                ".." !in name &&
                "//" !in name &&
                "@{" !in name &&
                name.split("/").none { it.startsWith(".") }
    }
}

@RestController
class GitBranchController(
    private val service: GitBranchService,
) {
    @GetMapping("/api/v1/issues/{number}/git/branch-targets")
    suspend fun targets(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): List<GitBranchTarget> = service.targets(user, number)

    @PostMapping("/api/v1/issues/{number}/git/branches")
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody request: GitBranchRequest,
    ): GitBranchCreated = service.create(user, number, request)

    @DeleteMapping("/api/v1/issues/{number}/git/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unlink(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @PathVariable linkId: UUID,
    ) = service.unlink(user, number, linkId)
}
