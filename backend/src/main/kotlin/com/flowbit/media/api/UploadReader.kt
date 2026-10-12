package com.flowbit.media.api

import com.flowbit.common.error.ApiException
import com.flowbit.media.application.StorageProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Component

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
                DataBufferUtils.join(file.content(), limit.toBytes().toInt()).awaitSingle()
            } catch (_: DataBufferLimitException) {
                throw UploadTooLargeException("${limit.toMegabytes()}MB")
            }
        try {
            return ByteArray(buffer.readableByteCount()).also(buffer::read)
        } finally {
            DataBufferUtils.release(buffer)
        }
    }
}
