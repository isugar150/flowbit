package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.ISSUE_LIST_LIMIT
import com.flowbit.issue.application.IssueCommentService
import com.flowbit.issue.application.IssueDocumentService
import com.flowbit.issue.application.IssueDuplicationService
import com.flowbit.issue.application.IssueGitService
import com.flowbit.issue.application.IssuePatch
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.application.MyIssueScope
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.media.api.UploadReader
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Issues are addressed by their number, which is unique in the workspace (FB-12 is number 12).
 * Access is decided inside IssueService for every call. Writes are announced after IssueService
 * has committed them.
 */
@RestController
@RequestMapping("/api/v1/issues")
class IssueController(
    private val issueService: IssueService,
    private val commentService: IssueCommentService,
    private val documentService: IssueDocumentService,
    private val duplicationService: IssueDuplicationService,
    private val gitService: IssueGitService,
    private val announcer: IssueAnnouncer,
    private val uploadReader: UploadReader,
) {
    /**
     * Issues the caller may see, newest first; `project` narrows the list to the ones linked to that project. `limit` is how
     * many of them to send (1000 unless asked; at most 10000): the list screens ask for one more than they show to know whether
     * there are more, and for a thousand more each time they are asked to load more (D-227).
     */
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) project: String?,
        @RequestParam(defaultValue = "false") deleted: Boolean,
        @RequestParam(defaultValue = "$ISSUE_LIST_LIMIT") limit: Int,
    ): List<IssueResponse> = issueService.list(user, project, deleted, limit).map(IssueResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: CreateIssueRequest,
    ): IssueResponse {
        val change = issueService.create(user, body.toNewIssue())
        announcer.changed(user, change)
        // A new sub-issue shows on its parent's page.
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }

    /** Picks which of its project's repositories the issue's work is in (D-148). */
    @PutMapping("/{number}/git-repository")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun setGitRepository(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: GitRepositoryRequest,
    ) {
        announcer.touched(user, gitService.setGitRepository(user, number, body.connectionId, body.repository))
    }

    @GetMapping("/{number}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueResponse = IssueResponse.from(issueService.get(user, number))

    /**
     * Only the fields present in the body change; `null` clears `dueDate`, `projectId` and
     * `parentId`, and `labelIds` and `assigneeIds` replace the whole set of labels and of assignees.
     */
    @PatchMapping("/{number}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: Map<String, Any?>,
    ): IssueResponse {
        val change = issueService.update(user, number, IssuePatch(body))
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }

    /**
     * The caller closed the issue: the notification about what they did to it goes out now instead
     * of after a minute of quiet (D-126). Only the caller's own held changes; nothing to see, so no
     * access check is needed.
     */
    @PostMapping("/{number}/notifications/flush")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun flushNotifications(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ) {
        announcer.left(user, number)
    }

    /**
     * Deletes the issue. It leaves the lists (ask for them with `deleted=true`) but can still be
     * read with its history, and restored.
     */
    @DeleteMapping("/{number}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ) {
        val change = issueService.delete(user, number)
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
    }

    /**
     * Makes a copy of the issue, without its sub-issues, and returns the copy (D-212). Needs the right
     * to edit the issue.
     */
    @PostMapping("/{number}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun duplicate(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueResponse {
        val change = duplicationService.duplicate(user, number)
        announcer.changed(user, change)
        // A copy that sits under a parent shows on the parent's page.
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }

    @PostMapping("/{number}/restore")
    suspend fun restore(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueResponse {
        val change = issueService.restore(user, number)
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }

    /** The history of the issue ("활동") and who follows it. */
    @GetMapping("/{number}/activity")
    suspend fun activity(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueActivityResponse = IssueActivityResponse.from(commentService.activity(user, number))

    /** The latest entries of the history only, newest first (`limit` 1 to 10): for the card shown over an issue on a calendar. */
    @GetMapping("/{number}/activity/recent")
    suspend fun recentActivity(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestParam(defaultValue = "3") limit: Int,
    ): List<IssueActivityItemResponse> =
        issueService.recentActivity(user, number, limit.coerceIn(1, 10)).map {
            IssueActivityItemResponse(it.id, it.type, it.detail, it.actor?.let(IssuePersonResponse::from), it.createdAt, it.reactions)
        }

    @PutMapping("/{number}/subscription")
    suspend fun subscribe(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueActivityResponse = subscription(user, number, true)

    @DeleteMapping("/{number}/subscription")
    suspend fun unsubscribe(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueActivityResponse = subscription(user, number, false)

    /** Adds the caller's emoji to the issue, or takes it back when it is already there. */
    @PostMapping("/{number}/reactions")
    suspend fun react(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val reactions = commentService.toggleReaction(user, number, body.emoji)
        announcer.touched(user, number)
        return reactions
    }

    @PostMapping("/{number}/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun attach(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestPart("file") file: FilePart,
    ): AttachmentResponse {
        val attachment = commentService.attach(user, number, file.filename(), uploadReader.read(file))
        announcer.touched(user, number)
        return AttachmentResponse.from(attachment)
    }

    /** The deleted documents of the issue; the ones in use come with the issue itself. */
    @GetMapping("/{number}/documents/deleted")
    suspend fun deletedDocuments(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): List<DocumentSummaryResponse> = documentService.deletedDocuments(user, number).map(DocumentSummaryResponse::from)

    @PostMapping("/{number}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createDocument(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = documentService.createDocument(user, number, body.title)
        announcer.touched(user, number)
        return DocumentSummaryResponse.from(document)
    }

    /**
     * Stores a file for a comment that is being written (D-164). It shows nowhere until the comment
     * is sent with its id in `attachmentIds`.
     */
    @PostMapping("/{number}/comment-files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun attachToDraft(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestPart("file") file: FilePart,
    ): AttachmentResponse = AttachmentResponse.from(commentService.attachToDraft(user, number, file.filename(), uploadReader.read(file)))

    /** Writes a comment (`content` is an editor document). It is read back with the activity. */
    @PostMapping("/{number}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun comment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: CommentRequest,
    ): CommentResponse {
        val result = commentService.addComment(user, number, body.content, body.attachmentIds.orEmpty(), body.parentId)
        announcer.commented(user, result.change)
        return CommentResponse.from(result.comment)
    }

    private suspend fun subscription(
        user: AuthenticatedUser,
        number: Int,
        subscribed: Boolean,
    ): IssueActivityResponse {
        val feed = commentService.setSubscribed(user, number, subscribed)
        announcer.touched(user, number)
        return IssueActivityResponse.from(feed)
    }
}

/**
 * The issues linked to one project, under the project's address. The same issues are reachable
 * under /api/v1/issues; this is the list a project page asks for, and creating here links the
 * new issue to the project.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/issues")
class ProjectIssueController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "false") deleted: Boolean,
        @RequestParam(defaultValue = "$ISSUE_LIST_LIMIT") limit: Int,
    ): List<IssueResponse> = issueService.list(user, projectId, deleted, limit).map(IssueResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CreateIssueRequest,
    ): IssueResponse {
        val change = issueService.create(user, body.toNewIssue(projectId = projectId))
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }
}

/** The triage queue: the issues of the projects the caller may edit that wait to be sorted (D-231). */
@RestController
@RequestMapping("/api/v1/triage")
class TriageController(
    private val issueService: IssueService,
) {
    @GetMapping
    suspend fun queue(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "false") rejected: Boolean,
    ): List<IssueResponse> = issueService.triageQueue(user, rejected).map(IssueResponse::from)
}

/** "내 티켓": the caller's issues across projects. `scope` is `assigned` (default) or `created`. */
@RestController
@RequestMapping("/api/v1/me/issues")
class MyIssuesController(
    private val issueService: IssueService,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "assigned") scope: String,
        @RequestParam(defaultValue = "false") deleted: Boolean,
        @RequestParam(defaultValue = "$ISSUE_LIST_LIMIT") limit: Int,
    ): List<IssueResponse> {
        val parsed =
            MyIssueScope.entries.firstOrNull { it.name.equals(scope, ignoreCase = true) }
                ?: throw ValidationException("scope", "INVALID", "값이 올바르지 않습니다")
        return issueService.mine(user, parsed, deleted, limit).map(IssueResponse::from)
    }
}
