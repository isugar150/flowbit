package com.flowbit.mcp

import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.media.api.UploadReader
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.nio.charset.StandardCharsets

@RestController
class McpFileController(
    private val files: McpFiles,
    private val uploads: UploadReader,
) {
    @GetMapping("/api/v1/integrations/mcp/issues/{number}/files/{fileId}")
    suspend fun download(
        @PathVariable number: Int,
        @PathVariable fileId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Resource> {
        val identity = exchange.getAttribute<TokenIdentity>(MCP_IDENTITY) ?: throw UnauthenticatedException()
        val file = files.open(identity.user, number, fileId, original = true)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(file.ref.name, StandardCharsets.UTF_8)
                    .build()
                    .toString(),
            ).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.resource)
    }

    @PostMapping("/api/v1/integrations/mcp/issues/{number}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun upload(
        @PathVariable number: Int,
        @RequestPart("file") file: FilePart,
        @RequestParam(defaultValue = "ATTACHMENT") placement: McpFilePlacement,
        @RequestParam(defaultValue = "false") image: Boolean,
        @RequestParam(required = false) markdown: String?,
        exchange: ServerWebExchange,
    ): Any {
        val identity = exchange.getAttribute<TokenIdentity>(MCP_IDENTITY) ?: throw UnauthenticatedException()
        files.requireWrite(identity)
        exchange.response.headers.cacheControl = "no-store"
        return files.attach(identity.user, number, file.filename(), uploads.read(file), placement, image, markdown)
    }
}
