package com.flowbit.git

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/** A repository on one of the Git connections. */
data class GitRepositoryRef(
    val connectionId: UUID,
    /** org/web, lower case. */
    val repository: String,
)

/** A connection a project can take repositories from, as its managers see it. */
data class GitConnectionOption(
    val id: UUID,
    val name: String,
    val provider: GitProviderType,
)

/** What a project's Git settings show. */
data class ProjectGitSettings(
    /** Whether Git is in use at all; without it the settings are not shown (D-145). */
    val enabled: Boolean,
    val connections: List<GitConnectionOption>,
    val repositories: List<GitRepositoryRef>,
    /**
     * Repositories to pick from, by connection (D-151): a GitHub App's installed repositories, and
     * the repositories that sent events to any connection. The project's own are left out.
     */
    val available: List<GitRepositoryRef>,
)

data class ProjectGitRepositoriesRequest(
    val repositories: List<GitRepositoryRef> = emptyList(),
)

/**
 * The repositories each project's work happens in (D-148), set by the project's managers. A
 * project with repositories takes Git events only from them, whatever the connection's allowed
 * projects say; a project without any follows the connection's allowed projects.
 */
@Service
class GitProjectRepositoryService(
    private val databaseClient: DatabaseClient,
    private val connections: GitConnectionRepository,
    private val connectionService: GitConnectionService,
    private val catalog: GitRepositoryCatalog,
    private val projectService: ProjectService,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {
    /** For anyone who may enter the project. */
    suspend fun settings(
        user: AuthenticatedUser,
        projectId: String,
        refresh: Boolean = false,
    ): ProjectGitSettings {
        val project = projectService.access(user, projectId).project
        val enabled = connections.findAllOrdered().toList().filter { it.enabled }
        // GitHub can be asked for its list; asked again at most once a minute unless [refresh].
        enabled.filter { it.provider == GitProviderType.GITHUB }.forEach { catalog.refreshGitHub(it, force = refresh) }
        val options = enabled.map { GitConnectionOption(it.id, it.name, it.provider) }
        val repositories = repositoriesOf(project.id)
        val seen =
            databaseClient
                .sql(
                    """
                    select distinct r.connection_id, lower(r.repository) as repository
                    from (select connection_id, repository from issue_git_links
                          union select connection_id, repository from issue_git_commits) r
                    """.trimIndent(),
                ).map { row ->
                    GitRepositoryRef(checkNotNull(row.get("connection_id", UUID::class.java)), checkNotNull(row.get("repository", String::class.java)))
                }.all()
                .collectList()
                .awaitSingle()
        val known = catalog.list(enabled.map { it.id }).map { GitRepositoryRef(it.connectionId, it.repository) }
        val enabledIds = enabled.map { it.id }.toSet()
        val available =
            (known + seen)
                .filter { it.connectionId in enabledIds && it !in repositories }
                .distinct()
                .sortedBy { it.repository }
        return ProjectGitSettings(connectionService.inUse(), options, repositories, available)
    }

    /** Replaces the project's repositories. Its managers only. */
    suspend fun replace(
        user: AuthenticatedUser,
        projectId: String,
        input: List<GitRepositoryRef>,
    ): ProjectGitSettings {
        val access = projectService.access(user, projectId)
        if (!access.role.canManage) throw ForbiddenException("프로젝트를 관리할 권한이 없습니다")
        val known =
            connections
                .findAllOrdered()
                .toList()
                .map { it.id }
                .toSet()
        val repositories =
            input
                .map {
                    it.copy(
                        repository =
                            it.repository
                                .trim()
                                .trim('/')
                                .lowercase(),
                    )
                }.distinct()
        if (repositories.size > MAX_REPOSITORIES) {
            throw ValidationException("repositories", "TOO_MANY", "저장소는 ${MAX_REPOSITORIES}개까지 정할 수 있습니다")
        }
        repositories.forEachIndexed { index, ref ->
            if (ref.connectionId !in known) throw ValidationException("repositories[$index].connectionId", "UNKNOWN_CONNECTION", "없는 Git 연결입니다")
            if (!REPOSITORY.matches(ref.repository)) {
                throw ValidationException("repositories[$index].repository", "INVALID", "저장소는 '조직/저장소' 모양으로 입력하세요 (예: acme/web)")
            }
        }
        transactionalOperator.executeAndAwait {
            databaseClient
                .sql("delete from project_git_repositories where project_id = :id")
                .bind("id", access.project.id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            val now = clock.instant()
            // Each one a microsecond after the one before, so that the order they were chosen in is kept (the column is that order).
            repositories.forEachIndexed { index, ref ->
                databaseClient
                    .sql(
                        "insert into project_git_repositories (project_id, connection_id, repository, created_at) values (:project, :connection, :repository, :now)",
                    ).bind("project", access.project.id)
                    .bind("connection", ref.connectionId)
                    .bind("repository", ref.repository)
                    .bind("now", now.plus(index.toLong(), java.time.temporal.ChronoUnit.MICROS))
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
        }
        return settings(user, projectId)
    }

    /** Every project that set repositories, with them; for deciding which issues an event may touch. */
    suspend fun all(): Map<UUID, Set<GitRepositoryRef>> =
        databaseClient
            .sql("select project_id, connection_id, repository from project_git_repositories")
            .map { row ->
                checkNotNull(row.get("project_id", UUID::class.java)) to
                    GitRepositoryRef(checkNotNull(row.get("connection_id", UUID::class.java)), checkNotNull(row.get("repository", String::class.java)))
            }.all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    /** Picks the issue's repository unless one is picked already (D-152). */
    suspend fun pickIfUnset(
        issueId: UUID,
        ref: GitRepositoryRef,
        now: java.time.Instant,
    ) {
        databaseClient
            .sql(
                """
                insert into issue_git_repositories (issue_id, connection_id, repository, updated_at) values (:issue, :connection, :repository, :now)
                on conflict (issue_id) do nothing
                """.trimIndent(),
            ).bind("issue", issueId)
            .bind("connection", ref.connectionId)
            .bind("repository", ref.repository)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The repositories picked on these issues (D-148). */
    suspend fun issueRepositories(issueIds: Collection<UUID>): Map<UUID, GitRepositoryRef> {
        if (issueIds.isEmpty()) return emptyMap()
        return databaseClient
            .sql("select issue_id, connection_id, repository from issue_git_repositories where issue_id in (:ids)")
            .bind("ids", issueIds)
            .map { row ->
                checkNotNull(row.get("issue_id", UUID::class.java)) to
                    GitRepositoryRef(checkNotNull(row.get("connection_id", UUID::class.java)), checkNotNull(row.get("repository", String::class.java)))
            }.all()
            .collectList()
            .awaitSingle()
            .toMap()
    }

    private suspend fun repositoriesOf(projectId: UUID): List<GitRepositoryRef> =
        databaseClient
            .sql("select connection_id, repository from project_git_repositories where project_id = :id order by created_at, repository")
            .bind("id", projectId)
            .map { row -> GitRepositoryRef(checkNotNull(row.get("connection_id", UUID::class.java)), checkNotNull(row.get("repository", String::class.java))) }
            .all()
            .collectList()
            .awaitSingle()

    private companion object {
        const val MAX_REPOSITORIES = 50

        /** org/web, or deeper for GitLab's subgroups (group/sub/web). */
        val REPOSITORY = Regex("^[a-z0-9_.-]+(/[a-z0-9_.-]+)+$")
    }
}

/** A project's Git repositories; access is decided by ProjectService. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/git")
class ProjectGitController(
    private val service: GitProjectRepositoryService,
) {
    /** With `refresh=true`, GitHub Apps' repository lists are read from GitHub now (D-151). */
    @GetMapping
    suspend fun settings(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "false") refresh: Boolean,
    ): ProjectGitSettings = service.settings(user, projectId, refresh)

    @PutMapping("/repositories")
    suspend fun replace(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: ProjectGitRepositoriesRequest,
    ): ProjectGitSettings = service.replace(user, projectId, body.repositories)
}
