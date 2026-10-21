package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.media.application.FileStorage
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.Resource
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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

/** A comment on a project, a document or a milestone, as the page shows it. */
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

/**
 * General comments under the activity of a project, a document or a milestone (D-165): the same
 * as an issue's, with emoji and files, but open to everyone who can see the page. An issue keeps
 * its own comments. A deleted document can only be read.
 */
@Service
class ContentCommentService(
    private val access: ContentAccess,
    private val store: ContentCommentStore,
    private val log: ContentLog,
    private val directory: ContentDirectory,
    private val subscriptions: DocumentSubscriptionStore,
    private val richText: RichText,
    private val fileStorage: FileStorage,
    private val clock: Clock,
) {
    /** The comments of a page for the person looking, with their emoji and files. */
    suspend fun list(
        user: AuthenticatedUser,
        context: ContentContext,
    ): List<ContentCommentView> {
        val target = context.target
        val emoji = store.commentReactions(target, user.id)
        val files = store.files(target)
        return store.comments(target).map {
            // A deleted comment kept for its replies shows no emoji or files (D-167).
            if (it.deleted) return@map it
            val mine = it.author.id == user.id
            it.copy(
                canEdit = mine && !context.readOnly,
                canDelete = (mine || user.role == UserRole.ADMIN) && !context.readOnly,
                reactions = emoji[it.id].orEmpty(),
                files = files[it.id].orEmpty(),
            )
        }
    }

    /**
     * With `parentId` it is a reply to that comment (D-167): one level, so the comment must be on this
     * page and not itself a reply. The author of the comment and whoever else has replied to it are told
     * as well as the people tagged in the reply, and nobody else (the followers are not).
     */
    @Transactional
    suspend fun add(
        user: AuthenticatedUser,
        target: ContentTarget,
        rawContent: Any?,
        fileIds: List<UUID>,
        parentId: UUID? = null,
    ): ContentCommentResult {
        val context = writable(user, target)
        val parent = parentId?.let { parentComment(target, it) }
        val files = pendingFiles(context, user, fileIds)
        val document = commentDocument(rawContent, hasFiles = files.isNotEmpty())
        val now = clock.instant()
        val id = store.add(target, user.id, document, now, parent?.id)
        store.attachFiles(id, files.map { it.id })
        val tagged = taggedPeople(context, document.mentions, user.id)
        val conversation = if (parent == null) emptyList() else inConversation(context, parent, user.id)
        val text = document.plainText.ifBlank { files.joinToString(", ") { it.fileName } }.take(PREVIEW_LENGTH)
        val names = tagged.map { directory.displayName(it) }
        followTagged(target, tagged)
        val detail = mapOf("text" to text, "mentions" to names) + (if (parent != null) mapOf("reply" to true) else emptyMap())
        log.announce(target, user.id, listOf(ContentEvent(ContentEventType.COMMENT_ADDED, detail)), (tagged + conversation).distinct())
        return ContentCommentResult(shown(user, context, id), context)
    }

    /** The comment a reply goes to: on this page, not deleted, and not a reply itself. */
    private suspend fun parentComment(
        target: ContentTarget,
        parentId: UUID,
    ): ContentCommentView {
        val parent = store.find(parentId)?.takeIf { it.target == target } ?: throw commentNotFound()
        if (parent.parentId != null) throw ValidationException("parentId", "INVALID", "답글에는 답글을 달 수 없습니다")
        return parent
    }

    /** The author of the comment and the others who have replied to it, other than the person writing, who can still open the page. */
    private suspend fun inConversation(
        context: ContentContext,
        parent: ContentCommentView,
        actorId: UUID,
    ): List<UUID> =
        (listOf(parent.author.id) + store.replyAuthors(parent.id))
            .distinct()
            .filter { it != actorId && directory.canReach(context, it) }

    /** `fileIds`, when given, is the whole set of files the comment keeps; only its author may change it. */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        commentId: UUID,
        rawContent: Any?,
        fileIds: List<UUID>?,
    ): ContentCommentResult {
        val existing = store.find(commentId) ?: throw commentNotFound()
        val context = writable(user, existing.target)
        if (existing.author.id != user.id) throw ForbiddenException("다른 사람의 댓글은 수정할 수 없습니다")
        val current = store.filesOf(commentId)
        val keptIds = fileIds?.distinct() ?: current.map { it.id }
        val added = pendingFiles(context, user, keptIds.filter { id -> current.none { it.id == id } }, total = keptIds.size)
        val removed = current.filter { it.id !in keptIds }
        val document = commentDocument(rawContent, hasFiles = keptIds.isNotEmpty())
        store.update(commentId, document, clock.instant())
        store.attachFiles(commentId, added.map { it.id })
        removed.forEach { store.deleteFile(it.id) }
        removed.forEach { fileStorage.delete(it.fileKey) }
        // Only the people newly tagged by the change are told.
        val tagged = taggedPeople(context, document.mentions - richText.mentionsIn(existing.content), user.id)
        if (tagged.isNotEmpty()) {
            val detail =
                mapOf("text" to document.plainText.take(PREVIEW_LENGTH), "mentions" to tagged.map { directory.displayName(it) }) +
                    (if (existing.parentId != null) mapOf("reply" to true) else emptyMap())
            followTagged(existing.target, tagged)
            log.announce(existing.target, user.id, listOf(ContentEvent(ContentEventType.COMMENT_ADDED, detail)), tagged)
        }
        return ContentCommentResult(shown(user, context, commentId), context)
    }

    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        commentId: UUID,
    ): ContentContext {
        val existing = store.find(commentId) ?: throw commentNotFound()
        val context = writable(user, existing.target)
        if (existing.author.id != user.id && user.role != UserRole.ADMIN) throw ForbiddenException("다른 사람의 댓글은 지울 수 없습니다")
        store.delete(commentId, clock.instant())
        return context
    }

    /** Adds the caller's emoji to a comment or takes it back; anyone who may see the page. */
    suspend fun toggleCommentReaction(
        user: AuthenticatedUser,
        commentId: UUID,
        emoji: String,
    ): Pair<ContentContext, List<ReactionSummary>> {
        requireEmoji(emoji)
        val comment = store.find(commentId) ?: throw commentNotFound()
        val context = writable(user, comment.target)
        store.toggleCommentReaction(commentId, user.id, emoji)
        return context to store.commentReactionsOf(commentId, user.id)
    }

    suspend fun toggleActivityReaction(
        user: AuthenticatedUser,
        activityId: UUID,
        emoji: String,
    ): Pair<ContentContext, List<ReactionSummary>> {
        requireEmoji(emoji)
        val target = log.targetOf(activityId) ?: throw NotFoundException("ACTIVITY_NOT_FOUND", "활동 기록을 찾을 수 없습니다")
        val context = writable(user, target)
        store.toggleActivityReaction(activityId, user.id, emoji)
        return context to store.activityReactionsOf(activityId, user.id)
    }

    /** Stores a file for a comment being written; it is the uploader's alone until the comment is sent. */
    suspend fun upload(
        user: AuthenticatedUser,
        target: ContentTarget,
        rawFileName: String,
        bytes: ByteArray,
    ): ContentFileView {
        writable(user, target)
        val fileName =
            rawFileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim()
                .take(FILE_NAME_MAX_LENGTH)
                .ifEmpty { "file" }
        return store.addFile(target, fileStorage.store(bytes), fileName, bytes.size.toLong(), user.id, clock.instant())
    }

    /** The file, for someone who may see its page; one not yet sent only for who uploaded it, one on a deleted comment for nobody. */
    suspend fun download(
        user: AuthenticatedUser,
        fileId: UUID,
    ): ContentFileDownload {
        val file = store.findFile(fileId) ?: throw fileNotFound()
        access.resolve(user, file.target)
        if (file.pending && file.uploadedBy != user.id) throw fileNotFound()
        if (file.commentId != null && store.find(file.commentId) == null) throw fileNotFound()
        val resource = fileStorage.find(file.fileKey) ?: throw fileNotFound()
        return ContentFileDownload(file, resource)
    }

    /** Takes back a file chosen for a comment that was never sent. A file on a comment goes by changing the comment. */
    @Transactional
    suspend fun removeFile(
        user: AuthenticatedUser,
        fileId: UUID,
    ): ContentContext {
        val file = store.findFile(fileId) ?: throw fileNotFound()
        val context = access.resolve(user, file.target)
        if (!file.pending || file.uploadedBy != user.id) {
            if (file.pending) throw fileNotFound()
            throw ForbiddenException("댓글에 붙은 파일은 댓글을 수정해서 지우세요")
        }
        store.deleteFile(file.id)
        fileStorage.delete(file.fileKey)
        return context
    }

    private suspend fun writable(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ContentContext {
        // An issue keeps its own comments (`issue_comments`).
        if (!target.type.hasActivity) throw NotFoundException("CONTENT_NOT_FOUND", "찾을 수 없습니다")
        val context = access.resolve(user, target)
        if (context.readOnly) throw ForbiddenException("삭제된 문서에는 댓글을 달 수 없습니다")
        return context
    }

    private suspend fun shown(
        user: AuthenticatedUser,
        context: ContentContext,
        commentId: UUID,
    ): ContentCommentView {
        val comment = checkNotNull(store.find(commentId))
        val mine = comment.author.id == user.id
        return comment.copy(
            canEdit = mine && !context.readOnly,
            canDelete = (mine || user.role == UserRole.ADMIN) && !context.readOnly,
            reactions = store.commentReactionsOf(commentId, user.id),
            files = store.filesOf(commentId),
        )
    }

    /** The files `user` uploaded for this page and has not sent, for the ids given; `total` is how many the comment will have in all. */
    private suspend fun pendingFiles(
        context: ContentContext,
        user: AuthenticatedUser,
        ids: List<UUID>,
        total: Int = ids.size,
    ): List<ContentFileView> {
        if (total > MAX_FILES) throw ValidationException("attachmentIds", "TOO_MANY", "파일은 댓글마다 ${MAX_FILES}개까지 붙일 수 있습니다")
        return ids.distinct().map { id ->
            store.findFile(id)?.takeIf { it.target == context.target && it.pending && it.uploadedBy == user.id }
                ?: throw ValidationException("attachmentIds", "INVALID", "올린 파일을 찾을 수 없습니다. 다시 올려 주세요")
        }
    }

    private fun commentDocument(
        raw: Any?,
        hasFiles: Boolean,
    ): RichTextDocument {
        val document = raw?.let { richText.parse(it) }
        // A picture alone is a comment too, and so are files alone.
        if (document == null) return if (hasFiles) EMPTY_COMMENT else throw required()
        if (!hasFiles && document.plainText.isBlank() && "\"image\"" !in document.json) throw required()
        return document
    }

    private suspend fun taggedPeople(
        context: ContentContext,
        mentions: Collection<UUID>,
        actorId: UUID,
    ): List<UUID> = mentions.filter { it != actorId && directory.canReach(context, it) }

    /** Being tagged in a comment on a document makes the person follow it; they can stop (D-166). */
    private suspend fun followTagged(
        target: ContentTarget,
        tagged: Collection<UUID>,
    ) {
        if (target.type == ContentTargetType.DOCUMENT) tagged.forEach { subscriptions.subscribe(target.id, it) }
    }

    private fun requireEmoji(emoji: String) {
        if (emoji !in IssueService.REACTIONS) throw ValidationException("emoji", "INVALID", "쓸 수 없는 이모지입니다")
    }

    private fun required() = ValidationException("content", "REQUIRED", "댓글 내용을 입력하세요")

    private fun commentNotFound() = NotFoundException("COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다")

    private fun fileNotFound() = NotFoundException("FILE_NOT_FOUND", "파일을 찾을 수 없습니다")

    companion object {
        const val MAX_FILES = 10
        private const val PREVIEW_LENGTH = 300
        private const val FILE_NAME_MAX_LENGTH = 255
        private val EMPTY_COMMENT = RichTextDocument("""{"type":"doc","content":[{"type":"paragraph"}]}""", "")
    }
}
