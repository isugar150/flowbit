package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val PASSWORD = "correct-horse-battery"

/** The WebSocket at /api/v1/realtime, driven the way two browsers on the same page would. */
class RealtimeIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient

    @BeforeEach
    fun createProjectWithTwoPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val editorId = admin.post("/api/v1/admin/members", """{"loginId":"editor","password":"$PASSWORD"}""").json()["id"]
        admin.post("/api/v1/projects", """{"name":"결제 개편","key":"PAY"}""")
        admin.put("/api/v1/projects/PAY/members/$editorId", """{"role":"EDITOR"}""")
        editor = client().also { it.login("editor", PASSWORD) }
    }

    @Test
    fun `같은 화면을 보는 사람과 그 사람이 가리키는 곳이 서로에게 전해진다`() {
        val a = Socket(admin)
        val b = Socket(editor)
        a.send("""{"type":"subscribe","topics":["project:PAY"],"presence":"project:PAY"}""")
        b.send("""{"type":"subscribe","topics":["project:PAY"],"presence":"project:PAY"}""")
        b.awaitPresence { viewers -> viewers.size == 2 }

        a.send("""{"type":"focus","target":"description"}""")

        val seen = b.awaitPresence { viewers -> viewers.any { it["focus"] == "description" } }
        assertEquals("admin", seen.single { it["focus"] == "description" }["loginId"])

        a.send("""{"type":"focus","target":null}""")
        b.awaitPresence { viewers -> viewers.none { it["focus"] != null } }
        a.close()
        b.awaitPresence { viewers -> viewers.size == 1 }
        b.close()
    }

    @Test
    fun `이슈가 바뀌면 그 프로젝트를 보는 사람에게 알리고 볼 수 없는 주제는 구독되지 않는다`() {
        admin.post("/api/v1/projects", """{"name":"비공개","key":"SEC"}""")
        val b = Socket(editor)
        b.send("""{"type":"subscribe","topics":["project:PAY","project:SEC","issues"],"presence":"project:SEC"}""")

        admin.post("/api/v1/projects/SEC/issues", """{"title":"비공개 이슈"}""")
        admin.post("/api/v1/projects/PAY/issues", """{"title":"결제 실패"}""")

        val topics = (1..3).map { b.awaitChanged() }
        // "issues" twice (one per new issue) and the one project the editor may enter.
        assertEquals(listOf("issues", "issues", "project:PAY"), topics.sorted())
        assertNull(b.poll("presence"))
        b.close()
    }

    private inner class Socket(
        client: ApiClient,
    ) : WebSocket.Listener {
        private val mapper = JsonMapper.builder().build()
        private val messages = LinkedBlockingQueue<Map<String, Any?>>()
        private val text = StringBuilder()
        private val socket: WebSocket =
            HttpClient
                .newHttpClient()
                .newWebSocketBuilder()
                .header("Cookie", client.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .buildAsync(URI.create("ws://localhost:$port/api/v1/realtime"), this)
                .get(10, TimeUnit.SECONDS)

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            text.append(data)
            if (last) {
                @Suppress("UNCHECKED_CAST")
                messages.add(mapper.readValue(text.toString(), Map::class.java) as Map<String, Any?>)
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        fun send(json: String) {
            socket.sendText(json, true).get(10, TimeUnit.SECONDS)
        }

        fun close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(10, TimeUnit.SECONDS)
        }

        /** The next message of this type within a short wait, skipping others; null when none comes. */
        fun poll(type: String): Map<String, Any?>? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val message = messages.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (message["type"] == type) return message
            }
            return null
        }

        fun awaitChanged(): String = assertNotNull(poll("changed"), "no change notice arrived")["topic"] as String

        /** Waits for a presence message whose viewers satisfy the condition. */
        @Suppress("UNCHECKED_CAST")
        fun awaitPresence(condition: (List<Map<String, Any?>>) -> Boolean): List<Map<String, Any?>> {
            repeat(10) {
                val viewers = poll("presence")?.get("viewers") as List<Map<String, Any?>>? ?: return@repeat
                if (condition(viewers)) return viewers
            }
            throw AssertionError("no matching presence message arrived")
        }
    }
}
