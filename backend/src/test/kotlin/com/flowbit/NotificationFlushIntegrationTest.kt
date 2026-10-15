package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** Notifications are held while someone works on an issue and go out as one when they close it (D-126). */
class NotificationFlushIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var kim: ApiClient
    private lateinit var kimId: String

    @BeforeEach
    fun start() {
        received.clear()
        admin = client().also { it.setup("admin", PASSWORD) }
        kimId = admin.post("/api/v1/admin/members", """{"loginId":"kimjm","password":"$PASSWORD"}""").json()["id"] as String
        kim = client().also { it.login("kimjm", PASSWORD) }
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            """
            {"name":"알림","enabled":true,"url":"$outside/hook","method":"POST","contentType":"application/json",
             "headers":{},"bodyTemplate":"","signingSecret":null,"eventTypes":[]}
            """.trimIndent(),
        )
    }

    @Test
    fun `티켓을 만들고 여러 번 고쳐도 닫을 때 한 번에 하나의 알림으로 간다`() {
        // kim follows the issue as its assignee.
        admin.post("/api/v1/issues", """{"title":"배포 준비","assigneeIds":["$kimId"]}""")
        admin.patch("/api/v1/issues/1", """{"status":"READY"}""")
        admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS","priority":"HIGH"}""")
        admin.patch("/api/v1/issues/1", """{"title":"배포 준비 (금요일)"}""")
        Thread.sleep(500)
        assertTrue(received.isEmpty(), "held while the issue is being worked on")

        // Someone else leaving the issue does not send what admin did.
        assertEquals(204, kim.post("/api/v1/issues/1/notifications/flush", "{}").status)
        Thread.sleep(300)
        assertTrue(received.isEmpty())

        assertEquals(204, admin.post("/api/v1/issues/1/notifications/flush", "{}").status)

        awaitTrue { received.size == 1 }
        Thread.sleep(300)
        assertEquals(1, received.size)
        val payload = JsonMapper.builder().build().readTree(received.single())
        assertEquals("issue.created", payload.get("event").asString())
        assertEquals("배포 준비 (금요일)", payload.get("issue").get("title").asString())
        val changes = payload.get("changes")
        assertEquals(
            listOf("CREATED", "STATUS_CHANGED", "PRIORITY_CHANGED", "TITLE_CHANGED"),
            (0 until changes.size()).map { changes.get(it).get("type").asString() },
        )
        val status = changes.get(1).get("detail")
        assertEquals("BACKLOG", status.get("from").asString())
        assertEquals("IN_PROGRESS", status.get("to").asString())
        assertEquals(401, client().post("/api/v1/issues/1/notifications/flush", "{}").status)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting" }
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

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            // Long enough that only closing the issue can send it within the test.
            registry.add("flowbit.notifications.batch-delay") { "5m" }
        }
    }
}
