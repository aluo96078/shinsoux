package dev.shinsou.kmp.ui

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.navigation.AppNavigator
import dev.shinsou.kmp.navigation.Destination
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.BrowseSnapshot
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.shinsou.kmp.ui.library.LibrarySelectionState

class TitleNavigationControllerTest {
    private val sourceKey = SourceKey(packageId = "pkg.test", sourceId = "novels")
    private val item = BrowseManga(
        sourceId = Long.MIN_VALUE,
        url = "",
        title = "Novel",
        sourceKey = sourceKey,
        remotePublicationId = "r1",
    )

    private class FakeBrowse : EmptyBrowseCallbacks() {
        val browseState = MutableStateFlow(
            BrowseSnapshot(
                extensions = listOf(
                    BrowseExtension(id = "pkg.test", name = "Test", version = "1", language = "en", installed = true, trusted = true),
                ),
            ),
        )
        var resolved: Long? = null
        var resolveFailure: Throwable? = null
        var pendingReview: PluginEventGrantReview? = null
        val removedDownloads = mutableListOf<String>()
        val purged = mutableListOf<String>()

        /** The legacy recovery search: answered when completed, running until then. */
        val recoverySearch = CompletableDeferred<ExtensionLibraryBindingV2?>()
        var recoverySearchStopped = false

        override suspend fun recoverExtensionLibraryBindingV2(publicationKey: PublicationKey, title: String): ExtensionLibraryBindingV2? =
            try {
                recoverySearch.await()
            } finally {
                recoverySearchStopped = !recoverySearch.isCompleted
            }

        override val state: StateFlow<BrowseSnapshot> get() = browseState
        override suspend fun pendingPluginEventGrantReview(extensionId: String) = pendingReview
        override suspend fun resolveManga(item: BrowseManga): Long? {
            resolveFailure?.let { throw it }
            return resolved
        }
        override suspend fun removeExtensionPublicationDownloadsV2(sourceKey: SourceKey, remotePublicationId: String) {
            removedDownloads += remotePublicationId
        }
        override suspend fun purgeExtensionPublicationContentV2(sourceKey: SourceKey, remotePublicationId: String) {
            purged += remotePublicationId
        }
    }

    private class Fixture(scope: TestScope) {
        val repository = ShinsouRepository()
        val browse = FakeBrowse()
        val selection = LibrarySelectionState()
        val navigator = AppNavigator(MainSection.BROWSE, selection)
        val messages = mutableListOf<String>()
        val environment = HolderEnvironment(
            scope = scope,
            messages = AppMessages({ shinsouStringsFor("en") }) { messages += it },
            workDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val localLibrary = ExtensionLocalLibrary(repository, browse, environment.workDispatcher, now = { 5_000L })
        val reviews = PluginReviewCoordinator(
            browse,
            scope,
            environment.messages,
            acknowledgeLink = {},
            workDispatcher = environment.workDispatcher,
        )
        val titles = TitleNavigationController(navigator, repository, browse, reviews, localLibrary, environment)
    }

    @Test
    fun anUnknownOrSourceTitleOpensItsDetail() = runTest {
        val f = Fixture(this)
        f.titles.openManga(42L)
        assertEquals(Destination.Manga(42L), f.navigator.state.value.destination)
        val manga = f.repository.upsertManga(Manga(source = 9L, url = "/m", title = "M"))
        f.titles.openManga(manga.id)
        assertEquals(Destination.Manga(manga.id), f.navigator.state.value.destination)
    }

