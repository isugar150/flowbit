package com.flowbit.content

import com.flowbit.common.UuidV7
import com.flowbit.issue.application.IssuePerson
import com.flowbit.issue.application.ReactionSummary
import com.flowbit.notification.ContentNotification
import com.flowbit.notification.IssueNotification
import com.flowbit.notification.NotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A stored entry of the activity of a project or a document. */
data class ContentActivityView(
    val id: UUID,
    val type: String,
    /** Raw JSON. */
    val detail: String,
    val actor: IssuePerson,
    val createdAt: Instant,
    val reactions: List<ReactionSummary> = emptyList(),
)

/**
 * The activity of projects and documents (`content_activities`), the way
 * `IssueActivityStore` keeps an issue's, and the telling of the people who follow them. Every
 * service that changes one of those pages writes what it did here, in its own transaction.
 */
@Component
class ContentLog(
    private val databaseClient: DatabaseClient,
    private val jsonMapper: JsonMapper,
    private val directory: ContentDirectory,
    private val notifications: NotificationDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Keeps the events in the activity and tells whoever follows the page. Typing is saved every few
     * seconds, so a body or name edit by the same person shortly after their last one extends that
     * entry instead of adding another.
     */
    suspend fun record(
        target: ContentTarget,
        actorId: UUID,
        events: List<ContentEvent>,
        now: Instant,
    ) {
        if (events.isEmpty()) return
        for (event in events) {
            if (event.type in ContentEventType.MERGED && mergeIntoLatest(target, actorId, event, now)) continue
            databaseClient
                .sql(
                    """
                    insert into content_activities (id, target_type, target_id, actor_id, type, detail, created_at)
                    values (:id, :targetType, :targetId, :actorId, :type, cast(:detail as jsonb), :now)
                    """.trimIndent(),
                ).bind("id", UuidV7.generate(now))
                .bind("targetType", target.type.name)
                .bind("targetId", target.id)
                .bind("actorId", actorId)
                .bind("type", event.type)
                .bind("detail", jsonMapper.writeValueAsString(event.detail))
                .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        announce(target, actorId, events)
    }

    /**
     * Tells the people who follow the page about what happened to it, and the people in [told]
     * about a comment (D-166, D-167): the ones tagged in it and, for a reply, the ones in the
     * conversation, and nobody else, whoever follows the page. Never fails the request that made the
     * change. Changes nobody follows, such as who joined a project, are only kept in the activity.
     */
    suspend fun announce(
        target: ContentTarget,
        actorId: UUID,
        events: List<ContentEvent>,
        told: Collection<UUID> = emptyList(),
    ) {
        val tellable = events.filter { it.type !in ContentEventType.SILENT }
        if (tellable.isEmpty()) return
        try {
            val context = directory.describe(target) ?: return
            val (talk, activity) = tellable.partition { it.type in ContentEventType.TALK }
            if (activity.isNotEmpty()) {
                tell(context, actorId, activity, directory.followers(context).filter { it != actorId }, held = true)
            }
            if (talk.isNotEmpty()) tell(context, actorId, talk, told.distinct().filter { it != actorId }, held = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not tell about a change to {} {}", target.type, target.id, e)
        }
    }

    /** [held]: what one person does to a page is gathered into one message; a comment that tags someone goes at once. */
    private suspend fun tell(
        context: ContentContext,
        actorId: UUID,
        events: List<ContentEvent>,
        recipients: List<UUID>,
        held: Boolean,
    ) {
        if (recipients.isEmpty()) return
        val notification =
            ContentNotification(
                targetType = context.target.type.name,
                targetId = context.target.id,
                title = context.title,
                path = context.path,
                projectId = context.projectId,
                actorId = actorId,
                actorName = directory.displayName(actorId),
                events = events.map { IssueNotification.Event(it.type, it.detail) },
                recipientIds = recipients,
            )
        if (held) notifications.dispatch(notification) else notifications.dispatchTalk(notification)
    }

    /** True when the page's latest entry is the same kind of edit by the same person, made recently. */
    private suspend fun mergeIntoLatest(
        target: ContentTarget,
        actorId: UUID,
        event: ContentEvent,
        now: Instant,
    ): Boolean {
        // Keeps the original "from" of a name edit and takes the newest "to".
        val updated =
            databaseClient
                .sql(
                    """
                    update content_activities a
                    set detail = a.detail || cast(:patch as jsonb), created_at = :now
                    where a.seq = (select max(seq) from content_activities where target_type = :targetType and target_id = :targetId)
                      and a.target_type = :targetType and a.target_id = :targetId
                      and a.type = :type and a.actor_id = :actorId and a.created_at > :since
                    """.trimIndent(),
                ).bind("patch", jsonMapper.writeValueAsString(event.detail.filterKeys { it == "to" }))
                .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .bind("targetType", target.type.name)
                .bind("targetId", target.id)
                .bind("type", event.type)
                .bind("actorId", actorId)
                .bind("since", OffsetDateTime.ofInstant(now.minus(MERGE_WINDOW), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        return updated > 0
    }

    suspend fun list(target: ContentTarget): List<ContentActivityView> =
        databaseClient
            .sql(
                """
                select a.id, a.type, a.detail::text as detail, a.created_at,
                       u.id as actor_id, u.login_id, u.display_name, u.avatar_key
                from content_activities a join users u on u.id = a.actor_id
                where a.target_type = :targetType and a.target_id = :targetId
                order by a.seq
                """.trimIndent(),
            ).bind("targetType", target.type.name)
            .bind("targetId", target.id)
            .map { row ->
                ContentActivityView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    type = checkNotNull(row.get("type", String::class.java)),
                    detail = checkNotNull(row.get("detail", String::class.java)),
                    actor =
                        IssuePerson(
                            id = checkNotNull(row.get("actor_id", UUID::class.java)),
                            loginId = checkNotNull(row.get("login_id", String::class.java)),
                            displayName = row.get("display_name", String::class.java),
                            avatarKey = row.get("avatar_key", String::class.java),
                        ),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** The name a person goes by, for the details of an entry. */
    suspend fun nameOf(userId: UUID): String = directory.displayName(userId)

    /** The page an entry of the activity is on. */
    suspend fun targetOf(activityId: UUID): ContentTarget? =
        databaseClient
            .sql("select target_type, target_id from content_activities where id = :id")
            .bind("id", activityId)
            .map { row ->
                ContentTarget(
                    ContentTargetType.valueOf(checkNotNull(row.get("target_type", String::class.java))),
                    checkNotNull(row.get("target_id", UUID::class.java)),
                )
            }.one()
            .awaitSingleOrNull()

    private companion object {
        val MERGE_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
