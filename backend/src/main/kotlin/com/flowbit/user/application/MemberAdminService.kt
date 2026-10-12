package com.flowbit.user.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.ExternalAuthSettingsService
import com.flowbit.auth.application.PasswordHasher
import com.flowbit.auth.application.SessionRevoker
import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import com.flowbit.user.domain.UserRole
import com.flowbit.user.domain.UserStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Workspace administrators managing the people who can sign in
 * (docs/features/M3-projects-and-issues.md, "구성원 관리"). Callers are already known to be
 * administrators: every endpoint that reaches this sits under `/api/v1/admin`.
 */
@Service
class MemberAdminService(
    private val repository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val passwordHasher: PasswordHasher,
    private val externalAuthSettings: ExternalAuthSettingsService,
    private val sessionRevoker: SessionRevoker,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    suspend fun list(): List<User> = repository.findAllOrdered().toList()

    suspend fun create(
        actor: AuthenticatedUser,
        rawLoginId: String,
        password: String,
        rawDisplayName: String?,
        role: UserRole,
        client: ClientInfo,
    ): User {
        // With LDAP on, people come from the directory: they get an account by signing in.
        if (externalAuthSettings.ldap().enabled) {
            throw ConflictException("MEMBERS_FROM_DIRECTORY", "LDAP 을 사용하는 동안에는 구성원을 직접 추가할 수 없습니다. LDAP 계정으로 처음 로그인하면 구성원이 됩니다")
        }
        val loginId = CredentialPolicy.normalizeLoginId(rawLoginId)
        val displayName = rawDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        val errors = CredentialPolicy.validate(loginId, password).toMutableList()
        if (displayName != null && displayName.length > User.DISPLAY_NAME_MAX_LENGTH) {
            errors += UserService.displayNameTooLong()
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (repository.existsByLoginIdOrEmail(loginId)) throw loginIdTaken()

        val now = clock.instant()
        val user = User.create(UuidV7.generate(now), loginId, passwordHasher.hash(password), role, displayName, now)
        val created =
            try {
                entityTemplate.insert(user).awaitSingle()
            } catch (_: DuplicateKeyException) {
                // Two administrators creating the same id at once: the unique constraint decides.
                throw loginIdTaken()
            }
        auditService.record(AuditEventType.MEMBER_CREATED, actor.id, client, mapOf("memberId" to created.id.toString()))
        return created
    }

    /** Changes role and/or status. Administrators cannot change themselves, so one always remains. */
    @Transactional
    suspend fun update(
        actor: AuthenticatedUser,
        memberId: UUID,
        role: UserRole?,
        status: UserStatus?,
        client: ClientInfo,
    ): User {
        val member = find(memberId)
        if (member.id == actor.id) {
            throw ConflictException("CANNOT_CHANGE_SELF", "자신의 역할이나 상태는 바꿀 수 없습니다. 다른 관리자에게 요청하세요")
        }
        val now = clock.instant()
        val detail = mapOf("memberId" to memberId.toString())
        if (role != null && role != member.role) {
            repository.updateRole(memberId, role.name, now)
            auditService.record(AuditEventType.MEMBER_ROLE_CHANGED, actor.id, client, detail + ("role" to role.name))
        }
        if (status != null && status != member.status) {
            repository.updateStatus(memberId, status.name, now)
            if (status == UserStatus.DISABLED) {
                sessionRevoker.revokeAll(memberId)
                auditService.record(AuditEventType.MEMBER_DISABLED, actor.id, client, detail)
            } else {
                auditService.record(AuditEventType.MEMBER_ENABLED, actor.id, client, detail)
            }
        }
        return find(memberId)
    }

    /**
     * Sets a new password, clears any lockout, and signs the member out everywhere. An account that
     * signed in through LDAP or single sign-on becomes a local one again.
     */
    suspend fun resetPassword(
        actor: AuthenticatedUser,
        memberId: UUID,
        password: String,
        client: ClientInfo,
    ) {
        val member = find(memberId)
        if (externalAuthSettings.ldap().enabled) {
            // Everyone's password lives in the directory; only administrators keep (or get back) one
            // here, so that someone can still sign in when the directory is unreachable.
            if (member.role != UserRole.ADMIN) {
                throw ConflictException("PASSWORD_IN_DIRECTORY", "LDAP 을 사용하는 동안에는 관리자 계정의 비밀번호만 재설정할 수 있습니다. 다른 구성원의 비밀번호는 LDAP 에서 바꿉니다")
            }
        } else if (member.authSource != AuthSource.LOCAL) {
            throw ConflictException("EXTERNAL_ACCOUNT", "LDAP 또는 SSO 로 로그인하는 계정의 비밀번호는 여기서 바꿀 수 없습니다")
        }
        val errors = CredentialPolicy.validate(member.loginId, password)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        repository.updatePassword(memberId, passwordHasher.hash(password), clock.instant())
        sessionRevoker.revokeAll(memberId)
        auditService.record(
            AuditEventType.MEMBER_PASSWORD_RESET,
            actor.id,
            client,
            mapOf("memberId" to memberId.toString()),
        )
    }

    private suspend fun find(memberId: UUID): User = repository.findById(memberId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")

    private fun loginIdTaken() = ConflictException("LOGIN_ID_TAKEN", "이미 쓰고 있는 아이디입니다")
}
