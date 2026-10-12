package com.flowbit.media.api

import com.flowbit.media.application.ImageStorage
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
        val path = imageStorage.find(key) ?: throw ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)
        return ResponseEntity
            .ok()
            .contentType(WEBP)
            // A new upload gets a new key, so a given URL never changes its content.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(path))
    }

    private companion object {
        val WEBP: MediaType = MediaType.parseMediaType("image/webp")
    }
}
