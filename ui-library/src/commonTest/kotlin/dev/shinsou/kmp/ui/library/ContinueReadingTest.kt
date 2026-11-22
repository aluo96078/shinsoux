@file:OptIn(dev.shinsou.kmp.testing.TestOnlyApi::class)

package dev.shinsou.kmp.ui.library

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.LibraryItem
import dev.shinsou.kmp.domain.model.LibraryManga
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.plugin.PluginTestAccess
import dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueReadingTest {
    @Test
    fun continueReadingUsesTheExactLatestHistoryChapter() {
        val snapshot = AppSnapshot(
            mangas = listOf(Manga(id = 1), Manga(id = 2)),
            chapters = listOf(
                Chapter(id = 10, mangaId = 1, sourceOrder = 3, read = true),
                Chapter(id = 11, mangaId = 1, sourceOrder = 2, read = false),
                Chapter(id = 12, mangaId = 1, sourceOrder = 1, read = false),
                Chapter(id = 20, mangaId = 2, sourceOrder = 1, read = false),
            ),
            histories = listOf(
                History(id = 1, chapterId = 11, lastRead = 100, timeRead = 0),
                History(id = 2, chapterId = 10, lastRead = 300, timeRead = 0),
                History(id = 3, chapterId = 20, lastRead = 200, timeRead = 0),
            ),
        )

        assertEquals(10L, continueChapter(snapshot, mangaId = 1)?.id)
        assertEquals(10L, latestHistoryChapter(snapshot, allowedMangaIds = setOf(1))?.id)
        assertEquals(20L, latestHistoryChapter(snapshot, allowedMangaIds = setOf(2))?.id)
        assertEquals(10L, latestHistoryChapter(snapshot)?.id)
    }

    @Test
    fun mangaWithoutHistoryFallsBackToStoryOrder() {
        val snapshot = AppSnapshot(
            mangas = listOf(Manga(id = 1)),
            chapters = listOf(
                Chapter(id = 2, mangaId = 1, sourceOrder = 0, read = false),
                Chapter(id = 1, mangaId = 1, sourceOrder = 1, read = true),
            ),
        )

        assertEquals(2L, continueChapter(snapshot, mangaId = 1)?.id)
    }

    private val sourceKey = SourceKey(packageId = "pkg", sourceId = "novels")

    private fun extensionContinue(reading: ExtensionLocalReadingStateV2, unitIds: List<String>) = runTest {
        val publication = RemotePublicationV2(remoteId = "p1", title = "Novel")
        val units = unitIds.map { id ->
            PluginTestAccess.unitSelection(sourceKey, "p1", "Novel", RemoteUnitV2(id, "Chapter $id"), Any())
        }
        val browse = object : EmptyBrowseCallbacks() {
            override suspend fun cachedExtensionPublicationPageV2(sourceKey: SourceKey, remotePublicationId: String) =
                PluginTestAccess.publicationPage(sourceKey, publication, units, 0 to false, true)

            override fun extensionLocalReadingStateV2(publicationKey: PublicationKey) = reading

            override fun extensionLocalReadingStateV2(
                sourceKey: SourceKey,
                remotePublicationId: String,
                remoteUnitIds: Collection<String>,
            ) = reading.also { assertEquals(unitIds, remoteUnitIds.toList(), "asked with the saved chapter list") }
        }
        // A library row with no chapter stored here: only the synced reading knows the place.
        val manga = Manga(id = 1, url = encodeExtensionLibraryPublicationUrl(sourceKey, "p1"), favorite = true)
        val item = LibraryItem(LibraryManga(manga, lastRead = 0))
        result = continueReadingOf(item, AppSnapshot(mangas = listOf(manga)), emptyMap(), browse, shinsouStringsFor("en"))
    }

    private var result: LibraryContinueReading? = null

    @Test
    fun anExtensionTitleContinuesWhereAnyDeviceLeftIt() {
        extensionContinue(
            ExtensionLocalReadingStateV2(
                completedRemoteUnitIds = setOf("u1"),
                lastReadRemoteUnitId = "u3",
                inProgressRemoteUnits = mapOf("u3" to ExtensionUnitPageProgressV2(13, 45)),
            ),
            listOf("u1", "u2", "u3"),
        )
        val continuing = assertNotNull(result)
        assertEquals("u3", continuing.extensionUnitId, "the chapter read last, not the first unread one")
        val detail = assertNotNull(continuing.detail)
        assertTrue("Chapter u3" in detail && "14" in detail && "45" in detail, detail)
    }

    @Test
    fun aFinishedExtensionTitleHasNothingToContinue() {
        extensionContinue(
            ExtensionLocalReadingStateV2(completedRemoteUnitIds = setOf("u1", "u2"), lastReadRemoteUnitId = "u2"),
            listOf("u1", "u2"),
        )
        assertNull(result)
    }
}
