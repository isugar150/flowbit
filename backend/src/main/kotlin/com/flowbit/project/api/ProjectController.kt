package com.flowbit.project.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.api.UploadReader
import com.flowbit.media.application.ImageStorage
import com.flowbit.notification.NotificationDispatcher
import com.flowbit.project.application.ProjectAccessRequestRow
import com.flowbit.project.application.ProjectEntry
import com.flowbit.project.application.ProjectMemberRow
import com.flowbit.project.application.ProjectPerson
import com.flowbit.project.application.ProjectService
import com.flowbit.project.application.ProjectView
import com.flowbit.project.domain.ProjectHealth
import com.flowbit.project.domain.ProjectPriority
import com.flowbit.project.domain.ProjectRole
import com.flowbit.project.domain.ProjectStatus
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProjectResponse(
    val id: UUID,
    val name: String,
    val description: String,
    /** The uploaded icon; null for the default one. */
    val iconUrl: String?,
    /** Null for a project in the list that the caller may not enter. */
    val myRole: ProjectRole?,
    val memberCount: Int,
    val createdAt: Instant,
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val lead: ProjectPersonResponse?,
    val startDate: LocalDate?,
    val targetDate: LocalDate?,
    val labels: List<String>,
    val issueCount: Int,
    val doneCount: Int,
    val health: ProjectHealth?,
    val healthUpdatedAt: Instant?,
    /** Whether the caller follows the project. */
    val subscribed: Boolean,
    /** Whether the caller was added to the project (a workspace administrator may enter it without). */
    val member: Boolean,
    /** When the project was archived; null for a project in use. */
    val archivedAt: Instant?,
    /** Whether every member of the workspace enters the project as an editor (D-192). */
    val openToAll: Boolean,
    /** Whether the project's issues carry an estimate (D-228). */
    val estimatesEnabled: Boolean,
    /** Whether an issue made by someone who does not manage the project waits in the triage queue (D-231). */
    val triageEnabled: Boolean,
    /** The overview document as editor JSON; present only when one project is requested. */
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
) {
    companion object {
        fun from(view: ProjectView) =
            ProjectResponse(
                id = view.id,
                name = view.name,
                description = view.description,
                iconUrl = ImageStorage.urlOf(view.iconKey),
                myRole = view.myRole,
                memberCount = view.memberCount,
                createdAt = view.createdAt,
                status = view.status,
                priority = view.priority,
                lead = view.lead?.let(ProjectPersonResponse::from),
                startDate = view.startDate,
                targetDate = view.targetDate,
                labels = view.labels,
                issueCount = view.issueCount,
                doneCount = view.doneCount,
                health = view.health,
                healthUpdatedAt = view.healthUpdatedAt,
                subscribed = view.subscribed,
                member = view.member,
                archivedAt = view.archivedAt,
                openToAll = view.openToAll,
                estimatesEnabled = view.estimatesEnabled,
                triageEnabled = view.triageEnabled,
                content = view.content,
            )
    }
}

data class ProjectPersonResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        fun from(person: ProjectPerson) = ProjectPersonResponse(person.id, person.loginId, person.displayName, ImageStorage.urlOf(person.avatarKey))
    }
}

data class ProjectMemberResponse(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: ProjectRole,
    /** False for someone listed only because the project is open to everyone (D-192). */
    val direct: Boolean,
) {
    companion object {
        fun from(row: ProjectMemberRow) =
            ProjectMemberResponse(row.userId, row.loginId, row.displayName, ImageStorage.urlOf(row.avatarKey), row.role, row.direct)
    }
}

data class ProjectEntryResponse(
    val id: UUID,
    val name: String,
    val myRole: ProjectRole?,
    val managers: List<ProjectMemberResponse>,
    /** When the caller asked to be let in and is still waiting; null otherwise (D-128). */
    val requestedAt: Instant?,
) {
    companion object {
        fun from(entry: ProjectEntry) =
            ProjectEntryResponse(entry.id, entry.name, entry.myRole, entry.managers.map(ProjectMemberResponse::from), entry.requestedAt)
    }
}

/** Someone waiting to be let into the project. */
data class ProjectAccessRequestResponse(
    val userId: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val requestedAt: Instant,
) {
    companion object {
        fun from(row: ProjectAccessRequestRow) =
            ProjectAccessRequestResponse(row.userId, row.loginId, row.displayName, ImageStorage.urlOf(row.avatarKey), row.requestedAt)
    }
}

data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    /** Opens the project to every member of the workspace from the start (D-192). */
    val openToAll: Boolean = false,
)

data class PutProjectMemberRequest(
    val role: ProjectRole,
)

