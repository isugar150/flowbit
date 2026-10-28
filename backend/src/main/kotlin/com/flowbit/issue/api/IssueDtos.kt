package com.flowbit.issue.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import com.flowbit.customproperty.application.CustomPropertyType
import com.flowbit.customproperty.application.NewCustomProperty
import com.flowbit.issue.application.AttachmentView
import com.flowbit.issue.application.CommentView
import com.flowbit.issue.application.DocumentDetail
import com.flowbit.issue.application.DocumentSummary
import com.flowbit.issue.application.IssueActivityFeed
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueDetail
import com.flowbit.issue.application.IssueGitLinkView
import com.flowbit.issue.application.IssueGitRepositoryView
import com.flowbit.issue.application.IssueLabel
import com.flowbit.issue.application.IssueParent
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.IssueProject
import com.flowbit.issue.application.IssueView
import com.flowbit.issue.application.NewIssue
import com.flowbit.issue.application.NewRelation
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.issue.application.RelationKind
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.media.application.ImageStorage
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// What the issue endpoints take and give.

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
data class IssueCycleResponse(
    val id: UUID,
    val number: Int,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

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
    /** The category of the issue's status: open or finished. */
    val status: IssueStatus,
    /** The status the issue is in, and its name. */
    val statusId: UUID,
    val statusName: String,
    val priority: IssuePriority,
    /** Story points, or null. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val estimate: Int?,
    /** How many issues that block it are still open (D-230). */
    val openBlockers: Int,
    /** Waiting in the triage queue of its project (D-231). */
    val triage: Boolean,
    /** The cycle of its project it is planned in (D-232), or null. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cycle: IssueCycleResponse?,
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
    val parent: IssueParent?,
    /** Every issue above this one, the top-level one first and `parent` last. */
    val ancestors: List<IssueParent>,
    val labels: List<IssueLabel>,
    /** How many sub-issues the issue has, and how many of them are done; for the progress shown in lists. */
    val subIssueCount: Int,
    val subIssueDoneCount: Int,
    /** How many comments the issue has (on it and on the blocks of its body) and how many entries its history has besides "created"; shown in lists (D-219). */
    val commentCount: Int,
    val activityCount: Int,
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
    /** Whether a sub-issue may be made under it; false at the deepest level (5 below a top-level issue). */
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
                statusId = issue.statusId,
                statusName = issue.statusName,
                priority = issue.priority,
                estimate = issue.estimate,
                openBlockers = issue.openBlockers,
                triage = issue.triage,
                cycle = issue.cycle?.let { IssueCycleResponse(it.id, it.number, it.startsOn, it.endsOn) },
                assignees = issue.assignees.map(IssuePersonResponse::from),
                startDate = issue.startDate,
                dueDate = issue.dueDate,
                project = issue.project,
                parent = issue.parent,
                ancestors = issue.ancestors,
                labels = issue.labels,
                subIssueCount = issue.subIssueCount,
                subIssueDoneCount = issue.subIssueDoneCount,
                commentCount = issue.commentCount,
                activityCount = issue.activityCount,
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
    /** The comment this one answers; null for a comment of its own (D-167). */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val parentId: UUID?,
    /** Deleted, but kept because it has replies: no text, files or emoji (D-167). */
    val deleted: Boolean,
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
                comment.parentId,
                comment.deleted,
            )
    }
}

/**
 * `content` may be left out when the comment is made of files alone. `attachmentIds` are files
 * uploaded for it beforehand; on a change they are the whole set the comment keeps (left out: no change).
 * `parentId` makes a new comment a reply to that comment (D-167); a change ignores it.
 */
data class CommentRequest(
    val content: Map<String, Any?>? = null,
    val attachmentIds: List<UUID>? = null,
    val parentId: UUID? = null,
)

data class IssueActivityResponse(
    val items: List<IssueActivityItemResponse>,
    val comments: List<CommentResponse>,
    val subscribers: List<IssuePersonResponse>,
    val subscribed: Boolean,
    val includeDescendants: Boolean,
    val inheritedSubscription: Boolean,
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
                includeDescendants = feed.includeDescendants,
                inheritedSubscription = feed.inheritedSubscription,
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
    val labelIds: List<UUID>? = null,
    /** Links the issue to this project; without it the issue is on its own. */
    val projectId: String? = null,
    /** Makes the issue a sub-issue of this one. */
    val parentId: UUID? = null,
    /** With `dueDate`, makes the issue cover a span of days. */
    val startDate: LocalDate? = null,
    /** Properties to give the issue as it is made, in order (D-193); the same as adding each one afterwards. */
    val customProperties: List<NewCustomPropertyRequest>? = null,
    /** Starts the issue in this status (otherwise in the first one of `status`'s category). */
    val statusId: UUID? = null,
    val estimate: Int? = null,
    /** True sends the issue to the project's triage queue even when a manager makes it (D-236); ignored where triage is off. */
    val triage: Boolean? = null,
    /** Relations to give the issue as it is made (D-237), from its point of view like `POST …/relations`. */
    val relations: List<NewRelationRequest>? = null,
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
            labelIds.orEmpty(),
            projectId,
            parentId,
            startDate,
            customProperties.orEmpty().map { NewCustomProperty(it.label, it.type, it.value) },
            statusId,
            estimate,
            triage,
            relations.orEmpty().map { NewRelation(it.kind, it.targetNumber) },
        )
}

data class NewRelationRequest(
    val kind: RelationKind,
    val targetNumber: Int,
)

data class NewCustomPropertyRequest(
    val label: String,
    val type: CustomPropertyType,
    val value: Any? = null,
)

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

data class IssueSubscriptionRequest(
    val includeDescendants: Boolean = false,
)
