package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

class ReaderHistoryPresentationTest {
    @Test
    fun historyDisplaysTheStoredZeroBasedPositionAsAOneBasedPage() {
        val chapter = Chapter(name = "第八十六章", lastPageRead = 12)

        assertEquals(
            "第八十六章 · 第 13 頁",
            historyPositionLabel(chapter, shinsouStringsFor("zh-TW")),
        )
        assertEquals(
            "第八十六章 · Page 13",
            historyPositionLabel(chapter, shinsouStringsFor("en-US")),
        )
    }

    @Test
    fun renderedPageIsRetainedIndependentlyFromTheTextLocatorBlock() {
        val position = ReaderProgressPosition(
            locator = ReadingLocator.Text(
                schemaVersion = 1,
                scope = scope(),
                resourceId = "body",
                blockId = "paragraph-2",
                offset = 120,
                progression = 0.4,
            ),
            pageIndex = 7,
        )

        assertEquals(7, position.pageIndex)
        assertEquals("paragraph-2", position.locator.blockId)
    }

    @Test
    fun renderedPageIsRetainedForImageAndEpubLocators() {
        val scope = scope()
        val image = ReaderProgressPosition(
            ReadingLocator.Image(1, scope, pageResourceId = "page-3", pageIndexHint = 2),
            pageIndex = 2,
        )
        val epub = ReaderProgressPosition(
            ReadingLocator.Epub(
                schemaVersion = 1,
                scope = scope,
                resourceId = "chapter-4",
                resourceHref = "Text/chapter-4.xhtml",
                cfi = "epubcfi(/6/8!/4/2:0)",
                spineIndexHint = 3,
            ),
            pageIndex = 3,
        )

        assertEquals(2, image.pageIndex)
        assertEquals(3, epub.pageIndex)
        assertFailsWith<IllegalArgumentException> { image.copy(pageIndex = -1) }
    }

    @Test
    fun progressObservationClockOrdersCallbacksThatShareOneMillisecond() {
        val clock = ReaderProgressObservationClock()

        assertEquals(2_000, clock.next(2_000))
        assertEquals(2_001, clock.next(2_000))
        assertEquals(2_002, clock.next(1_999))
        assertEquals(3_000, clock.next(3_000))
    }

    @Test
    fun extensionLocalCommitDoesNotRequireTheReporterLane() = runTest {
        val repository = ShinsouRepository()
        val locator = ReadingLocator.Text(
            schemaVersion = 1,
            scope = scope(),
            resourceId = "body",
            blockId = "paragraph-8",
            offset = 800,
            progression = 0.8,
        )

        val accepted = V2ReaderProgressCoordinator(repository, reporter = null).commitLocal(
            progressEvent(locator, pageIndex = 8, readAt = 3_000),
        )

        assertEquals(true, accepted)
        assertEquals(8, repository.history().single().chapter.lastPageRead)
        assertEquals(locator, repository.currentSnapshot.histories.single().lastLocator)
    }

    @Test
    fun finalExtensionPageWinsAndAnOlderDelayedWriteCannotRevertIt() = runTest {
        val repository = ShinsouRepository()
        val coordinator = V2ReaderProgressCoordinator(repository, reporter = null)
        val locator = ReadingLocator.Text(
            schemaVersion = 1,
            scope = scope(),
            resourceId = "body",
            blockId = "paragraph-2",
            offset = 120,
            progression = 0.4,
        )

        coordinator.commit(progressEvent(locator, pageIndex = 7, readAt = 2_000))
        coordinator.commit(progressEvent(locator, pageIndex = 3, readAt = 1_000))

        assertEquals(7, repository.history().single().chapter.lastPageRead)
        assertEquals(2_000, repository.history().single().lastRead)
        assertEquals(locator, repository.currentSnapshot.histories.single().lastLocator)
    }

    @Test
    fun initialReaderCallbackCannotResetAStoredExtensionPage() = runTest {
        val repository = ShinsouRepository()
        val locator = ReadingLocator.Text(
            schemaVersion = 1,
            scope = scope(),
            resourceId = "body",
            blockId = "paragraph-1",
            offset = 0,
            progression = 0.0,
        )
        val chapterUrl = encodeTypedLocalChapterUrl(
            publicationKey = locator.scope.publicationId,
            acquisitionId = locator.scope.acquisitionId,
            unitKey = locator.scope.unitId,
        )
        val manga = repository.upsertManga(
            Manga(source = LOCAL_SOURCE_ID, url = "local://test", title = "小說"),
        )
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = chapterUrl, name = "章節"),
        )
        repository.markChapterProgress(chapter.id, lastPageRead = 7, read = false, readAt = 2_000)

        V2ReaderProgressCoordinator(repository, reporter = null).commit(
            progressEvent(locator, pageIndex = 0, readAt = 1_000),
        )

        assertEquals(7, repository.chapter(chapter.id)?.lastPageRead)
        assertEquals(2_000, repository.history().single().lastRead)
    }

    @Test
    fun localReaderFlushWaitsForQueuedFinalPageAndDiskPersistence() = runTest {
        val persisted = mutableListOf<String>()
        val repository = ShinsouRepository(persist = persisted::add)
        val manga = repository.upsertManga(
            Manga(source = 10, url = "/manga", title = "漫畫"),
        )
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = "/chapter", name = "章節"),
        )
        val coordinator = ReaderLocalProgressCoordinator(repository)
        val worker = launch { coordinator.run() }

        coordinator.enqueue(
            ReaderLocalProgressEvent(
                chapterId = chapter.id,
                pageIndex = 8,
                pageCount = 12,
                read = false,
                readAt = 3_000,
            ),
        )
        coordinator.flushLocal()
        runCurrent()

        val restored = ShinsouRepository.decodeSnapshot(persisted.last())
        assertEquals(8, restored.chapters.single().lastPageRead)
        assertEquals(12, restored.histories.single().lastPageCount)
        worker.cancel()
        repository.closePersistence()
    }

    private fun progressEvent(
        locator: ReadingLocator,
        pageIndex: Int,
        readAt: Long,
    ): V2ReaderProgressEvent = V2ReaderProgressEvent(
        title = "小說",
        unitTitle = "章節",
        locator = locator,
        pageIndex = pageIndex,
        progressSessionId = "reader-session",
        readAt = readAt,
    )

    private fun scope(): ReadingScope {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return ReadingScope(
            schemaVersion = 1,
            publicationId = publication,
            acquisitionId = "22222222-2222-4222-8222-222222222222",
            unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
            contentRevision = 1,
        )
    }
}
