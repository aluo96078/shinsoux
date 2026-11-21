package dev.shinsou.kmp.ui.screens

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryColumnCountTest {
    @Test
    fun aPhoneKeepsTheConfiguredColumns() {
        assertEquals(3, libraryColumnCount(availableWidth = 352.dp, configuredColumns = 3))
        assertEquals(5, libraryColumnCount(availableWidth = 780.dp, configuredColumns = 5))
    }

    @Test
    fun aWideWindowAddsColumnsInsteadOfGrowingCovers() {
        for (width in listOf(1_100, 1_600, 2_400, 3_800)) {
            val columns = libraryColumnCount(availableWidth = width.dp, configuredColumns = 4)
            val coverWidth = (width - 14 * (columns - 1)) / columns.toFloat()
            assertTrue(coverWidth <= 200f, "$width dp wide: $columns columns of $coverWidth dp")
            assertTrue(columns >= 4)
        }
    }

    @Test
    fun anEmptyOrInvalidMeasurementFallsBackToTheConfiguredCount() {
        assertEquals(4, libraryColumnCount(availableWidth = 0.dp, configuredColumns = 4))
        assertEquals(1, libraryColumnCount(availableWidth = 0.dp, configuredColumns = 0))
    }
}
