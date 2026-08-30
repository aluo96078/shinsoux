package dev.shinsou.kmp.sync

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.AppearanceSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotConflictResolverTest {
    @Test
    fun specialReadingFieldsMergeWhileRemainingFieldsUseLww() {
        val local = AppSnapshot(
            revision = 4,
            settings = AppSettings(appearance = AppearanceSettings(theme = ThemeMode.LIGHT)),
            mangas = listOf(
                Manga(id = 1, source = 1, favorite = true, url = "/m", title = "Local", lastModifiedAt = 100),
            ),
            chapters = listOf(
                Chapter(
                    id = 10,
                    mangaId = 1,
                    url = "/c",
                    name = "Local chapter",
                    read = false,
                    lastPageRead = 8,
                    lastModifiedAt = 100,
                ),
            ),
            histories = listOf(History(id = 10, chapterId = 10, lastRead = 100)),
            mangaCategories = listOf(MangaCategory(1, Category.Default.id)),
            tracks = listOf(Track(id = 1, mangaId = 1, trackerId = 2, title = "Local track", lastChapterRead = 12.0)),
        )
        val remote = local.copy(
            revision = 7,
            settings = AppSettings(appearance = AppearanceSettings(theme = ThemeMode.DARK)),
            mangas = listOf(local.mangas.single().copy(title = "Remote", lastModifiedAt = 200)),
            chapters = listOf(
                local.chapters.single().copy(
                    name = "Remote chapter",
                    read = true,
                    lastPageRead = 3,
                    lastModifiedAt = 200,
                ),
            ),
            histories = listOf(History(id = 10, chapterId = 10, lastRead = 200)),
            tracks = listOf(local.tracks.single().copy(id = 2, title = "Remote track", lastChapterRead = 5.0)),
        )

        val result = SnapshotConflictResolver.merge(
            local = SnapshotReplica(local, modifiedAt = 100, deviceId = "a"),
            remote = SnapshotReplica(remote, modifiedAt = 200, deviceId = "b"),
        )
        val chapter = result.snapshot.chapters.single()
        val track = result.snapshot.tracks.single()

        assertEquals("Remote", result.snapshot.mangas.single().title)
        assertEquals(ThemeMode.DARK, result.snapshot.settings.appearance.theme)
        assertEquals("Remote chapter", chapter.name)
        assertTrue(chapter.read)
        assertEquals(3, chapter.lastPageRead)
        assertEquals("Remote track", track.title)
        assertEquals(12.0, track.lastChapterRead)
        assertTrue(result.conflicts.any { it.entity == "chapter" && it.winner == ConflictWinner.MERGED })
        assertTrue(result.conflicts.any { it.entity == "track" && it.winner == ConflictWinner.MERGED })
        result.snapshot.validate()
    }

    @Test
    fun newerReadingCursorDoesNotReplaceNewerChapterMetadata() {
        val local = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/m", title = "Title")),
            chapters = listOf(
                Chapter(
                    id = 10,
                    mangaId = 1,
                    url = "/c",
                    name = "Older metadata",
                    lastPageRead = 7,
                    lastModifiedAt = 100,
                ),
            ),
            histories = listOf(History(id = 10, chapterId = 10, lastRead = 300)),
        )
        val remote = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/m", title = "Title")),
            chapters = listOf(
                Chapter(
                    id = 10,
                    mangaId = 1,
                    url = "/c",
                    name = "Newer metadata",
                    lastPageRead = 2,
                    lastModifiedAt = 200,
                ),
            ),
            histories = listOf(History(id = 10, chapterId = 10, lastRead = 200)),
        )

        val merged = SnapshotConflictResolver.merge(
            local = SnapshotReplica(local, modifiedAt = 300, deviceId = "local"),
            remote = SnapshotReplica(remote, modifiedAt = 200, deviceId = "remote"),
        ).snapshot

        assertEquals("Newer metadata", merged.chapters.single().name)
        assertEquals(7, merged.chapters.single().lastPageRead)
        assertEquals(300, merged.histories.single().lastRead)
        merged.validate()
    }

    @Test
    fun olderRemoteLosesOrdinaryLwwFieldsButReadStillUsesOr() {
        val local = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/m", title = "New local")),
            chapters = listOf(Chapter(id = 10, mangaId = 1, url = "/c", name = "New local chapter", read = false)),
        )
        val remote = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/m", title = "Old remote")),
            chapters = listOf(Chapter(id = 10, mangaId = 1, url = "/c", name = "Old remote chapter", read = true)),
        )

        val merged = SnapshotConflictResolver.merge(local, remote, localModifiedAt = 20, remoteModifiedAt = 10).snapshot

        assertEquals("New local", merged.mangas.single().title)
        assertEquals("New local chapter", merged.chapters.single().name)
        assertTrue(merged.chapters.single().read)
    }

    @Test
    fun idCollisionsForDifferentNaturalKeysAreRemappedWithoutLosingRecords() {
        val local = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/local", title = "Local")),
            chapters = listOf(Chapter(id = 10, mangaId = 1, url = "/local-c", name = "Local C")),
        )
        val remote = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 2, url = "/remote", title = "Remote")),
            chapters = listOf(Chapter(id = 10, mangaId = 1, url = "/remote-c", name = "Remote C")),
        )

        val result = SnapshotConflictResolver.merge(local, remote, localModifiedAt = 1, remoteModifiedAt = 2)

        assertEquals(2, result.snapshot.mangas.size)
        assertEquals(2, result.snapshot.chapters.size)
        assertEquals(2, result.snapshot.mangas.map { it.id }.distinct().size)
        assertEquals(2, result.snapshot.chapters.map { it.id }.distinct().size)
        assertEquals(1, result.summary.remappedMangaIds)
        assertEquals(1, result.summary.remappedChapterIds)
        assertFalse(result.snapshot.chapters.any { chapter -> result.snapshot.mangas.none { it.id == chapter.mangaId } })
        result.snapshot.validate()
    }

    @Test
    fun downloadQueueAlwaysRemainsLocalRuntimeState() {
        val manga = Manga(id = 1, source = 1, url = "/m", title = "M")
        val chapter = Chapter(id = 10, mangaId = 1, url = "/c", name = "C")
        val localDownload = DownloadQueueItem(
            id = DownloadQueueItem.id(1, 10),
            mangaId = 1,
            chapterId = 10,
            state = DownloadState.DOWNLOADED,
            downloadedPages = 2,
            totalPages = 2,
        )
        val remoteDownload = localDownload.copy(
            state = DownloadState.QUEUED,
            downloadedPages = 0,
            totalPages = 0,
            updatedAt = 999,
        )
        val local = AppSnapshot(mangas = listOf(manga), chapters = listOf(chapter), downloadQueue = listOf(localDownload))
        val remote = AppSnapshot(mangas = listOf(manga), chapters = listOf(chapter), downloadQueue = listOf(remoteDownload))

        val result = SnapshotConflictResolver.merge(local, remote, localModifiedAt = 1, remoteModifiedAt = 2)

        assertEquals(listOf(localDownload), result.snapshot.downloadQueue)
        assertFalse(result.conflicts.any { it.entity == "download" })
    }
}
