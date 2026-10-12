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
import com.flowbit.project.domain.Project
import com.flowbit.project.domain.ProjectRepository
import com.flowbit.project.domain.ProjectRole
import com.flowbit.user.application.UserService
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A project as one particular person sees it. */
data class ProjectView(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    /** What this person may do here. Workspace administrators act as managers everywhere. */
    val myRole: ProjectRole,
    val memberCount: Int,
    val createdAt: Instant,
    /** The overview document as raw editor JSON. Loaded for a single project only, never in lists. */
    val content: String? = null,
)

/**
 * Projects and who may enter them. Every method takes the acting user and decides access itself;
 * a project the user may not enter is reported as not found, so its existence is not revealed.
 */
@Service
class ProjectService(
    private val repository: ProjectRepository,
    private val memberStore: ProjectMemberStore,
    private val entityTemplate: R2dbcEntityTemplate,
    private val userService: UserService,
    private val auditService: AuditService,
    private val richText: RichText,
    private val clock: Clock,
) {
    suspend fun list(user: AuthenticatedUser): List<ProjectView> = memberStore.projectsVisibleTo(user.id, includeAll = user.isAdmin).map { view(it, user) }

    suspend fun get(
        user: AuthenticatedUser,
        key: String,
    ): ProjectView = detail(access(user, key).project.id, user)

    /** Any signed-in user may create a project and becomes its first manager. */
    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        rawName: String,
        rawKey: String,
        rawDescription: String?,
        client: ClientInfo,
    ): ProjectView {
        val name = rawName.trim()
        val key = rawKey.trim().uppercase(Locale.ROOT)
        val description = rawDescription?.trim().orEmpty()
        val errors = detailErrors(name, description).toMutableList()
        if (!Project.KEY_PATTERN.matches(key)) {
            errors += FieldError("key", "INVALID_FORMAT", "키는 영문 대문자로 시작하는 영문 대문자·숫자 2~10자여야 합니다")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (repository.existsByKey(key)) throw keyTaken()

        val now = clock.instant()
        val project = Project(UuidV7.generate(now), key, name, description, 1, user.id, null, now, now)
        try {
            entityTemplate.insert(project).awaitSingle()
        } catch (_: DuplicateKeyException) {
            throw keyTaken()
        }
        memberStore.put(project.id, user.id, ProjectRole.MANAGER, now)
        auditService.record(AuditEventType.PROJECT_CREATED, user.id, client, mapOf("projectKey" to key))
        return view(memberStore.summary(project.id, user.id), user)
    }

    /**
     * Changes the fields present in `changes`. Renaming needs the manager role; the summary
     * (`description`) and the overview document (`content`) can be written by anyone who may edit.
     */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        key: String,
        changes: Map<String, Any?>,
    ): ProjectView {
        val access = access(user, key)
        val project = access.project
        if (!access.role.canEdit || ("name" in changes && !access.role.canManage)) {
            throw ForbiddenException("프로젝트를 고칠 권한이 없습니다")
        }
        val name = if ("name" in changes) (changes["name"] as? String)?.trim().orEmpty() else project.name
        val description =
            if ("description" in changes) (changes["description"] as? String)?.trim().orEmpty() else project.description
        val errors = detailErrors(name, description)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val document = if ("content" in changes) changes["content"]?.let { richText.parse(it) } else null

        repository.updateDetails(project.id, name, description, clock.instant())
        if ("content" in changes) richText.store(RichText.ContentTable.PROJECTS, project.id, document)
        return detail(project.id, user)
    }

    private suspend fun detail(
        projectId: UUID,
        user: AuthenticatedUser,
    ): ProjectView = view(memberStore.summary(projectId, user.id), user).copy(content = richText.load(RichText.ContentTable.PROJECTS, projectId))

    suspend fun members(
        user: AuthenticatedUser,
        key: String,
    ): List<ProjectMemberRow> = memberStore.members(access(user, key).project.id)

    /** Adds a member or changes their role. */
    @Transactional
    suspend fun putMember(
        user: AuthenticatedUser,
        key: String,
        memberId: UUID,
        role: ProjectRole,
    ): List<ProjectMemberRow> {
        val project = manage(user, key)
        userService.findActive(memberId) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")
        if (role != ProjectRole.MANAGER) requireAnotherManager(project.id, memberId)
        memberStore.put(project.id, memberId, role, clock.instant())
        return memberStore.members(project.id)
    }

    @Transactional
    suspend fun removeMember(
        user: AuthenticatedUser,
        key: String,
        memberId: UUID,
    ) {
        val project = manage(user, key)
        requireAnotherManager(project.id, memberId)
        memberStore.remove(project.id, memberId)
    }

    /** The project and the caller's role in it, or "not found" when they may not enter. */
    suspend fun access(
        user: AuthenticatedUser,
        key: String,
    ): ProjectAccess {
        val project = repository.findActiveByKey(key.uppercase(Locale.ROOT)) ?: throw notFound()
        val role = effectiveRole(memberStore.roleOf(project.id, user.id), user) ?: throw notFound()
        return ProjectAccess(project, role)
    }

    private suspend fun manage(
        user: AuthenticatedUser,
        key: String,
    ): Project {
        val access = access(user, key)
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
            throw ConflictException("LAST_MANAGER", "프로젝트에는 관리 역할이 한 명 이상 있어야 합니다. 다른 사람을 먼저 관리 역할로 지정하세요")
        }
    }

    private fun view(
        row: ProjectSummaryRow,
        user: AuthenticatedUser,
    ) = ProjectView(
        id = row.id,
        key = row.key,
        name = row.name,
        description = row.description,
        myRole = checkNotNull(effectiveRole(row.memberRole, user)),
        memberCount = row.memberCount,
        createdAt = row.createdAt,
    )

    private fun effectiveRole(
        memberRole: ProjectRole?,
        user: AuthenticatedUser,
    ): ProjectRole? = if (user.isAdmin) ProjectRole.MANAGER else memberRole

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

    private fun keyTaken() = ConflictException("PROJECT_KEY_TAKEN", "이미 쓰고 있는 키입니다")

    private val AuthenticatedUser.isAdmin: Boolean
        get() = role == UserRole.ADMIN
}

data class ProjectAccess(
    val project: Project,
    val role: ProjectRole,
)
