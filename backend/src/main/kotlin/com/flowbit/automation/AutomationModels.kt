package com.flowbit.automation

import java.time.Instant
import java.util.UUID

enum class AutomationActionType { NOTIFY, ASSIGN, ADD_LABELS, REMOVE_LABELS }

enum class AutomationAudience { PEOPLE, ASSIGNEES, CREATOR, MANAGERS }

data class AutomationAction(
    val id: UUID,
    val type: AutomationActionType,
    val audience: AutomationAudience = AutomationAudience.PEOPLE,
    val userIds: List<UUID> = emptyList(),
    val labelIds: List<UUID> = emptyList(),
    val message: String = "",
)

data class AutomationInput(
    val name: String,
    val enabled: Boolean = true,
    val fromStatusId: UUID? = null,
    val toStatusId: UUID,
    val actions: List<AutomationAction>,
)

data class AutomationRule(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val enabled: Boolean,
    val fromStatusId: UUID?,
    val toStatusId: UUID,
    val actions: List<AutomationAction>,
    val createdAt: Instant,
)

data class AutomationResult(
    val type: AutomationActionType,
    val message: String,
)

data class AutomationRun(
    val id: UUID,
    val ruleName: String,
    val issueNumber: Int,
    val issueKey: String,
    val issueTitle: String,
    val results: List<AutomationResult>,
    val createdAt: Instant,
)

/** Captured at the point of the notification action, before any later action changes its audience. */
data class AutomationNotice(
    val ruleName: String,
    val recipientIds: List<UUID>,
    val message: String,
)
