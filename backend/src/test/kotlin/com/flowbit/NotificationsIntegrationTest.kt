package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** One request another system received from Flowbit. */
private data class Received(
    val path: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val query: Map<String, String> = emptyMap(),
)

class NotificationsIntegrationTest : IntegrationTest() {
    private lateinit var mail: GreenMail
    private lateinit var admin: ApiClient
    private lateinit var kim: ApiClient
    private lateinit var kimId: String

    @BeforeEach
    fun start() {
        received.clear()
        failuresLeft = 0
        mail = GreenMail(ServerSetupTest.SMTP.dynamicPort()).also { it.start() }
        admin = client().also { it.setup("admin", PASSWORD) }
        kimId = admin.post("/api/v1/admin/members", """{"loginId":"kimjm","password":"$PASSWORD","displayName":"김지민"}""").json()["id"] as String
        kim = client().also { it.login("kimjm", PASSWORD) }
    }

    @AfterEach
    fun stop() {
        mail.stop()
    }

    // --- 내 알림 설정 -----------------------------------------------------------------------------

    @Test
    fun `내 알림 설정에서 이메일을 정하고 메일 수신을 끌 수 있다`() {
        val before = kim.get("/api/v1/me/notifications").json()
        val saved = kim.patch("/api/v1/me/notifications", """{"email":" Kim@Example.com ","notifyEmail":false}""")

        assertEquals(null, before["email"])
        assertEquals(true, before["notifyEmail"])
        assertEquals(false, before["mailAvailable"])
        assertEquals(null, before["push"])
        assertEquals(200, saved.status)
        assertEquals("kim@example.com", saved.json()["email"])
        assertEquals(false, saved.json()["notifyEmail"])
        // The address also signs in.
        assertEquals(200, client().login("kim@example.com", PASSWORD).status)
    }

    @Test
    fun `틀린 형식이거나 다른 사람이 쓰는 이메일은 받지 않는다`() {
        admin.patch("/api/v1/me/notifications", """{"email":"admin@example.com"}""")

        assertEquals(422, kim.patch("/api/v1/me/notifications", """{"email":"not-an-address"}""").status)
        assertEquals(409, kim.patch("/api/v1/me/notifications", """{"email":"admin@example.com"}""").status)
        assertEquals(409, kim.patch("/api/v1/me/notifications", """{"email":"admin"}""".replace("admin", "admin@example.com")).status)
    }

    // --- 메일 -----------------------------------------------------------------------------------

    @Test
    fun `알림 설정은 관리자만 다루고 비밀번호는 돌려주지 않는다`() {
        assertEquals(403, kim.get("/api/v1/admin/notifications").status)

        val saved = saveSmtp(password = "smtp-secret")

        assertEquals(200, saved.status)
        assertFalse(saved.body.contains("smtp-secret"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (saved.json()["smtp"] as Map<String, Any?>)["passwordSet"])
        assertEquals(422, admin.put("/api/v1/admin/notifications/smtp", """{"enabled":true,"host":"","fromAddress":"x"}""").status)
    }

    @Test
    fun `티켓이 바뀌면 구독자 가운데 주소가 있고 메일을 켠 사람에게만 메일이 간다`() {
        saveSmtp()
        kim.patch("/api/v1/me/notifications", """{"email":"kim@example.com","notifyEmail":true}""")
        val leeId = admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""").json()["id"]
        val lee = client().also { it.login("leesj", PASSWORD) }
        lee.patch("/api/v1/me/notifications", """{"email":"lee@example.com","notifyEmail":false}""")
        // kim follows the issue as its author, lee as its assignee.
        kim.post("/api/v1/issues", """{"title":"결제 \"오류\" 수정","assigneeId":"$leeId"}""")

        admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""")

        assertTrue(mail.waitForIncomingEmail(10_000, 1))
        Thread.sleep(300)
        val message = mail.receivedMessages.single()
        assertEquals("kim@example.com", message.allRecipients.single().toString())
        assertEquals("[FB-1] 결제 \"오류\" 수정", message.subject)
        assertEquals("noreply@example.com", (message.from.single() as jakarta.mail.internet.InternetAddress).address)
    }

