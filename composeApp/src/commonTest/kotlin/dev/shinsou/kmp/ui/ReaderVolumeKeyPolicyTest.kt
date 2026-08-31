package dev.shinsou.kmp.ui

import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.ui.screens.extensionContinueUnitId
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
        val first = router.register(firstEvents::add)
        val second = router.register(secondEvents::add)

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
        val outgoing = router.register(outgoingSlot::dispatch)

        incomingSlot.update { incomingCalls++; true }
        val incoming = router.register(incomingSlot::dispatch)
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
}
