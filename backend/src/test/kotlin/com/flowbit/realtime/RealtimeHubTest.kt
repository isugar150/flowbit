package com.flowbit.realtime

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealtimeHubTest {
    private val hub = RealtimeHub()
    private val kim = Viewer(UUID.randomUUID(), "kim", "김", null)
    private val lee = Viewer(UUID.randomUUID(), "lee", null, null)

    @Test
    fun `같은 화면을 보는 사람은 탭이 여러 개여도 한 번만 나온다`() {
        hub.setPresence("a", "project:PAY", kim)
        hub.setPresence("b", "project:PAY", kim)
        hub.setPresence("c", "project:PAY", lee)
        hub.setPresence("d", "issue:PAY-1", lee)

        assertEquals(listOf("kim", "lee"), hub.viewers("project:PAY").map { it.loginId }.sorted())
    }

    @Test
    fun `가리키고 있는 곳이 보는 사람 목록에 실리고 가리키는 탭이 그 사람을 대표한다`() {
        hub.setPresence("a", "project:PAY", kim)
        hub.setPresence("b", "project:PAY", kim)

        hub.setFocus("b", "description")

        assertEquals("description", hub.viewers("project:PAY").single().focus)
        hub.setFocus("b", null)
        assertNull(hub.viewers("project:PAY").single().focus)
    }

    @Test
    fun `화면을 옮기면 가리키던 곳은 지워지고 어느 화면에도 없으면 무시한다`() {
        hub.setPresence("a", "project:PAY", kim)
        hub.setFocus("a", "description")

        hub.setPresence("a", "issue:PAY-1", kim)
        hub.setFocus("z", "title")

        assertNull(hub.viewers("issue:PAY-1").single().focus)
        assertEquals(emptyList(), hub.viewers("project:PAY"))
    }
}
