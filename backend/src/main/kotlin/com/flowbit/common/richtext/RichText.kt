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
    /** The people tagged in it with @, each once, in the order they first appear. */
    val mentions: List<UUID> = emptyList(),
)

/**
 * Documents from the block editor (Tiptap/ProseMirror JSON).
 *
 * The client is not trusted to send a well-formed document: only the node and mark types the
 * editor offers are accepted, links may only point to web, mail or in-app addresses, images may
 * only be files this server stores, embeds only Figma files and Outline share pages, and size is capped. Nothing here is ever stored or served as HTML; the editor rebuilds the page from JSON.
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
        val mentions = linkedSetOf<UUID>()
        visit(root, 0, text, nodes, mentions, field)
        val json = jsonMapper.writeValueAsString(root)
        if (json.length > MAX_JSON_LENGTH) {
            throw ValidationException(field, "TOO_LONG", "내용이 너무 깁니다")
        }
        return RichTextDocument(json, text.toString().trim(), mentions.toList())
    }

    /** The people tagged in a stored document (raw JSON, as [load] returns it). */
    fun mentionsIn(json: String?): Set<UUID> {
        val found = linkedSetOf<UUID>()

        fun walk(node: Any?) {
            val map = node as? Map<*, *> ?: return
            if (map["type"] == "mention") {
                ((map["attrs"] as? Map<*, *>)?.get("id") as? String)?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() }?.let(found::add)
            }
            (map["content"] as? List<*>)?.forEach(::walk)
        }
        if (json != null) walk(jsonMapper.readValue(json, Map::class.java))
        return found
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
        mentions: MutableSet<UUID>,
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
            "pageLink" -> {
                val id = attrs?.get("id") as? String ?: throw invalid(field)
                if (!DOCUMENT_ID.matches(id)) throw invalid(field)
                val title = attrs["title"]
                if (title != null && (title !is String || title.length > MAX_PAGE_TITLE_LENGTH)) throw invalid(field)
            }
            "embed" -> {
                val url = attrs?.get("url") as? String ?: throw invalid(field)
                val pattern = EMBED_URLS[attrs["provider"]] ?: throw invalid(field)
                if (url.length > MAX_EMBED_URL_LENGTH || !pattern.matches(url)) throw invalid(field)
            }
            "mention" -> {
                val id = attrs?.get("id") as? String ?: throw invalid(field)
                if (!DOCUMENT_ID.matches(id)) throw invalid(field)
                val label = attrs["label"] as? String ?: throw invalid(field)
                if (label.length > MAX_MENTION_LABEL_LENGTH) throw invalid(field)
                mentions += UUID.fromString(id)
                text.append('@').append(label)
                return
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
            visit(child as? Map<*, *> ?: throw invalid(field), depth + 1, text, nodes, mentions, field)
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
        DOCUMENTS("documents"),
        MILESTONES("milestones"),
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
                setOf("image", "table", "tableRow", "tableHeader", "tableCell", "pageLink", "mention", "embed")
        val MARK_TYPES = setOf("bold", "italic", "strike", "code", "underline", "link")

        /** Only images uploaded here (ImageStorage keys); an outside address could track readers. */
        val STORED_IMAGE = Regex("^/api/v1/files/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")

        /** A page block points at a document by its id; the title in it is only what to show until the real one loads. */
        val DOCUMENT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val MAX_PAGE_TITLE_LENGTH = 200

        /** A mention points at a person by id; the label is their name when they were tagged. */
        const val MAX_MENTION_LABEL_LENGTH = 100

        /**
         * Pages shown in a frame, by service; the same as `PATTERNS` in components/editor/embed.ts.
         * Outline is installed per organisation, so any host, but only its public share links.
         * Google: Docs, Sheets and Slides. Office: SharePoint and OneDrive documents, Office's own
         * embed address, and its web viewer.
         */
        val EMBED_URLS =
            mapOf(
                "figma" to Regex("""^https://(?:www\.)?figma\.com/(?:file|design|proto|board|slides|deck)/[A-Za-z0-9]+(?:[/?#]\S*)?$"""),
                "outline" to Regex("""^https?://[^/\s?#]+/s/[A-Za-z0-9_-]+(?:[/?#]\S*)?$"""),
                "google" to
                    Regex("""^https://docs\.google\.com/(?:document|spreadsheets|presentation)/d/(?:e/)?[A-Za-z0-9_-]+(?:[/?#]\S*)?$"""),
                "office" to
                    Regex(
                        """^https://(?:[a-z0-9-]+\.sharepoint\.com/(?::[wxp]:/\S+|\S*/_layouts/15/(?:Doc|Doc2|WopiFrame|guestaccess)\.aspx\?\S+)""" +
                            """|onedrive\.live\.com/embed\?\S+|view\.officeapps\.live\.com/op/(?:view|embed)\.aspx\?\S*src=\S+)$""",
                        RegexOption.IGNORE_CASE,
                    ),
            )
        const val MAX_EMBED_URL_LENGTH = 2_000
        val CELL_ALIGN = setOf(null, "left", "center", "right")
        val CELL_VERTICAL_ALIGN = setOf(null, "top", "middle", "bottom")
        const val MAX_SPAN = 100

        /** Web and mail links, and paths inside this app. Rules out `javascript:` and other schemes. */
        val SAFE_LINK = Regex("^(https?://|mailto:|/(?!/)|#)", RegexOption.IGNORE_CASE)
    }
}
