package dev.shinsou.kmp.content

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.WorkLink
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.ContentOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The durable state exposed by [SqlDriverContentTransactionStore].  Attachments are included in
 * the state because the manifest/blob-ref ledger is part of the same authority as metadata and
 * outbox rows. Body bytes never enter this object; the sibling blob store persists payload BLOBs
 * in its own table while this state exposes only immutable [BlobRef] values.
 */
public data class SqlContentTransactionState<D : Any>(
    val metadata: Map<String, String>,
    val aliases: Map<String, String>,
    val migrations: Map<String, ContentMigrationLedgerMutation>,
    val attachments: List<ManifestAttachment>,
    val outbox: List<D>,
    val committedIds: Set<String>,
    val publications: Map<PublicationKey, Publication> = emptyMap(),
    val auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
    val quarantines: Map<String, ContentQuarantineMutation> = emptyMap(),
    val rightsGrants: Map<RightsGrantRef, RightsGrant> = emptyMap(),
    val blobSyncJobs: Map<String, ContentBlobSyncJobMutation> = emptyMap(),
    val publicationReplicaCursors: Map<PublicationKey, ContentPublicationReplicaCursor> = emptyMap(),
    val blobRemovalIntents: Map<String, ContentBlobRemovalIntent> = emptyMap(),
)

/**
 * SQLite-backed implementation of the shared content transaction boundary.
 *
 * The supplied SQLDelight driver is used synchronously, just like the platform SQLite drivers
 * used by the existing sync persistence.  [TransacterImpl] gives all rows one transaction.  The
 * transactional blob participant is held locked while that transaction runs; its rollback
 * snapshot is restored if SQLite or any participant write fails. Restart-safe participants are
 * eagerly hydrated from every durable SQL attachment before the store becomes observable, so GC
 * can never classify an attached blob as an orphan after reopen.
 *
 * Receipt objects remain process-local capabilities.  Their scalar descriptor is journaled for
 * diagnostics/fingerprint binding; normal commit-journal replay still requires the exact
 * original object identity while it is alive in this process.  A migration-ledger replay is the
 * deliberate exception: its durable source/result identity is sufficient after reopen.
 */
