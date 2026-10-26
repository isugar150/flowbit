package com.flowbit.notification

import com.flowbit.common.UuidV7
import com.flowbit.realtime.RealtimeHub
import com.flowbit.user.domain.UserRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** One notification in a person's inbox, as the bell lists it. */
data class InboxItem(
    val id: UUID,
    val title: String,
    val headline: String,
    /** One line per change, as the mail has them. */
    val lines: List<String>,
    /** Where it opens in the app. */
    val path: String,
    val createdAt: Instant,
    val read: Boolean,
)

/** `inbox_notifications` (docs/features/inbox-and-daily-summary.md, D-216). */
@Component
class InboxStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun add(
        userIds: Collection<UUID>,
        message: NotificationMessage,
        now: Instant,
    ) {
        for (userId in userIds.distinct()) {
            databaseClient
                .sql(
                    """
                    insert into inbox_notifications (id, user_id, title, headline, body, path, created_at)
                    values (:id, :userId, :title, :headline, :body, :path, :now)
                    """.trimIndent(),
                ).bind("id", UuidV7.generate(now))
                .bind("userId", userId)
                .bind("title", message.title)
                .bind("headline", message.headline)
                .bind("body", message.lines.joinToString("\n"))
                .bind("path", message.path)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    /** The latest [limit] of the person's notifications, newest first. */
    suspend fun latest(
        userId: UUID,
        limit: Int,
    ): List<InboxItem> =
        databaseClient
            .sql(
                """
                select id, title, headline, body, path, created_at, read_at
                from inbox_notifications where user_id = :userId
                order by created_at desc, id desc limit :limit
                """.trimIndent(),
            ).bind("userId", userId)
            .bind("limit", limit)
            .map { row ->
                InboxItem(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    title = checkNotNull(row.get("title", String::class.java)),
                    headline = checkNotNull(row.get("headline", String::class.java)),
                    lines = checkNotNull(row.get("body", String::class.java)).split('\n').filter { it.isNotEmpty() },
                    path = checkNotNull(row.get("path", String::class.java)),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    read = row.get("read_at", OffsetDateTime::class.java) != null,
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun unreadCount(userId: UUID): Int =
        databaseClient
            .sql("select count(*) as n from inbox_notifications where user_id = :userId and read_at is null")
            .bind("userId", userId)
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)).toInt() }
            .one()
            .awaitSingle()

    /** Marks these of the person's notifications read (the ones of others are not touched). */
    suspend fun markRead(
        userId: UUID,
        ids: Collection<UUID>,
        now: Instant,
    ) {
        if (ids.isEmpty()) return
        databaseClient
            .sql("update inbox_notifications set read_at = :now where user_id = :userId and read_at is null and id = any(:ids)")
            .bind("now", now)
            .bind("userId", userId)
            .bind("ids", ids.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun markAllRead(
        userId: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update inbox_notifications set read_at = :now where user_id = :userId and read_at is null")
            .bind("now", now)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /**
     * Marks read the person's notifications about the page [path] (`/issue/FB-12`, `/doc/…`): the ones whose address,
     * without the part after `#`, is this one (case does not matter). Returns how many were unread.
     */
    suspend fun markPathRead(
        userId: UUID,
        path: String,
        now: Instant,
    ): Long =
        databaseClient
            .sql(
                """
                update inbox_notifications set read_at = :now
                where user_id = :userId and read_at is null and lower(split_part(path, '#', 1)) = lower(:path)
                """.trimIndent(),
            ).bind("now", now)
            .bind("userId", userId)
            .bind("path", path)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** Forgets notifications made before [cutoff], read or not. Returns how many. */
    suspend fun purgeBefore(cutoff: Instant): Long =
        databaseClient
            .sql("delete from inbox_notifications where created_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}

/**
 * Keeps in each recipient's inbox what the other channels tell them (D-216): it is the channel that needs no
 * setup, so it is how someone who has no mail address and no browser registered still hears of a change. The
 * recipients are those of the other channels (followers; the people tagged for a comment), and the words are
 * theirs. Then the bell of those who have the app open is told to read again.
 */
@Component
class InboxNotificationChannel(
    private val store: InboxStore,
    private val userRepository: UserRepository,
    private val texts: NotificationText,
    private val hub: RealtimeHub,
    private val clock: Clock,
) : NotificationChannel {
    override suspend fun send(notification: IssueNotification) {
        val shown = notification.forEveryChannel() ?: return
        keep(shown.recipientIds, texts.of(shown))
    }

    override suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {
        keep(request.recipientIds, texts.of(request))
    }

    override suspend fun sendContent(notification: ContentNotification) {
        keep(notification.recipientIds, texts.of(notification))
    }

    private suspend fun keep(
        recipientIds: List<UUID>,
        message: NotificationMessage,
    ) {
        if (recipientIds.isEmpty()) return
        val active = userRepository.findActiveByIds(recipientIds).toList().map { it.id }
        store.add(active, message, clock.instant())
        active.forEach { hub.publish(RealtimeHub.inbox(it), RealtimeHub.NOBODY) }
    }
}
