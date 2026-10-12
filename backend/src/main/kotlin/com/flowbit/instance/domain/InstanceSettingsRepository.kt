package com.flowbit.instance.domain

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InstanceSettingsRepository : CoroutineCrudRepository<InstanceSettings, Short> {
    /** Reads the settings row and holds a row lock until the surrounding transaction ends. */
    @Query("select * from instance_settings where id = 1 for update")
    suspend fun findForUpdate(): InstanceSettings
}
