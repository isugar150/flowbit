package com.flowbit.setup.application

import com.flowbit.auth.application.PasswordHasher
import com.flowbit.auth.application.SessionService
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.instance.application.InstanceService
import com.flowbit.user.application.UserService
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

data class SetupResult(
    val admin: User,
    val sessionToken: String,
)

@Service
class SetupService(
    private val instanceService: InstanceService,
    private val userService: UserService,
    private val sessionService: SessionService,
    private val passwordHasher: PasswordHasher,
    private val auditService: AuditService,
    private val transactionalOperator: TransactionalOperator,
) {
    suspend fun isCompleted(): Boolean = instanceService.isSetupCompleted()

    /**
     * Creates the first administrator, names the workspace, signs the administrator in, and closes setup for good.
     * Succeeds at most once, even when called concurrently.
     */
    suspend fun complete(
        rawLoginId: String,
        password: String,
        rawWorkspaceName: String?,
        client: ClientInfo,
    ): SetupResult {
        if (instanceService.isSetupCompleted()) {
            throw instanceService.setupAlreadyCompleted()
        }
        val loginId = CredentialPolicy.normalizeLoginId(rawLoginId)
        val workspaceName = rawWorkspaceName?.trim()?.ifEmpty { null }
        val errors =
            CredentialPolicy.validate(loginId, password) +
                listOfNotNull(workspaceName?.let { instanceService.workspaceNameError(it, "workspaceName") })
        if (errors.isNotEmpty()) {
            throw ValidationException(errors)
        }
        // Hashing is slow on purpose; keep it outside the transaction that holds the settings row lock.
        val passwordHash = passwordHasher.hash(password)
        return transactionalOperator.executeAndAwait {
            instanceService.completeSetup(workspaceName)
            val admin = userService.createAdmin(loginId, passwordHash)
            val sessionToken = sessionService.start(admin, client)
            auditService.record(AuditEventType.SETUP_COMPLETED, admin.id, client)
            SetupResult(admin, sessionToken)
        }
    }
}
