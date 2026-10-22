package com.flowbit.media.api

import com.flowbit.media.application.FileStorage
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets

data class UploadedEditorFileResponse(
    val url: String,
    val name: String,
    val size: Long,
)

/**
 * Files placed in an editor document as a block of their own (D-182). Like pictures, any signed-in user may upload one; the
 * document that ends up pointing at it is checked where it is saved (RichText). The file is kept as it came in and only ever
 * served as a download of opaque bytes. Its name lives in the document, so the download takes it back in `name`.
 */
@RestController
@RequestMapping("/api/v1/editor-files")
class EditorFileController(
    private val uploadReader: UploadReader,
    private val fileStorage: FileStorage,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun upload(
        @RequestPart("file") file: FilePart,
    ): UploadedEditorFileResponse {
        val bytes = uploadReader.read(file)
        val key = fileStorage.store(bytes)
        return UploadedEditorFileResponse("$URL_PREFIX$key", fileNameOf(file.filename()), bytes.size.toLong())
    }

    @GetMapping("/{key}")
    suspend fun download(
        @PathVariable key: String,
        @RequestParam(required = false) name: String?,
    ): ResponseEntity<Resource> {
        val resource = fileStorage.find(key) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val disposition = ContentDisposition.attachment().filename(fileNameOf(name ?: "file"), StandardCharsets.UTF_8).build()
        return ResponseEntity
            .ok()
            // Always a download of opaque bytes: an uploaded page or script must never run from this origin.
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    /** Keeps only the last path segment and no control characters: the name is shown and sent back in a header. */
    private fun fileNameOf(raw: String): String =
        raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifEmpty { "file" }

    companion object {
        const val URL_PREFIX = "/api/v1/editor-files/"
        const val MAX_NAME_LENGTH = 255
    }
}
