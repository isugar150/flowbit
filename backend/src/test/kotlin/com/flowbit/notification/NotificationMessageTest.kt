package com.flowbit.notification

import com.flowbit.common.web.FlowbitProperties
import com.flowbit.common.web.PublicUrl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotificationMessageTest {
    private val text = NotificationText(PublicUrl(FlowbitProperties()))

    private fun comment(detail: Map<String, Any?>) =
        IssueNotification(
            issueKey = "FB-12",
            issueNumber = 12,
            issueTitle = "로그인 화면 깨짐",
            projectId = null,
            actorId = null,
            actorName = "김지민",
            events = listOf(IssueNotification.Event("COMMENT_ADDED", detail)),
            recipientIds = emptyList(),
        )

    @Test
    fun `답글이면 머리말이 댓글에 답글을 남겼다고 말한다`() {
        assertEquals("김지민 님이 댓글을 남겼습니다", text.of(comment(mapOf("text" to "확인했습니다"))).headline)
        assertEquals("김지민 님이 댓글에 답글을 남겼습니다", text.of(comment(mapOf("text" to "확인했습니다", "reply" to true))).headline)
    }

    private fun message(url: String) =
        NotificationMessage(
            title = "[FB-12] 로그인 화면 깨짐",
            lines = listOf("상태: 백로그 → 진행 중"),
            headline = "김지민 님이 티켓을 수정했습니다",
            path = "/issue/FB-12",
            url = url,
        )

    @Test
    fun `한 덩어리 글의 마지막 줄은 티켓을 여는 주소다`() {
        assertEquals(
            "[FB-12] 로그인 화면 깨짐 — 김지민 님이 티켓을 수정했습니다\n상태: 백로그 → 진행 중\nhttps://flowbit.example.com/issue/FB-12",
            message("https://flowbit.example.com/issue/FB-12").text,
        )
    }

    @Test
    fun `설치 주소를 모르면 열 수 없는 경로는 붙이지 않는다`() {
        assertEquals("[FB-12] 로그인 화면 깨짐 — 김지민 님이 티켓을 수정했습니다\n상태: 백로그 → 진행 중", message("/issue/FB-12").text)
    }
}
