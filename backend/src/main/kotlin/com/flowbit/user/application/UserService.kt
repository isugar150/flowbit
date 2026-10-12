package com.flowbit.user.application

import com.flowbit.common.UuidV7
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.common.error.ValidationException
import com.flowbit.instance.application.InstanceService
import com.flowbit.media.application.ImageProcessor
import com.flowbit.media.application.ImageSpec
import com.flowbit.media.application.ImageStorage
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
    val displayName: String?,
    val avatarKey: String?,
    val role: UserRole,
    val locale: String,
    val timezone: String,
)

@Service
class UserService(
    private val repository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val instanceService: InstanceService,
    private val imageProcessor: ImageProcessor,
    private val imageStorage: ImageStorage,
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
    suspend fun profile(userId: UUID): UserProfile = profileOf(requireActive(userId))

    suspend fun profileOf(user: User): UserProfile {
        val defaults = instanceService.defaults()
        return UserProfile(
            id = user.id,
            loginId = user.loginId,
            displayName = user.displayName,
            avatarKey = user.avatarKey,
            role = user.role,
            locale = user.locale ?: defaults.locale,
            timezone = user.timezone ?: defaults.timezone,
        )
    }

    /** Sets the name shown instead of the login id. Blank clears it. */
    suspend fun updateDisplayName(
        userId: UUID,
        rawDisplayName: String?,
    ): UserProfile {
        requireActive(userId)
        val displayName = rawDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        if (displayName != null && displayName.length > User.DISPLAY_NAME_MAX_LENGTH) {
            throw ValidationException(
                listOf(FieldError("displayName", "TOO_LONG", "이름은 ${User.DISPLAY_NAME_MAX_LENGTH}자 이하여야 합니다")),
            )
        }
        repository.updateDisplayName(userId, displayName, clock.instant())
        return profile(userId)
    }

    /** Replaces the profile image with the upload, converted to a 256px WebP. */
    suspend fun replaceAvatar(
        userId: UUID,
        upload: ByteArray,
    ): UserProfile {
        val previous = requireActive(userId).avatarKey
        val key = imageStorage.store(imageProcessor.toWebp(upload, ImageSpec.AVATAR))
        repository.updateAvatarKey(userId, key, clock.instant())
        imageStorage.delete(previous)
        return profile(userId)
    }

    suspend fun removeAvatar(userId: UUID): UserProfile {
        val previous = requireActive(userId).avatarKey
        repository.updateAvatarKey(userId, null, clock.instant())
        imageStorage.delete(previous)
        return profile(userId)
    }

    private suspend fun requireActive(userId: UUID): User = findActive(userId) ?: throw UnauthenticatedException()
}
