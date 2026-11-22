@file:OptIn(dev.shinsou.kmp.testing.TestOnlyApi::class)

package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.plugin.PluginTestAccess
import dev.shinsou.kmp.plugin.SourceWebChallengeCapability
import dev.shinsou.kmp.plugin.SourceWebChallengeRequest
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.BrowsePage
import dev.shinsou.kmp.plugin.BrowseSource
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.plugin.InstalledPluginPermissions
import dev.shinsou.kmp.plugin.PluginPermissionDetails
import dev.shinsou.kmp.plugin.UpdatePermissionPreview
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.ui.challenge.WebChallengeCapture
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal fun TestScope.screenWork() = ScreenWork(
    scope = this,
    workDispatcher = StandardTestDispatcher(testScheduler),
    awaitFrame = {},
    strings = { shinsouStringsFor("en") },
)

class SourceCatalogueStateTest {
    private val source = BrowseSource(id = 1L, name = "Source", language = "en")
    private val other = BrowseSource(id = 2L, name = "Other", language = "en")

    private class FakeBackend : SourceCatalogueBackend {
        val pages = mutableMapOf<Int, CompletableDeferred<BrowsePage>>()
        var challengeAvailable = true
        val cancelled = mutableListOf<SourceWebChallengeCapability>()
        val imported = mutableListOf<Long>()
        val issued = SourceWebChallengeRequest(PluginTestAccess.webChallengeCapability(), 1L, "Source", "https://example.test", "UA")

        fun page(number: Int) = pages.getOrPut(number) { CompletableDeferred() }

        override suspend fun page(request: SourceCatalogueRequest): BrowsePage = page(request.requestedPage).await()

        override suspend fun isWebChallengeAvailable(sourceId: Long) = challengeAvailable

        override suspend fun webChallenge(sourceId: Long) = issued

        override suspend fun importWebChallenge(sourceId: Long, capture: WebChallengeCapture) {
            imported += sourceId
        }

        override suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability) {
            cancelled += capability
        }
    }

    private fun item(id: String) = BrowseManga(sourceId = 1L, url = "/$id", title = id)

    @Test
    fun pagesAppendWithoutDuplicates() = runTest {
        val backend = FakeBackend()
        val catalogue = SourceCatalogueState(backend, screenWork()) { source.identityKey }
        catalogue.browse(source, "", SourceCatalogueMode.Popular, null)
        assertTrue(catalogue.loading)
        backend.page(1).complete(BrowsePage(listOf(item("a"), item("b")), hasNextPage = true))
        advanceUntilIdle()
        assertFalse(catalogue.loading)
        catalogue.loadMore(source, "", SourceCatalogueMode.Popular, null)
        backend.page(2).complete(BrowsePage(listOf(item("b"), item("c"))))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), catalogue.page.items.map { it.title })
        assertEquals(2, catalogue.pageNumber)
    }

    @Test
    fun aReplacedRequestCannotChangeTheCatalogue() = runTest {
        val backend = FakeBackend()
        val catalogue = SourceCatalogueState(backend, screenWork()) { source.identityKey }
        catalogue.browse(source, "old", SourceCatalogueMode.Popular, null)
        advanceUntilIdle()
        catalogue.cancelLoad()
        assertFalse(catalogue.loading)
        backend.page(1).complete(BrowsePage(listOf(item("stale"))))
        advanceUntilIdle()
        assertEquals(emptyList(), catalogue.page.items)
    }

    @Test
    fun aChallengedRequestIsRetriedOnceAfterTheBrowserPassed() = runTest {
        val backend = FakeBackend()
        val catalogue = SourceCatalogueState(backend, screenWork()) { source.identityKey }
        catalogue.browse(source, "q", SourceCatalogueMode.Popular, null)
        backend.page(1).completeExceptionally(IllegalStateException("SHINSOU_SOURCE_HTTP_CHALLENGE"))
        advanceUntilIdle()
        assertTrue(catalogue.challengeAvailable)
        assertNotNull(catalogue.error)
        catalogue.openWebChallenge()
        advanceUntilIdle()
        val challenge = assertNotNull(catalogue.challenge)
        backend.pages.clear()
        catalogue.importWebChallenge(challenge, WebChallengeCapture(challenge.browserRequest.capability, emptyList(), "UA"))
        advanceUntilIdle()
        assertEquals(listOf(1L), backend.imported)
        // The capability is consumed whatever happened, and the request runs again.
        assertEquals(listOf(challenge.browserRequest.capability), backend.cancelled)
        assertNull(catalogue.pendingRecovery)
        assertTrue(catalogue.loading)
        backend.page(1).complete(BrowsePage(listOf(item("after"))))
        advanceUntilIdle()
        assertEquals(listOf("after"), catalogue.page.items.map { it.title })
    }

    @Test
    fun aChallengeOfASourceNoLongerOpenIsNotOffered() = runTest {
        val backend = FakeBackend()
        var open: BrowseSource = source
        val catalogue = SourceCatalogueState(backend, screenWork()) { open.identityKey }
        catalogue.browse(source, "", SourceCatalogueMode.Popular, null)
        open = other
        backend.page(1).completeExceptionally(IllegalStateException("SHINSOU_SOURCE_HTTP_CHALLENGE"))
        advanceUntilIdle()
        assertFalse(catalogue.challengeAvailable)
        assertNull(catalogue.pendingRecovery)
    }

    @Test
    fun clearingTheRecoveryWithdrawsAnIssuedCapability() = runTest {
        val backend = FakeBackend()
        val catalogue = SourceCatalogueState(backend, screenWork()) { source.identityKey }
        catalogue.browse(source, "", SourceCatalogueMode.Popular, null)
        backend.page(1).completeExceptionally(IllegalStateException("SHINSOU_SOURCE_HTTP_CHALLENGE"))
        advanceUntilIdle()
        catalogue.openWebChallenge()
        advanceUntilIdle()
        val challenge = assertNotNull(catalogue.challenge)
        catalogue.clearRecovery()
        advanceUntilIdle()
        assertNull(catalogue.challenge)
        assertEquals(listOf(challenge.browserRequest.capability), backend.cancelled)
    }
}

