package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.richtext.RichText
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.project.application.ProjectService
import com.flowbit.project.domain.Project
import org.springframework.stereotype.Component
import java.util.UUID

/** How many people one issue can be given to. */
internal const val MAX_ASSIGNEES = 10

/** An issue the caller may see, and what their role lets them do with it. */
data class IssueAccess(
    val issue: Issue,
    /** Whether the caller's role lets them change the issue (and delete or restore it). */
    val mayEdit: Boolean,
) {
    /** A deleted issue is read-only for everyone until it is restored. */
    val canEdit: Boolean
        get() = mayEdit && issue.deletedAt == null
}

/**
 * Who may see and change an issue, and the lookups every part of the issue code starts from (split out of
 * [IssueService], which was one class for everything). An issue linked to a project follows that project's
 * access rules; one linked to none is open to every signed-in person. An issue the caller may not see is
 * "not found".
 */
@Component
class IssueLookup(
    private val projectService: ProjectService,
    private val repository: IssueRepository,
    private val queries: IssueQueries,
    private val activities: IssueActivityStore,
    private val richText: RichText,
) {
    /** The issue with its body, as its page shows it. */
    suspend fun detail(number: Int): IssueView? = queries.find(number)?.let { it.copy(content = richText.load(RichText.ContentTable.ISSUES, it.id)) }

    /** Everyone, administrators included, sees the issues of the projects they are a member of (D-131). */
    fun viewer(user: AuthenticatedUser) = Viewer(user.id, seesAllProjects = false)

    suspend fun access(
        user: AuthenticatedUser,
        number: Int,
    ): IssueAccess = repository.findByNumber(number)?.let { accessOf(user, it) } ?: throw notFound()

    suspend fun accessById(
        user: AuthenticatedUser,
        issueId: UUID,
    ): IssueAccess = repository.findById(issueId)?.let { accessOf(user, it) } ?: throw notFound()

    /** Null when the issue is linked to a project the caller may not enter. */
    suspend fun accessOf(
        user: AuthenticatedUser,
        issue: Issue,
    ): IssueAccess? {
        val mayEdit = if (issue.projectId == null) true else (projectService.accessById(user, issue.projectId!!) ?: return null).role.canEdit
        return IssueAccess(issue, mayEdit)
    }

    fun editable(access: IssueAccess): IssueAccess {
        if (!access.mayEdit) throw noEditRight()
        if (access.issue.deletedAt != null) throw ForbiddenException("삭제된 티켓은 수정할 수 없습니다. 먼저 복원하세요")
        return access
    }

    /** A project the caller may put issues into. */
    suspend fun editableProject(
        user: AuthenticatedUser,
        projectId: String,
    ): Project {
        val access = projectService.access(user, projectId)
        if (!access.role.canEdit) throw noEditRight()
        return access.project
    }

    suspend fun editableProjectById(
        user: AuthenticatedUser,
        projectId: UUID,
    ): Project = projectService.accessById(user, projectId)?.takeIf { it.role.canEdit }?.project ?: throw noEditRight()

    /** Whether the person could be given the issue or be told about it: a member of its project, or any active person for an issue on its own. */
    suspend fun canSee(
        issue: Issue,
        userId: UUID,
    ): Boolean = issue.projectId?.let { queries.isProjectMember(it, userId) } ?: queries.isActiveUser(userId)

    /** Whom a change to the issue is told to: its followers, without the person who made it. */
    suspend fun recipients(
        issueId: UUID,
        actor: AuthenticatedUser,
    ): List<UUID> = activities.recipientIds(issueId).filter { it != actor.id }

    suspend fun actorName(user: AuthenticatedUser): String = activities.displayName(user.id)

    fun noEditRight() = ForbiddenException("이 티켓을 쓰거나 수정할 권한이 없습니다")

    fun notFound() = NotFoundException("ISSUE_NOT_FOUND", "티켓을 찾을 수 없습니다")
}
