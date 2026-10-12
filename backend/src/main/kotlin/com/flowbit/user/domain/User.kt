package com.flowbit.user.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class UserRole { ADMIN, MEMBER }

enum class UserStatus { ACTIVE, DISABLED }

/** Where an account's password is checked: here, in the LDAP directory, or by the single sign-on provider. */
enum class AuthSource { LOCAL, LDAP, SSO }

@Table("users")
class User(
    @Id
    val id: UUID,
    val loginId: String,
    /** Null for accounts that sign in through LDAP or single sign-on. */
    var passwordHash: String?,
    var authSource: AuthSource,
    /** The provider's own identifier for the person (OIDC `sub`). */
    var externalId: String?,
    var role: UserRole,
    var status: UserStatus,
    var email: String?,
    /** Whether mail about followed issues goes to [email]. */
    var notifyEmail: Boolean,
    var displayName: String?,
    /** File name of the profile image in storage, see ImageStorage. */
    var avatarKey: String?,
    var locale: String?,
    var timezone: String?,
    var failedLoginCount: Int,
    var lockedUntil: Instant?,
    var lastLoginAt: Instant?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    val isActive: Boolean
        get() = status == UserStatus.ACTIVE

    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    /** Returns true when this failure locked the account. */
    fun recordFailedLogin(
        now: Instant,
        maxFailures: Int,
        lockDuration: Duration,
    ): Boolean {
        updatedAt = now
        failedLoginCount += 1
        if (failedLoginCount < maxFailures) {
            return false
        }
        failedLoginCount = 0
        lockedUntil = now.plus(lockDuration)
        return true
    }

    /**
     * Hands the account over to a directory or an identity provider: from now on the password is
     * checked there, and the one kept here is forgotten.
     */
    fun convertTo(
        source: AuthSource,
        externalId: String?,
        now: Instant,
    ) {
        require(source != AuthSource.LOCAL) { "Use a password reset to make an account local" }
        authSource = source
        this.externalId = externalId
        passwordHash = null
        updatedAt = now
    }

    fun recordSuccessfulLogin(now: Instant) {
        failedLoginCount = 0
        lockedUntil = null
        lastLoginAt = now
        updatedAt = now
    }

    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 50

        fun create(
            id: UUID,
            loginId: String,
            passwordHash: String,
            role: UserRole,
            displayName: String?,
            now: Instant,
        ) = external(id, loginId, AuthSource.LOCAL, null, loginId.takeIf(CredentialPolicy::isEmail), role, displayName, now, passwordHash)

        /** An account whose password lives elsewhere; [passwordHash] is only given for local accounts. */
        fun external(
            id: UUID,
            loginId: String,
            authSource: AuthSource,
            externalId: String?,
            email: String?,
            role: UserRole,
            displayName: String?,
            now: Instant,
            passwordHash: String? = null,
        ) = User(
            id = id,
            loginId = loginId,
            passwordHash = passwordHash,
            authSource = authSource,
            externalId = externalId,
            role = role,
            status = UserStatus.ACTIVE,
            email = email,
            notifyEmail = true,
            displayName = displayName,
            avatarKey = null,
            locale = null,
            timezone = null,
            failedLoginCount = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
