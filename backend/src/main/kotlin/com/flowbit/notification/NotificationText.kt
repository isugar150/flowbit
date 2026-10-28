package com.flowbit.notification

import com.flowbit.common.web.PublicUrl
import org.springframework.stereotype.Component

/** A notification as words, shared by every channel so mail, push and API calls say the same thing. */
data class NotificationMessage(
    /** `[FB-12] 로그인 화면 깨짐` */
    val title: String,
    /** One line per change: `상태: 백로그 → 진행 중`. */
    val lines: List<String>,
    /** `김지민 님이 티켓을 수정했습니다` */
    val headline: String,
    /** `/issue/FB-12` */
    val path: String,
    /** The full address when this installation knows its own, otherwise the path. */
    val url: String,
    /** The words of the link in a mail. */
    val linkLabel: String = "티켓 열기",
    /**
     * The lines are laid out by whoever wrote them, blank lines between the parts included (the daily summary, D-221): a
     * blank line also stands between the headline and the lines and before the link, and a mail shows them as they are
     * rather than as a bulleted list.
     */
    val spaced: Boolean = false,
) {
    /** What follows the headline: the lines, behind a blank one when they are [spaced]. */
    val bodyLines: List<String>
        get() = if (spaced && lines.isNotEmpty()) listOf("") + lines else lines

    /**
     * Everything in one paragraph, for places with a single text field (chat messages). The last
     * line is the address of the issue, so the reader can open it from the message; it is left out
     * when this installation does not know its own address, since a bare path opens nothing.
     */
    val text: String
        get() {
            val link = url.takeIf { it.startsWith("http") }
            val body = listOf("$title — $headline") + bodyLines
            return (if (spaced && link != null) body + "" + link else body + listOfNotNull(link)).joinToString("\n")
        }
}

