package com.flowbit.issue.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.issue.application.IssueRelationView
import com.flowbit.issue.application.IssueRelations
import com.flowbit.issue.application.RelationKind
import com.flowbit.issue.domain.IssueStatus
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RelatedIssueResponse(
    val number: Int,
    val key: String,
    val title: String,
    val status: IssueStatus,
    val statusName: String,
)

data class IssueRelationResponse(
    val id: UUID,
    val kind: RelationKind,
    val issue: RelatedIssueResponse,
) {
    companion object {
        fun from(view: IssueRelationView) =
            IssueRelationResponse(
                view.id,
                view.kind,
                RelatedIssueResponse(view.issue.number, view.issue.key, view.issue.title, view.issue.status, view.issue.statusName),
            )
    }
}

/** `kind` is from the point of view of the issue in the address: `BLOCKS` means it blocks the target. */
data class AddRelationRequest(
    val kind: RelationKind,
    val targetNumber: Int,
)

@RestController
@RequestMapping("/api/v1/issues/{number}/relations")
class IssueRelationController(
    private val relations: IssueRelations,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
    ): List<IssueRelationResponse> = relations.list(user, number).map(IssueRelationResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun add(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @RequestBody body: AddRelationRequest,
    ): List<IssueRelationResponse> = relations.add(user, number, body.kind, body.targetNumber).map(IssueRelationResponse::from)

    @DeleteMapping("/{relationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun remove(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable number: Int,
        @PathVariable relationId: UUID,
    ) = relations.remove(user, number, relationId)
}
