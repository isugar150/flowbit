package com.flowbit.media.application

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.web.ClientInfo
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Compares what the storage holds with what the database uses, for the administrator's file page and for
 * the clean-up after the retention job (D-171, docs/features/admin-files.md). Reads only; [cleanOrphans]
 * is the one thing that removes anything, and only what nothing uses and that has been there for a day.
 */
@Service
class StorageInspector(
    private val store: ObjectStore,
    private val properties: StorageProperties,
    private val databaseClient: DatabaseClient,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Everything the file page shows. Reads the whole storage and the database's text columns: seconds for a large installation. */
    suspend fun inspect(): StorageInspection {
        val scan = scan()
        val rows = fileRows()
        val storedFiles = scan.listed.getValue(StorageArea.FILES).associateBy { it.key }
        val storedImages = scan.listed.getValue(StorageArea.IMAGES).associateBy { it.key }
        val storedOriginals = scan.listed.getValue(StorageArea.ORIGINALS).associateBy { it.key }

        fun imageTally(keys: Set<String>): Tally =
            keys.fold(Tally()) { tally, key ->
                val image = storedImages[key] ?: return@fold tally
                tally.with(image.size + (storedOriginals[key.removeSuffix(WEBP)]?.size ?: 0))
            }

        val known = rows.filter { !it.pending }
        val uses =
            FileUses(
                ticketAttachments = known.filter { it.source == FileSource.TICKET_ATTACHMENT }.tally(),
                ticketCommentFiles = known.filter { it.source == FileSource.TICKET_COMMENT }.tally(),
                contentCommentFiles = known.filter { it.source == FileSource.CONTENT_COMMENT }.tally(),
                pendingFiles = rows.filter { it.pending }.tally(),
                avatars = imageTally(scan.references.avatars),
                logo = imageTally(scan.references.logos),
                projectIcons = imageTally(scan.references.icons),
                contentImages = imageTally(scan.references.contentImages),
                awaitingPurge = rows.filter { it.deleted }.tally(),
            )
        val uploaders =
            known
                .groupBy { it.uploaderId }
                .map { (id, files) -> Uploader(id, files.first().uploader, files.size.toLong(), files.sumOf { it.bytes }) }
                .sortedByDescending { it.bytes }
                .take(TOP_UPLOADERS)
        val largest =
            known
                .sortedByDescending { it.bytes }
                .take(LARGEST_FILES)
                .map { LargeFile(it.name, it.bytes, it.uploader, it.source, it.issueKey, it.createdAt) }

        val missingFiles = rows.filter { it.key !in storedFiles }.distinctBy { it.key }
        val missingImages = scan.references.all.filter { it !in storedImages }
        val withoutOriginal = scan.references.all.count { it in storedImages && it.removeSuffix(WEBP) !in storedOriginals }
        val now = clock.instant()
        val orphanSamples =
            scan.orphans
                .sortedBy { it.modifiedAt }
                .take(ORPHAN_SAMPLES)
                .map { OrphanSample(it.area, it.key, it.size, it.modifiedAt, it.cleanableAt(now)) }

        val areas = AreaTallies(scan.tally(StorageArea.FILES), scan.tally(StorageArea.IMAGES), scan.tally(StorageArea.ORIGINALS))
        return StorageInspection(
            checkedAt = now,
            storage = store.describe(),
            maxUploadBytes = properties.maxUploadSize.toBytes(),
            areas = areas,
            total = areas.total,
            uses = uses,
            topUploaders = uploaders,
            largestFiles = largest,
            consistency =
                Consistency(
                    graceHours = GRACE.toHours(),
                    orphans = scan.orphanTallies(),
                    cleanable = scan.orphans.filter { it.cleanableAt(now) }.fold(Tally()) { tally, orphan -> tally.with(orphan.size) },
                    inGrace = scan.orphans.count { !it.cleanableAt(now) }.toLong(),
                    orphanSamples = orphanSamples,
                    missing = MissingCounts(missingFiles.size.toLong(), missingImages.size.toLong()),
                    missingSamples =
                        (
                            missingFiles.map { MissingSample(MissingKind.FILE, it.name, it.key, it.source, it.issueKey) } +
                                missingImages.map { MissingSample(MissingKind.IMAGE, null, it, null, null) }
                        ).take(MISSING_SAMPLES),
                    imagesWithoutOriginal = withoutOriginal.toLong(),
                    unknownObjects = scan.unknown,
                ),
        )
    }

    /**
     * Removes what nothing in the database uses and that is more than a day old, then records it. `automatic` is the run
     * after the retention job: it refuses when the database seems to use no picture at all while the storage holds many,
     * which would mean the reading of the database went wrong rather than that all of them are unused.
     */
    suspend fun cleanOrphans(
        actorId: UUID?,
        client: ClientInfo?,
        automatic: Boolean,
    ): CleanupResult {
        val scan = scan()
        val imagesStored = scan.listed.getValue(StorageArea.IMAGES).size
        if (automatic && imagesStored >= SUSPICIOUS_IMAGE_COUNT && scan.references.all.isEmpty()) {
            log.warn("Not cleaning up the storage: it holds {} pictures and the database uses none of them", imagesStored)
            return CleanupResult(AreaTallies(), 0, 0)
        }
        val now = clock.instant()
        var deleted = AreaTallies()
        var failed = 0L
        for (orphan in scan.orphans.filter { it.cleanableAt(now) }) {
            try {
                store.delete(orphan.area, orphan.key)
                deleted = deleted.plusOne(orphan.area, orphan.size)
            } catch (e: Exception) {
                failed++
                log.warn("Could not remove the unused file {}/{}", orphan.area.folder, orphan.key, e)
            }
        }
        val result = CleanupResult(deleted, deleted.total.bytes, failed)
        if (deleted.total.count > 0 || failed > 0) {
            auditService.record(
                AuditEventType.FILES_CLEANED,
                actorId,
                client ?: ClientInfo(null, null),
                mapOf(
                    "files" to "${deleted.files.count}",
                    "images" to "${deleted.images.count}",
                    "originals" to "${deleted.originals.count}",
                    "bytes" to "${result.bytes}",
                    "failed" to "$failed",
                    "automatic" to "$automatic",
                ),
            )
            log.info("Removed {} unused files ({} bytes), {} failed", deleted.total.count, result.bytes, failed)
        }
        return result
    }

    /** The storage listed, what the database uses, and from the two what is unused. */
    private suspend fun scan(): Scan {
        val listed =
            try {
                StorageArea.entries.associateWith { store.list(it) }
            } catch (e: Exception) {
                throw StorageUnavailableException(e.message, e)
            }
        val references = references()
        val orphans = mutableListOf<Orphan>()
        var unknown = 0L
        for ((area, objects) in listed) {
            for (entry in objects) {
                val unused =
                    when (area) {
                        StorageArea.FILES -> if (UUID_KEY.matches(entry.key)) entry.key !in references.fileKeys else null
                        StorageArea.IMAGES -> if (IMAGE_KEY.matches(entry.key)) entry.key !in references.all else null
                        StorageArea.ORIGINALS -> if (UUID_KEY.matches(entry.key)) "${entry.key}$WEBP" !in references.all else null
                    }
                when (unused) {
                    null -> unknown++
                    true -> orphans += Orphan(area, entry.key, entry.size, entry.modifiedAt)
                    false -> Unit
                }
            }
        }
        return Scan(listed, references, orphans, unknown)
    }

    private fun Orphan.cleanableAt(now: Instant) = modifiedAt <= now.minus(GRACE)

    private fun AreaTallies.plusOne(
        area: StorageArea,
        size: Long,
    ) = when (area) {
        StorageArea.FILES -> copy(files = files.with(size))
        StorageArea.IMAGES -> copy(images = images.with(size))
        StorageArea.ORIGINALS -> copy(originals = originals.with(size))
    }

    private fun List<FileRow>.tally() = fold(Tally()) { tally, row -> tally.with(row.bytes) }

    private data class Orphan(
        val area: StorageArea,
        val key: String,
        val size: Long,
        val modifiedAt: Instant,
    )

    private class Scan(
        val listed: Map<StorageArea, List<StoredObject>>,
        val references: References,
        val orphans: List<Orphan>,
        val unknown: Long,
    ) {
        fun tally(area: StorageArea) = listed.getValue(area).fold(Tally()) { tally, entry -> tally.with(entry.size) }

        fun orphanTallies() =
            AreaTallies(
                files = orphans.filter { it.area == StorageArea.FILES }.fold(Tally()) { tally, orphan -> tally.with(orphan.size) },
                images = orphans.filter { it.area == StorageArea.IMAGES }.fold(Tally()) { tally, orphan -> tally.with(orphan.size) },
                originals = orphans.filter { it.area == StorageArea.ORIGINALS }.fold(Tally()) { tally, orphan -> tally.with(orphan.size) },
            )
    }

    /** The keys the database uses: of attached files, and of pictures by what they are used for. */
    private class References(
        val fileKeys: Set<String>,
        val avatars: Set<String>,
        val logos: Set<String>,
        val icons: Set<String>,
        val contentImages: Set<String>,
    ) {
        /** Every picture key in use, wherever it is used. */
        val all: Set<String> = avatars + logos + icons + contentImages
    }

    /** One stored file of an issue or of a page, as the database describes it. */
    private data class FileRow(
        val key: String,
        val name: String,
        val bytes: Long,
        val source: FileSource,
        val pending: Boolean,
        val deleted: Boolean,
        val uploaderId: UUID,
        val uploader: String,
        val issueKey: String?,
        val createdAt: Instant,
    )

    private suspend fun fileRows(): List<FileRow> {
        val issueFiles =
            databaseClient
                .sql(
                    """
                    select a.file_key, a.file_name, a.size_bytes, a.pending, a.created_at, a.comment_id is not null as on_comment,
                           (i.deleted_at is not null or c.deleted_at is not null) as deleted,
                           a.uploaded_by, coalesce(u.display_name, u.login_id) as uploader, s.workspace_key || '-' || i.number as issue_key
                    from issue_attachments a
                    join issues i on i.id = a.issue_id
                    left join issue_comments c on c.id = a.comment_id
                    join users u on u.id = a.uploaded_by
                    cross join instance_settings s
                    where s.id = 1
                    """.trimIndent(),
                ).map { row ->
                    val onComment = checkNotNull(row.get("on_comment", Boolean::class.javaObjectType))
                    FileRow(
                        key = checkNotNull(row.get("file_key", String::class.java)),
                        name = checkNotNull(row.get("file_name", String::class.java)),
                        bytes = checkNotNull(row.get("size_bytes", Long::class.javaObjectType)),
                        source = if (onComment) FileSource.TICKET_COMMENT else FileSource.TICKET_ATTACHMENT,
                        pending = checkNotNull(row.get("pending", Boolean::class.javaObjectType)),
                        deleted = checkNotNull(row.get("deleted", Boolean::class.javaObjectType)),
                        uploaderId = checkNotNull(row.get("uploaded_by", UUID::class.java)),
                        uploader = checkNotNull(row.get("uploader", String::class.java)),
                        issueKey = row.get("issue_key", String::class.java),
                        createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    )
                }.all()
                .collectList()
                .awaitSingle()
        val contentFiles =
            databaseClient
                .sql(
                    """
                    select f.file_key, f.file_name, f.size_bytes, f.pending, f.created_at,
                           (c.deleted_at is not null or d.deleted_at is not null) as deleted,
                           f.uploaded_by, coalesce(u.display_name, u.login_id) as uploader
                    from content_files f
                    left join content_comments c on c.id = f.comment_id
                    left join documents d on f.target_type = 'DOCUMENT' and d.id = f.target_id
                    join users u on u.id = f.uploaded_by
                    """.trimIndent(),
                ).map { row ->
                    FileRow(
                        key = checkNotNull(row.get("file_key", String::class.java)),
                        name = checkNotNull(row.get("file_name", String::class.java)),
                        bytes = checkNotNull(row.get("size_bytes", Long::class.javaObjectType)),
                        source = FileSource.CONTENT_COMMENT,
                        pending = checkNotNull(row.get("pending", Boolean::class.javaObjectType)),
                        deleted = checkNotNull(row.get("deleted", Boolean::class.javaObjectType)),
                        uploaderId = checkNotNull(row.get("uploaded_by", UUID::class.java)),
                        uploader = checkNotNull(row.get("uploader", String::class.java)),
                        issueKey = null,
                        createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                    )
                }.all()
                .collectList()
                .awaitSingle()
        return issueFiles + contentFiles
    }

    /**
     * What the database uses. Attached files: every `file_key` column of every table. Pictures: the three columns that hold one
     * (`avatar_key`, `logo_key`, `icon_key`) and a `<uuid>.webp` anywhere in the text or JSON of any other column, which is
     * where a picture put into a text shows up. Columns are found in the catalog, so a table added later is covered too.
     */
    private suspend fun references(): References {
        val fileKeys = mutableSetOf<String>()
        for ((table, column) in columns("c.column_name = 'file_key'")) {
            fileKeys += keys("select \"$column\" as key from \"$table\" where \"$column\" is not null")
        }
        val avatars = keys("select avatar_key as key from users where avatar_key is not null")
        val logos = keys("select logo_key as key from instance_settings where logo_key is not null")
        val icons = keys("select icon_key as key from projects where icon_key is not null")
        val own = setOf("users" to "avatar_key", "instance_settings" to "logo_key", "projects" to "icon_key")
        val contentImages = mutableSetOf<String>()
        // A file placed in a document is named by its address there (D-182), not by a column of its own.
        val blockFiles = mutableSetOf<String>()
        val textTypes = "c.data_type in ('text', 'character varying', 'json', 'jsonb')"
        for ((table, column) in columns("$textTypes and c.table_name not in ('audit_events', 'flyway_schema_history')")) {
            if (table to column in own) continue
            contentImages +=
                keys("select distinct m[1] as key from \"$table\", regexp_matches(\"$table\".\"$column\"::text, '$IMAGE_PATTERN', 'g') as m")
            blockFiles +=
                keys("select distinct m[1] as key from \"$table\", regexp_matches(\"$table\".\"$column\"::text, '$BLOCK_FILE_PATTERN', 'g') as m")
        }
        fileKeys += blockFiles
        return References(fileKeys, avatars, logos, icons, contentImages)
    }

    /** `table.column` of the tables (not views) of the schema whose columns satisfy [condition] (written by us, never input). */
    private suspend fun columns(condition: String): List<Pair<String, String>> =
        databaseClient
            .sql(
                """
                select c.table_name, c.column_name
                from information_schema.columns c
                join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
                where c.table_schema = current_schema() and t.table_type = 'BASE TABLE' and $condition
                order by c.table_name, c.column_name
                """.trimIndent(),
            ).map { row -> checkNotNull(row.get("table_name", String::class.java)) to checkNotNull(row.get("column_name", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    private suspend fun keys(sql: String): Set<String> =
        databaseClient
            .sql(sql)
            .map { row -> checkNotNull(row.get("key", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()

    companion object {
        private const val WEBP = ".webp"
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /** A picture's file name inside a text, as the SQL regular expression that finds it. */
        private const val IMAGE_PATTERN = "($UUID_PATTERN)\\.webp"
        private const val BLOCK_FILE_PATTERN = "/api/v1/editor-files/($UUID_PATTERN)"
        private val UUID_KEY = Regex("^$UUID_PATTERN$")
        private val IMAGE_KEY = Regex("^$UUID_PATTERN\\.webp$")

        /** How long a file in the storage that nothing uses is left alone: a picture is there before the text that holds it is saved. */
        val GRACE: Duration = Duration.ofDays(1)

        /** At this many pictures with none of them in use, an automatic clean-up assumes the database was misread. */
        private const val SUSPICIOUS_IMAGE_COUNT = 20
        private const val TOP_UPLOADERS = 5
        private const val LARGEST_FILES = 10
        private const val ORPHAN_SAMPLES = 20
        private const val MISSING_SAMPLES = 20
    }
}
