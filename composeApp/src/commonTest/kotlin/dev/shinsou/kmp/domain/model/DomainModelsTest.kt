package dev.shinsou.kmp.domain.model

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelsTest {
    @Test
    fun defaultsMatchOriginalMobileSettings() {
        val settings = AppSettings()

        assertEquals("MM/dd/yyyy", settings.general.dateFormat)
        assertFalse(settings.appearance.relativeTimestamps)
        assertFalse(settings.reader.skipFilteredChapters)
        assertFalse(settings.reader.skipReadChapters)
        assertFalse(settings.reader.skipDuplicateChapters)
        assertTrue(settings.reader.animatePageTransitions)
        assertFalse(settings.sync.enabled)
        assertTrue(settings.sync.syncOnForeground)
        assertTrue(settings.browse.showNsfwSources)
        assertTrue(settings.browse.enabledLanguages.isEmpty())
    }

    @Test
    fun mangaChapterFlagsMatchOriginalBitLayout() {
        val manga = Manga(
            chapterFlags = Manga.CHAPTER_SORT_ASC or
                Manga.CHAPTER_SHOW_UNREAD or
                Manga.CHAPTER_SHOW_DOWNLOADED or
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED or
                Manga.CHAPTER_SORTING_NUMBER or
                Manga.CHAPTER_DISPLAY_NUMBER,
        )

        assertFalse(manga.sortDescending)
        assertEquals(ChapterFilterState.INCLUDE, manga.unreadFilter)
        assertEquals(ChapterFilterState.INCLUDE, manga.downloadedFilter)
        assertEquals(ChapterFilterState.EXCLUDE, manga.bookmarkedFilter)
        assertEquals(Manga.CHAPTER_SORTING_NUMBER, manga.sorting)
        assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, manga.displayMode)
    }

    @Test
    fun categoryFlagsRoundTripDisplayAndSort() {
        val category = Category(id = 9, name = "Reading")
            .withDisplayMode(LibraryDisplayMode.COMFORTABLE_GRID)
            .withSort(LibrarySort(LibrarySortType.LATEST_CHAPTER, SortDirection.DESCENDING))

        assertEquals(LibraryDisplayMode.COMFORTABLE_GRID, category.displayModeOverride)
        assertEquals(LibrarySortType.LATEST_CHAPTER, category.sortTypeOverride)
        assertEquals(SortDirection.DESCENDING, category.sortDirectionOverride)
        assertEquals(LibrarySort(LibrarySortType.LATEST_CHAPTER, SortDirection.DESCENDING), category.effectiveSort)
    }

    @Test
    fun libraryFilterCyclesAndDropsDisabledTrackerEntries() {
        val filter = LibraryFilter().withTrackerFilter(TrackerIds.MY_ANIME_LIST, LibraryFilterState.INCLUDE)
        assertTrue(filter.hasActiveFilters)
        assertEquals(LibraryFilterState.INCLUDE, filter.trackerFilter(TrackerIds.MY_ANIME_LIST))
        assertEquals(LibraryFilterState.EXCLUDE, LibraryFilterState.INCLUDE.next())

        val cleared = filter.withTrackerFilter(TrackerIds.MY_ANIME_LIST, LibraryFilterState.DISABLED)
        assertFalse(cleared.hasActiveFilters)
        assertFalse(TrackerIds.MY_ANIME_LIST in cleared.trackerFilters)
    }

    @Test
    fun snapshotJsonRoundTripPreservesNullableAndNestedState() {
        val snapshot = AppSnapshot(
            revision = 12,
            mangas = listOf(
                Manga(
                    id = 1,
                    source = 99,
                    favorite = true,
                    url = "/series/one",
                    title = "One",
                    author = null,
                    genre = listOf("Action", "Drama"),
                    notes = "Remember this",
                    excludedScanlators = setOf("Alternate Team"),
                ),
            ),
            mangaCategories = listOf(MangaCategory(1, Category.Default.id)),
            settings = AppSettings(
                appearance = AppearanceSettings(theme = ThemeMode.DARK, amoledDark = true),
                reader = ReaderSettings(animatePageTransitions = false),
                security = SecuritySettings(incognitoMode = true),
            ),
        )

        val encoded = ShinsouRepository.encodeSnapshot(snapshot)
        val restored = ShinsouRepository.decodeSnapshot(encoded)

        assertEquals(snapshot, restored)
        assertFalse(restored.settings.reader.animatePageTransitions)
        assertTrue(encoded.contains("Remember this"))
    }

    @Test
    fun legacySnapshotWithoutPageTransitionPreferenceKeepsAnimationsEnabled() {
        val restored = ShinsouRepository.decodeSnapshot("""{"settings":{"reader":{}}}""")

        assertTrue(restored.settings.reader.animatePageTransitions)
    }
}
