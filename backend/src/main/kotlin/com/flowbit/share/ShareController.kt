package com.flowbit.share

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.api.EditorFileController
import com.flowbit.media.api.FileController
import org.springframework.core.io.Resource
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
    val item: String?,
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
            page.items.map { ShareAccessResponse(it.accessedAt, it.ipAddress, it.userAgent, it.embedded, it.referrer, it.item) },
        )
    }
}

/**
 * What a share link shows, and what it leads to: its own page, the pages under it, and the pictures and files those hold.
 * Open to everyone, signed in or not (SecurityConfig); every one of them is looked up by the link's token and nothing else.
 */
@RestController
@RequestMapping("/api/v1/public/shares")
class PublicShareController(
    private val service: ShareService,
    private val files: FileController,
    private val editorFiles: EditorFileController,
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

    /** A page under the shared one: `type` `issue` (a sub-issue, by number) or `document` (by id). Not found when it is not part of what was shared. */
    @GetMapping("/{token}/items/{type}/{id}")
    suspend fun item(
        @PathVariable token: String,
        @PathVariable type: String,
        @PathVariable id: String,
        @RequestParam(defaultValue = "false") embedded: Boolean,
        @RequestParam(required = false) referrer: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<PublicShare> {
        val shared = service.openItem(token, type, id, ClientInfo.from(exchange), embedded, referrer)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shared)
    }

    /** A picture the shared text holds, in the form the page shows. */
    @GetMapping("/{token}/files/{key}")
    suspend fun image(
        @PathVariable token: String,
        @PathVariable key: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Resource> {
        service.checkFile(token, SharedFile(SharedFileKind.IMAGE, key), ClientInfo.from(exchange))
        return files.file(key)
    }

    /** The picture as it was uploaded, to save. */
    @GetMapping("/{token}/files/{key}/original")
    suspend fun imageOriginal(
        @PathVariable token: String,
        @PathVariable key: String,
        @RequestParam(required = false) name: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Resource> {
        service.checkFile(token, SharedFile(SharedFileKind.IMAGE, key), ClientInfo.from(exchange))
        return files.original(key, name)
    }

    /** A file the shared text holds as a block, to save. */
    @GetMapping("/{token}/editor-files/{key}")
    suspend fun editorFile(
        @PathVariable token: String,
        @PathVariable key: String,
        @RequestParam(required = false) name: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Resource> {
        service.checkFile(token, SharedFile(SharedFileKind.EDITOR_FILE, key), ClientInfo.from(exchange))
        return editorFiles.download(key, name)
    }
}
