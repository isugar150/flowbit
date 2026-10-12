package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class IssuesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var editorId: String
    private lateinit var viewer: ApiClient
    private lateinit var outsider: ApiClient
    private lateinit var outsiderId: String

    @BeforeEach
    fun createProjectWithPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        editorId = createMember("editor")
        val viewerId = createMember("viewer")
        outsiderId = createMember("outsider")
        admin.post("/api/v1/projects", """{"name":"결제 개편","key":"PAY"}""")
        admin.put("/api/v1/projects/PAY/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/PAY/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", PASSWORD) }
        viewer = client().also { it.login("viewer", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
    }

    @Test
    fun `제목만으로 이슈를 만들 수 있고 번호가 차례로 붙는다`() {
        val first = editor.post("/api/v1/projects/PAY/issues", """{"title":"  결제 버튼이 두 번 눌린다 "}""")
        val second = editor.post("/api/v1/projects/PAY/issues", """{"title":"영수증 메일"}""")

        assertEquals(201, first.status)
        assertEquals("FB-1", first.json()["key"])
        assertEquals("결제 버튼이 두 번 눌린다", first.json()["title"])
        assertEquals("BACKLOG", first.json()["status"])
        assertEquals("NONE", first.json()["priority"])
        assertNull(first.json()["assignee"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("editor", (first.json()["createdBy"] as Map<String, Any?>)["loginId"])
        assertEquals("FB-2", second.json()["key"])
    }

    @Test
    fun `상태 우선순위 담당자 기한 설명을 함께 정해 만들 수 있다`() {
        val created =
            editor.post(
                "/api/v1/projects/PAY/issues",
                """
                {"title":"환불 흐름","description":"## 배경\n환불이 느리다","status":"READY",
                 "priority":"HIGH","assigneeId":"$editorId","dueDate":"2026-11-30"}
                """.trimIndent(),
            )

        assertEquals(201, created.status)
        val issue = created.json()
        assertEquals("READY", issue["status"])
        assertEquals("HIGH", issue["priority"])
        assertEquals("2026-11-30", issue["dueDate"])
        assertEquals("## 배경\n환불이 느리다", issue["description"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("editor", (issue["assignee"] as Map<String, Any?>)["loginId"])
    }

    @Test
    fun `제목이 없거나 너무 길면 만들 수 없다`() {
        assertEquals(422, editor.post("/api/v1/projects/PAY/issues", """{"title":"   "}""").status)
        assertEquals(422, editor.post("/api/v1/projects/PAY/issues", """{"title":"${"가".repeat(201)}"}""").status)
        assertTrue(editor.get("/api/v1/projects/PAY/issues").jsonList().isEmpty())
    }

    @Test
    fun `프로젝트 구성원이 아닌 사람은 담당자가 될 수 없다`() {
        val response = editor.post("/api/v1/projects/PAY/issues", """{"title":"담당자 테스트","assigneeId":"$outsiderId"}""")

        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        assertEquals("NOT_A_MEMBER", (response.json()["errors"] as List<Map<String, String>>).single()["code"])
    }

    @Test
    fun `보기 역할은 이슈를 읽을 수 있지만 만들거나 고칠 수 없다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"읽기 전용 확인"}""")

        assertEquals(1, viewer.get("/api/v1/projects/PAY/issues").jsonList().size)
        assertEquals("읽기 전용 확인", viewer.get("/api/v1/issues/1").json()["title"])
        assertEquals(403, viewer.post("/api/v1/projects/PAY/issues", """{"title":"안 됨"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"status":"DONE"}""").status)
    }

    @Test
    fun `프로젝트 밖의 사람에게는 이슈도 프로젝트도 없는 것으로 보인다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"숨겨진 이슈"}""")

        val list = outsider.get("/api/v1/projects/PAY/issues")
        assertEquals(404, list.status)
        assertEquals("PROJECT_NOT_FOUND", list.json()["code"])
        assertEquals(404, outsider.get("/api/v1/issues/1").status)
        assertEquals(404, outsider.post("/api/v1/projects/PAY/issues", """{"title":"끼어들기"}""").status)
    }

    @Test
    fun `보낸 항목만 바뀌고 null 을 보내면 담당자와 기한이 지워진다`() {
        editor.post(
            "/api/v1/projects/PAY/issues",
            """{"title":"원래 제목","priority":"LOW","assigneeId":"$editorId","dueDate":"2026-11-30"}""",
        )

        val moved = editor.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""").json()
        assertEquals("IN_PROGRESS", moved["status"])
        assertEquals("LOW", moved["priority"])
        assertEquals("원래 제목", moved["title"])
        assertEquals("2026-11-30", moved["dueDate"])
        assertEquals(2, moved["version"])

        val cleared = editor.patch("/api/v1/issues/1", """{"assigneeId":null,"dueDate":null,"title":"새 제목"}""").json()
        assertNull(cleared["assignee"])
        assertNull(cleared["dueDate"])
        assertEquals("새 제목", cleared["title"])
        assertEquals("IN_PROGRESS", cleared["status"])
    }

    @Test
    fun `알 수 없는 상태나 잘못된 날짜로는 고칠 수 없다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"검증"}""")

        assertEquals(422, editor.patch("/api/v1/issues/1", """{"status":"FLYING"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"dueDate":"내일"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"title":""}""").status)
        assertEquals("BACKLOG", editor.get("/api/v1/issues/1").json()["status"])
    }

    @Test
    fun `없는 이슈는 404 다`() {
        val response = editor.get("/api/v1/issues/99")

        assertEquals(404, response.status)
        assertEquals("ISSUE_NOT_FOUND", response.json()["code"])
    }

    @Test
    fun `동시에 만들어도 이슈 번호가 겹치지 않는다`() {
        val clients = (1..6).map { client().also { c -> c.login("editor", PASSWORD) } }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(clients.size)

        val keys =
            clients
                .mapIndexed { index, c ->
                    pool.submit<String> {
                        start.await()
                        c.post("/api/v1/projects/PAY/issues", """{"title":"동시 $index"}""").json()["key"] as String
                    }
                }.also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals((1..6).map { "FB-$it" }.toSet(), keys.toSet())
    }

    @Test
    fun `목록은 최근에 만든 이슈가 먼저 나온다`() {
        listOf("첫째", "둘째", "셋째").forEach { editor.post("/api/v1/projects/PAY/issues", """{"title":"$it"}""") }

        assertEquals(listOf("셋째", "둘째", "첫째"), editor.get("/api/v1/projects/PAY/issues").jsonList().map { it["title"] })
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    @Test
    fun `내 이슈는 내가 담당하거나 만든 이슈를 들어갈 수 있는 프로젝트에서만 모아 준다`() {
        admin.post("/api/v1/projects", """{"name":"비공개","key":"SEC"}""")
        editor.post("/api/v1/projects/PAY/issues", """{"title":"내가 만들고 내가 맡음","assigneeId":"$editorId"}""")
        editor.post("/api/v1/projects/PAY/issues", """{"title":"내가 만들기만 함"}""")
        admin.post("/api/v1/projects/PAY/issues", """{"title":"관리자가 나에게 맡김","assigneeId":"$editorId"}""")
        admin.post("/api/v1/projects/SEC/issues", """{"title":"다른 프로젝트"}""")

        val assigned =
            editor
                .get("/api/v1/me/issues")
                .jsonList()
                .map { it["key"] }
                .toSet()
        val created =
            editor
                .get("/api/v1/me/issues?scope=created")
                .jsonList()
                .map { it["key"] }
                .toSet()

        assertEquals(setOf("FB-1", "FB-3"), assigned)
        assertEquals(setOf("FB-1", "FB-2"), created)
        assertEquals(
            setOf("FB-3", "FB-4"),
            admin
                .get("/api/v1/me/issues?scope=created")
                .jsonList()
                .map { it["key"] }
                .toSet(),
        )
        assertEquals(emptyList(), viewer.get("/api/v1/me/issues").jsonList())
        assertEquals(422, editor.get("/api/v1/me/issues?scope=everything").status)
        assertEquals(401, client().get("/api/v1/me/issues").status)
    }

    @Test
    fun `프로젝트에서 빠지면 그 프로젝트의 이슈는 내 이슈에 나오지 않는다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"맡은 일","assigneeId":"$editorId"}""")

        admin.delete("/api/v1/projects/PAY/members/$editorId")

        assertEquals(emptyList(), editor.get("/api/v1/me/issues").jsonList())
    }

    @Test
    fun `처음부터 있는 라벨 세 개에 누구나 라벨을 더할 수 있고 같은 이름은 하나만 생긴다`() {
        assertEquals(listOf("버그", "기능", "중요"), viewer.get("/api/v1/labels").jsonList().map { it["name"] })

        val created = editor.post("/api/v1/labels", """{"name":"  디자인 "}""")
        val again = viewer.post("/api/v1/labels", """{"name":"디자인"}""")

        assertEquals(201, created.status)
        assertEquals("디자인", created.json()["name"])
        assertEquals(created.json()["id"], again.json()["id"])
        assertEquals(4, admin.get("/api/v1/labels").jsonList().size)
        assertEquals(422, editor.post("/api/v1/labels", """{"name":" "}""").status)
    }

    @Test
    fun `이슈에 라벨을 여러 개 붙이고 바꾸고 모두 뗄 수 있다`() {
        val labels = admin.get("/api/v1/labels").jsonList().associate { it["name"] to it["id"] }
        val created =
            editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패","labelIds":["${labels["버그"]}","${labels["중요"]}"]}""")

        assertEquals(201, created.status)
        assertEquals(listOf("버그", "중요"), labelNames(created.json()))
        assertEquals(listOf("버그", "중요"), labelNames(viewer.get("/api/v1/projects/PAY/issues").jsonList().single()))

        val changed = editor.patch("/api/v1/issues/1", """{"labelIds":["${labels["기능"]}"]}""")
        assertEquals(listOf("기능"), labelNames(changed.json()))
        assertEquals(listOf("기능"), labelNames(editor.patch("/api/v1/issues/1", """{"title":"제목만"}""").json()))
        assertEquals(emptyList(), labelNames(editor.patch("/api/v1/issues/1", """{"labelIds":[]}""").json()))
    }

    @Test
    fun `없는 라벨은 붙일 수 없고 보기 역할은 라벨을 바꿀 수 없다`() {
        val bug = admin.get("/api/v1/labels").jsonList().first()["id"]
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")

        val unknown = editor.patch("/api/v1/issues/1", """{"labelIds":["00000000-0000-7000-8000-000000000000"]}""")

        assertEquals(422, unknown.status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"labelIds":"버그"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"labelIds":["$bug"]}""").status)
    }

    @Test
    fun `이슈를 만들고 고친 일이 차례로 활동에 남는다`() {
        val bug = admin.get("/api/v1/labels").jsonList().first()["id"]
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")

        editor.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS","assigneeId":"$editorId"}""")
        admin.patch("/api/v1/issues/1", """{"labelIds":["$bug"],"dueDate":"2026-11-01"}""")
        admin.patch("/api/v1/issues/1", """{"priority":"NONE"}""")

        val items = activityOf(viewer.get("/api/v1/issues/1/activity").json())
        assertEquals(
            listOf("CREATED", "STATUS_CHANGED", "ASSIGNEE_CHANGED", "DUE_DATE_CHANGED", "LABELS_CHANGED"),
            items.map { it["type"] },
        )
        assertEquals(mapOf("from" to "BACKLOG", "to" to "IN_PROGRESS"), items[1]["detail"])
        assertEquals(mapOf("from" to null, "to" to "editor"), items[2]["detail"])
        assertEquals(mapOf("added" to listOf("버그"), "removed" to emptyList<String>()), items[4]["detail"])
        assertEquals("admin", (items[4]["actor"] as Map<*, *>)["loginId"])
    }

    @Test
    fun `제목을 이어서 고치면 활동은 하나로 합쳐지고 처음과 마지막 제목이 남는다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제"}""")

        editor.patch("/api/v1/issues/1", """{"title":"결제 실"}""")
        editor.patch("/api/v1/issues/1", """{"title":"결제 실패"}""")

        val items = activityOf(editor.get("/api/v1/issues/1/activity").json())
        assertEquals(listOf("CREATED", "TITLE_CHANGED"), items.map { it["type"] })
        assertEquals(mapOf("from" to "결제", "to" to "결제 실패"), items[1]["detail"])
    }

    @Test
    fun `만든 사람과 담당자는 자동으로 구독하고 누구나 구독하거나 그만둘 수 있다`() {
        admin.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패","assigneeId":"$editorId"}""")

        val feed = viewer.get("/api/v1/issues/1/activity").json()
        assertEquals(setOf("admin", "editor"), subscribersOf(feed))
        assertEquals(false, feed["subscribed"])

        val subscribed = viewer.put("/api/v1/issues/1/subscription", "{}").json()
        assertEquals(true, subscribed["subscribed"])
        assertEquals(setOf("admin", "editor", "viewer"), subscribersOf(subscribed))
        assertEquals(setOf("admin", "viewer"), subscribersOf(editor.delete("/api/v1/issues/1/subscription").json()))
        assertEquals(404, outsider.put("/api/v1/issues/1/subscription", "{}").status)
        assertEquals(404, outsider.get("/api/v1/issues/1/activity").status)
    }

    @Test
    fun `프로젝트 없이 만든 이슈는 누구나 보고 고치고 프로젝트에 연결하면 그 프로젝트 사람만 본다`() {
        val created = outsider.post("/api/v1/issues", """{"title":"혼자 있는 이슈","assigneeId":"$outsiderId"}""")

        assertEquals(201, created.status)
        assertEquals("FB-1", created.json()["key"])
        assertNull(created.json()["project"])
        assertEquals(200, viewer.patch("/api/v1/issues/1", """{"status":"READY"}""").status)
        assertEquals(listOf("FB-1"), outsider.get("/api/v1/issues").jsonList().map { it["key"] })
        // A milestone needs a project.
        assertEquals(422, outsider.patch("/api/v1/issues/1", """{"milestoneId":"00000000-0000-7000-8000-000000000000"}""").status)
        // Linking needs the right to edit in that project.
        assertEquals(404, outsider.patch("/api/v1/issues/1", """{"projectKey":"PAY"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"projectKey":"PAY"}""").status)

        val linked = editor.patch("/api/v1/issues/1", """{"projectKey":"PAY"}""")

        assertEquals("PAY", (linked.json()["project"] as Map<*, *>)["key"])
        assertEquals("FB-1", linked.json()["key"])
        assertEquals(404, outsider.get("/api/v1/issues/1").status)
        assertEquals(emptyList(), outsider.get("/api/v1/issues").jsonList())
        assertEquals(listOf("FB-1"), viewer.get("/api/v1/projects/PAY/issues").jsonList().map { it["key"] })
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"status":"DONE"}""").status)

        assertNull(editor.patch("/api/v1/issues/1", """{"projectKey":null}""").json()["project"])
        assertEquals(200, outsider.get("/api/v1/issues/1").status)
    }

    @Test
    fun `프로젝트 연결을 바꾸면 목표는 떨어지고 번호는 그대로다`() {
        admin.post("/api/v1/projects", """{"name":"보안","key":"SEC"}""")
        val milestone = admin.post("/api/v1/projects/PAY/milestones", """{"name":"베타"}""").json()["id"]
        admin.post("/api/v1/projects/PAY/issues", """{"title":"옮길 이슈","milestoneId":"$milestone"}""")

        val moved = admin.patch("/api/v1/issues/1", """{"projectKey":"SEC"}""").json()

        assertEquals("FB-1", moved["key"])
        assertEquals("SEC", (moved["project"] as Map<*, *>)["key"])
        assertNull(moved["milestone"])
        assertEquals(422, admin.patch("/api/v1/issues/1", """{"milestoneId":"$milestone"}""").status)
    }

    @Test
    fun `하위 이슈를 만들면 상위 이슈에 보이고 서로 물리는 관계는 만들 수 없다`() {
        val parent = editor.post("/api/v1/projects/PAY/issues", """{"title":"상위"}""").json()
        val child = editor.post("/api/v1/issues", """{"title":"하위","projectKey":"PAY","parentId":"${parent["id"]}"}""").json()

        assertEquals("FB-1", (child["parent"] as Map<*, *>)["key"])
        @Suppress("UNCHECKED_CAST")
        val subIssues = editor.get("/api/v1/issues/1").json()["subIssues"] as List<Map<String, Any?>>
        assertEquals(listOf("FB-2"), subIssues.map { it["key"] })
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"parentId":"${child["id"]}"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"parentId":"${parent["id"]}"}""").status)
        assertNull(editor.patch("/api/v1/issues/2", """{"parentId":null}""").json()["parent"])
    }

    @Test
    fun `이슈에 이모지를 달고 다시 누르면 지워진다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")

        editor.post("/api/v1/issues/1/reactions", """{"emoji":"👍"}""")
        val twice = viewer.post("/api/v1/issues/1/reactions", """{"emoji":"👍"}""").jsonList().single()

        assertEquals(2, twice["count"])
        assertEquals(true, twice["reacted"])
        assertEquals(1, editor.post("/api/v1/issues/1/reactions", """{"emoji":"👍"}""").jsonList().single()["count"])
        @Suppress("UNCHECKED_CAST")
        val shown = editor.get("/api/v1/issues/1").json()["reactions"] as List<Map<String, Any?>>
        assertEquals(false, shown.single()["reacted"])
        assertEquals(422, editor.post("/api/v1/issues/1/reactions", """{"emoji":"<script>"}""").status)
        assertEquals(404, outsider.post("/api/v1/issues/1/reactions", """{"emoji":"👍"}""").status)
    }

    @Test
    fun `이슈에 파일을 붙이고 내려받고 지울 수 있고 보기 역할은 내려받기만 한다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")
        val content = "<script>alert(1)</script>".toByteArray()

        val attached = editor.postFile("/api/v1/issues/1/attachments", "../로그 파일.html", "text/html", content)

        assertEquals(201, attached.status)
        assertEquals("로그 파일.html", attached.json()["fileName"])
        val url = attached.json()["url"] as String
        val downloaded = viewer.get(url)
        assertEquals(200, downloaded.status)
        assertTrue(downloaded.bytes.contentEquals(content))
        assertEquals("application/octet-stream", downloaded.header("Content-Type"))
        assertTrue(downloaded.header("Content-Disposition").orEmpty().startsWith("attachment"))
        assertEquals(403, viewer.postFile("/api/v1/issues/1/attachments", "a.txt", "text/plain", content).status)
        assertEquals(403, viewer.delete(url).status)
        assertEquals(404, outsider.get(url).status)
        assertEquals(413, editor.postFile("/api/v1/issues/1/attachments", "big.bin", "application/octet-stream", ByteArray(6 * 1024 * 1024)).status)

        assertEquals(204, editor.delete(url).status)
        assertEquals(404, editor.get(url).status)
        assertEquals(emptyList<Any>(), editor.get("/api/v1/issues/1").json()["attachments"])
    }

    @Test
    fun `이슈에 문서를 만들어 쓰고 지울 수 있다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")
        val body = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"원인 분석"}]}]}"""

        val created = editor.post("/api/v1/issues/1/documents", """{"title":" 회의록 "}""")

        assertEquals(201, created.status)
        val url = "/api/v1/documents/${created.json()["id"]}"
        val saved = editor.patch(url, """{"title":"회의록 1차","content":$body}""").json()
        assertEquals("회의록 1차", saved["title"])
        assertEquals("doc", (saved["content"] as Map<*, *>)["type"])
        assertEquals("FB-1", (viewer.get(url).json()["issue"] as Map<*, *>)["key"])
        assertEquals(false, viewer.get(url).json()["canEdit"])
        @Suppress("UNCHECKED_CAST")
        val listed = viewer.get("/api/v1/issues/1").json()["documents"] as List<Map<String, Any?>>
        assertEquals(listOf("회의록 1차"), listed.map { it["title"] })
        assertEquals(403, viewer.patch(url, """{"title":"바꿈"}""").status)
        assertEquals(404, outsider.get(url).status)
        assertEquals(422, editor.patch(url, """{"content":{"type":"script"}}""").status)

        assertEquals(204, editor.delete(url).status)
        assertEquals(404, editor.get(url).status)
    }

    @Test
    fun `관리자가 이슈 키를 바꾸면 모든 이슈의 키가 바뀌고 번호는 그대로다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")

        val changed = admin.patch("/api/v1/admin/workspace", """{"issueKey":"flow"}""")

        assertEquals("FLOW", changed.json()["issueKey"])
        assertEquals("FLOW-1", editor.get("/api/v1/issues/1").json()["key"])
        assertEquals(422, admin.patch("/api/v1/admin/workspace", """{"issueKey":"1"}""").status)
        assertEquals(403, editor.patch("/api/v1/admin/workspace", """{"issueKey":"X1"}""").status)
    }

    @Suppress("UNCHECKED_CAST")
    private fun activityOf(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun subscribersOf(feed: Map<String, Any?>): Set<Any?> = (feed["subscribers"] as List<Map<String, Any?>>).map { it["loginId"] }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun labelNames(issue: Map<String, Any?>): List<Any?> = (issue["labels"] as List<Map<String, Any?>>).map { it["name"] }
}
