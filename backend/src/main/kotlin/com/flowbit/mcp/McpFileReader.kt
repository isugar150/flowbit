package com.flowbit.mcp

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.media.application.ImageDimensions
import com.flowbit.media.application.ImageProcessor
import com.flowbit.media.application.ImageSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.tika.Tika
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.exception.WriteLimitReachedException
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.microsoft.OfficeParser
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser
import org.apache.tika.parser.microsoft.rtf.RTFParser
import org.apache.tika.parser.odf.OpenDocumentParser
import org.apache.tika.parser.txt.TXTParser
import org.apache.tika.sax.BodyContentHandler
import org.springframework.stereotype.Service
import org.xml.sax.ContentHandler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.imageio.ImageIO

/** Bounded local parsing; no remote fetches, external OCR, archive expansion, or executable parsers. */
@Service
class McpFileReader(
    private val files: McpFiles,
    private val images: ImageProcessor,
) {
    private val parser = AutoDetectParser(OfficeParser(), OOXMLParser(), OpenDocumentParser(), RTFParser(), TXTParser())
    private val tika = Tika(DefaultDetector(), parser)

    suspend fun read(
        user: AuthenticatedUser,
        number: Int,
        fileId: String,
        mode: String,
        offset: Int,
        limit: Int,
        page: Int,
        render: Boolean = false,
    ): McpFileContent {
        if (offset < 0 ||
            limit !in 1..(if (mode == "BASE64") BINARY_CHUNK_MAX else 20_000) ||
            page < 1
        ) {
            throw ValidationException("offset", "INVALID", "구간·크기·페이지를 확인하세요")
        }
        val stored = files.open(user, number, fileId, original = mode == "BASE64")
        val metadata = files.view(number, stored.ref)
        return withContext(Dispatchers.IO) {
            val totalBytes = stored.resource.contentLength()
            if (mode == "BASE64") {
                if (offset > totalBytes) throw ValidationException("offset", "INVALID", "구간이 파일 범위를 벗어났습니다")
                val chunkSize = minOf(limit, BINARY_CHUNK_MAX)
                val bytes =
                    stored.resource.inputStream.use { input ->
                        input.skipNBytes(offset.toLong())
                        input.readNBytes(chunkSize)
                    }
                val next = (offset.toLong() + bytes.size).takeIf { it < totalBytes }
                return@withContext McpFileContent(
                    metadata +
                        mapOf(
                            "encoding" to "base64",
                            "data" to Base64.getEncoder().encodeToString(bytes),
                            "byteOffset" to offset,
                            "nextOffset" to next,
                            "totalBytes" to totalBytes,
                        ),
                )
            }
            if (totalBytes > MAX_READ_BYTES) {
                return@withContext McpFileContent(
                    metadata +
                        mapOf(
                            "status" to "DOWNLOAD_REQUIRED",
                            "totalBytes" to totalBytes,
                            "message" to "파일이 32MB를 넘습니다. downloadUrl로 원본을 받거나 BASE64 모드로 구간을 읽으세요.",
                        ),
                )
            }
            val bytes = stored.resource.inputStream.use { it.readNBytes(MAX_READ_BYTES + 1) }
            if (bytes.size > MAX_READ_BYTES) throw ValidationException("fileId", "TOO_LARGE", "원본 다운로드를 사용하세요")
            val mime = tika.detect(bytes, stored.ref.name)
            if (render || mime == "application/pdf") {
                if (mime != "application/pdf") throw ValidationException("fileId", "NOT_PDF", "PDF 파일을 지정하세요")
                return@withContext try {
                    Loader.loadPDF(bytes).use { pdf ->
                        if (page > pdf.numberOfPages) throw ValidationException("page", "INVALID", "페이지가 파일의 범위를 벗어났습니다")
                        val pdfText =
                            PDFTextStripper()
                                .apply {
                                    startPage = page
                                    endPage = page
                                }.getText(pdf)
                        val data =
                            metadata +
                                mapOf(
                                    "mimeType" to mime,
                                    "page" to page,
                                    "pageCount" to pdf.numberOfPages,
                                    "nextPage" to (page + 1).takeIf { it <= pdf.numberOfPages },
                                )
                        val textResult = text(data, pdfText, offset, limit, pdfText.length > MAX_TEXT)
                        if (render || pdfText.isBlank()) {
                            val box = pdf.getPage(page - 1).cropBox
                            val scale = minOf(2f, 1600f / maxOf(box.width, box.height))
                            if (!scale.isFinite() || scale <= 0) throw ValidationException("page", "INVALID", "페이지 크기가 올바르지 않습니다")
                            val image = PDFRenderer(pdf).renderImage(page - 1, scale, ImageType.RGB)
                            val imageBytes =
                                ByteArrayOutputStream().use { output ->
                                    ImageIO.write(image, "png", output)
                                    output.toByteArray()
                                }
                            textResult.copy(data = textResult.data + ("rendered" to true), content = listOf(imageBlock(imageBytes, "image/png")))
                        } else {
                            textResult
                        }
                    }
                } catch (failure: ValidationException) {
                    throw failure
                } catch (_: Exception) {
                    McpFileContent(metadata + mapOf("status" to "UNREADABLE", "mimeType" to mime, "message" to "PDF를 읽을 수 없습니다. 암호화·손상 여부를 확인하거나 원본을 내려받으세요."))
                }
            }
            if (mode == "AUTO" && mime.startsWith("image/") && ImageDimensions.read(bytes) != null) {
                val webp = images.toWebp(bytes, ImageSpec.CONTENT)
                return@withContext McpFileContent(
                    metadata + mapOf("mimeType" to "image/webp", "status" to "IMAGE", "totalBytes" to bytes.size),
                    listOf(imageBlock(webp, "image/webp")),
                )
            }
            val data = metadata + mapOf("mimeType" to mime, "totalBytes" to bytes.size)
            if (isText(mime, stored.ref.name)) {
                val decoded = decode(bytes)
                if (decoded != null) return@withContext text(data, decoded, offset, limit, decoded.length > MAX_TEXT)
            }
            if (mime in OFFICE_MIMES || mime.contains("officedocument") || mime.contains("opendocument")) {
                val handler = BodyContentHandler(MAX_TEXT + 1)
                val context = ParseContext()
                context.set(
                    EmbeddedDocumentExtractor::class.java,
                    object : EmbeddedDocumentExtractor {
                        override fun shouldParseEmbedded(
                            metadata: Metadata,
                            parseContext: ParseContext,
                        ) = false

                        override fun parseEmbedded(
                            stream: TikaInputStream,
                            handler: ContentHandler,
                            metadata: Metadata,
                            parseContext: ParseContext,
                            outputHtml: Boolean,
                        ) = Unit
                    },
                )
                val info = Metadata().apply { set(TikaCoreProperties.RESOURCE_NAME_KEY, stored.ref.name) }
                var truncated = false
                try {
                    TikaInputStream.get(bytes).use { parser.parse(it, handler, info, context) }
                } catch (failure: Exception) {
                    if (WriteLimitReachedException.isWriteLimitReached(failure)) {
                        truncated = true
                    } else {
                        return@withContext McpFileContent(data + mapOf("status" to "UNREADABLE", "message" to "문서를 읽을 수 없습니다. 암호화·손상 여부를 확인하거나 원본을 내려받으세요."))
                    }
                }
                return@withContext text(data, handler.toString(), offset, limit, truncated)
            }
            McpFileContent(
                data + mapOf("status" to "DOWNLOAD_REQUIRED", "message" to "이 형식은 서버 텍스트 추출을 지원하지 않습니다. downloadUrl로 원본을 받거나 BASE64 모드로 바이트 구간을 읽어 분석하세요."),
            )
        }
    }

    private fun text(
        metadata: Map<String, Any?>,
        value: String,
        offset: Int,
        limit: Int,
        truncated: Boolean,
    ): McpFileContent {
        val bounded = value.take(MAX_TEXT)
        if (offset > bounded.length) throw ValidationException("offset", "INVALID", "구간이 텍스트 범위를 벗어났습니다")
        val end = minOf(offset.toLong() + limit, bounded.length.toLong()).toInt()
        return McpFileContent(
            metadata +
                mapOf(
                    "status" to "TEXT",
                    "text" to bounded.substring(offset, end),
                    "offset" to offset,
                    "nextOffset" to end.takeIf { it < bounded.length },
                    "totalCharacters" to bounded.length,
                    "extractionTruncated" to (truncated || value.length > MAX_TEXT),
                    "message" to if (truncated || value.length > MAX_TEXT) "추출 한도를 넘었습니다. 전체 내용은 원본을 내려받아 확인하세요." else null,
                ),
        )
    }

    private fun imageBlock(
        bytes: ByteArray,
        mime: String,
    ): Map<String, Any?> =
        mapOf(
            "type" to "image",
            "mimeType" to mime,
            "data" to Base64.getEncoder().encodeToString(bytes),
        )

    private fun isText(
        mime: String,
        name: String,
    ) = (mime.startsWith("text/") && mime !in OFFICE_MIMES) ||
        mime in setOf("application/json", "application/xml", "image/svg+xml", "application/javascript") ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun decode(bytes: ByteArray): String? {
        val charset =
            when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16
                else -> Charsets.UTF_8
            }

        fun decoded(charset: Charset): String? =
            try {
                charset
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix("\uFEFF")
                    .takeIf {
                        '\u0000' !in
                            it
                    }
            } catch (_: CharacterCodingException) {
                null
            }
        return decoded(charset) ?: if (charset == Charsets.UTF_8) decoded(Charset.forName("MS949")) else null
    }

    companion object {
        const val MAX_READ_BYTES = 32 * 1024 * 1024
        const val MAX_TEXT = 2_000_000
        const val BINARY_CHUNK_MAX = 48 * 1024
        private val TEXT_EXTENSIONS =
            setOf(
                "txt",
                "md",
                "json",
                "csv",
                "tsv",
                "log",
                "yaml",
                "yml",
                "xml",
                "html",
                "htm",
                "svg",
                "kt",
                "java",
                "py",
                "js",
                "ts",
                "tsx",
                "jsx",
                "css",
                "sql",
                "sh",
                "ps1",
                "toml",
                "ini",
                "properties",
            )
        private val OFFICE_MIMES =
            setOf(
                "application/msword",
                "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint",
                "application/rtf",
                "text/rtf",
                "application/x-tika-ooxml",
                "application/x-tika-msoffice",
            )
    }
}
