package com.flowbit.media.application

import com.flowbit.common.error.ApiException
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.webp.WebpWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** How an uploaded image is fitted to its use. */
enum class ImageFit {
    /** Fill the box exactly, cropping whatever overflows from the centre. For avatars. */
    COVER,

    /** Shrink to fit inside the box, keeping the aspect ratio. Smaller images are left as they are. For logos. */
    INSIDE,
}

data class ImageSpec(
    val width: Int,
    val height: Int,
    val fit: ImageFit,
) {
    companion object {
        /** Shown at up to 64px; stored at 256px so it stays sharp on high-density screens. */
        val AVATAR = ImageSpec(256, 256, ImageFit.COVER)
        val LOGO = ImageSpec(512, 512, ImageFit.INSIDE)
    }
}

class UnsupportedImageException :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "UNSUPPORTED_IMAGE",
        "이미지 파일을 읽을 수 없습니다. JPG, PNG, WebP, GIF 파일을 올려 주세요",
    )

class ImageTooLargeException :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "IMAGE_DIMENSIONS_TOO_LARGE",
        "이미지의 가로·세로 크기가 너무 큽니다. 더 작은 이미지를 올려 주세요",
    )

/**
 * Turns an upload into the one format the app stores: WebP, resized for its use.
 * Re-encoding also drops EXIF data (location, device) and anything else hidden in the original file.
 */
@Component
class ImageProcessor {
    suspend fun toWebp(
        bytes: ByteArray,
        spec: ImageSpec,
    ): ByteArray {
        val dimensions = ImageDimensions.read(bytes) ?: throw UnsupportedImageException()
        if (dimensions.width <= 0 || dimensions.height <= 0) throw UnsupportedImageException()
        if (dimensions.pixels > MAX_PIXELS) throw ImageTooLargeException()

        // Decoding and encoding are CPU-heavy and the encoder is an external process: keep them off the event loop.
        return withContext(Dispatchers.IO) {
            val image =
                try {
                    ImmutableImage.loader().fromBytes(bytes)
                } catch (_: Exception) {
                    throw UnsupportedImageException()
                }
            val fitted =
                when (spec.fit) {
                    ImageFit.COVER -> image.cover(spec.width, spec.height)
                    ImageFit.INSIDE ->
                        if (image.width > spec.width || image.height > spec.height) {
                            image.bound(spec.width, spec.height)
                        } else {
                            image
                        }
                }
            fitted.bytes(WebpWriter.DEFAULT.withQ(QUALITY))
        }
    }

    private companion object {
        /** About 160 MB once decoded to 4 bytes per pixel; anything bigger is refused before decoding. */
        const val MAX_PIXELS = 40_000_000L
        const val QUALITY = 82
    }
}
