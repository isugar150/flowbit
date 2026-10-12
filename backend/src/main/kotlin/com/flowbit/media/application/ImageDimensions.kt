package com.flowbit.media.application

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

data class ImageDimensions(
    val width: Int,
    val height: Int,
) {
    val pixels: Long
        get() = width.toLong() * height.toLong()

    companion object {
        /**
         * Width and height read from the file header, without decoding the pixels. Lets the caller
         * reject an image that would need gigabytes of memory before any of it is allocated.
         * Returns null when the bytes are not a JPEG, PNG, GIF or WebP image.
         */
        fun read(bytes: ByteArray): ImageDimensions? = readWebp(bytes) ?: readWithImageIo(bytes)

        private fun readWithImageIo(bytes: ByteArray): ImageDimensions? =
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                if (reader.formatName.lowercase() !in SUPPORTED_IMAGE_IO_FORMATS) return null
                try {
                    reader.input = input
                    ImageDimensions(reader.getWidth(0), reader.getHeight(0))
                } catch (_: Exception) {
                    null
                } finally {
                    reader.dispose()
                }
            }

        /** WebP is not known to ImageIO, so its three header layouts are parsed here. */
        private fun readWebp(bytes: ByteArray): ImageDimensions? {
            if (bytes.size < 30 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WEBP") return null
            return when (ascii(bytes, 12)) {
                "VP8X" -> ImageDimensions(1 + uint24(bytes, 24), 1 + uint24(bytes, 27))
                "VP8 " -> ImageDimensions(uint16(bytes, 26) and 0x3FFF, uint16(bytes, 28) and 0x3FFF)
                "VP8L" -> {
                    val bits = uint16(bytes, 21).toLong() or (uint16(bytes, 23).toLong() shl 16)
                    ImageDimensions(1 + (bits and 0x3FFF).toInt(), 1 + ((bits shr 14) and 0x3FFF).toInt())
                }
                else -> null
            }
        }

        private fun ascii(
            bytes: ByteArray,
            offset: Int,
        ): String = String(bytes, offset, 4, Charsets.US_ASCII)

        private fun byteAt(
            bytes: ByteArray,
            offset: Int,
        ): Int = bytes[offset].toInt() and 0xFF

        private fun uint16(
            bytes: ByteArray,
            offset: Int,
        ): Int = byteAt(bytes, offset) or (byteAt(bytes, offset + 1) shl 8)

        private fun uint24(
            bytes: ByteArray,
            offset: Int,
        ): Int = uint16(bytes, offset) or (byteAt(bytes, offset + 2) shl 16)

        private val SUPPORTED_IMAGE_IO_FORMATS = setOf("jpeg", "jpg", "png", "gif")
    }
}
