package com.flowbit.customproperty.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ProjectIssueField(
    val id: UUID,
    val projectId: UUID,
    val label: String,
    val type: CustomPropertyType,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val defaultValue: Any?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val person: CustomPropertyPerson?,
)

data class ProjectFieldInput(
    val label: String,
    val type: CustomPropertyType,
    val defaultValue: Any? = null,
)

data class IssueFieldInput(
    val fieldId: UUID,
    val value: Any? = null,
)

data class CheckedIssueField(
    val fieldId: UUID,
    val value: Any?,
)

/** Definitions are shared; only explicit ticket values need rows, including an explicit empty value. */
@Component
class ProjectIssueFieldStore(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val properties: CustomPropertyStore,
    private val clock: Clock,
) {
    suspend fun fields(projectId: UUID): List<ProjectIssueField> =
        views(
            db
                .sql(
                    "select id, project_id, label, type, default_value::text as value_text from project_issue_fields where project_id = :project order by created_at, id",
                ).bind("project", projectId)
                .map { row, _ -> rowOf(row) }
                .all()
                .collectList()
                .awaitSingle(),
        )

    suspend fun values(
        issueId: UUID,
        projectId: UUID,
    ): List<CustomPropertyView> {
        val rows =
            db
                .sql(
                    "select f.id, f.project_id, f.label, f.type, (case when v.issue_id is null then f.default_value else v.value end)::text as value_text from project_issue_fields f left join issue_field_values v on v.field_id = f.id and v.issue_id = :issue where f.project_id = :project order by f.created_at, f.id",
                ).bind("issue", issueId)
                .bind("project", projectId)
                .map { row, _ -> rowOf(row) }
                .all()
                .collectList()
                .awaitSingle()
        return views(rows).map { CustomPropertyView(it.id, it.label, it.type, it.defaultValue, it.person, projectFieldId = it.id) }
    }

    suspend fun personValue(
        issueId: UUID,
        projectId: UUID,
        fieldId: UUID?,
    ): UUID? = values(issueId, projectId).firstOrNull { it.id == fieldId && it.type == CustomPropertyType.PERSON }?.person?.id

    suspend fun hasLabel(
        projectId: UUID,
        label: String,
    ): Boolean = fields(projectId).any { it.label.equals(label, true) }

    suspend fun save(
        projectId: UUID,
        id: UUID?,
        input: ProjectFieldInput,
    ): ProjectIssueField {
        val fieldId = id ?: UuidV7.generate(clock.instant())
        val incompatible =
            db
                .sql(
                    "select count(*) from custom_properties c join issues i on i.id = c.issue_id where i.project_id = :project and lower(c.label) = lower(:label) and c.type <> :type",
                ).bind("project", projectId)
                .bind("label", input.label)
                .bind("type", input.type.name)
                .map { row, _ -> (row.get(0) as Number).toInt() }
                .one()
                .awaitSingle()
        if (incompatible > 0) throw ConflictException("CUSTOM_PROPERTY_TYPE_CONFLICT", "일부 티켓에 같은 이름의 다른 형식 속성이 있습니다. 다른 이름을 지정해 주세요")
        val sql =
            if (id ==
                null
            ) {
                "insert into project_issue_fields (id, project_id, label, type, default_value, created_at) values (:id, :project, :label, :type, cast(:value as jsonb), :now)"
            } else {
                "update project_issue_fields set label = :label, type = :type, default_value = cast(:value as jsonb), created_at = created_at where id = :id and project_id = :project"
            }
        var spec =
            db
                .sql(sql)
                .bind("id", fieldId)
                .bind("project", projectId)
                .bind("label", input.label)
                .bind("type", input.type.name)

        if (id == null) spec = spec.bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        spec = input.defaultValue?.let { spec.bind("value", json.writeValueAsString(it)) } ?: spec.bindNull("value", String::class.java)
        if (spec.fetch().rowsUpdated().awaitSingle() == 0L) throw missing()
        // Promote compatible private values, preserving values already explicitly assigned to the common field.
        db
            .sql(
                "insert into issue_field_values (issue_id, field_id, value) select c.issue_id, :field, c.value from custom_properties c join issues i on i.id = c.issue_id where i.project_id = :project and lower(c.label) = lower(:label) and c.type = :type on conflict do nothing",
            ).bind("field", fieldId)
            .bind("project", projectId)
            .bind("label", input.label)
            .bind("type", input.type.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                "delete from custom_properties c using issues i where c.issue_id = i.id and i.project_id = :project and lower(c.label) = lower(:label) and c.type = :type",
            ).bind("project", projectId)
            .bind("label", input.label)
            .bind("type", input.type.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return fields(projectId).first { it.id == fieldId }
    }

    suspend fun delete(
        projectId: UUID,
        id: UUID,
    ) {
        if (db
                .sql(
                    "delete from project_issue_fields where id = :id and project_id = :project",
                ).bind("id", id)
                .bind("project", projectId)
                .fetch()
                .rowsUpdated()
                .awaitSingle() ==
            0L
        ) {
            throw missing()
        }
    }

    suspend fun putValue(
        issueId: UUID,
        fieldId: UUID,
        value: Any?,
    ) {
        var spec =
            db
                .sql(
                    "insert into issue_field_values (issue_id, field_id, value) values (:issue, :field, cast(:value as jsonb)) on conflict (issue_id, field_id) do update set value = excluded.value",
                ).bind("issue", issueId)
                .bind("field", fieldId)
        spec = value?.let { spec.bind("value", json.writeValueAsString(it)) } ?: spec.bindNull("value", String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun copy(
        from: UUID,
        to: UUID,
    ) {
        db
            .sql(
                "insert into issue_field_values (issue_id, field_id, value) select :target, field_id, value from issue_field_values where issue_id = :source on conflict do nothing",
            ).bind("source", from)
            .bind("target", to)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun views(rows: List<ProjectIssueField>): List<ProjectIssueField> {
        val people = properties.people(rows.filter { it.type == CustomPropertyType.PERSON }.mapNotNull { (it.defaultValue as? String)?.let(UUID::fromString) })
        return rows.map { field ->
            field.copy(
                person =
                    (field.defaultValue as? String)?.let { value ->
                        runCatching { UUID.fromString(value) }.getOrNull()?.let(people::get)
                    },
            )
        }
    }

    private fun rowOf(row: Readable) =
        ProjectIssueField(
            checkNotNull(row.get("id", UUID::class.java)),
            checkNotNull(row.get("project_id", UUID::class.java)),
            checkNotNull(row.get("label", String::class.java)),
            CustomPropertyType.valueOf(checkNotNull(row.get("type", String::class.java))),
            row.get("value_text", String::class.java)?.let { json.readValue(it, Any::class.java) },
            null,
        )

    private fun missing() = NotFoundException("PROJECT_FIELD_NOT_FOUND", "프로젝트 티켓 속성을 찾을 수 없습니다")
}
