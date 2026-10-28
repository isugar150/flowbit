package com.flowbit.issue.application

import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.domain.IssueStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A status an issue can be in: it belongs to one of the five fixed [category] categories, which decides what it means. */
data class IssueStatusView(
    val id: UUID,
    val category: IssueStatus,
    val name: String,
    val position: Int,
)

/**
 * The statuses (docs/features/statuses-and-estimates.md): the five every installation starts with, which can be renamed, and
 * the ones an administrator adds inside a category ("리뷰 중" under "진행 중"). A category always keeps at least one.
 */
@Service
class IssueStatuses(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {
    /** In the order the board shows them: by category, then by position. */
    suspend fun all(): List<IssueStatusView> =
        databaseClient
            .sql(
                """
                select id, category, name, position from issue_statuses
                order by case category when 'BACKLOG' then 0 when 'READY' then 1 when 'IN_PROGRESS' then 2 when 'DONE' then 3 else 4 end,
                         position, name
                """.trimIndent(),
            ).map { row, _ -> view(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(id: UUID): IssueStatusView? = all().firstOrNull { it.id == id }

    suspend fun require(id: UUID): IssueStatusView = find(id) ?: throw ValidationException("statusId", "INVALID", "알 수 없는 상태입니다")

    /** The status an issue gets when only a category is known: the first of that category. */
    suspend fun defaultOf(category: IssueStatus): IssueStatusView = all().first { it.category == category }

    suspend fun create(
        category: IssueStatus,
        rawName: String,
    ): IssueStatusView {
        val name = checkedName(rawName)
        if (nameTaken(name, null)) throw nameTaken()
        val next = all().filter { it.category == category }.maxOfOrNull { it.position }?.plus(10) ?: 10
        val id = UuidV7.generate(clock.instant())
        databaseClient
            .sql("insert into issue_statuses (id, category, name, position, created_at) values (:id, :category, :name, :position, :now)")
            .bind("id", id)
            .bind("category", category.name)
            .bind("name", name)
            .bind("position", next)
            .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return IssueStatusView(id, category, name, next)
    }

    /** Renames, and/or moves one place up or down inside its category. */
    suspend fun update(
        id: UUID,
        rawName: String?,
        direction: String?,
    ): IssueStatusView {
        val current = find(id) ?: throw notFound()
        if (rawName != null) {
            val name = checkedName(rawName)
            if (nameTaken(name, id)) throw nameTaken()
            databaseClient
                .sql("update issue_statuses set name = :name where id = :id")
                .bind("name", name)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        if (direction != null) {
            val siblings = all().filter { it.category == current.category }
            val index = siblings.indexOfFirst { it.id == id }
            val other =
                when (direction.uppercase()) {
                    "UP" -> siblings.getOrNull(index - 1)
                    "DOWN" -> siblings.getOrNull(index + 1)
                    else -> throw ValidationException("direction", "INVALID", "값이 올바르지 않습니다")
                }
            if (other != null) swap(current, other, up = direction.uppercase() == "UP")
        }
        return find(id) ?: throw notFound()
    }

    /** Removes a status; the issues in it move to the first status left in its category. The last of a category cannot go. */
    suspend fun delete(id: UUID) {
        val current = find(id) ?: throw notFound()
        val heir =
            all().firstOrNull { it.category == current.category && it.id != id }
                ?: throw ConflictException("LAST_IN_CATEGORY", "각 범주에는 상태가 하나 이상 있어야 합니다")
        transactionalOperator.executeAndAwait {
            databaseClient
                .sql("update issues set status_id = :heir where status_id = :id")
                .bind("heir", heir.id)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            databaseClient
                .sql("delete from issue_statuses where id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingleOrNull()
        }
    }

    private suspend fun swap(
        first: IssueStatusView,
        second: IssueStatusView,
        up: Boolean,
    ) {
        // Positions can tie (both 10); give them distinct places before swapping.
        val low = minOf(first.position, second.position)
        val high = if (first.position == second.position) low + 1 else maxOf(first.position, second.position)
        transactionalOperator.executeAndAwait {
            // `first` is the one being moved: up, it takes the earlier place.
            for ((target, position) in listOf(first to (if (up) low else high), second to (if (up) high else low))) {
                databaseClient
                    .sql("update issue_statuses set position = :position where id = :id")
                    .bind("position", position)
                    .bind("id", target.id)
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
        }
    }

    private suspend fun nameTaken(
        name: String,
        except: UUID?,
    ): Boolean = all().any { it.id != except && it.name.equals(name, ignoreCase = true) }

    private fun nameTaken() = ConflictException("STATUS_NAME_TAKEN", "이미 있는 상태 이름입니다")

    private fun notFound() = NotFoundException("STATUS_NOT_FOUND", "상태를 찾을 수 없습니다")

    private fun checkedName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name.length > NAME_MAX_LENGTH) {
            throw ValidationException("name", "INVALID", "상태 이름은 1자 이상 ${NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        return name
    }

    private fun view(row: io.r2dbc.spi.Readable) =
        IssueStatusView(
            id = checkNotNull(row.get("id", UUID::class.java)),
            category = IssueStatus.valueOf(checkNotNull(row.get("category", String::class.java))),
            name = checkNotNull(row.get("name", String::class.java)),
            position = checkNotNull(row.get("position", Int::class.javaObjectType)),
        )

    companion object {
        const val NAME_MAX_LENGTH = 30
    }
}
