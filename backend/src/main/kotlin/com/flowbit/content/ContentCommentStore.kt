package com.flowbit.content

import com.flowbit.common.UuidV7
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.ReactionSummary
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.Resource
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A file of a comment (or one chosen for a comment not yet sent). */
data class ContentFileView(
    val id: UUID,
    val target: ContentTarget,
    val commentId: UUID?,
    val fileKey: String,
    val fileName: String,
    val sizeBytes: Long,
    val uploadedBy: UUID,
    val pending: Boolean,
    val createdAt: Instant,
)

/** A comment on a project or a document, as the page shows it. */
data class ContentCommentView(
    val id: UUID,
    val target: ContentTarget,
    val author: IssuePerson,
    /** Raw editor JSON. */
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val reactions: List<ReactionSummary> = emptyList(),
    val files: List<ContentFileView> = emptyList(),
    /** The comment this one answers; null for a comment of its own. A reply cannot be answered (D-167). */
    val parentId: UUID? = null,
    /** Deleted but kept because it has replies: no text, files or emoji (D-167). */
    val deleted: Boolean = false,
)

/** A comment after a change, with the page it is on. */
data class ContentCommentResult(
    val comment: ContentCommentView,
    val context: ContentContext,
)

/** A file ready to be sent. */
data class ContentFileDownload(
    val file: ContentFileView,
    val resource: Resource,
)

/** The two kinds of thing here an emoji goes on. */
private enum class EmojiTable(
    val table: String,
    val column: String,
) {
    COMMENT("content_comment_reactions", "comment_id"),
    ACTIVITY("content_activity_reactions", "activity_id"),
}

