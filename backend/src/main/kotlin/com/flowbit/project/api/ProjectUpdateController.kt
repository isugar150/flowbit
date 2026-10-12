package com.flowbit.project.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.project.application.ProjectUpdateService
import com.flowbit.project.application.ProjectUpdateView
import com.flowbit.project.domain.ProjectHealth
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class ProjectUpdateResponse(
    val id: UUID,
    val health: ProjectHealth,
    val body: String,
    val author: ProjectPersonResponse,
    val createdAt: Instant,
) {
    companion object {
        fun from(update: ProjectUpdateView) =
            ProjectUpdateResponse(update.id, update.health, update.body, ProjectPersonResponse.from(update.author), update.createdAt)
    }
}

data class PostProjectUpdateRequest(
    val health: ProjectHealth,
    val body: String? = null,
)

/** Project updates and health. Access is decided inside ProjectUpdateService. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/updates")
class ProjectUpdateController(
    private val updateService: ProjectUpdateService,
    private val realtime: RealtimeHub,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<ProjectUpdateResponse> = updateService.list(user, projectId).map(ProjectUpdateResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun post(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: PostProjectUpdateRequest,
    ): ProjectUpdateResponse {
        val update = updateService.post(user, projectId, body.health, body.body)
        // The project's health changes for everyone looking at the project.
        realtime.publish("project:$projectId", user.id)
        return ProjectUpdateResponse.from(update)
    }

    @DeleteMapping("/{updateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable updateId: UUID,
    ) {
        updateService.delete(user, projectId, updateId)
        realtime.publish("project:$projectId", user.id)
    }
}
