package com.flowbit.issue.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.application.IssueMilestone
import com.flowbit.issue.application.IssuePatch
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.application.IssueView
import com.flowbit.issue.application.NewIssue
import com.flowbit.issue.domain.IssuePreset
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.media.application.ImageStorage
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IssuePersonResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        fun from(person: IssuePerson) = IssuePersonResponse(person.id, person.loginId, person.displayName, ImageStorage.urlOf(person.avatarKey))
    }
}

data class IssueResponse(
    val id: UUID,
    val key: String,
    val number: Int,
    val title: String,
    val description: String,
    /** The body as editor JSON; present only when one issue is requested. */
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
    val preset: IssuePreset?,
    val status: IssueStatus,
    val priority: IssuePriority,
    val assignee: IssuePersonResponse?,
    val dueDate: LocalDate?,
    val milestone: IssueMilestone?,
    val createdBy: IssuePersonResponse,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(issue: IssueView) =
            IssueResponse(
                id = issue.id,
                key = issue.key,
                number = issue.number,
                title = issue.title,
                description = issue.description,
                content = issue.content,
                preset = issue.preset,
                status = issue.status,
                priority = issue.priority,
                assignee = issue.assignee?.let(IssuePersonResponse::from),
                dueDate = issue.dueDate,
                milestone = issue.milestone,
                createdBy = IssuePersonResponse.from(issue.createdBy),
                version = issue.version,
                createdAt = issue.createdAt,
                updatedAt = issue.updatedAt,
            )
    }
}

data class CreateIssueRequest(
    val title: String,
    val description: String? = null,
    val content: Map<String, Any?>? = null,
    val preset: IssuePreset? = null,
    val status: IssueStatus? = null,
    val priority: IssuePriority? = null,
    val assigneeId: UUID? = null,
    val dueDate: LocalDate? = null,
    val milestoneId: UUID? = null,
)

/**
 * Access is decided inside IssueService for every call. Writes are announced on the realtime hub
 * after IssueService has committed them.
 */
@RestController
@RequestMapping("/api/v1/projects/{key}/issues")
class IssueController(
    private val issueService: IssueService,
    private val realtime: RealtimeHub,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): List<IssueResponse> = issueService.list(user, key).map(IssueResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @RequestBody body: CreateIssueRequest,
    ): IssueResponse {
        val issue =
            issueService.create(
                user,
                key,
                NewIssue(
                    body.title,
                    body.description,
                    body.content,
                    body.preset,
                    body.status,
                    body.priority,
                    body.assigneeId,
                    body.dueDate,
                    body.milestoneId,
                ),
            )
        realtime.publish("project:${issue.projectKey}", user.id)
        return IssueResponse.from(issue)
    }

    @GetMapping("/{number}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @PathVariable number: Int,
    ): IssueResponse = IssueResponse.from(issueService.get(user, key, number))

    /** Only the fields present in the body change; `null` clears `assigneeId`, `dueDate` and `milestoneId`. */
    @PatchMapping("/{number}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @PathVariable number: Int,
        @RequestBody body: Map<String, Any?>,
    ): IssueResponse {
        val issue = issueService.update(user, key, number, IssuePatch(body))
        realtime.publish("project:${issue.projectKey}", user.id)
        realtime.publish("issue:${issue.key}", user.id)
        return IssueResponse.from(issue)
    }
}
