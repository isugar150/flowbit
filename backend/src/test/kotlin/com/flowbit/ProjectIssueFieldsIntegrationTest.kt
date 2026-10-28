package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"
private const val PROGRESS = "00000000-0000-7000-8000-0000000000a3"
private const val REVIEW = "00000000-0000-7000-8000-0000000000a6"

class ProjectIssueFieldsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var reviewer: ApiClient
    private lateinit var second: ApiClient
    private lateinit var reviewerId: String
    private lateinit var secondId: String
    private lateinit var path: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        reviewerId = admin.post("/api/v1/admin/members", """{"loginId":"reviewer","password":"$PASSWORD"}""").json()["id"] as String
        secondId = admin.post("/api/v1/admin/members", """{"loginId":"second","password":"$PASSWORD"}""").json()["id"] as String
        remember("PAY", admin.post("/api/v1/projects", """{"name":"결제"}""").json()["id"])
        for (id in listOf(reviewerId, secondId)) admin.put("/api/v1/projects/${p("PAY")}/members/$id", """{"role":"EDITOR"}""")
        reviewer = client().also { it.login("reviewer", PASSWORD) }
        second = client().also { it.login("second", PASSWORD) }
        path = "/api/v1/projects/${p("PAY")}/issue-fields"
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `리뷰어 추가와 제거는 담당자 변경 없이 속성 이름과 변경된 사람을 활동에 기록한다`() {
        val fieldId = admin.post(path, """{"label":"리뷰어","type":"PERSON","defaultValue":["$reviewerId"]}""").json()["id"]
        val issue = ticket("리뷰어 활동", ""","assigneeIds":["$reviewerId"]""")
        val issueId = issue["id"]

        fun changes() =
            (admin.get("/api/v1/issues/1/activity").json()["items"] as List<Map<String, Any?>>)
                .filter { it["type"] != "CREATED" }

        fun latest() = changes().first()["detail"] as Map<String, Any?>

        assertEquals(200, admin.put(valuePath(issueId, fieldId), """{"value":["$reviewerId","$secondId"]}""").status)
        assertEquals("리뷰어", latest()["label"])
        assertEquals("PERSON", latest()["propertyType"])
        assertEquals(listOf("second"), latest()["added"])
        assertEquals(emptyList<String>(), latest()["removed"])
        assertEquals("reviewer", latest()["from"])
        assertEquals("reviewer, second", latest()["to"])

        clock.advance(Duration.ofSeconds(1))
        assertEquals(200, admin.put(valuePath(issueId, fieldId), """{"value":[]}""").status)
        assertEquals(emptyList<String>(), latest()["added"])
        assertEquals(listOf("reviewer", "second"), latest()["removed"])
        assertEquals(listOf("CUSTOM_PROPERTY_CHANGED", "CUSTOM_PROPERTY_CHANGED"), changes().map { it["type"] })
        val assignees = admin.get("/api/v1/issues/1").json()["assignees"] as List<Map<String, Any?>>
        assertEquals(listOf(reviewerId), assignees.map { it["id"] })

        // A single-person field uses the same distinction.
        val singleId = admin.post(path, """{"label":"승인자","type":"PERSON","multiple":false}""").json()["id"]
        clock.advance(Duration.ofSeconds(1))
        assertEquals(200, admin.put(valuePath(issueId, singleId), """{"value":"$secondId"}""").status)
        assertEquals("승인자", latest()["label"])
        assertEquals(listOf("second"), latest()["added"])
        assertEquals(emptyList<String>(), latest()["removed"])
    }

    /** A person field that takes one person; the field takes several unless it says otherwise (D-238). */
    private fun definition(person: String) = """{"label":"리뷰어","type":"PERSON","multiple":false,"defaultValue":"$person"}"""

    private fun ticket(
        title: String,
        extra: String = "",
    ) = admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"$title","statusId":"$PROGRESS"$extra}""").json()

    private fun valuePath(
        issueId: Any?,
        fieldId: Any?,
    ) = "/api/v1/custom-properties/issue/$issueId/project-fields/$fieldId"

    @Suppress("UNCHECKED_CAST")
    private fun properties(issueId: Any?): List<Map<String, Any?>> =
        admin.get("/api/v1/custom-properties/issue/$issueId").json()["items"] as List<Map<String, Any?>>

    @Test
    fun `공통 속성은 기존과 새 티켓에 보이고 기본값을 바꿔도 지정값과 빈 값은 보존한다`() {
        val old = ticket("기존 티켓")["id"]
        val field = admin.post(path, definition(reviewerId))
        assertEquals(201, field.status)
        val fieldId = field.json()["id"]
        assertEquals(reviewerId, properties(old).single()["value"])
        assertEquals(fieldId, properties(old).single()["projectFieldId"])
        val fresh = ticket("새 티켓", ""","projectFields":[{"fieldId":"$fieldId","value":"$secondId"}]""")["id"]
        assertEquals(secondId, properties(fresh).single()["value"])
        assertEquals(200, admin.put("$path/$fieldId", definition(secondId)).status)
        assertEquals(secondId, properties(old).single()["value"])
        assertEquals(200, admin.put(valuePath(fresh, fieldId), """{"value":null}""").status)
        admin.put("$path/$fieldId", definition(reviewerId))
        assertNull(properties(fresh).single()["value"])
        assertEquals(reviewerId, properties(old).single()["value"])
    }

    @Test
    fun `기존 개별 속성을 같은 이름과 형식의 공통 속성으로 편입하고 값을 유지한다`() {
        val issueId = ticket("기존 리뷰어")["id"]
        admin.post("/api/v1/custom-properties/issue/$issueId", """{"label":"리뷰어","type":"PERSON","value":"$secondId"}""")
        val fieldId = admin.post(path, definition(reviewerId)).json()["id"]
        val items = properties(issueId)
        assertEquals(1, items.size)
        assertEquals(secondId, items.single()["value"])
        assertEquals(fieldId, items.single()["projectFieldId"])
        assertEquals(0, count("select count(*) from custom_properties"))
        assertEquals(204, admin.delete("$path/$fieldId").status)
        assertEquals(emptyList(), properties(issueId))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `자동화는 사람 속성의 기본값 또는 티켓에서 지정한 사람에게 알림을 보낸다`() {
        val fieldId = admin.post(path, definition(reviewerId)).json()["id"]
        val rule =
            """
            {"name":"속성 리뷰어에게 알림","fromStatusId":"$PROGRESS","toStatusId":"$REVIEW",
             "actions":[{"id":"${UUID.randomUUID()}","type":"NOTIFY","audience":"PROJECT_FIELD",
             "fieldId":"$fieldId","message":"검토해 주세요"}]}
            """.trimIndent()
        assertEquals(201, admin.post("/api/v1/projects/${p("PAY")}/automations", rule).status)
        ticket("기본 리뷰어")
        ticket("다른 리뷰어", ""","projectFields":[{"fieldId":"$fieldId","value":"$secondId"}]""")
        assertEquals(200, admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""").status)
        assertEquals(200, admin.patch("/api/v1/issues/2", """{"statusId":"$REVIEW"}""").status)
        for ((person, key) in listOf(reviewer to "FB-1", second to "FB-2")) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                val inbox = person.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
                assertTrue(inbox.any { it["path"] == "/issue/$key" && it["headline"].toString().contains("검토해 주세요") })
            }
        }
    }

    @Test
    fun `공통 속성 값은 복제되고 프로젝트를 옮기면 해당 프로젝트 정의를 따른다`() {
        val fieldId = admin.post(path, definition(reviewerId)).json()["id"]
        val original = ticket("원본", ""","projectFields":[{"fieldId":"$fieldId","value":"$secondId"}]""")["id"]
        val copied = admin.post("/api/v1/issues/1/duplicate", "{}").json()["id"]
        assertEquals(secondId, properties(copied).single()["value"])
        val other = admin.post("/api/v1/projects", """{"name":"다른 프로젝트"}""").json()["id"]
        admin.post("/api/v1/projects/$other/issue-fields", """{"label":"분류","type":"TEXT","defaultValue":"확인 필요"}""")
        assertEquals(200, admin.patch("/api/v1/issues/1", """{"projectId":"$other"}""").status)
        assertEquals("분류", properties(original).single()["label"])
        assertEquals("확인 필요", properties(original).single()["value"])
        admin.patch("/api/v1/issues/1", """{"projectId":"${p("PAY")}"}""")
        assertEquals(secondId, properties(original).single()["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `사람 속성은 기본으로 여러 명을 고르고 한 명으로 바꾸면 첫 사람만 남고 다시 여러 명으로 바꾸면 한 명 목록이 된다`() {
        val field = admin.post(path, """{"label":"리뷰어","type":"PERSON","defaultValue":["$reviewerId"]}""")
        assertEquals(201, field.status)
        assertEquals(true, field.json()["multiple"])
        assertEquals(listOf(reviewerId), field.json()["defaultValue"])
        val fieldId = field.json()["id"]

        val issue = ticket("여러 리뷰어", ""","projectFields":[{"fieldId":"$fieldId","value":["$reviewerId","$secondId"]}]""")["id"]
        val both = properties(issue).single()
        assertEquals(listOf(reviewerId, secondId), both["value"])
        assertEquals(true, both["multiple"])
        assertEquals(listOf("reviewer", "second"), (both["people"] as List<Map<String, Any?>>).map { it["loginId"] })

        // A single id is taken as a list of one.
        assertEquals(200, admin.put(valuePath(issue, fieldId), """{"value":"$secondId"}""").status)
        assertEquals(listOf(secondId), properties(issue).single()["value"])
        assertEquals(200, admin.put(valuePath(issue, fieldId), """{"value":["$secondId","$reviewerId"]}""").status)

        // One person: the first of the list stays.
        val single = admin.put("$path/$fieldId", """{"label":"리뷰어","type":"PERSON","multiple":false,"defaultValue":"$reviewerId"}""")
        assertEquals(200, single.status)
        assertEquals(false, single.json()["multiple"])
        assertEquals(secondId, properties(issue).single()["value"])
        assertEquals(200, admin.put(valuePath(issue, fieldId), """{"value":"$reviewerId"}""").status)

        // Several again: one person becomes a list of one.
        assertEquals(200, admin.put("$path/$fieldId", """{"label":"리뷰어","type":"PERSON","multiple":true,"defaultValue":["$reviewerId"]}""").status)
        assertEquals(listOf(reviewerId), properties(issue).single()["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `여러 명을 받는 사람 속성은 자동화가 지정된 모두에게 알림을 보낸다`() {
        val fieldId = admin.post(path, """{"label":"리뷰어","type":"PERSON","defaultValue":["$reviewerId","$secondId"]}""").json()["id"]
        val rule =
            """
            {"name":"속성 리뷰어에게 알림","fromStatusId":"$PROGRESS","toStatusId":"$REVIEW",
             "actions":[{"id":"${UUID.randomUUID()}","type":"NOTIFY","audience":"PROJECT_FIELD",
             "fieldId":"$fieldId","message":"함께 검토해 주세요"}]}
            """.trimIndent()
        assertEquals(201, admin.post("/api/v1/projects/${p("PAY")}/automations", rule).status)
        ticket("함께 검토")
        assertEquals(200, admin.patch("/api/v1/issues/1", """{"statusId":"$REVIEW"}""").status)
        for (person in listOf(reviewer, second)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                val inbox = person.get("/api/v1/me/inbox").json()["items"] as List<Map<String, Any?>>
                assertTrue(inbox.any { it["path"] == "/issue/FB-1" && it["headline"].toString().contains("함께 검토해 주세요") })
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `하위 티켓은 만들 때 상위 티켓의 담당자와 공통 속성 값을 이어받고 이후에는 따로 바꾼다`() {
        val fieldId = admin.post(path, definition(reviewerId)).json()["id"]
        val parent = ticket("상위", ""","assigneeIds":["$reviewerId"],"projectFields":[{"fieldId":"$fieldId","value":"$secondId"}]""")
        val child = admin.post("/api/v1/projects/${p("PAY")}/issues", """{"title":"하위","parentId":"${parent["id"]}"}""").json()

        fun assignees(number: Any?) = (admin.get("/api/v1/issues/$number").json()["assignees"] as List<Map<String, Any?>>).map { it["id"] }

        // The parent's explicit value, not the field's default (the reviewer), and the parent's assignee.
        assertEquals(secondId, properties(child["id"]).single()["value"])
        assertEquals(listOf(reviewerId), assignees(child["number"]))

        // Each is changed on its own: the sub-issue's change leaves the parent as it was.
        assertEquals(200, admin.put(valuePath(child["id"], fieldId), """{"value":"$reviewerId"}""").status)
        assertEquals(200, admin.patch("/api/v1/issues/${child["number"]}", """{"assigneeIds":["$secondId"]}""").status)
        assertEquals(reviewerId, properties(child["id"]).single()["value"])
        assertEquals(listOf(secondId), assignees(child["number"]))
        assertEquals(secondId, properties(parent["id"]).single()["value"])
        assertEquals(listOf(reviewerId), assignees(parent["number"]))

        // A value the request gives wins over the parent's.
        val own =
            admin
                .post(
                    "/api/v1/projects/${p("PAY")}/issues",
                    """{"title":"직접 지정","parentId":"${parent["id"]}","projectFields":[{"fieldId":"$fieldId","value":"$reviewerId"}]}""",
                ).json()
        assertEquals(reviewerId, properties(own["id"]).single()["value"])
    }

    @Test
    fun `관리자는 별도 관리자 경로에서 구성원이 아닌 프로젝트의 공통 속성을 설정한다`() {
        val other = reviewer.post("/api/v1/projects", """{"name":"관리자 설정 확인"}""").json()["id"]
        val adminPath = "/api/v1/admin/projects/$other/issue-fields"
        val result = admin.post(adminPath, """{"label":"확인 여부","type":"CHECKBOX","defaultValue":true}""")
        assertEquals(201, result.status)
        assertEquals(true, admin.get(adminPath).jsonList().single()["defaultValue"])
    }
}
