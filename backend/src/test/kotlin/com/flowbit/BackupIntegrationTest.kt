package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.ApiResponse
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val FILE_PASSWORD = "backup-file-password"
private const val ALL = """["WORKSPACE","AUTH","NOTIFICATIONS","MEMBERS"]"""

class BackupIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD, "마더케이", "MK") }
        admin.post("/api/v1/admin/members", """{"loginId":"kimjm","password":"$PASSWORD"}""")
        admin.put(
            "/api/v1/admin/notifications/smtp",
            """{"enabled":false,"host":"smtp.example.com","port":465,"security":"SSL","username":"mailer","password":"smtp-secret","fromAddress":"flowbit@example.com","fromName":"Flowbit"}""",
        )
        admin.post(
            "/api/v1/admin/notifications/webhooks",
            """{"name":"사내 메신저","enabled":true,"url":"https://hooks.example.com/send","method":"POST","contentType":"application/json","headers":{"Authorization":"Bearer abc"},"bodyTemplate":"","signingSecret":"hook-secret","eventTypes":["STATUS_CHANGED"]}""",
        )
        admin.put(
            "/api/v1/admin/auth/ldap",
            """{"enabled":false,"url":"ldaps://ldap.example.com:636","startTls":false,"bindDn":"cn=reader,dc=example,dc=com","bindPassword":"bind-secret","baseDn":"ou=users,dc=example,dc=com","userFilter":"(uid={0})","loginIdAttribute":"uid","emailAttribute":"mail","displayNameAttribute":"cn","autoProvision":true}""",
        )
    }

    @Test
    fun `관리자가 아니면 백업도 복원도 할 수 없다`() {
        val member = client().also { it.login("kimjm", PASSWORD) }

        assertEquals(403, member.post("/api/v1/admin/backup/export", """{"sections":$ALL}""").status)
        assertEquals(403, member.post("/api/v1/admin/backup/restore", """{"file":{},"sections":$ALL}""").status)
    }

    @Test
    fun `백업한 파일로 설정과 구성원을 되돌린다`() {
        val file = export()
        assertEquals(200, file.status)
        assertEquals("flowbit-backup", file.json()["format"])
        // The file is the one place the stored secrets leave the server.
        assertTrue(file.body.contains("smtp-secret") && file.body.contains("hook-secret") && file.body.contains("bind-secret"))

        execute("update instance_settings set organization_name = '다른 이름', workspace_key = 'ZZ'")
        execute("update ldap_settings set url = '', bind_password = ''")
        execute("update notification_settings set smtp_host = '', smtp_password = ''")
        execute("delete from webhooks")
        execute("delete from users where login_id = 'kimjm'")

        val restored = restore(file.body)

        assertEquals(200, restored.status)
        assertEquals(1, restored.json()["membersAdded"])
        // The administrator who restores is already there and is left as they are.
        assertEquals(1, restored.json()["membersSkipped"])
        assertEquals(1, restored.json()["webhooks"])
        assertEquals("마더케이", text("select organization_name from instance_settings"))
        assertEquals("MK", text("select workspace_key from instance_settings"))
        assertEquals("bind-secret", text("select bind_password from ldap_settings"))
        assertEquals("smtp-secret", text("select smtp_password from notification_settings"))
        assertEquals("hook-secret", text("select signing_secret from webhooks where url = 'https://hooks.example.com/send'"))
        // The restored account signs in with the password it had.
        assertEquals(200, client().login("kimjm", PASSWORD).status)
    }

    @Test
    fun `고른 항목만 파일에 담기고 고른 항목만 복원된다`() {
        val file = export("""["NOTIFICATIONS"]""")
        assertFalse(file.body.contains("bind-secret"))
        execute("delete from webhooks")
        execute("update instance_settings set organization_name = '다른 이름'")

        assertEquals(422, restore(file.body, """["WORKSPACE"]""").status)
        assertEquals(200, restore(file.body, """["NOTIFICATIONS"]""").status)

        assertEquals(1, count("select count(*) from webhooks"))
        assertEquals("다른 이름", text("select organization_name from instance_settings"))
    }

    @Test
    fun `암호를 정하면 파일에 비밀 값이 보이지 않고 그 암호로만 복원된다`() {
        val file = export(password = FILE_PASSWORD)

        assertEquals(200, file.status)
        assertFalse(file.body.contains("smtp-secret") || file.body.contains("kimjm"))
        execute("delete from webhooks")

        assertEquals("REQUIRED", fieldCode(restore(file.body)))
        assertEquals("WRONG_PASSWORD", fieldCode(restore(file.body, password = "another-password")))
        assertEquals(0, count("select count(*) from webhooks"))
        assertEquals(200, restore(file.body, password = FILE_PASSWORD).status)
        assertEquals(1, count("select count(*) from webhooks"))
    }

    @Test
    fun `값 하나가 올바르지 않으면 아무것도 바뀌지 않는다`() {
        val broken = export().body.replace("https://hooks.example.com/send", "ftp://hooks.example.com/send")
        execute("update instance_settings set organization_name = '다른 이름'")
        execute("delete from users where login_id = 'kimjm'")

        val response = restore(broken)

        assertEquals(422, response.status)
        assertEquals("다른 이름", text("select organization_name from instance_settings"))
        assertEquals(1, count("select count(*) from webhooks where url = 'https://hooks.example.com/send'"))
        assertEquals(0, count("select count(*) from users where login_id = 'kimjm'"))
    }

    @Test
    fun `백업 파일이 아닌 것과 짧은 암호는 거부한다`() {
        assertEquals(422, restore("""{"format":"something-else","version":1,"createdAt":"2026-10-13T00:00:00Z","sections":[]}""").status)
        assertEquals(422, restore("""{"hello":"world"}""").status)
        assertEquals(422, export(password = "short").status)
        assertEquals(422, export("[]").status)
    }

    private fun export(
        sections: String = ALL,
        password: String? = null,
    ): ApiResponse = admin.post("/api/v1/admin/backup/export", """{"sections":$sections,"password":${quoted(password)}}""")

    private fun restore(
        file: String,
        sections: String = ALL,
        password: String? = null,
    ): ApiResponse = admin.post("/api/v1/admin/backup/restore", """{"file":$file,"sections":$sections,"password":${quoted(password)}}""")

    private fun quoted(value: String?) = value?.let { "\"$it\"" }

    @Suppress("UNCHECKED_CAST")
    private fun fieldCode(response: ApiResponse): Any? = (response.json()["errors"] as List<Map<String, Any?>>).first()["code"]
}
