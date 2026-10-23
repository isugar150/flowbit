package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** docs/features/sharing.md */
class ShareIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var adminId: String

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
        adminId = admin.me().json()["id"] as String
    }

    @Test
    fun `같은 사람이 같은 화면과 달과 탭을 공유하면 같은 URL 이고 달이나 탭이나 사람이 다르면 다른 URL 이다`() {
        val first = share(admin, "2026-10", "assigned")
        val again = share(admin, "2026-10", "assigned")

        assertEquals(first["id"], again["id"])
        assertEquals(first["token"], again["token"])
        assertEquals("/s/${first["token"]}", first["path"])
        assertEquals(43, (first["token"] as String).length)
        assertEquals(mapOf("month" to "2026-10", "scope" to "assigned"), first["params"])
        assertEquals(1, count("select count(*) from share_links"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'SHARE_LINK_CREATED'"))

        assertNotEquals(first["token"], share(admin, "2026-11", "assigned")["token"])
        assertNotEquals(first["token"], share(admin, "2026-10", "created")["token"])
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }
        assertNotEquals(first["token"], share(member, "2026-10", "assigned")["token"])
        assertEquals(4, count("select count(*) from share_links"))
    }

    @Test
    fun `링크를 아는 사람은 로그인 없이 공유한 사람의 그 달에 걸친 티켓만 본다`() {
        issue("""{"title":"10월 중순","assigneeIds":["$adminId"],"dueDate":"2026-10-15"}""")
        issue("""{"title":"9월 말부터 10월 초까지","assigneeIds":["$adminId"],"startDate":"2026-09-28","dueDate":"2026-10-02"}""")
        issue("""{"title":"11월","assigneeIds":["$adminId"],"dueDate":"2026-11-05"}""")
        issue("""{"title":"담당자 없음","dueDate":"2026-10-10"}""")
        issue("""{"title":"기한 없음","assigneeIds":["$adminId"]}""")
        val deleted = issue("""{"title":"지운 티켓","assigneeIds":["$adminId"],"dueDate":"2026-10-20"}""")
        admin.delete("/api/v1/issues/${deleted["number"]}")
        val token = share(admin, "2026-10", "assigned")["token"]

        val visitor = client()
        val opened = visitor.get("/api/v1/public/shares/$token")

        assertEquals(200, opened.status)
        assertEquals("no-store", opened.header("Cache-Control"))
        val body = opened.json()
        assertEquals("MY_ISSUES_CALENDAR", body["kind"])
        assertEquals(mapOf("month" to "2026-10", "scope" to "assigned"), body["params"])
        @Suppress("UNCHECKED_CAST")
        val issues = (body["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>
        assertEquals(listOf("9월 말부터 10월 초까지", "10월 중순"), issues.map { it["title"] })
        // Only where and what it is: no body, project, people.
        assertEquals(setOf("number", "key", "title", "status", "startDate", "dueDate"), issues.first().keys)
        assertEquals("2026-09-28", issues.first()["startDate"])
        assertEquals("2026-10-02", issues.first()["dueDate"])
        assertEquals("BACKLOG", issues.last()["status"])

        // The other tab: what the person made.
        val created = visitor.get("/api/v1/public/shares/${share(admin, "2026-10", "created")["token"]}").json()

        @Suppress("UNCHECKED_CAST")
        val madeByAdmin = (created["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>
        assertEquals(listOf("9월 말부터 10월 초까지", "담당자 없음", "10월 중순"), madeByAdmin.map { it["title"] })
    }

    @Test
    fun `링크가 열릴 때마다 접속 기록이 남고 공유한 사람이 읽는다`() {
        val link = share(admin, "2026-10", "assigned")
        val token = link["token"]
        val visitor = client()

        visitor.get("/api/v1/public/shares/$token")
        clock.advance(Duration.ofMinutes(5))
        visitor.get("/api/v1/public/shares/$token?embedded=true&referrer=https://blog.example.com/calendar")

        assertEquals(2, count("select count(*) from share_accesses"))
        val history = admin.get("/api/v1/shares/${link["id"]}/accesses").json()
        assertEquals(2, history["total"])
        @Suppress("UNCHECKED_CAST")
        val items = history["items"] as List<Map<String, Any?>>
        // Newest first.
        assertEquals(true, items[0]["embedded"])
        assertEquals("https://blog.example.com/calendar", items[0]["referrer"])
        assertEquals(false, items[1]["embedded"])
        assertEquals(null, items[1]["referrer"])
        for (item in items) {
            assertNotNull(item["ipAddress"])
            assertTrue((item["userAgent"] as String).isNotBlank())
            assertNotNull(item["accessedAt"])
        }
    }

    @Test
    fun `공유를 중지하면 링크가 끝나고 다시 공유하면 다른 URL 이 나온다`() {
        val link = share(admin, "2026-10", "assigned")

        assertEquals(204, admin.delete("/api/v1/shares/${link["id"]}").status)

        assertEquals(1, count("select count(*) from share_links where revoked_at is not null"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'SHARE_LINK_REVOKED'"))
        val renewed = share(admin, "2026-10", "assigned")
        assertNotEquals(link["id"], renewed["id"])
        assertNotEquals(link["token"], renewed["token"])
        assertEquals(renewed["token"], share(admin, "2026-10", "assigned")["token"])
        assertEquals(200, client().get("/api/v1/public/shares/${renewed["token"]}").status)
    }

    @Test
    fun `타임라인은 같은 범위면 같은 URL 이고 범위나 탭이 다르면 다른 URL 이다`() {
        val first = timelineShare(admin, "2026-10", "2026-12", "assigned")
        val again = timelineShare(admin, "2026-10", "2026-12", "assigned")

        assertEquals(first["token"], again["token"])
        assertEquals(mapOf("from" to "2026-10", "to" to "2026-12", "scope" to "assigned"), first["params"])
        assertNotEquals(first["token"], timelineShare(admin, "2026-10", "2027-01", "assigned")["token"])
        assertNotEquals(first["token"], timelineShare(admin, "2026-09", "2026-12", "assigned")["token"])
        assertNotEquals(first["token"], timelineShare(admin, "2026-10", "2026-12", "created")["token"])
        // The same months are another page than the calendar's.
        assertNotEquals(share(admin, "2026-10", "assigned")["token"], timelineShare(admin, "2026-10", "2026-10", "assigned")["token"])
        assertEquals(6, count("select count(*) from share_links"))
        // A whole two years, the last month included, is the longest.
        assertEquals(200, admin.post("/api/v1/shares", timelineBody("2026-01", "2027-12", "assigned")).status)
    }

    @Test
    fun `타임라인 링크는 로그인 없이 고른 기간에 걸친 티켓을 프로젝트 이름과 함께 보여 준다`() {
        val project = admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"]
        issue("""{"title":"프로젝트의 10월","assigneeIds":["$adminId"],"projectId":"$project","startDate":"2026-10-10","dueDate":"2026-10-15"}""")
        issue("""{"title":"12월에서 내년 1월까지","assigneeIds":["$adminId"],"startDate":"2026-12-01","dueDate":"2027-01-10"}""")
        issue("""{"title":"기간 앞","assigneeIds":["$adminId"],"startDate":"2026-09-20","dueDate":"2026-09-30"}""")
        issue("""{"title":"기간 뒤","assigneeIds":["$adminId"],"startDate":"2027-01-02","dueDate":"2027-01-20"}""")
        issue("""{"title":"담당자 없음","dueDate":"2026-11-10"}""")
        val token = timelineShare(admin, "2026-10", "2026-12", "assigned")["token"]

        val opened = client().get("/api/v1/public/shares/$token")

        assertEquals(200, opened.status)
        val body = opened.json()
        assertEquals("MY_ISSUES_TIMELINE", body["kind"])
        assertEquals(mapOf("from" to "2026-10", "to" to "2026-12", "scope" to "assigned"), body["params"])
        @Suppress("UNCHECKED_CAST")
        val issues = (body["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>
        assertEquals(listOf("프로젝트의 10월", "12월에서 내년 1월까지"), issues.map { it["title"] })
        assertEquals(listOf("결제 개편", null), issues.map { it["project"] })
        // Where and what it is, and the project's name: no id of the project, no people, no text, nothing above it.
        assertEquals(
            setOf("number", "key", "title", "status", "startDate", "dueDate", "createdAt", "updatedAt", "project"),
            issues.first().keys,
        )
        assertEquals(1, count("select count(*) from share_accesses"))
    }

    @Test
    fun `기한이 없는 진행 중 티켓은 오늘까지 이어진 것으로 보고 그 기간에 오늘이 들면 나온다`() {
        issue("""{"title":"기한 없음","assigneeIds":["$adminId"],"status":"IN_PROGRESS"}""")
        val thisMonth = YearMonth.now(clock)
        val here = timelineShare(admin, thisMonth.toString(), thisMonth.plusMonths(1).toString(), "assigned")["token"]
        val later = timelineShare(admin, "2099-01", "2099-02", "assigned")["token"]

        @Suppress("UNCHECKED_CAST")
        fun titles(token: Any?) =
            ((client().get("/api/v1/public/shares/$token").json()["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>).map { it["title"] }

        assertEquals(listOf("기한 없음"), titles(here))
        assertEquals(emptyList(), titles(later))
    }

    private fun share(
        who: ApiClient,
        month: String,
        scope: String,
    ): Map<String, Any?> {
        val response = who.post("/api/v1/shares", """{"kind":"MY_ISSUES_CALENDAR","params":{"month":"$month","scope":"$scope"}}""")
        assertEquals(200, response.status)
        return response.json()
    }

    private fun timelineBody(
        from: String,
        to: String,
        scope: String,
    ) = """{"kind":"MY_ISSUES_TIMELINE","params":{"from":"$from","to":"$to","scope":"$scope"}}"""

    private fun timelineShare(
        who: ApiClient,
        from: String,
        to: String,
        scope: String,
    ): Map<String, Any?> {
        val response = who.post("/api/v1/shares", timelineBody(from, to, scope))
        assertEquals(200, response.status)
        return response.json()
    }

    private fun issue(json: String): Map<String, Any?> {
        val response = admin.post("/api/v1/issues", json)
        assertEquals(201, response.status)
        return response.json()
    }
}
