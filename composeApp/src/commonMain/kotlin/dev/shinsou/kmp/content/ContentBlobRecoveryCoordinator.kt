package dev.shinsou.kmp.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Clock

/** Describes the bounded work completed by one low-priority orphan-recovery slice. */
public enum class ContentBlobRecoverySliceOutcome {
    /** No aged candidate was eligible; newly observed orphans remain discover-only. */
    DISCOVERY_ONLY,

    /** At least one previously discovered, aged candidate was rechecked for deletion. */
    SWEEP_ATTEMPTED,
}

/** Bounded diagnostics returned to the lifecycle/background scheduler. */
public data class ContentBlobRecoverySliceResult(
    val outcome: ContentBlobRecoverySliceOutcome,
    val boundary: RecoveryBoundary,
    /** Every unreferenced blob observed at or before the explicit generation cutoff. */
    val discoveredOrphanCount: Int,
    /** Orphans first observed by this pass; these are never candidates in the same pass. */
    val newlyDiscoveredOrphanCount: Int,
    /** Previously discovered orphans old enough to be candidates before this slice's cap. */
    val eligibleCandidateCount: Int,
    /** Candidates submitted to the store's final safety recheck in this slice. */
    val attemptedCandidateCount: Int,
    /** Exact incarnations actually removed after the final safety recheck. */
    val removedCount: Int,
    /** Inline SQLite body bytes copied or verified by the bounded file migration slice. */
    val migratedInlineBytes: Int = 0,
    /** Inline rows switched to an already verified app-private immutable file. */
    val completedInlineMigrations: Int = 0,
    /** Direct object/staging entries inspected by crash-file recovery. */
    val scannedStorageFiles: Int = 0,
    /** Previously unknown crash files removed after a separate aged observation. */
    val removedUnknownStorageFiles: Int = 0,
) {
    init {
        require(discoveredOrphanCount >= 0) { "Discovered orphan count must be non-negative" }
        require(newlyDiscoveredOrphanCount in 0..discoveredOrphanCount) {
            "Newly discovered orphan count is inconsistent"
        }
        require(eligibleCandidateCount in 0..discoveredOrphanCount) {
            "Eligible orphan count is inconsistent"
        }
        require(attemptedCandidateCount in 0..eligibleCandidateCount) {
            "Attempted orphan count is inconsistent"
        }
        require(removedCount in 0..attemptedCandidateCount) {
            "Removed orphan count is inconsistent"
        }
        require(migratedInlineBytes >= 0) { "Migrated inline byte count must be non-negative" }
        require(completedInlineMigrations >= 0) {
            "Completed inline migration count must be non-negative"
        }
        require(scannedStorageFiles >= 0) { "Scanned storage-file count must be non-negative" }
        require(removedUnknownStorageFiles in 0..scannedStorageFiles) {
            "Removed storage-file count is inconsistent"
        }
        require(
            (outcome == ContentBlobRecoverySliceOutcome.DISCOVERY_ONLY) ==
                (attemptedCandidateCount == 0),
        ) { "Recovery outcome does not match the attempted candidate count" }
    }

    /** Includes capped candidates and candidates re-protected by the sweep-time safety check. */
    public val remainingEligibleCandidateCount: Int
        get() = eligibleCandidateCount - removedCount
}

/**
 * Production policy for M1 local immutable-blob orphan recovery.
 *
 * The caller must schedule [runLowPrioritySlice] only from an idle/background lifecycle edge and
 * must pass a generation captured from the intended committed safety boundary. There is no
 * implicit `currentGeneration` fallback. Work moves to [backgroundDispatcher], yields before both
 * phases, and bounds durable deletes so it cannot join the startup or reader critical path.
 *
 * Discovery and deletion are deliberately separate durable observations. [ContentBlobStore]
 * persists the first discovery timestamp and excludes a newly discovered orphan from that same
 * plan. A later invocation (including one after process restart) may sweep only after
 * [minimumAgeMillis], and [ContentBlobStore.sweepRecovery] then rechecks the exact reference,
 * incarnation, generation, attachment, receipt, and active-reader state.
 */
