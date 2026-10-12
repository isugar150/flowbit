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

data class DocumentSummary(
    val id: UUID,
    val issueId: UUID,
    val title: String,
    val updatedAt: Instant,
)

/** What hangs on an issue besides its own fields: reactions, attached files, documents (V12). */
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

    suspend fun documents(issueId: UUID): List<DocumentSummary> = documentsWhere("issue_id = :id order by created_at, id", issueId)

    suspend fun findDocument(id: UUID): DocumentSummary? = documentsWhere("id = :id", id).firstOrNull()

    suspend fun addDocument(
        issueId: UUID,
        title: String,
        userId: UUID,
        now: Instant,
    ): DocumentSummary {
        val id = UuidV7.generate(now)
        databaseClient
            .sql(
                """
                insert into issue_documents (id, issue_id, title, created_by, created_at, updated_at)
                values (:id, :issueId, :title, :userId, :now, :now)
                """.trimIndent(),
            ).bind("id", id)
            .bind("issueId", issueId)
            .bind("title", title)
            .bind("userId", userId)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return DocumentSummary(id, issueId, title, now)
    }

    /** Sets the title when one is given and marks the document as changed now. */
    suspend fun touchDocument(
        id: UUID,
        title: String?,
        now: Instant,
    ) {
        databaseClient
            .sql("update issue_documents set title = coalesce(:title, title), updated_at = :now where id = :id")
            .bind("id", id)
            .bind("now", now)
            .let { if (title != null) it.bind("title", title) else it.bindNull("title", String::class.java) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun deleteDocument(id: UUID) = deleteById("issue_documents", id)

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
            .sql("select id, issue_id, title, updated_at from issue_documents where $where")
            .bind("id", id)
            .map { row ->
                DocumentSummary(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    issueId = checkNotNull(row.get("issue_id", UUID::class.java)),
                    title = checkNotNull(row.get("title", String::class.java)),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
}
