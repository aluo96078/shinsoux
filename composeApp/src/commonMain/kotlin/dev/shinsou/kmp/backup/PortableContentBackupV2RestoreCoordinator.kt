package dev.shinsou.kmp.backup

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.LegacyOutboxDraft
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
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2RestoreResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production restore boundary for a verified content-backup v2 archive.
 *
 * It deliberately does not call `replaceSnapshot`. The legacy-compatible portable projection is
 * published through [ShinsouRepository.commitSnapshotMutationAtomically], after annotation rows,
 * content rows and body receipts commit on the shared driver. The restore is device-local.
 */
public class PortableContentBackupV2RestoreCoordinator(
    private val repository: ShinsouRepository,
    private val foundation: ContentFoundationRuntime,
    private val offlineStoreAuthorizer: ContentBodyOfflineStoreAuthorizer,
) {
    private val restoreMutex = Mutex()
    private val stagedReceiptsByArchive = mutableMapOf<String, MutableMap<String, BlobPublishReceipt>>()

    public suspend fun restore(
        expectedInspection: BackupV2Inspection,
        encodedArchive: ByteArray,
    ): PortableContentBackupV2RestoreResult = restore(
        expectedInspection = expectedInspection,
        archiveSource = ByteArrayBackupV2ArchiveSource(encodedArchive),
    )

    /** File/channel-backed restore path; encoded bodies are never collected into a whole archive. */
    public suspend fun restore(
        expectedInspection: BackupV2Inspection,
        archiveSource: BackupV2ArchiveSource,
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
        val bodyPlans = buildBodyPublishPlans(inspection)
        // Validate every exact grant/scope/byte tuple before the first externally visible body
        // publish. A later denial cannot strand earlier bodies.
        bodyPlans.flatMap(BackupV2BodyPublishPlan::authorizations)
            .forEach(offlineStoreAuthorizer::requireAllowed)
        ensureRestoreActive()
        val provisionalGrants = state.rightsGrants.filter { grant ->
            foundation.rightsGrants.find(grant.grantId) == null
        }

        val archiveDigest = inspection.envelope.manifestSha256
        // Body publication is durable but deliberately invisible to readers until the manifest
        // transaction consumes the receipts.
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
        val batch = ContentCommitBatch<LegacyOutboxDraft>(
            // Unchanged since the first device-only restore, so a commit journalled by an earlier
            // build keeps the same id and fingerprint.
            commitId = "backup-v2-restore:this_device_only:${inspection.envelope.manifestSha256}",
            receipts = published.receipts,
            attachments = published.attachments,
            metadata = state.auxiliary.metadata,
            aliases = state.auxiliary.aliases,
            migrations = state.auxiliary.migrations,
            publications = restoredPublications.map(::ContentPublicationMutation),
            rightsGrants = state.rightsGrants.map(::ContentRightsGrantMutation),
            blobSyncJobs = emptyList(),
            semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
        )
        try {
            repository.commitSnapshotMutationAtomically(
                requested = state.legacySnapshot,
                origin = SnapshotReplacementOrigin.DIRECT,
            ) { _, _, commitSyncJournal ->
                foundation.transaction {
                    // Failures before the content participant commits leave the annotations inside
                    // the enclosing SQLite rollback. No network operation occurs.
                    foundation.annotations.replaceAllAtomically(state.annotations)
                    commitSyncJournal()
                    foundation.transactions.commit(batch).also { result ->
                        check(!result.deferred) { "Portable backup restore was deferred" }
                    }
                }
            }
        } catch (failure: Throwable) {
            // The content participant can hydrate the in-memory authority before the enclosing
            // SQLite transaction later rolls back. Remove only grants that were provisional for
            // this restore; a durable, explicitly revoked grant must remain revoked.
            provisionalGrants.forEach { grant ->
                if (foundation.rightsGrants.find(grant.grantId) == null) {
                    foundation.rightsAuthority.revoke(grant.grantId)
                }
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
