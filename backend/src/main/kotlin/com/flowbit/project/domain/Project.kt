package com.flowbit.project.domain

import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

/** What a person may do inside one project. Declared from least to most so roles can be compared. */
enum class ProjectRole {
    VIEWER,
    EDITOR,
    MANAGER,
    ;

    val canEdit: Boolean
        get() = this >= EDITOR

    val canManage: Boolean
        get() = this == MANAGER
}

/** Home of issues and the unit of access control (docs/features/M3-projects-and-issues.md). */
@Table("projects")
class Project(
    @Id
    val id: UUID,
    /** Prefix of issue keys (FLOW-12). Never changes. */
    val key: String,
    var name: String,
    var description: String,
    var nextIssueNumber: Int,
    val createdBy: UUID,
    var archivedAt: Instant?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 80
        const val DESCRIPTION_MAX_LENGTH = 500
        val KEY_PATTERN = Regex("^[A-Z][A-Z0-9]{1,9}$")
    }
}

interface ProjectRepository : CoroutineCrudRepository<Project, UUID> {
    @Query("select * from projects where key = :key and archived_at is null")
    suspend fun findActiveByKey(key: String): Project?

    suspend fun existsByKey(key: String): Boolean

    @Modifying
    @Query("update projects set name = :name, description = :description, updated_at = :now where id = :id")
    suspend fun updateDetails(
        id: UUID,
        name: String,
        description: String,
        now: Instant,
    ): Int
}
