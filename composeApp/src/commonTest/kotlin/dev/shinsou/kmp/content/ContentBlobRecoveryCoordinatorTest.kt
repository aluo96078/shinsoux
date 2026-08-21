package dev.shinsou.kmp.content

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentBlobRecoveryCoordinatorTest {
    @Test
    fun firstPassAndTooYoungPassesNeverSweepBeforeMinimumAge() = runTest {
        var now = 100L
        val store = InMemoryContentBlobStore(clock = { now })
        val published = store.put("receipt lost at crash".encodeToByteArray(), "text/plain")
        store.simulateProcessCrashAndRecover()
        val minimumAge = ContentBlobRecoveryCoordinator.MINIMUM_PRODUCTION_AGE_MILLIS
        val coordinator = ContentBlobRecoveryCoordinator(
            blobStore = store,
            minimumAgeMillis = minimumAge,
            nowEpochMillis = { now },
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )

        val first = coordinator.runLowPrioritySlice(published.generation)
        assertEquals(ContentBlobRecoverySliceOutcome.DISCOVERY_ONLY, first.outcome)
        assertEquals(minimumAge, first.boundary.minimumAgeMillis)
        assertEquals(1, first.discoveredOrphanCount)
        assertEquals(0, first.eligibleCandidateCount)
        assertEquals(0, first.removedCount)
        assertTrue(store.containsForRecoveryTest(published.reference))

        now += minimumAge - 1L
        val stillTooYoung = coordinator.runLowPrioritySlice(published.generation)
        assertEquals(ContentBlobRecoverySliceOutcome.DISCOVERY_ONLY, stillTooYoung.outcome)
        assertEquals(0, stillTooYoung.attemptedCandidateCount)
        assertTrue(store.containsForRecoveryTest(published.reference))

        now += 1L
        val aged = coordinator.runLowPrioritySlice(published.generation)
        assertEquals(ContentBlobRecoverySliceOutcome.SWEEP_ATTEMPTED, aged.outcome)
        assertEquals(1, aged.eligibleCandidateCount)
        assertEquals(1, aged.attemptedCandidateCount)
        assertEquals(1, aged.removedCount)
        assertFalse(store.containsForRecoveryTest(published.reference))
    }

    @Test
    fun explicitCutoffAndDeleteCapKeepEachLowPrioritySliceBounded() = runTest {
        var now = 10L
        val store = InMemoryContentBlobStore(clock = { now })
        val receipts = listOf("first", "second", "after-cutoff").map { body ->
            store.put(body.encodeToByteArray(), "text/plain")
        }
        now = 20L
        store.simulateProcessCrashAndRecover()
        now += ContentBlobRecoveryCoordinator.MINIMUM_PRODUCTION_AGE_MILLIS
        val coordinator = ContentBlobRecoveryCoordinator(
            blobStore = store,
            minimumAgeMillis = ContentBlobRecoveryCoordinator.MINIMUM_PRODUCTION_AGE_MILLIS,
            maximumSweepCandidatesPerSlice = 1,
            nowEpochMillis = { now },
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )

        val firstSweep = coordinator.runLowPrioritySlice(receipts[1].generation)
        assertEquals(receipts[1].generation, firstSweep.boundary.safetyCutoffGeneration)
        assertEquals(2, firstSweep.eligibleCandidateCount)
        assertEquals(1, firstSweep.attemptedCandidateCount)
        assertEquals(1, firstSweep.removedCount)
        assertEquals(1, firstSweep.remainingEligibleCandidateCount)
        assertEquals(2, store.count)
        assertTrue(store.containsForRecoveryTest(receipts[2].reference))

        val secondSweep = coordinator.runLowPrioritySlice(receipts[1].generation)
        assertEquals(1, secondSweep.eligibleCandidateCount)
        assertEquals(1, secondSweep.removedCount)
        assertEquals(1, store.count)
        assertTrue(store.containsForRecoveryTest(receipts[2].reference))

        val expandedCutoff = coordinator.runLowPrioritySlice(receipts[2].generation)
        assertEquals(1, expandedCutoff.removedCount)
        assertEquals(0, store.count)
    }

    @Test
    fun productionPolicyRejectsUnsafeAgeAndUnboundedSweepConfiguration() {
        val store = InMemoryContentBlobStore()

        assertFailsWith<IllegalArgumentException> {
            ContentBlobRecoveryCoordinator(
                blobStore = store,
                minimumAgeMillis = ContentBlobRecoveryCoordinator.MINIMUM_PRODUCTION_AGE_MILLIS - 1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContentBlobRecoveryCoordinator(
                blobStore = store,
                maximumSweepCandidatesPerSlice =
                    ContentBlobRecoveryCoordinator.MAXIMUM_SWEEP_CANDIDATES_PER_SLICE + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContentBlobRecoveryCoordinator(
                blobStore = store,
                maximumStorageMaintenanceBytesPerSlice =
                    ContentBlobRecoveryCoordinator.MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContentBlobRecoveryCoordinator(
                blobStore = store,
                maximumStorageFilesPerSlice =
                    ContentBlobRecoveryCoordinator.MAXIMUM_STORAGE_FILES_PER_SLICE + 1,
            )
        }
    }
}

/** Discovered orphans intentionally reject ordinary reads; tests inspect lifecycle presence. */
private fun InMemoryContentBlobStore.containsForRecoveryTest(reference: BlobRef): Boolean =
    lifecycleState(reference) != null
