package com.flowbit.share

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.issue.application.IssueExtrasStore
import com.flowbit.issue.application.IssueLookup
import com.flowbit.issue.application.IssueParent
import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.IssueView
import com.flowbit.issue.application.Viewer
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** An issue named by where it stands, for the path at the top of a shared page and for its list of sub-issues. */
data class SharedIssueRef(
    val number: Int,
    val key: String,
    val title: String,
)

data class SharedLabel(
    val name: String,
    val color: String,
)

/** A sub-issue as the list of a shared issue shows it. */
data class SharedSubIssue(
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val priority: IssuePriority,
    val dueDate: LocalDate?,
    val subIssueCount: Int,
    val subIssueDoneCount: Int,
)

data class SharedDocumentRef(
    val id: UUID,
    val title: String,
    val updatedAt: Instant,
)

/**
 * A shared issue, or a sub-issue of it, as its shared page shows it. No comments, history, reactions, files listed on the
 * issue, Git links, followers or properties of its own, and of people only the display name (docs/features/sharing.md, D-200).
 */
data class SharedIssue(
    val type: String = "issue",
    /** The number of the issue the link shares: the top of every path on its pages. */
    val rootNumber: Int,
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val priority: IssuePriority,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val project: String?,
    val labels: List<SharedLabel>,
    val assignees: List<String>,
    val createdBy: String?,
    /** The body as editor JSON, with the addresses of its pictures and files turned into ones that work inside the link. */
    val content: Map<*, *>?,
    /** The issues from the shared one down to this one's parent; empty on the shared one itself. */
    val trail: List<SharedIssueRef>,
    val subIssues: List<SharedSubIssue>,
    val documents: List<SharedDocumentRef>,
)

/** A document of a shared issue or of one of its sub-issues, as its shared page shows it. */
data class SharedDocument(
    val type: String = "document",
    val rootNumber: Int,
    val id: UUID,
    val title: String,
    val updatedAt: Instant,
    val content: Map<*, *>?,
    /** The issue the document belongs to. */
    val issue: SharedIssueRef,
    /** The issues from the shared one down to the one the document belongs to, that one included. */
    val trail: List<SharedIssueRef>,
    /** The documents of that issue, for the titles of the document blocks in the text. */
    val documents: List<SharedDocumentRef>,
)

/**
 * An issue and what is under it (docs/features/sharing.md "티켓 공유", D-200): the issue's page, its sub-issues however deep,
 * and the documents of all of them, read-only. Each is made when it is opened, from what the person who shared it may see
 * now. Nothing above the shared issue, and nothing outside it, can be opened with the link.
 */
