package com.flowbit.auth.application

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.web.ClientInfo
import com.flowbit.instance.application.InstanceService
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

sealed interface LoginResult {
    data class Success(
        val user: User,
        val sessionToken: String,
    ) : LoginResult

    /** Unknown id, wrong password, locked and disabled accounts all look the same to the caller. */
    data object Rejected : LoginResult
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val instanceService: InstanceService,
    private val sessionService: SessionService,
    private val passwordHasher: PasswordHasher,
    private val auditService: AuditService,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    /**
     * Returns a result instead of throwing on rejection so the failure count and audit record
     * written here are committed.
     *
     * @param previousSessionToken the session cookie the request arrived with, if any. It is ended on
     * success so a session id that existed before sign-in never survives it (session fixation).
     */
    @Transactional
    suspend fun login(
        rawLoginId: String,
        password: String,
        client: ClientInfo,
        previousSessionToken: String?,
    ): LoginResult {
        if (!instanceService.isSetupCompleted()) {
            throw ConflictException("SETUP_REQUIRED", "초기 설정을 먼저 끝내야 합니다")
        }
        val loginId = CredentialPolicy.normalizeLoginId(rawLoginId)
        val now = clock.instant()
        val user = userRepository.findByLoginIdForUpdate(loginId)

        if (user == null || !user.isActive || user.isLocked(now)) {
            passwordHasher.burnTime(password)
            recordFailure(user?.id, loginId, client)
            return LoginResult.Rejected
        }
        if (!passwordHasher.matches(password, user.passwordHash)) {
            val locked = user.recordFailedLogin(now, properties.maxFailedLogins, properties.lockDuration)
            userRepository.save(user)
            recordFailure(user.id, loginId, client)
            if (locked) {
                auditService.record(AuditEventType.ACCOUNT_LOCKED, user.id, client)
            }
            return LoginResult.Rejected
        }
        user.recordSuccessfulLogin(now)
        userRepository.save(user)
        previousSessionToken?.let { sessionService.endByToken(it) }
        val sessionToken = sessionService.start(user, client)
        auditService.record(AuditEventType.LOGIN_SUCCEEDED, user.id, client)
        return LoginResult.Success(user, sessionToken)
    }

    @Transactional
    suspend fun logout(
        principal: AuthenticatedUser,
        client: ClientInfo,
    ) {
        sessionService.end(principal.sessionId)
        auditService.record(AuditEventType.LOGOUT, principal.id, client)
    }

    private suspend fun recordFailure(
        userId: UUID?,
        loginId: String,
        client: ClientInfo,
    ) {
        auditService.record(
            AuditEventType.LOGIN_FAILED,
            userId,
            client,
            mapOf("loginId" to loginId.take(MAX_LOGGED_LOGIN_ID_LENGTH)),
        )
    }

    private companion object {
        const val MAX_LOGGED_LOGIN_ID_LENGTH = 64
    }
}
