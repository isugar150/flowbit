package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.NotFoundException
import com.flowbit.issue.application.IssueService
import com.flowbit.project.application.ProjectService
import org.springframework.stereotype.Component

/**
 * Who may see a page. Anyone who can open its project (the view-only role included), or the issue
 * it is, or the issue it is on, can comment on it (D-165); a page the person cannot open "is not
 * found". Whether anything can still be added is the page's [ContentContext.readOnly].
 */
@Component
class ContentAccess(
    private val directory: ContentDirectory,
    private val projectService: ProjectService,
    private val issueService: IssueService,
) {
    suspend fun resolve(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ContentContext {
        val context = directory.describe(target) ?: throw notFound()
        val visible =
            when {
                // An issue, or a document that is on one: whoever may see the issue.
                context.issueNumber != null -> issueService.canRead(user, context.issueNumber)
                else -> context.projectId?.let { projectService.accessById(user, it) } != null
            }
        if (!visible) throw notFound()
        return context
    }

    private fun notFound() = NotFoundException("CONTENT_NOT_FOUND", "찾을 수 없습니다")
}
