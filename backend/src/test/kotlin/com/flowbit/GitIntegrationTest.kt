package com.flowbit

import com.flowbit.notification.NotificationDispatcher
import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
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
    @Autowired
    private lateinit var dispatcher: NotificationDispatcher

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

        assertEquals(
            mapOf("provider" to "REQUIRED"),
            fieldErrors(admin.post("/api/v1/admin/git/connections", """{"name":"Git","baseUrl":"https://git.example.com"}""").json()),
        )

        // A connection no longer names projects (D-149): projects choose their repositories.
        val created = admin.post("/api/v1/admin/git/connections", connectionJson())
        assertEquals(201, created.status)
        assertFalse("projectIds" in created.json() || "allProjects" in created.json())
        assertEquals(0, count("select count(*) from git_connections where name = ' '"))
    }

    @Test
    fun `Git 연결을 백업하고 같은 id 로 복원한다`() {
        val first = admin.post("/api/v1/admin/git/connections", connectionJson()).json()
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
        val receivedConnection = admin.get("/api/v1/admin/git/connections").jsonList().single()
        // The branch also started the work (stage 3).
        assertEquals(mapOf("linkedIssues" to listOf("FB-1"), "statusChanged" to listOf("FB-1"), "skipped" to 0), receivedConnection["lastResult"])
        assertNull(receivedConnection["lastError"])

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
        val connection = connect()
        // OPS takes events only from acme/ops, so the sample's acme/web does not reach FB-8.
        admin.put("/api/v1/projects/${p("OPS")}/git/repositories", """{"repositories":[{"connectionId":"${connection["id"]}","repository":"acme/ops"}]}""")

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
        assertEquals(listOf("CREATED", "GIT_BRANCH_LINKED", "GIT_COMMITS_PUSHED", "STATUS_CHANGED", "GIT_PR_LINKED"), activityTypes(1))

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
        val connection = connect(autoInProgress = false, autoDone = false)
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
        val connection = connect(autoInProgress = false, autoDone = false)

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

    // --- 상태 자동 변경 --------------------------------------------------------------------------

    @Test
    fun `브랜치를 올리거나 PR 을 열면 백로그·준비됨 티켓이 진행 중이 되고 완료·취소 티켓은 바뀌지 않는다`() {
        createIssues("BACKLOG", "READY", "DONE", "CANCELED", "BACKLOG")
        val connection = connect()

        val pushed = deliver(connection, "push", push("kimjm/fb-1-login")).json()
        assertEquals(listOf("FB-1"), pushed["statusChanged"])
        val opened = deliver(connection, "pull_request", pullRequest(number = 40, title = "FB-2 결제 수정", branch = "hotfix", body = "Refs FB-5")).json()
        assertEquals(listOf("FB-2", "FB-5"), opened["linkedIssues"])
        assertEquals(listOf("FB-2"), opened["statusChanged"])
        deliver(connection, "push", push("fb-3-again"))
        deliver(connection, "pull_request", pullRequest(number = 41, title = "FB-4 다시", branch = "retry"))

        // A reference does not start anything; done and canceled stay as they are.
        assertEquals(listOf("IN_PROGRESS", "IN_PROGRESS", "DONE", "CANCELED", "BACKLOG"), statuses(5))
        val change = activity(1).last()
        assertEquals("STATUS_CHANGED", change["type"])
        assertEquals(mapOf("source" to "git", "name" to "kimjm"), detailOf(change)["via"])
        assertEquals("BACKLOG" to "IN_PROGRESS", detailOf(change)["from"] to detailOf(change)["to"])
        // Pushing the same branch again after it was moved back by hand does not move it again.
        admin.patch("/api/v1/issues/1", """{"status":"READY"}""")
        deliver(connection, "push", push("kimjm/fb-1-login", "다음 커밋"))
        assertEquals("READY", statuses(1).single())
    }

    @Test
    fun `닫는 PR 이 머지되면 완료가 되고 참고 PR 과 머지 없이 닫힌 PR 은 상태를 바꾸지 않는다`() {
        createIssues("BACKLOG", "BACKLOG", "BACKLOG", "BACKLOG")
        val connection = connect()

        deliver(connection, "pull_request", pullRequest(number = 34, title = "FB-1 로그인 수정", branch = "a"))
        val done = deliver(connection, "pull_request", merged(number = 34, title = "FB-1 로그인 수정", branch = "a")).json()
        assertEquals(listOf("FB-1"), done["statusChanged"])

        // Only a reference: merging it changes nothing.
        deliver(connection, "pull_request", pullRequest(number = 35, title = "정리", body = "Refs FB-2", branch = "b"))
        deliver(connection, "pull_request", merged(number = 35, title = "정리", body = "Refs FB-2", branch = "b"))

        // Closed without merging: the issue stays where the pull request put it.
        deliver(connection, "pull_request", pullRequest(number = 36, title = "FB-3 시도", branch = "c"))
        deliver(connection, "pull_request", pullRequest(number = 36, title = "FB-3 시도", branch = "c", action = "closed", state = "closed"))

        // Two closing pull requests: the issue is done once the last open one is merged.
        deliver(connection, "pull_request", pullRequest(number = 37, title = "FB-4 앞부분", branch = "d"))
        deliver(connection, "pull_request", pullRequest(number = 38, title = "FB-4 뒷부분", branch = "e"))
        deliver(connection, "pull_request", merged(number = 37, title = "FB-4 앞부분", branch = "d"))
        assertEquals("IN_PROGRESS", statuses(4).last())
        deliver(connection, "pull_request", merged(number = 38, title = "FB-4 뒷부분", branch = "e"))

        assertEquals(listOf("DONE", "BACKLOG", "IN_PROGRESS", "DONE"), statuses(4))
        // leesj merged it; nobody here is leesj, so the history keeps the Git name.
        val finished = activity(1).last()
        assertEquals("STATUS_CHANGED", finished["type"])
        assertNull(finished["actor"])
        assertEquals("leesj", (detailOf(finished)["via"] as Map<*, *>)["name"])
        // The same merge delivered again changes nothing more.
        deliver(connection, "pull_request", merged(number = 34, title = "FB-1 로그인 수정", branch = "a"))
        assertEquals(1, activityTypes(1).count { it == "GIT_PR_MERGED" })
        assertEquals(2, activityTypes(1).count { it == "STATUS_CHANGED" })
    }

    @Test
    fun `기본 브랜치에 바로 올린 커밋이 닫는 말을 쓰면 완료가 된다`() {
        createIssues("IN_PROGRESS", "BACKLOG", "CANCELED")
        val connection = connect()

        val result = deliver(connection, "push", push("main", "Fixes FB-1 로그인 오류", "fix: FB-3 은 취소됨", "FB-2 언급만")).json()
        assertEquals(listOf("FB-1"), result["statusChanged"])
        // Not the default branch: "Fixes" waits for the merge.
        deliver(connection, "push", push("feature/x", "Fixes FB-2"))
        assertEquals(listOf("DONE", "BACKLOG", "CANCELED"), statuses(3))
    }

    @Test
    fun `자동 상태 변경을 끈 연결은 연결만 한다`() {
        createIssues("BACKLOG")
        val connection = connect(autoInProgress = false, autoDone = false)
        deliver(connection, "push", push("fb-1-login"))
        deliver(connection, "pull_request", pullRequest())
        val merged = deliver(connection, "pull_request", merged()).json()

        assertEquals(listOf("FB-1"), merged["linkedIssues"])
        assertEquals(emptyList<String>(), merged["statusChanged"])
        assertEquals("BACKLOG", statuses(1).single())
        assertEquals(2, count("select count(*) from issue_git_links"))
    }

    @Test
    fun `상태 변경은 구독자에게 알림으로 가고 연결과 커밋은 그것을 고른 API 알림에만 간다`() {
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            """{"name":"받기","url":"$outside/notify","method":"POST","contentType":"application/json","headers":{},"bodyTemplate":"","eventTypes":[]}""",
        )
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            """{"name":"Git","url":"$outside/git","method":"POST","contentType":"application/json","headers":{},"bodyTemplate":"",""" +
                """"eventTypes":["GIT_PR_LINKED","GIT_COMMITS_PUSHED"]}""",
        )
        val kim = admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$PASSWORD","displayName":"김지민"}""").json()["id"]
        admin.post("/api/v1/issues", """{"title":"로그인 오류","assigneeIds":["$kim"]}""")
        val connection = connect()
        // What the admin did (making the issue) goes out first, to the assignee.
        dispatcher.flush(1, adminId())
        awaitTrue { received.size == 1 }
        received.clear()

        // A link and commits only: the API notification that chose them gets them, once per follower; nothing else does.
        deliver(connection, "pull_request", pullRequest(number = 50, title = "정리", body = "Refs FB-1", branch = "chore"))
        deliver(connection, "push", push("main", "FB-1 언급"))
        dispatcher.flush(1, null)
        awaitTrue { received.size == 2 }
        Thread.sleep(300)
        assertEquals(listOf("/git", "/git"), received.map { it.first })
        assertEquals(listOf("GIT_PR_LINKED", "GIT_COMMITS_PUSHED"), changeTypes(received.first().second))
        assertTrue(
            mapper
                .readTree(received.first().second)
                .get("text")
                .asString()
                .contains("PR 연결: web#50 정리"),
        )
        received.clear()

        // Work started by someone without an account here: everyone who follows the issue hears it.
        deliver(connection, "push", push("fb-1-login", pusher = Triple("parkhs", "parkhs@example.com", "박현수")))
        dispatcher.flush(1, null)
        awaitTrue { received.size == 4 }
        val plain = received.filter { it.first == "/notify" }
        assertEquals(2, plain.size)
        val payload = mapper.readTree(plain.first().second)
        assertEquals("parkhs(Git)", payload.get("actor").get("name").asString())
        assertEquals(listOf("STATUS_CHANGED"), changeTypes(plain.first().second))
        assertEquals(
            "IN_PROGRESS",
            payload
                .get("changes")
                .get(0)
                .get("detail")
                .get("to")
                .asString(),
        )
        assertTrue(payload.get("text").asString().contains("parkhs(Git) 님이 Git에서 티켓을 수정했습니다"))
        // The one that chose Git's kinds gets the whole change, the commit with the status.
        assertEquals(listOf("GIT_BRANCH_LINKED", "GIT_COMMITS_PUSHED", "STATUS_CHANGED"), changeTypes(received.first { it.first == "/git" }.second))
    }

    private fun changeTypes(body: String): List<String> {
        val changes = mapper.readTree(body).get("changes")
        return (0 until changes.size()).map { changes.get(it).get("type").asString() }
    }

    @Test
    fun `Git 이 쓰이는지는 누구나 알 수 있고 켜진 연결이 있을 때만 참이다`() {
        admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""")
        val member = client().also { it.login("member", PASSWORD) }
        assertEquals(mapOf("enabled" to false), member.get("/api/v1/git/status").json())

        val connection = connect()
        assertEquals(mapOf("enabled" to true), member.get("/api/v1/git/status").json())
        admin.put("/api/v1/admin/git/connections/${connection["id"]}", connectionJson(enabled = false))
        assertEquals(mapOf("enabled" to false), member.get("/api/v1/git/status").json())
        // A GitHub connection counts once its app is installed.
        admin.post("/api/v1/admin/git/connections", """{"name":"GitHub","provider":"GITHUB","githubOrganization":"acme"}""")
        assertEquals(mapOf("enabled" to false), member.get("/api/v1/git/status").json())
        assertEquals(401, client().get("/api/v1/git/status").status)
    }

    @Test
    fun `저장소가 여럿인 프로젝트의 티켓은 처음 연결된 브랜치의 저장소로 저장소가 정해진다`() {
        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        admin.post("/api/v1/issues", """{"title":"웹 티켓","projectId":"${p("WEB")}"}""") // FB-1
        admin.post("/api/v1/issues", """{"title":"이미 고른 티켓","projectId":"${p("WEB")}"}""") // FB-2
        val connection = connect(autoInProgress = false)
        val id = connection["id"] as String
        admin.put(
            "/api/v1/projects/${p("WEB")}/git/repositories",
            """{"repositories":[{"connectionId":"$id","repository":"acme/web"},{"connectionId":"$id","repository":"acme/api"}]}""",
        )
        admin.put("/api/v1/issues/2/git-repository", """{"connectionId":"$id","repository":"acme/web"}""")

        // The first branch is in acme/api: that becomes FB-1's repository, and acme/web no longer reaches it.
        deliver(connection, "push", push("feature/fb-1-login", repository = "acme/api"))
        assertEquals("acme/api", (admin.get("/api/v1/issues/1").json()["gitRepository"] as Map<*, *>)["repository"])
        assertEquals(emptyList<String>(), deliver(connection, "push", push("feature/fb-1-more", repository = "acme/web")).json()["linkedIssues"])

        // A pick that exists is never changed by Git.
        deliver(connection, "push", push("feature/fb-2-x", repository = "acme/web"))
        assertEquals("acme/web", (admin.get("/api/v1/issues/2").json()["gitRepository"] as Map<*, *>)["repository"])
    }

    @Test
    fun `프로젝트마다 Git 저장소를 정하면 그 저장소에서만 연결하고 정하지 않은 프로젝트는 모든 저장소에서 연결한다`() {
        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        remember("OPS", admin.post("/api/v1/projects", """{"name":"운영"}""").json()["id"])
        admin.post("/api/v1/issues", """{"title":"웹 티켓","projectId":"${p("WEB")}"}""") // FB-1
        admin.post("/api/v1/issues", """{"title":"운영 티켓","projectId":"${p("OPS")}"}""") // FB-2
        val editor = admin.post("/api/v1/admin/members", """{"loginId":"editor","password":"$PASSWORD"}""").json()["id"]
        admin.put("/api/v1/projects/${p("OPS")}/members/$editor", """{"role":"EDITOR"}""")
        admin.post("/api/v1/admin/members", """{"loginId":"outsider","password":"$PASSWORD"}""")
        val editorClient = client().also { it.login("editor", PASSWORD) }
        val outsider = client().also { it.login("outsider", PASSWORD) }
        val connection = connect(autoInProgress = false)
        val id = connection["id"] as String

        // No project chose repositories yet: any repository reaches any issue.
        assertEquals(listOf("FB-1", "FB-2"), deliver(connection, "push", push("main", "FB-1 FB-2", repository = "acme/web")).json()["linkedIssues"])

        // OPS's managers say its work is in acme/ops.
        assertEquals(
            403,
            editorClient.put("/api/v1/projects/${p("OPS")}/git/repositories", """{"repositories":[{"connectionId":"$id","repository":"acme/ops"}]}""").status,
        )
        assertEquals(404, outsider.get("/api/v1/projects/${p("OPS")}/git").status)
        assertEquals(422, admin.put("/api/v1/projects/${p("OPS")}/git/repositories", """{"repositories":[{"connectionId":"$id","repository":"ops"}]}""").status)
        assertEquals(
            422,
            admin
                .put(
                    "/api/v1/projects/${p("OPS")}/git/repositories",
                    """{"repositories":[{"connectionId":"0192f0c4-0000-7000-8000-000000000000","repository":"acme/ops"}]}""",
                ).status,
        )
        val saved = admin.put("/api/v1/projects/${p("OPS")}/git/repositories", """{"repositories":[{"connectionId":"$id","repository":" Acme/Ops "}]}""")
        assertEquals(200, saved.status)
        assertEquals(listOf(mapOf("connectionId" to id, "repository" to "acme/ops")), saved.json()["repositories"])
        val settings = editorClient.get("/api/v1/projects/${p("OPS")}/git").json()
        assertEquals(true, settings["enabled"])
        assertEquals(listOf(mapOf("connectionId" to id, "repository" to "acme/web")), settings["available"])

        assertEquals(listOf("FB-2"), deliver(connection, "push", push("main", "FB-2 정리", repository = "acme/ops")).json()["linkedIssues"])
        // From any other repository OPS is left alone.
        assertEquals(listOf("FB-1"), deliver(connection, "push", push("main", "FB-1 FB-2 둘 다", repository = "acme/web")).json()["linkedIssues"])

        // WEB chooses acme/web: another repository no longer reaches it; names match without case.
        admin.put("/api/v1/projects/${p("WEB")}/git/repositories", """{"repositories":[{"connectionId":"$id","repository":"acme/web"}]}""")
        assertEquals(emptyList<String>(), deliver(connection, "push", push("main", "FB-1 다른 곳", repository = "acme/other")).json()["linkedIssues"])
        assertEquals(listOf("FB-1"), deliver(connection, "push", push("main", "FB-1 대문자", repository = "ACME/Web")).json()["linkedIssues"])

        // Removing the repositories opens OPS to every repository again.
        admin.put("/api/v1/projects/${p("OPS")}/git/repositories", """{"repositories":[]}""")
        assertEquals(listOf("FB-2"), deliver(connection, "push", push("main", "FB-2 다시", repository = "acme/elsewhere")).json()["linkedIssues"])
    }

    @Test
    fun `저장소가 여럿인 프로젝트의 티켓은 저장소를 골라 그 저장소에서만 연결한다`() {
        remember("WEB", admin.post("/api/v1/projects", """{"name":"웹"}""").json()["id"])
        admin.post("/api/v1/issues", """{"title":"웹 티켓","projectId":"${p("WEB")}"}""") // FB-1
        admin.post("/api/v1/issues", """{"title":"혼자 있는 티켓"}""") // FB-2
        admin.post("/api/v1/admin/members", """{"loginId":"outsider","password":"$PASSWORD"}""")
        val outsider = client().also { it.login("outsider", PASSWORD) }
        val connection = connect(autoInProgress = false)
        val id = connection["id"] as String
        val both = """{"repositories":[{"connectionId":"$id","repository":"acme/web"},{"connectionId":"$id","repository":"acme/api"}]}"""
        assertEquals(200, admin.put("/api/v1/projects/${p("WEB")}/git/repositories", both).status)

        val issue = admin.get("/api/v1/issues/1").json()
        assertEquals(listOf("acme/web", "acme/api"), (issue["gitRepositoryOptions"] as List<*>).map { (it as Map<*, *>)["repository"] })
        assertFalse("gitRepository" in issue)
        // An issue on its own has nothing to pick from.
        assertEquals(emptyList<Any?>(), admin.get("/api/v1/issues/2").json()["gitRepositoryOptions"])
        assertEquals(422, admin.put("/api/v1/issues/2/git-repository", """{"connectionId":"$id","repository":"acme/web"}""").status)

        assertEquals(422, admin.put("/api/v1/issues/1/git-repository", """{"connectionId":"$id","repository":"acme/other"}""").status)
        assertEquals(404, outsider.put("/api/v1/issues/1/git-repository", """{"connectionId":"$id","repository":"acme/api"}""").status)
        assertEquals(204, admin.put("/api/v1/issues/1/git-repository", """{"connectionId":"$id","repository":"Acme/API"}""").status)
        assertEquals(
            mapOf("connectionId" to id, "repository" to "acme/api", "url" to "https://git.example.com/acme/api"),
            admin.get("/api/v1/issues/1").json()["gitRepository"],
        )

        // Picked acme/api: the project's other repository no longer reaches it.
        assertEquals(emptyList<String>(), deliver(connection, "push", push("fb-1-x", repository = "acme/web")).json()["linkedIssues"])
        assertEquals(listOf("FB-1"), deliver(connection, "push", push("fb-1-x", repository = "acme/api")).json()["linkedIssues"])

        // The project gives up acme/api: the pick no longer counts.
        admin.put("/api/v1/projects/${p("WEB")}/git/repositories", """{"repositories":[{"connectionId":"$id","repository":"acme/web"}]}""")
        assertFalse("gitRepository" in admin.get("/api/v1/issues/1").json())
        assertEquals(listOf("FB-1"), deliver(connection, "push", push("fb-1-y", repository = "acme/web")).json()["linkedIssues"])

        assertEquals(204, admin.put("/api/v1/issues/1/git-repository", "{}").status)
        assertEquals(0, count("select count(*) from issue_git_repositories"))
    }

    // --- GitLab ---------------------------------------------------------------------------------

    @Test
    fun `GitLab 은 비밀 토큰으로 확인하고 push 와 MR 로 연결하고 상태를 바꾼다`() {
        createIssues("BACKLOG")
        admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""")
        val connection = admin.post("/api/v1/admin/git/connections", connectionJson(name = "GitLab", provider = "GITLAB")).json()
        val push = mapper.writeValueAsString(sample("gitlab-push.json"))

        assertEquals(401, deliverGitLab(connection, "Push Hook", push, token = "not-the-token").status)
        assertEquals(401, deliverGitLab(connection, "Push Hook", push, token = null).status)

        val pushed = deliverGitLab(connection, "Push Hook", push).json()
        assertEquals(listOf("FB-1"), pushed["linkedIssues"])
        assertEquals(listOf("FB-1"), pushed["statusChanged"])
        assertEquals("https://gitlab.example.com/acme/web/-/tree/kimjm/fb-1-login-error", gitOf(1).single()["url"])
        assertEquals(2, count("select count(*) from issue_git_commits"))

        deliverGitLab(connection, "Merge Request Hook", mapper.writeValueAsString(sample("gitlab-merge-request.json")))
        val mr = gitOf(1).first()
        assertEquals("PULL_REQUEST" to 34, mr["kind"] to mr["prNumber"])
        assertEquals("OPEN", mr["prState"])
        assertEquals("https://gitlab.example.com/acme/web/-/merge_requests/34", mr["url"])

        val merged = deliverGitLab(connection, "Merge Request Hook", mapper.writeValueAsString(sample("gitlab-merge-request-merged.json"))).json()
        assertEquals(listOf("FB-1"), merged["statusChanged"])
        assertEquals("DONE", statuses(1).single())
        assertEquals("MERGED", gitOf(1).first()["prState"])
        // GitLab names the merger by user name only; leesj has an account here.
        assertEquals("leesj", (activity(1).last()["actor"] as Map<*, *>)["loginId"])
        assertEquals(200, deliverGitLab(connection, "Note Hook", "{}").status)
    }

    // --- helpers --------------------------------------------------------------------------------

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Adds a Gitea connection; the result holds its id and secret. */
    private fun connect(
        autoInProgress: Boolean = true,
        autoDone: Boolean = true,
    ): Map<String, Any?> =
        admin
            .post(
                "/api/v1/admin/git/connections",
                connectionJson(autoInProgress = autoInProgress, autoDone = autoDone),
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

    /** Calls the webhook as GitLab does: the secret token as it is in a header. */
    private fun deliverGitLab(
        connection: Map<String, Any?>,
        event: String,
        body: String,
        token: String? = connection["secret"] as String,
    ): ApiResponse {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/integrations/git/${connection["id"]}/webhook"))
                .header("Content-Type", "application/json")
                .header("X-Gitlab-Event", event)
        if (token != null) request.header("X-Gitlab-Token", token)
        val response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
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
        repository: String? = null,
    ): String {
        val node = sample("gitea-push.json")
        node.put("ref", "refs/heads/$branch")
        if (repository != null) (node.get("repository") as ObjectNode).put("full_name", repository)
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

    /** The merged pull request sample, by default #34 from kimjm/fb-1-login-error; merged by leesj. */
    private fun merged(
        number: Int = 34,
        title: String = "FB-1 로그인 오류 수정",
        body: String = "세션이 만료되면 로그인 화면으로 보냅니다.",
        branch: String = "kimjm/fb-1-login-error",
    ): String {
        val node = sample("gitea-pull-request-merged.json")
        node.put("number", number)
        val pr = node.get("pull_request") as ObjectNode
        pr
            .put("number", number)
            .put("title", title)
            .put("body", body)
            .put("html_url", "https://git.example.com/acme/web/pulls/$number")
        (pr.get("head") as ObjectNode).put("ref", branch).put("label", branch)
        return mapper.writeValueAsString(node)
    }

    /** Issues FB-1, FB-2, … in these states. */
    private fun createIssues(vararg statuses: String) {
        statuses.forEachIndexed { index, status -> admin.post("/api/v1/issues", """{"title":"티켓 ${index + 1}","status":"$status"}""") }
    }

    /** The states of FB-1 to FB-[last]. */
    private fun statuses(last: Int): List<Any?> = (1..last).map { admin.get("/api/v1/issues/$it").json()["status"] }

    private fun adminId(): java.util.UUID = java.util.UUID.fromString(admin.me().json()["id"] as String)

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting; received $received" }
            Thread.sleep(50)
        }
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
        autoInProgress: Boolean = true,
        autoDone: Boolean = true,
    ): String =
        """{"name":"$name","enabled":$enabled,"provider":"$provider","baseUrl":"https://git.example.com/",""" +
            """"autoInProgress":$autoInProgress,"autoDone":$autoDone}"""

    @Suppress("UNCHECKED_CAST")
    private fun fieldErrors(problem: Map<String, Any?>): Map<String, String> =
        (problem["errors"] as List<Map<String, Any?>>).associate { it["field"] as String to it["code"] as String }

    companion object {
        /** The address and body of each API notification Flowbit sent. */
        private val received = CopyOnWriteArrayList<Pair<String, String>>()

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    received += exchange.requestURI.path to String(exchange.requestBody.readAllBytes())
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        private val outside = "http://localhost:${server.address.port}"
    }
}
