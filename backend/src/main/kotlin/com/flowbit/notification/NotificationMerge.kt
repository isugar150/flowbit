package com.flowbit.notification

/**
 * How the changes held for one notification are put together (D-126). A field changed several
 * times becomes one change from its first value to its last, and drops out when it ends where it
 * began; labels and assignees keep what was added or taken off in the end. "Created" and "body
 * edited" are said once, tagged names are gathered, and comments, files and documents each stay.
 */
object NotificationMerge {
    /** Changes that hold `from` and `to`. */
    private val FROM_TO =
        setOf(
            "TITLE_CHANGED",
            "STATUS_CHANGED",
            "PRIORITY_CHANGED",
            "ASSIGNEE_CHANGED",
            "DUE_DATE_CHANGED",
            "MILESTONE_CHANGED",
            "PROJECT_CHANGED",
            "PARENT_CHANGED",
            // What a project, a document or a milestone has (D-165).
            "NAME_CHANGED",
            "LEAD_CHANGED",
            "DATES_CHANGED",
            "TARGET_DATE_CHANGED",
        )
    private val ONCE = setOf("CREATED", "DESCRIPTION_CHANGED", "CONTENT_EDITED")
    private val OPPOSITES = mapOf("DELETED" to "RESTORED", "RESTORED" to "DELETED")

    /** `earlier` with `later` on top: the newer title, and the recipients of both. */
    fun merge(
        earlier: IssueNotification,
        later: IssueNotification,
    ): IssueNotification {
        val events = earlier.events.toMutableList()
        later.events.forEach { add(events, it) }
        return later.copy(events = events, recipientIds = (earlier.recipientIds + later.recipientIds).distinct())
    }

    /** The same for what is done to a project, a document or a milestone. */
    fun merge(
        earlier: ContentNotification,
        later: ContentNotification,
    ): ContentNotification {
        val events = earlier.events.toMutableList()
        later.events.forEach { add(events, it) }
        return later.copy(events = events, recipientIds = (earlier.recipientIds + later.recipientIds).distinct())
    }

    private fun add(
        events: MutableList<IssueNotification.Event>,
        event: IssueNotification.Event,
    ) {
        val type = event.type
        val index = events.indexOfFirst { it.type == type }
        val held = events.getOrNull(index)
        when {
            type in ONCE -> {
                if (held == null) events += event
            }
            type in OPPOSITES -> {
                // Deleted and restored again in one go: nothing happened.
                val opposite = events.indexOfFirst { it.type == OPPOSITES[type] }
                if (opposite >= 0) {
                    events.removeAt(opposite)
                } else if (held == null) {
                    events += event
                }
            }
            held == null -> {
                events += event
            }
            "added" in event.detail && "added" in held.detail -> {
                val added1 = names(held.detail["added"])
                val removed1 = names(held.detail["removed"])
                val added2 = names(event.detail["added"])
                val removed2 = names(event.detail["removed"])
                val added = (added1 - removed2.toSet()) + (added2 - removed1.toSet())
                val removed = (removed1 - added2.toSet()) + (removed2 - added1.toSet())
                replace(events, index, event.copy(detail = mapOf("added" to added.distinct(), "removed" to removed.distinct())))
            }
            type in FROM_TO && "to" in event.detail && "from" in held.detail -> {
                // The newer change's other details (who did it through Git, say) stay.
                replace(events, index, event.copy(detail = event.detail + ("from" to held.detail["from"])))
            }
            type == "MENTIONED" -> {
                val mentions = (names(held.detail["mentions"]) + names(event.detail["mentions"])).distinct()
                replace(events, index, event.copy(detail = mapOf("mentions" to mentions)))
            }
            else -> {
                events += event
            }
        }
    }

    /** Puts the merged change in place of the held one, or takes it out when it no longer changes anything. */
    private fun replace(
        events: MutableList<IssueNotification.Event>,
        index: Int,
        merged: IssueNotification.Event,
    ) {
        val detail = merged.detail
        val empty =
            if ("added" in detail) {
                names(detail["added"]).isEmpty() && names(detail["removed"]).isEmpty()
            } else {
                "from" in detail && detail["from"] == detail["to"]
            }
        if (empty) events.removeAt(index) else events[index] = merged
    }

    private fun names(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it.toString() }
}
