package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.web.ClientInfo
import com.flowbit.issue.application.IssueStatusView
import com.flowbit.issue.application.IssueStatuses
import com.flowbit.issue.domain.IssueStatus
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
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

data class IssueStatusResponse(
    val id: UUID,
    val category: IssueStatus,
    val name: String,
    val position: Int,
) {
    companion object {
        fun from(status: IssueStatusView) = IssueStatusResponse(status.id, status.category, status.name, status.position)
    }
}

data class CreateIssueStatusRequest(
    val category: IssueStatus,
    val name: String,
)

/** `name` renames; `direction` (`UP` or `DOWN`) moves it one place inside its category. */
data class UpdateIssueStatusRequest(
    val name: String? = null,
    val direction: String? = null,
)

/** Every status, in the order the board shows them: for the status menus and the board's columns (docs/features/statuses-and-estimates.md). */
@RestController
@RequestMapping("/api/v1/issue-statuses")
class IssueStatusController(
    private val statuses: IssueStatuses,
) {
    @GetMapping
    suspend fun list(): List<IssueStatusResponse> = statuses.all().map(IssueStatusResponse::from)
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/issue-statuses")
class AdminIssueStatusController(
    private val statuses: IssueStatuses,
    private val auditService: AuditService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @RequestBody body: CreateIssueStatusRequest,
        exchange: ServerWebExchange,
    ): IssueStatusResponse {
        val created = statuses.create(body.category, body.name)
        audit(admin, exchange, "created", created.name)
        return IssueStatusResponse.from(created)
    }

    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: UpdateIssueStatusRequest,
        exchange: ServerWebExchange,
    ): IssueStatusResponse {
        val updated = statuses.update(id, body.name, body.direction)
        audit(admin, exchange, if (body.name != null) "renamed" else "moved", updated.name)
        return IssueStatusResponse.from(updated)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ) {
        val name = statuses.find(id)?.name.orEmpty()
        statuses.delete(id)
        audit(admin, exchange, "deleted", name)
    }

    private suspend fun audit(
        admin: AuthenticatedUser,
        exchange: ServerWebExchange,
        action: String,
        name: String,
    ) = auditService.record(AuditEventType.ISSUE_STATUSES_CHANGED, admin.id, ClientInfo.from(exchange), mapOf("action" to action, "name" to name))
}