class BrowseScreenStateTest {
    private val source = BrowseSource(id = 1L, name = "Source", language = "en")

    @Test
    fun backClosesTheTopmostOverlayOnce() {
        val events = mutableListOf<String>()
        val state = BrowseScreenState(BrowseSection.Sources, initialBackRequest = 0L)
        state.host = BrowseHost(
            onOpenManga = { events += "shell:${it.title}" },
            onSourcePinnedChange = { _, _ -> },
            onSourcePinnedKeyChange = { _, _ -> },
            onParentDismissed = { events += "parent" },
            onReaderVisibilityChanged = { events += "reader:$it" },
        )
        state.onCatalogueClosed = { events += "catalogue-closed" }
        state.openSource(source)
        state.openManga(BrowseManga(sourceId = 1L, url = "/l", title = "Legacy"))
        val publication = BrowseManga(
            sourceId = 1L,
            url = "",
            title = "V2",
            sourceKey = dev.shinsou.kmp.domain.model.SourceKey(packageId = "pkg", sourceId = "src"),
            remotePublicationId = "r",
        )
        state.openManga(publication)
        state.setReaderVisible(true)
        state.onSystemBack(1L)
        // The reader is asked to close itself first.
        assertEquals(1L, state.readerBackRequest)
        state.onSystemBack(1L)
        assertEquals(1L, state.readerBackRequest)
        state.setReaderVisible(false)
        state.onSystemBack(2L)
        assertNull(state.overlays.activeV2Publication)
        state.onSystemBack(3L)
        assertNull(state.overlays.activeSource)
        assertEquals(
            listOf("shell:Legacy", "reader:true", "reader:false", "reader:false", "catalogue-closed", "reader:false", "parent"),
            events,
        )
    }

    @Test
    fun anotherPageClosesEverythingAndClearsTheSearch() {
        var dismissed = 0
        val state = BrowseScreenState(BrowseSection.Sources, initialBackRequest = 5L)
        state.host = BrowseHost(onOpenManga = {}, onSourcePinnedChange = { _, _ -> }, onSourcePinnedKeyChange = { _, _ -> }, onParentDismissed = { dismissed++ })
        state.openGlobalSearch()
        state.query = "abc"
        state.selectSection(BrowseSection.Extensions)
        assertEquals(BrowseSection.Extensions, state.section)
        assertFalse(state.overlays.hasOverlay)
        assertEquals("", state.query)
        assertEquals(1, dismissed)
        // The request the screen started with was already handled.
        state.openGlobalSearch()
        state.onSystemBack(5L)
        assertTrue(state.overlays.globalSearchVisible)
    }
}

