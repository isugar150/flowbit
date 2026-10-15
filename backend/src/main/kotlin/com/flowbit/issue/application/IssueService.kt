package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.richtext.RichTextDocument
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
    /**
     * The people the issue is given to, in order; empty for no one. Null leaves it to the default:
     * a sub-issue starts with its parent's assignees (D-129), any other issue with no one.
     */
    val assigneeIds: List<UUID>?,
    val dueDate: LocalDate?,
    val milestoneId: UUID? = null,
    val labelIds: List<UUID> = emptyList(),
    /** The project to link the issue to; null leaves it on its own. */
    val projectId: String? = null,
    /** Makes the new issue a sub-issue of this one. */
    val parentId: UUID? = null,
    /** With [dueDate], makes the issue cover a span of days. */
    val startDate: LocalDate? = null,
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
    /** Oldest first; the page mixes them into `items` by time. */
    val comments: List<CommentView>,
    val subscribers: List<IssuePerson>,
    /** Whether the caller is one of the subscribers. */
    val subscribed: Boolean,
)

/** One issue with everything its page shows. */
data class IssueDetail(
    val issue: IssueView,
    /** Whether the caller may change the issue. Never for a deleted one. */
    val canEdit: Boolean,
    /** Whether the caller's role would let them restore the issue if it is deleted. */
    val canRestore: Boolean,
    /** Whether a sub-issue may be made under it: the caller may edit, and it is not at the deepest level. */
    val canAddSubIssue: Boolean,
    val subIssues: List<IssueView>,
    val reactions: List<ReactionSummary>,
    val attachments: List<AttachmentView>,
    val documents: List<DocumentSummary>,
)

/** A comment that was written or changed, with whom to tell about it. */
data class CommentResult(
    val comment: CommentView,
    val change: IssueChange,
)

/** A document with what its page needs to know about the issue or the project it belongs to. */
data class DocumentDetail(
    val document: DocumentSummary,
    /** Raw editor JSON, or null while the document is empty. */
    val content: String?,
    val issue: IssueView?,
    val project: IssueProject?,
    val canEdit: Boolean,
    /** The documents it sits inside, the outermost first. */
    val ancestors: List<DocumentSummary>,
    /** The documents inside it. */
    val children: List<DocumentSummary>,
    /** Whether a document may be made inside it: the caller may edit, and it is not at the deepest level. */
    val canAddChild: Boolean,
    /** Whether the caller may bring this deleted document back. */
    val canRestore: Boolean = false,
)

