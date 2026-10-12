package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.FakeOidcProvider
import com.flowbit.support.IntegrationTest
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val LDAP_PASSWORD = "ldap-secret-1"

class ExternalAuthIntegrationTest : IntegrationTest() {
    private lateinit var directory: InMemoryDirectoryServer
    private lateinit var provider: FakeOidcProvider
    private lateinit var admin: ApiClient

    @BeforeEach
    fun start() {
        val config = InMemoryDirectoryServerConfig("dc=example,dc=com")
        config.addAdditionalBindCredentials("cn=search,dc=example,dc=com", "search-secret")
        directory = InMemoryDirectoryServer(config)
        directory.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example")
        directory.add("dn: ou=people,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: people")
        addPerson("kim", "kim@example.com", "김지민")
        addPerson("lee", "lee@example.com", "이서준")
        directory.startListening()
        provider = FakeOidcProvider()

        admin = client()
        admin.setup("admin", PASSWORD)
    }

    @AfterEach
    fun stop() {
        directory.shutDown(true)
        provider.close()
    }

    // --- 이메일 아이디 ---------------------------------------------------------------------------

    @Test
    fun `이메일 주소를 아이디로 쓰는 구성원을 만들고 그 주소로 로그인할 수 있다`() {
        val created = admin.post("/api/v1/admin/members", """{"loginId":"Park@Example.com","password":"$PASSWORD"}""")

        assertEquals(201, created.status)
        assertEquals("park@example.com", created.json()["loginId"])
        assertEquals("park@example.com", created.json()["email"])
        assertEquals(200, client().login("park@example.com", PASSWORD).status)
    }

    @Test
    fun `형식이 틀린 이메일 아이디는 거부한다`() {
        val response = admin.post("/api/v1/admin/members", """{"loginId":"park@home","password":"$PASSWORD"}""")

        assertEquals(422, response.status)
    }

    // --- LDAP -----------------------------------------------------------------------------------

