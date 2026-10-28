package com.flowbit.automation

import com.flowbit.issue.application.IssueView

/** Replace tokens once so user-controlled values can never expand into more tokens. */
internal fun renderAutomationMessage(
    template: String,
    before: IssueView,
    after: IssueView,
    actorName: String,
): String {
    val values =
        mapOf(
            "티켓번호" to after.key,
            "티켓제목" to after.title,
            "이전상태" to before.statusName,
            "현재상태" to after.statusName,
            "변경자" to actorName,
            "프로젝트명" to after.project?.name.orEmpty(),
        )
    return Regex("\\{([^{}]+)\\}").replace(template.trim()) { match -> values[match.groupValues[1]] ?: match.value }
}
