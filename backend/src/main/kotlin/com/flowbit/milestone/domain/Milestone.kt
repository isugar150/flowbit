package com.flowbit.milestone.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A stage inside a project that issues are grouped into (docs/features/M3-projects-and-issues.md, "마일스톤"). */
@Table("milestones")
class Milestone(
    @Id
    val id: UUID,
    val projectId: UUID,
    var name: String,
    var targetDate: LocalDate?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 80
    }
}

interface MilestoneRepository : CoroutineCrudRepository<Milestone, UUID> {
    suspend fun findByIdAndProjectId(
        id: UUID,
        projectId: UUID,
    ): Milestone?
}
