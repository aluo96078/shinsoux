package dev.shinsou.kmp.ui.reader

import androidx.compose.ui.input.key.Key
import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderVolumeKeyPolicyTest {
    @Test
    fun volumeButtonsMapToReaderPageDirection() {
        assertEquals(
            ReaderTapAction.PREVIOUS_PAGE,
            readerVolumeKeyAction(ReaderVolumeKeyEvent.VOLUME_UP, readerOpen = true, volumeKeysEnabled = true),
        )
        assertEquals(
            ReaderTapAction.NEXT_PAGE,
            readerVolumeKeyAction(ReaderVolumeKeyEvent.VOLUME_DOWN, readerOpen = true, volumeKeysEnabled = true),
        )
    }

    @Test
    fun volumeButtonsAreIgnoredOutsideAnEnabledReader() {
        ReaderVolumeKeyEvent.entries.forEach { event ->
            assertNull(readerVolumeKeyAction(event, readerOpen = false, volumeKeysEnabled = true))
            assertNull(readerVolumeKeyAction(event, readerOpen = true, volumeKeysEnabled = false))
            assertNull(readerVolumeKeyAction(event, readerOpen = false, volumeKeysEnabled = false))
        }
    }

    @Test
    fun platformCapabilityControlsSettingVisibilityAndActivation() {
        assertTrue(shouldShowReaderVolumeKeySetting(platformSupported = true))
        assertFalse(shouldShowReaderVolumeKeySetting(platformSupported = false))
        assertTrue(effectiveReaderVolumeKeysEnabled(configured = true, platformSupported = true))
        assertFalse(effectiveReaderVolumeKeysEnabled(configured = false, platformSupported = true))
        assertFalse(effectiveReaderVolumeKeysEnabled(configured = true, platformSupported = false))
    }

    @Test
    fun monitoringRequiresAnOpenReaderAndAnEnabledSupportedPlatform() {
        assertTrue(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = true,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = false,
                configured = true,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = false,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = true,
                platformSupported = false,
            ),
        )
    }

    @Test
    fun routerDispatchesEachEventOnlyToTheNewestMountedReader() {
        val router = ReaderVolumeKeyRouter()
        val firstEvents = mutableListOf<ReaderVolumeKeyEvent>()
        val secondEvents = mutableListOf<ReaderVolumeKeyEvent>()
        val first = router.register(handler = firstEvents::add)
        val second = router.register(handler = secondEvents::add)

        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertTrue(firstEvents.isEmpty())
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_DOWN), secondEvents)

        first.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertTrue(firstEvents.isEmpty())
        assertEquals(
            listOf(ReaderVolumeKeyEvent.VOLUME_DOWN, ReaderVolumeKeyEvent.VOLUME_UP),
            secondEvents,
        )

        second.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
    }

    @Test
    fun aCoveredReaderTakesNoPressesUntilItIsInFrontAgain() {
        val router = ReaderVolumeKeyRouter()
        val events = mutableListOf<ReaderVolumeKeyEvent>()
        router.register(coverable = true) { events.add(it) }

        router.covered = true
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertTrue(events.isEmpty())

        router.covered = false
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_UP), events)
    }

    @Test
    fun aChapterReaderOpenedOverACoveredExtensionReaderKeepsPaging() {
        val router = ReaderVolumeKeyRouter()
        val extension = mutableListOf<ReaderVolumeKeyEvent>()
        val chapter = mutableListOf<ReaderVolumeKeyEvent>()
        router.register(coverable = true) { extension.add(it) }
        router.covered = true
        router.register { chapter.add(it) }

        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_DOWN), chapter)
        assertTrue(extension.isEmpty())
    }

    @Test
    fun theExtensionReaderOwnsPressesAgainWhenTheChapterReaderOverItCloses() {
        val router = ReaderVolumeKeyRouter()
        val extension = mutableListOf<ReaderVolumeKeyEvent>()
        val chapter = mutableListOf<ReaderVolumeKeyEvent>()
        val beneath = router.register(coverable = true) { extension.add(it) }
        router.covered = true
        // Two chapter readers in turn (next chapter): the first is outgoing once the second mounts.
        val first = router.register { chapter.add(it) }
        val second = router.register { chapter.add(it) }
        first.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_DOWN), chapter)

        second.unregister()
        // Still covered until the navigator says the extension reader is in front.
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        router.covered = false
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_UP), extension)

        beneath.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
    }

    @Test
    fun anExtensionReaderDisposedBeneathAChapterReaderDoesNotChangeTheOwner() {
        val router = ReaderVolumeKeyRouter()
        var extensionCalls = 0
        var chapterCalls = 0
        val beneath = router.register(coverable = true) { extensionCalls++; true }
        val chapter = router.register { chapterCalls++; true }

        beneath.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        chapter.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(0, extensionCalls)
        assertEquals(1, chapterCalls)
    }

    @Test
    fun anExtensionReaderRegisteringLateGoesBeneathTheChapterReaderInFront() {
        val router = ReaderVolumeKeyRouter()
        val chapter = mutableListOf<ReaderVolumeKeyEvent>()
        val extension = mutableListOf<ReaderVolumeKeyEvent>()
        val front = router.register { chapter.add(it) }
        // The extension reader beneath finishes loading after the chapter reader opened over it.
        val beneath = router.register(coverable = true) { extension.add(it) }

        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_DOWN), chapter)
        assertTrue(extension.isEmpty())
        // Covering the extension reader does not silence the chapter reader in front of it.
        router.covered = true
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        router.covered = false

        front.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertEquals(listOf(ReaderVolumeKeyEvent.VOLUME_UP), extension)
        assertEquals(2, chapter.size)
        beneath.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
    }

    @Test
    fun aNewerExtensionReaderOwnsPressesOverAnOlderOne() {
        val router = ReaderVolumeKeyRouter()
        var older = 0
        var newer = 0
        router.register(coverable = true) { older++; true }
        val latest = router.register(coverable = true) { newer++; true }

        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(0, older)
        assertEquals(1, newer)
        latest.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(1, older)
    }

    @Test
    fun aCoveredReaderSwallowsOnlyItsPagingKeys() {
        listOf(Key.PageDown, Key.Spacebar, Key.DirectionLeft, Key.DirectionDown, Key.PageUp).forEach {
            assertTrue(readerPagingKeyIsSwallowedWhileCovered(it), "$it")
        }
        // Volume goes back to the system; Back and Escape go to the page in front.
        listOf(Key.VolumeDown, Key.VolumeUp, Key.Back, Key.Escape, Key.A).forEach {
            assertFalse(readerPagingKeyIsSwallowedWhileCovered(it), "$it")
        }
    }

    @Test
    fun oldRegistrationCannotClearItsReplacement() {
        val router = ReaderVolumeKeyRouter()
        var oldCalls = 0
        var currentCalls = 0
        val old = router.register { oldCalls++; true }
        val current = router.register { currentCalls++; true }

        old.unregister()
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(0, oldCalls)
        assertEquals(1, currentCalls)

        current.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        old.unregister()
    }

    @Test
    fun unregisteringReplacementDoesNotReactivateItsPredecessor() {
        val router = ReaderVolumeKeyRouter()
        var firstCalls = 0
        var replacementCalls = 0
        val first = router.register { firstCalls++; true }
        val replacement = router.register { replacementCalls++; true }

        replacement.unregister()
        assertFalse(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(0, firstCalls)
        assertEquals(0, replacementCalls)
        first.unregister()
    }

    @Test
    fun replacementChapterSlotReceivesEventsAfterOutgoingChapterDisposes() {
        val router = ReaderVolumeKeyRouter()
        val outgoingSlot = ReaderVolumeKeyHandlerSlot()
        val incomingSlot = ReaderVolumeKeyHandlerSlot()
        var outgoingCalls = 0
        var incomingCalls = 0
        outgoingSlot.update { outgoingCalls++; true }
        val outgoing = router.register(handler = outgoingSlot::dispatch)

        incomingSlot.update { incomingCalls++; true }
        val incoming = router.register(handler = incomingSlot::dispatch)
        outgoing.unregister()

        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(0, outgoingCalls)
        assertEquals(1, incomingCalls)

        incomingSlot.update { incomingCalls += 10; true }
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertEquals(11, incomingCalls)
        incoming.unregister()
    }

    @Test
    fun provisionalProsePaginationCannotCrossAChapterBoundary() {
        assertFalse(
            readerMayCrossChapterBoundary(
                proseReader = true,
                pageCountMeasured = false,
                interactionBlocked = false,
            ),
        )
        assertTrue(
            readerMayCrossChapterBoundary(
                proseReader = true,
                pageCountMeasured = true,
                interactionBlocked = false,
            ),
        )
        assertTrue(
            readerMayCrossChapterBoundary(
                proseReader = false,
                pageCountMeasured = false,
                interactionBlocked = false,
            ),
        )
        assertFalse(
            readerMayCrossChapterBoundary(
                proseReader = false,
                pageCountMeasured = true,
                interactionBlocked = true,
            ),
        )
    }

    @Test
    fun onePhysicalVolumePressDispatchesOnlyItsFirstDownEvent() {
        val tracker = ReaderVolumeKeyPressTracker()
        val event = ReaderVolumeKeyEvent.VOLUME_DOWN

        assertTrue(tracker.shouldDispatchDown(event, repeatCount = 0))
        assertFalse(tracker.shouldDispatchDown(event, repeatCount = 1))
        assertFalse(
            tracker.shouldDispatchDown(event, repeatCount = 0),
            "OEM repeatCount resets must not duplicate a held press",
        )

        tracker.release(event)
        assertTrue(tracker.shouldDispatchDown(event, repeatCount = 0))
    }

    @Test
    fun volumePressTrackerKeepsBothPhysicalButtonsIndependentAndCanReset() {
        val tracker = ReaderVolumeKeyPressTracker()

        assertTrue(tracker.shouldDispatchDown(ReaderVolumeKeyEvent.VOLUME_UP, repeatCount = 0))
        assertTrue(tracker.shouldDispatchDown(ReaderVolumeKeyEvent.VOLUME_DOWN, repeatCount = 0))
        tracker.clear()
        assertTrue(tracker.shouldDispatchDown(ReaderVolumeKeyEvent.VOLUME_UP, repeatCount = 0))
        assertTrue(tracker.shouldDispatchDown(ReaderVolumeKeyEvent.VOLUME_DOWN, repeatCount = 0))
    }

    @Test
    fun extensionContinueReadingPrefersTheExactPersistedRemoteUnit() {
        assertEquals(
            "chapter-215",
            extensionContinueUnitId(
                unitIds = listOf("chapter-1", "chapter-100", "chapter-215"),
                completedUnitIds = setOf("chapter-1"),
                resumeUnitId = "chapter-215",
            ),
        )
    }

    @Test
    fun extensionContinueReadingFallsBackOnlyWhenPersistedUnitIsAbsent() {
        assertEquals(
            "chapter-2",
            extensionContinueUnitId(
                unitIds = listOf("chapter-1", "chapter-2", "chapter-3"),
                completedUnitIds = setOf("chapter-1"),
                resumeUnitId = "chapter-215",
            ),
        )
        assertEquals(
            "chapter-3",
            extensionContinueUnitId(
                unitIds = listOf("chapter-1", "chapter-2", "chapter-3"),
                completedUnitIds = setOf("chapter-1", "chapter-2", "chapter-3"),
                resumeUnitId = null,
            ),
        )
    }

    @Test
    fun progressCallbacksRequireTheExactActiveSessionAndNoTransition() {
        val active = Any()
        assertTrue(readerProgressSessionIsActive(active, active, transitionInFlight = false))
        assertFalse(readerProgressSessionIsActive(active, Any(), transitionInFlight = false))
        assertFalse(readerProgressSessionIsActive(active, active, transitionInFlight = true))
        assertFalse(readerProgressSessionIsActive(null, active, transitionInFlight = false))
    }

    @Test
    fun openReaderConsumesVolumeKeysEvenWhenThePageCannotMove() {
        assertTrue(
            readerVolumeKeyShouldBeConsumed(
                readerOpen = true,
                volumeKeysEnabled = true,
                monitoringEnabled = true,
            ),
        )
        assertFalse(
            readerVolumeKeyShouldBeConsumed(
                readerOpen = false,
                volumeKeysEnabled = true,
                monitoringEnabled = true,
            ),
        )
        assertFalse(
            readerVolumeKeyShouldBeConsumed(
                readerOpen = true,
                volumeKeysEnabled = false,
                monitoringEnabled = true,
            ),
        )
        assertFalse(
            readerVolumeKeyShouldBeConsumed(
                readerOpen = true,
                volumeKeysEnabled = true,
                monitoringEnabled = false,
            ),
        )
    }

    @Test
    fun routerAcceptsVolumeEventsFromTheAppSurfaceWhenAReaderIsMounted() {
        val router = ReaderVolumeKeyRouter()
        val events = mutableListOf<ReaderVolumeKeyEvent>()
        router.register { event ->
            events += event
            true
        }
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertTrue(router.dispatch(ReaderVolumeKeyEvent.VOLUME_UP))
        assertEquals(
            listOf(ReaderVolumeKeyEvent.VOLUME_DOWN, ReaderVolumeKeyEvent.VOLUME_UP),
            events,
        )
    }
}
