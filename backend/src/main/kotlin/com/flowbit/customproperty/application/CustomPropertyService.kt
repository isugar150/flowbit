package com.flowbit.customproperty.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.content.ContentEvent
import com.flowbit.content.ContentEventType
import com.flowbit.content.ContentLog
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.issue.application.IssueActivityStore
import com.flowbit.issue.application.IssueActivityType
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueEvent
import com.flowbit.issue.application.IssueLookup
import com.flowbit.issue.application.IssueQueries
import com.flowbit.project.application.ProjectMemberStore
import com.flowbit.project.application.ProjectService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The properties people add to a project or an issue on their own (D-186): a label, a
 * kind of value and the value, kept with that one item. Whoever may see the item sees them; whoever
 * may edit it (the editor role of its project, or anyone for an issue on its own) adds, changes and
 * deletes them. What was done is kept in the item's activity and told to those who follow it.
 */
@Service
class CustomPropertyService(
    private val store: CustomPropertyStore,
    private val projectService: ProjectService,
    private val issueLookup: IssueLookup,
    private val issueQueries: IssueQueries,
    private val issueActivities: IssueActivityStore,
    private val memberStore: ProjectMemberStore,
    private val contentLog: ContentLog,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    suspend fun list(
        user: AuthenticatedUser,
        kind: CustomPropertyOwnerKind,
        id: UUID,
    ): CustomPropertyList {
        val target = resolve(user, kind, id)
        return CustomPropertyList(target.canEdit, views(store.list(target.owner)))
    }

    @Transactional
    suspend fun create(
        user: AuthenticatedUser,
        kind: CustomPropertyOwnerKind,
        id: UUID,
        rawLabel: String,
        type: CustomPropertyType,
        rawValue: Any?,
    ): CustomPropertyChange {
        val target = editable(resolve(user, kind, id))
        val label = validLabel(rawLabel)
        if (store.count(target.owner) >= MAX_PER_ITEM) {
            throw ValidationException("label", "TOO_MANY", "속성은 한 항목에 ${MAX_PER_ITEM}개까지 추가할 수 있습니다")
        }
        if (store.labelTaken(target.owner, label)) throw taken()
        val value = normalize(type, rawValue, target.projectId, current = null)
        val now = clock.instant()
        val propertyId = UuidV7.generate(now)
        store.insert(propertyId, target.owner, label, type, value?.let(jsonMapper::writeValueAsString), now)
        val item = views(listOf(checkNotNull(store.find(propertyId)))).single()
        val detail = mapOf("op" to "ADDED", "label" to label, "to" to shown(item))
        return announced(user, target, item, detail, now)
    }

    /**
     * Checks the properties a new issue is to be made with, as [create] would one by one: names that are
     * allowed and do not repeat (ignoring case), at most [MAX_PER_ITEM], and values that fit their kind.
     * `projectId` is the project the issue will be in, whose members a PERSON value may name. Nothing is
     * stored; the issue is made first, then [addChecked] keeps these with it.
     */
    suspend fun checkAll(
        inputs: List<NewCustomProperty>,
        projectId: UUID?,
    ): List<CheckedCustomProperty> {
        if (inputs.size > MAX_PER_ITEM) {
            throw ValidationException("customProperties", "TOO_MANY", "속성은 한 항목에 ${MAX_PER_ITEM}개까지 추가할 수 있습니다")
        }
        val seen = mutableSetOf<String>()
        return inputs.map { input ->
            val label = validLabel(input.label)
            if (!seen.add(label.lowercase())) throw taken()
            val value = normalize(input.type, input.value, projectId, current = null)
            CheckedCustomProperty(label, input.type, value?.let(jsonMapper::writeValueAsString))
        }
    }

    /**
     * Keeps checked properties with an issue that has just been made, in the order given. They are part of
     * making the issue, so no activity is written for them (the issue's "created" entry stands for it all);
     * the right to make the issue is the right to give it properties.
     */
    @Transactional
    suspend fun addChecked(
        issueId: UUID,
        checked: List<CheckedCustomProperty>,
        now: Instant,
    ) {
        val owner = CustomPropertyOwner(CustomPropertyOwnerKind.ISSUE, issueId)
        // A property's place in the list is the time it was added (ids made in one millisecond do not sort), so each
        // one is a microsecond after the one before.
        checked.forEachIndexed { index, property ->
            val at = now.plusNanos(index * 1_000L)
            store.insert(UuidV7.generate(at), owner, property.label, property.type, property.valueJson, at)
        }
    }

    /** Changes the fields present in `changes`: `label` and `value` (the kind of value cannot change). */
    @Transactional
    suspend fun update(
        user: AuthenticatedUser,
        propertyId: UUID,
        changes: Map<String, Any?>,
    ): CustomPropertyChange {
        val row = store.find(propertyId) ?: throw notFound()
        val target = editable(resolve(user, row.owner.kind, row.owner.id))
        val before = views(listOf(row)).single()
        val label = if ("label" in changes) validLabel(changes["label"] as? String ?: "") else row.label
        if (!label.equals(row.label, ignoreCase = true) && store.labelTaken(target.owner, label, propertyId)) throw taken()
        val currentPerson = (before.value as? String)?.takeIf { row.type == CustomPropertyType.PERSON }?.let(UUID::fromString)
        val value = if ("value" in changes) normalize(row.type, changes["value"], target.projectId, currentPerson) else before.value
        val valueJson = value?.let(jsonMapper::writeValueAsString)
        val now = clock.instant()
        store.update(propertyId, label, valueJson, now)
        val item = views(listOf(checkNotNull(store.find(propertyId)))).single()

        val details = mutableListOf<Map<String, Any?>>()
        if (label != row.label) details += mapOf("op" to "RENAMED", "label" to label, "from" to row.label, "to" to label)
        if (item.value != before.value) details += mapOf("op" to "CHANGED", "label" to label, "from" to shown(before), "to" to shown(item))
        return announced(user, target, item, details, now)
    }

    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        propertyId: UUID,
    ): CustomPropertyChange {
        val row = store.find(propertyId) ?: throw notFound()
        val target = editable(resolve(user, row.owner.kind, row.owner.id))
        val before = views(listOf(row)).single()
        store.delete(propertyId)
        val detail = mapOf("op" to "REMOVED", "label" to row.label, "from" to shown(before))
        return announced(user, target, null, detail, clock.instant())
    }

    // ----- Who may do what -----

    /** An item, and what the caller may do with its properties. */
    private data class Target(
        val owner: CustomPropertyOwner,
        val canEdit: Boolean,
        /** Whose members a PERSON value may name; null for an issue on its own, where any active person may be named. */
        val projectId: UUID?,
        /** For an issue: its number, and whether it is the one whose activity to write to. */
        val issueId: UUID? = null,
        val issueNumber: Int? = null,
        /** The realtime topics of the page that shows the item (an issue's are announced with the issue). */
        val topics: List<String> = emptyList(),
    )

    private suspend fun resolve(
        user: AuthenticatedUser,
        kind: CustomPropertyOwnerKind,
        id: UUID,
    ): Target =
        when (kind) {
            CustomPropertyOwnerKind.PROJECT -> {
                val access = projectService.accessById(user, id) ?: throw notFound()
                Target(CustomPropertyOwner(kind, id), access.role.canEdit, id, topics = listOf("project:$id"))
            }
            CustomPropertyOwnerKind.ISSUE -> {
                val access = issueLookup.accessById(user, id)
                val issue = access.issue
                Target(CustomPropertyOwner(kind, id), access.canEdit, issue.projectId, issueId = issue.id, issueNumber = issue.number)
            }
        }

    private fun editable(target: Target): Target {
        if (!target.canEdit) throw ForbiddenException("이 항목의 속성을 수정할 권한이 없습니다")
        return target
    }

    // ----- What was done -----

    /** Keeps what was done in the item's activity and says who is to be told. */
    private suspend fun announced(
        user: AuthenticatedUser,
        target: Target,
        item: CustomPropertyView?,
        detail: Map<String, Any?>,
        now: Instant,
    ) = announced(user, target, item, listOf(detail), now)

    private suspend fun announced(
        user: AuthenticatedUser,
        target: Target,
        item: CustomPropertyView?,
        details: List<Map<String, Any?>>,
        now: Instant,
    ): CustomPropertyChange {
        if (target.issueId != null && target.issueNumber != null) {
            val events = details.map { IssueEvent(IssueActivityType.CUSTOM_PROPERTY_CHANGED, it) }
            issueActivities.record(target.issueId, user.id, events, now)
            val issue = checkNotNull(issueQueries.find(target.issueNumber))
            val change = IssueChange(issue, events, issueLookup.recipients(target.issueId, user))
            return CustomPropertyChange(item, change, emptyList())
        }
        val content = ContentTarget(ContentTargetType.PROJECT, target.owner.id)
        contentLog.record(content, user.id, details.map { ContentEvent(ContentEventType.CUSTOM_PROPERTY_CHANGED, it) }, now)
        return CustomPropertyChange(item, null, target.topics)
    }

    // ----- Reading and checking values -----

    private suspend fun views(rows: List<CustomPropertyRow>): List<CustomPropertyView> {
        val values = rows.map { row -> row.valueJson?.let { jsonMapper.readValue(it, Any::class.java) } }
        val people =
            store.people(
                rows.indices.mapNotNull { index ->
                    (values[index] as? String)?.takeIf { rows[index].type == CustomPropertyType.PERSON }?.let(UUID::fromString)
                },
            )
        return rows.mapIndexed { index, row ->
            val value = values[index]
            CustomPropertyView(
                id = row.id,
                label = row.label,
                type = row.type,
                value = value,
                person = if (row.type == CustomPropertyType.PERSON) (value as? String)?.let { people[UUID.fromString(it)] } else null,
            )
        }
    }

    /** The value as the activity says it: what a person would read. */
    private fun shown(item: CustomPropertyView): String? =
        when (item.type) {
            CustomPropertyType.PERSON -> item.person?.let { it.displayName ?: it.loginId }
            CustomPropertyType.CHECKBOX -> if (item.value == true) "예" else "아니요"
            else -> item.value?.toString()
        }

    private fun validLabel(raw: String): String {
        val label = raw.trim()
        if (label.isEmpty()) throw ValidationException("label", "REQUIRED", "속성 이름을 입력하세요")
        if (label.length > LABEL_MAX_LENGTH) throw ValidationException("label", "TOO_LONG", "속성 이름은 ${LABEL_MAX_LENGTH}자 이하여야 합니다")
        return label
    }

    /**
     * The value to store: null for none. A text is trimmed, a number is whole or has decimals, a date is
     * `YYYY-MM-DD`, a link is an http or https address, and a person is someone who is in the project
     * (a person who already is the value stays valid, whatever has become of their membership).
     */
    private suspend fun normalize(
        type: CustomPropertyType,
        raw: Any?,
        projectId: UUID?,
        current: UUID?,
    ): Any? {
        fun invalid() = ValidationException("value", "INVALID", "값이 올바르지 않습니다")
        if (raw == null) return if (type == CustomPropertyType.CHECKBOX) false else null
        return when (type) {
            CustomPropertyType.TEXT -> {
                val text = (raw as? String ?: throw invalid()).trim()
                if (text.length > TEXT_MAX_LENGTH) throw ValidationException("value", "TOO_LONG", "값은 ${TEXT_MAX_LENGTH}자 이하여야 합니다")
                text.ifEmpty { null }
            }
            CustomPropertyType.NUMBER -> {
                val number = runCatching { BigDecimal(if (raw is Number) raw.toString() else throw invalid()) }.getOrElse { throw invalid() }
                if (number.abs() >= NUMBER_LIMIT) throw ValidationException("value", "OUT_OF_RANGE", "숫자가 너무 큽니다")
                val plain = number.stripTrailingZeros()
                if (plain.scale() <= 0) plain.toLong() else plain.toDouble()
            }
            CustomPropertyType.DATE -> {
                val text = (raw as? String ?: throw invalid()).trim()
                if (text.isEmpty()) null else runCatching { LocalDate.parse(text).toString() }.getOrElse { throw invalid() }
            }
            CustomPropertyType.CHECKBOX -> raw as? Boolean ?: throw invalid()
            CustomPropertyType.LINK -> {
                val text = (raw as? String ?: throw invalid()).trim()
                if (text.length > TEXT_MAX_LENGTH) throw ValidationException("value", "TOO_LONG", "값은 ${TEXT_MAX_LENGTH}자 이하여야 합니다")
                if (text.isEmpty()) {
                    null
                } else if (isWebAddress(text)) {
                    text
                } else {
                    throw ValidationException("value", "NOT_A_LINK", "http:// 또는 https:// 로 시작하는 주소를 입력하세요")
                }
            }
            CustomPropertyType.PERSON -> {
                val text = (raw as? String ?: throw invalid()).trim()
                if (text.isEmpty()) {
                    null
                } else {
                    val userId = runCatching { UUID.fromString(text) }.getOrElse { throw invalid() }
                    if (userId != current && !isPersonAllowed(projectId, userId)) {
                        throw ValidationException("value", "NOT_A_MEMBER", "이 프로젝트의 구성원을 고르세요")
                    }
                    userId.toString()
                }
            }
        }
    }

    private fun isWebAddress(text: String): Boolean =
        runCatching { URI(text) }.getOrNull()?.let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() } ?: false

    private suspend fun isPersonAllowed(
        projectId: UUID?,
        userId: UUID,
    ): Boolean = if (projectId != null) memberStore.isActiveMember(projectId, userId) else issueQueries.isActiveUser(userId)

    private fun notFound() = NotFoundException("CUSTOM_PROPERTY_NOT_FOUND", "속성을 찾을 수 없습니다")

    private fun taken() = ConflictException("CUSTOM_PROPERTY_EXISTS", "같은 이름의 속성이 이미 있습니다")

    companion object {
        const val LABEL_MAX_LENGTH = 30
        const val TEXT_MAX_LENGTH = 500
        const val MAX_PER_ITEM = 20
        private val NUMBER_LIMIT = BigDecimal("1000000000000")
    }
}
