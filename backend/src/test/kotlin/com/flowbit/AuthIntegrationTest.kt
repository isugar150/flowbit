package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val WRONG_PASSWORD = "wrong-horse-battery"

class AuthIntegrationTest : IntegrationTest() {
    @BeforeEach
    fun completeSetup() {
        client().setup("admin", PASSWORD)
    }

    @Test
    fun `로그인하지 않으면 내 정보를 볼 수 없다`() {
        val response = client().me()

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", response.json()["code"])
        assertNotNull(response.json()["traceId"])
        assertTrue(response.header("Content-Type").orEmpty().startsWith("application/problem+json"))
    }

    @Test
    fun `올바른 아이디와 비밀번호로 로그인하면 내 정보를 돌려준다`() {
        val client = client()

        val login = client.login("admin", PASSWORD)

        assertEquals(200, login.status)
        assertEquals("admin", login.json()["loginId"])
        assertEquals("admin", client.me().json()["loginId"])
        assertEquals(1, count("select count(*) from users where last_login_at is not null"))
    }

    @Test
    fun `아이디는 대소문자를 구분하지 않는다`() {
        assertEquals(200, client().login("  ADMIN ", PASSWORD).status)
    }

    @Test
    fun `없는 아이디와 틀린 비밀번호의 응답이 같다`() {
        val unknown = client().login("nobody", PASSWORD)
        val wrong = client().login("admin", WRONG_PASSWORD)

        assertEquals(401, unknown.status)
        assertEquals(401, wrong.status)
        assertEquals("INVALID_CREDENTIALS", unknown.json()["code"])
        assertEquals(unknown.json()["code"], wrong.json()["code"])
        assertEquals(unknown.json()["title"], wrong.json()["title"])
    }

    @Test
    fun `5회 연속 실패하면 올바른 비밀번호로도 로그인할 수 없고 15분 뒤에는 가능하다`() {
        val client = client()
        repeat(5) { assertEquals(401, client.login("admin", WRONG_PASSWORD).status) }

        val whileLocked = client.login("admin", PASSWORD)
        assertEquals(401, whileLocked.status)
        assertEquals("INVALID_CREDENTIALS", whileLocked.json()["code"])
        assertEquals(1, count("select count(*) from audit_events where event_type = 'ACCOUNT_LOCKED'"))

        clock.advance(Duration.ofMinutes(15).plusSeconds(1))
        assertEquals(200, client.login("admin", PASSWORD).status)
    }

    @Test
    fun `4회 실패한 뒤 성공하면 실패 횟수가 초기화된다`() {
        val client = client()
        repeat(4) { client.login("admin", WRONG_PASSWORD) }

        assertEquals(200, client.login("admin", PASSWORD).status)
        assertEquals(0, count("select failed_login_count from users"))
    }

    @Test
    fun `로그인 성공 시 세션 ID 가 바뀐다`() {
        val client = client()
        client.login("admin", PASSWORD)
        val before = assertNotNull(client.sessionCookie)

        client.login("admin", PASSWORD)
        val after = assertNotNull(client.sessionCookie)

        assertNotEquals(before, after)
        assertEquals(401, withSession(before).me().status)
    }

    @Test
    fun `로그아웃 후에는 이전 세션 쿠키로 내 정보를 부를 수 없다`() {
        val client = client()
        client.login("admin", PASSWORD)
        val session = assertNotNull(client.sessionCookie)

        assertEquals(204, client.logout().status)

        assertEquals(401, withSession(session).me().status)
    }

    @Test
    fun `로그인하지 않은 상태에서는 로그아웃할 수 없다`() {
        assertEquals(401, client().logout().status)
    }

    @Test
    fun `CSRF 토큰 없이 보낸 POST 는 거부된다`() {
        val client = client().apply { sendCsrfHeader = false }

        val response = client.login("admin", PASSWORD)

        assertEquals(403, response.status)
        assertEquals("CSRF_TOKEN_INVALID", response.json()["code"])
    }

    @Test
    fun `같은 IP 에서 1분에 10번을 넘게 로그인을 시도하면 429 를 받는다`() {
        val client = client()
        repeat(10) { client.login("nobody", WRONG_PASSWORD) }

        val response = client.login("nobody", WRONG_PASSWORD)

        assertEquals(429, response.status)
        assertEquals("TOO_MANY_REQUESTS", response.json()["code"])
        assertTrue(assertNotNull(response.header("Retry-After")).toInt() in 1..60)
    }

    @Test
    fun `비활성화된 사용자는 로그인할 수 없고 기존 세션도 쓸 수 없다`() {
        val client = client()
        client.login("admin", PASSWORD)

        execute("update users set status = 'DISABLED'")

        assertEquals(401, client.me().status)
        assertEquals("INVALID_CREDENTIALS", client().login("admin", PASSWORD).json()["code"])
    }

    @Test
    fun `7일 동안 쓰지 않은 세션은 끝난다`() {
        val client = client()
        client.login("admin", PASSWORD)

        clock.advance(Duration.ofDays(6))
        assertEquals(200, client.me().status)

        clock.advance(Duration.ofDays(7).plusMinutes(1))
        assertEquals(401, client.me().status)
    }

    @Test
    fun `로그인한 지 30일이 지난 세션은 끝난다`() {
        val client = client()
        client.login("admin", PASSWORD)

        clock.advance(Duration.ofDays(30).plusMinutes(1))

        assertEquals(401, client.me().status)
    }

    @Test
    fun `로그인 성공과 실패와 로그아웃이 감사 로그에 남고 비밀번호는 남지 않는다`() {
        val client = client()
        client.login("admin", WRONG_PASSWORD)
        client.login("admin", PASSWORD)
        client.logout()

        assertEquals(1, count("select count(*) from audit_events where event_type = 'LOGIN_FAILED' and detail ->> 'loginId' = 'admin'"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'LOGIN_SUCCEEDED'"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'LOGOUT'"))
        assertEquals(0, count("select count(*) from audit_events where ip_address is null"))
        val details = text("select string_agg(detail::text, ' ') from audit_events")
        assertFalse(details.contains(PASSWORD))
        assertFalse(details.contains(WRONG_PASSWORD))
    }

    @Test
    fun `알 수 없는 경로는 로그인한 사용자에게 404 문제 문서를 돌려준다`() {
        val client = client()
        client.login("admin", PASSWORD)

        val response = client.get("/api/v1/nope")

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", response.json()["code"])
    }

    private fun withSession(session: String): ApiClient = client().apply { cookies[ApiClient.SESSION_COOKIE] = session }
}