/** What a document belonged to: an issue (by number) or a project (by id). */
data class DocumentOwner(
    val issueNumber: Int?,
    val projectId: UUID?,
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
    private val comments: IssueCommentStore,
    private val fileStorage: FileStorage,
    private val clock: Clock,
) {
    /**
     * Issues the caller may see; of one project when `projectId` is given. `deleted` asks for the
     * deleted ones instead of the ones in use.
     */
    suspend fun list(
        user: AuthenticatedUser,
        projectId: String?,
        deleted: Boolean = false,
    ): List<IssueView> = queries.list(viewer(user), projectId?.let { projectService.access(user, it).project.id }, deleted)

    /** The caller's own issues ("내 티켓"). */
    suspend fun mine(
        user: AuthenticatedUser,
        scope: MyIssueScope,
        deleted: Boolean = false,
    ): List<IssueView> = queries.listForUser(viewer(user), scope, deleted)

    /**
     * Deletes an issue: it leaves the lists but can still be opened, read-only, with its history,
     * and restored. Needs the right to edit it.
     */
    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        number: Int,
    ): IssueChange = setDeleted(user, number, true)

    @Transactional
    suspend fun restore(
        user: AuthenticatedUser,
        number: Int,
    ): IssueChange = setDeleted(user, number, false)

    private suspend fun setDeleted(
        user: AuthenticatedUser,
        number: Int,
        deleted: Boolean,
    ): IssueChange {
        val access = access(user, number)
        if (!access.mayEdit) throw noEditRight()
        val issue = access.issue
        val type = if (deleted) IssueActivityType.DELETED else IssueActivityType.RESTORED
        // Deleting what is already deleted (or restoring what is in use) changes nothing.
        val events = if ((issue.deletedAt != null) == deleted) emptyList() else listOf(IssueEvent(type))
        if (events.isNotEmpty()) {
            val now = clock.instant()
            issue.deletedAt = now.takeIf { deleted }
            issue.deletedBy = user.id.takeIf { deleted }
            issue.version += 1
            issue.updatedAt = now
            repository.save(issue)
            activities.record(issue.id, user.id, events, now)
        }
        return IssueChange(checkNotNull(detail(number)), events, recipients(issue.id, user))
    }

    suspend fun get(
        user: AuthenticatedUser,
        number: Int,
    ): IssueDetail {
        val access = access(user, number)
        val id = access.issue.id
        return IssueDetail(
            issue = checkNotNull(detail(number)),
            canEdit = access.canEdit,
            canRestore = access.mayEdit,
            canAddSubIssue = access.canEdit && depthOf(access.issue) < MAX_SUB_ISSUE_DEPTH,
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

    /** The project an issue the caller may see is linked to; null when it is linked to none. */
    suspend fun projectIdOf(
        user: AuthenticatedUser,
        number: Int,
    ): UUID? = access(user, number).issue.projectId

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        input: NewIssue,
    ): IssueChange {
        val parent = input.parentId?.let { requireParent(user, it, null) }
        // A sub-issue is always where its parent is: the same project and milestone (D-129). What
        // the request says about them is not used.
        val project =
            if (parent != null) {
                parent.projectId?.let { editableProjectById(user, it) }
            } else {
                input.projectId?.let { editableProject(user, it) }
            }
        val milestoneId = if (parent != null) parent.milestoneId else input.milestoneId
        val title = input.title.trim()
        val document = input.content?.let { richText.parse(it) }
        val description = document?.plainText ?: input.description?.trim().orEmpty()
        val errors = textErrors(title, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val assigneeIds =
            if (input.assigneeIds == null && parent != null) {
                inheritedAssignees(parent, project?.id)
            } else {
                requireAssignees(project?.id, input.assigneeIds.orEmpty(), emptyList())
            }
        if (parent == null) input.milestoneId?.let { requireMilestone(project?.id, it) }
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
                startDate = spanStart(input.startDate, input.dueDate),
                dueDate = input.dueDate,
                milestoneId = milestoneId,
                createdBy = user.id,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        entityTemplate.insert(issue).awaitSingle()
        if (document != null) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        if (labelIds.isNotEmpty()) queries.replaceLabels(issue.id, labelIds)
        if (assigneeIds.isNotEmpty()) queries.replaceAssignees(issue.id, assigneeIds, now)
        val events = listOf(IssueEvent(IssueActivityType.CREATED))
        activities.record(issue.id, user.id, events, now)
        // The author and the assignees follow the issue from the start.
        activities.subscribe(issue.id, user.id)
        assigneeIds.forEach { activities.subscribe(issue.id, it) }
        val mentioned = mentionEvents(tag(issue, document?.mentions.orEmpty(), user))
        return IssueChange(checkNotNull(detail(issue.number)), events + mentioned, recipients(issue.id, user))
    }

    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        number: Int,
        patch: IssuePatch,
    ): IssueChange {
        val issue = editable(access(user, number)).issue
        val before = queries.find(number) ?: throw notFound()
        val oldProjectId = issue.projectId
        val oldMilestoneId = issue.milestoneId

        if (patch.has("title")) issue.title = patch.string("title")?.trim().orEmpty()
        if (patch.has("description")) issue.description = patch.string("description")?.trim().orEmpty()
        val document = if (patch.has("content")) patch.raw("content")?.let { richText.parse(it) } else null
        if (patch.has("content")) issue.description = document?.plainText.orEmpty()
        val errors = textErrors(issue.title, issue.description)
        if (errors.isNotEmpty()) throw ValidationException(errors)

        patch.enum("status", IssueStatus::valueOf)?.let { issue.status = it }
        patch.enum("priority", IssuePriority::valueOf)?.let { issue.priority = it }
        if (patch.has("dueDate")) issue.dueDate = patch.date("dueDate")
        if (patch.has("startDate")) issue.startDate = patch.date("startDate")
        issue.startDate = spanStart(issue.startDate, issue.dueDate)
        // The parent first: a sub-issue's project and milestone are always its parent's (D-129).
        if (patch.has("parentId")) {
            val parentId = patch.uuid("parentId")
            // Sending the parent it already has changes nothing and is not checked again.
            if (parentId != issue.parentId) issue.parentId = parentId?.let { requireParent(user, it, issue).id }
        }
        val parent = issue.parentId?.let { repository.findById(it) }
        if (parent != null) {
            // Its parent's values may be sent back; anything else cannot be set on a sub-issue.
            val otherProject = patch.has("projectId") && patch.uuid("projectId") != parent.projectId
            val otherMilestone = patch.has("milestoneId") && patch.uuid("milestoneId") != parent.milestoneId
            if (otherProject || otherMilestone) throw ConflictException("FOLLOWS_PARENT", "하위 티켓의 프로젝트와 목표는 상위 티켓을 따릅니다")
            // Put under an issue of another project: it moves there, which needs the right to edit there.
            if (parent.projectId != issue.projectId) parent.projectId?.let { editableProjectById(user, it) }
            issue.projectId = parent.projectId
            issue.milestoneId = parent.milestoneId
        } else {
            if (patch.has("projectId")) {
                val projectId = patch.string("projectId")?.let { editableProject(user, it).id }
                if (projectId != issue.projectId) {
                    issue.projectId = projectId
                    // A milestone belongs to one project; it cannot follow the issue to another.
                    if (!patch.has("milestoneId")) issue.milestoneId = null
                }
            }
            if (patch.has("milestoneId")) {
                val milestoneId = patch.uuid("milestoneId")
                if (milestoneId != null) requireMilestone(issue.projectId, milestoneId)
                issue.milestoneId = milestoneId
            }
        }
        val assigneeIds =
            if (patch.has("assigneeIds")) requireAssignees(issue.projectId, patch.uuids("assigneeIds"), before.assignees.map { it.id }) else null
        val labelIds = if (patch.has("labelIds")) requireLabels(patch.uuids("labelIds")) else null
        issue.version += 1
        issue.updatedAt = clock.instant()
        repository.save(issue)
        if (labelIds != null) queries.replaceLabels(issue.id, labelIds)
        if (assigneeIds != null) queries.replaceAssignees(issue.id, assigneeIds, issue.updatedAt)
        // Only people tagged by this save hear about it, not everyone the body already named.
        val newMentions =
            document?.mentions?.takeIf { it.isNotEmpty() }?.let { it - richText.mentionsIn(richText.load(RichText.ContentTable.ISSUES, issue.id)) }
        if (patch.has("content")) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        val after = checkNotNull(detail(number))
        val recorded = activities.diff(before, after)
        activities.record(issue.id, user.id, recorded, issue.updatedAt)
        val events = recorded + mentionEvents(tag(issue, newMentions.orEmpty(), user))
        // Whoever is given the issue follows it from then on.
        after.assignees.filter { person -> before.assignees.none { it.id == person.id } }.forEach { activities.subscribe(issue.id, it.id) }
        if (issue.projectId != oldProjectId || issue.milestoneId != oldMilestoneId) moveSubIssuesAlong(user, issue, 0)
        return IssueChange(after, events, recipients(issue.id, user), listOfNotNull(before.project?.id, after.project?.id).distinct())
    }

    /**
     * A sub-issue is always where its parent is (D-129): when an issue's project or milestone
     * changes, every issue below it follows, each with an entry in its own history.
     */
    private suspend fun moveSubIssuesAlong(
        user: AuthenticatedUser,
        parent: Issue,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (child in repository.findByParentId(parent.id)) {
            if (child.projectId == parent.projectId && child.milestoneId == parent.milestoneId) continue
            val before = queries.find(child.number) ?: continue
            child.projectId = parent.projectId
            child.milestoneId = parent.milestoneId
            child.version += 1
            child.updatedAt = parent.updatedAt
            repository.save(child)
            val after = queries.find(child.number) ?: continue
            activities.record(child.id, user.id, activities.diff(before, after), parent.updatedAt)
            moveSubIssuesAlong(user, child, depth + 1)
        }
    }

    /** The history of an issue with who follows it. Anyone who may see the issue can read it. */
    suspend fun activity(
        user: AuthenticatedUser,
        number: Int,
    ): IssueActivityFeed {
        val access = access(user, number)
        val issue = access.issue
        val subscribers = activities.subscribers(issue.id)
        val shown = comments.list(issue.id).map { it.withRights(user, access) }
        return IssueActivityFeed(activities.list(issue.id), shown, subscribers, subscribers.any { it.id == user.id })
    }

    /**
     * Writes a comment on an issue; needs the right to edit it. The author and the people tagged
     * in it with @ follow the issue from then on, and every follower is told.
     */
    @Transactional
    suspend fun addComment(
        user: AuthenticatedUser,
        number: Int,
        content: Any?,
    ): CommentResult {
        val access = editable(access(user, number))
        val issue = access.issue
        val document = commentDocument(content)
        val now = clock.instant()
        val id = comments.add(issue.id, user.id, document, now)
        activities.subscribe(issue.id, user.id)
        val tagged = tag(issue, document.mentions, user)
        val comment = checkNotNull(comments.find(id)).withRights(user, access)
        return CommentResult(comment, IssueChange(checkNotNull(queries.find(number)), listOf(commentEvent(document, tagged)), recipients(issue.id, user)))
    }

    /** Only its author may change a comment. People newly tagged by the change are told; nobody else is told again. */
    @Transactional
    suspend fun updateComment(
        user: AuthenticatedUser,
        commentId: UUID,
        content: Any?,
    ): CommentResult {
        val existing = comments.find(commentId) ?: throw commentNotFound()
        val access = editable(accessById(user, existing.issueId))
        if (existing.author.id != user.id) throw ForbiddenException("다른 사람의 댓글은 수정할 수 없습니다")
        val document = commentDocument(content)
        comments.update(commentId, document, clock.instant())
        val tagged = tag(access.issue, document.mentions - richText.mentionsIn(existing.content), user)
        val comment = checkNotNull(comments.find(commentId)).withRights(user, access)
        val events = if (tagged.isEmpty()) emptyList() else listOf(commentEvent(document, tagged))
        return CommentResult(comment, IssueChange(checkNotNull(queries.find(access.issue.number)), events, tagged.map { it.first }))
    }

    /** Its author or a workspace admin may delete a comment. Returns the number of the issue it was on. */
    @Transactional
    suspend fun removeComment(
        user: AuthenticatedUser,
        commentId: UUID,
    ): Int {
        val existing = comments.find(commentId) ?: throw commentNotFound()
        val access = editable(accessById(user, existing.issueId))
        if (existing.author.id != user.id && user.role != UserRole.ADMIN) throw ForbiddenException("다른 사람의 댓글은 지울 수 없습니다")
        comments.delete(commentId, clock.instant())
        return access.issue.number
    }

    private fun CommentView.withRights(
        user: AuthenticatedUser,
        access: IssueAccess,
    ): CommentView {
        val mine = author.id == user.id
        return copy(canEdit = access.canEdit && mine, canDelete = access.canEdit && (mine || user.role == UserRole.ADMIN))
    }

    private fun commentDocument(content: Any?): RichTextDocument {
        val document = content?.let { richText.parse(it) }
        // A picture alone is a comment too.
        if (document == null || (document.plainText.isBlank() && "\"image\"" !in document.json)) {
            throw ValidationException("content", "REQUIRED", "댓글 내용을 입력하세요")
        }
        return document
    }

    private fun commentEvent(
        document: RichTextDocument,
        tagged: List<Pair<UUID, String>>,
    ) = IssueEvent(
        IssueActivityType.COMMENT_ADDED,
        mapOf("text" to document.plainText.take(COMMENT_PREVIEW_LENGTH), "mentions" to tagged.map { it.second }),
    )

    private fun mentionEvents(tagged: List<Pair<UUID, String>>): List<IssueEvent> =
        if (tagged.isEmpty()) emptyList() else listOf(IssueEvent(IssueActivityType.MENTIONED, mapOf("mentions" to tagged.map { it.second })))

    /**
     * Makes the people tagged with @ follow the issue, so the notification about it reaches them
     * through every channel they have set up. Only people who could be given the issue count
     * (members of its project, or anyone active for an issue on its own); tagging oneself does
     * nothing. Returns who was tagged, with their names as of now.
     */
    private suspend fun tag(
        issue: Issue,
        userIds: Collection<UUID>,
        actor: AuthenticatedUser,
    ): List<Pair<UUID, String>> =
        userIds.filter { it != actor.id }.mapNotNull { userId ->
            val reachable = issue.projectId?.let { queries.isProjectMember(it, userId) } ?: queries.isActiveUser(userId)
            if (!reachable) return@mapNotNull null
            activities.subscribe(issue.id, userId)
            userId to activities.displayName(userId)
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
        val document = extras.addDocument(issue.id, null, null, title, user.id, now)
        activities.record(issue.id, user.id, listOf(IssueEvent(IssueActivityType.DOCUMENT_ADDED, mapOf("to" to title))), now)
        return document
    }

    /** A document of a project, made from its overview. Needs the right to edit in the project. */
    suspend fun createProjectDocument(
        user: AuthenticatedUser,
        projectId: String,
        rawTitle: String?,
    ): DocumentSummary = extras.addDocument(null, editableProject(user, projectId).id, null, documentTitle(rawTitle.orEmpty()), user.id, clock.instant())

    /**
     * A document inside another one. Only a project's documents nest, and only
     * [MAX_DOCUMENT_DEPTH] levels deep; an issue's documents stay one level.
     */
    @Transactional
    suspend fun createChildDocument(
        user: AuthenticatedUser,
        parentId: UUID,
        rawTitle: String?,
    ): DocumentSummary {
        val parent = extras.findDocument(parentId) ?: throw notFound()
        if (!ownerOf(user, parent).canEdit || parent.deletedAt != null) throw noEditRight()
        if (parent.issueId != null) throw ConflictException("DOCUMENT_TOO_DEEP", "티켓의 문서 안에는 문서를 만들 수 없습니다")
        if (extras.documentAncestors(parent.id).size + 1 >= MAX_DOCUMENT_DEPTH) {
            throw ConflictException("DOCUMENT_TOO_DEEP", "문서는 ${MAX_DOCUMENT_DEPTH}단계까지만 만들 수 있습니다")
        }
        return extras.addDocument(null, parent.projectId, parent.id, documentTitle(rawTitle.orEmpty()), user.id, clock.instant())
    }

    suspend fun projectDocuments(
        user: AuthenticatedUser,
        projectId: String,
        deleted: Boolean = false,
    ): List<DocumentSummary> {
        val id = projectService.access(user, projectId).project.id
        return if (deleted) extras.deletedProjectDocuments(id) else extras.projectDocuments(id)
    }

    /** The deleted documents of an issue; the ones in use come with the issue itself. */
    suspend fun deletedDocuments(
        user: AuthenticatedUser,
        number: Int,
    ): List<DocumentSummary> = extras.deletedDocuments(access(user, number).issue.id)

    suspend fun document(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentDetail {
        val document = extras.findDocument(documentId) ?: throw notFound()
        val owner = ownerOf(user, document)
        val ancestors = extras.documentAncestors(document.id)
        return DocumentDetail(
            document = document,
            content = richText.load(RichText.ContentTable.DOCUMENTS, document.id),
            issue = owner.issue?.let { checkNotNull(queries.find(it.number)) },
            project = owner.project?.let { IssueProject(it.id, it.name) },
            // A deleted document is read-only until it is restored.
            canEdit = owner.canEdit && document.deletedAt == null,
            ancestors = ancestors,
            children = extras.childDocuments(document.id),
            canAddChild =
                owner.canEdit && document.deletedAt == null && document.issueId == null && ancestors.size + 1 < MAX_DOCUMENT_DEPTH,
            canRestore = owner.canEdit && document.deletedAt != null,
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
        if (!ownerOf(user, document).canEdit || document.deletedAt != null) throw noEditRight()
        val title = if (patch.has("title")) documentTitle(patch.string("title").orEmpty()) else null
        if (patch.has("content")) {
            val parsed = patch.raw("content")?.let { richText.parse(it) }
            richText.store(RichText.ContentTable.DOCUMENTS, document.id, parsed)
        }
        extras.touchDocument(document.id, title, clock.instant())
        return document(user, documentId)
    }

    /**
     * Archives the document (the row stays and can be restored). Returns what it belongs to, so
     * that whoever shows it can be told.
     */
    @Transactional
    suspend fun removeDocument(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentOwner {
        val document = extras.findDocument(documentId)?.takeIf { it.deletedAt == null } ?: throw notFound()
        val owner = ownerOf(user, document)
        if (!owner.canEdit) throw noEditRight()
        extras.deleteDocument(document.id, user.id, clock.instant())
        owner.issue?.let { issue ->
            val event = IssueEvent(IssueActivityType.DOCUMENT_REMOVED, mapOf("from" to document.title))
            activities.record(issue.id, user.id, listOf(event), clock.instant())
        }
        return DocumentOwner(owner.issue?.number, owner.project?.id)
    }

    /** Brings a deleted document back to where it was. */
    @Transactional
    suspend fun restoreDocument(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentOwner {
        val document = extras.findDocument(documentId)?.takeIf { it.deletedAt != null } ?: throw notFound()
        val owner = ownerOf(user, document)
        if (!owner.canEdit) throw noEditRight()
        extras.restoreDocument(document.id)
        owner.issue?.let { issue ->
            val event = IssueEvent(IssueActivityType.DOCUMENT_RESTORED, mapOf("to" to document.title))
            activities.record(issue.id, user.id, listOf(event), clock.instant())
        }
        return DocumentOwner(owner.issue?.number, owner.project?.id)
    }

    /** The issue or project a document belongs to, as the caller may see it; "not found" when they may not. */
    private suspend fun ownerOf(
        user: AuthenticatedUser,
        document: DocumentSummary,
    ): OwnerAccess {
        if (document.issueId != null) {
            val access = accessById(user, document.issueId)
            return OwnerAccess(access.issue, null, access.canEdit)
        }
        val access = projectService.accessById(user, checkNotNull(document.projectId)) ?: throw notFound()
        return OwnerAccess(null, access.project, access.role.canEdit)
    }

    suspend fun actorName(user: AuthenticatedUser): String = activities.displayName(user.id)

    private suspend fun detail(number: Int): IssueView? = queries.find(number)?.let { it.copy(content = richText.load(RichText.ContentTable.ISSUES, it.id)) }

    /** Everyone, administrators included, sees the issues of the projects they are a member of (D-131). */
    private fun viewer(user: AuthenticatedUser) = Viewer(user.id, seesAllProjects = false)

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
        val mayEdit = if (issue.projectId == null) true else (projectService.accessById(user, issue.projectId!!) ?: return null).role.canEdit
        return IssueAccess(issue, mayEdit)
    }

    private fun editable(access: IssueAccess): IssueAccess {
        if (!access.mayEdit) throw noEditRight()
        if (access.issue.deletedAt != null) throw ForbiddenException("삭제된 티켓은 수정할 수 없습니다. 먼저 복원하세요")
        return access
    }

    /** A project the caller may put issues into. */
    private suspend fun editableProject(
        user: AuthenticatedUser,
        projectId: String,
    ): Project {
        val access = projectService.access(user, projectId)
        if (!access.role.canEdit) throw noEditRight()
        return access.project
    }

    private suspend fun editableProjectById(
        user: AuthenticatedUser,
        projectId: UUID,
    ): Project = projectService.accessById(user, projectId)?.takeIf { it.role.canEdit }?.project ?: throw noEditRight()

    /** A new sub-issue's assignees: its parent's, without those who can no longer be given an issue there (D-129). */
    private suspend fun inheritedAssignees(
        parent: Issue,
        projectId: UUID?,
    ): List<UUID> =
        queries.find(parent.number)?.assignees.orEmpty().map { it.id }.filter { userId ->
            if (projectId != null) queries.isProjectMember(projectId, userId) else queries.isActiveUser(userId)
        }

    /**
     * The issue to hang `child` under: one the caller may see, not the child itself and not one
     * of its own descendants (which would make a loop). `child` is null for an issue being created.
     * Sub-issues go [MAX_SUB_ISSUE_DEPTH] levels below a top-level issue at most, counting the
     * issues that already hang under `child` (409 `ISSUE_TOO_DEEP`).
     */
    private suspend fun requireParent(
        user: AuthenticatedUser,
        parentId: UUID,
        child: Issue?,
    ): Issue {
        val invalid = ValidationException("parentId", "INVALID_PARENT", "상위 티켓으로 지정할 수 없는 티켓입니다")
        val parent = repository.findById(parentId)?.takeIf { accessOf(user, it) != null } ?: throw invalid
        // How deep the child would sit: one more than the number of issues above its parent.
        var depth = 0
        var ancestor: Issue? = parent
        while (ancestor != null) {
            if (ancestor.id == child?.id || ++depth > MAX_DEPTH) throw invalid
            ancestor = ancestor.parentId?.let { repository.findById(it) }
        }
        val below = child?.let { levelsBelow(it, MAX_SUB_ISSUE_DEPTH) } ?: 0
        if (depth + below > MAX_SUB_ISSUE_DEPTH) {
            throw ConflictException("ISSUE_TOO_DEEP", "하위 티켓은 ${MAX_SUB_ISSUE_DEPTH}단계까지만 만들 수 있습니다")
        }
        return parent
    }

    /** How many issues are above this one; 0 for a top-level issue. */
    private suspend fun depthOf(issue: Issue): Int {
        var depth = 0
        var ancestor = issue.parentId?.let { repository.findById(it) }
        while (ancestor != null && ++depth < MAX_DEPTH) ancestor = ancestor.parentId?.let { repository.findById(it) }
        return depth
    }

    /** How many levels of sub-issues hang under the issue, deleted ones included. Stops counting past `limit`. */
    private suspend fun levelsBelow(
        issue: Issue,
        limit: Int,
    ): Int {
        if (limit == 0) return 0
        return repository.findByParentId(issue.id).maxOfOrNull { 1 + levelsBelow(it, limit - 1) } ?: 0
    }

    /**
     * The ids without repeats, once every one of them may be given the issue: a linked issue to
     * members of its project, an issue on its own to any active person. People who already have
     * it (`current`) are not checked again, so someone who has since left the project can stay on.
     */
    private suspend fun requireAssignees(
        projectId: UUID?,
        userIds: List<UUID>,
        current: List<UUID>,
    ): List<UUID> {
        val distinct = userIds.distinct()
        if (distinct.size > MAX_ASSIGNEES) {
            throw ValidationException("assigneeIds", "TOO_MANY", "담당자는 ${MAX_ASSIGNEES}명까지 지정할 수 있습니다")
        }
        for (userId in distinct - current.toSet()) {
            val assignable = if (projectId != null) queries.isProjectMember(projectId, userId) else queries.isActiveUser(userId)
            if (!assignable) {
                throw ValidationException("assigneeIds", "NOT_A_MEMBER", "담당자는 이 프로젝트의 구성원이어야 합니다")
            }
        }
        return distinct
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
    ): List<UUID> = activities.recipientIds(issueId).filter { it != actor.id }

    /** The start of an issue's span: none without a due date, and never after it. */
    private fun spanStart(
        startDate: LocalDate?,
        dueDate: LocalDate?,
    ): LocalDate? {
        if (startDate == null || dueDate == null) return null
        if (startDate.isAfter(dueDate)) throw ValidationException("startDate", "AFTER_DUE_DATE", "시작일은 종료일보다 늦을 수 없습니다")
        return startDate
    }

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
                title.isEmpty() -> FieldError("title", "REQUIRED", "티켓 제목을 입력하세요")
                title.length > Issue.TITLE_MAX_LENGTH -> FieldError("title", "TOO_LONG", "티켓 제목은 ${Issue.TITLE_MAX_LENGTH}자 이하여야 합니다")
                else -> null
            },
            if (description.length > Issue.DESCRIPTION_MAX_LENGTH) {
                FieldError("description", "TOO_LONG", "설명이 너무 깁니다")
            } else {
                null
            },
        )

    private fun noEditRight() = ForbiddenException("이 티켓을 쓰거나 수정할 권한이 없습니다")

    private fun notFound() = NotFoundException("ISSUE_NOT_FOUND", "티켓을 찾을 수 없습니다")

    private fun commentNotFound() = NotFoundException("COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다")

    private data class OwnerAccess(
        val issue: Issue?,
        val project: Project?,
        val canEdit: Boolean,
    )

    private data class IssueAccess(
        val issue: Issue,
        /** Whether the caller's role lets them change the issue (and delete or restore it). */
        val mayEdit: Boolean,
    ) {
        /** A deleted issue is read-only for everyone until it is restored. */
        val canEdit: Boolean
            get() = mayEdit && issue.deletedAt == null
    }

    companion object {
        /** The emoji an issue can be reacted to with. */
        val REACTIONS = listOf("👍", "❤️", "🎉", "😄", "👀", "🚀", "🙏", "✅")
        private const val MAX_LABELS = 10
        private const val MAX_ASSIGNEES = 10

        /** Stops walks up and down the tree should the data ever hold a loop. */
        private const val MAX_DEPTH = 50

        /** How many levels of sub-issues a top-level issue can have under it (D-118). */
        const val MAX_SUB_ISSUE_DEPTH = 10
        private const val FILE_NAME_MAX_LENGTH = 255

        /** How much of a comment a notification quotes. */
        private const val COMMENT_PREVIEW_LENGTH = 300
    }
}
