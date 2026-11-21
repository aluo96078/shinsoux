package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.ContentOperation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.content.deepImmutableSnapshot

// R4-4 (docs/architecture/0006): moved out of ContentTransactions.kt without changing it.

/** Executable shared-transaction oracle with rollback at every physical write boundary. */
public class InMemorySharedContentTransactionStore<D : Any>(
    public val blobStore: InMemoryContentBlobStore,
    private val outboxAdapter: ContentOutboxAdapter<D>,
    /** Host-owned negotiated state; an individual batch cannot self-declare a future wire. */
    private val syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
) : SharedContentTransactionStore<D> {
    private val transactionMutex = SynchronousLock()
    private val metadata = LinkedHashMap<String, String>()
    private val aliases = LinkedHashMap<String, String>()
    private val migrations = LinkedHashMap<String, ContentMigrationLedgerMutation>()
    private val outbox = LinkedHashMap<String, D>()
    private val publications = LinkedHashMap<PublicationKey, Publication>()
    private val quarantines = LinkedHashMap<String, ContentQuarantineMutation>()
    private val rightsGrants = LinkedHashMap<RightsGrantRef, RightsGrant>()
    private val blobSyncJobs = LinkedHashMap<String, ContentBlobSyncJobMutation>()
    private val publicationReplicaCursors = LinkedHashMap<PublicationKey, ContentPublicationReplicaCursor>()
    private val blobRemovalIntents = LinkedHashMap<String, ContentBlobRemovalIntent>()
    private val commits = LinkedHashMap<String, CommittedBatch>()

    public var failureInjection: ContentTransactionFailurePoint? = null

    public val state: InMemoryContentTransactionState<D>
        get() = withTransactionLock {
            InMemoryContentTransactionState(
                metadata = metadata.toMap(),
                aliases = aliases.toMap(),
                migrations = migrations.toMap(),
                outbox = outbox.values.toList(),
                committedIds = commits.keys.toSet(),
                publications = publications.toMap(),
                quarantines = quarantines.toMap(),
                rightsGrants = rightsGrants.toMap(),
                blobSyncJobs = blobSyncJobs.toMap(),
                publicationReplicaCursors = publicationReplicaCursors.toMap(),
                blobRemovalIntents = blobRemovalIntents.toMap(),
            )
        }

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = withTransactionLock {
        lookupMigrationLedgerLocked(namespace, sourceDigestSha256, resultFingerprintSha256)
    }

    override fun pendingOutbox(): List<D> = withTransactionLock { outbox.values.toList() }

    override fun pendingOutboxCount(): Int = withTransactionLock { outbox.size }

    override fun pendingOutboxPage(afterDraftId: String?, limit: Int): List<D> = withTransactionLock {
        require(limit > 0) { "Outbox page limit must be positive" }
        outbox.keys.sorted()
            .filter { afterDraftId == null || it > afterDraftId }
            .take(limit)
            .map { outbox.getValue(it) }
    }

    override fun pendingOutboxDraft(draftId: String): D? = withTransactionLock { outbox[draftId] }

    override fun replaceOutboxDrafts(remove: Set<String>, add: List<D>): Int = withTransactionLock {
        validateAcknowledgementIds(remove, "Outbox draft id")
        add.forEach(outboxAdapter::validate)
        val added = add.associateBy(outboxAdapter::id)
        require(added.size == add.size) { "Replacement outbox draft ids must be unique" }
        require(added.keys.none { it in outbox && it !in remove }) { "Replacement outbox draft id already exists" }
        val removed = remove.count(outbox::containsKey)
        remove.forEach(outbox::remove)
        outbox.putAll(added)
        removed
    }

    override fun acknowledgeOutbox(draftIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(draftIds, "Outbox draft id")
        val existing = draftIds.count(outbox::containsKey)
        draftIds.forEach(outbox::remove)
        existing
    }

    override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = withTransactionLock {
        blobSyncJobs.values.map { it.copy(blob = it.blob.copy()) }
    }

    override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(jobIds, "Blob sync job id")
        val existing = jobIds.count(blobSyncJobs::containsKey)
        jobIds.forEach(blobSyncJobs::remove)
        existing
    }

    override fun publicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? = withTransactionLock {
        publicationKey.validate()
        publicationReplicaCursors[publicationKey]
    }

    override fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> = withTransactionLock {
        blobRemovalIntents.values.sortedBy(ContentBlobRemovalIntent::intentId)
    }

    override fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(intentIds, "Blob removal intent id")
        val existing = intentIds.count(blobRemovalIntents::containsKey)
        intentIds.forEach(blobRemovalIntents::remove)
        existing
    }

    override fun purgeLocalPublicationBodies(
        publicationKey: PublicationKey,
        forgetCommitIdsOf: (Publication) -> Set<String>,
        draftIdPrefixOf: (commitId: String) -> String?,
    ): ContentLocalBodyPurgeResult = withTransactionLock {
        publicationKey.validate()
        val publication = publications[publicationKey] ?: return@withTransactionLock ContentLocalBodyPurgeResult()
        val forgetCommitIds = forgetCommitIdsOf(publication)
        val forgetDraftIdPrefixes = forgetCommitIds.mapNotNullTo(linkedSetOf(), draftIdPrefixOf)
        val purgedUnits = publication.acquisitions.sumOf { acquisition ->
            acquisition.units.count { it.manifestRevisions.isNotEmpty() }
        }
        if (purgedUnits == 0) return@withTransactionLock ContentLocalBodyPurgeResult()
        blobStore.withExclusiveTransaction {
            val blobRollback = blobStore.snapshotForTransactionLocked()
            val publicationSnapshot = LinkedHashMap(publications)
            val blobSyncJobSnapshot = LinkedHashMap(blobSyncJobs)
            val commitSnapshot = LinkedHashMap(commits)
            val outboxSnapshot = LinkedHashMap(outbox)
            try {
                val detached = blobStore.detachManifestAttachmentsLocked(publicationKey)
                outbox.keys.removeAll { id -> forgetDraftIdPrefixes.any(id::startsWith) }
                publications[publicationKey] = publication.withoutManifestRevisions()
                blobSyncJobs.entries.removeAll { (_, job) -> job.owner.publicationKey == publicationKey }
                forgetCommitIds.forEach(commits::remove)
                maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                ContentLocalBodyPurgeResult(
                    removedBlobs = detached.flatMap(BlobAttachment::blobs).distinct(),
                    purgedUnitCount = purgedUnits,
                )
            } catch (error: Throwable) {
                blobRollback.rollback()
                restore(publications, publicationSnapshot)
                restore(blobSyncJobs, blobSyncJobSnapshot)
                restore(commits, commitSnapshot)
                restore(outbox, outboxSnapshot)
                throw error
            }
        }
    }

    override fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult = withTransactionLock {
        val cursorSnapshot = LinkedHashMap(publicationReplicaCursors)
        val intentSnapshot = LinkedHashMap(blobRemovalIntents)
        val commitSnapshot = LinkedHashMap(commits)
        try {
            val publicationKeys = publicationReplicaCursors
                .filterValues { cursor -> cursor.belongsTo(authority) }
                .keys
                .toSet()
            val commitIds = commits.keys.filterTo(linkedSetOf()) { commitId ->
                publicationKeys.any { publicationKey ->
                    commitId.isReplicaCommitFor(publicationKey)
                }
            }
            val removalIntentIds = blobRemovalIntents
                .filterValues { intent -> intent.sourceCursor.belongsTo(authority) }
                .keys
                .toSet()

            publicationKeys.forEach(publicationReplicaCursors::remove)
            commitIds.forEach(commits::remove)
            removalIntentIds.forEach(blobRemovalIntents::remove)
            maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE)

            ContentReplicaAuthorityDepartureResult(
                authority = authority,
                removedCursorCount = publicationKeys.size,
                removedCommitCount = commitIds.size,
                removedBlobRemovalIntentCount = removalIntentIds.size,
            )
        } catch (failure: Throwable) {
            restore(publicationReplicaCursors, cursorSnapshot)
            restore(blobRemovalIntents, intentSnapshot)
            restore(commits, commitSnapshot)
            throw failure
        }
    }

    override fun commit(batch: ContentCommitBatch<D>): ContentCommitResult = withTransactionLock {
        batch.outbox.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeTransactionKey(outboxAdapter.id(draft), "Outbox draft id")
        }
        val authoritativeSyncMode = syncModeProvider()
        val replacingPortableGraph =
            batch.semantics == ContentCommitSemantics.REPLACE_PORTABLE_GRAPH
        val replicaReplacement = batch.replicaReplacement
        replicaReplacement?.let { replacement ->
            val current = publicationReplicaCursors[replacement.replacement.publicationKey]
            if (current == replacement.replacement) {
                validateReplicaReplay(batch)
                val previous = commits[batch.commitId]
                consumeReplayReceipts(batch.receipts, batch.attachments, batch.auxiliaryAttachments)
                return@withTransactionLock (previous?.result ?: replayResult(batch)).copy(replayed = true)
            }
            if (current != replacement.expected) {
                throw ContentTransactionException.CommitConflict(replacement.conflictId)
            }
        }
        val fingerprint = fingerprint(batch, authoritativeSyncMode)
        val migrationReplay = batch.migrations
            .takeUnless { replacingPortableGraph }
            ?.singleOrNull()
            ?.let { migration ->
            lookupMigrationLedgerLocked(
                migration.namespace,
                migration.sourceDigestSha256,
                migration.resultFingerprintSha256,
            )
        }
        migrationReplay?.let { lookup ->
            if (lookup.isConflict) throw ContentTransactionException.CommitConflict(lookup.deterministicCommitId)
            if (lookup.isReplay) {
                val previous = commits[lookup.deterministicCommitId]
                consumeReplayReceipts(batch.receipts, batch.attachments, batch.auxiliaryAttachments)
                return@withTransactionLock (previous?.result ?: replayResult(batch)).copy(replayed = true)
            }
        }
        commits[batch.commitId]?.takeUnless { replacingPortableGraph }?.let { previous ->
            if (previous.fingerprint != fingerprint) throw ContentTransactionException.CommitConflict(batch.commitId)
            // A receipt is an in-process capability, not a bearer token.  An attacker (or a
            // stale caller) may copy every scalar field and still must not replay a commit with
            // a different object.  The original batch remains replayable for idempotence.
            if (previous.receipts.size != batch.receipts.size || previous.receipts.zip(batch.receipts).any { (expected, actual) -> expected !== actual }) {
                val token = batch.receipts.firstOrNull()?.commitToken ?: batch.commitId
                throw ContentBlobStoreException.ReceiptMismatch(token)
            }
            return@withTransactionLock previous.result.copy(replayed = true)
        }

        maybeFail(ContentTransactionFailurePoint.BEFORE_VALIDATE)
        val v1Gaps = buildList {
            batch.outbox.filterNot(outboxAdapter::isRepresentableByCurrentV1).mapTo(this, outboxAdapter::id)
            // String metadata/aliases have no typed proof that sync v1 can represent them.
            batch.metadata.mapTo(this) { "metadata:${it.key}" }
            batch.aliases.mapTo(this) { "alias:${it.alias}" }
            if (batch.attachments.isNotEmpty() || batch.auxiliaryAttachments.isNotEmpty() ||
                batch.receipts.isNotEmpty()
            ) {
                batch.attachments.mapTo(this) { "manifest:${it.manifestId}" }
                if (batch.attachments.isEmpty() && batch.auxiliaryAttachments.isEmpty()) {
                    add("unattached-blob-receipt")
                }
            }
            batch.migrations.mapTo(this) { "migration:${it.migrationKey}" }
            batch.publications.mapTo(this) { "publication:${it.publicationKey.value}" }
            batch.auxiliaryAttachments.mapTo(this) { "auxiliary:${it.attachmentKey}" }
            batch.quarantines.mapTo(this) { "quarantine:${it.quarantineId}" }
            batch.rightsGrants.mapTo(this) { "rights:${it.grantReference.value}" }
            batch.blobSyncJobs.mapTo(this) { "blob-sync-job:${it.jobId}" }
            batch.replicaReplacement?.let { add("replica:${it.replacement.publicationKey.value}") }
        }.distinct()
        if (authoritativeSyncMode == ContentSyncMode.V1_ACTIVE && v1Gaps.isNotEmpty()) {
            if (batch.unrepresentableDraftPolicy == UnrepresentableDraftPolicy.DEFER) {
                return@withTransactionLock ContentCommitResult(
                    batch.commitId,
                    replayed = false,
                    deferred = true,
                    committedGeneration = null,
                    attachedOwnerIds = emptyList(),
                    outboxDraftIds = v1Gaps,
                    migrationKeys = emptyList(),
                )
            }
            throw ContentTransactionException.V1SyncCannotRepresent(v1Gaps)
        }

        validateUniqueBatchEntries(batch)
        maybeFail(ContentTransactionFailurePoint.AFTER_STRUCTURAL_VALIDATE)
        val blobAttachments = batch.attachments.map(ManifestAttachment::asBlobAttachment)

        blobStore.withExclusiveTransaction {
            val blobRollback = blobStore.snapshotForTransactionLocked()
            val metadataSnapshot = LinkedHashMap(metadata)
            val aliasSnapshot = LinkedHashMap(aliases)
            val migrationSnapshot = LinkedHashMap(migrations)
            val outboxSnapshot = LinkedHashMap(outbox)
            val publicationSnapshot = LinkedHashMap(publications)
            val quarantineSnapshot = LinkedHashMap(quarantines)
            val rightsGrantSnapshot = LinkedHashMap(rightsGrants)
            val blobSyncJobSnapshot = LinkedHashMap(blobSyncJobs)
            val publicationReplicaCursorSnapshot = LinkedHashMap(publicationReplicaCursors)
            val blobRemovalIntentSnapshot = LinkedHashMap(blobRemovalIntents)
            val commitSnapshot = LinkedHashMap(commits)
            try {
                if (replacingPortableGraph) {
                    blobStore.clearManifestAttachmentsLocked()
                    publications.clear()
                    rightsGrants.clear()
                    blobSyncJobs.clear()
                    outbox.clear()
                    publicationReplicaCursors.clear()
                    blobRemovalIntents.clear()
                    commits.keys.removeAll { it.startsWith(CONTENT_REPLICA_COMMIT_ID_PREFIX) }
                    // A restore is repeatable even after the same archive was applied before.
                    commits.remove(batch.commitId)
                }
                var detachedReplicaAttachments: List<BlobAttachment> = emptyList()
                replicaReplacement?.let { replacement ->
                    val publicationKey = replacement.replacement.publicationKey
                    if (publicationReplicaCursors[publicationKey] != replacement.expected) {
                        throw ContentTransactionException.CommitConflict(replacement.conflictId)
                    }
                    detachedReplicaAttachments = blobStore.detachManifestAttachmentsLocked(publicationKey)
                    publications.remove(publicationKey)
                    rightsGrants.entries.removeAll { (_, grant) ->
                        grant.scope.publicationId == publicationKey
                    }
                    blobSyncJobs.entries.removeAll { (_, job) ->
                        job.owner.publicationKey == publicationKey
                    }
                }
                blobStore.validateAtomicAttachmentsLocked(
                    batch.receipts,
                    blobAttachments,
                    batch.auxiliaryAttachments,
                )
                maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
                validateMetadataAndAliases(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
                validateMigrations(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
                validateOutbox(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)
                validatePublications(batch)
                validateTypedPublicationAttachments(
                    publications = batch.publications,
                    suppliedAttachments = batch.attachments,
                ) { expected ->
                    blobStore.attachedLocked(
                        expected.owner,
                        expected.manifestId,
                        expected.contentRevision,
                    )?.let { durable -> ManifestAttachment(durable.owner, durable.manifest) }
                }
                validateQuarantines(batch)
                validateRightsGrants(batch)
                validateBlobSyncJobs(batch)

                blobStore.consumeAtomicAttachmentsLocked(
                    batch.receipts,
                    blobAttachments,
                    batch.auxiliaryAttachments,
                ) {
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
                    // Kept for callers of the first M0 draft; the newer point above is emitted
                    // after each attachment, while this one is emitted once below.
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_WRITE)
                batch.metadata.forEach { mutation ->
                    metadata[mutation.key] = mutation.value
                    maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_METADATA_WRITE)
                }
                batch.aliases.forEach { mutation ->
                    aliases[mutation.alias] = mutation.target
                    maybeFail(ContentTransactionFailurePoint.AFTER_ALIAS_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_ALIAS_WRITE)
                }
                batch.migrations.forEach { mutation ->
                    migrations[mutation.migrationKey] = mutation
                    maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_MIGRATION_WRITE)
                }
                batch.outbox.forEach { draft ->
                    outbox[outboxAdapter.id(draft)] = draft
                    maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_OUTBOX_WRITE)
                }
                batch.publications.forEach { mutation ->
                    publications[mutation.publicationKey] = mutation.publication
                    maybeFail(ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_PUBLICATION_WRITE)
                }
                batch.quarantines.forEach { mutation ->
                    quarantines[mutation.quarantineId] = mutation
                    maybeFail(ContentTransactionFailurePoint.AFTER_QUARANTINE_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_QUARANTINE_WRITE)
                }
                batch.rightsGrants.forEach { mutation ->
                    rightsGrants[mutation.grantReference] = mutation.grant
                    maybeFail(ContentTransactionFailurePoint.AFTER_RIGHTS_GRANT_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_RIGHTS_GRANT_WRITE)
                }
                batch.blobSyncJobs.forEach { mutation ->
                    blobSyncJobs[mutation.jobId] = mutation
                }
                val removalIntent = replicaReplacement?.let { replacement ->
                    publicationReplicaCursors[replacement.replacement.publicationKey] = replacement.replacement
                    maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE)
                    buildBlobRemovalIntent(
                        replacement.replacement,
                        detachedReplicaAttachments.flatMap(BlobAttachment::blobs),
                        batch.attachments.flatMap(ManifestAttachment::blobs),
                    )?.also { intent ->
                        blobRemovalIntents[intent.intentId]?.let { existing ->
                            if (existing != intent) {
                                throw ContentTransactionException.CommitConflict(intent.intentId)
                            }
                        }
                        blobRemovalIntents[intent.intentId] = intent
                        maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE)
                    }
                }
                val result = ContentCommitResult(
                    batch.commitId,
                    replayed = false,
                    deferred = false,
                    committedGeneration = batch.receipts.maxOfOrNull(BlobPublishReceipt::generation),
                    attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
                    outboxDraftIds = batch.outbox.map(outboxAdapter::id),
                    migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
                    publicationIds = batch.publications.map { it.publicationKey.value },
                    auxiliaryAttachmentIds = batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey),
                    quarantineIds = batch.quarantines.map(ContentQuarantineMutation::quarantineId),
                    rightsGrantIds = batch.rightsGrants.map { it.grantReference.value },
                    blobSyncJobIds = batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId),
                    blobRemovalIntentIds = listOfNotNull(removalIntent?.intentId),
                )
                commits[batch.commitId] = CommittedBatch(fingerprint, result, batch.receipts.toList())
                maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                result
            } catch (error: Throwable) {
                blobRollback.rollback()
                restore(metadata, metadataSnapshot)
                restore(aliases, aliasSnapshot)
                restore(migrations, migrationSnapshot)
                restore(outbox, outboxSnapshot)
                restore(publications, publicationSnapshot)
                restore(quarantines, quarantineSnapshot)
                restore(rightsGrants, rightsGrantSnapshot)
                restore(blobSyncJobs, blobSyncJobSnapshot)
                restore(publicationReplicaCursors, publicationReplicaCursorSnapshot)
                restore(blobRemovalIntents, blobRemovalIntentSnapshot)
                restore(commits, commitSnapshot)
                throw error
            }
        }
    }

    private fun validateUniqueBatchEntries(batch: ContentCommitBatch<D>) {
        if (batch.metadata.map(ContentMetadataMutation::key).distinct().size != batch.metadata.size) {
            throw ContentTransactionException.DuplicateEntry("Metadata keys must be unique")
        }
        if (batch.aliases.map(ContentAliasMutation::alias).distinct().size != batch.aliases.size) {
            throw ContentTransactionException.DuplicateEntry("Alias keys must be unique")
        }
        if (batch.migrations.map(ContentMigrationLedgerMutation::migrationKey).distinct().size != batch.migrations.size) {
            throw ContentTransactionException.DuplicateEntry("Migration ledger keys must be unique")
        }
        if (batch.outbox.map(outboxAdapter::id).distinct().size != batch.outbox.size) {
            throw ContentTransactionException.DuplicateEntry("Outbox draft ids must be unique")
        }
        if (batch.attachments.map(ManifestAttachment::attachmentKey).distinct().size != batch.attachments.size) {
            throw ContentTransactionException.DuplicateEntry("Manifest attachment keys must be unique")
        }
        if (batch.publications.map(ContentPublicationMutation::publicationKey).distinct().size !=
            batch.publications.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Publication keys must be unique")
        }
        if (batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey).distinct().size !=
            batch.auxiliaryAttachments.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Auxiliary attachment keys must be unique")
        }
        if (batch.quarantines.map(ContentQuarantineMutation::quarantineId).distinct().size !=
            batch.quarantines.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Quarantine ids must be unique")
        }
        if (batch.rightsGrants.map(ContentRightsGrantMutation::grantReference).distinct().size !=
            batch.rightsGrants.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Rights grant ids must be unique")
        }
        if (batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId).distinct().size != batch.blobSyncJobs.size) {
            throw ContentTransactionException.DuplicateEntry("Blob sync job ids must be unique")
        }
    }

    private fun lookupMigrationLedgerLocked(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup {
        val requested = ContentMigrationLedgerMutation(namespace, sourceDigestSha256, resultFingerprintSha256)
        val existing = migrations[requested.migrationKey]
        return ContentMigrationLedgerLookup(
            status = when {
                existing == null -> ContentMigrationLookupStatus.MISSING
                existing.resultFingerprintSha256 == requested.resultFingerprintSha256 ->
                    ContentMigrationLookupStatus.REPLAY
                else -> ContentMigrationLookupStatus.CONFLICT
            },
            namespace = requested.namespace,
            sourceDigestSha256 = requested.sourceDigestSha256,
            requestedResultFingerprintSha256 = requested.resultFingerprintSha256,
            deterministicCommitId = requested.commitId,
            existing = existing,
        )
    }

    private fun replayResult(batch: ContentCommitBatch<D>): ContentCommitResult = ContentCommitResult(
        commitId = batch.commitId,
        replayed = true,
        deferred = false,
        committedGeneration = null,
        attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
        outboxDraftIds = batch.outbox.map(outboxAdapter::id),
        migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
        publicationIds = batch.publications.map { it.publicationKey.value },
        auxiliaryAttachmentIds = batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey),
        quarantineIds = batch.quarantines.map(ContentQuarantineMutation::quarantineId),
        rightsGrantIds = batch.rightsGrants.map { it.grantReference.value },
        blobSyncJobIds = batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId),
        blobRemovalIntentIds = batch.replicaReplacement?.replacement?.let { cursor ->
            blobRemovalIntents.values
                .filter { it.sourceCursor == cursor }
                .map(ContentBlobRemovalIntent::intentId)
                .sorted()
        }.orEmpty(),
    )

    private fun validateReplicaReplay(batch: ContentCommitBatch<D>) {
        val replacement = requireNotNull(batch.replicaReplacement).replacement
        val durablePublication = publications[replacement.publicationKey]
        val durableAttachments = blobStore.withExclusiveTransaction {
            blobStore.manifestAttachmentsLocked(replacement.publicationKey)
        }
        if (replacement.present) {
            if (durablePublication != batch.publications.single().publication ||
                durableAttachments != batch.attachments
                    .map(ManifestAttachment::asBlobAttachment)
                    .sortedBy(BlobAttachment::attachmentKey)
            ) {
                throw ContentTransactionException.CommitConflict(
                    "replica-state:${replacement.publicationKey.value}",
                )
            }
            val requestedRights = batch.rightsGrants.associate { it.grantReference to it.grant }
            val durableRights = rightsGrants.filterValues {
                it.scope.publicationId == replacement.publicationKey
            }
            if (durableRights != requestedRights) {
                throw ContentTransactionException.CommitConflict(
                    "replica-rights:${replacement.publicationKey.value}",
                )
            }
        } else if (durablePublication != null || durableAttachments.isNotEmpty() ||
            rightsGrants.values.any { it.scope.publicationId == replacement.publicationKey }
        ) {
            throw ContentTransactionException.CommitConflict(
                "replica-tombstone:${replacement.publicationKey.value}",
            )
        }
    }

    private fun consumeReplayReceipts(
        receipts: List<BlobPublishReceipt>,
        attachments: List<ManifestAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    ) {
        blobStore.withExclusiveTransaction {
            // A migration ledger proves that the source/result was already committed, but it
            // does not authorize a caller to attach a new owner/blob during replay.  Retire a
            // receipt only when the caller supplied the exact durable attachment (including its
            // complete manifest/ref graph); unrelated/new attachments remain untouched.
            val durableAttachments = attachments.mapNotNull { candidate ->
                val candidateSnapshot = candidate.deepImmutableSnapshot()
                val durable = blobStore.attachedLocked(
                    candidateSnapshot.owner,
                    candidateSnapshot.manifestId,
                    candidateSnapshot.contentRevision,
                )
                candidateSnapshot.asBlobAttachment().takeIf { it == durable }
            }
            val durableAuxiliaryAttachments = auxiliaryAttachments.mapNotNull { candidate ->
                val candidateSnapshot = candidate.deepImmutableSnapshot()
                candidateSnapshot.takeIf {
                    blobStore.auxiliaryAttachedLocked(candidateSnapshot.attachmentKey) == candidateSnapshot
                }
            }
            // The migration ledger is the durable replay authority.  A reopened process may
            // have lost local blob bytes (or have detected corruption) even though its durable
            // metadata/attachment row is intact.  In that case replay still succeeds, but no
            // ephemeral receipt is retired; the caller can recover or republish the capability
            // later.  Only expected blob-boundary failures are swallowed here.
            try {
                blobStore.hydrateAttachmentsLocked(durableAttachments, durableAuxiliaryAttachments)
            } catch (_: ContentBlobStoreException) {
                // Expected blob-boundary failure: receipts stay pending for a later recovery.
                return@withExclusiveTransaction
            }
            val referencedBlobIds = (
                durableAttachments.flatMap(BlobAttachment::blobs) +
                    durableAuxiliaryAttachments.flatMap(AuxiliaryBlobAttachment::blobs)
                )
                .mapTo(hashSetOf(), BlobRef::blobId)
            if (receipts.isEmpty() || referencedBlobIds.isEmpty()) return@withExclusiveTransaction
            val eligible = receipts.filter { it.reference.blobId in referencedBlobIds }
            val valid = eligible.mapNotNull { receipt ->
                try {
                    blobStore.validateReceiptsOnlyLocked(listOf(receipt))
                    receipt
                } catch (_: ContentBlobStoreException) {
                    // Semantic replay does not depend on an ephemeral receipt.  A valid local
                    // receipt is opportunistically retired; a stale/foreign one is left alone.
                    null
                }
            }
            if (valid.isNotEmpty()) {
                try {
                    // Validate/consume the valid subset as one operation so a multi-resource
                    // replay cannot partially retire its local capabilities.
                    blobStore.consumeReceiptsLocked(valid)
                } catch (_: ContentBlobStoreException) {
                    // Keep all capabilities pending if the final grouped consume no longer
                    // matches; durable semantic replay itself remains successful.
                }
            }
        }
    }

    private fun validateMetadataAndAliases(batch: ContentCommitBatch<D>) {
        batch.metadata.forEach { draft ->
            metadata[draft.key]?.let { if (it != draft.value) throw ContentTransactionException.CommitConflict("metadata:${draft.key}") }
        }
        batch.aliases.forEach { draft ->
            aliases[draft.alias]?.let { if (it != draft.target) throw ContentTransactionException.CommitConflict("alias:${draft.alias}") }
        }
    }

    private fun validateOutbox(batch: ContentCommitBatch<D>) {
        batch.outbox.forEach { draft ->
            val id = outboxAdapter.id(draft)
            outbox[id]?.let { existing ->
                if (!outboxAdapter.fingerprint(existing).contentEquals(outboxAdapter.fingerprint(draft))) {
                    throw ContentTransactionException.CommitConflict("outbox:$id")
                }
            }
        }
    }

    private fun validateMigrations(batch: ContentCommitBatch<D>) {
        batch.migrations.forEach { mutation ->
            migrations[mutation.migrationKey]?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict("migration:${mutation.migrationKey}")
                }
            }
        }
    }

    private fun validatePublications(batch: ContentCommitBatch<D>) {
        batch.publications.forEach { mutation ->
            publications[mutation.publicationKey]?.let { existing ->
                if (existing != mutation.publication &&
                    !isImmutablePublicationGraphExtension(existing, mutation.publication)
                ) {
                    throw ContentTransactionException.CommitConflict(
                        "publication:${mutation.publicationKey.value}",
                    )
                }
            }
        }
    }

    private fun validateQuarantines(batch: ContentCommitBatch<D>) {
        val auxiliaryByOwner = batch.auxiliaryAttachments.associateBy(AuxiliaryBlobAttachment::ownerId)
        batch.quarantines.forEach { mutation ->
            val attachment = auxiliaryByOwner[mutation.auxiliaryOwnerId]
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Quarantine ${mutation.quarantineId} is missing its auxiliary blob attachment",
                )
            if (attachment.purpose != AuxiliaryBlobPurpose.PLUGIN_QUARANTINE ||
                attachment.blobs != listOf(mutation.scriptBlob)
            ) {
                throw ContentTransactionException.CommitConflict(
                    "quarantine-attachment:${mutation.quarantineId}",
                )
            }
            quarantines[mutation.quarantineId]?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "quarantine:${mutation.quarantineId}",
                    )
                }
            }
        }
    }

    private fun validateRightsGrants(batch: ContentCommitBatch<D>) {
        // Older projection rows may carry a reference whose provider policy remains local-only;
        // absence therefore stays fail-closed at RightsAuthority instead of breaking decode.
        val publicationByKey = LinkedHashMap(publications)
        batch.publications.forEach { publicationByKey[it.publicationKey] = it.publication }
        val acquisitions = publicationByKey.values
            .flatMap { publication -> publication.acquisitions.map { publication.key to it } }
        batch.rightsGrants.forEach { mutation ->
            val scopedAcquisition = acquisitions.singleOrNull { (publicationKey, acquisition) ->
                acquisition.rightsGrantRef == mutation.grantReference &&
                    publicationKey == mutation.grant.scope.publicationId &&
                    acquisition.id == mutation.grant.scope.acquisitionId
            }
            if (scopedAcquisition == null) {
                throw ContentTransactionException.DuplicateEntry(
                    "Rights grant ${mutation.grantReference.value} has no exact acquisition scope",
                )
            }
            rightsGrants[mutation.grantReference]?.let { existing ->
                if (existing != mutation.grant) {
                    throw ContentTransactionException.CommitConflict(
                        "rights:${mutation.grantReference.value}",
                    )
                }
            }
        }
    }

    private fun validateBlobSyncJobs(batch: ContentCommitBatch<D>) {
        if (batch.blobSyncJobs.isEmpty()) return
        val grants = LinkedHashMap(rightsGrants)
        batch.rightsGrants.forEach { grants[it.grantReference] = it.grant }
        batch.blobSyncJobs.forEach { job ->
            val attachment = batch.attachments.singleOrNull {
                it.owner == job.owner && it.manifestId == job.manifestId &&
                    it.contentRevision == job.contentRevision
            } ?: blobStore.attachedLocked(job.owner, job.manifestId, job.contentRevision)?.let { durable ->
                ManifestAttachment(durable.owner, durable.manifest)
            }
            if (attachment == null || job.blob !in attachment.blobs) {
                throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no exact durable manifest attachment",
                )
            }
            val grant = grants[job.grantReference]
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no durable rights grant",
                )
            if (!grant.scope.covers(job.accessScope) || ContentOperation.SYNC_BLOB !in grant.allowedOperations) {
                throw ContentTransactionException.CommitConflict("blob-sync-rights:${job.jobId}")
            }
            blobSyncJobs[job.jobId]?.let { existing ->
                if (existing != job) throw ContentTransactionException.CommitConflict("blob-sync-job:${job.jobId}")
            }
        }
    }

    private fun fingerprint(batch: ContentCommitBatch<D>, authoritativeSyncMode: ContentSyncMode): String {
        val writer = CanonicalWriter()
        writer.string(batch.commitId)
        writer.string(authoritativeSyncMode.name)
        writer.string(batch.unrepresentableDraftPolicy.name)
        writer.string(batch.semantics.name)
        writer.list(batch.receipts.sortedBy(BlobPublishReceipt::commitToken)) { receipt ->
            string(receipt.storeInstanceId); string(receipt.commitToken); blobRef(receipt.reference)
            long(receipt.incarnation); long(receipt.generation); long(receipt.publishedAtEpochMillis)
        }
        writer.list(batch.attachments.sortedBy(ManifestAttachment::attachmentKey)) { attachment ->
            string(attachment.owner.publicationKey.value)
            string(attachment.owner.acquisitionId)
            string(attachment.owner.unitKey.value)
            bytes(canonicalJsonBytes(CANONICAL_JSON.encodeToJsonElement(ContentManifest.serializer(), attachment.manifest)))
        }
        writer.list(batch.metadata.sortedBy(ContentMetadataMutation::key)) { string(it.key); string(it.value) }
        writer.list(batch.aliases.sortedBy(ContentAliasMutation::alias)) { string(it.alias); string(it.target) }
        writer.list(batch.migrations.sortedBy(ContentMigrationLedgerMutation::migrationKey)) { mutation ->
            string(mutation.namespace); string(mutation.sourceDigestSha256); string(mutation.resultFingerprintSha256)
        }
        writer.list(batch.publications.sortedBy { it.publicationKey.value }) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(Publication.serializer(), mutation.publication),
                ),
            )
        }
        writer.list(batch.auxiliaryAttachments.sortedBy(AuxiliaryBlobAttachment::attachmentKey)) { attachment ->
            string(attachment.ownerId)
            string(attachment.purpose.name)
            list(attachment.blobs.sortedBy(BlobRef::blobId)) { blobRef(it) }
        }
        writer.list(batch.quarantines.sortedBy(ContentQuarantineMutation::quarantineId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(ContentQuarantineMutation.serializer(), mutation),
                ),
            )
        }
        writer.list(batch.rightsGrants.sortedBy { it.grantReference.value }) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(RightsGrant.serializer(), mutation.grant),
                ),
            )
        }
        writer.list(batch.blobSyncJobs.sortedBy(ContentBlobSyncJobMutation::jobId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(ContentBlobSyncJobMutation.serializer(), mutation),
                ),
            )
        }
        batch.replicaReplacement?.let { replacement ->
            writer.bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(
                        ContentPublicationReplicaReplacement.serializer(),
                        replacement,
                    ),
                ),
            )
        } ?: writer.bytes(ByteArray(0))
        writer.list(batch.outbox.sortedBy(outboxAdapter::id)) { draft ->
            string(outboxAdapter.id(draft)); bytes(outboxAdapter.fingerprint(draft))
        }
        return Sha256.hex(writer.toByteArray())
    }

    private fun maybeFail(point: ContentTransactionFailurePoint) {
        if (failureInjection == point) {
            failureInjection = null
            throw IllegalStateException("Injected shared content transaction failure at $point")
        }
    }

    private fun validateAcknowledgementIds(ids: Set<String>, label: String) {
        ids.forEach { requireSafeTransactionKey(it, label) }
    }

    private inline fun <T> withTransactionLock(block: () -> T): T {
        return transactionMutex.withLock(block)
    }

    private fun <K, V> restore(target: MutableMap<K, V>, snapshot: Map<K, V>) {
        target.clear(); target.putAll(snapshot)
    }

    private data class CommittedBatch(
        val fingerprint: String,
        val result: ContentCommitResult,
        val receipts: List<BlobPublishReceipt>,
    )
}

