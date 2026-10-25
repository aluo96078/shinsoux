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
import kotlin.test.assertNull

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
}
