package dev.shinsou.kmp.ui.screens

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