public class SqlDriverContentTransactionStore<D : Any>(
    private val driver: SqlDriver,
    private val blobStore: ContentBlobStore,
    private val outboxAdapter: ContentOutboxPersistenceAdapter<D>,
    json: Json = ContentTransactionJson,
    /** Host-owned sync authority; callers cannot upgrade a batch to V2 by setting its field. */
    private val syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
    /** Platform database hosts pass false and remain the sole close owner of the shared driver. */
    private val ownsDriver: Boolean = true,
) : SharedContentTransactionStore<D> {
    public constructor(
        driver: SqlDriver,
        outboxAdapter: ContentOutboxPersistenceAdapter<D>,
        blobStore: ContentBlobStore,
        json: Json = ContentTransactionJson,
        syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
        ownsDriver: Boolean = true,
    ) : this(driver, blobStore, outboxAdapter, json, syncModeProvider, ownsDriver)
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
    }
    private val transactions = object : TransacterImpl(driver) {}
    private val blobParticipant: ContentBlobTransactionParticipant =
        blobStore.transactionParticipantOrNull()
            ?: throw ContentBlobStoreException.InvalidStage(
                "SQLite content transactions require a transactional blob participant",
            )

    /** Test and fault-injection seam; production code leaves this null. */
    public var failureInjection: ContentTransactionFailurePoint? = null

    /**
     * Reads the complete durable SQL authority on every access.
     *
     * This is intentionally a diagnostic/compatibility surface. Production read sides should use
     * the focused methods below so a publication lookup never decodes unrelated outbox, migration,
     * attachment, quarantine, or blob lifecycle rows.
     */
    public val state: SqlContentTransactionState<D>
        get() = withTransactionLock { readState() }

    init {
        ContentTransactionSchema.create(driver).value
        // WAL is a database property on SQLite.  Do not require it here: Android/iOS may choose
        // a platform-specific journal mode, while the transaction semantics remain identical.
        driver.execute(null, "PRAGMA journal_mode = WAL", 0).value
        driver.execute(null, "PRAGMA synchronous = FULL", 0).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        if (blobParticipant.isRestartSafe) {
            val durableAttachments = loadAllAttachments().map(ManifestAttachment::asBlobAttachment)
            val durableAuxiliaryAttachments = loadAllAuxiliaryAttachments()
            blobParticipant.withExclusiveTransaction {
                blobParticipant.hydrateAttachmentsLocked(
                    durableAttachments,
                    durableAuxiliaryAttachments,
                )
            }
        }
    }

    /** Closes the underlying driver.  Reopening the same path reconstructs [state]. */
    public fun close() {
        if (ownsDriver) driver.close()
    }

    /** Reads and verifies exactly one publication graph without hydrating unrelated SQL state. */
    public fun findPublicationDirect(key: PublicationKey): Publication? = withTransactionLock {
        key.validate()
        findPublication(key)
    }

    /** Reads and verifies only publication graph rows, in stable portable-id order. */
    public fun allPublicationsDirect(): List<Publication> = withTransactionLock {
        loadAllPublications()
    }

    /** Reads and verifies exactly one grant and its scoped publication graph. */
    public fun findRightsGrantDirect(reference: RightsGrantRef): RightsGrant? = withTransactionLock {
        reference.validate()
        findRightsGrant(reference)
    }

    /** Reads and verifies only rights grants and the publication graphs which scope them. */
    public fun allRightsGrantsDirect(): List<RightsGrant> = withTransactionLock {
        loadAllRightsGrants()
    }

    /** Reads the complete grant set owned by one publication replica. */
    public fun rightsGrantsForPublicationDirect(publicationKey: PublicationKey): List<RightsGrant> =
        withTransactionLock {
            publicationKey.validate()
            loadRightsGrantsForPublication(publicationKey)
        }

    /**
     * Reads only the body-free auxiliary authority needed by Backup v2. The stable ordering makes
     * archive checksums deterministic while avoiding unrelated outbox, attachment and body rows.
     */
    public fun portableAuxiliaryStateDirect(): ContentPortableAuxiliaryState = withTransactionLock {
        ContentPortableAuxiliaryState(
            metadata = readPairs(TABLE_METADATA, "key", "value")
                .map { (key, value) -> ContentMetadataMutation(key, value) },
            aliases = readPairs(TABLE_ALIASES, "alias", "target")
                .map { (alias, target) -> ContentAliasMutation(alias, target) },
            migrations = loadAllMigrations(),
        )
    }

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = withTransactionLock {
        lookupMigrationLedgerLocked(namespace, sourceDigestSha256, resultFingerprintSha256)
    }

    override fun commit(batch: ContentCommitBatch<D>): ContentCommitResult = withTransactionLock {
        commitLocked(batch)
    }

    override fun pendingOutbox(): List<D> = withTransactionLock {
        queryRows(TABLE_OUTBOX, "draft_id, payload ORDER BY draft_id").map { row ->
            val id = requireNotNull(row[0])
            val draft = decodeOutbox(requireNotNull(row[1]))
            outboxAdapter.validate(draft)
            check(outboxAdapter.id(draft) == id) { "Outbox key/body mismatch" }
            draft
        }
    }

    override fun acknowledgeOutbox(draftIds: Set<String>): Int = withTransactionLock {
        acknowledgeRows(TABLE_OUTBOX, "draft_id", draftIds, "Outbox draft id")
    }

    override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = withTransactionLock {
        queryRows(TABLE_BLOB_SYNC_JOBS, "job_id ORDER BY job_id").map { row ->
            requireNotNull(findBlobSyncJob(requireNotNull(row[0])))
        }
    }

    override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int = withTransactionLock {
        acknowledgeRows(TABLE_BLOB_SYNC_JOBS, "job_id", jobIds, "Blob sync job id")
    }

    override fun publicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? = withTransactionLock {
        publicationKey.validate()
        findPublicationReplicaCursor(publicationKey)
    }

    override fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> = withTransactionLock {
        queryRows(TABLE_BLOB_REMOVAL_INTENTS, "intent_id ORDER BY intent_id").map { row ->
            requireNotNull(findBlobRemovalIntent(requireNotNull(row[0])))
        }
    }

    override fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int = withTransactionLock {
        acknowledgeRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "intent_id",
            intentIds,
            "Blob removal intent id",
        )
    }

    override fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult = withTransactionLock {
        val mutation = transactions.transactionWithResult<ReplicaAuthorityDepartureMutation>(false) {
            val publicationKeys = queryRows(
                TABLE_PUBLICATION_REPLICA_CURSORS,
                "publication_id WHERE instance_id = '${sqlLiteral(authority.instanceId)}' " +
                    "AND workspace_id = '${sqlLiteral(authority.workspaceId)}' ORDER BY publication_id",
            ).mapTo(linkedSetOf()) { row ->
                PublicationKey(requireNotNull(row[0])).also { publicationKey ->
                    val cursor = requireNotNull(findPublicationReplicaCursor(publicationKey))
                    check(cursor.instanceId == authority.instanceId &&
                        cursor.workspaceId == authority.workspaceId) {
                        "Replica authority index/body mismatch"
                    }
                }
            }
            val commitIds = queryRows(TABLE_COMMITS, "commit_id ORDER BY commit_id")
                .mapNotNullTo(linkedSetOf()) { row ->
                    requireNotNull(row[0]).takeIf { commitId ->
                        publicationKeys.any { publicationKey ->
                            commitId.startsWith(
                                "$CONTENT_REPLICA_COMMIT_ID_PREFIX${publicationKey.value}:",
                            )
                        }
                    }
                }
            val removalIntentIds = queryRows(
                TABLE_BLOB_REMOVAL_INTENTS,
                "intent_id ORDER BY intent_id",
            ).mapNotNullTo(linkedSetOf()) { row ->
                val intentId = requireNotNull(row[0])
                val cursor = requireNotNull(findBlobRemovalIntent(intentId)).sourceCursor
                intentId.takeIf {
                    cursor.instanceId == authority.instanceId &&
                        cursor.workspaceId == authority.workspaceId
                }
            }

            publicationKeys.forEach { publicationKey ->
                execute(
                    "DELETE FROM $TABLE_PUBLICATION_REPLICA_CURSORS " +
                        "WHERE publication_id = ? AND instance_id = ? AND workspace_id = ?",
                    3,
                ) {
                    bindString(0, publicationKey.value)
                    bindString(1, authority.instanceId)
                    bindString(2, authority.workspaceId)
                }
            }
            commitIds.forEach { commitId ->
                execute("DELETE FROM $TABLE_COMMITS WHERE commit_id = ?", 1) {
                    bindString(0, commitId)
                }
            }
            removalIntentIds.forEach { intentId ->
                execute("DELETE FROM $TABLE_BLOB_REMOVAL_INTENTS WHERE intent_id = ?", 1) {
                    bindString(0, intentId)
                }
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE)

            ReplicaAuthorityDepartureMutation(
                result = ContentReplicaAuthorityDepartureResult(
                    authority = authority,
                    removedCursorCount = publicationKeys.size,
                    removedCommitCount = commitIds.size,
                    removedBlobRemovalIntentCount = removalIntentIds.size,
                ),
                removedCommitIds = commitIds,
            )
        }
        mutation.removedCommitIds.forEach(activeReceiptObjects::remove)
        mutation.result
    }

    private fun commitLocked(batch: ContentCommitBatch<D>): ContentCommitResult {
        val authoritativeSyncMode = syncModeProvider()
        val replacingPortableGraph =
            batch.semantics == ContentCommitSemantics.REPLACE_PORTABLE_GRAPH
        val replicaReplacement = batch.replicaReplacement
        validateDrafts(batch)
        replicaReplacement?.let { replacement ->
            val current = findPublicationReplicaCursor(replacement.replacement.publicationKey)
            if (current == replacement.replacement) {
                validateReplicaReplay(batch)
                val previous = findCommit(batch.commitId)
                val durableAttachments = batch.attachments.mapNotNull { candidate ->
                    findAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                consumeReplayReceipts(batch.receipts, durableAttachments, emptyList())
                return replayResult(batch, previous?.result)
            }
            if (current != replacement.expected) {
                throw ContentTransactionException.CommitConflict(replacement.conflictId)
            }
        }
        // A migration ledger is the durable semantic idempotency boundary.  It must be
        // consulted before the normal commit journal, since a reopened process necessarily has
        // different receipt objects (and may have no local receipt objects at all).  An exact
        // source/result pair is therefore a replay even when the caller supplied fresh,
        // republished receipts or a batch with no receipts.
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
            if (lookup.isConflict) {
                throw ContentTransactionException.CommitConflict(lookup.deterministicCommitId)
            }
            if (lookup.isReplay) {
                val previous = findCommit(batch.commitId)
                val durableAttachments = batch.attachments.mapNotNull { candidate ->
                    findAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                val durableAuxiliaryAttachments = batch.auxiliaryAttachments.mapNotNull { candidate ->
                    findAuxiliaryAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                consumeReplayReceipts(
                    batch.receipts,
                    durableAttachments,
                    durableAuxiliaryAttachments,
                )
                return replayResult(batch, previous?.result)
            }
        }
        val fingerprint = fingerprint(batch, authoritativeSyncMode)
        val previous = findCommit(batch.commitId)
        if (previous != null && !replacingPortableGraph) {
            if (previous.fingerprint != fingerprint) {
                throw ContentTransactionException.CommitConflict(batch.commitId)
            }
            val rememberedReceipts = activeReceiptObjects[batch.commitId]
            if (previous.receiptDescriptors.isNotEmpty() &&
                (rememberedReceipts == null ||
                    rememberedReceipts.size != batch.receipts.size ||
                    rememberedReceipts.zip(batch.receipts).any { (expected, actual) -> expected !== actual })
            ) {
                val token = batch.receipts.firstOrNull()?.commitToken ?: batch.commitId
                throw ContentBlobStoreException.ReceiptMismatch(token)
            }
            return previous.result.copy(replayed = true)
        }

        maybeFail(ContentTransactionFailurePoint.BEFORE_VALIDATE)
        val v1Gaps = unrepresentableV1Gaps(batch, authoritativeSyncMode)
        if (authoritativeSyncMode == ContentSyncMode.V1_ACTIVE && v1Gaps.isNotEmpty()) {
            if (batch.unrepresentableDraftPolicy == UnrepresentableDraftPolicy.DEFER) {
                return ContentCommitResult(
                    commitId = batch.commitId,
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

        return blobParticipant.withExclusiveTransaction {
            val blobRollback = blobParticipant.snapshotForTransactionLocked()
            try {
                transactions.transactionWithResult<ContentCommitResult>(false) {
                    var detachedReplicaAttachments: List<BlobAttachment> = emptyList()
                    if (replacingPortableGraph) {
                        deletePortableGraphRows(batch.commitId)
                        blobParticipant.clearManifestAttachmentsLocked()
                    }
                    replicaReplacement?.let { replacement ->
                        val publicationKey = replacement.replacement.publicationKey
                        if (findPublicationReplicaCursor(publicationKey) != replacement.expected) {
                            throw ContentTransactionException.CommitConflict(replacement.conflictId)
                        }
                        val durableAttachments = loadPublicationAttachments(publicationKey)
                            .map(ManifestAttachment::asBlobAttachment)
                            .sortedBy(BlobAttachment::attachmentKey)
                        val participantAttachments = blobParticipant
                            .detachManifestAttachmentsLocked(publicationKey)
                            .sortedBy(BlobAttachment::attachmentKey)
                        check(participantAttachments == durableAttachments) {
                            "SQLite/blob participant publication attachment ledger mismatch"
                        }
                        detachedReplicaAttachments = durableAttachments
                        deletePublicationReplicaRows(publicationKey)
                    }
                    val persistedAttachments = batch.attachments.map { attachment ->
                        attachment to findAttachment(attachment.attachmentKey)
                    }
                    persistedAttachments.forEach { (attachment, existing) ->
                        if (existing != null && existing != attachment) {
                            throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
                        }
                    }
                    val persistedAuxiliaryAttachments = batch.auxiliaryAttachments.map { attachment ->
                        attachment to findAuxiliaryAttachment(attachment.attachmentKey)
                    }
                    persistedAuxiliaryAttachments.forEach { (attachment, existing) ->
                        if (existing != null && existing != attachment) {
                            throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
                        }
                    }

                    validateSqlRows(batch)
                    validatePublicationAttachments(batch)
                    validateQuarantines(batch)
                    validateRightsGrants(batch)
                    validateBlobSyncJobs(batch)

                    val attachmentBlobIds = (
                        batch.attachments.flatMap(ManifestAttachment::blobs) +
                            batch.auxiliaryAttachments.flatMap(AuxiliaryBlobAttachment::blobs)
                        )
                        .map(BlobRef::blobId)
                        .toSet()
                    if (batch.receipts.any { it.reference.blobId !in attachmentBlobIds }) {
                        throw ContentBlobStoreException.InvalidStage(
                            "Blob receipts must match a referenced manifest or auxiliary blob",
                        )
                    }

                    // Existing rows are hydrated into the in-memory participant below so a
                    // freshly reopened process has the same attachment/GC view as SQL.  New
                    // rows still require every referenced receipt, and receipts supplied for an
                    // existing row are checked and consumed separately below.
                    val newAttachments = persistedAttachments
                        .filter { (_, existing) -> existing == null }
                        .map { (attachment, _) -> attachment.asBlobAttachment() }
                    val existingAttachments = persistedAttachments
                        .mapNotNull { (_, existing) -> existing?.asBlobAttachment() }
                    val newAuxiliaryAttachments = persistedAuxiliaryAttachments
                        .filter { (_, existing) -> existing == null }
                        .map { (attachment, _) -> attachment }
                    val existingAuxiliaryAttachments = persistedAuxiliaryAttachments
                        .mapNotNull { (_, existing) -> existing }
                    val newBlobIds = (
                        newAttachments.flatMap(BlobAttachment::blobs) +
                            newAuxiliaryAttachments.flatMap(AuxiliaryBlobAttachment::blobs)
                        ).map(BlobRef::blobId).toSet()
                    val newReceipts = batch.receipts.filter { it.reference.blobId in newBlobIds }
                    val existingReceipts = batch.receipts.filter { it.reference.blobId !in newBlobIds }

                    // Existing SQL rows must also be installed in a freshly opened blob
                    // participant; otherwise the participant's recovery/GC view would treat a
                    // SQL-referenced blob as an orphan.
                    blobParticipant.hydrateAttachmentsLocked(
                        existingAttachments,
                        existingAuxiliaryAttachments,
                    )
                    blobParticipant.validateAtomicAttachmentsLocked(
                        newReceipts,
                        newAttachments,
                        newAuxiliaryAttachments,
                    )
                    blobParticipant.validateReceiptsOnlyLocked(existingReceipts)
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)

                    blobParticipant.consumeAtomicAttachmentsLocked(
                        newReceipts,
                        newAttachments,
                        newAuxiliaryAttachments,
                    ) {
                        maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
                        maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
                    }
                    blobParticipant.consumeReceiptsLocked(existingReceipts)
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_WRITE)

                    writeAttachments(newAttachments)
                    writeAuxiliaryAttachments(newAuxiliaryAttachments)
                    writeMetadata(batch.metadata)
                    writeAliases(batch.aliases)
                    writeMigrations(batch.migrations)
                    writeOutbox(batch.outbox)
                    writePublications(batch.publications)
                    writeQuarantines(batch.quarantines)
                    writeRightsGrants(batch.rightsGrants)
                    writeBlobSyncJobs(batch.blobSyncJobs)

                    val removalIntent = replicaReplacement?.let { replacement ->
                        writePublicationReplicaCursor(replacement.replacement)
                        maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE)
                        buildBlobRemovalIntent(
                            replacement.replacement,
                            detachedReplicaAttachments.flatMap(BlobAttachment::blobs),
                            batch.attachments.flatMap(ManifestAttachment::blobs),
                        )?.also { intent ->
                            writeBlobRemovalIntent(intent)
                            maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE)
                        }
                    }

                    val result = ContentCommitResult(
                        commitId = batch.commitId,
                        replayed = false,
                        deferred = false,
                        committedGeneration = batch.receipts.maxOfOrNull(BlobPublishReceipt::generation),
                        attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
                        outboxDraftIds = batch.outbox.map(outboxAdapter::id),
                        migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
                        publicationIds = batch.publications.map { it.publicationKey.value },
                        auxiliaryAttachmentIds = batch.auxiliaryAttachments.map(
                            AuxiliaryBlobAttachment::attachmentKey,
                        ),
                        quarantineIds = batch.quarantines.map(ContentQuarantineMutation::quarantineId),
                        rightsGrantIds = batch.rightsGrants.map { it.grantReference.value },
                        blobSyncJobIds = batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId),
                        blobRemovalIntentIds = listOfNotNull(removalIntent?.intentId),
                    )
                    insertCommit(
                        commitId = batch.commitId,
                        fingerprint = fingerprint,
                        result = result,
                        receipts = batch.receipts,
                    )
                    maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                    result
                }
            } catch (error: Throwable) {
                // SQLDelight has already rolled back the SQLite transaction when the callback
                // throws.  Restore the object-identity receipt ledger and attachment map too.
                blobRollback.rollback()
                throw error
            }
        }.also {
            if (replacingPortableGraph) {
                activeReceiptObjects.keys.removeAll {
                    it.startsWith(CONTENT_REPLICA_COMMIT_ID_PREFIX)
                }
            }
            activeReceiptObjects[batch.commitId] = batch.receipts.toList()
        }
    }

    /**
     * Replays only the ephemeral capabilities that are still local and valid.  The migration
     * ledger/commit journal already proves the durable operation; a stale or foreign capability
     * must not turn an otherwise successful semantic replay into a failure.  We deliberately
     * catch only the blob boundary's expected validation errors so programming/locking errors
     * remain visible to callers.
     */
    private fun consumeReplayReceipts(
        receipts: List<BlobPublishReceipt>,
        attachments: List<ManifestAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    ) {
        val durableAttachments = attachments.map(ManifestAttachment::asBlobAttachment)
        val referencedBlobIds = (
            durableAttachments.flatMap(BlobAttachment::blobs) +
                auxiliaryAttachments.flatMap(AuxiliaryBlobAttachment::blobs)
            )
            .mapTo(hashSetOf(), BlobRef::blobId)
        if (receipts.isEmpty() || referencedBlobIds.isEmpty()) return
        val eligible = receipts.filter { it.reference.blobId in referencedBlobIds }
        if (eligible.isEmpty()) return
        blobParticipant.withExclusiveTransaction {
            // A semantic replay is authorized by the durable migration ledger, not by the
            // process-local blob participant.  If local bytes are missing/corrupt, leave all
            // receipts pending and let a later recovery/republish repair the participant.
            try {
                blobParticipant.hydrateAttachmentsLocked(durableAttachments, auxiliaryAttachments)
            } catch (_: ContentBlobStoreException) {
                return@withExclusiveTransaction
            }
            val valid = eligible.mapNotNull { receipt ->
                try {
                    blobParticipant.validateReceiptsOnlyLocked(listOf(receipt))
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
                    // attachment cannot partially retire its local capabilities.
                    blobParticipant.consumeReceiptsLocked(valid)
                } catch (_: ContentBlobStoreException) {
                    // Keep all capabilities pending if the final grouped consume no longer
                    // matches; durable semantic replay itself remains successful.
                }
            }
        }
    }

    private fun replayResult(
        batch: ContentCommitBatch<D>,
        previous: ContentCommitResult?,
    ): ContentCommitResult = (previous ?: ContentCommitResult(
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
            queryRows(
                TABLE_BLOB_REMOVAL_INTENTS,
                "intent_id ORDER BY intent_id",
            ).mapNotNull { row ->
                findBlobRemovalIntent(requireNotNull(row[0]))
            }.filter { it.sourceCursor == cursor }
                .map(ContentBlobRemovalIntent::intentId)
        }.orEmpty(),
    )).copy(replayed = true)

    private val activeReceiptObjects = LinkedHashMap<String, List<BlobPublishReceipt>>()
    private val transactionMutex = SynchronousLock()

    private inline fun <T> withTransactionLock(block: () -> T): T {
        return transactionMutex.withLock(block)
    }

    private fun validateDrafts(batch: ContentCommitBatch<D>) {
        batch.outbox.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeSqlKey(outboxAdapter.id(draft), "Outbox draft id")
            val encoded = outboxAdapter.encode(draft)
            require(encoded.length <= MAX_SQL_PAYLOAD_LENGTH) { "Outbox payload is too large" }
        }
    }

    private fun unrepresentableV1Gaps(
        batch: ContentCommitBatch<D>,
        authoritativeSyncMode: ContentSyncMode,
    ): List<String> {
        if (authoritativeSyncMode != ContentSyncMode.V1_ACTIVE) return emptyList()
        return buildList {
        batch.outbox.filterNot(outboxAdapter::isRepresentableByCurrentV1).mapTo(this, outboxAdapter::id)
        // There is no typed v1 proof for the new metadata/alias authority.  Fail closed instead
        // of treating a string key/value as if the legacy wire could represent it losslessly.
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
        if (batch.receipts.map(BlobPublishReceipt::commitToken).distinct().size != batch.receipts.size) {
            throw ContentTransactionException.DuplicateEntry("Blob receipt tokens must be unique")
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
        if (batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId).distinct().size !=
            batch.blobSyncJobs.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Blob sync job ids must be unique")
        }
    }

    private fun validateSqlRows(batch: ContentCommitBatch<D>) {
        batch.metadata.forEach { mutation ->
            findString(TABLE_METADATA, "key", mutation.key, "value")?.let { existing ->
                if (existing != mutation.value) throw ContentTransactionException.CommitConflict("metadata:${mutation.key}")
            }
        }
        batch.aliases.forEach { mutation ->
            findString(TABLE_ALIASES, "alias", mutation.alias, "target")?.let { existing ->
                if (existing != mutation.target) throw ContentTransactionException.CommitConflict("alias:${mutation.alias}")
            }
        }
        batch.migrations.forEach { mutation ->
            findMigration(mutation.migrationKey)?.let { existing ->
                if (existing != mutation) throw ContentTransactionException.CommitConflict("migration:${mutation.migrationKey}")
            }
        }
        batch.outbox.forEach { draft ->
            val id = outboxAdapter.id(draft)
            findString(TABLE_OUTBOX, "draft_id", id, "payload")?.let { encoded ->
                val existing = decodeOutbox(encoded)
                outboxAdapter.validate(existing)
                if (!outboxAdapter.fingerprint(existing).contentEquals(outboxAdapter.fingerprint(draft))) {
                    throw ContentTransactionException.CommitConflict("outbox:$id")
                }
            }
        }
        batch.publications.forEach { mutation ->
            findPublication(mutation.publicationKey)?.let { existing ->
                if (existing != mutation.publication &&
                    !isImmutablePublicationGraphExtension(existing, mutation.publication)
                ) {
                    throw ContentTransactionException.CommitConflict(
                        "publication:${mutation.publicationKey.value}",
                    )
                }
            }
        }
        batch.quarantines.forEach { mutation ->
            findQuarantine(mutation.quarantineId)?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "quarantine:${mutation.quarantineId}",
                    )
                }
            }
        }
        batch.rightsGrants.forEach { mutation ->
            findRightsGrant(mutation.grantReference)?.let { existing ->
                if (existing != mutation.grant) {
                    throw ContentTransactionException.CommitConflict(
                        "rights:${mutation.grantReference.value}",
                    )
                }
            }
        }
        batch.blobSyncJobs.forEach { mutation ->
            findBlobSyncJob(mutation.jobId)?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "blob-sync-job:${mutation.jobId}",
                    )
                }
            }
        }
    }

    /** Reads and verifies one migration row, including every denormalized SQL column. */
    private fun findMigration(migrationKey: String): ContentMigrationLedgerMutation? {
        val row = queryRows(
            TABLE_MIGRATIONS,
            "namespace, source_digest, result_fingerprint, commit_id, mutation_json WHERE migration_key = '${sqlLiteral(migrationKey)}'",
        ).singleOrNull() ?: return null
        val existing = decodeJson(
            ContentMigrationLedgerMutation.serializer(),
            requireNotNull(row[4]),
        )
        check(existing.migrationKey == migrationKey) { "Migration key/body mismatch" }
        check(existing.namespace == requireNotNull(row[0])) { "Migration namespace mismatch" }
        check(existing.sourceDigestSha256 == requireNotNull(row[1])) {
            "Migration source digest mismatch"
        }
        check(existing.resultFingerprintSha256 == requireNotNull(row[2])) {
            "Migration result fingerprint mismatch"
        }
        check(existing.commitId == requireNotNull(row[3])) { "Migration commit id mismatch" }
        return existing
    }

    /** Reads a typed publication and verifies every normalized child row before returning it. */
    private fun findPublication(key: PublicationKey): Publication? {
        val row = queryRows(
            TABLE_PUBLICATIONS,
            "publication_json WHERE publication_id = '${sqlLiteral(key.value)}'",
        ).singleOrNull() ?: return null
        val publication = decodeJson(Publication.serializer(), requireNotNull(row[0]))
        check(publication.key == key) { "Publication key/body mismatch" }

        val acquisitions = queryRows(
            TABLE_ACQUISITIONS,
            "acquisition_id, publication_id, acquisition_json " +
                "WHERE publication_id = '${sqlLiteral(key.value)}' ORDER BY acquisition_id",
        ).associate { acquisitionRow ->
            val acquisitionId = requireNotNull(acquisitionRow[0])
            check(requireNotNull(acquisitionRow[1]) == key.value) { "Acquisition publication scope mismatch" }
            val acquisition = decodeJson(Acquisition.serializer(), requireNotNull(acquisitionRow[2]))
            check(acquisition.id == acquisitionId) { "Acquisition key/body mismatch" }
            acquisitionId to acquisition
        }
        check(acquisitions == publication.acquisitions.associateBy(Acquisition::id)) {
            "Publication acquisition ledger mismatch"
        }
        val rights = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "acquisition_id, publication_id, grant_ref_json " +
                "WHERE publication_id = '${sqlLiteral(key.value)}' ORDER BY acquisition_id",
        ).associate { rightsRow ->
            val acquisitionId = requireNotNull(rightsRow[0])
            check(requireNotNull(rightsRow[1]) == key.value) { "Rights publication scope mismatch" }
            acquisitionId to decodeJson(RightsGrantRef.serializer(), requireNotNull(rightsRow[2]))
        }
        val expectedRights = publication.acquisitions.mapNotNull { acquisition ->
            acquisition.rightsGrantRef?.let { acquisition.id to it }
        }.toMap()
        check(rights == expectedRights) { "Publication acquisition-rights ledger mismatch" }

        val units = queryRows(
            TABLE_UNITS,
            "unit_id, publication_id, acquisition_id, unit_json " +
                "WHERE publication_id = '${sqlLiteral(key.value)}' ORDER BY unit_id",
        ).associate { unitRow ->
            val unitId = requireNotNull(unitRow[0])
            check(requireNotNull(unitRow[1]) == key.value) { "Unit publication scope mismatch" }
            val acquisitionId = requireNotNull(unitRow[2])
            val unit = decodeJson(PublicationUnit.serializer(), requireNotNull(unitRow[3]))
            check(unit.key.value == unitId && unit.key.publicationKey == key) { "Unit key/body mismatch" }
            check(acquisitions[acquisitionId]?.units?.any { it == unit } == true) {
                "Unit acquisition scope mismatch"
            }
            unitId to unit
        }
        check(units == publication.acquisitions.flatMap(Acquisition::units).associateBy { it.key.value }) {
            "Publication unit ledger mismatch"
        }

        val workLinks = queryRows(
            TABLE_WORK_LINKS,
            "ordinal, target_publication_id, link_json " +
                "WHERE publication_id = '${sqlLiteral(key.value)}' ORDER BY ordinal",
        ).mapIndexed { expectedOrdinal, linkRow ->
            check(requireNotNull(linkRow[0]).toIntOrNull() == expectedOrdinal) {
                "Publication work-link ordinal mismatch"
            }
            val link = decodeJson(WorkLink.serializer(), requireNotNull(linkRow[2]))
            check(link.target.value == requireNotNull(linkRow[1])) { "Publication work-link target mismatch" }
            link
        }
        check(workLinks == publication.workLinks) { "Publication work-link ledger mismatch" }
        return publication
    }

    private fun findPublicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? {
        val row = queryRows(
            TABLE_PUBLICATION_REPLICA_CURSORS,
            "instance_id, workspace_id, through_workspace_seq, present, graph_fingerprint, " +
                "cursor_json WHERE publication_id = '${sqlLiteral(publicationKey.value)}'",
        ).singleOrNull() ?: return null
        val cursor = decodeJson(
            ContentPublicationReplicaCursor.serializer(),
            requireNotNull(row[5]),
        )
        check(cursor.publicationKey == publicationKey) { "Replica cursor publication key/body mismatch" }
        check(cursor.instanceId == requireNotNull(row[0])) { "Replica cursor instance mismatch" }
        check(cursor.workspaceId == requireNotNull(row[1])) { "Replica cursor workspace mismatch" }
        check(cursor.throughWorkspaceSeq == requireNotNull(row[2]).toLongOrNull()) {
            "Replica cursor sequence mismatch"
        }
        check(cursor.present == (requireNotNull(row[3]).toLongOrNull() == 1L)) {
            "Replica cursor presence mismatch"
        }
        check(cursor.graphFingerprintSha256 == requireNotNull(row[4])) {
            "Replica cursor fingerprint mismatch"
        }
        return cursor
    }

    private fun findBlobRemovalIntent(intentId: String): ContentBlobRemovalIntent? {
        val row = queryRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "publication_id, through_workspace_seq, reason, intent_json " +
                "WHERE intent_id = '${sqlLiteral(intentId)}'",
        ).singleOrNull() ?: return null
        val intent = decodeJson(ContentBlobRemovalIntent.serializer(), requireNotNull(row[3]))
        check(intent.intentId == intentId) { "Blob removal intent key/body mismatch" }
        check(intent.publicationKey.value == requireNotNull(row[0])) {
            "Blob removal intent publication mismatch"
        }
        check(intent.sourceCursor.throughWorkspaceSeq == requireNotNull(row[1]).toLongOrNull()) {
            "Blob removal intent sequence mismatch"
        }
        check(intent.reason.name == requireNotNull(row[2])) { "Blob removal intent reason mismatch" }
        return intent
    }

    private fun loadPublicationAttachments(publicationKey: PublicationKey): List<ManifestAttachment> {
        publicationKey.validate()
        val ownerPrefix = "${sqlLiteral(publicationKey.value)}/%"
        return queryRows(
            TABLE_ATTACHMENTS,
            "attachment_key WHERE owner_scope LIKE '$ownerPrefix' ORDER BY attachment_key",
        ).map { row -> loadAttachment(requireNotNull(row[0])) }
    }

    private fun validateReplicaReplay(batch: ContentCommitBatch<D>) {
        val replacement = requireNotNull(batch.replicaReplacement).replacement
        val durablePublication = findPublication(replacement.publicationKey)
        val durableAttachments = loadPublicationAttachments(replacement.publicationKey)
        val durableRights = queryRows(
            TABLE_RIGHTS_GRANTS,
            "grant_id WHERE publication_id = '${sqlLiteral(replacement.publicationKey.value)}' " +
                "ORDER BY grant_id",
        ).associate { row ->
            val grant = requireNotNull(findRightsGrant(RightsGrantRef(requireNotNull(row[0]))))
            grant.grantId to grant
        }
        if (replacement.present) {
            if (durablePublication != batch.publications.single().publication ||
                durableAttachments != batch.attachments.sortedBy(ManifestAttachment::attachmentKey) ||
                durableRights != batch.rightsGrants.associate { it.grantReference to it.grant }
            ) {
                throw ContentTransactionException.CommitConflict(
                    "replica-state:${replacement.publicationKey.value}",
                )
            }
        } else if (durablePublication != null || durableAttachments.isNotEmpty() || durableRights.isNotEmpty()) {
            throw ContentTransactionException.CommitConflict(
                "replica-tombstone:${replacement.publicationKey.value}",
            )
        }
    }

    private fun validatePublicationAttachments(batch: ContentCommitBatch<D>) {
        validateTypedPublicationAttachments(
            publications = batch.publications,
            suppliedAttachments = batch.attachments,
        ) { expected -> findAttachment(expected.attachmentKey) }
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
        }
    }

    private fun validateRightsGrants(batch: ContentCommitBatch<D>) {
        if (batch.rightsGrants.isEmpty()) return
        val publicationByKey = queryRows(TABLE_PUBLICATIONS, "publication_id ORDER BY publication_id")
            .associateTo(linkedMapOf()) { row ->
                val key = PublicationKey(requireNotNull(row[0]))
                key to requireNotNull(findPublication(key))
            }
        batch.publications.forEach { publicationByKey[it.publicationKey] = it.publication }
        val acquisitions = publicationByKey.values.flatMap { publication ->
            publication.acquisitions.map { acquisition -> publication.key to acquisition }
        }
        batch.rightsGrants.forEach { mutation ->
            val exactScope = acquisitions.singleOrNull { (publicationKey, acquisition) ->
                acquisition.rightsGrantRef == mutation.grantReference &&
                    publicationKey == mutation.grant.scope.publicationId &&
                    acquisition.id == mutation.grant.scope.acquisitionId
            }
            if (exactScope == null) {
                throw ContentTransactionException.DuplicateEntry(
                    "Rights grant ${mutation.grantReference.value} has no exact acquisition scope",
                )
            }
        }
    }

    private fun validateBlobSyncJobs(batch: ContentCommitBatch<D>) {
        batch.blobSyncJobs.forEach { job ->
            val attachment = batch.attachments.singleOrNull {
                it.owner == job.owner &&
                    it.manifestId == job.manifestId &&
                    it.contentRevision == job.contentRevision
            } ?: findAttachment(blobSyncAttachmentKey(job))
            if (attachment == null || job.blob !in attachment.blobs) {
                throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no exact durable manifest attachment",
                )
            }
            val grant = batch.rightsGrants
                .singleOrNull { it.grantReference == job.grantReference }
                ?.grant
                ?: findRightsGrant(job.grantReference)
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no durable rights grant",
                )
            if (!grant.scope.covers(job.accessScope) || ContentOperation.SYNC_BLOB !in grant.allowedOperations) {
                throw ContentTransactionException.CommitConflict("blob-sync-rights:${job.jobId}")
            }
        }
    }

    private fun lookupMigrationLedgerLocked(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup {
        val requested = ContentMigrationLedgerMutation(
            namespace = namespace,
            sourceDigestSha256 = sourceDigestSha256,
            resultFingerprintSha256 = resultFingerprintSha256,
        )
        val existing = findMigration(requested.migrationKey)
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

    private fun writeAttachments(attachments: List<BlobAttachment>) {
        attachments.forEach { attachment ->
            val encodedManifest = encodeJson(
                ManifestAttachment.serializer(),
                ManifestAttachment(attachment.owner, attachment.manifest.deepImmutableSnapshot()),
            )
            require(encodedManifest.length <= MAX_SQL_PAYLOAD_LENGTH) { "Manifest payload is too large" }
            execute(
                """
                    INSERT INTO $TABLE_ATTACHMENTS(
                      attachment_key, owner_scope, manifest_id, content_revision, manifest_json
                    ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                5,
            ) {
                bindString(0, attachment.attachmentKey)
                bindString(1, attachment.owner.scopeKey)
                bindString(2, attachment.manifestId)
                bindLong(3, attachment.contentRevision)
                bindString(4, encodedManifest)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
            attachment.blobs.forEach { reference ->
                execute(
                    "INSERT INTO $TABLE_ATTACHMENT_REFS(attachment_key, blob_id, reference_json) VALUES (?, ?, ?)",
                    3,
                ) {
                    bindString(0, attachment.attachmentKey)
                    bindString(1, reference.blobId)
                    bindString(2, encodeJson(BlobRef.serializer(), reference))
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
            }
        }
    }

    /**
     * Deletes only the authority represented by Backup v2. Migration aliases, ledgers,
     * quarantines and their auxiliary blobs are intentionally device-local and survive.
     * The caller owns the enclosing SQLite and blob rollback boundaries.
     */
    private fun deletePortableGraphRows(replacementCommitId: String) {
        execute("DELETE FROM $TABLE_BLOB_SYNC_JOBS", 0)
        execute("DELETE FROM $TABLE_RIGHTS_GRANTS", 0)
        execute("DELETE FROM $TABLE_ACQUISITION_RIGHTS", 0)
        execute("DELETE FROM $TABLE_UNITS", 0)
        execute("DELETE FROM $TABLE_WORK_LINKS", 0)
        execute("DELETE FROM $TABLE_ACQUISITIONS", 0)
        execute("DELETE FROM $TABLE_PUBLICATIONS", 0)
        execute("DELETE FROM $TABLE_ATTACHMENT_REFS", 0)
        execute("DELETE FROM $TABLE_ATTACHMENTS", 0)
        execute("DELETE FROM $TABLE_OUTBOX", 0)
        execute("DELETE FROM $TABLE_PUBLICATION_REPLICA_CURSORS", 0)
        execute("DELETE FROM $TABLE_BLOB_REMOVAL_INTENTS", 0)
        execute("DELETE FROM $TABLE_COMMITS WHERE commit_id LIKE ?", 1) {
            bindString(0, "$CONTENT_REPLICA_COMMIT_ID_PREFIX%")
        }
        execute("DELETE FROM $TABLE_COMMITS WHERE commit_id = ?", 1) {
            bindString(0, replacementCommitId)
        }
    }

    /** Deletes exactly one publication's local projection; replica cursor/intent rows survive. */
    private fun deletePublicationReplicaRows(publicationKey: PublicationKey) {
        val ownerPrefix = "${publicationKey.value}/%"
        execute(
            "DELETE FROM $TABLE_BLOB_SYNC_JOBS WHERE attachment_key IN (" +
                "SELECT attachment_key FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ?)",
            1,
        ) { bindString(0, ownerPrefix) }
        execute(
            "DELETE FROM $TABLE_RIGHTS_GRANTS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ACQUISITION_RIGHTS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_UNITS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_WORK_LINKS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ACQUISITIONS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_PUBLICATIONS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ATTACHMENT_REFS WHERE attachment_key IN (" +
                "SELECT attachment_key FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ?)",
            1,
        ) { bindString(0, ownerPrefix) }
        execute(
            "DELETE FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ?",
            1,
        ) { bindString(0, ownerPrefix) }
    }

    private fun writePublicationReplicaCursor(cursor: ContentPublicationReplicaCursor) {
        execute(
            """
                INSERT INTO $TABLE_PUBLICATION_REPLICA_CURSORS(
                  publication_id, instance_id, workspace_id, through_workspace_seq,
                  present, graph_fingerprint, cursor_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(publication_id) DO UPDATE SET
                  instance_id = excluded.instance_id,
                  workspace_id = excluded.workspace_id,
                  through_workspace_seq = excluded.through_workspace_seq,
                  present = excluded.present,
                  graph_fingerprint = excluded.graph_fingerprint,
                  cursor_json = excluded.cursor_json
            """.trimIndent(),
            7,
        ) {
            bindString(0, cursor.publicationKey.value)
            bindString(1, cursor.instanceId)
            bindString(2, cursor.workspaceId)
            bindLong(3, cursor.throughWorkspaceSeq)
            bindLong(4, if (cursor.present) 1L else 0L)
            bindString(5, cursor.graphFingerprintSha256)
            bindString(6, encodeJson(ContentPublicationReplicaCursor.serializer(), cursor))
        }
    }

    private fun writeBlobRemovalIntent(intent: ContentBlobRemovalIntent) {
        findBlobRemovalIntent(intent.intentId)?.let { existing ->
            if (existing != intent) throw ContentTransactionException.CommitConflict(intent.intentId)
            return
        }
        execute(
            """
                INSERT INTO $TABLE_BLOB_REMOVAL_INTENTS(
                  intent_id, publication_id, through_workspace_seq, reason, intent_json
                ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            5,
        ) {
            bindString(0, intent.intentId)
            bindString(1, intent.publicationKey.value)
            bindLong(2, intent.sourceCursor.throughWorkspaceSeq)
            bindString(3, intent.reason.name)
            bindString(4, encodeJson(ContentBlobRemovalIntent.serializer(), intent))
        }
    }

    private fun writeAuxiliaryAttachments(attachments: List<AuxiliaryBlobAttachment>) {
        attachments.forEach { attachment ->
            val encoded = encodeJson(AuxiliaryBlobAttachment.serializer(), attachment)
            execute(
                """
                    INSERT INTO $TABLE_AUXILIARY_ATTACHMENTS(
                      attachment_key, owner_id, purpose, attachment_json
                    ) VALUES (?, ?, ?, ?)
                """.trimIndent(),
                4,
            ) {
                bindString(0, attachment.attachmentKey)
                bindString(1, attachment.ownerId)
                bindString(2, attachment.purpose.name)
                bindString(3, encoded)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
            attachment.blobs.forEach { reference ->
                execute(
                    "INSERT INTO $TABLE_AUXILIARY_ATTACHMENT_REFS(" +
                        "attachment_key, blob_id, reference_json) VALUES (?, ?, ?)",
                    3,
                ) {
                    bindString(0, attachment.attachmentKey)
                    bindString(1, reference.blobId)
                    bindString(2, encodeJson(BlobRef.serializer(), reference))
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
            }
        }
    }

    private fun writeMetadata(mutations: List<ContentMetadataMutation>) {
        mutations.forEach { mutation ->
            execute(
                "INSERT INTO $TABLE_METADATA(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                2,
            ) {
                bindString(0, mutation.key)
                bindString(1, mutation.value)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_METADATA_WRITE)
        }
    }

    private fun writeAliases(mutations: List<ContentAliasMutation>) {
        mutations.forEach { mutation ->
            execute(
                "INSERT INTO $TABLE_ALIASES(alias, target) VALUES (?, ?) ON CONFLICT(alias) DO UPDATE SET target = excluded.target",
                2,
            ) {
                bindString(0, mutation.alias)
                bindString(1, mutation.target)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_ALIAS_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_ALIAS_WRITE)
        }
    }

    private fun writeMigrations(mutations: List<ContentMigrationLedgerMutation>) {
        mutations.forEach { mutation ->
            execute(
                """
                    INSERT INTO $TABLE_MIGRATIONS(
                      migration_key, namespace, source_digest, result_fingerprint, commit_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent() + " ON CONFLICT(migration_key) DO NOTHING",
                6,
            ) {
                bindString(0, mutation.migrationKey)
                bindString(1, mutation.namespace)
                bindString(2, mutation.sourceDigestSha256)
                bindString(3, mutation.resultFingerprintSha256)
                bindString(4, mutation.commitId)
                bindString(5, encodeJson(ContentMigrationLedgerMutation.serializer(), mutation))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_MIGRATION_WRITE)
        }
    }

    private fun writeOutbox(drafts: List<D>) {
        drafts.forEach { draft ->
            val id = outboxAdapter.id(draft)
            val payload = outboxAdapter.encode(draft)
            execute(
                "INSERT INTO $TABLE_OUTBOX(draft_id, payload) VALUES (?, ?) ON CONFLICT(draft_id) DO NOTHING",
                2,
            ) {
                bindString(0, id)
                bindString(1, payload)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_OUTBOX_WRITE)
        }
    }

    private fun writePublications(mutations: List<ContentPublicationMutation>) {
        mutations.forEach { mutation ->
            val publication = mutation.publication
            val durable = findPublication(mutation.publicationKey)
            if (durable == publication) return@forEach
            if (durable == null) {
                execute(
                    "INSERT INTO $TABLE_PUBLICATIONS(publication_id, publication_json) VALUES (?, ?)",
                    2,
                ) {
                    bindString(0, publication.key.value)
                    bindString(1, encodeJson(Publication.serializer(), publication))
                }
            } else {
                execute(
                    "UPDATE $TABLE_PUBLICATIONS SET publication_json = ? WHERE publication_id = ?",
                    2,
                ) {
                    bindString(0, encodeJson(Publication.serializer(), publication))
                    bindString(1, publication.key.value)
                }
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_PUBLICATION_WRITE)
            val durableAcquisitions = durable?.acquisitions?.associateBy(Acquisition::id).orEmpty()
            publication.acquisitions.forEach { acquisition ->
                val durableAcquisition = durableAcquisitions[acquisition.id]
                if (durableAcquisition == null) {
                    execute(
                        """
                            INSERT INTO $TABLE_ACQUISITIONS(
                              acquisition_id, publication_id, acquisition_json
                            ) VALUES (?, ?, ?)
                        """.trimIndent(),
                        3,
                    ) {
                        bindString(0, acquisition.id)
                        bindString(1, publication.key.value)
                        bindString(2, encodeJson(Acquisition.serializer(), acquisition))
                    }
                } else if (durableAcquisition != acquisition) {
                    execute(
                        """
                            UPDATE $TABLE_ACQUISITIONS SET acquisition_json = ?
                            WHERE acquisition_id = ? AND publication_id = ?
                        """.trimIndent(),
                        3,
                    ) {
                        bindString(0, encodeJson(Acquisition.serializer(), acquisition))
                        bindString(1, acquisition.id)
                        bindString(2, publication.key.value)
                    }
                }
                acquisition.rightsGrantRef?.let { rights ->
                    if (durableAcquisition?.rightsGrantRef == null) {
                        execute(
                            """
                                INSERT INTO $TABLE_ACQUISITION_RIGHTS(
                                  acquisition_id, publication_id, grant_ref_json
                                ) VALUES (?, ?, ?)
                            """.trimIndent(),
                            3,
                        ) {
                            bindString(0, acquisition.id)
                            bindString(1, publication.key.value)
                            bindString(2, encodeJson(RightsGrantRef.serializer(), rights))
                        }
                    }
                }
                val durableUnits = durableAcquisition?.units?.associateBy { it.key }.orEmpty()
                acquisition.units.forEach { unit ->
                    val durableUnit = durableUnits[unit.key]
                    if (durableUnit == null) {
                        execute(
                            """
                                INSERT INTO $TABLE_UNITS(
                                  unit_id, publication_id, acquisition_id, unit_json
                                ) VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                            4,
                        ) {
                            bindString(0, unit.key.value)
                            bindString(1, publication.key.value)
                            bindString(2, acquisition.id)
                            bindString(3, encodeJson(PublicationUnit.serializer(), unit))
                        }
                    } else if (durableUnit != unit) {
                        execute(
                            """
                                UPDATE $TABLE_UNITS SET unit_json = ?
                                WHERE unit_id = ? AND publication_id = ? AND acquisition_id = ?
                            """.trimIndent(),
                            4,
                        ) {
                            bindString(0, encodeJson(PublicationUnit.serializer(), unit))
                            bindString(1, unit.key.value)
                            bindString(2, publication.key.value)
                            bindString(3, acquisition.id)
                        }
                    }
                }
            }
            if (durable == null) publication.workLinks.forEachIndexed { index, link ->
                execute(
                    """
                        INSERT INTO $TABLE_WORK_LINKS(
                          publication_id, ordinal, target_publication_id, link_json
                        ) VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    4,
                ) {
                    bindString(0, publication.key.value)
                    bindLong(1, index.toLong())
                    bindString(2, link.target.value)
                    bindString(3, encodeJson(WorkLink.serializer(), link))
                }
            }
        }
    }

    private fun writeQuarantines(mutations: List<ContentQuarantineMutation>) {
        mutations.forEach { mutation ->
            if (findQuarantine(mutation.quarantineId) != null) return@forEach
            val auxiliaryAttachmentKey = AuxiliaryBlobAttachment(
                ownerId = mutation.auxiliaryOwnerId,
                purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                blobs = listOf(mutation.scriptBlob),
            ).attachmentKey
            execute(
                """
                    INSERT INTO $TABLE_QUARANTINES(
                      quarantine_id, auxiliary_attachment_key, package_id, version, origin,
                      ordinal, script_blob_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                8,
            ) {
                bindString(0, mutation.quarantineId)
                bindString(1, auxiliaryAttachmentKey)
                bindString(2, mutation.packageId)
                bindString(3, mutation.version)
                bindString(4, mutation.origin)
                bindLong(5, mutation.ordinal.toLong())
                bindString(6, mutation.scriptBlob.blobId)
                bindString(7, encodeJson(ContentQuarantineMutation.serializer(), mutation))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_QUARANTINE_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_QUARANTINE_WRITE)
        }
    }

    private fun writeRightsGrants(mutations: List<ContentRightsGrantMutation>) {
        mutations.forEach { mutation ->
            if (findRightsGrant(mutation.grantReference) != null) return@forEach
            val grant = mutation.grant
            execute(
                """
                    INSERT INTO $TABLE_RIGHTS_GRANTS(
                      grant_id, publication_id, acquisition_id, unit_id, manifest_id,
                      content_revision, grant_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                7,
            ) {
                bindString(0, grant.grantId.value)
                bindString(1, grant.scope.publicationId.value)
                bindString(2, grant.scope.acquisitionId)
                bindString(3, grant.scope.unitId?.value)
                bindString(4, grant.scope.manifestId)
                bindLong(5, grant.scope.contentRevision)
                bindString(6, encodeJson(RightsGrant.serializer(), grant))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_RIGHTS_GRANT_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_RIGHTS_GRANT_WRITE)
        }
    }

    private fun writeBlobSyncJobs(mutations: List<ContentBlobSyncJobMutation>) {
        mutations.forEach { mutation ->
            if (findBlobSyncJob(mutation.jobId) != null) return@forEach
            execute(
                """
                    INSERT INTO $TABLE_BLOB_SYNC_JOBS(
                      job_id, attachment_key, blob_id, owner_scope, manifest_id,
                      content_revision, grant_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                8,
            ) {
                bindString(0, mutation.jobId)
                bindString(1, blobSyncAttachmentKey(mutation))
                bindString(2, mutation.blob.blobId)
                bindString(3, mutation.owner.scopeKey)
                bindString(4, mutation.manifestId)
                bindLong(5, mutation.contentRevision)
                bindString(6, mutation.grantReference.value)
                bindString(7, encodeJson(ContentBlobSyncJobMutation.serializer(), mutation))
            }
        }
    }

    private fun insertCommit(
        commitId: String,
        fingerprint: String,
        result: ContentCommitResult,
        receipts: List<BlobPublishReceipt>,
    ) {
        val encodedResult = encodeJson(PersistedCommitResult.serializer(), PersistedCommitResult.from(result))
        val encodedReceipts = encodeJson(
            PersistedReceiptDescriptorList.serializer(),
            PersistedReceiptDescriptorList(receipts.map(PersistedReceiptDescriptor::from)),
        )
        execute(
            "INSERT INTO $TABLE_COMMITS(commit_id, fingerprint, result_json, receipt_descriptors_json) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, commitId)
            bindString(1, fingerprint)
            bindString(2, encodedResult)
            bindString(3, encodedReceipts)
        }
    }

    private fun readState(): SqlContentTransactionState<D> {
        val metadata = readPairs(TABLE_METADATA, "key", "value")
        val aliases = readPairs(TABLE_ALIASES, "alias", "target")
        val migrations = loadAllMigrations().associateByTo(
            linkedMapOf(),
            ContentMigrationLedgerMutation::migrationKey,
        )
        val attachments = immutableListOf(loadAllAttachments())
        val auxiliaryAttachments = immutableListOf(loadAllAuxiliaryAttachments())
        val outbox = queryRows(TABLE_OUTBOX, "draft_id, payload ORDER BY draft_id").map { row ->
            val id = requireNotNull(row[0])
            val draft = decodeOutbox(requireNotNull(row[1]))
            outboxAdapter.validate(draft)
            check(outboxAdapter.id(draft) == id) { "Outbox key/body mismatch" }
            draft
        }
        val committedIds = queryRows(TABLE_COMMITS, "commit_id ORDER BY commit_id")
            .mapTo(linkedSetOf()) { requireNotNull(it[0]) }
        val publications = loadAllPublications().associateBy(Publication::key)
        val quarantines = queryRows(TABLE_QUARANTINES, "quarantine_id ORDER BY quarantine_id")
            .associate { row ->
                val id = requireNotNull(row[0])
                id to requireNotNull(findQuarantine(id))
            }
        val rightsGrants = loadAllRightsGrants().associateBy(RightsGrant::grantId)
        val blobSyncJobs = queryRows(TABLE_BLOB_SYNC_JOBS, "job_id ORDER BY job_id")
            .associate { row ->
                val jobId = requireNotNull(row[0])
                jobId to requireNotNull(findBlobSyncJob(jobId))
            }
        val publicationReplicaCursors = queryRows(
            TABLE_PUBLICATION_REPLICA_CURSORS,
            "publication_id ORDER BY publication_id",
        ).associate { row ->
            val publicationKey = PublicationKey(requireNotNull(row[0]))
            publicationKey to requireNotNull(findPublicationReplicaCursor(publicationKey))
        }
        val blobRemovalIntents = queryRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "intent_id ORDER BY intent_id",
        ).associate { row ->
            val intentId = requireNotNull(row[0])
            intentId to requireNotNull(findBlobRemovalIntent(intentId))
        }
        return SqlContentTransactionState(
            metadata = metadata,
            aliases = aliases,
            migrations = migrations,
            attachments = attachments,
            outbox = outbox,
            committedIds = committedIds,
            publications = publications,
            auxiliaryAttachments = auxiliaryAttachments,
            quarantines = quarantines,
            rightsGrants = rightsGrants,
            blobSyncJobs = blobSyncJobs,
            publicationReplicaCursors = publicationReplicaCursors,
            blobRemovalIntents = blobRemovalIntents,
        )
    }

    private fun loadAllAttachments(): List<ManifestAttachment> =
        queryRows(TABLE_ATTACHMENTS, "attachment_key ORDER BY attachment_key")
            .map { row -> loadAttachment(requireNotNull(row[0])) }

    private fun loadAllPublications(): List<Publication> =
        queryRows(TABLE_PUBLICATIONS, "publication_id ORDER BY publication_id")
            .map { row -> requireNotNull(findPublication(PublicationKey(requireNotNull(row[0])))) }

    private fun loadAllRightsGrants(): List<RightsGrant> = loadRightsGrantRows(
        rightsRows = queryRows(TABLE_RIGHTS_GRANTS, "$RIGHTS_GRANT_COLUMNS ORDER BY grant_id"),
        acquisitionRightsRows = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "publication_id, acquisition_id, grant_ref_json ORDER BY publication_id, acquisition_id",
        ),
    )

    private fun loadRightsGrantsForPublication(publicationKey: PublicationKey): List<RightsGrant> {
        val publication = sqlLiteral(publicationKey.value)
        return loadRightsGrantRows(
            rightsRows = queryRows(
                TABLE_RIGHTS_GRANTS,
                "$RIGHTS_GRANT_COLUMNS WHERE publication_id = '$publication' ORDER BY grant_id",
            ),
            acquisitionRightsRows = queryRows(
                TABLE_ACQUISITION_RIGHTS,
                "publication_id, acquisition_id, grant_ref_json " +
                    "WHERE publication_id = '$publication' ORDER BY acquisition_id",
            ),
        )
    }

    private fun loadRightsGrantRows(
        rightsRows: List<List<String?>>,
        acquisitionRightsRows: List<List<String?>>,
    ): List<RightsGrant> {
        val acquisitionRights = acquisitionRightsRows.associate { row ->
            val key = AcquisitionRightsKey(
                publicationId = requireNotNull(row[0]),
                acquisitionId = requireNotNull(row[1]),
            )
            key to decodeJson(RightsGrantRef.serializer(), requireNotNull(row[2]))
        }
        return rightsRows.map { row ->
            val key = AcquisitionRightsKey(
                publicationId = requireNotNull(row[1]),
                acquisitionId = requireNotNull(row[2]),
            )
            decodeRightsGrantRow(row, acquisitionRights[key])
        }
    }

    private data class AcquisitionRightsKey(
        val publicationId: String,
        val acquisitionId: String,
    )

    private fun loadAllAuxiliaryAttachments(): List<AuxiliaryBlobAttachment> =
        queryRows(TABLE_AUXILIARY_ATTACHMENTS, "attachment_key ORDER BY attachment_key")
            .map { row -> loadAuxiliaryAttachment(requireNotNull(row[0])) }

    private data class PersistedCommit(
        val fingerprint: String,
        val result: ContentCommitResult,
        val receiptDescriptors: List<PersistedReceiptDescriptor>,
    )

    private data class ReplicaAuthorityDepartureMutation(
        val result: ContentReplicaAuthorityDepartureResult,
        val removedCommitIds: Set<String>,
    )

    private fun findCommit(commitId: String): PersistedCommit? {
        val row = queryRows(
            TABLE_COMMITS,
            "fingerprint, result_json, receipt_descriptors_json WHERE commit_id = '${sqlLiteral(commitId)}'",
        ).singleOrNull() ?: return null
        val result = PersistedCommitResult.toRuntime(
            decodeJson(PersistedCommitResult.serializer(), requireNotNull(row[1])),
        )
        check(result.commitId == commitId) { "Commit journal result/id mismatch" }
        val descriptors = decodeJson(
            PersistedReceiptDescriptorList.serializer(),
            requireNotNull(row[2]),
        ).receipts.onEach(::validateReceiptDescriptor)
        check(descriptors.map(PersistedReceiptDescriptor::commitToken).distinct().size == descriptors.size) {
            "Duplicate receipt descriptor token in commit journal"
        }
        check(descriptors.map { it.reference.blobId }.distinct().size == descriptors.size) {
            "Duplicate receipt descriptor blob id in commit journal"
        }
        val journalFingerprint = requireNotNull(row[0])
        check(SHA256_HEX.matches(journalFingerprint)) { "Commit journal fingerprint is invalid" }
        return PersistedCommit(journalFingerprint, result, descriptors)
    }

    private fun findAttachment(key: String): ManifestAttachment? {
        if (queryRows(TABLE_ATTACHMENTS, "attachment_key WHERE attachment_key = '${sqlLiteral(key)}'").isEmpty()) {
            return null
        }
        return loadAttachment(key)
    }

    private fun findAuxiliaryAttachment(key: String): AuxiliaryBlobAttachment? {
        if (queryRows(
                TABLE_AUXILIARY_ATTACHMENTS,
                "attachment_key WHERE attachment_key = '${sqlLiteral(key)}'",
            ).isEmpty()
        ) {
            return null
        }
        return loadAuxiliaryAttachment(key)
    }

    /** Loads and verifies the manifest row and its normalized blob-ref ledger as one unit. */
    private fun loadAttachment(key: String): ManifestAttachment {
        val row = queryRows(
            TABLE_ATTACHMENTS,
            "owner_scope, manifest_id, content_revision, manifest_json WHERE attachment_key = '${sqlLiteral(key)}'",
        ).singleOrNull() ?: error("Missing manifest attachment row $key")
        val ownerScope = requireNotNull(row[0])
        val manifestId = requireNotNull(row[1])
        val revision = requireNotNull(row[2]).toLongOrNull()
            ?: error("Invalid manifest content revision for $key")
        val attachment = decodeJson(ManifestAttachment.serializer(), requireNotNull(row[3]))
        check(attachment.attachmentKey == key) { "Attachment key/body mismatch" }
        check(attachment.owner.scopeKey == ownerScope) { "Attachment owner scope mismatch" }
        check(attachment.manifestId == manifestId) { "Attachment manifest id mismatch" }
        check(attachment.contentRevision == revision) { "Attachment revision mismatch" }

        val persistedRefs = queryRows(
            TABLE_ATTACHMENT_REFS,
            "blob_id, reference_json WHERE attachment_key = '${sqlLiteral(key)}' ORDER BY blob_id",
        ).map { refRow ->
            val blobId = requireNotNull(refRow[0])
            val reference = decodeJson(BlobRef.serializer(), requireNotNull(refRow[1]))
            check(reference.blobId == blobId) { "Attachment blob-ref key/body mismatch" }
            blobId to reference
        }.toMap()
        val expectedRefs = attachment.blobs.associateBy(BlobRef::blobId)
        check(persistedRefs.size == expectedRefs.size && persistedRefs == expectedRefs) {
            "Attachment blob-ref ledger mismatch"
        }
        return ManifestAttachment(attachment.owner, attachment.manifest.deepImmutableSnapshot())
    }

    /** Loads and verifies an auxiliary row and its normalized blob-ref ledger as one unit. */
    private fun loadAuxiliaryAttachment(key: String): AuxiliaryBlobAttachment {
        val row = queryRows(
            TABLE_AUXILIARY_ATTACHMENTS,
            "owner_id, purpose, attachment_json WHERE attachment_key = '${sqlLiteral(key)}'",
        ).singleOrNull() ?: error("Missing auxiliary attachment row $key")
        val attachment = decodeJson(AuxiliaryBlobAttachment.serializer(), requireNotNull(row[2]))
        check(attachment.attachmentKey == key) { "Auxiliary attachment key/body mismatch" }
        check(attachment.ownerId == requireNotNull(row[0])) { "Auxiliary attachment owner mismatch" }
        check(attachment.purpose.name == requireNotNull(row[1])) { "Auxiliary attachment purpose mismatch" }

        val persistedRefs = queryRows(
            TABLE_AUXILIARY_ATTACHMENT_REFS,
            "blob_id, reference_json WHERE attachment_key = '${sqlLiteral(key)}' ORDER BY blob_id",
        ).map { refRow ->
            val blobId = requireNotNull(refRow[0])
            val reference = decodeJson(BlobRef.serializer(), requireNotNull(refRow[1]))
            check(reference.blobId == blobId) { "Auxiliary blob-ref key/body mismatch" }
            blobId to reference
        }.toMap()
        val expectedRefs = attachment.blobs.associateBy(BlobRef::blobId)
        check(persistedRefs.size == expectedRefs.size && persistedRefs == expectedRefs) {
            "Auxiliary blob-ref ledger mismatch"
        }
        return attachment.deepImmutableSnapshot()
    }

    private fun findQuarantine(quarantineId: String): ContentQuarantineMutation? {
        val row = queryRows(
            TABLE_QUARANTINES,
            "auxiliary_attachment_key, package_id, version, origin, ordinal, script_blob_id, " +
                "mutation_json WHERE quarantine_id = '${sqlLiteral(quarantineId)}'",
        ).singleOrNull() ?: return null
        val mutation = decodeJson(ContentQuarantineMutation.serializer(), requireNotNull(row[6]))
        check(mutation.quarantineId == quarantineId) { "Quarantine key/body mismatch" }
        check(mutation.packageId == requireNotNull(row[1])) { "Quarantine package id mismatch" }
        check(mutation.version == requireNotNull(row[2])) { "Quarantine version mismatch" }
        check(mutation.origin == requireNotNull(row[3])) { "Quarantine origin mismatch" }
        check(mutation.ordinal == requireNotNull(row[4]).toIntOrNull()) { "Quarantine ordinal mismatch" }
        check(mutation.scriptBlob.blobId == requireNotNull(row[5])) { "Quarantine script blob mismatch" }
        val attachmentKey = requireNotNull(row[0])
        val attachment = requireNotNull(findAuxiliaryAttachment(attachmentKey)) {
            "Quarantine auxiliary attachment is missing"
        }
        check(attachment.ownerId == mutation.auxiliaryOwnerId &&
            attachment.purpose == AuxiliaryBlobPurpose.PLUGIN_QUARANTINE &&
            attachment.blobs == listOf(mutation.scriptBlob)
        ) {
            "Quarantine auxiliary attachment mismatch"
        }
        return mutation.deepImmutableSnapshot()
    }

    private fun findRightsGrant(reference: RightsGrantRef): RightsGrant? {
        val row = queryRows(
            TABLE_RIGHTS_GRANTS,
            "$RIGHTS_GRANT_COLUMNS " +
                "WHERE grant_id = '${sqlLiteral(reference.value)}'",
        ).singleOrNull() ?: return null
        check(requireNotNull(row[0]) == reference.value) { "Rights grant lookup key mismatch" }
        val publicationId = requireNotNull(row[1])
        val acquisitionId = requireNotNull(row[2])
        val pairedReference = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "grant_ref_json WHERE publication_id = '${sqlLiteral(publicationId)}' " +
                "AND acquisition_id = '${sqlLiteral(acquisitionId)}'",
        ).singleOrNull()?.let { pairing ->
            decodeJson(RightsGrantRef.serializer(), requireNotNull(pairing[0]))
        }
        return decodeRightsGrantRow(row, pairedReference)
    }

    private fun decodeRightsGrantRow(
        row: List<String?>,
        pairedReference: RightsGrantRef?,
    ): RightsGrant {
        val reference = RightsGrantRef(requireNotNull(row[0]))
        val grant = decodeJson(RightsGrant.serializer(), requireNotNull(row[6]))
        check(grant.grantId == reference) { "Rights grant key/body mismatch" }
        check(grant.scope.publicationId.value == requireNotNull(row[1])) {
            "Rights grant publication scope mismatch"
        }
        check(grant.scope.acquisitionId == requireNotNull(row[2])) {
            "Rights grant acquisition scope mismatch"
        }
        check(grant.scope.unitId?.value == row[3]) { "Rights grant unit scope mismatch" }
        check(grant.scope.manifestId == row[4]) { "Rights grant manifest scope mismatch" }
        check(grant.scope.contentRevision == row[5]?.toLongOrNull()) {
            "Rights grant content revision mismatch"
        }
        check(pairedReference == reference) {
            "Rights grant is not paired with its exact acquisition reference"
        }
        return ContentRightsGrantMutation(grant).deepImmutableSnapshot().grant
    }

    private fun findBlobSyncJob(jobId: String): ContentBlobSyncJobMutation? {
        val row = queryRows(
            TABLE_BLOB_SYNC_JOBS,
            "attachment_key, blob_id, owner_scope, manifest_id, content_revision, grant_id, " +
                "mutation_json WHERE job_id = '${sqlLiteral(jobId)}'",
        ).singleOrNull() ?: return null
        val mutation = decodeJson(ContentBlobSyncJobMutation.serializer(), requireNotNull(row[6]))
        check(mutation.jobId == jobId) { "Blob sync job key/body mismatch" }
        check(mutation.blob.blobId == requireNotNull(row[1])) { "Blob sync job blob mismatch" }
        check(mutation.owner.scopeKey == requireNotNull(row[2])) { "Blob sync job owner mismatch" }
        check(mutation.manifestId == requireNotNull(row[3])) { "Blob sync job manifest mismatch" }
        check(mutation.contentRevision == row[4]?.toLongOrNull()) { "Blob sync job revision mismatch" }
        check(mutation.grantReference.value == requireNotNull(row[5])) { "Blob sync job grant mismatch" }
        val attachment = requireNotNull(findAttachment(requireNotNull(row[0]))) {
            "Blob sync job manifest attachment is missing"
        }
        check(
            attachment.owner == mutation.owner &&
                attachment.manifestId == mutation.manifestId &&
                attachment.contentRevision == mutation.contentRevision &&
                mutation.blob in attachment.blobs,
        ) { "Blob sync job attachment mismatch" }
        val grant = requireNotNull(findRightsGrant(mutation.grantReference)) {
            "Blob sync job rights grant is missing"
        }
        check(grant.scope.covers(mutation.accessScope) && ContentOperation.SYNC_BLOB in grant.allowedOperations) {
            "Blob sync job is no longer authorized by its durable grant"
        }
        return mutation.copy(blob = mutation.blob.copy())
    }

    private fun validateReceiptDescriptor(descriptor: PersistedReceiptDescriptor) {
        requireSafeSqlKey(descriptor.storeInstanceId, "Receipt store instance id")
        requireSafeSqlKey(descriptor.commitToken, "Receipt commit token")
        descriptor.reference.validate()
        require(descriptor.incarnation > 0) { "Receipt incarnation must be positive" }
        require(descriptor.generation >= 0) { "Receipt generation must be non-negative" }
        require(descriptor.publishedAtEpochMillis >= 0) {
            "Receipt publication timestamp must be non-negative"
        }
    }

    private fun readPairs(table: String, keyColumn: String, valueColumn: String): Map<String, String> =
        queryRows(table, "$keyColumn, $valueColumn ORDER BY $keyColumn").associate { row ->
            requireNotNull(row[0]) to requireNotNull(row[1])
        }

    private fun loadAllMigrations(): List<ContentMigrationLedgerMutation> {
        val migrations = ArrayList<ContentMigrationLedgerMutation>()
        val keys = hashSetOf<String>()
        queryRows(
            TABLE_MIGRATIONS,
            "migration_key, namespace, source_digest, result_fingerprint, commit_id, mutation_json ORDER BY migration_key",
        ).forEach { row ->
            val key = requireNotNull(row[0])
            val mutation = decodeJson(
                ContentMigrationLedgerMutation.serializer(),
                requireNotNull(row[5]),
            )
            check(mutation.migrationKey == key) { "Migration key/body mismatch" }
            check(mutation.namespace == requireNotNull(row[1])) { "Migration namespace mismatch" }
            check(mutation.sourceDigestSha256 == requireNotNull(row[2])) {
                "Migration source digest mismatch"
            }
            check(mutation.resultFingerprintSha256 == requireNotNull(row[3])) {
                "Migration result fingerprint mismatch"
            }
            check(mutation.commitId == requireNotNull(row[4])) { "Migration commit id mismatch" }
            check(keys.add(key)) { "Duplicate migration key" }
            migrations += mutation
        }
        return migrations
    }

    private fun findString(table: String, keyColumn: String, key: String, valueColumn: String): String? =
        queryRows(table, "$valueColumn WHERE $keyColumn = '${sqlLiteral(key)}'").singleOrNull()?.get(0)

    private fun queryRows(table: String, projectionAndClause: String): List<List<String?>> {
        val projection = projectionAndClause
            .substringBefore(" WHERE ")
            .substringBefore(" ORDER BY ")
            .trim()
        val suffix = projectionAndClause.substring(projection.length)
        val sql = "SELECT $projection FROM $table$suffix"
        return driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val rows = mutableListOf<List<String?>>()
                val columnCount = projectionColumnCount(projectionAndClause)
                while (cursor.next().value) {
                    val values = ArrayList<String?>(columnCount)
                    repeat(columnCount) { index -> values += cursor.getString(index) }
                    rows += values
                }
                QueryResult.Value(rows)
            },
            parameters = 0,
        ).value
    }

    private fun execute(sql: String, parameters: Int, bind: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit = {}) {
        driver.execute(null, sql, parameters, bind).value
    }

    private fun acknowledgeRows(
        table: String,
        keyColumn: String,
        ids: Set<String>,
        label: String,
    ): Int {
        ids.forEach { requireSafeSqlKey(it, label) }
        if (ids.isEmpty()) return 0
        return transactions.transactionWithResult<Int>(false) {
            ids.sorted().count { id ->
                val exists = queryRows(
                    table,
                    "$keyColumn WHERE $keyColumn = '${sqlLiteral(id)}'",
                ).isNotEmpty()
                if (exists) {
                    execute("DELETE FROM $table WHERE $keyColumn = ?", 1) { bindString(0, id) }
                }
                exists
            }
        }
    }

    private fun <T> encodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        codec.encodeToString(serializer, value).also {
            require(it.length <= MAX_SQL_PAYLOAD_LENGTH) { "Content transaction payload is too large" }
        }

    private fun <T> decodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: String): T {
        require(value.length <= MAX_SQL_PAYLOAD_LENGTH) {
            "Content transaction payload is too large"
        }
        return codec.decodeFromString(serializer, value)
    }

    private fun decodeOutbox(value: String): D {
        require(value.length <= MAX_SQL_PAYLOAD_LENGTH) { "Outbox payload is too large" }
        return outboxAdapter.decode(value)
    }

    private fun fingerprint(batch: ContentCommitBatch<D>, authoritativeSyncMode: ContentSyncMode): String {
        val writer = SqlCanonicalWriter()
        writer.string(batch.commitId)
        writer.string(authoritativeSyncMode.name)
        writer.string(batch.unrepresentableDraftPolicy.name)
        writer.string(batch.semantics.name)
        writer.list(batch.receipts.sortedBy(BlobPublishReceipt::commitToken)) { receipt ->
            string(receipt.storeInstanceId)
            string(receipt.commitToken)
            blobRef(receipt.reference)
            long(receipt.incarnation)
            long(receipt.generation)
            long(receipt.publishedAtEpochMillis)
        }
        writer.list(batch.attachments.sortedBy(ManifestAttachment::attachmentKey)) { attachment ->
            string(attachment.owner.publicationKey.value)
            string(attachment.owner.acquisitionId)
            string(attachment.owner.unitKey.value)
            bytes(canonicalJsonBytes(codec.encodeToJsonElement(ContentManifest.serializer(), attachment.manifest)))
        }
        writer.list(batch.metadata.sortedBy(ContentMetadataMutation::key)) { string(it.key); string(it.value) }
        writer.list(batch.aliases.sortedBy(ContentAliasMutation::alias)) { string(it.alias); string(it.target) }
        writer.list(batch.migrations.sortedBy(ContentMigrationLedgerMutation::migrationKey)) { mutation ->
            string(mutation.namespace)
            string(mutation.sourceDigestSha256)
            string(mutation.resultFingerprintSha256)
        }
        writer.list(batch.publications.sortedBy { it.publicationKey.value }) { mutation ->
            bytes(canonicalJsonBytes(codec.encodeToJsonElement(Publication.serializer(), mutation.publication)))
        }
        writer.list(batch.auxiliaryAttachments.sortedBy(AuxiliaryBlobAttachment::attachmentKey)) { attachment ->
            string(attachment.ownerId)
            string(attachment.purpose.name)
            list(attachment.blobs.sortedBy(BlobRef::blobId)) { blobRef(it) }
        }
        writer.list(batch.quarantines.sortedBy(ContentQuarantineMutation::quarantineId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    codec.encodeToJsonElement(ContentQuarantineMutation.serializer(), mutation),
                ),
            )
        }
        writer.list(batch.rightsGrants.sortedBy { it.grantReference.value }) { mutation ->
            bytes(canonicalJsonBytes(codec.encodeToJsonElement(RightsGrant.serializer(), mutation.grant)))
        }
        writer.list(batch.blobSyncJobs.sortedBy(ContentBlobSyncJobMutation::jobId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    codec.encodeToJsonElement(ContentBlobSyncJobMutation.serializer(), mutation),
                ),
            )
        }
        batch.replicaReplacement?.let { replacement ->
            writer.bytes(
                canonicalJsonBytes(
                    codec.encodeToJsonElement(
                        ContentPublicationReplicaReplacement.serializer(),
                        replacement,
                    ),
                ),
            )
        } ?: writer.bytes(ByteArray(0))
        writer.list(batch.outbox.sortedBy(outboxAdapter::id)) { draft ->
            string(outboxAdapter.id(draft))
            bytes(outboxAdapter.fingerprint(draft))
        }
        return dev.shinsou.kmp.plugin.Sha256.hex(writer.toByteArray())
    }

    private fun maybeFail(point: ContentTransactionFailurePoint) {
        if (failureInjection == point) {
            failureInjection = null
            throw IllegalStateException("Injected SQL content transaction failure at $point")
        }
    }

    private fun requireSafeSqlKey(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 4096) { "$label must be non-blank and bounded" }
        require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
            "$label contains unsafe characters"
        }
    }

    private fun blobSyncAttachmentKey(job: ContentBlobSyncJobMutation): String =
        "${job.owner.scopeKey}/${job.manifestId}/${job.contentRevision}"

    private fun sqlLiteral(value: String): String = value.replace("'", "''")

    private fun projectionColumnCount(projectionAndClause: String): Int {
        val projection = projectionAndClause
            .substringBefore(" WHERE ")
            .substringBefore(" ORDER BY ")
            .trim()
        return projection.split(',').size
    }
}

