package com.flowbit.media.application

import com.flowbit.common.error.ApiException
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/** How many files and how many bytes they take up. */
data class Tally(
    val count: Long = 0,
    val bytes: Long = 0,
) {
    operator fun plus(other: Tally) = Tally(count + other.count, bytes + other.bytes)

    /** One more file of this size. */
    fun with(size: Long) = Tally(count + 1, bytes + size)
}

/** A tally for each of the three areas of the storage. */
data class AreaTallies(
    val files: Tally = Tally(),
    val images: Tally = Tally(),
    val originals: Tally = Tally(),
) {
    val total: Tally
        get() = files + images + originals
}

/** What the files are used for, from what the database says (the files of the storage that are known to it). */
data class FileUses(
    val ticketAttachments: Tally,
    val ticketCommentFiles: Tally,
    val contentCommentFiles: Tally,
    /** Uploaded for a comment that has not been sent. */
    val pendingFiles: Tally,
    val avatars: Tally,
    val logo: Tally,
    val projectIcons: Tally,
    /** Pictures put into the text of tickets, documents, comments, goals and projects. */
    val contentImages: Tally,
    /** The attached files of what is marked deleted and waits for its time (D-132); already counted above. */
    val awaitingPurge: Tally,
)

data class Uploader(
    val userId: UUID,
    val name: String,
    val count: Long,
    val bytes: Long,
)

/** Where an attached file was put. */
enum class FileSource {
    TICKET_ATTACHMENT,
    TICKET_COMMENT,
    CONTENT_COMMENT,
}

data class LargeFile(
    val name: String,
    val bytes: Long,
    val uploader: String,
    val source: FileSource,
    val issueKey: String?,
    val createdAt: Instant,
)

data class OrphanSample(
    val area: StorageArea,
    val key: String,
    val bytes: Long,
    val modifiedAt: Instant,
    /** Old enough to be removed now. */
    val cleanable: Boolean,
)

enum class MissingKind {
    FILE,
    IMAGE,
}

/** Something the database points at that the storage does not have. */
data class MissingSample(
    val kind: MissingKind,
    val name: String?,
    val key: String,
    val source: FileSource?,
    val issueKey: String?,
)

data class MissingCounts(
    val files: Long,
    val images: Long,
)

data class Consistency(
    val graceHours: Long,
    /** Files in the storage that nothing in the database uses (D-171). */
    val orphans: AreaTallies,
    /** The orphans that are old enough to be removed. */
    val cleanable: Tally,
    /** The orphans too new to be removed yet. */
    val inGrace: Long,
    val orphanSamples: List<OrphanSample>,
    val missing: MissingCounts,
    val missingSamples: List<MissingSample>,
    /** Pictures in use whose original is not kept (uploaded before D-169). */
    val imagesWithoutOriginal: Long,
    /** Things in the storage whose names are not ours: counted, never removed. */
    val unknownObjects: Long,
)

data class StorageInspection(
    val checkedAt: Instant,
    val storage: StorageDescription,
    /** The largest file the server takes (FLOWBIT_MAX_UPLOAD_SIZE), so that the page can say where the limit is set. */
    val maxUploadBytes: Long,
    val areas: AreaTallies,
    val total: Tally,
    val uses: FileUses,
    val topUploaders: List<Uploader>,
    val largestFiles: List<LargeFile>,
    val consistency: Consistency,
)

/** What a clean-up removed. */
data class CleanupResult(
    val deleted: AreaTallies,
    val bytes: Long,
    val failed: Long,
)

class StorageUnavailableException(
    reason: String?,
    cause: Throwable? = null,
) : ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "저장소를 읽을 수 없습니다${reason?.let { ": $it" }.orEmpty()}") {
    init {
        cause?.let(::initCause)
    }
}
