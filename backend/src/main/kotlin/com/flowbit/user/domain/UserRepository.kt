package com.flowbit.user.domain

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    /**
     * The account someone signing in means: what they typed is a login id or an e-mail address.
     * A login id wins when both match. Holds a row lock until the surrounding transaction ends.
     */
    @Query(
        "select * from users where login_id = :identifier or email = :identifier " +
            "order by (login_id = :identifier) desc limit 1 for update",
    )
    suspend fun findForLogin(identifier: String): User?

    /** The same account as [findForLogin], read without locking it. */
    @Query("select * from users where login_id = :identifier or email = :identifier order by (login_id = :identifier) desc limit 1")
    suspend fun findByLoginIdOrEmail(identifier: String): User?

    @Query("select * from users where auth_source = :authSource and external_id = :externalId for update")
    suspend fun findByExternalIdForUpdate(
        authSource: String,
        externalId: String,
    ): User?

    /** Whether someone already signs in with this value, as their login id or as their e-mail address. */
    @Query("select exists(select 1 from users where login_id = :value or email = :value)")
    suspend fun existsByLoginIdOrEmail(value: String): Boolean

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

    /**
     * A new password also clears any lockout left by attempts against the old one, and makes the
     * account a local one again if it had been handed over to LDAP or single sign-on.
     */
    @Modifying
    @Query(
        "update users set password_hash = :passwordHash, auth_source = 'LOCAL', external_id = null, " +
            "failed_login_count = 0, locked_until = null, updated_at = :now where id = :id",
    )
    suspend fun updatePassword(
        id: UUID,
        passwordHash: String,
        now: Instant,
    ): Int

    @Modifying
    @Query("update users set email = :email, notify_email = :notifyEmail, updated_at = :now where id = :id")
    suspend fun updateEmailSettings(
        id: UUID,
        email: String?,
        notifyEmail: Boolean,
        now: Instant,
    ): Int

    /** Whether someone other than [id] already signs in with this value. */
    @Query("select exists(select 1 from users where (login_id = :value or email = :value) and id <> :id)")
    suspend fun isUsedByAnother(
        value: String,
        id: UUID,
    ): Boolean

    @Query("select * from users where id in (:ids) and status = 'ACTIVE'")
    fun findActiveByIds(ids: Collection<UUID>): Flow<User>
}