/**
 * Access is decided inside ProjectService for every call; see its class comment. Writes are
 * announced on the realtime hub after ProjectService has committed them.
 */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectController(
    private val projectService: ProjectService,
    private val uploadReader: UploadReader,
    private val realtime: RealtimeHub,
    private val notifications: NotificationDispatcher,
) {
    /** The projects in use, or with `?archived=true` the ones that were put away. */
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "false") archived: Boolean,
    ): List<ProjectResponse> = (if (archived) projectService.listArchived(user) else projectService.list(user)).map(ProjectResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: CreateProjectRequest,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.create(user, body.name, body.description, body.openToAll, ClientInfo.from(exchange))
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return ProjectResponse.from(project)
    }

    @GetMapping("/{projectId}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectResponse = ProjectResponse.from(projectService.get(user, projectId))

    /** For any signed-in user: whether they may enter, and whom to ask if not. */
    @GetMapping("/{projectId}/access")
    suspend fun entry(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectEntryResponse = ProjectEntryResponse.from(projectService.entry(user, projectId))

    /**
     * For someone who may not enter: asks to be let in (D-128). The managers are told at once;
     * asking again while waiting tells nobody again. Answers like `/access`.
     */
    @PostMapping("/{projectId}/access-requests")
    suspend fun requestAccess(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectEntryResponse {
        val result = projectService.requestAccess(user, projectId)
        result.notification?.let(notifications::announce)
        // A manager on the settings page sees the new request without reloading.
        realtime.publish("project:${result.entry.id}", user.id)
        return ProjectEntryResponse.from(result.entry)
    }

    /** The people waiting to be let in, the longest waiting first. Managers only. */
    @GetMapping("/{projectId}/access-requests")
    suspend fun accessRequests(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<ProjectAccessRequestResponse> = projectService.accessRequests(user, projectId).map(ProjectAccessRequestResponse::from)

    /** Lets the person in with the role in the body. Managers only. Answers with the members. */
    @PostMapping("/{projectId}/access-requests/{userId}/approve")
    suspend fun approveAccess(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        @RequestBody body: PutProjectMemberRequest,
    ): List<ProjectMemberResponse> {
        val members = projectService.approveAccess(user, projectId, userId, body.role)
        realtime.publish("project:$projectId", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return members.map(ProjectMemberResponse::from)
    }

    /** Turns the request down; the person may ask again. Managers only. */
    @DeleteMapping("/{projectId}/access-requests/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun rejectAccess(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
    ) {
        projectService.rejectAccess(user, projectId, userId)
        realtime.publish("project:$projectId", user.id)
    }

    @PatchMapping("/{projectId}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: Map<String, Any?>,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.update(user, projectId, body, ClientInfo.from(exchange))
        realtime.publish("project:${project.id}", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return ProjectResponse.from(project)
    }

    /** Managers only. The project leaves every list; nothing is deleted. */
    @PostMapping("/{projectId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun archive(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ) {
        val id = projectService.archive(user, projectId, ClientInfo.from(exchange))
        realtime.publish("project:$id", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
    }

    @PostMapping("/{projectId}/restore")
    suspend fun restore(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.restore(user, projectId, ClientInfo.from(exchange))
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return ProjectResponse.from(project)
    }

    /** Multipart with one `file` part; any common image format, stored as WebP. Managers only. */
    @PutMapping("/{projectId}/icon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replaceIcon(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestPart("file") file: FilePart,
    ): ProjectResponse {
        val project = projectService.replaceIcon(user, projectId, uploadReader.read(file))
        realtime.publish("project:${project.id}", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return ProjectResponse.from(project)
    }

    @DeleteMapping("/{projectId}/icon")
    suspend fun removeIcon(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectResponse {
        val project = projectService.removeIcon(user, projectId)
        realtime.publish("project:${project.id}", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return ProjectResponse.from(project)
    }

    /** Follow the project: be told about every change to its issues. Only the caller's own choice, so nobody else is told. */
    @PutMapping("/{projectId}/subscription")
    suspend fun subscribe(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectResponse = ProjectResponse.from(projectService.setSubscribed(user, projectId, true))

    @DeleteMapping("/{projectId}/subscription")
    suspend fun unsubscribe(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectResponse = ProjectResponse.from(projectService.setSubscribed(user, projectId, false))

    @GetMapping("/{projectId}/members")
    suspend fun members(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<ProjectMemberResponse> = projectService.members(user, projectId).map(ProjectMemberResponse::from)

    @PutMapping("/{projectId}/members/{userId}")
    suspend fun putMember(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        @RequestBody body: PutProjectMemberRequest,
    ): List<ProjectMemberResponse> {
        val members = projectService.putMember(user, projectId, userId, body.role)
        realtime.publish("project:$projectId", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
        return members.map(ProjectMemberResponse::from)
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun removeMember(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
    ) {
        projectService.removeMember(user, projectId, userId)
        realtime.publish("project:$projectId", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
    }
}

/** One project and what a given member may do in it; `role` is null when they are not a member. */
data class MemberProjectRoleResponse(
    val projectId: UUID,
    val name: String,
    val role: ProjectRole?,
    /** Open to every member of the workspace (D-192): they enter without a role of their own. */
    val openToAll: Boolean,
)

/**
 * Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). An administrator is
 * not a member of every project (D-131), so this screen has endpoints of its own for setting one
 * person's role in any project.
 */
@RestController
@RequestMapping("/api/v1/admin/members")
class AdminMemberProjectsController(
    private val projectService: ProjectService,
    private val realtime: RealtimeHub,
) {
    @PutMapping("/{memberId}/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun putRole(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable memberId: UUID,
        @PathVariable projectId: UUID,
        @RequestBody body: PutProjectMemberRequest,
    ) {
        projectService.adminPutMember(user.id, projectId, memberId, body.role)
        realtime.publish("project:$projectId", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
    }

    @DeleteMapping("/{memberId}/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun removeRole(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable memberId: UUID,
        @PathVariable projectId: UUID,
    ) {
        projectService.adminRemoveMember(user.id, projectId, memberId)
        realtime.publish("project:$projectId", user.id)
        realtime.publish(RealtimeHub.PROJECTS, user.id)
    }

    @GetMapping("/{memberId}/projects")
    suspend fun projects(
        @PathVariable memberId: UUID,
    ): List<MemberProjectRoleResponse> =
        projectService.rolesOf(memberId).map { (project, role) ->
            MemberProjectRoleResponse(project.id, project.name, role, project.openToAll)
        }
}
