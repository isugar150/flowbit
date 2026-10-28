package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val PASSWORD = "correct-horse-battery"

class SavedViewsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var member: ApiClient

    @BeforeEach
    fun signIn() {
        admin = client().also { it.setup("admin", PASSWORD) }
        admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$PASSWORD"}""")
        member = client().also { it.login("jimin", PASSWORD) }
    }

    @Test
    fun `저장한 뷰는 만든 사람에게 보이고 공유한 뷰는 모두에게 보인다`() {
        val mine = member.post("/api/v1/views", """{"name":" 내 급한 일 ","path":"/my-issues","query":"?priority=URGENT&view=board"}""")
        member.post("/api/v1/views", """{"name":"이번 주 마감","path":"/issues","query":"due=week","shared":true}""")

        assertEquals(201, mine.status)
        assertEquals("내 급한 일", mine.json()["name"])
        assertEquals("priority=URGENT&view=board", mine.json()["query"])
        assertEquals(true, mine.json()["mine"])
        assertEquals(listOf("내 급한 일", "이번 주 마감"), member.get("/api/v1/views").jsonList().map { it["name"] })
        // The administrator sees only the shared one, and it is not theirs.
        val seen = admin.get("/api/v1/views").jsonList()
        assertEquals(listOf("이번 주 마감"), seen.map { it["name"] })
        assertEquals(false, seen.single()["mine"])
    }

    @Test
    fun `만든 사람은 뷰의 이름 조건 공유를 바꾸고 지울 수 있다`() {
        val id = member.post("/api/v1/views", """{"name":"뷰","path":"/issues","query":"status=READY"}""").json()["id"]

        val changed = member.patch("/api/v1/views/$id", """{"name":"준비된 일","query":"status=READY&group=priority","shared":true}""")

        assertEquals("준비된 일", changed.json()["name"])
        assertEquals("status=READY&group=priority", changed.json()["query"])
        assertEquals(true, changed.json()["shared"])
        assertEquals(204, member.delete("/api/v1/views/$id").status)
        assertEquals(0, member.get("/api/v1/views").jsonList().size)
    }

    @Test
    fun `관리자는 다른 사람이 공유한 뷰를 지울 수 있다`() {
        val id = member.post("/api/v1/views", """{"name":"공유 뷰","path":"/issues","shared":true}""").json()["id"]

        assertEquals(204, admin.delete("/api/v1/views/$id").status)
        assertEquals(0, member.get("/api/v1/views").jsonList().size)
    }
}
