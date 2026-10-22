package com.flowbit.realtime

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.NotFoundException
import com.flowbit.content.ContentAccess
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.issue.application.IssueService
import com.flowbit.media.application.ImageStorage
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Someone who belongs on a page, with when they last had it open (null: never). */
data class PagePerson(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val lastSeenAt: Instant?,
)

/**
 * The pages a realtime connection can be on (`project:<id>`, `issue:<number>`, `document:<id>`):
 * who may be there, who belongs there, and when each of them last looked.
 */
@Component
class PagePresence(
    private val projectService: ProjectService,
    private val issueService: IssueService,
    private val contentAccess: ContentAccess,
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {
    /**
     * `project:<id>` is readable by whoever may enter the project, `issue:12` by whoever may see
     * that issue, and `issues` and `projects` (something changed, with no detail) by every signed-in person.
     */
    suspend fun canRead(
        user: AuthenticatedUser,
        topic: String,
    ): Boolean {
        if (topic == RealtimeHub.ISSUES || topic == RealtimeHub.PROJECTS) return true
        return try {
            page(user, topic)
            true
        } catch (_: NotFoundException) {
            false
        }
    }

    /** Notes that the person has the page open now. Called when they arrive and when they leave. */
    suspend fun seen(
        userId: UUID,
        key: String,
    ) {
        databaseClient
            .sql(
                """
                insert into page_views (page_key, user_id, seen_at) values (:key, :userId, :now)
                on conflict (page_key, user_id) do update set seen_at = excluded.seen_at
                """.trimIndent(),
            ).bind("key", key)
            .bind("userId", userId)
            .bind("now", clock.instant())
            .fetch()
            .rowsUpdated()
            .awaitSingleOrNull()
    }

    /**
     * Everyone who belongs on the page: the members of the project (for an issue, of the project
     * it is linked to; every active person when it is linked to none), and whoever else has looked
     * at it. Most recent first, then those who never looked, by name.
     */
    suspend fun people(
        user: AuthenticatedUser,
        key: String,
    ): List<PagePerson> {
        val projectId = page(user, key)
        val belongs =
            if (projectId == null) {
                "true"
            } else {
                "(v.user_id is not null or exists(select 1 from project_members pm where pm.project_id = :projectId and pm.user_id = u.id))"
            }
        return databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key, v.seen_at
                from users u left join page_views v on v.user_id = u.id and v.page_key = :key
                where u.status = 'ACTIVE' and $belongs
                order by v.seen_at desc nulls last, lower(coalesce(u.display_name, u.login_id))
                limit $MAX_PEOPLE
                """.trimIndent(),
            ).bind("key", key)
            .let { if (projectId != null) it.bind("projectId", projectId) else it }
            .map { row ->
                PagePerson(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    loginId = checkNotNull(row.get("login_id", String::class.java)),
                    displayName = row.get("display_name", String::class.java),
                    avatarUrl = ImageStorage.urlOf(row.get("avatar_key", String::class.java)),
                    lastSeenAt = row.get("seen_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    /**
     * The project whose members belong on the page; null for an issue linked to no project.
     * "Not found" when the key names no page or one the caller may not see.
     */
    private suspend fun page(
        user: AuthenticatedUser,
        key: String,
    ): UUID? {
        if (key.startsWith("issue:")) {
            val number = key.removePrefix("issue:").takeIf { ISSUE_NUMBER.matches(it) }?.toInt() ?: throw notFound()
            return issueService.projectIdOf(user, number)
        }
        if (key.startsWith("document:")) {
            // A document belongs with the people of its project; one on an issue, of the issue's project (D-166).
            val id = key.removePrefix("document:").takeIf { PAGE_ID.matches(it) }?.let(UUID::fromString) ?: throw notFound()
            return contentAccess.resolve(user, ContentTarget(ContentTargetType.DOCUMENT, id)).projectId
        }
        if (!key.startsWith("project:")) throw notFound()
        return projectService.access(user, key.removePrefix("project:")).project.id
    }

    private fun notFound() = NotFoundException("PAGE_NOT_FOUND", "화면을 찾을 수 없습니다")

    private companion object {
        val ISSUE_NUMBER = Regex("^\\d{1,9}$")
        val PAGE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val MAX_PEOPLE = 200
    }
}

/** Who belongs on a page and when they last looked; who is there right now comes over the WebSocket. */
@RestController
@RequestMapping("/api/v1/presence")
class PagePresenceController(
    private val pagePresence: PagePresence,
) {
    @GetMapping
    suspend fun people(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam key: String,
    ): List<PagePerson> = pagePresence.people(user, key)
}
