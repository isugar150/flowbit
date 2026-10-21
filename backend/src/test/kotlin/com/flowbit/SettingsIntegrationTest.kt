package com.flowbit

import com.flowbit.media.application.ImageDimensions
import com.flowbit.support.ApiClient
import com.flowbit.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct-horse-battery"

class SettingsIntegrationTest : IntegrationTest() {
    private lateinit var admin: ApiClient

    @BeforeEach
    fun signInAsAdmin() {
        (storedFiles() + storedOriginals()).forEach(Files::delete)
        admin = client().also { it.setup("admin", PASSWORD) }
    }

    @Test
    fun `이름을 바꾸면 내 정보에 반영되고 비우면 지워진다`() {
        val updated = admin.patch("/api/v1/me", """{"displayName":"  김지민 "}""")

        assertEquals(200, updated.status)
        assertEquals("김지민", updated.json()["displayName"])
        assertEquals("김지민", admin.me().json()["displayName"])

        assertNull(admin.patch("/api/v1/me", """{"displayName":"  "}""").json()["displayName"])
    }

    @Test
    fun `투어를 마치면 시각이 남고 다시 마쳐도 처음 시각 그대로다`() {
        assertNull(admin.me().json()["tourCompletedAt"])

        val first = admin.post("/api/v1/me/tour/complete")

        assertEquals(200, first.status)
        val completedAt = assertNotNull(first.json()["tourCompletedAt"])
        assertEquals(completedAt, admin.post("/api/v1/me/tour/complete").json()["tourCompletedAt"])
        assertEquals(completedAt, admin.me().json()["tourCompletedAt"])
        assertEquals(401, client().post("/api/v1/me/tour/complete").status)
    }

    @Test
    fun `50자를 넘는 이름은 거부한다`() {
        val response = admin.patch("/api/v1/me", """{"displayName":"${"가".repeat(51)}"}""")

        assertEquals(422, response.status)
        assertEquals("VALIDATION_FAILED", response.json()["code"])
    }

    @Test
    fun `프로필 이미지를 올리면 256px 정사각형 WebP 로 바뀌어 저장되고 올린 원본도 그대로 저장된다`() {
        val upload = image("png", 1200, 800)
        val response = admin.putFile("/api/v1/me/avatar", "photo.png", "image/png", upload)

        assertEquals(200, response.status)
        val avatarUrl = assertNotNull(response.json()["avatarUrl"] as String?)
        assertEquals(avatarUrl, admin.me().json()["avatarUrl"])

        val file = admin.get(avatarUrl)
        assertEquals(200, file.status)
        assertEquals("image/webp", file.header("Content-Type"))
        assertTrue(file.header("Cache-Control").orEmpty().contains("immutable"))
        assertEquals(ImageDimensions(256, 256), ImageDimensions.read(file.bytes))
        assertEquals(1, storedFiles().size)
        // The upload is kept byte for byte, under the same id as the WebP (D-169).
        val original = storedOriginals().single()
        val webpName = storedFiles().single().fileName.toString()
        assertEquals(webpName.removeSuffix(".webp"), original.fileName.toString())
        assertContentEquals(upload, Files.readAllBytes(original))
    }

    @Test
    fun `JPEG 와 WebP 도 올릴 수 있다`() {
        val jpeg = admin.putFile("/api/v1/me/avatar", "photo.jpg", "image/jpeg", image("jpg", 640, 480))
        assertEquals(200, jpeg.status)

        // A WebP produced by the server itself is a valid WebP upload.
        val webp = admin.get(jpeg.json()["avatarUrl"] as String).bytes
        assertEquals(200, admin.putFile("/api/v1/me/avatar", "photo.webp", "image/webp", webp).status)
    }