/** General comments, their emoji and files, and the emoji on the activity (`content_comments`, `content_files`). */
@Component
class ContentCommentStore(
    private val databaseClient: DatabaseClient,
) {
    /** Oldest first, replies among the rest. A deleted comment is listed, as deleted, while it has replies (D-167). */
    suspend fun comments(target: ContentTarget): List<ContentCommentView> =
        select(
            "c.target_type = :type and c.target_id = :id order by c.created_at, c.id",
            "(c.deleted_at is null or (c.parent_id is null and exists (select 1 from content_comments r where r.parent_id = c.id and r.deleted_at is null)))",
        ) { it.bind("type", target.type.name).bind("id", target.id) }

    /** A comment that has not been deleted. */
    suspend fun find(id: UUID): ContentCommentView? = select("c.id = :id") { it.bind("id", id) }.firstOrNull()

    /** The people whose replies to the comment are still there. */
    suspend fun replyAuthors(parentId: UUID): List<UUID> =
        databaseClient
            .sql("select distinct author_id from content_comments where parent_id = :id and deleted_at is null")
            .bind("id", parentId)
            .map { row -> checkNotNull(row.get("author_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun add(
        target: ContentTarget,
        authorId: UUID,
        document: RichTextDocument,
        now: Instant,
        parentId: UUID? = null,
    ): UUID {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into content_comments (id, target_type, target_id, author_id, content, body, created_at, updated_at, parent_id)
                values (:id, :type, :targetId, :authorId, cast(:content as jsonb), :body, :now, :now, :parentId)
                """.trimIndent(),
            ).bind("id", id)
            .bind("type", target.type.name)
            .bind("targetId", target.id)
            .bind("authorId", authorId)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", at(now))
            .let { if (parentId != null) it.bind("parentId", parentId) else it.bindNull("parentId", UUID::class.java) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    suspend fun update(
        id: UUID,
        document: RichTextDocument,
        now: Instant,
    ) {
        databaseClient
            .sql("update content_comments set content = cast(:content as jsonb), body = :body, updated_at = :now where id = :id")
            .bind("id", id)
            .bind("content", document.json)
            .bind("body", document.plainText)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(
        id: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update content_comments set deleted_at = :now where id = :id")
            .bind("id", id)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // --- emoji ----------------------------------------------------------------------------------

    suspend fun commentReactions(
        target: ContentTarget,
        userId: UUID,
    ): Map<UUID, List<ReactionSummary>> =
        summaries(EmojiTable.COMMENT, "join content_comments t on t.id = r.comment_id where t.target_type = :type and t.target_id = :id", userId) {
            it.bind("type", target.type.name).bind("id", target.id)
        }

    suspend fun activityReactions(
        target: ContentTarget,
        userId: UUID,
    ): Map<UUID, List<ReactionSummary>> =
        summaries(EmojiTable.ACTIVITY, "join content_activities t on t.id = r.activity_id where t.target_type = :type and t.target_id = :id", userId) {
            it.bind("type", target.type.name).bind("id", target.id)
        }

    suspend fun commentReactionsOf(
        commentId: UUID,
        userId: UUID,
    ): List<ReactionSummary> = reactionsOf(EmojiTable.COMMENT, commentId, userId)

    suspend fun activityReactionsOf(
        activityId: UUID,
        userId: UUID,
    ): List<ReactionSummary> = reactionsOf(EmojiTable.ACTIVITY, activityId, userId)

    suspend fun toggleCommentReaction(
        commentId: UUID,
        userId: UUID,
        emoji: String,
    ) = toggle(EmojiTable.COMMENT, commentId, userId, emoji)

    suspend fun toggleActivityReaction(
        activityId: UUID,
        userId: UUID,
        emoji: String,
    ) = toggle(EmojiTable.ACTIVITY, activityId, userId, emoji)

    // --- files ----------------------------------------------------------------------------------

    /** The files on the comments of a page, by comment, in the order they were sent. */
    suspend fun files(target: ContentTarget): Map<UUID, List<ContentFileView>> =
        filesWhere("target_type = :type and target_id = :id and comment_id is not null order by created_at, id") {
            it.bind("type", target.type.name).bind("id", target.id)
        }.groupBy { checkNotNull(it.commentId) }

    suspend fun filesOf(commentId: UUID): List<ContentFileView> = filesWhere("comment_id = :id order by created_at, id") { it.bind("id", commentId) }

    suspend fun findFile(id: UUID): ContentFileView? = filesWhere("id = :id") { it.bind("id", id) }.firstOrNull()

    suspend fun addFile(
        target: ContentTarget,
        fileKey: String,
        fileName: String,
        sizeBytes: Long,
        userId: UUID,
        now: Instant,
    ): ContentFileView {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into content_files (id, target_type, target_id, file_key, file_name, size_bytes, uploaded_by, pending, created_at)
                values (:id, :type, :targetId, :fileKey, :fileName, :sizeBytes, :userId, true, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("type", target.type.name)
            .bind("targetId", target.id)
            .bind("fileKey", fileKey)
            .bind("fileName", fileName)
            .bind("sizeBytes", sizeBytes)
            .bind("userId", userId)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return ContentFileView(id, target, null, fileKey, fileName, sizeBytes, userId, true, now)
    }

    suspend fun attachFiles(
        commentId: UUID,
        fileIds: Collection<UUID>,
    ) {
        for (id in fileIds) {
            databaseClient
                .sql("update content_files set comment_id = :commentId, pending = false where id = :id")
                .bind("commentId", commentId)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    suspend fun deleteFile(id: UUID) {
        databaseClient
            .sql("delete from content_files where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** `live` is the condition for a comment to count; by default it is not deleted. */
    private suspend fun select(
        where: String,
        live: String = "c.deleted_at is null",
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<ContentCommentView> =
        bind(
            databaseClient.sql(
                """
                select c.id, c.target_type, c.target_id, c.parent_id, c.deleted_at, c.content::text as content, c.created_at, c.updated_at,
                       u.id as author_id, u.login_id, u.display_name, u.avatar_key
                from content_comments c join users u on u.id = c.author_id
                where $live and $where
                """.trimIndent(),
            ),
        ).map { row ->
            val deleted = row.get("deleted_at", OffsetDateTime::class.java) != null
            ContentCommentView(
                id = checkNotNull(row.get("id", UUID::class.java)),
                parentId = row.get("parent_id", UUID::class.java),
                deleted = deleted,
                target =
                    ContentTarget(
                        ContentTargetType.valueOf(checkNotNull(row.get("target_type", String::class.java))),
                        checkNotNull(row.get("target_id", UUID::class.java)),
                    ),
                author =
                    IssuePerson(
                        id = checkNotNull(row.get("author_id", UUID::class.java)),
                        loginId = checkNotNull(row.get("login_id", String::class.java)),
                        displayName = row.get("display_name", String::class.java),
                        avatarKey = row.get("avatar_key", String::class.java),
                    ),
                // What a deleted comment said is not sent any more.
                content = if (deleted) EMPTY_DOCUMENT else checkNotNull(row.get("content", String::class.java)),
                createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
            )
        }.all()
            .collectList()
            .awaitSingle()

    private suspend fun filesWhere(
        where: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<ContentFileView> =
        bind(
            databaseClient.sql(
                "select id, target_type, target_id, comment_id, file_key, file_name, size_bytes, uploaded_by, pending, created_at from content_files where $where",
            ),
        ).map { row ->
            ContentFileView(
                id = checkNotNull(row.get("id", UUID::class.java)),
                target =
                    ContentTarget(
                        ContentTargetType.valueOf(checkNotNull(row.get("target_type", String::class.java))),
                        checkNotNull(row.get("target_id", UUID::class.java)),
                    ),
                commentId = row.get("comment_id", UUID::class.java),
                fileKey = checkNotNull(row.get("file_key", String::class.java)),
                fileName = checkNotNull(row.get("file_name", String::class.java)),
                sizeBytes = checkNotNull(row.get("size_bytes", Long::class.javaObjectType)),
                uploadedBy = checkNotNull(row.get("uploaded_by", UUID::class.java)),
                pending = checkNotNull(row.get("pending", Boolean::class.javaObjectType)),
                createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
            )
        }.all()
            .collectList()
            .awaitSingle()

    private suspend fun reactionsOf(
        table: EmojiTable,
        targetId: UUID,
        userId: UUID,
    ): List<ReactionSummary> = summaries(table, "where r.${table.column} = :id", userId) { it.bind("id", targetId) }[targetId].orEmpty()

    /** The reactions grouped by emoji for every row the clause (what follows the join of `r`) picks. */
    private suspend fun summaries(
        table: EmojiTable,
        clause: String,
        userId: UUID,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): Map<UUID, List<ReactionSummary>> =
        bind(
            databaseClient.sql(
                """
                select r.${table.column} as target_id, r.emoji, count(*) as n, bool_or(r.user_id = :userId) as reacted,
                       string_agg(coalesce(u.display_name, u.login_id), ', ' order by r.created_at) as names
                from ${table.table} r join users u on u.id = r.user_id
                $clause
                group by r.${table.column}, r.emoji
                order by min(r.created_at)
                """.trimIndent(),
            ),
        ).bind("userId", userId)
            .map { row ->
                checkNotNull(row.get("target_id", UUID::class.java)) to
                    ReactionSummary(
                        emoji = checkNotNull(row.get("emoji", String::class.java)),
                        count = checkNotNull(row.get("n", Long::class.javaObjectType)).toInt(),
                        reacted = checkNotNull(row.get("reacted", Boolean::class.javaObjectType)),
                        names = checkNotNull(row.get("names", String::class.java)).split(", "),
                    )
            }.all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })

    private suspend fun toggle(
        table: EmojiTable,
        targetId: UUID,
        userId: UUID,
        emoji: String,
    ) {
        val removed =
            databaseClient
                .sql("delete from ${table.table} where ${table.column} = :id and user_id = :userId and emoji = :emoji")
                .bind("id", targetId)
                .bind("userId", userId)
                .bind("emoji", emoji)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (removed > 0) return
        databaseClient
            .sql("insert into ${table.table} (${table.column}, user_id, emoji) values (:id, :userId, :emoji) on conflict do nothing")
            .bind("id", targetId)
            .bind("userId", userId)
            .bind("emoji", emoji)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val EMPTY_DOCUMENT = """{"type":"doc","content":[]}"""
    }
}
