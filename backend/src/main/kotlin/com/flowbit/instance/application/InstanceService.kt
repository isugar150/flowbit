package com.flowbit.instance.application

import com.flowbit.common.error.ConflictException
import com.flowbit.instance.domain.InstanceSettings
import com.flowbit.instance.domain.InstanceSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class InstanceDefaults(
    val locale: String,
    val timezone: String,
)

@Service
class InstanceService(
    private val repository: InstanceSettingsRepository,
    private val clock: Clock,
) {
    suspend fun isSetupCompleted(): Boolean = settings().isSetupCompleted

    suspend fun defaults(): InstanceDefaults = settings().let { InstanceDefaults(it.defaultLocale, it.defaultTimezone) }

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

    private suspend fun settings(): InstanceSettings = checkNotNull(repository.findById(InstanceSettings.SINGLETON_ID))
}
