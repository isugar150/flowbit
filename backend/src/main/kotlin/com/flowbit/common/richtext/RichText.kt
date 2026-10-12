package com.flowbit.common.richtext

import com.flowbit.common.error.ValidationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A validated editor document: its JSON to store, and its text for previews and search. */
data class RichTextDocument(
    val json: String,
    val plainText: String,
)

/**
 * Documents from the block editor (Tiptap/ProseMirror JSON).
 *
 * The client is not trusted to send a well-formed document: only the node and mark types the
 * editor offers are accepted, links may only point to web, mail or in-app addresses, images may
 * only be files this server stores, and size is capped. Nothing here is ever stored or served as HTML; the editor rebuilds the page from JSON.
 */
@Component
class RichText(
    private val jsonMapper: JsonMapper,
    private val databaseClient: DatabaseClient,
) {
    /** @param field the request field the document came in, named in validation errors. */
    fun parse(
        document: Any?,
        field: String = "content",
    ): RichTextDocument {
        val root = document as? Map<*, *> ?: throw invalid(field)
        if (root["type"] != "doc") throw invalid(field)
        val text = StringBuilder()
        val nodes = intArrayOf(0)
        visit(root, 0, text, nodes, field)
        val json = jsonMapper.writeValueAsString(root)
        if (json.length > MAX_JSON_LENGTH) {
            throw ValidationException(field, "TOO_LONG", "내용이 너무 깁니다")
        }
        return RichTextDocument(json, text.toString().trim())
    }

    /** Stores a document (or clears it with null) in the `content` column of one row. */
    suspend fun store(
        table: ContentTable,
        id: UUID,
        document: RichTextDocument?,
    ) {
        val spec = databaseClient.sql("update ${table.sqlName} set content = cast(:content as jsonb) where id = :id").bind("id", id)
        (if (document != null) spec.bind("content", document.json) else spec.bindNull("content", String::class.java))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The stored document of one row as raw JSON, or null when there is none. */
    suspend fun load(
        table: ContentTable,
        id: UUID,
    ): String? =
        databaseClient
            .sql("select content::text as content from ${table.sqlName} where id = :id")
            .bind("id", id)
            .map { row -> java.util.Optional.ofNullable(row.get("content", String::class.java)) }
            .one()
            .awaitSingleOrNull()
            ?.orElse(null)

    private fun visit(
        node: Map<*, *>,
        depth: Int,
        text: StringBuilder,
        nodes: IntArray,
        field: String,
    ) {
        val type = node["type"] as? String ?: throw invalid(field)
        if (type !in NODE_TYPES || depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) throw invalid(field)

        (node["marks"] as? List<*>)?.forEach { mark ->
            val markMap = mark as? Map<*, *> ?: throw invalid(field)
            val markType = markMap["type"] as? String
            if (markType !in MARK_TYPES) throw invalid(field)
            if (markType == "link") {
                val href = (markMap["attrs"] as? Map<*, *>)?.get("href") as? String ?: throw invalid(field)
                if (!SAFE_LINK.containsMatchIn(href)) throw invalid(field)
            }
        }
        val attrs = node["attrs"] as? Map<*, *>
        when (type) {
            "image" -> {
                val src = attrs?.get("src") as? String ?: throw invalid(field)
                if (!STORED_IMAGE.matches(src)) throw invalid(field)
            }
            "tableCell", "tableHeader" -> {
                if (attrs?.get("align") !in CELL_ALIGN || attrs?.get("verticalAlign") !in CELL_VERTICAL_ALIGN) throw invalid(field)
                if (!isSpan(attrs?.get("colspan")) || !isSpan(attrs?.get("rowspan"))) throw invalid(field)
            }
        }
        if (type == "text") {
            text.append(node["text"] as? String ?: throw invalid(field))
            return
        }
        (node["content"] as? List<*>)?.forEach { child ->
            visit(child as? Map<*, *> ?: throw invalid(field), depth + 1, text, nodes, field)
        }
        if (type in BLOCK_TYPES && text.isNotEmpty() && text.last() != '\n') text.append('\n')
    }

    /** A missing span means 1. */
    private fun isSpan(value: Any?): Boolean = value == null || (value is Int && value in 1..MAX_SPAN)

    private fun invalid(field: String) = ValidationException(field, "INVALID_DOCUMENT", "문서 형식이 올바르지 않습니다")

    /** Tables that have a `content` column. An enum, so a table name never comes from input. */
    enum class ContentTable(
        val sqlName: String,
    ) {
        PROJECTS("projects"),
        ISSUES("issues"),
        ISSUE_DOCUMENTS("issue_documents"),
    }

    private companion object {
        const val MAX_JSON_LENGTH = 200_000
        const val MAX_DEPTH = 24
        const val MAX_NODES = 5_000

        val BLOCK_TYPES =
            setOf("paragraph", "heading", "codeBlock", "listItem", "taskItem", "blockquote", "horizontalRule")
        val NODE_TYPES =
            BLOCK_TYPES +
                setOf("doc", "text", "bulletList", "orderedList", "taskList", "hardBreak") +
                setOf("image", "table", "tableRow", "tableHeader", "tableCell")
        val MARK_TYPES = setOf("bold", "italic", "strike", "code", "underline", "link")

        /** Only images uploaded here (ImageStorage keys); an outside address could track readers. */
        val STORED_IMAGE = Regex("^/api/v1/files/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")
        val CELL_ALIGN = setOf(null, "left", "center", "right")
        val CELL_VERTICAL_ALIGN = setOf(null, "top", "middle", "bottom")
        const val MAX_SPAN = 100

        /** Web and mail links, and paths inside this app. Rules out `javascript:` and other schemes. */
        val SAFE_LINK = Regex("^(https?://|mailto:|/(?!/)|#)", RegexOption.IGNORE_CASE)
    }
}
