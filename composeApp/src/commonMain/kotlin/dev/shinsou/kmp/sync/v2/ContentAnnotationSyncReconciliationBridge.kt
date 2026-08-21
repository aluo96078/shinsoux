package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.AnnotationConflictException
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.annotation.ContentAnnotationStore
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Result of one bounded, background annotation reconciliation slice. */
public data class ContentAnnotationSyncReconciliationResult(
    val examined: Int,
    val converged: Int,
    val localAnnotationsStaged: Int,
    val outboxDraftsStaged: Int,
    val replayedCommits: Int,
    val remoteAnnotationsMaterialized: Int,
    val invalidRemoteRecords: Int,
    val concurrentConflicts: Int,
    /** Pass this value as `afterAnnotationId` to continue the same ordered scan. */
    val nextAfterAnnotationId: String?,
)

/**
 * Restart-safe bridge between durable annotations and the verified sync-v2 replica.
 *
 * Local winners are never written directly into LocalSyncStore. They first become deterministic
 * drafts in [SharedContentTransactionStore], so the existing [ContentSyncOutboxDrainBridge] keeps
 * the cross-store boundary at-least-once and restart safe. Remote winners use annotation-store CAS
 * and therefore cannot overwrite an interactive edit which raced this background slice.
 *
 * Tombstones outrank active values regardless of wall-clock time. Other conflicts use the domain
 * update time and then the canonical annotation document as a total, device-independent order.
 * Callers page with [ContentAnnotationSyncReconciliationResult.nextAfterAnnotationId]; all database
 * and canonicalization work is forced onto [workDispatcher], away from the UI dispatcher.
 */
