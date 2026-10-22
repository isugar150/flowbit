package com.flowbit.media.application

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses where uploaded files are kept (D-167): the storage folder or an Amazon S3 bucket, as
 * `FLOWBIT_STORAGE_TYPE` says (D-181). Left out, a bucket in `FLOWBIT_S3_BUCKET` means S3 and none means the folder.
 * Chosen once, when the application starts; a changed setting needs a restart.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun objectStore(properties: StorageProperties): ObjectStore {
        val name = if (properties.kind == StorageKind.S3) "amazon-s3" else "local"
        if (properties.explicit) {
            log.info("File storage: {} (FLOWBIT_STORAGE_TYPE)", name)
        } else {
            log.info(
                "File storage: {}, worked out from FLOWBIT_S3_BUCKET being {}. Set FLOWBIT_STORAGE_TYPE to say it.",
                name,
                if (properties.kind == StorageKind.S3) "set" else "empty",
            )
        }
        // A bucket that is written down but not used is the setting most likely to be forgotten about.
        if (properties.kind == StorageKind.LOCAL && properties.s3.bucket.isNotBlank()) {
            log.warn("FLOWBIT_S3_BUCKET is set but FLOWBIT_STORAGE_TYPE is local: files go to {}, not to the bucket.", properties.path)
        }
        return when (properties.kind) {
            StorageKind.LOCAL -> LocalObjectStore(properties.path)
            StorageKind.S3 -> S3ObjectStore.create(properties.s3)
        }
    }
}
