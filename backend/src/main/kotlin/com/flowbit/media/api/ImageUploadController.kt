package com.flowbit.media.api

import com.flowbit.media.application.ImageProcessor
import com.flowbit.media.application.ImageSpec
import com.flowbit.media.application.ImageStorage
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class UploadedImageResponse(
    val url: String,
)

/**
 * Stores an image to be placed in an editor document. Any signed-in user may upload; the document
 * that ends up pointing at the image is checked where it is saved (RichText).
 */
@RestController
@RequestMapping("/api/v1/images")
class ImageUploadController(
    private val uploadReader: UploadReader,
    private val imageProcessor: ImageProcessor,
    private val imageStorage: ImageStorage,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun upload(
        @RequestPart("file") file: FilePart,
    ): UploadedImageResponse {
        val upload = uploadReader.read(file)
        val key = imageStorage.store(webp = imageProcessor.toWebp(upload, ImageSpec.CONTENT), original = upload)
        return UploadedImageResponse(checkNotNull(ImageStorage.urlOf(key)))
    }
}
