package dev.shinsou.kmp.local

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.ImportedDocument
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalContentManagerTest {
    @Test
    fun multipleImagesBecomeOneSourceZeroMangaAndSurviveSnapshotReload() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 1_000 })

        val imported = manager.importLocalDocuments(
            listOf(
                ImportedDocument("Series 10.jpg", byteArrayOf(10)),
                ImportedDocument("Series 2.png", byteArrayOf(2)),
                ImportedDocument("Series 1.webp", byteArrayOf(1)),
            ),
        ).single()

        val manga = repository.manga(imported.mangaId)!!
        assertEquals(LOCAL_SOURCE_ID, manga.source)
        assertEquals("Series", manga.title)
        assertTrue(manga.favorite)
        assertEquals(3, imported.pageCount)
        val storedPages = files.list("local/${imported.mangaId}/${imported.chapterId}")
            .filter(::isSupportedLocalImage)
            .let(::naturalSortedPaths)
        assertContentEquals(byteArrayOf(1), files.read(storedPages[0])!!)
        assertContentEquals(byteArrayOf(2), files.read(storedPages[1])!!)
        assertContentEquals(byteArrayOf(10), files.read(storedPages[2])!!)
        assertEquals(
            "atomic:local/${imported.mangaId}/${imported.chapterId}/${LocalContentManifests.FILE_NAME}",
            files.operations.last(),
        )
        assertNull(manager.resolveChapterOriginalUrl(imported.mangaId, imported.chapterId))

        val restored = ShinsouRepository(ShinsouRepository.decodeSnapshot(repository.exportSnapshot()))
        val restoredManager = LocalContentManager(restored, files)
        val reader = restoredManager.loadReaderChapter(imported.mangaId, imported.chapterId)
        assertEquals(3, reader.pages.size)
        assertTrue(reader.pages.all { it.local && it.imageUrl.startsWith("memory://") })
    }

    @Test
    fun localPagesWithoutManifestAreNeverAccepted() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 1_500 })
        val imported = manager.importLocalDocuments(
            listOf(ImportedDocument("Page.jpg", byteArrayOf(1, 2, 3))),
        ).single()
        val directory = "local/${imported.mangaId}/${imported.chapterId}"
        files.delete("$directory/${LocalContentManifests.FILE_NAME}")

        assertTrue(files.exists("$directory/page-000001.jpg"))
        assertFailsWith<LocalContentUnavailableException> {
            manager.loadReaderChapter(imported.mangaId, imported.chapterId)
        }
    }

    @Test
    fun corruptManifestCannotNameUncontrolledPages() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 1_600 })
        val imported = manager.importLocalDocuments(
            listOf(ImportedDocument("Page.png", byteArrayOf(4, 5, 6))),
        ).single()
        val manifestPath =
            "local/${imported.mangaId}/${imported.chapterId}/${LocalContentManifests.FILE_NAME}"
        files.write(
            manifestPath,
            """{"version":2,"pageCount":1,"pages":["../page-000001.png"]}""".encodeToByteArray(),
        )

        assertFailsWith<LocalContentUnavailableException> {
            manager.loadReaderChapter(imported.mangaId, imported.chapterId)
        }
    }

    @Test
    fun validManifestDoesNotAcceptPartialPageSet() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 1_700 })
        val imported = manager.importLocalDocuments(
            listOf(
                ImportedDocument("Page 1.jpg", byteArrayOf(1)),
                ImportedDocument("Page 2.png", byteArrayOf(2)),
            ),
        ).single()
        val directory = "local/${imported.mangaId}/${imported.chapterId}"
        files.delete("$directory/page-000002.png")

        assertTrue(files.exists("$directory/${LocalContentManifests.FILE_NAME}"))
        assertFailsWith<LocalContentUnavailableException> {
            manager.loadReaderChapter(imported.mangaId, imported.chapterId)
        }
    }

    @Test
    fun completeCanonicalLegacyManifestIsAtomicallyMigrated() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 1_800 })
        val imported = manager.importLocalDocuments(
            listOf(
                ImportedDocument("Page 1.jpg", byteArrayOf(1)),
                ImportedDocument("Page 2.png", byteArrayOf(2)),
            ),
        ).single()
        val manifestPath =
            "local/${imported.mangaId}/${imported.chapterId}/${LocalContentManifests.FILE_NAME}"
        files.write(manifestPath, "version=1\npageCount=2\n".encodeToByteArray())
        val atomicWritesBeforeLoad = files.operations.count { it == "atomic:$manifestPath" }

        assertEquals(2, manager.loadReaderChapter(imported.mangaId, imported.chapterId).pages.size)
        assertEquals(
            atomicWritesBeforeLoad + 1,
            files.operations.count { it == "atomic:$manifestPath" },
        )
        assertTrue(files.read(manifestPath)!!.decodeToString().contains("\"version\":2"))
    }

    @Test
    fun restoredLocalRecordReportsMissingPageFilesClearly() = runTest {
        val files = LocalMemoryFileSystem()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, files, now = { 2_000 })
        val imported = manager.importLocalDocuments(
            listOf(ImportedDocument("Only Page.jpg", byteArrayOf(1, 2, 3))),
        ).single()
        files.deleteTree("local/${imported.mangaId}/${imported.chapterId}")

        val error = assertFailsWith<LocalContentUnavailableException> {
            manager.loadReaderChapter(imported.mangaId, imported.chapterId)
        }
        assertTrue(error.message.orEmpty().contains("missing or incomplete"))
        assertTrue(error.message.orEmpty().contains("Re-import"))
    }

    @Test
    fun refreshDelegationFiltersOutLocalMangaIds() = runTest {
        val remote = RecordingContentCallbacks()
        val repository = ShinsouRepository()
        val manager = LocalContentManager(repository, LocalMemoryFileSystem(), remote, now = { 3_000 })
        val local = manager.importLocalDocuments(
            listOf(ImportedDocument("Local.jpg", byteArrayOf(1))),
        ).single()
        val remoteManga = repository.upsertManga(
            dev.shinsou.kmp.domain.model.Manga(source = 99, url = "/remote", title = "Remote"),
        )

        manager.refreshLibrary(setOf(local.mangaId, remoteManga.id))
        manager.refreshManga(local.mangaId)

        assertEquals(setOf(remoteManga.id), remote.refreshedLibraryIds)
        assertFalse(local.mangaId in remote.refreshedMangaIds)
    }

    @Test
    fun archivePathsAndNaturalOrderingAreDeterministic() {
        assertFalse(isSafeArchiveEntryName("../cover.jpg"))
        assertFalse(isSafeArchiveEntryName("folder/../../cover.jpg"))
        assertFalse(isSafeArchiveEntryName("/absolute/cover.jpg"))
        assertFalse(isSafeArchiveEntryName("C:/private/cover.jpg"))
        assertFalse(isSafeArchiveEntryName("folder\\..\\cover.jpg"))
        assertTrue(isSafeArchiveEntryName("chapter 1/page 01.jpg"))
        assertEquals(
            listOf("page1.jpg", "page2.jpg", "page02.jpg", "page10.jpg"),
            naturalSortedPaths(listOf("page10.jpg", "page02.jpg", "page2.jpg", "page1.jpg")),
        )
    }
}

