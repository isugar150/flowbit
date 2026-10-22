package com.flowbit.media.api

import com.flowbit.media.application.ImageStorage
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Serves stored images to signed-in users. */
@RestController
@RequestMapping("/api/v1/files")
class FileController(
    private val imageStorage: ImageStorage,
) {
    @GetMapping("/{key}")
    suspend fun file(
        @PathVariable key: String,
    ): ResponseEntity<Resource> {
        val image = imageStorage.find(key) ?: throw ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)
        return ResponseEntity
            .ok()
            .contentType(WEBP)
            // A new upload gets a new key, so a given URL never changes its content.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .header("X-Content-Type-Options", "nosniff")
            .body(image)
    }

    /**
     * The picture as it was uploaded (D-169), or the WebP copy when no original was kept. Always a download: the file's kind is
     * read from its first bytes, never trusted from the name, and the name only says what to save it as (`name`, the one the
     * picture was uploaded with, given back by the page).
     */
    @GetMapping("/{key}/original")
    suspend fun original(
        @PathVariable key: String,
        @RequestParam(required = false) name: String?,
    ): ResponseEntity<Resource> {
        val original = imageStorage.findOriginal(key)
        val resource = original ?: imageStorage.find(key) ?: throw ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)
        val (type, extension) = if (original != null) kindOf(original) else WEBP to "webp"
        val last = (name ?: "image").substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }
        val base =
            last
                .substringBeforeLast('.')
                .trim()
                .take(MAX_NAME)
                .ifEmpty { "image" }
        val disposition = ContentDisposition.attachment().filename("$base.$extension", StandardCharsets.UTF_8).build()
        return ResponseEntity
            .ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    /** What an upload is, from its first bytes: the picture types the server accepts; anything else is opaque bytes. */
    private fun kindOf(resource: Resource): Pair<MediaType, String> {
        val head = resource.inputStream.use { it.readNBytes(12) }

        fun starts(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        return when {
            starts(0x89, 0x50, 0x4E, 0x47) -> MediaType.IMAGE_PNG to "png"
            starts(0xFF, 0xD8, 0xFF) -> MediaType.IMAGE_JPEG to "jpg"
            starts(0x47, 0x49, 0x46, 0x38) -> MediaType.IMAGE_GIF to "gif"
            starts(0x52, 0x49, 0x46, 0x46) && head.size >= 12 && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> WEBP to "webp"
            else -> MediaType.APPLICATION_OCTET_STREAM to "bin"
        }
    }

    private companion object {
        const val MAX_NAME = 200
        val WEBP: MediaType = MediaType.parseMediaType("image/webp")
    }
}
