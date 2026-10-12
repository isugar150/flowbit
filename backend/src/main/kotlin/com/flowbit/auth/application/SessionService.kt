package com.flowbit.auth.application

import com.flowbit.auth.domain.UserSessionRepository
import com.flowbit.common.UuidV7
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.application.UserService
import com.flowbit.user.domain.User
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Server-side sessions stored in PostgreSQL. The browser holds a random token in a cookie;
 * the database holds only its hash, so a leaked table cannot be replayed as cookies.
 */
@Service
class SessionService(
    private val repository: UserSessionRepository,
    private val userService: UserService,
    private val databaseClient: DatabaseClient,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** Starts a session and returns the token to put in the cookie. */
    suspend fun start(
        user: User,
        client: ClientInfo,
    ): String {
        val now = clock.instant()
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        var spec =
            databaseClient
                .sql(
                    """
                    insert into user_sessions (id, token_hash, user_id, created_at, last_seen_at, ip_address, user_agent)
                    values (:id, :tokenHash, :userId, :now, :now, cast(:ipAddress as inet), :userAgent)
                    """.trimIndent(),
                ).bind("id", UuidV7.generate(now))
                .bind("tokenHash", hash(token))
                .bind("userId", user.id)
                .bind("now", timestamp)
        spec = if (client.ipAddress != null) spec.bind("ipAddress", client.ipAddress) else spec.bindNull("ipAddress", String::class.java)
        spec = if (client.userAgent != null) spec.bind("userAgent", client.userAgent) else spec.bindNull("userAgent", String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
        repository.deleteExpired(now.minus(properties.sessionIdleTimeout), now.minus(properties.sessionMaxAge))
        return token
    }

    /** The user a cookie token belongs to, or null when the session is unknown, expired, or the user is disabled. */
    suspend fun resolve(token: String): AuthenticatedUser? {
        val session = repository.findByTokenHash(hash(token)) ?: return null
        val now = clock.instant()
        if (session.isExpired(now, properties.sessionIdleTimeout, properties.sessionMaxAge)) {
            repository.deleteById(session.id)
            return null
        }
        val user = userService.findActive(session.userId) ?: return null
        if (Duration.between(session.lastSeenAt, now) > TOUCH_INTERVAL) {
            repository.touch(session.id, now)
        }
        return AuthenticatedUser(user.id, user.loginId, user.role, session.id)
    }

    suspend fun end(sessionId: UUID) {
        repository.deleteById(sessionId)
    }

    suspend fun endByToken(token: String) {
        repository.deleteByTokenHash(hash(token))
    }

    private fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private companion object {
        const val TOKEN_BYTES = 32

        /** How stale `last_seen_at` may get before it is written again; avoids a write on every request. */
        val TOUCH_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