@Component
class IssueShare(
    private val queries: IssueQueries,
    private val lookup: IssueLookup,
    private val extras: IssueExtrasStore,
    private val richText: RichText,
    private val jsonMapper: JsonMapper,
) : ShareContentProvider {
    override val kind = ShareKind.ISSUE

    override fun normalize(params: Map<String, String>): Map<String, String> {
        val number = params["number"]?.toIntOrNull()?.takeIf { it > 0 } ?: throw ValidationException("params.number", "INVALID", "티켓 번호가 올바르지 않습니다")
        return mapOf("number" to number.toString())
    }

    /** Only someone who may change the issue shares it: showing it to everyone is not for a reader (an issue they cannot see is not found). */
    override suspend fun check(
        owner: AuthenticatedUser,
        params: Map<String, String>,
    ) {
        if (!lookup.access(owner, rootOf(params)).canEdit) throw ForbiddenException("티켓을 고칠 수 있는 사람만 공유할 수 있습니다")
    }

    /** "FB-12 제목", or null when the person cannot see the issue any more. */
    override suspend fun describe(
        ownerId: UUID,
        params: Map<String, String>,
    ): String? = queries.findVisible(viewer(ownerId), rootOf(params))?.let { "${it.key} ${it.title}" }

    override suspend fun content(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
    ): SharedIssue {
        val root = rootOf(params)
        val issue = queries.findVisible(viewer(ownerId), root) ?: throw NotFoundException("SHARE_NOT_FOUND", "이 링크는 열 수 없습니다")
        return sharedIssue(ownerId, issue, root, token)
    }

    override suspend fun item(
        ownerId: UUID,
        params: Map<String, String>,
        token: String,
        type: String,
        id: String,
    ): Any? {
        val root = rootOf(params)
        val viewer = viewer(ownerId)
        // The shared issue itself must still be there: a link to a deleted issue opens nothing, in whole or in part.
        queries.findVisible(viewer, root) ?: return null
        return when (type) {
            "issue" ->
                id
                    .toIntOrNull()
                    ?.let { queries.findVisible(viewer, it) }
                    ?.takeIf { underRoot(it, root) }
                    ?.let { sharedIssue(ownerId, it, root, token) }
            "document" -> {
                val document = runCatching { UUID.fromString(id) }.getOrNull()?.let { extras.findDocument(it) }?.takeIf { it.deletedAt == null }
                val issue = document?.issueId?.let { queries.findVisibleById(viewer, it) }?.takeIf { underRoot(it, root) }
                if (document == null || issue == null) {
                    null
                } else {
                    SharedDocument(
                        rootNumber = root,
                        id = document.id,
                        title = document.title,
                        updatedAt = document.updatedAt,
                        content = assetsInside(richText.load(RichText.ContentTable.DOCUMENTS, document.id), token),
                        issue = ref(issue),
                        trail = trailOf(issue, root) + ref(issue),
                        documents = extras.documents(issue.id).map { SharedDocumentRef(it.id, it.title, it.updatedAt) },
                    )
                }
            }
            else -> null
        }
    }

    override suspend fun mayServe(
        ownerId: UUID,
        params: Map<String, String>,
        file: SharedFile,
    ): Boolean {
        val root = rootOf(params)
        if (queries.findVisible(viewer(ownerId), root) == null) return false
        val pattern =
            when (file.kind) {
                SharedFileKind.IMAGE -> if (IMAGE_KEY.matches(file.key)) "$IMAGE_PREFIX${file.key}" else return false
                SharedFileKind.EDITOR_FILE -> if (FILE_KEY.matches(file.key)) "$FILE_PREFIX${file.key}" else return false
            }
        // The address as it stands in the text, closed by its quote: only a file the shared texts really hold.
        return queries.treeTextHolds(root, "\"$pattern\"")
    }

    private suspend fun sharedIssue(
        ownerId: UUID,
        issue: IssueView,
        root: Int,
        token: String,
    ): SharedIssue =
        SharedIssue(
            rootNumber = root,
            number = issue.number,
            key = issue.key,
            title = issue.title,
            status = issue.status,
            priority = issue.priority,
            startDate = issue.startDate,
            dueDate = issue.dueDate,
            createdAt = issue.createdAt,
            updatedAt = issue.updatedAt,
            project = issue.project?.name,
            labels = issue.labels.map { SharedLabel(it.name, it.color) },
            // Display names only: a login id may be an e-mail address.
            assignees = issue.assignees.mapNotNull { it.displayName?.takeIf(String::isNotBlank) },
            createdBy = issue.createdBy.displayName?.takeIf(String::isNotBlank),
            content = assetsInside(richText.load(RichText.ContentTable.ISSUES, issue.id), token),
            trail = trailOf(issue, root),
            subIssues =
                queries.subIssues(viewer(ownerId), issue.id).map {
                    SharedSubIssue(it.number, it.key, it.title, it.status, it.priority, it.dueDate, it.subIssueCount, it.subIssueDoneCount)
                },
            documents = extras.documents(issue.id).map { SharedDocumentRef(it.id, it.title, it.updatedAt) },
        )

    private fun rootOf(params: Map<String, String>): Int = checkNotNull(params["number"]?.toIntOrNull())

    private fun viewer(ownerId: UUID) = Viewer(ownerId, seesAllProjects = false)

    private fun ref(issue: IssueView) = SharedIssueRef(issue.number, issue.key, issue.title)

    private fun underRoot(
        issue: IssueView,
        root: Int,
    ) = issue.number == root || issue.ancestors.any { it.number == root }

    /** The issues from the shared one down to the issue's parent (the shared one first); nothing above it is told. */
    private fun trailOf(
        issue: IssueView,
        root: Int,
    ): List<SharedIssueRef> = if (issue.number == root) emptyList() else issue.ancestors.dropWhile { it.number != root }.map(::refOf)

    private fun refOf(parent: IssueParent) = SharedIssueRef(parent.number, parent.key, parent.title)

    /**
     * The text of an issue or document with the addresses of its pictures and files changed to ones that work at this link
     * (`/api/v1/files/<key>` to `/api/v1/public/shares/<token>/files/<key>`), because the first need a sign-in.
     */
    private fun assetsInside(
        json: String?,
        token: String,
    ): Map<*, *>? {
        if (json == null) return null
        val root = jsonMapper.readValue(json, MutableMap::class.java)

        fun walk(node: Any?) {
            val map = node as? MutableMap<*, *> ?: return

            @Suppress("UNCHECKED_CAST")
            val attrs = map["attrs"] as? MutableMap<Any?, Any?>
            val src = attrs?.get("src") as? String
            if (attrs != null && src != null) {
                when (map["type"]) {
                    "image" -> if (src.startsWith(IMAGE_PREFIX)) attrs["src"] = "/api/v1/public/shares/$token/files/${src.removePrefix(IMAGE_PREFIX)}"
                    "fileBlock" -> if (src.startsWith(FILE_PREFIX)) attrs["src"] = "/api/v1/public/shares/$token/editor-files/${src.removePrefix(FILE_PREFIX)}"
                }
            }
            (map["content"] as? List<*>)?.forEach(::walk)
        }
        walk(root)
        return root
    }

    private companion object {
        const val IMAGE_PREFIX = "/api/v1/files/"
        const val FILE_PREFIX = "/api/v1/editor-files/"
        val IMAGE_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp$")
        val FILE_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
