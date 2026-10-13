package com.flowbit.backup

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode

/** `password`: absent or empty leaves the file unencrypted. */
data class BackupExportRequest(
    val sections: List<BackupSection> = emptyList(),
    val password: String? = null,
)

/** `file` is the backup file as it was downloaded; `sections` is what to take from it. */
data class BackupRestoreRequest(
    val file: JsonNode,
    val sections: List<BackupSection> = emptyList(),
    val password: String? = null,
)

/** Administrators only (the `/api/v1/admin` rule in SecurityConfig). docs/features/admin-backup.md */
@RestController
@RequestMapping("/api/v1/admin/backup")
class BackupController(
    private val service: BackupService,
) {
    /** A POST although it changes nothing: the password must not travel in an address. */
    @PostMapping("/export")
    suspend fun export(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: BackupExportRequest,
        exchange: ServerWebExchange,
    ): BackupFile = service.export(user, body.sections, body.password, ClientInfo.from(exchange))

    @PostMapping("/restore")
    suspend fun restore(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: BackupRestoreRequest,
        exchange: ServerWebExchange,
    ): RestoreResult = service.restore(user, body.file, body.sections, body.password, ClientInfo.from(exchange))
}
