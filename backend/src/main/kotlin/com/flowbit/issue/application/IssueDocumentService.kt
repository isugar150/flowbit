package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.richtext.RichText
import com.flowbit.content.ContentEvent
import com.flowbit.content.ContentEventType
import com.flowbit.content.ContentLog
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.issue.domain.Issue
import com.flowbit.project.application.ProjectService
import com.flowbit.project.domain.Project
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The documents of issues and projects (split out of [IssueService]): making them, nesting them, editing, deleting
 * (which archives) and restoring them. A document is its own page; who may see and change it is its issue's or
 * its project's say.
 */
@Service
class IssueDocumentService(
    private val lookup: IssueLookup,
    private val projectService: ProjectService,
    private val queries: IssueQueries,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val richText: RichText,
    private val contentLog: ContentLog,
    private val clock: Clock,
) {
    @Transactional
    suspend fun createDocument(
        user: AuthenticatedUser,
        number: Int,
        rawTitle: String?,
    ): DocumentSummary {
        val issue = lookup.editable(lookup.access(user, number)).issue
        val title = documentTitle(rawTitle.orEmpty())
        val now = clock.instant()
        val document = extras.addDocument(issue.id, null, null, title, user.id, now)
        activities.record(issue.id, user.id, listOf(IssueEvent(IssueActivityType.DOCUMENT_ADDED, mapOf("to" to title))), now)
        return document
    }

    /** A document of a project, made from its overview. Needs the right to edit in the project. */
    @Transactional
    suspend fun createProjectDocument(
        user: AuthenticatedUser,
        projectId: String,
        rawTitle: String?,
    ): DocumentSummary {
        val project = lookup.editableProject(user, projectId)
        val title = documentTitle(rawTitle.orEmpty())
        val now = clock.instant()
        val document = extras.addDocument(null, project.id, null, title, user.id, now)
        // A document made on the overview is a line of the project's activity (D-165).
        contentLog.record(
            ContentTarget(ContentTargetType.PROJECT, project.id),
            user.id,
            listOf(
                ContentEvent(
                    ContentEventType.DOCUMENT_ADDED,
                    mapOf(
                        "to" to title,
                    ),
                ),
            ),
            now,
        )
        return document
    }

    /**
     * A document inside another one. Only a project's documents nest, and only
     * [MAX_DOCUMENT_DEPTH] levels deep; an issue's documents stay one level.
     */
    @Transactional
    suspend fun createChildDocument(
        user: AuthenticatedUser,
        parentId: UUID,
        rawTitle: String?,
    ): DocumentSummary {
        val parent = extras.findDocument(parentId) ?: throw lookup.notFound()
        if (!ownerOf(user, parent).canEdit || parent.deletedAt != null) throw lookup.noEditRight()
        if (parent.issueId != null) throw ConflictException("DOCUMENT_TOO_DEEP", "티켓의 문서 안에는 문서를 만들 수 없습니다")
        if (extras.documentAncestors(parent.id).size + 1 >= MAX_DOCUMENT_DEPTH) {
            throw ConflictException("DOCUMENT_TOO_DEEP", "문서는 ${MAX_DOCUMENT_DEPTH}단계까지만 만들 수 있습니다")
        }
        val title = documentTitle(rawTitle.orEmpty())
        val now = clock.instant()
        val child = extras.addDocument(null, parent.projectId, parent.id, title, user.id, now)
        // One inside another is a line of the activity of the one it is in.
        contentLog.record(
            ContentTarget(ContentTargetType.DOCUMENT, parent.id),
            user.id,
            listOf(
                ContentEvent(
                    ContentEventType.DOCUMENT_ADDED,
                    mapOf(
                        "to" to title,
                    ),
                ),
            ),
            now,
        )
        return child
    }

    suspend fun projectDocuments(
        user: AuthenticatedUser,
        projectId: String,
        deleted: Boolean = false,
    ): List<DocumentSummary> {
        val id = projectService.access(user, projectId).project.id
        return if (deleted) extras.deletedProjectDocuments(id) else extras.projectDocuments(id)
    }

    /** The deleted documents of an issue; the ones in use come with the issue itself. */
    suspend fun deletedDocuments(
        user: AuthenticatedUser,
        number: Int,
    ): List<DocumentSummary> = extras.deletedDocuments(lookup.access(user, number).issue.id)

    suspend fun document(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentDetail {
        val document = extras.findDocument(documentId) ?: throw lookup.notFound()
        val owner = ownerOf(user, document)
        val ancestors = extras.documentAncestors(document.id)
        return DocumentDetail(
            document = document,
            content = richText.load(RichText.ContentTable.DOCUMENTS, document.id),
            issue = owner.issue?.let { checkNotNull(queries.find(it.number)) },
            project = owner.project?.let { IssueProject(it.id, it.name) },
            // A deleted document is read-only until it is restored.
            canEdit = owner.canEdit && document.deletedAt == null,
            ancestors = ancestors,
            children = extras.childDocuments(document.id),
            canAddChild =
                owner.canEdit && document.deletedAt == null && document.issueId == null && ancestors.size + 1 < MAX_DOCUMENT_DEPTH,
            canRestore = owner.canEdit && document.deletedAt != null,
        )
    }

    /** `title` and `content` change only when present in the patch; a null `content` empties the document. */
    @Transactional
    suspend fun updateDocument(
        user: AuthenticatedUser,
        documentId: UUID,
        patch: IssuePatch,
    ): DocumentDetail {
        val document = extras.findDocument(documentId) ?: throw lookup.notFound()
        if (!ownerOf(user, document).canEdit || document.deletedAt != null) throw lookup.noEditRight()
        val title = if (patch.has("title")) documentTitle(patch.string("title").orEmpty()) else null
        var contentChanged = false
        if (patch.has("content")) {
            // A document is a page whose blocks can be commented on: every top-level block has a number (D-165).
            val parsed = patch.raw("content")?.let { richText.parse(it, blockIds = true) }
            contentChanged = !richText.same(richText.load(RichText.ContentTable.DOCUMENTS, document.id), parsed)
            richText.store(RichText.ContentTable.DOCUMENTS, document.id, parsed)
        }
        val now = clock.instant()
        extras.touchDocument(document.id, title, now)
        val events = mutableListOf<ContentEvent>()
        if (title != null && title != document.title) {
            events += ContentEvent(ContentEventType.NAME_CHANGED, mapOf("from" to document.title, "to" to title))
        }
        if (contentChanged) events += ContentEvent(ContentEventType.CONTENT_EDITED)
        contentLog.record(ContentTarget(ContentTargetType.DOCUMENT, document.id), user.id, events, now)
        return document(user, documentId)
    }

    /**
     * Archives the document (the row stays and can be restored). Returns what it belongs to, so
     * that whoever shows it can be told.
     */
    @Transactional
    suspend fun removeDocument(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentOwner {
        val document = extras.findDocument(documentId)?.takeIf { it.deletedAt == null } ?: throw lookup.notFound()
        val owner = ownerOf(user, document)
        if (!owner.canEdit) throw lookup.noEditRight()
        extras.deleteDocument(document.id, user.id, clock.instant())
        owner.issue?.let { issue ->
            val event = IssueEvent(IssueActivityType.DOCUMENT_REMOVED, mapOf("from" to document.title))
            activities.record(issue.id, user.id, listOf(event), clock.instant())
        }
        recordInHolder(document, user.id, ContentEvent(ContentEventType.DOCUMENT_REMOVED, mapOf("from" to document.title)))
        return DocumentOwner(owner.issue?.number, owner.project?.id)
    }

    /** Brings a deleted document back to where it was. */
    @Transactional
    suspend fun restoreDocument(
        user: AuthenticatedUser,
        documentId: UUID,
    ): DocumentOwner {
        val document = extras.findDocument(documentId)?.takeIf { it.deletedAt != null } ?: throw lookup.notFound()
        val owner = ownerOf(user, document)
        if (!owner.canEdit) throw lookup.noEditRight()
        extras.restoreDocument(document.id)
        owner.issue?.let { issue ->
            val event = IssueEvent(IssueActivityType.DOCUMENT_RESTORED, mapOf("to" to document.title))
            activities.record(issue.id, user.id, listOf(event), clock.instant())
        }
        recordInHolder(document, user.id, ContentEvent(ContentEventType.DOCUMENT_RESTORED, mapOf("to" to document.title)))
        return DocumentOwner(owner.issue?.number, owner.project?.id)
    }

    /**
     * A document that was deleted or restored is a line of the activity of what holds it (D-165):
     * the document it sits inside, or else the project. An issue's own documents are in the
     * issue's activity already.
     */
    private suspend fun recordInHolder(
        document: DocumentSummary,
        actorId: UUID,
        event: ContentEvent,
    ) {
        val holder =
            document.parentId?.let { ContentTarget(ContentTargetType.DOCUMENT, it) }
                ?: document.projectId?.let { ContentTarget(ContentTargetType.PROJECT, it) }
                ?: return
        contentLog.record(holder, actorId, listOf(event), clock.instant())
    }

    /** The issue or project a document belongs to, as the caller may see it; "not found" when they may not. */
    private suspend fun ownerOf(
        user: AuthenticatedUser,
        document: DocumentSummary,
    ): OwnerAccess {
        if (document.issueId != null) {
            val access = lookup.accessById(user, document.issueId)
            return OwnerAccess(access.issue, null, access.canEdit)
        }
        val access = projectService.accessById(user, checkNotNull(document.projectId)) ?: throw lookup.notFound()
        return OwnerAccess(null, access.project, access.role.canEdit)
    }

    private fun documentTitle(raw: String): String {
        val title = raw.trim()
        if (title.length > Issue.TITLE_MAX_LENGTH) {
            throw ValidationException("title", "TOO_LONG", "문서 제목은 ${Issue.TITLE_MAX_LENGTH}자 이하여야 합니다")
        }
        return title
    }

    private data class OwnerAccess(
        val issue: Issue?,
        val project: Project?,
        val canEdit: Boolean,
    )
}
