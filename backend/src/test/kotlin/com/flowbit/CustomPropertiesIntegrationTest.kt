package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** The properties people add to a project or an issue (D-186, docs/features/custom-properties.md). */
class CustomPropertiesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var viewer: ApiClient
    private lateinit var outsider: ApiClient
    private lateinit var editorId: String
    private lateinit var outsiderId: String
    private lateinit var projectId: String
    private lateinit var issueId: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        editorId = member("editor")
        val viewerId = member("viewer")
        outsiderId = member("outsider")
        projectId = admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"] as String
        admin.put("/api/v1/projects/$projectId/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/$projectId/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", PASSWORD) }
        viewer = client().also { it.login("viewer", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
        issueId = admin.post("/api/v1/projects/$projectId/issues", """{"title":"결제 버튼"}""").json()["id"] as String
    }

    private fun member(loginId: String) = admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    private val targets get() = listOf("project" to projectId, "issue" to issueId)

    private fun path(
        type: String,
        id: String,
    ) = "/api/v1/custom-properties/$type/$id"

    @Suppress("UNCHECKED_CAST")
    private fun items(response: Map<String, Any?>): List<Map<String, Any?>> = response["items"] as List<Map<String, Any?>>

    private fun add(
        client: ApiClient,
        type: String,
        id: String,
        label: String,
        kind: String,
        value: String? = null,
    ) = client.post(path(type, id), """{"label":"$label","type":"$kind"${value?.let { ""","value":$it""" } ?: ""}}""")

    @Test
    fun `프로젝트와 티켓마다 여섯 가지 형식의 속성을 추가하고 값을 읽는다`() {
        for ((type, id) in targets) {
            assertEquals(emptyList<Any?>(), items(admin.get(path(type, id)).json()))

            assertEquals(201, add(editor, type, id, "담당 부서", "TEXT", "\"  결제팀  \"").status)
            assertEquals(201, add(editor, type, id, "예산", "NUMBER", "1500.5").status)
            assertEquals(201, add(editor, type, id, "배포예정일", "DATE", "\"2026-11-01\"").status)
            assertEquals(201, add(editor, type, id, "검수 완료", "CHECKBOX").status)
            assertEquals(201, add(editor, type, id, "명세", "LINK", "\"https://example.com/spec\"").status)
            assertEquals(201, add(editor, type, id, "QA 담당", "PERSON", "\"$editorId\"").status)
            assertEquals(201, add(editor, type, id, "비고", "TEXT").status)

            val list = admin.get(path(type, id)).json()
            assertEquals(true, list["canEdit"])
            val all = items(list)
            // In the order they were added.
            assertEquals(listOf("담당 부서", "예산", "배포예정일", "검수 완료", "명세", "QA 담당", "비고"), all.map { it["label"] })
            assertEquals(listOf("결제팀", 1500.5, "2026-11-01", false, "https://example.com/spec", editorId, null), all.map { it["value"] })
            @Suppress("UNCHECKED_CAST")
            assertEquals("editor", (all[5]["person"] as Map<String, Any?>)["loginId"])
            assertNull(all[6]["person"])
        }
    }

    @Test
    fun `값을 바꾸고 지우고 이름을 바꿀 수 있고 형식은 바꿀 수 없다`() {
        val id = add(editor, "project", projectId, "예산", "NUMBER", "10").json()["id"] as String

        assertEquals(20, editor.patch("/api/v1/custom-properties/$id", """{"value":20}""").json()["value"])
        assertEquals(2500000000L, editor.patch("/api/v1/custom-properties/$id", """{"value":2500000000}""").json()["value"])
        // A whole number written with decimals is stored as a whole number.
        assertEquals(7, editor.patch("/api/v1/custom-properties/$id", """{"value":7.0}""").json()["value"])
        assertNull(editor.patch("/api/v1/custom-properties/$id", """{"value":null}""").json()["value"])
        val renamed = editor.patch("/api/v1/custom-properties/$id", """{"label":"  총예산 "}""").json()
        assertEquals("총예산", renamed["label"])
        // The kind is the one it was added with.
        assertEquals("NUMBER", editor.patch("/api/v1/custom-properties/$id", """{"type":"TEXT"}""").json()["type"])

        assertEquals(204, editor.delete("/api/v1/custom-properties/$id").status)
        assertEquals(emptyList<Any?>(), items(admin.get(path("project", projectId)).json()))
        assertEquals(404, editor.delete("/api/v1/custom-properties/$id").status)
    }

    @Test
    fun `이름은 비울 수 없고 30자까지이며 대소문자 없이 겹칠 수 없고 한 항목에 20개까지다`() {
        assertEquals(422, add(editor, "project", projectId, "  ", "TEXT").status)
        assertEquals(422, add(editor, "project", projectId, "가".repeat(31), "TEXT").status)
        assertEquals(201, add(editor, "project", projectId, "가".repeat(30), "TEXT").status)

        val id = add(editor, "project", projectId, "Due", "TEXT").json()["id"] as String
        assertEquals(409, add(editor, "project", projectId, "due", "DATE").status)
        add(editor, "project", projectId, "Other", "TEXT")
        assertEquals(409, editor.patch("/api/v1/custom-properties/$id", """{"label":"OTHER"}""").status)
        // Its own label in another case is fine; the same label on another item is fine.
        assertEquals(200, editor.patch("/api/v1/custom-properties/$id", """{"label":"DUE"}""").status)
        assertEquals(201, add(editor, "issue", issueId, "Due", "TEXT").status)

        for (n in 4..20) assertEquals(201, add(editor, "project", projectId, "속성 $n", "TEXT").status)
        assertEquals(422, add(editor, "project", projectId, "하나 더", "TEXT").status)
    }

    @Test
    fun `값이 형식에 맞지 않으면 거부한다`() {
        fun rejected(
            kind: String,
            value: String,
        ) = assertEquals(422, add(editor, "issue", issueId, "x$kind${value.hashCode()}", kind, value).status, "$kind $value")

        rejected("NUMBER", "\"abc\"")
        rejected("NUMBER", "1000000000000")
        rejected("NUMBER", "true")
        rejected("DATE", "\"2026-13-40\"")
        rejected("DATE", "\"내일\"")
        rejected("DATE", "20261101")
        rejected("CHECKBOX", "\"yes\"")
        rejected("LINK", "\"ftp://example.com\"")
        rejected("LINK", "\"javascript:alert(1)\"")
        rejected("LINK", "\"example.com\"")
        rejected("LINK", "\"https://\"")
        rejected("TEXT", "\"${"가".repeat(501)}\"")
        rejected("TEXT", "5")
        rejected("PERSON", "\"not-an-id\"")
        // Someone who is not in the project cannot be named; someone who is, can.
        rejected("PERSON", "\"$outsiderId\"")
        rejected("PERSON", "\"00000000-0000-0000-0000-000000000000\"")
        assertEquals(emptyList<Any?>(), items(admin.get(path("issue", issueId)).json()))

        // An empty value is no value.
        assertEquals(201, add(editor, "issue", issueId, "빈 날짜", "DATE", "\"\"").status)
        assertNull(items(admin.get(path("issue", issueId)).json()).single()["value"])
    }

    @Test
    fun `프로젝트에 없는 티켓에서는 활성 사용자 누구나 사람으로 고를 수 있다`() {
        val alone = admin.post("/api/v1/issues", """{"title":"혼자 있는 티켓"}""").json()["id"] as String
        assertEquals(201, add(admin, "issue", alone, "확인자", "PERSON", "\"$outsiderId\"").status)
        // Who may edit an issue on its own is anyone; a person from outside shows by their id.
        assertEquals(201, add(outsider, "issue", alone, "메모", "TEXT", "\"봤습니다\"").status)
    }

    @Test
    fun `보기 역할은 읽기만 하고 프로젝트 밖의 사람은 볼 수 없다`() {
        val id = add(editor, "project", projectId, "배포예정일", "DATE", "\"2026-11-01\"").json()["id"] as String
        add(editor, "issue", issueId, "비고", "TEXT", "\"a\"")

        for ((type, target) in targets) {
            val read = viewer.get(path(type, target))
            assertEquals(200, read.status)
            assertEquals(false, read.json()["canEdit"])
            assertEquals(1, items(read.json()).size)
            assertEquals(403, add(viewer, type, target, "새 속성", "TEXT").status)
            assertEquals(404, outsider.get(path(type, target)).status)
            assertEquals(404, add(outsider, type, target, "새 속성", "TEXT").status)
        }
        assertEquals(403, viewer.patch("/api/v1/custom-properties/$id", """{"value":"2026-12-01"}""").status)
        assertEquals(403, viewer.delete("/api/v1/custom-properties/$id").status)
        assertEquals(404, outsider.patch("/api/v1/custom-properties/$id", """{"value":"2026-12-01"}""").status)
        assertEquals(404, outsider.delete("/api/v1/custom-properties/$id").status)
        assertEquals("2026-11-01", items(admin.get(path("project", projectId)).json()).single()["value"])
    }

    @Test
    fun `알 수 없는 종류나 없는 항목은 찾을 수 없다`() {
        assertEquals(404, admin.get(path("document", projectId)).status)
        assertEquals(404, admin.get(path("project", "00000000-0000-0000-0000-000000000000")).status)
        assertEquals(404, admin.get(path("issue", "00000000-0000-0000-0000-000000000000")).status)
        // Milestones are gone (D-189): the kind is unknown now.
        assertEquals(404, admin.get(path("milestone", projectId)).status)
    }

    @Test
    fun `삭제한 티켓은 속성을 읽을 수만 있고 복원하면 다시 고칠 수 있다`() {
        add(editor, "issue", issueId, "비고", "TEXT", "\"a\"")
        assertEquals(204, editor.delete("/api/v1/issues/1").status)

        val read = editor.get(path("issue", issueId)).json()
        assertEquals(false, read["canEdit"])
        assertEquals(1, items(read).size)
        assertEquals(403, add(editor, "issue", issueId, "새 속성", "TEXT").status)

        editor.post("/api/v1/issues/1/restore")
        assertEquals(201, add(editor, "issue", issueId, "새 속성", "TEXT").status)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `추가 변경 이름 바꾸기 삭제가 프로젝트와 티켓의 활동에 남는다`() {
        for ((type, id) in targets.filter { it.first != "issue" }) {
            val property = add(editor, type, id, "배포예정일", "DATE", "\"2026-11-01\"").json()["id"] as String
            editor.patch("/api/v1/custom-properties/$property", """{"value":"2026-12-01"}""")
            editor.patch("/api/v1/custom-properties/$property", """{"label":"출시일"}""")
            editor.delete("/api/v1/custom-properties/$property")

            val feed = admin.get("/api/v1/content/$type/$id/feed").json()
            val details =
                (feed["items"] as List<Map<String, Any?>>)
                    .filter {
                        it["type"] == "CUSTOM_PROPERTY_CHANGED"
                    }.map { it["detail"] as Map<String, Any?> }
            // The oldest first, as the server keeps them; the page shows the newest on top.
            assertEquals(listOf("ADDED", "CHANGED", "RENAMED", "REMOVED"), details.map { it["op"] })
            assertEquals(mapOf("op" to "ADDED", "label" to "배포예정일", "to" to "2026-11-01"), details.first())
            assertEquals(mapOf("op" to "CHANGED", "label" to "배포예정일", "from" to "2026-11-01", "to" to "2026-12-01"), details[1])
            assertEquals(mapOf("op" to "RENAMED", "label" to "출시일", "from" to "배포예정일", "to" to "출시일"), details[2])
            assertEquals(mapOf("op" to "REMOVED", "label" to "출시일", "from" to "2026-12-01"), details.last())
        }

        val property = add(editor, "issue", issueId, "배포예정일", "DATE", "\"2026-11-01\"").json()["id"] as String
        editor.patch("/api/v1/custom-properties/$property", """{"value":"2026-12-01"}""")
        // Saving the same value again says nothing.
        editor.patch("/api/v1/custom-properties/$property", """{"value":"2026-12-01"}""")
        editor.delete("/api/v1/custom-properties/$property")
        val entries = (admin.get("/api/v1/issues/1/activity").json()["items"] as List<Map<String, Any?>>).filter { it["type"] == "CUSTOM_PROPERTY_CHANGED" }
        assertEquals(listOf("ADDED", "CHANGED", "REMOVED"), entries.map { (it["detail"] as Map<String, Any?>)["op"] })
        assertTrue(entries.all { (it["actor"] as Map<String, Any?>)["loginId"] == "editor" })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `사람과 체크박스는 읽을 수 있는 말로 활동에 남는다`() {
        val person = add(editor, "project", projectId, "리뷰어", "PERSON").json()["id"] as String
        val box = add(editor, "project", projectId, "검수", "CHECKBOX").json()["id"] as String
        editor.patch("/api/v1/custom-properties/$person", """{"value":"$editorId"}""")
        editor.patch("/api/v1/custom-properties/$box", """{"value":true}""")

        val feed = admin.get("/api/v1/content/project/$projectId/feed").json()
        val changed =
            (feed["items"] as List<Map<String, Any?>>)
                .filter { it["type"] == "CUSTOM_PROPERTY_CHANGED" }
                .map { it["detail"] as Map<String, Any?> }
                .filter { it["op"] == "CHANGED" }
        assertTrue(changed.contains(mapOf("op" to "CHANGED", "label" to "리뷰어", "from" to null, "to" to "editor")))
        assertTrue(changed.contains(mapOf("op" to "CHANGED", "label" to "검수", "from" to "아니요", "to" to "예")))
    }
}
