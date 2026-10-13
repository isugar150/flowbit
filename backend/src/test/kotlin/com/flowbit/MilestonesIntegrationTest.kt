package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val PASSWORD = "correct-horse-battery"
private const val DOCUMENT = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"결제 화면은 끝났고 환불이 남았다"}]}]}"""

class MilestonesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var viewer: ApiClient
    private lateinit var milestones: String
    private lateinit var milestone: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val viewerId = admin.post("/api/v1/admin/members", """{"loginId":"viewer","password":"$PASSWORD"}""").json()["id"]
        val project = admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"]
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
}
