package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.application.Actor
import com.flowbit.issue.application.DocumentOwner
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueEvent
import com.flowbit.issue.application.IssueLookup
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationDispatcher
import com.flowbit.notification.NotificationMerge
import com.flowbit.realtime.RealtimeHub
import org.springframework.stereotype.Component
import java.util.UUID

/** Tells whoever shows an issue, or a list it is in, that it changed; and whoever follows it or its project through the notification channels. */
@Component
class IssueAnnouncer(
    private val lookup: IssueLookup,
    private val realtime: RealtimeHub,
    private val notifications: NotificationDispatcher,
) {
    /** Call after the change is committed. */
    suspend fun changed(
        user: AuthenticatedUser,
        change: IssueChange,
    ) = changed(user.id, change) { lookup.actorName(user) }

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
        events: List<IssueEvent> = change.events,
        recipientIds: List<UUID> = change.recipientIds,
    ) = IssueNotification(
        issueKey = change.issue.key,
        issueNumber = change.issue.number,
        issueTitle = change.issue.title,
        projectId = change.issue.project?.id,
        actorId = actorId,
        actorName = actorName,
        events = events.map { IssueNotification.Event(it.type.name, it.detail) },
        recipientIds = recipientIds,
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
    ) {
        // The lists show how many comments an issue has (D-219): they read again.
        realtime.publish(RealtimeHub.ISSUES, user.id)
        change.projectIds.forEach { realtime.publish("project:$it", user.id) }
        commented(user.id, change) { lookup.actorName(user) }
    }

    /** A comment of the issue was deleted: its page and the lists, which show how many comments it has, read again (D-219). */
    suspend fun commentsChanged(
        user: AuthenticatedUser,
        number: Int,
    ) {
        touched(user, number)
        realtime.publish(RealtimeHub.ISSUES, user.id)
        lookup
            .access(user, number)
            .issue.projectId
            ?.let { realtime.publish("project:$it", user.id) }
    }

    private suspend fun commented(
        actorId: UUID?,
        change: IssueChange,
        actorName: suspend () -> String,
    ) {
        touched(actorId ?: RealtimeHub.NOBODY, change.issue.number)
        // What was done to the issue goes to the people who follow it or its project; what was said (a comment,
        // a tag in the body) goes at once to the people tagged and to nobody else (D-166).
        val (talk, done) = change.events.partition { it.type in IssueChange.TALK }
        val toFollowers = done.isNotEmpty() && change.recipientIds.isNotEmpty()
        val toTagged = talk.isNotEmpty() && change.taggedIds.isNotEmpty()
        if (!toFollowers && !toTagged) return
        val name = actorName()
        if (toFollowers) notifications.dispatch(notificationOf(actorId, change, name, done))
        if (toTagged) notifications.dispatchTalk(notificationOf(actorId, change, name, talk, change.taggedIds))
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
