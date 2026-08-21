package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

public data class ContentBlobSyncDrainResultV2(
    val inspected: Int,
    val uploaded: Int,
    val replayed: Int,
    val acknowledged: Int,
    val remaining: Int,
    val rightsDenied: Int = 0,
    val failed: Int = 0,
)

/**
 * Low-priority body worker. It never participates in app startup, foreground navigation, reader
 * progress, or the metadata catch-up critical path.
 *
 * Ordering is strict: exact Worker manifest commit -> durable transfer receipt -> durable
 * BlobReferenceCommit draft -> content-job acknowledgement -> transfer-journal cleanup.
 */
public class ContentBlobSyncCoordinatorV2(
    private val contentStore: SharedContentTransactionStore<SyncDraft>,
    private val localStore: LocalSyncStore,
    private val uploader: EncryptedBlobUploaderV2,
    private val journal: BlobTransferJournalV2,
    private val nowEpochMillis: () -> Long,
    private val accessRequest: (ContentBlobSyncJobMutation) -> ContentAccessRequest = { job ->
        ContentAccessRequest(job.grantReference, job.accessScope)
    },
) {
    private val mutex = Mutex()

    public suspend fun drain(
        session: SyncSession,
        capability: WorkspaceCapability,
        maxJobs: Int = DEFAULT_MAX_JOBS,
    ): ContentBlobSyncDrainResultV2 = locked {
        require(maxJobs > 0) { "Blob body drain limit must be positive" }
        val allPendingJobs = contentStore.pendingBlobSyncJobs()
        recoverAcknowledgedJournalEntries(session, allPendingJobs)
        val jobs = rotateAfter(
            allPendingJobs.sortedBy(ContentBlobSyncJobMutation::jobId),
            journal.loadSchedulingCursor(session.instanceId, session.workspaceId),
        )
        var uploaded = 0
        var replayed = 0
        var acknowledged = 0
        var inspected = 0
        var rightsDenied = 0
        var failed = 0
        var firstFailure: Throwable? = null
        for (job in jobs.take(MAX_INSPECTIONS_PER_SLICE)) {
            if (uploaded + replayed >= maxJobs) break
            inspected++
            // Advance before the potentially failing side effect. A crash or permanent denial
            // resumes at another job on the next bounded background edge.
            journal.saveSchedulingCursor(session.instanceId, session.workspaceId, job.jobId)
            val key = job.transferKey(session)
            val alreadyCommitted = journal.loadCommitted(key) != null
            val committed = try {
                uploader.upload(
                    session = session,
                    capability = capability,
                    blob = job.blob,
                    access = accessRequest(job),
                    generation = job.generation,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ContentOperationDeniedException) {
                rightsDenied++
                continue
            } catch (failure: Throwable) {
                failed++
                if (firstFailure == null) firstFailure = failure
                continue
            }
            if (alreadyCommitted) replayed++ else uploaded++
            val mutation = committed.toMutation(job.reincarnationEvidence)
            val opId = operationId(committed.receipt)
            try {
                localStore.transaction {
                    if (opId !in state().replica.appliedOpIds) {
                        val wallMillis = nowEpochMillis()
                        val hlc = nextLocalHlc(session.deviceId, wallMillis)
                        applyLocalEvent(
                            event = SyncEvent(opId = opId, hlc = hlc, mutations = listOf(mutation)),
                            nowMillis = wallMillis,
                        )
                    }
                }
                acknowledged += contentStore.acknowledgeBlobSyncJobs(setOf(job.jobId))
                // Clearing before the LocalSyncStore draft or content acknowledgement would lose
                // the only durable copy of the DEK envelope needed after a crash.
                if (contentStore.pendingBlobSyncJobs().none { it.transferKey(session) == key }) {
                    journal.removeCompleted(key)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failed++
                if (firstFailure == null) firstFailure = failure
            }
        }
        if (uploaded + replayed == 0 && firstFailure != null) throw requireNotNull(firstFailure)
        ContentBlobSyncDrainResultV2(
            inspected = inspected,
            uploaded = uploaded,
            replayed = replayed,
            acknowledged = acknowledged,
            remaining = contentStore.pendingBlobSyncJobs().size,
            rightsDenied = rightsDenied,
            failed = failed,
        )
    }

    private suspend fun recoverAcknowledgedJournalEntries(
        session: SyncSession,
        pendingJobs: List<ContentBlobSyncJobMutation>,
    ) {
        val pendingKeys = pendingJobs.mapTo(hashSetOf()) { it.transferKey(session) }
        val appliedOperationIds = localStore.readState().replica.appliedOpIds
        journal.committedKeys(session.instanceId, session.workspaceId).forEach { key ->
            if (key in pendingKeys) return@forEach
            val receipt = journal.loadCommitted(key) ?: return@forEach
            if (operationId(receipt) in appliedOperationIds) journal.removeCompleted(key)
        }
    }

    private fun operationId(receipt: BlobBodyCommitReceiptV2): String =
        "blob-body-v2:${receipt.receiptId}"

    private fun ContentBlobSyncJobMutation.transferKey(session: SyncSession): BlobTransferKeyV2 =
        BlobTransferKeyV2(session.instanceId, session.workspaceId, blob.blobId, generation)

    private fun rotateAfter(
        jobs: List<ContentBlobSyncJobMutation>,
        cursor: String?,
    ): List<ContentBlobSyncJobMutation> {
        if (jobs.size < 2 || cursor == null) return jobs
        val start = jobs.indexOfFirst { it.jobId > cursor }.let { if (it < 0) 0 else it }
        return jobs.drop(start) + jobs.take(start)
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    public companion object {
        /** One potentially large body per scheduler slice keeps metadata and reader work ahead. */
        public const val DEFAULT_MAX_JOBS: Int = 1
        public const val MAX_INSPECTIONS_PER_SLICE: Int = 32
    }
}
