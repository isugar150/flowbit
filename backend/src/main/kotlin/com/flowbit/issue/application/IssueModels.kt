package com.flowbit.issue.application

import com.flowbit.common.error.ValidationException
import com.flowbit.customproperty.application.NewCustomProperty
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import org.springframework.core.io.Resource
import java.time.LocalDate
import java.util.UUID

// What the issue services take and give (docs/03-backend-guide.md): requests in, results out.

/** What a new issue is made from. Only the title is required. */
data class NewIssue(
    val title: String,
    val description: String?,
    /** The body as an editor document. When present, `description` is derived from it. */
    val content: Any?,
    val status: IssueStatus?,
    val priority: IssuePriority?,
    /**
     * The people the issue is given to, in order; empty for no one. Null leaves it to the default:
     * a sub-issue starts with its parent's assignees (D-129), any other issue with no one.
     */
    val assigneeIds: List<UUID>?,
    val dueDate: LocalDate?,
    val labelIds: List<UUID> = emptyList(),
    /** The project to link the issue to; null leaves it on its own. */
    val projectId: String? = null,
    /** Makes the new issue a sub-issue of this one. */
    val parentId: UUID? = null,
    /** With [dueDate], makes the issue cover a span of days. */
    val startDate: LocalDate? = null,
    /** The properties the people adding it want it to have from the start (D-186, D-193), in order. */
    val customProperties: List<NewCustomProperty> = emptyList(),
    /** Which status to start in; without it, the first one of [status]'s category (backlog unless said). */
    val statusId: UUID? = null,
    val estimate: Int? = null,
)

/**
 * A partial change. A field is applied only when it was present in the request, so that
 * "not mentioned" and "set to nothing" (clearing the assignee or the due date) stay different.
 */
class IssuePatch(
    private val fields: Map<String, Any?>,
) {
    fun has(name: String): Boolean = fields.containsKey(name)

    fun string(name: String): String? = fields[name] as? String

    fun raw(name: String): Any? = fields[name]

    fun <T : Enum<T>> enum(
        name: String,
        parse: (String) -> T,
    ): T? = string(name)?.let { value -> runCatching { parse(value) }.getOrElse { throw invalid(name) } }

    /** A whole number, or null when the field is null. */
    fun int(name: String): Int? =
        when (val value = fields[name]) {
            null -> null
            is Int -> value
            is Number -> if (value.toDouble() % 1.0 == 0.0) value.toInt() else throw invalid(name)
            else -> throw invalid(name)
        }

    fun bool(name: String): Boolean = fields[name] as? Boolean ?: throw invalid(name)

    fun uuid(name: String): UUID? = string(name)?.let { value -> runCatching { UUID.fromString(value) }.getOrElse { throw invalid(name) } }

    /** A list of ids; anything else in the field is a validation error. */
    fun uuids(name: String): List<UUID> =
        (fields[name] as? List<*> ?: throw invalid(name)).map { value ->
            runCatching { UUID.fromString(value as String) }.getOrElse { throw invalid(name) }
        }

    fun date(name: String): LocalDate? = string(name)?.let { value -> runCatching { LocalDate.parse(value) }.getOrElse { throw invalid(name) } }

    private fun invalid(name: String) = ValidationException(name, "INVALID", "값이 올바르지 않습니다")
}

data class IssueActivityFeed(
    val items: List<IssueActivityView>,
    /** Oldest first; the page mixes them into `items` by time. */
    val comments: List<CommentView>,
    val subscribers: List<IssuePerson>,
    /** Whether the caller is one of the subscribers. */
    val subscribed: Boolean,
)

/** One issue with everything its page shows. */
data class IssueDetail(
    val issue: IssueView,
    /** Whether the caller may change the issue. Never for a deleted one. */
    val canEdit: Boolean,
    /** Whether the caller's role would let them restore the issue if it is deleted. */
    val canRestore: Boolean,
    /** Whether a sub-issue may be made under it: the caller may edit, and it is not at the deepest level. */
    val canAddSubIssue: Boolean,
    val subIssues: List<IssueView>,
    val reactions: List<ReactionSummary>,
    val attachments: List<AttachmentView>,
    val documents: List<DocumentSummary>,
    /** Branches and pull requests a Git server linked it to (D-141). */
    val git: List<IssueGitLinkView> = emptyList(),
    /** The repository picked on the issue (D-148); kept only while it is one of [gitRepositoryOptions]. */
    val gitRepository: IssueGitRepositoryView? = null,
    /** The repositories of its project that can be picked; empty for an issue on its own. */
    val gitRepositoryOptions: List<IssueGitRepositoryView> = emptyList(),
)

/** A comment that was written or changed, with whom to tell about it. */
data class CommentResult(
    val comment: CommentView,
    val change: IssueChange,
)

/** The emoji left on a comment or an entry of the history after a press, and the issue it is on. */
data class EntryReactions(
    val issueNumber: Int,
    val reactions: List<ReactionSummary>,
)

/** A document with what its page needs to know about the issue or the project it belongs to. */
data class DocumentDetail(
    val document: DocumentSummary,
    /** Raw editor JSON, or null while the document is empty. */
    val content: String?,
    val issue: IssueView?,
    val project: IssueProject?,
    val canEdit: Boolean,
    /** The documents it sits inside, the outermost first. */
    val ancestors: List<DocumentSummary>,
    /** The documents inside it. */
    val children: List<DocumentSummary>,
    /** Whether a document may be made inside it: the caller may edit, and it is not at the deepest level. */
    val canAddChild: Boolean,
    /** Whether the caller may bring this deleted document back. */
    val canRestore: Boolean = false,
)

/** What a document belonged to: an issue (by number) or a project (by id). */
data class DocumentOwner(
    val issueNumber: Int?,
    val projectId: UUID?,
)

/** An attached file ready to be sent. */
data class AttachmentFile(
    val attachment: AttachmentView,
    val resource: Resource,
)
