package com.flowbit.user.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.ExternalAuthSettingsService
import com.flowbit.auth.application.PasswordHasher
import com.flowbit.auth.application.SessionRevoker
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * A member changing their own password (docs/features/my-password.md). It is offered only while the
 * administrator has not turned LDAP on, and only for accounts whose password is kept here.
 */
@Service
class PasswordChangeService(
    private val repository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val externalAuthSettings: ExternalAuthSettingsService,
    private val sessionRevoker: SessionRevoker,
    private val rateLimiter: RateLimiter,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    /** Whether the settings page shows the form to this person. */
    suspend fun isAvailable(userId: UUID): Boolean {
        val user = repository.findById(userId)?.takeIf { it.isActive } ?: return false
        return keepsPasswordHere(user) && !externalAuthSettings.ldap().enabled
    }

    suspend fun change(
        actor: AuthenticatedUser,
        currentPassword: String,
        newPassword: String,
        client: ClientInfo,
    ) {
        // Every try counts, so the current password cannot be guessed through this form.
        rateLimiter.check("password-change", actor.id.toString(), MAX_TRIES, TRY_WINDOW)
        val user = repository.findById(actor.id)?.takeIf { it.isActive } ?: throw UnauthenticatedException()
        if (externalAuthSettings.ldap().enabled) {
            throw ConflictException("PASSWORD_IN_DIRECTORY", "LDAP 을 사용하는 동안에는 여기서 비밀번호를 바꿀 수 없습니다. LDAP 에서 바꿉니다")
        }
        val hash = user.passwordHash
        if (!keepsPasswordHere(user) || hash == null) {
            throw ConflictException("EXTERNAL_ACCOUNT", "LDAP 또는 SSO 로 로그인하는 계정의 비밀번호는 여기서 바꿀 수 없습니다")
        }
        if (!passwordHasher.matches(currentPassword, hash)) {
            throw ValidationException(listOf(FieldError("currentPassword", "INCORRECT", "현재 비밀번호가 올바르지 않습니다")))
        }
        val errors =
            CredentialPolicy
                .validate(user.loginId, newPassword)
                .filter { it.field == "password" }
                .map { it.copy(field = "newPassword") }
                .toMutableList()
        if (newPassword == currentPassword) {
            errors += FieldError("newPassword", "SAME_AS_CURRENT", "현재 비밀번호와 다른 비밀번호를 입력하세요")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)

        repository.updatePassword(user.id, passwordHasher.hash(newPassword), clock.instant())
        // Whoever else knew the old password is signed out; this browser stays signed in.
        sessionRevoker.revokeOthers(user.id, actor.sessionId)
        auditService.record(AuditEventType.MEMBER_PASSWORD_CHANGED, actor.id, client, mapOf("memberId" to user.id.toString()))
    }

    private fun keepsPasswordHere(user: User): Boolean = user.authSource == AuthSource.LOCAL

    private companion object {
        const val MAX_TRIES = 10
        val TRY_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
