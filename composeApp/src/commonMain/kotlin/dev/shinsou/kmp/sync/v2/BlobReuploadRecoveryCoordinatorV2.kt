package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentBlobReincarnationEvidence
import dev.shinsou.kmp.content.ContentBlobReincarnationTerminalKind
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public enum class BlobReuploadRecoveryStatusV2 {
    NO_ACTION,
    LIVE_REFERENCE_NOT_FOUND,
    RIGHTS_DENIED,
    LOCAL_BODY_MISSING,
    JOB_COMMITTED,
    COMMIT_REPLAYED,
    JOURNAL_CHANGED,
}

public data class BlobReuploadRecoveryResultV2(
    val status: BlobReuploadRecoveryStatusV2,
    val blobId: String? = null,
    val jobId: String? = null,
)

/**
 * Converts a terminal Worker `reupload_required` result into one ordinary durable body-upload job.
 *
 * This coordinator is deliberately local-only: it performs no Worker call and handles at most one
 * blob per background edge. The exact content transaction commits before the lifecycle journal is
 * compare-and-swap removed, so every crash boundary reduces to either deterministic commit replay
 * or the already-durable [ContentBlobSyncJobMutation].
 */
public class BlobReuploadRecoveryCoordinatorV2(
    private val contentStore: SharedContentTransactionStore<SyncDraft>,
    private val blobStore: ContentBlobStore,
    private val lifecycleJournal: BlobLifecycleJournalV2,
    private val publications: () -> List<Publication>,
    /** Runtime policy is re-evaluated immediately before durable work is admitted. */
    private val authorizeSync: (ContentBlobSyncJobMutation) -> Boolean,
) {
    private val mutex = Mutex()

    public suspend fun drainSlice(
        instanceId: String,
        workspaceId: String,
    ): BlobReuploadRecoveryResultV2 = mutex.withLock {
        val terminals = lifecycleJournal.entries(instanceId, workspaceId)
            .asSequence()
            .filterIsInstance<DurableBlobLifecycleIntentV2.ReferenceTombstone>()
            .filter(DurableBlobLifecycleIntentV2.ReferenceTombstone::requiresBodyReupload)
            .sortedWith(compareBy({ it.attemptCount }, { it.blobId }))
            .take(MAX_INSPECTIONS_PER_SLICE)
            .toList()
        if (terminals.isEmpty()) {
            return@withLock BlobReuploadRecoveryResultV2(BlobReuploadRecoveryStatusV2.NO_ACTION)
        }

        var firstDeferred: BlobReuploadRecoveryResultV2? = null
        terminals.forEach { original ->
            val terminal = original.nextAttempt() as DurableBlobLifecycleIntentV2.ReferenceTombstone
            lifecycleJournal.save(terminal)
            val result = processTerminal(terminal, instanceId, workspaceId)
            when (result.status) {
                BlobReuploadRecoveryStatusV2.JOB_COMMITTED,
                BlobReuploadRecoveryStatusV2.COMMIT_REPLAYED,
                BlobReuploadRecoveryStatusV2.JOURNAL_CHANGED,
                -> return@withLock result
                else -> if (firstDeferred == null) firstDeferred = result
            }
        }
        return@withLock requireNotNull(firstDeferred)
    }

    private suspend fun processTerminal(
        terminal: DurableBlobLifecycleIntentV2.ReferenceTombstone,
        instanceId: String,
        workspaceId: String,
    ): BlobReuploadRecoveryResultV2 {
        val candidates = findCandidates(terminal.blobId)
        if (candidates.isEmpty()) {
            return BlobReuploadRecoveryResultV2(
                status = BlobReuploadRecoveryStatusV2.LIVE_REFERENCE_NOT_FOUND,
                blobId = terminal.blobId,
            )
        }
        val prepared = candidates.map { candidate ->
            candidate to candidate.toJob(terminal, instanceId, workspaceId)
        }
        val (candidate, job) = prepared.firstOrNull { (_, candidateJob) ->
            authorizeSync(candidateJob)
        } ?: run {
            val deniedJob = prepared.first().second
            return BlobReuploadRecoveryResultV2(
                status = BlobReuploadRecoveryStatusV2.RIGHTS_DENIED,
                blobId = terminal.blobId,
                jobId = deniedJob.jobId,
            )
        }
        if (!blobStore.contains(candidate.blob)) {
            return BlobReuploadRecoveryResultV2(
                status = BlobReuploadRecoveryStatusV2.LOCAL_BODY_MISSING,
                blobId = terminal.blobId,
                jobId = job.jobId,
            )
        }

        val commitId = recoveryIdentity(terminal, candidate, instanceId, workspaceId)
        val committed = contentStore.commit(
            ContentCommitBatch(
                commitId = "$RECOVERY_COMMIT_PREFIX$commitId",
                blobSyncJobs = listOf(job),
            ),
        )
        val removed = lifecycleJournal.remove(terminal)
        return BlobReuploadRecoveryResultV2(
            status = when {
                !removed -> BlobReuploadRecoveryStatusV2.JOURNAL_CHANGED
                committed.replayed -> BlobReuploadRecoveryStatusV2.COMMIT_REPLAYED
                else -> BlobReuploadRecoveryStatusV2.JOB_COMMITTED
            },
            blobId = terminal.blobId,
            jobId = job.jobId,
        )
    }

    private fun findCandidates(blobId: String): List<ReuploadCandidate> = publications()
        .asSequence()
        .flatMap { publication ->
            publication.acquisitions.asSequence().flatMap { acquisition ->
                val grant = acquisition.rightsGrantRef
                if (grant == null) {
                    emptySequence()
                } else {
                    acquisition.units.asSequence().flatMap { unit ->
                        unit.manifestRevisions.asSequence().flatMap { manifest ->
                            manifest.referencedBlobs.asSequence()
                                .filter { it.blobId == blobId }
                                .map { blob ->
                                    ReuploadCandidate(
                                        blob = blob,
                                        owner = ContentManifestOwner(
                                            publicationKey = publication.key,
                                            acquisitionId = acquisition.id,
                                            unitKey = unit.key,
                                        ),
                                        manifestId = manifest.manifestId,
                                        contentRevision = manifest.contentRevision,
                                        grantReference = grant,
                                    )
                                }
                        }
                    }
                }
            }
        }
        .sortedBy(ReuploadCandidate::stableOrder)
        .toList()

    private fun ReuploadCandidate.toJob(
        terminal: DurableBlobLifecycleIntentV2.ReferenceTombstone,
        instanceId: String,
        workspaceId: String,
    ): ContentBlobSyncJobMutation {
        val identity = recoveryIdentity(terminal, this, instanceId, workspaceId)
        return ContentBlobSyncJobMutation(
            jobId = "$RECOVERY_JOB_PREFIX$identity",
            blob = blob,
            owner = owner,
            manifestId = manifestId,
            contentRevision = contentRevision,
            grantReference = grantReference,
            generation = terminal.generation + 1,
            reincarnationEvidence = terminal.toContentEvidence(),
        )
    }

    private fun recoveryIdentity(
        terminal: DurableBlobLifecycleIntentV2.ReferenceTombstone,
        candidate: ReuploadCandidate,
        instanceId: String,
        workspaceId: String,
    ): String = Sha256.hex(
        buildString {
            append("shinsou-blob-reupload-recovery-v2\n")
            append(instanceId).append('\n')
            append(workspaceId).append('\n')
            append(terminal.handle.tombstoneId).append('\n')
            append(terminal.generation).append('\n')
            append(terminal.terminalEvidenceIdentity()).append('\n')
            append(candidate.stableOrder).append('\n')
            append(candidate.blob.blobId).append('\n')
            append(candidate.blob.plaintextDigest).append('\n')
            append(candidate.blob.byteSize).append('\n')
            append(candidate.blob.mediaType)
        }.encodeToByteArray(),
    )

    private data class ReuploadCandidate(
        val blob: BlobRef,
        val owner: ContentManifestOwner,
        val manifestId: String,
        val contentRevision: Long,
        val grantReference: dev.shinsou.kmp.rights.RightsGrantRef,
    ) {
        val stableOrder: String
            get() = "${owner.scopeKey}/$manifestId/$contentRevision/${grantReference.value}"
    }

    private companion object {
        const val RECOVERY_COMMIT_PREFIX: String = "blob-reupload-recovery-v2:commit:"
        const val RECOVERY_JOB_PREFIX: String = "blob-reupload-recovery-v2:job:"
        const val MAX_INSPECTIONS_PER_SLICE: Int = 32
    }
}

