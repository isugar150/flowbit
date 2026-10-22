package com.flowbit.media.application

import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

/**
 * Keeps processed images in the [ObjectStore] (the storage folder or an S3 bucket, D-167), and next to
 * each the file as it was uploaded (D-169). The processed copy is smaller and lossy, so on its own it
 * could not rebuild the picture; the original can. A key is the file name: a random UUID plus `.webp`.
 * The original lives under the same UUID, without the extension, in the `originals` area.
 * Keys never come from user input.
 */
@Component
class ImageStorage(
    private val objects: ObjectStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Stores both and returns the key of the processed copy. Fails, storing neither, when either cannot be kept. */
    suspend fun store(
        webp: ByteArray,
        original: ByteArray,
    ): String {
        val id = StorageKeys.newId()
        // The original first: a picture that could not be kept in full is not kept at all.
        objects.put(StorageArea.ORIGINALS, id, original)
        try {
            objects.put(StorageArea.IMAGES, "$id$EXTENSION", webp)
        } catch (e: Exception) {
            remove(StorageArea.ORIGINALS, id)
            throw e
        }
        return "$id$EXTENSION"
    }

    /** Removes a stored image and its original. Never fails the caller: a leftover file is harmless, a failed request is not. */
    suspend fun delete(key: String?) {
        if (key == null || !KEY_PATTERN.matches(key)) return
        remove(StorageArea.IMAGES, key)
        remove(StorageArea.ORIGINALS, key.removeSuffix(EXTENSION))
    }

    /** The image for a key, or null when the key is malformed or nothing is stored under it. */
    suspend fun find(key: String): Resource? = if (KEY_PATTERN.matches(key)) objects.get(StorageArea.IMAGES, key) else null

    private suspend fun remove(
        area: StorageArea,
        key: String,
    ) {
        try {
            objects.delete(area, key)
        } catch (e: Exception) {
            log.warn("Could not delete stored image {}/{}", area.folder, key, e)
        }
    }

    companion object {
        private const val EXTENSION = ".webp"
        val KEY_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")

        fun urlOf(key: String?): String? = key?.let { "/api/v1/files/$it" }
    }
}
