package com.flowbit.media.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses where uploaded files are kept (D-167): the storage folder, which is the default and how it has
 * always been, or an S3 bucket when `FLOWBIT_S3_BUCKET` (or `FLOWBIT_STORAGE_TYPE=s3`) says so.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {
    @Bean
    fun objectStore(properties: StorageProperties): ObjectStore =
        when (properties.kind) {
            StorageKind.LOCAL -> LocalObjectStore(properties.path)
            StorageKind.S3 -> S3ObjectStore.create(properties.s3)
        }
}
