package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class IssuesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var editorId: String
    private lateinit var viewer: ApiClient
    private lateinit var viewerId: String
    private lateinit var outsider: ApiClient
    private lateinit var outsiderId: String

    @BeforeEach
    fun createProjectWithPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        editorId = createMember("editor")
        viewerId = createMember("viewer")
        outsiderId = createMember("outsider")
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/${p("PAY")}/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", PASSWORD) }
        viewer = client().also { it.login("viewer", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
    }

    @Test
    fun `제목만으로 티켓을 만들 수 있고 번호가 차례로 붙는다`() {
        val first = editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"  결제 버튼이 두 번 눌린다 "}""")
        val second = editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"영수증 메일"}""")

        assertEquals(201, first.status)
        assertEquals("FB-1", first.json()["key"])
        assertEquals("결제 버튼이 두 번 눌린다", first.json()["title"])
        assertEquals("BACKLOG", first.json()["status"])
        assertEquals("NONE", first.json()["priority"])
        assertEquals(emptyList<Any?>(), first.json()["assignees"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("editor", (first.json()["createdBy"] as Map<String, Any?>)["loginId"])
        assertEquals("FB-2", second.json()["key"])
    }

    @Test
    fun `상태 우선순위 담당자 기한 설명을 함께 정해 만들 수 있다`() {
        val created =
            editor.post(
                "/api/v1/projects/${p("PAY")}/issues",
                """
                {"title":"환불 흐름","description":"## 배경\n환불이 느리다","status":"READY",
                 "priority":"HIGH","assigneeIds":["$editorId"],"dueDate":"2026-11-30"}
                """.trimIndent(),
            )

        assertEquals(201, created.status)
        val issue = created.json()
        assertEquals("READY", issue["status"])
        assertEquals("HIGH", issue["priority"])
        assertEquals("2026-11-30", issue["dueDate"])
        assertEquals("## 배경\n환불이 느리다", issue["description"])
        assertEquals(listOf("editor"), loginIdsOf(issue["assignees"]))
    }

    @Test
    fun `담당자를 여러 명 둘 수 있고 바꾸면 더하고 뺀 사람이 활동에 남는다`() {
        val created =
            editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"함께 맡기","assigneeIds":["$viewerId","$editorId","$viewerId"]}""")

        assertEquals(201, created.status)
        // In the order they were sent, without repeats.
        assertEquals(listOf("viewer", "editor"), loginIdsOf(created.json()["assignees"]))
        assertEquals(listOf("FB-1"), viewer.get("/api/v1/me/issues").jsonList().map { it["key"] })
        assertEquals(listOf("FB-1"), editor.get("/api/v1/me/issues").jsonList().map { it["key"] })
        assertEquals(setOf("editor", "viewer"), subscribersOf(admin.get("/api/v1/issues/1/activity").json()))

        // Whoever stays keeps their place; whoever is added comes after.
        val helperId = createMember("helper")
        admin.put("/api/v1/projects/${p("PAY")}/members/$helperId", """{"role":"EDITOR"}""")
        val changed = editor.patch("/api/v1/issues/1", """{"assigneeIds":["$helperId","$editorId"]}""").json()
        assertEquals(listOf("editor", "helper"), loginIdsOf(changed["assignees"]))
        assertEquals(emptyList(), viewer.get("/api/v1/me/issues").jsonList())
        val entry = activityOf(admin.get("/api/v1/issues/1/activity").json()).last()
        assertEquals("ASSIGNEE_CHANGED", entry["type"])
        assertEquals(mapOf("added" to listOf("helper"), "removed" to listOf("viewer")), entry["detail"])

        val cleared = editor.patch("/api/v1/issues/1", """{"assigneeIds":[]}""").json()
        assertEquals(emptyList<Any?>(), cleared["assignees"])
    }

    @Test
    fun `담당자는 열 명까지이고 한 명이라도 구성원이 아니면 바뀌지 않는다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"담당자 한도","assigneeIds":["$editorId"]}""")
        val many = (1..11).map { createMember("member$it") }
        many.forEach { admin.put("/api/v1/projects/${p("PAY")}/members/$it", """{"role":"EDITOR"}""") }

        val tooMany = editor.patch("/api/v1/issues/1", """{"assigneeIds":[${many.joinToString(",") { "\"$it\"" }}]}""")
        val withOutsider = editor.patch("/api/v1/issues/1", """{"assigneeIds":["$viewerId","$outsiderId"]}""")

        assertEquals(422, tooMany.status)
        @Suppress("UNCHECKED_CAST")
        assertEquals("TOO_MANY", (tooMany.json()["errors"] as List<Map<String, String>>).single()["code"])
        assertEquals(422, withOutsider.status)
        assertEquals(listOf("editor"), loginIdsOf(editor.get("/api/v1/issues/1").json()["assignees"]))
    }

    @Test
    fun `제목이 없거나 너무 길면 만들 수 없다`() {
        assertEquals(422, editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"   "}""").status)
        assertEquals(422, editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"${"가".repeat(201)}"}""").status)
        assertTrue(editor.get("/api/v1/projects/${p("PAY")}/issues").jsonList().isEmpty())
    }

    @Test
    fun `프로젝트 구성원이 아닌 사람은 담당자가 될 수 없다`() {
        val response = editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"담당자 테스트","assigneeIds":["$outsiderId"]}""")

        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        assertEquals("NOT_A_MEMBER", (response.json()["errors"] as List<Map<String, String>>).single()["code"])
    }

    @Test
    fun `보기 역할은 티켓을 읽을 수 있지만 만들거나 고칠 수 없다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"읽기 전용 확인"}""")

        assertEquals(1, viewer.get("/api/v1/projects/${p("PAY")}/issues").jsonList().size)
        assertEquals("읽기 전용 확인", viewer.get("/api/v1/issues/1").json()["title"])
        assertEquals(403, viewer.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"안 됨"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"status":"DONE"}""").status)
    }

    @Test
    fun `프로젝트 밖의 사람에게는 티켓도 프로젝트도 없는 것으로 보인다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"숨겨진 티켓"}""")

        val list = outsider.get("/api/v1/projects/${p("PAY")}/issues")
        assertEquals(404, list.status)
        assertEquals("PROJECT_NOT_FOUND", list.json()["code"])
        assertEquals(404, outsider.get("/api/v1/issues/1").status)
        assertEquals(404, outsider.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"끼어들기"}""").status)
    }

    @Test
    fun `보낸 항목만 바뀌고 null 을 보내면 담당자와 기한이 지워진다`() {
        editor.post(
            "/api/v1/projects/${p("PAY")}/issues",
            """{"title":"원래 제목","priority":"LOW","assigneeIds":["$editorId"],"dueDate":"2026-11-30"}""",
        )

        val moved = editor.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""").json()
        assertEquals("IN_PROGRESS", moved["status"])
        assertEquals("LOW", moved["priority"])
        assertEquals("원래 제목", moved["title"])
        assertEquals("2026-11-30", moved["dueDate"])
        assertEquals(2, moved["version"])

        val cleared = editor.patch("/api/v1/issues/1", """{"assigneeIds":[],"dueDate":null,"title":"새 제목"}""").json()
        assertEquals(emptyList<Any?>(), cleared["assignees"])
        assertNull(cleared["dueDate"])
        assertEquals("새 제목", cleared["title"])
        assertEquals("IN_PROGRESS", cleared["status"])
    }

    @Test
    fun `알 수 없는 상태나 잘못된 날짜로는 고칠 수 없다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"검증"}""")

        assertEquals(422, editor.patch("/api/v1/issues/1", """{"status":"FLYING"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"dueDate":"내일"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"title":""}""").status)
        assertEquals("BACKLOG", editor.get("/api/v1/issues/1").json()["status"])
    }

    @Test
    fun `없는 티켓은 404 다`() {
        val response = editor.get("/api/v1/issues/99")

        assertEquals(404, response.status)
        assertEquals("ISSUE_NOT_FOUND", response.json()["code"])
    }

    @Test
    fun `동시에 만들어도 티켓 번호가 겹치지 않는다`() {
        val clients = (1..6).map { client().also { c -> c.login("editor", PASSWORD) } }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(clients.size)

        val keys =
            clients
                .mapIndexed { index, c ->
                    pool.submit<String> {
                        start.await()
                        c.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"동시 $index"}""").json()["key"] as String
                    }
                }.also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals((1..6).map { "FB-$it" }.toSet(), keys.toSet())
    }

    @Test
    fun `목록은 최근에 만든 티켓이 먼저 나온다`() {
        listOf("첫째", "둘째", "셋째").forEach { editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"$it"}""") }

        assertEquals(listOf("셋째", "둘째", "첫째"), editor.get("/api/v1/projects/${p("PAY")}/issues").jsonList().map { it["title"] })
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    @Test
    fun `내 티켓은 내가 담당하거나 만든 티켓을 들어갈 수 있는 프로젝트에서만 모아 준다`() {
        remember("SEC", admin.post("/api/v1/projects", """{"name":"비공개"}""").json()["id"])
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"내가 만들고 내가 맡음","assigneeIds":["$editorId"]}""")
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"내가 만들기만 함"}""")
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"관리자가 나에게 맡김","assigneeIds":["$editorId"]}""")
        admin.post("/api/v1/projects/${p("SEC")}/issues", """{"title":"다른 프로젝트"}""")

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
    fun `프로젝트에서 빠지면 그 프로젝트의 티켓은 내 티켓에 나오지 않는다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"맡은 일","assigneeIds":["$editorId"]}""")

        admin.delete("/api/v1/projects/${p("PAY")}/members/$editorId")

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
    fun `티켓에 라벨을 여러 개 붙이고 바꾸고 모두 뗄 수 있다`() {
        val labels = admin.get("/api/v1/labels").jsonList().associate { it["name"] to it["id"] }
        val created =
            editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패","labelIds":["${labels["버그"]}","${labels["중요"]}"]}""")

        assertEquals(201, created.status)
        assertEquals(listOf("버그", "중요"), labelNames(created.json()))
        assertEquals(listOf("버그", "중요"), labelNames(viewer.get("/api/v1/projects/${p("PAY")}/issues").jsonList().single()))

        val changed = editor.patch("/api/v1/issues/1", """{"labelIds":["${labels["기능"]}"]}""")
        assertEquals(listOf("기능"), labelNames(changed.json()))
        assertEquals(listOf("기능"), labelNames(editor.patch("/api/v1/issues/1", """{"title":"제목만"}""").json()))
        assertEquals(emptyList(), labelNames(editor.patch("/api/v1/issues/1", """{"labelIds":[]}""").json()))
    }

    @Test
    fun `없는 라벨은 붙일 수 없고 보기 역할은 라벨을 바꿀 수 없다`() {
        val bug = admin.get("/api/v1/labels").jsonList().first()["id"]
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")

        val unknown = editor.patch("/api/v1/issues/1", """{"labelIds":["00000000-0000-7000-8000-000000000000"]}""")

        assertEquals(422, unknown.status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"labelIds":"버그"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"labelIds":["$bug"]}""").status)
    }

    @Test
    fun `티켓을 만들고 고친 일이 차례로 활동에 남는다`() {
        val bug = admin.get("/api/v1/labels").jsonList().first()["id"]
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")

        editor.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS","assigneeIds":["$editorId"]}""")
        admin.patch("/api/v1/issues/1", """{"labelIds":["$bug"],"dueDate":"2026-11-01"}""")
        admin.patch("/api/v1/issues/1", """{"priority":"NONE"}""")

        val items = activityOf(viewer.get("/api/v1/issues/1/activity").json())
        assertEquals(
            listOf("CREATED", "STATUS_CHANGED", "ASSIGNEE_CHANGED", "DUE_DATE_CHANGED", "LABELS_CHANGED"),
            items.map { it["type"] },
        )
        assertEquals(mapOf("from" to "BACKLOG", "to" to "IN_PROGRESS"), items[1]["detail"])
        assertEquals(mapOf("added" to listOf("editor"), "removed" to emptyList<String>()), items[2]["detail"])
        assertEquals(mapOf("added" to listOf("버그"), "removed" to emptyList<String>()), items[4]["detail"])
        assertEquals("admin", (items[4]["actor"] as Map<*, *>)["loginId"])
    }

    @Test
    fun `제목을 이어서 고치면 활동은 하나로 합쳐지고 처음과 마지막 제목이 남는다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제"}""")

        editor.patch("/api/v1/issues/1", """{"title":"결제 실"}""")
        editor.patch("/api/v1/issues/1", """{"title":"결제 실패"}""")

        val items = activityOf(editor.get("/api/v1/issues/1/activity").json())
        assertEquals(listOf("CREATED", "TITLE_CHANGED"), items.map { it["type"] })
        assertEquals(mapOf("from" to "결제", "to" to "결제 실패"), items[1]["detail"])
    }

    @Test
    fun `만든 사람과 담당자는 자동으로 구독하고 누구나 구독하거나 그만둘 수 있다`() {
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패","assigneeIds":["$editorId"]}""")

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
    fun `프로젝트 없이 만든 티켓은 누구나 보고 고치고 프로젝트에 연결하면 그 프로젝트 사람만 본다`() {
        val created = outsider.post("/api/v1/issues", """{"title":"혼자 있는 티켓","assigneeIds":["$outsiderId"]}""")

        assertEquals(201, created.status)
        assertEquals("FB-1", created.json()["key"])
        assertNull(created.json()["project"])
        assertEquals(200, viewer.patch("/api/v1/issues/1", """{"status":"READY"}""").status)
        assertEquals(listOf("FB-1"), outsider.get("/api/v1/issues").jsonList().map { it["key"] })
        // A milestone needs a project.
        assertEquals(422, outsider.patch("/api/v1/issues/1", """{"milestoneId":"00000000-0000-7000-8000-000000000000"}""").status)
        // Linking needs the right to edit in that project.
        assertEquals(404, outsider.patch("/api/v1/issues/1", """{"projectId":"${p("PAY")}"}""").status)
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"projectId":"${p("PAY")}"}""").status)

        val linked = editor.patch("/api/v1/issues/1", """{"projectId":"${p("PAY")}"}""")

        assertEquals(p("PAY"), (linked.json()["project"] as Map<*, *>)["id"])
        assertEquals("FB-1", linked.json()["key"])
        assertEquals(404, outsider.get("/api/v1/issues/1").status)
        assertEquals(emptyList(), outsider.get("/api/v1/issues").jsonList())
        assertEquals(listOf("FB-1"), viewer.get("/api/v1/projects/${p("PAY")}/issues").jsonList().map { it["key"] })
        assertEquals(403, viewer.patch("/api/v1/issues/1", """{"status":"DONE"}""").status)

        assertNull(editor.patch("/api/v1/issues/1", """{"projectId":null}""").json()["project"])
        assertEquals(200, outsider.get("/api/v1/issues/1").status)
    }

    @Test
    fun `프로젝트 연결을 바꾸면 목표는 떨어지고 번호는 그대로다`() {
        remember("SEC", admin.post("/api/v1/projects", """{"name":"보안"}""").json()["id"])
        val milestone = admin.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"베타"}""").json()["id"]
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"옮길 티켓","milestoneId":"$milestone"}""")

        val moved = admin.patch("/api/v1/issues/1", """{"projectId":"${p("SEC")}"}""").json()

        assertEquals("FB-1", moved["key"])
        assertEquals(p("SEC"), (moved["project"] as Map<*, *>)["id"])
        assertNull(moved["milestone"])
        assertEquals(422, admin.patch("/api/v1/issues/1", """{"milestoneId":"$milestone"}""").status)
    }

    @Test
    fun `하위 티켓을 만들면 상위 티켓에 보이고 서로 물리는 관계는 만들 수 없다`() {
        val parent = editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"상위"}""").json()
        val child = editor.post("/api/v1/issues", """{"title":"하위","projectId":"${p("PAY")}","parentId":"${parent["id"]}"}""").json()

        assertEquals("FB-1", (child["parent"] as Map<*, *>)["key"])
        @Suppress("UNCHECKED_CAST")
        val subIssues = editor.get("/api/v1/issues/1").json()["subIssues"] as List<Map<String, Any?>>
        assertEquals(listOf("FB-2"), subIssues.map { it["key"] })
        editor.patch("/api/v1/issues/2", """{"status":"DONE"}""")
        val listed = editor.get("/api/v1/issues").jsonList().single { it["key"] == "FB-1" }
        assertEquals(1 to 1, listed["subIssueCount"] to listed["subIssueDoneCount"])
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"parentId":"${child["id"]}"}""").status)
        assertEquals(422, editor.patch("/api/v1/issues/1", """{"parentId":"${parent["id"]}"}""").status)
        assertNull(editor.patch("/api/v1/issues/2", """{"parentId":null}""").json()["parent"])
    }

    @Test
    fun `하위 티켓은 10단계까지만 만들 수 있다`() {
        fun sub(parentId: Any?) = editor.post("/api/v1/issues", """{"title":"하위","parentId":"$parentId"}""")
        // levels[0] is the top-level issue, levels[n] the sub-issue n levels below it.
        val levels = mutableListOf(editor.post("/api/v1/issues", """{"title":"상위"}""").json())
        repeat(10) { levels += sub(levels.last()["id"]).json() }

        val tooDeep = sub(levels[10]["id"])
        assertEquals(409, tooDeep.status)
        assertEquals("ISSUE_TOO_DEEP", tooDeep.json()["code"])
        assertEquals(false, editor.get("/api/v1/issues/${levels[10]["number"]}").json()["canAddSubIssue"])
        assertEquals(true, editor.get("/api/v1/issues/${levels[9]["number"]}").json()["canAddSubIssue"])

        // An issue moves with what hangs under it, so its own sub-issue counts.
        val other = editor.post("/api/v1/issues", """{"title":"따로"}""").json()
        sub(other["id"])
        val moved = editor.patch("/api/v1/issues/${other["number"]}", """{"parentId":"${levels[9]["id"]}"}""")
        assertEquals(409, moved.status)
        assertEquals("ISSUE_TOO_DEEP", moved.json()["code"])
        assertEquals(200, editor.patch("/api/v1/issues/${other["number"]}", """{"parentId":"${levels[8]["id"]}"}""").status)
    }

    @Test
    fun `하위 티켓의 프로젝트와 목표는 항상 상위를 따르고 담당자는 상위 것으로 시작해 바꿀 수 있다`() {
        remember("SEC", admin.post("/api/v1/projects", """{"name":"보안"}""").json()["id"])
        val beta = admin.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"베타"}""").json()["id"]
        val release = admin.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"출시"}""").json()["id"]
        val alpha = admin.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"알파"}""").json()["id"]
        val parent =
            admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"상위","milestoneId":"$beta","assigneeIds":["$editorId"]}""").json()

        val child = admin.post("/api/v1/issues", """{"title":"하위","parentId":"${parent["id"]}"}""").json()
        // Another project or milestone in the request is not used; assignees that are sent are.
        val ownWay =
            admin
                .post(
                    "/api/v1/issues",
                    """{"title":"다른 곳","parentId":"${parent["id"]}","projectId":"${p("SEC")}","milestoneId":"$alpha","assigneeIds":[]}""",
                ).json()
        val grandchild = admin.post("/api/v1/issues", """{"title":"손주","parentId":"${child["id"]}"}""").json()

        listOf(child, ownWay, grandchild).forEach {
            assertEquals(p("PAY") to "베타", (it["project"] as Map<*, *>)["id"] to (it["milestone"] as Map<*, *>)["name"])
        }
        assertEquals(listOf("editor"), loginIdsOf(child["assignees"]))
        assertEquals(listOf("editor"), loginIdsOf(grandchild["assignees"]))
        // Every issue above it, the top-level one first.
        assertEquals(listOf("FB-1", "FB-2"), (grandchild["ancestors"] as List<*>).map { (it as Map<*, *>)["key"] })
        assertEquals(emptyList<Any?>(), parent["ancestors"])
        assertEquals(emptyList<Any?>(), ownWay["assignees"])

        // On a sub-issue the project and milestone cannot be set; the assignees can.
        val otherMilestone = admin.patch("/api/v1/issues/2", """{"milestoneId":"$alpha"}""")
        assertEquals(409, otherMilestone.status)
        assertEquals("FOLLOWS_PARENT", otherMilestone.json()["code"])
        assertEquals(409, admin.patch("/api/v1/issues/2", """{"projectId":"${p("SEC")}"}""").status)
        assertEquals(200, admin.patch("/api/v1/issues/2", """{"milestoneId":"$beta","projectId":"${p("PAY")}"}""").status)
        assertEquals(emptyList<Any?>(), admin.patch("/api/v1/issues/2", """{"assigneeIds":[]}""").json()["assignees"])

        // The parent moves to another milestone, then to another project: everything below follows.
        admin.patch("/api/v1/issues/1", """{"milestoneId":"$release"}""")
        listOf(2, 3, 4).forEach { assertEquals("출시", (admin.get("/api/v1/issues/$it").json()["milestone"] as Map<*, *>)["name"]) }
        admin.patch("/api/v1/issues/1", """{"projectId":"${p("SEC")}"}""")
        listOf(2, 3, 4).forEach { number ->
            val moved = admin.get("/api/v1/issues/$number").json()
            assertEquals(p("SEC"), (moved["project"] as Map<*, *>)["id"])
            assertNull(moved["milestone"])
        }
        assertTrue(activityOf(admin.get("/api/v1/issues/4/activity").json()).any { it["type"] == "PROJECT_CHANGED" })

        // Taken off its parent it is free again; put under one, it goes where that one is.
        assertNull(admin.patch("/api/v1/issues/3", """{"parentId":null}""").json()["parent"])
        assertEquals(p("PAY"), (admin.patch("/api/v1/issues/3", """{"projectId":"${p("PAY")}"}""").json()["project"] as Map<*, *>)["id"])
        val attached = admin.patch("/api/v1/issues/3", """{"parentId":"${parent["id"]}"}""").json()
        assertEquals(p("SEC"), (attached["project"] as Map<*, *>)["id"])
    }

    @Test
    fun `티켓에 이모지를 달고 다시 누르면 지워진다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")

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
    fun `티켓에 파일을 붙이고 내려받고 지울 수 있고 보기 역할은 내려받기만 한다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")
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
    fun `티켓에 문서를 만들어 쓰고 지울 수 있다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")
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

        // Deleted documents are archived: gone from the issue, still readable, not writable.
        assertEquals(204, editor.delete(url).status)
        assertNotNull(editor.get(url).json()["deletedAt"])
        assertEquals(emptyList<Any>(), editor.get("/api/v1/issues/1").json()["documents"])
    }

    @Test
    fun `티켓의 날짜는 하루이거나 시작일과 종료일이고 종료일을 지우면 시작일도 지워진다`() {
        val created = editor.post("/api/v1/issues", """{"title":"출시 준비","startDate":"2026-11-02","dueDate":"2026-11-06"}""").json()
        assertEquals("2026-11-02" to "2026-11-06", created["startDate"] to created["dueDate"])

        assertEquals(422, editor.patch("/api/v1/issues/1", """{"startDate":"2026-11-10"}""").status)
        val single = editor.patch("/api/v1/issues/1", """{"startDate":null}""").json()
        assertEquals(null to "2026-11-06", single["startDate"] to single["dueDate"])
        val span = editor.patch("/api/v1/issues/1", """{"startDate":"2026-11-04","dueDate":"2026-11-09"}""").json()
        assertEquals("2026-11-04" to "2026-11-09", span["startDate"] to span["dueDate"])
        val cleared = editor.patch("/api/v1/issues/1", """{"dueDate":null}""").json()
        assertEquals(null to null, cleared["startDate"] to cleared["dueDate"])
        val changes = activityOf(editor.get("/api/v1/issues/1/activity").json()).filter { it["type"] == "DUE_DATE_CHANGED" }
        assertEquals(mapOf("from" to "2026-11-06", "to" to "2026-11-04 ~ 2026-11-09"), changes[1]["detail"])
    }

    @Test
    fun `티켓을 삭제하면 목록에서 빠지고 삭제된 목록과 활동으로 볼 수 있으며 복원할 수 있다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"지울 티켓","assigneeIds":["$editorId"]}""")
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"남는 티켓"}""")

        assertEquals(403, viewer.delete("/api/v1/issues/1").status)
        assertEquals(204, editor.delete("/api/v1/issues/1").status)

        assertEquals(listOf("FB-2"), viewer.get("/api/v1/projects/${p("PAY")}/issues").jsonList().map { it["key"] })
        assertEquals(listOf("FB-1"), viewer.get("/api/v1/projects/${p("PAY")}/issues?deleted=true").jsonList().map { it["key"] })
        assertEquals(listOf("FB-1"), editor.get("/api/v1/issues?deleted=true").jsonList().map { it["key"] })
        assertEquals(listOf("FB-1"), editor.get("/api/v1/me/issues?deleted=true").jsonList().map { it["key"] })
        assertEquals(emptyList(), editor.get("/api/v1/me/issues").jsonList())
        val shown = viewer.get("/api/v1/issues/1").json()
        assertEquals(false, shown["canEdit"])
        assertTrue(shown["deletedAt"] != null)
        assertEquals(403, editor.patch("/api/v1/issues/1", """{"title":"고치기"}""").status)
        assertEquals("DELETED", activityOf(viewer.get("/api/v1/issues/1/activity").json()).last()["type"])
        assertEquals(1, admin.get("/api/v1/projects").jsonList().single()["issueCount"])

        assertEquals(403, viewer.post("/api/v1/issues/1/restore", "{}").status)
        assertNull(editor.post("/api/v1/issues/1/restore", "{}").json()["deletedAt"])
        assertEquals(2, viewer.get("/api/v1/projects/${p("PAY")}/issues").jsonList().size)
        assertEquals(200, editor.patch("/api/v1/issues/1", """{"title":"고치기"}""").status)
    }

    @Test
    fun `프로젝트에도 문서를 만들 수 있고 그 프로젝트의 권한을 따른다`() {
        val created = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"설계 메모"}""")

        assertEquals(201, created.status)
        val url = "/api/v1/documents/${created.json()["id"]}"
        val shown = viewer.get(url).json()
        assertEquals(p("PAY"), (shown["project"] as Map<*, *>)["id"])
        assertNull(shown["issue"])
        assertEquals(false, shown["canEdit"])
        assertEquals(listOf("설계 메모"), viewer.get("/api/v1/projects/${p("PAY")}/documents").jsonList().map { it["title"] })
        assertEquals(200, editor.patch(url, """{"title":"설계 메모 2"}""").status)
        assertEquals(403, viewer.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"안 됨"}""").status)
        assertEquals(403, viewer.patch(url, """{"title":"안 됨"}""").status)
        assertEquals(404, outsider.get(url).status)
        assertEquals(404, outsider.get("/api/v1/projects/${p("PAY")}/documents").status)
        assertEquals(204, editor.delete(url).status)
        assertEquals(emptyList(), editor.get("/api/v1/projects/${p("PAY")}/documents").jsonList())
    }

    @Test
    fun `프로젝트의 문서는 다섯 단계까지 안에 문서를 만들 수 있고 티켓의 문서는 한 단계뿐이다`() {
        fun child(parentId: Any?) = editor.post("/api/v1/documents/$parentId/documents", """{"title":"하위"}""")
        val top = editor.post("/api/v1/projects/${p("PAY")}/documents", """{"title":"1단계"}""").json()["id"]

        val ids = mutableListOf(top)
        repeat(4) { ids += child(ids.last()).also { assertEquals(201, it.status) }.json()["id"] }

        val tooDeep = child(ids.last())
        assertEquals(409, tooDeep.status)
        assertEquals("DOCUMENT_TOO_DEEP", tooDeep.json()["code"])
        val deepest = viewer.get("/api/v1/documents/${ids.last()}").json()
        assertEquals(ids.dropLast(1), (deepest["ancestors"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(false, deepest["canAddChild"])
        val first = editor.get("/api/v1/documents/$top").json()
        assertEquals(listOf(ids[1]), (first["children"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(true, first["canAddChild"])
        // Only the documents made on the overview itself are listed for the project.
        assertEquals(listOf(top), viewer.get("/api/v1/projects/${p("PAY")}/documents").jsonList().map { it["id"] })
        assertEquals(403, viewer.post("/api/v1/documents/$top/documents", """{"title":"안 됨"}""").status)
        // A page block in a body is accepted; one that does not point at a document is not.
        val page = """{"type":"doc","content":[{"type":"pageLink","attrs":{"id":"${ids[1]}","title":"하위"}}]}"""
        assertEquals(200, editor.patch("/api/v1/documents/$top", """{"content":$page}""").status)
        val bad = """{"type":"doc","content":[{"type":"pageLink","attrs":{"id":"/etc/passwd"}}]}"""
        assertEquals(422, editor.patch("/api/v1/documents/$top", """{"content":$bad}""").status)
        // Embeds: Figma files and Outline share pages only, never another scheme or service.
        val embed = { provider: String, url: String ->
            val node = """{"type":"embed","attrs":{"provider":"$provider","url":"$url"}}"""
            editor.patch("/api/v1/documents/$top", """{"content":{"type":"doc","content":[$node]}}""").status
        }
        assertEquals(200, embed("figma", "https://www.figma.com/design/AbC123/Flow?node-id=1-2"))
        assertEquals(200, embed("outline", "https://wiki.example.com/s/3f2a-team-notes"))
        assertEquals(422, embed("figma", "javascript:alert(1)"))
        assertEquals(422, embed("outline", "https://wiki.example.com/doc/private-abc"))
        assertEquals(422, embed("youtube", "https://www.figma.com/design/AbC123/Flow"))
        editor.patch("/api/v1/documents/$top", """{"content":$page}""")

        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")
        val issueDocument = editor.post("/api/v1/issues/1/documents", """{"title":"회의록"}""").json()["id"]
        assertEquals(409, child(issueDocument).status)
        assertEquals(false, editor.get("/api/v1/documents/$issueDocument").json()["canAddChild"])

        // Deleting a document archives it: it leaves the list, opens read-only and can be restored.
        val listed = { deleted: Boolean -> editor.get("/api/v1/projects/${p("PAY")}/documents?deleted=$deleted").jsonList().map { it["id"] } }
        assertEquals(204, editor.delete("/api/v1/documents/$top").status)
        assertEquals(emptyList(), listed(false))
        assertEquals(listOf(top), listed(true))
        val archived = editor.get("/api/v1/documents/$top").json()
        assertNotNull(archived["deletedAt"])
        assertEquals(false, archived["canEdit"])
        assertEquals(true, archived["canRestore"])
        assertEquals(403, editor.patch("/api/v1/documents/$top", """{"title":"바꾸기"}""").status)
        assertEquals(404, editor.delete("/api/v1/documents/$top").status)
        // The documents inside it are untouched, so they come back with it.
        assertEquals(204, editor.post("/api/v1/documents/$top/restore").status)
        assertEquals(listOf(top), listed(false))
        assertEquals(emptyList(), listed(true))
        assertEquals(200, editor.get("/api/v1/documents/${ids.last()}").status)
        assertEquals(404, editor.post("/api/v1/documents/$top/restore").status)

        // An issue's document: the same, and both steps are in the issue's history.
        assertEquals(204, editor.delete("/api/v1/documents/$issueDocument").status)
        assertEquals(listOf(issueDocument), editor.get("/api/v1/issues/1/documents/deleted").jsonList().map { it["id"] })
        assertEquals(204, editor.post("/api/v1/documents/$issueDocument/restore").status)
        assertEquals(emptyList(), editor.get("/api/v1/issues/1/documents/deleted").jsonList())
    }

    @Test
    fun `관리자가 티켓 키를 바꾸면 모든 티켓의 키가 바뀌고 번호는 그대로다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")

        val changed = admin.patch("/api/v1/admin/workspace", """{"workspaceKey":"flow"}""")

        assertEquals("FLOW", changed.json()["workspaceKey"])
        assertEquals("FLOW-1", editor.get("/api/v1/issues/1").json()["key"])
        assertEquals(422, admin.patch("/api/v1/admin/workspace", """{"workspaceKey":"1"}""").status)
        assertEquals(403, editor.patch("/api/v1/admin/workspace", """{"workspaceKey":"X1"}""").status)
    }

    @Test
    fun `편집 역할은 댓글을 쓰고 글쓴이만 고치며 글쓴이와 관리자가 지운다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"댓글 확인"}""")

        val written = editor.post("/api/v1/issues/1/comments", commentJson("확인했습니다"))
        val id = written.json()["id"]

        assertEquals(201, written.status)
        assertEquals(true, written.json()["canEdit"])
        assertEquals(403, viewer.post("/api/v1/issues/1/comments", commentJson("보기 역할")).status)
        assertEquals(404, outsider.post("/api/v1/issues/1/comments", commentJson("밖의 사람")).status)
        assertEquals(422, editor.post("/api/v1/issues/1/comments", """{"content":{"type":"doc","content":[{"type":"paragraph"}]}}""").status)
        assertEquals(422, editor.post("/api/v1/issues/1/comments", "{}").status)
        // Everyone who can see the issue reads the comment; only its author may change it.
        val seen = commentsOf(viewer.get("/api/v1/issues/1/activity").json()).single()
        assertEquals(id, seen["id"])
        assertEquals(false, seen["canEdit"])
        assertEquals(false, seen["canDelete"])
        assertEquals(403, admin.patch("/api/v1/comments/$id", commentJson("남의 글")).status)
        assertEquals(200, editor.patch("/api/v1/comments/$id", commentJson("다시 확인했습니다")).status)
        assertTrue(text("select body from issue_comments").contains("다시 확인"))
        assertEquals(403, viewer.delete("/api/v1/comments/$id").status)
        assertEquals(204, admin.delete("/api/v1/comments/$id").status)
        assertTrue(commentsOf(editor.get("/api/v1/issues/1/activity").json()).isEmpty())
        assertEquals(404, editor.patch("/api/v1/comments/$id", commentJson("지워진 글")).status)
    }

    @Test
    fun `댓글과 본문에서 태그된 프로젝트 구성원은 티켓을 구독하게 된다`() {
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"태그 확인"}""")
        val mention = """{"type":"mention","attrs":{"id":"%s","label":"이름"}}"""
        val tagging = """{"content":{"type":"doc","content":[{"type":"paragraph","content":[${mention.format(editorId)},${mention.format(outsiderId)}]}]}}"""

        assertEquals(201, admin.post("/api/v1/issues/1/comments", tagging).status)

        // The person outside the project cannot see the issue, so tagging them does nothing.
        assertEquals(setOf("admin", "editor"), subscribersOf(admin.get("/api/v1/issues/1/activity").json()))
        assertEquals("@이름@이름", text("select body from issue_comments"))

        admin.post("/api/v1/issues", """{"title":"본문 태그"}""")
        assertEquals(200, admin.patch("/api/v1/issues/2", tagging).status)
        assertEquals(setOf("admin", "editor", "outsider"), subscribersOf(admin.get("/api/v1/issues/2/activity").json()))
        val bad = """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"mention","attrs":{"id":"x","label":"이름"}}]}]}}"""
        assertEquals(422, admin.post("/api/v1/issues/1/comments", bad).status)
    }

    @Test
    fun `삭제된 티켓에는 댓글을 쓸 수 없다`() {
        editor.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"지울 티켓"}""")
        editor.delete("/api/v1/issues/1")

        assertEquals(403, editor.post("/api/v1/issues/1/comments", commentJson("늦은 댓글")).status)
    }

    private fun commentJson(text: String) = """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}}"""

    @Suppress("UNCHECKED_CAST")
    private fun commentsOf(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["comments"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun activityOf(feed: Map<String, Any?>): List<Map<String, Any?>> = feed["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun loginIdsOf(people: Any?): List<Any?> = (people as List<Map<String, Any?>>).map { it["loginId"] }

    @Suppress("UNCHECKED_CAST")
    private fun subscribersOf(feed: Map<String, Any?>): Set<Any?> = (feed["subscribers"] as List<Map<String, Any?>>).map { it["loginId"] }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun labelNames(issue: Map<String, Any?>): List<Any?> = (issue["labels"] as List<Map<String, Any?>>).map { it["name"] }
}
