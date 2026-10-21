package com.flowbit.media.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path

/** Where uploaded files are kept (D-167). */
enum class StorageKind { LOCAL, S3 }

@ConfigurationProperties("flowbit.storage")
data class StorageProperties(
    /** Directory that holds uploaded files when they are kept on the server. Set with FLOWBIT_STORAGE_PATH. */
    val path: Path = Path.of("./data/uploads"),
    /** Largest upload accepted, before any processing. */
    val maxUploadSize: DataSize = DataSize.ofMegabytes(5),
    /**
     * `local` or `s3` (FLOWBIT_STORAGE_TYPE). Left out: S3 when a bucket is given (FLOWBIT_S3_BUCKET),
     * the storage folder otherwise.
     */
    val type: String = "",
    val s3: S3 = S3(),
) {
    /** FLOWBIT_S3_*: the bucket and how to reach it. Blank values are left out. */
    data class S3(
        val bucket: String = "",
        /** An AWS region such as `ap-northeast-2`. Left out for a store with its own endpoint. */
        val region: String = "",
        /** The address of an S3-compatible store (MinIO, …). Left out for AWS. */
        val endpoint: String = "",
        /** Without these two the SDK looks in its usual places: AWS_ACCESS_KEY_ID…, a role on the server. */
        val accessKey: String = "",
        val secretKey: String = "",
        /** A folder in the bucket to keep everything under. Left out: the top of the bucket. */
        val prefix: String = "",
        /** `true` or `false`; left out: path style when there is an endpoint, virtual-host style on AWS. */
        val pathStyle: String = "",
    )

    val kind: StorageKind =
        when (type.trim().lowercase()) {
            "" -> if (s3.bucket.isNotBlank()) StorageKind.S3 else StorageKind.LOCAL
            "local" -> StorageKind.LOCAL
            "s3" -> StorageKind.S3
            else -> throw IllegalArgumentException("FLOWBIT_STORAGE_TYPE must be local or s3 (it is \"$type\")")
        }

    init {
        // The .env file is read as a properties file, where a backslash is an escape character: a
        // Windows path written with single backslashes arrives without them (C:datauploads), which
        // is a folder under wherever the backend happens to run. Stop instead of storing files there.
        require(!MANGLED_WINDOWS_PATH.containsMatchIn(path.toString())) {
            "FLOWBIT_STORAGE_PATH lost its backslashes ($path). Write a Windows path with forward slashes: C:/data/uploads"
        }
        if (kind == StorageKind.S3) {
            require(s3.bucket.isNotBlank()) { "FLOWBIT_STORAGE_TYPE is s3, so FLOWBIT_S3_BUCKET must name the bucket" }
            require((s3.accessKey.isBlank()) == (s3.secretKey.isBlank())) {
                "FLOWBIT_S3_ACCESS_KEY and FLOWBIT_S3_SECRET_KEY go together: give both, or neither to use the server's own credentials"
            }
            require(s3.pathStyle.isBlank() || s3.pathStyle.trim().lowercase() in setOf("true", "false")) {
                "FLOWBIT_S3_PATH_STYLE must be true or false (it is \"${s3.pathStyle}\")"
            }
        }
    }

    private companion object {
        val MANGLED_WINDOWS_PATH = Regex("""^[A-Za-z]:[^/\\]""")
    }
}
