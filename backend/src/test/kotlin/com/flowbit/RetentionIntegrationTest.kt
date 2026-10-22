package com.flowbit

import com.flowbit.retention.PurgeResult
import com.flowbit.retention.RetentionService
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import com.flowbit.support.TestUploads
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class RetentionIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var retention: RetentionService

    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    @Test
    fun `관리자만 보관 기간을 보고 바꾸며 처음에는 셋 다 영구 삭제하지 않는다`() {
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }

        assertEquals(mapOf("issues" to null, "documents" to null, "comments" to null), admin.get("/api/v1/admin/retention").json())
        assertEquals(403, member.get("/api/v1/admin/retention").status)
        assertEquals(403, member.put("/api/v1/admin/retention", "{}").status)

        val saved = admin.put("/api/v1/admin/retention", """{"issues":{"amount":3,"unit":"MONTHS"},"comments":{"amount":2,"unit":"WEEKS"}}""")

        assertEquals(200, saved.status)
        val expected = mapOf("issues" to mapOf("amount" to 3, "unit" to "MONTHS"), "documents" to null, "comments" to mapOf("amount" to 2, "unit" to "WEEKS"))
        assertEquals(expected, saved.json())
        assertEquals(expected, admin.get("/api/v1/admin/retention").json())
        assertEquals(1, count("select count(*) from audit_events where event_type = 'RETENTION_SETTINGS_CHANGED'"))

        val invalid = admin.put("/api/v1/admin/retention", """{"documents":{"amount":0,"unit":"DAYS"}}""")
        assertEquals(422, invalid.status)
        @Suppress("UNCHECKED_CAST")
        assertEquals("documents.amount", (invalid.json()["errors"] as List<Map<String, Any?>>).single()["field"])
        assertEquals(422, admin.put("/api/v1/admin/retention", """{"issues":{"amount":1000,"unit":"DAYS"}}""").status)
        assertEquals(expected, admin.get("/api/v1/admin/retention").json())

        assertEquals(mapOf("issues" to null, "documents" to null, "comments" to null), admin.put("/api/v1/admin/retention", "{}").json())
    }

    @Test
    fun `기간이 지난 삭제된 것만 영구 삭제하고 하위 티켓과 첨부 파일을 정리한다`() {
        admin.put(
            "/api/v1/admin/retention",
            """{"issues":{"amount":30,"unit":"DAYS"},"documents":{"amount":1,"unit":"WEEKS"},"comments":{"amount":1,"unit":"MONTHS"}}""",
        )
        val parent = admin.post("/api/v1/issues", """{"title":"지울 티켓"}""").json()
        admin.post("/api/v1/issues", """{"title":"나중에 지울 티켓"}""")
        admin.post("/api/v1/issues", """{"title":"하위 티켓","parentId":"${parent["id"]}"}""")
        admin.post("/api/v1/issues", """{"title":"남는 티켓"}""")
        admin.postFile("/api/v1/issues/1/attachments", "log.txt", "text/plain", "로그".toByteArray())
        val file = TestUploads.pathOf("files", text("select file_key from issue_attachments"))
        assertTrue(Files.exists(file))
        val comment = admin.post("/api/v1/issues/4/comments", commentJson("지울 댓글")).json()["id"]
        admin.post("/api/v1/issues/4/comments", commentJson("남는 댓글"))
        val document = admin.post("/api/v1/issues/4/documents", """{"title":"지울 문서"}""").json()["id"]
        admin.post("/api/v1/issues/4/documents", """{"title":"남는 문서"}""")

        assertEquals(204, admin.delete("/api/v1/issues/1").status)
        assertEquals(204, admin.delete("/api/v1/comments/$comment").status)
        assertEquals(204, admin.delete("/api/v1/documents/$document").status)
        clock.advance(Duration.ofDays(8))
        // The session has gone idle by now.
        admin.login("admin", PASSWORD)
        assertEquals(204, admin.delete("/api/v1/issues/2").status)

        // Eight days on: only the document is past its week.
        assertEquals(PurgeResult(issues = 0, documents = 1, comments = 0), purge())
        assertEquals(1, count("select count(*) from documents"))

        // 33 days after the first deletions, 25 after the second.
        clock.advance(Duration.ofDays(25))
        assertEquals(PurgeResult(issues = 1, documents = 0, comments = 1), purge())
        admin.login("admin", PASSWORD)

        assertEquals(404, admin.get("/api/v1/issues/1").status)
        assertEquals(200, admin.get("/api/v1/issues/2").status)
        assertEquals(0, count("select count(*) from issue_attachments"))
        assertFalse(Files.exists(file))
        // The sub-issue stays, now at the top.
        assertEquals(1, count("select count(*) from issues where number = 3 and parent_id is null and deleted_at is null"))
        assertEquals(1, count("select count(*) from issue_comments"))
        assertEquals(2, count("select count(*) from audit_events where event_type = 'DATA_PURGED'"))
        assertEquals(PurgeResult(0, 0, 0), purge())
    }

    @Test
    fun `기간을 정하지 않은 항목은 아무리 오래돼도 지우지 않는다`() {
        admin.post("/api/v1/issues", """{"title":"지운 티켓"}""")
        admin.delete("/api/v1/issues/1")
        clock.advance(Duration.ofDays(3650))

        assertEquals(PurgeResult(0, 0, 0), purge())
        assertEquals(1, count("select count(*) from issues"))
        assertEquals(0, count("select count(*) from audit_events where event_type = 'DATA_PURGED'"))
    }

    @Test
    fun `영구 삭제된 티켓의 본문에 있던 사진은 사본과 원본이 함께 지워지고 다른 본문에서 쓰는 사진은 남는다`() {
        admin.put("/api/v1/admin/retention", """{"issues":{"amount":30,"unit":"DAYS"}}""")
        val shared = pictureUrl()
        val own = pictureUrl()
        admin.post("/api/v1/issues", """{"title":"지울 티켓","content":${imagesDoc(shared, own)}}""")
        admin.post("/api/v1/issues", """{"title":"남는 티켓","content":${imagesDoc(shared)}}""")
        assertTrue(storedPicture(own) && storedPicture(shared))

        // Only marked as deleted: the pictures are still in its text, which can be restored.
        admin.delete("/api/v1/issues/1")
        clock.advance(Duration.ofDays(20))
        purge()
        assertTrue(storedPicture(own))

        clock.advance(Duration.ofDays(11))
        admin.login("admin", PASSWORD)
        assertEquals(PurgeResult(issues = 1, documents = 0, comments = 0), purge())

        assertFalse(storedPicture(own))
        assertTrue(storedPicture(shared))
    }

    private fun purge(): PurgeResult = runBlocking { retention.purge() }

    /** Uploads a picture for the text of a document and says which address it has. */
    private fun pictureUrl(): String {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return admin.postFile("/api/v1/images", "shot.png", "image/png", png).json()["url"] as String
    }

    private fun imagesDoc(vararg urls: String) = """{"type":"doc","content":[${urls.joinToString(",") { """{"type":"image","attrs":{"src":"$it"}}""" }}]}"""

    /** Whether both the WebP copy and the original of the picture are still stored. */
    private fun storedPicture(url: String): Boolean {
        val key = url.substringAfterLast('/')
        return Files.exists(TestUploads.pathOf("images", key)) && Files.exists(TestUploads.pathOf("originals", key.removeSuffix(".webp")))
    }

    private fun commentJson(text: String) = """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}}"""
}
