package com.flowbit.media.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Keeps processed images as files under the storage directory (FLOWBIT_STORAGE_PATH).
 * A key is the file name: a random UUID plus `.webp`. Keys never come from user input.
 */
@Component
class ImageStorage(
    properties: StorageProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directory: Path =
        properties.path
            .toAbsolutePath()
            .normalize()
            .resolve("images")

    init {
        Files.createDirectories(directory)
        log.info("Uploaded images are stored in {}", directory)
    }

    suspend fun store(webp: ByteArray): String =
        withContext(Dispatchers.IO) {
            val key = "${UUID.randomUUID()}.webp"
            // Write under a temporary name first so a half-written file is never served.
            val temporary = Files.createTempFile(directory, "upload-", ".tmp")
            try {
                Files.write(temporary, webp)
                Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            key
        }

    /** Removes a stored image. Never fails the caller: a leftover file is harmless, a failed request is not. */
    suspend fun delete(key: String?) {
        val path = key?.let(::pathOf) ?: return
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                log.warn("Could not delete stored image {}", key, e)
            }
        }
    }

    /** The file for a key, or null when the key is malformed or no such file exists. */
    suspend fun find(key: String): Path? {
        val path = pathOf(key) ?: return null
        return withContext(Dispatchers.IO) { path.takeIf(Files::isRegularFile) }
    }

    private fun pathOf(key: String): Path? {
        if (!KEY_PATTERN.matches(key)) return null
        return directory.resolve(key).normalize().takeIf { it.parent == directory }
    }

    companion object {
        val KEY_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")

        fun urlOf(key: String?): String? = key?.let { "/api/v1/files/$it" }
    }
}
