package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The conditional title mutations an import rollback relies on. */
class ShinsouRepositoryConditionalMangaTest {
    @Test
    fun aTitleIsDeletedOnlyWhileItIsTheExpectedRowOfTheSameSnapshot() = runTest {
        val repository = ShinsouRepository()
        val written = repository.upsertManga(Manga(source = 0, url = "local://a", title = "A", favorite = true))
        val count = repository.snapshotReplacementCount

        // A user edit: the row is no longer what the caller wrote.
        val edited = repository.upsertManga(written.copy(title = "Edited"))
        assertFalse(repository.deleteMangaIf(written, count))
        assertNotNull(repository.manga(written.id))

        // A restore that brings back an equal row gives its id to a title of its own.
        repository.replaceSnapshot(repository.currentSnapshot)
        assertFalse(repository.deleteMangaIf(edited, count))
        assertNotNull(repository.manga(written.id))

        val revision = repository.currentSnapshot.revision
        assertFalse(repository.deleteMangaIf(written, repository.snapshotReplacementCount))
        assertEquals(revision, repository.currentSnapshot.revision, "a refused mutation commits nothing")

        assertTrue(repository.deleteMangaIf(edited, repository.snapshotReplacementCount))
        assertNull(repository.manga(written.id))
    }

    @Test
    fun aTitleLeavesTheLibraryForItsReplacementOnlyWhileThatIsStillInTheLibrary() = runTest {
        val repository = ShinsouRepository()
        val shelf = repository.upsertCategory(Category(name = "Shelf"))
        val old = repository.upsertManga(Manga(source = 0, url = "local://old", title = "Old", favorite = true))
        repository.setMangaCategories(old.id, listOf(shelf.id))
        val backup = repository.currentSnapshot
        val replacement = repository.upsertManga(Manga(source = 0, url = "local://new", title = "New", favorite = true))
        val count = repository.snapshotReplacementCount

        // A backup restore brings the old title back without its replacement.
        repository.replaceSnapshot(backup)
        val restored = assertNotNull(repository.manga(old.id))
        assertNull(repository.unfavoriteMangaIf(restored, count, modifiedAt = 5L, replacedBy = replacement))
        assertNull(
            repository.unfavoriteMangaIf(restored, repository.snapshotReplacementCount, modifiedAt = 5L, replacedBy = replacement),
            "the replacement is gone, whatever the snapshot count the caller holds",
        )
        assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(old.id))

        // The replacement is back but out of the library: the old title still keeps its place.
        val again = repository.upsertManga(replacement.copy(id = 0, favorite = false))
        val now = repository.snapshotReplacementCount
        assertNull(repository.unfavoriteMangaIf(restored, now, modifiedAt = 5L, replacedBy = again))

