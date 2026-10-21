package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.application.DocumentOwner
import com.flowbit.issue.application.IssueDocumentService
import com.flowbit.issue.application.IssuePatch
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The documents of a project, made from its overview. They are read and changed under /api/v1/documents. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
class ProjectDocumentController(
    private val documentService: IssueDocumentService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "false") deleted: Boolean,
    ): List<DocumentSummaryResponse> = documentService.projectDocuments(user, projectId, deleted).map(DocumentSummaryResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = documentService.createProjectDocument(user, projectId, body.title)
        announcer.documentChanged(user, DocumentOwner(null, document.projectId))
        return DocumentSummaryResponse.from(document)
    }
}

/** Documents, whether they belong to an issue or to a project. */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val documentService: IssueDocumentService,
    private val announcer: IssueAnnouncer,
) {
    @GetMapping("/{id}")
    suspend fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): DocumentResponse = DocumentResponse.from(documentService.document(user, id))

    /** `title` and `content` change only when present; a null `content` empties the document. */
    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ): DocumentResponse {
        val document = documentService.updateDocument(user, id, IssuePatch(body))
        announcer.documentChanged(user, DocumentOwner(document.issue?.number, document.project?.id))
        return DocumentResponse.from(document)
    }

    /** Makes a document inside this one. Project documents only, five levels deep at most (409 `DOCUMENT_TOO_DEEP`). */
    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createChild(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: CreateDocumentRequest,
    ): DocumentSummaryResponse {
        val document = documentService.createChildDocument(user, id, body.title)
        announcer.documentChanged(user, DocumentOwner(null, document.projectId))
        return DocumentSummaryResponse.from(document)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.documentChanged(user, documentService.removeDocument(user, id))
    }

    /** Brings a deleted document back. 404 when it is not deleted. */
    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun restore(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) {
        announcer.documentChanged(user, documentService.restoreDocument(user, id))
    }
}
