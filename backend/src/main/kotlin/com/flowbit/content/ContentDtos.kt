package com.flowbit.content

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.issue.api.AttachmentResponse
import com.flowbit.issue.api.IssuePersonResponse
import com.flowbit.issue.application.ReactionSummary
import java.time.Instant
import java.util.UUID

data class ThreadCommentResponse(
    val id: UUID,
    val author: IssuePersonResponse,
    @get:JsonRawValue
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

data class ThreadResponse(
    val id: UUID,
    val blockId: String,
    val quote: String,
    /** The block is no longer in the page. */
    val orphaned: Boolean,
    val resolved: Boolean,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val resolvedAt: Instant?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val resolvedBy: IssuePersonResponse?,
    val createdAt: Instant,
    val createdBy: IssuePersonResponse,
    val comments: List<ThreadCommentResponse>,
) {
    companion object {
        fun from(thread: ThreadView) =
            ThreadResponse(
                id = thread.id,
                blockId = thread.blockId,
                quote = thread.quote,
                orphaned = thread.orphaned,
                resolved = thread.resolvedAt != null,
                resolvedAt = thread.resolvedAt,
                resolvedBy = thread.resolvedBy?.let(IssuePersonResponse::from),
                createdAt = thread.createdAt,
                createdBy = IssuePersonResponse.from(thread.createdBy),
                comments =
                    thread.comments.map {
                        ThreadCommentResponse(it.id, IssuePersonResponse.from(it.author), it.content, it.createdAt, it.updatedAt, it.canEdit, it.canDelete)
                    },
            )
    }
}

data class ContentActivityItemResponse(
    val id: UUID,
    val type: String,
    @get:JsonRawValue
    val detail: String,
    val actor: IssuePersonResponse,
    val createdAt: Instant,
    val reactions: List<ReactionSummary>,
)

data class ContentCommentResponse(
    val id: UUID,
    @get:JsonRawValue
    val content: String,
    val author: IssuePersonResponse,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val reactions: List<ReactionSummary>,
    val attachments: List<AttachmentResponse>,
    /** The comment this one answers; null for a comment of its own (D-167). */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val parentId: UUID?,
    /** Deleted, but kept because it has replies: no text, files or emoji (D-167). */
    val deleted: Boolean,
    val resolved: Boolean,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val resolvedAt: Instant?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val resolvedBy: IssuePersonResponse?,
) {
    companion object {
        fun from(comment: ContentCommentView) =
            ContentCommentResponse(
                id = comment.id,
                content = comment.content,
                author = IssuePersonResponse.from(comment.author),
                createdAt = comment.createdAt,
                updatedAt = comment.updatedAt,
                canEdit = comment.canEdit,
                canDelete = comment.canDelete,
                reactions = comment.reactions,
                attachments = comment.files.map(::file),
                parentId = comment.parentId,
                deleted = comment.deleted,
                resolved = comment.resolvedAt != null,
                resolvedAt = comment.resolvedAt,
                resolvedBy = comment.resolvedBy?.let(IssuePersonResponse::from),
            )

        fun file(file: ContentFileView) = AttachmentResponse(file.id, file.fileName, file.sizeBytes, "/api/v1/content-files/${file.id}", file.createdAt)
    }
}

/** Who follows a document and whether the reader does (D-166). */
data class ContentSubscriptionResponse(
    val subscribers: List<IssuePersonResponse>,
    val subscribed: Boolean,
) {
    companion object {
        fun from(subscription: ContentSubscription) =
            ContentSubscriptionResponse(subscription.subscribers.map(IssuePersonResponse::from), subscription.subscribed)
    }
}

/**
 * What a page shows under its text: the threads on its blocks and, but for an issue (which has its
 * own), the activity and comments. A document also says who follows it; the other pages leave
 * `subscription` out.
 */
data class ContentFeedResponse(
    val canComment: Boolean,
    val readOnly: Boolean,
    val threads: List<ThreadResponse>,
    val items: List<ContentActivityItemResponse>,
    val comments: List<ContentCommentResponse>,
    val subscription: ContentSubscriptionResponse? = null,
)

data class ThreadRequest(
    val blockId: String? = null,
    val content: Map<String, Any?>? = null,
)

data class ThreadCommentRequest(
    val content: Map<String, Any?>? = null,
)

/**
 * `content` may be left out when the comment is files alone; `attachmentIds` on a change is the whole set kept (left
 * out: no change). `parentId` makes a new comment a reply to that comment (D-167); a change ignores it.
 */
data class ContentCommentRequest(
    val content: Map<String, Any?>? = null,
    val attachmentIds: List<UUID>? = null,
    val parentId: UUID? = null,
)

data class ContentFeed(
    val context: ContentContext,
    val threads: List<ThreadView>,
    val items: List<ContentActivityView>,
    val comments: List<ContentCommentView>,
    val subscription: ContentSubscription? = null,
)
