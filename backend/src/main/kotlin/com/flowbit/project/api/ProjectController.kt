package com.flowbit.project.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.api.UploadReader
import com.flowbit.media.application.ImageStorage
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class ProjectResponse(
    val id: UUID,
    val key: String,
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
    val currentMilestone: String?,
    val health: ProjectHealth?,
    val healthUpdatedAt: Instant?,
    /** The overview document as editor JSON; present only when one project is requested. */
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
) {
    companion object {
        fun from(view: ProjectView) =
            ProjectResponse(
                id = view.id,
                key = view.key,
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
                currentMilestone = view.currentMilestone,
                health = view.health,
                healthUpdatedAt = view.healthUpdatedAt,
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
) {
    companion object {
        fun from(row: ProjectMemberRow) = ProjectMemberResponse(row.userId, row.loginId, row.displayName, ImageStorage.urlOf(row.avatarKey), row.role)
    }
}

data class ProjectEntryResponse(
    val key: String,
    val name: String,
    val myRole: ProjectRole?,
    val managers: List<ProjectMemberResponse>,
) {
    companion object {
        fun from(entry: ProjectEntry) = ProjectEntryResponse(entry.key, entry.name, entry.myRole, entry.managers.map(ProjectMemberResponse::from))
    }
}

data class CreateProjectRequest(
    val name: String,
    /** The short name in addresses (/project/PAY). Optional: left out, the server makes one from the name. */
    val key: String? = null,
    val description: String? = null,
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
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<ProjectResponse> = projectService.list(user).map(ProjectResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: CreateProjectRequest,
        exchange: ServerWebExchange,
    ): ProjectResponse = ProjectResponse.from(projectService.create(user, body.name, body.key, body.description, ClientInfo.from(exchange)))

    @GetMapping("/{key}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): ProjectResponse = ProjectResponse.from(projectService.get(user, key))

    /** For any signed-in user: whether they may enter, and whom to ask if not. */
    @GetMapping("/{key}/access")
    suspend fun entry(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): ProjectEntryResponse = ProjectEntryResponse.from(projectService.entry(user, key))

    @PatchMapping("/{key}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @RequestBody body: Map<String, Any?>,
    ): ProjectResponse {
        val project = projectService.update(user, key, body)
        realtime.publish("project:${project.key}", user.id)
        return ProjectResponse.from(project)
    }

    /** Multipart with one `file` part; any common image format, stored as WebP. Managers only. */
    @PutMapping("/{key}/icon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replaceIcon(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @RequestPart("file") file: FilePart,
    ): ProjectResponse {
        val project = projectService.replaceIcon(user, key, uploadReader.read(file))
        realtime.publish("project:${project.key}", user.id)
        return ProjectResponse.from(project)
    }

    @DeleteMapping("/{key}/icon")
    suspend fun removeIcon(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): ProjectResponse {
        val project = projectService.removeIcon(user, key)
        realtime.publish("project:${project.key}", user.id)
        return ProjectResponse.from(project)
    }

    @GetMapping("/{key}/members")
    suspend fun members(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): List<ProjectMemberResponse> = projectService.members(user, key).map(ProjectMemberResponse::from)

    @PutMapping("/{key}/members/{userId}")
    suspend fun putMember(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @PathVariable userId: UUID,
        @RequestBody body: PutProjectMemberRequest,
    ): List<ProjectMemberResponse> {
        val members = projectService.putMember(user, key, userId, body.role)
        realtime.publish("project:${key.uppercase(Locale.ROOT)}", user.id)
        return members.map(ProjectMemberResponse::from)
    }

    @DeleteMapping("/{key}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun removeMember(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @PathVariable userId: UUID,
    ) {
        projectService.removeMember(user, key, userId)
        realtime.publish("project:${key.uppercase(Locale.ROOT)}", user.id)
    }
}
