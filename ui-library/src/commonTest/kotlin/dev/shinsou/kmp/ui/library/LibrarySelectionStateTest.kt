package dev.shinsou.kmp.ui.library

import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.ui.library.ALL_LIBRARY_CATEGORY_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySelectionStateTest {
    private val selection = LibrarySelectionState()

    @Test
    fun clearingTheLibrarySelectionReportsWhetherThereWasOne() {
        assertFalse(selection.clearLibraryTitles())
        selection.selectLibraryTitles(setOf(1L, 2L))
        assertTrue(selection.clearLibraryTitles())
        assertTrue(selection.state.value.libraryIds.isEmpty())
    }

    @Test
    fun aDeletedOrSystemCategoryFallsBackToEveryTitle() {
        selection.selectCategory(5L)
        selection.retainCategory(listOf(Category(id = 5L, name = "Kept", sort = 1)))
        assertEquals(5L, selection.state.value.categoryId)
        selection.retainCategory(listOf(Category(id = 6L, name = "Other", sort = 1)))
        assertEquals(ALL_LIBRARY_CATEGORY_ID, selection.state.value.categoryId)
        selection.selectCategory(0L)
        selection.retainCategory(listOf(Category(id = 0L, name = "Default", sort = 0)))
        assertEquals(ALL_LIBRARY_CATEGORY_ID, selection.state.value.categoryId)
    }

    @Test
    fun clearAllKeepsTheCategory() {
        selection.selectCategory(5L)
        selection.selectLibraryTitles(setOf(1L))
        selection.selectChapters(setOf(2L))
        selection.clearAll()
        assertEquals(LibrarySelection(categoryId = 5L), selection.state.value)
    }
}
