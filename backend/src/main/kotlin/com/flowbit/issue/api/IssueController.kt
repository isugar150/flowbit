package com.flowbit.issue.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.AttachmentView
import com.flowbit.issue.application.CommentView
import com.flowbit.issue.application.DocumentDetail
import com.flowbit.issue.application.DocumentOwner
import com.flowbit.issue.application.DocumentSummary
import com.flowbit.issue.application.IssueActivityFeed
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueDetail
import com.flowbit.issue.application.IssueGitLinkView
import com.flowbit.issue.application.IssueGitRepositoryView
import com.flowbit.issue.application.IssueLabel
import com.flowbit.issue.application.IssueMilestone
import com.flowbit.issue.application.IssueParent
import com.flowbit.issue.application.IssuePatch
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.IssueProject
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.application.IssueView
import com.flowbit.issue.application.MyIssueScope
import com.flowbit.issue.application.NewIssue
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.media.api.UploadReader
import com.flowbit.media.application.ImageStorage
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationDispatcher
import com.flowbit.notification.NotificationMerge
import com.flowbit.realtime.RealtimeHub
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
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
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IssuePersonResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        fun from(person: IssuePerson) = IssuePersonResponse(person.id, person.loginId, person.displayName, ImageStorage.urlOf(person.avatarKey))
    }
}

data class AttachmentResponse(
    val id: UUID,
    val fileName: String,
    val sizeBytes: Long,
    /** Where to download the file. */
    val url: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(attachment: AttachmentView) =
            AttachmentResponse(
                attachment.id,
                attachment.fileName,
                attachment.sizeBytes,
                "/api/v1/attachments/${attachment.id}",
                attachment.createdAt,
            )
    }
}

data class DocumentSummaryResponse(
    val id: UUID,
    val title: String,
    val updatedAt: Instant,
    /** When it was deleted; null for a document in use. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val deletedAt: Instant?,
) {
    companion object {
        fun from(document: DocumentSummary) = DocumentSummaryResponse(document.id, document.title, document.updatedAt, document.deletedAt)
    }
}

/**
 * An issue. The last fields (from `canEdit`) are present only when one issue is requested (GET /issues/{number});
 * lists leave them out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IssueResponse(
    val id: UUID,
    val key: String,
    val number: Int,
    val title: String,
    val description: String,
    /** The body as editor JSON; present only when one issue is requested. */
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
    val status: IssueStatus,
    val priority: IssuePriority,
    /** In the order they were given the issue; empty when no one has it. */
    val assignees: List<IssuePersonResponse>,
    /** The first day when the issue covers a span of days that ends on `dueDate`; null for a single date. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val startDate: LocalDate?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val dueDate: LocalDate?,
    /** The project the issue is linked to; null when it is on its own. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val project: IssueProject?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val milestone: IssueMilestone?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val parent: IssueParent?,
    /** Every issue above this one, the top-level one first and `parent` last. */
    val ancestors: List<IssueParent>,
    val labels: List<IssueLabel>,
    /** How many sub-issues the issue has, and how many of them are done; for the progress shown in lists. */
    val subIssueCount: Int,
    val subIssueDoneCount: Int,
    val createdBy: IssuePersonResponse,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When the issue was deleted; null for an issue in use. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val deletedAt: Instant?,
    /** When a done or canceled issue was finished; null while it is open. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val completedAt: Instant?,
    val canEdit: Boolean? = null,
    /** Whether the caller may restore the issue; only on a deleted one. */
    val canRestore: Boolean? = null,
    /** Whether a sub-issue may be made under it; false at the deepest level (10 below a top-level issue). */
    val canAddSubIssue: Boolean? = null,
    val subIssues: List<IssueResponse>? = null,
    val reactions: List<ReactionSummary>? = null,
    val attachments: List<AttachmentResponse>? = null,
    val documents: List<DocumentSummaryResponse>? = null,
    /** Branches and pull requests a Git server linked the issue to; empty when none. */
    val git: List<IssueGitLinkView>? = null,
    /** The repository picked on the issue, or null (D-148). */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val gitRepository: IssueGitRepositoryView? = null,
    /** The project's repositories it can be picked from; empty for an issue on its own. */
    val gitRepositoryOptions: List<IssueGitRepositoryView>? = null,
) {
    companion object {
        fun from(issue: IssueView) =
            IssueResponse(
                id = issue.id,
                key = issue.key,
                number = issue.number,
                title = issue.title,
                description = issue.description,
                content = issue.content,
                status = issue.status,
                priority = issue.priority,
                assignees = issue.assignees.map(IssuePersonResponse::from),
                startDate = issue.startDate,
                dueDate = issue.dueDate,
                project = issue.project,
                milestone = issue.milestone,
                parent = issue.parent,
                ancestors = issue.ancestors,
                labels = issue.labels,
                subIssueCount = issue.subIssueCount,
                subIssueDoneCount = issue.subIssueDoneCount,
                createdBy = IssuePersonResponse.from(issue.createdBy),
                version = issue.version,
                createdAt = issue.createdAt,
                updatedAt = issue.updatedAt,
                deletedAt = issue.deletedAt,
                completedAt = issue.completedAt,
            )

        fun from(detail: IssueDetail) =
            from(detail.issue).copy(
                canEdit = detail.canEdit,
                canRestore = detail.canRestore.takeIf { detail.issue.deletedAt != null },
                canAddSubIssue = detail.canAddSubIssue,
                subIssues = detail.subIssues.map(::from),
                reactions = detail.reactions,
                attachments = detail.attachments.map(AttachmentResponse::from),
                documents = detail.documents.map(DocumentSummaryResponse::from),
                git = detail.git,
                gitRepository = detail.gitRepository,
                gitRepositoryOptions = detail.gitRepositoryOptions,
            )
    }
}

