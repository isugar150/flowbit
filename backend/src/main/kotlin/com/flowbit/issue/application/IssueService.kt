package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.media.application.FileStorage
import com.flowbit.milestone.application.MilestoneService
import com.flowbit.project.application.ProjectService
import com.flowbit.project.domain.Project
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What a new issue is made from. Only the title is required. */
data class NewIssue(
    val title: String,
    val description: String?,
    /** The body as an editor document. When present, `description` is derived from it. */
    val content: Any?,
    val status: IssueStatus?,
    val priority: IssuePriority?,
    val assigneeId: UUID?,
    val dueDate: LocalDate?,
    val milestoneId: UUID? = null,
    val labelIds: List<UUID> = emptyList(),
    /** The project to link the issue to; null leaves it on its own. */
    val projectKey: String? = null,
    /** Makes the new issue a sub-issue of this one. */
    val parentId: UUID? = null,
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

    /** A list of ids; anything else in the field is a validation error. */
    fun uuids(name: String): List<UUID> =
        (fields[name] as? List<*> ?: throw invalid(name)).map { value ->
            runCatching { UUID.fromString(value as String) }.getOrElse { throw invalid(name) }
        }

    fun date(name: String): LocalDate? = string(name)?.let { value -> runCatching { LocalDate.parse(value) }.getOrElse { throw invalid(name) } }

    private fun invalid(name: String) = ValidationException(name, "INVALID", "값이 올바르지 않습니다")
}

data class IssueActivityFeed(
    val items: List<IssueActivityView>,
    val subscribers: List<IssuePerson>,
    /** Whether the caller is one of the subscribers. */
    val subscribed: Boolean,
)

/** One issue with everything its page shows. */
data class IssueDetail(
    val issue: IssueView,
    /** Whether the caller may change the issue. */
    val canEdit: Boolean,
    val subIssues: List<IssueView>,
    val reactions: List<ReactionSummary>,
    val attachments: List<AttachmentView>,
    val documents: List<DocumentSummary>,
)

/** A document of an issue, with what its page needs to know about the issue. */
data class DocumentDetail(
    val document: DocumentSummary,
    /** Raw editor JSON, or null while the document is empty. */
    val content: String?,
    val issue: IssueView,
    val canEdit: Boolean,
)

/** An attached file ready to be sent. */
data class AttachmentFile(
    val attachment: AttachmentView,
    val path: Path,
)

/**
 * Issues. An issue stands on its own and may be linked to a project. Linked, it follows that
 * project's access rules (only people who may enter the project see it, and the view-only role
 * cannot change it); linked to none, every signed-in person may see and change it.
 * An issue the caller may not see is reported as "not found".
 */
