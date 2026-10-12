package com.flowbit.common.web

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(
    val status: String,
)

@RestController
@RequestMapping("/api/v1/health")
class HealthController(
    private val databaseClient: DatabaseClient,
) {
    @GetMapping
    suspend fun health(): HealthResponse {
        databaseClient
            .sql("select 1")
            .fetch()
            .one()
            .awaitSingle()
        return HealthResponse(status = "UP")
    }
}
