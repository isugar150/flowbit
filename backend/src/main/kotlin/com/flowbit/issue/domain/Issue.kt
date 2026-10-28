package com.flowbit.issue.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Fixed workflow (docs/features/M3-projects-and-issues.md, "상태 목록"). */
enum class IssueStatus { BACKLOG, READY, IN_PROGRESS, DONE, CANCELED }

enum class IssuePriority { NONE, URGENT, HIGH, MEDIUM, LOW }

@Table("issues")
class Issue(
    @Id
    val id: UUID,
    /** The project the issue is linked to, if any. The link can change; the number does not. */
    var projectId: UUID?,
    /** The issue this one is a sub-issue of. */
    var parentId: UUID?,
    /** Unique across the workspace; shown after the workspace's issue key: FB-12. */
    val number: Int,
    var title: String,
    var description: String,
    /** The category the issue's status belongs to: what decides open or finished. */
    var status: IssueStatus,
    /** Which of the statuses of that category the issue is in (docs/features/statuses-and-estimates.md). */
    var statusId: UUID,
    var priority: IssuePriority,
    /** The first day of the span the issue covers; only together with [dueDate], and never after it. */
    var startDate: LocalDate?,
    var dueDate: LocalDate?,
    val createdBy: UUID,
    var version: Int,
    val createdAt: Instant,
    var updatedAt: Instant,
    /** Set while the issue is deleted: out of the lists, still readable with its history, restorable. */
    var deletedAt: Instant? = null,
    var deletedBy: UUID? = null,
    /** Story points; only shown where the project turned estimates on. */
    var estimate: Int? = null,
    /** Waiting in the triage queue of its project until a manager accepts, declines or marks it a duplicate (D-231). */
    var triage: Boolean = false,
    var triageRejected: Boolean = false,
    var triageRejectedAt: Instant? = null,
    /** The cycle of its project the issue is planned in (D-232). */
    var cycleId: UUID? = null,
) {
    companion object {
        const val TITLE_MAX_LENGTH = 200
        const val DESCRIPTION_MAX_LENGTH = 50_000
    }
}

interface IssueRepository : CoroutineCrudRepository<Issue, UUID> {
    @org.springframework.data.r2dbc.repository.Query("select * from issues where id = :id for update")
    suspend fun lockById(id: UUID): Issue?

    suspend fun findByNumber(number: Int): Issue?

    suspend fun findByParentId(parentId: UUID): List<Issue>
}
