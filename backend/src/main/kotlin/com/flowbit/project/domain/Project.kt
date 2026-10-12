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

/** Where a project stands, in the order it moves through. */
enum class ProjectStatus {
    BACKLOG,
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELED,
}

/** How a project is going, as given by its latest update. */
enum class ProjectHealth {
    ON_TRACK,
    AT_RISK,
    OFF_TRACK,
}

/** Same scale as issue priority. */
enum class ProjectPriority {
    NONE,
    URGENT,
    HIGH,
    MEDIUM,
    LOW,
}

/** Home of issues and the unit of access control (docs/features/M3-projects-and-issues.md). */
@Table("projects")
class Project(
    @Id
    val id: UUID,
    var name: String,
    var description: String,
    val createdBy: UUID,
    var archivedAt: Instant?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 80
        const val DESCRIPTION_MAX_LENGTH = 500
        const val LABELS_MAX = 10
        const val LABEL_MAX_LENGTH = 30
    }
}

interface ProjectRepository : CoroutineCrudRepository<Project, UUID> {
    @Modifying
    @Query("update projects set icon_key = :iconKey, updated_at = :now where id = :id")
    suspend fun updateIconKey(
        id: UUID,
        iconKey: String?,
        now: Instant,
    ): Int

    @Query("select icon_key from projects where id = :id")
    suspend fun findIconKey(id: UUID): String?

    @Modifying
    @Query("update projects set name = :name, description = :description, updated_at = :now where id = :id")
    suspend fun updateDetails(
        id: UUID,
        name: String,
        description: String,
        now: Instant,
    ): Int
}
