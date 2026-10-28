package com.flowbit.automation

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/** The admin address is checked by SecurityConfig and by ProjectService's administrator access. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automations", "/api/v1/admin/projects/{projectId}/automations")
class AutomationController(
    private val service: AutomationService,
    private val hub: RealtimeHub,
) {
    private fun admin(exchange: ServerWebExchange) =
        exchange.request.path
            .value()
            .startsWith("/api/v1/admin/")

    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ): List<AutomationRule> = service.list(user, projectId, admin(exchange))

    @GetMapping("/runs")
    suspend fun runs(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ): List<AutomationRun> = service.runs(user, projectId, admin(exchange))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: AutomationInput,
        exchange: ServerWebExchange,
    ): AutomationRule {
        val rule = service.save(user, projectId, admin(exchange), null, body, ClientInfo.from(exchange))
        hub.publish("project:${rule.projectId}", user.id)
        return rule
    }

    @PutMapping("/{ruleId}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable ruleId: UUID,
        @RequestBody body: AutomationInput,
        exchange: ServerWebExchange,
    ): AutomationRule {
        val rule = service.save(user, projectId, admin(exchange), ruleId, body, ClientInfo.from(exchange))
        hub.publish("project:${rule.projectId}", user.id)
        return rule
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable ruleId: UUID,
        exchange: ServerWebExchange,
    ) {
        service.delete(user, projectId, admin(exchange), ruleId, ClientInfo.from(exchange))
        hub.publish("project:$projectId", user.id)
    }
}
