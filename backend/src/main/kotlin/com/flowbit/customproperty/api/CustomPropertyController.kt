package com.flowbit.customproperty.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.NotFoundException
import com.flowbit.customproperty.application.CustomPropertyChange
import com.flowbit.customproperty.application.CustomPropertyList
import com.flowbit.customproperty.application.CustomPropertyOwnerKind
import com.flowbit.customproperty.application.CustomPropertyService
import com.flowbit.customproperty.application.CustomPropertyType
import com.flowbit.customproperty.application.CustomPropertyView
import com.flowbit.customproperty.application.ProjectIssueFieldService
import com.flowbit.issue.api.IssueAnnouncer
import com.flowbit.realtime.RealtimeHub
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateCustomPropertyRequest(
    val label: String,
    val type: CustomPropertyType,
    val value: Any? = null,
)

/**
 * The properties people add to a project or an issue (D-186). Access is decided inside
 * [CustomPropertyService]; a change is announced after it is committed, so that whoever shows the
 * item reads again and whoever follows it is told.
 */
@RestController
@RequestMapping("/api/v1/custom-properties")
class CustomPropertyController(
    private val service: CustomPropertyService,
    private val projectFields: ProjectIssueFieldService,
    private val realtime: RealtimeHub,
    private val issueAnnouncer: IssueAnnouncer,
) {
    @GetMapping("/{type}/{id}")
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
    ): CustomPropertyList = service.list(user, kind(type), id)

    @PostMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable type: String,
        @PathVariable id: UUID,
        @RequestBody body: CreateCustomPropertyRequest,
    ): CustomPropertyView? = announce(user, service.create(user, kind(type), id, body.label, body.type, body.value))

    @PutMapping("/issue/{id}/project-fields/{fieldId}")
    suspend fun projectFieldValue(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @PathVariable fieldId: UUID,
        @RequestBody body: Map<String, Any?>,
    ): CustomPropertyView? = announce(user, projectFields.updateValue(user, id, fieldId, body["value"]))

    @PatchMapping("/{propertyId}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable propertyId: UUID,
        @RequestBody body: Map<String, Any?>,
    ): CustomPropertyView? = announce(user, service.update(user, propertyId, body))

    @DeleteMapping("/{propertyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable propertyId: UUID,
    ) {
        announce(user, service.delete(user, propertyId))
    }

    private suspend fun announce(
        user: AuthenticatedUser,
        change: CustomPropertyChange,
    ): CustomPropertyView? {
        change.issueChange?.let { issueAnnouncer.changed(user, it) }
        change.topics.forEach { realtime.publish(it, user.id) }
        return change.item
    }

    private fun kind(type: String) = CustomPropertyOwnerKind.ofPath(type) ?: throw NotFoundException("CUSTOM_PROPERTY_NOT_FOUND", "찾을 수 없습니다")
}
