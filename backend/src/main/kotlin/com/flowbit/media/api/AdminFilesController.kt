package com.flowbit.media.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.application.CleanupResult
import com.flowbit.media.application.StorageInspection
import com.flowbit.media.application.StorageInspector
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/** The administrator's file page (D-171, docs/features/admin-files.md). Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/files")
class AdminFilesController(
    private val inspector: StorageInspector,
) {
    @GetMapping
    suspend fun overview(): StorageInspection = inspector.inspect()

    /** Removes the unused files that are old enough, and says what went. */
    @PostMapping("/cleanup")
    suspend fun cleanup(
        @AuthenticationPrincipal user: AuthenticatedUser,
        exchange: ServerWebExchange,
    ): CleanupResult = inspector.cleanOrphans(user.id, ClientInfo.from(exchange), automatic = false)
}
