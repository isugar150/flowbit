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
    var status: IssueStatus,
    var priority: IssuePriority,
    var assigneeId: UUID?,
    var dueDate: LocalDate?,
    var milestoneId: UUID?,
    val createdBy: UUID,
    var version: Int,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    companion object {
        const val TITLE_MAX_LENGTH = 200
        const val DESCRIPTION_MAX_LENGTH = 50_000
    }
}

interface IssueRepository : CoroutineCrudRepository<Issue, UUID> {
    suspend fun findByNumber(number: Int): Issue?
}
