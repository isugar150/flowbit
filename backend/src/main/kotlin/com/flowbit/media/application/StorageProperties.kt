package com.flowbit.media.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path

@ConfigurationProperties("flowbit.storage")
data class StorageProperties(
    /** Directory that holds uploaded files. Set with FLOWBIT_STORAGE_PATH. */
    val path: Path = Path.of("./data/uploads"),
    /** Largest upload accepted, before any processing. */
    val maxUploadSize: DataSize = DataSize.ofMegabytes(5),
) {
    init {
        // The .env file is read as a properties file, where a backslash is an escape character: a
        // Windows path written with single backslashes arrives without them (C:datauploads), which
        // is a folder under wherever the backend happens to run. Stop instead of storing files there.
        require(!MANGLED_WINDOWS_PATH.containsMatchIn(path.toString())) {
            "FLOWBIT_STORAGE_PATH lost its backslashes ($path). Write a Windows path with forward slashes: C:/data/uploads"
        }
    }

    private companion object {
        val MANGLED_WINDOWS_PATH = Regex("""^[A-Za-z]:[^/\\]""")
    }
}
