package com.flowbit.instance.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** Settings for the whole installation. Exactly one row exists (id = 1). */
@Table("instance_settings")
class InstanceSettings(
    @Id
    val id: Short,
    var organizationName: String,
    /** File name of the workspace logo in storage, see ImageStorage. */
    var logoKey: String?,
    /** Prefix of every issue key: FB-12. */
    var issueKey: String,
    var defaultLocale: String,
    var defaultTimezone: String,
    var setupCompletedAt: Instant?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    val isSetupCompleted: Boolean
        get() = setupCompletedAt != null

    fun completeSetup(now: Instant) {
        check(!isSetupCompleted) { "Setup is already completed" }
        setupCompletedAt = now
        updatedAt = now
    }

    companion object {
        const val SINGLETON_ID: Short = 1
        const val ORGANIZATION_NAME_MAX_LENGTH = 100
    }
}
