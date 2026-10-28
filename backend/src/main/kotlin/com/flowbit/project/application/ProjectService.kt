package com.flowbit.project.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.common.web.ClientInfo
import com.flowbit.content.ContentEvent
import com.flowbit.content.ContentEventType
import com.flowbit.content.ContentLog
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.media.application.ImageProcessor
import com.flowbit.media.application.ImageSpec
import com.flowbit.media.application.ImageStorage
import com.flowbit.notification.ProjectAccessRequestNotification
import com.flowbit.project.domain.Project
import com.flowbit.project.domain.ProjectHealth
import com.flowbit.project.domain.ProjectPriority
import com.flowbit.project.domain.ProjectRepository
import com.flowbit.project.domain.ProjectRole
import com.flowbit.project.domain.ProjectStatus
import com.flowbit.project.domain.effectiveProjectRole
import com.flowbit.user.application.UserService
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** A project as one particular person sees it. */
data class ProjectView(
    val id: UUID,
    val name: String,
    val description: String,
    /** File name of the uploaded icon in storage; null for the default icon. */
    val iconKey: String?,
    /**
     * What this person may do here. Workspace administrators act as managers everywhere. Null in
     * the project list for a project the person has not been added to: they see it but cannot enter.
     */
    val myRole: ProjectRole?,
    val memberCount: Int,
    val createdAt: Instant,
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val lead: ProjectPerson?,
    val startDate: LocalDate?,
    val targetDate: LocalDate?,
    val labels: List<String>,
    /** Issues of the project, canceled ones not counted; progress is doneCount / issueCount. */
    val issueCount: Int,
    val doneCount: Int,
    val health: ProjectHealth?,
    val healthUpdatedAt: Instant?,
    /** Whether this person follows the project: is told about every change to its issues. */
    val subscribed: Boolean,
    /**
     * Whether the person was added to the project. A workspace administrator may enter every
     * project (`myRole`) but belongs only to these; the sidebar lists only these.
     */
    val member: Boolean = false,
    /** When the project was archived; null for a project in use. */
    val archivedAt: Instant? = null,
    /** Whether every member of the workspace enters the project as an editor (D-192). */
    val openToAll: Boolean = false,
    /** Whether the project's issues carry an estimate (D-228). */
    val estimatesEnabled: Boolean = false,
    /** Whether an issue made by someone who does not manage the project waits in the triage queue (D-231). */
    val triageEnabled: Boolean = false,
    /** The overview document as raw editor JSON. Loaded for a single project only, never in lists. */
    val content: String? = null,
)

/** Who may enter a project, for the screen shown to someone who may not. */
data class ProjectEntry(
    val id: UUID,
    val name: String,
    val myRole: ProjectRole?,
    /** Whom to ask for access. */
    val managers: List<ProjectMemberRow>,
    /** When the caller asked to be let in and is still waiting (D-128). */
    val requestedAt: Instant? = null,
)

/** A request to be let in, and what to tell the people who can answer it (null when it was already waiting). */
data class AccessRequestResult(
    val entry: ProjectEntry,
    val notification: ProjectAccessRequestNotification?,
)

/**
 * Projects and who may enter them. Every method takes the acting user and decides access itself.
 * Everyone sees every project in the list (D-047), but only members may enter one, unless the
 * project is open to everyone (D-192); to anyone else its data answers "not found".
 */
