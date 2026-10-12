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
)
