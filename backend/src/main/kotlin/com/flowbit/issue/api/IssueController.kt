package com.flowbit.issue.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.AttachmentView
import com.flowbit.issue.application.DocumentDetail
import com.flowbit.issue.application.DocumentSummary
import com.flowbit.issue.application.IssueActivityFeed
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueDetail
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
) {
    companion object {
        fun from(document: DocumentSummary) = DocumentSummaryResponse(document.id, document.title, document.updatedAt)
    }
}

/**
 * An issue. The last five fields are present only when one issue is requested (GET /issues/{number});
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
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val assignee: IssuePersonResponse?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val dueDate: LocalDate?,
    /** The project the issue is linked to; null when it is on its own. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val project: IssueProject?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val milestone: IssueMilestone?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val parent: IssueParent?,
    val labels: List<IssueLabel>,
    val createdBy: IssuePersonResponse,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val canEdit: Boolean? = null,
    val subIssues: List<IssueResponse>? = null,
    val reactions: List<ReactionSummary>? = null,
    val attachments: List<AttachmentResponse>? = null,
    val documents: List<DocumentSummaryResponse>? = null,
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
                assignee = issue.assignee?.let(IssuePersonResponse::from),
                dueDate = issue.dueDate,
                project = issue.project,
                milestone = issue.milestone,
                parent = issue.parent,
                labels = issue.labels,
                createdBy = IssuePersonResponse.from(issue.createdBy),
                version = issue.version,
                createdAt = issue.createdAt,
                updatedAt = issue.updatedAt,
            )

        fun from(detail: IssueDetail) =
            from(detail.issue).copy(
                canEdit = detail.canEdit,
                subIssues = detail.subIssues.map(::from),
                reactions = detail.reactions,
                attachments = detail.attachments.map(AttachmentResponse::from),
                documents = detail.documents.map(DocumentSummaryResponse::from),
            )
    }
}

data class IssueActivityItemResponse(
    val id: UUID,
    val type: IssueActivityType,
    /** Old and new values, depending on the type: `{from, to}` or `{added, removed}`. */
    @get:JsonRawValue
    val detail: String,
    val actor: IssuePersonResponse,
    val createdAt: Instant,
)

data class IssueActivityResponse(
    val items: List<IssueActivityItemResponse>,
    val subscribers: List<IssuePersonResponse>,
    val subscribed: Boolean,
) {
    companion object {
        fun from(feed: IssueActivityFeed) =
            IssueActivityResponse(
                items =
                    feed.items.map {
                        IssueActivityItemResponse(it.id, it.type, it.detail, IssuePersonResponse.from(it.actor), it.createdAt)
                    },
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
    /** The issue the document belongs to. */
    val issue: IssueParent,
    val canEdit: Boolean,
) {
    companion object {
        fun from(detail: DocumentDetail) =
            DocumentResponse(
                id = detail.document.id,
                title = detail.document.title,
                content = detail.content,
                updatedAt = detail.document.updatedAt,
                issue = IssueParent(detail.issue.number, detail.issue.key, detail.issue.title),
                canEdit = detail.canEdit,
            )
    }
}

data class CreateIssueRequest(
    val title: String,
    val description: String? = null,
    val content: Map<String, Any?>? = null,
    val status: IssueStatus? = null,
    val priority: IssuePriority? = null,
    val assigneeId: UUID? = null,
    val dueDate: LocalDate? = null,
    val milestoneId: UUID? = null,
    val labelIds: List<UUID>? = null,
    /** Links the issue to this project; without it the issue is on its own. */
    val projectKey: String? = null,
    /** Makes the issue a sub-issue of this one. */
    val parentId: UUID? = null,
) {
    fun toNewIssue(projectKey: String? = this.projectKey) =
        NewIssue(
            title,
            description,
            content,
            status,
            priority,
            assigneeId,
            dueDate,
            milestoneId,
            labelIds.orEmpty(),
            projectKey,
            parentId,
        )
}

data class ReactionRequest(
    val emoji: String,
)

data class CreateDocumentRequest(
    val title: String? = null,
)

/** Tells whoever shows an issue, or a list it is in, that it changed; and its subscribers through the notification channels. */
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
    ) {
        realtime.publish(RealtimeHub.ISSUES, user.id)
        change.projectKeys.forEach { realtime.publish("project:$it", user.id) }
        touched(user, change.issue.number)
        if (change.recipientIds.isEmpty() || change.events.isEmpty()) return
        notifications.dispatch(
            IssueNotification(
                issueKey = change.issue.key,
                issueNumber = change.issue.number,
                issueTitle = change.issue.title,
                projectKey = change.issue.project?.key,
                actorId = user.id,
                actorName = issueService.actorName(user),
                events = change.events.map { IssueNotification.Event(it.type.name, it.detail) },
                recipientIds = change.recipientIds,
            ),
        )
    }

    /** Something on the issue's own page changed (a reaction, a file, a document, who follows it). */
    fun touched(
        user: AuthenticatedUser,
        number: Int,
    ) {
        realtime.publish("issue:$number", user.id)
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
    ): List<IssueResponse> = issueService.list(user, project).map(IssueResponse::from)

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

    @GetMapping("/{number}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): IssueResponse = IssueResponse.from(issueService.get(user, number))

    /**
     * Only the fields present in the body change; `null` clears `assigneeId`, `dueDate`,
     * `milestoneId`, `projectKey` and `parentId`, and `labelIds` replaces the whole set of labels.
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
@RequestMapping("/api/v1/projects/{key}/issues")
class ProjectIssueController(
    private val issueService: IssueService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
    ): List<IssueResponse> = issueService.list(user, key).map(IssueResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable key: String,
        @RequestBody body: CreateIssueRequest,
    ): IssueResponse {
        val change = issueService.create(user, body.toNewIssue(projectKey = key))
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
        return IssueResponse.from(change.issue)
    }
}

/** "내 이슈": the caller's issues across projects. `scope` is `assigned` (default) or `created`. */
@RestController
@RequestMapping("/api/v1/me/issues")
class MyIssuesController(
    private val issueService: IssueService,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "assigned") scope: String,
    ): List<IssueResponse> {
        val parsed =
            MyIssueScope.entries.firstOrNull { it.name.equals(scope, ignoreCase = true) }
                ?: throw ValidationException("scope", "INVALID", "값이 올바르지 않습니다")
        return issueService.mine(user, parsed).map(IssueResponse::from)
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

/** Documents that belong to issues. */
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
        announcer.touched(user, document.issue.number)
        return DocumentResponse.from(document)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.touched(user, issueService.removeDocument(user, id))
    }
}