@Service
class ProjectService(
    private val repository: ProjectRepository,
    private val memberStore: ProjectMemberStore,
    private val entityTemplate: R2dbcEntityTemplate,
    private val userService: UserService,
    private val auditService: AuditService,
    private val richText: RichText,
    private val imageProcessor: ImageProcessor,
    private val imageStorage: ImageStorage,
    private val contentLog: ContentLog,
    private val clock: Clock,
) {
    /** Every active project; `myRole` is null for those the user may not enter. */
    suspend fun list(user: AuthenticatedUser): List<ProjectView> = memberStore.projectsVisibleTo(user.id, includeAll = true).map(::view)

    /**
     * The archived projects, for looking up what was put away. Like the list of active ones,
     * everyone sees them all; `myRole` says who may restore one.
     */
    suspend fun listArchived(user: AuthenticatedUser): List<ProjectView> = memberStore.archivedProjects(user.id).map(::view)

    /**
     * Puts a project away: it leaves the lists and the sidebar, its issues leave the issue lists,
     * and nobody can enter it until it is restored. Nothing is deleted. Managers only.
     */
    @Transactional
    suspend fun archive(
        user: AuthenticatedUser,
        projectId: String,
        client: ClientInfo,
    ): UUID {
        val project = manage(user, projectId)
        val now = clock.instant()
        repository.updateArchivedAt(project.id, now, now)
        auditService.record(AuditEventType.PROJECT_ARCHIVED, user.id, client, mapOf("projectId" to project.id.toString()))
        contentLog.record(projectTarget(project.id), user.id, listOf(ContentEvent(ContentEventType.ARCHIVED)), now)
        return project.id
    }

    /** Brings an archived project back as it was. Its managers only (and workspace administrators). */
    @Transactional
    suspend fun restore(
        user: AuthenticatedUser,
        projectId: String,
        client: ClientInfo,
    ): ProjectView {
        val project =
            runCatching { UUID.fromString(projectId) }
                .getOrNull()
                ?.let { repository.findById(it) }
                ?.takeIf { it.archivedAt != null }
                ?: throw notFound()
        val role = project.roleOf(memberStore.roleOf(project.id, user.id))
        if (role?.canManage != true) throw ForbiddenException("프로젝트를 관리할 권한이 없습니다")
        val now = clock.instant()
        repository.updateArchivedAt(project.id, null, now)
        auditService.record(AuditEventType.PROJECT_RESTORED, user.id, client, mapOf("projectId" to project.id.toString()))
        contentLog.record(projectTarget(project.id), user.id, listOf(ContentEvent(ContentEventType.RESTORED)), now)
        return detail(project.id, user)
    }

    /** Answers anyone signed in: the project's name, the caller's role (null: no access) and its managers. */
    suspend fun entry(
        user: AuthenticatedUser,
        projectId: String,
    ): ProjectEntry {
        val project = findActive(projectId) ?: throw notFound()
        val role = project.roleOf(memberStore.roleOf(project.id, user.id))
        val managers = memberStore.members(project.id).filter { it.role == ProjectRole.MANAGER }
        val requestedAt = if (role == null) memberStore.accessRequestedAt(project.id, user.id) else null
        return ProjectEntry(project.id, project.name, role, managers, requestedAt)
    }

    /**
     * Someone who may not enter asks to be let in (D-128). The project's active managers are told,
     * or the workspace administrators when it has none. Asking again while waiting changes nothing
     * and tells nobody again.
     */
    @Transactional
    suspend fun requestAccess(
        user: AuthenticatedUser,
        projectId: String,
    ): AccessRequestResult {
        val project = findActive(projectId) ?: throw notFound()
        if (project.roleOf(memberStore.roleOf(project.id, user.id)) != null) {
            throw ConflictException("ALREADY_MEMBER", "이미 이 프로젝트의 구성원입니다")
        }
        val added = memberStore.requestAccess(project.id, user.id, clock.instant())
        val notification =
            if (added) {
                val active = userService.activeUsers()
                val activeIds = active.map { it.id }.toSet()
                val managers = memberStore.members(project.id).filter { it.role == ProjectRole.MANAGER && it.userId in activeIds }.map { it.userId }
                val recipients = managers.ifEmpty { active.filter { it.role == UserRole.ADMIN }.map { it.id } }
                val requester = active.firstOrNull { it.id == user.id }
                ProjectAccessRequestNotification(
                    projectId = project.id,
                    projectName = project.name,
                    requesterId = user.id,
                    requesterName = requester?.let { it.displayName ?: it.loginId } ?: user.loginId,
                    recipientIds = recipients,
                )
            } else {
                null
            }
        return AccessRequestResult(entry(user, projectId), notification)
    }

    /** The people waiting to be let in. Managers only. */
    suspend fun accessRequests(
        user: AuthenticatedUser,
        projectId: String,
    ): List<ProjectAccessRequestRow> = memberStore.accessRequests(manage(user, projectId).id)

    /** Lets a waiting person in with the role the manager picked. Managers only. */
    @Transactional
    suspend fun approveAccess(
        user: AuthenticatedUser,
        projectId: String,
        requesterId: UUID,
        role: ProjectRole,
    ): List<ProjectMemberRow> {
        val project = manage(user, projectId)
        if (!memberStore.removeAccessRequest(project.id, requesterId)) throw accessRequestNotFound()
        userService.findActive(requesterId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        memberStore.put(project.id, requesterId, role, clock.instant())
        return memberStore.members(project.id)
    }

    /** Turns a request down; the person may ask again. Managers only. */
    @Transactional
    suspend fun rejectAccess(
        user: AuthenticatedUser,
        projectId: String,
        requesterId: UUID,
    ) {
        val project = manage(user, projectId)
        if (!memberStore.removeAccessRequest(project.id, requesterId)) throw accessRequestNotFound()
    }

    suspend fun get(
        user: AuthenticatedUser,
        projectId: String,
    ): ProjectView = detail(access(user, projectId).project.id, user)

    /** Any signed-in user may create a project and becomes its first manager. */
    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        rawName: String,
        rawDescription: String?,
        openToAll: Boolean,
        client: ClientInfo,
    ): ProjectView {
        val name = rawName.trim()
        val description = rawDescription?.trim().orEmpty()
        val errors = detailErrors(name, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)

        val now = clock.instant()
        val project = Project(UuidV7.generate(now), name, description, user.id, null, now, now, openToAll)
        entityTemplate.insert(project).awaitSingle()
        memberStore.put(project.id, user.id, ProjectRole.MANAGER, now)
        auditService.record(AuditEventType.PROJECT_CREATED, user.id, client, mapOf("projectId" to project.id.toString()))
        return view(memberStore.summary(project.id, user.id))
    }

    /**
     * Changes the fields present in `changes`. Renaming needs the manager role; the summary
     * (`description`), the overview document (`content`) and the properties (`status`, `priority`,
     * `leadId`, `startDate`, `targetDate`, `labels`) can be written by anyone who may edit. Who may
     * enter the project (`openToAll`, D-192) is the manager's to decide, like the name.
     */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        projectId: String,
        changes: Map<String, Any?>,
        client: ClientInfo,
    ): ProjectView {
        val access = access(user, projectId)
        val project = access.project
        if (!access.role.canEdit ||
            (("name" in changes || "openToAll" in changes || "estimatesEnabled" in changes || "triageEnabled" in changes) && !access.role.canManage)
        ) {
            throw ForbiddenException("프로젝트를 수정할 권한이 없습니다")
        }
        val openToAll =
            if ("openToAll" in changes) {
                changes["openToAll"] as? Boolean ?: throw ValidationException("openToAll", "INVALID", "값이 올바르지 않습니다")
            } else {
                project.openToAll
            }
        val estimatesEnabled =
            if ("estimatesEnabled" in changes) {
                changes["estimatesEnabled"] as? Boolean ?: throw ValidationException("estimatesEnabled", "INVALID", "값이 올바르지 않습니다")
            } else {
                project.estimatesEnabled
            }
        val triageEnabled =
            if ("triageEnabled" in changes) {
                changes["triageEnabled"] as? Boolean ?: throw ValidationException("triageEnabled", "INVALID", "값이 올바르지 않습니다")
            } else {
                project.triageEnabled
            }
        val name = if ("name" in changes) (changes["name"] as? String)?.trim().orEmpty() else project.name
        val description =
            if ("description" in changes) (changes["description"] as? String)?.trim().orEmpty() else project.description
        val errors = detailErrors(name, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        // The overview is a page whose blocks can be commented on: every top-level block has a number (D-165).
        val document = if ("content" in changes) changes["content"]?.let { richText.parse(it, blockIds = true) } else null
        val current = memberStore.summary(project.id, user.id)
        val properties = properties(project.id, current, changes)
        val contentChanged = "content" in changes && !richText.same(richText.load(RichText.ContentTable.PROJECTS, project.id), document)

        val now = clock.instant()
        repository.updateDetails(project.id, name, description, now)
        if (PROPERTY_FIELDS.any { it in changes }) memberStore.updateProperties(project.id, properties, now)
        if ("content" in changes) richText.store(RichText.ContentTable.PROJECTS, project.id, document)
        val accessEvents = changeAccess(project, openToAll, user, client, now)
        if (estimatesEnabled != project.estimatesEnabled) repository.updateEstimatesEnabled(project.id, estimatesEnabled, now)
        if (triageEnabled != project.triageEnabled) repository.updateTriageEnabled(project.id, triageEnabled, now)
        contentLog.record(
            projectTarget(project.id),
            user.id,
            activityOf(project.name, name, current, properties, contentChanged) + accessEvents,
            now,
        )
        return detail(project.id, user)
    }

    /**
     * Opens the project to every member of the workspace, or closes it again (D-192). Opening answers the
     * requests that were waiting to be let in. Nothing happens, and nothing is recorded, when the setting
     * is already what it should be.
     */
    private suspend fun changeAccess(
        project: Project,
        openToAll: Boolean,
        user: AuthenticatedUser,
        client: ClientInfo,
        now: Instant,
    ): List<ContentEvent> {
        if (openToAll == project.openToAll) return emptyList()
        repository.updateOpenToAll(project.id, openToAll, now)
        if (openToAll) memberStore.removeAccessRequests(project.id)
        auditService.record(
            AuditEventType.PROJECT_ACCESS_CHANGED,
            user.id,
            client,
            mapOf("projectId" to project.id.toString(), "openToAll" to openToAll.toString()),
        )
        return listOf(ContentEvent(ContentEventType.ACCESS_CHANGED, mapOf("open" to openToAll)))
    }

    private fun projectTarget(projectId: UUID) = ContentTarget(ContentTargetType.PROJECT, projectId)

    /** What a change to the name, the overview and the properties is in the project's activity. */
    private suspend fun activityOf(
        oldName: String,
        name: String,
        before: ProjectSummaryRow,
        after: ProjectProperties,
        contentChanged: Boolean,
    ): List<ContentEvent> {
        fun span(
            start: LocalDate?,
            end: LocalDate?,
        ): String? =
            when {
                start == null && end == null -> null
                start != null && end != null -> "$start ~ $end"
                start != null -> "$start ~"
                else -> "~ $end"
            }
        val events = mutableListOf<ContentEvent>()
        if (name != oldName) events += ContentEvent(ContentEventType.NAME_CHANGED, mapOf("from" to oldName, "to" to name))
        if (contentChanged) events += ContentEvent(ContentEventType.CONTENT_EDITED)
        if (before.status != after.status) {
            events += ContentEvent(ContentEventType.STATUS_CHANGED, mapOf("from" to before.status.name, "to" to after.status.name))
        }
        if (before.priority != after.priority) {
            events += ContentEvent(ContentEventType.PRIORITY_CHANGED, mapOf("from" to before.priority.name, "to" to after.priority.name))
        }
        if (before.lead?.id != after.leadId) {
            val from = before.lead?.let { it.displayName ?: it.loginId }
            events += ContentEvent(ContentEventType.LEAD_CHANGED, mapOf("from" to from, "to" to after.leadId?.let { contentLog.nameOf(it) }))
        }
        if (before.startDate != after.startDate || before.targetDate != after.targetDate) {
            events +=
                ContentEvent(
                    ContentEventType.DATES_CHANGED,
                    mapOf("from" to span(before.startDate, before.targetDate), "to" to span(after.startDate, after.targetDate)),
                )
        }
        val added = after.labels.filter { label -> before.labels.none { it.equals(label, ignoreCase = true) } }
        val removed = before.labels.filter { label -> after.labels.none { it.equals(label, ignoreCase = true) } }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            events += ContentEvent(ContentEventType.LABELS_CHANGED, mapOf("added" to added, "removed" to removed))
        }
        return events
    }

    /** Who joined, left or got another role, in the project's activity. */
    private suspend fun recordMember(
        projectId: UUID,
        actorId: UUID,
        memberId: UUID,
        before: ProjectRole?,
        after: ProjectRole?,
    ) {
        val name = contentLog.nameOf(memberId)
        val event =
            when {
                after == null -> if (before == null) return else ContentEvent(ContentEventType.MEMBER_REMOVED, mapOf("name" to name))
                before == null -> ContentEvent(ContentEventType.MEMBER_ADDED, mapOf("name" to name, "role" to after.name))
                before != after -> ContentEvent(ContentEventType.MEMBER_ROLE_CHANGED, mapOf("name" to name, "from" to before.name, "to" to after.name))
                else -> return
            }
        contentLog.record(projectTarget(projectId), actorId, listOf(event), clock.instant())
    }

    /** The current properties with the ones present in `changes` applied and checked. */
    private suspend fun properties(
        projectId: UUID,
        current: ProjectSummaryRow,
        changes: Map<String, Any?>,
    ): ProjectProperties {
        fun invalid(field: String) = ValidationException(field, "INVALID", "값이 올바르지 않습니다")

        fun string(field: String): String? = changes[field]?.let { it as? String ?: throw invalid(field) }

        fun <T> parsed(
            field: String,
            parse: (String) -> T,
        ): T? = string(field)?.let { runCatching { parse(it) }.getOrElse { throw invalid(field) } }

        val status = if ("status" in changes) parsed("status", ProjectStatus::valueOf) ?: throw invalid("status") else current.status
        val priority =
            if ("priority" in changes) parsed("priority", ProjectPriority::valueOf) ?: throw invalid("priority") else current.priority
        val leadId = if ("leadId" in changes) parsed("leadId", UUID::fromString) else current.lead?.id
        val startDate = if ("startDate" in changes) parsed("startDate", LocalDate::parse) else current.startDate
        val targetDate = if ("targetDate" in changes) parsed("targetDate", LocalDate::parse) else current.targetDate
        val labels = if ("labels" in changes) labels(changes["labels"]) else current.labels

        if (leadId != null && leadId != current.lead?.id && !memberStore.isActiveMember(projectId, leadId)) {
            throw ValidationException("leadId", "NOT_A_MEMBER", "리드는 이 프로젝트의 구성원이어야 합니다")
        }
        if (startDate != null && targetDate != null && startDate > targetDate) {
            throw ValidationException("targetDate", "BEFORE_START", "종료일은 시작일보다 앞설 수 없습니다")
        }
        return ProjectProperties(status, priority, leadId, startDate, targetDate, labels)
    }

    /** Trimmed, without duplicates (ignoring case), in the order given. */
    private fun labels(raw: Any?): List<String> {
        val items = raw as? List<*> ?: throw ValidationException("labels", "INVALID", "값이 올바르지 않습니다")
        val labels =
            items
                .map { (it as? String)?.trim() ?: throw ValidationException("labels", "INVALID", "값이 올바르지 않습니다") }
                .distinctBy { it.lowercase(Locale.ROOT) }
        if (labels.any { it.isEmpty() || it.length > Project.LABEL_MAX_LENGTH }) {
            throw ValidationException("labels", "INVALID_LABEL", "라벨은 1~${Project.LABEL_MAX_LENGTH}자여야 합니다")
        }
        if (labels.size > Project.LABELS_MAX) {
            throw ValidationException("labels", "TOO_MANY", "라벨은 ${Project.LABELS_MAX}개까지 붙일 수 있습니다")
        }
        return labels
    }

    private suspend fun detail(
        projectId: UUID,
        user: AuthenticatedUser,
    ): ProjectView = view(memberStore.summary(projectId, user.id)).copy(content = richText.load(RichText.ContentTable.PROJECTS, projectId))

    /** Replaces the project icon with the upload, converted to a 256px square WebP. The upload itself is kept too. Managers only. */
    suspend fun replaceIcon(
        user: AuthenticatedUser,
        projectId: String,
        upload: ByteArray,
    ): ProjectView {
        val project = manage(user, projectId)
        val previous = repository.findIconKey(project.id)
        val iconKey = imageStorage.store(webp = imageProcessor.toWebp(upload, ImageSpec.PROJECT_ICON), original = upload)
        repository.updateIconKey(project.id, iconKey, clock.instant())
        imageStorage.delete(previous)
        contentLog.record(projectTarget(project.id), user.id, listOf(ContentEvent(ContentEventType.ICON_CHANGED)), clock.instant())
        return detail(project.id, user)
    }

    /** Back to the default icon. Managers only. */
    suspend fun removeIcon(
        user: AuthenticatedUser,
        projectId: String,
    ): ProjectView {
        val project = manage(user, projectId)
        val previous = repository.findIconKey(project.id)
        repository.updateIconKey(project.id, null, clock.instant())
        imageStorage.delete(previous)
        contentLog.record(projectTarget(project.id), user.id, listOf(ContentEvent(ContentEventType.ICON_CHANGED)), clock.instant())
        return detail(project.id, user)
    }

    suspend fun members(
        user: AuthenticatedUser,
        projectId: String,
    ): List<ProjectMemberRow> = memberStore.members(access(user, projectId).project.id)

    /**
     * Every project with the role [memberId] has in it (null: not a member), for the administrator
     * screen that sets one person's project permissions. Only reached through `/api/v1/admin`.
     */
    suspend fun rolesOf(memberId: UUID): List<Pair<Project, ProjectRole?>> {
        userService.findActive(memberId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        return repository
            .findAll()
            .toList()
            .filter { it.archivedAt == null }
            .sortedBy { it.name.lowercase() }
            .map { it to memberStore.roleOf(it.id, memberId) }
    }

    /**
     * For the administrator screen that sets one person's project permissions (`/api/v1/admin`
     * only): adds them to any project in use, or changes their role, without being a member of it
     * oneself. A project still keeps at least one manager.
     */
    @Transactional
    suspend fun adminPutMember(
        actorId: UUID,
        projectId: UUID,
        memberId: UUID,
        role: ProjectRole,
    ) {
        val project = repository.findById(projectId)?.takeIf { it.archivedAt == null } ?: throw notFound()
        userService.findActive(memberId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        if (role != ProjectRole.MANAGER) requireAnotherManager(project.id, memberId)
        val before = memberStore.roleOf(project.id, memberId)
        memberStore.put(project.id, memberId, role, clock.instant())
        memberStore.removeAccessRequest(project.id, memberId)
        recordMember(project.id, actorId, memberId, before, role)
    }

    /** Takes the person out of a project, from the administrator screen. */
    @Transactional
    suspend fun adminRemoveMember(
        actorId: UUID,
        projectId: UUID,
        memberId: UUID,
    ) {
        val project = repository.findById(projectId)?.takeIf { it.archivedAt == null } ?: throw notFound()
        requireAnotherManager(project.id, memberId)
        val before = memberStore.roleOf(project.id, memberId)
        memberStore.remove(project.id, memberId)
        recordMember(project.id, actorId, memberId, before, null)
    }

    /** Adds a member or changes their role. */
    @Transactional
    suspend fun putMember(
        user: AuthenticatedUser,
        projectId: String,
        memberId: UUID,
        role: ProjectRole,
    ): List<ProjectMemberRow> {
        val project = manage(user, projectId)
        userService.findActive(memberId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        if (role != ProjectRole.MANAGER) requireAnotherManager(project.id, memberId)
        val before = memberStore.roleOf(project.id, memberId)
        memberStore.put(project.id, memberId, role, clock.instant())
        // Added directly: a request they had waiting is answered by it.
        memberStore.removeAccessRequest(project.id, memberId)
        recordMember(project.id, user.id, memberId, before, role)
        return memberStore.members(project.id)
    }

    @Transactional
    suspend fun removeMember(
        user: AuthenticatedUser,
        projectId: String,
        memberId: UUID,
    ) {
        val project = manage(user, projectId)
        requireAnotherManager(project.id, memberId)
        val before = memberStore.roleOf(project.id, memberId)
        memberStore.remove(project.id, memberId)
        recordMember(project.id, user.id, memberId, before, null)
    }

    /**
     * Follow or stop following a project: whoever follows it is told about every change to its
     * issues through the notification channels. Open to everyone who may enter it, the view-only
     * role included.
     */
    suspend fun setSubscribed(
        user: AuthenticatedUser,
        projectId: String,
        subscribed: Boolean,
    ): ProjectView {
        val project = access(user, projectId).project
        if (subscribed) memberStore.subscribe(project.id, user.id) else memberStore.unsubscribe(project.id, user.id)
        return detail(project.id, user)
    }

    /** The project and the caller's role in it, or "not found" when they may not enter. */
    suspend fun access(
        user: AuthenticatedUser,
        projectId: String,
    ): ProjectAccess {
        val project = findActive(projectId) ?: throw notFound()
        val role = project.roleOf(memberStore.roleOf(project.id, user.id)) ?: throw notFound()
        return ProjectAccess(project, role)
    }

    /** `projectId` comes from an address, so it may be anything; what is not an id finds nothing. */
    private suspend fun findActive(projectId: String): Project? =
        runCatching { UUID.fromString(projectId) }
            .getOrNull()
            ?.let { repository.findById(it) }
            ?.takeIf { it.archivedAt == null }

    /** The same for a project known by id; null when it is gone or the caller may not enter it. */
    suspend fun accessById(
        user: AuthenticatedUser,
        projectId: UUID,
    ): ProjectAccess? {
        val project = repository.findById(projectId)?.takeIf { it.archivedAt == null } ?: return null
        val role = project.roleOf(memberStore.roleOf(project.id, user.id)) ?: return null
        return ProjectAccess(project, role)
    }

    private suspend fun manage(
        user: AuthenticatedUser,
        projectId: String,
    ): Project {
        val access = access(user, projectId)
        if (!access.role.canManage) throw ForbiddenException("프로젝트를 관리할 권한이 없습니다")
        return access.project
    }

    /** A project must keep at least one manager: refuse when `memberId` is the only one. */
    private suspend fun requireAnotherManager(
        projectId: UUID,
        memberId: UUID,
    ) {
        val managers = memberStore.lockManagerIds(projectId)
        if (managers == listOf(memberId)) {
            throw ConflictException("LAST_MANAGER", "프로젝트에는 관리 역할이 한 명 이상 있어야 합니다. 다른 사람을 먼저 관리 역할로 지정하세요.")
        }
    }

    private fun view(row: ProjectSummaryRow) =
        ProjectView(
            id = row.id,
            name = row.name,
            description = row.description,
            iconKey = row.iconKey,
            myRole = effectiveProjectRole(row.memberRole, row.openToAll),
            memberCount = row.memberCount,
            createdAt = row.createdAt,
            status = row.status,
            priority = row.priority,
            lead = row.lead,
            startDate = row.startDate,
            targetDate = row.targetDate,
            labels = row.labels,
            issueCount = row.issueCount,
            doneCount = row.doneCount,
            health = row.health,
            healthUpdatedAt = row.healthUpdatedAt,
            subscribed = row.subscribed,
            member = row.memberRole != null,
            archivedAt = row.archivedAt,
            openToAll = row.openToAll,
            estimatesEnabled = row.estimatesEnabled,
            triageEnabled = row.triageEnabled,
        )

    private fun detailErrors(
        name: String,
        description: String,
    ): List<FieldError> =
        listOfNotNull(
            when {
                name.isEmpty() -> FieldError("name", "REQUIRED", "프로젝트 이름을 입력하세요")
                name.length > Project.NAME_MAX_LENGTH -> FieldError("name", "TOO_LONG", "프로젝트 이름은 ${Project.NAME_MAX_LENGTH}자 이하여야 합니다")
                else -> null
            },
            if (description.length > Project.DESCRIPTION_MAX_LENGTH) {
                FieldError("description", "TOO_LONG", "설명은 ${Project.DESCRIPTION_MAX_LENGTH}자 이하여야 합니다")
            } else {
                null
            },
        )

    private fun notFound() = NotFoundException("PROJECT_NOT_FOUND", "프로젝트를 찾을 수 없습니다")

    private fun accessRequestNotFound() = NotFoundException("ACCESS_REQUEST_NOT_FOUND", "접근 요청을 찾을 수 없습니다")
}

private val PROPERTY_FIELDS = listOf("status", "priority", "leadId", "startDate", "targetDate", "labels")

data class ProjectAccess(
    val project: Project,
    val role: ProjectRole,
)
