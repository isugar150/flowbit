package com.flowbit.media.api

import com.flowbit.common.error.ApiException
import com.flowbit.media.application.StorageProperties
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize

class UploadTooLargeException(
    limit: String,
) : ApiException(HttpStatus.valueOf(413), "FILE_TOO_LARGE", "파일이 너무 큽니다. $limit 이하의 파일을 올려 주세요")

/** Reads an uploaded file into memory, refusing anything over the configured limit. */
@Component
class UploadReader(
    private val properties: StorageProperties,
) {
    suspend fun read(file: FilePart): ByteArray {
        val limit = properties.maxUploadSize
        val buffer =
            try {
                DataBufferUtils.join(file.content(), limit.toBytes().toInt()).awaitSingleOrNull() ?: return ByteArray(0)
            } catch (_: DataBufferLimitException) {
                throw UploadTooLargeException(describe(limit))
            }
        try {
            return ByteArray(buffer.readableByteCount()).also(buffer::read)
        } finally {
            DataBufferUtils.release(buffer)
        }
    }

    /** "10MB", "1.5MB" or "512KB": what a person reads, not a rounded-down number of megabytes. */
    private fun describe(limit: DataSize): String {
        val bytes = limit.toBytes()
        if (bytes < MEGABYTE) return "${bytes / KILOBYTE}KB"
        val megabytes = bytes.toDouble() / MEGABYTE
        return if (bytes % MEGABYTE == 0L) "${bytes / MEGABYTE}MB" else "${"%.1f".format(megabytes)}MB"
    }

    private companion object {
        const val KILOBYTE = 1024L
        const val MEGABYTE = 1024L * 1024L
    }
}
