package com.flowbit.instance.application

import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ValidationException
import com.flowbit.instance.domain.InstanceSettings
import com.flowbit.instance.domain.InstanceSettingsRepository
import com.flowbit.media.application.ImageProcessor
import com.flowbit.media.application.ImageSpec
import com.flowbit.media.application.ImageStorage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class InstanceDefaults(
    val locale: String,
    val timezone: String,
)

/** What every signed-in user sees about the installation. */
data class Workspace(
    val name: String,
    val logoKey: String?,
)

@Service
class InstanceService(
    private val repository: InstanceSettingsRepository,
    private val imageProcessor: ImageProcessor,
    private val imageStorage: ImageStorage,
    private val clock: Clock,
) {
    suspend fun isSetupCompleted(): Boolean = settings().isSetupCompleted

    suspend fun defaults(): InstanceDefaults = settings().let { InstanceDefaults(it.defaultLocale, it.defaultTimezone) }

    suspend fun workspace(): Workspace = settings().let { Workspace(it.organizationName, it.logoKey) }

    /**
     * Marks setup as done. Locks the settings row so that of two concurrent callers
     * exactly one succeeds; the caller's transaction must also contain the work that setup performs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    suspend fun completeSetup() {
        val settings = repository.findForUpdate()
        if (settings.isSetupCompleted) {
            throw setupAlreadyCompleted()
        }
        settings.completeSetup(clock.instant())
        repository.save(settings)
    }

    fun setupAlreadyCompleted() = ConflictException("SETUP_ALREADY_COMPLETED", "초기 설정이 이미 끝났습니다")

    /** Administrators only; enforced by the `/api/v1/admin` rule in SecurityConfig. */
    suspend fun rename(rawName: String): Workspace {
        val name = rawName.trim()
        val error =
            when {
                name.isEmpty() -> FieldError("name", "REQUIRED", "워크스페이스 이름을 입력하세요")
                name.length > InstanceSettings.ORGANIZATION_NAME_MAX_LENGTH ->
                    FieldError("name", "TOO_LONG", "워크스페이스 이름은 ${InstanceSettings.ORGANIZATION_NAME_MAX_LENGTH}자 이하여야 합니다")
                else -> null
            }
        if (error != null) throw ValidationException(listOf(error))
        repository.updateOrganizationName(name, clock.instant())
        return workspace()
    }

    /** Replaces the workspace logo with the upload, converted to WebP no larger than 512px. */
    suspend fun replaceLogo(upload: ByteArray): Workspace {
        val previous = settings().logoKey
        val key = imageStorage.store(imageProcessor.toWebp(upload, ImageSpec.LOGO))
        repository.updateLogoKey(key, clock.instant())
        imageStorage.delete(previous)
        return workspace()
    }

    suspend fun removeLogo(): Workspace {
        val previous = settings().logoKey
        repository.updateLogoKey(null, clock.instant())
        imageStorage.delete(previous)
        return workspace()
    }

    private suspend fun settings(): InstanceSettings = checkNotNull(repository.findById(InstanceSettings.SINGLETON_ID))
}
