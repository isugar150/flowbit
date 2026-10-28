package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val PASSWORD = "correct-horse-battery"

class MyCommonFieldIssuesIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var member: ApiClient
    private lateinit var memberId: String
    private lateinit var projectId: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        memberId = admin.post("/api/v1/admin/members", """{"loginId":"member","password":"$PASSWORD"}""").json()["id"] as String
        projectId = admin.post("/api/v1/projects", """{"name":"공통 속성 업무"}""").json()["id"] as String
        admin.put("/api/v1/projects/$projectId/members/$memberId", """{"role":"EDITOR"}""")
        member = client().also { it.login("member", PASSWORD) }
    }

    private fun mine() =
        member
            .get("/api/v1/me/issues")
            .jsonList()
            .map { it["key"] }
            .sortedBy { it.toString() }

    @Test
    fun `공통 사람 속성의 기본값과 지정값은 내 티켓에 포함하고 명시적으로 비운 값은 제외한다`() {
        val fieldId =
            admin
                .post(
                    "/api/v1/projects/$projectId/issue-fields",
                    """{"label":"리뷰어","type":"PERSON","multiple":false,"defaultValue":"$memberId"}""",
                ).json()["id"]
        val issueId = admin.post("/api/v1/projects/$projectId/issues", """{"title":"기본 리뷰어"}""").json()["id"]
        assertEquals(listOf("FB-1"), mine())
        assertEquals(emptyList(), member.get("/api/v1/me/issues?scope=created").jsonList())
        val valuePath = "/api/v1/custom-properties/issue/$issueId/project-fields/$fieldId"
        admin.put(valuePath, """{"value":null}""")
        assertEquals(emptyList(), mine())
        admin.put(valuePath, """{"value":"$memberId"}""")
        assertEquals(listOf("FB-1"), mine())
        admin.put(valuePath, """{"value":null}""")
        admin.patch("/api/v1/issues/1", """{"assigneeIds":["$memberId"]}""")
        assertEquals(listOf("FB-1"), mine())
    }

    @Test
    fun `복수 사람 공통 속성은 중복 없이 포함하고 빈 목록과 프로젝트 이동은 현재 값을 따른다`() {
        val fieldId =
            admin
                .post(
                    "/api/v1/projects/$projectId/issue-fields",
                    """{"label":"검토자","type":"PERSON","multiple":true,"defaultValue":["$memberId"]}""",
                ).json()["id"]
        val issueId =
            admin
                .post(
                    "/api/v1/projects/$projectId/issues",
                    """{"title":"복수 검토","assigneeIds":["$memberId"]}""",
                ).json()["id"]
        assertEquals(listOf("FB-1"), mine())
        admin.patch("/api/v1/issues/1", """{"assigneeIds":[]}""")
        val valuePath = "/api/v1/custom-properties/issue/$issueId/project-fields/$fieldId"
        admin.put(valuePath, """{"value":[]}""")
        assertEquals(emptyList(), mine())
        admin.put(valuePath, """{"value":["$memberId"]}""")
        assertEquals(listOf("FB-1"), mine())
        val otherId = admin.post("/api/v1/projects", """{"name":"속성 없는 프로젝트"}""").json()["id"]
        admin.put("/api/v1/projects/$otherId/members/$memberId", """{"role":"EDITOR"}""")
        admin.patch("/api/v1/issues/1", """{"projectId":"$otherId"}""")
        assertEquals(emptyList(), mine())
        admin.patch("/api/v1/issues/1", """{"projectId":"$projectId"}""")
        assertEquals(listOf("FB-1"), mine())
    }
}
