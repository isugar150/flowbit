package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** Someone who may not enter a project asks to be let in; its managers are told and answer (D-128). */
class ProjectAccessRequestIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var lee: ApiClient
    private lateinit var kim: ApiClient
    private lateinit var kimId: String
    private lateinit var editor: ApiClient

    @BeforeEach
    fun start() {
        received.clear()
        admin = client().also { it.setup("admin", PASSWORD) }
        val leeId = createMember("leesj", "이수진")
        kimId = createMember("kimjm", "김지민")
        val editorId = createMember("editor", null)
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$leeId", """{"role":"MANAGER"}""")
        admin.put("/api/v1/projects/${p("PAY")}/members/$editorId", """{"role":"EDITOR"}""")
        lee = client().also { it.login("leesj", PASSWORD) }
        kim = client().also { it.login("kimjm", PASSWORD) }
        editor = client().also { it.login("editor", PASSWORD) }
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            """
            {"name":"알림","enabled":true,"url":"$outside/hook","method":"POST","contentType":"application/json",
             "headers":{},"bodyTemplate":"","signingSecret":null,"eventTypes":["ACCESS_REQUESTED"]}
            """.trimIndent(),
        )
    }

    @Test
    fun `접근을 요청하면 관리 역할에게 설정으로 가는 알림이 가고 관리 역할이 역할을 골라 승인한다`() {
        assertNull(kim.get("/api/v1/projects/${p("PAY")}/access").json()["requestedAt"])

        val requested = kim.post("/api/v1/projects/${p("PAY")}/access-requests", "{}")

        assertEquals(200, requested.status)
        assertNotNull(requested.json()["requestedAt"])
        assertNotNull(kim.get("/api/v1/projects/${p("PAY")}/access").json()["requestedAt"])
        // Both managers: the workspace administrator who made the project, and lee.
        awaitTrue { received.size == 2 }
        val payloads = received.map { JsonMapper.builder().build().readTree(it) }
        assertEquals(setOf("admin", "leesj"), payloads.map { it.get("recipient").get("loginId").asString() }.toSet())
        val payload = payloads.first()
        assertEquals("project.access_requested", payload.get("event").asString())
        assertTrue(payload.get("issue").isNull)
        assertEquals("결제 개편", payload.get("project").get("name").asString())
        assertEquals("/project/${p("PAY")}/settings#access-requests", payload.get("project").get("url").asString())
        assertEquals("[결제 개편] 접근 요청", payload.get("title").asString())
        assertEquals("김지민 님이 프로젝트 접근을 요청했습니다", payload.get("headline").asString())
        val change = payload.get("changes").get(0)
        assertEquals("ACCESS_REQUESTED", change.get("type").asString())

        // Asking again while waiting tells nobody again.
        assertEquals(200, kim.post("/api/v1/projects/${p("PAY")}/access-requests", "{}").status)
        Thread.sleep(400)
        assertEquals(2, received.size)

        // Only managers see and answer requests.
        assertEquals(403, editor.get("/api/v1/projects/${p("PAY")}/access-requests").status)
        assertEquals(404, kim.get("/api/v1/projects/${p("PAY")}/access-requests").status)
        val waiting = lee.get("/api/v1/projects/${p("PAY")}/access-requests").jsonList()
        assertEquals(listOf("kimjm"), waiting.map { it["loginId"] })
        assertEquals("김지민", waiting.single()["displayName"])
        assertEquals(403, editor.post("/api/v1/projects/${p("PAY")}/access-requests/$kimId/approve", """{"role":"VIEWER"}""").status)

        val approved = lee.post("/api/v1/projects/${p("PAY")}/access-requests/$kimId/approve", """{"role":"VIEWER"}""")

        assertEquals(200, approved.status)
        assertEquals("VIEWER", approved.jsonList().single { it["loginId"] == "kimjm" }["role"])
        assertEquals(emptyList(), lee.get("/api/v1/projects/${p("PAY")}/access-requests").jsonList())
        val entry = kim.get("/api/v1/projects/${p("PAY")}/access").json()
        assertEquals("VIEWER", entry["myRole"])
        assertNull(entry["requestedAt"])
        assertEquals(200, kim.get("/api/v1/projects/${p("PAY")}").status)
        // A member has nothing to ask for.
        assertEquals(409, kim.post("/api/v1/projects/${p("PAY")}/access-requests", "{}").status)
    }

    @Test
    fun `거절하면 요청만 지워지고 다시 요청할 수 있으며 직접 추가해도 요청이 지워진다`() {
        kim.post("/api/v1/projects/${p("PAY")}/access-requests", "{}")
        awaitTrue { received.size == 2 }

        assertEquals(204, lee.delete("/api/v1/projects/${p("PAY")}/access-requests/$kimId").status)
        assertEquals(404, lee.delete("/api/v1/projects/${p("PAY")}/access-requests/$kimId").status)
        assertEquals(404, lee.post("/api/v1/projects/${p("PAY")}/access-requests/$kimId/approve", """{"role":"EDITOR"}""").status)
        assertNull(kim.get("/api/v1/projects/${p("PAY")}/access").json()["myRole"])
        assertNull(kim.get("/api/v1/projects/${p("PAY")}/access").json()["requestedAt"])

        kim.post("/api/v1/projects/${p("PAY")}/access-requests", "{}")
        awaitTrue { received.size == 4 }
        lee.put("/api/v1/projects/${p("PAY")}/members/$kimId", """{"role":"EDITOR"}""")

        assertEquals(emptyList(), lee.get("/api/v1/projects/${p("PAY")}/access-requests").jsonList())
    }

    private fun createMember(
        loginId: String,
        displayName: String?,
    ): String =
        admin
            .post(
                "/api/v1/admin/members",
                """{"loginId":"$loginId","password":"$PASSWORD"${displayName?.let { ""","displayName":"$it"""" }.orEmpty()}}""",
            ).json()["id"] as String

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting; received ${received.size}" }
            Thread.sleep(50)
        }
    }

    companion object {
        private val received = CopyOnWriteArrayList<String>()

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    received += String(exchange.requestBody.readAllBytes())
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.use { it.write("{}".toByteArray()) }
                }
                start()
            }

        private val outside = "http://localhost:${server.address.port}"
    }
}
