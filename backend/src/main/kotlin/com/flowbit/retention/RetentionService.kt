package com.flowbit.retention

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.content.ContentTargetType
import com.flowbit.media.application.FileStorage
import com.flowbit.media.application.StorageInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What can be removed for good once it has been deleted long enough. */
enum class RetentionItem {
    ISSUES,
    DOCUMENTS,
    COMMENTS,
}

enum class RetentionUnit {
    DAYS,
    WEEKS,
    MONTHS,
}

data class RetentionPeriod(
    val amount: Int,
    val unit: RetentionUnit,
) {
    /** The moment before which something deleted is old enough to go. Months are calendar months in UTC. */
    fun cutoff(now: Instant): Instant {
        val at = now.atOffset(ZoneOffset.UTC)
        val amount = amount.toLong()
        return when (unit) {
            RetentionUnit.DAYS -> at.minusDays(amount)
            RetentionUnit.WEEKS -> at.minusWeeks(amount)
            RetentionUnit.MONTHS -> at.minusMonths(amount)
        }.toInstant()
    }

    companion object {
        const val MAX_AMOUNT = 999
    }
}

/** A null period: never removed. */
data class RetentionSettings(
    val issues: RetentionPeriod?,
    val documents: RetentionPeriod?,
    val comments: RetentionPeriod?,
) {
    fun of(item: RetentionItem): RetentionPeriod? =
        when (item) {
            RetentionItem.ISSUES -> issues
            RetentionItem.DOCUMENTS -> documents
            RetentionItem.COMMENTS -> comments
        }
}

/** How many rows one run removed, per item. */
data class PurgeResult(
    val issues: Long,
    val documents: Long,
    val comments: Long,
) {
    val isEmpty: Boolean
        get() = issues == 0L && documents == 0L && comments == 0L
}

/**
 * Removes deleted issues, documents and comments for good once they have been deleted for longer
 * than the administrator chose (docs/features/admin-retention.md, D-132). Until then they are only
 * marked, can be opened read-only and restored (D-079, D-121). The pictures that were in them
 * are removed with them (D-171).
 */
