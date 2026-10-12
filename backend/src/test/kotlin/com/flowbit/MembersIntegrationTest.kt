package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val MEMBER_PASSWORD = "member-first-password"
private const val NEW_PASSWORD = "member-second-password"

class MembersIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    @Test
    fun `관리자가 만든 구성원이 그 아이디와 비밀번호로 로그인할 수 있다`() {
        val created = createMember("Jimin", displayName = " 김지민 ")

        assertEquals(201, created.status)
        assertEquals("jimin", created.json()["loginId"])
        assertEquals("김지민", created.json()["displayName"])
        assertEquals("MEMBER", created.json()["role"])
        assertEquals("ACTIVE", created.json()["status"])

        val member = client()
        assertEquals(200, member.login("jimin", MEMBER_PASSWORD).status)
        assertEquals("MEMBER", member.me().json()["role"])
        assertEquals(1, count("select count(*) from audit_events where event_type = 'MEMBER_CREATED'"))
    }

    @Test
    fun `같은 아이디로는 구성원을 만들 수 없다`() {
        createMember("jimin")

        val duplicate = createMember("JIMIN")

        assertEquals(409, duplicate.status)
        assertEquals("LOGIN_ID_TAKEN", duplicate.json()["code"])
    }

    @Test
    fun `아이디와 비밀번호 규칙을 어기면 필드별 오류를 받는다`() {
        val response = admin.post("/api/v1/admin/members", """{"loginId":"a!","password":"short"}""")

        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        val fields = (response.json()["errors"] as List<Map<String, String>>).map { it["field"] }.toSet()
        assertEquals(setOf("loginId", "password"), fields)
    }

    @Test
    fun `구성원 목록에는 비활성 구성원도 보이고 사람 목록에는 활성 구성원만 보인다`() {
        val memberId = createMember("jimin").json()["id"] as String
        admin.patch("/api/v1/admin/members/$memberId", """{"status":"DISABLED"}""")

        assertEquals(listOf("admin", "jimin"), loginIds(admin.get("/api/v1/admin/members")))
        assertEquals(listOf("admin"), loginIds(admin.get("/api/v1/users")))
    }

    @Test
    fun `구성원은 구성원 관리 API 를 쓸 수 없다`() {
        createMember("jimin")
        val member = client().also { it.login("jimin", MEMBER_PASSWORD) }

        assertEquals(403, member.get("/api/v1/admin/members").status)
        assertEquals(403, member.post("/api/v1/admin/members", """{"loginId":"other","password":"$MEMBER_PASSWORD"}""").status)
        assertEquals(200, member.get("/api/v1/users").status)
    }

    @Test
    fun `비활성화하면 로그인도 기존 세션도 쓸 수 없고 다시 활성화하면 로그인된다`() {
        val memberId = createMember("jimin").json()["id"] as String
        val member = client().also { it.login("jimin", MEMBER_PASSWORD) }

        assertEquals("DISABLED", admin.patch("/api/v1/admin/members/$memberId", """{"status":"DISABLED"}""").json()["status"])

        assertEquals(401, member.me().status)
        assertEquals(401, client().login("jimin", MEMBER_PASSWORD).status)
        assertEquals(0, count("select count(*) from user_sessions where user_id = '$memberId'"))

        admin.patch("/api/v1/admin/members/$memberId", """{"status":"ACTIVE"}""")
        assertEquals(200, client().login("jimin", MEMBER_PASSWORD).status)
    }

    @Test
    fun `역할을 관리자로 바꾸면 관리 API 를 쓸 수 있다`() {
        val memberId = createMember("jimin").json()["id"] as String

        assertEquals("ADMIN", admin.patch("/api/v1/admin/members/$memberId", """{"role":"ADMIN"}""").json()["role"])

        val promoted = client().also { it.login("jimin", MEMBER_PASSWORD) }
        assertEquals(200, promoted.get("/api/v1/admin/members").status)
    }

    @Test
    fun `비밀번호를 재설정하면 새 비밀번호로만 로그인되고 기존 세션은 끝난다`() {
        val memberId = createMember("jimin").json()["id"] as String
        val member = client().also { it.login("jimin", MEMBER_PASSWORD) }

        assertEquals(204, admin.put("/api/v1/admin/members/$memberId/password", """{"password":"$NEW_PASSWORD"}""").status)

        assertEquals(401, member.me().status)
        assertEquals(401, client().login("jimin", MEMBER_PASSWORD).status)
        assertEquals(200, client().login("jimin", NEW_PASSWORD).status)
        val audit = text("select string_agg(detail::text, ' ') from audit_events")
        assertTrue(NEW_PASSWORD !in audit && MEMBER_PASSWORD !in audit)
    }

    @Test
    fun `재설정하는 비밀번호도 규칙을 지켜야 한다`() {
        val memberId = createMember("jimin").json()["id"] as String

        assertEquals(422, admin.put("/api/v1/admin/members/$memberId/password", """{"password":"short"}""").status)
        assertEquals(200, client().login("jimin", MEMBER_PASSWORD).status)
    }

    @Test
    fun `관리자는 자기 역할을 바꾸거나 자신을 비활성화할 수 없다`() {
        val adminId = admin.me().json()["id"] as String

        val demote = admin.patch("/api/v1/admin/members/$adminId", """{"role":"MEMBER"}""")
        val disable = admin.patch("/api/v1/admin/members/$adminId", """{"status":"DISABLED"}""")

        assertEquals(409, demote.status)
        assertEquals("CANNOT_CHANGE_SELF", demote.json()["code"])
        assertEquals(409, disable.status)
        assertEquals("ADMIN", admin.me().json()["role"])
    }

    @Test
    fun `없는 구성원을 고치려 하면 404 를 받는다`() {
        val response = admin.patch("/api/v1/admin/members/00000000-0000-7000-8000-000000000009", """{"role":"ADMIN"}""")

        assertEquals(404, response.status)
        assertEquals("MEMBER_NOT_FOUND", response.json()["code"])
        assertNull(response.json()["errors"])
    }

    private fun createMember(
        loginId: String,
        displayName: String? = null,
    ) = admin.post(
        "/api/v1/admin/members",
        """{"loginId":"$loginId","password":"$MEMBER_PASSWORD"${displayName?.let { ""","displayName":"$it"""" } ?: ""}}""",
    )

    private fun loginIds(response: com.flowbit.support.ApiResponse): List<String> = response.jsonList().map { it["loginId"] as String }
}
