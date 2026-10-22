package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val DOCUMENT = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"결제 화면은 끝났고 환불이 남았다"}]}]}"""

class MilestonesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var viewer: ApiClient
    private lateinit var project: Any
    private lateinit var milestones: String
    private lateinit var milestone: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val viewerId = admin.post("/api/v1/admin/members", """{"loginId":"viewer","password":"$PASSWORD"}""").json()["id"]
        project = checkNotNull(admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
        admin.put("/api/v1/projects/$project/members/$viewerId", """{"role":"VIEWER"}""")
        viewer = client().also { it.login("viewer", PASSWORD) }
        milestones = "/api/v1/projects/$project/milestones"
        milestone = "$milestones/${admin.post(milestones, """{"name":"베타"}""").json()["id"]}"
    }

    @Test
    fun `목표에 문서를 쓰고 지울 수 있고 목록에 함께 나온다`() {
        assertNull(admin.get(milestones).jsonList().single()["content"])

        val saved = admin.patch(milestone, """{"content":$DOCUMENT}""")

        assertEquals(200, saved.status)
        val stored = admin.get(milestones).jsonList().single()["content"] as Map<*, *>
        assertEquals("doc", stored["type"])
        // People who can only read the project read the document too.
        assertEquals("doc", (viewer.get(milestones).jsonList().single()["content"] as Map<*, *>)["type"])

        // Renaming leaves the document alone; null clears it.
        admin.patch(milestone, """{"name":"베타 출시"}""")
        assertEquals("doc", (admin.get(milestones).jsonList().single()["content"] as Map<*, *>)["type"])
        assertNull(admin.patch(milestone, """{"content":null}""").json()["content"])
    }

    @Test
    fun `보기 역할은 목표의 문서를 쓸 수 없고 문서가 아닌 값은 거부한다`() {
        assertEquals(403, viewer.patch(milestone, """{"content":$DOCUMENT}""").status)
        assertEquals(422, admin.patch(milestone, """{"content":{"type":"doc","content":[{"type":"script"}]}}""").status)
        assertEquals(422, admin.patch(milestone, """{"content":"글자만"}""").status)
        assertNull(admin.get(milestones).jsonList().single()["content"])
    }

    private fun member(
        loginId: String,
        role: String? = "EDITOR",
    ): Any? {
        val id = admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"]
        if (role != null) admin.put("/api/v1/projects/$project/members/$id", """{"role":"$role"}""")
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun logins(milestone: Map<String, Any?>): List<Any?> = (milestone["assignees"] as List<Map<String, Any?>>).map { it["loginId"] }

    @Suppress("UNCHECKED_CAST")
    private fun assigneeChanges(): List<Map<String, Any?>> =
        ((admin.get("/api/v1/content/milestone/${milestone.substringAfterLast('/')}/feed").json()["items"]) as List<Map<String, Any?>>)
            .filter { it["type"] == "ASSIGNEE_CHANGED" }
            .map { it["detail"] as Map<String, Any?> }

    @Test
    fun `목표에 담당자를 여러 명 두면 준 순서로 나오고 바꾼 것은 활동에 남는다`() {
        val first = member("first")
        val second = member("second")
        assertEquals(emptyList<Any?>(), logins(admin.get(milestones).jsonList().single()))

        val saved = admin.patch(milestone, """{"assigneeIds":["$second","$first"]}""")
        assertEquals(200, saved.status)
        assertEquals(listOf<Any?>("second", "first"), logins(saved.json()))
        // The people who only read the project see who has it.
        assertEquals(listOf<Any?>("second", "first"), logins(viewer.get(milestones).jsonList().single()))

        // Other changes leave the assignees alone.
        assertEquals(listOf<Any?>("second", "first"), logins(admin.patch(milestone, """{"name":"베타 출시"}""").json()))

        // Taking one off keeps the other; an empty list takes everyone off.
        assertEquals(listOf<Any?>("first"), logins(admin.patch(milestone, """{"assigneeIds":["$first"]}""").json()))
        assertEquals(emptyList<Any?>(), logins(admin.patch(milestone, """{"assigneeIds":[]}""").json()))

        val changes = assigneeChanges()
        assertEquals(3, changes.size)
        assertTrue(changes.contains(mapOf("added" to listOf("second", "first"), "removed" to emptyList<String>())))
        assertTrue(changes.contains(mapOf("added" to emptyList<String>(), "removed" to listOf("second"))))
        assertTrue(changes.contains(mapOf("added" to emptyList<String>(), "removed" to listOf("first"))))

        // Saving the same people again says nothing.
        admin.patch(milestone, """{"assigneeIds":["$first"]}""")
        admin.patch(milestone, """{"assigneeIds":["$first"]}""")
        assertEquals(4, assigneeChanges().size)
    }

    @Test
    fun `목표의 담당자는 이 프로젝트의 구성원만 열 명까지 지정할 수 있고 보기 역할은 바꿀 수 없다`() {
        val outsider = member("outsider", role = null)
        val inside = member("inside")
        assertEquals(422, admin.patch(milestone, """{"assigneeIds":["$outsider"]}""").status)
        assertEquals(422, admin.patch(milestone, """{"assigneeIds":["00000000-0000-0000-0000-000000000000"]}""").status)
        assertEquals(422, admin.patch(milestone, """{"assigneeIds":"$inside"}""").status)
        assertEquals(422, admin.patch(milestone, """{"assigneeIds":["not-an-id"]}""").status)
        val many = (1..11).joinToString(",") { "\"${member("many$it")}\"" }
        assertEquals(422, admin.patch(milestone, """{"assigneeIds":[$many]}""").status)
        assertEquals(403, viewer.patch(milestone, """{"assigneeIds":["$inside"]}""").status)
        assertEquals(emptyList<Any?>(), logins(admin.get(milestones).jsonList().single()))

        // A repeated id counts once, and ten people fit.
        val nine = (1..9).joinToString(",") { "\"${member("nine$it")}\"" }
        val saved = admin.patch(milestone, """{"assigneeIds":[$nine,"$inside","$inside"]}""")
        assertEquals(200, saved.status)
        assertEquals(10, logins(saved.json()).size)
    }
}
