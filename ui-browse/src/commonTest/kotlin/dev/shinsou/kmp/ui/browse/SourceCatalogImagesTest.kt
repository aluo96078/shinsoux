package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.BrowseSnapshot
import dev.shinsou.kmp.plugin.BrowseSource
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.ui.core.PluginImageLoader
import dev.shinsou.kmp.ui.core.PluginImageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 0008: what in the browse snapshot is the plugin images' source authority, and the loads it passes on. */
class SourceCatalogImagesTest {
    private val extension = BrowseExtension("fixture.package", "Fixture", "1.0", "en", installed = true, sourceIds = listOf(7L))
    private val source = BrowseSource(7L, "Fixture", "en")

    private class Catalog(override val state: MutableStateFlow<BrowseSnapshot>) : EmptyBrowseCallbacks() {
        val calls = mutableListOf<String>()

        override suspend fun loadPluginThumbnail(sourceId: Long, url: String, headers: Map<String, String>): ByteArray {
            calls += "$sourceId $url $headers"
            return url.encodeToByteArray()
        }

        override suspend fun loadPluginThumbnail(sourceKey: SourceKey, url: String, headers: Map<String, String>): ByteArray {
            calls += "${sourceKey.sourceId} $url $headers"
            return url.encodeToByteArray()
        }
    }

    @Test
    fun installsRemovalsUpdatesTrustAndEnablingChangeTheAuthority() {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(source), extensions = listOf(extension)))
        val images = SourceCatalogImages(Catalog(state))
        val tokens = mutableListOf(images.currentAuthority())
        fun change(snapshot: BrowseSnapshot) {
            state.value = snapshot
            tokens += images.currentAuthority()
        }

        change(BrowseSnapshot(sources = listOf(source), extensions = listOf(extension.copy(version = "1.1"))))
        change(BrowseSnapshot(sources = listOf(source), extensions = listOf(extension.copy(version = "1.1", trusted = true))))
        change(BrowseSnapshot(sources = listOf(source.copy(enabled = false)), extensions = listOf(extension.copy(version = "1.1", trusted = true))))
        change(BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1", trusted = true))))
        change(BrowseSnapshot())

        assertEquals(tokens.size, tokens.toSet().size, "every change is a new authority: $tokens")
    }

    @Test
    fun unrelatedSnapshotChangesKeepTheAuthority() {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(source), extensions = listOf(extension)))
        val images = SourceCatalogImages(Catalog(state))
        val before = images.currentAuthority()

        // The same snapshot is read again without recomputing the token.
        assertSame(before, images.currentAuthority())
        state.value = state.value.copy(isRefreshing = true, errorMessage = "transient")

        assertEquals(before, images.currentAuthority())
    }

    @Test
    fun authorityFlowEmitsOnlyForAuthorityChanges() = runTest {
        val state = MutableStateFlow(BrowseSnapshot(extensions = listOf(extension)))
        val images = SourceCatalogImages(Catalog(state))
        val emissions = mutableListOf<Any?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { images.authority.toList(emissions) }

        assertEquals(images.currentAuthority(), emissions.single())
        state.value = state.value.copy(isRefreshing = true)
        assertEquals(1, emissions.size)
        state.value = BrowseSnapshot(extensions = listOf(extension.copy(version = "1.1")))
        assertEquals(2, emissions.size)
        assertEquals(images.currentAuthority(), emissions.last())
        collector.cancel()
    }

    @Test
    fun theLoaderFetchesThroughTheCatalogueAndDropsCoversOfARemovedSource() = runTest {
        val state = MutableStateFlow(BrowseSnapshot(sources = listOf(source)))
        val catalog = Catalog(state)
        val loader = PluginImageLoader.forSource(SourceCatalogImages(catalog))
        val request = loader.load(7L, "https://cdn.example/cover.jpg", mapOf("Referer" to "r")) as PluginImageRequest
        assertNotNull(request.load())
        loader.load(SourceKey(2, "fixture.package", "fixture.source"), "https://cdn.example/key.jpg")?.invoke()
        assertNotNull(request.cachedBytes())

        state.value = BrowseSnapshot()

        assertNull(request.cachedBytes())
        assertEquals(listOf("7 https://cdn.example/cover.jpg {Referer=r}", "fixture.source https://cdn.example/key.jpg {}"), catalog.calls)
        assertNotEquals(null, loader.currentAuthority())
    }
}