/** Names retained for callers that use Sqlite/SQL naming rather than the driver name. */
public typealias SqlContentTransactionStore<D> = SqlDriverContentTransactionStore<D>
public typealias SqliteContentTransactionStore<D> = SqlDriverContentTransactionStore<D>
public typealias ContentSqliteSchema = ContentTransactionSchema

@Serializable
private data class PersistedCommitResult(
    val commitId: String,
    val replayed: Boolean,
    val deferred: Boolean,
    val committedGeneration: Long?,
    val attachedOwnerIds: List<String>,
    val outboxDraftIds: List<String>,
    val migrationKeys: List<String>,
    val publicationIds: List<String> = emptyList(),
    val auxiliaryAttachmentIds: List<String> = emptyList(),
    val quarantineIds: List<String> = emptyList(),
    val rightsGrantIds: List<String> = emptyList(),
    val blobSyncJobIds: List<String> = emptyList(),
    val blobRemovalIntentIds: List<String> = emptyList(),
) {
    companion object {
        fun from(result: ContentCommitResult): PersistedCommitResult = PersistedCommitResult(
            commitId = result.commitId,
            replayed = false,
            deferred = result.deferred,
            committedGeneration = result.committedGeneration,
            attachedOwnerIds = result.attachedOwnerIds,
            outboxDraftIds = result.outboxDraftIds,
            migrationKeys = result.migrationKeys,
            publicationIds = result.publicationIds,
            auxiliaryAttachmentIds = result.auxiliaryAttachmentIds,
            quarantineIds = result.quarantineIds,
            rightsGrantIds = result.rightsGrantIds,
            blobSyncJobIds = result.blobSyncJobIds,
            blobRemovalIntentIds = result.blobRemovalIntentIds,
        )

        fun toRuntime(result: PersistedCommitResult): ContentCommitResult = ContentCommitResult(
            commitId = result.commitId,
            replayed = result.replayed,
            deferred = result.deferred,
            committedGeneration = result.committedGeneration,
            attachedOwnerIds = result.attachedOwnerIds,
            outboxDraftIds = result.outboxDraftIds,
            migrationKeys = result.migrationKeys,
            publicationIds = result.publicationIds,
            auxiliaryAttachmentIds = result.auxiliaryAttachmentIds,
            quarantineIds = result.quarantineIds,
            rightsGrantIds = result.rightsGrantIds,
            blobSyncJobIds = result.blobSyncJobIds,
            blobRemovalIntentIds = result.blobRemovalIntentIds,
        )
    }
}

