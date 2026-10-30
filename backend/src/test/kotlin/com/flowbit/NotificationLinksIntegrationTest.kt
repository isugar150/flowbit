package com.flowbit

import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationLinks
import com.flowbit.notification.NotificationMessage
import com.flowbit.notification.WebhookRecipient
import com.flowbit.notification.WebhookSender
import com.flowbit.support.IntegrationTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestPropertySource(properties = ["flowbit.public-url=https://issues.motherk.link"])
class NotificationLinksIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var links: NotificationLinks

    @Autowired
    private lateinit var sender: WebhookSender

    private fun message(path: String = "/issue/MK-62") = NotificationMessage("제목", emptyList(), "변경 내용", path, "https://issues.motherk.link$path")

    @Test
    fun `고유 HTTPS 링크는 해시만 저장하며 익명으로 경로만 반복 조회한다`() =
        runBlocking {
            val first = links.prepare(message())
            val second = links.prepare(message())
            assertNotEquals(first.url, second.url)
            val token = first.url.substringAfterLast('/')
            assertEquals(43, token.length)
            assertEquals(0, count("select count(*) from notification_links where token_hash = '$token'"))
            assertEquals(2, count("select count(*) from notification_links"))
            repeat(2) {
                val result = client().get("/api/v1/public/notification-links/$token")
                assertEquals(200, result.status)
                assertEquals("no-store", result.header("Cache-Control"))
                assertEquals("no-referrer", result.header("Referrer-Policy"))
                assertEquals(mapOf("path" to "/issue/MK-62"), result.json())
            }
            assertEquals(401, client().get("/api/v1/issues/62").status)
            assertEquals("https://issues.motherk.link/issue/MK-62", first.webUrl)
            assertEquals("web+flowbit://issues.motherk.link/issue/MK-62", first.appUrl)
            assertTrue(first.text.endsWith(first.url))
            assertTrue(!first.text.contains("web+flowbit"))
        }

    @Test
    fun `틀린 토큰과 만료 링크는 거부하고 새 발급은 만료 행을 정리한다`() =
        runBlocking {
            val link = links.prepare(message())
            val token = link.url.substringAfterLast('/')
            assertEquals(404, client().get("/api/v1/public/notification-links/wrong").status)
            assertEquals(404, client().get("/api/v1/public/notification-links/${"a".repeat(43)}").status)
            clock.advance(Duration.ofDays(91))
            assertEquals(404, client().get("/api/v1/public/notification-links/$token").status)
            links.prepare(message())
            assertEquals(1, count("select count(*) from notification_links"))
        }

    @Test
    fun `외부 주소와 임의 경로는 저장하지 않는다`() =
        runBlocking {
            for (path in listOf("//evil.example", "/\\evil.example", "/admin", "/issue/MK-62?next=other", "/n/loop")) {
                val original = message(path)
                assertEquals(original, links.prepare(original))
            }
            assertEquals(0, count("select count(*) from notification_links"))
        }

    @Test
    fun `웹훅 표준 JSON과 템플릿 목적지는 같은 중간 링크를 사용한다`() =
        runBlocking {
            val content = sender.contentOf(IssueNotification("MK-62", 62, "제목", null, null, "김민수", emptyList(), emptyList()))
            val linked = links.prepare(content)
            val payload = sender.payload(linked, UUID.randomUUID(), WebhookRecipient(UUID.randomUUID(), "kim", "김민수", null))
            assertEquals(linked.message.url, (payload["issue"] as Map<*, *>)["url"])
            assertEquals(linked.message.url, linked.values["issue.url"])
            assertEquals("https://issues.motherk.link/issue/MK-62", payload["webUrl"])
            assertTrue(payload["text"].toString().endsWith(linked.message.url))
        }
}
