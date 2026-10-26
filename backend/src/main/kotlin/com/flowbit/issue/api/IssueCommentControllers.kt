package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.application.IssueCommentService
import com.flowbit.issue.application.ReactionSummary
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Files attached to issues. Whoever may see the issue may download; changing needs the right to edit it. */
@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController(
    private val commentService: IssueCommentService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping("/{id}")
    suspend fun download(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ResponseEntity<Resource> {
        val file = commentService.attachmentFile(user, id)
        val disposition = ContentDisposition.attachment().filename(file.attachment.fileName, StandardCharsets.UTF_8).build()
        return ResponseEntity
            .ok()
            // Always a download of opaque bytes: an uploaded page or script must never run from this origin.
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(file.resource)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.touched(user, commentService.removeAttachment(user, id))
    }
}

/** Comments on issues. Its author changes a comment; its author or a workspace admin deletes it. */
@RestController
@RequestMapping("/api/v1/comments")
class CommentController(
    private val commentService: IssueCommentService,
    private val announcer: IssueAnnouncer,
) {
    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: CommentRequest,
    ): CommentResponse {
        val result = commentService.updateComment(user, id, body.content, body.attachmentIds)
        announcer.commented(user, result.change)
        return CommentResponse.from(result.comment)
    }

    /** Adds the caller's emoji to the comment, or takes it back when it is already there (D-164). */
    @PostMapping("/{id}/reactions")
    suspend fun react(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val result = commentService.toggleCommentReaction(user, id, body.emoji)
        announcer.touched(user, result.issueNumber)
        return result.reactions
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.commentsChanged(user, commentService.removeComment(user, id))
    }
}

/** The entries of an issue's history ("활동"). Anyone who can see the issue can put an emoji on one. */
@RestController
@RequestMapping("/api/v1/activities")
class ActivityController(
    private val commentService: IssueCommentService,
    private val announcer: IssueAnnouncer,
) {
    @PostMapping("/{id}/reactions")
    suspend fun react(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val result = commentService.toggleActivityReaction(user, id, body.emoji)
        announcer.touched(user, result.issueNumber)
        return result.reactions
    }
}
