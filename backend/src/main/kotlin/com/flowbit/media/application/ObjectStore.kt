package com.flowbit.media.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** The kinds of thing kept: attached files as they came in, processed images, and the uploads those images were made from. */
enum class StorageArea(
    val folder: String,
) {
    FILES("files"),
    IMAGES("images"),
    ORIGINALS("originals"),
}

/**
 * Where the bytes of uploaded files are kept: a folder on the server or an S3 bucket (D-167). The
 * keys are made by [FileStorage] and [ImageStorage] (a random UUID, never user input), which also
 * check them before they get here.
 */
interface ObjectStore {
    suspend fun put(
        area: StorageArea,
        key: String,
        bytes: ByteArray,
    )

    /** Fails when the store cannot be reached; the callers that must not fail the request catch it. */
    suspend fun delete(
        area: StorageArea,
        key: String,
    )

    /** What was stored under the key, or null when nothing is. */
    suspend fun get(
        area: StorageArea,
        key: String,
    ): Resource?
}

/** The storage folder (FLOWBIT_STORAGE_PATH): `files/`, `images/` and `originals/` under it. */
class LocalObjectStore(
    root: Path,
) : ObjectStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directories: Map<StorageArea, Path> =
        StorageArea.entries.associateWith { root.toAbsolutePath().normalize().resolve(it.folder) }

    init {
        directories.values.forEach(Files::createDirectories)
        log.info("Uploaded files are stored in {}", directories.getValue(StorageArea.FILES).parent)
    }

    override suspend fun put(
        area: StorageArea,
        key: String,
        bytes: ByteArray,
    ) {
        val directory = directories.getValue(area)
        withContext(Dispatchers.IO) {
            // Write under a temporary name first so a half-written file is never served.
            val temporary = Files.createTempFile(directory, "upload-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    override suspend fun delete(
        area: StorageArea,
        key: String,
    ) {
        val path = pathOf(area, key) ?: return
        withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
    }

    override suspend fun get(
        area: StorageArea,
        key: String,
    ): Resource? {
        val path = pathOf(area, key) ?: return null
        return withContext(Dispatchers.IO) { path.takeIf(Files::isRegularFile) }?.let(::FileSystemResource)
    }

    private fun pathOf(
        area: StorageArea,
        key: String,
    ): Path? {
        val directory = directories.getValue(area)
        return directory.resolve(key).normalize().takeIf { it.parent == directory }
    }
}

/**
 * An S3 bucket, or any store that speaks S3 (MinIO, Ceph, …; set the endpoint): objects are
 * `<prefix>/files/<key>`, `<prefix>/images/<key>` and `<prefix>/originals/<key>`. The files are served by this application, not
 * by the bucket, so the bucket can stay private. The SDK client is blocking: every call runs on
 * Dispatchers.IO, like the folder's file access. An upload is held in memory anyway (up to
 * FLOWBIT_MAX_UPLOAD_SIZE), and so is a download.
 */
class S3ObjectStore(
    private val client: S3Client,
    private val bucket: String,
    prefix: String,
) : ObjectStore,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    init {
        log.info("Uploaded files are stored in the S3 bucket {}{}", bucket, if (base.isEmpty()) "" else " under $base")
        // Said once, loudly, when the bucket cannot be reached; the application still starts (a store that is down
        // for a moment must not keep the whole application down), and every upload then fails with the reason.
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        } catch (e: Exception) {
            log.error("Cannot reach the S3 bucket {}: {}. Uploads and downloads of files will fail until that is fixed.", bucket, e.message)
        }
    }

    private fun objectKey(
        area: StorageArea,
        key: String,
    ) = "$base${area.folder}/$key"

    override suspend fun put(
        area: StorageArea,
        key: String,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(objectKey(area, key))
                    .contentLength(bytes.size.toLong())
                    .build()
            client.putObject(request, RequestBody.fromBytes(bytes))
        }
    }

    override suspend fun delete(
        area: StorageArea,
        key: String,
    ) {
        withContext(Dispatchers.IO) {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(objectKey(area, key))
                    .build(),
            )
        }
    }

    override suspend fun get(
        area: StorageArea,
        key: String,
    ): Resource? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(objectKey(area, key))
                        .build()
                ByteArrayResource(client.getObjectAsBytes(request).asByteArray())
            } catch (_: NoSuchKeyException) {
                null
            }
        }

    override fun close() {
        client.close()
    }

    companion object {
        /** Region for stores that are not AWS and do not care: the SDK wants one. */
        private const val DEFAULT_REGION = "us-east-1"
        private val API_CALL_TIMEOUT = Duration.ofSeconds(60)
        private val API_ATTEMPT_TIMEOUT = Duration.ofSeconds(20)

        fun create(s3: StorageProperties.S3): S3ObjectStore {
            val builder = S3Client.builder()
            builder.region(Region.of(s3.region.ifBlank { DEFAULT_REGION }))
            // Keys given here win; otherwise the SDK's usual places (AWS_ACCESS_KEY_ID…, a role on the server).
            builder.credentialsProvider(
                if (s3.accessKey.isNotBlank()) {
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey))
                } else {
                    DefaultCredentialsProvider.builder().build()
                },
            )
            if (s3.endpoint.isNotBlank()) builder.endpointOverride(URI.create(s3.endpoint))
            // A store with its own address is reached as address/bucket/key; AWS as bucket.s3.region.amazonaws.com/key.
            val pathStyle =
                s3.pathStyle
                    .trim()
                    .lowercase()
                    .let { if (it.isEmpty()) s3.endpoint.isNotBlank() else it == "true" }
            builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            // Without limits a store that is reached but never answers holds a thread of Dispatchers.IO for minutes (the SDK's
            // default is to wait on the socket for 30 seconds, three times). A file is at most FLOWBIT_MAX_UPLOAD_SIZE.
            builder.overrideConfiguration { it.apiCallTimeout(API_CALL_TIMEOUT).apiCallAttemptTimeout(API_ATTEMPT_TIMEOUT) }
            return S3ObjectStore(builder.build(), s3.bucket, s3.prefix)
        }
    }
}
