package com.flowbit.auth.application

import com.flowbit.user.domain.UserRole
import java.util.UUID

/**
 * The signed-in user for the current request, resolved from the session cookie on every request.
 *
 * Deliberately not a `java.security.Principal`: WebFlux would then inject the whole
 * `Authentication` into `@AuthenticationPrincipal` parameters instead of this object.
 */
data class AuthenticatedUser(
    val id: UUID,
    val loginId: String,
    val role: UserRole,
    val sessionId: UUID,
)
