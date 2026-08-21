package dev.shinsou.kmp.backup

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotReplacementOrigin
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.ContentAnnotationSyncDraftFactory
import dev.shinsou.kmp.sync.v2.ContentAnnotationPatchV2
import dev.shinsou.kmp.sync.v2.ContentPublicationSyncDraftFactory
import dev.shinsou.kmp.sync.v2.ContentPortableGraphReplacementSyncDraftFactory
import dev.shinsou.kmp.sync.v2.ContentSyncFields
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncWorkspaceDeparture
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2RestoreResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The irreversible boundary crossed by a failed device-only restore. */
public enum class PortableContentBackupV2RestoreFailurePhase {
    LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
}

/** The only safe continuation after the local transaction rolls back post-departure. */
public enum class PortableContentBackupV2RestoreRecoveryStatus {
    RETRY_SAME_VERIFIED_ARCHIVE,
}

/**
 * A device-only restore left its Cloudflare workspace, then failed inside the local transaction.
 *
 * The legacy snapshot, portable graph, auxiliary metadata/aliases/ledgers, annotations, rights
 * grants, attachments and outbox were rolled back together. Published immutable bodies and their
 * process-local receipts intentionally remain staged. Retrying the same verified archive on this
 * coordinator reuses those receipts; a restarted process claims the same verified bodies and
 * creates replacement receipts.
 */
public class PortableContentBackupV2RecoverableRestoreException(
    public val phase: PortableContentBackupV2RestoreFailurePhase,
    public val recoveryStatus: PortableContentBackupV2RestoreRecoveryStatus,
    public val archiveManifestSha256: String,
    public val stagedContentBlobCount: Int,
    cause: Throwable,
) : IllegalStateException(
    "The workspace was left, but the device-local content restore rolled back. " +
        "Retry the same verified archive.",
    cause,
)

/**
 * Production restore boundary for a verified content-backup v2 archive.
 *
 * It deliberately does not call `replaceSnapshot`. The legacy-compatible portable projection is
 * published through [ShinsouRepository.commitSnapshotMutationAtomically], after annotation rows,
 * content rows/body receipts, and the selected target's v2 journals commit on the shared driver.
 */
