package com.flowbit.retention

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

data class RetentionPeriodBody(
    val amount: Int,
    val unit: RetentionUnit,
)

/** A null (or missing) item is never removed for good. */
data class RetentionSettingsBody(
    val issues: RetentionPeriodBody? = null,
    val documents: RetentionPeriodBody? = null,
    val comments: RetentionPeriodBody? = null,
    /** How long the record of who opened share links is kept (D-198). */
    val shareAccesses: RetentionPeriodBody? = null,
) {
    fun toSettings() = RetentionSettings(issues?.toPeriod(), documents?.toPeriod(), comments?.toPeriod(), shareAccesses?.toPeriod())

    companion object {
        fun from(settings: RetentionSettings) =
            RetentionSettingsBody(
                settings.issues?.toBody(),
                settings.documents?.toBody(),
                settings.comments?.toBody(),
                settings.shareAccesses?.toBody(),
            )

        private fun RetentionPeriodBody.toPeriod() = RetentionPeriod(amount, unit)

        private fun RetentionPeriod.toBody() = RetentionPeriodBody(amount, unit)
    }
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/retention")
class AdminRetentionController(
    private val service: RetentionService,
) {
    @GetMapping
    suspend fun settings(): RetentionSettingsBody = RetentionSettingsBody.from(service.settings())

    @PutMapping
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: RetentionSettingsBody,
        exchange: ServerWebExchange,
    ): RetentionSettingsBody = RetentionSettingsBody.from(service.update(body.toSettings(), user.id, ClientInfo.from(exchange)))
}
