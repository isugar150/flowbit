package com.flowbit.automation

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.IssueStatuses
import com.flowbit.issue.application.MAX_ASSIGNEES
import com.flowbit.project.application.ProjectService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AutomationService(
    private val projects: ProjectService,
    private val statuses: IssueStatuses,
    private val queries: IssueQueries,
    private val store: AutomationStore,
    private val audit: AuditService,
    private val db: DatabaseClient,
) {
    suspend fun list(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
    ): List<AutomationRule> = store.rules(manage(user, project, admin))

    suspend fun runs(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
    ): List<AutomationRun> = store.runs(manage(user, project, admin))

    @Transactional
    suspend fun save(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
        id: UUID?,
        input: AutomationInput,
        client: ClientInfo,
    ): AutomationRule {
        val projectId = manage(user, project, admin)
        // Serialize the per-project limit and concurrent edits of its rules.
        db
            .sql("select id from projects where id = :id for update")
            .bind("id", projectId)
            .map { row, _ -> checkNotNull(row.get("id", UUID::class.java)) }
            .one()
            .awaitSingle()
        if (id == null && store.rules(projectId).size >= 50) invalid("name", "프로젝트에는 자동화 규칙을 50개까지 만들 수 있습니다")
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 80) invalid("name", "규칙 이름은 1~80자여야 합니다")
        statuses.require(input.toStatusId)
        input.fromStatusId?.let { statuses.require(it) }
        if (input.fromStatusId == input.toStatusId) invalid("fromStatusId", "이전 상태와 다음 상태는 달라야 합니다")
        if (input.actions.size !in 1..10 ||
            input.actions
                .map { it.id }
                .distinct()
                .size != input.actions.size
        ) {
            invalid("actions", "서로 다른 실행 블록을 1~10개 넣어 주세요")
        }
        for (action in input.actions) {
            if (action.message.length > 1000) invalid("actions", "알림 메시지는 1,000자 이하여야 합니다")
            if (action.userIds.size > MAX_ASSIGNEES ||
                action.userIds.distinct().size != action.userIds.size
            ) {
                invalid("actions", "구성원은 중복 없이 ${MAX_ASSIGNEES}명까지 고를 수 있습니다")
            }
            if (action.labelIds.size > 10 || action.labelIds.distinct().size != action.labelIds.size) invalid("actions", "라벨은 중복 없이 10개까지 고를 수 있습니다")
            when (action.type) {
                AutomationActionType.ASSIGN -> {
                    val members = projects.members(user, project, admin).filter { it.role.canEdit }.map { it.userId }
                    if (action.userIds.any { it !in members }) invalid("actions", "담당자는 프로젝트의 편집·관리 역할 구성원이어야 합니다")
                }
                AutomationActionType.NOTIFY -> {
                    if (action.audience == AutomationAudience.PEOPLE && action.userIds.isEmpty()) invalid("actions", "알림을 받을 구성원을 골라 주세요")
                    if (action.userIds.any { !queries.isProjectMember(projectId, it) }) invalid("actions", "알림 대상은 프로젝트에 접근할 수 있는 활성 구성원이어야 합니다")
                }
                AutomationActionType.ADD_LABELS, AutomationActionType.REMOVE_LABELS -> {
                    if (action.labelIds.isEmpty() || queries.countLabels(action.labelIds) != action.labelIds.size) invalid("actions", "실행할 라벨을 골라 주세요")
                }
            }
        }
        val rule = store.save(projectId, id, input.copy(name = name))
        audit.record(
            AuditEventType.PROJECT_AUTOMATIONS_CHANGED,
            user.id,
            client,
            mapOf(
                "projectId" to projectId.toString(),
                "ruleId" to rule.id.toString(),
                "change" to "saved",
            ),
        )
        return rule
    }

    @Transactional
    suspend fun delete(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
        id: UUID,
        client: ClientInfo,
    ) {
        val projectId = manage(user, project, admin)
        store.delete(projectId, id)
        audit.record(
            AuditEventType.PROJECT_AUTOMATIONS_CHANGED,
            user.id,
            client,
            mapOf(
                "projectId" to projectId.toString(),
                "ruleId" to id.toString(),
                "change" to "deleted",
            ),
        )
    }

    private suspend fun manage(
        user: AuthenticatedUser,
        project: String,
        admin: Boolean,
    ): UUID {
        val access = projects.access(user, project, admin)
        if (!access.role.canManage) throw ForbiddenException("자동화는 프로젝트 관리 역할만 설정할 수 있습니다")
        return access.project.id
    }

    private fun invalid(
        field: String,
        message: String,
    ): Nothing = throw ValidationException(field, "INVALID", message)
}
