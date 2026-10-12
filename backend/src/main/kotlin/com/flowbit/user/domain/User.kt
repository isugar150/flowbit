package com.flowbit.user.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class UserRole { ADMIN, MEMBER }

enum class UserStatus { ACTIVE, DISABLED }

@Table("users")
class User(
    @Id
    val id: UUID,
    val loginId: String,
    var passwordHash: String,
    var role: UserRole,
    var status: UserStatus,
    var email: String?,
    var displayName: String?,
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

    fun recordSuccessfulLogin(now: Instant) {
        failedLoginCount = 0
        lockedUntil = null
        lastLoginAt = now
        updatedAt = now
    }

    companion object {
        fun newAdmin(
            id: UUID,
            loginId: String,
            passwordHash: String,
            now: Instant,
        ) = User(
            id = id,
            loginId = loginId,
            passwordHash = passwordHash,
            role = UserRole.ADMIN,
            status = UserStatus.ACTIVE,
            email = null,
            displayName = null,
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