    @Test
    fun aLibraryRowOfAnExtensionPublicationOpensThePublicationInTheLibrary() = runTest {
        val f = Fixture(this)
        val manga = f.repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(sourceKey, "r1"),
                title = "Novel",
                favorite = true,
            ),
        )
        f.titles.openManga(manga.id)
        val state = f.navigator.state.value
        assertEquals(MainSection.LIBRARY, state.section)
        assertEquals(sourceKey, state.localLibraryPublication?.sourceKey)
        assertEquals("r1", state.localLibraryPublication?.remotePublicationId)
    }

    @Test
    fun aTitleWaitingForItsPermissionReviewIsNotShownOverAPageTheUserMovedTo() = runTest {
        val f = Fixture(this)
        val extension = f.browse.browseState.value.extensions.single()
        f.browse.browseState.value = BrowseSnapshot(extensions = listOf(extension.copy(trusted = false)))
        f.browse.pendingReview = PluginEventGrantReview(
            artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
            sourceKeys = listOf(sourceKey),
            requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        )
        val manga = f.repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(sourceKey, "r1"),
                title = "Novel",
                favorite = true,
            ),
        )
        var outcome: TitleOpenOutcome? = null
        f.titles.openManga(manga.id) { outcome = it }
        // The user switches tabs while the review is being read.
        f.navigator.selectSection(MainSection.UPDATES)
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(MainSection.UPDATES, f.navigator.state.value.section)
        assertNull(f.navigator.state.value.localLibraryPublication)
        assertEquals(TitleOpenOutcome.ABANDONED, outcome)
    }

    @Test
    fun aNewerTitleOpenSupersedesOneStillInItsPermissionReview() = runTest {
        val f = Fixture(this)
        val extension = f.browse.browseState.value.extensions.single()
        f.browse.browseState.value = BrowseSnapshot(extensions = listOf(extension.copy(trusted = false)))
        f.browse.pendingReview = PluginEventGrantReview(
            artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
            sourceKeys = listOf(sourceKey),
            requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        )
        val publication = f.repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(sourceKey, "r1"),
                title = "Novel",
                favorite = true,
            ),
        )
        // A typed title: its binding is read off the UI thread, so the page does not change at once.
        val other = f.typedLocalTitleWithAChapter()
        var outcome: TitleOpenOutcome? = null
        f.titles.openManga(publication.id) { outcome = it }
        // Another title is opened on the same page while the review is being read.
        f.titles.openManga(other.id)
        advanceUntilIdle()
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(Destination.Manga(other.id), f.navigator.state.value.destination)
        assertEquals(TitleOpenOutcome.ABANDONED, outcome)
    }

    @Test
    fun aCancelledOpenTakesItsPermissionReviewAway() = runTest {
        val f = Fixture(this)
        val extension = f.browse.browseState.value.extensions.single()
        f.browse.browseState.value = BrowseSnapshot(extensions = listOf(extension.copy(trusted = false)))
        f.browse.pendingReview = PluginEventGrantReview(
            artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
            sourceKeys = listOf(sourceKey),
            requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        )
        val publication = f.repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(sourceKey, "r1"),
                title = "Novel",
                favorite = true,
            ),
        )
        var outcome: TitleOpenOutcome? = null
        val request = f.titles.openManga(publication.id) { outcome = it }
        advanceUntilIdle()
        assertNotNull(f.reviews.state.value.sourceReviewGate)
        // An older request does not touch it.
        f.titles.cancelOpen(request - 1)
        assertNotNull(f.reviews.state.value.sourceReviewGate)
        // The shell that asked stopped handling links.
        f.titles.cancelOpen(request)
        assertNull(f.reviews.state.value.sourceReviewGate)
        assertEquals(TitleOpenOutcome.ABANDONED, outcome)
        assertNull(f.navigator.state.value.localLibraryPublication)
    }

    @Test
    fun aCancelledOpenStopsALegacyRecoveryToo() = runTest {
        val f = Fixture(this)
        val request = f.navigator.beginRecovery(5L)
        f.titles.cancelOpen()
        assertNull(f.navigator.state.value.recoveringLocalLibraryMangaId)
        assertFalse(f.navigator.isRecovering(request, 5L))
    }

    @Test
    fun aResolvedSourceTitleReplacesItsPendingPage() = runTest {
        val f = Fixture(this)
        f.browse.resolved = 7L
        f.navigator.openBrowseManga(item)
        f.titles.resolvePending(item, checkNotNull(f.navigator.state.value.stack.pending).request)
        assertEquals(Destination.Manga(7L), f.navigator.state.value.destination)
        assertTrue(f.messages.isEmpty())
    }

    @Test
    fun aSourceTitleThatCannotBeOpenedSaysSo() = runTest {
        val f = Fixture(this)
        f.browse.resolveFailure = IllegalStateException("source offline")
        f.navigator.openBrowseManga(item)
        f.titles.resolvePending(item, checkNotNull(f.navigator.state.value.stack.pending).request)
        assertEquals(Destination.Section, f.navigator.state.value.destination)
        assertEquals(listOf("source offline"), f.messages)
    }

    @Test
    fun aCancelledPendingTitleIsNotOpenedLater() = runTest {
        val f = Fixture(this)
        f.browse.resolved = 7L
        f.navigator.openBrowseManga(item)
        val request = checkNotNull(f.navigator.state.value.stack.pending).request
        f.navigator.cancelPendingBrowse()
        f.titles.resolvePending(item, request)
        assertEquals(Destination.Section, f.navigator.state.value.destination)
    }

    @Test
    fun favoritingAndUnfavoritingAPublicationKeepsOneLibraryRow() = runTest {
        val f = Fixture(this)
        val publication = RemotePublicationV2(remoteId = "r1", title = "Novel", author = "A")
        assertFalse(f.localLibrary.isFavorite(item))
        f.localLibrary.setFavorite(item, publication, favorite = true)
        assertTrue(f.localLibrary.isFavorite(item))
        f.localLibrary.setFavorite(item, publication.copy(title = "Novel #r1"), favorite = false)
        advanceUntilIdle()
        val rows = f.repository.currentSnapshot.mangas
        assertEquals(1, rows.size)
        assertFalse(rows.single().favorite)
        assertEquals(listOf("r1"), f.browse.removedDownloads)
        assertEquals(listOf("r1"), f.browse.purged)
    }

    @Test
    fun removingTheOpenPublicationFromTheLibraryClosesIt() = runTest {
        val f = Fixture(this)
        val publication = RemotePublicationV2(remoteId = "r1", title = "Novel")
        f.localLibrary.setFavorite(item, publication, favorite = true)
        val row = f.repository.currentSnapshot.mangas.single()
        f.navigator.openLocalExtension(item, MainSection.LIBRARY)
        f.titles.closeRemovedLibraryTitles(setOf(row.id))
        assertEquals(null, f.navigator.state.value.localLibraryPublication)
    }

    @Test
    fun aTypedTitleWhoseLookupFinishesAfterTheUserMovedOnDoesNotNavigate() = runTest {
        val f = Fixture(this)
        val typed = f.typedLocalTitleWithAChapter()
        f.titles.openManga(typed.id)
        f.navigator.selectSection(MainSection.HISTORY)
        advanceUntilIdle()
        assertEquals(MainSection.HISTORY, f.navigator.state.value.section)
        assertEquals(Destination.Section, f.navigator.state.value.destination)
    }

    @Test
    fun aTypedTitleLookupIsDroppedAfterTheUserLeftAndCameBack() = runTest {
        val f = Fixture(this)
        val typed = f.typedLocalTitleWithAChapter()
        val start = f.navigator.state.value
        f.titles.openManga(typed.id)
        f.navigator.selectSection(MainSection.HISTORY)
        f.navigator.selectSection(start.section)
        advanceUntilIdle()
        assertEquals(start.section, f.navigator.state.value.section)
        assertEquals(Destination.Section, f.navigator.state.value.destination)
    }

    @Test
    fun aTypedTitleWhoseRowBecameAnotherTitleDuringTheLookupIsNotOpened() = runTest {
        val f = Fixture(this)
        val typed = f.typedLocalTitleWithAChapter()
        f.titles.openManga(typed.id)
        // A restored backup gave the row's id to another title while the binding was read.
        val replacement = encodeTypedLocalPublicationUrl(extensionPublicationKey(sourceKey, "typed-2"))
        f.repository.updateManga(typed.id) { it.copy(url = replacement, title = "Other") }
        advanceUntilIdle()
        assertEquals(Destination.Section, f.navigator.state.value.destination)
        assertEquals(replacement, f.repository.manga(typed.id)?.url)
    }

    @Test
    fun aNewerOpenSupersedesATypedTitleLookupInFlight() = runTest {
        val f = Fixture(this)
        val typed = f.typedLocalTitleWithAChapter()
        val other = f.repository.upsertManga(Manga(source = 9L, url = "/other", title = "Other"))
        f.titles.openManga(typed.id)
        f.titles.openManga(other.id)
        advanceUntilIdle()
        assertEquals(Destination.Manga(other.id), f.navigator.state.value.destination)
    }

    @Test
    fun aTypedTitleOpensItsDetailOnceTheLookupFinishes() = runTest {
        val f = Fixture(this)
        val typed = f.typedLocalTitleWithAChapter()
        f.titles.openManga(typed.id)
        advanceUntilIdle()
        assertEquals(Destination.Manga(typed.id), f.navigator.state.value.destination)
    }

    @Test
    fun aRecoveryLeftWithBackStopsSearchingAndSettlesAtOnce() = runTest {
        val f = Fixture(this)
        val legacy = f.legacyFavorite()
        val outcomes = mutableListOf<TitleOpenOutcome>()
        f.titles.openManga(legacy.id) { outcomes += it }
        advanceUntilIdle()
        assertEquals(Destination.Recovering(legacy.id), f.navigator.state.value.destination)
        f.navigator.cancelRecovery()
        advanceUntilIdle()
        // A link waiting on this open moves on now, not once every source has answered.
        assertEquals(listOf(TitleOpenOutcome.ABANDONED), outcomes)
        assertTrue(f.browse.recoverySearchStopped)
    }

    @Test
    fun aNewerOpenEndsARecoveryStillSearching() = runTest {
        val f = Fixture(this)
        val legacy = f.legacyFavorite()
        val other = f.repository.upsertManga(Manga(source = 9L, url = "/other", title = "Other"))
        f.titles.openManga(legacy.id)
        advanceUntilIdle()
        f.titles.openManga(other.id)
        advanceUntilIdle()
        assertEquals(Destination.Manga(other.id), f.navigator.state.value.destination)
        assertNull(f.navigator.state.value.recoveringLocalLibraryMangaId)
        assertTrue(f.browse.recoverySearchStopped)
    }

    @Test
    fun aRecoveryThatFindsNothingSettlesBeforeSayingSo() = runTest {
        val f = Fixture(this)
        val legacy = f.legacyFavorite()
        f.titles.openManga(legacy.id) { f.messages += "settled $it" }
        advanceUntilIdle()
        f.browse.recoverySearch.complete(null)
        advanceUntilIdle()
        assertEquals("settled ABANDONED", f.messages.first())
        assertEquals(2, f.messages.size)
        assertNull(f.navigator.state.value.recoveringLocalLibraryMangaId)
        assertFalse(f.browse.recoverySearchStopped)
    }

    /** A beta-era favorite: a UUID-only row with no chapters and no binding, so it is recovered. */
    private suspend fun Fixture.legacyFavorite(): Manga = repository.upsertManga(
        Manga(
            source = LOCAL_SOURCE_ID,
            url = encodeTypedLocalPublicationUrl(extensionPublicationKey(sourceKey, "legacy-1")),
            title = "Legacy",
            favorite = true,
        ),
    )

    /** A typed local row with a chapter: once its binding lookup finds nothing, it opens its detail page. */
    private suspend fun Fixture.typedLocalTitleWithAChapter(): Manga {
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(extensionPublicationKey(sourceKey, "typed-1")),
                title = "Typed",
            ),
        )
        repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "One"))
        return manga
    }
}
