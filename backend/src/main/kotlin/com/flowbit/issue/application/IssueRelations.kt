package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.domain.Issue
import com.flowbit.issue.domain.IssueRepository
import com.flowbit.issue.domain.IssueStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** How another issue stands to the one being looked at. */
enum class RelationKind {
    /** This issue blocks the other. */
    BLOCKS,

    /** The other issue blocks this one. */
    BLOCKED_BY,

    /** This issue is a duplicate of the other (the original). */
    DUPLICATE_OF,

    /** The other issue is a duplicate of this one. */
    DUPLICATED_BY,
    RELATED,
}

data class RelatedIssue(
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val statusName: String,
)

data class IssueRelationView(
    val id: UUID,
    val kind: RelationKind,
    val issue: RelatedIssue,
)

/** A relation asked for as an issue is made: how the new issue stands to the issue [targetNumber] (D-237). */
data class NewRelation(
    val kind: RelationKind,
    val targetNumber: Int,
)

/** How many relations an issue can be made with. */
private const val MAX_ON_CREATE = 20

/**
 * Relations between issues (docs/features/issue-relations.md). Seen by anyone who can see both issues, made and removed by
 * someone who may edit the issue they start from. The other end only needs to be an issue in use that the person can see.
 */
@Service
class IssueRelations(
    private val databaseClient: DatabaseClient,
    private val repository: IssueRepository,
    private val lookup: IssueLookup,
    private val clock: Clock,
) {
    /** The relations of an issue, those whose other end the caller may not see left out. */
    suspend fun list(
        user: AuthenticatedUser,
        number: Int,
    ): List<IssueRelationView> {
        val issue = lookup.access(user, number).issue
        return databaseClient
            .sql(
                """
                select r.id, r.issue_id, r.type, o.id as other_id, o.number, o.title, o.status, st.name as status_name,
                       (select workspace_key from instance_settings where id = 1) as prefix
                from issue_relations r
                join issues o on o.id = case when r.issue_id = :id then r.related_id else r.issue_id end
                join issue_statuses st on st.id = o.status_id
                where (r.issue_id = :id or r.related_id = :id) and o.deleted_at is null
                order by r.created_at, r.id
                """.trimIndent(),
            ).bind("id", issue.id)
            .map { row, _ ->
                val outgoing = checkNotNull(row.get("issue_id", UUID::class.java)) == issue.id
                val kind =
                    when (checkNotNull(row.get("type", String::class.java))) {
                        "BLOCKS" -> if (outgoing) RelationKind.BLOCKS else RelationKind.BLOCKED_BY
                        "DUPLICATES" -> if (outgoing) RelationKind.DUPLICATE_OF else RelationKind.DUPLICATED_BY
                        else -> RelationKind.RELATED
                    }
                val otherNumber = checkNotNull(row.get("number", Int::class.javaObjectType))
                Triple(
                    checkNotNull(row.get("other_id", UUID::class.java)),
                    IssueRelationView(
                        checkNotNull(row.get("id", UUID::class.java)),
                        kind,
                        RelatedIssue(
                            otherNumber,
                            "${row.get("prefix", String::class.java)}-$otherNumber",
                            checkNotNull(row.get("title", String::class.java)),
                            IssueStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                            checkNotNull(row.get("status_name", String::class.java)),
                        ),
                    ),
                    otherNumber,
                )
            }.all()
            .collectList()
            .awaitSingle()
            .filter { (otherId, _, _) -> repository.findById(otherId)?.let { lookup.accessOf(user, it) } != null }
            .map { it.second }
    }

    suspend fun add(
        user: AuthenticatedUser,
        number: Int,
        kind: RelationKind,
        targetNumber: Int,
    ): List<IssueRelationView> {
        val from = lookup.editable(lookup.access(user, number)).issue
        link(user, from, kind, target(user, targetNumber))
        return list(user, number)
    }

    /**
     * Checks the relations an issue is to be made with, before anything is stored (a bad one must not use up an issue number): the
     * other ends must be issues in use that the caller can see. Asking for the same one twice counts once.
     */
    suspend fun check(
        user: AuthenticatedUser,
        items: List<NewRelation>,
    ): List<Pair<RelationKind, Issue>> {
        if (items.size > MAX_ON_CREATE) throw ValidationException("relations", "TOO_MANY", "관계는 한 번에 ${MAX_ON_CREATE}개까지 맺을 수 있습니다")
        val checked = items.distinct().map { it.kind to target(user, it.targetNumber) }
        // The new issue blocking and being blocked by the same one could never be started.
        val blocking = checked.filter { (kind, _) -> kind == RelationKind.BLOCKS || kind == RelationKind.BLOCKED_BY }
        if (blocking.groupBy({ it.second.id }, { it.first }).any { (_, kinds) -> kinds.size > 1 }) {
            throw ConflictException("RELATION_CONFLICT", "서로를 막는 관계는 만들 수 없습니다")
        }
        return checked
    }

    /** Makes the relations [check] passed, from an issue just made by the caller. */
    suspend fun attach(
        user: AuthenticatedUser,
        from: Issue,
        checked: List<Pair<RelationKind, Issue>>,
    ) {
        checked.forEach { (kind, target) -> link(user, from, kind, target) }
    }

    /** The issue on the other end: one in use that the caller can see. */
    private suspend fun target(
        user: AuthenticatedUser,
        targetNumber: Int,
    ): Issue =
        repository.findByNumber(targetNumber)?.takeIf { it.deletedAt == null && lookup.accessOf(user, it) != null }
            ?: throw ValidationException("targetNumber", "INVALID", "관계를 맺을 수 없는 티켓입니다")

    private suspend fun link(
        user: AuthenticatedUser,
        from: Issue,
        kind: RelationKind,
        target: Issue,
    ) {
        if (target.id == from.id) throw ValidationException("targetNumber", "SELF", "자기 자신과는 관계를 맺을 수 없습니다")

        // The row as the table keeps it: "first <type> second".
        val (first, second, type) =
            when (kind) {
                RelationKind.BLOCKS -> Triple(from.id, target.id, "BLOCKS")
                RelationKind.BLOCKED_BY -> Triple(target.id, from.id, "BLOCKS")
                RelationKind.DUPLICATE_OF -> Triple(from.id, target.id, "DUPLICATES")
                RelationKind.DUPLICATED_BY -> Triple(target.id, from.id, "DUPLICATES")
                RelationKind.RELATED ->
                    if (from.id.toString() <
                        target.id.toString()
                    ) {
                        Triple(from.id, target.id, "RELATED")
                    } else {
                        Triple(target.id, from.id, "RELATED")
                    }
            }
        // Two issues that block each other could never be started; the same two as a duplicate and a block, or related and blocking, are fine.
        if (type == "BLOCKS" && exists(second, first, "BLOCKS")) throw ConflictException("RELATION_CONFLICT", "서로를 막는 관계는 만들 수 없습니다")
        try {
            databaseClient
                .sql(
                    "insert into issue_relations (id, issue_id, related_id, type, created_by, created_at) " +
                        "values (:id, :first, :second, :type, :by, :now)",
                ).bind("id", UuidV7.generate(clock.instant()))
                .bind("first", first)
                .bind("second", second)
                .bind("type", type)
                .bind("by", user.id)
                .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } catch (_: DuplicateKeyException) {
            throw ConflictException("RELATION_EXISTS", "이미 맺은 관계입니다")
        }
    }

    suspend fun remove(
        user: AuthenticatedUser,
        number: Int,
        relationId: UUID,
    ) {
        val issue = lookup.editable(lookup.access(user, number)).issue
        val removed =
            databaseClient
                .sql("delete from issue_relations where id = :id and (issue_id = :issue or related_id = :issue)")
                .bind("id", relationId)
                .bind("issue", issue.id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (removed == 0L) throw NotFoundException("RELATION_NOT_FOUND", "관계를 찾을 수 없습니다")
    }

    private suspend fun exists(
        first: UUID,
        second: UUID,
        type: String,
    ): Boolean =
        databaseClient
            .sql("select count(*) from issue_relations where issue_id = :first and related_id = :second and type = :type")
            .bind("first", first)
            .bind("second", second)
            .bind("type", type)
            .map { row, _ -> (row.get(0) as Number).toInt() }
            .one()
            .awaitSingle() > 0
}
