package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ADMIN_PASSWORD = "correct-horse-battery"
private const val FIRST_PASSWORD = "member-first-password"
private const val SECOND_PASSWORD = "member-second-password"

class MyPasswordIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", ADMIN_PASSWORD) }
        admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$FIRST_PASSWORD"}""")
    }

    @Test
    fun `LDAP 를 쓰지 않으면 구성원이 자기 비밀번호를 바꿀 수 있고 새 비밀번호로 로그인한다`() {
        val here = client().also { it.login("jimin", FIRST_PASSWORD) }
        assertEquals(true, here.get("/api/v1/me/password").json()["available"])

        val changed = here.put("/api/v1/me/password", """{"currentPassword":"$FIRST_PASSWORD","newPassword":"$SECOND_PASSWORD"}""")

        assertEquals(204, changed.status)
        assertEquals(200, here.me().status)
        assertEquals(200, client().login("jimin", SECOND_PASSWORD).status)
        assertEquals(401, client().login("jimin", FIRST_PASSWORD).status)
        assertEquals(1, count("select count(*) from audit_events where event_type = 'MEMBER_PASSWORD_CHANGED'"))
    }

    @Test
    fun `비밀번호를 바꾸면 이 브라우저는 로그인된 채로 남고 다른 브라우저는 로그아웃된다`() {
        val here = client().also { it.login("jimin", FIRST_PASSWORD) }
        val elsewhere = client().also { it.login("jimin", FIRST_PASSWORD) }
        assertEquals(200, elsewhere.me().status)

        here.put("/api/v1/me/password", """{"currentPassword":"$FIRST_PASSWORD","newPassword":"$SECOND_PASSWORD"}""")

        assertEquals(200, here.me().status)
        assertEquals(401, elsewhere.me().status)
    }

    @Test
    fun `LDAP 를 켜면 비밀번호 바꾸기를 보여 주지 않는다`() {
        val member = client().also { it.login("jimin", FIRST_PASSWORD) }
        assertEquals(true, member.get("/api/v1/me/password").json()["available"])

        execute("update ldap_settings set enabled = true")

        assertEquals(false, member.get("/api/v1/me/password").json()["available"])
        assertTrue(admin.get("/api/v1/me/password").json()["available"] == false)
    }
}
