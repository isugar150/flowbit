package com.flowbit

import com.flowbit.common.crypto.Secret
import com.flowbit.common.web.FlowbitProperties
import com.flowbit.common.web.PublicUrl
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationMessage
import com.flowbit.notification.NotificationText
import com.flowbit.notification.Webhook
import com.flowbit.notification.WebhookRecipient
import com.flowbit.notification.WebhookSender
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinkNotificationTest {
    private val texts = NotificationText(PublicUrl(FlowbitProperties("https://issues.motherk.link")))
    private val sender = WebhookSender(JsonMapper.builder().build(), texts, Clock.systemUTC())
    private val notification =
        IssueNotification("MK-9", 9, "피드백 확인", null, null, "김민수", emptyList(), emptyList())

    @Test
    fun `API 알림은 전용 앱 링크와 원래 웹 주소를 함께 제공한다`() {
        val content = sender.contentOf(notification)
        val message = content.message
        assertEquals("web+flowbit://issues.motherk.link/issue/MK-9", message.appUrl)
        assertTrue(!message.text.contains("web+flowbit"))
        assertTrue(message.text.endsWith("https://issues.motherk.link/issue/MK-9"))
        assertEquals(message.appUrl, content.values["issue.appUrl"])
        val payload = sender.payload(content, UUID.randomUUID(), WebhookRecipient(UUID.randomUUID(), "kim", "김민수", null))
        assertEquals(message.appUrl, payload["appUrl"])
        assertEquals(message.appUrl, (payload["issue"] as Map<*, *>)["appUrl"])
    }

    @Test
    fun `앱 링크 변수는 JSON 본문과 GET 주소에 각각 알맞게 인코딩된다`() {
        val recipient = WebhookRecipient(UUID.randomUUID(), "kim", "김민수", null)
        val webhook =
            Webhook(
                id = UUID.randomUUID(),
                name = "메신저",
                enabled = true,
                url = "https://example.com/send?app={{appUrl}}",
                method = "GET",
                contentType = "application/json",
                headersStored = Secret("{}"),
                bodyTemplate = """{"app":"{{appUrl}}","issueApp":"{{issue.appUrl}}"}""",
                signingSecretStored = Secret(""),
                eventTypes = "",
                lastStatus = null,
                lastError = null,
                lastAttemptAt = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        val content = sender.contentOf(notification)
        val deliveryId = UUID.randomUUID()
        val body = JsonMapper.builder().build().readTree(sender.body(webhook, content, deliveryId, recipient))
        assertEquals(content.message.appUrl, body.get("app").asString())
        assertEquals(content.message.appUrl, body.get("issueApp").asString())
        assertTrue(sender.url(webhook, content, deliveryId, recipient).contains("web%2Bflowbit%3A%2F%2F"))
    }

    @Test
    fun `공개 주소가 없거나 티켓 주소가 아니면 앱 링크를 만들지 않는다`() {
        for (url in listOf(
            "/issue/MK-9",
            "https://issues.motherk.link/admin",
            "javascript:alert(1)",
            "https://user:pass@issues.motherk.link/issue/MK-9",
            "https://issues.motherk.link/issue/MK-9?next=other",
            "https://issues.motherk.link/issue/MK-9#fragment",
            "https://issues.motherk.link/issue/MK-0",
        )) {
            assertNull(NotificationMessage("제목", emptyList(), "내용", "/issue/MK-9", url).appUrl)
        }
    }
}
