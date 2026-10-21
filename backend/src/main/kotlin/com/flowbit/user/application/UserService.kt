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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
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
    val tourCompletedAt: Instant?,
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
        return entityTemplate.insert(User.create(UuidV7.generate(now), loginId, passwordHash, UserRole.ADMIN, null, now)).awaitSingle()
    }

    suspend fun findActive(userId: UUID): User? = repository.findById(userId)?.takeIf { it.isActive }

    suspend fun activeUsers(): List<User> = repository.findActiveOrdered().toList()

    /**
     * The active account a Git user is, for changes a Git server reports (D-141): the one with
     * this e-mail address, otherwise the one whose login id is the Git user name. Null for neither.
     */
    suspend fun findActiveByEmailOrLoginId(
        email: String?,
        loginId: String?,
    ): User? =
        listOfNotNull(email, loginId)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { value -> repository.findByLoginIdOrEmail(value)?.takeIf { it.isActive } }

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
            tourCompletedAt = user.tourCompletedAt,
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
            throw ValidationException(listOf(displayNameTooLong()))
        }
        repository.updateDisplayName(userId, displayName, clock.instant())
        return profile(userId)
    }

    /** Replaces the profile image with the upload, converted to a 256px WebP. The upload itself is kept too. */
    suspend fun replaceAvatar(
        userId: UUID,
        upload: ByteArray,
    ): UserProfile {
        val previous = requireActive(userId).avatarKey
        val key = imageStorage.store(webp = imageProcessor.toWebp(upload, ImageSpec.AVATAR), original = upload)
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

    /** Records that the person finished or skipped the onboarding tour, so it is not shown again. */
    suspend fun completeTour(userId: UUID): UserProfile {
        requireActive(userId)
        repository.completeTour(userId, clock.instant())
        return profile(userId)
    }

    private suspend fun requireActive(userId: UUID): User = findActive(userId) ?: throw UnauthenticatedException()

    companion object {
        fun displayNameTooLong() = FieldError("displayName", "TOO_LONG", "이름은 ${User.DISPLAY_NAME_MAX_LENGTH}자 이하여야 합니다")
    }
}
