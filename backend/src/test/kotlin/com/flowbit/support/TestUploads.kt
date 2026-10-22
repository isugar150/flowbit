package com.flowbit.support

import com.flowbit.media.application.StorageKeys
import java.nio.file.Files
import java.nio.file.Path

/**
 * The folder the tests' uploads go to (`flowbit.storage.path` in [IntegrationTest]) and what is in
 * it. A file is kept in a folder per day inside its area (`files/2026/10/22/<key>`, D-182), so the
 * tests ask this instead of listing an area's own folder.
 */
object TestUploads {
    private val root: Path = Path.of("build/test-uploads")

    /** `files`, `images` or `originals`. */
    fun folder(area: String): Path = root.resolve(area)

    /** Every file kept in the area, in its day folders or directly in it (older and stray ones). Folders are not listed. */
    fun filesIn(area: String): List<Path> {
        val directory = folder(area)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { stream -> stream.filter(Files::isRegularFile).toList() }
    }

    /** Where the storage keeps the object with this key, which may or may not be there. */
    fun pathOf(
        area: String,
        key: String,
    ): Path = folder(area).resolve(StorageKeys.pathOf(key))
}