private class CanonicalWriter {
    private val output = ArrayList<Byte>()

    fun string(value: String) = bytes(value.encodeToByteArray())
    fun bytes(value: ByteArray) {
        long(value.size.toLong())
        value.forEach(output::add)
    }
    fun long(value: Long) {
        for (shift in 56 downTo 0 step 8) output += ((value ushr shift) and 0xff).toByte()
    }
    fun blobRef(reference: BlobRef) {
        string(reference.blobId); long(reference.schemaVersion.toLong()); string(reference.digestAlgorithm)
        string(reference.plaintextDigest); long(reference.byteSize); string(reference.mediaType)
    }
    fun <T> list(values: List<T>, write: CanonicalWriter.(T) -> Unit) {
        long(values.size.toLong())
        values.forEach { write(it) }
    }
    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private fun canonicalJsonBytes(element: JsonElement): ByteArray = CanonicalWriter().also { writer ->
    fun write(value: JsonElement) {
        when (value) {
            JsonNull -> writer.string("null")
            is JsonPrimitive -> {
                writer.string(if (value.isString) "string" else "primitive")
                writer.string(value.content)
            }
            is JsonArray -> {
                writer.string("array"); writer.long(value.size.toLong()); value.forEach(::write)
            }
            is JsonObject -> {
                writer.string("object"); writer.long(value.size.toLong())
                value.keys.sorted().forEach { key -> writer.string(key); write(requireNotNull(value[key])) }
            }
        }
    }
    write(element)
}.toByteArray()

private fun ContentPublicationReplicaCursor.belongsTo(authority: ContentReplicaAuthority): Boolean =
    instanceId == authority.instanceId && workspaceId == authority.workspaceId

private fun String.isReplicaCommitFor(publicationKey: PublicationKey): Boolean =
    startsWith("$CONTENT_REPLICA_COMMIT_ID_PREFIX${publicationKey.value}:")
