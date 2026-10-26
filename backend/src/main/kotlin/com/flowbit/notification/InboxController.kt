package com.flowbit.notification

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class InboxItemResponse(
    val id: UUID,
    val title: String,
    val headline: String,
    val lines: List<String>,
    val path: String,
    val createdAt: Instant,
    val read: Boolean,
) {
    companion object {
        fun from(item: InboxItem) = InboxItemResponse(item.id, item.title, item.headline, item.lines, item.path, item.createdAt, item.read)
    }
}

/** What the bell shows: the latest notifications, and how many of all of them are not read yet. */
data class InboxResponse(
    val items: List<InboxItemResponse>,
    val unreadCount: Int,
)

/** `path` is the address of the page that was opened: `/issue/FB-12`. */
data class ReadPathRequest(
    val path: String,
)

/** How many unread notifications the page being open made read. */
data class ReadPathResponse(
    val marked: Long,
)

data class UnreadCountResponse(
    val count: Int,
)

/** `ids` marks those read; `all` marks everything the person has. */
data class MarkReadRequest(
    val ids: List<UUID>? = null,
    val all: Boolean = false,
)

/**
 * The signed-in person's inbox (docs/features/inbox-and-daily-summary.md, D-216): the notifications
 * they were told about, newest first, and how many are not read yet. Nobody reads another person's.
 */
@RestController
@RequestMapping("/api/v1/me/inbox")
class InboxController(
    private val store: InboxStore,
    private val hub: RealtimeHub,
    private val clock: Clock,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "30") limit: Int,
    ): InboxResponse = InboxResponse(store.latest(user.id, limit.coerceIn(1, MAX_LIMIT)).map(InboxItemResponse::from), store.unreadCount(user.id))

    @GetMapping("/unread-count")
    suspend fun unreadCount(
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): UnreadCountResponse = UnreadCountResponse(store.unreadCount(user.id))

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun markRead(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: MarkReadRequest,
    ) {
        if (body.all) {
            store.markAllRead(user.id, clock.instant())
        } else {
            store.markRead(user.id, body.ids.orEmpty().take(MAX_LIMIT), clock.instant())
        }
        // Their other tabs show the count too.
        hub.publish(RealtimeHub.inbox(user.id), user.id)
    }

    /**
     * The person opened the page [ReadPathRequest.path]: what they were told about it is read (D-217). Called by the
     * app on every page it opens while something is unread, so it covers a link from a mail or a push as well as the bell.
     */
    @PostMapping("/read-path")
    suspend fun markPathRead(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: ReadPathRequest,
    ): ReadPathResponse {
        val marked = if (body.path.length <= MAX_PATH_LENGTH) store.markPathRead(user.id, body.path.trim(), clock.instant()) else 0L
        if (marked > 0) hub.publish(RealtimeHub.inbox(user.id), user.id)
        return ReadPathResponse(marked)
    }

    private companion object {
        const val MAX_LIMIT = 100
        const val MAX_PATH_LENGTH = 500
    }
}
