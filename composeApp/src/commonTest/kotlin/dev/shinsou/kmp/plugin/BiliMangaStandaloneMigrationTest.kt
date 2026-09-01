package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.migration.shuyue.shuyueCompatibilityProjectionHandoffMarker
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class BiliMangaStandaloneMigrationTest {
    @Test
    fun convertsLibraryAndChapterRoutingWithoutChangingUserRelationships() {
        val remotePublicationId = "https://www.bilimanga.net/detail/86.html"
        val publicationKey = extensionPublicationKey(LEGACY_BILIMANGA_MANGA_SOURCE_KEY, remotePublicationId)
        val acquisitionId = "11111111-1111-5111-8111-111111111111"
        val firstUnitKey = UnitKey(publicationKey, "22222222-2222-5222-8222-222222222222")
        val secondUnitKey = UnitKey(publicationKey, "33333333-3333-5333-8333-333333333333")
        val firstUrl = "https://www.bilimanga.net/read/86/100.html"
        val secondUrl = "https://www.bilimanga.net/read/86/101.html"
        val snapshot = AppSnapshot(
            mangas = listOf(
                Manga(
                    id = 7,
                    source = LOCAL_SOURCE_ID,
                    favorite = true,
                    url = encodeExtensionLibraryPublicationUrl(
                        LEGACY_BILIMANGA_MANGA_SOURCE_KEY,
                        remotePublicationId,
                    ),
                    title = "86",
                    initialized = true,
                    notes = "keep",
                ),
            ),
            chapters = listOf(
                Chapter(
                    id = 70,
                    mangaId = 7,
                    url = encodeTypedLocalChapterUrl(publicationKey, acquisitionId, firstUnitKey),
                    name = "First",
                    read = true,
                    lastPageRead = 12,
                ),
                Chapter(
                    id = 71,
                    mangaId = 7,
                    url = encodeTypedLocalChapterUrl(publicationKey, acquisitionId, secondUnitKey),
                    name = "Second",
                    bookmark = true,
                    lastPageRead = 4,
                ),
            ),
            categories = listOf(Category.Default, Category(id = 5, name = "Keep")),
            mangaCategories = listOf(MangaCategory(7, 5)),
            histories = listOf(History(id = 9, chapterId = 71, lastRead = 999, lastPageCount = 20)),
            downloadQueue = listOf(
                DownloadQueueItem(id = "download", mangaId = 7, chapterId = 70),
            ),
        ).validate()
        val publication = publication(
            publicationKey = publicationKey,
            acquisitionId = acquisitionId,
            units = listOf(firstUnitKey to firstUrl, secondUnitKey to secondUrl),
            remotePublicationId = remotePublicationId,
        )

        val migrated = migrateBiliMangaStandaloneSnapshot(snapshot, listOf(publication))

        assertEquals(BiliMangaStandaloneLibraryMigrationResult(1, 2), migrated.result)
        assertEquals(BILIMANGA_MANGA_SOURCE_ID, migrated.snapshot.mangas.single().source)
        assertEquals(remotePublicationId, migrated.snapshot.mangas.single().url)
        assertFalse(migrated.snapshot.mangas.single().initialized)
        assertEquals("keep", migrated.snapshot.mangas.single().notes)
        assertEquals(listOf(firstUrl, secondUrl), migrated.snapshot.chapters.map { it.url })
        assertEquals(listOf(70L, 71L), migrated.snapshot.chapters.map { it.id })
        assertEquals(listOf(12, 4), migrated.snapshot.chapters.map { it.lastPageRead })
        assertEquals(snapshot.histories, migrated.snapshot.histories)
        assertEquals(snapshot.mangaCategories, migrated.snapshot.mangaCategories)
        assertEquals(snapshot.downloadQueue, migrated.snapshot.downloadQueue)
        assertEquals(
            setOf(shuyueCompatibilityProjectionHandoffMarker(publicationKey)),
            migrated.snapshot.contentAuthorityProjectionMarkers,
        )
    }

    @Test
    fun defersARecordWhenAnyTypedChapterBindingIsUnavailable() {
        val remotePublicationId = "https://www.bilimanga.net/detail/86.html"
        val publicationKey = extensionPublicationKey(LEGACY_BILIMANGA_MANGA_SOURCE_KEY, remotePublicationId)
        val missingUnit = UnitKey(publicationKey, "44444444-4444-5444-8444-444444444444")
        val snapshot = AppSnapshot(
            mangas = listOf(
                Manga(
                    id = 7,
                    source = LOCAL_SOURCE_ID,
                    favorite = true,
                    url = encodeExtensionLibraryPublicationUrl(
                        LEGACY_BILIMANGA_MANGA_SOURCE_KEY,
                        remotePublicationId,
                    ),
                ),
            ),
            chapters = listOf(
                Chapter(
                    id = 70,
                    mangaId = 7,
                    url = encodeTypedLocalChapterUrl(
                        publicationKey,
                        "11111111-1111-5111-8111-111111111111",
                        missingUnit,
                    ),
                ),
            ),
        ).validate()

        val migrated = migrateBiliMangaStandaloneSnapshot(snapshot, emptyList())

        assertSame(snapshot, migrated.snapshot)
        assertEquals(BiliMangaStandaloneLibraryMigrationResult(0, 0), migrated.result)
    }

    @Test
    fun ignoresAReviewedBindingThatIsNotAnAppLocalFavorite() {
        val remotePublicationId = "https://www.bilimanga.net/detail/86.html"
        val snapshot = AppSnapshot(
            mangas = listOf(
                Manga(
                    id = 7,
                    source = 42,
                    favorite = true,
                    url = encodeExtensionLibraryPublicationUrl(
                        LEGACY_BILIMANGA_MANGA_SOURCE_KEY,
                        remotePublicationId,
                    ),
                ),
            ),
        ).validate()

        val migrated = migrateBiliMangaStandaloneSnapshot(snapshot, emptyList())

        assertSame(snapshot, migrated.snapshot)
        assertEquals(BiliMangaStandaloneLibraryMigrationResult(0, 0), migrated.result)
    }

    private fun publication(
        publicationKey: PublicationKey,
        acquisitionId: String,
        units: List<Pair<UnitKey, String>>,
        remotePublicationId: String,
    ): Publication {
        val publicationBinding = SourceBinding(
            sourceKey = LEGACY_BILIMANGA_MANGA_SOURCE_KEY,
            remoteId = remotePublicationId,
        )
        return Publication(
            key = publicationKey,
            title = "86",
            acquisitions = listOf(
                Acquisition(
                    id = acquisitionId,
                    origin = AcquisitionOrigin.ExtensionSource(publicationBinding),
                    units = units.map { (unitKey, url) ->
                        PublicationUnit(
                            key = unitKey,
                            title = url,
                            sourceBinding = SourceBinding(
                                sourceKey = LEGACY_BILIMANGA_MANGA_SOURCE_KEY,
                                remoteId = url,
                                canonicalUrl = url,
                                entityKind = RemoteEntityKind.UNIT,
                                parentPublication = publicationBinding.remoteEntityKey,
                            ),
                        )
                    },
                ),
            ),
        )
    }
}
