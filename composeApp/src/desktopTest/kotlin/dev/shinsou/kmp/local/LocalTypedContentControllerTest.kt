package dev.shinsou.kmp.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.reader.ImageRenderPageFactory
import dev.shinsou.kmp.reader.ImageSequenceNavigation
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LocalTypedContentControllerTest {
    @Test
    fun imageImportReadsOffCallerAndStopsAtTheNextChunkAfterCancellation() = runTest {
        val database = Files.createTempFile("shinsou-local-image-cancel", ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
        val foundation = ContentFoundationRuntime(
            driver,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        try {
            val manager = LocalContentManager(
                repository = ShinsouRepository(),
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = foundation,
            )
            val enteredRead = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val readCalls = AtomicInteger()
            val readThread = AtomicReference<Thread>()
            val callerThread = Thread.currentThread()
            val source = object : ImportedDocumentSource {
                override val byteSize: Long = (128 * 1024).toLong()

                override fun read(offset: Long, byteCount: Int): ByteArray {
                    readCalls.incrementAndGet()
                    readThread.compareAndSet(null, Thread.currentThread())
                    enteredRead.countDown()
                    check(releaseRead.await(5, TimeUnit.SECONDS)) { "Timed out releasing image source read" }
                    return ByteArray(byteCount)
                }
            }
            val importJob = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.importLocalDocuments(listOf(ImportedDocument("cancelled.png", source)))
            }
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS), "Image import never reached its source")

            importJob.cancel(CancellationException("image import screen left"))
            releaseRead.countDown()
            importJob.join()

            assertTrue(importJob.isCancelled)
            assertEquals(1, readCalls.get(), "Cancellation must stop before the second 64 KiB image read")
            assertNotEquals(callerThread, readThread.get(), "Image acquisition must not block the UI caller")
            assertTrue(foundation.publications.all().isEmpty())
            assertEquals(0, foundation.blobStore.count)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
        } finally {
            foundation.close()
            driver.close()
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun randomAccessEpubImportRunsOffCallerAndStopsAtTheNextChunkAfterCancellation() = runTest {
        val database = Files.createTempFile("shinsou-local-epub-cancel", ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
        val foundation = ContentFoundationRuntime(
            driver,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        try {
            val manager = LocalContentManager(
                repository = ShinsouRepository(),
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = foundation,
                epubArchiveExtractor = EpubArchiveExtractor { _, _ ->
                    error("EPUB extraction must be cancelled during source materialization")
                },
            )
            val enteredRead = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val readCalls = AtomicInteger()
            val maximumReadBytes = AtomicInteger()
            val readThread = AtomicReference<Thread>()
            val callerThread = Thread.currentThread()
            val source = object : ImportedDocumentSource {
                override val byteSize: Long = (128 * 1024).toLong()

                override fun read(offset: Long, byteCount: Int): ByteArray {
                    val call = readCalls.incrementAndGet()
                    maximumReadBytes.updateAndGet { previous -> maxOf(previous, byteCount) }
                    readThread.compareAndSet(null, Thread.currentThread())
                    if (call == 3) {
                        enteredRead.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS)) { "Timed out releasing EPUB source read" }
                    }
                    return ByteArray(byteCount)
                }
            }
            val importJob = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.importLocalDocuments(listOf(ImportedDocument("cancelled.epub", source)))
            }
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS), "EPUB import never reached its source")

            importJob.cancel(CancellationException("reader/import screen left"))
            releaseRead.countDown()
            importJob.join()

            assertTrue(importJob.isCancelled)
            assertEquals(3, readCalls.get(), "Cancellation must stop before the second extractor read chunk")
            assertTrue(
                maximumReadBytes.get() <= 64 * 1024,
                "Neither digest nor an extractor's large read may reach the picker source unchunked",
            )
            assertNotEquals(callerThread, readThread.get(), "Typed acquisition must not block the UI caller")
            assertTrue(foundation.publications.all().isEmpty())
            assertEquals(0, foundation.blobStore.count)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
        } finally {
            foundation.close()
            driver.close()
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun txtImportCreatesTypedReaderSessionAndSurvivesSqlAndSnapshotRestart() = runTest {
        val database = Files.createTempFile("shinsou-local-text", ".sqlite")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
            val firstFoundation = ContentFoundationRuntime(
                firstDriver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val repository = ShinsouRepository()
            val manager = LocalContentManager(
                repository = repository,
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = firstFoundation,
                now = { 12_345 },
            )
            val source = "# 第一章\n\n第一段 😀\n\n第二段".encodeToByteArray()
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("小說.txt", source)),
            ).single()

            val firstSession = assertNotNull(
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession,
            )
            assertEquals(source.decodeToString(), firstSession.canonicalText)
            assertIs<ContentRepresentation.PlainText>(firstSession.content.representation)
            assertTrue(firstFoundation.transactions.pendingOutbox().isNotEmpty())
            assertTrue(
                firstFoundation.transactions.pendingBlobSyncJobs().isEmpty(),
                "A normal local import must not imply consent to upload its body",
            )
            manager.importLocalDocuments(
                documents = listOf(ImportedDocument("小說.txt", source)),
                syncContentBodies = true,
            )
            assertEquals(1, firstFoundation.transactions.pendingBlobSyncJobs().size)
            val exportedSnapshot = repository.exportSnapshot()
            firstDriver.close()

            val secondDriver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
            val secondFoundation = ContentFoundationRuntime(
                secondDriver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val restoredRepository = ShinsouRepository(ShinsouRepository.decodeSnapshot(exportedSnapshot))
            val restoredManager = LocalContentManager(
                repository = restoredRepository,
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = secondFoundation,
            )
            val restored = assertNotNull(
                restoredManager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession,
            )
            assertEquals(source.decodeToString(), restored.canonicalText)
            assertNotNull(secondFoundation.rightsGrants.find(restored.access.grantReference!!))
            secondDriver.close()
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun imageImportUsesDurableImageSequenceAndUnifiedLazyReaderAfterRestart() = runTest {
        val database = Files.createTempFile("shinsou-local-images", ".sqlite")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
            val firstFoundation = ContentFoundationRuntime(
                firstDriver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val repository = ShinsouRepository()
            val manager = LocalContentManager(
                repository = repository,
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = firstFoundation,
                now = { 12_345 },
            )
            val first = byteArrayOf(1, 2, 3, 4)
            val second = byteArrayOf(5, 6, 7)
            val imported = manager.importLocalDocuments(
                documents = listOf(
                    ImportedDocument("002.png", second),
                    ImportedDocument("001.jpg", first),
                ),
                syncContentBodies = true,
            ).single()

            val initial = assertNotNull(
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession,
            )
            assertIs<ContentRepresentation.ImageSequence>(initial.content.representation)
            assertEquals(2, firstFoundation.transactions.pendingBlobSyncJobs().size)
            val exportedSnapshot = repository.exportSnapshot()
            firstDriver.close()

            val secondDriver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
            val secondFoundation = ContentFoundationRuntime(
                secondDriver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val restoredManager = LocalContentManager(
                repository = ShinsouRepository(ShinsouRepository.decodeSnapshot(exportedSnapshot)),
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = secondFoundation,
            )
            val restored = assertNotNull(
                restoredManager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession,
            )
            val navigation = assertIs<ImageSequenceNavigation>(restored.content.navigation)
            val loader = ImageRenderPageFactory(secondFoundation.blobStore)
            assertContentEquals(first, loader.load(navigation, 0).bytes)
            assertContentEquals(second, loader.load(navigation, 1).bytes)
            secondDriver.close()
        } finally {
            Files.deleteIfExists(database)
        }
    }
}

private class EmptyLocalFileSystem : AppFileSystem {
    override suspend fun write(relativePath: String, bytes: ByteArray) = Unit
    override suspend fun read(relativePath: String): ByteArray? = null
    override suspend fun exists(relativePath: String): Boolean = false
    override suspend fun delete(relativePath: String): Boolean = false
    override suspend fun deleteTree(relativeDirectory: String): Boolean = false
    override suspend fun list(relativeDirectory: String): List<String> = emptyList()
    override fun uri(relativePath: String): String = "memory://$relativePath"
}
