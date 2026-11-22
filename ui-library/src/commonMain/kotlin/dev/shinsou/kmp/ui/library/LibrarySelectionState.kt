package dev.shinsou.kmp.ui.library

import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.ui.library.ALL_LIBRARY_CATEGORY_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What is selected in the library and in an open title's chapter list. */
data class LibrarySelection(
    /** Titles selected for a bulk action in the library grid. */
    val libraryIds: Set<Long> = emptySet(),
    /** Chapters selected for a bulk action in the open title. */
    val chapterIds: Set<Long> = emptySet(),
    /** The library tab shown; [ALL_LIBRARY_CATEGORY_ID] for every title. */
    val categoryId: Long = ALL_LIBRARY_CATEGORY_ID,
)

/**
 * R4-3 (docs/architecture/0006): the library's selection, kept outside composition so navigation
 * can clear it (the shell's `AppNavigator`) and tests can drive it.
 */
class LibrarySelectionState {
    private val mutableState = MutableStateFlow(LibrarySelection())

    val state: StateFlow<LibrarySelection> = mutableState.asStateFlow()

    fun selectLibraryTitles(ids: Set<Long>) = mutableState.update { it.copy(libraryIds = ids) }

    /** Clears the library selection; true when there was one (it consumed a Back press). */
    fun clearLibraryTitles(): Boolean {
        if (mutableState.value.libraryIds.isEmpty()) return false
        mutableState.update { it.copy(libraryIds = emptySet()) }
        return true
    }

    fun selectChapters(ids: Set<Long>) = mutableState.update { it.copy(chapterIds = ids) }

    fun clearChapters() = mutableState.update { it.copy(chapterIds = emptySet()) }

    fun clearAll() = mutableState.update { it.copy(libraryIds = emptySet(), chapterIds = emptySet()) }

    fun selectCategory(categoryId: Long) = mutableState.update { it.copy(categoryId = categoryId) }

    /** Falls back to every title when the selected category was deleted or became a system one. */
    fun retainCategory(categories: List<Category>) = mutableState.update { selection ->
        val categoryId = selection.categoryId
        if (
            categoryId != ALL_LIBRARY_CATEGORY_ID &&
            categories.none { it.id == categoryId && !it.isSystemCategory }
        ) {
            selection.copy(categoryId = ALL_LIBRARY_CATEGORY_ID)
        } else {
            selection
        }
    }
}
