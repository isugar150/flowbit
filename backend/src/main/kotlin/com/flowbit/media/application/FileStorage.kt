package com.flowbit.media.application

import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

/**
 * Keeps attached files as they were uploaded, in the [ObjectStore] (the storage folder or an S3
 * bucket, D-167). A key is a random UUID; the original file name lives in the database, never in the path.
 */
@Component
class FileStorage(
    private val objects: ObjectStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun store(bytes: ByteArray): String {
        val key = StorageKeys.newId()
        objects.put(StorageArea.FILES, key, bytes)
        return key
    }

    /** Never fails the caller: a leftover file is harmless, a failed request is not. */
    suspend fun delete(key: String) {
        if (!KEY_PATTERN.matches(key)) return
        try {
            objects.delete(StorageArea.FILES, key)
        } catch (e: Exception) {
            log.warn("Could not delete stored file {}", key, e)
        }
    }

    /** The stored file, or null when the key is malformed or nothing is stored under it. */
    suspend fun find(key: String): Resource? = if (KEY_PATTERN.matches(key)) objects.get(StorageArea.FILES, key) else null

    private companion object {
        val KEY_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
