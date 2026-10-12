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
import java.util.Locale

data class InstanceDefaults(
    val locale: String,
    val timezone: String,
)

/** What every signed-in user sees about the installation. */
data class Workspace(
    val name: String,
    val logoKey: String?,
    /** Prefix of every issue key: FB-12. */
    val workspaceKey: String,
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

    suspend fun workspace(): Workspace = settings().let { Workspace(it.organizationName, it.logoKey, it.workspaceKey) }

    /**
     * Marks setup as done. Locks the settings row so that of two concurrent callers
     * exactly one succeeds; the caller's transaction must also contain the work that setup performs.
     * A workspace name and an issue key, already validated with [workspaceNameError] and
     * [workspaceKeyError], replace the default ones.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    suspend fun completeSetup(
        workspaceName: String? = null,
        workspaceKey: String? = null,
    ) {
        val settings = repository.findForUpdate()
        if (settings.isSetupCompleted) {
            throw setupAlreadyCompleted()
        }
        settings.completeSetup(clock.instant())
        if (workspaceName != null) settings.organizationName = workspaceName
        if (workspaceKey != null) settings.workspaceKey = workspaceKey
        repository.save(settings)
    }

    fun setupAlreadyCompleted() = ConflictException("SETUP_ALREADY_COMPLETED", "초기 설정이 이미 끝났습니다")

    /** Administrators only; enforced by the `/api/v1/admin` rule in SecurityConfig. */
    suspend fun rename(rawName: String): Workspace {
        val name = rawName.trim()
        val error = workspaceNameError(name, "name")
        if (error != null) throw ValidationException(listOf(error))
        repository.updateOrganizationName(name, clock.instant())
        return workspace()
    }

    /** Changes the prefix of issue keys. Every issue is shown with the new one at once; numbers stay. */
    suspend fun setWorkspaceKey(rawKey: String): Workspace {
        val key = rawKey.trim().uppercase(Locale.ROOT)
        workspaceKeyError(key, "workspaceKey")?.let { throw ValidationException(listOf(it)) }
        repository.updateWorkspaceKey(key, clock.instant())
        return workspace()
    }

    /** What is wrong with an upper-cased issue key, reported under [field]; null when it is fine. */
    fun workspaceKeyError(
        key: String,
        field: String,
    ): FieldError? =
        if (WORKSPACE_KEY_PATTERN.matches(key)) {
            null
        } else {
            FieldError(field, "INVALID", "워크스페이스 키는 영문 대문자로 시작하는 2~10자의 영문 대문자와 숫자여야 합니다")
        }

    /** What is wrong with a trimmed workspace name, reported under [field]; null when it is fine. */
    fun workspaceNameError(
        name: String,
        field: String,
    ): FieldError? =
        when {
            name.isEmpty() -> FieldError(field, "REQUIRED", "워크스페이스 이름을 입력하세요")
            name.length > InstanceSettings.ORGANIZATION_NAME_MAX_LENGTH ->
                FieldError(field, "TOO_LONG", "워크스페이스 이름은 ${InstanceSettings.ORGANIZATION_NAME_MAX_LENGTH}자 이하여야 합니다")
            else -> null
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

    private companion object {
        val WORKSPACE_KEY_PATTERN = Regex("^[A-Z][A-Z0-9]{1,9}$")
    }

    private suspend fun settings(): InstanceSettings = checkNotNull(repository.findById(InstanceSettings.SINGLETON_ID))
}
