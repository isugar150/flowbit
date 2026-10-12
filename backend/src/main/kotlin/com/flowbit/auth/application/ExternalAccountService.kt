package com.flowbit.auth.application

import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Who the directory or the identity provider says has just proved their identity. */
data class ExternalIdentity(
    /** The provider's own stable identifier (OIDC `sub`); null for LDAP, which is matched by login id. */
    val externalId: String?,
    val loginId: String,
    val email: String?,
    val displayName: String?,
)

enum class ExternalRefusal {
    /** The account exists here but an administrator disabled it. */
    DISABLED,

    /** The id belongs to someone else: another person's e-mail address, or another person of the same provider. */
    CONFLICT,

    /** There is no account yet and accounts are not created automatically. */
    NOT_REGISTERED,

    /** The id the provider gave cannot be stored as a login id. */
    INVALID_LOGIN_ID,
}

sealed interface ExternalSignIn {
    data class Success(
        val user: User,
        val sessionToken: String,
    ) : ExternalSignIn

    data class Refused(
        val reason: ExternalRefusal,
    ) : ExternalSignIn
}

/**
 * Signs in someone whose identity LDAP or single sign-on has already confirmed, creating their
 * account on the first visit (docs/features/M2b-ldap-and-sso.md, "자동 가입").
 */
@Service
class ExternalAccountService(
    private val userRepository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val sessionService: SessionService,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    /** Returns a result instead of throwing on refusal so the audit record written here is committed. */
    @Transactional
    suspend fun signIn(
        source: AuthSource,
        identity: ExternalIdentity,
        autoProvision: Boolean,
        client: ClientInfo,
        previousSessionToken: String?,
    ): ExternalSignIn {
        val loginId = CredentialPolicy.normalizeLoginId(identity.loginId)
        val now = clock.instant()
        val known =
            if (identity.externalId != null) {
                userRepository.findByExternalIdForUpdate(source.name, identity.externalId)
            } else {
                // LDAP accounts are told apart by login id; one that belongs to another source is a conflict below.
                userRepository.findForLogin(loginId)?.takeIf { it.authSource == source && it.loginId == loginId }
            }
        // An account that already answers to this login id, whatever it signed in with so far.
        val sameLoginId = if (known == null) userRepository.findForLogin(loginId)?.takeIf { it.loginId == loginId } else null
        val user =
            when {
                known != null -> known
                // Another person of the same provider under this id: an id is not proof of being them.
                sameLoginId != null && sameLoginId.authSource == source && sameLoginId.externalId != null ->
                    return refuse(ExternalRefusal.CONFLICT, source, loginId, client)
                sameLoginId != null -> convert(sameLoginId, source, identity, client)
                // The id is someone else's e-mail address.
                userRepository.existsByLoginIdOrEmail(loginId) -> return refuse(ExternalRefusal.CONFLICT, source, loginId, client)
                !autoProvision -> return refuse(ExternalRefusal.NOT_REGISTERED, source, loginId, client)
                !CredentialPolicy.isAcceptableExternalLoginId(loginId) -> return refuse(ExternalRefusal.INVALID_LOGIN_ID, source, loginId, client)
                else -> provision(source, identity, loginId, client)
            }
        if (!user.isActive) return refuse(ExternalRefusal.DISABLED, source, loginId, client)

        user.recordSuccessfulLogin(now)
        userRepository.save(user)
        previousSessionToken?.let { sessionService.endByToken(it) }
        val sessionToken = sessionService.start(user, client)
        auditService.record(AuditEventType.LOGIN_SUCCEEDED, user.id, client, mapOf("method" to source.name))
        return ExternalSignIn.Success(user, sessionToken)
    }

    /**
     * The account with this login id becomes an account of [source]: its password is checked there
     * from now on (docs/features/M2b-ldap-and-sso.md, "계정 전환"). Role, name, projects and
     * everything else it owns stay as they are. A disabled account is refused right after and stays as it was.
     */
    private suspend fun convert(
        user: User,
        source: AuthSource,
        identity: ExternalIdentity,
        client: ClientInfo,
    ): User {
        val from = user.authSource
        user.convertTo(source, identity.externalId, clock.instant())
        auditService.record(AuditEventType.MEMBER_CONVERTED, user.id, client, mapOf("from" to from.name, "to" to source.name))
        return user
    }

    private suspend fun provision(
        source: AuthSource,
        identity: ExternalIdentity,
        loginId: String,
        client: ClientInfo,
    ): User {
        val now = clock.instant()
        // An address someone else already signs in with stays theirs; the new account simply has none.
        val email =
            identity.email
                ?.let(CredentialPolicy::normalizeLoginId)
                ?.takeIf(CredentialPolicy::isEmail)
                ?.takeIf { it == loginId || !userRepository.existsByLoginIdOrEmail(it) }
        val displayName =
            identity.displayName
                ?.trim()
                ?.take(User.DISPLAY_NAME_MAX_LENGTH)
                ?.takeIf { it.isNotEmpty() }
        val user =
            entityTemplate
                .insert(User.external(UuidV7.generate(now), loginId, source, identity.externalId, email, UserRole.MEMBER, displayName, now))
                .awaitSingle()
        auditService.record(AuditEventType.MEMBER_PROVISIONED, user.id, client, mapOf("method" to source.name))
        return user
    }

    private suspend fun refuse(
        reason: ExternalRefusal,
        source: AuthSource,
        loginId: String,
        client: ClientInfo,
    ): ExternalSignIn {
        auditService.record(
            AuditEventType.LOGIN_FAILED,
            null,
            client,
            mapOf("loginId" to loginId.take(MAX_LOGGED_LOGIN_ID_LENGTH), "method" to source.name, "reason" to reason.name),
        )
        return ExternalSignIn.Refused(reason)
    }

    private companion object {
        const val MAX_LOGGED_LOGIN_ID_LENGTH = 64
    }
}