@Serializable
private data class PersistedReceiptDescriptor(
    val storeInstanceId: String,
    val commitToken: String,
    val reference: BlobRef,
    val incarnation: Long,
    val generation: Long,
    val publishedAtEpochMillis: Long,
) {
    companion object {
        fun from(receipt: BlobPublishReceipt): PersistedReceiptDescriptor = PersistedReceiptDescriptor(
            storeInstanceId = receipt.storeInstanceId,
            commitToken = receipt.commitToken,
            reference = receipt.reference,
            incarnation = receipt.incarnation,
            generation = receipt.generation,
            publishedAtEpochMillis = receipt.publishedAtEpochMillis,
        )
    }
}

@Serializable
private data class PersistedReceiptDescriptorList(val receipts: List<PersistedReceiptDescriptor>)

private class SqlCanonicalWriter {
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
        string(reference.blobId)
        long(reference.schemaVersion.toLong())
        string(reference.digestAlgorithm)
        string(reference.plaintextDigest)
        long(reference.byteSize)
        string(reference.mediaType)
    }

    fun <T> list(values: List<T>, write: SqlCanonicalWriter.(T) -> Unit) {
        long(values.size.toLong())
        values.forEach { write(it) }
    }

    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private fun canonicalJsonBytes(element: JsonElement): ByteArray = SqlCanonicalWriter().also { writer ->
    fun write(value: JsonElement) {
        when (value) {
            JsonNull -> writer.string("null")
            is JsonPrimitive -> {
                writer.string(if (value.isString) "string" else "primitive")
                writer.string(value.content)
            }
            is JsonArray -> {
                writer.string("array")
                writer.long(value.size.toLong())
                value.forEach(::write)
            }
            is JsonObject -> {
                writer.string("object")
                writer.long(value.size.toLong())
                value.keys.sorted().forEach { key ->
                    writer.string(key)
                    write(requireNotNull(value[key]))
                }
            }
        }
    }
    write(element)
}.toByteArray()

