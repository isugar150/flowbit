package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val PASSWORD = "correct-horse-battery"

class ProjectsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var owner: ApiClient
    private lateinit var ownerId: String
    private lateinit var outsider: ApiClient
    private lateinit var outsiderId: String

    @BeforeEach
    fun createPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        ownerId = createMember("owner")
        outsiderId = createMember("outsider")
        owner = client().also { it.login("owner", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
    }

    @Test
    fun `프로젝트를 만들면 만든 사람이 관리 역할이 된다`() {
        val created = owner.post("/api/v1/projects", """{"name":" 결제 개편 ","description":"결제 흐름을 다시 만든다"}""")
        remember("PAY", created.json()["id"])

        assertEquals(201, created.status)
        assertNull(created.json()["key"])
        assertEquals("결제 개편", created.json()["name"])
        assertEquals("MANAGER", created.json()["myRole"])
        assertEquals(1, created.json()["memberCount"])

        val members = owner.get("/api/v1/projects/${p("PAY")}/members").jsonList()
        assertEquals(listOf("owner" to "MANAGER"), members.map { it["loginId"] to it["role"] })
        assertEquals(listOf(p("PAY")), owner.get("/api/v1/projects").jsonList().map { it["id"] })
    }

    @Test
    fun `같은 이름의 프로젝트도 만들 수 있고 이름이 비면 만들 수 없다`() {
        createProject("PAY")

        val sameName = owner.post("/api/v1/projects", """{"name":"PAY 프로젝트"}""")
        assertEquals(201, sameName.status)
        assertNotEquals(p("PAY"), sameName.json()["id"])
        assertEquals(422, owner.post("/api/v1/projects", """{"name":"  "}""").status)
    }

    @Test
    fun `프로젝트 구성원이 아닌 사람은 목록에서 볼 수 있지만 들어갈 수 없고 관리자를 안내받는다`() {
        createProject("PAY")

        val listed = outsider.get("/api/v1/projects").jsonList()
        assertEquals(listOf(p("PAY")), listed.map { it["id"] })
        assertEquals(null, listed.single()["myRole"])
        val entry = outsider.get("/api/v1/projects/${p("PAY")}/access")
        assertEquals(200, entry.status)
        assertEquals(null, entry.json()["myRole"])
        @Suppress("UNCHECKED_CAST")
        val managers = entry.json()["managers"] as List<Map<String, Any?>>
        assertEquals(listOf("owner"), managers.map { it["loginId"] })
        assertEquals(404, outsider.get("/api/v1/projects/00000000-0000-7000-8000-000000000001/access").status)

        val response = outsider.get("/api/v1/projects/${p("PAY")}")
        assertEquals(404, response.status)
        assertEquals("PROJECT_NOT_FOUND", response.json()["code"])
        assertEquals(404, outsider.get("/api/v1/projects/${p("PAY")}/members").status)
        assertEquals(404, outsider.patch("/api/v1/projects/${p("PAY")}", """{"name":"빼앗기"}""").status)
        // The same answer as for a project that does not exist at all.
        assertEquals(response.json()["title"], outsider.get("/api/v1/projects/NOPE").json()["title"])
    }

    @Test
    fun `워크스페이스 관리자도 구성원이 아닌 프로젝트에는 들어갈 수 없고 관리자 화면에서만 권한을 정한다`() {
        createProject("PAY")

        // Listed like for anyone, but not entered: an administrator is not a member of every project (D-131).
        val listed = admin.get("/api/v1/projects").jsonList().single()
        assertEquals(p("PAY"), listed["id"])
        assertNull(listed["myRole"])
        assertEquals(false, listed["member"])
        assertEquals(404, admin.get("/api/v1/projects/${p("PAY")}").status)
        assertEquals(404, admin.patch("/api/v1/projects/${p("PAY")}", """{"name":"새 이름"}""").status)
        assertEquals(404, admin.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"VIEWER"}""").status)
        assertNull(admin.get("/api/v1/projects/${p("PAY")}/access").json()["myRole"])

        // The administrator screen sets anyone's role in any project.
        assertEquals(204, admin.put("/api/v1/admin/members/$outsiderId/projects/${p("PAY")}", """{"role":"VIEWER"}""").status)
        assertEquals("VIEWER", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
        assertEquals(204, admin.delete("/api/v1/admin/members/$outsiderId/projects/${p("PAY")}").status)
        assertEquals(404, outsider.get("/api/v1/projects/${p("PAY")}").status)
        assertEquals(403, outsider.put("/api/v1/admin/members/$outsiderId/projects/${p("PAY")}", """{"role":"MANAGER"}""").status)
    }

    @Test
    fun `구성원으로 추가되면 프로젝트가 보이고 역할에 따라 할 수 있는 일이 달라진다`() {
        createProject("PAY")

        val added = owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"VIEWER"}""")
        assertEquals(200, added.status)
        assertEquals(2, added.jsonList().size)

        assertEquals("VIEWER", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
        assertEquals(200, outsider.get("/api/v1/projects/${p("PAY")}/members").status)
        assertEquals(403, outsider.patch("/api/v1/projects/${p("PAY")}", """{"name":"바꾸기"}""").status)
        assertEquals(403, outsider.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"MANAGER"}""").status)

        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"EDITOR"}""")
        assertEquals("EDITOR", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
        assertEquals(403, outsider.delete("/api/v1/projects/${p("PAY")}/members/$ownerId").status)

        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"MANAGER"}""")
        assertEquals("함께 관리", outsider.patch("/api/v1/projects/${p("PAY")}", """{"name":"함께 관리"}""").json()["name"])
    }

    @Test
    fun `구성원을 내보내면 프로젝트가 다시 보이지 않는다`() {
        createProject("PAY")
        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"EDITOR"}""")

        assertEquals(204, owner.delete("/api/v1/projects/${p("PAY")}/members/$outsiderId").status)

        assertEquals(404, outsider.get("/api/v1/projects/${p("PAY")}").status)
    }

    @Test
    fun `마지막 관리 역할은 내보내거나 낮출 수 없다`() {
        createProject("PAY")

        val remove = owner.delete("/api/v1/projects/${p("PAY")}/members/$ownerId")
        val demote = owner.put("/api/v1/projects/${p("PAY")}/members/$ownerId", """{"role":"EDITOR"}""")

        assertEquals(409, remove.status)
        assertEquals("LAST_MANAGER", remove.json()["code"])
        assertEquals(409, demote.status)

        // Once someone else manages the project, the first manager can step down.
        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"MANAGER"}""")
        assertEquals(200, owner.put("/api/v1/projects/${p("PAY")}/members/$ownerId", """{"role":"VIEWER"}""").status)
        assertEquals("VIEWER", owner.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
    }

    @Test
    fun `비활성 구성원이나 없는 사람은 프로젝트에 추가할 수 없다`() {
        createProject("PAY")
        admin.patch("/api/v1/admin/members/$outsiderId", """{"status":"DISABLED"}""")

        val disabled = owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"VIEWER"}""")
        val missing = owner.put("/api/v1/projects/${p("PAY")}/members/00000000-0000-7000-8000-000000000009", """{"role":"VIEWER"}""")

        assertEquals(404, disabled.status)
        assertEquals("MEMBER_NOT_FOUND", disabled.json()["code"])
        assertEquals(404, missing.status)
    }

    @Test
    fun `비활성화된 구성원은 프로젝트 구성원 목록에 나오지 않고 다시 활성화하면 돌아온다`() {
        createProject("PAY")
        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"EDITOR"}""")
        assertEquals(listOf("owner", "outsider"), owner.get("/api/v1/projects/${p("PAY")}/members").jsonList().map { it["loginId"] })

        admin.patch("/api/v1/admin/members/$outsiderId", """{"status":"DISABLED"}""")

        assertEquals(listOf("owner"), owner.get("/api/v1/projects/${p("PAY")}/members").jsonList().map { it["loginId"] })
        admin.patch("/api/v1/admin/members/$outsiderId", """{"status":"ACTIVE"}""")
        assertEquals(listOf("owner", "outsider"), owner.get("/api/v1/projects/${p("PAY")}/members").jsonList().map { it["loginId"] })
    }

    @Test
    fun `비활성화된 관리 역할은 마지막 관리자를 셀 때 세지 않는다`() {
        createProject("PAY")
        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"MANAGER"}""")
        admin.patch("/api/v1/admin/members/$outsiderId", """{"status":"DISABLED"}""")

        // The only manager who can sign in cannot step down, though a disabled one is still in the project.
        val demote = owner.put("/api/v1/projects/${p("PAY")}/members/$ownerId", """{"role":"VIEWER"}""")

        assertEquals(409, demote.status)
        assertEquals("LAST_MANAGER", demote.json()["code"])
    }

    @Test
    fun `이름과 설명을 고칠 수 있고 키는 바뀌지 않는다`() {
        createProject("PAY")

        val updated = owner.patch("/api/v1/projects/${p("PAY")}", """{"description":"새 설명"}""")

        assertEquals(200, updated.status)
        assertEquals(p("PAY"), updated.json()["id"])
        assertEquals("PAY 프로젝트", updated.json()["name"])
        assertEquals("새 설명", updated.json()["description"])
        assertEquals(422, owner.patch("/api/v1/projects/${p("PAY")}", """{"name":"${"가".repeat(81)}"}""").status)
    }

    @Test
    fun `업데이트는 쓴 사람과 관리 역할만 지울 수 있고 지우면 건강도가 앞의 것으로 돌아간다`() {
        createProject("PAY")
        val base = "/api/v1/projects/${p("PAY")}"
        owner.put("$base/members/$outsiderId", """{"role":"EDITOR"}""")
        val first = owner.post("$base/updates", """{"health":"ON_TRACK","body":"순조롭다"}""").json()["id"]
        val second = outsider.post("$base/updates", """{"health":"AT_RISK"}""").json()["id"]
        assertEquals("AT_RISK", owner.get(base).json()["health"])

        // An editor may take back their own update, not someone else's.
        assertEquals(403, outsider.delete("$base/updates/$first").status)
        assertEquals(204, outsider.delete("$base/updates/$second").status)
        assertEquals("ON_TRACK", owner.get(base).json()["health"])
        assertEquals(404, owner.delete("$base/updates/$second").status)

        // A manager may remove anyone's; a viewer none, not even their own.
        val third = outsider.post("$base/updates", """{"health":"OFF_TRACK"}""").json()["id"]
        owner.put("$base/members/$outsiderId", """{"role":"VIEWER"}""")
        assertEquals(403, outsider.delete("$base/updates/$third").status)
        assertEquals(204, owner.delete("$base/updates/$third").status)
        assertEquals(listOf(first), owner.get("$base/updates").jsonList().map { it["id"] })
    }

    @Test
    fun `아카이브한 프로젝트는 목록과 티켓 목록에서 빠지고 아카이브 목록에 나오며 복원하면 돌아온다`() {
        createProject("PAY")
        createProject("OPS")
        val issue = owner.post("/api/v1/issues", """{"title":"결제 오류","projectId":"${p("PAY")}"}""")
        assertEquals(201, issue.status)

        assertEquals(204, owner.post("/api/v1/projects/${p("PAY")}/archive", "{}").status)

        assertEquals(listOf(p("OPS")), owner.get("/api/v1/projects").jsonList().map { it["id"] })
        val archived = outsider.get("/api/v1/projects?archived=true").jsonList()
        assertEquals(listOf(p("PAY")), archived.map { it["id"] })
        assertEquals(true, archived.single()["archivedAt"] != null)
        assertEquals(null, archived.single()["myRole"])
        assertEquals(404, owner.get("/api/v1/projects/${p("PAY")}").status)
        assertEquals(emptyList(), owner.get("/api/v1/issues").jsonList().map { it["title"] })
        assertEquals(404, owner.post("/api/v1/projects/${p("PAY")}/archive", "{}").status)

        val restored = owner.post("/api/v1/projects/${p("PAY")}/restore", "{}")
        assertEquals(200, restored.status)
        assertNull(restored.json()["archivedAt"])
        val listed = owner.get("/api/v1/projects").jsonList().map { it["id"] }
        assertEquals(setOf(p("PAY"), p("OPS")), listed.toSet())
        assertEquals(emptyList(), owner.get("/api/v1/projects?archived=true").jsonList())
        assertEquals(listOf("결제 오류"), owner.get("/api/v1/issues").jsonList().map { it["title"] })
        assertEquals(404, owner.post("/api/v1/projects/${p("PAY")}/restore", "{}").status)
    }

    @Test
    fun `아카이브와 복원은 관리 역할만 할 수 있다`() {
        createProject("PAY")
        owner.put("/api/v1/projects/${p("PAY")}/members/$outsiderId", """{"role":"EDITOR"}""")

        assertEquals(403, outsider.post("/api/v1/projects/${p("PAY")}/archive", "{}").status)
        assertEquals(204, owner.post("/api/v1/projects/${p("PAY")}/archive", "{}").status)
        assertEquals(403, outsider.post("/api/v1/projects/${p("PAY")}/restore", "{}").status)
        // A workspace administrator who is not one of its managers cannot either (D-131).
        assertEquals(403, admin.post("/api/v1/projects/${p("PAY")}/restore", "{}").status)
        assertEquals(200, owner.post("/api/v1/projects/${p("PAY")}/restore", "{}").status)
    }

    @Test
    fun `로그인하지 않으면 프로젝트를 볼 수 없다`() {
        createProject("PAY")

        assertEquals(401, client().get("/api/v1/projects").status)
        assertEquals(401, client().get("/api/v1/projects/${p("PAY")}").status)
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    private fun createProject(handle: String) {
        val created = owner.post("/api/v1/projects", """{"name":"$handle 프로젝트"}""")
        assertEquals(201, created.status)
        remember(handle, created.json()["id"])
    }
}
