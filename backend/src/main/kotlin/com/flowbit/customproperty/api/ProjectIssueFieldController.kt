package com.flowbit.customproperty.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.customproperty.application.ProjectFieldInput
import com.flowbit.customproperty.application.ProjectIssueField
import com.flowbit.customproperty.application.ProjectIssueFieldService
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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/issue-fields", "/api/v1/admin/projects/{projectId}/issue-fields")
class ProjectIssueFieldController(
    private val service: ProjectIssueFieldService,
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
    ): List<ProjectIssueField> = service.list(user, projectId, admin(exchange))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: ProjectFieldInput,
        exchange: ServerWebExchange,
    ): ProjectIssueField {
        val field = service.save(user, projectId, admin(exchange), null, body, ClientInfo.from(exchange))
        changed(user, field.projectId)
        return field
    }

    @PutMapping("/{fieldId}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable fieldId: UUID,
        @RequestBody body: ProjectFieldInput,
        exchange: ServerWebExchange,
    ): ProjectIssueField {
        val field = service.save(user, projectId, admin(exchange), fieldId, body, ClientInfo.from(exchange))
        changed(user, field.projectId)
        return field
    }

    @DeleteMapping("/{fieldId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable fieldId: UUID,
        exchange: ServerWebExchange,
    ) {
        service.delete(user, projectId, admin(exchange), fieldId, ClientInfo.from(exchange))
        changed(user, UUID.fromString(projectId))
    }

    private fun changed(
        user: AuthenticatedUser,
        projectId: UUID,
    ) {
        hub.publish("project:$projectId", user.id)
        hub.publish(RealtimeHub.ISSUES, user.id)
    }
}
