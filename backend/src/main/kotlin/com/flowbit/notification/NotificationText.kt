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
) {
    /**
     * Everything in one paragraph, for places with a single text field (chat messages). The last
     * line is the address of the issue, so the reader can open it from the message; it is left out
     * when this installation does not know its own address, since a bare path opens nothing.
     */
    val text: String
        get() = (listOf("$title — $headline") + lines + listOfNotNull(url.takeIf { it.startsWith("http") })).joinToString("\n")
}

@Component
class NotificationText(
    private val publicUrl: PublicUrl,
) {
    fun of(notification: IssueNotification): NotificationMessage {
        val types = notification.events.map { it.type }
        val did =
            when {
                "CREATED" in types -> "티켓을 만들었습니다"
                "DELETED" in types -> "티켓을 삭제했습니다"
                "RESTORED" in types -> "티켓을 복원했습니다"
                "COMMENT_ADDED" in types -> "댓글을 남겼습니다"
                else -> "티켓을 수정했습니다"
            }
        val path = "/issue/${notification.issueKey}"
        return NotificationMessage(
            title = "[${notification.issueKey}] ${notification.issueTitle}",
            lines = notification.events.mapNotNull(::line),
            headline = "${notification.actorName} 님이 $did",
            path = path,
            url = publicUrl.of(path),
        )
    }

    /** Null for the changes the headline already says. */
    private fun line(event: IssueNotification.Event): String? {
        val detail = event.detail
        return when (event.type) {
            "CREATED", "DELETED", "RESTORED" -> null
            "TITLE_CHANGED" -> change("제목", detail)
            "DESCRIPTION_CHANGED" -> "본문을 수정했습니다"
            "STATUS_CHANGED" -> change("상태", detail, STATUSES)
            "PRIORITY_CHANGED" -> change("우선순위", detail, PRIORITIES)
            // Several people since D-125: who was added and who was taken off. Older entries hold from/to.
            "ASSIGNEE_CHANGED" -> if ("from" in detail || "to" in detail) change("담당자", detail) else addedRemoved("담당자", detail)
            "DUE_DATE_CHANGED" -> change("기한", detail)
            "MILESTONE_CHANGED" -> change("목표", detail)
            "PROJECT_CHANGED" -> change("프로젝트", detail)
            "PARENT_CHANGED" -> change("상위 티켓", detail)
            "LABELS_CHANGED" -> addedRemoved("라벨", detail)
            "ATTACHMENT_ADDED" -> "파일 추가: ${detail["to"]}"
            "ATTACHMENT_REMOVED" -> "파일 삭제: ${detail["from"]}"
            "DOCUMENT_ADDED" -> "문서 추가: ${detail["to"]}"
            "DOCUMENT_REMOVED" -> "문서 삭제: ${detail["from"]}"
            // The comment's text, then who is tagged in it.
            "COMMENT_ADDED" ->
                listOfNotNull(
                    detail["text"]?.toString()?.takeIf { it.isNotBlank() },
                    names(detail["mentions"]).takeIf { it.isNotEmpty() }?.let { "언급: " + it.joinToString(", ") },
                ).joinToString("\n").ifEmpty { null }
            "MENTIONED" -> "본문에서 언급: " + names(detail["mentions"]).joinToString(", ")
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

    private fun addedRemoved(
        label: String,
        detail: Map<String, Any?>,
    ): String = "$label: " + (names(detail["added"]).map { "+$it" } + names(detail["removed"]).map { "-$it" }).joinToString(", ")

    private fun names(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it.toString() }

    private companion object {
        val STATUSES = mapOf("BACKLOG" to "백로그", "READY" to "준비됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "CANCELED" to "취소")
        val PRIORITIES = mapOf("NONE" to "우선순위 없음", "URGENT" to "긴급", "HIGH" to "높음", "MEDIUM" to "보통", "LOW" to "낮음")
    }
}
