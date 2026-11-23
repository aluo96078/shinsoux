package dev.shinsou.kmp.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.content.transaction.ContentCommitBatch
import dev.shinsou.kmp.content.transaction.ContentCommitSemantics
import dev.shinsou.kmp.content.transaction.ContentPublicationMutation
import dev.shinsou.kmp.content.transaction.ContentRightsGrantMutation
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.content.blob.ContentManifestOwner
import dev.shinsou.kmp.content.blob.ManifestAttachment
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.content.transaction.ContentSyncMode
import dev.shinsou.kmp.content.transaction.LegacyOutboxDraft
import dev.shinsou.kmp.data.SnapshotReplacementOrigin
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.reader.ImageRenderPageFactory
import dev.shinsou.kmp.domain.content.ImageSequenceNavigation
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentSource
import dev.shinsou.kmp.contract.LocalTextEncoding
import dev.shinsou.kmp.contract.LocalTextEncodingQuestion
import dev.shinsou.kmp.contract.KeepImportedTextEncoding
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
            val scopeRequests = mutableListOf<dev.shinsou.kmp.domain.model.SourceKey>()
            val manager = LocalContentManager(
                repository = repository,
                fileSystem = EmptyLocalFileSystem(),
                contentFoundation = firstFoundation,
                now = { 12_345 },
                remoteAssetScopeFor = { key -> scopeRequests += key; null },
            )
            val source = "# 第一章\n\n第一段 😀\n\n第二段".encodeToByteArray()
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("小說.txt", source)),
            ).single()

            val firstSession = assertNotNull(
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession,
            )
            assertEquals(source.decodeToString(), firstSession.canonicalText)
            // An import has no extension source: it never asks for (or gets) a network scope.
            assertTrue(scopeRequests.isEmpty())
            assertEquals(null, firstSession.remoteAssetScope)
            assertIs<ContentRepresentation.PlainText>(firstSession.content.representation)
            assertTrue(firstFoundation.transactions.pendingOutbox().isEmpty())
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
    fun aNovelWithMoreParagraphsThanAStoredManifestHoldsIsImportedAndRead() = runTest {
        withTypedManager { manager, _ ->
            // One block for each of these paragraphs would be a manifest too large to store.
            val source = buildString {
                repeat(120) { chapter ->
                    append("第").append(chapter + 1).append("章\r\n\r\n")
                    repeat(500) { paragraph -> append("段落 ").append(chapter).append('-').append(paragraph).append("\r\n\r\n") }
                }
            }.encodeToByteArray()
            val imported = manager.importLocalDocuments(listOf(ImportedDocument("長篇.txt", source))).single()

            val session = assertNotNull(manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession)
            val text = assertNotNull(session.canonicalText)
            val navigation = assertIs<dev.shinsou.kmp.domain.content.PlainTextNavigation>(session.content.navigation)
            assertTrue(navigation.itemCount in 20_000..36_000, "Got ${navigation.itemCount} blocks")
            // A position anywhere in the text still has a block to be kept by.
            val chapterStart = text.indexOf("第61章")
            val locator = navigation.locatorForOffset(chapterStart)
            assertEquals(chapterStart, navigation.locatorAt(assertNotNull(navigation.indexOf(locator))).offset)
            assertNotNull(navigation.indexOf(navigation.locatorForOffset(text.length)))
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

    @Test
    fun anUnclearLegacyTextAsksAndAnotherChoiceLaterReplacesTheEarlierTitle() = runTest {
        withTypedManager { manager, repository ->
            // Valid Big5 that also reads as GB18030 (获澳): too short for the guess to be trusted.
            val document = ImportedDocument("鳳凰.txt", "鳳凰".toByteArray(charset("Big5")))
            val questions = mutableListOf<LocalTextEncodingQuestion>()

            val big5 = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; LocalTextEncoding.BIG5 },
            ).single()
            assertEquals(
                setOf(LocalTextEncoding.GB18030, LocalTextEncoding.BIG5),
                questions.single().candidates.map { it.encoding }.toSet(),
            )
            assertTrue(questions.single().imported.isEmpty())
            assertEquals("鳳凰", manager.loadReaderChapter(big5.mangaId, big5.chapterId).typedSession?.canonicalText)

            val skipped = manager.importLocalDocuments(listOf(document), chooseTextEncoding = { questions += it; null })
            assertTrue(skipped.isEmpty())
            assertEquals(listOf(LocalTextEncoding.BIG5), questions.last().imported)
            assertNotNull(repository.manga(big5.mangaId), "skipping the file keeps the title it has")

            val gb = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { LocalTextEncoding.GB18030 },
            ).single()
            assertNotEquals(big5.mangaId, gb.mangaId)
            assertEquals("获澳", manager.loadReaderChapter(gb.mangaId, gb.chapterId).typedSession?.canonicalText)
            assertEquals(listOf(gb.mangaId), repository.library().map { it.manga.id }, "the other reading replaces it")
            // The replaced title left the library like a removed one: the startup repair keeps it out.
            assertEquals(false, repository.manga(big5.mangaId)?.favorite)
            assertEquals(LocalTypedProjectionRepairResult(0, 0), manager.repairTypedContentLegacyProjection())
            assertEquals(listOf(gb.mangaId), repository.library().map { it.manga.id })

            val big5Again = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; LocalTextEncoding.BIG5 },
            ).single()
            assertEquals(listOf(LocalTextEncoding.GB18030), questions.last().imported)
            assertEquals(big5.mangaId, big5Again.mangaId, "choosing the first reading again brings its title back")
            assertEquals(listOf(big5.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aLegacyTextThatIsAlsoValidUtf8CanBeImportedAndCorrectedEitherWay() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("小说.txt", "小说".toByteArray(charset("GBK")))
            val questions = mutableListOf<LocalTextEncodingQuestion>()
            val utf8 = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; LocalTextEncoding.UTF_8 },
            ).single()
            assertTrue(LocalTextEncoding.UTF_8 in questions.single().candidates.map { it.encoding })
            assertEquals(LocalTextEncoding.GB18030, questions.single().recommended)
            assertEquals("С˵", manager.loadReaderChapter(utf8.mangaId, utf8.chapterId).typedSession?.canonicalText)

            val gb = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; LocalTextEncoding.GB18030 },
            ).single()
            assertEquals(listOf(LocalTextEncoding.UTF_8), questions.last().imported)
            assertEquals("小说", manager.loadReaderChapter(gb.mangaId, gb.chapterId).typedSession?.canonicalText)
            assertEquals(listOf(gb.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aLegacyTextThatReadsAsPlainUtf8IsCorrectedByImportingItAgainAskingToChoose() = runTest {
        withTypedManager { manager, repository ->
            // GBK 小学 is valid UTF-8 in one script (Сѧ), so the import is sure of it and never asks.
            val document = ImportedDocument("小学.txt", "小学".toByteArray(charset("GBK")))
            val questions = mutableListOf<LocalTextEncodingQuestion>()
            val garbled = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; it.recommended },
            ).single()
            manager.importLocalDocuments(listOf(document), chooseTextEncoding = { questions += it; it.recommended })
            assertTrue(questions.isEmpty(), "importing it again the same way does not ask either")
            assertEquals("Сѧ", manager.loadReaderChapter(garbled.mangaId, garbled.chapterId).typedSession?.canonicalText)

            val corrected = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { questions += it; LocalTextEncoding.GB18030 },
                alwaysChooseTextEncoding = true,
            ).single()
            val question = questions.single()
            assertEquals(listOf(LocalTextEncoding.UTF_8), question.imported)
            assertEquals("小学", question.candidates.single { it.encoding == LocalTextEncoding.GB18030 }.preview)
            assertEquals("小学", manager.loadReaderChapter(corrected.mangaId, corrected.chapterId).typedSession?.canonicalText)
            assertEquals(listOf(corrected.mangaId), repository.library().map { it.manga.id }, "the garbled title is replaced")
        }
    }

    @Test
    fun anOrdinaryImportOfACorrectedTextKeepsTheCorrectedReadingAndItsShelves() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("小学.txt", "小学".toByteArray(charset("GBK")))
            val garbled = manager.importLocalDocuments(listOf(document)).single()
            val shelf = repository.upsertCategory(Category(name = "Shelf"))
            repository.setMangaCategories(garbled.mangaId, listOf(shelf.id))

            val corrected = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { LocalTextEncoding.GB18030 },
                alwaysChooseTextEncoding = true,
            ).single()
            assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(corrected.mangaId), "the corrected reading is shelved where it was")

            // Imported again the ordinary way, the file reads as Unicode: the library's reading is kept.
            val questions = mutableListOf<LocalTextEncodingQuestion>()
            val again = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { question -> questions += question; KeepImportedTextEncoding(question) },
            ).single()
            assertEquals(listOf(LocalTextEncoding.GB18030), questions.single().imported)
            assertEquals(corrected.mangaId, again.mangaId)
            assertEquals("小学", manager.loadReaderChapter(again.mangaId, again.chapterId).typedSession?.canonicalText)
            assertEquals(listOf(corrected.mangaId), repository.library().map { it.manga.id }, "the garbled title stays out")
            assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(corrected.mangaId))
        }
    }

    @Test
    fun anAbandonedImportGivesUpTheReceiptsOfTheBodiesItStaged() = runTest {
        withTypedManager { _, _, foundation ->
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })
            val prepared = controller.prepare(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray()))
            assertTrue(foundation.blobStore.pendingReceiptCount > 0)

            prepared.abandon()

            assertEquals(0, foundation.blobStore.pendingReceiptCount, "a retry can publish the same blobs again")
        }
    }

    @Test
    fun aBodyGivenBackToARestoredTitleKeepsItsDurableGrant() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            val grant = foundation.rightsGrants.all().single()
            foundation.restoreWithoutBodies(repository, publication, listOf(grant))
            // The title's grant is revoked meanwhile: an import of its file must not admit a new one over it.
            foundation.rightsAuthority.revoke(grant.grantId)
            val revision = foundation.rightsAuthority.policyRevision

            assertFailsWith<Exception> { manager.importLocalDocuments(listOf(document)) }
            assertEquals(revision, foundation.rightsAuthority.policyRevision, "nothing was admitted over the revoked grant")
            assertEquals(listOf(grant), foundation.rightsGrants.all())
        }
    }

    @Test
    fun aBodyThatCannotBeGivenBackLeavesNoPendingReceipt() = runTest {
        withTypedManager { manager, repository, foundation ->
            manager.importLocalDocuments(listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())))
            val publication = foundation.publications.all().single()
            val grant = foundation.rightsGrants.all().single()
            foundation.restoreWithoutBodies(repository, publication, listOf(grant))
            val bodyless = bodylessUnitOf(foundation)
            val manifest = assertNotNull(bodyless.unit.latestManifest)

            // The blob this device still holds is claimed, then the commit fails.
            assertFailsWith<IllegalStateException> {
                LocalPublicationBodies(foundation).restore(bodyless, manifest, grant) { error("commit fails") }
            }
            assertEquals(0, foundation.blobStore.pendingReceiptCount, "a later import can claim or publish the blob again")
        }
    }

    @Test
    fun aBodyGivenBackUnderTheRestoredTitlesOwnGrantDoesNotCommitTheImportsGrant() = runTest {
        withTypedManager { manager, repository, foundation ->
            manager.importLocalDocuments(listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())))
            val publication = foundation.publications.all().single()
            val restoredGrant = foundation.rightsGrants.all().single()
            foundation.restoreWithoutBodies(repository, publication, listOf(restoredGrant))
            // The import admitted a grant of its own before the restore brought the title's.
            val provisional = restoredGrant.copy(grantId = RightsGrantRef("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"))
            val bodyless = bodylessUnitOf(foundation)

            val used = LocalPublicationBodies(foundation).restore(
                bodyless,
                assertNotNull(bodyless.unit.latestManifest),
                provisional,
            ) { emptyList() }

            assertEquals(false, used, "the caller withdraws the unused grant")
            assertNull(foundation.rightsGrants.find(provisional.grantId), "no durable grant that nothing refers to")
            assertEquals(
                restoredGrant.grantId,
                foundation.publications.all().single().acquisitions.single().rightsGrantRef,
            )
        }
    }

    @Test
    fun anImportThatKeepsATitleRestoredWithItsBodyWithdrawsTheGrantItAdmitted() = runTest {
        withTypedManager { _, repository, foundation ->
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })
            val bytes = "Chapter 1\n\nText".encodeToByteArray()
            val racing = controller.prepare(ImportedDocument("story.txt", bytes))
            assertTrue(controller.prepare(ImportedDocument("story.txt", bytes)).commit())
            val publication = foundation.publications.all().single()
            val provisional = foundation.rightsGrants.all().single()
            // A restore brings the title back with its body, under a grant of its own.
            val own = provisional.copy(grantId = RightsGrantRef("0e0e0e0e-0e0e-4e0e-8e0e-0e0e0e0e0e0e"))
            val acquisition = publication.acquisitions.single()
            val unit = acquisition.units.single()
            val manifest = assertNotNull(unit.latestManifest)
            val owner = ContentManifestOwner(publication.key, acquisition.id, unit.key)
            repository.commitSnapshotMutationAtomically(repository.currentSnapshot, SnapshotReplacementOrigin.DIRECT) { _, _, journal ->
                journal()
                foundation.transactions.commit(
                    ContentCommitBatch<LegacyOutboxDraft>(
                        commitId = "test-restore-with-own-grant",
                        receipts = manifest.referencedBlobs.mapNotNull(foundation.blobStore::claimExistingVerified),
                        attachments = listOf(ManifestAttachment(owner, manifest)),
                        publications = listOf(
                            ContentPublicationMutation(
                                publication.copy(acquisitions = publication.acquisitions.map { it.copy(rightsGrantRef = own.grantId) }),
                            ),
                        ),
                        rightsGrants = listOf(ContentRightsGrantMutation(own)),
                        semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                    ),
                )
            }
            foundation.rightsAuthority.admit(own)
            // The racing import's grant (the derived id) is no longer durable: only the restored one is.
            assertEquals(listOf(own.grantId), foundation.rightsGrants.all().map { it.grantId })
            val revision = foundation.rightsAuthority.policyRevision

            assertTrue(racing.commit(), "the restored title is kept")
            racing.abandon()

            assertTrue(foundation.rightsAuthority.policyRevision > revision, "the unused provisional grant was revoked")
            assertNull(foundation.rightsGrants.find(provisional.grantId))
        }
    }

    @Test
    fun aRolledBackEncodingCorrectionStaysOutOfTheLibraryAfterStartupRepair() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("小学.txt", "小学".toByteArray(charset("GBK")))
            val garbled = manager.importLocalDocuments(listOf(document)).single()
            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(
                    // The correction commits, then the empty file fails the batch.
                    listOf(document, ImportedDocument("z.txt", ByteArray(0))),
                    chooseTextEncoding = { LocalTextEncoding.GB18030 },
                    alwaysChooseTextEncoding = true,
                )
            }
            assertEquals(listOf(garbled.mangaId), repository.library().map { it.manga.id })

            manager.repairTypedContentLegacyProjection()
            assertEquals(listOf(garbled.mangaId), repository.library().map { it.manga.id }, "the correction does not come back")
        }
    }

    @Test
    fun aReimportKeepsTheChaptersReadingStateAndSoDoesItsRollback() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            val imported = manager.importLocalDocuments(listOf(document)).single()
            repository.patchChapter(imported.chapterId, ChapterPatch(read = true, bookmark = true, lastPageRead = 7))

            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(listOf(document, ImportedDocument("z.txt", ByteArray(0))))
            }
            val chapter = assertNotNull(repository.chapter(imported.chapterId))
            assertEquals(Triple(true, true, 7), Triple(chapter.read, chapter.bookmark, chapter.lastPageRead))
            assertEquals(true, repository.manga(imported.mangaId)?.favorite)

            val again = manager.importLocalDocuments(listOf(document)).single()
            assertEquals(7, repository.chapter(again.chapterId)?.lastPageRead)
        }
    }

    @Test
    fun aBodyIsNotGivenBackUnderARestoredGrantThatDeniesStoringItOffline() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            val narrowed = foundation.rightsGrants.all().single().let { grant ->
                grant.copy(
                    grantId = RightsGrantRef("0d0d0d0d-0d0d-4d0d-8d0d-0d0d0d0d0d0d"),
                    allowedOperations = grant.allowedOperations - ContentOperation.OFFLINE_STORE,
                )
            }
            val restored = publication.copy(acquisitions = publication.acquisitions.map { it.copy(rightsGrantRef = narrowed.grantId) })
            foundation.restoreEmptyGraph(repository)

            var writes = 0
            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(
                    listOf(document),
                    writeLibrary = { write ->
                        // Staged under the import's own grant; then a restore brings the title back
                        // without its body, under a grant that does not allow storing it offline.
                        if (writes++ == 0) {
                            foundation.restoreWithoutBodies(repository, restored, listOf(narrowed))
                            foundation.rightsAuthority.admit(narrowed)
                        }
                        write()
                    },
                )
            }
            assertEquals(
                AcquisitionAvailability.PARTIAL,
                foundation.publications.all().single().acquisitions.single().availability,
            )
        }
    }

    @Test
    fun aReimportIsAuthorizedAgainstTheRestoredManifestExactly() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            val acquisition = publication.acquisitions.single()
            val unit = acquisition.units.single()
            val manifest = assertNotNull(unit.latestManifest)
            val grant = foundation.rightsGrants.all().single()
            fun scopedTo(manifestId: String) = grant.copy(
                scope = RightsScope(publication.key, acquisition.id, unit.key, manifestId, manifest.contentRevision),
            )

            // A grant for some other content of the title does not let this body be stored.
            val elsewhere = scopedTo("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b")
            foundation.restoreWithoutBodies(repository, publication, listOf(elsewhere))
            foundation.rightsAuthority.admit(elsewhere)
            assertFailsWith<LocalContentImportException> { manager.importLocalDocuments(listOf(document)) }

            // One scoped to the restored manifest itself does.
            val exact = scopedTo(manifest.manifestId)
            foundation.restoreWithoutBodies(repository, publication, listOf(exact))
            foundation.rightsAuthority.admit(exact)
            val imported = manager.importLocalDocuments(listOf(document)).single()
            assertEquals("Chapter 1\n\nText", manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession?.canonicalText)
        }
    }

    @Test
    fun theBodyOfATitleRestoredWithoutItIsCopiedBeforeTheCommit() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            foundation.restoreWithoutBodies(repository, publication, foundation.rightsGrants.all())
            val restoredBlobs = assertNotNull(publication.acquisitions.single().units.single().latestManifest).referencedBlobs
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })

            val prepared = controller.prepare(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray()))
            // Prepared off the repository lock: the restored blobs are held already.
            val held = foundation.blobStore.pendingReceipts().map { it.reference }
            assertTrue(held.containsAll(restoredBlobs), "copies made before the commit")

            assertTrue(prepared.commit())
            prepared.abandon()
            prepared.abandon()
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(
                AcquisitionAvailability.AVAILABLE,
                foundation.publications.all().single().acquisitions.single().availability,
            )
        }
    }

    @Test
    fun stagingIsAskedForTheImportsOwnAcquisitionWhateverTheGrantNames() = runTest {
        withTypedManager { manager, repository, foundation ->
            val bytes = "Chapter 1\n\nText".encodeToByteArray()
            manager.importLocalDocuments(listOf(ImportedDocument("story.txt", bytes)))
            val publication = foundation.publications.all().single()
            val acquisition = publication.acquisitions.single()
            val manifest = assertNotNull(acquisition.units.single().latestManifest)
            foundation.restoreWithoutBodies(repository, publication, foundation.rightsGrants.all())
            val rights = LocalImportRights(
                foundation,
                dev.shinsou.kmp.content.access.HostContentOperationGate(foundation.rightsAuthority) { 1 },
                dev.shinsou.kmp.content.acquisition.LocalAcquisitionIdentityDeriver(),
            )
            fun target(importId: String) = dev.shinsou.kmp.content.acquisition.LocalAcquisitionTarget(
                publicationKey = publication.key,
                publicationTitle = "story",
                stableImportId = importId,
                unitTitle = "story",
                contentRevision = 0,
                acquiredAtEpochMillis = 1,
            )

            // The restored title: its own acquisition, unit and manifest, exactly.
            val restored = rights.stagingScope(
                target("local-plain_text:sha256:${dev.shinsou.kmp.crypto.Sha256.hex(bytes)}"),
                dev.shinsou.kmp.content.acquisition.LocalContentFormat.PLAIN_TEXT,
            )
            assertEquals(acquisition.id, restored.acquisitionId)
            assertEquals(manifest.manifestId, restored.manifestId)
            assertEquals(manifest.contentRevision, restored.contentRevision)

            // Another file of no restored title: the acquisition it would have, nothing narrower.
            val fresh = rights.stagingScope(target("local-plain_text:sha256:other"), dev.shinsou.kmp.content.acquisition.LocalContentFormat.PLAIN_TEXT)
            assertNotEquals(acquisition.id, fresh.acquisitionId)
            assertNull(fresh.manifestId)
        }
    }

    @Test
    fun copiesMadeForAnotherRestoreOfTheSameManifestIdAreNotUsed() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            val grant = foundation.rightsGrants.all().single()
            foundation.restoreWithoutBodies(repository, publication, listOf(grant))
            val bodyless = bodylessUnitOf(foundation)
            val manifest = assertNotNull(bodyless.unit.latestManifest)
            // Copies prepared for a manifest of the same id but another revision: not this one's blobs.
            val stale = PreparedBodyCopies(
                foundation,
                manifest.copy(contentRevision = manifest.contentRevision + 1),
                listOf(foundation.blobStore.put("other".encodeToByteArray(), "text/plain")),
            )

            assertTrue(LocalPublicationBodies(foundation).restore(bodyless, manifest, grant, prepared = stale) { emptyList() })
            assertFalse(stale.stillPending())
            assertEquals(0, foundation.blobStore.pendingReceiptCount, "the stale copies were given up")
            assertEquals(
                AcquisitionAvailability.AVAILABLE,
                foundation.publications.all().single().acquisitions.single().availability,
            )
        }
    }

    @Test
    fun aShelfEditMadeDuringAFailedReimportIsKept() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            val imported = manager.importLocalDocuments(listOf(document)).single()
            val first = repository.upsertCategory(Category(name = "First"))
            val second = repository.upsertCategory(Category(name = "Second"))
            repository.setMangaCategories(imported.mangaId, listOf(first.id))

            var writes = 0
            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(
                    listOf(document, ImportedDocument("z.txt", ByteArray(0))),
                    writeLibrary = { write ->
                        write()
                        // The user moves the title to another shelf while the batch runs on.
                        if (writes++ == 0) repository.setMangaCategories(imported.mangaId, listOf(second.id))
                    },
                )
            }
            assertEquals(listOf(second.id), repository.categoryIdsOfManga(imported.mangaId))
            assertEquals(true, repository.manga(imported.mangaId)?.favorite)
        }
    }

    @Test
    fun anImportsProvisionalGrantNeverHasAnIdADurableGrantCanHave() = runTest {
        withTypedManager { _, _, foundation ->
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })
            val bytes = "Chapter 1\n\nText".encodeToByteArray()
            val first = controller.prepare(ImportedDocument("story.txt", bytes))
            val second = controller.prepare(ImportedDocument("story.txt", bytes))
            assertTrue(first.commit())
            val committed = foundation.rightsGrants.all().single()
            val publication = foundation.publications.all().single()
            val derived = dev.shinsou.kmp.content.acquisition.LocalAcquisitionIdentityDeriver().rightsGrantId(
                dev.shinsou.kmp.content.acquisition.LocalAcquisitionTarget(
                    publicationKey = publication.key,
                    publicationTitle = "story",
                    stableImportId = "local-plain_text:sha256:${dev.shinsou.kmp.crypto.Sha256.hex(bytes)}",
                    unitTitle = "story",
                    contentRevision = 0,
                    acquiredAtEpochMillis = 1,
                ),
                dev.shinsou.kmp.content.acquisition.LocalContentFormat.PLAIN_TEXT,
            )
            // A restore can commit the title's derived grant at any time: the import never admits under it.
            assertNotEquals(derived, committed.grantId.value)
            second.abandon()
            first.abandon()
            assertNotNull(controller.load(first.content.chapterUrl)?.canonicalText)
        }
    }

    @Test
    fun aFileCanBeImportedAgainAfterARestoreThatDroppedItsTitle() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            // A backup without the title: its publication and grant go, the commit record stays.
            foundation.restoreEmptyGraph(repository, AppSnapshot())

            val again = manager.importLocalDocuments(listOf(document)).single()
            assertEquals("Chapter 1\n\nText", manager.loadReaderChapter(again.mangaId, again.chapterId).typedSession?.canonicalText)
        }
    }

    @Test
    fun aSyncPullDuringACorrectionStillTakesTheOldReadingOutOfTheLibrary() = runTest {
        withTypedManager { manager, repository ->
            val document = ImportedDocument("小学.txt", "小学".toByteArray(charset("GBK")))
            manager.importLocalDocuments(listOf(document))
            var writes = 0
            val corrected = manager.importLocalDocuments(
                listOf(document),
                chooseTextEncoding = { LocalTextEncoding.GB18030 },
                alwaysChooseTextEncoding = true,
                writeLibrary = { write ->
                    // An unrelated sync pull replaces the snapshot between the two writes.
                    if (writes++ == 1) repository.replaceSnapshot(repository.currentSnapshot)
                    write()
                },
            ).single()
            assertEquals(listOf(corrected.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aBodyIsAttachedAsTheStoreRecordsIt() = runTest {
        withTypedManager { manager, repository, foundation ->
            manager.importLocalDocuments(listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())))
            val bodies = LocalPublicationBodies(foundation)
            fun attached(): Boolean {
                val publication = foundation.publications.all().single()
                val acquisition = publication.acquisitions.single()
                return bodies.isAttached(publication, acquisition, acquisition.units.single())
            }
            assertTrue(attached())
            foundation.restoreWithoutBodies(repository, foundation.publications.all().single(), foundation.rightsGrants.all())
            assertFalse(attached())
        }
    }

    private fun bodylessUnitOf(foundation: ContentFoundationRuntime): BodylessUnit {
        val publication = foundation.publications.all().single()
        val acquisition = publication.acquisitions.single()
        return BodylessUnit(publication, acquisition, acquisition.units.single())
    }

    @Test
    fun askingToChooseDoesNotAskForATextWithOneReading() = runTest {
        withTypedManager { manager, _ ->
            val questions = mutableListOf<LocalTextEncodingQuestion>()
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())),
                chooseTextEncoding = { questions += it; null },
                alwaysChooseTextEncoding = true,
            ).single()
            assertTrue(questions.isEmpty())
            assertEquals(
                "Chapter 1\n\nText",
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession?.canonicalText,
            )
        }
    }

    @Test
    fun validUtf8IsNotImportedInALegacyReadingWithoutAsking() = runTest {
        withTypedManager { manager, _ ->
            val text = List(40) { "λ = côte" }.joinToString("\n")
            val questions = mutableListOf<LocalTextEncodingQuestion>()
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("math.txt", text.encodeToByteArray())),
                chooseTextEncoding = { questions += it; LocalTextEncoding.UTF_8 },
            ).single()
            // Either the file keeps its one reading, or the user was asked: never a silent legacy import.
            assertTrue(questions.all { question -> LocalTextEncoding.UTF_8 in question.candidates.map { it.encoding } })
            assertEquals(text, manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession?.canonicalText)
        }
    }

    @Test
    fun aBatchDoesNotReturnTheTitleALaterCopyOfTheSameFileReplaced() = runTest {
        withTypedManager { manager, repository ->
            val bytes = "鳳凰".toByteArray(charset("Big5"))
            val choices = ArrayDeque(listOf(LocalTextEncoding.BIG5, LocalTextEncoding.GB18030))
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes), ImportedDocument("b.txt", bytes)),
                chooseTextEncoding = { choices.removeFirst() },
            )
            val gb = imported.single()
            assertEquals("获澳", manager.loadReaderChapter(gb.mangaId, gb.chapterId).typedSession?.canonicalText)
            assertEquals(listOf(gb.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aFailedImportRollsBackItsOwnTitlesAndTheTitlesItTookOutOfTheLibrary() = runTest {
        withTypedManager { manager, repository ->
            val bytes = "鳳凰".toByteArray(charset("Big5"))
            val big5 = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                chooseTextEncoding = { LocalTextEncoding.BIG5 },
            ).single()
            var writes = 0
            assertFailsWith<CancellationException> {
                manager.importLocalDocuments(
                    listOf(ImportedDocument("a.txt", bytes)),
                    writeLibrary = { write ->
                        write()
                        // The old reading leaves the library, then the hop back is cancelled.
                        if (writes++ == 1) throw CancellationException("cancelled after the commit")
                    },
                    chooseTextEncoding = { LocalTextEncoding.GB18030 },
                )
            }
            assertEquals(listOf(big5.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aFailedImportRollsBackOnlyTheTitlesItCreatedThatAreStillItsOwn() = runTest {
        withTypedManager { manager, repository ->
            val kept = manager.importLocalDocuments(
                listOf(ImportedDocument("kept.txt", "Kept".encodeToByteArray())),
            ).single()
            var writes = 0
            var restoredId = -1L
            assertFailsWith<LocalContentImportException> {
                manager.importLocalDocuments(
                    listOf(
                        ImportedDocument("a.txt", "Fresh".encodeToByteArray()),
                        // Already in the library: the import only updates it.
                        ImportedDocument("b.txt", "Kept".encodeToByteArray()),
                        // Empty, so the import fails here.
                        ImportedDocument("c.txt", ByteArray(0)),
                    ),
                    writeLibrary = { write ->
                        write()
                        if (writes++ == 1) {
                            // A backup restore between the files brings back a.txt's title as its
                            // own, with the same id, URL and fields.
                            restoredId = repository.library().single { it.manga.id != kept.mangaId }.manga.id
                            repository.replaceSnapshot(repository.currentSnapshot)
                        }
                    },
                )
            }
            assertNotNull(repository.manga(restoredId), "a restored title is not this import's")
            assertEquals(true, repository.manga(kept.mangaId)?.favorite, "a title the import only updated stays")
        }
    }

    @Test
    fun anImportCancelledOnItsWayBackFromTheLibraryWriteRollsItBack() = runTest {
        withTypedManager { manager, repository ->
            var writes = 0
            assertFailsWith<CancellationException> {
                manager.importLocalDocuments(
                    listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())),
                    // The rows commit, then the hop back from the store queue is cancelled.
                    writeLibrary = { write ->
                        write()
                        if (writes++ == 0) throw CancellationException("cancelled after the commit")
                    },
                )
            }
            assertTrue(repository.library().isEmpty(), "rollback must see the committed title")
        }
    }

    @Test
    fun aRestoreWhileATitleIsImportedDoesNotLeaveItWithoutABody() = runTest {
        withTypedManager { manager, repository, foundation ->
            var writes = 0
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())),
                writeLibrary = { write ->
                    // A backup restore right before the import's library write. The content commit
                    // belongs to that write, so the restore cannot take the body from under the rows.
                    if (writes++ == 0) foundation.restoreEmptyGraph(repository)
                    write()
                },
            ).single()
            assertEquals(
                "Chapter 1\n\nText",
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession?.canonicalText,
            )
            assertEquals(listOf(imported.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun anAbandonedImportKeepsTheDurableGrantOfThePublicationCommittedMeanwhile() = runTest {
        withTypedManager { _, _, foundation ->
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })
            val bytes = "Chapter 1\n\nText".encodeToByteArray()
            val abandoned = controller.prepare(ImportedDocument("story.txt", bytes))
            // The same publication becomes durable before the first import commits, as a backup
            // restore of it does: its grant has the id of the import's provisional one.
            val durable = controller.prepare(ImportedDocument("story.txt", bytes))
            assertTrue(durable.commit())
            val grantId = assertNotNull(controller.load(durable.content.chapterUrl)).access.grantReference!!
            assertNotNull(foundation.rightsGrants.find(grantId))

            abandoned.abandon()

            assertEquals(
                "Chapter 1\n\nText",
                assertNotNull(controller.load(durable.content.chapterUrl)).canonicalText,
                "the cancelled import must not revoke the committed title's rights",
            )
        }
    }

    @Test
    fun anImportThatFailsWithNothingDurableWithdrawsItsProvisionalGrant() = runTest {
        withTypedManager { _, _, foundation ->
            val controller = LocalTypedContentController(foundation, epubArchiveExtractor = null, nowEpochMillis = { 1 })
            val prepared = controller.prepare(ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray()))
            val revisionBefore = foundation.rightsAuthority.policyRevision

            prepared.abandon()

            assertTrue(foundation.rightsGrants.all().isEmpty())
            assertEquals(revisionBefore + 1, foundation.rightsAuthority.policyRevision, "the provisional grant was revoked")
        }
    }

    @Test
    fun aRestoreThatRemovesAnAlreadyImportedPublicationLeavesNoRowWithoutABody() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            var writes = 0
            // The second import finds the publication, then a restore removes it and its rows.
            // The import may fail or import the file anew; it must not write rows for the old one.
            try {
                manager.importLocalDocuments(
                    listOf(document),
                    writeLibrary = { write ->
                        if (writes++ == 0) foundation.restoreEmptyGraph(repository, AppSnapshot())
                        write()
                    },
                )
            } catch (_: LocalContentImportException) {
                // Importing the same file again after a restore is refused by the content store.
            }
            assertTrue(writes > 1, "the import was prepared again after the restore")
            repository.library().forEach { item ->
                val chapter = repository.currentSnapshot.chapters.single { it.mangaId == item.manga.id }
                assertNotNull(manager.loadReaderChapter(item.manga.id, chapter.id).typedSession)
            }
        }
    }

    @Test
    fun aRestoreAfterAnotherReadingWasWrittenKeepsTheRestoredTitleAndItsCategories() = runTest {
        withTypedManager { manager, repository ->
            val bytes = "鳳凰".toByteArray(charset("Big5"))
            val big5 = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                chooseTextEncoding = { LocalTextEncoding.BIG5 },
            ).single()
            val shelf = repository.upsertCategory(Category(name = "Shelf"))
            repository.setMangaCategories(big5.mangaId, listOf(shelf.id))
            val backup = repository.currentSnapshot
            var writes = 0
            val imported = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                writeLibrary = { write ->
                    // The other reading is written; before the batch takes the first one out of
                    // the library, a backup restore brings that one back without the other.
                    if (writes++ == 1) repository.replaceSnapshot(backup)
                    write()
                },
                chooseTextEncoding = { LocalTextEncoding.GB18030 },
            )
            assertEquals(2, writes)
            assertTrue(imported.isEmpty(), "the title the import wrote is not in the restored library")
            assertEquals(listOf(big5.mangaId), repository.library().map { it.manga.id })
            assertEquals(listOf(shelf.id), repository.categoriesForManga(big5.mangaId).map { it.id })
        }
    }

    @Test
    fun aReadingIsNotTakenOutOfTheLibraryForAReplacementTheUserRemovedMeanwhile() = runTest {
        withTypedManager { manager, repository ->
            val bytes = "鳳凰".toByteArray(charset("Big5"))
            val big5 = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                chooseTextEncoding = { LocalTextEncoding.BIG5 },
            ).single()
            var writes = 0
            manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                writeLibrary = { write ->
                    if (writes++ == 1) {
                        repository.library().single { it.manga.id != big5.mangaId }
                            .let { repository.deleteManga(it.manga.id) }
                    }
                    write()
                },
                chooseTextEncoding = { LocalTextEncoding.GB18030 },
            )
            assertEquals(listOf(big5.mangaId), repository.library().map { it.manga.id })
        }
    }

    @Test
    fun aMetadataOnlyRestoreOfTheTitleBeingImportedDoesNotDiscardTheStagedBody() = runTest {
        val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
        // The publication as another device's backup holds it.
        var backedUp: Publication? = null
        var backedUpGrants: List<RightsGrant> = emptyList()
        withTypedManager { manager, _, foundation ->
            manager.importLocalDocuments(listOf(document))
            backedUp = foundation.publications.all().single()
            backedUpGrants = foundation.rightsGrants.all()
        }
        withTypedManager { manager, repository, foundation ->
            var writes = 0
            val imported = manager.importLocalDocuments(
                listOf(document),
                writeLibrary = { write ->
                    // The file's body is staged; a metadata-only backup restores its publication.
                    if (writes++ == 0) foundation.restoreWithoutBodies(repository, backedUp!!, backedUpGrants)
                    write()
                },
            ).single()
            assertEquals(1, writes, "the staged body is committed to the restored publication")
            assertEquals(
                AcquisitionAvailability.AVAILABLE,
                foundation.publications.all().single().acquisitions.single().availability,
            )
            assertEquals(
                "Chapter 1\n\nText",
                manager.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession?.canonicalText,
            )
        }
    }

    @Test
    fun aTitleRestoredWithoutItsBodyGetsItBackWhenItsFileIsImportedAgain() = runTest {
        withTypedManager { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            val first = manager.importLocalDocuments(listOf(document)).single()
            val publication = foundation.publications.all().single()
            val grants = foundation.rightsGrants.all()

            // The publication is found with its body, then a metadata-only restore replaces it.
            var writes = 0
            val during = manager.importLocalDocuments(
                listOf(document),
                writeLibrary = { write ->
                    if (writes++ == 0) foundation.restoreWithoutBodies(repository, publication, grants)
                    write()
                },
            ).single()
            assertEquals(2, writes, "the body-less publication is not reused: the file is prepared again")
            assertEquals(first.mangaId, during.mangaId)
            assertEquals(
                "Chapter 1\n\nText",
                manager.loadReaderChapter(during.mangaId, during.chapterId).typedSession?.canonicalText,
            )

            // And once more, restored before the import starts.
            foundation.restoreWithoutBodies(repository, publication, grants)
            val after = manager.importLocalDocuments(listOf(document)).single()
            // Attached again: the blob the device still held no longer goes with the next cleanup.
            assertEquals(
                AcquisitionAvailability.AVAILABLE,
                foundation.publications.all().single().acquisitions.single().availability,
            )
            assertEquals(
                "Chapter 1\n\nText",
                manager.loadReaderChapter(after.mangaId, after.chapterId).typedSession?.canonicalText,
            )
        }
    }

    @Test
    fun aBodyRestoredTwiceInTheSameMillisecondIsCommittedBothTimes() = runTest {
        // A clock that does not advance: a retry within the millisecond, or a clock set back.
        withTypedManager(now = { 1_700_000_000_000 }) { manager, repository, foundation ->
            val document = ImportedDocument("story.txt", "Chapter 1\n\nText".encodeToByteArray())
            manager.importLocalDocuments(listOf(document))
            val publication = foundation.publications.all().single()
            val grants = foundation.rightsGrants.all()

            repeat(2) { round ->
                foundation.restoreWithoutBodies(repository, publication, grants)
                val restored = manager.importLocalDocuments(listOf(document)).single()
                assertEquals(
                    AcquisitionAvailability.AVAILABLE,
                    foundation.publications.all().single().acquisitions.single().availability,
                    "restore ${round + 1} attaches the body again",
                )
                assertEquals(
                    "Chapter 1\n\nText",
                    manager.loadReaderChapter(restored.mangaId, restored.chapterId).typedSession?.canonicalText,
                )
            }
        }
    }

    /** As a metadata-only backup restores [publication]: its graph, manifests and rights, without the bodies. */
    private suspend fun ContentFoundationRuntime.restoreWithoutBodies(
        repository: ShinsouRepository,
        publication: Publication,
        grants: List<RightsGrant>,
    ) {
        repository.commitSnapshotMutationAtomically(
            repository.currentSnapshot,
            SnapshotReplacementOrigin.DIRECT,
        ) { _, _, commitSyncJournal ->
            commitSyncJournal()
            transactions.commit(
                ContentCommitBatch<LegacyOutboxDraft>(
                    commitId = "test-restore-without-bodies",
                    // As the restore coordinator marks a publication whose bodies the archive lacks.
                    publications = listOf(
                        ContentPublicationMutation(
                            publication.copy(
                                acquisitions = publication.acquisitions.map {
                                    it.copy(availability = AcquisitionAvailability.PARTIAL)
                                },
                            ),
                        ),
                    ),
                    rightsGrants = grants.map(::ContentRightsGrantMutation),
                    semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                ),
            )
        }
        replaceRuntimeRightsWithDurableState()
        // The manifest is restored with the graph; nothing attaches its blobs.
        assertEquals(AcquisitionAvailability.PARTIAL, publications.all().single().acquisitions.single().availability)
    }

    private suspend fun ContentFoundationRuntime.restoreEmptyGraph(
        repository: ShinsouRepository,
        snapshot: AppSnapshot = repository.currentSnapshot,
    ) {
        // As the restore coordinator does: the publication graph and the snapshot in one step.
        repository.commitSnapshotMutationAtomically(snapshot, SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
            commitSyncJournal()
            transactions.commit(
                ContentCommitBatch<LegacyOutboxDraft>(
                    commitId = "test-restore",
                    semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                ),
            )
        }
        assertTrue(publications.all().isEmpty())
    }

    @Test
    fun aFailedImportGivesATitleItTookOutOfTheLibraryItsCategoriesBack() = runTest {
        withTypedManager { manager, repository ->
            val bytes = "鳳凰".toByteArray(charset("Big5"))
            val big5 = manager.importLocalDocuments(
                listOf(ImportedDocument("a.txt", bytes)),
                chooseTextEncoding = { LocalTextEncoding.BIG5 },
            ).single()
            val shelf = repository.upsertCategory(Category(name = "Shelf"))
            val other = repository.upsertCategory(Category(name = "Other"))
            repository.setMangaCategories(big5.mangaId, listOf(shelf.id, other.id))
            var writes = 0
            assertFailsWith<CancellationException> {
                manager.importLocalDocuments(
                    listOf(ImportedDocument("a.txt", bytes)),
                    writeLibrary = { write ->
                        write()
                        // The old reading leaves the library, then the hop back is cancelled.
                        if (writes++ == 1) throw CancellationException("cancelled after the commit")
                    },
                    chooseTextEncoding = { LocalTextEncoding.GB18030 },
                )
            }
            assertEquals(
                listOf(shelf.id, other.id),
                repository.categoriesForManga(big5.mangaId).map { it.id },
            )
        }
    }

    private suspend fun withTypedManager(block: suspend (LocalContentManager, ShinsouRepository) -> Unit) =
        withTypedManager { manager, repository, _ -> block(manager, repository) }

    private suspend fun withTypedManager(
        now: (() -> Long)? = null,
        block: suspend (LocalContentManager, ShinsouRepository, ContentFoundationRuntime) -> Unit,
    ) {
        val database = Files.createTempFile("shinsou-local-text", ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
        try {
            val repository = ShinsouRepository()
            val foundation = ContentFoundationRuntime(driver, syncModeProvider = { ContentSyncMode.V2_ACTIVE })
            val manager = if (now == null) {
                LocalContentManager(repository, EmptyLocalFileSystem(), contentFoundation = foundation)
            } else {
                LocalContentManager(repository, EmptyLocalFileSystem(), contentFoundation = foundation, now = now)
            }
            block(manager, repository, foundation)
        } finally {
            driver.close()
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
