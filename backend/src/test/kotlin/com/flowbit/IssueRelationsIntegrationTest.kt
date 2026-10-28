package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val PASSWORD = "correct-horse-battery"

class IssueRelationsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun createIssues() {
        admin = client().also { it.setup("admin", PASSWORD) }
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
        repeat(3) { admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"티켓 ${it + 1}"}""") }
    }

    @Test
    fun `한 티켓이 다른 티켓을 막으면 양쪽에서 막고 있음과 막혀 있음으로 보인다`() {
        val added = admin.post("/api/v1/issues/1/relations", """{"kind":"BLOCKS","targetNumber":2}""")

        assertEquals(201, added.status)
        assertEquals(listOf("BLOCKS"), added.jsonList().map { it["kind"] })
        assertEquals("FB-2", (added.jsonList().single()["issue"] as Map<*, *>)["key"])
        assertEquals(listOf("BLOCKED_BY"), admin.get("/api/v1/issues/2/relations").jsonList().map { it["kind"] })
        assertEquals(emptyList(), admin.get("/api/v1/issues/3/relations").jsonList())
    }

    @Test
    fun `중복과 관련 관계는 한 번만 저장되고 양쪽에서 보인다`() {
        admin.post("/api/v1/issues/3/relations", """{"kind":"DUPLICATE_OF","targetNumber":1}""")
        admin.post("/api/v1/issues/1/relations", """{"kind":"RELATED","targetNumber":2}""")

        assertEquals(
            listOf("DUPLICATED_BY", "RELATED"),
            admin
                .get("/api/v1/issues/1/relations")
                .jsonList()
                .map { it["kind"] as String }
                .sorted(),
        )
        assertEquals(listOf("DUPLICATE_OF"), admin.get("/api/v1/issues/3/relations").jsonList().map { it["kind"] })
        assertEquals(listOf("RELATED"), admin.get("/api/v1/issues/2/relations").jsonList().map { it["kind"] })
    }

    @Test
    fun `티켓을 만들면서 관계를 함께 맺으면 새 티켓과 상대 티켓 양쪽에 보인다`() {
        val made =
            admin.post(
                "/api/v1/projects/${p("PAY")}/issues",
                """{"title":"새 티켓","relations":[{"kind":"BLOCKED_BY","targetNumber":1},{"kind":"RELATED","targetNumber":2}]}""",
            )

        assertEquals(201, made.status)
        assertEquals(
            listOf("BLOCKED_BY", "RELATED"),
            admin
                .get("/api/v1/issues/4/relations")
                .jsonList()
                .map { it["kind"] as String }
                .sorted(),
        )
        assertEquals(listOf("BLOCKS"), admin.get("/api/v1/issues/1/relations").jsonList().map { it["kind"] })
        assertEquals(listOf("RELATED"), admin.get("/api/v1/issues/2/relations").jsonList().map { it["kind"] })
    }

    @Test
    fun `관계는 어느 쪽에서든 지울 수 있다`() {
        val id = admin.post("/api/v1/issues/1/relations", """{"kind":"BLOCKED_BY","targetNumber":2}""").jsonList().single()["id"]

        assertEquals(204, admin.delete("/api/v1/issues/2/relations/$id").status)
        assertEquals(emptyList(), admin.get("/api/v1/issues/1/relations").jsonList())
    }
}
