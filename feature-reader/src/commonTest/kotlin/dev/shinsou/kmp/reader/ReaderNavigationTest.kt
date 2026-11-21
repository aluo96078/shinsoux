package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderNavigationTest {
    @Test
    fun tapZonesRespectRtlAndUseCentreForChrome() {
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, readerTapAction(10f, 300f, ReadingMode.PAGER_LTR))
        assertEquals(ReaderTapAction.TOGGLE_CHROME, readerTapAction(150f, 300f, ReadingMode.PAGER_LTR))
        assertEquals(ReaderTapAction.NEXT_PAGE, readerTapAction(290f, 300f, ReadingMode.PAGER_LTR))

        assertEquals(ReaderTapAction.NEXT_PAGE, readerTapAction(10f, 300f, ReadingMode.PAGER_RTL))
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, readerTapAction(290f, 300f, ReadingMode.PAGER_RTL))
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, readerTapAction(10f, 300f, ReadingMode.WEBTOON))
        assertEquals(ReaderTapAction.NEXT_PAGE, readerTapAction(290f, 300f, ReadingMode.CONTINUOUS_VERTICAL))
    }

    @Test
    fun storyOrderPrioritisesDescendingSourceOrderWithStableTies() {
        val chapters = listOf(
            chapter(id = 40, sourceOrder = 0, number = 4.0),
            chapter(id = 22, sourceOrder = 2, number = 2.0),
            chapter(id = 11, sourceOrder = 3, number = 1.0),
            chapter(id = 21, sourceOrder = 2, number = 2.0),
        )

        val navigation = buildReaderChapterNavigation(
            chapters = chapters,
            currentChapterId = 21,
            manga = Manga(id = 1),
            settings = ReaderSettings(),
        )

        assertEquals(listOf(11L, 21L, 22L, 40L), navigation.storyOrder.map(Chapter::id))
        assertEquals(11L, navigation.previous?.id)
        assertEquals(22L, navigation.next?.id)
    }

    @Test
    fun navigationActuallyAppliesReadFilteredAndDuplicateSkips() {
        val current = chapter(id = 1, sourceOrder = 5, number = 1.0)
        val read = chapter(id = 2, sourceOrder = 4, number = 2.0, read = true)
        val filteredOut = chapter(id = 3, sourceOrder = 3, number = 3.0, bookmark = false)
        val duplicateToSkip = chapter(id = 4, sourceOrder = 2, number = 4.0, bookmark = true)
        val canonicalDuplicate = chapter(id = 5, sourceOrder = 1, number = 4.0, bookmark = true)
        val eligible = chapter(id = 6, sourceOrder = 0, number = 5.0, bookmark = true)
        val manga = Manga(id = 1, chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)

        val navigation = buildReaderChapterNavigation(
            chapters = listOf(current, read, filteredOut, duplicateToSkip, canonicalDuplicate, eligible),
            currentChapterId = current.id,
            manga = manga,
            settings = ReaderSettings(
                skipReadChapters = true,
                skipFilteredChapters = true,
                skipDuplicateChapters = true,
            ),
        )

        assertEquals(canonicalDuplicate.id, navigation.next?.id)

        val fromCanonical = buildReaderChapterNavigation(
            chapters = navigation.storyOrder,
            currentChapterId = canonicalDuplicate.id,
            manga = manga,
            settings = ReaderSettings(
                skipReadChapters = true,
                skipFilteredChapters = true,
                skipDuplicateChapters = true,
            ),
        )
        assertEquals(eligible.id, fromCanonical.next?.id)
        assertNull(fromCanonical.previous, "all earlier candidates are read, filtered, or duplicate")
    }

    @Test
    fun downloadedFilterAndMissingCurrentAreDeterministic() {
        val chapters = listOf(
            chapter(id = 1, sourceOrder = 2, number = 1.0),
            chapter(id = 2, sourceOrder = 1, number = 2.0),
            chapter(id = 3, sourceOrder = 0, number = 3.0),
        )
        val manga = Manga(id = 1, chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
        val navigation = buildReaderChapterNavigation(
            chapters,
            currentChapterId = 1,
            manga = manga,
            settings = ReaderSettings(skipFilteredChapters = true),
            downloadedChapterIds = setOf(3),
        )
        assertEquals(3L, navigation.next?.id)

        val missing = buildReaderChapterNavigation(chapters, 99, manga, ReaderSettings())
        assertEquals(-1, missing.currentIndex)
        assertNull(missing.previous)
        assertNull(missing.next)
    }

    @Test
    fun skipFilteredHonoursPersistedScanlatorExclusions() {
        val chapters = listOf(
            chapter(id = 1, sourceOrder = 2, number = 1.0),
            chapter(id = 2, sourceOrder = 1, number = 2.0, scanlator = "Hidden Team"),
            chapter(id = 3, sourceOrder = 0, number = 3.0, scanlator = "Visible Team"),
        )
        val navigation = buildReaderChapterNavigation(
            chapters = chapters,
            currentChapterId = 1,
            manga = Manga(id = 1, excludedScanlators = setOf("Hidden Team")),
            settings = ReaderSettings(skipFilteredChapters = true),
        )

        assertEquals(3L, navigation.next?.id)
    }

    @Test
    fun prefetchWindowPrioritisesForwardPagesAndStaysInRange() {
        assertEquals(listOf(6, 7, 8, 4), readerPrefetchIndices(currentPage = 5, pageCount = 10))
        assertEquals(listOf(1, 2, 3), readerPrefetchIndices(currentPage = 0, pageCount = 4))
        assertEquals(listOf(8), readerPrefetchIndices(currentPage = 9, pageCount = 10))
        assertEquals(emptyList(), readerPrefetchIndices(currentPage = 0, pageCount = 1))
    }

    @Test
    fun trackingProgressUsesChapterNumberOrOneBasedStoryPosition() {
        val numbered = chapter(id = 1, sourceOrder = 2, number = 12.5)
        val unnumbered = chapter(id = 2, sourceOrder = 1, number = -1.0)
        val storyOrder = listOf(numbered, unnumbered)

        assertEquals(12.5, readerTrackingProgress(numbered, storyOrder))
        assertEquals(2.0, readerTrackingProgress(unnumbered, storyOrder))
        assertEquals(0.0, readerTrackingProgress(unnumbered.copy(id = 99), storyOrder))
    }

    private fun chapter(
        id: Long,
        sourceOrder: Int,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = null,
    ): Chapter = Chapter(
        id = id,
        mangaId = 1,
        name = "Chapter $number",
        sourceOrder = sourceOrder,
        chapterNumber = number,
        read = read,
        bookmark = bookmark,
        scanlator = scanlator,
    )
}
