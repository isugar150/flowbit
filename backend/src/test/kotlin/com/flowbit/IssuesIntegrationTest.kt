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
        assertEquals("PAY-1", first.json()["key"])
        assertEquals("결제 버튼이 두 번 눌린다", first.json()["title"])
        assertEquals("BACKLOG", first.json()["status"])
        assertEquals("NONE", first.json()["priority"])
        assertNull(first.json()["assignee"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("editor", (first.json()["createdBy"] as Map<String, Any?>)["loginId"])
        assertEquals("PAY-2", second.json()["key"])
    }

    @Test
    fun `상태 우선순위 담당자 프리셋 기한 설명을 함께 정해 만들 수 있다`() {
        val created =
            editor.post(
                "/api/v1/projects/PAY/issues",
                """
                {"title":"환불 흐름","description":"## 배경\n환불이 느리다","preset":"ENHANCEMENT","status":"READY",
                 "priority":"HIGH","assigneeId":"$editorId","dueDate":"2026-11-30"}
                """.trimIndent(),
            )

        assertEquals(201, created.status)
        val issue = created.json()
        assertEquals("READY", issue["status"])
        assertEquals("HIGH", issue["priority"])
        assertEquals("ENHANCEMENT", issue["preset"])
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
        assertEquals("읽기 전용 확인", viewer.get("/api/v1/projects/PAY/issues/1").json()["title"])
        assertEquals(403, viewer.post("/api/v1/projects/PAY/issues", """{"title":"안 됨"}""").status)
        assertEquals(403, viewer.patch("/api/v1/projects/PAY/issues/1", """{"status":"DONE"}""").status)
    }

    @Test
    fun `프로젝트 밖의 사람에게는 이슈도 프로젝트도 없는 것으로 보인다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"숨겨진 이슈"}""")

        val list = outsider.get("/api/v1/projects/PAY/issues")
        assertEquals(404, list.status)
        assertEquals("PROJECT_NOT_FOUND", list.json()["code"])
        assertEquals(404, outsider.get("/api/v1/projects/PAY/issues/1").status)
        assertEquals(404, outsider.post("/api/v1/projects/PAY/issues", """{"title":"끼어들기"}""").status)
    }

    @Test
    fun `보낸 항목만 바뀌고 null 을 보내면 담당자와 기한이 지워진다`() {
        editor.post(
            "/api/v1/projects/PAY/issues",
            """{"title":"원래 제목","priority":"LOW","assigneeId":"$editorId","dueDate":"2026-11-30"}""",
        )

        val moved = editor.patch("/api/v1/projects/PAY/issues/1", """{"status":"IN_PROGRESS"}""").json()
        assertEquals("IN_PROGRESS", moved["status"])
        assertEquals("LOW", moved["priority"])
        assertEquals("원래 제목", moved["title"])
        assertEquals("2026-11-30", moved["dueDate"])
        assertEquals(2, moved["version"])

        val cleared = editor.patch("/api/v1/projects/PAY/issues/1", """{"assigneeId":null,"dueDate":null,"title":"새 제목"}""").json()
        assertNull(cleared["assignee"])
        assertNull(cleared["dueDate"])
        assertEquals("새 제목", cleared["title"])
        assertEquals("IN_PROGRESS", cleared["status"])
    }

    @Test
    fun `알 수 없는 상태나 잘못된 날짜로는 고칠 수 없다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"검증"}""")

        assertEquals(422, editor.patch("/api/v1/projects/PAY/issues/1", """{"status":"FLYING"}""").status)
        assertEquals(422, editor.patch("/api/v1/projects/PAY/issues/1", """{"dueDate":"내일"}""").status)
        assertEquals(422, editor.patch("/api/v1/projects/PAY/issues/1", """{"title":""}""").status)
        assertEquals("BACKLOG", editor.get("/api/v1/projects/PAY/issues/1").json()["status"])
    }

    @Test
    fun `없는 이슈는 404 다`() {
        val response = editor.get("/api/v1/projects/PAY/issues/99")

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

        assertEquals((1..6).map { "PAY-$it" }.toSet(), keys.toSet())
    }

    @Test
    fun `목록은 최근에 만든 이슈가 먼저 나온다`() {
        listOf("첫째", "둘째", "셋째").forEach { editor.post("/api/v1/projects/PAY/issues", """{"title":"$it"}""") }

        assertEquals(listOf("셋째", "둘째", "첫째"), editor.get("/api/v1/projects/PAY/issues").jsonList().map { it["title"] })
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String
}