    @Test
    fun `댓글에서 태그된 사람은 구독하지 않던 티켓이어도 메일을 받는다`() {
        saveSmtp()
        kim.patch("/api/v1/me/notifications", """{"email":"kim@example.com","notifyEmail":true}""")
        admin.post("/api/v1/issues", """{"title":"배포 일정"}""")

        val comment =
            """
            {"content":{"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"mention","attrs":{"id":"$kimId","label":"김지민"}},{"type":"text","text":" 확인 부탁드립니다"}]}]}}
            """.trimIndent()
        assertEquals(201, admin.post("/api/v1/issues/1/comments", comment).status)

        assertTrue(mail.waitForIncomingEmail(10_000, 1))
        val message = mail.receivedMessages.single()
        assertEquals("kim@example.com", message.allRecipients.single().toString())
        assertEquals("[FB-1] 배포 일정", message.subject)
    }

    @Test
    fun `메일이 꺼져 있으면 보내지 않고 테스트 메일은 저장하지 않은 설정으로 보낸다`() {
        val test = admin.post("/api/v1/admin/notifications/smtp/test", smtpJson(enabled = false, testTo = "someone@example.com"))
        val refused = admin.post("/api/v1/admin/notifications/smtp/test", smtpJson(port = 1, testTo = "someone@example.com"))

        assertEquals(true, test.json()["ok"])
        assertEquals(1, mail.receivedMessages.size)
        assertEquals(false, refused.json()["ok"])
        assertEquals(0, count("select count(*) from notification_settings where smtp_enabled"))
    }

    // --- API 알림(웹훅) ---------------------------------------------------------------------------

