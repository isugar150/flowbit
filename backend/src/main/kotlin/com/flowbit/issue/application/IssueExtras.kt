package com.flowbit.issue.application

import com.flowbit.common.UuidV7
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** One emoji on an issue with everyone who chose it. */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    /** Whether the person asking is one of them. */
    val reacted: Boolean,
    val names: List<String>,
)

data class AttachmentView(
    val id: UUID,
    val issueId: UUID,
    val fileKey: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

/** A document; it belongs to an issue or to a project, so exactly one of the two ids is set. */
data class DocumentSummary(
    val id: UUID,
    val issueId: UUID?,
    val projectId: UUID?,
    /** The document this one sits inside; null for one made on the issue or the project itself. */
    val parentId: UUID?,
    val title: String,
    val updatedAt: Instant,
    /** When it was deleted (archived); null for a document in use. */
    val deletedAt: Instant? = null,
)

/** A branch or pull request a Git server linked the issue to (docs/features/git-integration.md). */
data class IssueGitLinkView(
    val kind: String,
    val repository: String,
    val branch: String?,
    val prNumber: Int?,
    val title: String?,
    val url: String,
    val prState: String?,
    val closes: Boolean,
)

/** A Git repository an issue's work can be in (D-148). */
data class IssueGitRepositoryView(
    val connectionId: UUID,
    /** org/web */
    val repository: String,
    /** The repository's page on the Git server. */
    val url: String,
)

/** How deep documents nest in a project: a document on the overview is level 1. An issue's stay at level 1. */
const val MAX_DOCUMENT_DEPTH = 5

private const val PULL_REQUEST = "PULL_REQUEST"

/** What hangs on an issue besides its own fields: reactions, attached files, documents (V12), Git links (V11). */
@Component
class IssueExtrasStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun reactions(
        issueId: UUID,
        userId: UUID,
    ): List<ReactionSummary> =
        databaseClient
            .sql(
                """
                select r.emoji, count(*) as n, bool_or(r.user_id = :userId) as reacted,
                       string_agg(coalesce(u.display_name, u.login_id), ', ' order by r.created_at) as names
                from issue_reactions r join users u on u.id = r.user_id
                where r.issue_id = :issueId
                group by r.emoji
                order by min(r.created_at)
                """.trimIndent(),
            ).bind("issueId", issueId)
            .bind("userId", userId)
            .map { row ->
                ReactionSummary(
                    emoji = checkNotNull(row.get("emoji", String::class.java)),
                    count = checkNotNull(row.get("n", Long::class.javaObjectType)).toInt(),
                    reacted = checkNotNull(row.get("reacted", Boolean::class.javaObjectType)),
                    names = checkNotNull(row.get("names", String::class.java)).split(", "),
                )
            }.all()
            .collectList()
            .awaitSingle()

    /** Adds the person's reaction, or takes it back when it is already there. */
    suspend fun toggleReaction(
        issueId: UUID,
        userId: UUID,
        emoji: String,
    ) {
        val removed =
            databaseClient
                .sql("delete from issue_reactions where issue_id = :issueId and user_id = :userId and emoji = :emoji")
                .bind("issueId", issueId)
                .bind("userId", userId)
                .bind("emoji", emoji)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (removed > 0) return
        databaseClient
            .sql("insert into issue_reactions (issue_id, user_id, emoji) values (:issueId, :userId, :emoji) on conflict do nothing")
            .bind("issueId", issueId)
            .bind("userId", userId)
            .bind("emoji", emoji)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun attachments(issueId: UUID): List<AttachmentView> = attachmentsWhere("issue_id = :id order by created_at, id", issueId)

    suspend fun findAttachment(id: UUID): AttachmentView? = attachmentsWhere("id = :id", id).firstOrNull()

    suspend fun addAttachment(
        issueId: UUID,
        fileKey: String,
        fileName: String,
        sizeBytes: Long,
        userId: UUID,
        now: Instant,
    ): AttachmentView {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into issue_attachments (id, issue_id, file_key, file_name, size_bytes, uploaded_by, created_at)
                values (:id, :issueId, :fileKey, :fileName, :sizeBytes, :userId, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("issueId", issueId)
            .bind("fileKey", fileKey)
            .bind("fileName", fileName)
            .bind("sizeBytes", sizeBytes)
            .bind("userId", userId)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return AttachmentView(id, issueId, fileKey, fileName, sizeBytes, now)
    }

    suspend fun deleteAttachment(id: UUID) = deleteById("issue_attachments", id)

    suspend fun documents(issueId: UUID): List<DocumentSummary> = documentsWhere("issue_id = :id and deleted_at is null order by created_at, id", issueId)

    suspend fun projectDocuments(projectId: UUID): List<DocumentSummary> =
        documentsWhere("project_id = :id and parent_id is null and deleted_at is null order by created_at, id", projectId)

    /** The deleted documents of an issue, the last deleted first. */
    suspend fun deletedDocuments(issueId: UUID): List<DocumentSummary> =
        documentsWhere("issue_id = :id and deleted_at is not null order by deleted_at desc, id", issueId)

    /** The deleted documents of a project at any depth, the last deleted first. */
    suspend fun deletedProjectDocuments(projectId: UUID): List<DocumentSummary> =
        documentsWhere("project_id = :id and deleted_at is not null order by deleted_at desc, id", projectId)

    suspend fun childDocuments(documentId: UUID): List<DocumentSummary> =
        documentsWhere("parent_id = :id and deleted_at is null order by created_at, id", documentId)

    /** The documents a document sits inside, the outermost first. */
    suspend fun documentAncestors(documentId: UUID): List<DocumentSummary> {
        val ancestors = mutableListOf<DocumentSummary>()
        var parentId = findDocument(documentId)?.parentId
        while (parentId != null && ancestors.size < MAX_DOCUMENT_DEPTH) {
            val parent = findDocument(parentId) ?: break
            ancestors.add(0, parent)
            parentId = parent.parentId
        }
        return ancestors
    }

    suspend fun findDocument(id: UUID): DocumentSummary? = documentsWhere("id = :id", id).firstOrNull()

    /** Give exactly one of `issueId` and `projectId`: whose document this is. `parentId` puts it inside another document. */
    suspend fun addDocument(
        issueId: UUID?,
        projectId: UUID?,
        parentId: UUID?,
        title: String,
        userId: UUID,
        now: Instant,
    ): DocumentSummary {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into documents (id, issue_id, project_id, parent_id, title, created_by, created_at, updated_at)
                values (:id, :issueId, :projectId, :parentId, :title, :userId, :now, :now)
                """.trimIndent(),
            ).bind("id", id)
            .let { if (issueId != null) it.bind("issueId", issueId) else it.bindNull("issueId", UUID::class.java) }
            .let { if (projectId != null) it.bind("projectId", projectId) else it.bindNull("projectId", UUID::class.java) }
            .let { if (parentId != null) it.bind("parentId", parentId) else it.bindNull("parentId", UUID::class.java) }
            .bind("title", title)
            .bind("userId", userId)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return DocumentSummary(id, issueId, projectId, parentId, title, now)
    }

    /** Sets the title when one is given and marks the document as changed now. */
    suspend fun touchDocument(
        id: UUID,
        title: String?,
        now: Instant,
    ) {
        databaseClient
            .sql("update documents set title = coalesce(:title, title), updated_at = :now where id = :id")
            .bind("id", id)
            .bind("now", now)
            .let { if (title != null) it.bind("title", title) else it.bindNull("title", String::class.java) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /**
     * Archives the document: the row stays, with who deleted it and when. The documents inside it
     * are left as they are; they are reached through it, so they go and come back with it.
     */
    suspend fun deleteDocument(
        id: UUID,
        userId: UUID,
        now: Instant,
    ) {
        databaseClient
            .sql("update documents set deleted_at = :now, deleted_by = :userId where id = :id")
            .bind("id", id)
            .bind("userId", userId)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun restoreDocument(id: UUID) {
        databaseClient
            .sql("update documents set deleted_at = null, deleted_by = null where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /**
     * The branches and pull requests linked to the issue: pull requests first, newest first, then
     * branches. A branch that has a pull request is shown by that pull request alone.
     */
    suspend fun gitLinks(issueId: UUID): List<IssueGitLinkView> {
        val links =
            databaseClient
                .sql(
                    """
                    select connection_id, kind, repository, branch, pr_number, title, url, pr_state, closes
                    from issue_git_links where issue_id = :id
                    order by kind desc, created_at desc, id
                    """.trimIndent(),
                ).bind("id", issueId)
                .map { row ->
                    checkNotNull(row.get("connection_id", UUID::class.java)) to
                        IssueGitLinkView(
                            kind = checkNotNull(row.get("kind", String::class.java)),
                            repository = checkNotNull(row.get("repository", String::class.java)),
                            branch = row.get("branch", String::class.java),
                            prNumber = row.get("pr_number", Int::class.javaObjectType),
                            title = row.get("title", String::class.java),
                            url = checkNotNull(row.get("url", String::class.java)),
                            prState = row.get("pr_state", String::class.java),
                            closes = checkNotNull(row.get("closes", Boolean::class.javaObjectType)),
                        )
                }.all()
                .collectList()
                .awaitSingle()
        val branchesWithPullRequest =
            links.filter { it.second.kind == PULL_REQUEST }.map { (connectionId, link) -> Triple(connectionId, link.repository, link.branch) }.toSet()
        return links
            .filterNot { (connectionId, link) -> link.kind != PULL_REQUEST && Triple(connectionId, link.repository, link.branch) in branchesWithPullRequest }
            .map { it.second }
    }

    /** The repositories the project's managers chose, on connections that are switched on. */
    suspend fun projectGitRepositories(projectId: UUID): List<IssueGitRepositoryView> =
        gitRepositories(
            """
            select r.connection_id, r.repository, c.base_url from project_git_repositories r
            join git_connections c on c.id = r.connection_id and c.enabled
            where r.project_id = :id order by r.created_at, r.repository
            """.trimIndent(),
            projectId,
        )

    /** The repository picked on the issue, if any. */
    suspend fun issueGitRepository(issueId: UUID): IssueGitRepositoryView? =
        gitRepositories(
            """
            select r.connection_id, r.repository, c.base_url from issue_git_repositories r
            join git_connections c on c.id = r.connection_id
            where r.issue_id = :id
            """.trimIndent(),
            issueId,
        ).firstOrNull()

    /** Picks the issue's repository, or takes the pick away with null. */
    suspend fun setIssueGitRepository(
        issueId: UUID,
        connectionId: UUID?,
        repository: String?,
        now: Instant,
    ) {
        if (connectionId == null || repository == null) {
            deleteWhere("issue_git_repositories", "issue_id", issueId)
            return
        }
        databaseClient
            .sql(
                """
                insert into issue_git_repositories (issue_id, connection_id, repository, updated_at) values (:id, :connection, :repository, :now)
                on conflict (issue_id) do update set connection_id = excluded.connection_id, repository = excluded.repository, updated_at = excluded.updated_at
                """.trimIndent(),
            ).bind("id", issueId)
            .bind("connection", connectionId)
            .bind("repository", repository)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun gitRepositories(
        sql: String,
        id: UUID,
    ): List<IssueGitRepositoryView> =
        databaseClient
            .sql(sql)
            .bind("id", id)
            .map { row ->
                val repository = checkNotNull(row.get("repository", String::class.java))
                val base = checkNotNull(row.get("base_url", String::class.java)).trimEnd('/')
                IssueGitRepositoryView(checkNotNull(row.get("connection_id", UUID::class.java)), repository, "$base/$repository")
            }.all()
            .collectList()
            .awaitSingle()

    private suspend fun deleteWhere(
        table: String,
        column: String,
        id: UUID,
    ) {
        databaseClient
            .sql("delete from $table where $column = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun deleteById(
        table: String,
        id: UUID,
    ) {
        databaseClient
            .sql("delete from $table where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingleOrNull()
    }

    private suspend fun attachmentsWhere(
        where: String,
        id: UUID,
    ): List<AttachmentView> =
        databaseClient
            .sql("select id, issue_id, file_key, file_name, size_bytes, created_at from issue_attachments where $where")
            .bind("id", id)
            .map { row ->
                AttachmentView(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    issueId = checkNotNull(row.get("issue_id", UUID::class.java)),
                    fileKey = checkNotNull(row.get("file_key", String::class.java)),
                    fileName = checkNotNull(row.get("file_name", String::class.java)),
                    sizeBytes = checkNotNull(row.get("size_bytes", Long::class.javaObjectType)),
                    createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private suspend fun documentsWhere(
        where: String,
        id: UUID,
    ): List<DocumentSummary> =
        databaseClient
            .sql("select id, issue_id, project_id, parent_id, title, updated_at, deleted_at from documents where $where")
            .bind("id", id)
            .map { row ->
                DocumentSummary(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    issueId = row.get("issue_id", UUID::class.java),
                    projectId = row.get("project_id", UUID::class.java),
                    parentId = row.get("parent_id", UUID::class.java),
                    title = checkNotNull(row.get("title", String::class.java)),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                    deletedAt = row.get("deleted_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
}