    @Test
    fun `프로필 이미지를 바꾸면 이전 파일은 지워지고 주소가 바뀐다`() {
        val first = admin.putFile("/api/v1/me/avatar", "a.png", "image/png", image("png", 300, 300)).json()["avatarUrl"] as String
        val second = admin.putFile("/api/v1/me/avatar", "b.png", "image/png", image("png", 400, 400)).json()["avatarUrl"] as String

        assertNotEquals(first, second)
        assertEquals(404, admin.get(first).status)
        assertEquals(200, admin.get(second).status)
        assertEquals(1, storedFiles().size)
        assertEquals(1, storedOriginals().size)
    }

    @Test
    fun `프로필 이미지를 삭제하면 파일도 지워진다`() {
        val url = admin.putFile("/api/v1/me/avatar", "a.png", "image/png", image("png", 300, 300)).json()["avatarUrl"] as String

        val response = admin.delete("/api/v1/me/avatar")

        assertEquals(200, response.status)
        assertNull(response.json()["avatarUrl"])
        assertEquals(404, admin.get(url).status)
        assertTrue(storedFiles().isEmpty())
        assertTrue(storedOriginals().isEmpty())
    }

    @Test
    fun `이미지가 아닌 파일은 거부하고 아무것도 저장하지 않는다`() {
        val response = admin.putFile("/api/v1/me/avatar", "note.png", "image/png", "이건 그림이 아니다".toByteArray())

        assertEquals(422, response.status)
        assertEquals("UNSUPPORTED_IMAGE", response.json()["code"])
        assertNull(admin.me().json()["avatarUrl"])
        assertTrue(storedFiles().isEmpty())
        assertTrue(storedOriginals().isEmpty())
    }

    @Test
    fun `5MB 를 넘는 파일은 거부한다`() {
        val response = admin.putFile("/api/v1/me/avatar", "big.png", "image/png", ByteArray(6 * 1024 * 1024))

        assertEquals(413, response.status)
        assertEquals("FILE_TOO_LARGE", response.json()["code"])
        // The message names the limit that is set, so that a screen can show it as it is.
        assertTrue(response.body.contains("5MB"))
    }

    @Test
    fun `업로드 제한은 로그인하지 않아도 읽을 수 있고 설정한 값을 알려 준다`() {
        val response = client().get("/api/v1/upload-limit")

        assertEquals(200, response.status)
        assertEquals(5 * 1024 * 1024, (response.json()["maxUploadBytes"] as Number).toInt())
    }

    @Test
    fun `가로세로가 지나치게 큰 이미지는 풀지 않고 거부한다`() {
        // 8000 x 6000 = 48 megapixels; a flat colour compresses to a small file, so only the size check stops it.
        val response = admin.putFile("/api/v1/me/avatar", "huge.png", "image/png", image("png", 8000, 6000))

        assertEquals(422, response.status)
        assertEquals("IMAGE_DIMENSIONS_TOO_LARGE", response.json()["code"])
    }

    @Test
    fun `본문에 넣을 이미지를 올리면 1600px 안으로 줄인 WebP 의 주소를 받는다`() {
        val response = admin.postFile("/api/v1/images", "shot.png", "image/png", image("png", 3200, 1600))

        assertEquals(201, response.status)
        val file = admin.get(response.json()["url"] as String)
        assertEquals(200, file.status)
        assertEquals(ImageDimensions(1600, 800), ImageDimensions.read(file.bytes))
        assertEquals(401, client().postFile("/api/v1/images", "shot.png", "image/png", image("png", 64, 64)).status)
    }

    @Test
    fun `로그인하지 않으면 이미지를 올리거나 볼 수 없다`() {
        val url = admin.putFile("/api/v1/me/avatar", "a.png", "image/png", image("png", 300, 300)).json()["avatarUrl"] as String
        val anonymous = client()

        assertEquals(401, anonymous.get(url).status)
        assertEquals(401, anonymous.putFile("/api/v1/me/avatar", "a.png", "image/png", image("png", 300, 300)).status)
    }

