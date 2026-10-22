package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

/** What is written in the editors is found: documents and a project's overview (D-166), and a sub-issue is found with the issues above it (D-189). */
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
    fun `프로젝트 개요와 문서에 쓴 본문을 찾고 낱말이 서식으로 나뉘어도 찾는다`() {
        admin.patch("/api/v1/projects/$projectId", """{"content":${doc(piece("정산 마감은 매월 말일입니다"))}}""")
        val document = admin.post("/api/v1/projects/$projectId/documents", """{"title":"정책 모음"}""").json()["id"]
        // The word is cut in two by a bold piece.
        admin.patch("/api/v1/documents/$document", """{"content":${doc(piece("환불"), piece("정책", bold = true), piece("은 열흘"))}}""")

        val settlement = found(admin, "정산")
        assertEquals(listOf<Any?>("결제 개편"), names(settlement, "projects", "name"))
        assertTrue((names(settlement, "projects", "snippet").single() as String).contains("정산"))
        assertEquals(listOf<Any?>("정책 모음"), names(found(admin, "환불정책"), "documents", "title"))
        // The title alone is enough, and the initials work for it.
        assertEquals(listOf<Any?>("정책 모음"), names(found(admin, "ㅈㅊ"), "documents", "title"))
    }

    @Test
    fun `프로젝트 구성원이 아니면 프로젝트와 문서가 나오지 않는다`() {
        admin.patch("/api/v1/projects/$projectId", """{"content":${doc(piece("정산 마감은 매월 말일입니다"))}}""")

        val result = found(outsider, "정산")

        assertEquals(emptyList<Any?>(), names(result, "projects", "name"))
        assertEquals(emptyList<Any?>(), names(result, "documents", "title"))
    }

    @Test
    fun `하위 티켓은 맨 위 티켓부터 위의 티켓들과 함께 나온다`() {
        val root = admin.post("/api/v1/projects/$projectId/issues", """{"title":"정산 개편"}""").json()
        val middle = admin.post("/api/v1/issues", """{"title":"정산 API","parentId":"${root["id"]}"}""").json()
        admin.post("/api/v1/issues", """{"title":"정산 승인 요청","parentId":"${middle["id"]}"}""")

        val hits = found(admin, "승인")["issues"] as List<*>

        @Suppress("UNCHECKED_CAST")
        val leaf = hits.single() as Map<String, Any?>
        assertEquals("정산 승인 요청", leaf["title"])
        // The top-level issue first, the leaf's own parent last; the leaf itself is not in it.
        @Suppress("UNCHECKED_CAST")
        val ancestors = leaf["ancestors"] as List<Map<String, Any?>>
        assertEquals(listOf("FB-1", "FB-2"), ancestors.map { it["key"] })
        assertEquals(listOf("정산 개편", "정산 API"), ancestors.map { it["title"] })
        // A top-level issue has nothing above it.
        @Suppress("UNCHECKED_CAST")
        val top = (found(admin, "개편")["issues"] as List<Map<String, Any?>>).single()
        assertEquals(emptyList<Any?>(), top["ancestors"])
    }
}
