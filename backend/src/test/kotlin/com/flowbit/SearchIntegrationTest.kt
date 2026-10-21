package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** What is written in the editors is found: documents, a project's overview and milestones (D-166). */
class SearchIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var outsider: ApiClient
    private lateinit var projectId: String

    @BeforeEach
    fun prepare() {
        admin = client().also { it.setup("admin", PASSWORD) }
        admin.post("/api/v1/admin/members", """{"loginId":"outsider","password":"$PASSWORD"}""")
        outsider = client().also { it.login("outsider", PASSWORD) }
        projectId = admin.post("/api/v1/projects", """{"name":"결제 개편"}""").json()["id"] as String
    }

    private fun doc(vararg inline: String) = """{"type":"doc","content":[{"type":"paragraph","content":[${inline.joinToString(",")}]}]}"""

    private fun piece(
        value: String,
        bold: Boolean = false,
    ) = """{"type":"text","text":"$value"${if (bold) ""","marks":[{"type":"bold"}]""" else ""}}"""

    private fun found(
        client: ApiClient,
        query: String,
    ) = client.get("/api/v1/search?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8)}").json()

    @Suppress("UNCHECKED_CAST")
    private fun names(
        result: Map<String, Any?>,
        kind: String,
        field: String,
    ): List<Any?> = (result[kind] as List<Map<String, Any?>>).map { it[field] }

    @Test
    fun `프로젝트 개요와 목표와 문서에 쓴 본문을 찾고 낱말이 서식으로 나뉘어도 찾는다`() {
        admin.patch("/api/v1/projects/$projectId", """{"content":${doc(piece("정산 마감은 매월 말일입니다"))}}""")
        val milestone = admin.post("/api/v1/projects/$projectId/milestones", """{"name":"1차 출시"}""").json()["id"]
        admin.patch("/api/v1/projects/$projectId/milestones/$milestone", """{"content":${doc(piece("정산 화면부터 연다"))}}""")
        val document = admin.post("/api/v1/projects/$projectId/documents", """{"title":"정책 모음"}""").json()["id"]
        // The word is cut in two by a bold piece.
        admin.patch("/api/v1/documents/$document", """{"content":${doc(piece("환불"), piece("정책", bold = true), piece("은 열흘"))}}""")

        val settlement = found(admin, "정산")
        assertEquals(listOf<Any?>("결제 개편"), names(settlement, "projects", "name"))
        assertEquals(listOf<Any?>("1차 출시"), names(settlement, "milestones", "name"))
        assertTrue((names(settlement, "projects", "snippet").single() as String).contains("정산"))
        assertEquals(listOf<Any?>("정책 모음"), names(found(admin, "환불정책"), "documents", "title"))
        // The name alone is enough, and the initials work for it.
        assertEquals(listOf<Any?>("1차 출시"), names(found(admin, "ㅊㅅ"), "milestones", "name"))
    }

    @Test
    fun `프로젝트 구성원이 아니면 프로젝트와 목표와 문서가 나오지 않는다`() {
        admin.patch("/api/v1/projects/$projectId", """{"content":${doc(piece("정산 마감은 매월 말일입니다"))}}""")
        val milestone = admin.post("/api/v1/projects/$projectId/milestones", """{"name":"정산 개편"}""").json()["id"]
        admin.patch("/api/v1/projects/$projectId/milestones/$milestone", """{"content":${doc(piece("정산"))}}""")

        val result = found(outsider, "정산")

        assertEquals(emptyList<Any?>(), names(result, "projects", "name"))
        assertEquals(emptyList<Any?>(), names(result, "milestones", "name"))
        assertEquals(emptyList<Any?>(), names(result, "documents", "title"))
    }
}
