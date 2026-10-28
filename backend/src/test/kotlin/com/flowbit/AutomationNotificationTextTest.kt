package com.flowbit

import com.flowbit.common.web.FlowbitProperties
import com.flowbit.common.web.PublicUrl
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AutomationNotificationTextTest {
    private val texts = NotificationText(PublicUrl(FlowbitProperties("https://flowbit.example.com")))

    @Test
    fun `자동화는 설정 메시지만 표시하고 실행 안내와 규칙 이름을 붙이지 않는다`() {
        val message = texts.of(notification("FB-1 결제 기능의 리뷰가 요청되었습니다.\n진행 중 → 리뷰 준비됨"))
        assertEquals("FB-1 결제 기능의 리뷰가 요청되었습니다.", message.headline)
        assertEquals(listOf("진행 중 → 리뷰 준비됨"), message.lines)
        assertFalse(message.text.contains("자동화"))
        assertFalse(message.text.contains("리뷰 준비시 리뷰어에게 알림"))
        assertEquals("https://flowbit.example.com/issue/FB-1", message.url)
    }

    @Test
    fun `빈 메시지에도 실행 안내 대신 기본 상태 변경 문구를 표시한다`() {
        val message = texts.of(notification(""))
        assertEquals("티켓 상태가 변경되었습니다.", message.headline)
        assertEquals(emptyList(), message.lines)
    }

    private fun notification(message: String) =
        IssueNotification(
            issueKey = "FB-1",
            issueNumber = 1,
            issueTitle = "결제 기능",
            projectId = null,
            actorId = null,
            actorName = "김민수",
            events = listOf(IssueNotification.Event("AUTOMATION_TRIGGERED", mapOf("ruleName" to "리뷰 준비시 리뷰어에게 알림", "message" to message))),
            recipientIds = emptyList(),
        )
}
