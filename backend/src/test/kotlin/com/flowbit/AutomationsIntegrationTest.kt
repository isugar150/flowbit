package com.flowbit

import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.IssueGitService
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val PROGRESS = "00000000-0000-7000-8000-0000000000a3"
private const val REVIEW = "00000000-0000-7000-8000-0000000000a6"
private const val REVIEWING = "00000000-0000-7000-8000-0000000000a7"
private const val DONE = "00000000-0000-7000-8000-0000000000a4"

class AutomationsIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var git: IssueGitService
    private lateinit var admin: ApiClient
    private lateinit var reviewer: ApiClient
    private lateinit var reviewerId: String
    private lateinit var path: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        reviewerId = admin.post("/api/v1/admin/members", """{"loginId":"reviewer","password":"$PASSWORD"}""").json()["id"] as String
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
        admin.put("/api/v1/projects/${p("PAY")}/members/$reviewerId", """{"role":"EDITOR"}""")
        reviewer = client().also { it.login("reviewer", PASSWORD) }
        path = "/api/v1/projects/${p("PAY")}/automations"
    }

    private fun action(
        type: String,
        extra: String = "",
    ) = """{"id":"${UUID.randomUUID()}","type":"$type"$extra}"""

    private fun rule(
        actions: String,
        to: String = REVIEW,
        enabled: Boolean = true,
        from: String? = PROGRESS,
    ) = """{"name":"리뷰 요청","enabled":$enabled,"fromStatusId":${from?.let { "\"$it\"" } ?: "null"},"toStatusId":"$to","actions":[$actions]}"""

    @Test
    fun `리뷰 준비됨과 리뷰 중은 진행 중과 완료 사이의 미완료 상태다`() {
        val statuses = admin.get("/api/v1/issue-statuses").jsonList()
        assertEquals(listOf("진행 중", "리뷰 준비됨", "리뷰 중"), statuses.filter { it["category"] == "IN_PROGRESS" }.map { it["name"] })
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"리뷰할 티켓","statusId":"$REVIEW"}""")
        assertEquals("IN_PROGRESS", admin.get("/api/v1/issues/1").json()["status"])
        assertEquals("IN_PROGRESS", admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEWING"}""").json()["status"])
        assertEquals("DONE", admin.patch("/api/v1/issues/1", """{"statusId":"$DONE"}""").json()["status"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `상태 전환 파이프라인은 담당자 변경 후 알림을 보내고 라벨을 추가 제거하며 실행 기록을 남긴다`() {
        val oldLabel = admin.post("/api/v1/labels", """{"name":"개발 중"}""").json()["id"]
        val newLabel = admin.post("/api/v1/labels", """{"name":"리뷰 대기"}""").json()["id"]
        val actions =
            listOf(
                action("ASSIGN", ""","userIds":["$reviewerId"]"""),
                action("NOTIFY", ""","audience":"ASSIGNEES","message":"리뷰해 주세요 {티켓번호} {티켓제목} {이전상태} → {현재상태} {변경자} {프로젝트명}""""),
                action("ADD_LABELS", ""","labelIds":["$newLabel"]"""),
                action("REMOVE_LABELS", ""","labelIds":["$oldLabel"]"""),
            ).joinToString(",")
        assertEquals(201, admin.post(path, rule(actions)).status)
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"결제 기능","statusId":"$PROGRESS","labelIds":["$oldLabel"]}""")
        val changed = admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""")
        assertEquals(200, changed.status)
        assertEquals(listOf(reviewerId), (changed.json()["assignees"] as List<Map<String, Any?>>).map { it["id"] })
        assertEquals(listOf(newLabel), (changed.json()["labels"] as List<Map<String, Any?>>).map { it["id"] })
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val inbox = reviewer.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
            assertTrue(
                inbox.any {
                    it["headline"] == "리뷰해 주세요 FB-1 결제 기능 진행 중 → 리뷰 준비됨 admin 결제" &&
                        (it["lines"] as List<String>).none { line -> line.contains("자동화:") }
                },
            )
        }
        val runs = admin.get("$path/runs").jsonList()
        assertEquals(1, runs.size)
        assertEquals("리뷰 요청", runs.single()["ruleName"])
        assertEquals("FB-1", runs.single()["issueKey"])
        assertEquals(4, (runs.single()["results"] as List<*>).size)
        admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""")
        assertEquals(1, admin.get("$path/runs").jsonList().size)
    }

    @Test
    fun `규칙을 수정해 켜고 실행한 뒤 삭제해도 실행 기록은 남는다`() {
        val action = action("ASSIGN", ""","userIds":["$reviewerId"]""")
        val created = admin.post(path, rule(action, enabled = false)).json()
        val id = created["id"]
        assertEquals(false, admin.get(path).jsonList().single()["enabled"])
        admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"규칙 확인","statusId":"$PROGRESS"}""")
        admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""")
        assertEquals(0, admin.get("$path/runs").jsonList().size)
        assertEquals(200, admin.put("$path/$id", rule(action)).status)
        admin.patch("/api/v1/issues/1", """{"statusId":"$PROGRESS"}""")
        admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""")
        assertEquals(1, admin.get("$path/runs").jsonList().size)
        assertEquals(204, admin.delete("$path/$id").status)
        assertEquals(0, admin.get(path).jsonList().size)
        assertEquals(1, admin.get("$path/runs").jsonList().size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Git 상태 변경에도 프로젝트 자동화가 적용된다`() =
        runBlocking {
            admin.post(path, rule(action("ASSIGN", ""","userIds":["$reviewerId"]"""), to = DONE, from = null))
            val created =
                admin
                    .post(
                        "/api/v1/projects/${p("PAY")}/issues",
                        """{"title":"Git 완료","statusId":"$PROGRESS"}""",
                    ).json()
            val changed =
                git.changeStatusFromGit(
                    UUID.fromString(created["id"] as String),
                    IssueStatus.DONE,
                    setOf(IssueStatus.IN_PROGRESS),
                    Actor(null, "GitHub"),
                )
            assertEquals(listOf(UUID.fromString(reviewerId)), changed?.issue?.assignees?.map { it.id })
            assertEquals(1, admin.get("$path/runs").jsonList().size)
        }

    @Test
    fun `워크스페이스 관리자는 관리자 경로로 다른 프로젝트의 자동화를 설정한다`() {
        val other = reviewer.post("/api/v1/projects", """{"name":"리뷰어 프로젝트"}""").json()["id"]
        val adminPath = "/api/v1/admin/projects/$other/automations"
        val created = admin.post(adminPath, rule(action("NOTIFY", ""","audience":"MANAGERS""""))).json()
        assertEquals("리뷰 요청", admin.get(adminPath).jsonList().single()["name"])
        assertEquals(204, admin.delete("$adminPath/${created["id"]}").status)
    }
}
