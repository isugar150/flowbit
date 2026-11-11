package com.flowbit

import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import io.r2dbc.spi.ConnectionFactories
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RichTextImageLayoutTest {
    private val mapper = JsonMapper.builder().build()

    // Parsing never opens a connection; these checks can run without Docker.
    private val richText = RichText(mapper, DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://test:test@localhost/unused")))
    private val src = "/api/v1/files/0b6f2c1e-8a41-4c57-9f0e-2d6a1b3c4d5e.webp"

    @Test
    fun `정렬과 묶음 저장은 각 이미지의 너비와 블록 댓글 연결을 보존한다`() {
        val images =
            listOf("left", "center", "right").mapIndexed { index, align ->
                mapOf(
                    "type" to "image",
                    "attrs" to mapOf("src" to src, "align" to align, "imageGroup" to "gallery-123", "caption" to "설명 $index", "galleryHeight" to 320, "width" to 200 + index, "blockId" to "image-00$index"),
                )
            }
        val saved = richText.parse(mapOf("type" to "doc", "content" to images), blockIds = true)
        val content = mapper.readValue(saved.json, Map::class.java)["content"]
        assertEquals(images, content)
        assertEquals(listOf("image-000", "image-001", "image-002"), richText.blockIdsIn(saved.json))
        assertEquals("이미지", richText.blockQuote(saved.json, "image-001"))
    }

    @Test
    fun `기존 이미지와 묶음 해제의 null 속성을 허용한다`() {
        listOf(mapOf("src" to src), mapOf("src" to src, "align" to null, "imageGroup" to null)).forEach { attrs ->
            richText.parse(mapOf("type" to "doc", "content" to listOf(mapOf("type" to "image", "attrs" to attrs))))
        }
    }

    @Test
    fun `임의 스타일 정렬과 잘못된 묶음 식별자는 거부한다`() {
        val invalid =
            listOf(
                mapOf("galleryHeight" to 95),
                mapOf("galleryHeight" to 721),
                mapOf("galleryHeight" to 240.5),
                mapOf("caption" to 42),
                mapOf("caption" to "a".repeat(501)),
                mapOf("align" to "justify"),
                mapOf("align" to "center; color:red"),
                mapOf("align" to 12),
                mapOf("imageGroup" to "bad group"),
                mapOf("imageGroup" to "a".repeat(65)),
                mapOf("imageGroup" to 42),
            )
        invalid.forEach { attrs ->
            assertFailsWith<ValidationException> {
                richText.parse(mapOf("type" to "doc", "content" to listOf(mapOf("type" to "image", "attrs" to attrs + ("src" to src)))))
            }
        }
    }
}
