package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WORK_PASSWORD = "correct-horse-battery"

class McpWorkLogsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var member: ApiClient
    private lateinit var memberId: String
    private lateinit var projectId: String
    private lateinit var token: String
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val period = mapOf("from" to "2026-10-01", "to" to "2026-10-31", "timezone" to "Asia/Seoul")

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", WORK_PASSWORD) }
        memberId = admin.post("/api/v1/admin/members", """{"loginId":"worker","password":"$WORK_PASSWORD"}""").json()["id"] as String
        projectId = admin.post("/api/v1/projects", """{"name":"작업 프로젝트"}""").json()["id"] as String
        admin.put("/api/v1/projects/$projectId/members/$memberId", """{"role":"EDITOR"}""")
        member = client().also { it.login("worker", WORK_PASSWORD) }
        token = member.post("/api/v1/me/access-tokens", """{"name":"보고서","scope":"READ","expiresInDays":30}""").json()["token"] as String
    }

    private fun request(
        path: String,
        body: Any,
    ): ApiResponse {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$port$path"))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return ApiResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun query(
        name: String = "search_comments",
        filters: Map<String, Any?> = emptyMap(),
    ): ApiResponse = request("/api/v1/integrations/mcp/tools/$name", period + filters)

    @Suppress("UNCHECKED_CAST")
    private fun items(response: ApiResponse): List<Map<String, Any?>> {
        assertEquals(200, response.status, response.body)
        return response.json()["items"] as List<Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun comment(item: Map<String, Any?>): Map<String, Any?> = item["comment"] as Map<String, Any?>

    private fun issue(
        who: ApiClient = member,
        project: String? = projectId,
    ): Map<String, Any?> {
        val response = who.post("/api/v1/issues", json.writeValueAsString(mapOf("title" to "기록 대상", "projectId" to project)))
        assertEquals(201, response.status, response.body)
        val created = response.json()
        execute("update issue_activities set created_at = '2026-10-15T00:00:00Z' where issue_id = '${created["id"]}'")
        return created
    }

    private fun content(text: String): Map<String, Any?> =
        mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))))

    private fun addComment(
        number: Any?,
        text: String,
        who: ApiClient = member,
        parent: Any? = null,
    ): String {
        val response = who.post("/api/v1/issues/$number/comments", json.writeValueAsString(mapOf("content" to content(text), "parentId" to parent)))
        assertEquals(201, response.status, response.body)
        val id = response.json()["id"] as String
        stamp("issue_comments", id)
        return id
    }

    private fun stamp(
        table: String,
        id: String,
        created: String = "2026-10-15T01:00:00Z",
        updated: String = created,
    ) {
        execute("update $table set created_at = '$created', updated_at = '$updated' where id = '$id'")
    }

    @Test
    fun `MCP는 읽기 토큰으로 작업 근거와 댓글 원문을 발견하고 호출한다`() {
        val issue = issue()
        val id = addComment(issue["number"], "결제 오류를 재현하고 원인을 찾았습니다")
        member.patch("/api/v1/issues/${issue["number"]}", """{"status":"IN_PROGRESS"}""")
        execute("update issue_activities set created_at = '2026-10-15T02:00:00Z' where issue_id = '${issue["id"]}' and type = 'STATUS_CHANGED'")
        val discovery = request("/api/v1/mcp", mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))
        assertTrue(discovery.body.contains("list_work_logs"))
        assertTrue(discovery.body.contains("search_comments"))
        val called =
            request(
                "/api/v1/mcp",
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 2,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "list_work_logs", "arguments" to period),
                ),
            )
        assertTrue(called.body.contains("\"isError\":false"), called.body)
        val logs = items(query("list_work_logs"))
        assertEquals(setOf("CREATED", "COMMENT_ADDED", "STATUS_CHANGED"), logs.map { it["type"] }.toSet())
        val evidence = comment(logs.single { it["kind"] == "COMMENT_ADDED" })
        assertEquals(id, evidence["id"])
        assertTrue(evidence["markdown"].toString().contains("결제 오류"))
        assertTrue(evidence["content"] is Map<*, *>)
        assertEquals("결제 오류를 재현하고 원인을 찾았습니다", evidence["body"])
        assertEquals("no-store", query().header("Cache-Control"))
        assertTrue(member.get("/api/v1/issues/${issue["number"]}/activity").body.contains(id))
        val legacy = request("/api/v1/integrations/mcp/tools/list_comments", mapOf("issue" to issue["number"].toString()))
        assertTrue(legacy.json().containsKey("comments"))
        assertTrue(legacy.json().containsKey("activity"))
    }

    @Test
    fun `본인 필터는 담당자와 무관하며 타인과 전체 및 특정 댓글을 조회한다`() {
        val issue = issue(admin)
        val mine = addComment(issue["number"], "API 구현", member)
        val other = addComment(issue["number"], "API 검토", admin)
        val reply = addComment(issue["number"], "수정 완료", member, other)
        assertEquals(setOf(mine, reply), items(query()).map { it["id"] }.toSet())
        assertEquals(3, items(query(filters = mapOf("userId" to "all"))).size)
        assertEquals(setOf(mine, reply), items(query(filters = mapOf("userId" to memberId))).map { it["id"] }.toSet())
        assertEquals(listOf(other), items(query(filters = mapOf("userId" to "all", "commentId" to other))).map { it["id"] })
        assertEquals(listOf(reply), items(query(filters = mapOf("parentId" to other))).map { it["id"] })
        assertEquals(listOf(mine), items(query(filters = mapOf("q" to "api 구현", "issue" to issue["number"].toString()))).map { it["id"] })
        assertTrue(items(query(filters = mapOf("q" to "%"))).isEmpty())
    }

    @Test
    fun `날짜 양 끝과 사용자 시간대를 적용하고 오래된 댓글의 마지막 수정을 구분한다`() {
        val issue = issue()
        val first = addComment(issue["number"], "첫 경계")
        val last = addComment(issue["number"], "끝 경계")
        val outside = addComment(issue["number"], "범위 밖")
        val edited = addComment(issue["number"], "현재 수정된 본문")
        stamp("issue_comments", first, "2026-10-14T15:00:00Z")
        stamp("issue_comments", last, "2026-10-15T14:59:59.999999Z")
        stamp("issue_comments", outside, "2026-10-15T15:00:00Z")
        stamp("issue_comments", edited, "2026-10-01T00:00:00Z", "2026-10-15T01:00:00Z")
        val day = mapOf("from" to "2026-10-15", "to" to "2026-10-15")
        assertEquals(setOf(first, last), items(query(filters = day)).map { it["id"] }.toSet())
        assertEquals(setOf(first, last, edited), items(query(filters = day + ("dateField" to "updatedAt"))).map { it["id"] }.toSet())
        val logs = items(query("list_work_logs", day))
        assertEquals("COMMENT_UPDATED", logs.single { it["id"] == edited }["kind"])
        assertEquals("2026-10-14T15:00:00Z", query(filters = day).json()["fromInstant"])
        execute("update users set timezone = 'America/New_York' where id = '$memberId'")
        val defaultZone = request("/api/v1/integrations/mcp/tools/search_comments", day)
        assertEquals("America/New_York", defaultZone.json()["timezone"])
        assertEquals("2026-10-15T04:00:00Z", defaultZone.json()["fromInstant"])
        val both = items(query("list_work_logs"))
        assertEquals(setOf("COMMENT_ADDED", "COMMENT_UPDATED"), both.filter { it["id"] == edited }.map { it["kind"] }.toSet())
    }

    @Test
    fun `같은 시각의 댓글과 활동은 커서로 빠짐없이 읽고 다른 조건의 커서는 거절한다`() {
        val issue = issue()
        repeat(4) { addComment(issue["number"], "동일 시각 $it") }
        val expected = items(query("list_work_logs"))
        val collected = mutableListOf<Map<String, Any?>>()
        var cursor: Any? = null
        do {
            val filters = mapOf("limit" to 1) + if (cursor != null) mapOf("cursor" to cursor) else emptyMap()
            val response = query("list_work_logs", filters)
            collected += items(response)
            cursor = response.json()["nextCursor"]
        } while (cursor != null && collected.size <= 10)
        assertEquals(expected.map { it["id"] to it["kind"] }, collected.map { it["id"] to it["kind"] })
        val first = query(filters = mapOf("limit" to 1))
        val next = assertNotNull(first.json()["nextCursor"])
        assertEquals(422, query(filters = mapOf("cursor" to next, "userId" to "all")).status)
        assertEquals(422, query("list_work_logs", mapOf("cursor" to next)).status)
        assertEquals(422, query(filters = mapOf("cursor" to "garbage")).status)
    }

    @Test
    fun `비공개 프로젝트와 권한 회수 및 삭제된 댓글과 대상을 제외한다`() {
        val visible = issue()
        val deleted = addComment(visible["number"], "삭제된 본문")
        val reply = addComment(visible["number"], "남은 답글", parent = deleted)
        member.delete("/api/v1/comments/$deleted")
        val privateProject = admin.post("/api/v1/projects", """{"name":"비공개"}""").json()["id"] as String
        val hidden = issue(admin, privateProject)
        addComment(hidden["number"], "비공개 본문", admin)
        assertEquals(listOf(reply), items(query(filters = mapOf("userId" to "all"))).map { it["id"] })
        assertEquals(404, query(filters = mapOf("issue" to hidden["number"].toString())).status)
        assertEquals(404, query(filters = mapOf("projectId" to privateProject)).status)
        assertFalse(query("list_work_logs", mapOf("userId" to "all")).body.contains("비공개 본문"))
        val orphan = issue(project = null)
        addComment(orphan["number"], "프로젝트 없는 작업")
        admin.delete("/api/v1/projects/$projectId/members/$memberId")
        assertEquals(1, items(query()).size)
        member.delete("/api/v1/issues/${orphan["number"]}")
        assertTrue(items(query()).isEmpty())
    }

    @Test
    fun `프로젝트와 문서 일반 댓글 및 블록 대화의 본문과 근거를 조회한다`() {
        val projectComment =
            member
                .post(
                    "/api/v1/content/project/$projectId/comments",
                    json.writeValueAsString(mapOf("content" to content("프로젝트 분석"))),
                ).json()["id"] as String
        stamp("content_comments", projectComment)
        val document = member.post("/api/v1/projects/$projectId/documents", """{"title":"설계 문서"}""").json()["id"] as String
        val updated = member.patch("/api/v1/documents/$document", json.writeValueAsString(mapOf("content" to content("설계 본문"))))
        assertEquals(200, updated.status, updated.body)
        val documentComment =
            member
                .post(
                    "/api/v1/content/document/$document/comments",
                    json.writeValueAsString(mapOf("content" to content("문서 분석"))),
                ).json()["id"] as String
        stamp("content_comments", documentComment)
        val detail = member.get("/api/v1/documents/$document").json()
        val root = detail["content"] as Map<*, *>
        val block = (((root["content"] as List<*>).first() as Map<*, *>)["attrs"] as Map<*, *>)["blockId"]
        val opened =
            member.post(
                "/api/v1/content/document/$document/threads",
                json.writeValueAsString(mapOf("blockId" to block, "content" to content("블록 검토"))),
            )
        assertEquals(201, opened.status, opened.body)
        val thread = opened.json()["id"] as String
        val threadComment = ((opened.json()["comments"] as List<*>).first() as Map<*, *>)["id"] as String
        stamp("content_thread_comments", threadComment)
        val comments = items(query(filters = mapOf("projectId" to projectId)))
        assertEquals(setOf(projectComment, documentComment, threadComment), comments.map { it["id"] }.toSet())
        val found = items(query(filters = mapOf("threadId" to thread))).single()
        assertEquals(block, comment(found)["blockId"])
        assertEquals("블록 검토", comment(found)["body"])
        execute("update content_activities set created_at = '2026-10-15T03:00:00Z'")
        assertTrue(query("list_work_logs").body.contains("CONTENT_ACTIVITY"))
        member.delete("/api/v1/documents/$document")
        assertEquals(listOf(projectComment), items(query()).map { it["id"] })
    }

    @Test
    fun `티켓 블록 답글과 연결 문서는 티켓 필터와 현재 권한을 따른다`() {
        val issue = issue()
        val number = issue["number"]
        val issueId = issue["id"]
        val updated = member.patch("/api/v1/issues/$number", json.writeValueAsString(mapOf("content" to content("검토할 본문"))))
        assertEquals(200, updated.status, updated.body)
        val root = member.get("/api/v1/issues/$number").json()["content"] as Map<*, *>
        val block = (((root["content"] as List<*>).first() as Map<*, *>)["attrs"] as Map<*, *>)["blockId"]
        val opened = member.post("/api/v1/content/issue/$issueId/threads", json.writeValueAsString(mapOf("blockId" to block, "content" to content("티켓 블록 작업"))))
        assertEquals(201, opened.status, opened.body)
        val thread = opened.json()["id"]
        val first = ((opened.json()["comments"] as List<*>).first() as Map<*, *>)["id"] as String
        stamp("content_thread_comments", first)
        val answered = member.post("/api/v1/content-threads/$thread/comments", json.writeValueAsString(mapOf("content" to content("블록 답글 작업"))))
        assertEquals(201, answered.status, answered.body)
        val reply = (answered.json()["comments"] as List<*>).map { (it as Map<*, *>)["id"] as String }.single { it != first }
        stamp("content_thread_comments", reply)
        val document = member.post("/api/v1/issues/$number/documents", """{"title":"티켓 문서"}""").json()["id"]
        val documentComment =
            member
                .post(
                    "/api/v1/content/document/$document/comments",
                    json.writeValueAsString(mapOf("content" to content("연결 문서 작업"))),
                ).json()["id"] as String
        stamp("content_comments", documentComment)
        addComment(issue()["number"], "다른 티켓")
        val filtered = mapOf("issue" to number.toString())
        assertEquals(setOf(first, reply, documentComment), items(query(filters = filtered)).map { it["id"] }.toSet())
        assertEquals(2, items(query(filters = filtered + ("threadId" to thread))).size)
        assertEquals(204, member.delete("/api/v1/content-thread-comments/$reply").status)
        assertEquals(setOf(first, documentComment), items(query(filters = filtered)).map { it["id"] }.toSet())
        assertEquals(204, member.delete("/api/v1/content-thread-comments/$first").status)
        assertEquals(listOf(documentComment), items(query(filters = filtered)).map { it["id"] })
        admin.delete("/api/v1/projects/$projectId/members/$memberId")
        assertTrue(items(query()).isEmpty())
        assertEquals(404, query(filters = filtered).status)
    }

    @Test
    fun `시간대의 일광절약 경계와 요청함 및 익명 Git 활동을 구분한다`() {
        val issue = issue()
        val id = addComment(issue["number"], "일광절약 변경일")
        stamp("issue_comments", id, "2026-11-01T04:00:00Z")
        val day = mapOf("from" to "2026-11-01", "to" to "2026-11-01", "timezone" to "America/New_York")
        val result = query(filters = day)
        assertEquals(1, items(result).size)
        assertEquals("2026-11-02T05:00:00Z", result.json()["toExclusive"])
        admin.patch("/api/v1/projects/$projectId", """{"triageEnabled":true}""")
        val triage = issue()
        addComment(triage["number"], "요청함 작업")
        assertEquals(1, items(query(filters = mapOf("issue" to triage["number"].toString()))).size)
        execute("update issue_activities set actor_id = null where issue_id = '${triage["id"]}'")
        assertFalse(items(query("list_work_logs")).any { it["kind"] == "ISSUE_ACTIVITY" && it["actor"] == null })
        assertTrue(items(query("list_work_logs", mapOf("userId" to "all"))).any { it["kind"] == "ISSUE_ACTIVITY" && it["actor"] == null })
        assertEquals(200, query(filters = mapOf("from" to "2024-01-01", "to" to "2024-12-31")).status)
        assertEquals(422, query(filters = mapOf("from" to "2024-01-01", "to" to "2025-01-01")).status)
    }

    @Test
    fun `기간과 시간대 및 필터 입력 오류를 명확히 거절한다`() {
        listOf(
            mapOf("from" to "2026-02-30"),
            mapOf("from" to "+10000-01-01"),
            mapOf("to" to "2025-01-01"),
            mapOf("to" to "2028-01-01"),
            mapOf("timezone" to "Wrong/Zone"),
            mapOf("userId" to "작업자"),
            mapOf("commentId" to "wrong"),
            mapOf("dateField" to "wrong"),
            mapOf("limit" to 0),
            mapOf("limit" to 101),
            mapOf("limit" to 1.5),
            mapOf("q" to "x".repeat(501)),
            mapOf("unknown" to true),
            mapOf("issue" to "FB-0"),
        ).forEach { filters -> assertEquals(422, query(filters = filters).status, filters.toString()) }
        assertEquals(422, request("/api/v1/integrations/mcp/tools/search_comments", emptyMap<String, Any>()).status)
    }
}
