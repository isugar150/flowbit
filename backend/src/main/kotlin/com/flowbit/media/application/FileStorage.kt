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
 * Keeps attached files as they were uploaded, under the storage directory (FLOWBIT_STORAGE_PATH).
 * A key is a random UUID; the original file name lives in the database, never in the path.
 */
@Component
class FileStorage(
    properties: StorageProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directory: Path =
        properties.path
            .toAbsolutePath()
            .normalize()
            .resolve("files")

    init {
        Files.createDirectories(directory)
    }

    suspend fun store(bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val key = UUID.randomUUID().toString()
            // Write under a temporary name first so a half-written file is never served.
            val temporary = Files.createTempFile(directory, "upload-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            key
        }

    /** Never fails the caller: a leftover file is harmless, a failed request is not. */
    suspend fun delete(key: String) {
        val path = pathOf(key) ?: return
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                log.warn("Could not delete stored file {}", key, e)
            }
        }
    }

    suspend fun find(key: String): Path? {
        val path = pathOf(key) ?: return null
        return withContext(Dispatchers.IO) { path.takeIf(Files::isRegularFile) }
    }

    private fun pathOf(key: String): Path? {
        if (!KEY_PATTERN.matches(key)) return null
        return directory.resolve(key).normalize().takeIf { it.parent == directory }
    }

    private companion object {
        val KEY_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
