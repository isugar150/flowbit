package com.flowbit.user.domain

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    /** Reads the user and holds a row lock until the surrounding transaction ends. */
    @Query("select * from users where login_id = :loginId for update")
    suspend fun findByLoginIdForUpdate(loginId: String): User?

    suspend fun existsByLoginId(loginId: String): Boolean

    @Query("select * from users order by created_at, login_id")
    fun findAllOrdered(): Flow<User>

    @Query("select * from users where status = 'ACTIVE' order by lower(coalesce(display_name, login_id))")
    fun findActiveOrdered(): Flow<User>

    // Edits below touch only their own columns, so they can never overwrite the
    // login counters that a concurrent sign-in is updating.

    @Modifying
    @Query("update users set display_name = :displayName, updated_at = :now where id = :id")
    suspend fun updateDisplayName(
        id: UUID,
        displayName: String?,
        now: Instant,
    ): Int

    @Modifying
    @Query("update users set avatar_key = :avatarKey, updated_at = :now where id = :id")
    suspend fun updateAvatarKey(
        id: UUID,
        avatarKey: String?,
        now: Instant,
    ): Int

    @Modifying
    @Query("update users set role = :role, updated_at = :now where id = :id")
    suspend fun updateRole(
        id: UUID,
        role: String,
        now: Instant,
    ): Int

    @Modifying
    @Query("update users set status = :status, updated_at = :now where id = :id")
    suspend fun updateStatus(
        id: UUID,
        status: String,
        now: Instant,
    ): Int

    /** A new password also clears any lockout left by attempts against the old one. */
    @Modifying
    @Query(
        "update users set password_hash = :passwordHash, failed_login_count = 0, locked_until = null, updated_at = :now where id = :id",
    )
    suspend fun updatePassword(
        id: UUID,
        passwordHash: String,
        now: Instant,
    ): Int
}
