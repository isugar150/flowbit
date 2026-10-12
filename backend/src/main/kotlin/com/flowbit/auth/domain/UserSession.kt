package com.flowbit.auth.domain

import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One signed-in browser. `ip_address` and `user_agent` are written with SQL in SessionService. */
@Table("user_sessions")
class UserSession(
    @Id
    val id: UUID,
    val tokenHash: String,
    val userId: UUID,
    val createdAt: Instant,
    val lastSeenAt: Instant,
) {
    fun isExpired(
        now: Instant,
        idleTimeout: Duration,
        maxAge: Duration,
    ): Boolean = !lastSeenAt.plus(idleTimeout).isAfter(now) || !createdAt.plus(maxAge).isAfter(now)
}

interface UserSessionRepository : CoroutineCrudRepository<UserSession, UUID> {
    @Query("select id, token_hash, user_id, created_at, last_seen_at from user_sessions where token_hash = :tokenHash")
    suspend fun findByTokenHash(tokenHash: String): UserSession?

    @Modifying
    @Query("update user_sessions set last_seen_at = :now where id = :id")
    suspend fun touch(
        id: UUID,
        now: Instant,
    ): Int

    @Modifying
    @Query("delete from user_sessions where token_hash = :tokenHash")
    suspend fun deleteByTokenHash(tokenHash: String): Int

    @Modifying
    @Query("delete from user_sessions where last_seen_at < :idleBefore or created_at < :createdBefore")
    suspend fun deleteExpired(
        idleBefore: Instant,
        createdBefore: Instant,
    ): Int
}
