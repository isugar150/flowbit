package com.flowbit.issue.application

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class IssueActivityType {
    CREATED,
    TITLE_CHANGED,
    DESCRIPTION_CHANGED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    ASSIGNEE_CHANGED,
    DUE_DATE_CHANGED,
    MILESTONE_CHANGED,
    LABELS_CHANGED,
    PROJECT_CHANGED,
    PARENT_CHANGED,

    /** A property a person added to this issue was added, changed, renamed or deleted (D-186). */
    CUSTOM_PROPERTY_CHANGED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED,
    DOCUMENT_ADDED,
    DOCUMENT_REMOVED,
    DOCUMENT_RESTORED,
    DELETED,
    RESTORED,

    /** A Git server linked a branch whose name holds the issue's key (docs/features/git-integration.md). */
    GIT_BRANCH_LINKED,

    /** A pull request naming the issue was opened (or first seen). */
    GIT_PR_LINKED,
    GIT_PR_MERGED,

    /** A pull request was closed without being merged. */
    GIT_PR_CLOSED,

    /** Someone took a branch or pull request off the issue (D-161). */
    GIT_UNLINKED,

    /** Commits naming the issue, or pushed to its branch, reached the Git server. */
    GIT_COMMITS_PUSHED,

    /** Announced like the others but not kept in the history: the comment itself is what the page shows. */
    COMMENT_ADDED,

    /** People were tagged with @ in the body. Announced only, like a comment. */
    MENTIONED,
}

/** One thing that changed, before it is stored. Names in `detail` are the names at this moment. */
data class IssueEvent(
    val type: IssueActivityType,
    val detail: Map<String, Any?> = emptyMap(),
)

/** A stored entry of an issue's history. */
data class IssueActivityView(
    val id: UUID,
    val type: IssueActivityType,
    /** Raw JSON. An entry a Git server made holds `via: {source: "git", name}`. */
    val detail: String,
    /** Null when a Git server made the change for someone who has no account here. */
    val actor: IssuePerson?,
    val createdAt: Instant,
    /** The emoji on it (D-164). */
    val reactions: List<ReactionSummary> = emptyList(),
)

/**
 * Who made a change: a person signed in, or a Git server acting for someone (D-141). [userId] is
 * null when the Git user matched no account; [via] then names them, and is kept in every entry.
 */
data class Actor(
    val userId: UUID?,
    /** As notifications show it: the display name, or "kimjm(Git)". */
    val name: String,
    val via: Map<String, Any?>? = null,
) {
    /** Adds [via] to an entry's details, so the history can say the change came from Git. */
    fun mark(event: IssueEvent): IssueEvent = via?.let { event.copy(detail = event.detail + ("via" to it)) } ?: event
}

/** What an issue change leaves behind for the caller: the issue, what changed, and whom to tell. */
data class IssueChange(
    val issue: IssueView,
    val events: List<IssueEvent>,
    /** Subscribers of the issue and of its project, other than the person who made the change. They are told what happened to the issue. */
    val recipientIds: List<UUID>,
    /** The projects whose lists the change shows in: the one it is linked to, and the one it left. */
    val projectIds: List<UUID> = listOfNotNull(issue.project?.id),
    /** The people tagged by a comment or by the body in this change. A comment, and a tag in the body, is told to them alone (D-166). */
    val taggedIds: List<UUID> = emptyList(),
) {
    companion object {
        /** What is said on an issue rather than done to it: told to the people tagged, not to the subscribers. */
        val TALK = setOf(IssueActivityType.COMMENT_ADDED, IssueActivityType.MENTIONED)
    }
}