public class ContentAnnotationSyncReconciliationBridge(
    private val annotationStore: ContentAnnotationStore,
    private val contentStore: SharedContentTransactionStore<SyncDraft>,
    private val localStore: LocalSyncStore,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val reconcileMutex = Mutex()

    public suspend fun reconcileSlice(
        maxAnnotations: Int = DEFAULT_MAX_ANNOTATIONS,
        afterAnnotationId: String? = null,
    ): ContentAnnotationSyncReconciliationResult = withContext(workDispatcher) {
        reconcileMutex.withLock {
            reconcileLocked(maxAnnotations, afterAnnotationId)
        }
    }

    private suspend fun reconcileLocked(
        maxAnnotations: Int,
        afterAnnotationId: String?,
    ): ContentAnnotationSyncReconciliationResult {
        require(maxAnnotations in 1..MAX_ANNOTATIONS_PER_SLICE) {
            "Annotation reconciliation slice is outside the bounded limit"
        }
        require(afterAnnotationId == null ||
            (afterAnnotationId.isNotBlank() && afterAnnotationId.none(Char::isISOControl))) {
            "Annotation reconciliation cursor is invalid"
        }
        currentCoroutineContext().ensureActive()

        val pageLimit = maxAnnotations + 1
        val localAnnotations = annotationStore.listPage(
            afterAnnotationIdExclusive = afterAnnotationId,
            limit = pageLimit,
            includeTombstones = true,
        )
        val localById = localAnnotations.associateBy { annotation ->
            annotation.validate()
            annotation.annotationId
        }
        require(localById.size == localAnnotations.size) {
            "Annotation store returned duplicate identities"
        }
        currentCoroutineContext().ensureActive()
        val remoteById = localStore.readState().replica.contentAnnotations
        val remoteIds = smallestIdsAfter(remoteById.keys, afterAnnotationId, pageLimit)
        val orderedIds = (localById.keys + remoteIds).distinct().sorted()
        val selectedIds = orderedIds.take(maxAnnotations)

        var converged = 0
        var localAnnotationsStaged = 0
        var outboxDraftsStaged = 0
        var replayedCommits = 0
        var remoteAnnotationsMaterialized = 0
        var invalidRemoteRecords = 0
        var concurrentConflicts = 0

        selectedIds.forEachIndexed { index, annotationId ->
            currentCoroutineContext().ensureActive()
            val local = localById[annotationId]
            val rawRemote = remoteById[annotationId]
            val remote = rawRemote?.validated(annotationId)
            if (rawRemote != null && remote == null) {
                // An incomplete or internally inconsistent checkpoint row is not equivalent to
                // absence. In particular, treating an incomplete tombstone as absent could
                // resurrect an active local value.
                invalidRemoteRecords++
            } else {
                when (val resolution = resolve(local, remote)) {
                    Resolution.Converged -> converged++
                    Resolution.Empty -> Unit
                    is Resolution.StageLocal -> {
                        // Avoid publishing a stale snapshot if an interactive edit won the CAS
                        // race after list(). A later slice will derive a new deterministic id.
                        if (annotationStore.find(annotationId) != resolution.annotation) {
                            concurrentConflicts++
                        } else {
                            val plan = localDraftPlan(resolution.annotation, remote)
                            val commit = contentStore.commit(
                                ContentCommitBatch(
                                    commitId = plan.commitId,
                                    outbox = plan.drafts,
                                ),
                            )
                            check(!commit.deferred) {
                                "Annotation reconciliation cannot silently defer schema-v2 drafts"
                            }
                            localAnnotationsStaged++
                            outboxDraftsStaged += plan.drafts.size
                            if (commit.replayed) replayedCommits++
                        }
                    }
                    is Resolution.MaterializeRemote -> {
                        try {
                            val current = annotationStore.find(annotationId)
                            if (current != local) {
                                concurrentConflicts++
                            } else {
                                annotationStore.putFromVerifiedReplica(
                                    resolution.annotation,
                                    expectedUpdatedAtEpochMillis = local?.updatedAtEpochMillis,
                                )
                                remoteAnnotationsMaterialized++
                            }
                        } catch (_: AnnotationConflictException) {
                            concurrentConflicts++
                        }
                    }
                }
            }
            if (index % YIELD_INTERVAL == YIELD_INTERVAL - 1 || index == selectedIds.lastIndex) {
                yield()
            }
        }
        currentCoroutineContext().ensureActive()

        return ContentAnnotationSyncReconciliationResult(
            examined = selectedIds.size,
            converged = converged,
            localAnnotationsStaged = localAnnotationsStaged,
            outboxDraftsStaged = outboxDraftsStaged,
            replayedCommits = replayedCommits,
            remoteAnnotationsMaterialized = remoteAnnotationsMaterialized,
            invalidRemoteRecords = invalidRemoteRecords,
            concurrentConflicts = concurrentConflicts,
            nextAfterAnnotationId = selectedIds.lastOrNull().takeIf { selectedIds.size < orderedIds.size },
        )
    }

    private fun resolve(
        local: ContentAnnotation?,
        remote: ValidatedRemoteAnnotation?,
    ): Resolution = when {
        local == null && remote == null -> Resolution.Empty
        local == null -> Resolution.MaterializeRemote(requireNotNull(remote).annotation)
        remote == null -> Resolution.StageLocal(local)
        local == remote.annotation -> Resolution.Converged
        compareCanonical(local, remote.annotation) > 0 -> Resolution.StageLocal(local)
        else -> Resolution.MaterializeRemote(remote.annotation.forLocalCas(local))
    }

    private fun localDraftPlan(
        annotation: ContentAnnotation,
        remote: ValidatedRemoteAnnotation?,
    ): DurableLocalDraftPlan {
        val localDocument = canonicalDocument(annotation)
        val remoteFingerprint = remote?.let { value ->
            val document = canonicalDocument(value.annotation)
            Sha256.hex(
                buildString {
                    append(document.sha256)
                    append(':').append(value.hlc.millis)
                    append(':').append(value.hlc.counter)
                    append(':').append(Sha256.hex(value.hlc.deviceId.encodeToByteArray()))
                    append(':').append(value.present)
                }.encodeToByteArray(),
            )
        } ?: ABSENT_REMOTE_FINGERPRINT
        val operationNamespace = buildString {
            append(OPERATION_NAMESPACE_PREFIX)
            append(':').append(annotation.annotationId)
            append(':').append(localDocument.sha256)
            append(':').append(remoteFingerprint)
        }
        val operationDigest = Sha256.hex(operationNamespace.encodeToByteArray())
        val drafts = ContentAnnotationSyncDraftFactory.build(
            annotations = listOf(annotation),
            operationNamespace = operationNamespace,
            createdAtMillis = maxOf(annotation.updatedAtEpochMillis, remote?.hlc?.millis ?: 0),
        ).drafts
        check(drafts.isNotEmpty()) { "A local annotation winner produced no sync draft" }
        return DurableLocalDraftPlan(
            commitId = "$COMMIT_PREFIX:${annotation.annotationId}:$operationDigest",
            drafts = drafts,
        )
    }

    private fun SyncedAnnotationRecord.validated(
        expectedId: String,
    ): ValidatedRemoteAnnotation? {
        if (annotationId != expectedId) return null
        val annotationRegister = annotation ?: return null
        val presenceRegister = presence ?: return null
        if (annotationRegister.hlc != presenceRegister.hlc) return null
        val value = runCatching {
            annotationRegister.value.also(ContentAnnotation::validate)
        }.getOrNull() ?: return null
        if (value.annotationId != expectedId) return null
        val expectedPresence = value.state == ContentAnnotationState.ACTIVE
        if (presenceRegister.value != expectedPresence) return null
        return ValidatedRemoteAnnotation(
            annotation = value,
            present = presenceRegister.value,
            hlc = annotationRegister.hlc,
        )
    }

    private fun compareCanonical(first: ContentAnnotation, second: ContentAnnotation): Int {
        val firstTombstone = first.state == ContentAnnotationState.TOMBSTONE
        val secondTombstone = second.state == ContentAnnotationState.TOMBSTONE
        if (firstTombstone != secondTombstone) return firstTombstone.compareTo(secondTombstone)
        if (first.updatedAtEpochMillis != second.updatedAtEpochMillis) {
            return first.updatedAtEpochMillis.compareTo(second.updatedAtEpochMillis)
        }
        val firstDocument = canonicalDocument(first)
        val secondDocument = canonicalDocument(second)
        val digestOrder = firstDocument.sha256.compareTo(secondDocument.sha256)
        if (digestOrder != 0) return digestOrder
        return compareChunkLists(firstDocument.chunksBase64Url, secondDocument.chunksBase64Url)
    }

    private fun canonicalDocument(
        annotation: ContentAnnotation,
    ): ContentSyncDocumentCodec.EncodedDocument = ContentSyncDocumentCodec.encodeAnnotation(annotation)

    /**
     * A deletion is monotonic even when an active peer carries a later display timestamp. Promote
     * that tombstone to the local CAS boundary; the next slice then publishes the promoted value.
     */
    private fun ContentAnnotation.forLocalCas(local: ContentAnnotation): ContentAnnotation =
        if (state == ContentAnnotationState.TOMBSTONE &&
            local.state == ContentAnnotationState.ACTIVE &&
            updatedAtEpochMillis < local.updatedAtEpochMillis
        ) {
            copy(updatedAtEpochMillis = local.updatedAtEpochMillis)
        } else {
            this
        }

    private fun compareChunkLists(first: List<String>, second: List<String>): Int {
        val common = minOf(first.size, second.size)
        repeat(common) { index ->
            val compared = first[index].compareTo(second[index])
            if (compared != 0) return compared
        }
        return first.size.compareTo(second.size)
    }

    private suspend fun smallestIdsAfter(
        ids: Set<String>,
        afterExclusive: String?,
        limit: Int,
    ): List<String> {
        val selected = ArrayList<String>(limit)
        ids.forEachIndexed { index, id ->
            currentCoroutineContext().ensureActive()
            if (afterExclusive == null || id > afterExclusive) {
                val found = selected.binarySearch(id)
                if (found < 0) {
                    val insertion = -found - 1
                    if (selected.size < limit) {
                        selected.add(insertion, id)
                    } else if (insertion < limit) {
                        selected.add(insertion, id)
                        selected.removeAt(selected.lastIndex)
                    }
                }
            }
            if (index % REMOTE_SCAN_YIELD_INTERVAL == REMOTE_SCAN_YIELD_INTERVAL - 1) yield()
        }
        return selected
    }

    private sealed interface Resolution {
        data object Empty : Resolution
        data object Converged : Resolution
        data class StageLocal(val annotation: ContentAnnotation) : Resolution
        data class MaterializeRemote(val annotation: ContentAnnotation) : Resolution
    }

    private data class ValidatedRemoteAnnotation(
        val annotation: ContentAnnotation,
        val present: Boolean,
        val hlc: HlcTimestamp,
    )

    private data class DurableLocalDraftPlan(
        val commitId: String,
        val drafts: List<SyncDraft>,
    )

    public companion object {
        public const val DEFAULT_MAX_ANNOTATIONS: Int = 32
        public const val MAX_ANNOTATIONS_PER_SLICE: Int = 256
        private const val YIELD_INTERVAL: Int = 8
        private const val REMOTE_SCAN_YIELD_INTERVAL: Int = 32
        private const val OPERATION_NAMESPACE_PREFIX: String = "annotation-reconcile-v2"
        private const val COMMIT_PREFIX: String = "annotation-reconcile-v2"
        private const val ABSENT_REMOTE_FINGERPRINT: String = "absent"
    }
}