data class IssueActivityItemResponse(
    val id: UUID,
    val type: IssueActivityType,
    /** Old and new values, depending on the type: `{from, to}` or `{added, removed}`. */
    @get:JsonRawValue
    val detail: String,
    /** Null when a Git server made the change for someone without an account; `detail.via.name` names them. */
    val actor: IssuePersonResponse?,
    val createdAt: Instant,
    val reactions: List<ReactionSummary>,
)

data class CommentResponse(
    val id: UUID,
    @get:JsonRawValue
    val content: String,
    val author: IssuePersonResponse,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val reactions: List<ReactionSummary>,
    /** The files sent with the comment. */
    val attachments: List<AttachmentResponse>,
) {
    companion object {
        fun from(comment: CommentView) =
            CommentResponse(
                comment.id,
                comment.content,
                IssuePersonResponse.from(comment.author),
                comment.createdAt,
                comment.updatedAt,
                comment.canEdit,
                comment.canDelete,
                comment.reactions,
                comment.attachments.map(AttachmentResponse::from),
            )
    }
}

/**
 * `content` may be left out when the comment is made of files alone. `attachmentIds` are files
 * uploaded for it beforehand; on a change they are the whole set the comment keeps (left out: no change).
 */
data class CommentRequest(
    val content: Map<String, Any?>? = null,
    val attachmentIds: List<UUID>? = null,
)

data class IssueActivityResponse(
    val items: List<IssueActivityItemResponse>,
    val comments: List<CommentResponse>,
    val subscribers: List<IssuePersonResponse>,
    val subscribed: Boolean,
) {
    companion object {
        fun from(feed: IssueActivityFeed) =
            IssueActivityResponse(
                items =
                    feed.items.map {
                        IssueActivityItemResponse(it.id, it.type, it.detail, it.actor?.let(IssuePersonResponse::from), it.createdAt, it.reactions)
                    },
                comments = feed.comments.map(CommentResponse::from),
                subscribers = feed.subscribers.map(IssuePersonResponse::from),
                subscribed = feed.subscribed,
            )
    }
}