private fun DurableBlobLifecycleIntentV2.ReferenceTombstone.requiresBodyReupload(): Boolean =
    gcReceipt != null ||
    creationDisposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED ||
        revivalResult?.disposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED

private fun DurableBlobLifecycleIntentV2.ReferenceTombstone.toContentEvidence():
    ContentBlobReincarnationEvidence = if (gcReceipt != null) {
    ContentBlobReincarnationEvidence(
        previousManifestId = handle.manifestId,
        tombstoneId = handle.tombstoneId,
        previousGeneration = generation,
        terminalKind = ContentBlobReincarnationTerminalKind.GC_COMPLETED,
        gcReceiptId = gcReceipt.receiptId,
    )
} else {
    check(
        creationDisposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED ||
            revivalResult?.disposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED,
    ) { "Blob reupload recovery omitted terminal Worker evidence" }
    ContentBlobReincarnationEvidence(
        previousManifestId = handle.manifestId,
        tombstoneId = handle.tombstoneId,
        previousGeneration = generation,
        terminalKind = ContentBlobReincarnationTerminalKind.REUPLOAD_REQUIRED,
    )
}

private fun DurableBlobLifecycleIntentV2.ReferenceTombstone.terminalEvidenceIdentity(): String =
    gcReceipt?.let { "gc:${it.receiptId}" } ?: "terminal:reupload-required"
