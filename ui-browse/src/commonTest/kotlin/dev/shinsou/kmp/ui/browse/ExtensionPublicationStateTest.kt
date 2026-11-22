@file:OptIn(dev.shinsou.kmp.testing.TestOnlyApi::class)

package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.plugin.PluginTestAccess
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.EmptyBrowseCallbacks
import dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.ui.browse.ExtensionFavoriteDestination
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.ResourceRef
import dev.shinsou.kmp.domain.content.TextBlock
import dev.shinsou.kmp.domain.content.UnifiedReaderContent
import dev.shinsou.kmp.domain.content.UnifiedReaderNavigationFactory
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.ui.reader.ContentFeatures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import dev.shinsou.kmp.ui.i18n.StringKey
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionPublicationStateTest {
    private val sourceKey = SourceKey(packageId = "pkg", sourceId = "novels")
    private val item = BrowseManga(sourceId = 0L, url = "", title = "Novel", sourceKey = sourceKey, remotePublicationId = "p1")
    private val publication = RemotePublicationV2(remoteId = "p1", title = "Novel")
    private val owner = Any()

    private fun unit(id: String) = PluginTestAccess.unitSelection(sourceKey, "p1", "Novel", RemoteUnitV2(id, "Chapter $id"), owner)

    private fun page(number: Int, ids: List<String>, hasNext: Boolean, offline: Boolean = false) =
        PluginTestAccess.publicationPage(sourceKey, publication, ids.map(::unit), number to hasNext, offline)

    private inner class FakeBridge : EmptyBrowseCallbacks() {
        val pages = mutableMapOf<Int, ExtensionPublicationPageV2>()
        var cached: ExtensionPublicationPageV2? = null
        var stored: ExtensionPublicationPageV2? = null

        override suspend fun storedExtensionPublicationPageV2(sourceKey: SourceKey, publication: RemotePublicationV2) = stored
        val cachedWrites = mutableListOf<List<String>>()
        val sourceFavorites = mutableListOf<Boolean>()

        override suspend fun favoriteExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String, favorite: Boolean) {
            sourceFavorites += favorite
        }
        var reading = ExtensionLocalReadingStateV2()
        var readingGate: CompletableDeferred<Unit>? = null
        val enqueued = mutableListOf<String>()

        override suspend fun extensionPublicationPageV2(sourceKey: SourceKey, remotePublicationId: String, page: Int) =
            pages.getValue(page)

        override suspend fun extensionDetailsV2(sourceKey: SourceKey, remotePublicationId: String) = publication

        override suspend fun cachedExtensionPublicationPageV2(sourceKey: SourceKey, remotePublicationId: String) = cached

        override suspend fun cacheExtensionPublicationV2(
            sourceKey: SourceKey,
            publication: RemotePublicationV2,
            units: List<RemoteUnitV2>,
        ) {
            cachedWrites += units.map { it.remoteId }
        }

        override fun extensionLocalReadingStateV2(publicationKey: PublicationKey) = reading

        override fun extensionDownloadedUnitIdsV2(publicationKey: PublicationKey) = setOf("u2")

        override suspend fun enqueueExtensionDownloadsV2(selections: List<ExtensionUnitSelectionV2>) {
            enqueued += selections.map { it.unit.remoteId }
        }

        /** Holds the next chapter open until completed (a slow chapter load). */
        var openGate: CompletableDeferred<Unit>? = null

        /** Makes chapter opens fail while set (the source cannot be reached). */
        var openFailure: Throwable? = null

        override suspend fun materializeExtensionContentV2(selection: ExtensionUnitSelectionV2, representationId: String?) =
            materialization(selection.unit.remoteId).also {
                openGate?.await()
                openFailure?.let { throw it }
            }

        override suspend fun openMaterializedExtensionContentV2(materialization: ExtensionContentMaterializationV2) =
            readerSession()
    }

    private val publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111")

    private fun materialization(unitId: String) = ExtensionContentMaterializationV2(
        sourceKey = sourceKey,
        remotePublicationId = "p1",
        remoteUnitId = unitId,
        representationId = "text",
        publicationKey = publicationKey,
        acquisitionId = "22222222-2222-4222-8222-222222222222",
        unitKey = UnitKey(publicationKey, "33333333-3333-4333-8333-333333333333"),
        manifestId = "44444444-4444-4444-8444-444444444444",
        contentRepresentationId = "55555555-5555-4555-8555-555555555555",
        commit = ContentCommitResult("c", replayed = false, deferred = false, committedGeneration = null, attachedOwnerIds = emptyList(), outboxDraftIds = emptyList()),
    )

    private fun readerSession(): TypedReaderContentSession {
        val text = "text"
        val scope = ReadingScope(1, publicationKey, "22222222-2222-4222-8222-222222222222", UnitKey(publicationKey, "33333333-3333-4333-8333-333333333333"), 1)
        val representation = ContentRepresentation.PlainText(
            representationId = "55555555-5555-4555-8555-555555555555",
            resource = ResourceRef(
                id = "body",
                blob = BlobRef(
                    blobId = "66666666-6666-4666-8666-666666666666",
                    schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                    digestAlgorithm = BlobRef.SHA_256,
                    plaintextDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    byteSize = 0,
                    mediaType = "text/plain",
                ),
            ),
            canonicalUtf16Length = text.length,
            blocks = listOf(TextBlock("paragraph", 0, text.length)),
        )
        return TypedReaderContentSession(
            content = UnifiedReaderContent(UnifiedReaderNavigationFactory.create(scope, representation, text)),
            canonicalText = text,
            access = ContentAccessRequest(
                grantReference = null,
                scope = RightsScope(scope.publicationId, scope.acquisitionId, scope.unitId, "44444444-4444-4444-8444-444444444444", scope.contentRevision),
            ),
        )
    }

    /** Present so chapters open in the reader; the reader itself is not drawn here. */
    private object ReaderFeatures : ContentFeatures {
        override val translation get() = null
        override val operations get() = error("unused")
        override val searchIndex get() = error("unused")
        override val annotations get() = error("unused")
        override val textToSpeech get() = error("unused")
        override val speechCapability get() = error("unused")
        override val epubRenderRequests get() = error("unused")
        override val epubSemanticDocuments get() = error("unused")
        override val imageRenderPages get() = error("unused")
        override fun publicationContentKinds(publicationKeys: Set<PublicationKey>): Map<PublicationKey, Set<ContentKind>> = emptyMap()
        override suspend fun cleanupRevokedDerivedData(scope: ReadingScope, access: ContentAccessRequest) = error("unused")
    }

    private fun favorites(localLibrary: Boolean, favorite: Boolean) = PublicationFavorites(
        supportsFavorite = true,
        destination = ExtensionFavoriteDestination.LOCAL_LIBRARY,
        localLibrary = localLibrary,
        localLibraryFavorite = favorite,
    )

    @Test
    fun aLibraryTitleLoadsEveryPageAndSavesTheCompleteList() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = true)
        bridge.pages[1] = page(1, listOf("u3"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        assertEquals(listOf("u1", "u2", "u3"), state.units.map { it.unit.remoteId })
        assertFalse(state.loading)
        assertTrue(state.unitDirectoryComplete)
        assertEquals(setOf("u2"), state.downloadedUnitIds)
        assertEquals(listOf(listOf("u1", "u2", "u3")), bridge.cachedWrites)
    }

    @Test
    fun aRestoredChapterStaysPendingUntilItsOpenEnds() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()

        var settled = 0
        assertTrue(state.reopenUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2")) { settled++ })
        assertEquals(0, settled, "still opening: the chapter is still the one to save")
        advanceUntilIdle()
        assertEquals(1, settled)

        // A chapter no longer listed is given up at once.
        assertFalse(state.reopenUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("gone")) { settled++ })
        assertEquals(2, settled)
    }

    @Test
    fun aRestoredChapterTheSourceNoLongerListsOpensFromTheDevice() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        bridge.stored = page(0, listOf("u9"), hasNext = false, offline = true)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        var settled = 0
        state.loadAndReopen(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u9"), { true }) { settled++ }
        assertEquals("u9", state.busyUnitId, "the stored chapter is opening")
        advanceUntilIdle()
        assertEquals(1, settled)
    }

    @Test
    fun aRestoredChapterGivenUpWhileOpeningDoesNotTakeOver() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        var wanted = true
        var settled = 0
        assertTrue(state.reopenUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1"), { wanted }) { settled++ })
        // The user tapped the same title again while it opened.
        wanted = false
        advanceUntilIdle()
        assertEquals(1, settled)
        assertFalse("u1" in state.downloadedUnitIds, "the open's result is not applied")
        assertNull(state.operationMessage)
        // Already given up: nothing opens.
        assertFalse(state.reopenUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2"), { false }) { settled++ })
        assertEquals(2, settled)
        assertNull(state.busyUnitId)
    }

    @Test
    fun leavingTheReaderWhileTheNextChapterLoadsKeepsItClosed() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u3"), hasNext = false)
        val state = ExtensionPublicationState(
            item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true),
            ExtensionReaderHost(contentFeatures = ReaderFeatures), screenWork(),
        )
        state.load()
        advanceUntilIdle()
        assertTrue(state.materialize(state.units[0], openReader = true))
        advanceUntilIdle()
        assertEquals("u1", state.readerUnitId)

        // The next chapter loads slowly and the user presses Back meanwhile.
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        assertTrue(state.materialize(state.units[1], openReader = true))
        advanceUntilIdle()
        state.closeReader()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.readerSession, "the loaded chapter does not reopen the reader")
        assertTrue("u3" in state.downloadedUnitIds, "it is still stored offline")

        // The next chapter the user opens shows as usual.
        bridge.openGate = null
        assertTrue(state.materialize(state.units[0], openReader = true))
        advanceUntilIdle()
        assertEquals("u1", state.readerUnitId)
    }

    @Test
    fun leavingThePageWhileTheFirstChapterLoadsOpensNoReader() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = ExtensionPublicationState(
            item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true),
            ExtensionReaderHost(contentFeatures = ReaderFeatures), screenWork(),
        )
        val visibility = mutableListOf<Boolean>()
        state.onReaderVisibilityChanged = { visibility += it }
        var shown = true
        state.isShown = { shown }
        state.load()
        advanceUntilIdle()

        // The first chapter loads slowly; the user goes back to the catalogue meanwhile. The
        // page is still drawn while it animates away, so it is not yet disposed.
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        assertTrue(state.materialize(state.units[0], openReader = true))
        advanceUntilIdle()
        shown = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.readerSession, "a page the user left opens no reader")
        assertTrue(true !in visibility, "nor hides the shell's bar for one")
        assertTrue("u1" in state.downloadedUnitIds, "the chapter is still stored offline")
    }

    @Test
    fun closingBeforeTheFirstChapterShowsKeepsTheReaderClosed() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = ExtensionPublicationState(
            item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true),
            ExtensionReaderHost(contentFeatures = ReaderFeatures), screenWork(),
        )
        state.load()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        assertTrue(state.materialize(state.units[0], openReader = true))
        advanceUntilIdle()
        state.closeReader()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.readerSession)
    }

    private fun TestScope.readerState(bridge: FakeBridge) = ExtensionPublicationState(
        item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true),
        ExtensionReaderHost(contentFeatures = ReaderFeatures), screenWork(),
    )

    @Test
    fun continueReadingCoversThePageUntilTheReaderShows() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = readerState(bridge)
        val visibility = mutableListOf<Boolean>()
        state.onReaderVisibilityChanged = { visibility += it }
        val identity = dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2")
        val cover = state.coverForReader(identity)
        assertEquals(listOf(true), visibility, "the shell shows the reader from the start")
        state.loadAndReopen(identity, { true }) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        assertEquals("u2", state.readerUnitId)
        assertNull(state.readerCover, "the reader replaced the cover")
        assertTrue(false !in visibility, "the page never showed in between")
    }

    @Test
    fun continueReadingThatCannotOpenSaysWhyAndRetries() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        bridge.openFailure = IllegalStateException("offline")
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1"))
        state.continueUnit(cover.unit, { true }) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        assertNull(state.readerSession)
        assertNotNull(state.readerCover?.failure, "the cover says why")

        bridge.openFailure = null
        state.retryReaderCover()
        assertNull(state.readerCover?.failure, "opening again")
        advanceUntilIdle()
        assertEquals("u1", state.readerUnitId)
        assertNull(state.readerCover)
    }

    @Test
    fun retryAfterTheListFailedAsksForTheListAgain() = runTest {
        val bridge = FakeBridge()
        val state = readerState(bridge)
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1"))
        state.loadAndReopen(cover.unit, { true }, userRequested = true) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        assertNotNull(state.loadError)
        assertNotNull(state.readerCover?.failure, "the source could not be reached")

        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        state.retryReaderCover()
        advanceUntilIdle()
        assertEquals("u1", state.readerUnitId)
        assertNull(state.readerCover)
    }

    @Test
    fun continueReadingWhileAnotherChapterOpensOpensAfterIt() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        assertTrue(state.materialize(state.units.first()))
        runCurrent()
        bridge.openGate = null
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2"))
        val continuing = launch { state.continueUnit(cover.unit, { true }) { state.coveredOpenSettled(cover, reportFailure = true) } }
        runCurrent()
        assertNull(state.readerCover?.failure, "it waits rather than failing")
        gate.complete(Unit)
        advanceUntilIdle()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertTrue(continuing.isCompleted)
        assertEquals("u2", state.readerUnitId)
        assertNull(state.readerCover)
    }

    @Test
    fun aCoverWhoseOpenNeverBeganGoesWhenItsWorkStops() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        val visibility = mutableListOf<Boolean>()
        state.onReaderVisibilityChanged = { visibility += it }
        val waiting = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1"))
        state.coveredOpenCancelled(waiting)
        assertNull(state.readerCover)
        assertEquals(listOf(true, false), visibility)

        // One whose chapter opens already stays until that open settles.
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        val opening = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1"))
        assertTrue(state.materialize(state.units.first()))
        state.coveredOpenCancelled(opening)
        assertEquals(opening, state.readerCover)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.readerCover, "the reader replaced it")
    }

    @Test
    fun aRequestedChapterOpensOnAPageWhereTheUserChoseAnother() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        assertTrue(state.materialize(state.units.first()))
        advanceUntilIdle()
        state.closeReader()
        advanceUntilIdle()
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2"))
        state.loadAndReopen(cover.unit, { true }, userRequested = true) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        assertEquals("u2", state.readerUnitId)
    }

    @Test
    fun aChapterNoLongerListedLeavesTheCoverWithAReason() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("gone"))
        state.continueUnit(cover.unit, { true }) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        assertEquals(StringKey.THIS_CHAPTER_IS_NOT_LISTED_AND_NOT.english, state.readerCover?.failure)
    }

    @Test
    fun aRestoredChapterThatCannotOpenSimplyShowsThePage() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = readerState(bridge)
        val visibility = mutableListOf<Boolean>()
        state.onReaderVisibilityChanged = { visibility += it }
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("gone"))
        state.loadAndReopen(cover.unit, { true }) { state.coveredOpenSettled(cover, reportFailure = false) }
        advanceUntilIdle()
        assertNull(state.readerCover)
        assertEquals(listOf(true, false), visibility)
    }

    @Test
    fun leavingTheCoverGivesTheOpenUpAndShowsThePage() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1"), hasNext = false)
        val state = readerState(bridge)
        state.load()
        advanceUntilIdle()
        val visibility = mutableListOf<Boolean>()
        state.onReaderVisibilityChanged = { visibility += it }
        var gaveUp = 0
        val gate = CompletableDeferred<Unit>().also { bridge.openGate = it }
        val cover = state.coverForReader(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u1")) { gaveUp++ }
        state.continueUnit(cover.unit, { gaveUp == 0 }) { state.coveredOpenSettled(cover, reportFailure = true) }
        advanceUntilIdle()
        state.leaveReaderCover()
        assertEquals(1, gaveUp)
        assertNull(state.readerCover)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.readerSession, "the chapter that finished loading does not open over the page")
        assertNull(state.readerCover, "nor does a failure cover come back")
        assertEquals(listOf(true, false), visibility)
    }

    @Test
    fun continueReadingOnAnOpenPageOpensTheChapterEvenAfterTheUserChoseOne() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        // The user read u1 here; "continue reading" now asks for u2 on the same page.
        assertTrue(state.materialize(state.units.first(), openReader = true))
        advanceUntilIdle()
        var settled = 0
        state.continueUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2"), { true }) { settled++ }
        assertEquals("u2", state.busyUnitId, "the asked-for chapter is opening")
        advanceUntilIdle()
        assertEquals(1, settled)
    }

    @Test
    fun continueReadingWaitsForThePageStillLoading() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        var settled = 0
        val continuing = launch { state.continueUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2"), { true }) { settled++ } }
        runCurrent()
        assertNull(state.busyUnitId, "nothing is listed yet")
        state.load()
        // The UI's snapshot manager would send these: the page's list changed.
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertTrue(continuing.isCompleted)
        assertEquals(1, settled)
    }

    @Test
    fun aRestoredChapterDoesNotReplaceOneTheUserChose() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        // The user opens u1 before the saved u2 is reopened.
        assertTrue(state.materialize(state.units.first(), openReader = true))
        advanceUntilIdle()
        var settled = 0
        assertFalse(state.reopenUnit(dev.shinsou.kmp.ui.browse.extensionUnitIdentity("u2")) { settled++ })
        assertEquals(1, settled)
    }

    @Test
    fun aSavedListStaysWhenARefreshComesBackPartial() = runTest {
        val bridge = FakeBridge()
        bridge.cached = page(0, listOf("u1", "u2", "u3"), hasNext = false, offline = true)
        bridge.pages[0] = page(0, listOf("u1"), hasNext = true)
        bridge.pages[1] = page(1, emptyList(), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, true), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        assertEquals(listOf("u1", "u2", "u3"), state.units.map { it.unit.remoteId })
        // The partial list is not written over the saved one (fresh details still are, with the saved list).
        assertTrue(bridge.cachedWrites.all { it == listOf("u1", "u2", "u3") })
    }

    @Test
    fun aHandSetMarkSurvivesALateReadingState() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2"), hasNext = false)
        bridge.reading = ExtensionLocalReadingStateV2(completedRemoteUnitIds = setOf("u1"))
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(false, false), ExtensionReaderHost(), screenWork())
        state.setReadState(setOf("u1"), read = false)
        launch { state.load() }
        advanceUntilIdle()
        assertFalse("u1" in state.readUnitIds)
    }

    @Test
    fun downloadsSkipChaptersAlreadyOfflineOrQueued() = runTest {
        val bridge = FakeBridge()
        bridge.pages[0] = page(0, listOf("u1", "u2", "u3"), hasNext = false)
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(false, false), ExtensionReaderHost(), screenWork())
        state.load()
        advanceUntilIdle()
        state.downloadUnits(state.units, queuedUnitIds = setOf("u3"))
        advanceUntilIdle()
        assertEquals(listOf("u1"), bridge.enqueued)
        state.downloadUnits(state.units.filter { it.unit.remoteId == "u2" }, queuedUnitIds = emptySet())
        assertEquals(listOf("u1"), bridge.enqueued)
        assertTrue(state.operationMessage?.isNotBlank() == true)
    }

    @Test
    fun theLibrarysFavoriteStateReplacesThePagesOwn() = runTest {
        val bridge = FakeBridge()
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), favorites(true, false), ExtensionReaderHost(), screenWork())
        assertFalse(state.favorite)
        state.syncLocalFavorite(true)
        assertTrue(state.favorite)
        assertTrue(state.inLibrary)
        // The same answer again changes nothing.
        state.syncLocalFavorite(true)
        assertTrue(state.favorite)
        assertNull(state.readerSession)
    }

    @Test
    fun aFavoriteGoesWhereTheLiveSourceListSays() = runTest {
        val bridge = FakeBridge()
        val account = PublicationFavorites(
            supportsFavorite = true,
            destination = ExtensionFavoriteDestination.SOURCE_ACCOUNT,
            localLibrary = false,
            localLibraryFavorite = false,
        )
        val localFavorites = mutableListOf<Boolean>()
        val host = ExtensionReaderHost(library = ExtensionLibraryHost(setFavorite = { _, _, next -> localFavorites += next }))
        val state = ExtensionPublicationState(item, ExtensionPublicationServices(bridge, bridge, bridge, bridge), account, host, screenWork())
        // A background refresh removed the source while the page was open.
        state.syncFavorites(account.copy(destination = ExtensionFavoriteDestination.LOCAL_LIBRARY))
        state.toggleFavorite()
        advanceUntilIdle()

        assertEquals(listOf(true), localFavorites, "favorited in the app's library")
        assertTrue(bridge.sourceFavorites.isEmpty(), "not in the removed source's account")
        assertTrue(state.inLibrary)
    }
}
