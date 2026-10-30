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

    private fun change(
        vararg events: IssueNotification.Event,
        commits: List<IssueNotification.Event> = emptyList(),
    ) = IssueNotification(
        issueKey = "FB-12",
        issueNumber = 12,
        issueTitle = "로그인 화면 깨짐",
        projectId = null,
        actorId = null,
        actorName = "김지민",
        events = events.toList(),
        recipientIds = emptyList(),
        commits = commits,
    )

    @Test
    fun `상태는 범주가 아니라 이름으로 말해서 같은 범주 안의 이동도 보인다`() {
        val status =
            IssueNotification.Event(
                "STATUS_CHANGED",
                mapOf("from" to "IN_PROGRESS", "to" to "IN_PROGRESS", "fromName" to "진행 중", "toName" to "리뷰 준비됨"),
            )
        assertEquals(listOf("상태: 진행 중 → 리뷰 준비됨"), text.of(change(status)).lines)
        // An entry from before the names are kept says the category.
        val old = IssueNotification.Event("STATUS_CHANGED", mapOf("from" to "BACKLOG", "to" to "IN_PROGRESS"))
        assertEquals(listOf("상태: 백로그 → 진행 중"), text.of(change(old)).lines)
    }

    @Test
    fun `상태를 옮긴 커밋은 해시와 첫 줄과 남은 개수를 말한다`() {
        val status =
            IssueNotification.Event(
                "STATUS_CHANGED",
                mapOf("from" to "IN_PROGRESS", "to" to "IN_PROGRESS", "fromName" to "진행 중", "toName" to "리뷰 준비됨"),
            )
        val pushed =
            IssueNotification.Event(
                "GIT_COMMITS_PUSHED",
                mapOf(
                    "branch" to "main",
                    "count" to 3,
                    "commits" to listOf(mapOf("sha" to "a1b2c3d4e5", "message" to "Fixes FB-12 로그인 오류"), mapOf("sha" to "f6e5d4c3b2", "message" to "리뷰 반영")),
                ),
            )
        assertEquals(
            listOf("상태: 진행 중 → 리뷰 준비됨", "커밋 3개: main", "a1b2c3d4e5 Fixes FB-12 로그인 오류", "f6e5d4c3b2 리뷰 반영", "외 1개"),
            text.of(change(status, commits = listOf(pushed))).lines,
        )
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
            """
            [FB-12] 로그인 화면 깨짐 — 김지민 님이 티켓을 수정했습니다
            상태: 백로그 → 진행 중
            https://flowbit.example.com/issue/FB-12
            """.trimIndent(),
            message("https://flowbit.example.com/issue/FB-12").text,
        )
    }

    @Test
    fun `설치 주소를 모르면 열 수 없는 경로는 붙이지 않는다`() {
        assertEquals("[FB-12] 로그인 화면 깨짐 — 김지민 님이 티켓을 수정했습니다\n상태: 백로그 → 진행 중", message("/issue/FB-12").text)
    }
}
