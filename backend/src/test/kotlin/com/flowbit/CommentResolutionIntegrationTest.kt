package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RESOLUTION_PASSWORD = "correct-horse-battery"

class CommentResolutionIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var viewer: ApiClient
    private lateinit var outsider: ApiClient
    private lateinit var editorId: String
    private lateinit var projectId: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", RESOLUTION_PASSWORD) }

        fun member(name: String): String =
            admin.post("/api/v1/admin/members", """{"loginId":"$name","password":"$RESOLUTION_PASSWORD"}""").json()["id"] as String
        editorId = member("editor")
        val viewerId = member("viewer")
        member("outsider")
        projectId = admin.post("/api/v1/projects", """{"name":"댓글 확인"}""").json()["id"] as String
        admin.put("/api/v1/projects/$projectId/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/$projectId/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", RESOLUTION_PASSWORD) }
        viewer = client().also { it.login("viewer", RESOLUTION_PASSWORD) }
        outsider = client().also { it.login("outsider", RESOLUTION_PASSWORD) }
    }

    private fun body(parentId: Any? = null): String =
        """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"문구 수정 피드백"}]}]}${parentId?.let {
            ""","parentId":"$it""""
        } ?: ""}}"""

    @Suppress("UNCHECKED_CAST")
    private fun comments(path: String): List<Map<String, Any?>> = admin.get(path).json()["comments"] as List<Map<String, Any?>>

    @Test
    fun `티켓 원 댓글만 해결하고 처리자와 시각을 보존하며 다시 열면 해제한다`() {
        val issue = editor.post("/api/v1/projects/$projectId/issues", """{"title":"피드백"}""").json()["number"]
        val base = "/api/v1/issues/$issue"
        val root = admin.post("$base/comments", body()).json()["id"]
        val reply = editor.post("$base/comments", body(root)).json()["id"]
        val resolution = "/api/v1/comments/$root/resolution"
        assertEquals(false, comments("$base/activity").first()["resolved"])
        assertEquals(403, viewer.post(resolution).status)
        assertEquals(404, outsider.post(resolution).status)
        assertEquals(422, editor.post("/api/v1/comments/$reply/resolution").status)
        assertEquals(204, editor.post(resolution).status)
        val resolved = comments("$base/activity").first()
        assertEquals(true, resolved["resolved"])
        assertNotNull(resolved["resolvedAt"])
        assertEquals(editorId, (resolved["resolvedBy"] as Map<*, *>)["id"])
        assertEquals(resolved["createdAt"], resolved["updatedAt"])
        clock.advance(Duration.ofMinutes(1))
        assertEquals(204, admin.post(resolution).status)
        assertEquals(resolved, comments("$base/activity").first())
        assertEquals(201, editor.post("$base/comments", body(root)).status)
        assertEquals(true, comments("$base/activity").first()["resolved"])
        assertEquals(204, editor.delete(resolution).status)
        val reopened = comments("$base/activity").first()
        assertEquals(false, reopened["resolved"])
        assertNull(reopened["resolvedAt"])
        assertNull(reopened["resolvedBy"])
        editor.delete(base)
        assertEquals(403, editor.post(resolution).status)
        assertEquals(403, editor.delete(resolution).status)
    }

    @Test
    fun `프로젝트와 문서의 보기 역할도 원 댓글을 해결하며 답글과 삭제된 댓글은 거부한다`() {
        val documentId = editor.post("/api/v1/projects/$projectId/documents", """{"title":"설계"}""").json()["id"]
        for (target in listOf("project/$projectId", "document/$documentId")) {
            val base = "/api/v1/content/$target"
            val root = editor.post("$base/comments", body()).json()["id"]
            val reply = viewer.post("$base/comments", body(root)).json()["id"]
            val resolution = "/api/v1/content-comments/$root/resolution"
            assertEquals(404, outsider.post(resolution).status)
            assertEquals(422, viewer.post("/api/v1/content-comments/$reply/resolution").status)
            assertEquals(204, viewer.post(resolution).status)
            val resolved = comments("$base/feed").first()
            assertEquals(true, resolved["resolved"])
            assertNotNull(resolved["resolvedAt"])
            assertNotNull(resolved["resolvedBy"])
            assertEquals(204, editor.post(resolution).status)
            assertEquals(resolved, comments("$base/feed").first())
            assertEquals(204, viewer.delete(resolution).status)
            assertEquals(false, comments("$base/feed").first()["resolved"])
            assertEquals(204, editor.delete("/api/v1/content-comments/$root").status)
            assertEquals(404, viewer.post(resolution).status)
        }
        val root = viewer.post("/api/v1/content/document/$documentId/comments", body()).json()["id"]
        editor.delete("/api/v1/documents/$documentId")
        assertEquals(403, viewer.post("/api/v1/content-comments/$root/resolution").status)
        assertEquals(403, viewer.delete("/api/v1/content-comments/$root/resolution").status)
    }
}
