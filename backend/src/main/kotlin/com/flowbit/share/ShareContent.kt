package com.flowbit.share

import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.MyIssueScope
import com.flowbit.issue.application.Viewer
import com.flowbit.issue.domain.IssueStatus
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The kinds of page that can be shared. Adding one means adding a [ShareContentProvider] for it. */
enum class ShareKind {
    MY_ISSUES_CALENDAR,
}

/**
 * What one kind of shared page needs: a check of the parameters the sharing person sent, and the
 * content shown at the link. Everything else (links, the stop, the record of who opened it, the
 * clean-up) is [ShareService]'s and is the same for every kind (docs/features/sharing.md).
 */
interface ShareContentProvider {
    val kind: ShareKind

    /** The parameters in the form they are stored in, without anything the kind does not use. Throws [ValidationException] when they are not right. */
    fun normalize(params: Map<String, String>): Map<String, String>

    /**
     * The content to show to anyone who opens the link. It is made each time the link is opened,
     * from what [ownerId] (who shared it) may see now, and holds no more than the kind shows.
     */
    suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
    ): Any
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
        val month = params["month"]?.let(::monthOf) ?: throw ValidationException("params.month", "INVALID", "달은 2026-10 모양으로 적어야 합니다")
        val scope = scopeOf(params["scope"]) ?: throw ValidationException("params.scope", "INVALID", "값이 올바르지 않습니다")
        return mapOf("month" to month.toString(), "scope" to scope.name.lowercase())
    }

    override suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
    ): SharedCalendar {
        val month = checkNotNull(params["month"]?.let(::monthOf))
        val scope = checkNotNull(scopeOf(params["scope"]))
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

    private fun monthOf(text: String): YearMonth? =
        if (MONTH.matches(text)) {
            runCatching { YearMonth.parse(text) }.getOrNull()?.takeIf { it.year in YEARS }
        } else {
            null
        }

    private fun scopeOf(text: String?): MyIssueScope? = MyIssueScope.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }

    private companion object {
        val MONTH = Regex("""\d{4}-\d{2}""")
        val YEARS = 1900..2999
    }
}