        // In the library, edited by the user since: it is still the replacement.
        val favorite = repository.upsertManga(again.copy(favorite = true))
        repository.upsertManga(favorite.copy(title = "Renamed"))
        assertEquals(false, repository.unfavoriteMangaIf(restored, now, modifiedAt = 5L, replacedBy = favorite)?.favorite)
        assertTrue(repository.categoryIdsOfManga(old.id).isEmpty())
    }

    @Test
    fun aReplacementTakesOverTheCategoryLinksOfTheTitleItReplaces() = runTest {
        val repository = ShinsouRepository()
        val shelf = repository.upsertCategory(Category(name = "Shelf"))
        val other = repository.upsertCategory(Category(name = "Other"))
        val old = repository.upsertManga(Manga(source = 0, url = "local://old", title = "Old", favorite = true))
        repository.setMangaCategories(old.id, listOf(shelf.id))
        // Written by the import in the default category, which gives way to the old title's shelf.
        val replacement = repository.upsertManga(Manga(source = 0, url = "local://new", title = "New", favorite = true))
        val defaultOnly = repository.categoryIdsOfManga(replacement.id)

        assertNotNull(repository.unfavoriteMangaIf(old, repository.snapshotReplacementCount, 5L, replacedBy = replacement))
        assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(replacement.id))
        assertTrue(repository.categoryIdsOfManga(old.id).isEmpty())
        assertTrue(shelf.id !in defaultOnly)

        // A replacement with shelves of its own keeps them as well.
        val older = repository.upsertManga(Manga(source = 0, url = "local://older", title = "Older", favorite = true))
        repository.setMangaCategories(older.id, listOf(other.id))
        assertNotNull(repository.unfavoriteMangaIf(older, repository.snapshotReplacementCount, 6L, replacedBy = replacement))
        assertEquals(setOf(shelf.id, other.id), repository.categoryIdsOfManga(replacement.id).toSet())
    }

    @Test
    fun aTitleInDefaultReplacedByOneOnAShelfLeavesItOnlyOnTheShelf() = runTest {
        val repository = ShinsouRepository()
        val shelf = repository.upsertCategory(Category(name = "Shelf"))
        val old = repository.upsertManga(Manga(source = 0, url = "local://old", title = "Old", favorite = true))
        repository.setMangaCategories(old.id, listOf(Category.Default.id))
        val replacement = repository.upsertManga(Manga(source = 0, url = "local://new", title = "New", favorite = true))
        repository.setMangaCategories(replacement.id, listOf(shelf.id))

        assertNotNull(repository.unfavoriteMangaIf(old, repository.snapshotReplacementCount, 5L, replacedBy = replacement))
        assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(replacement.id))
    }

    @Test
    fun aReplacementAlreadyInTheLibraryKeepsItsShelfThoughItIsTheDefaultOne() = runTest {
        val repository = ShinsouRepository()
        val shelf = repository.upsertCategory(Category(name = "Shelf"))
        val favorites = repository.upsertCategory(Category(name = "Favorites"))
        // The user's default shelf for new titles is Favorites.
        repository.updateSettings { it.copy(library = it.library.copy(defaultCategoryId = favorites.id)) }
        val old = repository.upsertManga(Manga(source = 0, url = "local://old", title = "Old", favorite = true))
        repository.setMangaCategories(old.id, listOf(shelf.id))
        val kept = repository.upsertManga(Manga(source = 0, url = "local://kept", title = "Kept", favorite = true))
        repository.setMangaCategories(kept.id, listOf(favorites.id))

        assertNotNull(
            repository.unfavoriteMangaIf(old, repository.snapshotReplacementCount, 5L, replacedBy = kept, replacedByWasInLibrary = true),
        )
        assertEquals(setOf(shelf.id, favorites.id), repository.categoryIdsOfManga(kept.id).toSet())

        // A title just added has the default shelf only as a stand-in: it gives way.
        val older = repository.upsertManga(Manga(source = 0, url = "local://older", title = "Older", favorite = true))
        repository.setMangaCategories(older.id, listOf(shelf.id))
        val added = repository.upsertManga(Manga(source = 0, url = "local://added", title = "Added", favorite = true))
        assertEquals(listOf(favorites.id), repository.categoryIdsOfManga(added.id))
        assertNotNull(repository.unfavoriteMangaIf(older, repository.snapshotReplacementCount, 6L, replacedBy = added))
        assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(added.id))
    }

    @Test
    fun aReplacedTitleGetsItsCategoryLinksBack() = runTest {
        val repository = ShinsouRepository()
        val shelf = repository.upsertCategory(Category(name = "Shelf"))
        val removedLater = repository.upsertCategory(Category(name = "Removed later"))
        val before = repository.upsertManga(Manga(source = 0, url = "local://a", title = "A", favorite = true))
        repository.setMangaCategories(before.id, listOf(shelf.id, removedLater.id))
        val links = repository.categoryIdsOfManga(before.id)
        val count = repository.snapshotReplacementCount

        val removed = assertNotNull(repository.unfavoriteMangaIf(before, count, modifiedAt = 5L))
        assertFalse(removed.favorite)
        assertTrue(repository.categoryIdsOfManga(before.id).isEmpty())
        assertNull(repository.unfavoriteMangaIf(before, count, modifiedAt = 6L), "the row is not the expected one any more")
        repository.deleteCategory(removedLater.id)

        assertFalse(repository.replaceMangaIf(before, count, before, links), "only the row the caller left is replaced")
        assertTrue(repository.replaceMangaIf(removed, count, before, links))
        assertEquals(before, repository.manga(before.id))
        // A category deleted meanwhile is not brought back with the link.
        assertEquals(listOf(shelf.id), repository.categoryIdsOfManga(before.id))
    }

    @Test
    fun aTitleAndItsChapterAreWrittenOnlyWhenTheirContentIsCommitted() = runTest {
        val repository = ShinsouRepository()
        val manga = Manga(source = 0, url = "local://a", title = "A", favorite = true)
        val chapter = { mangaId: Long -> Chapter(mangaId = mangaId, url = "local://a/1", name = "A") }

        assertNull(repository.upsertMangaWithChapterAfter(manga, chapter, commitContent = { false }))
        assertTrue(repository.currentSnapshot.mangas.isEmpty())

        val written = assertNotNull(repository.upsertMangaWithChapterAfter(manga, chapter, commitContent = { true }))
        assertNull(written.before)
        assertEquals(written.manga, repository.manga(written.manga.id))
        assertEquals(written.chapter, repository.chapter(written.chapter.id))
        assertEquals(repository.snapshotReplacementCount, written.replacementCount)

        val again = assertNotNull(repository.upsertMangaWithChapterAfter(manga, chapter, commitContent = { true }))
        assertEquals(written.manga, again.before)
        assertEquals(written.chapter.id, again.chapter.id)
    }
}