data class DocumentResponse(
    val id: UUID,
    val title: String,
    @get:JsonRawValue
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val content: String?,
    val updatedAt: Instant,
    /** The issue the document belongs to, or null when it belongs to a project. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val issue: IssueParent?,
    /** The project the document belongs to, or null when it belongs to an issue. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val project: IssueProject?,
    val canEdit: Boolean,
    /** The documents it sits inside, the outermost first. */
    val ancestors: List<DocumentSummaryResponse>,
    /** The documents inside it (page blocks in its body). */
    val children: List<DocumentSummaryResponse>,
    val canAddChild: Boolean,
    /** When it was deleted; a deleted document is read-only until restored. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val deletedAt: Instant?,
    val canRestore: Boolean,
) {
    companion object {
        fun from(detail: DocumentDetail) =
            DocumentResponse(
                id = detail.document.id,
                title = detail.document.title,
                content = detail.content,
                updatedAt = detail.document.updatedAt,
                issue = detail.issue?.let { IssueParent(it.number, it.key, it.title) },
                project = detail.project,
                canEdit = detail.canEdit,
                ancestors = detail.ancestors.map(DocumentSummaryResponse::from),
                children = detail.children.map(DocumentSummaryResponse::from),
                canAddChild = detail.canAddChild,
                deletedAt = detail.document.deletedAt,
                canRestore = detail.canRestore,
            )
    }
}

data class CreateIssueRequest(
    val title: String,
    val description: String? = null,
    val content: Map<String, Any?>? = null,
    val status: IssueStatus? = null,
    val priority: IssuePriority? = null,
    /** The people to give the issue to, in order. Left out, a sub-issue starts with its parent's (D-129). */
    val assigneeIds: List<UUID>? = null,
    val dueDate: LocalDate? = null,
    val milestoneId: UUID? = null,
    val labelIds: List<UUID>? = null,
    /** Links the issue to this project; without it the issue is on its own. */
    val projectId: String? = null,
    /** Makes the issue a sub-issue of this one. */
    val parentId: UUID? = null,
    /** With `dueDate`, makes the issue cover a span of days. */
    val startDate: LocalDate? = null,
) {
    fun toNewIssue(projectId: String? = this.projectId) =
        NewIssue(
            title,
            description,
            content,
            status,
            priority,
            assigneeIds,
            dueDate,
            milestoneId,
            labelIds.orEmpty(),
            projectId,
            parentId,
            startDate,
        )
}

/** Both null takes the pick away. */
data class GitRepositoryRequest(
    val connectionId: UUID? = null,
    val repository: String? = null,
)

data class ReactionRequest(
    val emoji: String,
)

data class CreateDocumentRequest(
    val title: String? = null,
)

