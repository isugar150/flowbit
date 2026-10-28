package com.flowbit

import com.flowbit.auth.domain.LdapSettingsRepository
import com.flowbit.common.crypto.SecretCipher
import com.flowbit.common.crypto.SecretMigration
import com.flowbit.notification.NotificationSettingsRepository
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

@TestPropertySource(properties = ["flowbit.secrets.key=test-passphrase-for-secrets"])
class SecretsIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var migration: SecretMigration

    @Autowired
    private lateinit var cipher: SecretCipher

    @Autowired
    private lateinit var ldapRepository: LdapSettingsRepository

    @Autowired
    private lateinit var notificationRepository: NotificationSettingsRepository

    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    @Test
    fun `웹훅의 비밀 값과 헤더는 DB 에 암호화되어 저장되고 API 에서는 그대로 쓰인다`() {
        assertTrue(cipher.enabled)

        val created =
            admin.post(
                "/api/v1/admin/notifications/webhooks",
                """{"name":"사내 메신저","enabled":true,"url":"http://example.com/hook","method":"POST","contentType":"application/json",
                    "headers":{"Authorization":"Bearer abc"},"bodyTemplate":"","signingSecret":"hook-secret","eventTypes":[]}""",
            )

        assertEquals(201, created.status)
        val stored = text("select signing_secret from webhooks")
        assertTrue(stored.startsWith("enc:v1:"))
        assertFalse(stored.contains("hook-secret"))
        assertTrue(text("select headers from webhooks").startsWith("enc:v1:"))
        val listed = admin.get("/api/v1/admin/notifications/webhooks").jsonList().single()
        assertEquals(true, listed["signingSecretSet"])
        assertEquals("Bearer abc", (listed["headers"] as Map<*, *>)["Authorization"])
    }

    @Test
    fun `같은 비밀 값도 저장할 때마다 다르게 암호화된다`() {
        val first = cipher.encrypt("same-secret")
        val second = cipher.encrypt("same-secret")

        assertNotEquals(first, second)
        assertEquals("same-secret", cipher.decrypt(first))
        assertEquals("same-secret", cipher.decrypt(second))
        assertEquals("", cipher.encrypt(""))
    }

    @Test
    fun `예전에 평문으로 저장된 비밀 값은 이전 작업으로 암호화되고 계속 읽힌다`() {
        execute("update ldap_settings set bind_password = 'ldap-old'")
        execute("update notification_settings set smtp_password = 'smtp-old', fcm_service_account = ''")

        // Still readable before the migration: a value without the prefix is a plain-text secret.
        assertEquals("ldap-old", runBlocking { ldapRepository.findById(1) }?.bindPassword)

        val encrypted = runBlocking { migration.migrate() }

        assertEquals(2, encrypted)
        assertTrue(text("select bind_password from ldap_settings").startsWith("enc:v1:"))
        assertTrue(text("select smtp_password from notification_settings").startsWith("enc:v1:"))
        assertEquals("", text("select fcm_service_account from notification_settings"))
        assertEquals("ldap-old", runBlocking { ldapRepository.findById(1) }?.bindPassword)
        assertEquals("smtp-old", runBlocking { notificationRepository.findById(1) }?.smtpPassword)
        assertEquals(0, runBlocking { migration.migrate() })
    }
}
