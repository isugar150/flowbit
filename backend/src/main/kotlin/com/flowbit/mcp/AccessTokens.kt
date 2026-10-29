package com.flowbit.mcp

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

enum class TokenScope { READ, WRITE }

@Table("personal_access_tokens")
data class AccessToken(
    @Id val id: UUID,
    val userId: UUID,
    val name: String,
    val tokenHash: String,
    val scope: TokenScope,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant? = null,
)

interface AccessTokenRepository : CoroutineCrudRepository<AccessToken, UUID> {
    suspend fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<AccessToken>

    suspend fun findByTokenHash(tokenHash: String): AccessToken?

    @Modifying
    @Query("update personal_access_tokens set last_used_at = :now, updated_at = :now where id = :id")
    suspend fun touch(
        id: UUID,
        now: Instant,
    ): Int

    @Modifying
    @Query("delete from personal_access_tokens where id = :id and user_id = :userId")
    suspend fun revoke(
        id: UUID,
        userId: UUID,
    ): Int
}

data class AccessTokenView(
    val id: UUID,
    val name: String,
    val scope: TokenScope,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
) {
    companion object {
        fun from(token: AccessToken) = AccessTokenView(token.id, token.name, token.scope, token.createdAt, token.expiresAt, token.lastUsedAt)
    }
}

data class IssueTokenRequest(
    val name: String,
    val scope: TokenScope = TokenScope.READ,
    val expiresInDays: Int = 30,
)

data class IssuedToken(
    val token: String,
    val item: AccessTokenView,
)

data class TokenIdentity(
    val user: AuthenticatedUser,
    val scope: TokenScope,
)

@Service
class AccessTokens(
    private val repository: AccessTokenRepository,
    private val template: R2dbcEntityTemplate,
    private val users: UserService,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun list(user: AuthenticatedUser): List<AccessTokenView> = repository.findAllByUserIdOrderByCreatedAtDesc(user.id).map(AccessTokenView::from)

    suspend fun issue(
        user: AuthenticatedUser,
        input: IssueTokenRequest,
    ): IssuedToken {
        val name = input.name.trim()
        if (name.length !in 1..50) throw ValidationException("name", "INVALID", "이름은 1~50자로 입력하세요")
        if (input.expiresInDays !in 1..365) throw ValidationException("expiresInDays", "INVALID", "유효 기간은 1~365일로 입력하세요")
        val now = clock.instant()
        val token = "fb_mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val item = AccessToken(UuidV7.generate(now), user.id, name, hash(token), input.scope, now, now, now.plus(input.expiresInDays.toLong(), ChronoUnit.DAYS))
        template.insert(item).awaitSingle()
        return IssuedToken(token, AccessTokenView.from(item))
    }

    suspend fun revoke(
        user: AuthenticatedUser,
        id: UUID,
    ) {
        if (repository.revoke(id, user.id) == 0) throw NotFoundException("ACCESS_TOKEN_NOT_FOUND", "토큰을 찾을 수 없습니다")
    }

    suspend fun resolve(token: String): TokenIdentity? {
        if (!token.startsWith("fb_mcp_") || token.length != 50) return null
        val item = repository.findByTokenHash(hash(token)) ?: return null
        val now = clock.instant()
        if (!item.expiresAt.isAfter(now)) return null
        val user = users.findActive(item.userId) ?: return null
        if (item.lastUsedAt?.plusSeconds(60)?.isAfter(now) != true) repository.touch(item.id, now)
        return TokenIdentity(AuthenticatedUser(user.id, user.loginId, user.role, item.id), item.scope)
    }

    private fun hash(token: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
