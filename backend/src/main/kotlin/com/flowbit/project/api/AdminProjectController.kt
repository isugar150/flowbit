package com.flowbit.project.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.api.UploadReader
import com.flowbit.project.application.AdminProjectAudit
import com.flowbit.project.application.ProjectService
import com.flowbit.project.application.ProjectView
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
import java.util.UUID

/**
 * The settings of any project in use, for the administrator screen "관리자 설정 > 프로젝트" (D-236). The calls mirror
 * the ones under `/api/v1/projects/{projectId}` that the project's own settings page makes, with the same bodies and
 * answers, so that page's components serve both. An administrator is not a member of every project (D-131) and does
 * not become one here: the caller is a manager for these calls and nothing else, which is why the overview and the
 * properties of the project cannot be written and are never in an answer (`content` is always null).
 *
 * Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). What is changed is announced on the
 * realtime hub like any change to a project, and recorded in the audit log as PROJECT_ADMIN_CHANGED.
 */
@RestController
@RequestMapping("/api/v1/admin/projects/{projectId}")
class AdminProjectController(
    private val projectService: ProjectService,
    private val uploadReader: UploadReader,
    private val realtime: RealtimeHub,
    private val audit: AdminProjectAudit,
) {
    /** Only the name, who may enter (`openToAll`), the estimates and the triage: the fields of the settings page. */
    @PatchMapping
    suspend fun update(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: Map<String, Any?>,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.update(admin, projectId, body, ClientInfo.from(exchange), administrator = true)
        audit.record(admin, exchange, project.id.toString(), "settings", mapOf("fields" to body.keys.sorted().joinToString(",")))
        announce(project.id, admin)
        return settings(project)
    }

    @PutMapping("/icon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replaceIcon(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestPart("file") file: FilePart,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.replaceIcon(admin, projectId, uploadReader.read(file), administrator = true)
        audit.record(admin, exchange, project.id.toString(), "icon")
        announce(project.id, admin)
        return settings(project)
    }

    @DeleteMapping("/icon")
    suspend fun removeIcon(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.removeIcon(admin, projectId, administrator = true)
        audit.record(admin, exchange, project.id.toString(), "icon")
        announce(project.id, admin)
        return settings(project)
    }

    /** The project leaves every list; nothing is deleted. ProjectService records it (PROJECT_ARCHIVED). */
    @PostMapping("/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun archive(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ) {
        val id = projectService.archive(admin, projectId, ClientInfo.from(exchange), administrator = true)
        announce(id, admin)
    }

    /** Brings an archived project back, which a project's own page lets only its managers do. ProjectService records it (PROJECT_RESTORED). */
    @PostMapping("/restore")
    suspend fun restore(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        exchange: ServerWebExchange,
    ): ProjectResponse {
        val project = projectService.restore(admin, projectId, ClientInfo.from(exchange), administrator = true)
        announce(project.id, admin)
        return settings(project)
    }

    @GetMapping("/members")
    suspend fun members(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<ProjectMemberResponse> = projectService.members(admin, projectId, administrator = true).map(ProjectMemberResponse::from)

    @PutMapping("/members/{userId}")
    suspend fun putMember(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        @RequestBody body: PutProjectMemberRequest,
        exchange: ServerWebExchange,
    ): List<ProjectMemberResponse> {
        val members = projectService.putMember(admin, projectId, userId, body.role, administrator = true)
        audit.record(admin, exchange, projectId, "member", mapOf("memberId" to userId.toString(), "role" to body.role.name))
        announce(projectId, admin)
        return members.map(ProjectMemberResponse::from)
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun removeMember(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        exchange: ServerWebExchange,
    ) {
        projectService.removeMember(admin, projectId, userId, administrator = true)
        audit.record(admin, exchange, projectId, "member-removed", mapOf("memberId" to userId.toString()))
        announce(projectId, admin)
    }

    @GetMapping("/access-requests")
    suspend fun accessRequests(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
    ): List<ProjectAccessRequestResponse> = projectService.accessRequests(admin, projectId, administrator = true).map(ProjectAccessRequestResponse::from)

    @PostMapping("/access-requests/{userId}/approve")
    suspend fun approveAccess(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        @RequestBody body: PutProjectMemberRequest,
        exchange: ServerWebExchange,
    ): List<ProjectMemberResponse> {
        val members = projectService.approveAccess(admin, projectId, userId, body.role, administrator = true)
        audit.record(admin, exchange, projectId, "access-approved", mapOf("memberId" to userId.toString(), "role" to body.role.name))
        announce(projectId, admin)
        return members.map(ProjectMemberResponse::from)
    }

    @DeleteMapping("/access-requests/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun rejectAccess(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @PathVariable userId: UUID,
        exchange: ServerWebExchange,
    ) {
        projectService.rejectAccess(admin, projectId, userId, administrator = true)
        audit.record(admin, exchange, projectId, "access-rejected", mapOf("memberId" to userId.toString()))
        realtime.publish("project:$projectId", admin.id)
    }

    /** Whoever has the project open sees the change without reloading, and the lists refresh. */
    private fun announce(
        projectId: UUID,
        admin: AuthenticatedUser,
    ) = announce(projectId.toString(), admin)

    private fun announce(
        projectId: String,
        admin: AuthenticatedUser,
    ) {
        realtime.publish("project:$projectId", admin.id)
        realtime.publish(RealtimeHub.PROJECTS, admin.id)
    }

    /** The project as the settings show it: without the overview document, which is the project's own data. */
    private fun settings(view: ProjectView) = ProjectResponse.from(view.copy(content = null))
}