/** SQLite schema shared by Desktop, Android and Native content transaction stores. */
public object ContentTransactionSchema : SqlSchema<QueryResult.Value<Unit>> {
    public const val VERSION: Long = 5
    override val version: Long = VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        createTables(driver)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Content transaction database cannot migrate backwards" }
        if (oldVersion < VERSION && newVersion >= VERSION) createTables(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }

    private fun createTables(driver: SqlDriver) {
        ContentBlobPersistenceSchema.create(driver)
        listOf(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SCHEMA(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL
                )
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_METADATA(
                  key TEXT NOT NULL PRIMARY KEY,
                  value TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ALIASES(
                  alias TEXT NOT NULL PRIMARY KEY,
                  target TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_MIGRATIONS(
                  migration_key TEXT NOT NULL PRIMARY KEY,
                  namespace TEXT NOT NULL,
                  source_digest TEXT NOT NULL,
                  result_fingerprint TEXT NOT NULL,
                  commit_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_PUBLICATIONS(
                  publication_id TEXT NOT NULL PRIMARY KEY,
                  publication_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ACQUISITIONS(
                  acquisition_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ACQUISITION_RIGHTS(
                  acquisition_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  grant_ref_json TEXT NOT NULL,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id) ON DELETE CASCADE,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_RIGHTS_GRANTS(
                  grant_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_id TEXT,
                  manifest_id TEXT,
                  content_revision INTEGER,
                  grant_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id)
                    ON DELETE CASCADE,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id)
                    ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_UNITS(
                  unit_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_WORK_LINKS(
                  publication_id TEXT NOT NULL,
                  ordinal INTEGER NOT NULL,
                  target_publication_id TEXT NOT NULL,
                  link_json TEXT NOT NULL,
                  PRIMARY KEY(publication_id, ordinal),
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENTS(
                  attachment_key TEXT NOT NULL PRIMARY KEY,
                  owner_scope TEXT NOT NULL,
                  manifest_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  manifest_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENT_REFS(
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  reference_json TEXT NOT NULL,
                  PRIMARY KEY(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_ATTACHMENTS(attachment_key) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_AUXILIARY_ATTACHMENTS(
                  attachment_key TEXT NOT NULL PRIMARY KEY,
                  owner_id TEXT NOT NULL,
                  purpose TEXT NOT NULL,
                  attachment_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_AUXILIARY_ATTACHMENT_REFS(
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  reference_json TEXT NOT NULL,
                  PRIMARY KEY(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_AUXILIARY_ATTACHMENTS(attachment_key)
                    ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_QUARANTINES(
                  quarantine_id TEXT NOT NULL PRIMARY KEY,
                  auxiliary_attachment_key TEXT NOT NULL,
                  package_id TEXT NOT NULL,
                  version TEXT NOT NULL,
                  origin TEXT NOT NULL,
                  ordinal INTEGER NOT NULL,
                  script_blob_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL,
                  FOREIGN KEY(auxiliary_attachment_key)
                    REFERENCES $TABLE_AUXILIARY_ATTACHMENTS(attachment_key)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_OUTBOX(
                  draft_id TEXT NOT NULL PRIMARY KEY,
                  payload TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_SYNC_JOBS(
                  job_id TEXT NOT NULL PRIMARY KEY,
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  owner_scope TEXT NOT NULL,
                  manifest_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  grant_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL,
                  UNIQUE(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_ATTACHMENTS(attachment_key),
                  FOREIGN KEY(grant_id) REFERENCES $TABLE_RIGHTS_GRANTS(grant_id)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_PUBLICATION_REPLICA_CURSORS(
                  publication_id TEXT NOT NULL PRIMARY KEY,
                  instance_id TEXT NOT NULL,
                  workspace_id TEXT NOT NULL,
                  through_workspace_seq INTEGER NOT NULL CHECK(through_workspace_seq >= 0),
                  present INTEGER NOT NULL CHECK(present IN (0, 1)),
                  graph_fingerprint TEXT NOT NULL,
                  cursor_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_REMOVAL_INTENTS(
                  intent_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  through_workspace_seq INTEGER NOT NULL CHECK(through_workspace_seq >= 0),
                  reason TEXT NOT NULL,
                  intent_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_COMMITS(
                  commit_id TEXT NOT NULL PRIMARY KEY,
                  fingerprint TEXT NOT NULL,
                  result_json TEXT NOT NULL,
                  receipt_descriptors_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            "INSERT OR IGNORE INTO $TABLE_SCHEMA(singleton_id, format_version) VALUES (1, $VERSION)",
            "UPDATE $TABLE_SCHEMA SET format_version = $VERSION " +
                "WHERE singleton_id = 1 AND format_version < $VERSION",
        ).forEach { sql -> driver.execute(null, sql, 0).value }
    }
}

private const val TABLE_SCHEMA = "content_transaction_schema"
private const val TABLE_METADATA = "content_transaction_metadata"
private const val TABLE_ALIASES = "content_transaction_aliases"
private const val TABLE_MIGRATIONS = "content_transaction_migrations"
private const val TABLE_PUBLICATIONS = "content_publications"
private const val TABLE_ACQUISITIONS = "content_acquisitions"
private const val TABLE_ACQUISITION_RIGHTS = "content_acquisition_rights"
private const val TABLE_RIGHTS_GRANTS = "content_rights_grants"
private const val TABLE_UNITS = "content_units"
private const val TABLE_WORK_LINKS = "content_work_links"
private const val TABLE_ATTACHMENTS = "content_transaction_attachments"
private const val TABLE_ATTACHMENT_REFS = "content_transaction_attachment_refs"
private const val TABLE_AUXILIARY_ATTACHMENTS = "content_auxiliary_attachments"
private const val TABLE_AUXILIARY_ATTACHMENT_REFS = "content_auxiliary_attachment_refs"
private const val TABLE_QUARANTINES = "content_quarantines"
private const val TABLE_OUTBOX = "content_transaction_outbox"
private const val TABLE_BLOB_SYNC_JOBS = "content_blob_sync_jobs"
private const val TABLE_PUBLICATION_REPLICA_CURSORS = "content_publication_replica_cursors"
private const val TABLE_BLOB_REMOVAL_INTENTS = "content_blob_removal_intents"
private const val TABLE_COMMITS = "content_transaction_commits"
private const val RIGHTS_GRANT_COLUMNS =
    "grant_id, publication_id, acquisition_id, unit_id, manifest_id, content_revision, grant_json"
private const val MAX_SQL_PAYLOAD_LENGTH = MAX_CONTENT_METADATA_JSON_CHARS
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private val ContentTransactionJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
