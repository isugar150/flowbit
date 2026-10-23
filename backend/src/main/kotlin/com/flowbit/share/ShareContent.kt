package com.flowbit.share

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.MyIssueScope
import com.flowbit.issue.application.Viewer
import com.flowbit.issue.domain.IssueStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/** The kinds of page that can be shared. Adding one means adding a [ShareContentProvider] for it. */
enum class ShareKind {
    MY_ISSUES_CALENDAR,
    MY_ISSUES_TIMELINE,
    ISSUE,
}

/** A file a shared page points at, by the key in its address: a picture (`<uuid>.webp`) or a file placed in a text (`<uuid>`). */
data class SharedFile(
    val kind: SharedFileKind,
    val key: String,
)

enum class SharedFileKind { IMAGE, EDITOR_FILE }

/**
 * What one kind of shared page needs: a check of the parameters the sharing person sent, and the
 * content shown at the link. Everything else (links, the stop, the record of who opened it, the
 * clean-up) is [ShareService]'s and is the same for every kind (docs/features/sharing.md). A kind whose page leads to
 * others (a shared issue leads to its sub-issues and documents) also says what it leads to ([item]) and which
 * files it may serve ([mayServe]); the others keep the defaults, which lead nowhere.
 */
interface ShareContentProvider {
    val kind: ShareKind

    /** The parameters in the form they are stored in, without anything the kind does not use. Throws [ValidationException] when they are not right. */
    fun normalize(params: Map<String, String>): Map<String, String>

    /** Whether [owner] may share this at all, checked when the link is made. Throws when not; the default lets everyone share what is theirs. */
    suspend fun check(
        owner: AuthenticatedUser,
        params: Map<String, String>,
    ) {}

    /**
     * The content to show to anyone who opens the link. It is made each time the link is opened,
     * from what [ownerId] (who shared it) may see now, and holds no more than the kind shows. [token] is the link's, for the
     * addresses in the content that lead back into it.
     */
    suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
    ): Any

    /**
     * A page the shared one leads to, of the kind `type` and the name `id`, or null when there is none or it is not part of
     * what was shared. Made like [content].
     */
    suspend fun item(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
        type: String,
        id: String,
    ): Any? = null

    /** Whether [file] is one the shared pages point at, so that it may be served to whoever has the link. */
    suspend fun mayServe(
        ownerId: UUID,
        params: Map<String, String>,
        file: SharedFile,
    ): Boolean = false
}

/** An issue as the shared calendar shows it: where it is and what it is, and nothing else about it. */
data class SharedCalendarIssue(
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val startDate: LocalDate?,
    val dueDate: LocalDate,
)

data class SharedCalendar(
    val issues: List<SharedCalendarIssue>,
)

/**
 * An issue as the shared timeline draws it: the days it covers (`createdAt` and `updatedAt` are
 * for the issues that have no start or due date), its status, and the name of its project, which
 * the chart groups by. No project id, people, text, or the issues above it.
 */
data class SharedTimelineIssue(
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val project: String?,
)

data class SharedTimeline(
    val issues: List<SharedTimelineIssue>,
)

/** The parameters the shared pages of "내 티켓" have in common: a month (`2026-10`) and which tab's issues (`scope`). */
private object MyIssuesParams {
    private val MONTH = Regex("""\d{4}-\d{2}""")
    private val YEARS = 1900..2999

    fun month(text: String?): YearMonth? =
        if (text != null && MONTH.matches(text)) {
            runCatching { YearMonth.parse(text) }.getOrNull()?.takeIf { it.year in YEARS }
        } else {
            null
        }

    fun scope(text: String?): MyIssueScope? = MyIssueScope.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }

    fun requireMonth(
        params: Map<String, String>,
        name: String,
    ): YearMonth = month(params[name]) ?: throw ValidationException("params.$name", "INVALID", "달은 2026-10 모양으로 적어야 합니다")

    fun requireScope(params: Map<String, String>): MyIssueScope = scope(params["scope"]) ?: throw ValidationException("params.scope", "INVALID", "값이 올바르지 않습니다")
}

