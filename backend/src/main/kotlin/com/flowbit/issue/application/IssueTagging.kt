package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.domain.Issue
import org.springframework.stereotype.Component
import java.util.UUID

/** Tagging people with @ in the body or a comment of an issue (D-101). */
@Component
class IssueTagging(
    private val lookup: IssueLookup,
    private val activities: IssueActivityStore,
) {
    fun mentionEvents(tagged: List<Pair<UUID, String>>): List<IssueEvent> =
        if (tagged.isEmpty()) emptyList() else listOf(IssueEvent(IssueActivityType.MENTIONED, mapOf("mentions" to tagged.map { it.second })))

    /**
     * Makes the people tagged with @ follow the issue, so the notification about it reaches them
     * through every channel they have set up. Only people who could be given the issue count
     * (members of its project, or anyone active for an issue on its own); tagging oneself does
     * nothing. Returns who was tagged, with their names as of now.
     */
    suspend fun tag(
        issue: Issue,
        userIds: Collection<UUID>,
        actor: AuthenticatedUser,
    ): List<Pair<UUID, String>> =
        userIds.filter { it != actor.id }.mapNotNull { userId ->
            if (!lookup.canSee(issue, userId)) return@mapNotNull null
            activities.subscribe(issue.id, userId)
            userId to activities.displayName(userId)
        }
}
