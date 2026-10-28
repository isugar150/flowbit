package com.flowbit.auth.application

import com.flowbit.auth.domain.UserSessionRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Ends every session of one user, for example when their account is disabled or their password
 * is reset. Separate from SessionService so that user management can depend on it without a cycle.
 */
@Component
class SessionRevoker(
    private val repository: UserSessionRepository,
) {
    suspend fun revokeAll(userId: UUID) {
        repository.deleteByUserId(userId)
    }

    /** Signs the user out everywhere except the browser that is changing their password. */
    suspend fun revokeOthers(
        userId: UUID,
        keepSessionId: UUID,
    ) {
        repository.deleteByUserIdExcept(userId, keepSessionId)
    }
}
