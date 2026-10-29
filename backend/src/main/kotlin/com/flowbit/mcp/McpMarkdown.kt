package com.flowbit.mcp

import com.flowbit.common.error.ValidationException
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** Markdown is an input convenience; the normal RichText validator still checks the resulting document. */
@Component
class McpMarkdown(
    private val json: JsonMapper,
) {
    private val parser = Parser.builder().build()

    fun document(markdown: String): Map<String, Any?> {
        if (markdown.length > 100_000) throw ValidationException("markdown", "TOO_LONG", "본문은 100000자까지 입력하세요")

        fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

        fun node(
            type: String,
            content: List<Map<String, Any?>> = emptyList(),
            attrs: Map<String, Any?>? = null,
        ): Map<String, Any?> =
            buildMap {
                put("type", type)
                if (content.isNotEmpty()) put("content", content)
                if (attrs != null) put("attrs", attrs)
            }

        fun text(value: String) = mapOf<String, Any?>("type" to "text", "text" to value)

        fun convert(
            source: Node,
            depth: Int,
        ): Map<String, Any?> {
            if (depth > 30) throw ValidationException("markdown", "TOO_DEEP", "본문 구조가 너무 깊습니다")
            val content = children(source).map { convert(it, depth + 1) }
            return when (source) {
                is Document -> node("doc", content.ifEmpty { listOf(node("paragraph")) })
                is Paragraph -> node("paragraph", content)
                is Heading -> node("heading", content, mapOf("level" to source.level))
                is Text -> text(source.literal)
                is SoftLineBreak, is HardLineBreak -> node("hardBreak")
                is BulletList -> node("bulletList", content)
                is OrderedList -> node("orderedList", content, mapOf("start" to source.markerStartNumber))
                is ListItem -> node("listItem", content)
                is BlockQuote -> node("blockquote", content)
                is ThematicBreak -> node("horizontalRule")
                is FencedCodeBlock -> node("codeBlock", listOf(text(source.literal.trimEnd('\n'))), mapOf("language" to source.info.ifBlank { null }))
                is IndentedCodeBlock -> node("codeBlock", listOf(text(source.literal.trimEnd('\n'))))
                is Code -> text(source.literal) + ("marks" to listOf(mapOf("type" to "code")))
                is Emphasis, is StrongEmphasis, is Link -> {
                    // Inline marks live on each text node, never on a container node.
                    val mark =
                        when (source) {
                            is Link -> mapOf("type" to "link", "attrs" to mapOf("href" to source.destination))
                            is StrongEmphasis -> mapOf("type" to "bold")
                            else -> mapOf("type" to "italic")
                        }
                    return node("paragraph", content.map { child -> child + ("marks" to ((child["marks"] as? List<*>).orEmpty() + mark)) })
                }
                is Image -> text("[이미지: ${source.destination}]")
                is HtmlInline -> text(source.literal)
                is HtmlBlock -> node("paragraph", listOf(text(source.literal)))
                else -> node("paragraph", content)
            }
        }

        // Flatten the temporary wrappers used for inline marks, carrying marks through nested emphasis.
        fun flatten(
            source: Map<String, Any?>,
            inherited: List<Any?> = emptyList(),
            inline: Boolean = false,
        ): List<Map<String, Any?>> {
            val marks = inherited + (source["marks"] as? List<*>).orEmpty()
            val type = source["type"]
            val content = (source["content"] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()
            if (inline && type == "paragraph") return content.flatMap { flatten(it, marks, true) }
            val result = source.toMutableMap()
            if (content.isNotEmpty()) result["content"] = content.flatMap { flatten(it, emptyList(), type in setOf("paragraph", "heading")) }
            if (type == "text" && marks.isNotEmpty()) result["marks"] = marks
            return listOf(result)
        }
        return flatten(convert(parser.parse(markdown), 0)).single()
    }

    fun render(
        content: String?,
        fallback: String = "",
    ): String {
        if (content == null) return fallback

        fun renderNode(value: Any?): String {
            val node = value as? Map<*, *> ?: return ""
            val attrs = node["attrs"] as? Map<*, *>
            val children = (node["content"] as? List<*>).orEmpty()
            val text = children.joinToString("") { renderNode(it) }
            return when (node["type"]) {
                "doc" -> text.trimEnd()
                "paragraph" -> "$text\n\n"
                "heading" -> "${"#".repeat((attrs?.get("level") as? Number)?.toInt()?.coerceIn(1, 6) ?: 1)} $text\n\n"
                "text" -> {
                    var literal = node["text"] as? String ?: ""
                    (node["marks"] as? List<*>).orEmpty().forEach { mark ->
                        val m = mark as? Map<*, *>
                        literal =
                            when (m?.get("type")) {
                                "bold" -> "**$literal**"
                                "italic" -> "*$literal*"
                                "code" -> "`$literal`"
                                "strike" -> "~~$literal~~"
                                "link" -> "[$literal](${(m["attrs"] as? Map<*, *>)?.get("href")})"
                                else -> literal
                            }
                    }
                    literal
                }
                "hardBreak" -> "\n"
                "listItem" -> "$text\n"
                "bulletList", "orderedList", "taskList" ->
                    children
                        .mapIndexed {
                            index,
                            child,
                            ->
                            "${if (node["type"] == "orderedList") "${index + 1}." else "-"} ${renderNode(child).trim()}\n"
                        }.joinToString("") +
                        "\n"
                "blockquote" -> text.trim().lines().joinToString("\n") { "> $it" } + "\n\n"
                "codeBlock" -> "```\n$text\n```\n\n"
                "horizontalRule" -> "---\n\n"
                "mention" -> "@${attrs?.get("label")?.toString().orEmpty()}"
                "image" -> "![이미지](${attrs?.get("src")})\n\n"
                "pageLink" -> "[${attrs?.get("title")}](${attrs?.get("href")})"
                else -> text
            }
        }
        return renderNode(json.readValue(content, Map::class.java))
    }
}
