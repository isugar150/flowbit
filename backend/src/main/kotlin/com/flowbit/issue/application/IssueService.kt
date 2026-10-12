package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssuePreset
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.project.application.ProjectAccess
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What a new issue is made from. Only the title is required. */
data class NewIssue(
    val title: String,
    val description: String?,
    /** The body as an editor document. When present, `description` is derived from it. */
    val content: Any?,
    val preset: IssuePreset?,
    val status: IssueStatus?,
    val priority: IssuePriority?,
    val assigneeId: UUID?,
    val dueDate: LocalDate?,
)

/**
 * A partial change. A field is applied only when it was present in the request, so that
 * "not mentioned" and "set to nothing" (clearing the assignee or the due date) stay different.
 */
class IssuePatch(
    private val fields: Map<String, Any?>,
) {
    fun has(name: String): Boolean = fields.containsKey(name)

    fun string(name: String): String? = fields[name] as? String

    fun raw(name: String): Any? = fields[name]

    fun <T : Enum<T>> enum(
        name: String,
        parse: (String) -> T,
    ): T? = string(name)?.let { value -> runCatching { parse(value) }.getOrElse { throw invalid(name) } }

    fun uuid(name: String): UUID? = string(name)?.let { value -> runCatching { UUID.fromString(value) }.getOrElse { throw invalid(name) } }

    fun date(name: String): LocalDate? = string(name)?.let { value -> runCatching { LocalDate.parse(value) }.getOrElse { throw invalid(name) } }

    private fun invalid(name: String) = ValidationException(name, "INVALID", "값이 올바르지 않습니다")
}

/**
 * Issues of a project. Every call goes through ProjectService.access first, so an issue in a
 * project the caller may not enter is reported as "project not found".
 */
@Service
class IssueService(
    private val projectService: ProjectService,
    private val repository: IssueRepository,
    private val queries: IssueQueries,
    private val entityTemplate: R2dbcEntityTemplate,
    private val richText: RichText,
    private val clock: Clock,
) {
    suspend fun list(
        user: AuthenticatedUser,
        projectKey: String,
    ): List<IssueView> {
        val project = projectService.access(user, projectKey).project
        return queries.list(project.id, project.key)
    }

    suspend fun get(
        user: AuthenticatedUser,
        projectKey: String,
        number: Int,
    ): IssueView {
        val project = projectService.access(user, projectKey).project
        return detail(project.id, project.key, number) ?: throw notFound()
    }

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        projectKey: String,
        input: NewIssue,
    ): IssueView {
        val project = editable(projectService.access(user, projectKey)).project
        val title = input.title.trim()
        val document = input.content?.let { richText.parse(it) }
        val description = document?.plainText ?: input.description?.trim().orEmpty()
        val errors = textErrors(title, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        input.assigneeId?.let { requireAssignable(project.id, it) }

        val now = clock.instant()
        val issue =
            Issue(
                id = UuidV7.generate(now),
                projectId = project.id,
                number = queries.nextNumber(project.id),
                title = title,
                description = description,
                preset = input.preset,
                status = input.status ?: IssueStatus.BACKLOG,
                priority = input.priority ?: IssuePriority.NONE,
                assigneeId = input.assigneeId,
                dueDate = input.dueDate,
                createdBy = user.id,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        entityTemplate.insert(issue).awaitSingle()
        if (document != null) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        return checkNotNull(detail(project.id, project.key, issue.number))
    }

    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        projectKey: String,
        number: Int,
        patch: IssuePatch,
    ): IssueView {
        val project = editable(projectService.access(user, projectKey)).project
        val issue = repository.findByProjectIdAndNumber(project.id, number) ?: throw notFound()

        if (patch.has("title")) issue.title = patch.string("title")?.trim().orEmpty()
        if (patch.has("description")) issue.description = patch.string("description")?.trim().orEmpty()
        val document = if (patch.has("content")) patch.raw("content")?.let { richText.parse(it) } else null
        if (patch.has("content")) issue.description = document?.plainText.orEmpty()
        val errors = textErrors(issue.title, issue.description)
        if (errors.isNotEmpty()) throw ValidationException(errors)

        patch.enum("status", IssueStatus::valueOf)?.let { issue.status = it }
        patch.enum("priority", IssuePriority::valueOf)?.let { issue.priority = it }
        if (patch.has("preset")) issue.preset = patch.enum("preset", IssuePreset::valueOf)
        if (patch.has("dueDate")) issue.dueDate = patch.date("dueDate")
        if (patch.has("assigneeId")) {
            val assigneeId = patch.uuid("assigneeId")
            if (assigneeId != null && assigneeId != issue.assigneeId) requireAssignable(project.id, assigneeId)
            issue.assigneeId = assigneeId
        }
        issue.version += 1
        issue.updatedAt = clock.instant()
        repository.save(issue)
        if (patch.has("content")) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        return checkNotNull(detail(project.id, project.key, number))
    }

    private suspend fun detail(
        projectId: UUID,
        projectKey: String,
        number: Int,
    ): IssueView? =
        queries.find(projectId, projectKey, number)?.let {
            it.copy(content = richText.load(RichText.ContentTable.ISSUES, it.id))
        }

    private fun editable(access: ProjectAccess): ProjectAccess {
        if (!access.role.canEdit) throw ForbiddenException("이 프로젝트에서 이슈를 쓰거나 고칠 권한이 없습니다")
        return access
    }

    /** Only active members of the project can be assigned. */
    private suspend fun requireAssignable(
        projectId: UUID,
        userId: UUID,
    ) {
        if (!queries.isProjectMember(projectId, userId)) {
            throw ValidationException("assigneeId", "NOT_A_MEMBER", "담당자는 이 프로젝트의 구성원이어야 합니다")
        }
    }

    private fun textErrors(
        title: String,
        description: String,
    ): List<FieldError> =
        listOfNotNull(
            when {
                title.isEmpty() -> FieldError("title", "REQUIRED", "이슈 제목을 입력하세요")
                title.length > Issue.TITLE_MAX_LENGTH -> FieldError("title", "TOO_LONG", "이슈 제목은 ${Issue.TITLE_MAX_LENGTH}자 이하여야 합니다")
                else -> null
            },
            if (description.length > Issue.DESCRIPTION_MAX_LENGTH) {
                FieldError("description", "TOO_LONG", "설명이 너무 깁니다")
            } else {
                null
            },
        )

    private fun notFound() = NotFoundException("ISSUE_NOT_FOUND", "이슈를 찾을 수 없습니다")
}
