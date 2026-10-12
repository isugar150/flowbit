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

/** The kind of issue. On the client it decides the outline a new description starts with. */
enum class IssuePreset { BUG, ENHANCEMENT, FEATURE }

@Table("issues")
class Issue(
    @Id
    val id: UUID,
    val projectId: UUID,
    val number: Int,
    var title: String,
    var description: String,
    var preset: IssuePreset?,
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
    suspend fun findByProjectIdAndNumber(
        projectId: UUID,
        number: Int,
    ): Issue?
}
