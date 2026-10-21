package com.flowbit.content

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.api.AttachmentResponse
import com.flowbit.issue.api.IssuePersonResponse
import com.flowbit.issue.api.ReactionRequest
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.media.api.UploadReader
import com.flowbit.realtime.RealtimeHub
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
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

/** Reads a page's threads, activity and comments for the person looking. */
@Service
class ContentFeedService(
    private val threads: ContentThreadService,
    private val log: ContentLog,
    private val comments: ContentCommentService,
    private val store: ContentCommentStore,
    private val subscriptions: ContentSubscriptionService,
) {
    suspend fun feed(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ContentFeed {
        val found = threads.threads(user, target)
        if (!target.type.hasActivity) return ContentFeed(found.context, found.threads, emptyList(), emptyList())
        val emoji = store.activityReactions(target, user.id)
        val items = log.list(target).map { it.copy(reactions = emoji[it.id].orEmpty()) }
        return ContentFeed(found.context, found.threads, items, comments.list(user, found.context), subscriptions.of(user, found.context))
    }
}

/**
 * Comments on the blocks of a page, and the activity and comments under it (D-165,
 * docs/features/content-comments.md). Access is decided inside the services; writes are announced
 * on the realtime hub after they are committed, so that whoever shows the page reads it again.
 */
@RestController
@RequestMapping("/api/v1")
class ContentController(
    private val feeds: ContentFeedService,
    private val threads: ContentThreadService,
    private val comments: ContentCommentService,
    private val subscriptions: ContentSubscriptionService,
    private val realtime: RealtimeHub,
    private val uploadReader: UploadReader,
) {
    @GetMapping("/content/{type}/{id}/feed")
    suspend fun feed(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
    ): ContentFeedResponse {
        val feed = feeds.feed(user, target(type, id))
        return ContentFeedResponse(
            canComment = !feed.context.readOnly,
            readOnly = feed.context.readOnly,
            threads = feed.threads.map(ThreadResponse::from),
            items = feed.items.map { ContentActivityItemResponse(it.id, it.type, it.detail, IssuePersonResponse.from(it.actor), it.createdAt, it.reactions) },
            comments = feed.comments.map(ContentCommentResponse::from),
            subscription = feed.subscription?.let(ContentSubscriptionResponse::from),
        )
    }

    // --- following a document -------------------------------------------------------------------

    @PutMapping("/content/{type}/{id}/subscription")
    suspend fun follow(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
    ): ContentSubscriptionResponse = subscribe(user, type, id, true)

    @DeleteMapping("/content/{type}/{id}/subscription")
    suspend fun unfollow(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
    ): ContentSubscriptionResponse = subscribe(user, type, id, false)

    private suspend fun subscribe(
        user: AuthenticatedUser,
        type: String,
        id: UUID,
        subscribed: Boolean,
    ): ContentSubscriptionResponse {
        val (context, subscription) = subscriptions.set(user, target(type, id), subscribed)
        publish(user, context)
        return ContentSubscriptionResponse.from(subscription)
    }

    // --- threads --------------------------------------------------------------------------------

    @PostMapping("/content/{type}/{id}/threads")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun openThread(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
        @RequestBody body: ThreadRequest,
    ): ThreadResponse {
        val blockId = body.blockId?.takeIf { it.isNotBlank() } ?: throw ValidationException("blockId", "REQUIRED", "댓글을 달 블록을 정하세요")
        return announced(user, threads.open(user, target(type, id), blockId, body.content))
    }

    @PostMapping("/content-threads/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun reply(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ThreadCommentRequest,
    ): ThreadResponse = announced(user, threads.reply(user, id, body.content))

    @PatchMapping("/content-thread-comments/{id}")
    suspend fun editThreadComment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ThreadCommentRequest,
    ): ThreadResponse = announced(user, threads.edit(user, id, body.content))

    @DeleteMapping("/content-thread-comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteThreadComment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        val result = threads.delete(user, id)
        publish(user, result.context)
    }

    @PostMapping("/content-threads/{id}/resolution")
    suspend fun resolve(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ThreadResponse = announced(user, threads.setResolved(user, id, true))

    @DeleteMapping("/content-threads/{id}/resolution")
    suspend fun reopen(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ThreadResponse = announced(user, threads.setResolved(user, id, false))

    // --- general comments -----------------------------------------------------------------------

    @PostMapping("/content/{type}/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun comment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
        @RequestBody body: ContentCommentRequest,
    ): ContentCommentResponse {
        val result = comments.add(user, target(type, id), body.content, body.attachmentIds.orEmpty(), body.parentId)
        publish(user, result.context)
        return ContentCommentResponse.from(result.comment)
    }

    @PatchMapping("/content-comments/{id}")
    suspend fun editComment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ContentCommentRequest,
    ): ContentCommentResponse {
        val result = comments.update(user, id, body.content, body.attachmentIds)
        publish(user, result.context)
        return ContentCommentResponse.from(result.comment)
    }

    @DeleteMapping("/content-comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteComment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        publish(user, comments.delete(user, id))
    }

    @PostMapping("/content-comments/{id}/reactions")
    suspend fun reactToComment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val (context, reactions) = comments.toggleCommentReaction(user, id, body.emoji)
        publish(user, context)
        return reactions
    }

    @PostMapping("/content-activities/{id}/reactions")
    suspend fun reactToActivity(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val (context, reactions) = comments.toggleActivityReaction(user, id, body.emoji)
        publish(user, context)
        return reactions
    }

    @PostMapping("/content/{type}/{id}/comment-files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun uploadFile(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
        @RequestPart("file") file: FilePart,
    ): AttachmentResponse = ContentCommentResponse.file(comments.upload(user, target(type, id), file.filename(), uploadReader.read(file)))

    @GetMapping("/content-files/{id}")
    suspend fun download(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ResponseEntity<Resource> {
        val download = comments.download(user, id)
        val disposition = ContentDisposition.attachment().filename(download.file.fileName, StandardCharsets.UTF_8).build()
        return ResponseEntity
            .ok()
            // Always a download of opaque bytes: an uploaded page or script must never run from this origin.
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(download.resource)
    }

    @DeleteMapping("/content-files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteFile(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        publish(user, comments.removeFile(user, id))
    }

    private fun target(
        type: String,
        id: UUID,
    ): ContentTarget = ContentTarget(ContentTargetType.ofPath(type) ?: throw NotFoundException("CONTENT_NOT_FOUND", "찾을 수 없습니다"), id)

    private fun announced(
        user: AuthenticatedUser,
        result: ThreadResult,
    ): ThreadResponse {
        publish(user, result.context)
        return ThreadResponse.from(result.thread)
    }

    private fun publish(
        user: AuthenticatedUser,
        context: ContentContext,
    ) = context.topics.forEach { realtime.publish(it, user.id) }
}
