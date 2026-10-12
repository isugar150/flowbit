package com.flowbit.instance.domain

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface InstanceSettingsRepository : CoroutineCrudRepository<InstanceSettings, Short> {
    /** Reads the settings row and holds a row lock until the surrounding transaction ends. */
    @Query("select * from instance_settings where id = 1 for update")
    suspend fun findForUpdate(): InstanceSettings

    @Modifying
    @Query("update instance_settings set organization_name = :name, updated_at = :now where id = 1")
    suspend fun updateOrganizationName(
        name: String,
        now: Instant,
    ): Int

    @Modifying
    @Query("update instance_settings set logo_key = :logoKey, updated_at = :now where id = 1")
    suspend fun updateLogoKey(
        logoKey: String?,
        now: Instant,
    ): Int

    @Modifying
    @Query("update instance_settings set workspace_key = :workspaceKey, updated_at = :now where id = 1")
    suspend fun updateWorkspaceKey(
        workspaceKey: String,
        now: Instant,
    ): Int
}
