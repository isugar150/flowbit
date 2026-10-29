package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FILE_PASSWORD = "files-test-password-1234"

class McpFilesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var editorId: String
    private lateinit var project: String
    private lateinit var secret: String
    private lateinit var readOnly: String
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun prepareFiles() {
        admin = client().also { it.setup("admin", FILE_PASSWORD) }
        editorId = admin.post("/api/v1/admin/members", """{"loginId":"editor","password":"$FILE_PASSWORD"}""").json()["id"] as String
        project = admin.post("/api/v1/projects", """{"name":"파일 검증"}""").json()["id"] as String
        admin.put("/api/v1/projects/$project/members/$editorId", """{"role":"EDITOR"}""")
        editor = client().also { it.login("editor", FILE_PASSWORD) }
        editor.post("/api/v1/issues", """{"title":"파일 티켓","projectId":"$project","description":"보존할 본문"}""")
        secret = token("WRITE")
        readOnly = token("READ")
    }

    private fun token(scope: String) = editor.post("/api/v1/me/access-tokens", """{"name":"파일 검증","scope":"$scope"}""").json()["token"] as String

    private fun request(
        path: String,
        method: String = "GET",
        token: String = secret,
        bytes: ByteArray? = null,
        type: String = "application/json",
    ): ApiResponse {
        val builder =
            HttpRequest
                .newBuilder(
                    URI("http://localhost:$port$path"),
                ).header("Authorization", "Bearer $token")
                .header("Accept", "application/json, text/event-stream, */*")
        if (bytes != null) builder.header("Content-Type", type)
        builder.method(method, bytes?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return ApiResponse(response.statusCode(), response.body().toString(Charsets.UTF_8), response.headers().map(), response.body())
    }

    private fun tool(
        name: String,
        args: Map<String, Any?> = mapOf("issue" to "1"),
        token: String = secret,
    ) = request("/api/v1/integrations/mcp/tools/$name", "POST", token, json.writeValueAsBytes(args))

    private fun attach(
        name: String,
        bytes: ByteArray,
        placement: String = "ATTACHMENT",
        image: Boolean = false,
    ): ApiResponse =
        tool(
            "attach_file",
            mapOf(
                "issue" to "1",
                "fileName" to name,
                "data" to Base64.getEncoder().encodeToString(bytes),
                "placement" to placement,
                "image" to image,
            ),
        )

    private fun entries(): List<Map<*, *>> = (tool("list_issue_files").json()["items"] as List<*>).map { it as Map<*, *> }

    private fun id(name: String): String = entries().first { it["name"] == name }["fileId"] as String

    private fun read(
        id: String,
        extra: Map<String, Any?> = emptyMap(),
        token: String = readOnly,
    ): Map<*, *> {
        val result = tool("read_file", mapOf("issue" to "1", "fileId" to id) + extra, token)
        assertEquals(200, result.status, result.body)
        return result.json()["data"] as Map<*, *>
    }

    private fun multipart(
        name: String,
        bytes: ByteArray,
        query: String = "",
        token: String = secret,
    ): ApiResponse {
        val boundary = "flowbit-mcp-files-test"
        val body =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray() +
                bytes +
                "\r\n--$boundary--\r\n".toByteArray()
        return request("/api/v1/integrations/mcp/issues/1/files$query", "POST", token, body, "multipart/form-data; boundary=$boundary")
    }

    @Test
    fun `첨부한 텍스트와 JSON은 실제 내용을 구간별로 읽고 원본을 받는다`() {
        val bytes = "{\"메시지\":\"읽을 수 있어요\",\"number\":12345678901234567890}".toByteArray()
        assertEquals(200, attach("검증.json", bytes).status)
        val fileId = id("검증.json")
        val first = read(fileId, mapOf("limit" to 12))
        val second = read(fileId, mapOf("offset" to first["nextOffset"], "limit" to 1000))
        assertEquals(bytes.toString(Charsets.UTF_8), first["text"].toString() + second["text"].toString())
        assertEquals(false, first["extractionTruncated"])
        val download = request("/api/v1/integrations/mcp/issues/1/files/$fileId", token = readOnly)
        assertEquals(200, download.status)
        assertContentEquals(bytes, download.bytes)
        assertTrue(download.header("Content-Disposition").orEmpty().contains("attachment"))
        assertTrue(tool("get_issue").body.contains("검증.json"))
        assertEquals(422, tool("read_file", mapOf("issue" to "1", "fileId" to fileId, "offset" to -1)).status)
        assertEquals(422, tool("read_file", mapOf("issue" to "1", "fileId" to fileId, "mode" to "BASE64", "offset" to 9999)).status)
    }

    @Test
    fun `본문 파일과 사진을 추가해도 기존 본문과 블록 ID를 보존한다`() {
        val before = editor.get("/api/v1/issues/1").json()
        assertEquals(200, attach("메모.txt", "본문 첨부 내용".toByteArray(), "BODY").status)
        val afterFile = editor.get("/api/v1/issues/1").json()["content"] as Map<*, *>
        val firstBlock = (afterFile["content"] as List<*>).first()
        assertTrue(json.writeValueAsString(firstBlock).contains("보존할 본문"))
        assertEquals(200, attach("사진.png", png(), "BODY", true).status)
        val afterImage = editor.get("/api/v1/issues/1").json()["content"] as Map<*, *>
        assertEquals(firstBlock, (afterImage["content"] as List<*>).first())
        assertEquals(before["title"], editor.get("/api/v1/issues/1").json()["title"])
        assertEquals("본문 첨부 내용", read(id("메모.txt"))["text"])
        val imageId = id("사진.png")
        val image = tool("read_file", mapOf("issue" to "1", "fileId" to imageId), readOnly)
        assertEquals("image", ((image.json()["content"] as List<*>).single() as Map<*, *>)["type"])
        val raw = read(imageId, mapOf("mode" to "BASE64"))
        assertContentEquals(png(), Base64.getDecoder().decode(raw["data"] as String))
        val rpc =
            request(
                "/api/v1/mcp",
                "POST",
                readOnly,
                json.writeValueAsBytes(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 1,
                        "method" to "tools/call",
                        "params" to mapOf("name" to "read_file", "arguments" to mapOf("issue" to "1", "fileId" to imageId)),
                    ),
                ),
            )
        val result = rpc.json()["result"] as Map<*, *>
        assertEquals(false, result["isError"])
        assertTrue(
            (result["content"] as List<*>).filterIsInstance<Map<*, *>>().any {
                it["type"] == "image" &&
                    it["mimeType"] == "image/webp" &&
                    (it["data"] as String).isNotEmpty()
            },
        )
    }

    @Test
    fun `기존 화면에서 올린 본문 댓글과 연결 문서의 파일도 읽는다`() {
        val uploaded = editor.postFile("/api/v1/editor-files", "기존.txt", "text/plain", "기존 파일 내용".toByteArray()).json()
        val node = mapOf("type" to "fileBlock", "attrs" to mapOf("src" to uploaded["url"], "name" to "기존.txt", "size" to 20))
        val document = mapOf("type" to "doc", "content" to listOf(node))
        val docId = editor.post("/api/v1/issues/1/documents", """{"title":"연결 문서"}""").json()["id"]
        val saved = editor.patch("/api/v1/documents/$docId", json.writeValueAsString(mapOf("content" to document)))
        assertEquals(200, saved.status, saved.body)
        assertEquals("기존 파일 내용", read(id("기존.txt"))["text"])
        assertTrue(entries().any { it["location"] == "document:$docId" })
        assertTrue(tool("get_document", mapOf("documentId" to docId)).body.contains("기존.txt"))
        val docFile = editor.postFile("/api/v1/content/document/$docId/comment-files", "문서댓글.txt", "text/plain", "문서 댓글 파일".toByteArray()).json()["id"]
        val docComment = editor.post("/api/v1/content/document/$docId/comments", json.writeValueAsString(mapOf("attachmentIds" to listOf(docFile))))
        assertEquals(201, docComment.status, docComment.body)
        assertEquals("문서 댓글 파일", read(id("문서댓글.txt"))["text"])
        assertEquals(200, attach("댓글.txt", "댓글 파일 내용".toByteArray(), "COMMENT").status)
        val commentFileId = id("댓글.txt")
        assertEquals("댓글 파일 내용", read(commentFileId)["text"])
        val comments = editor.get("/api/v1/issues/1/activity").json()["comments"] as List<*>
        val commentId = (comments.single() as Map<*, *>)["id"]
        editor.delete("/api/v1/comments/$commentId")
        assertEquals(404, tool("read_file", mapOf("issue" to "1", "fileId" to commentFileId)).status)
        assertEquals(200, attach("댓글사진.png", png(), "COMMENT", true).status)
        assertTrue(entries().any { it["name"] == "댓글사진.png" && it["location"].toString().startsWith("comment:") })
    }

    @Test
    fun `다른 티켓 파일 임의 경로 삭제된 참조 읽기와 읽기 토큰 업로드를 거절한다`() {
        attach("비공개.txt", "비공개 내용".toByteArray(), "BODY")
        val fileId = id("비공개.txt")
        editor.post("/api/v1/issues", """{"title":"다른 티켓","projectId":"$project"}""")
        assertEquals(404, tool("read_file", mapOf("issue" to "2", "fileId" to fileId)).status)
        assertEquals(404, request("/api/v1/integrations/mcp/issues/2/files/$fileId").status)
        assertEquals(404, tool("read_file", mapOf("issue" to "1", "fileId" to "../../data")).status)
        val viewerId = admin.post("/api/v1/admin/members", """{"loginId":"viewer","password":"$FILE_PASSWORD"}""").json()["id"]
        admin.put("/api/v1/projects/$project/members/$viewerId", """{"role":"VIEWER"}""")
        assertEquals(403, multipart("차단.txt", byteArrayOf(1), token = readOnly).status)
        assertEquals(403, tool("attach_file", mapOf("issue" to "1", "fileName" to "차단", "data" to "YQ=="), readOnly).status)
        admin.put("/api/v1/projects/$project/members/$editorId", """{"role":"VIEWER"}""")
        assertEquals(403, attach("차단.txt", byteArrayOf(1)).status)
        assertEquals(200, tool("read_file", mapOf("issue" to "1", "fileId" to fileId)).status)
        admin.put("/api/v1/projects/$project/members/$editorId", """{"role":"EDITOR"}""")
        editor.patch("/api/v1/issues/1", """{"content":null}""")
        assertEquals(404, request("/api/v1/integrations/mcp/issues/1/files/$fileId").status)
        admin.delete("/api/v1/projects/$project/members/$editorId")
        assertEquals(404, tool("list_issue_files").status)
    }

    @Test
    fun `작은 도구 업로드와 큰 multipart 업로드는 기존 용량 제한과 파일명을 따른다`() {
        val big = ByteArray(140_000) { 'a'.code.toByte() }
        assertEquals(413, attach("큰파일.txt", big).status)
        assertEquals(201, multipart("큰파일.txt", big).status)
        assertEquals(140_000, read(id("큰파일.txt"))["totalCharacters"])
        assertEquals(413, multipart("제한초과.txt", ByteArray(5 * 1024 * 1024 + 1)).status)
        assertEquals(422, tool("attach_file", mapOf("issue" to "1", "fileName" to "오류", "data" to "not base64!")).status)
        assertEquals(200, attach("../안전.txt", "안전".toByteArray()).status)
        assertTrue(entries().any { it["name"] == "안전.txt" })
        assertEquals(201, multipart("빈파일.txt", byteArrayOf()).status)
        assertEquals("", read(id("빈파일.txt"))["text"])
    }

    @Test
    fun `Word Excel PowerPoint의 실제 글을 추출한다`() {
        val word =
            ByteArrayOutputStream().use { output ->
                XWPFDocument().use { doc ->
                    doc.createParagraph().createRun().setText("문서의 실제 내용")
                    doc.write(output)
                }
                output.toByteArray()
            }
        val sheet =
            ByteArrayOutputStream().use { output ->
                XSSFWorkbook().use { book ->
                    book
                        .createSheet("테스트")
                        .createRow(0)
                        .createCell(0)
                        .setCellValue("표의 실제 내용")
                    book.write(output)
                }
                output.toByteArray()
            }
        val slides =
            ByteArrayOutputStream().use { output ->
                XMLSlideShow().use { deck ->
                    deck.createSlide().createTextBox().setText("슬라이드 실제 내용")
                    deck.write(output)
                }
                output.toByteArray()
            }
        for ((name, bytes, expected) in listOf(
            Triple("문서.docx", word, "문서의 실제 내용"),
            Triple("표.xlsx", sheet, "표의 실제 내용"),
            Triple("발표.pptx", slides, "슬라이드 실제 내용"),
        )) {
            val upload = multipart(name, bytes)
            assertEquals(201, upload.status, upload.body)
            val data = read(id(name))
            assertEquals("TEXT", data["status"], data.toString())
            assertTrue(data["text"].toString().contains(expected), data.toString())
        }
    }

    @Test
    fun `PDF는 페이지 글과 실제 이미지를 제공하고 스캔과 암호화 상태를 구분한다`() {
        assertEquals(200, attach("문서.pdf", pdf()).status)
        val fileId = id("문서.pdf")
        val second = read(fileId, mapOf("page" to 2))
        assertEquals(2, second["pageCount"])
        assertTrue(second["text"].toString().contains("Second page"))
        assertFalse(second["text"].toString().contains("First page"))
        val rendered = tool("render_file_page", mapOf("issue" to "1", "fileId" to fileId, "page" to 1))
        assertEquals(200, rendered.status, rendered.body)
        assertEquals("image", ((rendered.json()["content"] as List<*>).single() as Map<*, *>)["type"])
        assertEquals(422, tool("read_file", mapOf("issue" to "1", "fileId" to fileId, "page" to 3)).status)
        attach("스캔.pdf", pdf(blank = true))
        assertEquals(true, read(id("스캔.pdf"))["rendered"])
        attach("암호.pdf", pdf(encrypted = true))
        assertEquals("UNREADABLE", read(id("암호.pdf"))["status"])
    }

    @Test
    fun `지원하지 않는 바이너리도 원본 바이트 구간과 다운로드로 빠짐없이 제공한다`() {
        val bytes = ByteArray(1000) { it.toByte() }
        attach("원본.bin", bytes)
        val fileId = id("원본.bin")
        assertEquals("DOWNLOAD_REQUIRED", read(fileId)["status"])
        val output = ByteArrayOutputStream()
        var offset: Any? = 0
        while (offset != null) {
            val piece = read(fileId, mapOf("mode" to "BASE64", "offset" to offset, "limit" to 100))
            output.write(Base64.getDecoder().decode(piece["data"] as String))
            offset = piece["nextOffset"]
        }
        assertContentEquals(bytes, output.toByteArray())
        assertContentEquals(bytes, request("/api/v1/integrations/mcp/issues/1/files/$fileId").bytes)
    }

    private fun png(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }

    private fun pdf(
        blank: Boolean = false,
        encrypted: Boolean = false,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            PDDocument().use { doc ->
                for (text in listOf("First page", "Second page")) {
                    val page = PDPage().also(doc::addPage)
                    if (!blank) {
                        PDPageContentStream(doc, page).use { stream ->
                            stream.beginText()
                            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                            stream.newLineAtOffset(30f, 700f)
                            stream.showText(text)
                            stream.endText()
                        }
                    }
                }
                if (encrypted) doc.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()))
                doc.save(output)
            }
            output.toByteArray()
        }
}
