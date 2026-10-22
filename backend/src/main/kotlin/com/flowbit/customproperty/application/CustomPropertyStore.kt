package com.flowbit.customproperty.application

import com.flowbit.media.application.ImageStorage
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A stored property; [valueJson] is the raw JSON of the value, null while empty. */
data class CustomPropertyRow(
    val id: UUID,
    val owner: CustomPropertyOwner,
    val label: String,
    val type: CustomPropertyType,
    val valueJson: String?,
)

/** Reads and writes `custom_properties`. The column an owner is stored in comes from [CustomPropertyOwnerKind], never from input. */
@Component
class CustomPropertyStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun list(owner: CustomPropertyOwner): List<CustomPropertyRow> =
        databaseClient
            .sql("$SELECT where ${owner.kind.column} = :ownerId order by created_at, id")
            .bind("ownerId", owner.id)
            .map { row -> rowOf(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(id: UUID): CustomPropertyRow? =
        databaseClient
            .sql("$SELECT where id = :id")
            .bind("id", id)
            .map { row -> rowOf(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun count(owner: CustomPropertyOwner): Int =
        databaseClient
            .sql("select count(*) as n from custom_properties where ${owner.kind.column} = :ownerId")
            .bind("ownerId", owner.id)
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)).toInt() }
            .one()
            .awaitSingle()

    /** Whether another property of the owner already has this label, ignoring case. */
    suspend fun labelTaken(
        owner: CustomPropertyOwner,
        label: String,
        exceptId: UUID? = null,
    ): Boolean =
        databaseClient
            .sql(
                """
                select exists(
                    select 1 from custom_properties
                    where ${owner.kind.column} = :ownerId and lower(label) = lower(:label) and (cast(:exceptId as uuid) is null or id <> :exceptId)
                ) as found
                """.trimIndent(),
            ).bind("ownerId", owner.id)
            .bind("label", label)
            .let { if (exceptId == null) it.bindNull("exceptId", UUID::class.java) else it.bind("exceptId", exceptId) }
            .map { row -> checkNotNull(row.get("found", Boolean::class.javaObjectType)) }
            .one()
            .awaitSingle()

    suspend fun insert(
        id: UUID,
        owner: CustomPropertyOwner,
        label: String,
        type: CustomPropertyType,
        valueJson: String?,
        now: Instant,
    ) {
        databaseClient
            .sql(
                """
                insert into custom_properties (id, ${owner.kind.column}, label, type, value, created_at, updated_at)
                values (:id, :ownerId, :label, :type, cast(:value as jsonb), :now, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("ownerId", owner.id)
            .bind("label", label)
            .bind("type", type.name)
            .let { if (valueJson == null) it.bindNull("value", String::class.java) else it.bind("value", valueJson) }
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun update(
        id: UUID,
        label: String,
        valueJson: String?,
        now: Instant,
    ) {
        databaseClient
            .sql("update custom_properties set label = :label, value = cast(:value as jsonb), updated_at = :now where id = :id")
            .bind("id", id)
            .bind("label", label)
            .let { if (valueJson == null) it.bindNull("value", String::class.java) else it.bind("value", valueJson) }
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(id: UUID) {
        databaseClient
            .sql("delete from custom_properties where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The people behind PERSON values, by id. */
    suspend fun people(ids: Collection<UUID>): Map<UUID, CustomPropertyPerson> {
        if (ids.isEmpty()) return emptyMap()
        return databaseClient
            .sql("select id, login_id, display_name, avatar_key from users where id = any(:ids)")
            .bind("ids", ids.distinct().toTypedArray())
            .map { row ->
                CustomPropertyPerson(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    loginId = checkNotNull(row.get("login_id", String::class.java)),
                    displayName = row.get("display_name", String::class.java),
                    avatarUrl = ImageStorage.urlOf(row.get("avatar_key", String::class.java)),
                )
            }.all()
            .collectList()
            .awaitSingle()
            .associateBy { it.id }
    }

    private fun rowOf(row: io.r2dbc.spi.Readable): CustomPropertyRow {
        val owner =
            row.get("project_id", UUID::class.java)?.let { CustomPropertyOwner(CustomPropertyOwnerKind.PROJECT, it) }
                ?: row.get("milestone_id", UUID::class.java)?.let { CustomPropertyOwner(CustomPropertyOwnerKind.MILESTONE, it) }
                ?: CustomPropertyOwner(CustomPropertyOwnerKind.ISSUE, checkNotNull(row.get("issue_id", UUID::class.java)))
        return CustomPropertyRow(
            id = checkNotNull(row.get("id", UUID::class.java)),
            owner = owner,
            label = checkNotNull(row.get("label", String::class.java)),
            type = CustomPropertyType.valueOf(checkNotNull(row.get("type", String::class.java))),
            valueJson = row.get("value_text", String::class.java),
        )
    }

    private companion object {
        const val SELECT = "select id, project_id, milestone_id, issue_id, label, type, value::text as value_text from custom_properties"
    }
}
