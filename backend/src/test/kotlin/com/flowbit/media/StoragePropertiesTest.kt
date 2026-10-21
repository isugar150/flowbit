package com.flowbit.media

import com.flowbit.media.application.StorageKind
import com.flowbit.media.application.StorageProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Which store is chosen from the settings (D-167). */
class StoragePropertiesTest {
    private fun s3(
        bucket: String = "flowbit-files",
        accessKey: String = "",
        secretKey: String = "",
        pathStyle: String = "",
    ) = StorageProperties.S3(bucket = bucket, accessKey = accessKey, secretKey = secretKey, pathStyle = pathStyle)

    @Test
    fun `아무것도 정하지 않으면 저장 폴더를 쓴다`() {
        assertEquals(StorageKind.LOCAL, StorageProperties().kind)
    }

    @Test
    fun `버킷을 적으면 따로 정하지 않아도 S3 를 쓴다`() {
        assertEquals(StorageKind.S3, StorageProperties(s3 = s3()).kind)
    }

    @Test
    fun `종류를 local 로 정하면 버킷이 있어도 저장 폴더를 쓴다`() {
        assertEquals(StorageKind.LOCAL, StorageProperties(type = " Local ", s3 = s3()).kind)
    }

    @Test
    fun `종류를 s3 로 정하면 버킷이 있어야 한다`() {
        assertEquals(StorageKind.S3, StorageProperties(type = "s3", s3 = s3()).kind)
        assertFailsWith<IllegalArgumentException> { StorageProperties(type = "s3") }
    }

    @Test
    fun `알 수 없는 종류와 짝이 안 맞는 접근 키와 이상한 주소 방식은 시작할 때 거부한다`() {
        assertFailsWith<IllegalArgumentException> { StorageProperties(type = "ftp") }
        assertFailsWith<IllegalArgumentException> { StorageProperties(s3 = s3(accessKey = "AKIA")) }
        assertFailsWith<IllegalArgumentException> { StorageProperties(s3 = s3(secretKey = "secret")) }
        assertFailsWith<IllegalArgumentException> { StorageProperties(s3 = s3(pathStyle = "maybe")) }
        assertEquals(StorageKind.S3, StorageProperties(s3 = s3(accessKey = "AKIA", secretKey = "secret", pathStyle = "TRUE")).kind)
    }
}
