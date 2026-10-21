package com.flowbit.media.application

import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Keeps processed images in the [ObjectStore] (the storage folder or an S3 bucket, D-167).
 * A key is the file name: a random UUID plus `.webp`. Keys never come from user input.
 */
@Component
class ImageStorage(
    private val objects: ObjectStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun store(webp: ByteArray): String {
        val key = "${UUID.randomUUID()}.webp"
        objects.put(StorageArea.IMAGES, key, webp)
        return key
    }

    /** Removes a stored image. Never fails the caller: a leftover file is harmless, a failed request is not. */
    suspend fun delete(key: String?) {
        if (key == null || !KEY_PATTERN.matches(key)) return
        try {
            objects.delete(StorageArea.IMAGES, key)
        } catch (e: Exception) {
            log.warn("Could not delete stored image {}", key, e)
        }
    }

    /** The image for a key, or null when the key is malformed or nothing is stored under it. */
    suspend fun find(key: String): Resource? = if (KEY_PATTERN.matches(key)) objects.get(StorageArea.IMAGES, key) else null

    companion object {
        val KEY_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")

        fun urlOf(key: String?): String? = key?.let { "/api/v1/files/$it" }
    }
}
