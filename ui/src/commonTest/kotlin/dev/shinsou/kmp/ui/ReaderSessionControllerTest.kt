package dev.shinsou.kmp.ui

import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSessionControllerTest {
    private class FakeSource : ReaderChapterSource {
        var load: suspend (ReaderSession) -> ReaderChapter = { ReaderChapter(pages = pages(3)) }

        override suspend fun load(session: ReaderSession): ReaderChapter = load.invoke(session)

        override suspend fun recheckRights(typed: TypedReaderContentSession) = Unit

        override fun stopSpeech() = Unit
    }

    private class Fixture(scope: TestScope) {
        val repository = ShinsouRepository()
        val source = FakeSource()
        val messages = mutableListOf<String>()
        val progress = ReaderLocalProgressCoordinator(repository)
        val reader = ReaderSessionController(
            source = source,
            progress = progress,
            scope = scope,
            messages = AppMessages({ shinsouStringsFor("en") }) { messages += it },
            now = { 1_000L },
        )
        lateinit var manga: Manga
        lateinit var first: Chapter
        lateinit var second: Chapter

        suspend fun seed() {
            manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
            first = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c1", name = "1"))
            second = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c2", name = "2"))
        }

        val state get() = reader.state.value
    }

    private fun readerTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this)
        fixture.seed()
        // A foreground job, so advanceUntilIdle also runs the progress writer.
        val writer = launch { fixture.progress.run() }
        block(fixture)
        writer.cancel()
    }

    @Test
    fun opensOnlyChaptersOfTheLibrary() = readerTest { f ->
        assertFalse(f.reader.open(f.manga.id, 999L, f.repository.currentSnapshot))
        assertNull(f.state.session)
        assertTrue(f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot))
        assertEquals(ReaderSession(f.manga.id, f.first.id, "local-reader-1"), f.state.session)
        assertTrue(f.state.showsLoading)
    }

    @Test
    fun loadingShowsTheChapterOfTheOpenSession() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val session = checkNotNull(f.state.session)
        f.reader.load(session)
        assertEquals(3, f.state.activeChapter.pages.size)
        assertFalse(f.state.loading)
        assertFalse(f.state.showsLoading)
        assertNull(f.state.error)
    }

    @Test
    fun aFailedLoadShowsItsError() = readerTest { f ->
        f.source.load = { error("offline") }
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        f.reader.load(checkNotNull(f.state.session))
        val error = assertIs<ReaderLoadError.Failed>(f.state.error)
        assertEquals("offline", error.cause.message)
        assertTrue(f.state.activeChapter.pages.isEmpty())
        assertFalse(f.state.showsLoading)
    }

    @Test
    fun aLoadForAReplacedSessionIsDropped() = readerTest { f ->
        val gate = CompletableDeferred<Unit>()
        f.source.load = { gate.await(); ReaderChapter(pages = pages(1)) }
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val stale = checkNotNull(f.state.session)
        val loading = launch { f.reader.load(stale) }
        advanceUntilIdle()
        f.reader.open(f.manga.id, f.second.id, f.repository.currentSnapshot)
        gate.complete(Unit)
        loading.join()
        assertEquals(f.second.id, f.state.session?.chapterId)
        assertNull(f.state.chapterSession)
        assertTrue(f.state.activeChapter.pages.isEmpty())
    }

    @Test
    fun changingChapterWaitsForTheProgressAndThenSwitches() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val session = checkNotNull(f.state.session)
        f.reader.markPositionInitialized(session)
        f.reader.transition(f.manga.id, f.second.id)
        assertTrue(f.state.transitionInFlight)
        f.reader.transition(f.manga.id, f.first.id) // ignored while the first is under way
        advanceUntilIdle()
        assertFalse(f.state.transitionInFlight)
        assertEquals(ReaderSession(f.manga.id, f.second.id, "local-reader-2"), f.state.session)
        assertNull(f.state.positionInitializedFor)
    }

    @Test
    fun aCloseDuringAChapterChangeClosesInsteadOfOpeningTheNextChapter() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        f.reader.transition(f.manga.id, f.second.id)
        assertTrue(f.state.transitionInFlight)
        // A link the user followed arrives while the progress is being written.
        f.reader.close()
        advanceUntilIdle()
        assertFalse(f.state.transitionInFlight)
        assertNull(f.state.session)
    }

    @Test
    fun closingEndsTheSession() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        f.reader.transition()
        advanceUntilIdle()
        assertNull(f.state.session)
    }

    @Test
    fun reachingTheLastPageCompletesTheChapterOnce() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val session = checkNotNull(f.state.session)
        assertFalse(f.reader.recordPagePosition(session, f.first, pageIndex = 1, pageCount = 3, incognito = false))
        assertTrue(f.reader.recordPagePosition(session, f.first, pageIndex = 2, pageCount = 3, incognito = false))
        assertFalse(f.reader.recordPagePosition(session, f.first, pageIndex = 2, pageCount = 3, incognito = false))
        f.progress.flushLocal()
        val stored = checkNotNull(f.repository.chapter(f.first.id))
        assertTrue(stored.read)
        assertEquals(2, stored.lastPageRead)
    }

    @Test
    fun progressIsIgnoredInIncognitoAndFromAnOldSession() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val old = checkNotNull(f.state.session)
        assertFalse(f.reader.recordPagePosition(old, f.first, pageIndex = 2, pageCount = 3, incognito = true))
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        assertFalse(f.reader.recordPagePosition(old, f.first, pageIndex = 2, pageCount = 3, incognito = false))
        f.progress.flushLocal()
        assertFalse(checkNotNull(f.repository.chapter(f.first.id)).read)
    }

    @Test
    fun theCategoryPickerConsumesOneBackPress() = readerTest { f ->
        assertFalse(f.reader.dismissCategoryPicker())
        f.reader.showCategoryPicker(f.manga.id)
        assertTrue(f.reader.dismissCategoryPicker())
        assertNull(f.state.categoryPickerMangaId)
        f.reader.requestBack()
        assertEquals(1L, f.state.backRequest)
    }

    @Test
    fun aVanishedTitleDropsOnlyItsOwnSession() = readerTest { f ->
        f.reader.open(f.manga.id, f.first.id, f.repository.currentSnapshot)
        val first = checkNotNull(f.state.session)
        f.reader.open(f.manga.id, f.second.id, f.repository.currentSnapshot)
        f.reader.dropSession(first)
        assertEquals(f.second.id, f.state.session?.chapterId)
        f.reader.dropSession(checkNotNull(f.state.session))
        assertNull(f.state.session)
    }
}

private fun pages(count: Int): List<ReaderPage> = List(count) { ReaderPage(index = it, imageUrl = "https://example.invalid/$it") }
