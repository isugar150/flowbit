package com.flowbit.project.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.web.ClientInfo
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

/**
 * What an administrator changed in a project's settings from the administrator screen (D-236), whether or not they
 * belong to the project. Archiving and restoring are recorded by ProjectService itself, so they are not here.
 */
@Component
class AdminProjectAudit(
    private val auditService: AuditService,
) {
    /** [change] says what was changed; [extra] adds the fields that name it (the person, the role, the fields of the settings). */
    suspend fun record(
        admin: AuthenticatedUser,
        exchange: ServerWebExchange,
        projectId: String,
        change: String,
        extra: Map<String, String> = emptyMap(),
    ) = auditService.record(
        AuditEventType.PROJECT_ADMIN_CHANGED,
        admin.id,
        ClientInfo.from(exchange),
        mapOf("projectId" to projectId, "change" to change) + extra,
    )
}
