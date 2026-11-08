package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.BrowseRepository
import dev.shinsou.kmp.ui.BrowseExtension
import dev.shinsou.kmp.ui.resolvedAgainst
import dev.shinsou.kmp.ui.MigrationCandidate
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowsePage
import dev.shinsou.kmp.ui.BrowseSnapshot
import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseSearchTest {
    @Test
    fun catalogueJobControllerCancelsReplacementWithoutClearingNewestJob() = runTest {
        val controller = CatalogueJobController()
        val first = launch { awaitCancellation() }
        val second = launch { awaitCancellation() }

        controller.replace(first)
        controller.replace(second)
        first.join()
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)

        // A late finally from the first request must not detach its replacement.
        controller.clear(first)
        controller.cancel()
        second.join()
        assertTrue(second.isCancelled)
    }

    @Test
    fun globalSearchUsesEmptyFiltersWithoutLoadingSourceDefaults() = runTest {
        var receivedFilters: List<BrowseFilter>? = null
        val callbacks = object : BrowseCallbacks {
            override val state = MutableStateFlow(BrowseSnapshot())

            override suspend fun browseSource(
                sourceId: Long,
                query: String,
                page: Int,
                filters: List<BrowseFilter>?,
            ): BrowsePage {
                assertEquals(42L, sourceId)
                assertEquals("needle", query)
                assertEquals(1, page)
                receivedFilters = filters
                return BrowsePage(items = listOf(BrowseManga(42L, "/result", "Result")))
            }
        }

        val result = browseGlobalSearchSource(callbacks, sourceId = 42L, query = "needle")

        assertEquals(emptyList(), receivedFilters)
        assertEquals("Result", result.items.single().title)
    }

    @Test
    fun globalSearchLimitsConcurrencyAndPublishesEachCompletedSource() = runTest {
        val sourceIds = (1L..6L).toList()
        val releases = sourceIds.associateWith { CompletableDeferred<Unit>() }
        val started = Channel<Long>(Channel.UNLIMITED)
        val updates = Channel<SourceSearchUpdate>(Channel.UNLIMITED)
        val activeMutex = Mutex()
        var active = 0
        var maxActive = 0
        val callbacks = object : BrowseCallbacks {
            override val state = MutableStateFlow(BrowseSnapshot())

            override suspend fun browseSource(
                sourceId: Long,
                query: String,
                page: Int,
                filters: List<BrowseFilter>?,
            ): BrowsePage {
                activeMutex.withLock {
                    active++
                    maxActive = maxOf(maxActive, active)
                }
                started.send(sourceId)
                try {
                    releases.getValue(sourceId).await()
                } finally {
                    activeMutex.withLock { active-- }
                }
                return BrowsePage(
                    items = (1..12).map { index ->
                        BrowseManga(sourceId, "/$sourceId/$index", "Result $index")
                    },
                )
            }
        }
        val sources = sourceIds.map { id -> BrowseSource(id, "Source $id", "en") }

        val searchJob = launch {
            searchAcrossSources(callbacks, sources, "result", ShinsouStrings()).collect(updates::send)
        }

        val firstWave = List(GLOBAL_SEARCH_MAX_CONCURRENCY) { started.receive() }
        assertEquals(GLOBAL_SEARCH_MAX_CONCURRENCY, firstWave.distinct().size)
        assertTrue(started.tryReceive().isFailure)

        val firstCompleted = firstWave.first()
        releases.getValue(firstCompleted).complete(Unit)
        val firstUpdate = updates.receive()
        assertEquals(firstCompleted, firstUpdate.result.source.id)
        assertEquals(10, firstUpdate.result.items.size)
        assertEquals(12, firstUpdate.result.totalCount)
        assertTrue(searchJob.isActive)

        started.receive()
        releases.values.forEach { it.complete(Unit) }
        searchJob.join()

        val emittedSourceIds = buildList {
            add(firstUpdate.result.source.id)
            while (true) {
                val update = updates.tryReceive().getOrNull() ?: break
                add(update.result.source.id)
            }
        }
        assertEquals(sourceIds.toSet(), emittedSourceIds.toSet())
        assertTrue(maxActive <= GLOBAL_SEARCH_MAX_CONCURRENCY)
    }

    @Test
    fun cancelledGlobalSearchDoesNotPublishAResultThatFinishesLate() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updates = Channel<SourceSearchUpdate>(Channel.UNLIMITED)
        val callbacks = object : BrowseCallbacks {
            override val state = MutableStateFlow(BrowseSnapshot())

            override suspend fun browseSource(
                sourceId: Long,
                query: String,
                page: Int,
                filters: List<BrowseFilter>?,
            ): BrowsePage = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                BrowsePage(items = listOf(BrowseManga(sourceId, "/late", "Late result")))
            }
        }

        val searchJob = launch {
            searchAcrossSources(
                callbacks = callbacks,
                sources = listOf(BrowseSource(1L, "Source", "en")),
                query = "late",
                strings = ShinsouStrings(),
            ).collect(updates::send)
        }

        entered.await()
        searchJob.cancel()
        release.complete(Unit)
        searchJob.join()

        assertTrue(updates.tryReceive().isFailure)
    }

    @Test
    fun pinnedSourcesArePartitionedDeduplicatedAndStablySorted() {
        val sources = listOf(
            BrowseSource(id = 4, name = "Beta", language = "zh"),
            BrowseSource(id = 3, name = "zeta", language = "en"),
            BrowseSource(id = 2, name = "Alpha", language = "zh"),
            BrowseSource(id = 1, name = "alpha", language = "en"),
            BrowseSource(id = 3, name = "duplicate", language = "ja"),
        )

        val sections = browseSourceSections(sources, pinnedSourceIds = setOf(3L, 2L, 99L))

        assertEquals(listOf(2L, 3L), sections.pinned.map(BrowseSource::id))
        assertEquals(listOf(1L, 4L), sections.regular.map(BrowseSource::id))
    }

    @Test
    fun v2SourcesUseStableSourceKeysWhenPartitioningPinnedSources() {
        val sourceKey = SourceKey(packageId = "test.plugin", sourceId = "novel")
        val v2Source = BrowseSource(
            id = Long.MIN_VALUE,
            name = "Novel source",
            language = "zh",
            sourceKey = sourceKey,
        )
        val legacySource = BrowseSource(id = 7L, name = "Legacy", language = "zh")

        val sections = browseSourceSections(
            sources = listOf(v2Source, legacySource),
            pinnedSourceIds = emptySet(),
            pinnedSourceKeys = setOf(v2Source.identityKey),
        )

        assertEquals(listOf(v2Source.identityKey), sections.pinned.map(BrowseSource::identityKey))
        assertEquals(listOf(legacySource.identityKey), sections.regular.map(BrowseSource::identityKey))
    }

    @Test
    fun migrationTargetsExcludeCurrentDisabledAndDuplicateSources() {
        val sources = listOf(
            BrowseSource(id = 3, name = "Current", language = "en"),
            BrowseSource(id = 2, name = "Zeta", language = "en"),
            BrowseSource(id = 1, name = "Alpha", language = "zh"),
            BrowseSource(id = 2, name = "Zeta duplicate", language = "en"),
            BrowseSource(id = 4, name = "Disabled", language = "en", enabled = false),
        )

        assertEquals(
            listOf(1L, 2L),
            eligibleMigrationSources(sources, currentSourceName = "current").map(BrowseSource::id),
        )
    }

    @Test
    fun migrationTargetsMatchTheTitlesKindAndIdentityPath() {
        val novelKey = dev.shinsou.kmp.domain.model.SourceKey(2, "zh.current", "1")
        val sources = listOf(
            BrowseSource(id = 1, name = "Legacy manga", language = "zh", contentType = PluginContentType.MANGA),
            BrowseSource(id = 2, name = "Legacy unknown", language = "zh"),
            BrowseSource(id = 3, name = "Current", language = "zh", sourceKey = novelKey, contentType = PluginContentType.NOVEL),
            BrowseSource(id = 4, name = "Other novel", language = "zh", sourceKey = dev.shinsou.kmp.domain.model.SourceKey(2, "zh.other", "1"), contentType = PluginContentType.NOVEL),
            BrowseSource(id = 5, name = "V2 manga", language = "zh", sourceKey = dev.shinsou.kmp.domain.model.SourceKey(2, "zh.manga", "1"), contentType = PluginContentType.MANGA),
            BrowseSource(id = 6, name = "Legacy novel", language = "zh", contentType = PluginContentType.NOVEL),
        )
        val novel = MigrationCandidate(
            mangaId = 1, title = "書", currentSourceName = "zh.current", sourceId = 0,
            sourceKey = novelKey, remotePublicationId = "b", contentType = PluginContentType.NOVEL,
        )
        assertEquals(listOf(4L), eligibleMigrationSources(sources, novel).map(BrowseSource::id))

        val manga = MigrationCandidate(mangaId = 2, title = "M", currentSourceName = "Legacy manga", sourceId = 1, contentType = PluginContentType.MANGA)
        assertEquals(listOf(2L), eligibleMigrationSources(sources, manga).map(BrowseSource::id))

        // The installed source names the title's current source and kind.
        val resolved = novel.copy(contentType = PluginContentType.BOTH).resolvedAgainst(sources)
        assertEquals("Current", resolved.currentSourceName)
        assertEquals(PluginContentType.NOVEL, resolved.contentType)
    }

    @Test
    fun aSelectedRepositoryShowsOnlyItsOwnExtensionsAndNoSelectionGroupsThemAll() {
        val community = BrowseRepository(id = "https://community.example", url = "https://community.example", name = "Community")
        val nsfw = BrowseRepository(id = "https://nsfw.example", url = "https://nsfw.example", name = "NSFW")
        val extensions = listOf(
            BrowseExtension("novel", "Novel", "1", "zh", installed = true, repositoryId = "https://community.example"),
            BrowseExtension("komiic", "Komiic", "1", "zh", repositoryId = "https://community.example/"),
            BrowseExtension("canovel", "CA", "1", "zh", repositoryId = "https://nsfw.example"),
            BrowseExtension("orphan", "Orphan", "1", "zh", installed = true, repositoryId = "https://gone.example"),
        )
        val onlyNsfw = extensionRepositoryGroups(extensions, listOf(community, nsfw), nsfw.id)
        assertEquals(listOf("canovel"), onlyNsfw.single().extensions.map(BrowseExtension::id))
        val onlyCommunity = extensionRepositoryGroups(extensions, listOf(community, nsfw), community.id)
        assertEquals(listOf("novel", "komiic"), onlyCommunity.single().extensions.map(BrowseExtension::id))

        val all = extensionRepositoryGroups(extensions, listOf(community, nsfw), null)
        assertEquals(listOf("Community", "NSFW", null), all.map { it.repository?.name })
        assertEquals(listOf("orphan"), all.last().extensions.map(BrowseExtension::id))
    }

    @Test
    fun theRepositoryPickerFindsByNameOrUrl() {
        val repositories = (1..30).map { BrowseRepository("https://r$it.example", "https://r$it.example", "Repo $it") } +
            BrowseRepository("https://nsfw-plugin.shinsoux.com", "https://nsfw-plugin.shinsoux.com", "Shinsou NSFW Plugins")
        assertEquals(31, repositoryPickerMatches(repositories, " ").size)
        assertEquals(listOf("Shinsou NSFW Plugins"), repositoryPickerMatches(repositories, "nsfw").map { it.name })
        assertEquals(listOf("Repo 12"), repositoryPickerMatches(repositories, "r12.").map { it.name })
    }

    @Test
    fun sourcesGroupByTheRepositoryOfTheirPackage() {
        val community = BrowseRepository("https://community.example", "https://community.example", "Community")
        val nsfw = BrowseRepository("https://nsfw.example", "https://nsfw.example", "NSFW")
        val sources = listOf(
            BrowseSource(id = 1, name = "Wenku", language = "zh", repositoryId = community.id),
            BrowseSource(id = 2, name = "Qimao", language = "zh", repositoryId = nsfw.id),
            BrowseSource(id = 3, name = "Local", language = "zh"),
        )
        val all = repositoryGroups(sources, listOf(community, nsfw), null) { it.repositoryId }
        assertEquals(listOf("Community", "NSFW", null), all.map { it.repository?.name })
        assertEquals(listOf(3L), all.last().items.map(BrowseSource::id))
        assertEquals(listOf(2L), repositoryGroups(sources, listOf(community, nsfw), nsfw.id) { it.repositoryId }.single().items.map(BrowseSource::id))
    }

    @Test
    fun rankingNormalizesNoisyTitlesAndDeduplicatesResults() {
        val items = listOf(
            BrowseManga(2, "/other", "Completely Different"),
            BrowseManga(2, "/exact", "Example Title"),
            BrowseManga(2, "/contains", "Example Title: Side Story"),
            BrowseManga(2, "/exact", "Duplicated network result"),
        )

        val ranked = rankBrowseResults("Example Title (Manga)", items)

        assertEquals(listOf("/exact", "/contains", "/other"), ranked.map(BrowseManga::url))
        assertEquals("Example Title", normalizeSearchTitle("Example Title [Web] - Manga"))
        assertTrue(titleSimilarity("Example Title", "Example Tittle") > 0.8)
    }
}