class ExtensionManagementStateTest {
    private val extension = BrowseExtension(id = "pkg", name = "Pkg", version = "1", language = "en", installed = true, updateAvailable = true)
    private val review = PluginEventGrantReview(
        artifact = dev.shinsou.kmp.domain.model.PluginArtifactIdentity("pkg", "2", 2, "a".repeat(64)),
        sourceKeys = listOf(dev.shinsou.kmp.domain.model.SourceKey(packageId = "pkg", sourceId = "source")),
        requestedPermissions = emptySet(),
    )
    private val details = PluginPermissionDetails(
        packageName = "Pkg",
        version = "2",
        publisherDisplayFingerprint = null,
        publisherState = null,
        fingerprintVerified = false,
        repositoryUrl = null,
        requestOrigins = emptyList(),
        contentOrigins = emptyList(),
        credentialOrigins = emptyList(),
        browserSessionOrigins = emptyList(),
    )

    private class FakeBridge : EmptyBrowseCallbacks() {
        var preview: UpdatePermissionPreview? = null
        var pending: PluginEventGrantReview? = null
        val installed = mutableListOf<String>()
        val narrowed = mutableListOf<PluginGrantSelection>()
        var installedPermissions: InstalledPluginPermissions? = null

        override suspend fun installedPluginPermissions(extensionId: String) = installedPermissions

        override suspend fun updatePermissionPreview(extensionId: String) = preview

        override suspend fun installExtension(extensionId: String) {
            installed += extensionId
        }

        override suspend fun pendingPluginEventGrantReview(extensionId: String) = pending

        override suspend fun narrowPluginPermissions(extensionId: String, selection: PluginGrantSelection) {
            narrowed += selection
        }
    }

    @Test
    fun anUpdateAskingForSomethingNewIsReviewedBeforeItInstalls() = runTest {
        val bridge = FakeBridge().apply { preview = UpdatePermissionPreview(review, details) }
        val messages = mutableListOf<String>()
        val state = ExtensionManagementState(bridge, bridge, screenWork()) { messages += it }
        state.install(extension)
        assertNotNull(state.operations.active)
        advanceUntilIdle()
        assertNull(state.operations.active)
        assertEquals(emptyList(), bridge.installed)
        assertEquals(extension, state.pendingUpdate?.extension)
    }

    @Test
    fun aGrantReviewFollowsAnInstallThatAsksForOne() = runTest {
        val bridge = FakeBridge().apply { pending = review }
        val state = ExtensionManagementState(bridge, bridge, screenWork()) {}
        state.install(extension.copy(updateAvailable = false, installed = false))
        advanceUntilIdle()
        assertEquals(listOf("pkg"), bridge.installed)
        assertSame(review, state.pendingGrant?.review)
        state.dismissGrant()
        assertNull(state.pendingGrant)
    }

    @Test
    fun keepingTheSamePermissionsClosesTheEditorWithoutWork() = runTest {
        val bridge = FakeBridge()
        val state = ExtensionManagementState(bridge, bridge, screenWork()) {}
        val granted = PluginGrantSelection(emptySet(), emptySet(), emptySet())
        bridge.installedPermissions = InstalledPluginPermissions(review, granted, details)
        state.editPermissions(extension)
        advanceUntilIdle()
        val edited = assertNotNull(state.editedPermissions)
        state.narrowPermissions(edited, granted)
        assertNull(state.editedPermissions)
        assertEquals(emptyList(), bridge.narrowed)
    }

    @Test
    fun aFailedActionIsReportedInTheSnackbar() = runTest {
        val bridge = object : EmptyBrowseCallbacks() {
            override suspend fun selectRepository(repositoryId: String?) = error("SHINSOU_SOURCE_HTTP_BLOCKED")
        }
        val messages = mutableListOf<String>()
        val state = ExtensionManagementState(bridge, bridge, screenWork()) { messages += it }
        state.selectRepository("r")
        advanceUntilIdle()
        assertEquals(1, messages.size)
        assertEquals(0, state.repositoryWork, "a failed switch does not leave the page busy")
    }

    @Test
    fun aRepositorySwitchStillWaitingShowsThePageBusy() = runTest {
        val released = CompletableDeferred<Unit>()
        val bridge = object : EmptyBrowseCallbacks() {
            override suspend fun selectRepository(repositoryId: String?) = released.await()
        }
        val state = ExtensionManagementState(bridge, bridge, screenWork()) {}
        state.selectRepository(null)
        advanceUntilIdle()
        assertEquals(1, state.repositoryWork, "queued behind other extension work: the page says so")
        released.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, state.repositoryWork)
    }
}
