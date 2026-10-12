package com.flowbit

import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class SetupIntegrationTest : IntegrationTest() {
    @Test
    fun `헬스 체크는 로그인 없이 응답한다`() {
        val response = client().get("/api/v1/health")

        assertEquals(200, response.status)
        assertEquals("UP", response.json()["status"])
    }

    @Test
    fun `설치 직후에는 초기 설정이 끝나지 않은 상태다`() {
        val response = client().get("/api/v1/setup/status")

        assertEquals(200, response.status)
        assertEquals(false, response.json()["completed"])
    }

    @Test
    fun `초기 설정 전에는 로그인할 수 없다`() {
        val response = client().login("admin", PASSWORD)

        assertEquals(409, response.status)
        assertEquals("SETUP_REQUIRED", response.json()["code"])
    }

    @Test
    fun `유효한 아이디와 비밀번호로 설정을 마치면 그 계정으로 로그인된 상태가 된다`() {
        val client = client()

        val response = client.setup("Admin", PASSWORD)

        assertEquals(201, response.status)
        assertEquals("admin", response.json()["loginId"])
        assertEquals("ADMIN", response.json()["role"])
        assertEquals("ko", response.json()["locale"])
        assertEquals("Asia/Seoul", response.json()["timezone"])
        assertNotNull(client.sessionCookie)
        assertEquals(1, count("select count(*) from user_sessions"))

        val me = client.me()
        assertEquals(200, me.status)
        assertEquals("admin", me.json()["loginId"])
        assertEquals(true, client.get("/api/v1/setup/status").json()["completed"])
        assertEquals(1, count("select count(*) from users where login_id = 'admin' and role = 'ADMIN' and status = 'ACTIVE'"))
    }

    @Test
    fun `설정 후 로그아웃하고 같은 아이디와 비밀번호로 다시 로그인할 수 있다`() {
        val client = client()
        client.setup("admin", PASSWORD)

        assertEquals(204, client.logout().status)
        assertEquals(401, client.me().status)

        assertEquals(200, client.login("admin", PASSWORD).status)
        assertEquals(200, client.me().status)
    }

    @Test
    fun `설정이 끝난 뒤에는 다시 설정할 수 없다`() {
        client().setup("admin", PASSWORD)

        val response = client().setup("second", PASSWORD)

        assertEquals(409, response.status)
        assertEquals("SETUP_ALREADY_COMPLETED", response.json()["code"])
        assertEquals(1, count("select count(*) from users"))
    }

    @Test
    fun `동시에 보낸 두 설정 요청 중 하나만 성공하고 사용자는 한 명만 만들어진다`() {
        val clients = listOf(client(), client()).onEach { it.get("/api/v1/auth/csrf") }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(clients.size)

        val statuses =
            clients
                .mapIndexed { index, client ->
                    pool.submit<Int> {
                        start.await()
                        client.setup("admin$index", PASSWORD).status
                    }
                }.also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(listOf(201, 409), statuses.sorted())
        assertEquals(1, count("select count(*) from users"))
    }

    @Test
    fun `입력 검증 규칙을 어기면 필드별 오류와 함께 422 를 받는다`() {
        val response = client().setup("a!", "short")

        assertEquals(422, response.status)
        assertEquals("VALIDATION_FAILED", response.json()["code"])
        assertTrue(response.header("Content-Type").orEmpty().startsWith("application/problem+json"))
        @Suppress("UNCHECKED_CAST")
        val errors = response.json()["errors"] as List<Map<String, String>>
        assertEquals(setOf("loginId", "password"), errors.map { it["field"] }.toSet())
        assertEquals(0, count("select count(*) from users"))
        assertEquals(false, client().get("/api/v1/setup/status").json()["completed"])
    }

    @Test
    fun `설정에서 정한 워크스페이스 이름이 저장된다`() {
        val client = client()

        assertEquals(201, client.setup("admin", PASSWORD, "  우리 회사  ").status)

        assertEquals("우리 회사", client.get("/api/v1/workspace").json()["name"])
    }

    @Test
    fun `워크스페이스 이름을 보내지 않으면 기본 이름을 쓴다`() {
        val client = client()

        assertEquals(201, client.setup("admin", PASSWORD).status)

        assertEquals("Flowbit", client.get("/api/v1/workspace").json()["name"])
    }

    @Test
    fun `너무 긴 워크스페이스 이름은 거부하고 설정은 끝나지 않는다`() {
        val response = client().setup("admin", PASSWORD, "가".repeat(101))

        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        val errors = response.json()["errors"] as List<Map<String, String>>
        assertEquals("workspaceName", errors.single()["field"])
        assertEquals(0, count("select count(*) from users"))
        assertEquals(false, client().get("/api/v1/setup/status").json()["completed"])
    }

    @Test
    fun `아이디와 같은 비밀번호는 거부한다`() {
        val response = client().setup("administrator", "Administrator")

        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        val errors = response.json()["errors"] as List<Map<String, String>>
        assertEquals("SAME_AS_LOGIN_ID", errors.single()["code"])
    }

    @Test
    fun `설정 완료가 감사 로그에 남고 비밀번호는 남지 않는다`() {
        client().setup("admin", PASSWORD)

        assertEquals(1, count("select count(*) from audit_events where event_type = 'SETUP_COMPLETED' and actor_user_id is not null"))
        assertFalse(text("select string_agg(detail::text, ' ') from audit_events").contains(PASSWORD))
    }
}