/** Tells whoever shows an issue, or a list it is in, that it changed; and whoever follows it or its project through the notification channels. */
@Component
class IssueAnnouncer(
    private val issueService: IssueService,
    private val realtime: RealtimeHub,
    private val notifications: NotificationDispatcher,
) {
    /** Call after the change is committed. */
    suspend fun changed(
        user: AuthenticatedUser,
        change: IssueChange,
    ) = changed(user.id, change) { issueService.actorName(user) }

    /**
     * What one call from a Git server did (D-141), in order. Each issue is told once and at once
     * (D-155): its links, commits and status changes go in one notification, from whoever did the
     * last of them.
     */
    suspend fun changedFromGit(changes: List<Pair<Actor, IssueChange>>) {
        for (group in changes.groupBy { it.second.issue.number }.values) {
            val (actor, change) = group.last()
            val actorId = actor.userId ?: RealtimeHub.NOBODY
            realtime.publish(RealtimeHub.ISSUES, actorId)
            group.flatMap { it.second.projectIds }.distinct().forEach { realtime.publish("project:$it", actorId) }
            touched(actorId, change.issue.number)
            val notification =
                group
                    .map { (by, it) -> notificationOf(by.userId, it, by.name) }
                    .reduce(NotificationMerge::merge)
                    .let { it.copy(recipientIds = it.recipientIds.filter { id -> id != actor.userId }) }
            if (notification.recipientIds.isNotEmpty()) notifications.dispatchNow(notification)
        }
    }

    private fun notificationOf(
        actorId: UUID?,
        change: IssueChange,
        actorName: String,
    ) = IssueNotification(
        issueKey = change.issue.key,
        issueNumber = change.issue.number,
        issueTitle = change.issue.title,
        projectId = change.issue.project?.id,
        actorId = actorId,
        actorName = actorName,
        events = change.events.map { IssueNotification.Event(it.type.name, it.detail) },
        recipientIds = change.recipientIds,
    )

    private suspend fun changed(
        actorId: UUID?,
        change: IssueChange,
        actorName: suspend () -> String,
    ) {
        realtime.publish(RealtimeHub.ISSUES, actorId ?: RealtimeHub.NOBODY)
        change.projectIds.forEach { realtime.publish("project:$it", actorId ?: RealtimeHub.NOBODY) }
        commented(actorId, change, actorName)
    }

    /** A comment was written or changed: the issue's page reads again, and whoever `change` names is told. */
    suspend fun commented(
        user: AuthenticatedUser,
        change: IssueChange,
    ) = commented(user.id, change) { issueService.actorName(user) }

    private suspend fun commented(
        actorId: UUID?,
        change: IssueChange,
        actorName: suspend () -> String,
    ) {
        touched(actorId ?: RealtimeHub.NOBODY, change.issue.number)
        // Every channel only reaches the people who follow the issue or its project.
        if (change.events.isEmpty() || change.recipientIds.isEmpty()) return
        notifications.dispatch(notificationOf(actorId, change, actorName()))
    }

    /** The person left the issue (closed it): what they did to it and is still held goes out now (D-126). */
    fun left(
        user: AuthenticatedUser,
        number: Int,
    ) {
        notifications.flush(number, user.id)
    }

    /** A document changed; whoever shows the issue or the project it belongs to should read again. */
    fun documentChanged(
        user: AuthenticatedUser,
        owner: DocumentOwner,
    ) {
        owner.issueNumber?.let { touched(user, it) }
        owner.projectId?.let { realtime.publish("project:$it", user.id) }
    }

    /** Something on the issue's own page changed (a reaction, a file, a document, who follows it). */
    fun touched(
        user: AuthenticatedUser,
        number: Int,
    ) = touched(user.id, number)

    /** As above, for a change no signed-in person made: the links a Git server added, say. */
    fun touched(
        actorId: UUID,
        number: Int,
    ) {
        realtime.publish("issue:$number", actorId)
    }
}

/**
 * Issues are addressed by their number, which is unique in the workspace (FB-12 is number 12).
 * Access is decided inside IssueService for every call. Writes are announced after IssueService
 * has committed them.
 */
