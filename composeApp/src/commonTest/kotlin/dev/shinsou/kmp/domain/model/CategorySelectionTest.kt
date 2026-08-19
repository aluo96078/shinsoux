package dev.shinsou.kmp.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CategorySelectionTest {
    @Test
    fun emptyPickerSelectionPersistsAsInternalDefault() {
        assertEquals(
            setOf(Category.Default.id),
            normalizeMangaCategorySelection(emptySet()),
        )
    }

    @Test
    fun pickerSelectionKeepsDefaultAndRealCategoriesMutuallyExclusive() {
        assertEquals(
            setOf(2L),
            toggleMangaCategorySelection(setOf(Category.Default.id), 2L),
        )
        assertEquals(
            setOf(2L, 3L),
            toggleMangaCategorySelection(setOf(2L), 3L),
        )
        assertEquals(
            setOf(Category.Default.id),
            toggleMangaCategorySelection(setOf(2L), 2L),
        )
        assertEquals(
            setOf(Category.Default.id),
            toggleMangaCategorySelection(setOf(2L, 3L), Category.Default.id),
        )
    }

    @Test
    fun batchPickerStartsWithOnlyCategoriesSharedByEveryManga() {
        assertEquals(
            setOf(2L),
            commonMangaCategorySelection(
                listOf(
                    setOf(1L, 2L),
                    setOf(2L, 3L),
                    setOf(2L, 4L),
                ),
            ),
        )
        assertEquals(
            setOf(Category.Default.id),
            commonMangaCategorySelection(listOf(setOf(1L), setOf(2L))),
        )
    }

    @Test
    fun alwaysAskSentinelNeverBecomesALibraryTab() {
        assertEquals(-1L, ALWAYS_ASK_CATEGORY_ID)
        assertEquals(
            Category.Default.id,
            resolveLibraryStartingCategory(ALWAYS_ASK_CATEGORY_ID, setOf(Category.Default.id, 2L)),
        )
        assertEquals(2L, resolveLibraryStartingCategory(2L, setOf(Category.Default.id, 2L)))
        assertEquals(
            true,
            shouldAskForCategoriesOnFavorite(ALWAYS_ASK_CATEGORY_ID, setOf(Category.Default.id, 2L)),
        )
        assertEquals(
            false,
            shouldAskForCategoriesOnFavorite(ALWAYS_ASK_CATEGORY_ID, setOf(Category.Default.id)),
        )
    }
}
