package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.automation.AutomationEngine
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.customproperty.application.CustomPropertyService
import com.flowbit.cycle.CycleService
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Issues. An issue stands on its own and may be linked to a project. Linked, it follows that
 * project's access rules (only people who may enter the project see it, and the view-only role
 * cannot change it); linked to none, every signed-in person may see and change it.
 * An issue the caller may not see is reported as "not found". Its comments, files and documents, and what a Git
 * server does to it, are the work of [IssueCommentService], [IssueDocumentService] and [IssueGitService]; who may
 * see and change it is [IssueLookup]'s.
 */
@Service
class IssueService(
    private val lookup: IssueLookup,
    private val tagging: IssueTagging,
    private val git: IssueGitService,
    private val projectService: ProjectService,
    private val repository: IssueRepository,
    private val queries: IssueQueries,
    private val entityTemplate: R2dbcEntityTemplate,
    private val richText: RichText,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val customProperties: CustomPropertyService,
    private val projectFields: com.flowbit.customproperty.application.ProjectIssueFieldService,
    private val statuses: IssueStatuses,
    private val cycles: CycleService,
    private val relations: IssueRelations,
    private val clock: Clock,
    private val automations: AutomationEngine,
) {
    /**
     * Issues the caller may see; of one project when `projectId` is given. `deleted` asks for the
     * deleted ones instead of the ones in use.
     */
    suspend fun list(
        user: AuthenticatedUser,
        projectId: String?,
        deleted: Boolean = false,
        limit: Int = ISSUE_LIST_LIMIT,
    ): List<IssueView> = queries.list(lookup.viewer(user), projectId?.let { projectService.access(user, it).project.id }, deleted, limit)

    /** The issues waiting in the triage queue of the projects the caller manages, the oldest first (D-231). */
    suspend fun triageQueue(
        user: AuthenticatedUser,
        rejected: Boolean = false,
    ): List<IssueView> =
        queries.triageQueue(lookup.viewer(user), rejected).filter { view ->
            view.project?.let { projectService.accessById(user, it.id)?.role?.canManage } == true
        }

    /** The caller's own issues ("내 티켓"). */
    suspend fun mine(
        user: AuthenticatedUser,
        scope: MyIssueScope,
        deleted: Boolean = false,
        limit: Int = ISSUE_LIST_LIMIT,
    ): List<IssueView> = queries.listForUser(lookup.viewer(user), scope, deleted, limit)

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
        val access = lookup.access(user, number)
        if (!access.mayEdit) throw lookup.noEditRight()
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
        return IssueChange(checkNotNull(lookup.detail(number)), events, lookup.recipients(issue.id, user))
    }

    suspend fun get(
        user: AuthenticatedUser,
        number: Int,
    ): IssueDetail {
        val access = lookup.access(user, number)
        val id = access.issue.id
        return IssueDetail(
            issue = checkNotNull(lookup.detail(number)),
            canEdit = access.canEdit,
            canRestore = access.mayEdit,
            canAddSubIssue = access.canEdit && depthOf(access.issue) < MAX_SUB_ISSUE_DEPTH,
            subIssues = queries.subIssues(lookup.viewer(user), id),
            reactions = extras.reactions(id, user.id),
            attachments = extras.attachments(id),
            documents = extras.documents(id),
            git = extras.gitLinks(id),
            gitRepository = git.gitRepositoryOf(access.issue),
            gitRepositoryOptions =
                access.issue.projectId
                    ?.let { extras.projectGitRepositories(it) }
                    .orEmpty(),
        )
    }

    /** The latest entries of an issue's history the caller may see, newest first. */
    suspend fun recentActivity(
        user: AuthenticatedUser,
        number: Int,
        limit: Int,
    ): List<IssueActivityView> = activities.recent(lookup.access(user, number).issue.id, limit)

    /** Whether the caller may see the issue; for deciding who hears about its changes. */
    suspend fun canRead(
        user: AuthenticatedUser,
        number: Int,
    ): Boolean = repository.findByNumber(number)?.let { lookup.accessOf(user, it) } != null

    /** The project an issue the caller may see is linked to; null when it is linked to none. */
    suspend fun projectIdOf(
        user: AuthenticatedUser,
        number: Int,
    ): UUID? = lookup.access(user, number).issue.projectId

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        input: NewIssue,
    ): IssueChange {
        val parent = input.parentId?.let { requireParent(user, it, null) }
        // A sub-issue is always where its parent is: the same project (D-129). What the request
        // says about it is not used.
        val project =
            if (parent != null) {
                parent.projectId?.let { lookup.editableProjectById(user, it) }
            } else {
                input.projectId?.let { lookup.editableProject(user, it) }
            }
        val title = input.title.trim()
        val document = input.content?.let { richText.parse(it, blockIds = true) }
        val description = document?.plainText ?: input.description?.trim().orEmpty()
        val errors = textErrors(title, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val assigneeIds =
            if (input.assigneeIds == null && parent != null) {
                inheritedAssignees(parent, project?.id)
            } else {
                requireAssignees(project?.id, input.assigneeIds.orEmpty(), emptyList())
            }
        val labelIds = requireLabels(input.labelIds)
        // Checked before anything is stored, so that a bad property does not use up an issue number.
        val properties = customProperties.checkAll(input.customProperties, project?.id)
        val fieldValues = projectFields.checkValues(project?.id, input.projectFields)
        val newRelations = relations.check(user, input.relations)

        val now = clock.instant()
        val startStatus = input.statusId?.let { statuses.require(it) } ?: statuses.defaultOf(input.status ?: IssueStatus.BACKLOG)
        val issue =
            Issue(
                id = UuidV7.generate(now),
                projectId = project?.id,
                parentId = parent?.id,
                number = queries.nextNumber(),
                title = title,
                description = description,
                status = startStatus.category,
                statusId = startStatus.id,
                estimate = estimateOf(input.estimate),
                // In a project that uses triage, what a person who does not manage it makes waits to be sorted (D-231).
                // A manager's issue goes straight in unless they send it to the queue themselves (D-236).
                triage =
                    project != null &&
                        project.triageEnabled &&
                        (input.triage == true || projectService.accessById(user, project.id)?.role?.canManage != true),
                priority = input.priority ?: IssuePriority.NONE,
                startDate = spanStart(input.startDate, input.dueDate),
                dueDate = input.dueDate,
                createdBy = user.id,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        entityTemplate.insert(issue).awaitSingle()
        if (document != null) richText.store(RichText.ContentTable.ISSUES, issue.id, document)
        if (labelIds.isNotEmpty()) queries.replaceLabels(issue.id, labelIds)
        if (assigneeIds.isNotEmpty()) queries.replaceAssignees(issue.id, assigneeIds, now)
        if (properties.isNotEmpty()) customProperties.addChecked(issue.id, properties, now)
        projectFields.storeChecked(issue.id, fieldValues)
        // A sub-issue starts with its parent's common property values (D-239), as with its assignees; what the request set stays.
        if (parent != null) projectFields.inheritValues(parent.id, issue.id)
        relations.attach(user, issue, newRelations)
        val events = listOf(IssueEvent(IssueActivityType.CREATED))
        activities.record(issue.id, user.id, events, now)
        // The author and the assignees follow the issue from the start.
        activities.subscribe(issue.id, user.id)
        assigneeIds.forEach { activities.subscribe(issue.id, it) }
        val tagged = tagging.tag(issue, document?.mentions.orEmpty(), user)
        return IssueChange(
            checkNotNull(
                lookup.detail(issue.number),
            ),
            events + tagging.mentionEvents(tagged),
            lookup.recipients(issue.id, user),
            taggedIds =
                tagged.map {
                    it.first
                },
            triageRecipientIds = triageRecipients(issue, user),
        )
    }

    @Transactional
    suspend fun appendContent(
        user: AuthenticatedUser,
        number: Int,
        blocks: List<Map<String, Any?>>,
    ): IssueChange {
        val access = lookup.editable(lookup.access(user, number))
        val locked = repository.lockById(access.issue.id) ?: throw lookup.notFound()
        val content = richText.append(richText.load(RichText.ContentTable.ISSUES, locked.id), locked.description, blocks)
        return update(user, number, IssuePatch(mapOf("content" to content)))
    }

    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        number: Int,
        patch: IssuePatch,
    ): IssueChange {
        val access = lookup.editable(lookup.access(user, number))
        val issue = repository.lockById(access.issue.id) ?: throw lookup.notFound()
        // Access may have changed while waiting for the row lock.
        lookup.editable(lookup.access(user, number))
        val before = queries.find(number) ?: throw lookup.notFound()
        val oldProjectId = issue.projectId

        if (patch.has("title")) issue.title = patch.string("title")?.trim().orEmpty()
        if (patch.has("description")) issue.description = patch.string("description")?.trim().orEmpty()
        val document = if (patch.has("content")) patch.raw("content")?.let { richText.parse(it, blockIds = true) } else null
        if (patch.has("content")) issue.description = document?.plainText.orEmpty()
        val errors = textErrors(issue.title, issue.description)
        if (errors.isNotEmpty()) throw ValidationException(errors)

        if (patch.has("statusId")) {
            val target = statuses.require(patch.uuid("statusId") ?: throw ValidationException("statusId", "INVALID", "값이 올바르지 않습니다"))
            issue.statusId = target.id
            issue.status = target.category
        } else {
            patch.enum("status", IssueStatus::valueOf)?.let { category ->
                // Only a category is said: stay where the issue is when it is already in it, otherwise go to the category's first status.
                if (category != issue.status) {
                    val target = statuses.defaultOf(category)
                    issue.statusId = target.id
                    issue.status = target.category
                }
            }
        }
        if (patch.has("estimate")) issue.estimate = estimateOf(patch.int("estimate"))
        if (patch.has("triageRejected")) {
            if (issue.projectId?.let { projectService.accessById(user, it)?.role?.canManage } != true) {
                throw ForbiddenException("요청함은 프로젝트 관리 역할만 정할 수 있습니다")
            }
            if (!patch.bool("triageRejected") || !issue.triage || !patch.has("triage") || patch.bool("triage") || issue.status != IssueStatus.CANCELED) {
                throw ValidationException("triageRejected", "INVALID", "대기 중인 티켓을 거절하고 취소할 때만 지정할 수 있습니다")
            }
            issue.triageRejected = true
            issue.triageRejectedAt = clock.instant()
        }
        if (patch.has("triage")) {
            val next = patch.bool("triage")
            // Sorting the queue is for the people who manage the project, not for the one who asked (D-231).
            if (next != issue.triage && issue.projectId?.let { projectService.accessById(user, it)?.role?.canManage } != true) {
                throw ForbiddenException("요청함은 프로젝트 관리 역할만 정할 수 있습니다")
            }
            issue.triage = next
            if (next) {
                issue.triageRejected = false
                issue.triageRejectedAt = null
            }
        }
        patch.enum("priority", IssuePriority::valueOf)?.let { issue.priority = it }
        if (patch.has("dueDate")) issue.dueDate = patch.date("dueDate")
        if (patch.has("startDate")) issue.startDate = patch.date("startDate")
        issue.startDate = spanStart(issue.startDate, issue.dueDate)
        // The parent first: a sub-issue's project is always its parent's (D-129).
        if (patch.has("parentId")) {
            val parentId = patch.uuid("parentId")
            // Sending the parent it already has changes nothing and is not checked again.
            if (parentId != issue.parentId) issue.parentId = parentId?.let { requireParent(user, it, issue).id }
        }
        val parent = issue.parentId?.let { repository.findById(it) }
        if (parent != null) {
            // Its parent's project may be sent back; any other cannot be set on a sub-issue.
            if (patch.has("projectId") && patch.uuid("projectId") != parent.projectId) {
                throw ConflictException("FOLLOWS_PARENT", "하위 티켓의 프로젝트는 상위 티켓을 따릅니다")
            }
            // Put under an issue of another project: it moves there, which needs the right to edit there.
            if (parent.projectId != issue.projectId) parent.projectId?.let { lookup.editableProjectById(user, it) }
            issue.projectId = parent.projectId
        } else if (patch.has("projectId")) {
            issue.projectId = patch.string("projectId")?.let { lookup.editableProject(user, it).id }
        }
        // A cycle is the project's own: an issue moved to another project leaves it, and one put in a cycle must be in its project's.
        if (patch.has("cycleId")) {
            val cycleId = patch.uuid("cycleId")
            if (cycleId != null && !cycles.belongsTo(issue.projectId, cycleId)) throw ValidationException("cycleId", "INVALID", "이 프로젝트의 사이클이 아닙니다")
            issue.cycleId = cycleId
        } else if (issue.projectId != oldProjectId) {
            issue.cycleId = null
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
        val transitioned = checkNotNull(lookup.detail(number))
        val automationNotices = automations.apply(before, transitioned, lookup.actorName(user))
        val after = checkNotNull(lookup.detail(number))
        val recorded = activities.diff(before, after)
        activities.record(issue.id, user.id, recorded, issue.updatedAt)
        val tagged = tagging.tag(issue, newMentions.orEmpty(), user)
        val events = recorded + tagging.mentionEvents(tagged)
        // Whoever is given the issue follows it from then on.
        after.assignees.filter { person -> before.assignees.none { it.id == person.id } }.forEach { activities.subscribe(issue.id, it.id) }
        if (issue.projectId != oldProjectId) moveSubIssuesAlong(user, issue, 0)
        return IssueChange(
            after,
            events,
            lookup.recipients(issue.id, user),
            listOfNotNull(before.project?.id, after.project?.id).distinct(),
            tagged.map { it.first },
            triageRecipientIds = if (!before.triage && issue.triage) triageRecipients(issue, user) else emptyList(),
            automationNotices = automationNotices,
        )
    }

    private suspend fun triageRecipients(
        issue: Issue,
        user: AuthenticatedUser,
    ): List<UUID> = if (issue.triage) issue.projectId?.let { queries.triageManagers(it).filter { id -> id != user.id } }.orEmpty() else emptyList()

    /** Story points: a whole number from 0 to 1000, or none. */
    private fun estimateOf(value: Int?): Int? {
        if (value != null && value !in 0..ESTIMATE_MAX) throw ValidationException("estimate", "INVALID", "추정치는 0에서 ${ESTIMATE_MAX} 사이여야 합니다")
        return value
    }

    /**
     * A sub-issue is always where its parent is (D-129): when an issue's project changes, every
     * issue below it follows, each with an entry in its own history.
     */
    private suspend fun moveSubIssuesAlong(
        user: AuthenticatedUser,
        parent: Issue,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (child in repository.findByParentId(parent.id)) {
            if (child.projectId == parent.projectId) continue
            val before = queries.find(child.number) ?: continue
            child.projectId = parent.projectId
            child.version += 1
            child.updatedAt = parent.updatedAt
            repository.save(child)
            val after = queries.find(child.number) ?: continue
            activities.record(child.id, user.id, activities.diff(before, after), parent.updatedAt)
            moveSubIssuesAlong(user, child, depth + 1)
        }
    }

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
        val parent = repository.findById(parentId)?.takeIf { lookup.accessOf(user, it) != null } ?: throw invalid
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

    /** The ids without repeats, once every one of them is a label that exists. */
    private suspend fun requireLabels(labelIds: List<UUID>): Set<UUID> {
        val distinct = labelIds.toSet()
        if (distinct.size > MAX_LABELS) throw ValidationException("labelIds", "TOO_MANY", "라벨은 ${MAX_LABELS}개까지 붙일 수 있습니다")
        if (distinct.isNotEmpty() && queries.countLabels(distinct) != distinct.size) {
            throw ValidationException("labelIds", "UNKNOWN_LABEL", "없는 라벨입니다")
        }
        return distinct
    }

    /** The start of an issue's span: none without a due date, and never after it. */
    private fun spanStart(
        startDate: LocalDate?,
        dueDate: LocalDate?,
    ): LocalDate? {
        if (startDate == null || dueDate == null) return null
        if (startDate.isAfter(dueDate)) throw ValidationException("startDate", "AFTER_DUE_DATE", "시작일은 종료일보다 늦을 수 없습니다")
        return startDate
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

    companion object {
        private const val MAX_LABELS = 10

        /** Stops walks up and down the tree should the data ever hold a loop. */
        private const val MAX_DEPTH = 50

        /** How many levels of sub-issues a top-level issue can have under it (D-118, 5 since D-190). */
        const val MAX_SUB_ISSUE_DEPTH = 5
        const val ESTIMATE_MAX = 1000
    }
}