@RestController
@RequestMapping("/api/v1/issues")
class IssueController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
    private val uploadReader: UploadReader,
) {
    /** Issues the caller may see; `project` narrows the list to the ones linked to that project. */
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) project: String?,
        @RequestParam(defaultValue = "false") deleted: Boolean,
    ): List<IssueResponse> = issueService.list(user, project, deleted).map(IssueResponse::from)

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
        announcer.touched(user, issueService.setGitRepository(user, number, body.connectionId, body.repository))
    }

    @GetMapping("/{number}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueResponse = IssueResponse.from(issueService.get(user, number))

    /**
     * Only the fields present in the body change; `null` clears `dueDate`, `milestoneId`,
     * `projectId` and `parentId`, and `labelIds` and `assigneeIds` replace the whole set of labels
     * and of assignees.
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
    ): IssueActivityResponse = IssueActivityResponse.from(issueService.activity(user, number))

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
        val reactions = issueService.toggleReaction(user, number, body.emoji)
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
        val attachment = issueService.attach(user, number, file.filename(), uploadReader.read(file))
        announcer.touched(user, number)
        return AttachmentResponse.from(attachment)
    }

    /** The deleted documents of the issue; the ones in use come with the issue itself. */
    @GetMapping("/{number}/documents/deleted")
    suspend fun deletedDocuments(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): List<DocumentSummaryResponse> = issueService.deletedDocuments(user, number).map(DocumentSummaryResponse::from)

    @PostMapping("/{number}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createDocument(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = issueService.createDocument(user, number, body.title)
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
    ): AttachmentResponse = AttachmentResponse.from(issueService.attachToDraft(user, number, file.filename(), uploadReader.read(file)))

    /** Writes a comment (`content` is an editor document). It is read back with the activity. */
    @PostMapping("/{number}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun comment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: CommentRequest,
    ): CommentResponse {
        val result = issueService.addComment(user, number, body.content, body.attachmentIds.orEmpty())
        announcer.commented(user, result.change)
        return CommentResponse.from(result.comment)
    }

    private suspend fun subscription(
        user: AuthenticatedUser,
        number: Int,
        subscribed: Boolean,
    ): IssueActivityResponse {
        val feed = issueService.setSubscribed(user, number, subscribed)
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
    ): List<IssueResponse> = issueService.list(user, projectId, deleted).map(IssueResponse::from)

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

/** The documents of a project, made from its overview. They are read and changed under /api/v1/documents. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
class ProjectDocumentController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "false") deleted: Boolean,
    ): List<DocumentSummaryResponse> = issueService.projectDocuments(user, projectId, deleted).map(DocumentSummaryResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = issueService.createProjectDocument(user, projectId, body.title)
        announcer.documentChanged(user, DocumentOwner(null, document.projectId))
        return DocumentSummaryResponse.from(document)
    }
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
    ): List<IssueResponse> {
        val parsed =
            MyIssueScope.entries.firstOrNull { it.name.equals(scope, ignoreCase = true) }
                ?: throw ValidationException("scope", "INVALID", "값이 올바르지 않습니다")
        return issueService.mine(user, parsed, deleted).map(IssueResponse::from)
    }
}

/** Files attached to issues. Whoever may see the issue may download; changing needs the right to edit it. */
@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping("/{id}")
    suspend fun download(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ResponseEntity<Resource> {
        val file = issueService.attachmentFile(user, id)
        val disposition = ContentDisposition.attachment().filename(file.attachment.fileName, StandardCharsets.UTF_8).build()
        return ResponseEntity
            .ok()
            // Always a download of opaque bytes: an uploaded page or script must never run from this origin.
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(file.path))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.touched(user, issueService.removeAttachment(user, id))
    }
}

/** Comments on issues. Its author changes a comment; its author or a workspace admin deletes it. */
@RestController
@RequestMapping("/api/v1/comments")
class CommentController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: CommentRequest,
    ): CommentResponse {
        val result = issueService.updateComment(user, id, body.content, body.attachmentIds)
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
        val result = issueService.toggleCommentReaction(user, id, body.emoji)
        announcer.touched(user, result.issueNumber)
        return result.reactions
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.touched(user, issueService.removeComment(user, id))
    }
}

/** The entries of an issue's history ("활동"). Anyone who can see the issue can put an emoji on one. */
@RestController
@RequestMapping("/api/v1/activities")
class ActivityController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @PostMapping("/{id}/reactions")
    suspend fun react(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ReactionRequest,
    ): List<ReactionSummary> {
        val result = issueService.toggleActivityReaction(user, id, body.emoji)
        announcer.touched(user, result.issueNumber)
        return result.reactions
    }
}

/** Documents, whether they belong to an issue or to a project. */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping("/{id}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): DocumentResponse = DocumentResponse.from(issueService.document(user, id))

    /** `title` and `content` change only when present; a null `content` empties the document. */
    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ): DocumentResponse {
        val document = issueService.updateDocument(user, id, IssuePatch(body))
        announcer.documentChanged(user, DocumentOwner(document.issue?.number, document.project?.id))
        return DocumentResponse.from(document)
    }

    /** Makes a document inside this one. Project documents only, five levels deep at most (409 `DOCUMENT_TOO_DEEP`). */
    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createChild(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = issueService.createChildDocument(user, id, body.title)
        announcer.documentChanged(user, DocumentOwner(null, document.projectId))
        return DocumentSummaryResponse.from(document)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.documentChanged(user, issueService.removeDocument(user, id))
    }

    /** Brings a deleted document back. 404 when it is not deleted. */
    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun restore(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.documentChanged(user, issueService.restoreDocument(user, id))
    }
}