@Component
class NotificationText(
    private val publicUrl: PublicUrl,
) {
    fun of(notification: IssueNotification): NotificationMessage {
        val types = notification.events.map { it.type }
        if (types.isNotEmpty() && types.all { it == "AUTOMATION_TRIGGERED" }) {
            val message =
                notification.events
                    .mapNotNull { it.detail["message"]?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString("\n")
                    .ifBlank { "티켓 상태가 변경되었습니다." }
                    .lines()
            val path = "/issue/${notification.issueKey}"
            return NotificationMessage(
                title = "[${notification.issueKey}] ${notification.issueTitle}",
                headline = message.first(),
                lines = message.drop(1),
                path = path,
                url = publicUrl.of(path),
                spaced = true,
            )
        }
        // Changes a Git server made say so (D-141); they carry `via` in their details.
        val fromGit = notification.events.isNotEmpty() && notification.events.all { "via" in it.detail }
        val duplicatedFrom = notification.events.firstNotNullOfOrNull { it.detail["duplicatedFrom"] as? String }
        val did =
            when {
                "TRIAGE_REQUESTED" in types -> "새 티켓의 분류를 요청했습니다"
                "CREATED" in types && duplicatedFrom != null -> "$duplicatedFrom 티켓을 복제했습니다"
                "CREATED" in types -> "티켓을 만들었습니다"
                "DELETED" in types -> "티켓을 삭제했습니다"
                "RESTORED" in types -> "티켓을 복원했습니다"
                "COMMENT_ADDED" in types -> if (isReply(notification.events)) "댓글에 답글을 남겼습니다" else "댓글을 남겼습니다"
                fromGit -> "Git에서 티켓을 수정했습니다"
                else -> "티켓을 수정했습니다"
            }
        val path = "/issue/${notification.issueKey}"
        return NotificationMessage(
            title = "[${notification.issueKey}] ${notification.issueTitle}",
            lines = notification.events.mapNotNull(::line),
            headline =
                if (fromGit && notification.actorId == null && gitNamesNobody(notification)) {
                    "${notification.actorName}에서 티켓을 수정했습니다"
                } else {
                    "${notification.actorName} 님이 $did"
                },
            path = path,
            url = publicUrl.of(path),
        )
    }

    /** The comment is an answer to another comment (D-167). */
    private fun isReply(events: List<IssueNotification.Event>): Boolean = events.any { it.detail["reply"] == true }

    /** A Git server's change that names nobody (D-153): the actor is the server itself, "GitHub". */
    private fun gitNamesNobody(notification: IssueNotification): Boolean = notification.events.all { (it.detail["via"] as? Map<*, *>)?.get("name") == null }

    /** A change to a project or a document, or a comment on one (D-165). */
    fun of(notification: ContentNotification): NotificationMessage {
        val types = notification.events.map { it.type }
        val did =
            when {
                "THREAD_COMMENT" in types || "COMMENT_ADDED" in types -> if (isReply(notification.events)) "댓글에 답글을 남겼습니다" else "댓글을 남겼습니다"
                else ->
                    when (notification.targetType) {
                        "PROJECT" -> "프로젝트를 수정했습니다"
                        else -> "문서를 수정했습니다"
                    }
            }
        return NotificationMessage(
            title = notification.title,
            lines = notification.events.mapNotNull(::contentLine),
            headline = "${notification.actorName} 님이 $did",
            path = notification.path,
            url = publicUrl.of(notification.path),
            linkLabel = "열기",
        )
    }

    private fun contentLine(event: IssueNotification.Event): String? {
        val detail = event.detail
        return when (event.type) {
            "NAME_CHANGED" -> change("이름", detail)
            "CONTENT_EDITED" -> "본문을 수정했습니다"
            "STATUS_CHANGED" -> change("상태", detail, PROJECT_STATUSES)
            "PRIORITY_CHANGED" -> change("우선순위", detail, PRIORITIES)
            "LEAD_CHANGED" -> change("리드", detail)
            "CUSTOM_PROPERTY_CHANGED" -> customProperty(detail)
            "DATES_CHANGED" -> change("기간", detail)
            "LABELS_CHANGED" -> addedRemoved("라벨", detail)
            "ICON_CHANGED" -> "아이콘을 바꿨습니다"
            "ARCHIVED" -> "프로젝트를 보관했습니다"
            "RESTORED" -> "프로젝트를 복원했습니다"
            "ACCESS_CHANGED" -> if (detail["open"] == true) "프로젝트를 모든 구성원에게 열었습니다" else "프로젝트를 지정한 구성원에게만 열도록 바꿨습니다"
            "MEMBER_ADDED" -> "구성원 추가: ${detail["name"]}"
            "MEMBER_REMOVED" -> "구성원 제거: ${detail["name"]}"
            "MEMBER_ROLE_CHANGED" -> "구성원 역할: ${detail["name"]}"
            "UPDATE_POSTED" -> "프로젝트 업데이트: " + (HEALTHS[detail["health"]?.toString()] ?: "")
            "DOCUMENT_ADDED" -> "문서 추가: ${detail["to"]}"
            "DOCUMENT_REMOVED" -> "문서 삭제: ${detail["from"]}"
            "DOCUMENT_RESTORED" -> "문서 복원: ${detail["to"]}"
            // A comment on a block: what the block says, then the comment, then who is tagged in it.
            "THREAD_COMMENT" ->
                listOfNotNull(
                    detail["quote"]?.toString()?.takeIf { it.isNotBlank() }?.let { "블록: $it" },
                    detail["text"]?.toString()?.takeIf { it.isNotBlank() },
                    names(detail["mentions"]).takeIf { it.isNotEmpty() }?.let { "언급: " + it.joinToString(", ") },
                ).joinToString("\n").ifEmpty { null }
            "COMMENT_ADDED" ->
                listOfNotNull(
                    detail["text"]?.toString()?.takeIf { it.isNotBlank() },
                    names(detail["mentions"]).takeIf { it.isNotEmpty() }?.let { "언급: " + it.joinToString(", ") },
                ).joinToString("\n").ifEmpty { null }
            else -> event.type
        }
    }

    /** A request to join a project; the link opens the project's settings, where it is answered (D-128). */
    fun of(request: ProjectAccessRequestNotification): NotificationMessage {
        val path = "/project/${request.projectId}/settings#access-requests"
        return NotificationMessage(
            title = "[${request.projectName}] 접근 요청",
            lines = listOf("프로젝트 설정의 구성원에서 승인하거나 거절할 수 있습니다."),
            headline = "${request.requesterName} 님이 프로젝트 접근을 요청했습니다",
            path = path,
            url = publicUrl.of(path),
            linkLabel = "설정 열기",
        )
    }

    /** Null for the changes the headline already says. */
    private fun line(event: IssueNotification.Event): String? {
        val detail = event.detail
        return when (event.type) {
            "AUTOMATION_TRIGGERED" -> detail["message"]?.toString()?.takeIf(String::isNotBlank)
            "TRIAGE_REQUESTED" -> "새로운 티켓이 트리아지에 도착했습니다. 요청을 확인하고 분류해 주세요."
            "CREATED", "DELETED", "RESTORED" -> null
            "TITLE_CHANGED" -> change("제목", detail)
            "DESCRIPTION_CHANGED" -> "본문을 수정했습니다"
            "STATUS_CHANGED" -> change("상태", detail, STATUSES)
            "PRIORITY_CHANGED" -> change("우선순위", detail, PRIORITIES)
            // Several people since D-125: who was added and who was taken off. Older entries hold from/to.
            "ASSIGNEE_CHANGED" -> if ("from" in detail || "to" in detail) change("담당자", detail) else addedRemoved("담당자", detail)
            "DUE_DATE_CHANGED" -> change("기한", detail)
            "PROJECT_CHANGED" -> change("프로젝트", detail)
            "PARENT_CHANGED" -> change("상위 티켓", detail)
            "CUSTOM_PROPERTY_CHANGED" -> customProperty(detail)
            "LABELS_CHANGED" -> addedRemoved("라벨", detail)
            "ATTACHMENT_ADDED" -> "파일 추가: ${detail["to"]}"
            "ATTACHMENT_REMOVED" -> "파일 삭제: ${detail["from"]}"
            "DOCUMENT_ADDED" -> "문서 추가: ${detail["to"]}"
            "DOCUMENT_REMOVED" -> "문서 삭제: ${detail["from"]}"
            // The comment's text, then who is tagged in it.
            "COMMENT_ADDED" ->
                listOfNotNull(
                    // A comment on a block of the body says which block (D-165).
                    detail["quote"]?.toString()?.takeIf { it.isNotBlank() }?.let { "블록: $it" },
                    detail["text"]?.toString()?.takeIf { it.isNotBlank() },
                    names(detail["mentions"]).takeIf { it.isNotEmpty() }?.let { "언급: " + it.joinToString(", ") },
                ).joinToString("\n").ifEmpty { null }
            "MENTIONED" -> "본문에서 언급: " + names(detail["mentions"]).joinToString(", ")
            // Git (D-141): only API notifications that ask for these get them.
            "GIT_BRANCH_LINKED" -> "브랜치 연결: ${detail["branch"]}"
            "GIT_PR_LINKED" -> "PR 연결: ${pullRequest(detail)} ${detail["title"] ?: ""}".trimEnd()
            "GIT_PR_MERGED" -> "PR 머지: ${pullRequest(detail)}"
            "GIT_PR_CLOSED" -> "PR 닫힘: ${pullRequest(detail)}"
            "GIT_COMMITS_PUSHED" -> "커밋 ${detail["count"]}개: ${detail["branch"]}"
            else -> event.type
        }
    }

    private fun change(
        label: String,
        detail: Map<String, Any?>,
        names: Map<String, String> = emptyMap(),
    ): String {
        fun shown(value: Any?) = value?.toString()?.let { names[it] ?: it } ?: "없음"
        return "$label: ${shown(detail["from"])} → ${shown(detail["to"])}"
    }

    /** A property people added to the item (D-186): added (with its value), renamed, changed or deleted. */
    private fun customProperty(detail: Map<String, Any?>): String {
        val label = detail["label"].toString()
        return when (detail["op"]) {
            "ADDED" -> "속성 추가: $label" + (detail["to"]?.let { " = $it" } ?: "")
            "REMOVED" -> "속성 삭제: $label"
            "RENAMED" -> "속성 이름: ${detail["from"]} → ${detail["to"]}"
            else -> change(label, detail)
        }
    }

    private fun addedRemoved(
        label: String,
        detail: Map<String, Any?>,
    ): String = "$label: " + (names(detail["added"]).map { "+$it" } + names(detail["removed"]).map { "-$it" }).joinToString(", ")

    private fun names(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it.toString() }

    /** `web#34`: the repository without its organization, and the number. */
    private fun pullRequest(detail: Map<String, Any?>): String = "${detail["repository"].toString().substringAfterLast('/')}#${detail["number"]}"

    private companion object {
        val STATUSES = mapOf("BACKLOG" to "백로그", "READY" to "준비됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "CANCELED" to "취소")
        val PRIORITIES = mapOf("NONE" to "우선순위 없음", "URGENT" to "긴급", "HIGH" to "높음", "MEDIUM" to "보통", "LOW" to "낮음")

        /** The statuses of a project. */
        val PROJECT_STATUSES = mapOf("BACKLOG" to "백로그", "PLANNED" to "계획됨", "IN_PROGRESS" to "진행 중", "COMPLETED" to "완료", "CANCELED" to "취소")
        val HEALTHS = mapOf("ON_TRACK" to "순조로움", "AT_RISK" to "위험", "OFF_TRACK" to "지연")
    }
}
