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
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$editorId", """{"role":"EDITOR"}""")
        editor = client().also { it.login("editor", PASSWORD) }
    }

    @Test
    fun `같은 화면을 보는 사람과 그 사람이 가리키는 곳이 서로에게 전해진다`() {
        val a = Socket(admin)
        val b = Socket(editor)
        a.send("""{"type":"subscribe","topics":["project:${p("PAY")}"],"presence":"project:${p("PAY")}"}""")
        b.send("""{"type":"subscribe","topics":["project:${p("PAY")}"],"presence":"project:${p("PAY")}"}""")
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
    fun `티켓이 바뀌면 그 프로젝트를 보는 사람에게 알리고 볼 수 없는 주제는 구독되지 않는다`() {
        remember("SEC", admin.post("/api/v1/projects", """{"name":"비공개"}""").json()["id"])
        val b = Socket(editor)
        b.send("""{"type":"subscribe","topics":["project:${p("PAY")}","project:${p("SEC")}","issues"],"presence":"project:${p("SEC")}"}""")

        admin.post("/api/v1/projects/${p("SEC")}/issues", """{"title":"비공개 티켓"}""")
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 실패"}""")

        val topics = (1..3).map { b.awaitChanged() }
        // "issues" twice (one per new issue) and the one project the editor may enter.
        assertEquals(listOf("issues", "issues", "project:${p("PAY")}"), topics.sorted())
        assertNull(b.poll("presence"))
        b.close()
    }

    @Test
    fun `프로젝트에 들어오게 되거나 빠지면 그 프로젝트를 볼 수 없던 사람에게도 알린다`() {
        remember("SEC", admin.post("/api/v1/projects", """{"name":"비공개"}""").json()["id"])
        val b = Socket(editor)
        b.send("""{"type":"subscribe","topics":["projects"]}""")
        val editorId = admin.get("/api/v1/projects/${p("PAY")}/members").jsonList().single { it["loginId"] == "editor" }["userId"]

        admin.put("/api/v1/projects/${p("SEC")}/members/$editorId", """{"role":"VIEWER"}""")
        assertEquals("projects", b.awaitChanged())
        admin.delete("/api/v1/projects/${p("SEC")}/members/$editorId")
        assertEquals("projects", b.awaitChanged())
        admin.post("/api/v1/projects", """{"name":"새 프로젝트"}""")
        assertEquals("projects", b.awaitChanged())
        b.close()
    }

    @Test
    fun `화면의 구성원 전부가 마지막으로 본 때와 함께 나오고 본 사람이 먼저다`() {
        val idlerId = admin.post("/api/v1/admin/members", """{"loginId":"idler","password":"$PASSWORD"}""").json()["id"]
        admin.put("/api/v1/projects/${p("PAY")}/members/$idlerId", """{"role":"VIEWER"}""")
        admin.post("/api/v1/admin/members", """{"loginId":"outsider","password":"$PASSWORD"}""")
        val outsider = client().also { it.login("outsider", PASSWORD) }
        val b = Socket(editor)
        b.send("""{"type":"subscribe","topics":[],"presence":"project:${p("PAY")}"}""")
        b.awaitPresence { viewers -> viewers.size == 1 }

        val people = admin.get("/api/v1/presence?key=project:${p("PAY")}").jsonList()

        assertEquals(listOf("editor", "admin", "idler"), people.map { it["loginId"] })
        assertNotNull(people[0]["lastSeenAt"])
        assertNull(people[2]["lastSeenAt"])
        assertEquals(404, outsider.get("/api/v1/presence?key=project:${p("PAY")}").status)
        assertEquals(404, admin.get("/api/v1/presence?key=nothing").status)
        b.close()
    }

    @Test
    fun `목표 상세도 보는 사람과 마지막으로 본 때가 남고 프로젝트 밖의 사람은 볼 수 없다`() {
        val milestone = admin.post("/api/v1/projects/${p("PAY")}/milestones", """{"name":"베타"}""").json()["id"]
        val idlerId = admin.post("/api/v1/admin/members", """{"loginId":"idler","password":"$PASSWORD"}""").json()["id"]
        admin.put("/api/v1/projects/${p("PAY")}/members/$idlerId", """{"role":"VIEWER"}""")
        admin.post("/api/v1/admin/members", """{"loginId":"outsider","password":"$PASSWORD"}""")
        val outsider = client().also { it.login("outsider", PASSWORD) }
        val a = Socket(admin)
        val b = Socket(editor)
        a.send("""{"type":"subscribe","topics":[],"presence":"milestone:$milestone"}""")
        b.send("""{"type":"subscribe","topics":[],"presence":"milestone:$milestone"}""")
        b.awaitPresence { viewers -> viewers.size == 2 }

        val people = admin.get("/api/v1/presence?key=milestone:$milestone").jsonList()

        // The project's members, those who have it open or looked first. The milestone's own page is not the project's.
        assertEquals(setOf("admin", "editor", "idler"), people.map { it["loginId"] }.toSet())
        assertEquals("idler", people.last()["loginId"])
        assertNotNull(people.single { it["loginId"] == "editor" }["lastSeenAt"])
        assertNull(people.single { it["loginId"] == "idler" }["lastSeenAt"])
        assertEquals(emptyList<Any?>(), admin.get("/api/v1/presence?key=project:${p("PAY")}").jsonList().filter { it["lastSeenAt"] != null })
        assertEquals(404, outsider.get("/api/v1/presence?key=milestone:$milestone").status)
        assertEquals(404, admin.get("/api/v1/presence?key=milestone:00000000-0000-0000-0000-000000000000").status)
        assertEquals(404, admin.get("/api/v1/presence?key=milestone:not-an-id").status)
        // A milestone's page is not open to the one who may not enter its project, over the socket either.
        val c = Socket(outsider)
        c.send("""{"type":"subscribe","topics":[],"presence":"milestone:$milestone"}""")
        assertNull(c.poll("presence"))
        a.close()
        b.close()
        c.close()
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
