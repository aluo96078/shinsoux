package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubRendition
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EpubBrowserContractTest {
    @Test
    fun crossSpineAndFragmentNavigationActivateTheRealDocumentWithoutRestoringOverAnchor() {
        val fixture = fixture()
        val state = EpubBrowserDocumentState(fixture.request)
        val targetUrl = fixture.request.publicationUrl(fixture.chapterB) + "#target"
        val originalRequest = state.committedRequest

        val target = assertNotNull(state.navigationRequested(targetUrl))
        assertEquals(1, target.documentIndex)
        assertEquals("OPS/b.xhtml", target.resourceHref)
        assertEquals("target", target.fragment)
        assertTrue(target.hasExplicitAnchor)
        assertSame(originalRequest, state.committedRequest)
        assertSame(target, state.pendingTarget)
        assertFalse(state.canSampleViewport)
        val generation = assertNotNull(state.pendingLoadGeneration)

        val loaded = assertNotNull(state.documentLoaded(targetUrl, generation))
        assertFalse(loaded.restoreInitialLocator)
        assertSame(target.request, state.committedRequest)
        assertNull(state.pendingTarget)
        assertTrue(state.canSampleViewport)
        assertNull(state.documentLoaded(targetUrl, generation))
        val locator = assertNotNull(
            EpubViewportLocatorCoalescer(loaded.target.request).offer(
                progression = 0.42,
                nowMillis = 1_000,
                force = true,
            ),
        )
        assertEquals("OPS/b.xhtml", locator.resourceHref)
        assertEquals(1, locator.spineIndexHint)

        val sameDocumentAnchor = EpubBrowserDocumentState(fixture.request)
        assertNotNull(sameDocumentAnchor.navigationRequested(fixture.request.documentUrl + "#footnote"))
        val anchorGeneration = assertNotNull(sameDocumentAnchor.pendingLoadGeneration)
        assertFalse(
            assertNotNull(
                sameDocumentAnchor.documentLoaded(
                    fixture.request.documentUrl + "#footnote",
                    anchorGeneration,
                ),
            )
                .restoreInitialLocator,
        )
    }

    @Test
    fun failedDocumentLoadRollsBackPendingIdentityAndKeepsViewportSamplingAvailable() {
        val fixture = fixture()
        val state = EpubBrowserDocumentState(fixture.request)
        val originalRequest = state.committedRequest
        val replacementRequest = fixture(initialProgression = 0.75).request

        state.updateHostRequest(replacementRequest)
        assertSame(replacementRequest, assertNotNull(state.pendingTarget).request)
        assertSame(originalRequest, state.committedRequest)
        assertFalse(state.canSampleViewport)
        val generation = assertNotNull(state.pendingLoadGeneration)

        val rollback = assertNotNull(state.documentLoadFailed(generation))
        assertSame(originalRequest, rollback.request)
        assertSame(originalRequest, state.committedRequest)
        assertEquals(state.initialBrowserDocumentUrl, rollback.browserUrl)
        assertNull(state.pendingTarget)
        assertTrue(state.canSampleViewport)
    }

    @Test
    fun onlyAnUnmodifiedHostDocumentLoadMayRestoreTheInitialLocator() {
        val fixture = fixture(initialProgression = 0.67)
        val state = EpubBrowserDocumentState(fixture.request)

        assertNotNull(state.navigationRequested(fixture.request.documentUrl))
        val initialGeneration = assertNotNull(state.pendingLoadGeneration)
        val firstLoad = assertNotNull(state.documentLoaded(fixture.request.documentUrl, initialGeneration))
        assertTrue(firstLoad.restoreInitialLocator)
        assertEquals(0.67, firstLoad.target.request.initialDocumentProgression)

        assertNotNull(state.navigationRequested(fixture.request.publicationUrl(fixture.chapterB)))
        val nextGeneration = assertNotNull(state.pendingLoadGeneration)
        assertFalse(
            assertNotNull(
                state.documentLoaded(fixture.request.publicationUrl(fixture.chapterB), nextGeneration),
            )
                .restoreInitialLocator,
        )
    }

    @Test
    fun hostLoadGenerationTokenDoesNotConsumeTheInitialLocatorRestore() {
        val fixture = fixture(initialProgression = 0.67)
        val state = EpubBrowserDocumentState(fixture.request)
        val browserUrl = state.updateHostRequest(fixture.request)
        val generation = assertNotNull(state.pendingLoadGeneration)
        val taggedUrl = epubBrowserUrlWithLoadGeneration(browserUrl, generation)

        assertNotNull(state.navigationRequested(taggedUrl))
        val loaded = assertNotNull(state.documentLoaded(taggedUrl, generation))

        assertTrue(loaded.restoreInitialLocator)
        assertEquals(browserUrl, loaded.target.browserUrl)
        assertEquals(fixture.request.documentUrl, loaded.target.canonicalUrl)
    }

    @Test
    fun supersededSameUrlSuccessAndFailureCannotSettleTheNewerLoad() {
        val original = fixture().request
        val state = EpubBrowserDocumentState(original)
        val requestB = fixture(initialProgression = 0.5).request
        val requestC = fixture(initialProgression = 0.75).request

        val urlB = state.updateHostRequest(requestB)
        val generationB = assertNotNull(state.pendingLoadGeneration)
        val taggedB = epubBrowserUrlWithLoadGeneration(urlB, generationB)
        val urlC = state.updateHostRequest(requestC)
        val generationC = assertNotNull(state.pendingLoadGeneration)

        assertEquals(urlB, urlC)
        assertNotEquals(generationB, generationC)
        assertEquals(generationB, epubBrowserLoadGeneration(taggedB))
        assertEquals(urlB, epubBrowserUrlWithoutLoadGeneration(taggedB))
        assertNull(state.documentLoaded(taggedB, generationB))
        assertNull(state.documentLoadFailed(generationB))
        assertSame(requestC, assertNotNull(state.pendingTarget).request)
        assertSame(original, state.committedRequest)

        val loadedC = assertNotNull(state.documentLoaded(urlC, generationC))
        assertSame(requestC, loadedC.target.request)
        assertSame(requestC, state.committedRequest)
    }

    @Test
    fun currentSameUrlFailureStillRollsBackAfterSupersededSuccessIsIgnored() {
        val original = fixture().request
        val state = EpubBrowserDocumentState(original)
        state.updateHostRequest(fixture(initialProgression = 0.5).request)
        val generationB = assertNotNull(state.pendingLoadGeneration)
        state.updateHostRequest(fixture(initialProgression = 0.75).request)
        val generationC = assertNotNull(state.pendingLoadGeneration)

        assertNull(state.documentLoaded(state.initialBrowserDocumentUrl, generationB))
        val rollback = assertNotNull(state.documentLoadFailed(generationC))
        assertSame(original, rollback.request)
        assertSame(original, state.committedRequest)
        assertTrue(state.canSampleViewport)
    }

    @Test
    fun browserUrlPolicyMapsOneRandomOriginAndRejectsExternalCrossPublicationAndUnknownResources() {
        val fixture = fixture()
        val randomRoot = "shinsou-epub://s-0123456789abcdef.invalid/" +
            fixture.request.publicationRootUrl.removePrefix("shinsou-epub://")
        val policy = EpubBrowserUrlPolicy(fixture.request.publicationRootUrl, randomRoot)
        val browserDocument = assertNotNull(policy.browserUrl(fixture.request.documentUrl))
        val resolver = EpubPublicationResourceResolver(fixture.request)

        assertEquals(fixture.request.documentUrl, policy.canonicalUrl(browserDocument))
        assertTrue(policy.allowsResource(browserDocument, resolver))
        assertNull(policy.canonicalUrl("https://example.com/book.xhtml"))
        assertNull(policy.canonicalUrl(fixture.request.documentUrl))
        assertNull(policy.canonicalUrl(randomRoot.dropLast(1) + "-other/OPS/a.xhtml"))
        assertFalse(policy.allowsResource(randomRoot + "OPS/missing.xhtml", resolver))
        assertNull(policy.canonicalUrl(randomRoot + "../other-publication/chapter.xhtml"))
    }

    @Test
    fun sharedJavascriptContractCoversVerticalHorizontalFixedAndRtlProgression() {
        val fixture = fixture(rightToLeft = true, packageLayout = "pre-paginated")
        val viewport = epubBrowserViewportScript(fixture.request)
        val restore = epubBrowserRestoreScript(fixture.request)
        val selection = epubBrowserSelectionScript(fixture.request)
        val guard = epubBrowserNavigationGuardScript(fixture.request.publicationRootUrl)

        assertTrue("shinsouFixed=true" in viewport)
        assertTrue("shinsouPageRtl=true" in viewport)
        assertTrue("vertical-" in viewport)
        assertTrue("HORIZONTAL" in viewport)
        assertTrue("shinsouAnchorCfi" in viewport)
        assertTrue("depth>shinsouMaxAnchorDepth" in viewport)
        assertTrue("window.location.hash.length>shinsouMaxFragmentLength" in viewport)
        assertTrue("cfi.length<=shinsouMaxAnchorCfiLength" in viewport)
        assertTrue("negative" in viewport && "reverse" in viewport && "default" in viewport)
        assertTrue("scrollLeft" in restore)
        assertTrue("shinsouMetrics()" in selection)
        assertTrue("document.addEventListener('click',guard,true)" in guard)
        assertTrue("document.addEventListener('submit'" in guard)

        assertEquals(
            EpubBrowserViewportSnapshot(
                progression = 0.5,
                axis = EpubBrowserScrollAxis.HORIZONTAL,
                direction = EpubBrowserScrollDirection.REVERSE,
                writingMode = "vertical-rl",
                fixedLayout = true,
            ),
            decodeEpubBrowserViewport(
                """{"progression":0.5,"axis":"HORIZONTAL","direction":"REVERSE","writingMode":"vertical-rl","fixedLayout":true}""",
            ),
        )
        assertNull(
            decodeEpubBrowserViewport(
                """{"progression":1.1,"axis":"VERTICAL","direction":"FORWARD","writingMode":"horizontal-tb","fixedLayout":false}""",
            ),
        )

        val anchoredViewport = EpubBrowserViewportSnapshot(
            progression = 0.4,
            axis = EpubBrowserScrollAxis.HORIZONTAL,
            direction = EpubBrowserScrollDirection.REVERSE,
            writingMode = "vertical-rl",
            fixedLayout = true,
            anchorCfi = "epubcfi(/6/2!/4/2)",
        )
        assertEquals(
            anchoredViewport.anchorCfi,
            EpubViewportLocatorCoalescer(fixture.request)
                .offerViewport(anchoredViewport, nowMillis = 2_000, force = true)
                ?.cfi,
        )

        val oversizedAnchor = "epubcfi(/6/2!" + "/2".repeat(600) + ")"
        assertNull(
            decodeEpubBrowserViewport(
                """{"progression":0.4,"axis":"HORIZONTAL","direction":"REVERSE","writingMode":"vertical-rl","fixedLayout":true,"anchorCfi":"$oversizedAnchor"}""",
            ),
        )
        assertNull(
            decodeEpubBrowserViewport(
                """{"progression":0.4,"axis":"HORIZONTAL","direction":"REVERSE","writingMode":"vertical-rl","fixedLayout":true}""" +
                    " ".repeat(2_049),
            ),
        )
    }

    @Test
    fun readerPresentationScriptsCoverThreeFlowsAnimationAndShuyueTapZones() {
        val request = fixture().request
        val continuous = EpubBrowserConfiguration(
            readingMode = EpubBrowserReadingMode.CONTINUOUS_VERTICAL,
            animatePageTransitions = false,
            fontSizeSp = 22f,
            lineHeightMultiplier = 1.8f,
            maxContentWidthDp = 720f,
        )
        val rtl = continuous.copy(
            readingMode = EpubBrowserReadingMode.PAGED_RIGHT_TO_LEFT,
            animatePageTransitions = true,
        )
        val configured = epubBrowserConfigureScript(request, continuous)
        val instantTurn = epubBrowserNavigationScript(request, continuous, ReaderTapAction.NEXT_PAGE)
        val animatedTurn = epubBrowserNavigationScript(request, rtl, ReaderTapAction.PREVIOUS_PAGE)
        val tap = epubBrowserTapScript(request, rtl, 0.15, 0.5)
        val fixedConfiguration = epubBrowserConfigureScript(
            fixture(packageLayout = "pre-paginated").request,
            continuous,
        )

        assertTrue("CONTINUOUS_VERTICAL" in configured)
        assertTrue("max-width: 720.0px" in configured)
        assertTrue("font-size: 22.0px" in configured)
        assertTrue("line-height: 1.8" in configured)
        assertTrue("window.__shinsouEpubReaderStyleNode" in configured)
        assertTrue("style.__shinsouEpubHostOwned===true" in configured)
        assertFalse("shinsou-epub-reader-style" in configured)
        assertTrue("shinsouLegacyFixedLayout()" in configured)
        assertTrue("height\\s*=\\s*" in configured)
        assertFalse("document.createElement('style')" in fixedConfiguration)
        assertFalse("font-size: 22.0px" in fixedConfiguration)
        assertTrue("shinsouTurnPage(1,false)" in instantTurn)
        assertTrue("shinsouTurnPage(-1,true)" in animatedTurn)
        assertTrue("x>=0.3&&x<=0.7" in tap)
        assertTrue("PAGED_RIGHT_TO_LEFT" in tap)
        assertTrue("node.isContentEditable" in tap)

        assertEquals(
            EpubBrowserActionResult(
                outcome = EpubBrowserActionOutcome.NEXT_BOUNDARY,
                pageIndex = 4,
                pageCount = 5,
            ),
            decodeEpubBrowserActionResult(
                """{"outcome":"NEXT_BOUNDARY","pageIndex":4,"pageCount":5}""",
            ),
        )
        assertNull(
            decodeEpubBrowserActionResult(
                """{"outcome":"MOVED","pageIndex":5,"pageCount":5}""",
            ),
        )
    }

    @Test
    fun resolverSlotStagesPendingCachesAndClosesOnlyAtCommitOrRollback() {
        val fixture = fixture()
        val first = EpubPublicationResourceResolver(fixture.request)
        assertNotNull(first.resolve(fixture.request.documentUrl))
        val slot = EpubBrowserResolverSlot(first)
        val second = EpubPublicationResourceResolver(fixture.request)

        slot.stage(second)
        assertFalse(first.isClosed)
        assertFalse(second.isClosed)
        assertSame(second, slot.read { it })
        assertTrue(slot.hasPending)

        slot.rollback()
        assertTrue(second.isClosed)
        assertFalse(first.isClosed)
        assertSame(first, slot.read { it })
        assertFalse(slot.hasPending)

        val third = EpubPublicationResourceResolver(fixture.request)
        slot.stage(third)
        slot.commit()
        assertTrue(first.isClosed)
        assertNull(first.resolve(fixture.request.documentUrl))
        assertFalse(third.isClosed)
        assertSame(third, slot.read { it })

        slot.close()
        assertTrue(third.isClosed)
        assertNull(slot.read { it })
    }

    @Test
    fun spineItemRenditionLayoutOverridesThePackageDefault() {
        val reflowableItem = fixture(
            packageLayout = "pre-paginated",
            chapterARenditionLayout = "reflowable",
        )
        val fixedItem = fixture(
            packageLayout = "reflowable",
            chapterASpineProperties = setOf("rendition:layout-pre-paginated"),
        )
        val conflictingItem = fixture(
            packageLayout = "pre-paginated",
            chapterARenditionLayout = "pre-paginated",
            chapterASpineProperties = setOf("rendition:layout-reflowable"),
        )
        val manifestFallback = fixture(
            packageLayout = "pre-paginated",
            chapterAResourceProperties = setOf("rendition:layout-reflowable"),
        )
        val explicitItemBeatsManifestFallback = fixture(
            packageLayout = "reflowable",
            chapterAResourceProperties = setOf("rendition:layout-reflowable"),
            chapterARenditionLayout = "pre-paginated",
        )

        assertTrue("shinsouFixed=false" in epubBrowserViewportScript(reflowableItem.request))
        assertTrue("shinsouFixed=true" in epubBrowserViewportScript(fixedItem.request))
        assertTrue("shinsouFixed=true" in epubBrowserViewportScript(conflictingItem.request))
        assertTrue("shinsouFixed=false" in epubBrowserViewportScript(manifestFallback.request))
        assertTrue("shinsouFixed=true" in epubBrowserViewportScript(explicitItemBeatsManifestFallback.request))
    }

    private fun fixture(
        initialProgression: Double = 0.25,
        rightToLeft: Boolean = false,
        packageLayout: String? = null,
        chapterAResourceProperties: Set<String> = emptySet(),
        chapterASpineProperties: Set<String> = emptySet(),
        chapterARenditionLayout: String? = null,
    ): Fixture {
        val packageResource = epubResource(
            id = "package",
            href = "OPS/package.opf",
            mediaType = "application/oebps-package+xml",
            blobId = "11111111-1111-4111-8111-111111111111",
        )
        val chapterAResource = epubResource(
            id = "chapter-a",
            href = "OPS/a.xhtml",
            mediaType = "application/xhtml+xml",
            blobId = "22222222-2222-4222-8222-222222222222",
            properties = chapterAResourceProperties,
        )
        val chapterBResource = epubResource(
            id = "chapter-b",
            href = "OPS/b.xhtml",
            mediaType = "application/xhtml+xml",
            blobId = "33333333-3333-4333-8333-333333333333",
        )
        val progression = if (rightToLeft) {
            ImageProgression.RIGHT_TO_LEFT
        } else {
            ImageProgression.LEFT_TO_RIGHT
        }
        val representation = ContentRepresentation.EpubSpine(
            representationId = "44444444-4444-4444-8444-444444444444",
            packageGraph = EpubPackage(
                archive = blob(
                    "55555555-5555-4555-8555-555555555555",
                    "application/epub+zip",
                ),
                packageDocumentId = packageResource.id,
                resources = listOf(packageResource, chapterAResource, chapterBResource),
                renditions = packageLayout?.let { listOf(EpubRendition(layout = it)) }.orEmpty(),
            ),
            documents = listOf(
                EpubSpineDocument(
                    id = "spine-a",
                    href = chapterAResource.href,
                    resourceId = chapterAResource.id,
                    pageProgression = progression,
                    properties = chapterASpineProperties,
                    rendition = chapterARenditionLayout?.let { EpubRendition(layout = it) },
                ),
                EpubSpineDocument("spine-b", chapterBResource.href, chapterBResource.id, pageProgression = progression),
            ),
        )
        val publication = PublicationKey("66666666-6666-4666-8666-666666666666")
        val navigation = EpubSpineNavigation(
            scope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = publication,
                acquisitionId = "77777777-7777-4777-8777-777777777777",
                unitId = UnitKey(publication, "88888888-8888-4888-8888-888888888888"),
                contentRevision = 3,
            ),
            representation = representation,
        )
        val chapterA = EpubRenderResource(
            "chapter-a",
            "OPS/a.xhtml",
            "application/xhtml+xml",
            XHTML_A.encodeToByteArray(),
        )
        val chapterB = EpubRenderResource(
            "chapter-b",
            "OPS/b.xhtml",
            "application/xhtml+xml",
            XHTML_B.encodeToByteArray(),
        )
        val request = EpubRenderRequest(
            navigation = navigation,
            documentIndex = 0,
            initialLocator = navigation.locator(
                documentIndex = 0,
                cfi = navigation.startCfi(0),
                progression = initialProgression,
            ),
            publisherResources = listOf(chapterA, chapterB),
        )
        return Fixture(request, chapterA, chapterB)
    }

    private fun epubResource(
        id: String,
        href: String,
        mediaType: String,
        blobId: String,
        properties: Set<String> = emptySet(),
    ): EpubResource = EpubResource(
        id = id,
        href = href,
        resource = ResourceRef(id, blob(blobId, mediaType)),
        properties = properties,
    )

    private fun blob(blobId: String, mediaType: String): BlobRef = BlobRef(
        blobId = blobId,
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = EMPTY_SHA256,
        byteSize = 0,
        mediaType = mediaType,
    )

    private data class Fixture(
        val request: EpubRenderRequest,
        val chapterA: EpubRenderResource,
        val chapterB: EpubRenderResource,
    )

    private companion object {
        const val EMPTY_SHA256: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val XHTML_A: String =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head><body><p>A</p></body></html>"
        const val XHTML_B: String =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head><body><p id=\"target\">B</p></body></html>"
    }
}
