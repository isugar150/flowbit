package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val PASSWORD = "correct-horse-battery"

/** 관리자 설정 > 프로젝트 (D-236): the settings of a project the administrator was not added to. */
class AdminProjectSettingsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var owner: ApiClient
    private lateinit var outsider: ApiClient
    private lateinit var outsiderId: String

    @BeforeEach
    fun createPeopleAndProject() {
        admin = client().also { it.setup("admin", PASSWORD) }
        createMember("owner")
        outsiderId = createMember("outsider")
        owner = client().also { it.login("owner", PASSWORD) }
        outsider = client().also { it.login("outsider", PASSWORD) }
        remember("PAY", owner.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"])
    }

    private val base get() = "/api/v1/admin/projects/${p("PAY")}"

    @Test
    fun `관리자는 구성원이 아닌 프로젝트의 이름과 공개 범위와 추정치와 트리아지를 관리자 화면에서 바꾼다`() {
        // Listed like for anyone; the administrator is not a member and does not become one.
        val listed = admin.get("/api/v1/projects").jsonList().single()
        assertEquals(false, listed["member"])

        val changed = admin.patch(base, """{"name":"결제 재개편","openToAll":true,"estimatesEnabled":true,"triageEnabled":true}""")
        assertEquals(200, changed.status)
        assertEquals("결제 재개편", changed.json()["name"])
        assertEquals(true, changed.json()["openToAll"])
        assertEquals(true, changed.json()["estimatesEnabled"])
        assertEquals(true, changed.json()["triageEnabled"])
        // The overview is the project's own data: it is not in the answer.
        assertNull(changed.json()["content"])

        val seen = owner.get("/api/v1/projects/${p("PAY")}").json()
        assertEquals("결제 재개편", seen["name"])
        assertEquals(true, seen["openToAll"])
        assertEquals(true, seen["estimatesEnabled"])
        assertEquals(true, seen["triageEnabled"])
        // Opened to everyone: the other member of the workspace is in, as an editor.
        assertEquals("EDITOR", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
        assertEquals(false, admin.get("/api/v1/projects").jsonList().single()["member"])

        assertEquals(false, admin.patch(base, """{"openToAll":false}""").json()["openToAll"])
        assertEquals(
            "estimatesEnabled,name,openToAll,triageEnabled",
            text("select detail ->> 'fields' from audit_events where event_type = 'PROJECT_ADMIN_CHANGED' and detail ->> 'fields' like '%name%'"),
        )
        assertEquals(2, count("select count(*) from audit_events where event_type = 'PROJECT_ADMIN_CHANGED'"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'PROJECT_ACCESS_CHANGED' and detail ->> 'openToAll' = 'true'"))
    }

    @Test
    fun `관리자는 구성원이 아닌 프로젝트의 구성원과 접근 요청을 관리자 화면에서 정한다`() {
        createMember("third")
        val third = client().also { it.login("third", PASSWORD) }
        assertEquals(200, outsider.post("/api/v1/projects/${p("PAY")}/access-requests").status)
        assertEquals(200, third.post("/api/v1/projects/${p("PAY")}/access-requests").status)

        assertEquals(listOf("outsider", "third"), admin.get("$base/access-requests").jsonList().map { it["loginId"] })
        val approved = admin.post("$base/access-requests/$outsiderId/approve", """{"role":"EDITOR"}""")
        assertEquals(200, approved.status)
        assertEquals(listOf("owner" to "MANAGER", "outsider" to "EDITOR"), approved.jsonList().map { it["loginId"] to it["role"] })
        assertEquals("EDITOR", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])

        val thirdId = admin.get("$base/access-requests").jsonList().single()["userId"]
        assertEquals(204, admin.delete("$base/access-requests/$thirdId").status)
        assertEquals(emptyList(), admin.get("$base/access-requests").jsonList())

        val demoted = admin.put("$base/members/$outsiderId", """{"role":"VIEWER"}""")
        assertEquals(200, demoted.status)
        assertEquals("VIEWER", outsider.get("/api/v1/projects/${p("PAY")}").json()["myRole"])
        assertEquals(listOf("owner" to "MANAGER", "outsider" to "VIEWER"), admin.get("$base/members").jsonList().map { it["loginId"] to it["role"] })

        assertEquals(204, admin.delete("$base/members/$outsiderId").status)
        assertEquals(listOf("owner"), admin.get("$base/members").jsonList().map { it["loginId"] })
        assertEquals(404, outsider.get("/api/v1/projects/${p("PAY")}").status)
        // The administrator still is not in the project.
        assertEquals(false, admin.get("/api/v1/projects").jsonList().single()["member"])

        assertEquals(4, count("select count(*) from audit_events where event_type = 'PROJECT_ADMIN_CHANGED'"))
        assertEquals(
            setOf("access-approved", "access-rejected", "member", "member-removed"),
            text("select string_agg(distinct detail ->> 'change', ',') from audit_events where event_type = 'PROJECT_ADMIN_CHANGED'").split(",").toSet(),
        )
    }

    @Test
    fun `관리자는 구성원이 아닌 프로젝트의 사이클과 Git 저장소와 아이콘을 관리자 화면에서 정한다`() {
        val cycles = admin.put("$base/cycles/settings", """{"enabled":true,"weeks":2,"startDay":1}""")
        assertEquals(200, cycles.status)
        assertNotNull(cycles.json()["current"])
        assertEquals(true, (admin.get("$base/cycles").json()["settings"] as Map<*, *>)["enabled"])
        assertEquals(true, (owner.get("/api/v1/projects/${p("PAY")}/cycles").json()["settings"] as Map<*, *>)["enabled"])

        val connection =
            admin
                .post(
                    "/api/v1/admin/git/connections",
                    """{"name":"Gitea","enabled":true,"provider":"GITEA","baseUrl":"https://git.example.com/","autoInProgress":true,"autoDone":true}""",
                ).json()
        val repositories = admin.put("$base/git/repositories", """{"repositories":[{"connectionId":"${connection["id"]}","repository":"acme/pay"}]}""")
        assertEquals(200, repositories.status)
        assertEquals(listOf("acme/pay"), (repositories.json()["repositories"] as List<*>).map { (it as Map<*, *>)["repository"] })
        assertEquals(1, (admin.get("$base/git").json()["repositories"] as List<*>).size)
        assertEquals(1, (owner.get("/api/v1/projects/${p("PAY")}/git").json()["repositories"] as List<*>).size)

        val icon = admin.putFile("$base/icon", "icon.png", "image/png", picture())
        assertEquals(200, icon.status)
        assertNotNull(icon.json()["iconUrl"])
        assertNull(icon.json()["content"])
        assertNotNull(owner.get("/api/v1/projects/${p("PAY")}").json()["iconUrl"])
        assertNull(admin.delete("$base/icon").json()["iconUrl"])

        assertEquals(
            setOf("cycles", "git", "icon"),
            text("select string_agg(distinct detail ->> 'change', ',') from audit_events where event_type = 'PROJECT_ADMIN_CHANGED'").split(",").toSet(),
        )
    }

    @Test
    fun `관리자는 관리자 화면에서 프로젝트를 아카이브하고 복원한다`() {
        assertEquals(204, admin.post("$base/archive", "{}").status)
        assertEquals(emptyList(), admin.get("/api/v1/projects").jsonList())
        assertEquals(listOf(p("PAY")), admin.get("/api/v1/projects?archived=true").jsonList().map { it["id"] })

        val restored = admin.post("$base/restore", "{}")
        assertEquals(200, restored.status)
        assertNull(restored.json()["archivedAt"])
        assertEquals(listOf(p("PAY")), admin.get("/api/v1/projects").jsonList().map { it["id"] })

        // Archiving and restoring are recorded as they are for a project's own managers.
        assertEquals(1, count("select count(*) from audit_events where event_type = 'PROJECT_ARCHIVED' and actor_user_id is not null"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'PROJECT_RESTORED' and actor_user_id is not null"))
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String

    private fun picture(): ByteArray {
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(43, 99, 227)
        graphics.fillRect(0, 0, 300, 300)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
