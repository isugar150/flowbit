package com.flowbit.auth.application

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.web.ClientInfo
import com.flowbit.instance.application.InstanceService
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

sealed interface LoginResult {
    data class Success(
        val user: User,
        val sessionToken: String,
    ) : LoginResult

    /** Unknown id, wrong password, locked and disabled accounts all look the same to the caller. */
    data object Rejected : LoginResult
}

/** What the password check against our own accounts concluded. */
private sealed interface PasswordOutcome {
    data class Decided(
        val result: LoginResult,
    ) : PasswordOutcome

    /** No account answers to this id. */
    data object AskDirectory : PasswordOutcome
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val instanceService: InstanceService,
    private val sessionService: SessionService,
    private val passwordHasher: PasswordHasher,
    private val externalAuthSettings: ExternalAuthSettingsService,
    private val ldapDirectory: LdapDirectory,
    private val externalAccounts: ExternalAccountService,
    private val auditService: AuditService,
    private val transactionalOperator: TransactionalOperator,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    /**
     * Signs in with an id (login id or e-mail address) and a password. Local accounts are checked
     * here; an id no local account answers to is tried against the LDAP directory, which creates
     * the account on the first sign-in (docs/features/M2b-ldap-and-sso.md).
     *
     * Returns a result instead of throwing on rejection so the failure count and audit record
     * written here are committed. The directory is asked outside any transaction: a slow LDAP
     * server must not hold a database connection or a row lock.
     *
     * @param previousSessionToken the session cookie the request arrived with, if any. It is ended on
     * success so a session id that existed before sign-in never survives it (session fixation).
     */
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
        // With LDAP switched on the directory is asked first, also for an id that has a local
        // account: a password the directory accepts turns that account into an LDAP account.
        val ldap = externalAuthSettings.ldap()
        if (ldap.enabled && mayAskDirectory(loginId, now = clock.instant())) {
            val identity = ldapDirectory.authenticate(ldap, loginId, password)
            if (identity != null) {
                return when (val signIn = externalAccounts.signIn(AuthSource.LDAP, identity, ldap.autoProvision, client, previousSessionToken)) {
                    is ExternalSignIn.Success -> LoginResult.Success(signIn.user, signIn.sessionToken)
                    is ExternalSignIn.Refused -> LoginResult.Rejected
                }
            }
        }
        // The directory did not vouch for them (or is off, or down): the password kept here decides.
        // This is what keeps a local administrator able to sign in while the directory is unreachable.
        val outcome = transactionalOperator.executeAndAwait { passwordLogin(loginId, password, client, previousSessionToken) }
        if (outcome is PasswordOutcome.Decided) return outcome.result
        passwordHasher.burnTime(password)
        recordFailure(null, loginId, client)
        return LoginResult.Rejected
    }

    /** A disabled or locked account is refused here; its password is not sent to the directory. */
    private suspend fun mayAskDirectory(
        loginId: String,
        now: Instant,
    ): Boolean {
        val user = userRepository.findByLoginIdOrEmail(loginId) ?: return true
        return user.isActive && !user.isLocked(now)
    }

    private suspend fun passwordLogin(
        loginId: String,
        password: String,
        client: ClientInfo,
        previousSessionToken: String?,
    ): PasswordOutcome {
        val now = clock.instant()
        val user = userRepository.findForLogin(loginId) ?: return PasswordOutcome.AskDirectory
        val passwordHash = user.passwordHash

        if (!user.isActive || user.isLocked(now)) {
            passwordHasher.burnTime(password)
            recordFailure(user.id, loginId, client)
            return PasswordOutcome.Decided(LoginResult.Rejected)
        }
        // LDAP and single sign-on accounts have no password to check here.
        if (passwordHash == null) {
            passwordHasher.burnTime(password)
            recordFailure(user.id, loginId, client)
            return PasswordOutcome.Decided(LoginResult.Rejected)
        }
        if (!passwordHasher.matches(password, passwordHash)) {
            val locked = user.recordFailedLogin(now, properties.maxFailedLogins, properties.lockDuration)
            userRepository.save(user)
            recordFailure(user.id, loginId, client)
            if (locked) {
                auditService.record(AuditEventType.ACCOUNT_LOCKED, user.id, client)
            }
            return PasswordOutcome.Decided(LoginResult.Rejected)
        }
        user.recordSuccessfulLogin(now)
        userRepository.save(user)
        previousSessionToken?.let { sessionService.endByToken(it) }
        val sessionToken = sessionService.start(user, client)
        auditService.record(AuditEventType.LOGIN_SUCCEEDED, user.id, client)
        return PasswordOutcome.Decided(LoginResult.Success(user, sessionToken))
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
