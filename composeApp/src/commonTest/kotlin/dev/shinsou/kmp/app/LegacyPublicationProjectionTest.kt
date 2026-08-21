package dev.shinsou.kmp.app

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.InMemoryPortableAliasLedger
import dev.shinsou.kmp.domain.model.LegacyMangaMapper
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LegacyPublicationProjectionTest {
    @Test
    fun projectsScopedInterleavedChaptersAndLinksWhileRetainingTheFullCategoryCatalog() {
        val snapshot = AppSnapshot(
            mangas = listOf(
                Manga(
                    id = 10,
                    source = 100,
                    favorite = true,
                    lastUpdate = 101,
                    nextUpdate = 102,
                    fetchInterval = 12,
                    dateAdded = 103,
                    viewerFlags = 104,
                    chapterFlags = 105,
                    coverLastModified = 106,
                    url = "/manga/10?raw=%2F",
                    title = "First",
                    artist = "Artist",
                    author = "Author",
                    description = "Description",
                    genre = listOf("Mystery", "奇幻"),
                    status = 107,
                    thumbnailUrl = "https://example.invalid/cover-10.png",
                    updateStrategy = 108,
                    initialized = true,
                    lastModifiedAt = 109,
                    favoriteModifiedAt = 110,
                    version = 111,
                    notes = "legacy notes",
                    excludedScanlators = linkedSetOf("A", "翻譯組"),
                ),
                Manga(
                    id = 20,
                    source = 200,
                    favorite = false,
                    url = "/manga/20",
                    title = "Second",
                    artist = null,
                    author = "Second Author",
                    genre = null,
                    thumbnailUrl = null,
                    favoriteModifiedAt = null,
                ),
            ),
            chapters = listOf(
                Chapter(
                    id = 101,
                    mangaId = 10,
                    url = "/chapter/101?raw=%2F",
                    name = "First A",
                    scanlator = "翻譯組",
                    read = true,
                    bookmark = true,
                    lastPageRead = 17,
                    chapterNumber = 1.5,
                    sourceOrder = 9,
                    dateFetch = 201,
                    dateUpload = 202,
                    lastModifiedAt = 203,
                    version = 204,
                ),
                Chapter(
                    id = 201,
                    mangaId = 20,
                    url = "/chapter/201",
                    name = "Second A",
                    scanlator = null,
                    chapterNumber = Double.NaN,
                ),
                Chapter(
                    id = 102,
                    mangaId = 10,
                    url = "/chapter/102",
                    name = "First B",
                    read = false,
                    bookmark = true,
                    lastPageRead = 3,
                    chapterNumber = -0.0,
                ),
            ),
            categories = listOf(
                Category(id = 2, name = "Archive", sort = 2),
                Category.Default,
                Category(id = 1, name = "Reading", sort = 1, flags = 99),
            ),
            mangaCategories = listOf(
                MangaCategory(mangaId = 20, categoryId = 2),
                MangaCategory(mangaId = 10, categoryId = 1),
            ),
        )

        val projected = LegacyPublicationProjection(InMemoryPortableAliasLedger()).project(snapshot)
        assertEquals(listOf("First", "Second"), projected.map { it.publication.title })

        val firstLegacy = LegacyMangaMapper.toLegacy(projected[0])
        val secondLegacy = LegacyMangaMapper.toLegacy(projected[1])
        assertEquals(listOf(101L, 102L), firstLegacy.chapters.map { it.id })
        assertEquals(listOf(201L), secondLegacy.chapters.map { it.id })
        assertEquals(listOf(0, 2), firstLegacy.chapters.map { it.legacyListOrdinal })
        assertEquals(listOf(1), secondLegacy.chapters.map { it.legacyListOrdinal })
        assertEquals(listOf(2L, 0L, 1L), firstLegacy.categories.map { it.id })
        assertEquals(listOf(2L, 0L, 1L), secondLegacy.categories.map { it.id })
        assertEquals(listOf(10L to 1L), firstLegacy.links.map { it.mangaId to it.categoryId })
        assertEquals(listOf(20L to 2L), secondLegacy.links.map { it.mangaId to it.categoryId })
        assertEquals(listOf(1), firstLegacy.links.map { it.legacyListOrdinal })
        assertEquals(listOf(0), secondLegacy.links.map { it.legacyListOrdinal })

        val compatibilityBase = snapshot.copy(
            mangas = emptyList(),
            chapters = emptyList(),
            categories = emptyList(),
            mangaCategories = emptyList(),
        )
        assertEquals(snapshot, LegacyPublicationProjection(InMemoryPortableAliasLedger()).materialize(
            bundles = projected.reversed(),
            baseSnapshot = compatibilityBase,
        ))

        assertNotEquals(projected[0].publication.key, projected[1].publication.key)
        val fromFreshLedger = LegacyPublicationProjection(InMemoryPortableAliasLedger()).project(snapshot)
        assertEquals(
            projected.map { bundle ->
                Triple(
                    bundle.publication.key,
                    bundle.publication.acquisitions.single().id,
                    bundle.publication.units.map { it.key },
                )
            },
            fromFreshLedger.map { bundle ->
                Triple(
                    bundle.publication.key,
                    bundle.publication.acquisitions.single().id,
                    bundle.publication.units.map { it.key },
                )
            },
        )

        assertFailsWith<IllegalArgumentException> {
            LegacyPublicationProjection(InMemoryPortableAliasLedger()).materialize(
                bundles = projected.drop(1),
                baseSnapshot = compatibilityBase,
            )
        }
    }
}
