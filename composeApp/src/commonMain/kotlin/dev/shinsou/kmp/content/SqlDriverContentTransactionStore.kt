package dev.shinsou.kmp.content

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
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
import kotlinx.coroutines.sync.Mutex

/**
 * The durable state exposed by [SqlDriverContentTransactionStore].  Attachments are included in
 * the state because the manifest/blob-ref ledger is part of the same authority as metadata and
 * outbox rows.  Body bytes never enter this object or SQLite; only immutable [BlobRef] values do.
 */
public data class SqlContentTransactionState<D : Any>(
    val metadata: Map<String, String>,
    val aliases: Map<String, String>,
    val migrations: Map<String, ContentMigrationLedgerMutation>,
    val attachments: List<ManifestAttachment>,
    val outbox: List<D>,
    val committedIds: Set<String>,
)

/**
 * SQLite-backed implementation of the shared content transaction boundary.
 *
 * The supplied SQLDelight driver is used synchronously, just like the platform SQLite drivers
 * used by the existing sync persistence.  [TransacterImpl] gives all rows one transaction.  The
 * in-memory blob store is held locked while that transaction runs; its rollback snapshot is
 * restored if SQLite or any participant write fails.  A future file/object blob implementation
 * can provide the same participant boundary without changing this SQL schema.
 *
 * The current in-memory participant is hydrated from durable SQL attachment rows only during a
 * commit or semantic replay.  This is an explicit M0 boundary: production wiring with a
 * persistent blob participant must hydrate all SQL attachments at startup, or make recovery/GC
 * consult SQL directly, before it can claim restart-safe orphan protection.
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
) : SharedContentTransactionStore<D> {
    public constructor(
        driver: SqlDriver,
        outboxAdapter: ContentOutboxPersistenceAdapter<D>,
        blobStore: ContentBlobStore,
        json: Json = ContentTransactionJson,
        syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
    ) : this(driver, blobStore, outboxAdapter, json, syncModeProvider)
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
    }
    private val transactions = object : TransacterImpl(driver) {}

    /** Test and fault-injection seam; production code leaves this null. */
    public var failureInjection: ContentTransactionFailurePoint? = null

    /** Reads the durable SQL authority on every access, so a reopened driver sees all rows. */
    public val state: SqlContentTransactionState<D>
        get() = readState()

    init {
        ContentTransactionSchema.create(driver).value
        // WAL is a database property on SQLite.  Do not require it here: Android/iOS may choose
        // a platform-specific journal mode, while the transaction semantics remain identical.
        driver.execute(null, "PRAGMA journal_mode = WAL", 0).value
        driver.execute(null, "PRAGMA synchronous = FULL", 0).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
    }

    /** Closes the underlying driver.  Reopening the same path reconstructs [state]. */
    public fun close() = driver.close()

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

    private fun commitLocked(batch: ContentCommitBatch<D>): ContentCommitResult {
        val authoritativeSyncMode = syncModeProvider()
        validateDrafts(batch)
        // A migration ledger is the durable semantic idempotency boundary.  It must be
        // consulted before the normal commit journal, since a reopened process necessarily has
        // different receipt objects (and may have no local receipt objects at all).  An exact
        // source/result pair is therefore a replay even when the caller supplied fresh,
        // republished receipts or a batch with no receipts.
        val migrationReplay = batch.migrations.singleOrNull()?.let { migration ->
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
                consumeReplayReceipts(batch.receipts, durableAttachments)
                return replayResult(batch, previous?.result)
            }
        }
        val fingerprint = fingerprint(batch, authoritativeSyncMode)
        val previous = findCommit(batch.commitId)
        if (previous != null) {
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

        // The current production blob participant is deliberately fail-closed.  Treating a
        // plain ContentBlobStore as independently atomic would violate the shared SQL boundary.
        val memoryBlobStore = blobStore as? InMemoryContentBlobStore
            ?: throw ContentBlobStoreException.InvalidStage(
                "SQLite content transactions require a transactional blob participant",
            )

        return memoryBlobStore.withExclusiveTransaction {
            val blobRollback = memoryBlobStore.snapshotForTransactionLocked()
            try {
                transactions.transactionWithResult<ContentCommitResult>(false) {
                    val persistedAttachments = batch.attachments.map { attachment ->
                        attachment to findAttachment(attachment.attachmentKey)
                    }
                    persistedAttachments.forEach { (attachment, existing) ->
                        if (existing != null && existing != attachment) {
                            throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
                        }
                    }

                    validateSqlRows(batch)

                    val attachmentBlobIds = batch.attachments
                        .flatMap(ManifestAttachment::blobs)
                        .map(BlobRef::blobId)
                        .toSet()
                    if (batch.receipts.any { it.reference.blobId !in attachmentBlobIds }) {
                        throw ContentBlobStoreException.InvalidStage(
                            "Blob receipts must match a referenced manifest blob",
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
                    val newBlobIds = newAttachments.flatMap { it.blobs }.map(BlobRef::blobId).toSet()
                    val newReceipts = batch.receipts.filter { it.reference.blobId in newBlobIds }
                    val existingReceipts = batch.receipts.filter { it.reference.blobId !in newBlobIds }

                    // Existing SQL rows must also be installed in a freshly opened blob
                    // participant; otherwise the participant's recovery/GC view would treat a
                    // SQL-referenced blob as an orphan.
                    memoryBlobStore.hydrateAttachmentsLocked(existingAttachments)
                    memoryBlobStore.validateAtomicAttachmentsLocked(newReceipts, newAttachments)
                    memoryBlobStore.validateReceiptsOnlyLocked(existingReceipts)
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)

                    memoryBlobStore.consumeAtomicAttachmentsLocked(newReceipts, newAttachments) {
                        maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
                        maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
                    }
                    memoryBlobStore.consumeReceiptsLocked(existingReceipts)
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_WRITE)

                    writeAttachments(newAttachments)
                    writeMetadata(batch.metadata)
                    writeAliases(batch.aliases)
                    writeMigrations(batch.migrations)
                    writeOutbox(batch.outbox)

                    val result = ContentCommitResult(
                        commitId = batch.commitId,
                        replayed = false,
                        deferred = false,
                        committedGeneration = batch.receipts.maxOfOrNull(BlobPublishReceipt::generation),
                        attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
                        outboxDraftIds = batch.outbox.map(outboxAdapter::id),
                        migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
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
    ) {
        val memoryBlobStore = blobStore as? InMemoryContentBlobStore ?: return
        val durableAttachments = attachments.map(ManifestAttachment::asBlobAttachment)
        val referencedBlobIds = durableAttachments
            .flatMap(BlobAttachment::blobs)
            .mapTo(hashSetOf(), BlobRef::blobId)
        if (receipts.isEmpty() || referencedBlobIds.isEmpty()) return
        val eligible = receipts.filter { it.reference.blobId in referencedBlobIds }
        if (eligible.isEmpty()) return
        memoryBlobStore.withExclusiveTransaction {
            // A semantic replay is authorized by the durable migration ledger, not by the
            // process-local blob participant.  If local bytes are missing/corrupt, leave all
            // receipts pending and let a later recovery/republish repair the participant.
            try {
                memoryBlobStore.hydrateAttachmentsLocked(durableAttachments)
            } catch (_: ContentBlobStoreException) {
                return@withExclusiveTransaction
            }
            val valid = eligible.mapNotNull { receipt ->
                try {
                    memoryBlobStore.validateReceiptsOnlyLocked(listOf(receipt))
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
                    memoryBlobStore.consumeReceiptsLocked(valid)
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
    )).copy(replayed = true)

    private val activeReceiptObjects = LinkedHashMap<String, List<BlobPublishReceipt>>()
    private val transactionMutex = Mutex()

    private inline fun <T> withTransactionLock(block: () -> T): T {
        if (!transactionMutex.tryLock()) {
            throw IllegalStateException("Concurrent content transaction must retry")
        }
        return try {
            block()
        } finally {
            transactionMutex.unlock()
        }
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
        if (batch.attachments.isNotEmpty() || batch.receipts.isNotEmpty()) {
            batch.attachments.mapTo(this) { "manifest:${it.manifestId}" }
            if (batch.attachments.isEmpty()) add("unattached-blob-receipt")
        }
        batch.migrations.mapTo(this) { "migration:${it.migrationKey}" }
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
                val existing = outboxAdapter.decode(encoded)
                outboxAdapter.validate(existing)
                if (!outboxAdapter.fingerprint(existing).contentEquals(outboxAdapter.fingerprint(draft))) {
                    throw ContentTransactionException.CommitConflict("outbox:$id")
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
        val migrations = linkedMapOf<String, ContentMigrationLedgerMutation>()
        queryRows(
            TABLE_MIGRATIONS,
            "migration_key, namespace, source_digest, result_fingerprint, commit_id, mutation_json ORDER BY migration_key",
        ).forEach { row ->
            val key = requireNotNull(row[0])
            val mutation = decodeJson(ContentMigrationLedgerMutation.serializer(), requireNotNull(row[5]))
            check(mutation.migrationKey == key) { "Migration key/body mismatch" }
            check(mutation.namespace == requireNotNull(row[1])) { "Migration namespace mismatch" }
            check(mutation.sourceDigestSha256 == requireNotNull(row[2])) { "Migration source digest mismatch" }
            check(mutation.resultFingerprintSha256 == requireNotNull(row[3])) {
                "Migration result fingerprint mismatch"
            }
            check(mutation.commitId == requireNotNull(row[4])) { "Migration commit id mismatch" }
            check(migrations.put(key, mutation) == null) { "Duplicate migration key" }
        }
        val attachments = immutableListOf(
            queryRows(TABLE_ATTACHMENTS, "attachment_key ORDER BY attachment_key")
                .map { row -> loadAttachment(requireNotNull(row[0])) },
        )
        val outbox = queryRows(TABLE_OUTBOX, "draft_id, payload ORDER BY draft_id").map { row ->
            val id = requireNotNull(row[0])
            val draft = outboxAdapter.decode(requireNotNull(row[1]))
            outboxAdapter.validate(draft)
            check(outboxAdapter.id(draft) == id) { "Outbox key/body mismatch" }
            draft
        }
        val committedIds = queryRows(TABLE_COMMITS, "commit_id ORDER BY commit_id")
            .mapTo(linkedSetOf()) { requireNotNull(it[0]) }
        return SqlContentTransactionState(
            metadata = metadata,
            aliases = aliases,
            migrations = migrations,
            attachments = attachments,
            outbox = outbox,
            committedIds = committedIds,
        )
    }

    private data class PersistedCommit(
        val fingerprint: String,
        val result: ContentCommitResult,
        val receiptDescriptors: List<PersistedReceiptDescriptor>,
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

    private fun <T> encodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        codec.encodeToString(serializer, value).also {
            require(it.length <= MAX_SQL_PAYLOAD_LENGTH) { "Content transaction payload is too large" }
        }

    private fun <T> decodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: String): T =
        codec.decodeFromString(serializer, value)

    private fun fingerprint(batch: ContentCommitBatch<D>, authoritativeSyncMode: ContentSyncMode): String {
        val writer = SqlCanonicalWriter()
        writer.string(batch.commitId)
        writer.string(authoritativeSyncMode.name)
        writer.string(batch.unrepresentableDraftPolicy.name)
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
        )

        fun toRuntime(result: PersistedCommitResult): ContentCommitResult = ContentCommitResult(
            commitId = result.commitId,
            replayed = result.replayed,
            deferred = result.deferred,
            committedGeneration = result.committedGeneration,
            attachedOwnerIds = result.attachedOwnerIds,
            outboxDraftIds = result.outboxDraftIds,
            migrationKeys = result.migrationKeys,
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
    public const val VERSION: Long = 1
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
                CREATE TABLE IF NOT EXISTS $TABLE_OUTBOX(
                  draft_id TEXT NOT NULL PRIMARY KEY,
                  payload TEXT NOT NULL
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
        ).forEach { sql -> driver.execute(null, sql, 0).value }
    }
}

private const val TABLE_SCHEMA = "content_transaction_schema"
private const val TABLE_METADATA = "content_transaction_metadata"
private const val TABLE_ALIASES = "content_transaction_aliases"
private const val TABLE_MIGRATIONS = "content_transaction_migrations"
private const val TABLE_ATTACHMENTS = "content_transaction_attachments"
private const val TABLE_ATTACHMENT_REFS = "content_transaction_attachment_refs"
private const val TABLE_OUTBOX = "content_transaction_outbox"
private const val TABLE_COMMITS = "content_transaction_commits"
private const val MAX_SQL_PAYLOAD_LENGTH = 4_000_000
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private val ContentTransactionJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
