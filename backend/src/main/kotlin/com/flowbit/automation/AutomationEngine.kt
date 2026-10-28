package com.flowbit.automation

import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.IssueView
import com.flowbit.project.application.ProjectMemberStore
import org.springframework.stereotype.Component
import java.time.Clock

/** Runs inside the originating status change transaction; it never changes status or invokes itself. */
@Component
class AutomationEngine(
    private val store: AutomationStore,
    private val queries: IssueQueries,
    private val members: ProjectMemberStore,
    private val clock: Clock,
) {
    suspend fun apply(
        before: IssueView,
        after: IssueView,
    ): List<AutomationNotice> {
        if (before.statusId == after.statusId || after.deletedAt != null) return emptyList()
        val projectId = after.project?.id ?: return emptyList()
        val rules =
            store.rules(projectId).filter {
                it.enabled &&
                    it.toStatusId == after.statusId &&
                    (it.fromStatusId == null || it.fromStatusId == before.statusId)
            }
        if (rules.isEmpty()) return emptyList()
        val notices = mutableListOf<AutomationNotice>()
        for (rule in rules) {
            val runId = store.claim(rule, after.id, after.version) ?: continue
            val results = mutableListOf<AutomationResult>()
            for (action in rule.actions) {
                val current = checkNotNull(queries.find(after.number))
                val eligible = members.members(projectId)
                val result =
                    when (action.type) {
                        AutomationActionType.NOTIFY -> {
                            val ids =
                                when (action.audience) {
                                    AutomationAudience.PEOPLE -> action.userIds
                                    AutomationAudience.ASSIGNEES -> current.assignees.map { it.id }
                                    AutomationAudience.CREATOR -> listOf(current.createdBy.id)
                                    AutomationAudience.MANAGERS -> eligible.filter { it.role.canManage }.map { it.userId }
                                }.distinct()
                            val allowed = ids.filter { id -> eligible.any { it.userId == id } }
                            if (allowed.isNotEmpty()) notices += AutomationNotice(rule.name, allowed, action.message.trim())
                            "알림 발송 요청 ${allowed.size}명" + if (allowed.size < ids.size) " (접근할 수 없는 구성원 제외)" else ""
                        }
                        AutomationActionType.ASSIGN -> {
                            val allowed = action.userIds.filter { id -> eligible.any { it.userId == id && it.role.canEdit } }
                            if (action.userIds.isEmpty() || allowed.isNotEmpty()) {
                                queries.replaceAssignees(after.id, allowed, clock.instant())
                                "담당자 ${allowed.size}명으로 변경" + if (allowed.size < action.userIds.size) " (편집할 수 없는 구성원 제외)" else ""
                            } else {
                                "담당자 변경 건너뜀: 지정한 구성원이 프로젝트를 편집할 수 없습니다"
                            }
                        }
                        AutomationActionType.ADD_LABELS, AutomationActionType.REMOVE_LABELS -> {
                            val existing = action.labelIds.filter { queries.countLabels(listOf(it)) == 1 }
                            val currentIds = current.labels.map { it.id }
                            val next =
                                if (action.type ==
                                    AutomationActionType.ADD_LABELS
                                ) {
                                    (currentIds + existing).distinct().take(10)
                                } else {
                                    currentIds - existing.toSet()
                                }
                            queries.replaceLabels(after.id, next)
                            (if (action.type == AutomationActionType.ADD_LABELS) "라벨 추가" else "라벨 제거") + " ${existing.size}개" +
                                if (existing.size <
                                    action.labelIds.size
                                ) {
                                    " (삭제된 라벨 제외)"
                                } else if (action.type == AutomationActionType.ADD_LABELS &&
                                    (currentIds + existing).distinct().size > 10
                                ) {
                                    " (라벨 상한 10개 적용)"
                                } else {
                                    ""
                                }
                        }
                    }
                results += AutomationResult(action.type, result)
            }
            store.finish(runId, results)
        }
        return notices
    }
}