public class ContentBlobRecoveryCoordinator(
    private val blobStore: ContentBlobStore,
    public val minimumAgeMillis: Long = DEFAULT_MINIMUM_AGE_MILLIS,
    public val maximumSweepCandidatesPerSlice: Int = DEFAULT_MAXIMUM_SWEEP_CANDIDATES_PER_SLICE,
    public val maximumStorageMaintenanceBytesPerSlice: Int =
        DEFAULT_MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE,
    public val maximumStorageFilesPerSlice: Int = DEFAULT_MAXIMUM_STORAGE_FILES_PER_SLICE,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val sliceMutex = Mutex()

    init {
        require(minimumAgeMillis >= MINIMUM_PRODUCTION_AGE_MILLIS) {
            "Production orphan recovery must wait at least $MINIMUM_PRODUCTION_AGE_MILLIS ms"
        }
        require(maximumSweepCandidatesPerSlice in 1..MAXIMUM_SWEEP_CANDIDATES_PER_SLICE) {
            "Recovery sweep size must be between 1 and $MAXIMUM_SWEEP_CANDIDATES_PER_SLICE"
        }
        require(
            maximumStorageMaintenanceBytesPerSlice in
                1..MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE,
        ) {
            "Blob migration byte budget must be between 1 and " +
                "$MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE"
        }
        require(maximumStorageFilesPerSlice in 1..MAXIMUM_STORAGE_FILES_PER_SLICE) {
            "Blob crash-file scan size must be between 1 and $MAXIMUM_STORAGE_FILES_PER_SLICE"
        }
    }

    /**
     * Runs one discover/optional-sweep slice away from the caller's foreground dispatcher.
     *
     * [safetyCutoffGeneration] is intentionally mandatory. It should be captured before the
     * lifecycle scheduler enqueues this slice, so blobs published after that boundary cannot be
     * selected even if their process-local receipt is later lost.
     */
    public suspend fun runLowPrioritySlice(
        safetyCutoffGeneration: Long,
    ): ContentBlobRecoverySliceResult = withContext(backgroundDispatcher) {
        require(safetyCutoffGeneration >= 0) { "Recovery generation cutoff must be non-negative" }
        require(safetyCutoffGeneration <= blobStore.currentGeneration) {
            "Recovery generation cutoff cannot be ahead of the blob store"
        }

        sliceMutex.withLock {
            currentCoroutineContext().ensureActive()
            yield()

            val boundary = RecoveryBoundary(
                safetyCutoffGeneration = safetyCutoffGeneration,
                nowEpochMillis = nowEpochMillis(),
                minimumAgeMillis = minimumAgeMillis,
            )
            val storageMaintenance = (blobStore as? SqlDriverContentBlobStore)
                ?.runStorageMaintenanceSlice(
                    ContentBlobStorageMaintenanceRequest(
                        nowEpochMillis = boundary.nowEpochMillis,
                        minimumAgeMillis = boundary.minimumAgeMillis,
                        maximumBytes = maximumStorageMaintenanceBytesPerSlice,
                        maximumFiles = maximumStorageFilesPerSlice,
                    ),
                ) ?: ContentBlobStorageMaintenanceResult()
            currentCoroutineContext().ensureActive()
            yield()
            val plan = blobStore.planRecovery(boundary)
            currentCoroutineContext().ensureActive()

            val candidates = plan.candidates.take(maximumSweepCandidatesPerSlice)
            if (candidates.isEmpty()) {
                return@withLock resultFor(
                    outcome = ContentBlobRecoverySliceOutcome.DISCOVERY_ONLY,
                    plan = plan,
                    attemptedCandidateCount = 0,
                    removedCount = 0,
                    storageMaintenance = storageMaintenance,
                )
            }

            // Cancellation after discovery is safe: it leaves only durable discovery metadata.
            // Yield again before writes so foreground lifecycle work can cancel this pass.
            yield()
            currentCoroutineContext().ensureActive()
            val removed = blobStore.sweepRecovery(plan.copy(candidates = candidates))
            resultFor(
                outcome = ContentBlobRecoverySliceOutcome.SWEEP_ATTEMPTED,
                plan = plan,
                attemptedCandidateCount = candidates.size,
                removedCount = removed,
                storageMaintenance = storageMaintenance,
            )
        }
    }

    private fun resultFor(
        outcome: ContentBlobRecoverySliceOutcome,
        plan: BlobRecoveryPlan,
        attemptedCandidateCount: Int,
        removedCount: Int,
        storageMaintenance: ContentBlobStorageMaintenanceResult,
    ): ContentBlobRecoverySliceResult = ContentBlobRecoverySliceResult(
        outcome = outcome,
        boundary = plan.boundary,
        discoveredOrphanCount = plan.discoveredOrphans.size,
        newlyDiscoveredOrphanCount = plan.protectedBlobs.values.count {
            it == BlobRecoveryProtection.DISCOVERED_ORPHAN
        },
        eligibleCandidateCount = plan.candidates.size,
        attemptedCandidateCount = attemptedCandidateCount,
        removedCount = removedCount,
        migratedInlineBytes = storageMaintenance.migratedInlineBytes,
        completedInlineMigrations = storageMaintenance.completedInlineMigrations,
        scannedStorageFiles = storageMaintenance.scannedFiles,
        removedUnknownStorageFiles = storageMaintenance.removedUnknownFiles,
    )

    public companion object {
        /** Hard floor for production scheduling; raw store contracts remain configurable in tests. */
        public const val MINIMUM_PRODUCTION_AGE_MILLIS: Long = 60L * 60L * 1_000L

        /** A full day leaves recovery/materialization workers time to reclaim a receipt-less body. */
        public const val DEFAULT_MINIMUM_AGE_MILLIS: Long = 24L * 60L * 60L * 1_000L

        public const val DEFAULT_MAXIMUM_SWEEP_CANDIDATES_PER_SLICE: Int = 8
        public const val MAXIMUM_SWEEP_CANDIDATES_PER_SLICE: Int = 64

        /** One slice never copies or verifies more than this amount of an old inline body. */
        public const val DEFAULT_MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE: Int = 256 * 1024
        public const val MAXIMUM_STORAGE_MAINTENANCE_BYTES_PER_SLICE: Int = 4 * 1024 * 1024

        /** Directory work is capped independently from immutable-body recovery. */
        public const val DEFAULT_MAXIMUM_STORAGE_FILES_PER_SLICE: Int = 32
        public const val MAXIMUM_STORAGE_FILES_PER_SLICE: Int = 256
    }
}
