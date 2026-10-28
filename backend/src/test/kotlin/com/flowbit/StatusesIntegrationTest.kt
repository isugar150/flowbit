package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val PASSWORD = "correct-horse-battery"

class StatusesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        admin = client().also { it.setup("admin", PASSWORD) }
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
    }

    @Test
    fun `관리자가 범주 안에 상태를 더하면 그 순서대로 목록에 나온다`() {
        val review = admin.post("/api/v1/admin/issue-statuses", """{"category":"IN_PROGRESS","name":" 품질 확인 "}""")

        assertEquals(201, review.status)
        assertEquals("품질 확인", review.json()["name"])
        val names = admin.get("/api/v1/issue-statuses").jsonList().map { it["name"] }
        assertEquals(listOf("백로그", "준비됨", "진행 중", "리뷰 준비됨", "리뷰 중", "품질 확인", "완료", "취소"), names)

        admin.patch("/api/v1/admin/issue-statuses/${review.json()["id"]}", """{"direction":"UP"}""")
        assertEquals(listOf("백로그", "준비됨", "진행 중", "리뷰 준비됨", "품질 확인", "리뷰 중", "완료", "취소"), admin.get("/api/v1/issue-statuses").jsonList().map { it["name"] })
    }

    @Test
    fun `티켓을 새 상태로 옮기면 범주는 그 상태의 것이 되고 활동에 이름이 남는다`() {
        val review = admin.post("/api/v1/admin/issue-statuses", """{"category":"IN_PROGRESS","name":"품질 확인"}""").json()["id"]
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 오류"}""")

        val moved = admin.patch("/api/v1/issues/1", """{"statusId":"$review"}""")

        assertEquals("IN_PROGRESS", moved.json()["status"])
        assertEquals("품질 확인", moved.json()["statusName"])
        assertEquals(review, moved.json()["statusId"])
        @Suppress("UNCHECKED_CAST")
        val items = admin.get("/api/v1/issues/1/activity").json()["items"] as List<Map<String, Any?>>
        assertEquals(
            mapOf("from" to "BACKLOG", "to" to "IN_PROGRESS", "fromName" to "백로그", "toName" to "품질 확인"),
            items.last()["detail"],
        )
        // Only the category is said: an issue already in it stays where it is.
        assertEquals("품질 확인", admin.patch("/api/v1/issues/1", """{"status":"IN_PROGRESS"}""").json()["statusName"])
        assertEquals("완료", admin.patch("/api/v1/issues/1", """{"status":"DONE"}""").json()["statusName"])
    }

    @Test
    fun `상태를 지우면 그 상태의 티켓은 같은 범주의 첫 상태로 옮겨진다`() {
        val review = admin.post("/api/v1/admin/issue-statuses", """{"category":"IN_PROGRESS","name":"품질 확인"}""").json()["id"]
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 오류","statusId":"$review"}""")

        assertEquals(204, admin.delete("/api/v1/admin/issue-statuses/$review").status)

        assertEquals("진행 중", admin.get("/api/v1/issues/1").json()["statusName"])
        assertEquals(8 - 1, admin.get("/api/v1/issue-statuses").jsonList().size)
    }

    @Test
    fun `프로젝트 관리 역할이 추정치를 켜면 티켓에 추정치를 적고 지울 수 있다`() {
        assertEquals(false, admin.get("/api/v1/projects/${p("PAY")}").json()["estimatesEnabled"])
        val enabled = admin.patch("/api/v1/projects/${p("PAY")}", """{"estimatesEnabled":true}""")
        assertEquals(true, enabled.json()["estimatesEnabled"])
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 오류","estimate":5}""")

        assertEquals(5, admin.get("/api/v1/issues/1").json()["estimate"])
        assertEquals(8, admin.patch("/api/v1/issues/1", """{"estimate":8}""").json()["estimate"])
        assertNull(admin.patch("/api/v1/issues/1", """{"estimate":null}""").json()["estimate"])
    }
}
