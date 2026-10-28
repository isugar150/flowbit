package com.flowbit.customproperty.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.issue.application.IssueActivityStore
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueEvent
import com.flowbit.issue.application.IssueLookup
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ProjectIssueFieldService(
    private val store: ProjectIssueFieldStore,
    private val properties: CustomPropertyService,
    private val projects: ProjectService,
    private val lookup: IssueLookup,
    private val activities: IssueActivityStore,
    private val audit: AuditService,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun list(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
    ): List<ProjectIssueField> = store.fields(projects.access(user, project, admin).project.id)

    @Transactional
    suspend fun save(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
        id: UUID?,
        input: ProjectFieldInput,
        client: ClientInfo,
    ): ProjectIssueField {
        val access = projects.access(user, project, admin)
        if (!access.role.canManage) throw ForbiddenException("티켓 속성은 프로젝트 관리 역할만 설정할 수 있습니다")
        val projectId = access.project.id
        db
            .sql("select id from projects where id = :id for update")
            .bind("id", projectId)
            .map {
                row,
                _,
                ->
                checkNotNull(row.get("id", UUID::class.java))
            }.one()
            .awaitSingle()
        val current = store.fields(projectId)
        val existing = id?.let { fieldId -> current.firstOrNull { it.id == fieldId } ?: throw missing() }
        val label = input.label.trim()
        if (label.length !in 1..30) throw ValidationException("label", "INVALID", "속성 이름은 1~30자여야 합니다")
        if (id == null && current.size >= 20) throw ValidationException("label", "TOO_MANY", "프로젝트 공통 속성은 20개까지 만들 수 있습니다")
        if (current.any { it.id != id && it.label.equals(label, true) }) throw ConflictException("CUSTOM_PROPERTY_EXISTS", "같은 이름의 속성이 이미 있습니다")
        if (existing != null && existing.type != input.type) throw ValidationException("type", "IMMUTABLE", "속성 형식은 만든 뒤 변경할 수 없습니다")
        // Only a person field has the choice between several people and one (D-238); every other type stores the default.
        val multiple = input.multiple || input.type != CustomPropertyType.PERSON
        val checked =
            input.copy(
                label = label,
                multiple = multiple,
                defaultValue = properties.normalizeForProject(input.type, input.defaultValue, projectId, multiple),
            )
        val field = store.save(projectId, id, checked)
        // Changing it changes the shape of the values the tickets hold: one becomes a list of one, of several the first stays.
        if (existing != null && existing.type == CustomPropertyType.PERSON && existing.multiple != multiple) store.convertPersonValues(field.id, multiple)
        audit.record(
            AuditEventType.PROJECT_ISSUE_FIELDS_CHANGED,
            user.id,
            client,
            mapOf(
                "projectId" to projectId.toString(),
                "fieldId" to field.id.toString(),
                "change" to "saved",
            ),
        )
        return field
    }

    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
        id: UUID,
        client: ClientInfo,
    ) {
        val access = projects.access(user, project, admin)
        if (!access.role.canManage) throw ForbiddenException("티켓 속성은 프로젝트 관리 역할만 설정할 수 있습니다")
        store.delete(access.project.id, id)
        audit.record(
            AuditEventType.PROJECT_ISSUE_FIELDS_CHANGED,
            user.id,
            client,
            mapOf(
                "projectId" to access.project.id.toString(),
                "fieldId" to id.toString(),
                "change" to "deleted",
            ),
        )
    }

    suspend fun checkValues(
        projectId: UUID?,
        inputs: List<IssueFieldInput>,
    ): List<CheckedIssueField> {
        if (inputs.isEmpty()) return emptyList()
        val definitions = projectId?.let { store.fields(it) }.orEmpty()
        if (inputs.size > 20 ||
            inputs.map { it.fieldId }.distinct().size != inputs.size
        ) {
            throw ValidationException("projectFields", "INVALID", "프로젝트 속성은 중복 없이 20개까지 지정할 수 있습니다")
        }
        return inputs.map { input ->
            val definition = definitions.firstOrNull { it.id == input.fieldId } ?: throw missing()
            CheckedIssueField(input.fieldId, properties.normalizeForProject(definition.type, input.value, projectId, definition.multiple))
        }
    }

    /**
     * A new sub-issue starts with the values its parent was given (D-239), like it starts with the parent's assignees. Called after
     * [storeChecked], so a value the request itself gave wins; a field the parent has no value of its own for stays at the project's
     * default. From then on the two are separate: changing one does not change the other.
     */
    suspend fun inheritValues(
        parentId: UUID,
        issueId: UUID,
    ) = store.copy(parentId, issueId)

    suspend fun storeChecked(
        issueId: UUID,
        values: List<CheckedIssueField>,
    ) {
        values.forEach { store.putValue(issueId, it.fieldId, it.value) }
    }

    @Transactional
    suspend fun updateValue(
        user: AuthenticatedUser,
        issueId: UUID,
        fieldId: UUID,
        rawValue: Any?,
    ): CustomPropertyChange {
        lookup.editable(lookup.accessById(user, issueId))
        db
            .sql("select id from issues where id = :id for update")
            .bind("id", issueId)
            .map {
                row,
                _,
                ->
                checkNotNull(row.get("id", UUID::class.java))
            }.one()
            .awaitSingle()
        val access = lookup.editable(lookup.accessById(user, issueId))
        val issue = access.issue
        val projectId = issue.projectId ?: throw missing()
        val before = store.values(issueId, projectId).firstOrNull { it.id == fieldId } ?: throw missing()
        val value = properties.normalizeForProject(before.type, rawValue, projectId, before.multiple)
        store.putValue(issueId, fieldId, value)
        val item = store.values(issueId, projectId).first { it.id == fieldId }
        val events =
            if (item.value ==
                before.value
            ) {
                emptyList()
            } else {
                listOf(
                    IssueEvent(
                        IssueActivityType.CUSTOM_PROPERTY_CHANGED,
                        mapOf(
                            "op" to "CHANGED",
                            "label" to item.label,
                            "from" to shown(before),
                            "to" to shown(item),
                        ),
                    ),
                )
            }
        activities.record(issueId, user.id, events, clock.instant())
        return CustomPropertyChange(item, IssueChange(checkNotNull(lookup.detail(issue.number)), events, lookup.recipients(issueId, user)), emptyList())
    }

    private fun shown(item: CustomPropertyView): String? =
        if (item.type ==
            CustomPropertyType.PERSON
        ) {
            item.people.joinToString(", ") { it.displayName ?: it.loginId }.ifEmpty { null }
        } else {
            item.value?.toString()
        }

    private fun missing() = NotFoundException("PROJECT_FIELD_NOT_FOUND", "이 프로젝트의 티켓 속성을 찾을 수 없습니다")
}
