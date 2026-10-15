package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** docs/features/git-integration.md "인수 기준". */
class GitIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    // --- 연결 관리 -------------------------------------------------------------------------------

    @Test
    fun `연결 관리는 관리자만 하고 비밀 값은 만들 때와 다시 만들 때만 돌려준다`() {
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }
        assertEquals(403, member.get("/api/v1/admin/git/connections").status)
        assertEquals(403, member.post("/api/v1/admin/git/connections", connectionJson()).status)

        val created = admin.post("/api/v1/admin/git/connections", connectionJson())
        assertEquals(201, created.status)
        val connection = created.json()
        val id = connection["id"] as String
        val secret = connection["secret"] as String
        assertEquals(64, secret.length)
        assertEquals("GITEA", connection["provider"])
        assertEquals("https://git.example.com", connection["baseUrl"])
        assertEquals(true, connection["secretSet"])
        assertEquals("/api/v1/integrations/git/$id/webhook", connection["webhookUrl"])
        assertEquals(true, connection["autoInProgress"])
        assertEquals(true, connection["autoDone"])
        assertNull(connection["lastReceivedAt"])

        val listed = admin.get("/api/v1/admin/git/connections").jsonList().single()
        assertFalse("secret" in listed)
        assertFalse(secret in admin.get("/api/v1/admin/git/connections").body)

        val updated = admin.put("/api/v1/admin/git/connections/$id", connectionJson(name = "사내 Gitea", autoDone = false))
        assertEquals(200, updated.status)
        assertEquals("사내 Gitea", updated.json()["name"])
        assertEquals(false, updated.json()["autoDone"])
        assertFalse("secret" in updated.json())

        val renewed = admin.post("/api/v1/admin/git/connections/$id/secret")
        assertEquals(200, renewed.status)
        val newSecret = renewed.json()["secret"] as String
        assertNotEquals(secret, newSecret)
        assertEquals(newSecret, text("select secret from git_connections"))
        assertEquals(403, member.post("/api/v1/admin/git/connections/$id/secret").status)

        assertEquals(204, admin.delete("/api/v1/admin/git/connections/$id").status)
        assertEquals(0, count("select count(*) from git_connections"))
        assertEquals(
            4,
            count(
                "select count(*) from audit_events where event_type in " +
                    "('GIT_CONNECTION_CREATED', 'GIT_CONNECTION_CHANGED', 'GIT_SECRET_REGENERATED', 'GIT_CONNECTION_DELETED')",
            ),
        )
        assertEquals(404, admin.delete("/api/v1/admin/git/connections/$id").status)
        assertEquals("GIT_CONNECTION_NOT_FOUND", admin.put("/api/v1/admin/git/connections/$id", connectionJson()).json()["code"])
    }

    @Test
    fun `연결의 입력값을 검사한다`() {
        val invalid = admin.post("/api/v1/admin/git/connections", """{"name":" ","provider":"GITEA","baseUrl":"git.example.com"}""")
        assertEquals(422, invalid.status)
        assertEquals(mapOf("name" to "REQUIRED", "baseUrl" to "INVALID_URL"), fieldErrors(invalid.json()))

        val noProjects = admin.post("/api/v1/admin/git/connections", connectionJson(allProjects = false))
        assertEquals(mapOf("projectIds" to "REQUIRED"), fieldErrors(noProjects.json()))
        val unknownProject =
            admin.post("/api/v1/admin/git/connections", connectionJson(allProjects = false, projectIds = listOf("0192f0c4-0000-7000-8000-000000000000")))
        assertEquals(mapOf("projectIds" to "UNKNOWN_PROJECT"), fieldErrors(unknownProject.json()))
        assertEquals(
            mapOf("provider" to "REQUIRED"),
            fieldErrors(admin.post("/api/v1/admin/git/connections", """{"name":"Git","baseUrl":"https://git.example.com"}""").json()),
        )
        // GitLab and GitHub come in a later step of the plan.
        assertEquals(mapOf("provider" to "UNSUPPORTED"), fieldErrors(admin.post("/api/v1/admin/git/connections", connectionJson(provider = "GITLAB")).json()))

        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        val limited = admin.post("/api/v1/admin/git/connections", connectionJson(allProjects = false, projectIds = listOf(p("WEB"))))
        assertEquals(201, limited.status)
        assertEquals(listOf(p("WEB")), limited.json()["projectIds"])
        assertEquals(0, count("select count(*) from git_connections where name = ' '"))
    }

    @Test
    fun `Git 연결을 백업하고 같은 id 로 복원한다`() {
        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        val first = admin.post("/api/v1/admin/git/connections", connectionJson(allProjects = false, projectIds = listOf(p("WEB")))).json()
        val second = admin.post("/api/v1/admin/git/connections", connectionJson(name = "두 번째")).json()

        val file = admin.post("/api/v1/admin/backup/export", """{"sections":["GIT"]}""")
        assertEquals(200, file.status)
        assertTrue(first["secret"] as String in file.body)

        admin.delete("/api/v1/admin/git/connections/${first["id"]}")
        admin.put("/api/v1/admin/git/connections/${second["id"]}", connectionJson(name = "바뀐 이름"))
        val third = admin.post("/api/v1/admin/git/connections", connectionJson(name = "세 번째")).json()

        val restored = admin.post("/api/v1/admin/backup/restore", """{"file":${file.body},"sections":["GIT"]}""")
        assertEquals(200, restored.status)
        assertEquals(2, restored.json()["gitConnections"])
        val connections = admin.get("/api/v1/admin/git/connections").jsonList()
        // A restored connection that had been removed comes back as new, so it is listed last.
        assertEquals(mapOf(first["id"] to "Gitea", second["id"] to "두 번째"), connections.associate { it["id"] to it["name"] })
        assertEquals(listOf(p("WEB")), connections.single { it["id"] == first["id"] }["projectIds"])
        assertEquals(first["secret"], text("select secret from git_connections where id = '${first["id"]}'"))
        assertEquals(0, count("select count(*) from git_connections where id = '${third["id"]}'"))
    }

    // --- 받기와 연결 -----------------------------------------------------------------------------

    @Test
    fun `서명이 틀리면 401, 없거나 꺼진 연결이면 404, 모르는 이벤트는 200 으로 무시한다`() {
        val connection = connect()
        val id = connection["id"] as String
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")

        val wrong = deliver(connection, "push", push("fb-1-login"), secret = "not-the-secret")
        assertEquals(401, wrong.status)
        assertEquals("INVALID_SIGNATURE", wrong.json()["code"])
        val unsigned = deliver(connection, "push", push("fb-1-login"), secret = null)
        assertEquals(401, unsigned.status)
        assertEquals(0, count("select count(*) from issue_git_links"))
        val failed = admin.get("/api/v1/admin/git/connections").jsonList().single()
        assertEquals("push", failed["lastEvent"])
        assertTrue((failed["lastError"] as String).startsWith("서명이 맞지 않습니다"))

        assertEquals(404, deliver(connection + ("id" to "0192f0c4-0000-7000-8000-000000000000"), "push", push("fb-1")).status)
        assertEquals(404, deliver(connection + ("id" to "not-an-id"), "push", push("fb-1")).status)

        val ignored = deliver(connection, "issues", """{"action":"opened"}""")
        assertEquals(200, ignored.status)
        assertEquals(mapOf("linkedIssues" to emptyList<String>(), "statusChanged" to emptyList<String>(), "skipped" to 0), ignored.json())
        val afterIgnored = admin.get("/api/v1/admin/git/connections").jsonList().single()
        assertEquals("issues", afterIgnored["lastEvent"])
        assertNull(afterIgnored["lastError"])
        assertNull(afterIgnored["lastResult"])

        assertEquals(400, deliver(connection, "push", "not json").status)

        val linked = deliver(connection, "push", push("fb-1-login"))
        assertEquals(200, linked.status)
        assertEquals(listOf("FB-1"), linked.json()["linkedIssues"])
        val received = admin.get("/api/v1/admin/git/connections").jsonList().single()
        assertEquals(mapOf("linkedIssues" to listOf("FB-1"), "statusChanged" to emptyList<String>(), "skipped" to 0), received["lastResult"])
        assertNull(received["lastError"])

        // A new secret: the old one no longer signs.
        val oldSecret = connection["secret"] as String
        val newSecret = admin.post("/api/v1/admin/git/connections/$id/secret").json()["secret"] as String
        assertEquals(401, deliver(connection, "push", push("fb-1-login"), secret = oldSecret).status)
        assertEquals(200, deliver(connection, "push", push("fb-1-login"), secret = newSecret).status)

        admin.put("/api/v1/admin/git/connections/$id", connectionJson(enabled = false))
        assertEquals(404, deliver(connection, "push", push("fb-1-login"), secret = newSecret).status)
    }

    @Test
    fun `본문이 5MB 를 넘으면 413 이다`() {
        val connection = connect()
        val big = """{"padding":"${"x".repeat(5 * 1024 * 1024)}"}"""
        assertEquals(413, deliver(connection, "push", big).status)
    }

    @Test
    fun `브랜치 이름, PR 제목과 본문, 커밋 메시지에서 키를 대소문자와 단어 경계를 가려 찾는다`() {
        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        remember("OPS", admin.post("/api/v1/projects", """{"name":"운영"}""").json()["id"])
        repeat(7) { admin.post("/api/v1/issues", """{"title":"티켓 ${it + 1}","projectId":"${p("WEB")}"}""") }
        admin.post("/api/v1/issues", """{"title":"다른 프로젝트","projectId":"${p("OPS")}"}""") // FB-8
        admin.post("/api/v1/issues", """{"title":"지운 티켓","projectId":"${p("WEB")}"}""") // FB-9
        admin.delete("/api/v1/issues/9")
        val connection = connect(allProjects = false, projectIds = listOf(p("WEB")))

        // The branch: lower case, inside a path, followed by a dash.
        assertEquals(listOf("FB-1"), deliver(connection, "push", push("feature/fb-1-login")).json()["linkedIssues"])
        // Not a key: inside a longer word on either side.
        assertEquals(emptyList<String>(), deliver(connection, "push", push("xfb-2", "fb-23x 와 afb-2 는 키가 아니다")).json()["linkedIssues"])
        // Commit messages: several keys, brackets, any case; a missing number, a deleted issue and another project are left out.
        val commits = deliver(connection, "push", push("main", "[FB-2] 정리, Fb-3 도 함께", "fb-404 fb-8 fb-9 는 무시"))
        assertEquals(listOf("FB-2", "FB-3"), commits.json()["linkedIssues"])
        // A pull request: the title, the body.
        val pr =
            deliver(
                connection,
                "pull_request",
                pullRequest(number = 35, title = "FB-4 결제 수정", body = "Fixes fb-5\n\nRefs FB-6, part of FB-7", branch = "hotfix"),
            )
        assertEquals(listOf("FB-4", "FB-5", "FB-6", "FB-7"), pr.json()["linkedIssues"])

        assertEquals(1, count("select count(*) from issue_git_links l join issues i on i.id = l.issue_id where i.number = 1 and l.kind = 'BRANCH'"))
        assertEquals(0, count("select count(*) from issue_git_links where kind = 'BRANCH' and branch = 'main'"))
        assertEquals(2, count("select count(*) from issue_git_commits c join issues i on i.id = c.issue_id where i.number in (2, 3)"))
        assertEquals(0, count("select count(*) from issue_git_commits c join issues i on i.id = c.issue_id where i.number in (8, 9)"))
        // The title and "Fixes" close the issue; the rest only refer to it.
        assertEquals(listOf(true, true, false, false), (4..7).map(::closes))

        // A changed workspace key is the only one recognized from then on.
        assertEquals(200, admin.patch("/api/v1/admin/workspace", """{"workspaceKey":"WEB"}""").status)
        assertEquals(emptyList<String>(), deliver(connection, "push", push("fb-6-old")).json()["linkedIssues"])
        assertEquals(listOf("WEB-6"), deliver(connection, "push", push("web-6-new")).json()["linkedIssues"])
    }

    @Test
    fun `같은 배달이 다시 와도 연결과 커밋과 활동이 두 번 쌓이지 않는다`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        val connection = connect()
        val delivery = push("kimjm/fb-1-login-error", "로그인 수정", "문구 수정")
        repeat(2) { assertEquals(200, deliver(connection, "push", delivery).status) }
        repeat(2) { assertEquals(200, deliver(connection, "pull_request", pullRequest()).status) }

        assertEquals(2, count("select count(*) from issue_git_links"))
        assertEquals(2, count("select count(*) from issue_git_commits"))
        assertEquals(listOf("CREATED", "GIT_BRANCH_LINKED", "GIT_COMMITS_PUSHED", "GIT_PR_LINKED"), activityTypes(1))

        // Another push with one more commit records only the new one.
        deliver(connection, "push", push("kimjm/fb-1-login-error", "로그인 수정", "문구 수정", "테스트 추가"))
        val detail = detailOf(activity(1).last())
        assertEquals("GIT_COMMITS_PUSHED", activity(1).last()["type"])
        assertEquals(1, detail["count"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("테스트 추가"), (detail["commits"] as List<Map<String, Any?>>).map { it["message"] })
    }

    @Test
    fun `옆 패널에 PR 과 브랜치를 보이고 PR 이 생긴 브랜치는 PR 줄에 합친다`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        admin.post("/api/v1/issues", """{"title":"참고만 하는 티켓"}""")
        val connection = connect()
        deliver(connection, "push", push("kimjm/fb-1-login-error"))
        deliver(connection, "push", push("kimjm/fb-1-other-try"))
        assertEquals(listOf("kimjm/fb-1-other-try", "kimjm/fb-1-login-error"), gitOf(1).map { it["branch"] })
        assertEquals("https://git.example.com/acme/web/src/branch/kimjm/fb-1-login-error", gitOf(1).last()["url"])

        deliver(connection, "pull_request", pullRequest(body = "Refs FB-2"))
        val links = gitOf(1)
        assertEquals(listOf("PULL_REQUEST", "BRANCH"), links.map { it["kind"] })
        assertEquals(listOf("kimjm/fb-1-login-error", "kimjm/fb-1-other-try"), links.map { it["branch"] })
        val pr = links.first()
        assertEquals("acme/web", pr["repository"])
        assertEquals(34, pr["prNumber"])
        assertEquals("FB-1 로그인 오류 수정", pr["title"])
        assertEquals("OPEN", pr["prState"])
        assertEquals(true, pr["closes"])
        assertEquals("https://git.example.com/acme/web/pulls/34", pr["url"])
        assertEquals(false, gitOf(2).single()["closes"])
        // Lists leave it out.
        assertFalse("git" in admin.get("/api/v1/issues").jsonList().first())

        deliver(connection, "pull_request", pullRequest(action = "edited", title = "[WIP] FB-1 로그인 오류 수정"))
        assertEquals("DRAFT", gitOf(1).first()["prState"])
        deliver(connection, "pull_request", merged())
        assertEquals("MERGED", gitOf(1).first()["prState"])
        assertEquals("GIT_PR_MERGED", activityTypes(1).last())
        // The state is always the latest event's word, even when events come out of order.
        deliver(connection, "pull_request", pullRequest(action = "closed", state = "closed"))
        assertEquals("CLOSED", gitOf(1).first()["prState"])
        assertEquals("GIT_PR_CLOSED", activityTypes(1).last())
    }

    @Test
    fun `사람은 이메일, 그다음 아이디로 맞추고 둘 다 아니면 Git 이름만 남긴다`() {
        val jimin = admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$PASSWORD","displayName":"김지민"}""").json()["id"]
        execute("update users set email = 'kimjm@example.com' where login_id = 'jimin'")
        val leesj = admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""").json()["id"]
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        val connection = connect()

        // The pusher's e-mail address is jimin's.
        deliver(connection, "push", push("fb-1-login"))
        // leesj merges; Gitea hides the address, so the login id decides.
        deliver(connection, "pull_request", pullRequest())
        deliver(connection, "pull_request", merged())
        // Nobody here is parkhs.
        deliver(connection, "push", push("fb-1-login", "다른 사람의 커밋", pusher = Triple("parkhs", "parkhs@example.com", "박현수")))

        val entries = activity(1).drop(1)
        assertEquals(
            listOf(
                "GIT_BRANCH_LINKED" to jimin,
                "GIT_COMMITS_PUSHED" to jimin,
                "GIT_PR_LINKED" to jimin,
                "GIT_PR_MERGED" to leesj,
                "GIT_COMMITS_PUSHED" to null,
            ),
            entries.map { entry -> entry["type"] to (entry["actor"] as Map<*, *>?)?.get("id") },
        )
        @Suppress("UNCHECKED_CAST")
        val names = entries.map { (detailOf(it)["via"] as Map<String, Any?>)["name"] }
        assertEquals(listOf("kimjm", "kimjm", "kimjm", "leesj", "parkhs"), names)
    }

    // --- helpers --------------------------------------------------------------------------------

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Adds a Gitea connection; the result holds its id and secret. */
    private fun connect(
        allProjects: Boolean = true,
        projectIds: List<String> = emptyList(),
        autoInProgress: Boolean = true,
        autoDone: Boolean = true,
    ): Map<String, Any?> =
        admin
            .post(
                "/api/v1/admin/git/connections",
                connectionJson(allProjects = allProjects, projectIds = projectIds, autoInProgress = autoInProgress, autoDone = autoDone),
            ).json()

    /** Calls the webhook as Gitea does, signed with [secret] (unsigned when null). */
    private fun deliver(
        connection: Map<String, Any?>,
        event: String,
        body: String,
        secret: String? = connection["secret"] as String,
    ): ApiResponse {
        val bytes = body.toByteArray()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/integrations/git/${connection["id"]}/webhook"))
                .header("Content-Type", "application/json")
                .header("X-Gitea-Event", event)
                .header("X-Gitea-Delivery", "c0ffee00-0000-4000-8000-000000000000")
        if (secret != null) request.header("X-Gitea-Signature", hmac(bytes, secret))
        val response = http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun hmac(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    private fun sample(name: String): ObjectNode = mapper.readTree(checkNotNull(javaClass.getResource("/git/$name")).readText()) as ObjectNode

    /** The push sample to another branch, with one commit per message (the same message makes the same commit). */
    private fun push(
        branch: String,
        vararg messages: String,
        pusher: Triple<String, String, String>? = null,
    ): String {
        val node = sample("gitea-push.json")
        node.put("ref", "refs/heads/$branch")
        val commits = node.get("commits") as ArrayNode
        if (messages.isNotEmpty()) {
            val template = commits.get(0) as ObjectNode
            commits.removeAll()
            for (message in messages) {
                val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(message.toByteArray()))
                commits.add(
                    template
                        .deepCopy()
                        .put("id", sha)
                        .put("message", message)
                        .put("url", "https://git.example.com/acme/web/commit/$sha"),
                )
            }
        }
        if (pusher != null) {
            (
                node.get(
                    "pusher",
                ) as ObjectNode
            ).put("login", pusher.first).put("username", pusher.first).put("email", pusher.second).put("full_name", pusher.third)
        }
        return mapper.writeValueAsString(node)
    }

    /** The opened pull request sample, by default #34 from kimjm/fb-1-login-error. */
    private fun pullRequest(
        number: Int = 34,
        title: String = "FB-1 로그인 오류 수정",
        body: String = "세션이 만료되면 로그인 화면으로 보냅니다.",
        branch: String = "kimjm/fb-1-login-error",
        action: String = "opened",
        state: String = "open",
    ): String {
        val node = sample("gitea-pull-request.json")
        node.put("action", action).put("number", number)
        val pr = node.get("pull_request") as ObjectNode
        pr
            .put("number", number)
            .put("title", title)
            .put("body", body)
            .put("state", state)
            .put("html_url", "https://git.example.com/acme/web/pulls/$number")
        (pr.get("head") as ObjectNode).put("ref", branch).put("label", branch)
        return mapper.writeValueAsString(node)
    }

    /** The merged pull request sample: #34 from kimjm/fb-1-login-error, merged by leesj. */
    private fun merged(
        title: String = "FB-1 로그인 오류 수정",
        body: String = "세션이 만료되면 로그인 화면으로 보냅니다.",
    ): String {
        val node = sample("gitea-pull-request-merged.json")
        (node.get("pull_request") as ObjectNode).put("title", title).put("body", body)
        return mapper.writeValueAsString(node)
    }

    @Suppress("UNCHECKED_CAST")
    private fun activity(number: Int): List<Map<String, Any?>> = admin.get("/api/v1/issues/$number/activity").json()["items"] as List<Map<String, Any?>>

    private fun activityTypes(number: Int): List<Any?> = activity(number).map { it["type"] }

    @Suppress("UNCHECKED_CAST")
    private fun detailOf(entry: Map<String, Any?>): Map<String, Any?> = entry["detail"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun gitOf(number: Int): List<Map<String, Any?>> = admin.get("/api/v1/issues/$number").json()["git"] as List<Map<String, Any?>>

    /** Whether the pull request link of the issue closes it. */
    private fun closes(number: Int): Boolean =
        text("select l.closes::text from issue_git_links l join issues i on i.id = l.issue_id where i.number = $number and l.kind = 'PULL_REQUEST'") == "true"

    private fun connectionJson(
        name: String = "Gitea",
        enabled: Boolean = true,
        provider: String = "GITEA",
        allProjects: Boolean = true,
        projectIds: List<String> = emptyList(),
        autoInProgress: Boolean = true,
        autoDone: Boolean = true,
    ): String =
        """{"name":"$name","enabled":$enabled,"provider":"$provider","baseUrl":"https://git.example.com/","allProjects":$allProjects,""" +
            """"projectIds":[${projectIds.joinToString { "\"$it\"" }}],"autoInProgress":$autoInProgress,"autoDone":$autoDone}"""

    @Suppress("UNCHECKED_CAST")
    private fun fieldErrors(problem: Map<String, Any?>): Map<String, String> =
        (problem["errors"] as List<Map<String, Any?>>).associate { it["field"] as String to it["code"] as String }
}
