package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
import com.flowbit.issue.domain.Issue
import com.flowbit.media.application.FileStorage
import com.flowbit.user.domain.UserRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * What is written and attached on an issue's page (split out of [IssueService]): its activity with who follows it,
 * the comments and their replies (D-167), the emoji (D-064, D-164), and the files, both the issue's own and the ones
 * sent with a comment.
 */
@Service
class IssueCommentService(
    private val lookup: IssueLookup,
    private val tagging: IssueTagging,
    private val queries: IssueQueries,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val comments: IssueCommentStore,
    private val richText: RichText,
    private val fileStorage: FileStorage,
    private val clock: Clock,
) {
    /** The history of an issue with who follows it. Anyone who may see the issue can read it. */
    suspend fun activity(
        user: AuthenticatedUser,
        number: Int,
    ): IssueActivityFeed {
        val access = lookup.access(user, number)
        val issue = access.issue
        val subscribers = activities.subscribers(issue.id)
        val commentReactions = extras.commentReactions(issue.id, user.id)
        val commentFiles = extras.commentAttachments(issue.id)
        val entryReactions = extras.activityReactions(issue.id, user.id)
        val shown =
            comments.list(issue.id).map {
                // A deleted comment kept for its replies shows no emoji or files (D-167).
                if (it.deleted) {
                    it
                } else {
                    it.withRights(user, access).copy(reactions = commentReactions[it.id].orEmpty(), attachments = commentFiles[it.id].orEmpty())
                }
            }
        val items = activities.list(issue.id).map { it.copy(reactions = entryReactions[it.id].orEmpty()) }
        return IssueActivityFeed(items, shown, subscribers, subscribers.any { it.id == user.id })
    }

    /**
     * Writes a comment on an issue; needs the right to edit it. The author and the people tagged
     * in it with @ follow the issue from then on, and the people tagged are told. `attachmentIds` are
     * files the caller uploaded for the comment ([attachToDraft]); with files, the text may be empty.
     *
     * With `parentId` it is a reply to that comment (D-167): one level, so the comment must be one of
     * the issue's own and not itself a reply. The author of the comment and whoever else has replied to
     * it are told as well as the people tagged, and nobody else (the subscribers are not).
     */
    @Transactional
    suspend fun addComment(
        user: AuthenticatedUser,
        number: Int,
        content: Any?,
        attachmentIds: List<UUID> = emptyList(),
        parentId: UUID? = null,
    ): CommentResult {
        val access = lookup.editable(lookup.access(user, number))
        val issue = access.issue
        val parent = parentId?.let { parentComment(issue, it) }
        val files = pendingFiles(issue, user, attachmentIds)
        val document = commentDocument(content, hasFiles = files.isNotEmpty())
        val now = clock.instant()
        val id = comments.add(issue.id, user.id, document, now, parent?.id)
        extras.attachToComment(id, files.map { it.id })
        activities.subscribe(issue.id, user.id)
        val tagged = tagging.tag(issue, document.mentions, user)
        val conversation = if (parent == null) emptyList() else inConversation(issue, parent, user)
        val comment = checkNotNull(comments.find(id)).withRights(user, access).withExtras(user)
        val change =
            IssueChange(
                checkNotNull(queries.find(number)),
                listOf(commentEvent(document, tagged, files, reply = parent != null)),
                lookup.recipients(issue.id, user),
                taggedIds = (tagged.map { it.first } + conversation).distinct(),
            )
        return CommentResult(comment, change)
    }

    /** The comment a reply goes to: one of this issue's, not deleted, and not a reply itself. */
    private suspend fun parentComment(
        issue: Issue,
        parentId: UUID,
    ): CommentView {
        val parent = comments.find(parentId)?.takeIf { it.issueId == issue.id } ?: throw commentNotFound()
        if (parent.parentId != null) throw ValidationException("parentId", "INVALID", "답글에는 답글을 달 수 없습니다")
        return parent
    }

    /** The author of the comment and the others who have replied to it, other than the person writing, who may still see the issue. */
    private suspend fun inConversation(
        issue: Issue,
        parent: CommentView,
        actor: AuthenticatedUser,
    ): List<UUID> =
        (listOf(parent.author.id) + comments.replyAuthors(parent.id))
            .distinct()
            .filter { it != actor.id && lookup.canSee(issue, it) }

    /**
     * Only its author may change a comment. People newly tagged by the change are told; nobody else is told again.
     * `attachmentIds`, when given, is the whole set of files the comment has afterwards: the ones
     * of it that are left out are removed, new ones are files the caller uploaded for it.
     */
    @Transactional
    suspend fun updateComment(
        user: AuthenticatedUser,
        commentId: UUID,
        content: Any?,
        attachmentIds: List<UUID>? = null,
    ): CommentResult {
        val existing = comments.find(commentId) ?: throw commentNotFound()
        val access = lookup.editable(lookup.accessById(user, existing.issueId))
        if (existing.author.id != user.id) throw ForbiddenException("다른 사람의 댓글은 수정할 수 없습니다")
        val current = extras.commentAttachmentsOf(commentId)
        val keptIds = attachmentIds?.distinct() ?: current.map { it.id }
        val added = pendingFiles(access.issue, user, keptIds.filter { id -> current.none { it.id == id } }, total = keptIds.size)
        val removed = current.filter { it.id !in keptIds }
        val document = commentDocument(content, hasFiles = keptIds.isNotEmpty())
        comments.update(commentId, document, clock.instant())
        extras.attachToComment(commentId, added.map { it.id })
        removed.forEach { extras.deleteAttachment(it.id) }
        removed.forEach { fileStorage.delete(it.fileKey) }
        val tagged = tagging.tag(access.issue, document.mentions - richText.mentionsIn(existing.content), user)
        val comment = checkNotNull(comments.find(commentId)).withRights(user, access).withExtras(user)
        val events = if (tagged.isEmpty()) emptyList() else listOf(commentEvent(document, tagged, comment.attachments, reply = existing.parentId != null))
        return CommentResult(
            comment,
            IssueChange(checkNotNull(queries.find(access.issue.number)), events, emptyList(), taggedIds = tagged.map { it.first }),
        )
    }

    /**
     * The files `user` uploaded for a comment on `issue` and has not sent, for the ids given.
     * `total` is how many files the comment will have in all (default: these).
     */
    private suspend fun pendingFiles(
        issue: Issue,
        user: AuthenticatedUser,
        ids: List<UUID>,
        total: Int = ids.size,
    ): List<AttachmentView> {
        if (total > MAX_COMMENT_FILES) throw ValidationException("attachmentIds", "TOO_MANY", "파일은 댓글마다 ${MAX_COMMENT_FILES}개까지 붙일 수 있습니다")
        return ids.distinct().map { id ->
            extras.findAttachment(id)?.takeIf { it.issueId == issue.id && it.pending && it.uploadedBy == user.id }
                ?: throw ValidationException("attachmentIds", "INVALID", "올린 파일을 찾을 수 없습니다. 다시 올려 주세요")
        }
    }

    /** The emoji and files of a comment, as the page shows them. */
    private suspend fun CommentView.withExtras(user: AuthenticatedUser): CommentView =
        copy(reactions = extras.commentReactionsOf(id, user.id), attachments = extras.commentAttachmentsOf(id))

    /** Its author or a workspace admin may delete a comment. Returns the number of the issue it was on. */
    @Transactional
    suspend fun removeComment(
        user: AuthenticatedUser,
        commentId: UUID,
    ): Int {
        val existing = comments.find(commentId) ?: throw commentNotFound()
        val access = lookup.editable(lookup.accessById(user, existing.issueId))
        if (existing.author.id != user.id && user.role != UserRole.ADMIN) throw ForbiddenException("다른 사람의 댓글은 지울 수 없습니다")
        comments.delete(commentId, clock.instant())
        return access.issue.number
    }

    private fun CommentView.withRights(
        user: AuthenticatedUser,
        access: IssueAccess,
    ): CommentView {
        val mine = author.id == user.id
        return copy(canEdit = access.canEdit && mine, canDelete = access.canEdit && (mine || user.role == UserRole.ADMIN))
    }

    private fun commentDocument(
        content: Any?,
        hasFiles: Boolean,
    ): RichTextDocument {
        val document = content?.let { richText.parse(it) }
        // A picture alone is a comment too, and so are files alone (D-164).
        if (document == null) return if (hasFiles) EMPTY_COMMENT else throw commentRequired()
        if (!hasFiles && document.plainText.isBlank() && "\"image\"" !in document.json) throw commentRequired()
        return document
    }

    private fun commentRequired() = ValidationException("content", "REQUIRED", "댓글 내용을 입력하세요")

    /** A comment made of files alone is announced by their names. */
    private fun commentEvent(
        document: RichTextDocument,
        tagged: List<Pair<UUID, String>>,
        files: List<AttachmentView>,
        reply: Boolean = false,
    ) = IssueEvent(
        IssueActivityType.COMMENT_ADDED,
        mapOf(
            "text" to document.plainText.ifBlank { files.joinToString(", ") { it.fileName } }.take(COMMENT_PREVIEW_LENGTH),
            "mentions" to tagged.map { it.second },
        ) + (if (reply) mapOf("reply" to true) else emptyMap()),
    )

    /** Follow or stop following an issue. Open to everyone who can see it, the view-only role included. */
    suspend fun setSubscribed(
        user: AuthenticatedUser,
        number: Int,
        subscribed: Boolean,
    ): IssueActivityFeed {
        val issue = lookup.access(user, number).issue
        if (subscribed) activities.subscribe(issue.id, user.id) else activities.unsubscribe(issue.id, user.id)
        return activity(user, number)
    }

    /** Adds the caller's emoji to the issue or takes it back. Open to everyone who can see the issue. */
    suspend fun toggleReaction(
        user: AuthenticatedUser,
        number: Int,
        emoji: String,
    ): List<ReactionSummary> {
        requireReaction(emoji)
        val issue = lookup.access(user, number).issue
        extras.toggleReaction(issue.id, user.id, emoji)
        return extras.reactions(issue.id, user.id)
    }

    /** Adds the caller's emoji to a comment or takes it back (D-164). Open to everyone who can see the issue. */
    suspend fun toggleCommentReaction(
        user: AuthenticatedUser,
        commentId: UUID,
        emoji: String,
    ): EntryReactions {
        requireReaction(emoji)
        val comment = comments.find(commentId) ?: throw commentNotFound()
        val issue = reactable(lookup.accessById(user, comment.issueId))
        extras.toggleCommentReaction(commentId, user.id, emoji)
        return EntryReactions(issue.number, extras.commentReactionsOf(commentId, user.id))
    }

    /** Adds the caller's emoji to an entry of the issue's history or takes it back (D-164). */
    suspend fun toggleActivityReaction(
        user: AuthenticatedUser,
        activityId: UUID,
        emoji: String,
    ): EntryReactions {
        requireReaction(emoji)
        val issueId = activities.issueIdOf(activityId) ?: throw NotFoundException("ACTIVITY_NOT_FOUND", "활동 기록을 찾을 수 없습니다")
        val issue = reactable(lookup.accessById(user, issueId))
        extras.toggleActivityReaction(activityId, user.id, emoji)
        return EntryReactions(issue.number, extras.activityReactionsOf(activityId, user.id))
    }

    private fun requireReaction(emoji: String) {
        if (emoji !in REACTIONS) throw ValidationException("emoji", "INVALID", "쓸 수 없는 이모지입니다")
    }

    private fun reactable(access: IssueAccess): Issue {
        if (access.issue.deletedAt != null) throw ForbiddenException("삭제된 티켓에는 이모지를 달 수 없습니다")
        return access.issue
    }

    @Transactional
    suspend fun attach(
        user: AuthenticatedUser,
        number: Int,
        rawFileName: String,
        bytes: ByteArray,
    ): AttachmentView {
        val issue = lookup.editable(lookup.access(user, number)).issue
        val fileName = fileNameOf(rawFileName)
        val now = clock.instant()
        val attachment = extras.addAttachment(issue.id, fileStorage.store(bytes), fileName, bytes.size.toLong(), user.id, now)
        activities.record(issue.id, user.id, listOf(IssueEvent(IssueActivityType.ATTACHMENT_ADDED, mapOf("to" to fileName))), now)
        return attachment
    }

    /**
     * Stores a file for a comment that is being written (D-164). It belongs to no comment and shows
     * nowhere until the comment is sent with its id; one never sent is removed after a day.
     */
    suspend fun attachToDraft(
        user: AuthenticatedUser,
        number: Int,
        rawFileName: String,
        bytes: ByteArray,
    ): AttachmentView {
        val issue = lookup.editable(lookup.access(user, number)).issue
        return extras.addAttachment(issue.id, fileStorage.store(bytes), fileNameOf(rawFileName), bytes.size.toLong(), user.id, clock.instant(), pending = true)
    }

    /** Keeps only the last path segment and no control characters: the name is shown and sent back in a header. */
    private fun fileNameOf(raw: String): String =
        raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
            .take(FILE_NAME_MAX_LENGTH)
            .ifEmpty { "file" }

    /** The file of an attachment, for someone who may see its issue. */
    suspend fun attachmentFile(
        user: AuthenticatedUser,
        attachmentId: UUID,
    ): AttachmentFile {
        val attachment = extras.findAttachment(attachmentId) ?: throw lookup.notFound()
        lookup.accessById(user, attachment.issueId)
        // A file not yet sent is its uploader's alone; one on a deleted comment goes with the comment.
        if (attachment.pending && attachment.uploadedBy != user.id) throw lookup.notFound()
        if (attachment.commentId != null && comments.find(attachment.commentId) == null) throw lookup.notFound()
        val resource = fileStorage.find(attachment.fileKey) ?: throw lookup.notFound()
        return AttachmentFile(attachment, resource)
    }

    /** Returns the number of the issue the attachment was on. */
    @Transactional
    suspend fun removeAttachment(
        user: AuthenticatedUser,
        attachmentId: UUID,
    ): Int {
        val attachment = extras.findAttachment(attachmentId) ?: throw lookup.notFound()
        if (attachment.pending) {
            // Taking back a file chosen for a comment that was never sent: nothing to record.
            if (attachment.uploadedBy != user.id) throw lookup.notFound()
            val number = lookup.accessById(user, attachment.issueId).issue.number
            extras.deleteAttachment(attachment.id)
            fileStorage.delete(attachment.fileKey)
            return number
        }
        if (attachment.commentId != null) throw ForbiddenException("댓글에 붙은 파일은 댓글을 수정해서 지우세요")
        val issue = lookup.editable(lookup.accessById(user, attachment.issueId)).issue
        extras.deleteAttachment(attachment.id)
        fileStorage.delete(attachment.fileKey)
        val event = IssueEvent(IssueActivityType.ATTACHMENT_REMOVED, mapOf("from" to attachment.fileName))
        activities.record(issue.id, user.id, listOf(event), clock.instant())
        return issue.number
    }

    private fun commentNotFound() = NotFoundException("COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다")

    companion object {
        /** The emoji an issue can be reacted to with. */
        val REACTIONS = listOf("👍", "❤️", "🎉", "😄", "👀", "🚀", "🙏", "✅")
        private const val FILE_NAME_MAX_LENGTH = 255

        /** How many files one comment can hold (D-164). */
        const val MAX_COMMENT_FILES = 10

        /** The text of a comment that is only files. */
        private val EMPTY_COMMENT = RichTextDocument("""{"type":"doc","content":[{"type":"paragraph"}]}""", "")

        /** How much of a comment a notification quotes. */
        private const val COMMENT_PREVIEW_LENGTH = 300
    }
}
