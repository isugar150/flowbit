package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** Comments on blocks and the activity of projects, documents and milestones (D-165). */
class ContentCommentsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var viewer: ApiClient
    private lateinit var viewerId: String
    private lateinit var outsider: ApiClient

    @BeforeEach
    fun createProjectWithPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val editorId = createMember("editor")
        viewerId = createMember("viewer")
        createMember("outsider")
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/${p("PAY")}/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", PASSWORD) }
        viewer = client().also { it.login("viewer", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    private fun paragraph(
        text: String,
        blockId: String? = null,
    ) = """{"type":"paragraph"${blockId?.let { ""","attrs":{"blockId":"$it"}""" } ?: ""},"content":[{"type":"text","text":"$text"}]}"""

    private fun doc(vararg blocks: String) = """{"type":"doc","content":[${blocks.joinToString(",")}]}"""

    private fun comment(text: String) = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""

    private fun saveOverview(vararg blocks: String) = editor.patch("/api/v1/projects/${p("PAY")}", """{"content":${doc(*blocks)}}""")

    @Suppress("UNCHECKED_CAST")
    private fun blockIds(page: Map<String, Any?>): List<String> =
        ((page["content"] as Map<String, Any?>)["content"] as List<Map<String, Any?>>).map { (it["attrs"] as Map<String, Any?>)["blockId"] as String }

    private fun overviewBlocks(): List<String> = blockIds(admin.get("/api/v1/projects/${p("PAY")}").json())

    private fun feed(
        client: ApiClient,
        type: String,
        id: String,
    ) = client.get("/api/v1/content/$type/$id/feed")

    private fun types(feed: Map<String, Any?>): List<Any?> = items(feed).map { it["type"] }

    @Suppress("UNCHECKED_CAST")
    private fun items(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun threads(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["threads"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun comments(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["comments"] as List<Map<String, Any?>>

    @Test
    fun `본문을 저장하면 최상위 블록마다 번호가 붙고 겹친 번호는 새로 받으며 이상한 번호는 거부된다`() {
        val shared = "11111111-1111-1111-1111-111111111111"

        assertEquals(200, saveOverview(paragraph("하나", shared), paragraph("둘", shared), paragraph("셋")).status)

        val ids = overviewBlocks()
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
        assertEquals(shared, ids.first())
        assertEquals(422, saveOverview(paragraph("나쁜 번호", "x y!")).status)
    }

    @Test
    fun `보기 역할도 블록에 스레드를 열고 답글을 달고 해결하고 다시 열 수 있다`() {
        saveOverview(paragraph("첫째 블록"), paragraph("둘째 블록"))
        val block = overviewBlocks().first()

        val opened = viewer.post("/api/v1/content/project/${p("PAY")}/threads", """{"blockId":"$block","content":${comment("확인 부탁드립니다")}}""")

        assertEquals(201, opened.status)
        val thread = opened.json()
        val id = thread["id"]
        assertEquals("첫째 블록", thread["quote"])
        assertEquals(false, thread["orphaned"])
        assertEquals(false, thread["resolved"])
        assertEquals(201, editor.post("/api/v1/content-threads/$id/comments", """{"content":${comment("확인했습니다")}}""").status)
        val resolved = viewer.post("/api/v1/content-threads/$id/resolution")
        assertEquals(true, resolved.json()["resolved"])
        assertEquals(false, editor.delete("/api/v1/content-threads/$id/resolution").json()["resolved"])

        val shown = threads(feed(editor, "project", p("PAY")).json()).single()
        @Suppress("UNCHECKED_CAST")
        assertEquals(2, (shown["comments"] as List<*>).size)
        // Someone who cannot open the project does not see that it has threads.
        assertEquals(404, feed(outsider, "project", p("PAY")).status)
        assertEquals(404, outsider.post("/api/v1/content/project/${p("PAY")}/threads", """{"blockId":"$block","content":${comment("밖의 사람")}}""").status)
    }

    @Test
    fun `블록이 지워지면 스레드는 삭제된 블록으로 남고 블록이 돌아오면 다시 붙는다`() {
        saveOverview(paragraph("남을 글"), paragraph("지울 글"))
        val (keep, removed) = overviewBlocks()
        viewer.post("/api/v1/content/project/${p("PAY")}/threads", """{"blockId":"$removed","content":${comment("이 문단은 왜?")}}""")

        saveOverview(paragraph("남을 글", keep))
        val gone = threads(feed(viewer, "project", p("PAY")).json()).single()
        assertEquals(true, gone["orphaned"])
        assertEquals("지울 글", gone["quote"])

        saveOverview(paragraph("남을 글", keep), paragraph("돌아온 글", removed))
        assertEquals(false, threads(feed(viewer, "project", p("PAY")).json()).single()["orphaned"])
    }

    @Test
    fun `저장된 본문에 없는 블록에는 댓글을 달 수 없고 블록 댓글은 글과 태그만 받는다`() {
        saveOverview(paragraph("글"))
        val block = overviewBlocks().first()
        val path = "/api/v1/content/project/${p("PAY")}/threads"

        assertEquals(409, viewer.post(path, """{"blockId":"00000000-0000-0000-0000-000000000000","content":${comment("없는 블록")}}""").status)
        assertEquals(422, viewer.post(path, """{"blockId":"$block"}""").status)
        assertEquals(422, viewer.post(path, """{"blockId":"$block","content":${doc("""{"type":"paragraph"}""")}}""").status)
        val heading = """{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"제목"}]}"""
        assertEquals(422, viewer.post(path, """{"blockId":"$block","content":${doc(heading)}}""").status)
    }

    @Test
    fun `스레드를 연 첫 댓글을 지우면 스레드째 지워지고 답글만 지우면 스레드는 남는다 글쓴이만 고친다`() {
        saveOverview(paragraph("글"))
        val block = overviewBlocks().first()
        val opened = viewer.post("/api/v1/content/project/${p("PAY")}/threads", """{"blockId":"$block","content":${comment("처음")}}""").json()
        val threadId = opened["id"]

        @Suppress("UNCHECKED_CAST")
        val first = (opened["comments"] as List<Map<String, Any?>>).single()["id"]
        val reply = editor.post("/api/v1/content-threads/$threadId/comments", """{"content":${comment("답")}}""").json()

        @Suppress("UNCHECKED_CAST")
        val answer = (reply["comments"] as List<Map<String, Any?>>).last()["id"]
        assertEquals(403, editor.patch("/api/v1/content-thread-comments/$first", """{"content":${comment("남의 글")}}""").status)
        assertEquals(200, viewer.patch("/api/v1/content-thread-comments/$first", """{"content":${comment("고친 글")}}""").status)
        assertEquals(403, viewer.delete("/api/v1/content-thread-comments/$answer").status)
        assertEquals(204, admin.delete("/api/v1/content-thread-comments/$answer").status)
        assertEquals(1, threads(feed(viewer, "project", p("PAY")).json()).size)

        assertEquals(204, viewer.delete("/api/v1/content-thread-comments/$first").status)
        assertEquals(0, threads(feed(viewer, "project", p("PAY")).json()).size)
    }

    @Test
    fun `티켓 본문과 프로젝트 문서와 목표에도 블록 댓글을 단다`() {
        val issue =
            editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패","content":${doc(paragraph("본문"))}}""").json()
        val issueId = issue["id"] as String
        val issueBlock = blockIds(editor.get("/api/v1/issues/${issue["number"]}").json()).first()
        assertEquals(201, viewer.post("/api/v1/content/issue/$issueId/threads", """{"blockId":"$issueBlock","content":${comment("티켓 본문 댓글")}}""").status)
        assertEquals(1, threads(feed(viewer, "issue", issueId).json()).size)

        val document = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"설계"}""").json()
        val documentId = document["id"] as String
        editor.patch("/api/v1/documents/$documentId", """{"content":${doc(paragraph("설계 본문"))}}""")
        val documentBlock = blockIds(editor.get("/api/v1/documents/$documentId").json()).first()
        val documentThread = """{"blockId":"$documentBlock","content":${comment("문서 댓글")}}"""
        assertEquals(201, viewer.post("/api/v1/content/document/$documentId/threads", documentThread).status)

        val milestone = editor.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"1차"}""").json()
        val milestoneId = milestone["id"] as String
        editor.patch("/api/v1/projects/${p("PAY")}/milestones/$milestoneId", """{"content":${doc(paragraph("목표 본문"))}}""")
        val milestoneBlock = blockIds(editor.get("/api/v1/projects/${p("PAY")}/milestones").jsonList().single { it["id"] == milestoneId }).first()
        assertEquals(
            201,
            viewer.post("/api/v1/content/milestone/$milestoneId/threads", """{"blockId":"$milestoneBlock","content":${comment("목표 댓글")}}""").status,
        )

        // A deleted issue can be read but not commented on.
        editor.delete("/api/v1/issues/${issue["number"]}")
        assertEquals(403, viewer.post("/api/v1/content/issue/$issueId/threads", """{"blockId":"$issueBlock","content":${comment("늦은 댓글")}}""").status)
        assertEquals(1, threads(feed(viewer, "issue", issueId).json()).size)
    }

    @Test
    fun `프로젝트의 속성 변경과 본문 수정과 구성원 변경과 목표와 문서가 활동에 남고 본문 수정은 합쳐진다`() {
        editor.patch("/api/v1/projects/${p("PAY")}", """{"status":"IN_PROGRESS","priority":"HIGH","labels":["결제"]}""")
        saveOverview(paragraph("첫 글"))
        saveOverview(paragraph("첫 글을 이어서 고침"))
        admin.put("/api/v1/projects/${p("PAY")}/members/$viewerId", """{"role":"EDITOR"}""")
        val milestone = editor.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"1차"}""").json()
        editor.delete("/api/v1/projects/${p("PAY")}/milestones/${milestone["id"]}")
        editor.post("/api/v1/projects/${p("PAY")}/updates", """{"health":"AT_RISK","body":"조금 늦음"}""")
        val document = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"설계"}""").json()
        editor.delete("/api/v1/documents/${document["id"]}")
        editor.post("/api/v1/documents/${document["id"]}/restore")

        val found = types(feed(viewer, "project", p("PAY")).json())

        assertEquals(1, found.count { it == "CONTENT_EDITED" })
        listOf(
            "STATUS_CHANGED",
            "PRIORITY_CHANGED",
            "LABELS_CHANGED",
            "MEMBER_ROLE_CHANGED",
            "MILESTONE_ADDED",
            "MILESTONE_REMOVED",
            "UPDATE_POSTED",
            "DOCUMENT_ADDED",
            "DOCUMENT_REMOVED",
            "DOCUMENT_RESTORED",
        ).forEach { assertTrue(it in found, "$it is in $found") }
    }

    @Test
    fun `문서와 목표의 활동에는 이름과 본문 수정이 남고 문서를 품은 쪽에는 하위 문서가 남는다`() {
        val document = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"설계"}""").json()
        val documentId = document["id"] as String
        editor.patch("/api/v1/documents/$documentId", """{"title":"설계 문서"}""")
        editor.patch("/api/v1/documents/$documentId", """{"content":${doc(paragraph("본문"))}}""")
        val child = editor.post("/api/v1/documents/$documentId/documents", """{"title":"하위"}""").json()
        editor.delete("/api/v1/documents/${child["id"]}")

        val own = types(feed(viewer, "document", documentId).json())
        assertTrue("NAME_CHANGED" in own && "CONTENT_EDITED" in own)
        assertTrue("DOCUMENT_ADDED" in own && "DOCUMENT_REMOVED" in own)

        val milestone = editor.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"1차"}""").json()
        val milestoneId = milestone["id"]
        editor.patch("/api/v1/projects/${p("PAY")}/milestones/$milestoneId", """{"name":"1차 출시","status":"IN_PROGRESS","targetDate":"2026-12-31"}""")
        val milestoneTypes = types(feed(viewer, "milestone", milestoneId as String).json())
        assertTrue(milestoneTypes.containsAll(listOf("NAME_CHANGED", "STATUS_CHANGED", "TARGET_DATE_CHANGED")))
    }

    @Test
    fun `일반 댓글은 보기 역할도 쓰고 이모지와 파일을 붙일 수 있고 삭제된 문서에는 쓸 수 없다`() {
        val document = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"설계"}""").json()
        val documentId = document["id"] as String
        val path = "/api/v1/content/document/$documentId"
        val bytes = "보고서".toByteArray()

        val uploaded = viewer.postFile("$path/comment-files", "../보고서.pdf", "application/pdf", bytes)
        assertEquals(201, uploaded.status)
        val fileId = uploaded.json()["id"] as String
        val url = uploaded.json()["url"] as String
        assertEquals(200, viewer.get(url).status)
        // Until the comment is sent the file is its uploader's alone.
        assertEquals(404, editor.get(url).status)

        val sent = viewer.post("$path/comments", """{"content":${comment("파일을 올립니다")},"attachmentIds":["$fileId"]}""")
        assertEquals(201, sent.status)
        assertEquals(true, sent.json()["canEdit"])
        assertTrue(editor.get(url).bytes.contentEquals(bytes))
        assertEquals(403, viewer.delete(url).status)
        assertEquals(404, outsider.get(url).status)

        val commentId = sent.json()["id"]
        val reaction = editor.post("/api/v1/content-comments/$commentId/reactions", """{"emoji":"👍"}""").jsonList().single()
        assertEquals(1, reaction["count"])
        val entry = items(feed(editor, "document", documentId).json())
        // A new document has no activity of its own yet; its first edit makes one the emoji can go on.
        assertEquals(emptyList<Any?>(), entry)
        editor.patch("/api/v1/documents/$documentId", """{"title":"설계 문서"}""")
        val activityId = items(feed(editor, "document", documentId).json()).single()["id"]
        assertEquals(200, viewer.post("/api/v1/content-activities/$activityId/reactions", """{"emoji":"🎉"}""").status)
        assertEquals(422, viewer.post("/api/v1/content-activities/$activityId/reactions", """{"emoji":"<script>"}""").status)

        val shown = comments(feed(viewer, "document", documentId).json()).single()
        assertEquals(true, shown["canDelete"])
        assertEquals(1, (shown["attachments"] as List<*>).size)

        // A deleted document can be read, but nothing is added to it.
        editor.delete("/api/v1/documents/$documentId")
        assertEquals(403, viewer.post("$path/comments", """{"content":${comment("늦은 댓글")}}""").status)
        assertEquals(403, viewer.post("/api/v1/content-comments/$commentId/reactions", """{"emoji":"❤️"}""").status)
        assertEquals(200, feed(viewer, "document", documentId).status)
    }
}
