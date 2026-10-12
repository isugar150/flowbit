package com.flowbit.user.domain

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    /** Reads the user and holds a row lock until the surrounding transaction ends. */
    @Query("select * from users where login_id = :loginId for update")
    suspend fun findByLoginIdForUpdate(loginId: String): User?

    // Profile edits touch only their own columns, so they can never overwrite the
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
}
