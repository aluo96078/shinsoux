package dev.shinsou.kmp.content

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.immutableListOf
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.RightsGrant
import kotlinx.serialization.json.Json

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

    /** Owns the SQL session (transacter and failure injection); the tables below share it. */
    private val sql = ContentSqlAccess(driver, codec)
    private val tables = ContentTables(sql)
    private val outbox = ContentOutboxTable(sql, outboxAdapter)
    private val validator = ContentCommitValidator(tables, outbox)
    private val blobParticipant: ContentBlobTransactionParticipant =
        blobStore.transactionParticipantOrNull()
            ?: throw ContentBlobStoreException.InvalidStage(
                "SQLite content transactions require a transactional blob participant",
            )

    /** Test and fault-injection seam; production code leaves this null. */
    public var failureInjection: ContentTransactionFailurePoint?
        get() = sql.failureInjection
        set(value) {
            sql.failureInjection = value
        }

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
        if (blobParticipant.isRestartSafe) {
            // Decoding every stored chapter manifest here took about two seconds of the first
            // frame. The ledger is installed by the first caller of the blob lock instead (the
            // composition's startup job warms it in the background). Writers of these rows take
            // the blob lock before their SQL transaction, so the rows read then are committed.
            blobParticipant.deferAttachmentHydration {
                DeferredAttachmentHydration(
                    attachments = tables.attachments.loadAllAttachments().map { BlobAttachment(it.owner, it.manifest) },
                    auxiliaryAttachments = tables.attachments.loadAllAuxiliaryAttachments(),
                )
            }
        }
    }

    /** Installs the deferred attachment ledger now; call from a background thread at startup. */
    public fun warmAttachmentLedger() {
        blobParticipant.withExclusiveTransaction { }
    }

    /** Closes the underlying driver.  Reopening the same path reconstructs [state]. */
    public fun close() {
        if (ownsDriver) driver.close()
    }

    /** Reads and verifies exactly one publication graph without hydrating unrelated SQL state. */
    public fun findPublicationDirect(key: PublicationKey): Publication? = withTransactionLock {
        key.validate()
        tables.publications.findPublication(key)
    }

    /** Reads and verifies only publication graph rows, in stable portable-id order. */
    public fun allPublicationsDirect(): List<Publication> = withTransactionLock {
        tables.publications.loadAllPublications()
    }

    /** One publication's outline row only: no child rows and no manifests are decoded. */
    public fun findPublicationOutlineDirect(key: PublicationKey): Publication? = withTransactionLock {
        sql.queryRows(
            TABLE_PUBLICATIONS,
            "publication_json WHERE publication_id = ?",
            key.value,
        ).singleOrNull()?.let { row ->
            sql.decodeJson(Publication.serializer(), row.string("publication_json")).withoutManifestRevisions().also {
                check(it.key == key) { "Publication key/body mismatch" }
            }
        }
    }

    /** Every publication's outline row, in stable portable-id order. */
    public fun allPublicationOutlinesDirect(): List<Publication> = withTransactionLock {
        sql.queryRows(TABLE_PUBLICATIONS, "publication_id, publication_json ORDER BY publication_id").map { row ->
            sql.decodeJson(Publication.serializer(), row.string("publication_json")).withoutManifestRevisions().also {
                check(it.key.value == row.string("publication_id")) { "Publication key/body mismatch" }
            }
        }
    }

    /** Reads and verifies exactly one grant and its scoped publication graph. */
    public fun findRightsGrantDirect(reference: RightsGrantRef): RightsGrant? = withTransactionLock {
        reference.validate()
        tables.rights.findRightsGrant(reference)
    }

    /** Reads and verifies only rights grants and the publication graphs which scope them. */
    public fun allRightsGrantsDirect(): List<RightsGrant> = withTransactionLock {
        tables.rights.loadAllRightsGrants()
    }

    /**
     * [allRightsGrantsDirect] for the runtime that is opening this store. Every platform opens it
     * off the UI thread; were one not to, it is reported there, not failed ([ContentSqlThreadGuard]).
     */
    internal fun allRightsGrantsWhileOpening(): List<RightsGrant> {
        ContentSqlThreadGuard.checkWhileOpening()
        return transactionMutex.withLock { tables.rights.loadAllRightsGrants() }
    }

    /** Reads the complete grant set owned by one publication replica. */
    public fun rightsGrantsForPublicationDirect(publicationKey: PublicationKey): List<RightsGrant> =
        withTransactionLock {
            publicationKey.validate()
            tables.rights.loadRightsGrantsForPublication(publicationKey)
        }

    /**
     * Reads only the body-free auxiliary authority needed by Backup v2. The stable ordering makes
     * archive checksums deterministic while avoiding unrelated outbox, attachment and body rows.
     */
    public fun portableAuxiliaryStateDirect(): ContentPortableAuxiliaryState = withTransactionLock {
        ContentPortableAuxiliaryState(
            metadata = sql.readPairs(TABLE_METADATA, "key", "value")
                .map { (key, value) -> ContentMetadataMutation(key, value) },
            aliases = sql.readPairs(TABLE_ALIASES, "alias", "target")
                .map { (alias, target) -> ContentAliasMutation(alias, target) },
            migrations = tables.ledger.loadAllMigrations(),
        )
    }

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = withTransactionLock {
        tables.ledger.lookupMigrationLedgerLocked(namespace, sourceDigestSha256, resultFingerprintSha256)
    }

    override fun commit(batch: ContentCommitBatch<D>): ContentCommitResult = withTransactionLock {
        commitLocked(batch)
    }

    override fun pendingOutbox(): List<D> = withTransactionLock {
        outbox.pending()
    }

    override fun acknowledgeOutbox(draftIds: Set<String>): Int = withTransactionLock {
        outbox.acknowledge(draftIds)
    }

    override fun pendingOutboxCount(): Int = withTransactionLock {
        outbox.count()
    }

    override fun pendingOutboxPage(afterDraftId: String?, limit: Int): List<D> = withTransactionLock {
        outbox.page(afterDraftId, limit)
    }

    override fun pendingOutboxDraft(draftId: String): D? = withTransactionLock {
        outbox.draft(draftId)
    }

    override fun replaceOutboxDrafts(remove: Set<String>, add: List<D>): Int = withTransactionLock {
        outbox.replace(remove, add)
    }

    override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = withTransactionLock {
        sql.queryRows(TABLE_BLOB_SYNC_JOBS, "job_id ORDER BY job_id").map { row ->
            requireNotNull(tables.blobSyncJobs.findBlobSyncJob(row.string("job_id")))
        }
    }

    override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int = withTransactionLock {
        sql.acknowledgeRows(TABLE_BLOB_SYNC_JOBS, "job_id", jobIds, "Blob sync job id")
    }

    override fun publicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? = withTransactionLock {
        publicationKey.validate()
        tables.replicas.findPublicationReplicaCursor(publicationKey)
    }

    override fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> = withTransactionLock {
        sql.queryRows(TABLE_BLOB_REMOVAL_INTENTS, "intent_id ORDER BY intent_id").map { row ->
            requireNotNull(tables.replicas.findBlobRemovalIntent(row.string("intent_id")))
        }
    }

    override fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int = withTransactionLock {
        sql.acknowledgeRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "intent_id",
            intentIds,
            "Blob removal intent id",
        )
    }

    override fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult = withTransactionLock {
        val mutation = sql.transactions.transactionWithResult<ReplicaAuthorityDepartureMutation>(false) {
            val publicationKeys = sql.queryRows(
                TABLE_PUBLICATION_REPLICA_CURSORS,
                "publication_id WHERE instance_id = ? " +
                    "AND workspace_id = ? ORDER BY publication_id",
                authority.instanceId,
                authority.workspaceId,
            ).mapTo(linkedSetOf()) { row ->
                PublicationKey(row.string("publication_id")).also { publicationKey ->
                    val cursor = requireNotNull(tables.replicas.findPublicationReplicaCursor(publicationKey))
                    check(cursor.instanceId == authority.instanceId &&
                        cursor.workspaceId == authority.workspaceId) {
                        "Replica authority index/body mismatch"
                    }
                }
            }
            val commitIds = sql.queryRows(TABLE_COMMITS, "commit_id ORDER BY commit_id")
                .mapNotNullTo(linkedSetOf()) { row ->
                    row.string("commit_id").takeIf { commitId ->
                        publicationKeys.any { publicationKey ->
                            commitId.startsWith(
                                "$CONTENT_REPLICA_COMMIT_ID_PREFIX${publicationKey.value}:",
                            )
                        }
                    }
                }
            val removalIntentIds = sql.queryRows(
                TABLE_BLOB_REMOVAL_INTENTS,
                "intent_id ORDER BY intent_id",
            ).mapNotNullTo(linkedSetOf()) { row ->
                val intentId = row.string("intent_id")
                val cursor = requireNotNull(tables.replicas.findBlobRemovalIntent(intentId)).sourceCursor
                intentId.takeIf {
                    cursor.instanceId == authority.instanceId &&
                        cursor.workspaceId == authority.workspaceId
                }
            }

            publicationKeys.forEach { publicationKey ->
                sql.execute(
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
                sql.execute("DELETE FROM $TABLE_COMMITS WHERE commit_id = ?", 1) {
                    bindString(0, commitId)
                }
            }
            removalIntentIds.forEach { intentId ->
                sql.execute("DELETE FROM $TABLE_BLOB_REMOVAL_INTENTS WHERE intent_id = ?", 1) {
                    bindString(0, intentId)
                }
            }
            sql.maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE)

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

    override fun purgeLocalPublicationBodies(
        publicationKey: PublicationKey,
        forgetCommitIdsOf: (Publication) -> Set<String>,
        draftIdPrefixOf: (commitId: String) -> String?,
    ): ContentLocalBodyPurgeResult = withTransactionLock {
        publicationKey.validate()
        val publication = tables.publications.findPublication(publicationKey)
            ?: return@withTransactionLock ContentLocalBodyPurgeResult()
        val forgetCommitIds = forgetCommitIdsOf(publication)
        val forgetDraftIdPrefixes = forgetCommitIds.mapNotNullTo(linkedSetOf(), draftIdPrefixOf)
        forgetCommitIds.forEach { requireSafeSqlKey(it, "Purged commit id") }
        forgetDraftIdPrefixes.forEach { requireSafeSqlKey(it, "Purged draft id prefix") }
        val purgedUnits = publication.acquisitions.sumOf { acquisition ->
            acquisition.units.count { it.manifestRevisions.isNotEmpty() }
        }
        if (purgedUnits == 0) return@withTransactionLock ContentLocalBodyPurgeResult()
        val result = blobParticipant.withExclusiveTransaction {
            val blobRollback = blobParticipant.snapshotForTransactionLocked()
            try {
                sql.transactions.transactionWithResult<ContentLocalBodyPurgeResult>(false) {
                    // Same mechanics as a replica tombstone, but local-only: no cursor, removal
                    // intent or outbox row is written, and the publication graph is re-installed
                    // below without its manifests so reading state and bindings survive.
                    val rights = tables.rights.loadRightsGrantsForPublication(publicationKey)
                    val durableAttachments = tables.attachments.loadPublicationAttachments(publicationKey)
                        .map(ManifestAttachment::asBlobAttachment)
                        .sortedBy(BlobAttachment::attachmentKey)
                    val participantAttachments = blobParticipant
                        .detachManifestAttachmentsLocked(publicationKey)
                        .sortedBy(BlobAttachment::attachmentKey)
                    check(participantAttachments == durableAttachments) {
                        "SQLite/blob participant publication attachment ledger mismatch"
                    }
                    tables.deletePublicationReplicaRows(publicationKey)
                    tables.publications.writePublications(
                        listOf(ContentPublicationMutation(publication.withoutManifestRevisions())),
                    )
                    tables.rights.writeRightsGrants(rights.map { ContentRightsGrantMutation(it) })
                    forgetCommitIds.forEach { commitId ->
                        sql.execute("DELETE FROM $TABLE_COMMITS WHERE commit_id = ?", 1) {
                            bindString(0, commitId)
                        }
                    }
                    // Exact prefix match (substr, not LIKE) so '_' or '%' in ids are literal.
                    forgetDraftIdPrefixes.forEach { prefix ->
                        sql.execute("DELETE FROM $TABLE_OUTBOX WHERE substr(draft_id, 1, ?) = ?", 2) {
                            bindLong(0, prefix.length.toLong())
                            bindString(1, prefix)
                        }
                    }
                    sql.maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                    ContentLocalBodyPurgeResult(
                        removedBlobs = durableAttachments.flatMap(BlobAttachment::blobs).distinct(),
                        purgedUnitCount = purgedUnits,
                    )
                }
            } catch (error: Throwable) {
                blobRollback.rollback()
                throw error
            }
        }
        forgetCommitIds.forEach(activeReceiptObjects::remove)
        result
    }

    private fun commitLocked(batch: ContentCommitBatch<D>): ContentCommitResult {
        val authoritativeSyncMode = syncModeProvider()
        val replacingPortableGraph =
            batch.semantics == ContentCommitSemantics.REPLACE_PORTABLE_GRAPH
        validator.validateDrafts(batch)
        replicaReplay(batch)?.let { return it }
        migrationReplay(batch, replacingPortableGraph)?.let { return it }
        val fingerprint = contentCommitFingerprint(batch, authoritativeSyncMode, codec, outboxAdapter)
        journalReplay(batch, fingerprint, replacingPortableGraph)?.let { return it }

        sql.maybeFail(ContentTransactionFailurePoint.BEFORE_VALIDATE)
        v1Deferral(batch, authoritativeSyncMode)?.let { return it }

        validator.validateUniqueBatchEntries(batch)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_STRUCTURAL_VALIDATE)

        return commitAtomically(batch, fingerprint, replacingPortableGraph).also {
            if (replacingPortableGraph) {
                activeReceiptObjects.keys.removeAll {
                    it.startsWith(CONTENT_REPLICA_COMMIT_ID_PREFIX)
                }
            }
            activeReceiptObjects[batch.commitId] = batch.receipts.toList()
        }
    }

    /**
     * A replica replacement whose cursor is already durable is a replay; one based on any cursor
     * other than the durable one conflicts. Null: carry on with the commit.
     */
    private fun replicaReplay(batch: ContentCommitBatch<D>): ContentCommitResult? =
        batch.replicaReplacement?.let { replacement ->
            val current = tables.replicas.findPublicationReplicaCursor(replacement.replacement.publicationKey)
            if (current == replacement.replacement) {
                validator.validateReplicaReplay(batch)
                val previous = tables.journal.findCommit(batch.commitId)
                val durableAttachments = batch.attachments.mapNotNull { candidate ->
                    tables.attachments.findAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                consumeReplayReceipts(batch.receipts, durableAttachments, emptyList())
                replayResult(batch, previous?.result)
            } else {
                if (current != replacement.expected) {
                    throw ContentTransactionException.CommitConflict(replacement.conflictId)
                }
                null
            }
        }

    private fun migrationReplay(
        batch: ContentCommitBatch<D>,
        replacingPortableGraph: Boolean,
    ): ContentCommitResult? {
        // A migration ledger is the durable semantic idempotency boundary.  It must be
        // consulted before the normal commit journal, since a reopened process necessarily has
        // different receipt objects (and may have no local receipt objects at all).  An exact
        // source/result pair is therefore a replay even when the caller supplied fresh,
        // republished receipts or a batch with no receipts.
        val migrationReplay = batch.migrations
            .takeUnless { replacingPortableGraph }
            ?.singleOrNull()
            ?.let { migration ->
            tables.ledger.lookupMigrationLedgerLocked(
                migration.namespace,
                migration.sourceDigestSha256,
                migration.resultFingerprintSha256,
            )
        }
        return migrationReplay?.let { lookup ->
            if (lookup.isConflict) {
                throw ContentTransactionException.CommitConflict(lookup.deterministicCommitId)
            }
            if (lookup.isReplay) {
                val previous = tables.journal.findCommit(batch.commitId)
                val durableAttachments = batch.attachments.mapNotNull { candidate ->
                    tables.attachments.findAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                val durableAuxiliaryAttachments = batch.auxiliaryAttachments.mapNotNull { candidate ->
                    tables.attachments.findAuxiliaryAttachment(candidate.attachmentKey)?.takeIf { it == candidate }
                }
                consumeReplayReceipts(
                    batch.receipts,
                    durableAttachments,
                    durableAuxiliaryAttachments,
                )
                replayResult(batch, previous?.result)
            } else {
                null
            }
        }
    }

    /** The journal's answer for an already committed [ContentCommitBatch.commitId]; null when there is none. */
    private fun journalReplay(
        batch: ContentCommitBatch<D>,
        fingerprint: String,
        replacingPortableGraph: Boolean,
    ): ContentCommitResult? {
        val previous = tables.journal.findCommit(batch.commitId)
        if (previous == null || replacingPortableGraph) return null
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

    /** What v1 sync cannot carry: deferred when the batch allows it, refused otherwise. Null: no gap. */
    private fun v1Deferral(
        batch: ContentCommitBatch<D>,
        authoritativeSyncMode: ContentSyncMode,
    ): ContentCommitResult? {
        val v1Gaps = validator.unrepresentableV1Gaps(batch, authoritativeSyncMode)
        if (authoritativeSyncMode != ContentSyncMode.V1_ACTIVE || v1Gaps.isEmpty()) return null
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

    /**
     * The commit's single SQLite transaction under the blob participant's lock. Every step below
     * runs inside it; any failure rolls back SQLite and restores the participant's snapshot.
     */
    private fun commitAtomically(
        batch: ContentCommitBatch<D>,
        fingerprint: String,
        replacingPortableGraph: Boolean,
    ): ContentCommitResult = blobParticipant.withExclusiveTransaction {
        val blobRollback = blobParticipant.snapshotForTransactionLocked()
        try {
            sql.transactions.transactionWithResult<ContentCommitResult>(false) {
                val detachedReplicaAttachments = detachReplacedRows(batch, replacingPortableGraph)
                val staged = stageAttachments(batch)
                writeBatchRows(batch, staged)
                val removalIntent = writeReplicaCursor(batch, detachedReplicaAttachments)
                val result = committedResult(batch, removalIntent)
                tables.journal.insertCommit(
                    commitId = batch.commitId,
                    fingerprint = fingerprint,
                    result = result,
                    receipts = batch.receipts,
                )
                sql.maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                result
            }
        } catch (error: Throwable) {
            // SQLDelight has already rolled back the SQLite transaction when the callback
            // throws.  Restore the object-identity receipt ledger and attachment map too.
            blobRollback.rollback()
            throw error
        }
    }

    /** Clears what the batch replaces; returns the replaced replica's attachments. */
    private fun detachReplacedRows(
        batch: ContentCommitBatch<D>,
        replacingPortableGraph: Boolean,
    ): List<BlobAttachment> {
        if (replacingPortableGraph) {
            tables.deletePortableGraphRows(batch.commitId)
            blobParticipant.clearManifestAttachmentsLocked()
        }
        return batch.replicaReplacement?.let { replacement ->
            val publicationKey = replacement.replacement.publicationKey
            if (tables.replicas.findPublicationReplicaCursor(publicationKey) != replacement.expected) {
                throw ContentTransactionException.CommitConflict(replacement.conflictId)
            }
            val durableAttachments = tables.attachments.loadPublicationAttachments(publicationKey)
                .map(ManifestAttachment::asBlobAttachment)
                .sortedBy(BlobAttachment::attachmentKey)
            val participantAttachments = blobParticipant
                .detachManifestAttachmentsLocked(publicationKey)
                .sortedBy(BlobAttachment::attachmentKey)
            check(participantAttachments == durableAttachments) {
                "SQLite/blob participant publication attachment ledger mismatch"
            }
            tables.deletePublicationReplicaRows(publicationKey)
            durableAttachments
        }.orEmpty()
    }

    /** The attachments the batch adds, after every row check and with their blobs consumed. */
    private class StagedAttachments(
        val newAttachments: List<BlobAttachment>,
        val newAuxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    )

    private fun stageAttachments(batch: ContentCommitBatch<D>): StagedAttachments {
        val persistedAttachments = batch.attachments.map { attachment ->
            attachment to tables.attachments.findAttachment(attachment.attachmentKey)
        }
        persistedAttachments.forEach { (attachment, existing) ->
            if (existing != null && existing != attachment) {
                throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
            }
        }
        val persistedAuxiliaryAttachments = batch.auxiliaryAttachments.map { attachment ->
            attachment to tables.attachments.findAuxiliaryAttachment(attachment.attachmentKey)
        }
        persistedAuxiliaryAttachments.forEach { (attachment, existing) ->
            if (existing != null && existing != attachment) {
                throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
            }
        }

        validator.validateSqlRows(batch)
        validator.validatePublicationAttachments(batch)
        validator.validateQuarantines(batch)
        validator.validateRightsGrants(batch)
        validator.validateBlobSyncJobs(batch)

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
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)

        blobParticipant.consumeAtomicAttachmentsLocked(
            newReceipts,
            newAttachments,
            newAuxiliaryAttachments,
        ) {
            sql.maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
            sql.maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
        }
        blobParticipant.consumeReceiptsLocked(existingReceipts)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_WRITE)
        return StagedAttachments(newAttachments, newAuxiliaryAttachments)
    }

    private fun writeBatchRows(batch: ContentCommitBatch<D>, staged: StagedAttachments) {
        tables.attachments.writeAttachments(staged.newAttachments)
        tables.attachments.writeAuxiliaryAttachments(staged.newAuxiliaryAttachments)
        tables.ledger.writeMetadata(batch.metadata)
        tables.ledger.writeAliases(batch.aliases)
        tables.ledger.writeMigrations(batch.migrations)
        outbox.writeOutbox(batch.outbox)
        tables.publications.writePublications(batch.publications)
        tables.quarantines.writeQuarantines(batch.quarantines)
        tables.rights.writeRightsGrants(batch.rightsGrants)
        tables.blobSyncJobs.writeBlobSyncJobs(batch.blobSyncJobs)
    }

    /** The replaced replica's new cursor, and the removal intent for blobs it no longer uses. */
    private fun writeReplicaCursor(
        batch: ContentCommitBatch<D>,
        detachedReplicaAttachments: List<BlobAttachment>,
    ): ContentBlobRemovalIntent? = batch.replicaReplacement?.let { replacement ->
        tables.replicas.writePublicationReplicaCursor(replacement.replacement)
        sql.maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE)
        buildBlobRemovalIntent(
            replacement.replacement,
            detachedReplicaAttachments.flatMap(BlobAttachment::blobs),
            batch.attachments.flatMap(ManifestAttachment::blobs),
        )?.also { intent ->
            tables.replicas.writeBlobRemovalIntent(intent)
            sql.maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE)
        }
    }

    private fun committedResult(
        batch: ContentCommitBatch<D>,
        removalIntent: ContentBlobRemovalIntent?,
    ): ContentCommitResult = ContentCommitResult(
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
                // Expected blob-boundary failure: receipts stay pending for a later recovery.
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
            sql.queryRows(
                TABLE_BLOB_REMOVAL_INTENTS,
                "intent_id ORDER BY intent_id",
            ).mapNotNull { row ->
                tables.replicas.findBlobRemovalIntent(row.string("intent_id"))
            }.filter { it.sourceCursor == cursor }
                .map(ContentBlobRemovalIntent::intentId)
        }.orEmpty(),
    )).copy(replayed = true)

    private val activeReceiptObjects = LinkedHashMap<String, List<BlobPublishReceipt>>()
    private val transactionMutex = SynchronousLock()

    /** Every store operation: never on the UI thread (R3-4, [ContentSqlThreadGuard]). */
    private inline fun <T> withTransactionLock(block: () -> T): T {
        ContentSqlThreadGuard.check()
        return transactionMutex.withLock(block)
    }

    private fun readState(): SqlContentTransactionState<D> {
        val metadata = sql.readPairs(TABLE_METADATA, "key", "value")
        val aliases = sql.readPairs(TABLE_ALIASES, "alias", "target")
        val migrations = tables.ledger.loadAllMigrations().associateByTo(
            linkedMapOf(),
            ContentMigrationLedgerMutation::migrationKey,
        )
        val attachments = immutableListOf(tables.attachments.loadAllAttachments())
        val auxiliaryAttachments = immutableListOf(tables.attachments.loadAllAuxiliaryAttachments())
        val outbox = this.outbox.pending()
        val committedIds = sql.queryRows(TABLE_COMMITS, "commit_id ORDER BY commit_id")
            .mapTo(linkedSetOf()) { it.string("commit_id") }
        val publications = tables.publications.loadAllPublications().associateBy(Publication::key)
        val quarantines = sql.queryRows(TABLE_QUARANTINES, "quarantine_id ORDER BY quarantine_id")
            .associate { row ->
                val id = row.string("quarantine_id")
                id to requireNotNull(tables.quarantines.findQuarantine(id))
            }
        val rightsGrants = tables.rights.loadAllRightsGrants().associateBy(RightsGrant::grantId)
        val blobSyncJobs = sql.queryRows(TABLE_BLOB_SYNC_JOBS, "job_id ORDER BY job_id")
            .associate { row ->
                val jobId = row.string("job_id")
                jobId to requireNotNull(tables.blobSyncJobs.findBlobSyncJob(jobId))
            }
        val publicationReplicaCursors = sql.queryRows(
            TABLE_PUBLICATION_REPLICA_CURSORS,
            "publication_id ORDER BY publication_id",
        ).associate { row ->
            val publicationKey = PublicationKey(row.string("publication_id"))
            publicationKey to requireNotNull(tables.replicas.findPublicationReplicaCursor(publicationKey))
        }
        val blobRemovalIntents = sql.queryRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "intent_id ORDER BY intent_id",
        ).associate { row ->
            val intentId = row.string("intent_id")
            intentId to requireNotNull(tables.replicas.findBlobRemovalIntent(intentId))
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

    private data class ReplicaAuthorityDepartureMutation(
        val result: ContentReplicaAuthorityDepartureResult,
        val removedCommitIds: Set<String>,
    )
}

/** Names retained for callers that use Sqlite/SQL naming rather than the driver name. */
public typealias SqlContentTransactionStore<D> = SqlDriverContentTransactionStore<D>
public typealias SqliteContentTransactionStore<D> = SqlDriverContentTransactionStore<D>
public typealias ContentSqliteSchema = ContentTransactionSchema

private val ContentTransactionJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
