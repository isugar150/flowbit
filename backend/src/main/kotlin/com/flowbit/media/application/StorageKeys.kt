package com.flowbit.media.application

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The names of stored objects (D-182). A key is a UUID. Files stored from now on get a version 7 UUID, which begins with the
 * time of the upload, so the storage can keep them in a folder per day (`2026/10/22/<key>`) and still find one from its key
 * alone. Files stored before that have a version 4 (random) UUID and stay where they are, directly in the area's folder.
 * The day is the UTC day.
 */
object StorageKeys {
    private val random = SecureRandom()

    /** A new key: the time in milliseconds, then random bits, in the layout of a version 7 UUID. */
    fun newId(now: Instant = Instant.now()): String {
        val high = (now.toEpochMilli() shl 16) or (VERSION_7 shl 12) or (random.nextInt(1 shl 12).toLong())
        val low = (random.nextLong() and LOW_62_BITS) or VARIANT
        return UUID(high, low).toString()
    }

    /**
     * The folder (`yyyy/MM/dd`) a key is kept in, or null for a key that is kept directly in the area's folder: a random UUID
     * from before the folders, or a name that is not a UUID. [key] may carry an extension (`<uuid>.webp`).
     */
    fun folderOf(key: String): String? {
        if (key.length < UUID_LENGTH) return null
        val id = runCatching { UUID.fromString(key.substring(0, UUID_LENGTH)) }.getOrNull() ?: return null
        if (id.version() != 7 || id.variant() != 2) return null
        val day = Instant.ofEpochMilli(id.mostSignificantBits ushr 16).atOffset(ZoneOffset.UTC)
        return "%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth)
    }

    /** Where a key is kept inside its area's folder: `2026/10/22/<key>`, or just the key. */
    fun pathOf(key: String): String = folderOf(key)?.let { "$it/$key" } ?: key

    private const val UUID_LENGTH = 36
    private const val VERSION_7 = 7L
    private const val LOW_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT = Long.MIN_VALUE
}
