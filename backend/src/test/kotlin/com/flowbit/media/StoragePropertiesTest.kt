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
    fun `종류를 amazon-s3 로 정하면 버킷이 있어야 한다`() {
        assertEquals(StorageKind.S3, StorageProperties(type = "amazon-s3", s3 = s3()).kind)
        assertFailsWith<IllegalArgumentException> { StorageProperties(type = "amazon-s3") }
    }

    @Test
    fun `amazon-s3 는 대소문자와 구분 기호가 달라도 s3 도 같은 뜻이다`() {
        for (spelling in listOf("s3", "S3", " Amazon-S3 ", "amazon_s3", "Amazon S3", "AMAZONS3")) {
            assertEquals(StorageKind.S3, StorageProperties(type = spelling, s3 = s3()).kind, spelling)
        }
        assertFailsWith<IllegalArgumentException> { StorageProperties(type = "s3") }
    }

    @Test
    fun `종류를 적었는지 버킷으로 알아냈는지 구분한다`() {
        assertEquals(true, StorageProperties(type = "local").explicit)
        assertEquals(false, StorageProperties(s3 = s3()).explicit)
        assertEquals(false, StorageProperties(type = "  ").explicit)
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
