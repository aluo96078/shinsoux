package dev.shinsou.kmp.content

import dev.shinsou.kmp.content.blob.ContentBlobRecoveryCoordinator
import dev.shinsou.kmp.content.transaction.ContentOutboxDiscarder
import dev.shinsou.kmp.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The content store's background maintenance (docs/architecture/0007 B2): discarding the legacy
 * outbox, recovering orphaned blobs, and the derived-data pass (rights invalidations, expired and
 * unauthorized derived rows, the search index).
 *
 * The composition root used to keep these jobs, their signal channels and the recovery
 * coordinator itself; they belong to the store they maintain. The app still decides *when* each
 * runs (startup, a lifecycle edge, a commit) and owns [scope]: every job here is a child of it.
 * The derived-data pass is supplied as [derivedDataPass] because the services it runs (search,
 * annotation and rights cleanup) are assembled above this module.
 *
 * Not thread-safe, like the composition that drives it: calls come from the app's lifecycle
 * callbacks, one at a time.
 */
public class ContentBackgroundWork(
    private val foundation: ContentFoundationRuntime,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val derivedDataPass: suspend () -> Unit,
) {
    /** Conflated derived-index invalidations; import/restore never waits for plaintext indexing. */
    private val derivedDataSignals = Channel<Unit>(Channel.CONFLATED)
    private var derivedDataJob: Job? = null

    /** Conflated requests to drop legacy outbox drafts and the blob-sync jobs nothing drains. */
    private val outboxDiscardSignals = Channel<Unit>(Channel.CONFLATED)
    private var outboxDiscardJob: Job? = null
    private val outboxDiscarder = ContentOutboxDiscarder(foundation.transactions)

    /** M1 orphan discovery/GC runs only after the app enters a background window. */
    private var blobRecoveryJob: Job? = null
    private val blobRecoveryCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ContentBlobRecoveryCoordinator(
            blobStore = foundation.blobStore,
            minimumAgeMillis = BLOB_ORPHAN_MINIMUM_AGE_MILLIS,
        )
    }

    /** Starts the single consumer that discards the content outbox; idempotent. */
    public fun startOutboxDiscarder() {
        if (outboxDiscardJob?.isActive == true) return
        outboxDiscardJob = scope.launch(dispatcher) {
            for (ignored in outboxDiscardSignals) {
                try {
                    yield()
                    outboxDiscarder.discardPending()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (logged: Throwable) {
                    Log.warn("Startup", logged) { "Could not discard the pending content drafts" }
                    // The drafts stay durable and harmless; the next commit or lifecycle edge
                    // retries the discard.
                }
            }
        }
    }

    /** Requests a discard pass; conflated, and buffered until the startup pass starts the consumer. */
    public fun scheduleOutboxDiscard() {
        outboxDiscardSignals.trySend(Unit)
    }

    /** Runs one low-priority orphan recovery slice, unless one is running. */
    public fun scheduleBlobRecovery() {
        val recovery = blobRecoveryCoordinator
        if (blobRecoveryJob?.isActive == true) return
        // Capture the exact generation before enqueueing. New publications cannot enter this
        // slice even if the coroutine begins after their blob receipts have been released.
        val safetyCutoffGeneration = foundation.blobStore.currentGeneration
        blobRecoveryJob = scope.launch {
            try {
                yield()
                recovery.runLowPrioritySlice(safetyCutoffGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Startup", logged) { "The content blob recovery pass failed" }
                // Recovery is conservative and retryable. A failure retains bytes for a later
                // background pass and never blocks foreground content access.
            }
        }
    }

    /** Starts one background-only consumer; conflation gives bursts of imports one eventual rescan. */
    @Suppress("SuspendFunSwallowedCancellation") // Cancellation is rethrown after keeping one pending reconciliation signal.
    public fun startDerivedDataReconciler() {
        if (derivedDataJob?.isActive == true) return
        derivedDataJob = scope.launch {
            for (ignored in derivedDataSignals) {
                try {
                    yield()
                    derivedDataPass()
                } catch (cancelled: CancellationException) {
                    // The actor may already have consumed the conflated invalidation. Preserve one
                    // retry for the next background edge before releasing foreground resources.
                    derivedDataSignals.trySend(Unit)
                    throw cancelled
                } catch (logged: Throwable) {
                    Log.warn("Startup", logged) { "The derived-data maintenance pass failed" }
                    // Derived rows remain rebuildable. A later background/import signal retries
                    // without making startup, restore, or reader interaction fail.
                }
            }
        }
    }

    /** Requests a derived-data pass; conflated, and run once the reconciler is started. */
    public fun scheduleDerivedDataReconciliation() {
        derivedDataSignals.trySend(Unit)
    }

    /**
     * Cancels the background-only slices (blob recovery, the derived-data pass) and waits until
     * they have released their resources: no full-library work may compete with an interactive frame.
     */
    public suspend fun stopBackgroundSlices() {
        val previousRecovery = blobRecoveryJob
        blobRecoveryJob = null
        val previousDerivedData = derivedDataJob
        derivedDataJob = null
        previousRecovery?.cancel()
        previousDerivedData?.cancel()
        previousRecovery?.join()
        previousDerivedData?.join()
    }

    /** Shutdown: cancels every job and closes the signal channels. */
    public fun cancel() {
        outboxDiscardJob?.cancel()
        outboxDiscardJob = null
        outboxDiscardSignals.close()
        blobRecoveryJob?.cancel()
        blobRecoveryJob = null
        derivedDataJob?.cancel()
        derivedDataJob = null
        derivedDataSignals.close()
    }

    private companion object {
        const val BLOB_ORPHAN_MINIMUM_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}
