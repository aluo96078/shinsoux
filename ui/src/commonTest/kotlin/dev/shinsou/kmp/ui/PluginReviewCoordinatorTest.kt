package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.BrowseRepository
import dev.shinsou.kmp.plugin.BrowseSnapshot
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.plugin.PackageSecuritySnapshot
import dev.shinsou.kmp.plugin.PluginDeveloperModeSettings
import dev.shinsou.kmp.plugin.RepositoryTrustPrompt
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginReviewCoordinatorTest {
    private val review = PluginEventGrantReview(
        artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
        sourceKeys = listOf(SourceKey(packageId = "pkg.test", sourceId = "source")),
        requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
    )

    private class FakeBrowse(extension: BrowseExtension) : EmptyBrowseCallbacks() {
        val browseState = MutableStateFlow(BrowseSnapshot(extensions = listOf(extension)))
        var pendingReview: PluginEventGrantReview? = null
        val approved = mutableListOf<String>()
        var trustPrompt: RepositoryTrustPrompt? = null
        val added = mutableListOf<String>()
        var developerHosts: List<String>? = null
        var addFailure: Throwable? = null

        override val state: StateFlow<BrowseSnapshot> get() = browseState
        override suspend fun pendingPluginEventGrantReview(extensionId: String) = pendingReview
        override suspend fun approvePluginEventGrantReview(
            extensionId: String,
            expectedReview: PluginEventGrantReview,
            permissions: Set<PluginHostPermission>,
            selection: PluginGrantSelection?,
        ) {
            approved += extensionId
        }
        override suspend fun inspectRepositoryTrust(url: String) = trustPrompt
        override suspend fun trustAndAddRepository(prompt: RepositoryTrustPrompt, fingerprint: String?): BrowseRepository? {
            added += prompt.url
            return null
        }
        override suspend fun addRepository(url: String): BrowseRepository? {
            addFailure?.let { throw it }
            added += url
            return null
        }
        override suspend fun packageSecurity() = PackageSecuritySnapshot(
            developerMode = developerHosts?.let { PluginDeveloperModeSettings(enabled = true, lanHosts = it) },
        )
    }

    private class Fixture(scope: TestScope, extension: BrowseExtension) {
        val browse = FakeBrowse(extension)
        val acknowledged = mutableListOf<ShinsouDeepLink>()
        val messages = mutableListOf<String>()
        var sourcesChanged = 0
        val reviews = PluginReviewCoordinator(
            browse = browse,
            scope = scope,
            messages = AppMessages({ shinsouStringsFor("en") }) { messages += it },
            acknowledgeLink = { acknowledged += it },
            onSourcesChanged = { sourcesChanged++ },
            workDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private val preinstalled = BrowseExtension(id = "pkg.test", name = "Test", version = "1", language = "en", installed = true)

    @Test
    fun aTrustedPackageOpensAtOnce() = runTest {
        val f = Fixture(this, preinstalled.copy(trusted = true))
        var opened = 0
        f.reviews.afterFirstAccessReview("pkg.test") { opened++ }
        assertEquals(1, opened)
        assertNull(f.reviews.state.value.sourceReviewGate)
    }

    @Test
    fun aPackageWaitingForItsReviewAsksFirstAndOpensOnceApproved() = runTest {
        val f = Fixture(this, preinstalled)
        f.browse.pendingReview = review
        var opened = 0
        var abandoned = 0
        f.reviews.afterFirstAccessReview("pkg.test", abandon = { abandoned++ }) { opened++ }
        advanceUntilIdle()
        val gate = assertNotNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(0, opened)
        f.reviews.approveReview(gate, PluginGrantSelection(review.requestedPermissions, emptySet(), emptySet()))
        assertNull(f.reviews.state.value.sourceReviewGate)
        advanceUntilIdle()
        assertEquals(listOf("pkg.test"), f.browse.approved)
        assertEquals(1, f.sourcesChanged)
        assertEquals(1, opened)
        assertEquals(0, abandoned)
    }

    @Test
    fun aPackageNotInTheIndexYetIsStillReviewedFirst() = runTest {
        val f = Fixture(this, preinstalled.copy(id = "pkg.other"))
        f.browse.pendingReview = review
        var opened = 0
        f.reviews.afterFirstAccessReview("pkg.test") { opened++ }
        advanceUntilIdle()
        val gate = assertNotNull(f.reviews.state.value.sourceReviewGate)
        assertEquals("pkg.test", gate.extension.id)
        assertEquals(0, opened)
        f.reviews.approveReview(gate, PluginGrantSelection(review.requestedPermissions, emptySet(), emptySet()))
        advanceUntilIdle()
        assertEquals(1, opened)
    }

    @Test
    fun anInstalledPackageNotInTheIndexWithoutAReviewOpens() = runTest {
        val f = Fixture(this, preinstalled.copy(id = "pkg.other"))
        var opened = 0
        f.reviews.afterFirstAccessReview("pkg.test") { opened++ }
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(1, opened)
    }

    @Test
    fun noReviewIsShownWhenTheUserMovedOnWhileItWasRead() = runTest {
        val f = Fixture(this, preinstalled)
        f.browse.pendingReview = review
        var wanted = true
        var opened = 0
        var abandoned = 0
        f.reviews.afterFirstAccessReview("pkg.test", abandon = { abandoned++ }, stillWanted = { wanted }) { opened++ }
        wanted = false
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(0, opened)
        assertEquals(1, abandoned)
    }

    @Test
    fun aReviewWhoseOpenWentStaleGrantsNothing() = runTest {
        val f = Fixture(this, preinstalled)
        f.browse.pendingReview = review
        var wanted = true
        var opened = 0
        var abandoned = 0
        f.reviews.afterFirstAccessReview("pkg.test", abandon = { abandoned++ }, stillWanted = { wanted }) { opened++ }
        advanceUntilIdle()
        val gate = assertNotNull(f.reviews.state.value.sourceReviewGate)
        // The page changed under the prompt; the user still confirms it.
        wanted = false
        f.reviews.approveReview(gate, PluginGrantSelection(review.requestedPermissions, emptySet(), emptySet()))
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(emptyList(), f.browse.approved)
        assertEquals(0, opened)
        assertEquals(1, abandoned)
        // A prompt already replaced or dismissed grants nothing either.
        f.reviews.approveReview(gate, PluginGrantSelection(review.requestedPermissions, emptySet(), emptySet()))
        advanceUntilIdle()
        assertEquals(emptyList(), f.browse.approved)
        assertEquals(1, abandoned)
    }

    @Test
    fun dismissingTheReviewStaysWhereTheUserWas() = runTest {
        val f = Fixture(this, preinstalled)
        f.browse.pendingReview = review
        var opened = 0
        var abandoned = 0
        f.reviews.afterFirstAccessReview("pkg.test", abandon = { abandoned++ }) { opened++ }
        advanceUntilIdle()
        assertEquals(0, abandoned, "still waiting for the user")
        f.reviews.dismissReview()
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(0, opened)
        // The open that waited on it learns it ended (a restoration stops waiting for it).
        assertEquals(1, abandoned)
    }

    @Test
    fun aSignedRepositoryLinkWaitsForTheUserAndIsAcknowledgedOnce() = runTest {
        val f = Fixture(this, preinstalled)
        val link = ShinsouDeepLink.AddRepository("https://repo.invalid/index.json", "fp")
        f.browse.trustPrompt = RepositoryTrustPrompt("https://repo.invalid/index.json", "Repo", "AA", fingerprintRequired = false)
        var browseOpened = 0
        f.reviews.onRepositoryLink(link) { browseOpened++ }
        assertEquals(1, browseOpened)
        assertEquals("fp", f.reviews.state.value.linkedRepositoryTrust?.linkedFingerprint)
        assertEquals(link, f.reviews.state.value.pendingRepositoryLink)
        // The platform re-delivers its head link: it is already waiting.
        f.reviews.onRepositoryLink(link) { browseOpened++ }
        assertEquals(1, browseOpened)
        f.reviews.trustLinkedRepository("fp")
        advanceUntilIdle()
        assertEquals(listOf("https://repo.invalid/index.json"), f.browse.added)
        assertEquals(listOf<ShinsouDeepLink>(link), f.acknowledged)
        assertNull(f.reviews.state.value.pendingRepositoryLink)
    }

    @Test
    fun aFingerprintedLinkToAnUnsignedRepositoryIsRefused() = runTest {
        val f = Fixture(this, preinstalled)
        val link = ShinsouDeepLink.AddRepository("https://repo.invalid/index.json", "fp")
        f.reviews.onRepositoryLink(link) {}
        assertEquals(listOf<ShinsouDeepLink>(link), f.acknowledged)
        assertEquals(1, f.messages.size)
        assertNull(f.reviews.state.value.pendingRepositoryLink)
    }

    @Test
    fun declininganUnsignedRepositoryAcknowledgesItsLink() = runTest {
        val f = Fixture(this, preinstalled)
        val link = ShinsouDeepLink.AddRepository("https://repo.invalid/index.json", null)
        f.reviews.onRepositoryLink(link) {}
        assertEquals("https://repo.invalid/index.json", f.reviews.state.value.linkedUnsignedRepository)
        f.reviews.declineLinkedUnsignedRepository()
        assertEquals(listOf<ShinsouDeepLink>(link), f.acknowledged)
        assertTrue(f.browse.added.isEmpty())
    }

    @Test
    fun aRepositoryThatFailsToAddStillAnswersItsLink() = runTest {
        val f = Fixture(this, preinstalled)
        f.browse.addFailure = IllegalStateException("offline")
        val link = ShinsouDeepLink.AddRepository("https://repo.invalid/index.json", null)
        f.reviews.onRepositoryLink(link) {}
        f.reviews.addLinkedUnsignedRepository()
        advanceUntilIdle()
        // Answered: the queue lets the next link through, and following this one again asks again.
        assertEquals(listOf<ShinsouDeepLink>(link), f.acknowledged)
        assertFalse(f.reviews.handlesRepositoryLink(link))
        assertEquals(1, f.messages.size)
    }

    @Test
    fun aDevelopmentServerNeedsDeveloperMode() = runTest {
        val f = Fixture(this, preinstalled)
        val link = ShinsouDeepLink.AddDevRepository("http://192.168.1.20:8080/index.json")
        f.reviews.onDevRepositoryLink(link) {}
        assertEquals(listOf<ShinsouDeepLink>(link), f.acknowledged)
        assertNull(f.reviews.state.value.linkedUnsignedRepository)
    }
}
