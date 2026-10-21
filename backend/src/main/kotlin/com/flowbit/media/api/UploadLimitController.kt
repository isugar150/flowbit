package com.flowbit.media.api

import com.flowbit.media.application.StorageProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UploadLimitResponse(
    val maxUploadBytes: Long,
)

/**
 * The largest file the server takes (FLOWBIT_MAX_UPLOAD_SIZE), for the screens that check a file before sending it and
 * say what the limit is. Public: the setup page, where nobody is signed in yet, picks a logo too. It says nothing secret.
 */
@RestController
@RequestMapping("/api/v1/upload-limit")
class UploadLimitController(
    private val properties: StorageProperties,
) {
    @GetMapping
    fun limit() = UploadLimitResponse(properties.maxUploadSize.toBytes())
}
