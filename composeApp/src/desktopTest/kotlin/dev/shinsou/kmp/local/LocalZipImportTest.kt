package dev.shinsou.kmp.local

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.DesktopAppFileSystem
import dev.shinsou.kmp.ui.ImportedDocument
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class LocalZipImportTest {
    @Test
    fun cbzUsesOkioZipFileSystemAndNaturalPageOrder() = runTest {
        val root = Files.createTempDirectory("shinsou-local-zip-test")
        try {
            val files = DesktopAppFileSystem(root)
            val repository = ShinsouRepository()
            val manager = LocalContentManager(repository, files, now = { 4_000 })
            val archive = zip(
                "pages/page10.jpg" to byteArrayOf(10),
                "pages/page2.png" to byteArrayOf(2),
                "pages/page1.webp" to byteArrayOf(1),
                "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
                "__MACOSX/._page1.jpg" to byteArrayOf(99),
            )

            val result = manager.importLocalDocuments(listOf(ImportedDocument("Volume 1.cbz", archive))).single()
            val paths = files.list("local/${result.mangaId}/${result.chapterId}")
                .filter(::isSupportedLocalImage)
                .let(::naturalSortedPaths)

            assertEquals(3, result.pageCount)
            assertContentEquals(byteArrayOf(1), files.read(paths[0])!!)
            assertContentEquals(byteArrayOf(2), files.read(paths[1])!!)
            assertContentEquals(byteArrayOf(10), files.read(paths[2])!!)
            assertEquals(3, manager.loadReaderChapter(result.mangaId, result.chapterId).pages.size)
        } finally {
            root.deleteRecursivelyForTest()
        }
    }

    @Test
    fun archiveTraversalIsRejectedBeforeExtraction() = runTest {
        val root = Files.createTempDirectory("shinsou-local-zip-traversal-test")
        try {
            val manager = LocalContentManager(ShinsouRepository(), DesktopAppFileSystem(root), now = { 5_000 })
            val archive = zip("../escape.jpg" to byteArrayOf(1, 2, 3))

            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(listOf(ImportedDocument("unsafe.cbz", archive)))
            }
            assertFalse(Files.exists(root.parent.resolve("escape.jpg")))
        } finally {
            root.deleteRecursivelyForTest()
        }
    }
}

private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, contents) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(contents)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

private fun Path.deleteRecursivelyForTest() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
