package com.flowbit.customproperty.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.flowbit.issue.application.IssueChange
import java.util.UUID

/** What kind of value a property holds (D-186). Fixed when the property is added. */
enum class CustomPropertyType {
    TEXT,
    NUMBER,
    DATE,
    CHECKBOX,
    LINK,
    PERSON,
}

/** The kinds of item a property can be added to: the word in the address (`/api/v1/custom-properties/{type}/{id}`) and the column of the table. */
enum class CustomPropertyOwnerKind(
    val path: String,
    internal val column: String,
) {
    PROJECT("project", "project_id"),
    ISSUE("issue", "issue_id"),
    ;

    companion object {
        fun ofPath(path: String): CustomPropertyOwnerKind? = entries.firstOrNull { it.path == path }
    }
}

/** One item: its kind and the id of its row (an issue's `id`, not its number). */
data class CustomPropertyOwner(
    val kind: CustomPropertyOwnerKind,
    val id: UUID,
)

/** The person a PERSON value names. */
data class CustomPropertyPerson(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
)

/** A property as the screens show it. */
data class CustomPropertyView(
    val id: UUID,
    val label: String,
    val type: CustomPropertyType,
    /** A string for TEXT, LINK, DATE (`YYYY-MM-DD`) and PERSON (the user's id), a number for NUMBER, a boolean for CHECKBOX; null while empty. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val value: Any?,
    /** Who a PERSON value is. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val person: CustomPropertyPerson?,
)

/** A property asked for together with the item it belongs to (a new issue), before it is checked. */
data class NewCustomProperty(
    val label: String,
    val type: CustomPropertyType,
    val value: Any? = null,
)

/** A [NewCustomProperty] that passed the checks, ready to be stored: the label as kept and the value as JSON. */
data class CheckedCustomProperty(
    val label: String,
    val type: CustomPropertyType,
    val valueJson: String?,
)

/** The properties of one item, and whether the caller may change them. */
data class CustomPropertyList(
    val canEdit: Boolean,
    val items: List<CustomPropertyView>,
)

/** What a change leaves behind for the controller to announce. */
data class CustomPropertyChange(
    /** The property after the change; null once it is deleted. */
    val item: CustomPropertyView?,
    /** For an issue: what to tell the people who follow it. */
    val issueChange: IssueChange?,
    /** The realtime topics whoever shows the item listens to (an issue is announced through [issueChange]). */
    val topics: List<String>,
)
