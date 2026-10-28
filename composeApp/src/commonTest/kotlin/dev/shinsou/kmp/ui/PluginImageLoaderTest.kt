package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.assertNull
import kotlin.test.assertSame

private val emptyBrowseState = kotlinx.coroutines.flow.MutableStateFlow(BrowseSnapshot())

class PluginImageLoaderTest {
    @Test
    fun coverFetchesOverlapUpToConfiguredBound() = runTest {
        val firstWaveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var starts = 0
        val counterLock = Mutex()
        val loader = PluginImageLoader(
            callbacks = object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceKey: SourceKey,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? {
                    counterLock.withLock {
                        active++
                        peak = maxOf(peak, active)
                        starts++
                        if (starts == 3) firstWaveStarted.complete(Unit)
                    }
                    try {
                        release.await()
                        return url.encodeToByteArray()
                    } finally {
                        counterLock.withLock { active-- }
                    }
                }
            },
            maxConcurrentLoads = 3,
        )
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val jobs = (1..8).map { index ->
            async { loader.load(sourceKey, "https://cdn.example/$index.jpg")?.invoke() }
        }

        firstWaveStarted.await()
        counterLock.withLock {
            assertEquals(3, starts)
            assertEquals(3, active)
        }
        release.complete(Unit)
        jobs.awaitAll()

