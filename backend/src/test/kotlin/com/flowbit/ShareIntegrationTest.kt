package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
        // Where and what it is, and what the card over it says (D-201): no body, no people on it, no history (D-214).
        assertEquals(
            setOf("number", "key", "title", "status", "priority", "startDate", "dueDate", "project", "createdBy", "labels"),
            issues.first().keys,
        )
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
    fun `링크를 여러 번 열어도 접속 기록은 남기지 않고 그 표도 없다`() {
        val token = share(admin, "2026-10", "assigned")["token"]
        val visitor = client()

        assertEquals(200, visitor.get("/api/v1/public/shares/$token").status)
        assertEquals(200, visitor.get("/api/v1/public/shares/$token").status)

        // V5 took the table away (D-208); there is nothing to write an opening to.
        assertEquals(0, count("select count(*) from information_schema.tables where table_name = 'share_accesses'"))
        assertEquals(1, count("select count(*) from share_links"))
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

    @Test
    fun `티켓 링크는 같은 티켓이면 같은 URL 이고 다른 티켓이면 다른 URL 이다`() {
        issue("""{"title":"첫 티켓"}""")
        issue("""{"title":"둘째 티켓"}""")

        val first = issueShare(1)
        assertEquals(first["token"], issueShare(1)["token"])
        assertEquals(mapOf("number" to "1"), first["params"])
        assertNotEquals(first["token"], issueShare(2)["token"])
        assertEquals(2, count("select count(*) from share_links"))
    }

    @Test
    fun `링크를 아는 사람은 로그인 없이 티켓과 하위 티켓과 문서를 읽는다`() {
        val body = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"본문 내용"}]}]}"""
        val root = issue("""{"title":"공유할 티켓","content":$body,"assigneeIds":["$adminId"]}""")
        val child = issue("""{"title":"하위 티켓","parentId":"${root["id"]}"}""")
        issue("""{"title":"손자 티켓","parentId":"${child["id"]}"}""")
        issue("""{"title":"남의 티켓"}""")
        admin.post("/api/v1/issues/1/comments", """{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"비밀 댓글"}]}]}}""")
        val document = admin.post("/api/v1/issues/2/documents", """{"title":"설계 메모"}""").json()["id"]
        admin.patch("/api/v1/documents/$document", """{"content":$body}""")
        val link = issueShare(1)
        val visitor = client()

        val opened = visitor.get("/api/v1/public/shares/${link["token"]}")

        assertEquals(200, opened.status)
        assertEquals(false, opened.body.contains("비밀 댓글"))
        val shared = opened.json()
        assertEquals("ISSUE", shared["kind"])
        @Suppress("UNCHECKED_CAST")
        val rootPage = shared["content"] as Map<String, Any?>
        assertEquals("공유할 티켓", rootPage["title"])
        assertEquals("issue", rootPage["type"])
        assertEquals(emptyList<Any>(), rootPage["trail"])
        assertEquals(listOf("하위 티켓"), titles(rootPage["subIssues"]))
        assertEquals(emptyList<Any>(), rootPage["documents"])
        assertEquals("doc", (rootPage["content"] as Map<*, *>)["type"])
        // Of people only a display name, and this person has none: the login id is not told.
        assertEquals(emptyList<Any>(), rootPage["assignees"])
        assertEquals(false, opened.body.contains("admin"))
        assertEquals(false, rootPage.keys.any { it in setOf("comments", "activity", "reactions", "attachments", "git", "customProperties") })

        // A sub-issue, a sub-issue of it, and a document of the sub-issue are all opened inside the same link.
        val childPage = item(visitor, link["token"], "issue", "2")
        assertEquals("하위 티켓", childPage["title"])
        assertEquals(listOf("공유할 티켓"), titles(childPage["trail"]))
        assertEquals(listOf("손자 티켓"), titles(childPage["subIssues"]))
        assertEquals(listOf("설계 메모"), titles(childPage["documents"]))
        val grandchildPage = item(visitor, link["token"], "issue", "3")
        assertEquals(listOf("공유할 티켓", "하위 티켓"), titles(grandchildPage["trail"]))
        val documentPage = item(visitor, link["token"], "document", document as String)
        assertEquals("document", documentPage["type"])
        assertEquals("설계 메모", documentPage["title"])
        assertEquals("하위 티켓", (documentPage["issue"] as Map<*, *>)["title"])
        assertEquals("doc", (documentPage["content"] as Map<*, *>)["type"])
    }

    @Test
    fun `본문의 사진과 파일은 링크 안의 주소로 바뀌어 로그인 없이 받을 수 있다`() {
        val image = admin.postFile("/api/v1/images", "shot.png", "image/png", png()).json()["url"] as String
        val file = admin.postFile("/api/v1/editor-files", "메모.txt", "text/plain", "내용".toByteArray()).json()
        val body =
            """{"type":"doc","content":[{"type":"image","attrs":{"src":"$image"}},""" +
                """{"type":"fileBlock","attrs":{"src":"${file["url"]}","name":"메모.txt","size":6}}]}"""
        issue("""{"title":"사진과 파일","content":$body}""")
        val token = issueShare(1)["token"]
        val visitor = client()

        val page = visitor.get("/api/v1/public/shares/$token").json()["content"] as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val nodes = (page["content"] as Map<String, Any?>)["content"] as List<Map<String, Any?>>
        val imageSrc = (nodes[0]["attrs"] as Map<*, *>)["src"] as String
        val fileSrc = (nodes[1]["attrs"] as Map<*, *>)["src"] as String
        assertEquals("/api/v1/public/shares/$token/files/${image.substringAfterLast('/')}", imageSrc)
        assertEquals("/api/v1/public/shares/$token/editor-files/${(file["url"] as String).substringAfterLast('/')}", fileSrc)

        val picture = visitor.get(imageSrc)
        assertEquals(200, picture.status)
        assertEquals("image/webp", picture.header("Content-Type"))
        assertEquals(200, visitor.get("$imageSrc/original?name=shot.png").status)
        val download = visitor.get("$fileSrc?name=" + java.net.URLEncoder.encode("메모.txt", "UTF-8"))
        assertEquals(200, download.status)
        assertEquals("내용", download.body)
    }

    @Test
    fun `공유 캘린더 카드에는 활동 기록이 들어 있지 않다 (D-214)`() {
        issue("""{"title":"카드 티켓","assigneeIds":["$adminId"],"dueDate":"2026-10-15"}""")
        admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""")
        admin.patch("/api/v1/issues/1", """{"priority":"HIGH"}""")
        val token = share(admin, "2026-10", "assigned")["token"]

        @Suppress("UNCHECKED_CAST")
        val issues = (client().get("/api/v1/public/shares/$token").json()["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>

        // The card has what the issue is, not what was done to it.
        assertEquals("HIGH", issues.single()["priority"])
        assertEquals("IN_PROGRESS", issues.single()["status"])
        assertEquals(false, "recentActivity" in issues.single())
    }

    @Test
    fun `티켓의 최근 활동만 최신 순으로 개수를 정해 읽는다`() {
        issue("""{"title":"활동 티켓"}""")
        admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""")
        admin.patch("/api/v1/issues/1", """{"priority":"HIGH"}""")

        val recent = admin.get("/api/v1/issues/1/activity/recent?limit=2")

        assertEquals(200, recent.status)
        assertEquals(listOf("PRIORITY_CHANGED", "STATUS_CHANGED"), recent.jsonList().map { it["type"] })
        assertEquals(3, admin.get("/api/v1/issues/1/activity/recent").jsonList().size)
    }

    @Test
    fun `내 링크 목록은 쓰고 있는 링크를 보여 주고 삭제하면 사라진다`() {
        issue("""{"title":"공유할 티켓"}""")
        val calendar = share(admin, "2026-10", "assigned")
        val shared = issueShare(1)
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }

        val page = admin.get("/api/v1/shares").json()

        assertEquals(2, page["total"])
        assertEquals(mapOf("ISSUE" to 1, "MY_ISSUES_CALENDAR" to 1), page["kinds"])

        @Suppress("UNCHECKED_CAST")
        val listed = page["items"] as List<Map<String, Any?>>

        // The newest first, each with what it is about; nothing of who opened it (D-208).
        assertEquals(listOf(shared["id"], calendar["id"]), listed.map { it["id"] })
        assertEquals("FB-1 공유할 티켓", listed[0]["subject"])
        assertEquals(null, listed[1]["subject"])
        assertEquals(false, listed.any { it.containsKey("accessCount") || it.containsKey("lastAccessedAt") })
        assertEquals(mapOf("month" to "2026-10", "scope" to "assigned"), listed[1]["params"])
        assertEquals("/s/${calendar["token"]}", listed[1]["path"])
        // Nobody else's links are in it.
        assertEquals(0, member.get("/api/v1/shares").json()["total"])
        assertEquals(emptyList<Any>(), member.get("/api/v1/shares").json()["items"])

        assertEquals(204, admin.delete("/api/v1/shares/${calendar["id"]}").status)
        assertEquals(listOf(shared["id"]), titlesOf(admin.get("/api/v1/shares").json()["items"], "id"))
    }

    @Test
    fun `내 링크 목록은 쪽으로 나뉘고 종류로 걸러 볼 수 있다`() {
        issue("""{"title":"공유할 티켓"}""")
        val calendarOctober = share(admin, "2026-10", "assigned")
        val calendarNovember = share(admin, "2026-11", "assigned")
        val timeline = timelineShare(admin, "2026-10", "2026-12", "assigned")
        val shared = issueShare(1)

        // Pages: two at a time, the newest first; the total is of all of them.
        val first = admin.get("/api/v1/shares?limit=2").json()
        assertEquals(4, first["total"])
        assertEquals(listOf(shared["id"], timeline["id"]), titlesOf(first["items"], "id"))
        val second = admin.get("/api/v1/shares?limit=2&offset=2").json()
        assertEquals(4, second["total"])
        assertEquals(listOf(calendarNovember["id"], calendarOctober["id"]), titlesOf(second["items"], "id"))

        // One kind: the total is of that kind, and how many of each kind stays what it is.
        val calendars = admin.get("/api/v1/shares?kind=MY_ISSUES_CALENDAR").json()
        assertEquals(2, calendars["total"])
        assertEquals(mapOf("ISSUE" to 1, "MY_ISSUES_CALENDAR" to 2, "MY_ISSUES_TIMELINE" to 1), calendars["kinds"])
        assertEquals(listOf(calendarNovember["id"], calendarOctober["id"]), titlesOf(calendars["items"], "id"))
    }

    @Test
    fun `타임라인은 시작일 없이 기한만 있는 티켓을 만든 날부터가 아니라 그 하루로 본다`() {
        // A day's work written down long after: due in March 2020, made now.
        issue("""{"title":"하루짜리 일","assigneeIds":["$adminId"],"dueDate":"2020-03-10"}""")
        val march = timelineShare(admin, "2020-03", "2020-03", "assigned")["token"]
        val thisMonth = YearMonth.now(clock)
        val now = timelineShare(admin, thisMonth.toString(), thisMonth.toString(), "assigned")["token"]

        // It is on its day, and not drawn on from that day to the day it was made.
        assertEquals(listOf("하루짜리 일"), timelineTitles(march))
        assertEquals(emptyList(), timelineTitles(now))
    }

    private fun timelineTitles(token: Any?): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        val issues = (client().get("/api/v1/public/shares/$token").json()["content"] as Map<String, Any?>)["issues"] as List<Map<String, Any?>>
        return issues.map { it["title"] }
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

    private fun issueShare(number: Int): Map<String, Any?> {
        val response = admin.post("/api/v1/shares", """{"kind":"ISSUE","params":{"number":"$number"}}""")
        assertEquals(200, response.status)
        return response.json()
    }

    private fun item(
        visitor: ApiClient,
        token: Any?,
        type: String,
        id: String,
    ): Map<String, Any?> {
        val response = visitor.get("/api/v1/public/shares/$token/items/$type/$id")
        assertEquals(200, response.status)
        @Suppress("UNCHECKED_CAST")
        return response.json()["content"] as Map<String, Any?>
    }

    private fun titlesOf(
        list: Any?,
        key: String,
    ): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return (list as List<Map<String, Any?>>).map { it[key] }
    }

    private fun titles(list: Any?): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return (list as List<Map<String, Any?>>).map { it["title"] }
    }

    private fun png(): ByteArray {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun issue(json: String): Map<String, Any?> {
        val response = admin.post("/api/v1/issues", json)
        assertEquals(201, response.status)
        return response.json()
    }
}
