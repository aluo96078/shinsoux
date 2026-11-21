package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShinsouRepositoryRebindTest {
    private suspend fun seeded(): Triple<ShinsouRepository, Manga, List<Chapter>> {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 0, url = "local://old", title = "Old", favorite = true))
        val chapters = repository.upsertChapters(
            listOf(
                Chapter(mangaId = manga.id, url = "old/1", name = "1", read = true),
                Chapter(mangaId = manga.id, url = "old/2", name = "2", lastPageRead = 4),
            ),
        )
        repository.recordHistory(chapters[1].id, lastRead = 10)
        repository.recordHistory(chapters[0].id, lastRead = 5)
        return Triple(repository, manga, chapters)
    }

    @Test
    fun rebindingChangesTheRowAndItsChaptersInOneCommit() = runTest {
        val (repository, manga, chapters) = seeded()
        repository.rebindLibraryTitle(
            mangaId = manga.id,
            expectedUrl = "local://old",
            transform = { it.copy(url = "local://new", title = "New") },
            rewrittenChapters = listOf(chapters[1].copy(url = "new/2")),
            removedChapterIds = listOf(chapters[0].id),
        )
        val snapshot = repository.currentSnapshot
        val row = snapshot.mangas.single { it.id == manga.id }
        assertEquals("local://new", row.url)
        assertEquals(listOf("new/2"), snapshot.chapters.filter { it.mangaId == manga.id }.map { it.url })
        assertEquals(4, snapshot.chapters.single { it.id == chapters[1].id }.lastPageRead)
        assertEquals(listOf(chapters[1].id), snapshot.histories.map { it.chapterId })
        assertTrue(snapshot.mangaCategories.any { it.mangaId == manga.id })
    }

    @Test
    fun aStalePlanChangesNothing() = runTest {
        val (repository, manga, chapters) = seeded()
        val before = repository.currentSnapshot
        // A chapter appeared after the plan was made.
        repository.upsertChapters(listOf(Chapter(mangaId = manga.id, url = "old/3", name = "3")))
        val withNewChapter = repository.currentSnapshot
        assertFailsWith<RepositoryConstraintException> {
            repository.rebindLibraryTitle(
                manga.id, "local://old", { it.copy(url = "local://new") },
                listOf(chapters[1].copy(url = "new/2")), listOf(chapters[0].id),
            )
        }
        // The row moved elsewhere meanwhile.
        assertFailsWith<RepositoryConstraintException> {
            repository.rebindLibraryTitle(manga.id, "local://other", { it.copy(url = "local://new") }, emptyList(), emptyList())
        }
        // The target is already another library row.
        repository.upsertManga(Manga(source = 0, url = "local://taken", title = "Taken", favorite = true))
        val all = withNewChapter.chapters.filter { it.mangaId == manga.id }
        assertFailsWith<RepositoryConstraintException> {
            repository.rebindLibraryTitle(manga.id, "local://old", { it.copy(url = "local://taken") }, all, emptyList())
        }
        assertEquals("local://old", repository.currentSnapshot.mangas.single { it.id == manga.id }.url)
        assertEquals(withNewChapter.chapters, repository.currentSnapshot.chapters)
        assertEquals(before.histories, repository.currentSnapshot.histories)
    }
}
