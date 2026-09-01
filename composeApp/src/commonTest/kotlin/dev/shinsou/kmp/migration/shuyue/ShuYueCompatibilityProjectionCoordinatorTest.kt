package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.ContentAliasMutation
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.content.ContentPortableAuxiliaryState
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ShuYueCompatibilityProjectionCoordinatorTest {
    @Test
    fun projectsMultiUnitRemotePublicationWithoutFeedingLegacyObserver() = runTest {
        val fixture = fixture()
        val repository = ShinsouRepository()
        var observerCalls = 0
        repository.setMutationObserver { _, _ -> observerCalls++ }
        val coordinator = coordinator(repository, fixture)

        val result = coordinator.repair()

        assertEquals(0, observerCalls)
        assertEquals(1, result.mangasCreated)
        assertEquals(2, result.chaptersCreated)
        assertEquals(1, result.categoriesCreated)
        assertEquals(1, result.membershipsCreated)
        assertEquals(1, result.historiesCreated)
        assertEquals(1, result.projectionMarkersCreated)
        assertEquals(1, result.categoryBindingsCreated)
        val manga = repository.currentSnapshot.mangas.single()
        assertEquals(0L, manga.source)
        assertEquals("Book", manga.title)
        assertTrue(manga.url.endsWith(fixture.publication.key.value))
        val chapters = repository.currentSnapshot.chapters.sortedBy { it.sourceOrder }
        assertEquals(listOf("First", "Second"), chapters.map { it.name })
        assertTrue(chapters.all { it.url.startsWith("${manga.url}/") })
        assertEquals(0, chapters.first().lastPageRead)
        assertFalse(chapters.first().read)
        assertEquals(888L, repository.currentSnapshot.histories.single().lastRead)
        assertEquals("Reading", repository.categoriesForManga(manga.id).single().name)
        assertEquals(
            shuyueLegacyCategoryId(fixture.categoryId),
            repository.categoriesForManga(manga.id).single().id,
        )

        val settings = repository.currentSnapshot.settings
        assertEquals("en", settings.general.languagePreference)
        assertEquals(ThemeMode.DARK, settings.appearance.theme)
        assertTrue(settings.appearance.amoledDark)
        assertEquals("green", settings.appearance.tintColor)
        assertEquals(20f, settings.reader.novelFontSizeSp)
        assertEquals(1.7f, settings.reader.novelLineHeightMultiplier)
        assertFalse(settings.reader.keepScreenOn)
        assertTrue(settings.reader.volumeKeys)
        assertFalse(settings.sync.syncOnForeground)
        assertFalse(settings.browse.showNsfwSources)
        assertTrue(settings.security.appLockEnabled)
        assertTrue(settings.security.secureScreen)
        assertTrue(settings.security.incognitoMode)
    }

    @Test
    fun durableOneShotMarkerPreservesUserRowsSettingsAndCategoryRemovalDuringRepair() = runTest {
        val fixture = fixture()
        val repository = ShinsouRepository()
        val coordinator = coordinator(repository, fixture)
        coordinator.repair()
        val firstSnapshot = repository.currentSnapshot
        val manga = firstSnapshot.mangas.single()
        val retainedChapter = firstSnapshot.chapters.first()
        val removedChapter = firstSnapshot.chapters.last()
        val importedCategory = firstSnapshot.categories.single { it.id != 0L }

        repository.updateManga(manga.id) {
            it.copy(title = "User title", favorite = false, notes = "keep me")
        }
        repository.patchChapter(
            retainedChapter.id,
            ChapterPatch(name = "User chapter", read = true, lastPageRead = 7),
        )
        repository.deleteChapter(removedChapter.id)
        repository.renameCategory(importedCategory.id, "User category")
        repository.updateSettings {
            it.copy(
                appearance = it.appearance.copy(theme = ThemeMode.LIGHT, amoledDark = false),
                reader = it.reader.copy(keepScreenOn = true, volumeKeys = false),
                security = it.security.copy(appLockEnabled = false),
            )
        }
        val persisted = ShinsouRepository.decodeSnapshot(repository.exportSnapshot())
        val restarted = ShinsouRepository(persisted)
        val restartCoordinator = coordinator(restarted, fixture)

        val repaired = restartCoordinator.repair()

        assertEquals(0, repaired.mangasCreated)
        assertEquals(1, repaired.chaptersCreated)
        assertEquals(0, repaired.categoriesCreated)
        assertEquals(0, repaired.membershipsCreated)
        assertEquals(0, repaired.settingsFieldsApplied)
        assertEquals(0, repaired.projectionMarkersCreated)
        val preservedManga = restarted.currentSnapshot.mangas.single()
        assertEquals("User title", preservedManga.title)
        assertEquals("keep me", preservedManga.notes)
        assertFalse(preservedManga.favorite)
        assertTrue(restarted.currentSnapshot.mangaCategories.none { it.mangaId == preservedManga.id })
        val preservedChapter = assertNotNull(restarted.chapter(retainedChapter.id))
        assertEquals("User chapter", preservedChapter.name)
        assertEquals(7, preservedChapter.lastPageRead)
        assertTrue(preservedChapter.read)
        assertEquals("User category", restarted.currentSnapshot.categories.single { it.id != 0L }.name)
        assertEquals(ThemeMode.LIGHT, restarted.currentSnapshot.settings.appearance.theme)
        assertTrue(restarted.currentSnapshot.settings.reader.keepScreenOn)
        assertFalse(restarted.currentSnapshot.settings.reader.volumeKeys)
        assertFalse(restarted.currentSnapshot.settings.security.appLockEnabled)
    }

    @Test
    fun aliasesAndMetadataWithoutAtomicShuYueLedgerCannotMaterializeRows() = runTest {
        val fixture = fixture()
        val repository = ShinsouRepository()
        val withoutLedger = fixture.auxiliary.copy(migrations = emptyList())
        val coordinator = ShuYueCompatibilityProjectionCoordinator(
            repository = repository,
            publications = { listOf(fixture.publication) },
            auxiliaryState = { withoutLedger },
        )

        val result = coordinator.repair()

        assertFalse(result.changed)
        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertTrue(repository.currentSnapshot.contentAuthorityProjectionMarkers.isEmpty())
    }

    @Test
    fun handedOffPublicationIsNotRecreatedAfterItsCompatibilityRowsMoveToAStandaloneSource() = runTest {
        val fixture = fixture()
        val handoffMarker = shuyueCompatibilityProjectionHandoffMarker(fixture.publication.key)
        val repository = ShinsouRepository(
            AppSnapshot(contentAuthorityProjectionMarkers = setOf(handoffMarker)).validate(),
        )

        val result = coordinator(repository, fixture).repair()

        assertEquals(0, result.mangasCreated)
        assertEquals(0, result.chaptersCreated)
        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertTrue(repository.currentSnapshot.chapters.isEmpty())
        assertTrue(handoffMarker in repository.currentSnapshot.contentAuthorityProjectionMarkers)
    }

    @Test
    fun deterministicCategoryNumericIdCollisionFailsBeforePublishingAnyProjection() = runTest {
        val fixture = fixture()
        val stableId = shuyueLegacyCategoryId(fixture.categoryId)
        val repository = ShinsouRepository(
            AppSnapshot(
                categories = listOf(Category.Default, Category(id = stableId, name = "Unrelated")),
            ),
        )
        val coordinator = coordinator(repository, fixture)

        assertFailsWith<ShuYueCompatibilityProjectionConflictException> {
            coordinator.repair()
        }

        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertEquals(setOf("Default", "Unrelated"), repository.currentSnapshot.categories.mapTo(hashSetOf()) { it.name })
        assertTrue(repository.currentSnapshot.contentAuthorityProjectionMarkers.isEmpty())
        assertTrue(repository.currentSnapshot.shuyueCategoryProjectionBindings.isEmpty())

        val first = ShinsouRepository()
        val second = ShinsouRepository()
        coordinator(first, fixture).repair()
        coordinator(second, fixture).repair()
        assertEquals(
            first.currentSnapshot.categories.single { it.id != 0L }.id,
            second.currentSnapshot.categories.single { it.id != 0L }.id,
        )
    }

    @Test
    fun failedProjectionCasPublishesNeitherSettingsNorMarkerAndRetryAppliesBoth() = runTest {
        val fixture = fixture()
        val repository = ShinsouRepository()
        var fail = true
        repository.setSnapshotReplacementGuard { origin, _, _ ->
            if (origin == dev.shinsou.kmp.data.SnapshotReplacementOrigin.CONTENT_AUTHORITY_MATERIALIZER && fail) {
                fail = false
                error("injected projection persistence boundary failure")
            }
        }
        val coordinator = coordinator(repository, fixture)

        assertFailsWith<IllegalStateException> { coordinator.repair() }
        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertEquals(ThemeMode.SYSTEM, repository.currentSnapshot.settings.appearance.theme)
        assertTrue(repository.currentSnapshot.contentAuthorityProjectionMarkers.isEmpty())

        val retried = coordinator.repair()
        assertTrue(retried.changed)
        assertEquals(ThemeMode.DARK, repository.currentSnapshot.settings.appearance.theme)
        assertEquals(1, repository.currentSnapshot.contentAuthorityProjectionMarkers.size)
        assertEquals(1, repository.currentSnapshot.mangas.size)
    }

    private fun coordinator(
        repository: ShinsouRepository,
        fixture: ProjectionFixture,
    ): ShuYueCompatibilityProjectionCoordinator = ShuYueCompatibilityProjectionCoordinator(
        repository = repository,
        publications = { listOf(fixture.publication) },
        auxiliaryState = { fixture.auxiliary },
    )

    private fun fixture(): ProjectionFixture {
        val publicationKey = PublicationKey("10000000-0000-5000-8000-000000000001")
        val acquisitionId = "20000000-0000-5000-8000-000000000001"
        val firstUnit = UnitKey(publicationKey, "30000000-0000-5000-8000-000000000001")
        val secondUnit = UnitKey(publicationKey, "30000000-0000-5000-8000-000000000002")
        val source = SourceKey(packageId = "fixture.package", sourceId = "fixture.source")
        val publicationBinding = SourceBinding(
            sourceKey = source,
            remoteId = "legacy-book",
            entityKind = RemoteEntityKind.PUBLICATION,
        )
        fun unitBinding(remoteId: String): SourceBinding = SourceBinding(
            sourceKey = source,
            remoteId = remoteId,
            entityKind = RemoteEntityKind.UNIT,
            parentPublication = publicationBinding.remoteEntityKey,
        )
        val publication = Publication(
            key = publicationKey,
            title = "Book",
            description = "Description",
            authors = listOf("Author"),
            acquisitions = listOf(
                Acquisition(
                    id = acquisitionId,
                    origin = AcquisitionOrigin.ExtensionSource(publicationBinding),
                    acquiredAtEpochMillis = 123,
                    units = listOf(
                        PublicationUnit(
                            key = firstUnit,
                            title = "First",
                            ordinal = 0,
                            sourceBinding = unitBinding("legacy-first"),
                        ),
                        PublicationUnit(
                            key = secondUnit,
                            title = "Second",
                            ordinal = 1,
                            sourceBinding = unitBinding("legacy-second"),
                        ),
                    ),
                ),
            ),
        )
        val category = ShuYueImportedCategory(
            categoryId = "40000000-0000-5000-8000-000000000001",
            name = "Reading",
        )
        val membership = ShuYueImportedCategoryMembership(
            publicationId = publicationKey.value,
            categoryId = category.categoryId,
        )
        val progress = ShuYueImportedReadingProgress(
            locator = ReadingLocator.Text(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                scope = ReadingScope(
                    schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                    publicationId = publicationKey,
                    acquisitionId = acquisitionId,
                    unitId = firstUnit,
                    contentRevision = 1,
                ),
                resourceId = "resource-first",
                blockId = "body",
                offset = 20,
                progression = 0.5,
            ),
            updatedAtEpochMillis = 888,
        )
        val settings = ShuYueImportedReaderSettings(
            language = "ENGLISH",
            fontSizeSp = 20f,
            lineHeightPercent = 170,
            pageChars = 900,
            theme = "OLED",
            accentColor = "GREEN",
            volumeKeysEnabled = true,
            volumeUpAction = "PREVIOUS",
            volumeDownAction = "NEXT",
            keepScreenOn = false,
            syncOnLaunch = false,
            appLockEnabled = true,
            secureScreen = true,
            incognitoMode = true,
            showNsfwSources = false,
            imageParsingEnabled = true,
            showPluginErrors = false,
        )
        val digest = "a".repeat(64)
        val auxiliary = ContentPortableAuxiliaryState(
            metadata = listOf(
                ContentMetadataMutation(
                    "migration.shuyue.category.${category.categoryId}",
                    JSON.encodeToString(category),
                ),
                ContentMetadataMutation(
                    shuyueCategoryMembershipMetadataKey(membership),
                    JSON.encodeToString(membership),
                ),
                ContentMetadataMutation(
                    "migration.shuyue.progress.${firstUnit.value}",
                    JSON.encodeToString(progress),
                ),
                ContentMetadataMutation(
                    "migration.shuyue.reader-settings.$digest",
                    JSON.encodeToString(settings),
                ),
            ),
            aliases = listOf(
                ContentAliasMutation("shuyue-v1-book:01", publicationKey.value),
                ContentAliasMutation("shuyue-v1-chapter:01:01", firstUnit.value),
                ContentAliasMutation("shuyue-v1-chapter:01:02", secondUnit.value),
            ),
            migrations = listOf(
                ContentMigrationLedgerMutation(
                    namespace = "shuyue.backup.v1",
                    sourceDigestSha256 = digest,
                    resultFingerprintSha256 = "b".repeat(64),
                ),
            ),
        )
        return ProjectionFixture(publication, category.categoryId, auxiliary)
    }

    private data class ProjectionFixture(
        val publication: Publication,
        val categoryId: String,
        val auxiliary: ContentPortableAuxiliaryState,
    )

    private companion object {
        val JSON: Json = Json { encodeDefaults = true }
    }
}
