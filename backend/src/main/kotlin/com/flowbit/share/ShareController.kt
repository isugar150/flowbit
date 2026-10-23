package com.flowbit.share

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

data class ShareRequest(
    val kind: String? = null,
    val params: Map<String, String>? = null,
)

data class ShareResponse(
    val id: UUID,
    val kind: String,
    val params: Map<String, String>,
    val token: String,
    /** Where the link opens, on the address people open Flowbit at. */
    val path: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(shared: SharedLink) =
            ShareResponse(
                id = shared.link.id,
                kind = shared.link.kind,
                params = shared.params,
                token = shared.link.token,
                path = "/s/${shared.link.token}",
                createdAt = shared.link.createdAt,
            )
    }
}

data class ShareAccessResponse(
    val accessedAt: Instant,
    val ipAddress: String?,
    val userAgent: String?,
    val embedded: Boolean,
    val referrer: String?,
)

data class ShareAccessesResponse(
    val total: Long,
    val items: List<ShareAccessResponse>,
)

/** Making, stopping and reading the history of one's own share links. */
@RestController
@RequestMapping("/api/v1/shares")
class ShareController(
    private val service: ShareService,
) {
    /** The link to share this page: the person's existing one, or a new one. */
    @PostMapping
    suspend fun share(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: ShareRequest,
        exchange: ServerWebExchange,
    ): ShareResponse = ShareResponse.from(service.share(user, body.kind, body.params, ClientInfo.from(exchange)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun revoke(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        exchange: ServerWebExchange,
    ) {
        service.revoke(user, id, ClientInfo.from(exchange))
    }

    @GetMapping("/{id}/accesses")
    suspend fun accesses(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ShareAccessesResponse {
        val page = service.accesses(user, id)
        return ShareAccessesResponse(
            page.total,
            page.items.map { ShareAccessResponse(it.accessedAt, it.ipAddress, it.userAgent, it.embedded, it.referrer) },
        )
    }
}

/** What a share link shows. Open to everyone, signed in or not (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/public/shares")
class PublicShareController(
    private val service: ShareService,
) {
    @GetMapping("/{token}")
    suspend fun open(
        @PathVariable token: String,
        @RequestParam(defaultValue = "false") embedded: Boolean,
        @RequestParam(required = false) referrer: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<PublicShare> {
        val shared = service.open(token, ClientInfo.from(exchange), embedded, referrer)
        // What a link shows follows what its owner may see, so a copy kept anywhere would go stale.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shared)
    }
}
