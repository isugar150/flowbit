package com.flowbit.content

import com.flowbit.common.richtext.RichText
import java.util.UUID

/** The four kinds of page the editor's blocks can be commented on (D-165). */
enum class ContentTargetType(
    /** The word in the address: `/api/v1/content/{type}/{id}/…`. */
    val path: String,
    val table: RichText.ContentTable,
) {
    PROJECT("project", RichText.ContentTable.PROJECTS),
    ISSUE("issue", RichText.ContentTable.ISSUES),
    DOCUMENT("document", RichText.ContentTable.DOCUMENTS),
    MILESTONE("milestone", RichText.ContentTable.MILESTONES),
    ;

    /** Whether it has activity and general comments of its own: an issue keeps its own (`issue_activities`). */
    val hasActivity: Boolean get() = this != ISSUE

    companion object {
        fun ofPath(path: String): ContentTargetType? = entries.firstOrNull { it.path == path }
    }
}

/** One page: its kind and the id of its row (an issue's `id`, not its number). */
data class ContentTarget(
    val type: ContentTargetType,
    val id: UUID,
)

/** One thing that happened to a project, a document or a milestone, before it is stored. */
data class ContentEvent(
    val type: String,
    val detail: Map<String, Any?> = emptyMap(),
)

/**
 * What the rest of the code needs to know about a page without asking who is looking: where it
 * is, what it is called, what it belongs to and who follows it. [readOnly] is a deleted issue or a
 * deleted document: it can be read but nothing can be added to it.
 */
data class ContentContext(
    val target: ContentTarget,
    val title: String,
    /** Where it opens in the app. */
    val path: String,
    /** The project it belongs to; an issue's own documents, and an issue on its own, have none. */
    val projectId: UUID?,
    /** The issue it is, or the one a document is on. */
    val issueId: UUID?,
    val issueNumber: Int?,
    /** The realtime topics whoever shows the page listens to. */
    val topics: List<String>,
    val readOnly: Boolean,
)

/** The kinds of change [ContentEvent] names (docs/features/content-comments.md). */
object ContentEventType {
    const val NAME_CHANGED = "NAME_CHANGED"
    const val CONTENT_EDITED = "CONTENT_EDITED"
    const val STATUS_CHANGED = "STATUS_CHANGED"
    const val PRIORITY_CHANGED = "PRIORITY_CHANGED"
    const val LEAD_CHANGED = "LEAD_CHANGED"
    const val DATES_CHANGED = "DATES_CHANGED"
    const val TARGET_DATE_CHANGED = "TARGET_DATE_CHANGED"
    const val LABELS_CHANGED = "LABELS_CHANGED"
    const val ICON_CHANGED = "ICON_CHANGED"
    const val ARCHIVED = "ARCHIVED"
    const val RESTORED = "RESTORED"
    const val MEMBER_ADDED = "MEMBER_ADDED"
    const val MEMBER_REMOVED = "MEMBER_REMOVED"
    const val MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED"
    const val UPDATE_POSTED = "UPDATE_POSTED"
    const val MILESTONE_ADDED = "MILESTONE_ADDED"
    const val MILESTONE_REMOVED = "MILESTONE_REMOVED"
    const val DOCUMENT_ADDED = "DOCUMENT_ADDED"
    const val DOCUMENT_REMOVED = "DOCUMENT_REMOVED"
    const val DOCUMENT_RESTORED = "DOCUMENT_RESTORED"

    /** Told but never kept in the activity: the comment itself is what the page shows. */
    const val COMMENT_ADDED = "COMMENT_ADDED"
    const val THREAD_COMMENT = "THREAD_COMMENT"

    /** Kept in the activity but never told: changes the people who follow a project do not need a message for. */
    val SILENT = setOf(MEMBER_ADDED, MEMBER_REMOVED, MEMBER_ROLE_CHANGED, ICON_CHANGED, ARCHIVED, RESTORED)

    /** Typing is saved every few seconds: the same person's next edit of these extends the latest entry. */
    val MERGED = setOf(CONTENT_EDITED, NAME_CHANGED)
}
