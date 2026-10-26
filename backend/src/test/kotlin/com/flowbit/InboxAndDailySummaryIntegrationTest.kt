package com.flowbit

import com.flowbit.notification.DailySummaryBuilder
import com.flowbit.notification.DailySummaryService
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private val SEOUL = ZoneId.of("Asia/Seoul")
private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

/** The inbox behind the bell, and the daily summary an administrator switches on for a member (D-216). */
class InboxAndDailySummaryIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var summaries: DailySummaryService

    @Autowired
    private lateinit var builder: DailySummaryBuilder

    private lateinit var admin: ApiClient
    private lateinit var kim: ApiClient
    private lateinit var kimId: String

    @BeforeEach
    fun start() {
        admin = client().also { it.setup("admin", PASSWORD) }
        kimId = admin.post("/api/v1/admin/members", """{"loginId":"kimjm","password":"$PASSWORD"}""").json()["id"] as String
        kim = client().also { it.login("kimjm", PASSWORD) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `티켓이 바뀌면 구독자의 수신함에 쌓이고 읽음으로 표시하면 읽지 않은 수가 줄어든다`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류","assigneeIds":["$kimId"]}""")
        admin.post("/api/v1/issues/1/notifications/flush", "{}")
        awaitUnread(kim, 1)

        val inbox = kim.get("/api/v1/me/inbox").json()
        assertEquals(1, inbox["unreadCount"])
        val first = (inbox["items"] as List<Map<String, Any?>>).single()
        assertEquals("[FB-1] 로그인 오류", first["title"])
        assertTrue((first["headline"] as String).endsWith("티켓을 만들었습니다"))
        assertEquals("/issue/FB-1", first["path"])
        assertEquals(false, first["read"])
        // What a person did themselves is not told to them.
        assertEquals(0, admin.get("/api/v1/me/inbox").json()["unreadCount"])

        assertEquals(204, kim.post("/api/v1/me/inbox/read", """{"ids":["${first["id"]}"]}""").status)
        assertEquals(0, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
        assertEquals(true, ((kim.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>).single())["read"])

        // A later change comes in as a new one, newest first, and "all" reads everything.
        admin.patch("/api/v1/issues/1", """{"priority":"HIGH"}""")
        admin.post("/api/v1/issues/1/notifications/flush", "{}")
        awaitUnread(kim, 1)
        val items = kim.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertTrue((items.first()["headline"] as String).endsWith("티켓을 수정했습니다"))
        assertTrue((items.first()["lines"] as List<String>).any { it.startsWith("우선순위") })
        assertEquals(204, kim.post("/api/v1/me/inbox/read", """{"all":true}""").status)
        assertEquals(0, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
    }

    @Test
    fun `새 댓글은 구독자 모두에게 가고 답글은 태그된 사람과 대화한 사람에게만 간다 (D-218)`() {
        admin.post("/api/v1/issues", """{"title":"태그 확인"}""")
        val mention = """{"type":"mention","attrs":{"id":"$kimId","label":"kimjm"}}"""

        fun comment(content: String) =
            admin.post("/api/v1/issues/1/comments", """{"content":{"type":"doc","content":[{"type":"paragraph","content":[$content]}]}}""")
        val text = """{"type":"text","text":"확인 부탁"}"""

        // Tagged: told at once, and from then on following the issue.
        assertEquals(201, comment("$mention,$text").status)
        awaitUnread(kim, 1)
        // Tagged again in another comment: told again.
        assertEquals(201, comment("$mention,$text").status)
        awaitUnread(kim, 2)
        // A new comment that does not tag them is theirs too: they follow the issue.
        val third = comment(text)
        assertEquals(201, third.status)
        awaitUnread(kim, 3)

        // A reply is for the ones it is about: not for kim, who follows but is neither tagged nor in the conversation...
        fun reply(content: String) =
            admin.post(
                "/api/v1/issues/1/comments",
                """{"parentId":"${third.json()["id"]}","content":{"type":"doc","content":[{"type":"paragraph","content":[$content]}]}}""",
            )
        assertEquals(201, reply(text).status)
        Thread.sleep(700)
        assertEquals(3, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
        // ...unless the reply tags them.
        assertEquals(201, reply("$mention,$text").status)
        awaitUnread(kim, 4)

        // What changes about the issue is told to its followers, the one tagged among them.
        admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""")
        admin.post("/api/v1/issues/1/notifications/flush", "{}")
        awaitUnread(kim, 5)
    }

    @Test
    fun `문서의 새 댓글도 문서를 구독한 사람에게 가고 답글은 가지 않는다 (D-218)`() {
        admin.post("/api/v1/issues", """{"title":"문서 확인"}""")
        val documentId = admin.post("/api/v1/issues/1/documents", """{"title":"설계"}""").json()["id"] as String
        val path = "/api/v1/content/document/$documentId"
        assertEquals(200, kim.put("$path/subscription", "{}").status)

        fun body(text: String) = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""

        // A new comment on the document is for the people who follow it.
        val parent = admin.post("$path/comments", """{"content":${body("검토 부탁")}}""").json()["id"]
        awaitUnread(kim, 1)

        // A reply is for the author of the comment and whoever is tagged: not for kim.
        assertEquals(201, admin.post("$path/comments", """{"content":${body("보충")},"parentId":"$parent"}""").status)
        Thread.sleep(700)
        assertEquals(1, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
    }

    @Test
    fun `그 화면을 열면 그 화면에 대한 읽지 않은 알림이 읽음이 된다 (D-217)`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류","assigneeIds":["$kimId"]}""")
        admin.post("/api/v1/issues", """{"title":"결제 오류","assigneeIds":["$kimId"]}""")
        admin.post("/api/v1/issues/1/notifications/flush", "{}")
        admin.post("/api/v1/issues/2/notifications/flush", "{}")
        awaitUnread(kim, 2)

        // Opening FB-1 reads what was told about FB-1 and nothing else.
        val opened = kim.post("/api/v1/me/inbox/read-path", """{"path":"/issue/FB-1"}""")
        assertEquals(200, opened.status)
        assertEquals(1, opened.json()["marked"])
        assertEquals(1, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
        // Again there is nothing left to read there.
        assertEquals(0, kim.post("/api/v1/me/inbox/read-path", """{"path":"/issue/FB-1"}""").json()["marked"])

        // The case of the address does not matter, and a page nothing was told about reads nothing.
        assertEquals(0, kim.post("/api/v1/me/inbox/read-path", """{"path":"/issue/FB-9"}""").json()["marked"])
        assertEquals(1, kim.post("/api/v1/me/inbox/read-path", """{"path":"/issue/fb-2"}""").json()["marked"])
        assertEquals(0, kim.get("/api/v1/me/inbox/unread-count").json()["count"])
    }

    @Test
    fun `하루 요약은 처음에는 꺼져 있고 관리자가 켜고 시각을 정한다`() {
        val off = admin.get("/api/v1/admin/members/$kimId/daily-summary").json()
        assertEquals(false, off["enabled"])
        assertEquals("09:00", off["time"])
        assertEquals("Asia/Seoul", off["timezone"])
        assertNull(off["lastSentOn"])

        val saved = admin.put("/api/v1/admin/members/$kimId/daily-summary", """{"enabled":true,"time":"08:30"}""")

        assertEquals(200, saved.status)
        assertEquals(true, saved.json()["enabled"])
        assertEquals("08:30", admin.get("/api/v1/admin/members/$kimId/daily-summary").json()["time"])
        // Only an administrator reaches it.
        assertEquals(403, kim.get("/api/v1/admin/members/$kimId/daily-summary").status)
    }

    @Test
    fun `정한 시각이 지나면 보낸 날짜가 남는다`() {
        val today = today()
        val now =
            clock
                .instant()
                .atZone(SEOUL)
                .toLocalTime()
                .withSecond(0)
                .withNano(0)
        admin.post("/api/v1/issues", """{"title":"오늘 마감","assigneeIds":["$kimId"],"dueDate":"$today"}""")

        // Two hours from now (or, round midnight, hours past it): the time has not come.
        admin.put("/api/v1/admin/members/$kimId/daily-summary", """{"enabled":true,"time":"${now.plusHours(2).format(HOUR_MINUTE)}"}""")
        runBlockingSendDue()
        assertNull(admin.get("/api/v1/admin/members/$kimId/daily-summary").json()["lastSentOn"])

        // An hour ago: it has, and it went out for today.
        admin.put("/api/v1/admin/members/$kimId/daily-summary", """{"enabled":true,"time":"${now.minusHours(1).format(HOUR_MINUTE)}"}""")
        runBlockingSendDue()
        assertEquals(today.toString(), admin.get("/api/v1/admin/members/$kimId/daily-summary").json()["lastSentOn"])
    }

    @Test
    fun `하루 요약에는 기한과 상태별 티켓과 새로 할당된 티켓이 들어가고 비어 있으면 보내지 않는다`() {
        val today = today()
        admin.post("/api/v1/issues", """{"title":"기한 지난 일","assigneeIds":["$kimId"],"dueDate":"${today.minusDays(1)}"}""")
        admin.post("/api/v1/issues", """{"title":"오늘 마감 일","assigneeIds":["$kimId"],"dueDate":"$today"}""")
        admin.post("/api/v1/issues", """{"title":"진행 중 일","assigneeIds":["$kimId"],"status":"IN_PROGRESS"}""")
        admin.post("/api/v1/issues", """{"title":"끝난 일","assigneeIds":["$kimId"],"status":"DONE","dueDate":"${today.minusDays(3)}"}""")

        val lines = kotlinx.coroutines.runBlocking { builder.lines(UUID.fromString(kimId), today, clock.instant().minus(Duration.ofHours(24))) }

        assertTrue(lines.any { it.startsWith("기한 지남 1건: FB-1 기한 지난 일") }, "$lines")
        assertTrue(lines.any { it.startsWith("오늘 마감 1건: FB-2 오늘 마감 일") }, "$lines")
        assertTrue(lines.any { it.startsWith("어제 이후 새로 할당된 티켓 3건") }, "$lines")
        // The finished one is in none of them.
        assertTrue(lines.none { it.contains("끝난 일") }, "$lines")
        assertTrue(lines.any { it == "내게 할당된 진행할 티켓 3건 (백로그 2 · 진행 중 1)" }, "$lines")

        // "지금 보내 보기": sent for the member with tickets, nothing for the one with none.
        assertEquals(true, admin.post("/api/v1/admin/members/$kimId/daily-summary/send").json()["sent"])
        val adminId = admin.me().json()["id"]
        assertEquals(false, admin.post("/api/v1/admin/members/$adminId/daily-summary/send").json()["sent"])
    }

    /** Today in Seoul. Just after midnight there the clock is moved on two hours, so that "an hour ago" is still today. */
    private fun today(): LocalDate {
        if (clock.instant().atZone(SEOUL).toLocalTime() < LocalTime.of(1, 30)) clock.advance(Duration.ofHours(2))
        return clock.instant().atZone(SEOUL).toLocalDate()
    }

    private fun runBlockingSendDue() = kotlinx.coroutines.runBlocking { summaries.sendDue() }

    private fun awaitUnread(
        person: ApiClient,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (person.get("/api/v1/me/inbox/unread-count").json()["count"] != count) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $count unread" }
            Thread.sleep(50)
        }
    }
}
