package com.flowbit.mcp

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

@RestController
class AccessTokenController(
    private val tokens: AccessTokens,
    private val publicUrl: PublicUrl,
    private val audit: AuditService,
) {
    @GetMapping("/api/v1/me/mcp")
    fun connection(exchange: ServerWebExchange): Map<String, String> {
        exchange.response.headers.cacheControl = "no-store"
        return mapOf(
            "path" to MCP_PATH,
            "url" to publicUrl.of(MCP_PATH),
            "protocolVersion" to "2025-11-25",
            "guidePath" to MCP_SETUP_PATH,
            "guideUrl" to publicUrl.of(MCP_SETUP_PATH),
        )
    }

    @GetMapping("/api/v1/me/access-tokens")
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        exchange: ServerWebExchange,
    ): List<AccessTokenView> {
        exchange.response.headers.cacheControl = "no-store"
        return tokens.list(user)
    }

    @PostMapping("/api/v1/me/access-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun issue(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: IssueTokenRequest,
        exchange: ServerWebExchange,
    ): IssuedToken {
        exchange.response.headers.cacheControl = "no-store"
        val issued = tokens.issue(user, body)
        audit.record(
            AuditEventType.ACCESS_TOKEN_CREATED,
            user.id,
            ClientInfo.from(exchange),
            mapOf(
                "tokenId" to issued.item.id.toString(),
                "name" to issued.item.name,
                "scope" to issued.item.scope.name,
            ),
        )
        return issued
    }

    @DeleteMapping("/api/v1/me/access-tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun revoke(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ) {
        tokens.revoke(user, id)
        audit.record(AuditEventType.ACCESS_TOKEN_REVOKED, user.id, ClientInfo.from(exchange), mapOf("tokenId" to id.toString()))
    }
}
