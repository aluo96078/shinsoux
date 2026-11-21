package dev.shinsou.kmp.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.shinsou.kmp.domain.model.LibrarySettings
import dev.shinsou.kmp.sync.v3.SyncMissingSource
import dev.shinsou.kmp.ui.i18n.StringKey
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The library says which titles from other devices wait for an extension this device lacks. */
class LibraryWaitingSourcesTest {
    @get:Rule
    val rule = createComposeRule()

    private val waiting = listOf(
        SyncMissingSource(
            packageId = "zh.example",
            name = "Example Source",
            language = "zh",
            repositoryUrl = "https://plugins.example.org/repo",
            titleCount = 2,
            titles = listOf("One", "Two"),
        ),
    )

    private fun library(waitingSources: List<SyncMissingSource>, onOpenWaitingSources: () -> Unit = {}) {
        rule.setContent {
            LibraryScreen(
                items = emptyList(),
                categories = emptyList(),
                settings = LibrarySettings(),
                selectedCategoryId = 0,
                selectedMangaIds = emptySet(),
                compactChrome = false,
                onCategorySelected = {},
                onCreateCategory = {},
                onRenameCategory = { _, _ -> },
                onDeleteCategory = {},
                onReorderCategories = {},
                onSelectionChange = {},
                onSettingsChange = {},
                onOpenManga = {},
                onContinueReading = {},
                waitingSources = waitingSources,
                onOpenWaitingSources = onOpenWaitingSources,
                onRefresh = {},
                onMarkSelectedRead = {},
                onMoveSelected = { _, _ -> },
                onDeleteSelected = {},
            )
        }
    }

    @Test
    fun anEmptyLibraryNamesTheTitlesWaitingForTheirExtension() {
        var opened = 0
        library(waiting) { opened++ }
        rule.onNodeWithText("2 titles are waiting for their extensions").assertExists()
        rule.onNodeWithText("Example Source", substring = true).assertExists()
        rule.onNodeWithText("One · Two").assertExists()
        rule.onNodeWithText("From repository https://plugins.example.org/repo").assertExists()
        rule.onNodeWithText(StringKey.YOUR_LIBRARY_IS_EMPTY.english).assertDoesNotExist()
        rule.onNodeWithText(StringKey.SYNC_DETAILS.english).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withNothingWaitingTheLibraryIsJustEmpty() {
        library(emptyList())
        rule.onNodeWithText(StringKey.YOUR_LIBRARY_IS_EMPTY.english).assertExists()
        rule.onNodeWithText(StringKey.SYNC_DETAILS.english).assertDoesNotExist()
    }
}