@Service
class IssueService(
    private val projectService: ProjectService,
    private val repository: IssueRepository,
    private val queries: IssueQueries,
    private val entityTemplate: R2dbcEntityTemplate,
    private val richText: RichText,
    private val milestoneService: MilestoneService,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val fileStorage: FileStorage,
    private val clock: Clock,
) {
    /** Issues the caller may see; of one project when `projectKey` is given. */
    suspend fun list(
        user: AuthenticatedUser,
        projectKey: String?,
    ): List<IssueView> = queries.list(viewer(user), projectKey?.let { projectService.access(user, it).project.id })

    /** The caller's own issues ("내 이슈"). */
    suspend fun mine(
        user: AuthenticatedUser,
        scope: MyIssueScope,
    ): List<IssueView> = queries.listForUser(viewer(user), scope)

    suspend fun get(
        user: AuthenticatedUser,
        number: Int,
    ): IssueDetail {
        val access = access(user, number)
        val id = access.issue.id
        return IssueDetail(
            issue = checkNotNull(detail(number)),
            canEdit = access.canEdit,
            subIssues = queries.subIssues(viewer(user), id),
            reactions = extras.reactions(id, user.id),
            attachments = extras.attachments(id),
            documents = extras.documents(id),
        )
    }

    /** Whether the caller may see the issue; for deciding who hears about its changes. */
    suspend fun canRead(
        user: AuthenticatedUser,
        number: Int,
    ): Boolean = repository.findByNumber(number)?.let { accessOf(user, it) } != null

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        input: NewIssue,
    ): IssueChange {
        val project = input.projectKey?.let { editableProject(user, it) }
        val parent = input.parentId?.let { requireParent(user, it, null) }
        val title = input.title.trim()
        val document = input.content?.let { richText.parse(it) }
        val description = document?.plainText ?: input.description?.trim().orEmpty()
        val errors = textErrors(title, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        input.assigneeId?.let { requireAssignable(project?.id, it) }
        input.milestoneId?.let { requireMilestone(project?.id, it) }
        val labelIds = requireLabels(input.labelIds)

        val now = clock.instant()
        val issue =
            Issue(
                id = UuidV7.generate(now),
                projectId = project?.id,
                parentId = parent?.id,
                number = queries.nextNumber(),
                title = title,
                description = description,
                status = input.status ?: IssueStatus.BACKLOG,
                priority = input.priority ?: IssuePriority.NONE,
                assigneeId = input.assigneeId,
                dueDate = input.dueDate,
                milestoneId = input.milestoneId,
                createdBy = user.id,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        entityTemplate.insert(issue).awaitSingle()
        if (document != null) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        if (labelIds.isNotEmpty()) queries.replaceLabels(issue.id, labelIds)
        val events = listOf(IssueEvent(IssueActivityType.CREATED))
        activities.record(issue.id, user.id, events, now)
        // The author and the assignee follow the issue from the start.
        activities.subscribe(issue.id, user.id)
        input.assigneeId?.let { activities.subscribe(issue.id, it) }
        return IssueChange(checkNotNull(detail(issue.number)), events, recipients(issue.id, user))
    }

    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        number: Int,
        patch: IssuePatch,
    ): IssueChange {
        val issue = editable(access(user, number)).issue
        val before = queries.find(number) ?: throw notFound()

        if (patch.has("title")) issue.title = patch.string("title")?.trim().orEmpty()
        if (patch.has("description")) issue.description = patch.string("description")?.trim().orEmpty()
        val document = if (patch.has("content")) patch.raw("content")?.let { richText.parse(it) } else null
        if (patch.has("content")) issue.description = document?.plainText.orEmpty()
        val errors = textErrors(issue.title, issue.description)
        if (errors.isNotEmpty()) throw ValidationException(errors)

        patch.enum("status", IssueStatus::valueOf)?.let { issue.status = it }
        patch.enum("priority", IssuePriority::valueOf)?.let { issue.priority = it }
        if (patch.has("dueDate")) issue.dueDate = patch.date("dueDate")
        if (patch.has("projectKey")) {
            val projectId = patch.string("projectKey")?.let { editableProject(user, it).id }
            if (projectId != issue.projectId) {
                issue.projectId = projectId
                // A milestone belongs to one project; it cannot follow the issue to another.
                if (!patch.has("milestoneId")) issue.milestoneId = null
            }
        }
        if (patch.has("assigneeId")) {
            val assigneeId = patch.uuid("assigneeId")
            if (assigneeId != null && assigneeId != issue.assigneeId) requireAssignable(issue.projectId, assigneeId)
            issue.assigneeId = assigneeId
        }
        if (patch.has("milestoneId")) {
            val milestoneId = patch.uuid("milestoneId")
            if (milestoneId != null) requireMilestone(issue.projectId, milestoneId)
            issue.milestoneId = milestoneId
        }
        if (patch.has("parentId")) issue.parentId = patch.uuid("parentId")?.let { requireParent(user, it, issue).id }
        val labelIds = if (patch.has("labelIds")) requireLabels(patch.uuids("labelIds")) else null
        issue.version += 1
        issue.updatedAt = clock.instant()
        repository.save(issue)
        if (labelIds != null) queries.replaceLabels(issue.id, labelIds)
        if (patch.has("content")) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        val after = checkNotNull(detail(number))
        val events = activities.diff(before, after)
        activities.record(issue.id, user.id, events, issue.updatedAt)
        // Whoever is given the issue follows it from then on.
        if (after.assignee != null && after.assignee.id != before.assignee?.id) activities.subscribe(issue.id, after.assignee.id)
        return IssueChange(after, events, recipients(issue.id, user), listOfNotNull(before.project?.key, after.project?.key).distinct())
    }

    /** The history of an issue with who follows it. Anyone who may see the issue can read it. */
    suspend fun activity(
        user: AuthenticatedUser,
        number: Int,
    ): IssueActivityFeed {
        val issue = access(user, number).issue
        val subscribers = activities.subscribers(issue.id)
        return IssueActivityFeed(activities.list(issue.id), subscribers, subscribers.any { it.id == user.id })
    }

    /** Follow or stop following an issue. Open to everyone who can see it, the view-only role included. */
    suspend fun setSubscribed(
        user: AuthenticatedUser,
        number: Int,
        subscribed: Boolean,
    ): IssueActivityFeed {
        val issue = access(user, number).issue
        if (subscribed) activities.subscribe(issue.id, user.id) else activities.unsubscribe(issue.id, user.id)
        return activity(user, number)
    }

    /** Adds the caller's emoji to the issue or takes it back. Open to everyone who can see the issue. */
    suspend fun toggleReaction(
        user: AuthenticatedUser,
        number: Int,
        emoji: String,
    ): List<ReactionSummary> {
        if (emoji !in REACTIONS) throw ValidationException("emoji", "INVALID", "쓸 수 없는 이모지입니다")
        val issue = access(user, number).issue
        extras.toggleReaction(issue.id, user.id, emoji)
        return extras.reactions(issue.id, user.id)
    }

    @Transactional
    suspend fun attach(
        user: AuthenticatedUser,
        number: Int,
        rawFileName: String,
        bytes: ByteArray,
    ): AttachmentView {
        val issue = editable(access(user, number)).issue
        // Keep only the last path segment and no control characters: the name is shown and sent back in a header.
        val fileName =
            rawFileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim()
                .take(FILE_NAME_MAX_LENGTH)
                .ifEmpty { "file" }
        val now = clock.instant()
        val attachment = extras.addAttachment(issue.id, fileStorage.store(bytes), fileName, bytes.size.toLong(), user.id, now)
        activities.record(issue.id, user.id, listOf(IssueEvent(IssueActivityType.ATTACHMENT_ADDED, mapOf("to" to fileName))), now)
        return attachment
    }

    /** The file of an attachment, for someone who may see its issue. */
    suspend fun attachmentFile(
        user: AuthenticatedUser,
        attachmentId: UUID,
    ): AttachmentFile {
        val attachment = extras.findAttachment(attachmentId) ?: throw notFound()
        accessById(user, attachment.issueId)
        val path = fileStorage.find(attachment.fileKey) ?: throw notFound()
        return AttachmentFile(attachment, path)
    }

    /** Returns the number of the issue the attachment was on. */
    @Transactional
    suspend fun removeAttachment(
        user: AuthenticatedUser,
        attachmentId: UUID,
    ): Int {
        val attachment = extras.findAttachment(attachmentId) ?: throw notFound()
        val issue = editable(accessById(user, attachment.issueId)).issue
        extras.deleteAttachment(attachment.id)
        fileStorage.delete(attachment.fileKey)
        val event = IssueEvent(IssueActivityType.ATTACHMENT_REMOVED, mapOf("from" to attachment.fileName))
        activities.record(issue.id, user.id, listOf(event), clock.instant())
        return issue.number
    }

    @Transactional
    suspend fun createDocument(
        user: AuthenticatedUser,
        number: Int,
        rawTitle: String?,
    ): DocumentSummary {
        val issue = editable(access(user, number)).issue
        val title = documentTitle(rawTitle.orEmpty())
        val now = clock.instant()
        val document = extras.addDocument(issue.id, title, user.id, now)
        activities.record(issue.id, user.id, listOf(IssueEvent(IssueActivityType.DOCUMENT_ADDED, mapOf("to" to title))), now)
        return document
    }

    suspend fun document(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentDetail {
        val document = extras.findDocument(documentId) ?: throw notFound()
        val access = accessById(user, document.issueId)
        return DocumentDetail(
            document = document,
            content = richText.load(RichText.ContentTable.ISSUE_DOCUMENTS, document.id),
            issue = checkNotNull(queries.find(access.issue.number)),
            canEdit = access.canEdit,
        )
    }

    /** `title` and `content` change only when present in the patch; a null `content` empties the document. */
    @Transactional
    suspend fun updateDocument(
        user: AuthenticatedUser,
        documentId: UUID,
        patch: IssuePatch,
    ): DocumentDetail {
        val document = extras.findDocument(documentId) ?: throw notFound()
        editable(accessById(user, document.issueId))
        val title = if (patch.has("title")) documentTitle(patch.string("title").orEmpty()) else null
        if (patch.has("content")) {
            val parsed = patch.raw("content")?.let { richText.parse(it) }
            richText.store(RichText.ContentTable.ISSUE_DOCUMENTS, document.id, parsed)
        }
        extras.touchDocument(document.id, title, clock.instant())
        return document(user, documentId)
    }

    /** Returns the number of the issue the document belonged to. */
    @Transactional
    suspend fun removeDocument(
        user: AuthenticatedUser,
        documentId: UUID,
    ): Int {
        val document = extras.findDocument(documentId) ?: throw notFound()
        val issue = editable(accessById(user, document.issueId)).issue
        extras.deleteDocument(document.id)
        val event = IssueEvent(IssueActivityType.DOCUMENT_REMOVED, mapOf("from" to document.title))
        activities.record(issue.id, user.id, listOf(event), clock.instant())
        return issue.number
    }

    suspend fun actorName(user: AuthenticatedUser): String = activities.displayName(user.id)

    private suspend fun detail(number: Int): IssueView? = queries.find(number)?.let { it.copy(content = richText.load(RichText.ContentTable.ISSUES, it.id)) }

    private fun viewer(user: AuthenticatedUser) = Viewer(user.id, seesAllProjects = user.role == UserRole.ADMIN)

    private suspend fun access(
        user: AuthenticatedUser,
        number: Int,
    ): IssueAccess = repository.findByNumber(number)?.let { accessOf(user, it) } ?: throw notFound()

    private suspend fun accessById(
        user: AuthenticatedUser,
        issueId: UUID,
    ): IssueAccess = repository.findById(issueId)?.let { accessOf(user, it) } ?: throw notFound()

    /** Null when the issue is linked to a project the caller may not enter. */
    private suspend fun accessOf(
        user: AuthenticatedUser,
        issue: Issue,
    ): IssueAccess? {
        val projectId = issue.projectId ?: return IssueAccess(issue, canEdit = true)
        val project = projectService.accessById(user, projectId) ?: return null
        return IssueAccess(issue, project.role.canEdit)
    }

    private fun editable(access: IssueAccess): IssueAccess {
        if (!access.canEdit) throw noEditRight()
        return access
    }

    /** A project the caller may put issues into. */
    private suspend fun editableProject(
        user: AuthenticatedUser,
        projectKey: String,
    ): Project {
        val access = projectService.access(user, projectKey)
        if (!access.role.canEdit) throw noEditRight()
        return access.project
    }

    /**
     * The issue to hang `child` under: one the caller may see, not the child itself and not one
     * of its own descendants (which would make a loop). `child` is null for an issue being created.
     */
    private suspend fun requireParent(
        user: AuthenticatedUser,
        parentId: UUID,
        child: Issue?,
    ): Issue {
        val invalid = ValidationException("parentId", "INVALID_PARENT", "상위 이슈로 지정할 수 없는 이슈입니다")
        val parent = repository.findById(parentId)?.takeIf { accessOf(user, it) != null } ?: throw invalid
        if (child != null) {
            var ancestor: Issue? = parent
            var depth = 0
            while (ancestor != null) {
                if (ancestor.id == child.id || ++depth > MAX_DEPTH) throw invalid
                ancestor = ancestor.parentId?.let { repository.findById(it) }
            }
        }
        return parent
    }

    /** A linked issue can be given to members of its project; an issue on its own to any active person. */
    private suspend fun requireAssignable(
        projectId: UUID?,
        userId: UUID,
    ) {
        val assignable = if (projectId != null) queries.isProjectMember(projectId, userId) else queries.isActiveUser(userId)
        if (!assignable) {
            throw ValidationException("assigneeId", "NOT_A_MEMBER", "담당자는 이 프로젝트의 구성원이어야 합니다")
        }
    }

    /** Only milestones of the project the issue is linked to. */
    private suspend fun requireMilestone(
        projectId: UUID?,
        milestoneId: UUID,
    ) {
        if (projectId == null || !milestoneService.belongsTo(projectId, milestoneId)) {
            throw ValidationException("milestoneId", "NOT_IN_PROJECT", "이 프로젝트의 목표가 아닙니다")
        }
    }

    /** The ids without repeats, once every one of them is a label that exists. */
    private suspend fun requireLabels(labelIds: List<UUID>): Set<UUID> {
        val distinct = labelIds.toSet()
        if (distinct.size > MAX_LABELS) throw ValidationException("labelIds", "TOO_MANY", "라벨은 ${MAX_LABELS}개까지 붙일 수 있습니다")
        if (distinct.isNotEmpty() && queries.countLabels(distinct) != distinct.size) {
            throw ValidationException("labelIds", "UNKNOWN_LABEL", "없는 라벨입니다")
        }
        return distinct
    }

    private suspend fun recipients(
        issueId: UUID,
        actor: AuthenticatedUser,
    ): List<UUID> = activities.subscribers(issueId).map { it.id }.filter { it != actor.id }

    private fun documentTitle(raw: String): String {
        val title = raw.trim()
        if (title.length > Issue.TITLE_MAX_LENGTH) {
            throw ValidationException("title", "TOO_LONG", "문서 제목은 ${Issue.TITLE_MAX_LENGTH}자 이하여야 합니다")
        }
        return title
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

    private fun noEditRight() = ForbiddenException("이 이슈를 쓰거나 고칠 권한이 없습니다")

    private fun notFound() = NotFoundException("ISSUE_NOT_FOUND", "이슈를 찾을 수 없습니다")

    private data class IssueAccess(
        val issue: Issue,
        val canEdit: Boolean,
    )

    companion object {
        /** The emoji an issue can be reacted to with. */
        val REACTIONS = listOf("👍", "❤️", "🎉", "😄", "👀", "🚀", "🙏", "✅")
        private const val MAX_LABELS = 10
        private const val MAX_DEPTH = 50
        private const val FILE_NAME_MAX_LENGTH = 255
    }
}