private class RecordingContentCallbacks : ContentCallbacks {
    var refreshedLibraryIds: Set<Long> = emptySet()
    val refreshedMangaIds = mutableListOf<Long>()

    override suspend fun refreshLibrary(mangaIds: Set<Long>) {
        refreshedLibraryIds = mangaIds
    }

    override suspend fun refreshManga(mangaId: Long) {
        refreshedMangaIds += mangaId
    }
}

private class LocalMemoryFileSystem : AppFileSystem {
    private val files = linkedMapOf<String, ByteArray>()
    val operations = mutableListOf<String>()

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        operations += "write:$relativePath"
        files[relativePath] = bytes.copyOf()
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray) {
        operations += "atomic:$relativePath"
        files[relativePath] = bytes.copyOf()
    }

    override suspend fun read(relativePath: String): ByteArray? = files[relativePath]?.copyOf()

    override suspend fun exists(relativePath: String): Boolean = relativePath in files

    override suspend fun delete(relativePath: String): Boolean = files.remove(relativePath) != null

    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        val matching = files.keys.filter { it.startsWith(prefix) }
        matching.forEach(files::remove)
        return matching.isNotEmpty()
    }

    override suspend fun list(relativeDirectory: String): List<String> {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
    }

    override fun uri(relativePath: String): String = "memory://$relativePath"
}
