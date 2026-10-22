package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.common.transaction.AfterCommit
import com.flowbit.issue.application.IssueCommentService
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.media.application.FileStorage
import com.flowbit.user.domain.UserRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * General comments under the activity of a project or a document (D-165): the same
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
    private val afterCommit: AfterCommit,
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
        // The files go once this change is committed (D-172).
        afterCommit.run { removed.forEach { fileStorage.delete(it.fileKey) } }
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
        afterCommit.run { fileStorage.delete(file.fileKey) }
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
        if (emoji !in IssueCommentService.REACTIONS) throw ValidationException("emoji", "INVALID", "쓸 수 없는 이모지입니다")
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