    @Test
    fun `저장된 파일 주소에 다른 경로를 넣어도 폴더 밖 파일은 읽을 수 없다`() {
        // An encoded slash is refused outright by the HTTP firewall (400) before it reaches the controller.
        assertEquals(400, admin.get("/api/v1/files/..%2F..%2Fbuild.gradle.kts").status)
        assertEquals(404, admin.get("/api/v1/files/build.gradle.kts").status)
        assertEquals(404, admin.get("/api/v1/files/00000000-0000-0000-0000-000000000000.webp").status)
    }

    @Test
    fun `관리자는 워크스페이스 이름과 로고를 바꿀 수 있다`() {
        assertEquals("Flowbit", admin.get("/api/v1/workspace").json()["name"])

        val renamed = admin.patch("/api/v1/admin/workspace", """{"name":" 플로우빗 팀 "}""")
        assertEquals(200, renamed.status)
        assertEquals("플로우빗 팀", renamed.json()["name"])

        val withLogo = admin.putFile("/api/v1/admin/workspace/logo", "logo.png", "image/png", image("png", 1600, 400))
        assertEquals(200, withLogo.status)
        val logoUrl = assertNotNull(withLogo.json()["logoUrl"] as String?)
        // Logos keep their shape and are only shrunk to fit inside 512px.
        assertEquals(ImageDimensions(512, 128), ImageDimensions.read(admin.get(logoUrl).bytes))
        assertEquals(logoUrl, admin.get("/api/v1/workspace").json()["logoUrl"])

        assertNull(admin.delete("/api/v1/admin/workspace/logo").json()["logoUrl"])
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `작은 로고는 키우지 않는다`() {
        val logoUrl = admin.putFile("/api/v1/admin/workspace/logo", "logo.png", "image/png", image("png", 120, 40)).json()["logoUrl"] as String

        assertEquals(ImageDimensions(120, 40), ImageDimensions.read(admin.get(logoUrl).bytes))
    }

    @Test
    fun `워크스페이스 이름은 비울 수 없다`() {
        val response = admin.patch("/api/v1/admin/workspace", """{"name":"   "}""")

        assertEquals(422, response.status)
        assertEquals("Flowbit", admin.get("/api/v1/workspace").json()["name"])
    }

    @Test
    fun `관리자가 아닌 구성원은 워크스페이스를 볼 수는 있지만 바꿀 수 없다`() {
        // There is no member-creation API yet (M2); copy the admin row as a MEMBER with the same password.
        execute(
            """
            insert into users (id, login_id, password_hash, role, status)
            select '00000000-0000-7000-8000-000000000001', 'member', password_hash, 'MEMBER', 'ACTIVE' from users
            """.trimIndent(),
        )
        val member = client().also { assertEquals(200, it.login("member", PASSWORD).status) }

        assertEquals(200, member.get("/api/v1/workspace").status)

        val rename = member.patch("/api/v1/admin/workspace", """{"name":"내 것"}""")
        assertEquals(403, rename.status)
        assertEquals("FORBIDDEN", rename.json()["code"])
        assertEquals(403, member.putFile("/api/v1/admin/workspace/logo", "l.png", "image/png", image("png", 64, 64)).status)
        assertEquals(403, member.delete("/api/v1/admin/workspace/logo").status)
        assertEquals("Flowbit", admin.get("/api/v1/workspace").json()["name"])
        assertTrue(storedFiles().isEmpty())
    }

    private fun image(
        format: String,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(43, 99, 227)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    /** Everything in the test storage directory; it is emptied before each test. */
    private fun storedFiles(): List<Path> = filesIn(storage)

    /** The originals of the uploaded pictures, kept next to the WebP copies. */
    private fun storedOriginals(): List<Path> = filesIn(originals)

    private fun filesIn(directory: Path): List<Path> = if (Files.isDirectory(directory)) Files.list(directory).use { it.toList() } else emptyList()

    private companion object {
        val storage: Path = Path.of("build/test-uploads/images")
        val originals: Path = Path.of("build/test-uploads/originals")
    }
}