/**
 * The calendar of "내 티켓" for one month: the issues of the sharing person (the ones assigned to
 * them, or the ones they made: `scope`) that are on a day of that month, on their due date or on
 * the days from their start to their due date.
 */
@Component
class MyIssuesCalendarShare(
    private val queries: IssueQueries,
) : ShareContentProvider {
    override val kind = ShareKind.MY_ISSUES_CALENDAR

    override fun normalize(params: Map<String, String>): Map<String, String> {
        val month = MyIssuesParams.requireMonth(params, "month")
        val scope = MyIssuesParams.requireScope(params)
        return mapOf("month" to month.toString(), "scope" to scope.name.lowercase())
    }

    override suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
    ): SharedCalendar {
        val month = checkNotNull(MyIssuesParams.month(params["month"]))
        val scope = checkNotNull(MyIssuesParams.scope(params["scope"]))
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val issues =
            queries
                .listForUser(Viewer(ownerId, seesAllProjects = false), scope, deleted = false)
                .mapNotNull { issue ->
                    val due = issue.dueDate ?: return@mapNotNull null
                    if (due < first || (issue.startDate ?: due) > last) return@mapNotNull null
                    SharedCalendarIssue(issue.number, issue.key, issue.title, issue.status, issue.startDate, due)
                }.sortedWith(compareBy({ it.startDate ?: it.dueDate }, { it.number }))
        return SharedCalendar(issues)
    }
}

/**
 * The timeline of "내 티켓" from one month to another (the last one included, [MAX_MONTHS] at
 * most): the issues of the sharing person that cover a day of that time. An issue covers the days
 * from its start date (or the day it was made) to its due date; without a due date, to the day it
 * was last changed when it is finished and to today when it is not, as the chart draws it.
 */
@Component
class MyIssuesTimelineShare(
    private val queries: IssueQueries,
    private val clock: Clock,
) : ShareContentProvider {
    override val kind = ShareKind.MY_ISSUES_TIMELINE

    override fun normalize(params: Map<String, String>): Map<String, String> {
        val from = MyIssuesParams.requireMonth(params, "from")
        val to = MyIssuesParams.requireMonth(params, "to")
        val scope = MyIssuesParams.requireScope(params)
        if (to < from) throw ValidationException("params.to", "BEFORE_FROM", "끝 달은 시작 달보다 빠를 수 없습니다")
        if (Period.between(from.atDay(1), to.atDay(1)).toTotalMonths() >= MAX_MONTHS) {
            throw ValidationException("params.to", "TOO_LONG", "기간은 ${MAX_MONTHS}개월까지 고를 수 있습니다")
        }
        return mapOf("from" to from.toString(), "to" to to.toString(), "scope" to scope.name.lowercase())
    }

    override suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
    ): SharedTimeline {
        val first = checkNotNull(MyIssuesParams.month(params["from"])).atDay(1)
        val last = checkNotNull(MyIssuesParams.month(params["to"])).atEndOfMonth()
        val scope = checkNotNull(MyIssuesParams.scope(params["scope"]))
        val today = LocalDate.now(clock)
        val issues =
            queries
                .listForUser(Viewer(ownerId, seesAllProjects = false), scope, deleted = false)
                .filter { issue ->
                    val start = issue.startDate ?: day(issue.createdAt)
                    val finished = issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED
                    val end = issue.dueDate ?: if (finished) day(issue.updatedAt) else today
                    // The bar covers the days between the two, whichever comes first.
                    minOf(start, end) <= last && maxOf(start, end) >= first
                }.sortedBy { it.number }
                .map { SharedTimelineIssue(it.number, it.key, it.title, it.status, it.startDate, it.dueDate, it.createdAt, it.updatedAt, it.project?.name) }
        return SharedTimeline(issues)
    }

    private fun day(moment: Instant): LocalDate = moment.atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        /** How many months one shared timeline can cover. */
        const val MAX_MONTHS = 24L
    }
}
