package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationReplicaCursor
import dev.shinsou.kmp.content.ContentPublicationReplicaReplacement
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRightsAdmissionLease
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentTransactionException
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsScope
import kotlinx.coroutines.sync.Mutex

internal enum class ContentReplicaMaterializationStatusV2 {
    IDLE,
    BLOCKED_INCOMPLETE_GRAPH,
    BLOCKED_STALE_REPLICA,
    DOWNLOADED_ONE_BLOB,
    COMMITTED,
    REMOVED,
}

internal data class ContentReplicaMaterializationResultV2(
    val status: ContentReplicaMaterializationStatusV2,
    val publicationId: String? = null,
    val blobId: String? = null,
    val reason: String? = null,
    val replayed: Boolean = false,
)

/** Exact owner context used for the host-owned download rights decision. */
internal data class ContentReplicaBlobContextV2(
    val blob: BlobRef,
    val publicationKey: PublicationKey,
    val acquisitionId: String,
    val unitKey: UnitKey,
    val manifestId: String,
    val contentRevision: Long,
    val grantReference: RightsGrantRef?,
) {
    fun defaultAccessRequest(): ContentAccessRequest = ContentAccessRequest(
        grantReference = grantReference,
        scope = RightsScope(
            publicationId = publicationKey,
            acquisitionId = acquisitionId,
            unitId = unitKey,
            manifestId = manifestId,
            contentRevision = contentRevision,
        ),
    )
}

/**
 * Low-priority destination consumer for schema-v2 publication graphs.
 *
 * Construction performs no I/O. Each explicit [drainSlice] rebuilds the graph from the durable
 * replica, downloads at most one missing body, and returns. A later slice (or a restarted process)
 * adopts already durable bodies through [ContentBlobStore.claimExistingVerified] and commits the
 * complete publication, rights grants, manifest attachments, and one-use receipts atomically.
 */
