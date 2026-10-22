package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import com.flowbit.support.TestUploads
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** The administrator's file page and the clean-up of files nothing uses (D-171, docs/features/admin-files.md). */
class AdminFilesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun start() {
        // Other tests leave files behind (their rows are gone): the page would count them as unused.
        FOLDERS.flatMap(TestUploads::filesIn).forEach(Files::delete)
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    @Test
    fun `관리자만 보고 올린 첨부와 사진이 영역별 수와 종류별 통계에 반영된다`() {
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }
        assertEquals(403, member.get("/api/v1/admin/files").status)
        assertEquals(403, member.post("/api/v1/admin/files/cleanup").status)

        admin.post("/api/v1/issues", """{"title":"티켓"}""")
        admin.postFile("/api/v1/issues/1/attachments", "log.txt", "text/plain", "로그".toByteArray())
        val photo = picture(1200, 800)
        assertEquals(200, admin.putFile("/api/v1/me/avatar", "a.png", "image/png", photo).status)

        val page = admin.get("/api/v1/admin/files")

        assertEquals(200, page.status)
        val json = page.json()
        assertEquals("LOCAL", json.at("storage", "type"))
        assertEquals(1, json.number("areas", "files", "count"))
        assertEquals(1, json.number("areas", "images", "count"))
        assertEquals(1, json.number("areas", "originals", "count"))
        assertEquals(3, json.number("total", "count"))
        assertEquals("로그".toByteArray().size.toLong() + photo.size + json.number("areas", "images", "bytes"), json.number("total", "bytes"))
        assertEquals(1, json.number("uses", "ticketAttachments", "count"))
        assertEquals(0, json.number("uses", "ticketCommentFiles", "count"))
        assertEquals(1, json.number("uses", "avatars", "count"))
        assertEquals(photo.size.toLong() + json.number("areas", "images", "bytes"), json.number("uses", "avatars", "bytes"))
        assertEquals("log.txt", (json.at("largestFiles") as List<*>).map { (it as Map<*, *>)["name"] }.single())
        assertEquals(1, (json.at("topUploaders") as List<*>).size)
        assertEquals(0, json.number("consistency", "orphans", "files", "count"))
        assertEquals(0, json.number("consistency", "missing", "files"))
        assertEquals(0, json.number("consistency", "imagesWithoutOriginal"))
    }

    @Test
    fun `저장소에는 있는데 DB 가 모르는 파일은 하루가 지나야 정리되고 쓰는 파일은 지워지지 않는다`() {
        admin.post("/api/v1/issues", """{"title":"티켓"}""")
        admin.postFile("/api/v1/issues/1/attachments", "log.txt", "text/plain", "로그".toByteArray())
        admin.postFile("/api/v1/issues/1/attachments", "lost.txt", "text/plain", "잃은 파일".toByteArray())
        admin.putFile("/api/v1/me/avatar", "a.png", "image/png", picture(300, 300))
        // The row of one attachment is gone (a request that died in between): its file is unused now.
        execute("delete from issue_attachments where file_name = 'lost.txt'")
        // A random (version 4) name is kept directly in the area's folder, as the files from before the day folders are (D-182).
        val strayImage = TestUploads.folder("images").resolve("${UUID.randomUUID()}.webp")
        Files.write(strayImage, byteArrayOf(1, 2, 3))
        val strangeName = TestUploads.folder("files").resolve("not-ours.tmp")
        Files.write(strangeName, byteArrayOf(9))

        val fresh = admin.get("/api/v1/admin/files").json()
        assertEquals(1, fresh.number("consistency", "orphans", "files", "count"))
        assertEquals(1, fresh.number("consistency", "orphans", "images", "count"))
        assertEquals(2, fresh.number("consistency", "inGrace"))
        assertEquals(0, fresh.number("consistency", "cleanable", "count"))
        assertEquals(1, fresh.number("consistency", "unknownObjects"))
        assertEquals(24, fresh.number("consistency", "graceHours"))

        // Too new: nothing goes.
        val early = admin.post("/api/v1/admin/files/cleanup").json()
        assertEquals(0, early.number("deleted", "files", "count"))
        assertTrue(Files.exists(strayImage))

        clock.advance(Duration.ofDays(2))
        admin.login("admin", PASSWORD)
        val cleaned = admin.post("/api/v1/admin/files/cleanup")

        assertEquals(200, cleaned.status)
        val result = cleaned.json()
        assertEquals(1, result.number("deleted", "files", "count"))
        assertEquals(1, result.number("deleted", "images", "count"))
        assertEquals(0, result.number("deleted", "originals", "count"))
        assertEquals(0, result.number("failed"))
        assertFalse(Files.exists(strayImage))
        // Kept: the files that are used, and what has a name that is not ours.
        assertEquals(1, TestUploads.filesIn("files").count { it.fileName.toString() != "not-ours.tmp" })
        assertTrue(Files.exists(strangeName))
        assertEquals(1, TestUploads.filesIn("images").size)
        assertEquals(1, TestUploads.filesIn("originals").size)
        assertEquals(1, count("select count(*) from audit_events where event_type = 'FILES_CLEANED'"))
        val after = admin.get("/api/v1/admin/files").json()
        assertEquals(0, after.number("consistency", "orphans", "files", "count"))
        assertEquals(0, after.number("consistency", "orphans", "images", "count"))
    }

    @Test
    fun `DB 는 아는데 저장소에 없는 첨부는 누락으로 센다`() {
        admin.post("/api/v1/issues", """{"title":"티켓"}""")
        admin.postFile("/api/v1/issues/1/attachments", "gone.pdf", "application/pdf", "내용".toByteArray())
        TestUploads.filesIn("files").forEach(Files::delete)

        val json = admin.get("/api/v1/admin/files").json()

        assertEquals(1, json.number("consistency", "missing", "files"))
        val sample = (json.at("consistency", "missingSamples") as List<*>).single() as Map<*, *>
        assertEquals("FILE", sample["kind"])
        assertEquals("gone.pdf", sample["name"])
        assertEquals("FB-1", sample["issueKey"])
    }

    @Test
    fun `본문에 넣은 사진은 쓰는 곳이 있는 동안 지워지지 않는다`() {
        val url = admin.postFile("/api/v1/images", "shot.png", "image/png", picture(800, 600)).json()["url"] as String
        admin.post("/api/v1/issues", """{"title":"사진 티켓","content":{"type":"doc","content":[{"type":"image","attrs":{"src":"$url"}}]}}""")
        clock.advance(Duration.ofDays(2))
        admin.login("admin", PASSWORD)

        val cleaned = admin.post("/api/v1/admin/files/cleanup").json()

        assertEquals(0, cleaned.number("deleted", "images", "count"))
        assertEquals(0, cleaned.number("deleted", "originals", "count"))
        assertEquals(200, admin.get(url).status)
        assertEquals(1, admin.get("/api/v1/admin/files").json().number("uses", "contentImages", "count"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.at(vararg path: String): Any? = path.fold(this as Any?) { node, key -> (node as Map<String, Any?>)[key] }

    private fun Map<String, Any?>.number(vararg path: String): Long = (at(*path) as Number).toLong()

    private fun picture(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(43, 99, 227)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private companion object {
        val FOLDERS = listOf("files", "images", "originals")
    }
}
