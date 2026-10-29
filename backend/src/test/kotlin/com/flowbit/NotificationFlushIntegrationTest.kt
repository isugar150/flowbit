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

    @Test
    fun `연속 댓글과 답글은 창 닫기나 변경 알림 대기 없이 각각 바로 전달된다`() {
        assertEquals(201, admin.post("/api/v1/issues", """{"title":"즉시 댓글","assigneeIds":["$kimId"]}""").status)
        assertEquals(200, admin.patch("/api/v1/issues/1", """{"priority":"HIGH"}""").status)
        val first = admin.post("/api/v1/issues/1/comments", comment("첫 댓글"))
        assertEquals(201, first.status)
        awaitTrue { received.size == 1 && inboxSize(kim) == 1 }
        assertEquals(201, admin.post("/api/v1/issues/1/comments", comment("둘째 댓글")).status)
        awaitTrue { received.size == 2 && inboxSize(kim) == 2 }

        assertEquals(201, kim.post("/api/v1/issues/1/comments", comment("답글", first.json()["id"] as String)).status)
        awaitTrue { received.size == 3 && inboxSize(admin) == 1 }
        val mapper = JsonMapper.builder().build()
        assertTrue(received.all { mapper.readTree(it).get("event").asString() == "issue.commented" })
        assertTrue(received.all { mapper.readTree(it).get("changes").size() == 1 })

        // Comments must neither flush nor merge into the pending property changes.
        assertEquals(204, admin.post("/api/v1/issues/1/notifications/flush", "{}").status)
        awaitTrue { received.size == 4 && inboxSize(kim) == 3 }
        assertEquals(1, received.count { mapper.readTree(it).get("event").asString() == "issue.created" })
    }

    @Test
    fun `프로젝트와 문서의 일반 댓글과 블록 답글도 대기 없이 각각 바로 전달된다`() {
        val projectId = admin.post("/api/v1/projects", """{"name":"즉시 알림"}""").json()["id"] as String
        assertEquals(200, admin.put("/api/v1/projects/$projectId/members/$kimId", """{"role":"EDITOR"}""").status)
        val documentId = admin.post("/api/v1/projects/$projectId/documents", """{"title":"설계"}""").json()["id"] as String
        val blockId = "11111111-1111-1111-1111-111111111111"
        for ((type, id, editPath) in listOf(
            Triple("project", projectId, "/api/v1/projects/$projectId"),
            Triple("document", documentId, "/api/v1/documents/$documentId"),
        )) {
            assertEquals(200, kim.put("/api/v1/content/$type/$id/subscription", "{}").status)
            assertEquals(
                200,
                admin
                    .patch(
                        editPath,
                        """{"content":{"type":"doc","content":[{"type":"paragraph","attrs":{"blockId":"$blockId"},"content":[{"type":"text","text":"검토 본문"}]}]}}""",
                    ).status,
            )
            val before = received.size
            val first = admin.post("/api/v1/content/$type/$id/comments", comment("첫 댓글"))
            assertEquals(201, first.status)
            awaitTrue { received.size == before + 1 }
            assertEquals(201, admin.post("/api/v1/content/$type/$id/comments", comment("둘째 댓글")).status)
            awaitTrue { received.size == before + 2 }
            assertEquals(201, kim.post("/api/v1/content/$type/$id/comments", comment("답글", first.json()["id"] as String)).status)
            awaitTrue { received.size == before + 3 }
            val thread = admin.post("/api/v1/content/$type/$id/threads", """{"blockId":"$blockId",${comment("블록 댓글").removePrefix("{")}""")
            assertEquals(201, thread.status)
            awaitTrue { received.size == before + 4 }
            assertEquals(201, kim.post("/api/v1/content-threads/${thread.json()["id"]}/comments", comment("블록 답글")).status)
            awaitTrue { received.size == before + 5 }
        }
        awaitTrue { inboxSize(kim) == 6 && inboxSize(admin) == 4 }
        assertEquals(10, received.size)
        val mapper = JsonMapper.builder().build()
        assertTrue(
            received.all {
                mapper
                    .readTree(it)
                    .get("event")
                    .asString()
                    .endsWith(".commented")
            },
        )
    }

    private fun comment(
        text: String,
        parentId: String? = null,
    ): String =
        """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}${parentId?.let {
            ""","parentId":"$it""""
        } ?: ""}}"""

    private fun inboxSize(client: ApiClient): Int = (client.get("/api/v1/me/inbox").json()["items"] as List<*>).size

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
