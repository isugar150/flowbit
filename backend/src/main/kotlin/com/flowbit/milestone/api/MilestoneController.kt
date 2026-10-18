package com.flowbit.milestone.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.milestone.application.MilestoneService
import com.flowbit.milestone.application.MilestoneView
import com.flowbit.milestone.domain.MilestoneStatus
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateMilestoneRequest(
    val name: String,
    val targetDate: LocalDate? = null,
    val status: MilestoneStatus = MilestoneStatus.PLANNED,
)

/**
 * Milestones of a project. Access is decided inside MilestoneService; writes are announced on
 * the realtime hub after they are committed, since they change the project's overview.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/milestones")
class MilestoneController(
    private val milestoneService: MilestoneService,
    private val realtime: RealtimeHub,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<MilestoneView> = milestoneService.list(user, projectId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CreateMilestoneRequest,
    ): MilestoneView {
        val milestone = milestoneService.create(user, projectId, body.name, body.targetDate, body.status)
        announce(projectId, user)
        return milestone
    }

    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ): MilestoneView {
        val milestone = milestoneService.update(user, projectId, id, body)
        announce(projectId, user)
        return milestone
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable id: UUID,
    ) {
        milestoneService.delete(user, projectId, id)
        announce(projectId, user)
    }

    private fun announce(
        projectId: String,
        user: AuthenticatedUser,
    ) = realtime.publish("project:$projectId", user.id)
}
