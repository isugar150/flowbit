package com.flowbit.git

import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/** One member and the Git user they are, if an administrator said so. */
data class GitAccountView(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val username: String?,
    /** The GitHub account they connected themselves (D-150), which counts first for GitHub. */
    val githubLogin: String? = null,
)

data class GitAccountRequest(
    /** Empty or null takes the Git user name away. */
    val username: String? = null,
)

/**
 * The Git user name of each member (D-147). GitHub's pull requests carry no e-mail address, and a
 * github.com user name is rarely the company login id, so an administrator can say who is who.
 * Matching a Git user goes: e-mail address, then this, then the login id.
 */
@Service
class GitAccountService(
    private val databaseClient: DatabaseClient,
    private val userService: UserService,
    private val githubAccounts: GitHubAccountService,
    private val clock: Clock,
) {
    suspend fun list(): List<GitAccountView> {
        val names = all()
        val github = githubAccounts.logins()
        return userService.activeUsers().map { GitAccountView(it.id, it.loginId, it.displayName, names[it.id], github[it.id]) }
    }

    suspend fun set(
        userId: UUID,
        rawUsername: String?,
    ): GitAccountView {
        val user = userService.findActive(userId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        val username =
            rawUsername
                ?.trim()
                ?.removePrefix("@")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        if (username != null && !USERNAME.matches(username)) {
            throw ValidationException("username", "INVALID", "Git 아이디는 영문, 숫자, . _ - 로 된 100자 이하여야 합니다")
        }
        if (username == null) {
            databaseClient
                .sql("delete from git_user_accounts where user_id = :id")
                .bind("id", userId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else {
            try {
                databaseClient
                    .sql(
                        """
                        insert into git_user_accounts (user_id, username, updated_at) values (:id, :username, :now)
                        on conflict (user_id) do update set username = excluded.username, updated_at = excluded.updated_at
                        """.trimIndent(),
                    ).bind("id", userId)
                    .bind("username", username)
                    .bind("now", clock.instant())
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            } catch (_: DataIntegrityViolationException) {
                throw ConflictException("GIT_USERNAME_TAKEN", "다른 구성원이 이미 쓰는 Git 아이디입니다")
            }
        }
        return GitAccountView(user.id, user.loginId, user.displayName, username)
    }

    /** The member an administrator said is this Git user; null when nobody is. */
    suspend fun userIdOf(username: String): UUID? =
        databaseClient
            .sql("select user_id from git_user_accounts where username = :username")
            .bind("username", username.trim().lowercase())
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) }
            .one()
            .awaitSingleOrNull()

    private suspend fun all(): Map<UUID, String> =
        databaseClient
            .sql("select user_id, username from git_user_accounts")
            .map { row -> checkNotNull(row.get("user_id", UUID::class.java)) to checkNotNull(row.get("username", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    private companion object {
        val USERNAME = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
    }
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/git/accounts")
class GitAccountController(
    private val service: GitAccountService,
) {
    @GetMapping
    suspend fun list(): List<GitAccountView> = service.list()

    @PutMapping("/{userId}")
    suspend fun set(
        @PathVariable userId: UUID,
        @RequestBody body: GitAccountRequest,
    ): GitAccountView = service.set(userId, body.username)
}