        assertEquals(3, peak)
        assertEquals(8, starts)
    }

    @Test
    fun exactSourceKeyCoverUsesHostContentPlaneAndPreservesRequestMetadata() = runTest {
        val calls = mutableListOf<Triple<SourceKey, String, Map<String, String>>>()
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceKey: SourceKey,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? {
                    calls += Triple(sourceKey, url, headers)
                    return "cover-bytes".encodeToByteArray()
                }
            },
        )

        val load = loader.load(
            sourceKey,
            "https://cdn.example/cover.webp",
            mapOf("Referer" to "https://source.example/book"),
        )

        assertEquals("cover-bytes", load?.invoke()?.decodeToString())
        assertEquals(
            listOf(
                Triple(
                    sourceKey,
                    "https://cdn.example/cover.webp",
                    mapOf("Referer" to "https://source.example/book"),
                ),
            ),
            calls,
        )
    }

    @Test
    fun sourceZeroRemoteCoverRemainsInertAndNeverFallsBackToDirectNetwork() = runTest {
        var callbackCalls = 0
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceId: Long,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? {
                    callbackCalls++
                    return "must-not-run".encodeToByteArray()
                }
            },
        )

        val load = loader.load(0L, "https://unreviewed.example/cover.jpg")

        // A remote URL attached to the app-owned source id is ambiguous. It must be rendered as
        // a placeholder instead of being handed to Coil or an arbitrary plugin callback.
        assertNull(load?.invoke())
        assertEquals(0, callbackCalls)
    }

    @Test
    fun legacySourceCoverFailureStaysAPlaceholder() = runTest {
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceId: Long,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? = null
            },
        )

        val load = loader.load(123L, "https://cdn.example/cover.jpg")

        // The caller receives bounded bytes or null; it never receives a remote URL fallback.
        assertNull(load?.invoke())
    }

    @Test
    fun defaultBoundLetsAVisibleGridOfCoversLoadInParallel() = runTest {
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val counterLock = Mutex()
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceKey: SourceKey,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? {
                    counterLock.withLock {
                        active++
                        peak = maxOf(peak, active)
                        if (active == DEFAULT_PLUGIN_IMAGE_CONCURRENCY) allStarted.complete(Unit)
                    }
                    try {
                        release.await()
                        return url.encodeToByteArray()
                    } finally {
                        counterLock.withLock { active-- }
                    }
                }
            },
        )
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val jobs = (1..12).map { index ->
            async { loader.load(sourceKey, "https://cdn.example/$index.jpg")?.invoke() }
        }

        // Every permit is in use at once: the covers are not fetched one after another.
        allStarted.await()
        release.complete(Unit)
        jobs.awaitAll()
        assertEquals(DEFAULT_PLUGIN_IMAGE_CONCURRENCY, peak)
    }

    @Test
    fun reloadedCoverIsServedFromMemoryWithoutRefetching() = runTest {
        val fixture = CountingCovers()
        val loader = PluginImageLoader(fixture.callbacks)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val url = "https://cdn.example/cover.webp"

        val first = loader.load(sourceKey, url) as PluginImageRequest
        assertNull(first.cachedBytes())
        val bytes = first()
        // The composable is disposed and later recreated with a fresh request object.
        val second = loader.load(sourceKey, url) as PluginImageRequest

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertSame(bytes, second.cachedBytes())
        assertSame(bytes, second())
        assertEquals(listOf(url), fixture.calls)
    }

    @Test
    fun failedCoverIsNotCachedAndIsRetriedOnNextLoad() = runTest {
        var attempts = 0
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceId: Long,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? = if (++attempts == 1) null else "late".encodeToByteArray()
            },
        )

        assertNull(loader.load(123L, "https://cdn.example/cover.jpg")?.invoke())
        assertEquals("late", loader.load(123L, "https://cdn.example/cover.jpg")?.invoke()?.decodeToString())
        assertEquals(2, attempts)
    }

    @Test
    fun cacheEntriesAreScopedBySourceAndHeaders() = runTest {
        val fixture = CountingCovers()
        val loader = PluginImageLoader(fixture.callbacks)
        val url = "https://cdn.example/shared.jpg"
        val first = SourceKey(2, "fixture.package", "first")
        val second = SourceKey(2, "fixture.package", "second")

        loader.load(first, url)?.invoke()
        loader.load(second, url)?.invoke()
        loader.load(first, url, mapOf("Referer" to "https://other.example/"))?.invoke()
        loader.load(first, url)?.invoke()

        assertNotEquals(loader.load(first, url), loader.load(second, url))
        assertEquals(3, fixture.calls.size)
    }

    @Test
    fun leastRecentlyUsedCoverIsEvictedWhenTheByteBudgetIsExceeded() = runTest {
        val fixture = CountingCovers(size = 10)
        val loader = PluginImageLoader(fixture.callbacks, maxCachedEntries = 4)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        suspend fun load(name: String) = loader.load(sourceKey, "https://cdn.example/$name.jpg")!!.invoke()

        listOf("a", "b", "c", "d").forEach { load(it) }
        load("a") // Refreshes a; b is now the eldest entry.
        load("e")
        assertEquals(4, loader.cache.count())

        load("a")
        load("c")
        load("d")
        load("e")
        assertEquals(5, fixture.calls.size)
        load("b")
        assertEquals(6, fixture.calls.size)
        assertEquals("https://cdn.example/b.jpg", fixture.calls.last())
    }

    @Test
    fun oversizedCoverIsServedButNotRetained() = runTest {
        val fixture = CountingCovers(size = 11)
        val loader = PluginImageLoader(fixture.callbacks, maxCachedBytes = 40)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")

        assertEquals(11, loader.load(sourceKey, "https://cdn.example/big.jpg")?.invoke()?.size)
        assertEquals(0, loader.cache.size())
    }

    @Test
    fun byteBudgetChargesKeysAndOverheadSoTinyResponsesCannotGrowUnbounded() = runTest {
        val fixture = CountingCovers(size = 1)
        val loader = PluginImageLoader(fixture.callbacks, maxCachedBytes = 16 * 1_024)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val longPath = "x".repeat(1_000)

        repeat(500) { loader.load(sourceKey, "https://cdn.example/$longPath/$it.jpg")?.invoke() }

        assertTrue(loader.cache.size() <= 16 * 1_024)
        // Each entry costs far more than its one-byte payload.
        assertTrue(loader.cache.count() < 10)
    }

    @Test
    fun entryCountIsBoundedIndependentlyOfBytes() = runTest {
        val fixture = CountingCovers(size = 1)
        val loader = PluginImageLoader(fixture.callbacks, maxCachedEntries = 3)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")

        repeat(10) { loader.load(sourceKey, "https://cdn.example/$it.jpg")?.invoke() }

        assertEquals(3, loader.cache.count())
    }

    @Test
    fun overlongKeyIsServedButNotRetained() = runTest {
        val fixture = CountingCovers(size = 1)
        val loader = PluginImageLoader(fixture.callbacks)
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")

        assertEquals(1, loader.load(sourceKey, "https://cdn.example/${"y".repeat(5_000)}")?.invoke()?.size)
        assertEquals(0, loader.cache.count())
    }

    @Test
    fun removedSourceCanNoLongerServeCachedCovers() = runTest {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(BrowseSource(7L, "Fixture", "en"))))
        val fixture = CountingCovers(state = state)
        val loader = PluginImageLoader(fixture.callbacks)
        val url = "https://cdn.example/cover.jpg"
        val request = loader.load(7L, url) as PluginImageRequest
        val before = assertNotNull(request.load())
        assertNotNull(request.cachedBytes())

        // Uninstall/revocation removes the source from the browse snapshot.
        state.value = BrowseSnapshot()

        assertNull(request.cachedBytes())
        val after = assertNotNull(request.load())
        assertNotEquals(request.memoryCacheKey(before), request.memoryCacheKey(after))
        assertEquals(listOf(url, url), fixture.calls)
    }

    @Test
    fun replacedArtifactInvalidatesCachedCovers() = runTest {
        val extension = BrowseExtension("fixture.package", "Fixture", "1.0", "en", installed = true, sourceIds = listOf(7L))
        val state = MutableStateFlow(BrowseSnapshot(extensions = listOf(extension)))
        val fixture = CountingCovers(state = state)
        val loader = PluginImageLoader(fixture.callbacks)
        val request = loader.load(7L, "https://cdn.example/cover.jpg")!!

        request()
        state.value = BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1")))
        request()

        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun unrelatedSnapshotChangesKeepTheCache() = runTest {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(BrowseSource(7L, "Fixture", "en"))))
        val fixture = CountingCovers(state = state)
        val loader = PluginImageLoader(fixture.callbacks)
        val request = loader.load(7L, "https://cdn.example/cover.jpg")!!

        request()
        state.value = state.value.copy(isRefreshing = true, errorMessage = "transient")
        request()

        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun coverRevokedWhileInFlightIsNeitherReturnedNorCached() = runTest {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(BrowseSource(7L, "Fixture", "en"))))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = state
                override suspend fun loadPluginThumbnail(
                    sourceId: Long,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray? {
                    started.complete(Unit)
                    release.await()
                    return "stale".encodeToByteArray()
                }
            },
        )
        val request = loader.load(7L, "https://cdn.example/cover.jpg") as PluginImageRequest
        val pending = async { request() }

        started.await()
        state.value = BrowseSnapshot()
        release.complete(Unit)

        assertNull(pending.await())
        assertEquals(0, loader.cache.count())
    }

    @Test
    fun undecodableCoverCanBeEvicted() = runTest {
        val fixture = CountingCovers()
        val loader = PluginImageLoader(fixture.callbacks)
        val request = loader.load(SourceKey(2, "fixture.package", "fixture.source"), "https://cdn.example/bad.jpg")
            as PluginImageRequest

        request()
        request.evict()

        assertNull(request.cachedBytes())
        request()
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun mountedCoverKeepsTheCoilKeyOfItsOwnGenerationAfterAnotherCoverAdvancesIt() = runTest {
        val extension = BrowseExtension("fixture.package", "Fixture", "1.0", "en", installed = true, sourceIds = listOf(7L))
        val state = MutableStateFlow(BrowseSnapshot(extensions = listOf(extension)))
        val fixture = CountingCovers(state = state)
        val loader = PluginImageLoader(fixture.callbacks)
        val mounted = loader.load(7L, "https://cdn.example/mounted.jpg") as PluginImageRequest
        val other = loader.load(7L, "https://cdn.example/other.jpg") as PluginImageRequest

        // A mounted cover holds bytes fetched under version 1.0.
        val held = assertNotNull(mounted.load())
        val heldKey = mounted.memoryCacheKey(held)

        // The extension is replaced and a different cover's access advances the generation.
        state.value = BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1")))
        other.load()

        // Recomposing the mounted cover must not pair its old bytes with the new generation.
        assertEquals(heldKey, mounted.memoryCacheKey(held))
        val fresh = assertNotNull(mounted.load())
        assertNotEquals(heldKey, mounted.memoryCacheKey(fresh))
        assertEquals(3, fixture.calls.size)
    }

    @Test
    fun authorityFlowSignalsMountedCoversOnlyForAuthorityChanges() = runTest {
        val extension = BrowseExtension("fixture.package", "Fixture", "1.0", "en", installed = true, sourceIds = listOf(7L))
        val state = MutableStateFlow(BrowseSnapshot(extensions = listOf(extension)))
        val loader = PluginImageLoader(CountingCovers(state = state).callbacks)
        val emissions = mutableListOf<Any?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { loader.authority.toList(emissions) }

        assertEquals(loader.currentAuthority(), emissions.single())
        state.value = state.value.copy(isRefreshing = true)
        assertEquals(1, emissions.size)
        state.value = BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1")))
        assertEquals(2, emissions.size)
        state.value = BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1", trusted = true)))
        assertEquals(3, emissions.size)
        assertEquals(loader.currentAuthority(), emissions.last())
        collector.cancel()
    }

    @Test
    fun recreatedLoaderNeverReusesAnEarlierLoadersCoilKey() = runTest {
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val url = "https://cdn.example/cover.jpg"
        // E.g. an Activity recreation: a new loader starts at generation zero again while Coil's
        // process-wide memory cache still holds the first loader's decoded bitmap.
        val first = PluginImageLoader(CountingCovers().callbacks)
        val second = PluginImageLoader(CountingCovers(size = 3).callbacks)
        val firstRequest = first.load(sourceKey, url) as PluginImageRequest
        val secondRequest = second.load(sourceKey, url) as PluginImageRequest

        val firstResult = assertNotNull(firstRequest.load())
        val secondResult = assertNotNull(secondRequest.load())

        assertEquals(firstResult.fetchId, secondResult.fetchId)
        assertNotEquals(firstRequest.memoryCacheKey(firstResult), secondRequest.memoryCacheKey(secondResult))
        // Within one loader the key stays stable, so scrolling back still hits Coil's cache.
        assertEquals(secondRequest.memoryCacheKey(secondResult), secondRequest.memoryCacheKey(assertNotNull(secondRequest.load())))
    }

    @Test
    fun cacheHitKeepsItsCoilKeySoScrollingBackSkipsDecoding() = runTest {
        val loader = PluginImageLoader(CountingCovers().callbacks)
        val request = loader.load(SourceKey(2, "fixture.package", "fixture.source"), "https://cdn.example/a.jpg")
            as PluginImageRequest

        val fetched = assertNotNull(request.load())
        val hit = assertNotNull(request.cached())

        assertSame(fetched.bytes, hit.bytes)
        assertEquals(request.memoryCacheKey(fetched), request.memoryCacheKey(hit))
    }

    @Test
    fun redownloadAfterEvictionGetsANewCoilKey() = runTest {
        var version = 0
        val loader = PluginImageLoader(
            object : BrowseCallbacks {
                override val state = emptyBrowseState
                override suspend fun loadPluginThumbnail(
                    sourceKey: SourceKey,
                    url: String,
                    headers: Map<String, String>,
                ): ByteArray = "$url#${++version}".encodeToByteArray()
            },
            maxCachedEntries = 1,
        )
        val sourceKey = SourceKey(2, "fixture.package", "fixture.source")
        val cover = loader.load(sourceKey, "https://cdn.example/cover.jpg") as PluginImageRequest

        val old = assertNotNull(cover.load())
        loader.load(sourceKey, "https://cdn.example/other.jpg")?.invoke() // Evicts the cover.
        // The source updated the image meanwhile; Coil may still hold the old decoded bitmap.
        val updated = assertNotNull(cover.load())

        assertNotEquals(old.bytes.decodeToString(), updated.bytes.decodeToString())
        assertNotEquals(cover.memoryCacheKey(old), cover.memoryCacheKey(updated))
    }

    @Test
    fun uncachedOversizedImageGetsANewCoilKeyOnEveryDownload() = runTest {
        val loader = PluginImageLoader(CountingCovers(size = 11).callbacks, maxCachedBytes = 40)
        val request = loader.load(SourceKey(2, "fixture.package", "fixture.source"), "https://cdn.example/big.jpg")
            as PluginImageRequest

        val first = assertNotNull(request.load())
        val second = assertNotNull(request.load())

        assertEquals(0, loader.cache.count())
        assertNotEquals(request.memoryCacheKey(first), request.memoryCacheKey(second))
    }

    @Test
    fun disabledSourceCanNoLongerServeCachedCovers() = runTest {
        val source = BrowseSource(7L, "Fixture", "en")
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(source)))
        val fixture = CountingCovers(state = state)
        val loader = PluginImageLoader(fixture.callbacks)
        val request = loader.load(7L, "https://cdn.example/cover.jpg") as PluginImageRequest
        val emissions = mutableListOf<Any?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { loader.authority.toList(emissions) }

        request()
        state.value = BrowseSnapshot(sources = listOf(source.copy(enabled = false)))

        // Mounted covers are told to drop their bytes, and the cache no longer serves them.
        assertEquals(2, emissions.size)
        assertNull(request.cachedBytes())
        assertEquals(0, loader.cache.count())
        collector.cancel()
    }

    private class CountingCovers(
        private val size: Int? = null,
        private val state: kotlinx.coroutines.flow.StateFlow<BrowseSnapshot> = emptyBrowseState,
    ) {
        val calls = mutableListOf<String>()
        val callbacks = object : BrowseCallbacks {
            override val state = this@CountingCovers.state
            override suspend fun loadPluginThumbnail(
                sourceId: Long,
                url: String,
                headers: Map<String, String>,
            ): ByteArray {
                calls += url
                return size?.let(::ByteArray) ?: url.encodeToByteArray()
            }
            override suspend fun loadPluginThumbnail(
                sourceKey: SourceKey,
                url: String,
                headers: Map<String, String>,
            ): ByteArray {
                calls += url
                return size?.let(::ByteArray) ?: url.encodeToByteArray()
            }
        }
    }
}
