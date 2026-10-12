package com.flowbit

import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

private const val DOCUMENT =
    """{"type":"doc","content":[
        {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"배경"}]},
        {"type":"paragraph","content":[{"type":"text","text":"환불이 "},{"type":"text","marks":[{"type":"bold"}],"text":"느리다"}]},
        {"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":false},"content":[
            {"type":"paragraph","content":[{"type":"text","text":"3초 안에 끝난다"}]}]}]}
    ]}"""

class RichTextIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient
    private lateinit var editor: ApiClient
    private lateinit var viewer: ApiClient

    @BeforeEach
    fun createProjectWithPeople() {
        admin = client().also { it.setup("admin", PASSWORD) }
        val editorId = createMember("editor")
        val viewerId = createMember("viewer")
        admin.post("/api/v1/projects", """{"name":"결제 개편","key":"PAY"}""")
        admin.put("/api/v1/projects/PAY/members/$editorId", """{"role":"EDITOR"}""")
        admin.put("/api/v1/projects/PAY/members/$viewerId", """{"role":"VIEWER"}""")
        editor = client().also { it.login("editor", PASSWORD) }
        viewer = client().also { it.login("viewer", PASSWORD) }
    }

    @Test
    fun `편집 역할은 프로젝트 개요 문서와 요약을 쓸 수 있고 다시 읽으면 그대로 나온다`() {
        assertNull(editor.get("/api/v1/projects/PAY").json()["content"])

        val saved = editor.patch("/api/v1/projects/PAY", """{"description":"결제를 다시 만든다","content":$DOCUMENT}""")

        assertEquals(200, saved.status)
        assertEquals("결제를 다시 만든다", saved.json()["description"])
        val content = viewer.get("/api/v1/projects/PAY").json()["content"] as Map<*, *>
        assertEquals("doc", content["type"])
        assertEquals(3, (content["content"] as List<*>).size)
        // Lists stay light: the document is only sent when one project is requested.
        assertNull(editor.get("/api/v1/projects").jsonList().single()["content"])
    }

    @Test
    fun `문서를 null 로 보내면 지워지고 보내지 않으면 그대로다`() {
        editor.patch("/api/v1/projects/PAY", """{"content":$DOCUMENT}""")

        editor.patch("/api/v1/projects/PAY", """{"description":"요약만 바꿈"}""")
        assertTrue(editor.get("/api/v1/projects/PAY").json()["content"] is Map<*, *>)

        editor.patch("/api/v1/projects/PAY", """{"content":null}""")
        assertNull(editor.get("/api/v1/projects/PAY").json()["content"])
    }

    @Test
    fun `보기 역할은 개요를 고칠 수 없고 편집 역할은 이름을 바꿀 수 없다`() {
        assertEquals(403, viewer.patch("/api/v1/projects/PAY", """{"content":$DOCUMENT}""").status)
        assertEquals(403, editor.patch("/api/v1/projects/PAY", """{"name":"내 프로젝트"}""").status)
        assertEquals("결제 개편", editor.get("/api/v1/projects/PAY").json()["name"])
    }

    @Test
    fun `허용하지 않는 블록이나 위험한 링크가 든 문서는 거부한다`() {
        val unknownNode = """{"type":"doc","content":[{"type":"script","content":[{"type":"text","text":"alert(1)"}]}]}"""
        val badLink =
            """{"type":"doc","content":[{"type":"paragraph","content":[
                {"type":"text","text":"눌러 보세요","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}"""
        val notADocument = """{"type":"paragraph"}"""

        listOf(unknownNode, badLink, notADocument, "\"그냥 글자\"").forEach { document ->
            val response = editor.patch("/api/v1/projects/PAY", """{"content":$document}""")
            assertEquals(422, response.status, document)
            assertEquals("VALIDATION_FAILED", response.json()["code"])
        }
        assertNull(editor.get("/api/v1/projects/PAY").json()["content"])
    }

    @Test
    fun `안전한 링크는 받아들인다`() {
        val document =
            """{"type":"doc","content":[{"type":"paragraph","content":[
                {"type":"text","text":"문서","marks":[{"type":"link","attrs":{"href":"https://example.com/spec"}}]}]}]}"""

        assertEquals(200, editor.patch("/api/v1/projects/PAY", """{"content":$document}""").status)
    }

    @Test
    fun `이슈를 문서와 함께 만들면 본문이 저장되고 설명은 문서의 글자로 채워진다`() {
        val created = editor.post("/api/v1/projects/PAY/issues", """{"title":"환불 흐름","content":$DOCUMENT}""")

        assertEquals(201, created.status)
        assertEquals("배경\n환불이 느리다\n3초 안에 끝난다", created.json()["description"])
        assertEquals("doc", (created.json()["content"] as Map<*, *>)["type"])
        assertEquals("doc", (viewer.get("/api/v1/projects/PAY/issues/1").json()["content"] as Map<*, *>)["type"])
        assertNull(viewer.get("/api/v1/projects/PAY/issues").jsonList().single()["content"])
    }

    @Test
    fun `이슈 본문을 고치면 설명도 함께 바뀌고 다른 속성은 그대로다`() {
        editor.post("/api/v1/projects/PAY/issues", """{"title":"환불 흐름","priority":"HIGH","content":$DOCUMENT}""")
        val shorter = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"다시 씀"}]}]}"""

        val updated = editor.patch("/api/v1/projects/PAY/issues/1", """{"content":$shorter}""").json()

        assertEquals("다시 씀", updated["description"])
        assertEquals("HIGH", updated["priority"])
        assertEquals(1, ((updated["content"] as Map<*, *>)["content"] as List<*>).size)
        assertEquals(422, editor.patch("/api/v1/projects/PAY/issues/1", """{"content":{"type":"doc","content":[{"type":"iframe"}]}}""").status)
    }

    private fun createMember(loginId: String): String =
        admin.post("/api/v1/admin/members", """{"loginId":"$loginId","password":"$PASSWORD"}""").json()["id"] as String
}
