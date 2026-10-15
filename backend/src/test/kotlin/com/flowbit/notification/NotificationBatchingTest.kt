package com.flowbit.notification

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationBatchingTest {
    private val actor = UUID.randomUUID()
    private val kim = UUID.randomUUID()
    private val lee = UUID.randomUUID()

    private fun notification(
        vararg events: IssueNotification.Event,
        recipients: List<UUID> = listOf(kim),
        number: Int = 1,
        by: UUID = actor,
        title: String = "배포 준비",
    ) = IssueNotification("FB-$number", number, title, null, by, "관리자", events.toList(), recipients)

    private fun event(
        type: String,
        vararg detail: Pair<String, Any?>,
    ) = IssueNotification.Event(type, mapOf(*detail))

    @Test
    fun `같은 항목을 여러 번 바꾸면 처음 값에서 마지막 값으로 한 줄이 되고 되돌리면 빠진다`() {
        val merged =
            listOf(
                notification(event("CREATED")),
                notification(event("STATUS_CHANGED", "from" to "BACKLOG", "to" to "READY")),
                notification(event("STATUS_CHANGED", "from" to "READY", "to" to "DONE"), event("DESCRIPTION_CHANGED")),
                notification(event("PRIORITY_CHANGED", "from" to "NONE", "to" to "HIGH"), event("DESCRIPTION_CHANGED")),
                notification(event("PRIORITY_CHANGED", "from" to "HIGH", "to" to "NONE"), recipients = listOf(lee), title = "배포 준비 (수정)"),
            ).reduce(NotificationMerge::merge)

        assertEquals(
            listOf(
                event("CREATED"),
                event("STATUS_CHANGED", "from" to "BACKLOG", "to" to "DONE"),
                event("DESCRIPTION_CHANGED"),
            ),
            merged.events,
        )
        assertEquals(listOf(kim, lee), merged.recipientIds)
        assertEquals("배포 준비 (수정)", merged.issueTitle)
    }

    @Test
    fun `라벨과 담당자는 최종으로 더하고 뺀 것만 남고 삭제 뒤 복원은 없던 일이 된다`() {
        val merged =
            listOf(
                notification(event("LABELS_CHANGED", "added" to listOf("버그"), "removed" to emptyList<String>())),
                notification(event("LABELS_CHANGED", "added" to listOf("중요"), "removed" to listOf("버그", "기능"))),
                notification(event("ASSIGNEE_CHANGED", "added" to listOf("김지민"), "removed" to emptyList<String>())),
                notification(event("ASSIGNEE_CHANGED", "added" to emptyList<String>(), "removed" to listOf("김지민"))),
                notification(event("DELETED")),
                notification(event("RESTORED")),
                notification(event("MENTIONED", "mentions" to listOf("김지민"))),
                notification(event("MENTIONED", "mentions" to listOf("이수진", "김지민"))),
            ).reduce(NotificationMerge::merge)

        assertEquals(
            listOf(
                event("LABELS_CHANGED", "added" to listOf("중요"), "removed" to listOf("기능")),
                event("MENTIONED", "mentions" to listOf("김지민", "이수진")),
            ),
            merged.events,
        )
    }

    @Test
    fun `한 사람이 한 티켓에 한 변경은 조용해진 뒤 한 번에 가고 다른 사람과 다른 티켓은 따로 간다`() {
        val sent = CopyOnWriteArrayList<IssueNotification>()
        val dispatcher = NotificationDispatcher(listOf(Recorder(sent)), Duration.ofMillis(300), Duration.ofMinutes(10))

        dispatcher.dispatch(notification(event("CREATED")))
        Thread.sleep(100)
        dispatcher.dispatch(notification(event("STATUS_CHANGED", "from" to "BACKLOG", "to" to "DONE")))
        dispatcher.dispatch(notification(event("STATUS_CHANGED", "from" to "BACKLOG", "to" to "DONE"), by = kim, recipients = listOf(actor)))
        dispatcher.dispatch(notification(event("CREATED"), number = 2))
        Thread.sleep(150)
        assertTrue(sent.isEmpty(), "nothing goes out while changes keep coming")

        await { sent.size == 3 }
        val first = sent.single { it.issueNumber == 1 && it.actorId == actor }
        assertEquals(listOf("CREATED", "STATUS_CHANGED"), first.events.map { it.type })
        dispatcher.stop()
    }

    @Test
    fun `닫으면 바로 가고 계속 고쳐도 최대 시간이 지나면 간다`() {
        val sent = CopyOnWriteArrayList<IssueNotification>()
        val dispatcher = NotificationDispatcher(listOf(Recorder(sent)), Duration.ofMinutes(1), Duration.ofMillis(500))

        dispatcher.dispatch(notification(event("CREATED")))
        dispatcher.flush(1, actor)
        // Someone else's held changes, and an issue with nothing held, are not touched.
        dispatcher.flush(1, kim)
        dispatcher.flush(9, actor)
        await { sent.size == 1 }

        dispatcher.dispatch(notification(event("TITLE_CHANGED", "from" to "가", "to" to "나")))
        repeat(6) {
            Thread.sleep(100)
            dispatcher.dispatch(notification(event("DESCRIPTION_CHANGED")))
        }
        await { sent.size == 2 }
        assertEquals(listOf("TITLE_CHANGED", "DESCRIPTION_CHANGED"), sent[1].events.map { it.type })
        dispatcher.stop()
    }

    @Test
    fun `서버가 꺼질 때 모아 두던 알림을 보낸다`() {
        val sent = CopyOnWriteArrayList<IssueNotification>()
        val dispatcher = NotificationDispatcher(listOf(Recorder(sent)), Duration.ofMinutes(1), Duration.ofMinutes(10))

        dispatcher.dispatch(notification(event("CREATED")))
        dispatcher.stop()

        assertEquals(1, sent.size)
    }

    private class Recorder(
        private val sent: MutableList<IssueNotification>,
    ) : NotificationChannel {
        override suspend fun send(notification: IssueNotification) {
            sent += notification
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting" }
            Thread.sleep(20)
        }
    }
}
