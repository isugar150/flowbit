package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class CyclesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun createProject() {
        admin = client().also { it.setup("admin", PASSWORD) }
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun cycles() = admin.get("/api/v1/projects/${p("PAY")}/cycles").json()

    @Suppress("UNCHECKED_CAST")
    private fun day(
        cycle: Any?,
        field: String,
    ): LocalDate = LocalDate.parse((cycle as Map<String, Any?>)[field] as String)

    @Test
    fun `사이클을 켜면 오늘의 사이클과 다음 사이클이 저절로 만들어진다`() {
        assertNull(cycles()["current"])

        val settings = admin.put("/api/v1/projects/${p("PAY")}/cycles/settings", """{"enabled":true,"weeks":2,"startDay":1}""").json()

        val current = settings["current"]
        val upcoming = settings["upcoming"]
        assertNotNull(current)
        assertNotNull(upcoming)
        // Monday to Sunday, two weeks; the next one starts the day after.
        assertEquals(
            13L,
            java.time.temporal.ChronoUnit.DAYS
                .between(day(current, "startsOn"), day(current, "endsOn")),
        )
        assertEquals(day(current, "endsOn").plusDays(1), day(upcoming, "startsOn"))
        assertEquals(java.time.DayOfWeek.MONDAY, day(current, "startsOn").dayOfWeek)
        assertTrue(!day(current, "startsOn").isAfter(LocalDate.parse(settings["today"] as String)))
    }

    @Test
    fun `티켓을 사이클에 넣으면 사이클의 진행이 센다`() {
        val current = admin.put("/api/v1/projects/${p("PAY")}/cycles/settings", """{"enabled":true,"weeks":1,"startDay":1}""").json()["current"] as Map<*, *>
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"하나"}""")
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"둘"}""")

        val put = admin.patch("/api/v1/issues/1", """{"cycleId":"${current["id"]}"}""")
        admin.patch("/api/v1/issues/2", """{"cycleId":"${current["id"]}","status":"DONE"}""")

        assertEquals(1, (put.json()["cycle"] as Map<*, *>)["number"])
        val counted = cycles()["current"] as Map<*, *>
        assertEquals(2, counted["total"])
        assertEquals(1, counted["done"])
    }

    @Test
    fun `사이클이 끝나면 끝나지 않은 티켓만 다음 사이클로 넘어간다`() {
        val first = admin.put("/api/v1/projects/${p("PAY")}/cycles/settings", """{"enabled":true,"weeks":1,"startDay":1}""").json()["current"] as Map<*, *>
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"안 끝남"}""")
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"끝남"}""")
        admin.patch("/api/v1/issues/1", """{"cycleId":"${first["id"]}"}""")
        admin.patch("/api/v1/issues/2", """{"cycleId":"${first["id"]}","status":"DONE"}""")

        // In two steps, so that the session is not idle for a week.
        clock.advance(Duration.ofDays(4))
        cycles()
        clock.advance(Duration.ofDays(4))
        val later = cycles()

        val now = later["current"] as Map<*, *>
        assertEquals(2, now["number"])
        assertEquals(2, (admin.get("/api/v1/issues/1").json()["cycle"] as Map<*, *>)["number"])
        assertEquals(1, (admin.get("/api/v1/issues/2").json()["cycle"] as Map<*, *>)["number"])
    }
}