/** The history of issues and who is subscribed to them (`issue_activities`, `issue_subscriptions`). */
@Component
class IssueActivityStore(
    private val databaseClient: DatabaseClient,
    private val jsonMapper: JsonMapper,
) {
    /** What two versions of an issue differ in, as history entries. */
    fun diff(
        before: IssueView,
        after: IssueView,
    ): List<IssueEvent> {
        fun name(person: IssuePerson) = person.displayName ?: person.loginId
        val events = mutableListOf<IssueEvent>()
        if (before.title != after.title) {
            events += IssueEvent(IssueActivityType.TITLE_CHANGED, mapOf("from" to before.title, "to" to after.title))
        }
        if (before.description != after.description) events += IssueEvent(IssueActivityType.DESCRIPTION_CHANGED)
        if (before.status != after.status) {
            events += IssueEvent(IssueActivityType.STATUS_CHANGED, mapOf("from" to before.status.name, "to" to after.status.name))
        }
        if (before.priority != after.priority) {
            events += IssueEvent(IssueActivityType.PRIORITY_CHANGED, mapOf("from" to before.priority.name, "to" to after.priority.name))
        }
        // Like labels: who was added and who was taken off (entries before D-125 hold `from`/`to`).
        val assigned = after.assignees.filter { person -> before.assignees.none { it.id == person.id } }.map(::name)
        val unassigned = before.assignees.filter { person -> after.assignees.none { it.id == person.id } }.map(::name)
        if (assigned.isNotEmpty() || unassigned.isNotEmpty()) {
            events += IssueEvent(IssueActivityType.ASSIGNEE_CHANGED, mapOf("added" to assigned, "removed" to unassigned))
        }
        if (before.dueDate != after.dueDate || before.startDate != after.startDate) {
            // One date, or "first day ~ last day" for a span.
            fun dates(issue: IssueView) = issue.dueDate?.let { due -> issue.startDate?.let { "$it ~ $due" } ?: due.toString() }
            events += IssueEvent(IssueActivityType.DUE_DATE_CHANGED, mapOf("from" to dates(before), "to" to dates(after)))
        }
        if (before.milestone?.id != after.milestone?.id) {
            events += IssueEvent(IssueActivityType.MILESTONE_CHANGED, mapOf("from" to before.milestone?.name, "to" to after.milestone?.name))
        }
        if (before.project?.id != after.project?.id) {
            events += IssueEvent(IssueActivityType.PROJECT_CHANGED, mapOf("from" to before.project?.name, "to" to after.project?.name))
        }
        if (before.parent?.number != after.parent?.number) {
            events += IssueEvent(IssueActivityType.PARENT_CHANGED, mapOf("from" to before.parent?.key, "to" to after.parent?.key))
        }
        val added = after.labels.filter { label -> before.labels.none { it.id == label.id } }.map { it.name }
        val removed = before.labels.filter { label -> after.labels.none { it.id == label.id } }.map { it.name }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            events += IssueEvent(IssueActivityType.LABELS_CHANGED, mapOf("added" to added, "removed" to removed))
        }
        return events
    }

    /**
     * Adds entries to an issue's history. Typing is saved every few seconds, so a title or body
     * edit by the same person shortly after their last one extends that entry instead of adding another.
     */
    suspend fun record(
        issueId: UUID,
        actorId: UUID?,
        events: List<IssueEvent>,
        now: Instant,
    ) {
        for (event in events) {
            if (actorId != null && event.type in MERGED_TYPES && mergeIntoLatest(issueId, actorId, event, now)) continue
            databaseClient
                .sql(
                    """
                    insert into issue_activities (id, issue_id, actor_id, type, detail, created_at)
                    values (:id, :issueId, :actorId, :type, cast(:detail as jsonb), :now)
                    """.trimIndent(),
                ).bind(
                    "id",
                    com.flowbit.common.UuidV7
                        .generate(now),
                ).bind("issueId", issueId)
                .let { if (actorId == null) it.bindNull("actorId", UUID::class.java) else it.bind("actorId", actorId) }
                .bind("type", event.type.name)
                .bind("detail", jsonMapper.writeValueAsString(event.detail))
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    /** True when the issue's latest entry is the same kind of edit by the same person, made recently. */
    private suspend fun mergeIntoLatest(
        issueId: UUID,
        actorId: UUID,
        event: IssueEvent,
        now: Instant,
    ): Boolean {
        // Keeps the original "from" of a title edit and takes the newest "to".
        val updated =
            databaseClient
                .sql(
                    """
                    update issue_activities a
                    set detail = a.detail || cast(:patch as jsonb), created_at = :now
                    where a.seq = (select max(seq) from issue_activities where issue_id = :issueId)
                      and a.issue_id = :issueId and a.type = :type and a.actor_id = :actorId and a.created_at > :since
                    """.trimIndent(),
                ).bind("patch", jsonMapper.writeValueAsString(event.detail.filterKeys { it == "to" }))
                .bind("now", now)
                .bind("issueId", issueId)
                .bind("type", event.type.name)
                .bind("actorId", actorId)
                .bind("since", now.minus(MERGE_WINDOW))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        return updated > 0
    }

    /** The issue an entry of the history belongs to. */
    suspend fun issueIdOf(activityId: UUID): UUID? =
        databaseClient
            .sql("select issue_id from issue_activities where id = :id")
            .bind("id", activityId)
            .map { row -> checkNotNull(row.get("issue_id", UUID::class.java)) }
            .one()
            .awaitSingleOrNull()

    suspend fun list(issueId: UUID): List<IssueActivityView> =
        databaseClient
            .sql(
                """
                select a.id, a.type, a.detail::text as detail, a.created_at,
                       u.id as actor_id, u.login_id, u.display_name, u.avatar_key
                from issue_activities a left join users u on u.id = a.actor_id
                where a.issue_id = :issueId
                order by a.seq
                """.trimIndent(),
            ).bind("issueId", issueId)
            .map { row ->
                IssueActivityView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    type = IssueActivityType.valueOf(checkNotNull(row.get("type", String::class.java))),
                    detail = checkNotNull(row.get("detail", String::class.java)),
                    actor = row.get("actor_id", UUID::class.java)?.let { person(row) },
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** Subscribing twice is the same as once. */
    suspend fun subscribe(
        issueId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("insert into issue_subscriptions (issue_id, user_id) values (:issueId, :userId) on conflict do nothing")
            .bind("issueId", issueId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun unsubscribe(
        issueId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("delete from issue_subscriptions where issue_id = :issueId and user_id = :userId")
            .bind("issueId", issueId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Active users subscribed to the issue, in the order they subscribed. */
    suspend fun subscribers(issueId: UUID): List<IssuePerson> =
        databaseClient
            .sql(
                """
                select u.id as actor_id, u.login_id, u.display_name, u.avatar_key
                from issue_subscriptions s join users u on u.id = s.user_id
                where s.issue_id = :issueId and u.status = 'ACTIVE'
                order by s.created_at, u.login_id
                """.trimIndent(),
            ).bind("issueId", issueId)
            .map { row -> person(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Whom a change to the issue is told to: its subscribers, and whoever follows the project it is
     * linked to and may still enter that project. Active users only.
     */
    suspend fun recipientIds(issueId: UUID): List<UUID> =
        databaseClient
            .sql(
                """
                select u.id
                from users u
                where u.status = 'ACTIVE'
                  and (exists (select 1 from issue_subscriptions s where s.issue_id = :issueId and s.user_id = u.id)
                       or exists (
                           select 1 from issues i join project_subscriptions ps on ps.project_id = i.project_id
                           where i.id = :issueId and ps.user_id = u.id
                             and exists (select 1 from project_members pm where pm.project_id = i.project_id and pm.user_id = u.id)
                       ))
                order by u.login_id
                """.trimIndent(),
            ).bind("issueId", issueId)
            .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun displayName(userId: UUID): String =
        databaseClient
            .sql("select coalesce(display_name, login_id) as name from users where id = :id")
            .bind("id", userId)
            .map { row -> checkNotNull(row.get("name", String::class.java)) }
            .one()
            .awaitSingleOrNull()
            .orEmpty()

    private fun person(row: io.r2dbc.spi.Readable) =
        IssuePerson(
            id = checkNotNull(row.get("actor_id", UUID::class.java)),
            loginId = checkNotNull(row.get("login_id", String::class.java)),
            displayName = row.get("display_name", String::class.java),
            avatarKey = row.get("avatar_key", String::class.java),
        )

    private companion object {
        val MERGED_TYPES = setOf(IssueActivityType.TITLE_CHANGED, IssueActivityType.DESCRIPTION_CHANGED)
        val MERGE_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