    @Test
    fun `LDAP 설정은 관리자만 보고 바꿀 수 있고 비밀번호는 돌려주지 않는다`() {
        assertEquals(401, client().get("/api/v1/admin/auth/ldap").status)

        val saved = saveLdap()

        assertEquals(200, saved.status)
        assertEquals(true, saved.json()["bindPasswordSet"])
        assertFalse(saved.body.contains("search-secret"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'AUTH_SETTINGS_CHANGED'"))
        // Leaving the password out keeps the stored one.
        val again = admin.put("/api/v1/admin/auth/ldap", ldapJson(bindPassword = null))
        assertEquals(true, again.json()["bindPasswordSet"])
        assertEquals("search-secret", text("select bind_password from ldap_settings"))
    }

    @Test
    fun `LDAP 를 켤 때 설정이 틀리면 저장하지 않는다`() {
        val response = admin.put("/api/v1/admin/auth/ldap", ldapJson(url = "http://example.com", userFilter = "(uid=kim)"))

        assertEquals(422, response.status)
        assertEquals(0, count("select count(*) from ldap_settings where enabled"))
    }

    @Test
    fun `LDAP 연결 테스트는 저장하지 않고 결과와 찾은 사용자를 알려 준다`() {
        val ok = admin.post("/api/v1/admin/auth/ldap/test", ldapJson(testLoginId = "kim@example.com"))
        val wrongAccount = admin.post("/api/v1/admin/auth/ldap/test", ldapJson(bindPassword = "nope"))
        val unknown = admin.post("/api/v1/admin/auth/ldap/test", ldapJson(testLoginId = "nobody"))

        assertEquals(true, ok.json()["ok"])
        assertEquals("kim", ok.json()["loginId"])
        assertEquals("김지민", ok.json()["displayName"])
        assertEquals(false, wrongAccount.json()["ok"])
        assertEquals(false, unknown.json()["ok"])
        assertEquals(0, count("select count(*) from ldap_settings where enabled"))
    }

    @Test
    fun `LDAP 사용자가 처음 로그인하면 계정이 만들어진다`() {
        saveLdap()
        val client = client()

        val login = client.login("kim", LDAP_PASSWORD)

        assertEquals(200, login.status)
        assertEquals("kim", login.json()["loginId"])
        assertEquals("김지민", login.json()["displayName"])
        assertEquals("MEMBER", login.json()["role"])
        assertEquals("kim", client.me().json()["loginId"])
        assertEquals("LDAP", text("select auth_source from users where login_id = 'kim'"))
        assertEquals("kim@example.com", text("select email from users where login_id = 'kim'"))
        assertEquals(1, count("select count(*) from users where login_id = 'kim' and password_hash is null"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'MEMBER_PROVISIONED'"))
    }

    @Test
    fun `LDAP 사용자는 이메일 주소로도 로그인하고 같은 계정으로 들어온다`() {
        saveLdap()

        assertEquals(200, client().login("Kim@Example.com", LDAP_PASSWORD).status)
        assertEquals(200, client().login("kim", LDAP_PASSWORD).status)

        assertEquals(1, count("select count(*) from users where auth_source = 'LDAP'"))
    }

    @Test
    fun `LDAP 비밀번호가 틀리거나 비어 있으면 로그인할 수 없고 계정도 만들지 않는다`() {
        saveLdap()

        val wrong = client().login("kim", "wrong-password-1")
        val empty = client().login("kim", "")

        assertEquals(401, wrong.status)
        assertEquals("INVALID_CREDENTIALS", wrong.json()["code"])
        assertEquals(401, empty.status)
        assertEquals(1, count("select count(*) from users"))
    }

    @Test
    fun `LDAP 가 꺼져 있으면 디렉터리 사용자는 로그인할 수 없다`() {
        assertEquals(401, client().login("kim", LDAP_PASSWORD).status)
    }

    @Test
    fun `자동 가입을 끄면 계정이 없는 LDAP 사용자는 로그인할 수 없다`() {
        saveLdap()
        assertEquals(200, client().login("kim", LDAP_PASSWORD).status)
        admin.put("/api/v1/admin/auth/ldap", ldapJson(autoProvision = false))

        assertEquals(200, client().login("kim", LDAP_PASSWORD).status)
        assertEquals(401, client().login("lee", LDAP_PASSWORD).status)
        assertEquals(0, count("select count(*) from users where login_id = 'lee'"))
    }

    @Test
    fun `같은 아이디의 로컬 계정은 LDAP 비밀번호로 로그인하면 LDAP 계정으로 바뀐다`() {
        val id = admin.post("/api/v1/admin/members", """{"loginId":"lee1","password":"$PASSWORD","displayName":"원래 이름"}""").json()["id"]
        addPerson("lee1", "lee1@example.com", "디렉터리 이름")
        saveLdap()

        // Until the directory vouches for them, the password kept here still works.
        assertEquals(200, client().login("lee1", PASSWORD).status)
        assertEquals("LOCAL", text("select auth_source from users where login_id = 'lee1'"))

        val converted = client().login("lee1", LDAP_PASSWORD)

        assertEquals(200, converted.status)
        // The same account: its id and what it was set up with stay.
        assertEquals(id, converted.json()["id"])
        assertEquals("원래 이름", converted.json()["displayName"])
        assertEquals("LDAP", text("select auth_source from users where login_id = 'lee1'"))
        assertEquals(1, count("select count(*) from users where login_id = 'lee1' and password_hash is null"))
        assertEquals(1, count("select count(*) from audit_events where event_type = 'MEMBER_CONVERTED'"))
        // From now on only the directory's password works.
        assertEquals(401, client().login("lee1", PASSWORD).status)
        assertEquals(200, client().login("lee1", LDAP_PASSWORD).status)
    }

    @Test
    fun `LDAP 을 쓰는 동안에는 구성원을 직접 추가할 수 없고 관리자 비밀번호만 재설정할 수 있다`() {
        val memberId = admin.post("/api/v1/admin/members", """{"loginId":"park1","password":"$PASSWORD"}""").json()["id"]
        saveLdap()
        val adminId = admin.me().json()["id"]

        val added = admin.post("/api/v1/admin/members", """{"loginId":"choi1","password":"$PASSWORD"}""")
        val memberReset = admin.put("/api/v1/admin/members/$memberId/password", """{"password":"another-horse-battery"}""")
        val adminReset = admin.put("/api/v1/admin/members/$adminId/password", """{"password":"another-horse-battery"}""")

        assertEquals(409, added.status)
        assertEquals("MEMBERS_FROM_DIRECTORY", added.json()["code"])
        assertEquals(409, memberReset.status)
        assertEquals("PASSWORD_IN_DIRECTORY", memberReset.json()["code"])
        assertEquals(204, adminReset.status)
        assertEquals(200, client().login("admin", "another-horse-battery").status)
    }

    @Test
    fun `LDAP 계정이 된 관리자의 비밀번호를 재설정하면 다시 로컬 계정이 된다`() {
        addPerson("boss", "boss@example.com", "관리자 둘")
        saveLdap()
        val bossId = client().login("boss", LDAP_PASSWORD).json()["id"]
        admin.patch("/api/v1/admin/members/$bossId", """{"role":"ADMIN"}""")

        assertEquals(204, admin.put("/api/v1/admin/members/$bossId/password", """{"password":"$PASSWORD"}""").status)

        assertEquals("LOCAL", text("select auth_source from users where login_id = 'boss'"))
        // Works with the directory switched off.
        admin.put("/api/v1/admin/auth/ldap", ldapJson().replace("\"enabled\":true", "\"enabled\":false"))
        assertEquals(200, client().login("boss", PASSWORD).status)
    }

    @Test
    fun `비활성화한 LDAP 계정은 로그인할 수 없고 비밀번호 재설정 대상이 아니다`() {
        saveLdap()
        val id = client().login("kim", LDAP_PASSWORD).json()["id"]

        assertEquals(409, admin.put("/api/v1/admin/members/$id/password", """{"password":"$PASSWORD"}""").status)
        admin.patch("/api/v1/admin/members/$id", """{"status":"DISABLED"}""")
        assertEquals(401, client().login("kim", LDAP_PASSWORD).status)
    }

    // --- SSO ------------------------------------------------------------------------------------

    @Test
    fun `로그인 화면은 켜진 로그인 방법을 알 수 있다`() {
        val before = client().get("/api/v1/auth/methods").json()
        saveSso()
        val after = client().get("/api/v1/auth/methods").json()

        assertNull(before["sso"])
        assertEquals(false, before["ldap"])
        assertEquals(mapOf("displayName" to "사내 계정", "buttonStyle" to "DEFAULT"), after["sso"])
    }

    @Test
    fun `SSO 설정은 시크릿을 돌려주지 않고 등록할 리다이렉트 주소를 알려 준다`() {
        val saved = saveSso()

        assertEquals(200, saved.status)
        assertEquals(true, saved.json()["clientSecretSet"])
        assertFalse(saved.body.contains("oidc-secret"))
        assertEquals("http://localhost:$port/api/v1/auth/sso/callback", saved.json()["redirectUri"])
    }

    @Test
    fun `설정 문서를 읽을 수 없는 발급자로는 SSO 를 켤 수 없다`() {
        val response = admin.put("/api/v1/admin/auth/sso", ssoJson(issuer = "http://localhost:1"))

        assertEquals(422, response.status)
        assertEquals(0, count("select count(*) from sso_settings where enabled"))
    }

    @Test
    fun `SSO 로 처음 로그인하면 계정이 만들어지고 원래 가려던 곳으로 간다`() {
        saveSso()
        provider.claims = mapOf("sub" to "user-1", "preferred_username" to "Choi", "email" to "choi@example.com", "name" to "최하늘")
        val client = client()

        val callback = signInWithSso(client, next = "/issues")

        assertEquals(302, callback.status)
        assertEquals("/issues", callback.header("Location"))
        val me = client.me().json()
        assertEquals("choi", me["loginId"])
        assertEquals("최하늘", me["displayName"])
        assertEquals("SSO", text("select auth_source from users where login_id = 'choi'"))
        assertEquals("user-1", text("select external_id from users where login_id = 'choi'"))
        // The code was exchanged with the client's own credentials and the PKCE verifier.
        assertEquals("authorization_code", provider.lastTokenRequest["grant_type"])
        assertTrue(provider.lastTokenAuthorization.orEmpty().startsWith("Basic "))
        assertNull(client.cookies["FLOWBIT_SSO"])
    }

    @Test
    fun `SSO 로 다시 로그인하면 같은 계정이고 아이디 클레임이 없으면 이메일이 아이디가 된다`() {
        saveSso()
        provider.claims = mapOf("sub" to "user-2", "email" to "Han@Example.com")

        signInWithSso(client())
        val second = client()
        signInWithSso(second)

        assertEquals("han@example.com", second.me().json()["loginId"])
        assertEquals(1, count("select count(*) from users where auth_source = 'SSO'"))
    }

    @Test
    fun `state 가 다르거나 nonce 가 다른 토큰이면 로그인되지 않는다`() {
        saveSso()
        provider.claims = mapOf("sub" to "user-3", "preferred_username" to "song")

        val wrongState = client()
        wrongState.get("/api/v1/auth/sso/start")
        val forged = wrongState.get("/api/v1/auth/sso/callback?code=abc&state=not-the-state")
        val wrongNonce = client()
        val replayed = signInWithSso(wrongNonce, nonce = "someone-elses-nonce")

        assertEquals("/login?error=sso_failed", forged.header("Location"))
        assertEquals("/login?error=sso_failed", replayed.header("Location"))
        assertNull(wrongState.sessionCookie)
        assertNull(wrongNonce.sessionCookie)
        assertEquals(1, count("select count(*) from users"))
    }

    @Test
    fun `다른 클라이언트에게 발급된 토큰은 받지 않는다`() {
        saveSso()
        provider.claims = mapOf("sub" to "user-4", "preferred_username" to "yoon")
        provider.audience = "another-app"

        assertEquals("/login?error=sso_failed", signInWithSso(client()).header("Location"))
    }

    @Test
    fun `같은 아이디의 기존 계정은 SSO 로 로그인하면 SSO 계정으로 바뀐다`() {
        val id = admin.post("/api/v1/admin/members", """{"loginId":"kang1","password":"$PASSWORD"}""").json()["id"]
        saveSso()
        provider.claims = mapOf("sub" to "user-5", "preferred_username" to "kang1")
        val client = client()

        val callback = signInWithSso(client)

        assertEquals("/", callback.header("Location"))
        assertEquals(id, client.me().json()["id"])
        assertEquals("SSO", text("select auth_source from users where login_id = 'kang1'"))
        assertEquals("user-5", text("select external_id from users where login_id = 'kang1'"))
        assertEquals(401, client().login("kang1", PASSWORD).status)
        // Someone else of the same provider cannot take the account by claiming its id.
        provider.claims = mapOf("sub" to "user-other", "preferred_username" to "kang1")
        assertEquals("/login?error=sso_conflict", signInWithSso(client()).header("Location"))
    }

    @Test
    fun `다른 사람의 이메일을 아이디로 내세운 SSO 사용자는 로그인되지 않는다`() {
        admin.patch("/api/v1/me/notifications", """{"email":"owner@example.com"}""")
        saveSso()
        provider.claims = mapOf("sub" to "user-8", "preferred_username" to "owner@example.com")

        assertEquals("/login?error=sso_conflict", signInWithSso(client()).header("Location"))
    }

    @Test
    fun `자동 가입을 끄면 계정이 없는 SSO 사용자는 로그인되지 않는다`() {
        admin.put("/api/v1/admin/auth/sso", ssoJson(autoProvision = false))
        provider.claims = mapOf("sub" to "user-6", "preferred_username" to "moon")

        assertEquals("/login?error=sso_not_registered", signInWithSso(client()).header("Location"))
        assertEquals(1, count("select count(*) from users"))
    }

    @Test
    fun `SSO 가 꺼져 있으면 시작할 수 없고 바깥 주소로는 돌아가지 않는다`() {
        assertEquals("/login?error=sso_failed", client().get("/api/v1/auth/sso/start").header("Location"))

        saveSso()
        provider.claims = mapOf("sub" to "user-7", "preferred_username" to "bae")
        assertEquals("/", signInWithSso(client(), next = "//evil.example.com").header("Location"))
    }

    @Test
    fun `표준이 아닌 SSO 도 주소와 교환 방식을 직접 정해 쓸 수 있다`() {
        val saved =
            admin.put(
                "/api/v1/admin/auth/sso",
                """
                {"enabled":true,"protocol":"OAUTH2","displayName":"NAVER WORKS","buttonStyle":"NAVER_WORKS",
                 "authorizeUrl":"${provider.issuer}/custom/authorize","tokenUrl":"${provider.issuer}/custom/token",
                 "clientId":"flowbit","clientSecret":"api-key-1","tokenAuth":"BEARER","tokenFormat":"JSON",
                 "tokenBody":"{\"clientId\":\"{{clientId}}\",\"code\":\"{{code}}\"}",
                 "subjectField":"email","loginIdClaim":"email","emailField":"email","nameField":"userName","scopes":"","autoProvision":true}
                """.trimIndent(),
            )
        assertEquals(200, saved.status)
        provider.customProfile = """{"clientId":"flowbit","email":"Hong@Motherk.co.kr","userName":"홍길동","administrator":false}"""
        val client = client()

        val start = client.get("/api/v1/auth/sso/start")
        val location = assertNotNull(start.header("Location"))
        val query =
            URI
                .create(location)
                .rawQuery
                .split("&")
                .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        val callback = client.get("/api/v1/auth/sso/callback?code=one-time-code&state=${query.getValue("state")}")

        assertTrue(location.startsWith(provider.issuer + "/custom/authorize?"))
        assertEquals("flowbit", query["client_id"])
        // Nothing an in-house provider might not know.
        assertNull(query["nonce"])
        assertNull(query["code_challenge"])
        assertEquals("/", callback.header("Location"))
        assertEquals("Bearer api-key-1", provider.lastTokenAuthorization)
        assertEquals("""{"clientId":"flowbit","code":"one-time-code"}""", provider.lastTokenBody)
        assertEquals("hong@motherk.co.kr", client.me().json()["loginId"])
        assertEquals("홍길동", client.me().json()["displayName"])
        assertEquals(mapOf("displayName" to "NAVER WORKS", "buttonStyle" to "NAVER_WORKS"), client().get("/api/v1/auth/methods").json()["sso"])
        // The provider turning someone away comes back as its own message.
        val refused = client()
        val again = URI.create(assertNotNull(refused.get("/api/v1/auth/sso/start").header("Location"))).rawQuery
        val state = again.split("&").first { it.startsWith("state=") }.substringAfter("=")
        assertEquals("/login?error=sso_denied", refused.get("/api/v1/auth/sso/callback?error=access_denied&state=$state").header("Location"))
    }

    @Test
    fun `직접 설정한 SSO 는 주소가 빠지면 켤 수 없다`() {
        val response = admin.put("/api/v1/admin/auth/sso", """{"enabled":true,"protocol":"OAUTH2","clientId":"a","clientSecret":"b","loginIdClaim":"email"}""")

        assertEquals(422, response.status)
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Walks the browser's side of the flow: start, "sign in" at the provider, come back with the code. */
    private fun signInWithSso(
        client: ApiClient,
        next: String? = null,
        nonce: String? = null,
    ) = client.get("/api/v1/auth/sso/start" + (next?.let { "?next=$it" } ?: "")).let { start ->
        assertEquals(302, start.status)
        val location = URI.create(assertNotNull(start.header("Location")))
        assertTrue(location.toString().startsWith(provider.issuer + "/authorize?"))
        val query = location.rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        assertEquals("http://localhost:$port/api/v1/auth/sso/callback", query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        provider.nonce = nonce ?: query.getValue("nonce")
        val callback = client.get("/api/v1/auth/sso/callback?code=test-code&state=${query.getValue("state")}")
        // The verifier sent to the token endpoint is the one the challenge was made from.
        provider.lastTokenRequest["code_verifier"]?.let {
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
            assertEquals(query["code_challenge"], challenge)
        }
        callback
    }

    private fun addPerson(
        uid: String,
        mail: String,
        name: String,
    ) {
        directory.add(
            "dn: uid=$uid,ou=people,dc=example,dc=com",
            "objectClass: top",
            "objectClass: person",
            "objectClass: organizationalPerson",
            "objectClass: inetOrgPerson",
            "uid: $uid",
            "cn: $name",
            "sn: $name",
            "mail: $mail",
            "userPassword: $LDAP_PASSWORD",
        )
    }

    private fun saveLdap() = admin.put("/api/v1/admin/auth/ldap", ldapJson())

    private fun ldapJson(
        url: String = "ldap://localhost:${directory.listenPort}",
        bindPassword: String? = "search-secret",
        userFilter: String = "(|(uid={0})(mail={0}))",
        autoProvision: Boolean = true,
        testLoginId: String? = null,
    ): String =
        """
        {"enabled":true,"url":"$url","startTls":false,"bindDn":"cn=search,dc=example,dc=com",
         "bindPassword":${bindPassword?.let { "\"$it\"" }},"baseDn":"ou=people,dc=example,dc=com","userFilter":"$userFilter",
         "loginIdAttribute":"uid","emailAttribute":"mail","displayNameAttribute":"cn","autoProvision":$autoProvision,
         "testLoginId":${testLoginId?.let { "\"$it\"" }}}
        """.trimIndent()

    private fun saveSso() = admin.put("/api/v1/admin/auth/sso", ssoJson())

    private fun ssoJson(
        issuer: String = provider.issuer,
        autoProvision: Boolean = true,
    ): String =
        """
        {"enabled":true,"displayName":"사내 계정","issuer":"$issuer","clientId":"flowbit","clientSecret":"oidc-secret",
         "scopes":"openid profile email","loginIdClaim":"preferred_username","autoProvision":$autoProvision}
        """.trimIndent()
}