internal class ContentReplicaMaterializerV2(
    private val localStore: LocalSyncStore,
    private val blobStore: ContentBlobStore,
    private val contentStore: SharedContentTransactionStore<SyncDraft>,
    private val downloader: EncryptedBlobDownloaderV2,
    private val accessRequest: (ContentReplicaBlobContextV2) -> ContentAccessRequest =
        ContentReplicaBlobContextV2::defaultAccessRequest,
    /** Acquired only after the complete authenticated document graph and exact scopes pass. */
    private val acquireValidatedRights: (List<RightsGrant>) -> ContentRightsAdmissionLease = {
        ContentRightsAdmissionLease {}
    },
) {
    private val mutex = Mutex()

    suspend fun drainSlice(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): ContentReplicaMaterializationResultV2 = locked {
        require(session.status == SyncSessionStatus.READY) {
            "Content replica materialization requires a ready sync session"
        }
        require(capability.binding.workspaceId == session.workspaceId &&
            capability.binding.deviceId == session.deviceId) {
            "Content materialization capability is not bound to this session"
        }

        val replica = localStore.readState().replica
        val publicationRecords = replica.entities.entries
            .filter { (key, _) -> key.entityType == SyncEntityType.PUBLICATION }
            .sortedBy { (key, _) -> key }
        if (publicationRecords.isEmpty()) {
            return@locked ContentReplicaMaterializationResultV2(
                ContentReplicaMaterializationStatusV2.IDLE,
            )
        }

        var projectionToMaterialize: MaterializedPublicationProjectionV2? = null
        var firstBlocked: ContentReplicaMaterializationResultV2? = null
        for ((key, record) in publicationRecords) {
            val publicationKey = try {
                require(record.key == key) { "Sync entity map key and record identity disagree" }
                PublicationKey(key.canonicalValue)
            } catch (rejected: IncompleteContentReplicaGraphV2) {
                if (firstBlocked == null) firstBlocked = ContentReplicaMaterializationResultV2(
                    status = ContentReplicaMaterializationStatusV2.BLOCKED_INCOMPLETE_GRAPH,
                    publicationId = key.canonicalValue,
                    reason = rejected.message,
                )
                continue
            } catch (invalid: IllegalArgumentException) {
                if (firstBlocked == null) firstBlocked = ContentReplicaMaterializationResultV2(
                    status = ContentReplicaMaterializationStatusV2.BLOCKED_INCOMPLETE_GRAPH,
                    publicationId = key.canonicalValue,
                    reason = invalid.message ?: "Invalid content replica graph",
                )
                continue
            }

            val current = contentStore.publicationReplicaCursor(publicationKey)
            if (current != null &&
                (current.instanceId != session.instanceId || current.workspaceId != session.workspaceId)
            ) {
                throw SyncInvariantViolation(
                    "Content publication ${publicationKey.value} is bound to a different sync authority",
                )
            }
            if (current != null && replica.throughWorkspaceSeq < current.throughWorkspaceSeq) {
                return@locked ContentReplicaMaterializationResultV2(
                    status = ContentReplicaMaterializationStatusV2.BLOCKED_STALE_REPLICA,
                    publicationId = publicationKey.value,
                    reason = "Replica workspace sequence is older than the durable publication cursor",
                )
            }

            val graph = if (record.isPresent) {
                try {
                    ContentReplicaGraphBuilderV2(replica).build(key, record)
                } catch (rejected: IncompleteContentReplicaGraphV2) {
                    if (firstBlocked == null) firstBlocked = ContentReplicaMaterializationResultV2(
                        status = ContentReplicaMaterializationStatusV2.BLOCKED_INCOMPLETE_GRAPH,
                        publicationId = key.canonicalValue,
                        reason = rejected.message,
                    )
                    continue
                } catch (invalid: IllegalArgumentException) {
                    if (firstBlocked == null) firstBlocked = ContentReplicaMaterializationResultV2(
                        status = ContentReplicaMaterializationStatusV2.BLOCKED_INCOMPLETE_GRAPH,
                        publicationId = key.canonicalValue,
                        reason = invalid.message ?: "Invalid content replica graph",
                    )
                    continue
                }
            } else {
                null
            }
            val candidate = ContentPublicationReplicaCursor(
                publicationKey = publicationKey,
                instanceId = session.instanceId,
                workspaceId = session.workspaceId,
                throughWorkspaceSeq = replica.throughWorkspaceSeq,
                present = record.isPresent,
                graphFingerprintSha256 = graph?.graphSha256 ?: tombstoneFingerprint(key),
            )
            if (current != null && candidate.throughWorkspaceSeq == current.throughWorkspaceSeq) {
                if (candidate != current) {
                    throw SyncInvariantViolation(
                        "Content publication ${publicationKey.value} changed at an already materialized workspace sequence",
                    )
                }
                continue
            }
            projectionToMaterialize = MaterializedPublicationProjectionV2(current, candidate, graph)
            break
        }
        val projection = projectionToMaterialize ?: return@locked firstBlocked
            ?: ContentReplicaMaterializationResultV2(ContentReplicaMaterializationStatusV2.IDLE)
        val graph = projection.graph
        val replacement = ContentPublicationReplicaReplacement(
            expected = projection.expectedCursor,
            replacement = projection.cursor,
        )
        if (graph == null) {
            return@locked commitProjection(projection, replacement, emptyList())
        }
        // The downloader's host gate must resolve the authenticated remote grant before the
        // content transaction can durably hydrate it. The lease is released on every one-body
        // return, cancellation, and failure. Only an exact durable commit may keep the admission.
        val admission = acquireValidatedRights(graph.rightsGrants)
        try {
            val receipts = ArrayList<BlobPublishReceipt>(graph.blobs.size)
            graph.blobs.forEach { requirement ->
                val existing = blobStore.claimExistingVerified(requirement.context.blob)
                if (existing != null) {
                    receipts += existing
                    return@forEach
                }
                val downloaded = downloader.download(
                    session = session,
                    capability = capability,
                    synced = requirement.synced,
                    access = accessRequest(requirement.context),
                )
                check(downloaded.reference == requirement.context.blob) {
                    "Blob downloader published a different immutable reference"
                }
                // Stop after one potentially large body. The returned receipt stays in the blob
                // store's process-local ledger; the next slice returns that same object, while a
                // restarted SQL store verifies and adopts the durable payload into a fresh receipt.
                return@locked ContentReplicaMaterializationResultV2(
                    status = ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB,
                    publicationId = graph.publication.key.value,
                    blobId = requirement.context.blob.blobId,
                )
            }

            commitProjection(projection, replacement, receipts)
        } finally {
            admission.release()
        }
    }

    private fun commitProjection(
        projection: MaterializedPublicationProjectionV2,
        replacement: ContentPublicationReplicaReplacement,
        receipts: List<BlobPublishReceipt>,
    ): ContentReplicaMaterializationResultV2 {
        val graph = projection.graph
        val committed = try {
            contentStore.commit(
                ContentCommitBatch(
                    commitId = replacement.commitId,
                    receipts = receipts,
                    attachments = graph?.attachments.orEmpty(),
                    publications = graph?.let { listOf(ContentPublicationMutation(it.publication)) }.orEmpty(),
                    rightsGrants = graph?.rightsGrants?.map(::ContentRightsGrantMutation).orEmpty(),
                    replicaReplacement = replacement,
                    semantics = ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA,
                ),
            )
        } catch (conflict: ContentTransactionException.CommitConflict) {
            if (conflict.conflictingId != replacement.conflictId) throw conflict
            return ContentReplicaMaterializationResultV2(
                status = ContentReplicaMaterializationStatusV2.BLOCKED_STALE_REPLICA,
                publicationId = projection.cursor.publicationKey.value,
                reason = "A newer publication replica won the materialization race",
            )
        }
        check(!committed.deferred) { "Schema-v2 destination materialization was unexpectedly deferred" }
        return ContentReplicaMaterializationResultV2(
            status = if (projection.cursor.present) {
                ContentReplicaMaterializationStatusV2.COMMITTED
            } else {
                ContentReplicaMaterializationStatusV2.REMOVED
            },
            publicationId = projection.cursor.publicationKey.value,
            replayed = committed.replayed,
        )
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

private data class MaterializedPublicationProjectionV2(
    val expectedCursor: ContentPublicationReplicaCursor?,
    val cursor: ContentPublicationReplicaCursor,
    val graph: MaterializedPublicationGraphV2?,
) {
    init {
        require(expectedCursor == null ||
            expectedCursor.publicationKey == cursor.publicationKey) {
            "Replica CAS cursor has the wrong publication identity"
        }
        require(cursor.present == (graph != null)) {
            "Replica presence and materialized publication graph disagree"
        }
        require(graph == null || graph.publication.key == cursor.publicationKey) {
            "Replica cursor and materialized publication identity disagree"
        }
    }
}

private data class MaterializedBlobRequirementV2(
    val context: ContentReplicaBlobContextV2,
    val synced: SyncedBlobReferenceRecord,
)

private data class MaterializedPublicationGraphV2(
    val publication: Publication,
    val rightsGrants: List<RightsGrant>,
    val attachments: List<ManifestAttachment>,
    val blobs: List<MaterializedBlobRequirementV2>,
    val graphSha256: String,
)

private class ContentReplicaGraphBuilderV2(private val state: SyncState) {
    private val fingerprintParts = ArrayList<String>()
    private val rightsById = linkedMapOf<RightsGrantRef, RightsGrant>()
    private val attachments = ArrayList<ManifestAttachment>()
    private val blobContexts = linkedMapOf<String, ContentReplicaBlobContextV2>()

    fun build(publicationKey: SyncEntityKey, publicationRecord: SyncEntityRecord): MaterializedPublicationGraphV2 {
        requireExactRecordKey(publicationKey, publicationRecord)
        val publicationBase = ContentSyncDocumentCodec.decodePublication(publicationRecord.fields)
            ?: reject("Publication document is missing or failed its digest")
        if (publicationBase.acquisitions.isNotEmpty()) reject("Publication document contains inline acquisitions")
        if (SyncEntityKey.publication(publicationBase.key.value) != publicationKey) {
            reject("Publication document identity does not match its entity key")
        }
        val projection = publicationRecord.requiredProjection(
            ContentSyncFields.Publication.PROJECTION_SHA256,
            ContentSyncFields.Publication.PROJECTION_CHUNK_COUNT,
            ContentSyncFields.Publication.PROJECTION_CHUNK_PREFIX,
            "publication",
        )
        requirePublicationScalars(projection, publicationBase)
        val acquisitionIds = projection.requiredChildIds(
            ContentSyncFields.Publication.ACQUISITION_IDS,
            "publication acquisitions",
        )
        fingerprintNode(publicationKey, publicationRecord, ContentSyncFields.Publication.DOCUMENT_SHA256)
        fingerprintProjection(publicationKey, publicationRecord, ContentSyncFields.Publication.PROJECTION_SHA256)

        val acquisitionKeys = acquisitionIds.map(SyncEntityKey::acquisition)
        requireExactPresentChildren(
            parent = publicationKey,
            childType = SyncEntityType.ACQUISITION,
            parentField = ContentSyncFields.Acquisition.PUBLICATION_KEY,
            expected = acquisitionKeys,
            label = "publication acquisitions",
        )
        val acquisitions = acquisitionKeys.map { acquisitionKey ->
            buildAcquisition(publicationBase.key, publicationKey, acquisitionKey)
        }
        val publication = publicationBase.copy(acquisitions = acquisitions).also(Publication::validate)

        val requirements = blobContexts.entries.sortedBy { it.key }
            .map { (blobId, context) ->
                val synced = state.blobReferences[blobId]
                    ?: reject("Manifest blob $blobId has no synchronized body reference")
                if (!synced.isRemotelyAvailable || synced.blob?.value != context.blob) {
                    reject("Manifest blob $blobId is not an exact remotely available reference")
                }
                MaterializedBlobRequirementV2(context, synced)
            }
        val digestInput = buildString {
            fingerprintParts.forEach { part ->
                append(part.length)
                append(':')
                append(part)
            }
        }
        return MaterializedPublicationGraphV2(
            publication = publication,
            rightsGrants = rightsById.values.sortedBy { it.grantId.value },
            attachments = attachments.sortedBy(ManifestAttachment::attachmentKey),
            blobs = requirements,
            graphSha256 = Sha256.hex(digestInput.encodeToByteArray()),
        )
    }

    private fun buildAcquisition(
        publicationId: PublicationKey,
        publicationKey: SyncEntityKey,
        acquisitionKey: SyncEntityKey,
    ): Acquisition {
        val record = requiredPresentRecord(acquisitionKey, "acquisition")
        record.requireParent(ContentSyncFields.Acquisition.PUBLICATION_KEY, publicationKey, "acquisition")
        val base = ContentSyncDocumentCodec.decodeAcquisition(record.fields)
            ?: reject("Acquisition document is missing or failed its digest")
        if (base.units.isNotEmpty()) reject("Acquisition document contains inline units")
        if (base.id != acquisitionKey.canonicalValue || !PublicationKey.isPortableUuid(base.id)) {
            reject("Acquisition document identity does not match its entity key")
        }
        val projection = record.requiredProjection(
            ContentSyncFields.Acquisition.PROJECTION_SHA256,
            ContentSyncFields.Acquisition.PROJECTION_CHUNK_COUNT,
            ContentSyncFields.Acquisition.PROJECTION_CHUNK_PREFIX,
            "acquisition",
        )
        requireAcquisitionScalars(projection, base)
        fingerprintNode(acquisitionKey, record, ContentSyncFields.Acquisition.DOCUMENT_SHA256)
        fingerprintProjection(acquisitionKey, record, ContentSyncFields.Acquisition.PROJECTION_SHA256)

        val rightsReference = base.rightsGrantRef
        if (rightsReference == null) {
            if (record.hasRightsDocumentFields()) {
                reject("Acquisition without a rights reference contains a rights document")
            }
        } else {
            val grant = ContentSyncDocumentCodec.decodeRightsGrant(record.fields)
                ?: reject("Rights document is missing or failed its digest")
            if (grant.grantId != rightsReference || grant.scope.publicationId != publicationId ||
                grant.scope.acquisitionId != base.id
            ) {
                reject("Rights document identity or scope does not match its acquisition")
            }
            rightsById[grant.grantId]?.let { existing ->
                if (existing != grant) reject("One rights grant id resolves to conflicting documents")
            }
            rightsById[grant.grantId] = grant
            fingerprintNode(
                acquisitionKey,
                record,
                ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_SHA256,
                suffix = "rights",
            )
        }

        val unitIds = projection.requiredChildIds(ContentSyncFields.Acquisition.UNIT_IDS, "acquisition units")
        val unitKeys = unitIds.map(SyncEntityKey::publicationUnit)
        requireExactPresentChildren(
            parent = acquisitionKey,
            childType = SyncEntityType.PUBLICATION_UNIT,
            parentField = ContentSyncFields.Unit.ACQUISITION_KEY,
            expected = unitKeys,
            label = "acquisition units",
        )
        val units = unitKeys.map { unitKey ->
            buildUnit(publicationId, base, acquisitionKey, unitKey)
        }
        return base.copy(units = units).also(Acquisition::validate)
    }

    private fun buildUnit(
        publicationId: PublicationKey,
        acquisition: Acquisition,
        acquisitionKey: SyncEntityKey,
        unitKey: SyncEntityKey,
    ): PublicationUnit {
        val record = requiredPresentRecord(unitKey, "publication unit")
        record.requireParent(ContentSyncFields.Unit.ACQUISITION_KEY, acquisitionKey, "publication unit")
        val base = ContentSyncDocumentCodec.decodeUnit(record.fields)
            ?: reject("Publication-unit document is missing or failed its digest")
        if (base.manifestRevisions.isNotEmpty()) reject("Publication-unit document contains inline manifests")
        if (base.key.publicationKey != publicationId || base.key.value != unitKey.canonicalValue) {
            reject("Publication-unit document identity does not match its entity key")
        }
        val projection = record.requiredProjection(
            ContentSyncFields.Unit.PROJECTION_SHA256,
            ContentSyncFields.Unit.PROJECTION_CHUNK_COUNT,
            ContentSyncFields.Unit.PROJECTION_CHUNK_PREFIX,
            "publication unit",
        )
        requireUnitScalars(projection, base)
        fingerprintNode(unitKey, record, ContentSyncFields.Unit.DOCUMENT_SHA256)
        fingerprintProjection(unitKey, record, ContentSyncFields.Unit.PROJECTION_SHA256)

        val manifestIds = projection.requiredChildIds(ContentSyncFields.Unit.MANIFEST_IDS, "unit manifests")
        val manifestKeys = manifestIds.map(SyncEntityKey::contentManifest)
        requireExactPresentChildren(
            parent = unitKey,
            childType = SyncEntityType.CONTENT_MANIFEST,
            parentField = ContentSyncFields.Manifest.UNIT_KEY,
            expected = manifestKeys,
            label = "unit manifests",
        )
        val manifests = manifestKeys.map { manifestKey ->
            buildManifest(publicationId, acquisition, base.key, unitKey, manifestKey)
        }
        return base.copy(manifestRevisions = manifests).also(PublicationUnit::validate)
    }

    private fun buildManifest(
        publicationId: PublicationKey,
        acquisition: Acquisition,
        unitId: UnitKey,
        unitKey: SyncEntityKey,
        manifestKey: SyncEntityKey,
    ): ContentManifest {
        val record = requiredPresentRecord(manifestKey, "content manifest")
        record.requireParent(ContentSyncFields.Manifest.UNIT_KEY, unitKey, "content manifest")
        val manifest = ContentSyncDocumentCodec.decodeManifest(record.fields)
            ?: reject("Content-manifest document is missing or failed its digest")
        if (manifest.manifestId != manifestKey.canonicalValue) {
            reject("Content-manifest document identity does not match its entity key")
        }
        val projection = record.requiredProjection(
            ContentSyncFields.Manifest.PROJECTION_SHA256,
            ContentSyncFields.Manifest.PROJECTION_CHUNK_COUNT,
            ContentSyncFields.Manifest.PROJECTION_CHUNK_PREFIX,
            "content manifest",
        )
        requireManifestScalars(projection, manifest)
        fingerprintNode(manifestKey, record, ContentSyncFields.Manifest.DOCUMENT_SHA256)
        fingerprintProjection(manifestKey, record, ContentSyncFields.Manifest.PROJECTION_SHA256)

        val owner = ContentManifestOwner(publicationId, acquisition.id, unitId)
        attachments += ManifestAttachment(owner, manifest)
        manifest.referencedBlobs.forEach { blob ->
            val context = ContentReplicaBlobContextV2(
                blob = blob,
                publicationKey = publicationId,
                acquisitionId = acquisition.id,
                unitKey = unitId,
                manifestId = manifest.manifestId,
                contentRevision = manifest.contentRevision,
                grantReference = acquisition.rightsGrantRef,
            )
            blobContexts[blob.blobId]?.let { existing ->
                if (existing.blob != blob) reject("One blob id resolves to conflicting immutable references")
            }
            // Deterministic first owner supplies the operation scope for a shared immutable body.
            if (blob.blobId !in blobContexts) blobContexts[blob.blobId] = context
        }
        return manifest
    }

    private fun requirePublicationScalars(fields: Map<String, SyncValue>, publication: Publication) {
        if (fields.requiredString(ContentSyncFields.Publication.TITLE) != publication.title ||
            fields.requiredStrings(ContentSyncFields.Publication.AUTHOR) != publication.authors ||
            fields.requiredNullableString(ContentSyncFields.Publication.DESCRIPTION) != publication.description
        ) reject("Publication scalar fields disagree with its verified document")
    }

    private fun requireAcquisitionScalars(fields: Map<String, SyncValue>, acquisition: Acquisition) {
        if (fields.requiredNullableString(ContentSyncFields.Acquisition.SOURCE_KEY) !=
            acquisition.sourceBinding?.sourceKey?.canonicalId ||
            fields.requiredNullableString(ContentSyncFields.Acquisition.REMOTE_CANONICAL_ID) !=
            acquisition.sourceBinding?.remoteEntityKey?.canonicalId ||
            fields.requiredNullableString(ContentSyncFields.Acquisition.RIGHTS_GRANT_ID) !=
            acquisition.rightsGrantRef?.value ||
            fields.requiredString(ContentSyncFields.Acquisition.AVAILABILITY) != acquisition.availability.name ||
            fields.requiredLong(ContentSyncFields.Acquisition.CONTENT_REVISION) != acquisition.contentRevision
        ) reject("Acquisition scalar fields disagree with its verified document")
    }

    private fun requireUnitScalars(fields: Map<String, SyncValue>, unit: PublicationUnit) {
        val ordinal = fields.requiredNullableLong(ContentSyncFields.Unit.ORDINAL)?.toIntExact("unit ordinal")
        if (fields.requiredString(ContentSyncFields.Unit.TITLE) != unit.title ||
            fields.requiredLong(ContentSyncFields.Unit.SOURCE_ORDER) != (unit.ordinal ?: 0).toLong() ||
            ordinal != unit.ordinal ||
            fields.requiredNullableString(ContentSyncFields.Unit.REMOTE_CANONICAL_ID) !=
            unit.sourceBinding?.remoteEntityKey?.canonicalId
        ) reject("Publication-unit scalar fields disagree with its verified document")
    }

    private fun requireManifestScalars(fields: Map<String, SyncValue>, manifest: ContentManifest) {
        if (fields.requiredLong(ContentSyncFields.Manifest.CONTENT_REVISION) != manifest.contentRevision ||
            fields.requiredStrings(ContentSyncFields.Manifest.CONTENT_KIND) !=
            manifest.representations.map { it.kind.name } ||
            fields.requiredStrings(ContentSyncFields.Manifest.BLOB_IDS) !=
            manifest.referencedBlobs.map(BlobRef::blobId).sorted() ||
            fields.requiredStrings(ContentSyncFields.Manifest.REPRESENTATION_ID) !=
            manifest.representations.map { it.representationId }.sorted()
        ) reject("Content-manifest scalar fields disagree with its verified document")
    }

    private fun requireExactPresentChildren(
        parent: SyncEntityKey,
        childType: SyncEntityType,
        parentField: String,
        expected: List<SyncEntityKey>,
        label: String,
    ) {
        val expectedSet = expected.toSet()
        if (expectedSet.size != expected.size) reject("$label contains duplicate identities")
        val actual = state.entities.values.filter { record ->
            record.key.entityType == childType && record.isPresent &&
                (record.fields[parentField]?.value as? SyncValue.EntityKeyValue)?.value == parent
        }.mapTo(linkedSetOf(), SyncEntityRecord::key)
        if (actual != expectedSet) reject("$label child list and parent edges are not exactly bidirectional")
    }

    private fun requiredPresentRecord(key: SyncEntityKey, label: String): SyncEntityRecord {
        val record = state.entities[key] ?: reject("Expected $label ${key.canonicalValue} is missing")
        requireExactRecordKey(key, record)
        if (!record.isPresent) reject("Expected $label ${key.canonicalValue} is tombstoned")
        return record
    }

    private fun requireExactRecordKey(key: SyncEntityKey, record: SyncEntityRecord) {
        if (record.key != key) reject("Sync entity map key and record identity disagree")
    }

    private fun fingerprintNode(
        key: SyncEntityKey,
        record: SyncEntityRecord,
        digestField: String,
        suffix: String = "document",
    ) {
        val digest = (record.fields[digestField]?.value as? SyncValue.StringValue)?.value
            ?: reject("Required string field $digestField is missing")
        if (!SHA256_HEX.matches(digest)) reject("$suffix digest is not canonical SHA-256")
        fingerprintParts += "${key.stableString()}|$suffix|$digest"
    }

    private fun fingerprintProjection(
        key: SyncEntityKey,
        record: SyncEntityRecord,
        digestField: String,
    ) {
        val digest = (record.fields[digestField]?.value as? SyncValue.StringValue)?.value ?: return
        if (!SHA256_HEX.matches(digest)) reject("projection digest is not canonical SHA-256")
        fingerprintParts += "${key.stableString()}|projection|$digest"
    }
}

private fun SyncEntityRecord.requiredProjection(
    digestField: String,
    countField: String,
    chunkPrefix: String,
    label: String,
): Map<String, SyncValue> {
    val hasProjection = ContentSyncDocumentCodec.hasDocumentFields(
        fields,
        digestField,
        countField,
        chunkPrefix,
    )
    if (!hasProjection) return fields.mapValues { it.value.value }
    return ContentSyncDocumentCodec.decodeProjection(fields, digestField, countField, chunkPrefix)
        ?: reject("$label projection is incomplete or failed its digest")
}

private fun Map<String, SyncValue>.requiredChildIds(field: String, label: String): List<String> {
    val values = requiredStrings(field)
    if (values != values.distinct().sorted() || values.any { !PublicationKey.isPortableUuid(it) }) {
        reject("$label must be unique, sorted canonical UUIDs")
    }
    return values
}

private fun SyncEntityRecord.requireParent(field: String, expected: SyncEntityKey, label: String) {
    val actual = (fields[field]?.value as? SyncValue.EntityKeyValue)?.value
    if (actual != expected) reject("$label parent edge is missing or inconsistent")
}

private fun Map<String, SyncValue>.requiredString(field: String): String =
    (this[field] as? SyncValue.StringValue)?.value
        ?: reject("Required string field $field is missing")

private fun Map<String, SyncValue>.requiredStrings(field: String): List<String> =
    (this[field] as? SyncValue.StringListValue)?.value
        ?: reject("Required string-list field $field is missing")

private fun Map<String, SyncValue>.requiredLong(field: String): Long =
    (this[field] as? SyncValue.LongValue)?.value
        ?: reject("Required integer field $field is missing")

private fun Map<String, SyncValue>.requiredNullableLong(field: String): Long? = when (val value = this[field]) {
    SyncValue.NullValue -> null
    is SyncValue.LongValue -> value.value
    else -> reject("Required nullable integer field $field is missing or invalid")
}

private fun Map<String, SyncValue>.requiredNullableString(field: String): String? = when (val value = this[field]) {
    SyncValue.NullValue -> null
    is SyncValue.StringValue -> value.value
    else -> reject("Required nullable string field $field is missing or invalid")
}

private fun SyncEntityRecord.hasRightsDocumentFields(): Boolean = fields.keys.any { field ->
    field == ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_SHA256 ||
        field == ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_COUNT ||
        field.startsWith(ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_PREFIX)
}

private fun Long.toIntExact(label: String): Int {
    if (this !in 0..Int.MAX_VALUE.toLong()) reject("$label is outside the supported range")
    return toInt()
}

private class IncompleteContentReplicaGraphV2(message: String) : IllegalStateException(message)

private fun reject(message: String): Nothing = throw IncompleteContentReplicaGraphV2(message)

private fun tombstoneFingerprint(key: SyncEntityKey): String = Sha256.hex(
    "$TOMBSTONE_FINGERPRINT_DOMAIN\u0000${key.stableString()}".encodeToByteArray(),
)

private const val TOMBSTONE_FINGERPRINT_DOMAIN: String = "shinsou:content-publication-tombstone:v2"
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
