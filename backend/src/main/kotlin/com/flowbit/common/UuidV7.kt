package com.flowbit.common

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** Time-ordered UUIDs (RFC 9562 version 7). */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(now: Instant): UUID {
        val bytes = ByteArray(10).also(random::nextBytes)
        val msb =
            (now.toEpochMilli() shl 16) or
                0x7000L or
                ((bytes[0].toLong() and 0x0F) shl 8) or
                (bytes[1].toLong() and 0xFF)
        var lsb = 0L
        for (i in 2..9) {
            lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        }
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }
}
