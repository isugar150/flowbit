package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class AuditLogIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
        admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"member-first-password","displayName":"김지민"}""")
        admin.post("/api/v1/projects", """{"name":"결제"}""")
    }

    @Test
    fun `감사 로그는 최근 것부터 하는 사람과 함께 쪽으로 나누어 보인다`() {
        val page = admin.get("/api/v1/admin/audit?limit=2&offset=0").json()

        assertEquals(2, (page["items"] as List<*>).size)
        assertTrue((page["total"] as Number).toInt() >= 3)
        @Suppress("UNCHECKED_CAST")
        val first = (page["items"] as List<Map<String, Any?>>)[0]
        assertEquals("PROJECT_CREATED", first["type"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("admin", (first["actor"] as Map<String, Any?>)["loginId"])

        val rest = admin.get("/api/v1/admin/audit?limit=2&offset=2").json()
        assertTrue((rest["items"] as List<*>).isNotEmpty())
    }

    @Test
    fun `종류와 하는 사람으로 거를 수 있고 모든 종류의 목록도 받는다`() {
        val created = admin.get("/api/v1/admin/audit?type=MEMBER_CREATED").json()

        assertEquals(1, (created["total"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val entry = (created["items"] as List<Map<String, Any?>>).single()
        @Suppress("UNCHECKED_CAST")
        assertTrue((entry["detail"] as Map<String, String>).containsKey("memberId"))
        @Suppress("UNCHECKED_CAST")
        assertTrue("MEMBER_PASSWORD_CHANGED" in (created["types"] as List<String>))

        val byActor = admin.get("/api/v1/admin/audit?actor=ADM").json()
        assertTrue((byActor["total"] as Number).toInt() >= 3)
        assertEquals(0, (admin.get("/api/v1/admin/audit?actor=nobody").json()["total"] as Number).toInt())
    }

    @Test
    fun `날짜로 거르면 그날 안의 기록만 나온다`() {
        val today =
            java.time.LocalDate
                .now(java.time.ZoneId.of("Asia/Seoul"))
                .toString()

        assertTrue((admin.get("/api/v1/admin/audit?from=$today&to=$today").json()["total"] as Number).toInt() >= 3)
        assertEquals(0, (admin.get("/api/v1/admin/audit?to=2000-01-01").json()["total"] as Number).toInt())
    }
}