@Service
class RetentionService(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val fileStorage: FileStorage,
    private val storageInspector: StorageInspector,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun settings(): RetentionSettings {
        val rows =
            databaseClient
                .sql("select item, amount, unit from retention_policies")
                .map { row ->
                    RetentionItem.valueOf(checkNotNull(row.get("item", String::class.java))) to
                        RetentionPeriod(
                            checkNotNull(row.get("amount", Int::class.javaObjectType)),
                            RetentionUnit.valueOf(checkNotNull(row.get("unit", String::class.java))),
                        )
                }.all()
                .collectList()
                .awaitSingle()
                .toMap()
        return RetentionSettings(rows[RetentionItem.ISSUES], rows[RetentionItem.DOCUMENTS], rows[RetentionItem.COMMENTS])
    }

    /** Replaces all three periods. Administrators only (reached through `/api/v1/admin`). */
    suspend fun update(
        settings: RetentionSettings,
        actorId: UUID,
        client: ClientInfo,
    ): RetentionSettings {
        val errors =
            RetentionItem.entries.mapNotNull { item ->
                val amount = settings.of(item)?.amount ?: return@mapNotNull null
                if (amount in 1..RetentionPeriod.MAX_AMOUNT) {
                    null
                } else {
                    FieldError("${fieldOf(item)}.amount", "OUT_OF_RANGE", "기간은 1~${RetentionPeriod.MAX_AMOUNT} 사이의 숫자여야 합니다")
                }
            }
        if (errors.isNotEmpty()) throw ValidationException(errors)

        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        transactionalOperator.executeAndAwait {
            execute("delete from retention_policies")
            for (item in RetentionItem.entries) {
                val period = settings.of(item) ?: continue
                databaseClient
                    .sql("insert into retention_policies (item, amount, unit, updated_at) values (:item, :amount, :unit, :now)")
                    .bind("item", item.name)
                    .bind("amount", period.amount)
                    .bind("unit", period.unit.name)
                    .bind("now", now)
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
            auditService.record(
                AuditEventType.RETENTION_SETTINGS_CHANGED,
                actorId,
                client,
                RetentionItem.entries.associate { item ->
                    fieldOf(item) to (settings.of(item)?.let { "${it.amount} ${it.unit}" } ?: "NEVER")
                },
            )
        }
        return settings()
    }

    /**
     * Removes what has been deleted for longer than its period: comments, then documents, then
     * issues (with everything under them; their attached files are taken out of storage after the
     * rows are gone). Items without a period are left alone.
     */
    suspend fun purge(): PurgeResult {
        val settings = settings()
        val now = clock.instant()
        val removedFiles = mutableListOf<String>()
        val result =
            transactionalOperator.executeAndAwait {
                val comments =
                    settings.comments?.let { period ->
                        val cutoff = period.cutoff(now)
                        // The files sent with a comment go with it.
                        val issueReplies = noReplyLeft("issue_comments", "c")
                        val contentReplies = noReplyLeft("content_comments", "c")
                        removedFiles +=
                            fileKeys(
                                "select a.file_key from issue_attachments a join issue_comments c on c.id = a.comment_id " +
                                    "where c.deleted_at is not null and c.deleted_at < :cutoff and $issueReplies",
                                cutoff,
                            )
                        removedFiles +=
                            fileKeys(
                                "select f.file_key from content_files f join content_comments c on c.id = f.comment_id " +
                                    "where c.deleted_at is not null and c.deleted_at < :cutoff and $contentReplies",
                                cutoff,
                            )
                        // Comments on the blocks of pages and under the activity of pages are comments too (D-165).
                        deleteOlder("issue_comments", cutoff, guardReplies = true) +
                            deleteOlder("content_comments", cutoff, guardReplies = true) +
                            deleteOlder("content_thread_comments", cutoff)
                    } ?: 0
                val documents = settings.documents?.let { deleteOlder("documents", it.cutoff(now)) } ?: 0
                val issues =
                    settings.issues?.let { period ->
                        removedFiles +=
                            fileKeys(
                                "select a.file_key from issue_attachments a join issues i on i.id = a.issue_id where i.deleted_at is not null and i.deleted_at < :cutoff",
                                period.cutoff(now),
                            )
                        deleteOlder("issues", period.cutoff(now))
                    } ?: 0
                // Not a setting: files uploaded for a comment that was never sent (D-164) are removed after a day.
                val abandoned = now.minus(PENDING_FILE_LIFETIME)
                removedFiles += fileKeys("select a.file_key from issue_attachments a where a.pending and a.created_at < :cutoff", abandoned)
                databaseClient
                    .sql("delete from issue_attachments where pending and created_at < :cutoff")
                    .bind("cutoff", at(abandoned))
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                removedFiles += fileKeys("select f.file_key from content_files f where f.pending and f.created_at < :cutoff", abandoned)
                databaseClient
                    .sql("delete from content_files where pending and created_at < :cutoff")
                    .bind("cutoff", at(abandoned))
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                // Comments, threads and activity are kept by the page's id without a foreign key (D-165): what a page deleted for good left behind goes too.
                removedFiles += sweepOrphans()
                val result = PurgeResult(issues = issues, documents = documents, comments = comments)
                if (!result.isEmpty) {
                    auditService.record(
                        AuditEventType.DATA_PURGED,
                        null,
                        ClientInfo(null, null),
                        mapOf("issues" to "$issues", "documents" to "$documents", "comments" to "$comments"),
                    )
                }
                result
            }
        removedFiles.forEach { fileStorage.delete(it) }
        if (!result.isEmpty) {
            log.info("Removed for good: {} issues, {} documents, {} comments", result.issues, result.documents, result.comments)
        }
        if (!result.isEmpty || removedFiles.isNotEmpty()) cleanUpPictures()
        return result
    }

    /**
     * What was just removed may have held pictures (put into a text, with their originals): nothing uses those now, so
     * they go the same way (D-171). When it does not work nothing is removed and the next run, or the administrator's file page, tries again.
     */
    private suspend fun cleanUpPictures() {
        try {
            storageInspector.cleanOrphans(actorId = null, client = null, automatic = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not clean up the pictures of what was removed", e)
        }
    }

    /**
     * `guardReplies`: a comment that has replies not yet due for removal stays, whatever its own age: removing it
     * would take them along (their foreign key) before their own period is over, and their files would be left behind (D-167).
     */
    private suspend fun deleteOlder(
        table: String,
        cutoff: Instant,
        guardReplies: Boolean = false,
    ): Long =
        databaseClient
            .sql("delete from $table where deleted_at is not null and deleted_at < :cutoff" + if (guardReplies) " and ${noReplyLeft(table, table)}" else "")
            .bind("cutoff", at(cutoff))
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** The condition that no reply of the comment (alias `alias` in `table`) is live or newer than the cutoff. `table` is ours, never input. */
    private fun noReplyLeft(
        table: String,
        alias: String,
    ) = "not exists (select 1 from $table r where r.parent_id = $alias.id and (r.deleted_at is null or r.deleted_at >= :cutoff))"

    /**
     * Removes the threads, comments, activity and files of pages that no longer exist, and returns
     * the storage keys of those files. The page is one of four kinds of row, so nothing deletes
     * these along with it.
     */
    private suspend fun sweepOrphans(): List<String> {
        val keys = mutableListOf<String>()
        for ((table, types) in CONTENT_TABLES) {
            for (type in types) {
                val gone = "target_type = '${type.name}' and not exists (select 1 from ${type.table.sqlName} p where p.id = $table.target_id)"
                if (table == "content_files") {
                    keys +=
                        databaseClient
                            .sql("select file_key from content_files where $gone")
                            .map { row -> checkNotNull(row.get("file_key", String::class.java)) }
                            .all()
                            .collectList()
                            .awaitSingle()
                }
                execute("delete from $table where $gone")
            }
        }
        return keys
    }

    /** The storage keys of the files the query (one `:cutoff` parameter) selects. */
    private suspend fun fileKeys(
        sql: String,
        cutoff: Instant,
    ): List<String> =
        databaseClient
            .sql(sql)
            .bind("cutoff", at(cutoff))
            .map { row -> checkNotNull(row.get("file_key", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    private suspend fun execute(sql: String) {
        databaseClient
            .sql(sql)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun fieldOf(item: RetentionItem) = item.name.lowercase()

    private companion object {
        /** How long a file uploaded for a comment waits for the comment to be sent. */
        val PENDING_FILE_LIFETIME: Duration = Duration.ofDays(1)

        /** Which kinds of page each table of comments, threads, activity and files can hold. `table` and `type` are ours, never input. */
        val CONTENT_TABLES: Map<String, List<ContentTargetType>> =
            mapOf(
                "content_threads" to ContentTargetType.entries,
                "content_activities" to ContentTargetType.entries.filter { it.hasActivity },
                "content_comments" to ContentTargetType.entries.filter { it.hasActivity },
                "content_files" to ContentTargetType.entries.filter { it.hasActivity },
            )
    }
}
