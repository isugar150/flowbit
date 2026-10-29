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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MCP_PASSWORD = "correct-horse-battery"

class McpIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var editorId: String
    private lateinit var project: String
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", MCP_PASSWORD) }
        editorId = admin.post("/api/v1/admin/members", """{"loginId":"editor","password":"$MCP_PASSWORD"}""").json()["id"] as String
        project = admin.post("/api/v1/projects", """{"name":"개발"}""").json()["id"] as String
        admin.put("/api/v1/projects/$project/members/$editorId", """{"role":"EDITOR"}""")
        editor = client().also { it.login("editor", MCP_PASSWORD) }
    }

    private fun token(
        client: ApiClient = editor,
        scope: String = "WRITE",
        days: Int = 30,
    ): String {
        val issued = client.post("/api/v1/me/access-tokens", """{"name":"개발 PC","scope":"$scope","expiresInDays":$days}""")
        assertEquals(201, issued.status, issued.body)
        return issued.json()["token"] as String
    }

    private fun request(
        path: String,
        token: String?,
        body: Any? = null,
        origin: String? = null,
        method: String = "POST",
        accept: String = "application/json, text/event-stream",
    ): ApiResponse {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Accept", accept)
        token?.let { builder.header("Authorization", "Bearer $it") }
        origin?.let { builder.header("Origin", it) }
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun tool(
        token: String,
        name: String,
        args: Map<String, Any?> = emptyMap(),
    ): ApiResponse = request("/api/v1/integrations/mcp/tools/$name", token, args)

    private fun rpc(
        token: String,
        method: String,
        params: Map<String, Any?> = emptyMap(),
    ): ApiResponse =
        request(
            "/api/v1/mcp",
            token,
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to method,
                "params" to params,
            ),
        )

    @Test
    fun `개인 토큰은 해시로 보관하고 자기 목록과 폐기만 허용한다`() {
        val secret = token()
        assertEquals(50, secret.length)
        val list = editor.get("/api/v1/me/access-tokens")
        assertFalse(list.body.contains(secret))
        assertFalse(text("select token_hash from personal_access_tokens").contains(secret))
        val id = list.jsonList().single()["id"]
        assertEquals(404, admin.delete("/api/v1/me/access-tokens/$id").status)
        assertEquals(200, tool(secret, "list_statuses").status)
        assertNotNull(editor.get("/api/v1/me/access-tokens").jsonList().single()["lastUsedAt"])
        assertEquals(204, editor.delete("/api/v1/me/access-tokens/$id").status)
        assertEquals(401, tool(secret, "list_statuses").status)
        assertEquals(2, count("select count(*) from audit_events where event_type in ('ACCESS_TOKEN_CREATED','ACCESS_TOKEN_REVOKED')"))
    }

    @Test
    fun `AI 연결 가이드는 로그인 없이 설치 주소와 설정 절차만 공개한다`() {
        val secret = token()
        editor.post("/api/v1/issues", """{"title":"공개되면 안 되는 테스트 티켓"}""")
        val guide = request("/api/v1/mcp/setup", null, method = "GET", accept = "text/markdown")
        assertEquals(200, guide.status, guide.body)
        assertTrue(guide.header("Content-Type").orEmpty().contains("text/markdown"))
        assertEquals("no-store", guide.header("Cache-Control"))
        assertTrue(guide.body.contains("http://localhost:$port/api/v1/mcp"))
        assertTrue(guide.body.contains("http://localhost:$port/settings/mcp"))
        assertTrue(guide.body.contains("http_headers"))
        assertTrue(guide.body.contains("~/.claude.json"))
        assertTrue(guide.body.contains("Flowbit 개인 토큰"))
        assertTrue(guide.body.contains("initialize → tools/list → list_projects"))
        assertFalse(guide.body.contains("{{"))
        assertFalse(guide.body.contains(secret))
        assertFalse(guide.body.contains(editorId))
        assertFalse(guide.body.contains("공개되면 안 되는 테스트 티켓"))
        assertEquals(guide.body, request("/api/v1/mcp/setup", secret, method = "GET", accept = "*/*").body)
        val head = request("/api/v1/mcp/setup", null, method = "HEAD", accept = "*/*")
        assertEquals(200, head.status)
        assertEquals("", head.body)
        assertEquals(401, request("/api/v1/integrations/mcp/tools", null, method = "GET").status)
        assertEquals(401, request("/api/v1/me/access-tokens", null, method = "GET").status)
    }

    @Test
    fun `만료와 계정 비활성화는 토큰을 즉시 차단한다`() {
        val secret = token(days = 1)
        clock.advance(Duration.ofDays(1))
        assertEquals(401, tool(secret, "list_statuses").status)
        val active = token()
        admin.patch("/api/v1/admin/members/$editorId", """{"status":"DISABLED"}""")
        assertEquals(401, tool(active, "list_statuses").status)
    }

    @Test
    fun `읽기 전용 토큰과 세션과 관리자 경로를 분리한다`() {
        val read = token(scope = "READ")
        assertEquals(200, tool(read, "list_projects").status)
        assertEquals(403, tool(read, "create_issue", mapOf("fields" to mapOf("title" to "금지"))).status)
        assertEquals(401, request("/api/v1/me/access-tokens", read, method = "GET").status)
        assertEquals(401, request("/api/v1/admin/members", read, method = "GET").status)
        assertEquals(401, editor.post("/api/v1/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        assertEquals(0, count("select count(*) from issues"))
    }

    @Test
    fun `MCP 초기화 도구 발견 호출과 알림 및 Origin 검증을 지원한다`() {
        val secret = token(scope = "READ")
        val initialized =
            rpc(
                secret,
                "initialize",
                mapOf(
                    "protocolVersion" to "2025-11-25",
                    "clientInfo" to mapOf("name" to "test", "version" to "1"),
                    "capabilities" to emptyMap<String, Any>(),
                ),
            )
        assertEquals(200, initialized.status)
        assertTrue(initialized.body.contains("2025-11-25"))
        val discovery = rpc(secret, "tools/list")
        assertTrue(discovery.body.contains("get_issue_options"))
        assertFalse(discovery.body.contains("create_issue"))
        assertTrue(rpc(secret, "tools/call", mapOf("name" to "list_statuses")).body.contains("\"isError\":false"))
        assertTrue(
            rpc(
                secret,
                "tools/call",
                mapOf("name" to "create_issue", "arguments" to mapOf("fields" to mapOf("title" to "금지"))),
            ).body.contains("\"isError\":true"),
        )
        assertTrue(rpc(secret, "unknown").body.contains("-32601"))
        assertTrue(rpc(secret, "tools/call", mapOf("name" to "unknown")).body.contains("-32602"))
        assertEquals(202, request("/api/v1/mcp", secret, mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized")).status)
        assertEquals(405, request("/api/v1/mcp", secret, method = "GET").status)
        assertEquals(403, request("/api/v1/mcp", secret, mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "ping"), "https://attacker.example").status)
        assertEquals(200, request("/api/v1/mcp", secret, mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "ping"), "http://localhost:$port").status)
    }

    @Test
    fun `API와 MCP 생성은 화면의 요청함과 권한 규칙을 따른다`() {
        admin.patch("/api/v1/projects/$project", """{"triageEnabled":true}""")
        val secret = token()
        val created = tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "요청", "projectId" to project, "triage" to false)))
        assertEquals(200, created.status, created.body)
        assertTrue(created.body.contains("\"triage\":true"))
        assertEquals(1, admin.get("/api/v1/triage").jsonList().size)
        assertEquals(403, tool(secret, "update_issue", mapOf("issue" to "FB-1", "fields" to mapOf("triage" to false))).status)
        val privateProject = admin.post("/api/v1/projects", """{"name":"비공개"}""").json()["id"]
        assertEquals(404, tool(secret, "get_issue_options", mapOf("projectId" to privateProject)).status)
        assertEquals(404, tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "차단", "projectId" to privateProject))).status)
        assertFalse(tool(secret, "list_projects").body.contains("비공개"))
        admin.put("/api/v1/projects/$project/members/$editorId", """{"role":"VIEWER"}""")
        assertEquals(403, tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "차단", "projectId" to project))).status)
    }

    @Test
    fun `티켓과 댓글 CRUD와 복원은 화면에서도 동일한 값과 활동으로 보인다`() {
        val secret = token()
        val created =
            tool(
                secret,
                "create_issue",
                mapOf(
                    "fields" to
                        mapOf(
                            "title" to "로그인 오류",
                            "projectId" to project,
                            "markdown" to "## 재현\n\n**굵게**와 *기울임*, [링크](https://example.com)\n\n- 첫 항목\n- 둘째 항목\n\n```kotlin\nval a = 1\n```",
                        ),
                ),
            )
        assertEquals(200, created.status, created.body)
        assertTrue(created.body.contains("heading"))
        assertTrue(created.body.contains("bold"))
        assertTrue(created.body.contains("codeBlock"))
        assertEquals("로그인 오류", editor.get("/api/v1/issues/1").json()["title"])
        val updated = tool(secret, "update_issue", mapOf("issue" to "FB-1", "fields" to mapOf("priority" to "HIGH", "dueDate" to "2026-12-01")))
        assertEquals(200, updated.status, updated.body)
        assertTrue(tool(secret, "get_issue", mapOf("issue" to "1")).body.contains("로그인 오류"))
        assertEquals("HIGH", editor.get("/api/v1/issues/1").json()["priority"])
        assertEquals(200, tool(secret, "update_issue", mapOf("issue" to "1", "fields" to mapOf("dueDate" to null))).status)
        assertEquals(null, editor.get("/api/v1/issues/1").json()["dueDate"])
        val comment = tool(secret, "create_comment", mapOf("issue" to "1", "markdown" to "작업 **완료**"))
        assertEquals(200, comment.status, comment.body)
        val commentId = comment.json()["id"]
        assertEquals(200, tool(secret, "update_comment", mapOf("commentId" to commentId, "markdown" to "작업 수정")).status)
        assertTrue(tool(secret, "list_comments", mapOf("issue" to "1")).body.contains("작업 수정"))
        assertEquals(200, tool(secret, "delete_comment", mapOf("commentId" to commentId)).status)
        assertEquals(200, tool(secret, "delete_issue", mapOf("issue" to "1")).status)
        assertNotNull(editor.get("/api/v1/issues/1").json()["deletedAt"])
        assertEquals(200, tool(secret, "restore_issue", mapOf("issue" to "FB-1")).status)
        assertEquals(null, editor.get("/api/v1/issues/1").json()["deletedAt"])
        assertTrue(count("select count(*) from issue_activities where issue_id = (select id from issues where number = 1)") >= 4)
    }

    @Test
    fun `속성 선택정보와 공통 속성 개별 속성 연결 변경을 제공한다`() {
        val field = admin.post("/api/v1/projects/$project/issue-fields", """{"label":"리뷰어","type":"PERSON","multiple":true}""").json()["id"]
        val secret = token()
        val options = tool(secret, "get_issue_options", mapOf("projectId" to project, "limit" to 1))
        assertEquals(200, options.status, options.body)
        for (key in listOf(
            "statuses",
            "labels",
            "cycles",
            "projectFields",
            "people",
            "리뷰어",
            "multiple",
            "nextOffset",
        )) {
            assertTrue(options.body.contains(key), key)
        }
        assertEquals(1, tool(secret, "list_people", mapOf("projectId" to project, "q" to "editor")).json()["total"])
        val values =
            mapOf(
                "title" to "속성 티켓",
                "projectId" to project,
                "assigneeIds" to listOf(editorId),
                "projectFields" to listOf(mapOf("fieldId" to field, "value" to listOf(editorId))),
            )
        assertEquals(200, tool(secret, "create_issue", mapOf("fields" to values)).status)
        assertEquals(200, tool(secret, "set_project_field", mapOf("issue" to "1", "fieldId" to field, "value" to null)).status)
        val property = tool(secret, "create_custom_property", mapOf("issue" to "1", "label" to "점수", "type" to "NUMBER", "value" to 3))
        assertEquals(200, property.status, property.body)
        val propertyId = (property.json()["item"] as Map<*, *>)["id"]
        assertEquals(200, tool(secret, "update_custom_property", mapOf("propertyId" to propertyId, "fields" to mapOf("value" to 5))).status)
        assertTrue(tool(secret, "list_custom_properties", mapOf("issue" to "1")).body.contains("점수"))
        assertEquals(200, tool(secret, "delete_custom_property", mapOf("propertyId" to propertyId)).status)
        tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "연결 대상", "projectId" to project)))
        val connected = tool(secret, "add_relation", mapOf("issue" to "1", "target" to "2", "kind" to "BLOCKED_BY"))
        assertEquals(200, connected.status, connected.body)
        val relationId = connected.jsonList().single()["id"]
        assertEquals(200, tool(secret, "remove_relation", mapOf("issue" to "1", "relationId" to relationId)).status)
        assertEquals(emptyList(), tool(secret, "list_relations", mapOf("issue" to "1")).jsonList())
    }

    @Test
    fun `잘못된 필드와 선택값은 저장 없이 거절한다`() {
        val secret = token()
        assertEquals(422, tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "거절", "unknown" to true))).status)
        assertEquals(422, tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "거절", "assigneeIds" to listOf("잘못된 번호")))).status)
        assertEquals(
            422,
            tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "거절", "markdown" to "텍스트", "content" to emptyMap<String, Any>()))).status,
        )
        assertEquals(422, tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "거절", "markdown" to "[링크](javascript:alert(1))"))).status)
        assertEquals(0, count("select count(*) from issues"))
    }

    @Test
    fun `티켓 번호 제목 본문 댓글과 초성 검색은 접근 권한과 요청함을 반영한다`() {
        val secret = token()
        tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "결제 오류", "projectId" to project, "markdown" to "환불 처리 중 실패")))
        tool(secret, "create_comment", mapOf("issue" to "1", "markdown" to "재시도하면 성공합니다"))
        for (query in listOf("FB-1", "결제", "환불", "재시도", "ㄱㅈ")) {
            val found = tool(secret, "search_issues", mapOf("q" to query))
            assertEquals(200, found.status, found.body)
            assertTrue(found.body.contains("결제 오류"), query)
        }
        val privateProject = admin.post("/api/v1/projects", """{"name":"비공개 검색"}""").json()["id"]
        admin.post("/api/v1/issues", """{"title":"결제 비공개","projectId":"$privateProject"}""")
        assertFalse(tool(secret, "search_issues", mapOf("q" to "결제")).body.contains("결제 비공개"))
        admin.patch("/api/v1/projects/$project", """{"triageEnabled":true}""")
        tool(secret, "create_issue", mapOf("fields" to mapOf("title" to "결제 요청함", "projectId" to project)))
        assertTrue(tool(secret, "search_issues", mapOf("q" to "결제 요청함")).body.contains("결제 요청함"))
    }

    @Test
    fun `토큰 발급은 세션 CSRF를 요구하고 연동 API는 도구 정의를 조회한다`() {
        editor.sendCsrfHeader = false
        assertEquals(403, editor.post("/api/v1/me/access-tokens", """{"name":"차단"}""").status)
        editor.sendCsrfHeader = true
        val read = token(scope = "READ")
        val definitions = request("/api/v1/integrations/mcp/tools", read, method = "GET")
        assertEquals(200, definitions.status)
        assertTrue(definitions.body.contains("search_issues"))
        assertFalse(definitions.body.contains("create_issue"))
    }
}
