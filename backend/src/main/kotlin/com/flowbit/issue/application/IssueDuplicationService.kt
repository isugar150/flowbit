package com.flowbit.issue.application

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.richtext.RichText
import com.flowbit.customproperty.application.CustomPropertyService
import com.flowbit.issue.domain.Issue
import com.flowbit.media.application.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Duplicates an issue (docs/features/M3-projects-and-issues.md "티켓 복제", D-212): a new issue with the
 * title "<title> - 복제됨" and everything the issue itself holds: its body, status, priority, dates, project,
 * parent, labels, assignees, properties, files and documents. Only that one issue: its sub-issues stay with the
 * original. Not copied: what was said and done (the comments and the history; the copy's one entry says which
 * issue it came from), reactions, and what a Git server linked. Needs the right to edit the issue.
 */
@Service
class IssueDuplicationService(
    private val lookup: IssueLookup,
    private val queries: IssueQueries,
    private val entityTemplate: R2dbcEntityTemplate,
    private val richText: RichText,
    private val activities: IssueActivityStore,
    private val extras: IssueExtrasStore,
    private val customProperties: CustomPropertyService,
    private val fileStorage: FileStorage,
    private val clock: Clock,
) {
    /** Returns the new issue as a creation, for the caller to announce. */
    @Transactional
    suspend fun duplicate(
        user: AuthenticatedUser,
        number: Int,
    ): IssueChange {
        val original = lookup.editable(lookup.access(user, number)).issue
        val view = checkNotNull(queries.find(original.number))
        val now = clock.instant()
        val copy =
            Issue(
                id = UuidV7.generate(now),
                projectId = original.projectId,
                parentId = original.parentId,
                number = queries.nextNumber(),
                title = original.title.take(Issue.TITLE_MAX_LENGTH - SUFFIX.length) + SUFFIX,
                description = original.description,
                status = original.status,
                priority = original.priority,
                startDate = original.startDate,
                dueDate = original.dueDate,
                createdBy = user.id,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        entityTemplate.insert(copy).awaitSingle()
        richText.copy(RichText.ContentTable.ISSUES, original.id, copy.id)
        val labelIds = view.labels.map { it.id }
        if (labelIds.isNotEmpty()) queries.replaceLabels(copy.id, labelIds)
        // Someone who has left the project, or the workspace, is not given the copy.
        val assigneeIds = view.assignees.map { it.id }.filter { lookup.canSee(original, it) }
        if (assigneeIds.isNotEmpty()) queries.replaceAssignees(copy.id, assigneeIds, now)
        customProperties.copyIssueProperties(original.id, copy.id, now)
        copyFiles(original, copy, now)
        copyDocuments(user, original, copy, now)
        val event = IssueEvent(IssueActivityType.CREATED, mapOf("duplicatedFrom" to view.key, "duplicatedFromNumber" to original.number))
        activities.record(copy.id, user.id, listOf(event), now)
        // The one who copies it and the assignees follow the copy from the start.
        activities.subscribe(copy.id, user.id)
        assigneeIds.forEach { activities.subscribe(copy.id, it) }
        return IssueChange(checkNotNull(lookup.detail(copy.number)), listOf(event), lookup.recipients(copy.id, user))
    }

    /**
     * The files put on the issue itself (the ones in its body are named by the body, which is copied as it
     * is). Each gets a file of its own, so that taking one off the copy does not take it off the original.
     */
    private suspend fun copyFiles(
        original: Issue,
        copy: Issue,
        at: Instant,
    ) {
        extras.attachments(original.id).forEachIndexed { index, attachment ->
            val resource = fileStorage.find(attachment.fileKey) ?: return@forEachIndexed
            val bytes = withContext(Dispatchers.IO) { resource.inputStream.use { it.readAllBytes() } }
            extras.addAttachment(
                copy.id,
                fileStorage.store(bytes),
                attachment.fileName,
                bytes.size.toLong(),
                attachment.uploadedBy,
                at.plusNanos(index * 1_000L),
            )
        }
    }

    /** The documents in use (an issue's stay one level), with their text. What was said about them is not copied. */
    private suspend fun copyDocuments(
        user: AuthenticatedUser,
        original: Issue,
        copy: Issue,
        at: Instant,
    ) {
        extras.documents(original.id).forEachIndexed { index, document ->
            val created = extras.addDocument(copy.id, null, null, document.title, user.id, at.plusNanos(index * 1_000L))
            richText.copy(RichText.ContentTable.DOCUMENTS, document.id, created.id)
        }
    }

    private companion object {
        const val SUFFIX = " - 복제됨"
    }
}
