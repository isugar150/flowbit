package com.flowbit.notification

import java.util.UUID

/** Something happened to an issue that people and other systems should hear about. */
data class IssueNotification(
    /** For example PAY-12. */
    val issueKey: String,
    val issueNumber: Int,
    val issueTitle: String,
    /** The project the issue is linked to, if any. */
    val projectId: UUID?,
    /** Null when a Git server made the change for someone without an account here (D-141). */
    val actorId: UUID?,
    val actorName: String,
    /** IssueActivityType names, such as STATUS_CHANGED, with the same details the activity list shows. */
    val events: List<Event>,
    /** Subscribers of the issue and of its project, without the person who made the change. May be empty. */
    val recipientIds: List<UUID>,
    /**
     * The commits a Git server pushed that moved the issue (D-242): told in the words of the notification, as the hash
     * and first line of each, but not among its [events], so that the changes it carries stay what they were.
     */
    val commits: List<Event> = emptyList(),
) {
    data class Event(
        val type: String,
        val detail: Map<String, Any?>,
    )

    /**
     * Without the changes only an API notification that names them gets: a Git server's links
     * and commits (D-141). Null when nothing else is left to tell. When the issue's status moved, the commits that
     * moved it are still named in the words (D-242), since "진행 중 → 리뷰 준비됨" alone does not say why.
     */
    fun forEveryChannel(): IssueNotification? {
        val rest = events.filterNot { it.type in OPT_IN_TYPES }
        if (rest.isEmpty()) return null
        val pushed = if (rest.any { it.type == "STATUS_CHANGED" }) events.filter { it.type == "GIT_COMMITS_PUSHED" } else emptyList()
        return copy(events = rest, commits = pushed)
    }

    companion object {
        /** Sent only to API notifications that list them in their "보낼 변경"; never by mail or push. */
        val OPT_IN_TYPES = setOf("GIT_BRANCH_LINKED", "GIT_PR_LINKED", "GIT_PR_MERGED", "GIT_PR_CLOSED", "GIT_COMMITS_PUSHED")
    }
}

/**
 * Something happened to a project or a document, or someone commented on one (D-165):
 * what [IssueNotification] is for an issue. The changes are held and merged the same way.
 */
data class ContentNotification(
    /** `PROJECT` or `DOCUMENT`. */
    val targetType: String,
    val targetId: UUID,
    /** What the notification is headed with: `[결제 개편] 설계 문서`. */
    val title: String,
    /** Where it opens: `/doc/…`. */
    val path: String,
    val projectId: UUID?,
    val actorId: UUID,
    val actorName: String,
    /** ContentEventType names, with the same details the activity list shows. */
    val events: List<IssueNotification.Event>,
    /**
     * For a change, the people who follow the page (the document, or the project for a project);
     * for a comment, the people tagged in it (D-166). Without the actor.
     */
    val recipientIds: List<UUID>,
)

/**
 * Someone asked to be let into a project they may not enter (D-128). Told at once, not held,
 * to the people who can let them in.
 */
data class ProjectAccessRequestNotification(
    val projectId: UUID,
    val projectName: String,
    val requesterId: UUID,
    val requesterName: String,
    /** The project's active managers, or the workspace administrators when it has none. */
    val recipientIds: List<UUID>,
) {
    companion object {
        /** The kind of change a webhook can choose, next to the IssueActivityType names. */
        const val TYPE = "ACCESS_REQUESTED"
        const val EVENT = "project.access_requested"
    }
}

/**
 * The daily summary of one member (docs/features/inbox-and-daily-summary.md, D-216): not a change to anything, so it
 * has no issue, and it is told to that member alone. API notifications get it when they chose no kinds or chose [TYPE].
 */
data class DailySummaryNotification(
    val recipientId: UUID,
    val message: NotificationMessage,
) {
    companion object {
        /** The kind of notification a webhook can choose, next to the IssueActivityType names. */
        const val TYPE = "DAILY_SUMMARY"
        const val EVENT = "summary.daily"
    }
}

/**
 * One way of telling the world outside the app: mail (SMTP), web push (Firebase Cloud Messaging),
 * outgoing API calls (webhooks). To add one, implement this as a Spring bean; the dispatcher picks
 * it up. A channel checks for itself whether it is switched on, looks up what it needs for each
 * recipient (the address in `users.email`, the device tokens) and skips whom it cannot reach.
 */
interface NotificationChannel {
    suspend fun send(notification: IssueNotification)

    /** A request to join a project. A channel that has nothing to say about it leaves this out. */
    suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {}

    /** A change to a project or a document, or a comment on one (D-165). */
    suspend fun sendContent(notification: ContentNotification) {}
}
