package com.flowbit

import com.flowbit.notification.InboxStore
import com.flowbit.notification.NotificationMessage
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESOLUTION_PASSWORD = "correct-horse-battery"

class CommentResolutionIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var inbox: InboxStore
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

    @Suppress("UNCHECKED_CAST")
    private fun alerts(client: ApiClient): List<Map<String, Any?>> = client.get("/api/v1/me/inbox?limit=100").json()["items"] as List<Map<String, Any?>>

    private fun seed(
        path: String,
        title: String = "연결 알림",
    ) = runBlocking {
        val recipients = listOf(admin, editor, viewer).map { UUID.fromString(it.get("/api/v1/me").json()["id"] as String) }
        inbox.add(recipients, NotificationMessage(title, emptyList(), "댓글", path, path), clock.instant())
    }

    private fun assertCompleted(
        path: String,
        title: String = "연결 알림",
        completed: Boolean = true,
    ) {
        for (client in listOf(admin, editor, viewer)) {
            val item = alerts(client).single { it["title"] == title && it["path"] == path }
            assertEquals(completed, item["completed"])
        }
    }

    @Test
    fun `티켓 대화 해결은 모든 수신자의 원 댓글과 답글 알림만 완료하고 읽음은 보존한다`() {
        val issue = editor.post("/api/v1/projects/$projectId/issues", """{"title":"대화 완료"}""").json()
        val base = "/api/v1/issues/${issue["number"]}"
        val root = admin.post("$base/comments", body()).json()["id"]
        val reply = editor.post("$base/comments", body(root)).json()["id"]
        val other = admin.post("$base/comments", body()).json()["id"]
        val rootPath = "/issue/${issue["key"]}#comment-$root"
        val replyPath = "/issue/${issue["key"]}#comment-$reply"
        val otherPath = "/issue/${issue["key"]}#comment-$other"
        seed(rootPath)
        seed(replyPath)
        seed(otherPath)
        val readId = alerts(editor).single { it["path"] == rootPath && it["title"] == "연결 알림" }["id"]
        editor.post("/api/v1/me/inbox/read", """{"ids":["$readId"]}""")
        assertEquals(403, viewer.post("/api/v1/comments/$root/resolution").status)
        assertCompleted(rootPath, completed = false)
        assertEquals(204, editor.post("/api/v1/comments/$root/resolution").status)
        assertCompleted(rootPath)
        assertCompleted(replyPath)
        assertCompleted(otherPath, completed = false)
        assertEquals(true, alerts(editor).single { it["id"] == readId }["read"])
        assertEquals(false, alerts(viewer).single { it["path"] == rootPath && it["title"] == "연결 알림" }["read"])
        seed(replyPath, "늦게 도착")
        assertCompleted(replyPath, "늦게 도착")
        assertEquals(204, editor.delete("/api/v1/comments/$root/resolution").status)
        assertCompleted(rootPath)
        seed(rootPath, "다시 열린 뒤")
        assertCompleted(rootPath, "다시 열린 뒤", completed = false)
    }

    @Test
    fun `프로젝트와 문서 댓글 해결은 모든 수신자의 연결 알림을 완료한다`() {
        val documentId = editor.post("/api/v1/projects/$projectId/documents", """{"title":"검토"}""").json()["id"]
        for ((target, path) in listOf("project/$projectId" to "/project/$projectId", "document/$documentId" to "/doc/$documentId")) {
            val base = "/api/v1/content/$target"
            val root = admin.post("$base/comments", body()).json()["id"]
            val reply = editor.post("$base/comments", body(root)).json()["id"]
            val rootPath = "$path#comment-$root"
            val replyPath = "$path#comment-$reply"
            seed(rootPath)
            seed(replyPath)
            assertEquals(404, outsider.post("/api/v1/content-comments/$root/resolution").status)
            assertCompleted(rootPath, completed = false)
            assertEquals(204, viewer.post("/api/v1/content-comments/$root/resolution").status)
            assertCompleted(rootPath)
            assertCompleted(replyPath)
            seed(replyPath, "늦게 도착")
            assertCompleted(replyPath, "늦게 도착")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `블록 대화 해결은 원 댓글과 답글의 모든 수신자 알림을 완료한다`() {
        val content = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"검토할 문단"}]}]}"""
        admin.patch("/api/v1/projects/$projectId", """{"content":$content}""")
        val project = admin.get("/api/v1/projects/$projectId").json()
        val blocks = (project["content"] as Map<String, Any?>)["content"] as List<Map<String, Any?>>
        val blockId = (blocks.first()["attrs"] as Map<String, Any?>)["blockId"]
        val thread = admin.post("/api/v1/content/project/$projectId/threads", """{"blockId":"$blockId","content":$content}""").json()
        val root = (thread["comments"] as List<Map<String, Any?>>).first()["id"]
        val answered = editor.post("/api/v1/content-threads/${thread["id"]}/comments", body()).json()
        val reply = (answered["comments"] as List<Map<String, Any?>>).single { it["id"] != root }["id"]
        assertTrue(root != reply)
        val rootPath = "/project/$projectId#comment-$root"
        val replyPath = "/project/$projectId#comment-$reply"
        seed(rootPath)
        seed(replyPath)
        assertEquals(200, viewer.post("/api/v1/content-threads/${thread["id"]}/resolution").status)
        assertCompleted(rootPath)
        assertCompleted(replyPath)
        seed(replyPath, "늦게 도착")
        assertCompleted(replyPath, "늦게 도착")
    }

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
