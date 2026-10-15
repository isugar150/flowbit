package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    // --- helpers --------------------------------------------------------------------------------

    private fun connectionJson(
        name: String = "Gitea",
        provider: String = "GITEA",
        allProjects: Boolean = true,
        projectIds: List<String> = emptyList(),
        autoInProgress: Boolean = true,
        autoDone: Boolean = true,
    ): String =
        """{"name":"$name","provider":"$provider","baseUrl":"https://git.example.com/","allProjects":$allProjects,""" +
            """"projectIds":[${projectIds.joinToString { "\"$it\"" }}],"autoInProgress":$autoInProgress,"autoDone":$autoDone}"""

    @Suppress("UNCHECKED_CAST")
    private fun fieldErrors(problem: Map<String, Any?>): Map<String, String> =
        (problem["errors"] as List<Map<String, Any?>>).associate { it["field"] as String to it["code"] as String }
}
