package com.flowbit.media

import com.flowbit.media.application.FileStorage
import com.flowbit.media.application.ImageStorage
import com.flowbit.media.application.LocalObjectStore
import com.flowbit.media.application.S3ObjectStore
import com.flowbit.media.application.StorageArea
import com.flowbit.media.application.StorageProperties
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The storage folder keeps files as it always did (D-167). */
class LocalObjectStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `파일과 이미지를 넣고 읽고 지운다`() =
        runBlocking {
            val files = FileStorage(LocalObjectStore(root))
            val images = ImageStorage(LocalObjectStore(root))

            val fileKey = files.store("보고서".toByteArray())
            val imageKey = images.store(byteArrayOf(1, 2, 3))

            assertContentEquals("보고서".toByteArray(), files.find(fileKey)!!.inputStream.readBytes())
            assertContentEquals(byteArrayOf(1, 2, 3), images.find(imageKey)!!.inputStream.readBytes())
            assertEquals(
                1,
                root
                    .resolve("files")
                    .toFile()
                    .listFiles()!!
                    .size,
            )
            assertEquals(
                1,
                root
                    .resolve("images")
                    .toFile()
                    .listFiles()!!
                    .size,
            )

            files.delete(fileKey)
            images.delete(imageKey)
            assertNull(files.find(fileKey))
            assertNull(images.find(imageKey))
        }

    @Test
    fun `모양이 다른 키는 찾지도 지우지도 않는다`() =
        runBlocking {
            val files = FileStorage(LocalObjectStore(root))
            assertNull(files.find("../../etc/passwd"))
            files.delete("../outside")
        }
}

/** An S3-compatible store (MinIO) in a container: objects land under the prefix, and are read back and deleted (D-167). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ObjectStoreTest {
    private val minio = GenericContainer(DockerImageName.parse("minio/minio:latest"))
    private lateinit var admin: S3Client

    @BeforeAll
    fun start() {
        minio
            .withEnv("MINIO_ROOT_USER", "flowbit")
            .withEnv("MINIO_ROOT_PASSWORD", "flowbit-secret")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
        minio.start()
        admin =
            S3Client
                .builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("flowbit", "flowbit-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())
    }

    @AfterAll
    fun stop() {
        admin.close()
        minio.stop()
    }

    private fun endpoint() = "http://${minio.host}:${minio.getMappedPort(9000)}"

    private fun store(prefix: String) =
        S3ObjectStore.create(
            StorageProperties.S3(bucket = BUCKET, endpoint = endpoint(), accessKey = "flowbit", secretKey = "flowbit-secret", prefix = prefix),
        )

    @Test
    fun `파일과 이미지가 접두어 아래 files 와 images 에 쌓이고 읽고 지워진다`() =
        runBlocking {
            val objects = store("flowbit/")
            val files = FileStorage(objects)
            val images = ImageStorage(objects)

            val fileKey = files.store("보고서".toByteArray())
            val imageKey = images.store(byteArrayOf(9, 8, 7))

            assertContentEquals("보고서".toByteArray(), files.find(fileKey)!!.inputStream.readBytes())
            assertContentEquals(byteArrayOf(9, 8, 7), images.find(imageKey)!!.inputStream.readBytes())
            val keys =
                admin
                    .listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(BUCKET)
                            .prefix("flowbit/")
                            .build(),
                    ).contents()
                    .map { it.key() }
            assertEquals(setOf("flowbit/files/$fileKey", "flowbit/images/$imageKey"), keys.toSet())

            files.delete(fileKey)
            assertNull(files.find(fileKey))
            // Nothing stored under a key that was never used.
            assertNull(objects.get(StorageArea.FILES, UUID.randomUUID().toString()))
        }

    private companion object {
        const val BUCKET = "flowbit-test"
    }
}
