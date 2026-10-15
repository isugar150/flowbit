package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** GitHub as a GitHub App (docs/features/git-integration.md "GitHub: GitHub App 으로 연결", D-146), against a fake GitHub. */
class GitHubIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun reset() {
        admin = client().also { it.setup("admin", PASSWORD) }
        GitHub.reset()
    }

    @Test
    fun `GitHub 앱을 매니페스트로 만들고 조직에 설치한다`() {
        assertEquals(
            "REQUIRED",
            fieldError(admin.post("/api/v1/admin/git/connections", """{"name":"GitHub","provider":"GITHUB","baseUrl":"${GitHub.base}"}""").json()),
        )
        assertEquals(
            "UNSUPPORTED",
            fieldError(
                admin
                    .post(
                        "/api/v1/admin/git/connections",
                        """{"name":"Gitea","provider":"GITEA","baseUrl":"https://git.example.com","receiveMode":"POLL"}""",
                    ).json(),
            ),
        )
        val connection = admin.post("/api/v1/admin/git/connections", githubJson()).json()
        val id = connection["id"] as String
        assertEquals(
            mapOf(
                "organization" to "acme",
                "appId" to null,
                "appSlug" to null,
                "appUrl" to null,
                "installUrl" to null,
                "settingsUrl" to null,
                "callbackUrl" to "/settings/github/callback",
                "installationId" to null,
                "repositorySelection" to null,
            ),
            connection["github"],
        )

        val form = admin.get("/api/v1/admin/git/connections/$id/github/manifest?origin=http://localhost:3000").json()
        val action = form["action"] as String
        assertTrue(action.startsWith("${GitHub.base}/organizations/acme/settings/apps/new?state="))
        val state = action.substringAfter("state=")
        val manifest = mapper.readTree(form["manifest"] as String)
        assertEquals("http://localhost:3000/api/v1/integrations/git/$id/webhook", manifest.get("hook_attributes").get("url").asString())
        assertEquals(true, manifest.get("hook_attributes").get("active").asBoolean())
        assertEquals("http://localhost:3000/admin/git/github/created", manifest.get("redirect_url").asString())
        assertEquals("http://localhost:3000/admin/git/github/installed", manifest.get("setup_url").asString())
        assertEquals("http://localhost:3000/settings/github/callback", manifest.get("callback_urls").get(0).asString())
        assertEquals("read", manifest.get("default_permissions").get("pull_requests").asString())
        assertEquals(listOf("push", "pull_request"), (0 until manifest.get("default_events").size()).map { manifest.get("default_events").get(it).asString() })

        assertEquals(422, admin.post("/api/v1/admin/git/github/created", """{"code":"good-code","state":"wrong"}""").status)
        assertEquals(502, admin.post("/api/v1/admin/git/github/created", """{"code":"expired-code","state":"$state"}""").status)
        val created = admin.post("/api/v1/admin/git/github/created", """{"code":"good-code","state":"$state"}""")
        assertEquals(200, created.status)
        @Suppress("UNCHECKED_CAST")
        val app = created.json()["github"] as Map<String, Any?>
        assertEquals(99, app["appId"])
        assertEquals("flowbit-acme", app["appSlug"])
        assertEquals("${GitHub.base}/apps/flowbit-acme/installations/new", app["installUrl"])
        assertNull(app["installationId"])
        assertEquals("gh-webhook-secret", text("select secret from git_connections"))
        // The code is used up: the state no longer leads anywhere.
        assertEquals(422, admin.post("/api/v1/admin/git/github/created", """{"code":"good-code","state":"$state"}""").status)
        assertEquals(409, admin.get("/api/v1/admin/git/connections/$id/github/manifest?origin=http://localhost:3000").status)

        // Installed on GitHub: the page GitHub sends back to reads it.
        GitHub.installed = true
        @Suppress("UNCHECKED_CAST")
        val installed = admin.post("/api/v1/admin/git/github/installations").jsonList().single()["github"] as Map<String, Any?>
        assertEquals(555, installed["installationId"])
        assertEquals("selected", installed["repositorySelection"])
        // Every call to GitHub was signed by the app's key.
        assertTrue(GitHub.jwtChecks > 0)
        assertEquals(0, GitHub.badJwts)

        // GitHub keeps the webhook secret; the organization cannot change under an app.
        assertEquals(422, admin.post("/api/v1/admin/git/connections/$id/secret").status)
        assertEquals(422, admin.put("/api/v1/admin/git/connections/$id", githubJson(organization = "other")).status)
    }

    @Test
    fun `GitHub 앱의 웹훅은 서명을 확인하고 push 와 PR 로 연결하고 설치 이벤트로 설치를 다시 읽는다`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""")
        val id = installedConnection()

        assertEquals(200, deliver(id, "ping", sample("github-ping.json")).status)
        assertEquals(401, deliver(id, "push", sample("github-push.json"), secret = "wrong").status)

        val pushed = deliver(id, "push", sample("github-push.json")).json()
        assertEquals(listOf("FB-1"), pushed["statusChanged"])
        assertEquals("https://github.com/acme/web/tree/kimjm/fb-1-login-error", gitOf(1).single()["url"])
        deliver(id, "pull_request", sample("github-pull-request.json"))
        val merged = deliver(id, "pull_request", sample("github-pull-request-merged.json")).json()
        assertEquals(listOf("FB-1"), merged["statusChanged"])
        assertEquals("DONE", admin.get("/api/v1/issues/1").json()["status"])
        assertEquals("https://github.com/acme/web/pull/34", gitOf(1).first()["url"])
        // merged_by is leesj, who has an account here.
        @Suppress("UNCHECKED_CAST")
        val last = (admin.get("/api/v1/issues/1/activity").json()["items"] as List<Map<String, Any?>>).last()
        assertEquals("leesj", (last["actor"] as Map<*, *>)["loginId"])

        GitHub.installationId = 556
        assertEquals(200, deliver(id, "installation", """{"action":"created","installation":{"id":556}}""").status)
        assertEquals("556", text("select github_installation_id::text from git_connections"))
    }

    @Test
    fun `가져오기는 첫 번째에 기준만 잡고 그 뒤의 브랜치, 커밋, PR 을 연결한다`() {
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        admin.post("/api/v1/issues", """{"title":"결제 오류"}""")
        admin.post("/api/v1/issues", """{"title":"예전 일"}""")
        // Things that happened before the connection.
        GitHub.pulls += pull(10, "FB-3 예전 작업", "fb-3-old", merged = true, updatedAt = "2026-01-01T00:00:00Z")
        GitHub.branches["main"] = "s0"
        GitHub.branches["fb-3-old"] = "old"
        val id = installedConnection(mode = "POLL")

        val first = admin.post("/api/v1/admin/git/connections/$id/sync")
        assertEquals(200, first.status)
        assertEquals(emptyList<Any?>(), first.json()["linkedIssues"])
        assertEquals("BACKLOG", status(3))

        // Nothing changed: GitHub answers 304 and nothing is read again.
        val before = GitHub.fullAnswers.get()
        assertEquals(emptyList<Any?>(), admin.post("/api/v1/admin/git/connections/$id/sync").json()["linkedIssues"])
        assertEquals(before + 1, GitHub.fullAnswers.get()) // the repository list only

        // A branch for FB-1 with a commit, and a pull request.
        GitHub.branches["kimjm/fb-1-login"] = "s1"
        GitHub.compares["main...s1"] = listOf(commitJson("s1", "로그인 수정", "kimjm"))
        GitHub.pulls += pull(34, "FB-1 로그인 오류 수정", "kimjm/fb-1-login", updatedAt = Instant.now().toString())
        val second = admin.post("/api/v1/admin/git/connections/$id/sync").json()
        assertEquals(listOf("FB-1"), second["linkedIssues"])
        assertEquals(listOf("FB-1"), second["statusChanged"])
        assertEquals("IN_PROGRESS", status(1))
        assertEquals(1, count("select count(*) from issue_git_commits"))
        // The branch and its pull request are both linked; the panel shows the branch by its pull request.
        assertEquals(2, count("select count(*) from issue_git_links"))
        assertEquals(listOf("PULL_REQUEST"), gitOf(1).map { it["kind"] })

        // Merged by leesj, and a commit straight to main that fixes FB-2.
        GitHub.pulls.replaceAll {
            if (it.get("number").asInt() ==
                34
            ) {
                pull(34, "FB-1 로그인 오류 수정", "kimjm/fb-1-login", merged = true, updatedAt = Instant.now().plusSeconds(5).toString())
            } else {
                it
            }
        }
        GitHub.branches["main"] = "s2"
        GitHub.compares["s0...s2"] = listOf(commitJson("s2", "Fixes FB-2 결제 오류", "leesj"))
        val third = admin.post("/api/v1/admin/git/connections/$id/sync").json()
        assertEquals(listOf("FB-1", "FB-2"), third["statusChanged"])
        assertEquals(listOf("DONE", "DONE", "BACKLOG"), (1..3).map(::status))

        val connection = admin.get("/api/v1/admin/git/connections").jsonList().single()
        assertTrue(connection["lastSyncedAt"] != null)
        assertNull(connection["syncError"])
        assertEquals(4321, connection["rateLimitRemaining"])
        assertEquals(0, GitHub.badJwts)

        // GitHub failing is kept for the administrator to see.
        GitHub.failRepositories = true
        assertEquals(502, admin.post("/api/v1/admin/git/connections/$id/sync").status)
        assertTrue((admin.get("/api/v1/admin/git/connections").jsonList().single()["syncError"] as String).contains("500"))
    }

    @Test
    fun `내 설정에서 GitHub 계정을 연결하면 그 계정의 GitHub 이벤트가 내 것이 된다`() {
        val jiminId = admin.post("/api/v1/admin/members", """{"loginId":"jimin","password":"$PASSWORD","displayName":"김지민"}""").json()["id"]
        admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""")
        val jimin = client().also { it.login("jimin", PASSWORD) }
        val lee = client().also { it.login("leesj", PASSWORD) }

        // No GitHub App yet: nothing to connect through.
        assertEquals(mapOf("available" to false, "login" to null, "linkedAt" to null), jimin.get("/api/v1/me/git/github").json())
        assertEquals(409, jimin.post("/api/v1/me/git/github/authorize?origin=http://localhost:3000").status)

        val connection = installedConnection()
        assertEquals(true, jimin.get("/api/v1/me/git/github").json()["available"])
        val url = jimin.post("/api/v1/me/git/github/authorize?origin=http://localhost:3000").json()["url"] as String
        assertTrue(
            url.startsWith(
                "${GitHub.base}/login/oauth/authorize?client_id=Iv1.test&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fsettings%2Fgithub%2Fcallback&state=",
            ),
        )
        val state = url.substringAfter("state=").substringBefore("&")

        // Someone else cannot finish it, and a state is used once.
        assertEquals(422, lee.post("/api/v1/me/git/github/callback", """{"code":"user-code","state":"$state"}""").status)
        assertEquals(422, jimin.post("/api/v1/me/git/github/callback", """{"code":"user-code","state":"$state"}""").status)

        val bad = jimin.post("/api/v1/me/git/github/authorize?origin=http://localhost:3000").json()["url"] as String
        assertEquals(
            502,
            jimin.post("/api/v1/me/git/github/callback", """{"code":"stale-code","state":"${bad.substringAfter("state=").substringBefore("&")}"}""").status,
        )

        val good = jimin.post("/api/v1/me/git/github/authorize?origin=http://localhost:3000").json()["url"] as String
        val linked = jimin.post("/api/v1/me/git/github/callback", """{"code":"user-code","state":"${good.substringAfter("state=").substringBefore("&")}"}""")
        assertEquals(200, linked.status)
        assertEquals("kimjm", linked.json()["login"])
        assertEquals("http://localhost:3000/settings/github/callback", GitHub.lastRedirectUri)

        // The same GitHub account cannot be someone else's too.
        val other = lee.post("/api/v1/me/git/github/authorize?origin=http://localhost:3000").json()["url"] as String
        assertEquals(
            409,
            lee.post("/api/v1/me/git/github/callback", """{"code":"user-code","state":"${other.substringAfter("state=").substringBefore("&")}"}""").status,
        )

        // The administrator sees it next to the Git user names they set.
        val accounts = admin.get("/api/v1/admin/git/accounts").jsonList().associate { it["loginId"] to it["githubLogin"] }
        assertEquals("kimjm", accounts["jimin"])

        // A push by GitHub user kimjm (a no-reply address, and not jimin's login id) is jimin's.
        admin.post("/api/v1/issues", """{"title":"로그인 오류"}""")
        deliver(connection, "push", sample("github-push.json"))
        @Suppress("UNCHECKED_CAST")
        val items = admin.get("/api/v1/issues/1/activity").json()["items"] as List<Map<String, Any?>>
        assertEquals(jiminId, (items.last()["actor"] as Map<*, *>)["id"])

        assertEquals(204, jimin.delete("/api/v1/me/git/github").status)
        assertNull(jimin.get("/api/v1/me/git/github").json()["login"])
    }

    // --- helpers --------------------------------------------------------------------------------

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun githubJson(
        organization: String = "acme",
        mode: String = "WEBHOOK",
    ) = """{"name":"GitHub","provider":"GITHUB","baseUrl":"${GitHub.base}","githubOrganization":"$organization","receiveMode":"$mode"}"""

    /** A GitHub connection whose app is made and installed; returns its id. */
    private fun installedConnection(mode: String = "WEBHOOK"): String {
        val id = admin.post("/api/v1/admin/git/connections", githubJson(mode = mode)).json()["id"] as String
        val action = admin.get("/api/v1/admin/git/connections/$id/github/manifest?origin=http://localhost:3000").json()["action"] as String
        admin.post("/api/v1/admin/git/github/created", """{"code":"good-code","state":"${action.substringAfter("state=")}"}""")
        GitHub.installed = true
        admin.post("/api/v1/admin/git/connections/$id/github/installation")
        return id
    }

    private fun deliver(
        id: String,
        event: String,
        body: String,
        secret: String = "gh-webhook-secret",
    ): ApiResponse {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/integrations/git/$id/webhook"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", "72d3162e-cc78-11e3-81ab-4c9367dc0958")
                .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.toByteArray())))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun sample(name: String): String = checkNotNull(javaClass.getResource("/git/$name")).readText()

    @Suppress("UNCHECKED_CAST")
    private fun gitOf(number: Int): List<Map<String, Any?>> = admin.get("/api/v1/issues/$number").json()["git"] as List<Map<String, Any?>>

    private fun status(number: Int): Any? = admin.get("/api/v1/issues/$number").json()["status"]

    @Suppress("UNCHECKED_CAST")
    private fun fieldError(problem: Map<String, Any?>): Any? = (problem["errors"] as List<Map<String, Any?>>).single()["code"]

    private fun pull(
        number: Int,
        title: String,
        branch: String,
        merged: Boolean = false,
        updatedAt: String,
    ): ObjectNode =
        mapper.readTree(
            """
            {"number":$number,"title":"$title","body":"","state":"${if (merged) "closed" else "open"}","draft":false,
             "html_url":"https://github.com/acme/web/pull/$number","user":{"login":"kimjm"},"head":{"ref":"$branch"},
             "updated_at":"$updatedAt","merged_at":${if (merged) "\"$updatedAt\"" else "null"}}
            """.trimIndent(),
        ) as ObjectNode

    private fun commitJson(
        sha: String,
        message: String,
        login: String,
    ): String =
        """{"sha":"$sha","html_url":"https://github.com/acme/web/commit/$sha","author":{"login":"$login"},""" +
            """"commit":{"message":"$message","author":{"name":"$login","email":"$login@example.com","date":"2026-10-16T01:00:00Z"}}}"""

    /** A fake GitHub: the app API, the installation and one repository, acme/web. */
    private object GitHub {
        private val mapper: JsonMapper = JsonMapper.builder().build()
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** The key as GitHub hands it out: PKCS#1 (Java's encoding is PKCS#8, which wraps it after 26 bytes). */
        private val pem =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded.copyOfRange(26, keys.private.encoded.size)) +
                "\n-----END RSA PRIVATE KEY-----\n"

        @Volatile var installed = false

        @Volatile var installationId = 555

        @Volatile var failRepositories = false

        @Volatile var jwtChecks = 0

        @Volatile var badJwts = 0

        @Volatile var lastRedirectUri: String? = null
        val fullAnswers =
            java.util.concurrent.atomic
                .AtomicInteger()
        val pulls = CopyOnWriteArrayList<ObjectNode>()
        val branches = ConcurrentHashMap<String, String>()
        val compares = ConcurrentHashMap<String, List<String>>()

        fun reset() {
            installed = false
            installationId = 555
            failRepositories = false
            jwtChecks = 0
            badJwts = 0
            lastRedirectUri = null
            fullAnswers.set(0)
            pulls.clear()
            branches.clear()
            compares.clear()
        }

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/api/v3/") { exchange -> handle(exchange) }
                createContext("/login/oauth/access_token") { exchange -> userToken(exchange) }
                start()
            }

        val base = "http://localhost:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/api/v3")
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            exchange.requestBody.readAllBytes()
            when {
                path.startsWith("/app-manifests/") -> {
                    val code = path.removePrefix("/app-manifests/").removeSuffix("/conversions")
                    if (code != "good-code") return answer(exchange, 404, """{"message":"Not Found"}""")
                    val body =
                        mapper.writeValueAsString(
                            mapOf(
                                "id" to 99,
                                "slug" to "flowbit-acme",
                                "client_id" to "Iv1.test",
                                "client_secret" to "client-secret",
                                "webhook_secret" to "gh-webhook-secret",
                                "pem" to pem,
                                "owner" to mapOf("login" to "acme"),
                            ),
                        )
                    answer(exchange, 201, body)
                }
                path == "/orgs/acme/installation" -> {
                    checkJwt(authorization)
                    if (!installed) return answer(exchange, 404, """{"message":"Not Found"}""")
                    answer(exchange, 200, """{"id":$installationId,"account":{"login":"acme"},"repository_selection":"selected"}""")
                }
                path.startsWith("/app/installations/") && path.endsWith("/access_tokens") -> {
                    checkJwt(authorization)
                    answer(exchange, 201, """{"token":"ghs_test-token","expires_at":"${Instant.now().plusSeconds(3600)}"}""")
                }
                path == "/user" && authorization == "Bearer ghu_user-token" -> answer(exchange, 200, """{"login":"KimJM","id":5001}""")
                authorization != "Bearer ghs_test-token" -> answer(exchange, 401, """{"message":"Bad credentials"}""")
                path == "/installation/repositories" -> {
                    if (failRepositories) return answer(exchange, 500, "{}")
                    answer(
                        exchange,
                        200,
                        """{"total_count":1,"repositories":[{"full_name":"acme/web","default_branch":"main","html_url":"https://github.com/acme/web"}]}""",
                    )
                }
                path == "/repos/acme/web/pulls" -> listAnswer(exchange, mapper.writeValueAsString(pulls.sortedByDescending { it.get("updated_at").asString() }))
                path.startsWith("/repos/acme/web/pulls/") -> {
                    val pr = pulls.first { it.get("number").asInt().toString() == path.substringAfterLast("/") }.deepCopy()
                    if (!pr.get("merged_at").isNull) pr.putObject("merged_by").put("login", "leesj")
                    answer(exchange, 200, mapper.writeValueAsString(pr))
                }
                path == "/repos/acme/web/branches" ->
                    listAnswer(exchange, mapper.writeValueAsString(branches.map { (name, sha) -> mapOf("name" to name, "commit" to mapOf("sha" to sha)) }))
                path.startsWith("/repos/acme/web/compare/") -> {
                    val commits = compares[path.removePrefix("/repos/acme/web/compare/")].orEmpty()
                    answer(exchange, 200, """{"commits":[${commits.joinToString(",")}]}""")
                }
                else -> answer(exchange, 404, """{"message":"Not Found"}""")
            }
        }

        /** The user authorization's code exchange: only `user-code`, and only in JSON when asked so. */
        private fun userToken(exchange: HttpExchange) {
            val body = mapper.readTree(exchange.requestBody.readAllBytes())
            lastRedirectUri = body.get("redirect_uri")?.asString()
            val json =
                exchange.requestHeaders
                    .getFirst("Accept")
                    .orEmpty()
                    .contains("application/json")
            val ok =
                json &&
                    body.get("client_id")?.asString() == "Iv1.test" &&
                    body.get("client_secret")?.asString() == "client-secret" &&
                    body.get("code")?.asString() == "user-code"
            // GitHub answers 200 with an error in the body for a bad code.
            answer(
                exchange,
                200,
                if (ok) {
                    """{"access_token":"ghu_user-token","token_type":"bearer","scope":""}"""
                } else {
                    """{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}"""
                },
            )
        }

        /** Lists answer 304 when the caller already has them, as GitHub does for a matching ETag. */
        private fun listAnswer(
            exchange: HttpExchange,
            body: String,
        ) {
            val etag = "\"" + Integer.toHexString(body.hashCode()) + "\""
            exchange.responseHeaders.add("ETag", etag)
            if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return
            }
            answer(exchange, 200, body)
        }

        private fun answer(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            if (status == 200) fullAnswers.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("X-RateLimit-Remaining", "4321")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        /** The app's JWT: RS256, signed by the app's key, issued by its client id, ten minutes at most. */
        private fun checkJwt(authorization: String) {
            jwtChecks += 1
            val parts = authorization.removePrefix("Bearer ").split(".")
            val ok =
                parts.size == 3 &&
                    runCatching {
                        val verifier = Signature.getInstance("SHA256withRSA")
                        verifier.initVerify(keys.public as RSAPublicKey)
                        verifier.update("${parts[0]}.${parts[1]}".toByteArray())
                        val claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
                        verifier.verify(Base64.getUrlDecoder().decode(parts[2])) &&
                            claims.get("iss").asString() == "Iv1.test" &&
                            claims.get("exp").asLong() - claims.get("iat").asLong() <= 600
                    }.getOrDefault(false)
            if (!ok) badJwts += 1
        }
    }
}
