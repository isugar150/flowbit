package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.automation.AutomationEngine
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.issue.domain.IssueStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** What a Git server does to an issue, and the repository an issue's work is in (split out of [IssueService], D-141, D-148). */
@Service
class IssueGitService(
    private val lookup: IssueLookup,
    private val repository: IssueRepository,
    private val queries: IssueQueries,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val statuses: IssueStatuses,
    private val clock: Clock,
    private val automations: AutomationEngine,
) {
    // --- what a Git server does (docs/features/git-integration.md, D-141) --------------------

    /**
     * The issues in use with these numbers, for a Git server. There is no person to check access
     * for; which repositories may reach an issue is decided by the Git module (D-148, D-149).
     */
    suspend fun gitTargets(numbers: Collection<Int>): List<Issue> =
        numbers.distinct().mapNotNull { repository.findByNumber(it) }.filter { it.deletedAt == null }

    /**
     * Moves an issue to [status] for a Git server, and only from one of [from]: the rules never
     * move an issue back, so a status set by hand is not undone by Git (D-141). Null when the
     * issue is deleted or not in one of [from]. Recorded as [actor], with whom to tell, like a
     * change by hand.
     */
    @Transactional
    suspend fun changeStatusFromGit(
        issueId: UUID,
        status: IssueStatus,
        from: Set<IssueStatus>,
        actor: Actor,
    ): IssueChange? {
        val issue = repository.lockById(issueId) ?: return null
        if (issue.deletedAt != null || issue.status !in from) return null
        val before = queries.find(issue.number) ?: return null
        issue.status = status
        issue.statusId = statuses.defaultOf(status).id
        issue.version += 1
        issue.updatedAt = clock.instant()
        repository.save(issue)
        val transitioned = checkNotNull(lookup.detail(issue.number))
        val automationNotices = automations.apply(before, transitioned, actor.name)
        val after = checkNotNull(lookup.detail(issue.number))
        after.assignees.filter { person -> before.assignees.none { it.id == person.id } }.forEach { activities.subscribe(issue.id, it.id) }
        val events = activities.diff(before, after).map(actor::mark)
        activities.record(issue.id, actor.userId, events, issue.updatedAt)
        return IssueChange(after, events, activities.recipientIds(issue.id).filter { it != actor.userId }, automationNotices = automationNotices)
    }

    /** The issue, once [user] may edit it: for making its branch on a Git server (D-159). */
    suspend fun editableForGit(
        user: AuthenticatedUser,
        number: Int,
    ): Issue = lookup.editable(lookup.access(user, number)).issue

    /**
     * Adds the person a Git server says worked on the issue to its assignees (D-156): someone
     * who pushed to its branch. Nothing happens when they already are one, when the issue has as
     * many as it may, or when they could not be given it by hand (a project they are not in).
     */
    suspend fun addAssigneeFromGit(
        issueId: UUID,
        actor: Actor,
    ): IssueChange? {
        val userId = actor.userId ?: return null
        val issue = repository.findById(issueId) ?: return null
        if (issue.deletedAt != null) return null
        val before = queries.find(issue.number) ?: return null
        val current = before.assignees.map { it.id }
        if (userId in current || current.size >= MAX_ASSIGNEES) return null
        val assignable = issue.projectId?.let { queries.isProjectMember(it, userId) } ?: queries.isActiveUser(userId)
        if (!assignable) return null
        issue.version += 1
        issue.updatedAt = clock.instant()
        repository.save(issue)
        queries.replaceAssignees(issue.id, current + userId, issue.updatedAt)
        val after = checkNotNull(lookup.detail(issue.number))
        val events = activities.diff(before, after).map(actor::mark)
        activities.record(issue.id, actor.userId, events, issue.updatedAt)
        return IssueChange(after, events, activities.recipientIds(issue.id).filter { it != actor.userId })
    }

    /**
     * What to tell about Git entries that were just recorded: they go only to API notifications
     * that ask for them (IssueNotification.OPT_IN_TYPES), to the people who follow the issue.
     */
    suspend fun gitNotice(
        issueId: UUID,
        actor: Actor,
        events: List<IssueEvent>,
    ): IssueChange? {
        if (events.isEmpty()) return null
        val issue = repository.findById(issueId) ?: return null
        val view = lookup.detail(issue.number) ?: return null
        return IssueChange(view, events.map(actor::mark), activities.recipientIds(issue.id).filter { it != actor.userId })
    }

    /**
     * The repository picked on the issue (D-148), or the one its first branch or pull request came
     * from (D-153). In a project that chose its repositories it counts while it is still one of them.
     */
    suspend fun gitRepositoryOf(issue: Issue): IssueGitRepositoryView? {
        val picked = extras.issueGitRepository(issue.id) ?: return null
        val options = issue.projectId?.let { extras.projectGitRepositories(it) }.orEmpty()
        if (options.isEmpty()) return picked
        return picked.takeIf { choice -> options.any { it.connectionId == choice.connectionId && it.repository == choice.repository } }
    }

    /**
     * Picks which of its project's repositories the issue's work is in (D-148), or takes the pick
     * away with nulls. Needs the right to edit the issue. Returns the issue's number.
     */
    suspend fun setGitRepository(
        user: AuthenticatedUser,
        number: Int,
        connectionId: UUID?,
        rawRepository: String?,
    ): Int {
        val issue = lookup.editable(lookup.access(user, number)).issue
        val repository = rawRepository?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (connectionId != null && repository != null) {
            val options = issue.projectId?.let { extras.projectGitRepositories(it) }.orEmpty()
            if (options.none { it.connectionId == connectionId && it.repository == repository }) {
                throw ValidationException("repository", "NOT_IN_PROJECT", "이 티켓의 프로젝트에 정해진 저장소가 아닙니다")
            }
            extras.setIssueGitRepository(issue.id, connectionId, repository, clock.instant())
        } else {
            extras.setIssueGitRepository(issue.id, null, null, clock.instant())
        }
        return number
    }

    /** Adds history entries for something a Git server reported, marked as coming from Git. */
    suspend fun recordFromGit(
        issueId: UUID,
        actor: Actor,
        events: List<IssueEvent>,
    ) {
        if (events.isNotEmpty()) activities.record(issueId, actor.userId, events.map(actor::mark), clock.instant())
    }
}