public class PortableContentBackupV2RestoreCoordinator(
    private val repository: ShinsouRepository,
    private val foundation: ContentFoundationRuntime,
    private val sessionStore: SyncSessionStore,
    private val workspaceDeparture: SyncWorkspaceDeparture,
    private val offlineStoreAuthorizer: ContentBodyOfflineStoreAuthorizer,
) {
    private val restoreMutex = Mutex()
    private val stagedReceiptsByArchive = mutableMapOf<String, MutableMap<String, BlobPublishReceipt>>()

    public suspend fun restore(
        expectedInspection: BackupV2Inspection,
        encodedArchive: ByteArray,
        target: SnapshotRestoreTarget,
    ): PortableContentBackupV2RestoreResult = restore(
        expectedInspection = expectedInspection,
        archiveSource = ByteArrayBackupV2ArchiveSource(encodedArchive),
        target = target,
    )

    /** File/channel-backed restore path; encoded bodies are never collected into a whole archive. */
    public suspend fun restore(
        expectedInspection: BackupV2Inspection,
        archiveSource: BackupV2ArchiveSource,
        target: SnapshotRestoreTarget,
    ): PortableContentBackupV2RestoreResult = restoreMutex.withLock {
        val restoreContext = currentCoroutineContext()
        val ensureRestoreActive: () -> Unit = { restoreContext.ensureActive() }
        ensureRestoreActive()
        val decoded = BackupV2BinaryCodec.decodeInspected(
            CancellationCheckingBackupV2ArchiveSource(archiveSource, ensureRestoreActive),
        )
        ensureRestoreActive()
        val archive = decoded.archive
        val inspection = decoded.inspection
        require(inspection == expectedInspection) {
            "Verified backup inspection does not match the selected archive"
        }
        val state = inspection.portableState
        val previousPublications = foundation.publications.all()
        val previousAnnotations = foundation.annotations.list(includeTombstones = true)
        val bodyPlans = buildBodyPublishPlans(inspection)
        // Validate every exact grant/scope/byte tuple before workspace departure and before the
        // first externally visible body publish. A later denial cannot strand earlier bodies.
        bodyPlans.flatMap(BackupV2BodyPublishPlan::authorizations)
            .forEach(offlineStoreAuthorizer::requireAllowed)
        ensureRestoreActive()
        val provisionalGrants = state.rightsGrants.filter { grant ->
            foundation.rightsGrants.find(grant.grantId) == null
        }
        val session = sessionStore.load()
        val cloudflareConfigured = session?.provider == SyncProvider.CLOUDFLARE_V2
        if (target == SnapshotRestoreTarget.ALL_SYNCED_DEVICES) {
            require(cloudflareConfigured && session.status == SyncSessionStatus.READY) {
                "Restoring to all devices requires a ready Cloudflare v2 workspace"
            }
        }

        val archiveDigest = inspection.envelope.manifestSha256
        // Body publication is durable but deliberately invisible to readers until the manifest
        // transaction consumes the receipts. Finish this retryable staging before leaving a
        // workspace: a publish/claim failure must never strand the device outside sync.
        val published = publishBodiesForRetry(
            archive = archive,
            inspection = inspection,
            plans = bodyPlans,
            cancellationCheckpoint = ensureRestoreActive,
        )
        ensureRestoreActive()
        val restoredPublications = state.publications.withLocalBodyAvailability(
            published.receipts.mapTo(hashSetOf()) { it.reference.blobId },
        )
        val synchronized = target == SnapshotRestoreTarget.ALL_SYNCED_DEVICES
        val outbox = if (synchronized) {
            BackupV2SyncDraftFactory.build(
                archiveDigest = inspection.envelope.manifestSha256,
                publications = restoredPublications,
                annotations = state.annotations,
                attachments = published.attachments,
                grants = state.rightsGrants,
                previousPublications = previousPublications,
                previousAnnotations = previousAnnotations,
            )
        } else {
            BackupV2SyncBundle(emptyList(), emptyList())
        }
        val batch = ContentCommitBatch(
            commitId = "backup-v2-restore:${target.name.lowercase()}:${inspection.envelope.manifestSha256}",
            receipts = published.receipts,
            attachments = published.attachments,
            metadata = state.auxiliary.metadata,
            aliases = state.auxiliary.aliases,
            outbox = outbox.drafts,
            migrations = state.auxiliary.migrations,
            publications = restoredPublications.map(::ContentPublicationMutation),
            rightsGrants = state.rightsGrants.map(::ContentRightsGrantMutation),
            blobSyncJobs = outbox.blobSyncJobs,
            semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
        )
        val origin = if (synchronized) {
            SnapshotReplacementOrigin.SYNCHRONIZED_BULK
        } else {
            SnapshotReplacementOrigin.DIRECT
        }
        var departedForDeviceOnlyRestore = false
        var workspaceDepartureAttempted = false
        try {
            if (target == SnapshotRestoreTarget.THIS_DEVICE_ONLY && cloudflareConfigured) {
                ensureRestoreActive()
                workspaceDepartureAttempted = true
                workspaceDeparture.leaveWorkspace()
                // A successful return crosses the irreversible boundary. From this assignment
                // onward, cancellation or an unreadable session postcondition must be reported as
                // typed recovery instead of leaking a plain failure after the workspace is gone.
                departedForDeviceOnlyRestore = true
                ensureRestoreActive()
                if (sessionStore.load()?.provider == SyncProvider.CLOUDFLARE_V2) {
                    // A host which returned without clearing Cloudflare violated the departure
                    // contract and did not establish the irreversible boundary.
                    departedForDeviceOnlyRestore = false
                    error("Workspace departure did not clear the active Cloudflare session")
                }
            }
            // Also fail closed if a workspace was provisioned while the archive was being staged.
            // The repository guard repeats this check at its serialized mutation boundary.
            if (target == SnapshotRestoreTarget.THIS_DEVICE_ONLY) {
                check(sessionStore.load()?.provider != SyncProvider.CLOUDFLARE_V2) {
                    "A device-only restore cannot commit while Cloudflare sync is active"
                }
            }
            repository.commitSnapshotMutationAtomically(
                requested = state.legacySnapshot,
                origin = origin,
            ) { _, _, commitSyncJournal ->
                foundation.transaction {
                    // Failures before the content participant commits leave both annotations and the
                    // sync journal inside the enclosing SQLite rollback. No network operation occurs.
                    foundation.annotations.replaceAllAtomically(state.annotations)
                    commitSyncJournal()
                    foundation.transactions.commit(batch).also { result ->
                        check(!result.deferred) { "Portable backup restore was deferred" }
                    }
                }
            }
        } catch (failure: Throwable) {
            if (!departedForDeviceOnlyRestore && workspaceDepartureAttempted) {
                // A cancellable departure implementation may finish clearing the session on a
                // worker dispatcher, then deliver cancellation instead of its successful result.
                // Recheck without cancellation before deciding whether the irreversible boundary
                // was crossed. An unreadable postcondition is conservatively recoverable.
                departedForDeviceOnlyRestore = withContext(NonCancellable) {
                    try {
                        sessionStore.load()?.provider != SyncProvider.CLOUDFLARE_V2
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            // The content participant can hydrate the in-memory authority before the enclosing
            // SQLite transaction later rolls back. Remove only grants that were provisional for
            // this restore; a durable, explicitly revoked grant must remain revoked.
            provisionalGrants.forEach { grant ->
                if (foundation.rightsGrants.find(grant.grantId) == null) {
                    foundation.rightsAuthority.revoke(grant.grantId)
                }
            }
            if (departedForDeviceOnlyRestore) {
                throw PortableContentBackupV2RecoverableRestoreException(
                    phase = PortableContentBackupV2RestoreFailurePhase
                        .LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
                    recoveryStatus = PortableContentBackupV2RestoreRecoveryStatus
                        .RETRY_SAME_VERIFIED_ARCHIVE,
                    archiveManifestSha256 = archiveDigest,
                    stagedContentBlobCount = published.receipts.size,
                    cause = failure,
                )
            }
            throw failure
        }
        foundation.replaceRuntimeRightsWithDurableState()
        PortableContentBackupV2RestoreResult(
            publicationCount = state.publications.size,
            annotationCount = state.annotations.size,
            contentBlobCount = inspection.envelope.manifest.entries.count {
                it.kind == BackupV2EntryKind.CONTENT_BLOB
            },
            synchronizedTarget = target,
        ).also {
            stagedReceiptsByArchive.remove(archiveDigest)
        }
    }

    /**
     * Body publication intentionally precedes the shared SQL transaction. If a later annotation,
     * journal, content, or outbox write rolls back, the publish receipts remain valid and must be
     * reused by the retry rather than publishing the same immutable blob a second time.
     *
     * Cache entries are recorded one body at a time so a platform storage failure partway through
     * a multi-body archive can also resume without duplicating the receipts already obtained.
     */
    private fun publishBodiesForRetry(
        archive: BackupV2Archive,
        inspection: BackupV2Inspection,
        plans: List<BackupV2BodyPublishPlan>,
        cancellationCheckpoint: () -> Unit,
    ): BackupV2PublishedBodies {
        val archiveDigest = inspection.envelope.manifestSha256
        val staged = stagedReceiptsByArchive.getOrPut(archiveDigest) { mutableMapOf() }
        val receipts = plans.map { plan ->
            cancellationCheckpoint()
            staged[plan.descriptor.path] ?: run {
                val reference = requireNotNull(plan.descriptor.blob)
                executeAuthorized(plan.authorizations) {
                    // A previous process may have published the verified immutable payload and
                    // then crashed before the shared metadata transaction. Receipts are purposely
                    // process-local, so adopt that exact durable body before attempting a publish.
                    foundation.blobStore.claimExistingVerified(reference)
                        ?: publishArchiveEntry(
                            entry = requireNotNull(archive.entry(plan.descriptor.path)) {
                                "Verified backup body entry is missing"
                            },
                            reference = reference,
                            cancellationCheckpoint = cancellationCheckpoint,
                        )
                }.also { receipt ->
                    cancellationCheckpoint()
                    staged[plan.descriptor.path] = receipt
                }
            }
        }
        return BackupV2PublishedBodies(
            inspection = inspection,
            receipts = receipts,
            attachments = inspection.envelope.manifest.attachments.map(
                BackupV2AttachmentRecord::attachment,
            ),
        )
    }

    /** Streams one already-inspected entry into the immutable blob staging state machine. */
    private fun publishArchiveEntry(
        entry: BackupV2ArchiveEntry,
        reference: BlobRef,
        cancellationCheckpoint: () -> Unit,
    ): BlobPublishReceipt {
        if (entry.byteSize != reference.byteSize) {
            throw BackupFormatException("Content backup entry size mismatch")
        }
        val stage = foundation.blobStore.beginStage(reference.byteSize, reference.mediaType)
        val reader = try {
            entry.open()
        } catch (failure: Throwable) {
            stage.abort()
            throw failure
        }
        return try {
            var total = 0L
            while (true) {
                cancellationCheckpoint()
                val chunk = reader.readChunk() ?: break
                if (chunk.isEmpty() || total > reference.byteSize - chunk.size) {
                    throw BackupFormatException("Content backup entry size mismatch")
                }
                stage.append(chunk)
                total += chunk.size
            }
            if (total != reference.byteSize) {
                throw BackupFormatException("Content backup entry is truncated")
            }
            cancellationCheckpoint()
            foundation.blobStore.publish(stage.seal(reference))
        } catch (failure: Throwable) {
            stage.abort()
            throw failure
        } finally {
            reader.close()
        }
    }

    /**
     * Resolves archive records back to the exact portable publication graph and acquisition grant.
     * Every body descriptor needs at least one complete attachment; a shared immutable body carries
     * one authorization per owning manifest scope.
     */
    private fun buildBodyPublishPlans(inspection: BackupV2Inspection): List<BackupV2BodyPublishPlan> {
        val state = inspection.portableState
        val publicationsById = state.publications.associateBy(Publication::key)
        val grantsById = state.rightsGrants.associateBy(RightsGrant::grantId)
        val stateAttachments = state.publications.flatMap { publication ->
            publication.acquisitions.flatMap { acquisition ->
                acquisition.units.flatMap { unit ->
                    unit.manifestRevisions.map { manifest ->
                        ManifestAttachment(
                            ContentManifestOwner(publication.key, acquisition.id, unit.key),
                            manifest,
                        )
                    }
                }
            }
        }.associateBy(ManifestAttachment::attachmentKey)
        val authorizationsByPath = LinkedHashMap<String, MutableList<PendingContentBodyStoreRequest>>()
        inspection.envelope.manifest.attachments.forEach { record ->
            val attachment = record.attachment
            require(stateAttachments[attachment.attachmentKey] == attachment) {
                "Backup body attachment is not the exact portable publication manifest"
            }
            val publication = requireNotNull(publicationsById[attachment.owner.publicationKey]) {
                "Backup body attachment refers to an absent publication"
            }
            val acquisition = publication.acquisitions.singleOrNull {
                it.id == attachment.owner.acquisitionId
            } ?: error("Backup body attachment refers to an absent acquisition")
            val grantReference = requireNotNull(acquisition.rightsGrantRef) {
                "Backup body attachment acquisition has no rights grant"
            }
            val grant = requireNotNull(grantsById[grantReference]) {
                "Backup body attachment rights grant is absent"
            }
            val scope = RightsScope(
                publicationId = attachment.owner.publicationKey,
                acquisitionId = attachment.owner.acquisitionId,
                unitId = attachment.owner.unitKey,
                manifestId = attachment.manifestId,
                contentRevision = attachment.contentRevision,
            )
            record.blobEntryPaths.forEach { path ->
                val descriptor = inspection.envelope.manifest.entries.singleOrNull {
                    it.path == path && it.kind == BackupV2EntryKind.CONTENT_BLOB
                } ?: error("Backup body attachment refers to an absent body descriptor")
                authorizationsByPath.getOrPut(path, ::ArrayList) += PendingContentBodyStoreRequest(
                    grant = grant,
                    scope = scope,
                    byteCount = descriptor.byteSize,
                )
            }
        }
        return inspection.envelope.manifest.entries
            .filter { it.kind == BackupV2EntryKind.CONTENT_BLOB }
            .sortedBy(BackupV2EntryDescriptor::path)
            .map { descriptor ->
                val authorizations = authorizationsByPath[descriptor.path]
                    .orEmpty()
                    .distinct()
                require(authorizations.isNotEmpty()) {
                    "Backup body descriptor is not owned by a complete manifest attachment"
                }
                BackupV2BodyPublishPlan(descriptor, authorizations)
            }
    }

    private fun <T> executeAuthorized(
        authorizations: List<PendingContentBodyStoreRequest>,
        index: Int = 0,
        block: () -> T,
    ): T = if (index == authorizations.size) {
        block()
    } else {
        offlineStoreAuthorizer.execute(authorizations[index]) {
            executeAuthorized(authorizations, index + 1, block)
        }
    }

}

private data class BackupV2BodyPublishPlan(
    val descriptor: BackupV2EntryDescriptor,
    val authorizations: List<PendingContentBodyStoreRequest>,
)

/** Keeps synchronous random-access archive verification cooperative with its suspending caller. */
private class CancellationCheckingBackupV2ArchiveSource(
    private val delegate: BackupV2ArchiveSource,
    private val cancellationCheckpoint: () -> Unit,
) : BackupV2ArchiveSource {
    override val byteSize: Long get() = delegate.byteSize

    override fun read(offset: Long, byteCount: Int): ByteArray {
        cancellationCheckpoint()
        return delegate.read(offset, byteCount).also { cancellationCheckpoint() }
    }
}

private data class BackupV2SyncBundle(
    val drafts: List<SyncDraft>,
    val blobSyncJobs: List<ContentBlobSyncJobMutation>,
)

private object BackupV2SyncDraftFactory {
    fun build(
        archiveDigest: String,
        publications: List<Publication>,
        annotations: List<ContentAnnotation>,
        attachments: List<ManifestAttachment>,
        grants: List<RightsGrant>,
        previousPublications: List<Publication>,
        previousAnnotations: List<ContentAnnotation>,
    ): BackupV2SyncBundle {
        val publicationDrafts = publications.sortedBy { it.key.value }.flatMap { publication ->
            ContentPublicationSyncDraftFactory.build(
                publication = publication,
                rightsGrants = grants.filter { it.scope.publicationId == publication.key },
                operationNamespace = "backup-v2:$archiveDigest:${publication.key.value}",
                createdAtMillis = 0,
            ).drafts
        }
        val annotationDrafts = ContentAnnotationSyncDraftFactory.build(
            annotations = annotations,
            operationNamespace = "backup-v2:$archiveDigest:annotations",
            createdAtMillis = 0,
        ).drafts
        val replacementTombstones = ContentPortableGraphReplacementSyncDraftFactory.build(
            previousPublications = previousPublications,
            replacementPublications = publications,
            previousAnnotations = previousAnnotations,
            replacementAnnotations = annotations,
            operationNamespace = "backup-v2:$archiveDigest:replacement-tombstones",
            createdAtMillis = 0,
        )
        val drafts = publicationDrafts + annotationDrafts + replacementTombstones.drafts
        val jobs = bodyJobs(archiveDigest, attachments, grants)
        // The authoritative replacement contains both the annotations present in the archive and
        // redacted tombstones for annotations removed by it.  Validate the complete draft set;
        // looking only at [annotationDrafts] incorrectly rejects every non-empty replacement and
        // can hide a missing tombstone in the negative half of the graph update.
        val committedAnnotationIds = drafts
            .flatMap { it.event.mutations }
            .filterIsInstance<ContentAnnotationPatchV2>()
            .filter { ContentSyncFields.Annotation.COMMITTED_SHA256 in it.fields }
            .map { it.key.canonicalValue }
            .sorted()
        val expectedAnnotationIds = (
            annotations.map(ContentAnnotation::annotationId) +
                replacementTombstones.tombstonedAnnotationIds
            ).sorted()
        require(drafts.map(SyncDraft::draftId).distinct().size == drafts.size &&
            committedAnnotationIds == expectedAnnotationIds) {
            "Backup v2 sync metadata drafts are incomplete or collide"
        }
        return BackupV2SyncBundle(drafts, jobs)
    }

    private fun bodyJobs(
        archiveDigest: String,
        attachments: List<ManifestAttachment>,
        grants: List<RightsGrant>,
    ): List<ContentBlobSyncJobMutation> {
        val grantsById = grants.associateBy(RightsGrant::grantId)
        data class Candidate(
            val attachment: ManifestAttachment,
            val blob: BlobRef,
            val grant: RightsGrant,
        )
        val candidates = attachments.flatMap { attachment ->
            val publication = attachment.owner.publicationKey
            grants.asSequence()
                .filter { grant ->
                    grant.scope.publicationId == publication &&
                        grant.scope.acquisitionId == attachment.owner.acquisitionId &&
                        ContentOperation.SYNC_BLOB in grant.allowedOperations
                }
                .flatMap { grant -> attachment.blobs.asSequence().map { Candidate(attachment, it, grant) } }
                .toList()
        }
        return candidates.groupBy { it.blob.blobId }.entries.sortedBy { it.key }.map { (blobId, choices) ->
            val selected = choices.minBy {
                "${it.attachment.owner.scopeKey}/${it.attachment.manifestId}/${it.attachment.contentRevision}"
            }
            check(grantsById[selected.grant.grantId] == selected.grant)
            ContentBlobSyncJobMutation(
                jobId = stableId(archiveDigest, "blob:$blobId"),
                blob = selected.blob,
                owner = ContentManifestOwner(
                    selected.attachment.owner.publicationKey,
                    selected.attachment.owner.acquisitionId,
                    selected.attachment.owner.unitKey,
                ),
                manifestId = selected.attachment.manifestId,
                contentRevision = selected.attachment.contentRevision,
                grantReference = selected.grant.grantId,
            )
        }
    }

    private fun stableId(archiveDigest: String, label: String): String =
        "backup-v2:${Sha256.hex("$archiveDigest|$label".encodeToByteArray())}"

}

private fun List<Publication>.withLocalBodyAvailability(
    locallyPublishedBlobIds: Set<String>,
): List<Publication> = map { publication ->
    publication.copy(
        acquisitions = publication.acquisitions.map { acquisition ->
            val hasMissingBody = acquisition.units.any { unit ->
                unit.manifestRevisions.any { manifest ->
                    manifest.referencedBlobs.any { it.blobId !in locallyPublishedBlobIds }
                }
            }
            if (!hasMissingBody || acquisition.availability == AcquisitionAvailability.UNAVAILABLE) {
                acquisition
            } else {
                acquisition.copy(availability = AcquisitionAvailability.PARTIAL)
            }
        },
    )
}
