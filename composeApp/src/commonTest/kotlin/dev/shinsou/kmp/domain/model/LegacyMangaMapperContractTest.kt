package dev.shinsou.kmp.domain.model

import dev.shinsou.kmp.data.APP_SNAPSHOT_SCHEMA_VERSION
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyMangaMapperContractTest {
    @Test
    fun fullLegacyAggregateRoundTripsWithoutLosingFieldsNullabilityFlagsOrOrder() {
        val input = completeAggregate()
        val bundle = LegacyMangaMapper.toPublication(input, InMemoryPortableAliasLedger())

        assertEquals(input, LegacyMangaMapper.toLegacy(bundle))
        assertEquals(1, bundle.publication.acquisitions.size)
        assertEquals(2, bundle.publication.units.size)
        assertEquals(input.record.source, bundle.publication.acquisitions.single().sourceBinding?.sourceKey?.legacyLongId)
        assertEquals(input.record.url, bundle.publication.acquisitions.single().legacyMangaFacet?.record?.url)
        bundle.publication.units.forEach { unit ->
            assertEquals(
                bundle.publication.acquisitions.single().sourceBinding?.remoteEntityKey,
                unit.sourceBinding?.remoteEntityKey?.parentPublication,
            )
        }
    }

    @Test
    fun uuidV5UsesTheRfc9562KnownVectorOnCommonCode() {
        val dns = MigrationNamespaceId("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            Rfc9562UuidV5.derive(dns, "www.example.com"),
        )
    }

    @Test
    fun identityIsStableAcrossMetadataChangesAndOnlyNewChapterAddsAnAlias() {
        val ledger = InMemoryPortableAliasLedger()
        val firstInput = completeAggregate().copy(categories = emptyList(), links = emptyList())
        val first = LegacyMangaMapper.toPublication(firstInput, ledger)
        val changed = firstInput.copy(record = firstInput.record.copy(title = " changed ", notes = "new notes"))
        val second = LegacyMangaMapper.toPublication(changed, ledger)
        assertEquals(first.publication.key, second.publication.key)
        assertEquals(first.publication.acquisitions.single().id, second.publication.acquisitions.single().id)
        assertEquals(first.publication.units.map { it.key }, second.publication.units.map { it.key })

        val newChapter = firstInput.chapters.last().copy(
            id = 103,
            url = " /chapter/103 ",
            legacyListOrdinal = 2,
        )
        val before = ledger.snapshot().map { it.alias }.toSet()
        val third = LegacyMangaMapper.toPublication(
            firstInput.copy(chapters = firstInput.chapters + newChapter),
            ledger,
        )
        val after = ledger.snapshot().map { it.alias }.toSet()
        assertEquals(1, after.size - before.size)
        assertEquals(first.publication.units.map { it.key }, third.publication.units.take(2).map { it.key })
    }

    @Test
    fun namespacesAndSourcesDoNotMergeTitleEqualPublications() {
        val input = completeAggregate().copy(categories = emptyList(), links = emptyList())
        val otherNamespace = MigrationNamespaceId("11111111-1111-5111-8111-111111111111")
        val first = LegacyMangaMapper.toPublication(input, InMemoryPortableAliasLedger())
        val namespaced = LegacyMangaMapper.toPublication(input, InMemoryPortableAliasLedger(), otherNamespace)
        assertNotEquals(first.publication.key, namespaced.publication.key)

        val otherSourceInput = input.copy(record = input.record.copy(source = -778L))
        val otherSource = LegacyMangaMapper.toPublication(otherSourceInput, InMemoryPortableAliasLedger())
        assertEquals(first.publication.title, otherSource.publication.title)
        assertNotEquals(first.publication.key, otherSource.publication.key)
    }

    @Test
    fun aliasCollisionRollsBackTheWholeLedgerTransaction() {
        val ledger = InMemoryPortableAliasLedger()
        val namespace = MigrationNamespaceId.LEGACY_MANGA_V1
        val existingAlias = LegacyAliasKey.Manga(1, 1)
        val existingUuid = "aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa"
        ledger.resolveOrBind(namespace, existingAlias, existingUuid)
        val newAlias = LegacyAliasKey.Manga(2, 1)
        val collidingAlias = LegacyAliasKey.Chapter(2, 3, 1)

        assertFailsWith<PortableAliasException.UuidCollision> {
            ledger.resolveOrBindAll(
                listOf(
                    PortableAliasRequest(namespace, newAlias, "bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb"),
                    PortableAliasRequest(namespace, collidingAlias, existingUuid),
                ),
            )
        }
        assertNull(ledger.resolve(namespace, newAlias))
        assertEquals(existingUuid, ledger.resolve(namespace, existingAlias)?.portableUuid)
    }

    @Test
    fun mapperRejectsDuplicateIdsAndMissingCompatibilityFacetsOrAliases() {
        val input = completeAggregate()
        assertFailsWith<IllegalArgumentException> {
            input.copy(chapters = listOf(input.chapters.first(), input.chapters.first()))
        }

        val bundle = LegacyMangaMapper.toPublication(input, InMemoryPortableAliasLedger())
        val acquisition = bundle.publication.acquisitions.single()
        val missingFacet = bundle.copy(
            publication = bundle.publication.copy(
                acquisitions = listOf(acquisition.copy(legacyCompatibilityFacet = null)),
            ),
        )
        assertFailsWith<IllegalArgumentException> { LegacyMangaMapper.toLegacy(missingFacet) }
        assertFailsWith<IllegalArgumentException> {
            LegacyMangaMapper.toLegacy(bundle.copy(aliases = bundle.aliases.dropLast(1)))
        }
    }

    @Test
    fun legacyProjectionCoexistsWithAdditionalAcquisitions() {
        val input = completeAggregate()
        val bundle = LegacyMangaMapper.toPublication(input, InMemoryPortableAliasLedger())
        val localAcquisition = Acquisition(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            origin = AcquisitionOrigin.LocalText,
        )
        val withLocalImport = bundle.copy(
            publication = bundle.publication.copy(
                acquisitions = bundle.publication.acquisitions + localAcquisition,
            ),
        )

        assertEquals(2, withLocalImport.publication.acquisitions.size)
        assertEquals(input, LegacyMangaMapper.toLegacy(withLocalImport))
    }

    @Test
    fun opaqueIdsRemainExactAndLegacyProjectionDoesNotChangeSnapshotWire() {
        val exact = SourceKey(contractVersion = 2, packageId = "pkg", sourceId = " id ")
        val trimmed = SourceKey(contractVersion = 2, packageId = "pkg", sourceId = "id")
        assertNotEquals(exact, trimmed)
        assertEquals(" id ", exact.sourceId)

        assertEquals(1, APP_SNAPSHOT_SCHEMA_VERSION)
        val encoded = AppSnapshotJson.encode(AppSnapshot())
        assertFalse(encoded.contains("legacyCompatibilityFacet"))
        assertFalse(encoded.contains("ContentManifest"))
        assertTrue(encoded.contains("\"schemaVersion\":1"))
    }

    private fun completeAggregate(): LegacyMangaAggregateV1 {
        val manga = LegacyMangaRecordV1(
            id = 41,
            source = -777,
            favorite = true,
            lastUpdate = 1_001,
            nextUpdate = 2_002,
            fetchInterval = 17,
            dateAdded = 3_003,
            viewerFlags = 0x1234,
            chapterFlags = 0x5678,
            coverLastModified = 4_004,
            url = " /book/41?x=%20 ",
            title = "Same title",
            artist = null,
            author = "Author",
            description = "",
            genre = emptyList(),
            status = 6,
            thumbnailUrl = "https://example.test/cover.jpg",
            updateStrategy = 1,
            initialized = true,
            lastModifiedAt = 5_005,
            favoriteModifiedAt = null,
            version = 19,
            notes = "notes\nkept",
            excludedScanlators = linkedSetOf("B", "A"),
            legacyListOrdinal = 7,
        )
        val chapters = listOf(
            LegacyChapterRecordV1(
                id = 101,
                mangaId = 41,
                url = " /chapter/101 ",
                name = "",
                scanlator = null,
                read = true,
                bookmark = false,
                lastPageRead = 11,
                chapterNumber = 1.5,
                sourceOrder = 9,
                dateFetch = 6_006,
                dateUpload = 7_007,
                lastModifiedAt = 8_008,
                version = 3,
                legacyListOrdinal = 0,
            ),
            LegacyChapterRecordV1(
                id = 102,
                mangaId = 41,
                url = "/chapter/102",
                name = "Chapter 2",
                scanlator = "",
                read = false,
                bookmark = true,
                lastPageRead = 0,
                chapterNumber = -1.0,
                sourceOrder = 2,
                dateFetch = 9_009,
                dateUpload = 0,
                lastModifiedAt = 10_010,
                version = 4,
                legacyListOrdinal = 1,
            ),
        )
        val categories = listOf(
            LegacyCategoryRecordV1(id = 0, name = "Default", sort = 3, flags = 0x40, legacyListOrdinal = 0),
            LegacyCategoryRecordV1(id = 9, name = "Custom", sort = 1, flags = 0x22, legacyListOrdinal = 1),
        )
        val links = listOf(
            LegacyMangaCategoryLinkV1(mangaId = 41, categoryId = 9, legacyListOrdinal = 0),
            LegacyMangaCategoryLinkV1(mangaId = 41, categoryId = 0, legacyListOrdinal = 1),
        )
        return LegacyMangaAggregateV1(record = manga, chapters = chapters, categories = categories, links = links)
    }
}
