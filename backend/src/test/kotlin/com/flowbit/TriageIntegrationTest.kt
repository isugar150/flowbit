package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class TriageIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var requester: ApiClient

    @BeforeEach
    fun createProject() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val requesterId = admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$PASSWORD"}""").json()["id"]
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$requesterId", """{"role":"EDITOR"}""")
        requester = client().also { it.login("jimin", PASSWORD) }
    }

    @Test
    fun `트리아지를 켠 프로젝트에서 관리 역할이 아닌 사람이 만든 티켓은 대기열에 들어간다`() {
        assertEquals(true, admin.patch("/api/v1/projects/${p("PAY")}", """{"triageEnabled":true}""").json()["triageEnabled"])

        val asked = requester.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제가 안 돼요"}""")
        val own = admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"관리자가 만든 티켓"}""")

        assertEquals(true, asked.json()["triage"])
        assertEquals(false, own.json()["triage"])
        assertEquals(listOf("FB-1"), admin.get("/api/v1/triage").jsonList().map { it["key"] })
        // The one who asked is not a manager: the queue is not theirs to sort.
        assertEquals(emptyList(), requester.get("/api/v1/triage").jsonList())
    }

    @Test
    fun `관리 역할인 사람은 티켓을 만들 때 트리아지로 보낼지 고른다`() {
        admin.patch("/api/v1/projects/${p("PAY")}", """{"triageEnabled":true}""")

        val straight = admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"바로 올릴 티켓","triage":false}""")
        val sent = admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"트리아지로 보낼 티켓","triage":true}""")

        assertEquals(false, straight.json()["triage"])
        assertEquals(true, sent.json()["triage"])
        assertEquals(listOf("FB-2"), admin.get("/api/v1/triage").jsonList().map { it["key"] })
    }

    @Test
    fun `관리 역할이 수락하면 대기열에서 빠지고 거절하면 취소된다`() {
        admin.patch("/api/v1/projects/${p("PAY")}", """{"triageEnabled":true}""")
        requester.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"수락할 티켓"}""")
        requester.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"거절할 티켓"}""")

        val accepted = admin.patch("/api/v1/issues/1", """{"triage":false,"priority":"HIGH"}""")
        val declined = admin.patch("/api/v1/issues/2", """{"triage":false,"status":"CANCELED","triageRejected":true}""")

        assertEquals(false, accepted.json()["triage"])
        assertEquals("HIGH", accepted.json()["priority"])
        assertEquals("CANCELED", declined.json()["status"])
        assertEquals(listOf("FB-2"), admin.get("/api/v1/triage?rejected=true").jsonList().map { it["key"] })
        assertEquals("거절할 티켓", requester.get("/api/v1/issues/2").json()["title"])
        assertEquals(emptyList(), admin.get("/api/v1/triage").jsonList())
    }

    @Test
    fun `트리아지를 켜지 않은 프로젝트의 티켓은 대기열에 들어가지 않는다`() {
        val asked = requester.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"그냥 티켓"}""")

        assertEquals(false, asked.json()["triage"])
        assertEquals(emptyList(), admin.get("/api/v1/triage").jsonList())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `새 요청은 구독하지 않은 모든 프로젝트 관리 역할에게 분류 요청 알림을 보낸다`() {
        val managerId = admin.post("/api/v1/admin/members", """{"loginId":"manager","password":"$PASSWORD"}""").json()["id"]
        admin.put("/api/v1/projects/${p("PAY")}/members/$managerId", """{"role":"MANAGER"}""")
        val manager = client().also { it.login("manager", PASSWORD) }
        admin.patch("/api/v1/projects/${p("PAY")}", """{"triageEnabled":true}""")
        requester.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"분류할 새 요청"}""")

        for (recipient in listOf(admin, manager)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                val items = recipient.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
                val request = items.single { (it["headline"] as String).contains("분류를 요청") }
                assertEquals("[FB-1] 분류할 새 요청", request["title"])
                assertEquals("/issue/FB-1", request["path"])
                assertTrue((request["lines"] as List<String>).single().contains("분류해 주세요"))
            }
        }
        assertEquals(0, requester.get("/api/v1/me/inbox").json()["unreadCount"])
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"관리자의 요청","triage":true}""")
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val items = manager.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
            assertEquals(2, items.count { (it["headline"] as String).contains("분류를 요청") })
        }
        val own = admin.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
        assertEquals(1, own.count { (it["headline"] as String).contains("분류를 요청") })
    }
}