    @Test
    fun `API 알림은 알림을 받는 사람마다 표준 JSON 으로 보내고 서명한다`() {
        val created = admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/standard", signingSecret = "hook-secret"))
        assertEquals(201, created.status)
        assertEquals(true, created.json()["signingSecretSet"])
        assertFalse(created.body.contains("hook-secret"))

        // kim follows the issue as its assignee.
        admin.post("/api/v1/issues", """{"title":"배포 준비","assigneeId":"$kimId"}""")

        val request = awaitReceived("/standard")
        val payload = JsonMapper.builder().build().readTree(request.body)
        assertEquals("POST", request.method)
        assertEquals("issue.created", payload.get("event").asString())
        assertEquals("FB-1", payload.get("issue").get("key").asString())
        assertEquals("배포 준비", payload.get("issue").get("title").asString())
        assertEquals(
            "CREATED",
            payload
                .get("changes")
                .get(0)
                .get("type")
                .asString(),
        )
        assertEquals("kimjm", payload.get("recipient").get("loginId").asString())
        assertEquals("issue.created", request.headers["x-flowbit-event"])
        assertEquals("Bearer abc", request.headers["authorization"])
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("hook-secret".toByteArray(), "HmacSHA256")) }
        assertEquals("sha256=" + HexFormat.of().formatHex(mac.doFinal(request.body.toByteArray())), request.headers["x-flowbit-signature"])
        awaitTrue { count("select count(*) from webhooks where last_status = 200") == 1 }
    }

    @Test
    fun `본문 템플릿의 자리표시자는 값이 JSON 을 깨지 않게 채워진다`() {
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            webhookJson(path = "/chat", bodyTemplate = """{\"text\":\"{{text}}\",\"key\":\"{{ issue.key }}\",\"raw\":{{payload}},\"none\":\"{{nope}}\"}"""),
        )

        admin.post("/api/v1/issues", """{"title":"따옴표 \" 와 역슬래시 \\","assigneeId":"$kimId"}""")

        val body = JsonMapper.builder().build().readTree(awaitReceived("/chat").body)
        assertTrue(body.get("text").asString().startsWith("[FB-1] 따옴표 \" 와 역슬래시 \\ — "))
        assertEquals("FB-1", body.get("key").asString())
        assertEquals("issue.created", body.get("raw").get("event").asString())
        assertEquals("", body.get("none").asString())
    }

    @Test
    fun `고른 변경 종류에만 보내고 꺼 둔 것은 보내지 않는다`() {
        admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/status-only", eventTypes = """["STATUS_CHANGED"]"""))
        admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/off", enabled = false))
        admin.post("/api/v1/issues", """{"title":"배포 준비","assigneeId":"$kimId"}""")

        admin.patch("/api/v1/issues/1", """{"status":"DONE"}""")

        val request = awaitReceived("/status-only")
        assertTrue(request.body.contains("issue.updated"))
        Thread.sleep(300)
        assertEquals(listOf("/status-only"), received.map { it.path })
    }

    @Test
    fun `받는 사람마다 한 번씩 GET 으로 부르고 값은 주소에 인코딩되어 들어간다`() {
        kim.patch("/api/v1/me/notifications", """{"email":"kim@example.com"}""")
        // A subscriber without an address cannot be reached through an API addressed by e-mail.
        val leeId = admin.post("/api/v1/admin/members", """{"loginId":"leesj","password":"$PASSWORD"}""").json()["id"]
        val created =
            admin.post(
                "/api/v1/admin/notifications/webhooks",
                webhookJson(
                    url = "$outside/works/send?botId=42&email={{recipient.email}}&message={{text}}",
                    method = "GET",
                ),
            )
        assertEquals(201, created.status)
        kim.post("/api/v1/issues", """{"title":"결제 & 환불 #1","assigneeId":"$leeId"}""")

        admin.patch("/api/v1/issues/1", """{"status":"DONE"}""")

        val request = awaitReceived("/works/send")
        Thread.sleep(300)
        assertEquals("GET", request.method)
        assertEquals("", request.body)
        assertEquals("Bearer abc", request.headers["authorization"])
        assertEquals("42", request.query["botId"])
        assertEquals("kim@example.com", request.query["email"])
        assertTrue(request.query.getValue("message").startsWith("[FB-1] 결제 & 환불 #1 — "))
        assertTrue(request.query.getValue("message").contains("상태: 백로그 → 완료"))
        assertEquals(1, received.count { it.path == "/works/send" })
    }

    @Test
    fun `프로젝트를 구독하면 그 프로젝트의 티켓 변경을 받고 아무도 구독하지 않은 변경은 보내지 않는다`() {
        admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/followed"))
        val pay = admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"]
        val search = admin.post("/api/v1/projects", """{"name":"검색"}""").json()["id"]
        admin.put("/api/v1/projects/$pay/members/$kimId", """{"role":"VIEWER"}""")
        // Not a member: cannot follow what they cannot enter.
        assertEquals(404, kim.put("/api/v1/projects/$search/subscription", "{}").status)
        assertEquals(false, kim.get("/api/v1/projects/$pay").json()["subscribed"])

        assertEquals(true, kim.put("/api/v1/projects/$pay/subscription", "{}").json()["subscribed"])
        assertEquals(mapOf(pay to true, search to false), kim.get("/api/v1/projects").jsonList().associate { it["id"] to it["subscribed"] })
        // Nobody follows the other project or an issue on its own.
        admin.post("/api/v1/projects/$search/issues", """{"title":"검색 색인"}""")
        admin.post("/api/v1/issues", """{"title":"혼자 쓰는 메모"}""")
        admin.post("/api/v1/projects/$pay/issues", """{"title":"환불 처리"}""")

        val request = awaitReceived("/followed")
        Thread.sleep(300)
        val payload = JsonMapper.builder().build().readTree(request.body)
        assertEquals("FB-3", payload.get("issue").get("key").asString())
        assertEquals("kimjm", payload.get("recipient").get("loginId").asString())
        assertEquals(1, received.count { it.path == "/followed" })

        // Someone taken off the project is no longer told, even though they never stopped following.
        admin.delete("/api/v1/projects/$pay/members/$kimId")
        admin.patch("/api/v1/issues/3", """{"status":"DONE"}""")
        admin.put("/api/v1/projects/$pay/members/$kimId", """{"role":"VIEWER"}""")
        assertEquals(false, kim.delete("/api/v1/projects/$pay/subscription").json()["subscribed"])
        admin.patch("/api/v1/issues/3", """{"priority":"URGENT"}""")
        Thread.sleep(500)
        assertEquals(1, received.count { it.path == "/followed" })
    }

    @Test
    fun `잠깐 실패한 주소는 다시 시도하고 결과를 남긴다`() {
        failuresLeft = 1
        admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/flaky"))

        admin.post("/api/v1/issues", """{"title":"배포 준비","assigneeId":"$kimId"}""")

        awaitTrue { count("select count(*) from webhooks where last_status = 200") == 1 }
        assertEquals(2, received.count { it.path == "/flaky" })
    }

    @Test
    fun `API 알림을 고치고 지우고 저장 없이 시험해 볼 수 있다`() {
        val id = admin.post("/api/v1/admin/notifications/webhooks", webhookJson(path = "/a", signingSecret = "keep-me")).json()["id"]

        val test = admin.post("/api/v1/admin/notifications/webhooks/test", webhookJson(path = "/try", method = "PUT"))
        val failing = admin.post("/api/v1/admin/notifications/webhooks/test", webhookJson(url = "http://localhost:1/x"))
        val updated = admin.put("/api/v1/admin/notifications/webhooks/$id", webhookJson(path = "/b", name = "새 이름"))

        assertEquals(true, test.json()["ok"])
        assertEquals("PUT", awaitReceived("/try").method)
        assertEquals(false, failing.json()["ok"])
        assertEquals("새 이름", updated.json()["name"])
        // Leaving the secret out keeps it.
        assertEquals(true, updated.json()["signingSecretSet"])
        assertEquals(1, admin.get("/api/v1/admin/notifications/webhooks").jsonList().size)
        assertEquals(204, admin.delete("/api/v1/admin/notifications/webhooks/$id").status)
        assertEquals(404, admin.delete("/api/v1/admin/notifications/webhooks/$id").status)
    }

    @Test
    fun `틀린 API 알림 설정은 저장하지 않는다`() {
        assertEquals(422, admin.post("/api/v1/admin/notifications/webhooks", webhookJson(url = "ftp://example.com/x")).status)
        assertEquals(422, admin.post("/api/v1/admin/notifications/webhooks", webhookJson(method = "TRACE")).status)
        assertEquals(422, admin.post("/api/v1/admin/notifications/webhooks", webhookJson(eventTypes = """["NOPE"]""")).status)
        assertEquals(422, admin.post("/api/v1/admin/notifications/webhooks", webhookJson(headers = """{"Host":"evil"}""")).status)
        assertEquals(403, kim.post("/api/v1/admin/notifications/webhooks", webhookJson()).status)
        assertEquals(0, count("select count(*) from webhooks"))
    }

    // --- 웹 푸시 ---------------------------------------------------------------------------------

    @Test
    fun `푸시를 켜면 브라우저가 등록할 설정을 받고 서비스 계정 키는 돌려주지 않는다`() {
        val saved = savePush()

        assertEquals(200, saved.status)
        assertFalse(saved.body.contains("PRIVATE KEY"))
        @Suppress("UNCHECKED_CAST")
        val push = saved.json()["push"] as Map<String, Any?>
        assertEquals(true, push["serviceAccountSet"])
        assertEquals("flowbit-test", push["projectId"])
        assertEquals(
            mapOf(
                "apiKey" to "web-api-key",
                "projectId" to "flowbit-test",
                "messagingSenderId" to "1234",
                "appId" to "1:1234:web:abc",
                "vapidKey" to "vapid-key",
            ),
            kim.get("/api/v1/me/notifications").json()["push"],
        )
        assertEquals(422, admin.put("/api/v1/admin/notifications/push", """{"enabled":true,"serviceAccount":"{}"}""").status)
    }

    @Test
    fun `등록한 브라우저로 푸시가 가고 사라진 등록은 지운다`() {
        savePush()
        assertEquals(204, kim.post("/api/v1/me/push-tokens", """{"token":"browser-1"}""").status)
        assertEquals(204, kim.post("/api/v1/me/push-tokens", """{"token":"gone-browser"}""").status)
        kim.post("/api/v1/issues", """{"title":"배포 준비"}""")

        admin.patch("/api/v1/issues/1", """{"priority":"URGENT"}""")

        awaitTrue { received.count { it.path.endsWith("messages:send") } == 2 }
        val sent = received.first { it.path.endsWith("messages:send") && it.body.contains("browser-1") }
        val message =
            JsonMapper
                .builder()
                .build()
                .readTree(sent.body)
                .get("message")
        assertEquals("/v1/projects/flowbit-test/messages:send", sent.path)
        assertEquals("Bearer fake-access-token", sent.headers["authorization"])
        assertEquals(
            "[FB-1] 배포 준비",
            message
                .get("webpush")
                .get("notification")
                .get("title")
                .asString(),
        )
        assertTrue(
            message
                .get("webpush")
                .get("notification")
                .get("body")
                .asString()
                .contains("우선순위: 우선순위 없음 → 긴급"),
        )
        assertEquals("/issue/FB-1", message.get("data").get("path").asString())
        assertNotNull(received.firstOrNull { it.path == "/token" && it.body.contains("jwt-bearer") })
        awaitTrue { count("select count(*) from push_tokens") == 1 }
    }

    @Test
    fun `브라우저 등록을 지우면 푸시가 가지 않는다`() {
        savePush()
        kim.post("/api/v1/me/push-tokens", """{"token":"browser-1"}""")
        // Another person cannot remove it.
        admin.post("/api/v1/me/push-tokens/unregister", """{"token":"browser-1"}""")
        assertEquals(1, count("select count(*) from push_tokens"))

        assertEquals(204, kim.post("/api/v1/me/push-tokens/unregister", """{"token":"browser-1"}""").status)

        assertEquals(0, count("select count(*) from push_tokens"))
        assertEquals(false, admin.post("/api/v1/admin/notifications/push/test").json()["ok"])
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting; received ${received.map { it.path }}" }
            Thread.sleep(50)
        }
    }

    private fun awaitReceived(path: String): Received {
        awaitTrue { received.any { it.path == path } }
        return received.first { it.path == path }
    }

    private fun saveSmtp(password: String? = null) = admin.put("/api/v1/admin/notifications/smtp", smtpJson(password = password))

    private fun smtpJson(
        enabled: Boolean = true,
        port: Int = mail.smtp.port,
        password: String? = null,
        testTo: String? = null,
    ): String =
        """
        {"enabled":$enabled,"host":"localhost","port":$port,"security":"NONE","username":"",
         "password":${password?.let { "\"$it\"" }},"fromAddress":"noreply@example.com","fromName":"Flowbit",
         "testTo":${testTo?.let { "\"$it\"" }}}
        """.trimIndent()

    private fun webhookJson(
        path: String = "/hook",
        url: String = "$outside$path",
        name: String = "사내 메신저",
        enabled: Boolean = true,
        method: String = "POST",
        headers: String = """{"Authorization":"Bearer abc"}""",
        bodyTemplate: String = "",
        signingSecret: String? = null,
        eventTypes: String = "[]",
    ): String =
        """
        {"name":"$name","enabled":$enabled,"url":"$url","method":"$method","contentType":"application/json",
         "headers":$headers,"bodyTemplate":"$bodyTemplate","signingSecret":${signingSecret?.let { "\"$it\"" }},"eventTypes":$eventTypes}
        """.trimIndent()

    private fun savePush(): com.flowbit.support.ApiResponse {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(key.encoded) + "\\n-----END PRIVATE KEY-----\\n"
        val account = """{"project_id":"flowbit-test","client_email":"sender@flowbit-test.example","private_key":"$pem","token_uri":"$outside/token"}"""
        val escaped = JsonMapper.builder().build().writeValueAsString(account)
        return admin.put(
            "/api/v1/admin/notifications/push",
            """{"enabled":true,"serviceAccount":$escaped,"apiKey":"web-api-key","messagingSenderId":"1234","appId":"1:1234:web:abc","vapidKey":"vapid-key"}""",
        )
    }

    companion object {
        private val received = CopyOnWriteArrayList<Received>()

        /** How many of the next webhook calls answer 503 before they start to succeed. */
        @Volatile
        private var failuresLeft = 0

        /** Everything outside Flowbit that notifications are sent to: webhook targets, Google's token endpoint and FCM. */
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    val body = String(exchange.requestBody.readAllBytes())
                    val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                    val path = exchange.requestURI.path
                    val query =
                        exchange.requestURI.rawQuery
                            .orEmpty()
                            .split("&")
                            .filter { it.contains("=") }
                            .associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
                    received += Received(path, exchange.requestMethod, headers, body, query)
                    val (status, answer) =
                        when {
                            path == "/token" -> 200 to """{"access_token":"fake-access-token","expires_in":3600}"""
                            path.endsWith("messages:send") && body.contains("gone-browser") -> 404 to """{"error":{"status":"NOT_FOUND"}}"""
                            path == "/flaky" && failuresLeft > 0 -> 503.also { failuresLeft -= 1 } to "{}"
                            else -> 200 to "{}"
                        }
                    val bytes = answer.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        private val outside = "http://localhost:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("flowbit.notifications.fcm-url") { outside }
        }
    }
}
