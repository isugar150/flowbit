package com.flowbit.user.application

import com.flowbit.common.UuidV7
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.instance.application.InstanceService
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** What the signed-in user sees about themselves. */
data class UserProfile(
    val id: UUID,
    val loginId: String,
    val role: UserRole,
    val locale: String,
    val timezone: String,
)

@Service
class UserService(
    private val repository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val instanceService: InstanceService,
    private val clock: Clock,
) {
    suspend fun createAdmin(
        loginId: String,
        passwordHash: String,
    ): User {
        val now = clock.instant()
        // Ids are assigned here, so `save` would issue an UPDATE; insert explicitly.
        return entityTemplate.insert(User.newAdmin(UuidV7.generate(now), loginId, passwordHash, now)).awaitSingle()
    }

    suspend fun findActive(userId: UUID): User? = repository.findById(userId)?.takeIf { it.isActive }

    /** Profile of an active user. A deleted or disabled account is treated as signed out. */
    suspend fun profile(userId: UUID): UserProfile = profileOf(findActive(userId) ?: throw UnauthenticatedException())

    suspend fun profileOf(user: User): UserProfile {
        val defaults = instanceService.defaults()
        return UserProfile(
            id = user.id,
            loginId = user.loginId,
            role = user.role,
            locale = user.locale ?: defaults.locale,
            timezone = user.timezone ?: defaults.timezone,
        )
    }
}
