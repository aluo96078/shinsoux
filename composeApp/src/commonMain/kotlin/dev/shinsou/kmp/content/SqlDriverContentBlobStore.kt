package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Restart-safe immutable blob participant backed by the platform-owned content SQLite driver.
 *
 * Payloads are deliberately separate from [SqlContentTransactionState]. Publication and manifest
 * queries therefore never materialize body bytes. Cold start reconstructs only bounded identity,
 * generation and lifecycle metadata; an exact payload is fetched only when that blob is opened or
 * deduplicated. This prevents a large library from putting every body on the startup critical path.
 * [close] is intentionally absent: the platform database host is the single driver owner.
 */
public class SqlDriverContentBlobStore private constructor(
    private val delegate: InMemoryContentBlobStore,
) : ContentBlobStore by delegate {
    public constructor(
        driver: SqlDriver,
        /** Null is retained only for isolated compatibility tests and legacy database migration. */
        blobDirectoryPath: String? = null,
        maximumBlobSizeBytes: Long = InMemoryContentBlobStore.DEFAULT_MAXIMUM_BLOB_SIZE_BYTES,
        blobIdFactory: () -> String = ::randomContentUuid,
        commitTokenFactory: () -> String = { "sql-content-${randomContentUuid()}" },
        storeInstanceIdFactory: () -> String = { "sql-store-${randomContentUuid()}" },
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        createPersistentDelegate(
            driver = driver,
            blobDirectoryPath = blobDirectoryPath,
            maximumBlobSizeBytes = maximumBlobSizeBytes,
            blobIdFactory = blobIdFactory,
            commitTokenFactory = commitTokenFactory,
            storeInstanceIdFactory = storeInstanceIdFactory,
            clock = clock,
        ),
    )

    public val storeInstanceId: String get() = delegate.storeInstanceId
    public val count: Int get() = delegate.count
    public val pendingReceiptCount: Int get() = delegate.pendingReceiptCount
    internal var beginStageCountForTesting: Int = 0
        private set
    public fun pendingReceipts(): List<BlobPublishReceipt> = delegate.pendingReceipts()
    public fun lifecycleState(reference: BlobRef): BlobLifecycleState? = delegate.lifecycleState(reference)

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
        beginStageCountForTesting += 1
        return delegate.beginStage(expectedSizeBytes, mediaType)
    }

    internal fun runStorageMaintenanceSlice(
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult = delegate.runStorageMaintenanceSlice(request)

    internal val transactionParticipant: ContentBlobTransactionParticipant =
        InMemoryContentBlobParticipant(delegate)
}

internal fun ContentBlobStore.transactionParticipantOrNull(): ContentBlobTransactionParticipant? = when (this) {
    is InMemoryContentBlobStore -> InMemoryContentBlobParticipant(this)
    is SqlDriverContentBlobStore -> transactionParticipant
    else -> null
}

private class InMemoryContentBlobParticipant(
    private val store: InMemoryContentBlobStore,
) : ContentBlobTransactionParticipant {
    override val isRestartSafe: Boolean get() = store.isRestartSafe

    override fun <T> withExclusiveTransaction(block: () -> T): T =
        store.withExclusiveTransaction(block)

    override fun snapshotForTransactionLocked(): ContentBlobRollback =
        store.snapshotForTransactionLocked()

    override fun hydrateAttachmentsLocked(
        attachmentsToHydrate: List<BlobAttachment>,
        auxiliaryAttachmentsToHydrate: List<AuxiliaryBlobAttachment>,
    ) = store.hydrateAttachmentsLocked(attachmentsToHydrate, auxiliaryAttachmentsToHydrate)

    override fun clearManifestAttachmentsLocked() = store.clearManifestAttachmentsLocked()

    override fun detachManifestAttachmentsLocked(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): List<BlobAttachment> = store.detachManifestAttachmentsLocked(publicationKey)

    override fun attachedLocked(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment? = store.attachedLocked(owner, manifestId, contentRevision)

    override fun auxiliaryAttachedLocked(attachmentKey: String): AuxiliaryBlobAttachment? =
        store.auxiliaryAttachedLocked(attachmentKey)

    override fun validateAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    ) = store.validateAtomicAttachmentsLocked(receipts, attachments, auxiliaryAttachments)

    override fun validateReceiptsOnlyLocked(receipts: List<BlobPublishReceipt>) =
        store.validateReceiptsOnlyLocked(receipts)

    override fun consumeAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
        afterAttachment: (() -> Unit)?,
    ) = store.consumeAtomicAttachmentsLocked(
        receipts,
        attachments,
        auxiliaryAttachments,
        afterAttachment,
    )

    override fun consumeReceiptsLocked(receipts: List<BlobPublishReceipt>) =
        store.consumeReceiptsLocked(receipts)
}

private fun createPersistentDelegate(
    driver: SqlDriver,
    blobDirectoryPath: String?,
    maximumBlobSizeBytes: Long,
    blobIdFactory: () -> String,
    commitTokenFactory: () -> String,
    storeInstanceIdFactory: () -> String,
    clock: () -> Long,
): InMemoryContentBlobStore {
    val durability = SqlDriverContentBlobDurability(
        driver = driver,
        storeInstanceIdFactory = storeInstanceIdFactory,
        bodyFiles = blobDirectoryPath?.let(::AppPrivateContentBlobFiles),
    )
    return InMemoryContentBlobStore(
        maximumBlobSizeBytes = maximumBlobSizeBytes,
        blobIdFactory = blobIdFactory,
        commitTokenFactory = commitTokenFactory,
        clock = clock,
        configuredStoreInstanceId = durability.storeInstanceId,
        durability = durability,
    )
}

private class SqlDriverContentBlobDurability(
    private val driver: SqlDriver,
    storeInstanceIdFactory: () -> String,
    private val bodyFiles: AppPrivateContentBlobFiles? = null,
    json: Json = ContentBlobPersistenceJson,
) : ContentBlobDurability {
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    override val storeInstanceId: String

    init {
        ContentBlobPersistenceSchema.create(driver)
        storeInstanceId = readStoreInstanceId() ?: run {
            val created = storeInstanceIdFactory()
            requireSqlIdentity(created, "Content blob store instance id")
            driver.execute(
                identifier = null,
                sql = "INSERT OR IGNORE INTO $TABLE_BLOB_STORE(singleton_id, store_instance_id) VALUES (1, ?)",
                parameters = 1,
            ) { bindString(0, created) }.value
            readStoreInstanceId() ?: error("Content blob store identity was not durable after insertion")
        }
        requireSqlIdentity(storeInstanceId, "Content blob store instance id")
    }

    override fun loadMetadata(): List<DurableContentBlob> = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT blob_id, reference_json, incarnation, generation,
                   published_at_epoch_millis, lifecycle_state, discovered_at_epoch_millis,
                   opaque_name
            FROM $TABLE_BLOBS ORDER BY blob_id
        """.trimIndent(),
        mapper = { cursor ->
            val rows = mutableListOf<DurableContentBlob>()
            while (cursor.next().value) {
                val blobId = requireNotNull(cursor.getString(0))
                val reference = codec.decodeFromString<BlobRef>(requireNotNull(cursor.getString(1)))
                check(reference.blobId == blobId) { "Durable blob key/body mismatch" }
                val lifecycle = try {
                    BlobLifecycleState.valueOf(requireNotNull(cursor.getString(5)))
                } catch (error: IllegalArgumentException) {
                    throw ContentBlobStoreException.CorruptBlob(blobId)
                }
                cursor.getString(7)?.let { opaqueName ->
                    bodyFiles?.requireCanonicalOpaqueName(reference, opaqueName)
                }
                rows += DurableContentBlob(
                    reference = reference,
                    bytes = null,
                    incarnation = requireNotNull(cursor.getLong(2)) { "Durable blob incarnation is null" },
                    generation = requireNotNull(cursor.getLong(3)) { "Durable blob generation is null" },
                    publishedAtEpochMillis = requireNotNull(cursor.getLong(4)) {
                        "Durable blob publication timestamp is null"
                    },
                    lifecycleState = lifecycle,
                    discoveredAtEpochMillis = cursor.getLong(6),
                )
            }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    override fun readPayload(blob: DurableContentBlob): ByteArray? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT reference_json, payload, opaque_name, incarnation, generation
            FROM $TABLE_BLOBS WHERE blob_id = ?
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                val reference = codec.decodeFromString<BlobRef>(requireNotNull(cursor.getString(0)))
                val incarnation = requireNotNull(cursor.getLong(3))
                val generation = requireNotNull(cursor.getLong(4))
                if (reference != blob.reference || incarnation != blob.incarnation ||
                    generation != blob.generation
                ) {
                    throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                }
                val opaqueName = cursor.getString(2)
                val payload = if (opaqueName == null) {
                    requireNotNull(cursor.getBytes(1)) { "Durable blob payload is null" }
                } else {
                    val files = bodyFiles ?: throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                    files.requireCanonicalOpaqueName(reference, opaqueName)
                    files.read(reference, opaqueName)
                        ?: throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                }
                QueryResult.Value(payload)
            }
        },
        parameters = 1,
        binders = { bindString(0, blob.reference.blobId) },
    ).value

    override fun beginStage(
        expectedSizeBytes: Long?,
        mediaType: String,
        maximumBlobSizeBytes: Long,
    ): DurableContentBlobStageHandle? = bodyFiles?.beginStage(
        expectedSizeBytes = expectedSizeBytes,
        mediaType = mediaType,
        maximumBlobSizeBytes = maximumBlobSizeBytes,
    )

    override fun publish(blob: DurableContentBlob, pending: DurableContentBlobPendingHandle) {
        val files = bodyFiles
            ?: throw ContentBlobStoreException.InvalidStage("File-backed stage has no body directory")
        val snapshot = blob.immutableCopy()
        require(snapshot.bytes == null) { "Streaming publish must not materialize body bytes" }
        existingMetadata(snapshot.reference.blobId)?.let { durable ->
            if (durable.reference != snapshot.reference || durable.incarnation != snapshot.incarnation ||
                !verifyPayload(durable)
            ) {
                throw ContentBlobStoreException.CorruptBlob(snapshot.reference.blobId)
            }
        }
        val opaqueName = files.publish(snapshot.reference, pending)
        upsertRow(snapshot, EMPTY_SQLITE_PAYLOAD, opaqueName)
    }

    override fun openRead(blob: DurableContentBlob): DurableContentBlobReadHandle? {
        val opaqueName = readOpaqueName(blob)
        return if (opaqueName != null) {
            bodyFiles?.openRead(blob.reference, opaqueName)
        } else {
            if (blob.reference.byteSize <= MAXIMUM_CACHED_INLINE_READ_BYTES) {
                readPayload(blob)?.let { payload ->
                    if (payload.size.toLong() != blob.reference.byteSize ||
                        dev.shinsou.kmp.plugin.Sha256.hex(payload) != blob.reference.plaintextDigest
                    ) {
                        throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                    }
                    ByteArrayContentBlobReadHandle(payload)
                }
            } else {
                if (!verifyInlinePayload(blob)) {
                    throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                }
                SqliteInlineContentBlobReadHandle(blob.reference.byteSize) { offset, maximumBytes ->
                    readCurrentRepresentationChunk(blob, offset, maximumBytes)
                }
            }
        }
    }

    override fun verifyPayload(blob: DurableContentBlob): Boolean {
        val opaqueName = readOpaqueName(blob)
        return if (opaqueName != null) {
            bodyFiles?.verify(blob.reference, opaqueName) == true
        } else {
            verifyInlinePayload(blob)
        }
    }

    private fun verifyInlinePayload(blob: DurableContentBlob): Boolean {
        val hasher = ResumableSha256.initial()
        var offset = 0L
        while (offset < blob.reference.byteSize) {
            val chunk = readInlinePayloadChunk(
                blob = blob,
                offset = offset,
                maximumBytes = minOf(
                    INLINE_SQL_CHUNK_BYTES,
                    blob.reference.byteSize - offset,
                ).toInt(),
            ) ?: return false
            if (chunk.isEmpty()) return false
            hasher.update(chunk)
            offset += chunk.size
        }
        return offset == blob.reference.byteSize && hasher.digestHex() == blob.reference.plaintextDigest
    }

    private fun readInlinePayloadChunk(
        blob: DurableContentBlob,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT reference_json, substr(payload, ?, ?), length(payload), opaque_name
            FROM $TABLE_BLOBS WHERE blob_id = ? AND incarnation = ? AND generation = ?
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                val reference = codec.decodeFromString<BlobRef>(requireNotNull(cursor.getString(0)))
                if (reference != blob.reference || cursor.getString(3) != null ||
                    requireNotNull(cursor.getLong(2)) != reference.byteSize
                ) {
                    throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                }
                QueryResult.Value(requireNotNull(cursor.getBytes(1)))
            }
        },
        parameters = 5,
        binders = {
            bindLong(0, offset + 1L)
            bindLong(1, maximumBytes.toLong())
            bindString(2, blob.reference.blobId)
            bindLong(3, blob.incarnation)
            bindLong(4, blob.generation)
        },
    ).value

    private fun readCurrentRepresentationChunk(
        blob: DurableContentBlob,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray? {
        val row = driver.executeQuery(
            identifier = null,
            sql = """
                SELECT reference_json, substr(payload, ?, ?), length(payload), opaque_name
                FROM $TABLE_BLOBS WHERE blob_id = ? AND incarnation = ?
            """.trimIndent(),
            mapper = { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value(null)
                } else {
                    val reference = codec.decodeFromString<BlobRef>(requireNotNull(cursor.getString(0)))
                    if (reference != blob.reference) {
                        throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
                    }
                    QueryResult.Value(
                        CurrentBlobRepresentation(
                            inlineChunk = cursor.getBytes(1) ?: ByteArray(0),
                            inlineSize = requireNotNull(cursor.getLong(2)),
                            opaqueName = cursor.getString(3),
                        ),
                    )
                }
            },
            parameters = 4,
            binders = {
                bindLong(0, offset + 1L)
                bindLong(1, maximumBytes.toLong())
                bindString(2, blob.reference.blobId)
                bindLong(3, blob.incarnation)
            },
        ).value ?: return null
        return if (row.opaqueName == null) {
            if (row.inlineSize != blob.reference.byteSize) {
                throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
            }
            row.inlineChunk
        } else {
            val files = bodyFiles ?: throw ContentBlobStoreException.CorruptBlob(blob.reference.blobId)
            files.readMigrationObjectChunk(blob.reference, row.opaqueName, offset, maximumBytes)
        }
    }

    override fun upsert(blob: DurableContentBlob) {
        val snapshot = blob.immutableCopy()
        val encodedReference = codec.encodeToString(snapshot.reference)
        val payload = snapshot.bytes
        if (payload == null) {
            val updated = driver.execute(
                identifier = null,
                sql = """
                    UPDATE $TABLE_BLOBS
                    SET generation = ?, published_at_epoch_millis = ?, lifecycle_state = ?,
                        discovered_at_epoch_millis = ?
                    WHERE blob_id = ? AND reference_json = ? AND incarnation = ?
                """.trimIndent(),
                parameters = 7,
            ) {
                bindLong(0, snapshot.generation)
                bindLong(1, snapshot.publishedAtEpochMillis)
                bindString(2, snapshot.lifecycleState.name)
                bindLong(3, snapshot.discoveredAtEpochMillis)
                bindString(4, snapshot.reference.blobId)
                bindString(5, encodedReference)
                bindLong(6, snapshot.incarnation)
            }.value
            if (updated != 1L) {
                throw ContentBlobStoreException.CorruptBlob(snapshot.reference.blobId)
            }
            return
        }
        existingMetadata(snapshot.reference.blobId)?.let { durable ->
            if (durable.reference != snapshot.reference || durable.incarnation != snapshot.incarnation ||
                readPayload(durable)?.contentEquals(payload) != true
            ) {
                throw ContentBlobStoreException.CorruptBlob(snapshot.reference.blobId)
            }
        }
        val opaqueName = bodyFiles?.publish(snapshot.reference, payload)
        val sqlitePayload = if (opaqueName == null) payload else EMPTY_SQLITE_PAYLOAD
        upsertRow(snapshot, sqlitePayload, opaqueName)
    }

    private fun upsertRow(snapshot: DurableContentBlob, sqlitePayload: ByteArray, opaqueName: String?) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO $TABLE_BLOBS(
                  blob_id, reference_json, payload, opaque_name, incarnation, generation,
                  published_at_epoch_millis, lifecycle_state, discovered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(blob_id) DO UPDATE SET
                  reference_json = excluded.reference_json,
                  payload = excluded.payload,
                  opaque_name = excluded.opaque_name,
                  incarnation = excluded.incarnation,
                  generation = excluded.generation,
                  published_at_epoch_millis = excluded.published_at_epoch_millis,
                  lifecycle_state = excluded.lifecycle_state,
                  discovered_at_epoch_millis = excluded.discovered_at_epoch_millis
            """.trimIndent(),
            parameters = 9,
        ) {
            bindString(0, snapshot.reference.blobId)
            bindString(1, codec.encodeToString(snapshot.reference))
            bindBytes(2, sqlitePayload)
            bindString(3, opaqueName)
            bindLong(4, snapshot.incarnation)
            bindLong(5, snapshot.generation)
            bindLong(6, snapshot.publishedAtEpochMillis)
            bindString(7, snapshot.lifecycleState.name)
            bindLong(8, snapshot.discoveredAtEpochMillis)
        }.value
    }

    override fun delete(blob: DurableContentBlob) {
        val opaqueName = readOpaqueName(blob)
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_BLOBS WHERE blob_id = ? AND incarnation = ? AND generation = ?",
            parameters = 3,
        ) {
            bindString(0, blob.reference.blobId)
            bindLong(1, blob.incarnation)
            bindLong(2, blob.generation)
        }.value
        if (existingMetadata(blob.reference.blobId) != null) {
            throw ContentBlobStoreException.RecoveryPlanInvalid(
                "Durable blob changed before the recovery sweep",
            )
        }
        if (opaqueName != null) bodyFiles?.delete(blob.reference, opaqueName)
    }

    override fun runStorageMaintenanceSlice(
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult {
        val files = bodyFiles ?: return ContentBlobStorageMaintenanceResult()
        val migration = migrateOneInlineBodySlice(files, request.maximumBytes)
        val crashFiles = inspectCrashFilesSlice(files, request)
        return migration + crashFiles
    }

    private fun migrateOneInlineBodySlice(
        files: AppPrivateContentBlobFiles,
        maximumBytes: Int,
    ): ContentBlobStorageMaintenanceResult {
        var progress = loadInlineMigrationProgress()
        if (progress != null && loadInlineCandidate(progress.blobId) != progress.candidate) {
            files.deleteMigrationStage(progress.stagedName)
            deleteInlineMigrationProgress(progress.blobId)
            progress = null
        }
        if (progress == null) {
            val candidate = loadInlineCandidate() ?: return ContentBlobStorageMaintenanceResult()
            val created = InlineBodyMigrationProgress(
                candidate = candidate,
                stagedName = migrationStagedName(candidate),
                phase = InlineBodyMigrationPhase.COPYING,
                copyOffset = 0L,
                copyHashState = ResumableSha256.initial().snapshot(),
                verifyOffset = 0L,
                verifyHashState = ResumableSha256.initial().snapshot(),
            )
            insertInlineMigrationProgress(created)
            progress = loadInlineMigrationProgress()
                ?: throw ContentBlobStoreException.InvalidStage("Inline body migration was not durable")
        }

        return when (progress.phase) {
            InlineBodyMigrationPhase.COPYING -> copyInlineMigrationSlice(files, progress, maximumBytes)
            InlineBodyMigrationPhase.VERIFYING -> verifyInlineMigrationSlice(files, progress, maximumBytes)
        }
    }

    private fun copyInlineMigrationSlice(
        files: AppPrivateContentBlobFiles,
        progress: InlineBodyMigrationProgress,
        maximumBytes: Int,
    ): ContentBlobStorageMaintenanceResult {
        val candidate = progress.candidate
        val reference = candidate.reference
        val copyHasher = ResumableSha256.restore(progress.copyHashState)
        if (copyHasher.totalBytes != progress.copyOffset || progress.copyOffset > reference.byteSize) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }

        val stagedSize = files.migrationStageSize(progress.stagedName)
        when {
            stagedSize == null && progress.copyOffset != 0L -> {
                resetInlineMigration(files, progress)
                return ContentBlobStorageMaintenanceResult()
            }
            stagedSize != null && stagedSize < progress.copyOffset -> {
                resetInlineMigration(files, progress)
                return ContentBlobStorageMaintenanceResult()
            }
            stagedSize != null && stagedSize > progress.copyOffset ->
                files.truncateMigrationStage(progress.stagedName, progress.copyOffset)
        }

        var copied = 0
        var updated = progress
        if (reference.byteSize == 0L && stagedSize == null) {
            files.createEmptyMigrationStage(progress.stagedName)
        }
        if (progress.copyOffset < reference.byteSize) {
            val count = minOf(maximumBytes.toLong(), reference.byteSize - progress.copyOffset).toInt()
            val chunk = readInlineChunk(candidate, progress.copyOffset, count)
            if (chunk.size != count) throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            files.appendMigrationChunk(progress.stagedName, progress.copyOffset, chunk)
            copyHasher.update(chunk)
            updated = progress.copy(
                copyOffset = progress.copyOffset + chunk.size,
                copyHashState = copyHasher.snapshot(),
            )
            if (!updateInlineMigrationCopy(progress, updated)) {
                throw ContentBlobStoreException.InvalidStage("Inline body migration changed while copying")
            }
            copied = chunk.size
        }

        if (updated.copyOffset != reference.byteSize) {
            return ContentBlobStorageMaintenanceResult(migratedInlineBytes = copied)
        }
        val completedHasher = ResumableSha256.restore(updated.copyHashState)
        if (completedHasher.digestHex() != reference.plaintextDigest) {
            files.deleteMigrationStage(updated.stagedName)
            deleteInlineMigrationProgress(updated.blobId)
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val opaqueName = files.publishMigrationStage(reference, updated.stagedName)
        if (!transitionInlineMigrationToVerification(updated, opaqueName)) {
            throw ContentBlobStoreException.InvalidStage("Inline body migration changed before verification")
        }
        return ContentBlobStorageMaintenanceResult(migratedInlineBytes = copied)
    }

    private fun verifyInlineMigrationSlice(
        files: AppPrivateContentBlobFiles,
        progress: InlineBodyMigrationProgress,
        maximumBytes: Int,
    ): ContentBlobStorageMaintenanceResult {
        val candidate = progress.candidate
        val reference = candidate.reference
        val opaqueName = files.canonicalOpaqueName(reference)
        // A crash may happen after atomic move but before the phase update. Publishing again is
        // idempotent and checks the exact canonical destination metadata.
        files.publishMigrationStage(reference, progress.stagedName)
        val verifyHasher = ResumableSha256.restore(progress.verifyHashState)
        if (verifyHasher.totalBytes != progress.verifyOffset || progress.verifyOffset > reference.byteSize) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }

        var verified = 0
        var updated = progress
        if (progress.verifyOffset < reference.byteSize) {
            val chunk = files.readMigrationObjectChunk(
                reference = reference,
                opaqueName = opaqueName,
                offset = progress.verifyOffset,
                maximumBytes = maximumBytes,
            )
            if (chunk.isEmpty()) throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            verifyHasher.update(chunk)
            updated = progress.copy(
                verifyOffset = progress.verifyOffset + chunk.size,
                verifyHashState = verifyHasher.snapshot(),
            )
            if (!updateInlineMigrationVerification(progress, updated)) {
                throw ContentBlobStoreException.InvalidStage("Inline body migration changed while verifying")
            }
            verified = chunk.size
        }
        if (updated.verifyOffset != reference.byteSize) {
            return ContentBlobStorageMaintenanceResult(migratedInlineBytes = verified)
        }
        if (ResumableSha256.restore(updated.verifyHashState).digestHex() != reference.plaintextDigest) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }

        val migrated = driver.execute(
            identifier = null,
            sql = """
                UPDATE $TABLE_BLOBS SET payload = ?, opaque_name = ?
                WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
                  AND opaque_name IS NULL AND length(payload) = ?
            """.trimIndent(),
            parameters = 7,
        ) {
            bindBytes(0, EMPTY_SQLITE_PAYLOAD)
            bindString(1, opaqueName)
            bindString(2, candidate.reference.blobId)
            bindString(3, candidate.encodedReference)
            bindLong(4, candidate.incarnation)
            bindLong(5, candidate.generation)
            bindLong(6, candidate.reference.byteSize)
        }.value
        if (migrated == 1L) {
            deleteInlineMigrationProgress(candidate.reference.blobId)
            // If a destination pre-existed, publish conservatively retained the completed stage.
            files.deleteMigrationStage(updated.stagedName)
        }
        return ContentBlobStorageMaintenanceResult(
            migratedInlineBytes = verified,
            completedInlineMigrations = if (migrated == 1L) 1 else 0,
        )
    }

    private fun loadInlineCandidate(blobId: String? = null): InlineBodyCandidate? {
        val predicate = if (blobId == null) "opaque_name IS NULL" else "blob_id = ? AND opaque_name IS NULL"
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT blob_id, reference_json, incarnation, generation, length(payload)
                FROM $TABLE_BLOBS WHERE $predicate ORDER BY blob_id LIMIT 1
            """.trimIndent(),
            mapper = { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value(null)
                } else {
                    val id = requireNotNull(cursor.getString(0))
                    val encoded = requireNotNull(cursor.getString(1))
                    val reference = codec.decodeFromString<BlobRef>(encoded)
                    if (reference.blobId != id || requireNotNull(cursor.getLong(4)) != reference.byteSize) {
                        throw ContentBlobStoreException.CorruptBlob(id)
                    }
                    QueryResult.Value(
                        InlineBodyCandidate(
                            reference = reference,
                            encodedReference = encoded,
                            incarnation = requireNotNull(cursor.getLong(2)),
                            generation = requireNotNull(cursor.getLong(3)),
                        ),
                    )
                }
            },
            parameters = if (blobId == null) 0 else 1,
            binders = if (blobId == null) null else ({ bindString(0, blobId) }),
        ).value
    }

    private fun readInlineChunk(
        candidate: InlineBodyCandidate,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT substr(payload, ?, ?)
            FROM $TABLE_BLOBS
            WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
              AND opaque_name IS NULL AND length(payload) = ?
        """.trimIndent(),
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) requireNotNull(cursor.getBytes(0))
                else throw ContentBlobStoreException.CorruptBlob(candidate.reference.blobId),
            )
        },
        parameters = 7,
        binders = {
            bindLong(0, offset + 1L)
            bindLong(1, maximumBytes.toLong())
            bindString(2, candidate.reference.blobId)
            bindString(3, candidate.encodedReference)
            bindLong(4, candidate.incarnation)
            bindLong(5, candidate.generation)
            bindLong(6, candidate.reference.byteSize)
        },
    ).value

    private fun loadInlineMigrationProgress(): InlineBodyMigrationProgress? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT blob_id, reference_json, incarnation, generation, staged_name, phase,
                   copy_offset, copy_hash_state, verify_offset, verify_hash_state
            FROM $TABLE_BLOB_FILE_MIGRATIONS ORDER BY blob_id LIMIT 1
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                val id = requireNotNull(cursor.getString(0))
                val encoded = requireNotNull(cursor.getString(1))
                val reference = codec.decodeFromString<BlobRef>(encoded)
                if (reference.blobId != id) throw ContentBlobStoreException.CorruptBlob(id)
                QueryResult.Value(
                    InlineBodyMigrationProgress(
                        candidate = InlineBodyCandidate(
                            reference = reference,
                            encodedReference = encoded,
                            incarnation = requireNotNull(cursor.getLong(2)),
                            generation = requireNotNull(cursor.getLong(3)),
                        ),
                        stagedName = requireNotNull(cursor.getString(4)),
                        phase = try {
                            InlineBodyMigrationPhase.valueOf(requireNotNull(cursor.getString(5)))
                        } catch (_: IllegalArgumentException) {
                            throw ContentBlobStoreException.CorruptBlob(id)
                        },
                        copyOffset = requireNotNull(cursor.getLong(6)),
                        copyHashState = requireNotNull(cursor.getString(7)),
                        verifyOffset = requireNotNull(cursor.getLong(8)),
                        verifyHashState = requireNotNull(cursor.getString(9)),
                    ),
                )
            }
        },
        parameters = 0,
    ).value

    private fun insertInlineMigrationProgress(progress: InlineBodyMigrationProgress) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT OR IGNORE INTO $TABLE_BLOB_FILE_MIGRATIONS(
                  blob_id, reference_json, incarnation, generation, staged_name, phase,
                  copy_offset, copy_hash_state, verify_offset, verify_hash_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 10,
        ) {
            bindMigrationIdentity(progress)
            bindString(5, progress.phase.name)
            bindLong(6, progress.copyOffset)
            bindString(7, progress.copyHashState)
            bindLong(8, progress.verifyOffset)
            bindString(9, progress.verifyHashState)
        }.value
    }

    private fun updateInlineMigrationCopy(
        previous: InlineBodyMigrationProgress,
        updated: InlineBodyMigrationProgress,
    ): Boolean = driver.execute(
        identifier = null,
        sql = """
            UPDATE $TABLE_BLOB_FILE_MIGRATIONS SET copy_offset = ?, copy_hash_state = ?
            WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
              AND phase = ? AND copy_offset = ? AND copy_hash_state = ?
        """.trimIndent(),
        parameters = 9,
    ) {
        bindLong(0, updated.copyOffset)
        bindString(1, updated.copyHashState)
        bindString(2, previous.blobId)
        bindString(3, previous.candidate.encodedReference)
        bindLong(4, previous.candidate.incarnation)
        bindLong(5, previous.candidate.generation)
        bindString(6, previous.phase.name)
        bindLong(7, previous.copyOffset)
        bindString(8, previous.copyHashState)
    }.value == 1L

    private fun transitionInlineMigrationToVerification(
        progress: InlineBodyMigrationProgress,
        opaqueName: String,
    ): Boolean {
        check(opaqueName == "v1-${progress.candidate.reference.blobId}.blob")
        return driver.execute(
            identifier = null,
            sql = """
                UPDATE $TABLE_BLOB_FILE_MIGRATIONS SET phase = 'VERIFYING'
                WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
                  AND phase = 'COPYING' AND copy_offset = ? AND copy_hash_state = ?
            """.trimIndent(),
            parameters = 6,
        ) {
            bindString(0, progress.blobId)
            bindString(1, progress.candidate.encodedReference)
            bindLong(2, progress.candidate.incarnation)
            bindLong(3, progress.candidate.generation)
            bindLong(4, progress.copyOffset)
            bindString(5, progress.copyHashState)
        }.value == 1L
    }

    private fun updateInlineMigrationVerification(
        previous: InlineBodyMigrationProgress,
        updated: InlineBodyMigrationProgress,
    ): Boolean = driver.execute(
        identifier = null,
        sql = """
            UPDATE $TABLE_BLOB_FILE_MIGRATIONS SET verify_offset = ?, verify_hash_state = ?
            WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
              AND phase = ? AND verify_offset = ? AND verify_hash_state = ?
        """.trimIndent(),
        parameters = 9,
    ) {
        bindLong(0, updated.verifyOffset)
        bindString(1, updated.verifyHashState)
        bindString(2, previous.blobId)
        bindString(3, previous.candidate.encodedReference)
        bindLong(4, previous.candidate.incarnation)
        bindLong(5, previous.candidate.generation)
        bindString(6, previous.phase.name)
        bindLong(7, previous.verifyOffset)
        bindString(8, previous.verifyHashState)
    }.value == 1L

    private fun resetInlineMigration(
        files: AppPrivateContentBlobFiles,
        progress: InlineBodyMigrationProgress,
    ) {
        files.deleteMigrationStage(progress.stagedName)
        val initial = ResumableSha256.initial().snapshot()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE $TABLE_BLOB_FILE_MIGRATIONS
                SET phase = 'COPYING', copy_offset = 0, copy_hash_state = ?,
                    verify_offset = 0, verify_hash_state = ?
                WHERE blob_id = ? AND reference_json = ? AND incarnation = ? AND generation = ?
            """.trimIndent(),
            parameters = 6,
        ) {
            bindString(0, initial)
            bindString(1, initial)
            bindString(2, progress.blobId)
            bindString(3, progress.candidate.encodedReference)
            bindLong(4, progress.candidate.incarnation)
            bindLong(5, progress.candidate.generation)
        }.value
    }

    private fun deleteInlineMigrationProgress(blobId: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_BLOB_FILE_MIGRATIONS WHERE blob_id = ?",
            parameters = 1,
        ) { bindString(0, blobId) }.value
    }

    private fun app.cash.sqldelight.db.SqlPreparedStatement.bindMigrationIdentity(
        progress: InlineBodyMigrationProgress,
    ) {
        bindString(0, progress.blobId)
        bindString(1, progress.candidate.encodedReference)
        bindLong(2, progress.candidate.incarnation)
        bindLong(3, progress.candidate.generation)
        bindString(4, progress.stagedName)
    }

    private fun inspectCrashFilesSlice(
        files: AppPrivateContentBlobFiles,
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult {
        val cursor = loadCrashFileCursor()
        val page = files.scanCrashFiles(cursor.kind, cursor.afterName, request.maximumFiles)
        var discovered = 0
        var removed = 0
        page.entries.forEach { file ->
            val recognized = recognizedCrashFile(file)
            val protected = recognized && isCrashFileProtected(files, file)
            if (!recognized || protected) {
                deleteCrashFileObservation(file.kind, file.opaqueName)
                return@forEach
            }
            val previous = loadCrashFileObservation(file.kind, file.opaqueName)
            if (previous == null || !previous.sameFile(file)) {
                upsertCrashFileObservation(file, request.nowEpochMillis)
                discovered++
                return@forEach
            }
            val age = if (request.nowEpochMillis <= previous.discoveredAtEpochMillis) 0L
            else request.nowEpochMillis - previous.discoveredAtEpochMillis
            if (age >= request.minimumAgeMillis && files.deleteCrashFile(file)) {
                deleteCrashFileObservation(file.kind, file.opaqueName)
                removed++
            }
        }
        storeCrashFileCursor(
            if (page.reachedEnd) cursor.kind.next() else cursor.kind,
            if (page.reachedEnd) null else page.nextAfterName,
        )
        return ContentBlobStorageMaintenanceResult(
            scannedFiles = page.visitedCount,
            discoveredUnknownFiles = discovered,
            removedUnknownFiles = removed,
        )
    }

    private fun recognizedCrashFile(file: ContentBlobCrashFile): Boolean = when (file.kind) {
        ContentBlobCrashFileKind.OBJECT -> blobIdFromCanonicalOpaqueName(file.opaqueName) != null
        ContentBlobCrashFileKind.STAGING ->
            STAGE_FILE_PATTERN.matches(file.opaqueName) || MIGRATION_STAGE_FILE_PATTERN.matches(file.opaqueName)
    }

    private fun isCrashFileProtected(
        files: AppPrivateContentBlobFiles,
        file: ContentBlobCrashFile,
    ): Boolean = when (file.kind) {
        ContentBlobCrashFileKind.OBJECT -> {
            val blobId = blobIdFromCanonicalOpaqueName(file.opaqueName) ?: return false
            driver.executeQuery(
                identifier = null,
                sql = "SELECT reference_json FROM $TABLE_BLOBS WHERE blob_id = ?",
                mapper = { cursor ->
                    if (!cursor.next().value) {
                        QueryResult.Value(false)
                    } else {
                        val reference = codec.decodeFromString<BlobRef>(requireNotNull(cursor.getString(0)))
                        if (reference.blobId != blobId || files.canonicalOpaqueName(reference) != file.opaqueName) {
                            throw ContentBlobStoreException.CorruptBlob(blobId)
                        }
                        QueryResult.Value(true)
                    }
                },
                parameters = 1,
                binders = { bindString(0, blobId) },
            ).value
        }
        ContentBlobCrashFileKind.STAGING -> files.isActiveStage(file.opaqueName) ||
            driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM $TABLE_BLOB_FILE_MIGRATIONS WHERE staged_name = ?",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(requireNotNull(cursor.getLong(0)) != 0L)
                },
                parameters = 1,
                binders = { bindString(0, file.opaqueName) },
            ).value
    }

    private fun loadCrashFileCursor(): ContentBlobCrashFileCursor = driver.executeQuery(
        identifier = null,
        sql = "SELECT scan_kind, scan_after_name FROM $TABLE_BLOB_MAINTENANCE WHERE singleton_id = 1",
        mapper = { cursor ->
            check(cursor.next().value)
            val kind = try {
                ContentBlobCrashFileKind.valueOf(requireNotNull(cursor.getString(0)))
            } catch (_: IllegalArgumentException) {
                throw ContentBlobStoreException.InvalidStage("Invalid blob crash-file scan cursor")
            }
            QueryResult.Value(ContentBlobCrashFileCursor(kind, cursor.getString(1)))
        },
        parameters = 0,
    ).value

    private fun storeCrashFileCursor(kind: ContentBlobCrashFileKind, afterName: String?) {
        driver.execute(
            identifier = null,
            sql = "UPDATE $TABLE_BLOB_MAINTENANCE SET scan_kind = ?, scan_after_name = ? " +
                "WHERE singleton_id = 1",
            parameters = 2,
        ) {
            bindString(0, kind.name)
            bindString(1, afterName)
        }.value
    }

    private fun loadCrashFileObservation(
        kind: ContentBlobCrashFileKind,
        opaqueName: String,
    ): ContentBlobCrashFileObservation? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT byte_size, modified_at_epoch_millis, discovered_at_epoch_millis
            FROM $TABLE_BLOB_FILE_ORPHANS WHERE file_kind = ? AND opaque_name = ?
        """.trimIndent(),
        mapper = { cursor ->
            QueryResult.Value(
                if (!cursor.next().value) null else ContentBlobCrashFileObservation(
                    byteSize = requireNotNull(cursor.getLong(0)),
                    modifiedAtEpochMillis = cursor.getLong(1),
                    discoveredAtEpochMillis = requireNotNull(cursor.getLong(2)),
                ),
            )
        },
        parameters = 2,
        binders = {
            bindString(0, kind.name)
            bindString(1, opaqueName)
        },
    ).value

    private fun upsertCrashFileObservation(file: ContentBlobCrashFile, discoveredAt: Long) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO $TABLE_BLOB_FILE_ORPHANS(
                  file_kind, opaque_name, byte_size, modified_at_epoch_millis,
                  discovered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(file_kind, opaque_name) DO UPDATE SET
                  byte_size = excluded.byte_size,
                  modified_at_epoch_millis = excluded.modified_at_epoch_millis,
                  discovered_at_epoch_millis = excluded.discovered_at_epoch_millis
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, file.kind.name)
            bindString(1, file.opaqueName)
            bindLong(2, file.byteSize)
            bindLong(3, file.modifiedAtEpochMillis)
            bindLong(4, discoveredAt)
        }.value
    }

    private fun deleteCrashFileObservation(kind: ContentBlobCrashFileKind, opaqueName: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_BLOB_FILE_ORPHANS WHERE file_kind = ? AND opaque_name = ?",
            parameters = 2,
        ) {
            bindString(0, kind.name)
            bindString(1, opaqueName)
        }.value
    }

    private fun existingMetadata(blobId: String): DurableContentBlob? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT reference_json, incarnation, generation,
                   published_at_epoch_millis, lifecycle_state, discovered_at_epoch_millis
            FROM $TABLE_BLOBS WHERE blob_id = ?
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                QueryResult.Value(
                    DurableContentBlob(
                        reference = codec.decodeFromString(requireNotNull(cursor.getString(0))),
                        bytes = null,
                        incarnation = requireNotNull(cursor.getLong(1)),
                        generation = requireNotNull(cursor.getLong(2)),
                        publishedAtEpochMillis = requireNotNull(cursor.getLong(3)),
                        lifecycleState = try {
                            BlobLifecycleState.valueOf(requireNotNull(cursor.getString(4)))
                        } catch (error: IllegalArgumentException) {
                            throw ContentBlobStoreException.CorruptBlob(blobId)
                        },
                        discoveredAtEpochMillis = cursor.getLong(5),
                    ),
                )
            }
        },
        parameters = 1,
        binders = { bindString(0, blobId) },
    ).value

    private fun readStoreInstanceId(): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT store_instance_id FROM $TABLE_BLOB_STORE WHERE singleton_id = 1",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    private fun readOpaqueName(blob: DurableContentBlob): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT opaque_name FROM $TABLE_BLOBS " +
            "WHERE blob_id = ? AND incarnation = ? AND generation = ?",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        },
        parameters = 3,
        binders = {
            bindString(0, blob.reference.blobId)
            bindLong(1, blob.incarnation)
            bindLong(2, blob.generation)
        },
    ).value?.also { bodyFiles?.requireCanonicalOpaqueName(blob.reference, it) }
}

private data class InlineBodyCandidate(
    val reference: BlobRef,
    val encodedReference: String,
    val incarnation: Long,
    val generation: Long,
) {
    init {
        reference.validate()
        require(incarnation > 0L)
        require(generation >= 0L)
    }
}

private data class CurrentBlobRepresentation(
    val inlineChunk: ByteArray,
    val inlineSize: Long,
    val opaqueName: String?,
)

private enum class InlineBodyMigrationPhase {
    COPYING,
    VERIFYING,
}

private data class InlineBodyMigrationProgress(
    val candidate: InlineBodyCandidate,
    val stagedName: String,
    val phase: InlineBodyMigrationPhase,
    val copyOffset: Long,
    val copyHashState: String,
    val verifyOffset: Long,
    val verifyHashState: String,
) {
    val blobId: String get() = candidate.reference.blobId
}

private fun migrationStagedName(candidate: InlineBodyCandidate): String =
    "migration-v1-${candidate.reference.blobId}-${candidate.incarnation}-${candidate.generation}.tmp"

private enum class ContentBlobCrashFileKind {
    OBJECT,
    STAGING;

    fun next(): ContentBlobCrashFileKind = if (this == OBJECT) STAGING else OBJECT
}

private data class ContentBlobCrashFile(
    val kind: ContentBlobCrashFileKind,
    val opaqueName: String,
    val byteSize: Long,
    val modifiedAtEpochMillis: Long?,
)

private data class ContentBlobCrashFilePage(
    val entries: List<ContentBlobCrashFile>,
    val nextAfterName: String?,
    val reachedEnd: Boolean,
    val visitedCount: Int = entries.size,
)

private data class ContentBlobCrashFileCursor(
    val kind: ContentBlobCrashFileKind,
    val afterName: String?,
)

private data class ContentBlobCrashFileObservation(
    val byteSize: Long,
    val modifiedAtEpochMillis: Long?,
    val discoveredAtEpochMillis: Long,
) {
    fun sameFile(file: ContentBlobCrashFile): Boolean =
        byteSize == file.byteSize && modifiedAtEpochMillis == file.modifiedAtEpochMillis
}

/** Idempotent tables included in the unified platform database. */
internal object ContentBlobPersistenceSchema {
    fun create(driver: SqlDriver) {
        listOf(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_STORE(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  store_instance_id TEXT NOT NULL UNIQUE
                )
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOBS(
                  blob_id TEXT NOT NULL PRIMARY KEY,
                  reference_json TEXT NOT NULL,
                  payload BLOB NOT NULL,
                  opaque_name TEXT,
                  incarnation INTEGER NOT NULL CHECK(incarnation > 0),
                  generation INTEGER NOT NULL CHECK(generation >= 0),
                  published_at_epoch_millis INTEGER NOT NULL CHECK(published_at_epoch_millis >= 0),
                  lifecycle_state TEXT NOT NULL,
                  discovered_at_epoch_millis INTEGER
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_FILE_MIGRATIONS(
                  blob_id TEXT NOT NULL PRIMARY KEY,
                  reference_json TEXT NOT NULL,
                  incarnation INTEGER NOT NULL CHECK(incarnation > 0),
                  generation INTEGER NOT NULL CHECK(generation >= 0),
                  staged_name TEXT NOT NULL UNIQUE,
                  phase TEXT NOT NULL,
                  copy_offset INTEGER NOT NULL CHECK(copy_offset >= 0),
                  copy_hash_state TEXT NOT NULL,
                  verify_offset INTEGER NOT NULL CHECK(verify_offset >= 0),
                  verify_hash_state TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_FILE_ORPHANS(
                  file_kind TEXT NOT NULL,
                  opaque_name TEXT NOT NULL,
                  byte_size INTEGER NOT NULL CHECK(byte_size >= 0),
                  modified_at_epoch_millis INTEGER,
                  discovered_at_epoch_millis INTEGER NOT NULL CHECK(discovered_at_epoch_millis >= 0),
                  PRIMARY KEY(file_kind, opaque_name)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_MAINTENANCE(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  scan_kind TEXT NOT NULL,
                  scan_after_name TEXT
                )
            """.trimIndent(),
        ).forEach { sql -> driver.execute(null, sql, 0).value }
        driver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO $TABLE_BLOB_MAINTENANCE(" +
                "singleton_id, scan_kind, scan_after_name) VALUES (1, 'OBJECT', NULL)",
            parameters = 0,
        ).value
        if (!hasColumn(driver, TABLE_BLOBS, "opaque_name")) {
            driver.execute(
                identifier = null,
                sql = "ALTER TABLE $TABLE_BLOBS ADD COLUMN opaque_name TEXT",
                parameters = 0,
            ).value
        }
    }

    private fun hasColumn(driver: SqlDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                var found = false
                while (cursor.next().value) {
                    if (cursor.getString(1) == column) found = true
                }
                QueryResult.Value(found)
            },
            parameters = 0,
        ).value
}

/**
 * Device-local immutable body adapter. SQLite contains only lifecycle metadata and this adapter's
 * canonical opaque name. A complete temporary file is flushed and closed before atomic publish.
 */
private class AppPrivateContentBlobFiles(
    rootPath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val root: Path = rootPath.toPath(normalize = true)
    private val objects: Path = root.resolve("objects")
    private val staging: Path = root.resolve("staging")
    /** Access is serialized by the owning blob state machine. */
    private val activeStageNames = LinkedHashSet<String>()

    init {
        require(root.isAbsolute) { "Content blob directory must be absolute" }
        fileSystem.createDirectories(objects)
        fileSystem.createDirectories(staging)
        // Directory enumeration is intentionally absent here. Crash files and legacy inline
        // bodies are handled only by the bounded background maintenance slice.
    }

    fun beginStage(
        expectedSizeBytes: Long?,
        mediaType: String,
        maximumBlobSizeBytes: Long,
    ): DurableContentBlobStageHandle {
        val name = "stage-${randomContentUuid()}.tmp"
        check(activeStageNames.add(name)) { "Generated a duplicate blob staging name" }
        return try {
            FileStage(
                expectedSizeBytes = expectedSizeBytes,
                mediaType = mediaType,
                maximumBlobSizeBytes = maximumBlobSizeBytes,
                temporary = staging.resolve(name),
                stagingName = name,
            )
        } catch (failure: Throwable) {
            activeStageNames.remove(name)
            throw failure
        }
    }

    fun scanCrashFiles(
        kind: ContentBlobCrashFileKind,
        afterName: String?,
        maximumFiles: Int,
    ): ContentBlobCrashFilePage {
        require(maximumFiles > 0)
        val directory = if (kind == ContentBlobCrashFileKind.OBJECT) objects else staging
        val paths = fileSystem.listOrNull(directory).orEmpty()
            .sortedBy(Path::name)
        val remaining = if (afterName == null) paths else paths.filter { it.name > afterName }
        val selected = remaining.take(maximumFiles)
        val reachedEnd = selected.size == remaining.size
        val entries = selected.mapNotNull { path ->
            val metadata = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
            if (!metadata.isRegularFile || metadata.symlinkTarget != null) return@mapNotNull null
            val size = metadata.size ?: return@mapNotNull null
            if (size < 0L) return@mapNotNull null
            ContentBlobCrashFile(
                kind = kind,
                opaqueName = path.name,
                byteSize = size,
                modifiedAtEpochMillis = metadata.lastModifiedAtMillis,
            )
        }
        return ContentBlobCrashFilePage(
            entries = entries,
            nextAfterName = if (reachedEnd) null else selected.lastOrNull()?.name ?: afterName,
            reachedEnd = reachedEnd,
            visitedCount = selected.size,
        )
    }

    fun isActiveStage(opaqueName: String): Boolean = opaqueName in activeStageNames

    fun deleteCrashFile(file: ContentBlobCrashFile): Boolean {
        val directory = if (file.kind == ContentBlobCrashFileKind.OBJECT) objects else staging
        val path = directory.resolve(file.opaqueName)
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile || metadata.symlinkTarget != null ||
            metadata.size != file.byteSize || metadata.lastModifiedAtMillis != file.modifiedAtEpochMillis
        ) {
            return false
        }
        fileSystem.delete(path, mustExist = false)
        return !fileSystem.exists(path)
    }

    fun migrationStageSize(stagedName: String): Long? {
        requireSafeMaintenanceName(stagedName)
        val metadata = fileSystem.metadataOrNull(staging.resolve(stagedName)) ?: return null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null) {
            throw ContentBlobStoreException.InvalidStage("Blob migration stage is not a regular file")
        }
        return metadata.size
    }

    fun truncateMigrationStage(stagedName: String, byteSize: Long) {
        requireSafeMaintenanceName(stagedName)
        require(byteSize >= 0L)
        val path = staging.resolve(stagedName)
        val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
        try {
            handle.resize(byteSize)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun deleteMigrationStage(stagedName: String) {
        requireSafeMaintenanceName(stagedName)
        fileSystem.delete(staging.resolve(stagedName), mustExist = false)
    }

    fun appendMigrationChunk(stagedName: String, expectedOffset: Long, chunk: ByteArray) {
        requireSafeMaintenanceName(stagedName)
        require(expectedOffset >= 0L)
        require(chunk.isNotEmpty())
        val path = staging.resolve(stagedName)
        val handle = fileSystem.openReadWrite(
            path,
            mustCreate = expectedOffset == 0L && !fileSystem.exists(path),
            mustExist = expectedOffset != 0L || fileSystem.exists(path),
        )
        try {
            if (handle.size() != expectedOffset) {
                throw ContentBlobStoreException.InvalidStage("Blob migration stage offset changed")
            }
            handle.write(expectedOffset, chunk, 0, chunk.size)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun createEmptyMigrationStage(stagedName: String) {
        requireSafeMaintenanceName(stagedName)
        val path = staging.resolve(stagedName)
        if (fileSystem.exists(path)) return
        val handle = fileSystem.openReadWrite(path, mustCreate = true, mustExist = false)
        try {
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun publishMigrationStage(reference: BlobRef, stagedName: String): String {
        requireSafeMaintenanceName(stagedName)
        val opaqueName = canonicalOpaqueName(reference)
        val source = staging.resolve(stagedName)
        val destination = objects.resolve(opaqueName)
        val destinationMetadata = fileSystem.metadataOrNull(destination)
        if (destinationMetadata == null) {
            val sourceMetadata = fileSystem.metadataOrNull(source)
                ?: throw ContentBlobStoreException.InvalidStage("Completed blob migration stage is missing")
            if (!sourceMetadata.isRegularFile || sourceMetadata.symlinkTarget != null ||
                sourceMetadata.size != reference.byteSize
            ) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            fileSystem.atomicMove(source, destination)
        } else if (!destinationMetadata.isRegularFile || destinationMetadata.symlinkTarget != null ||
            destinationMetadata.size != reference.byteSize
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val published = fileSystem.metadataOrNull(destination)
        if (published == null || !published.isRegularFile || published.symlinkTarget != null ||
            published.size != reference.byteSize
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        return opaqueName
    }

    fun readMigrationObjectChunk(
        reference: BlobRef,
        opaqueName: String,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray {
        requireCanonicalOpaqueName(reference, opaqueName)
        require(offset in 0..reference.byteSize)
        require(maximumBytes > 0)
        val count = minOf(maximumBytes.toLong(), reference.byteSize - offset).toInt()
        if (count == 0) return ByteArray(0)
        val handle = fileSystem.openReadOnly(objects.resolve(opaqueName))
        return try {
            if (handle.size() != reference.byteSize) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            ByteArray(count).also { output ->
                val read = handle.read(offset, output, 0, count)
                if (read != count) throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
        } finally {
            handle.close()
        }
    }

    private fun requireSafeMaintenanceName(value: String) {
        require(value.isNotBlank() && value.length <= 256 && '/' !in value && '\\' !in value) {
            "Unsafe blob maintenance filename"
        }
        require(value != "." && value != "..") { "Unsafe blob maintenance filename" }
    }

    fun publish(reference: BlobRef, pending: DurableContentBlobPendingHandle): String {
        val filePending = pending as? FilePending
            ?: throw ContentBlobStoreException.InvalidStage("Pending body belongs to another durability adapter")
        if (filePending.stage.owner !== this || filePending.reference != reference) {
            throw ContentBlobStoreException.InvalidStage("Pending body identity does not match this blob store")
        }
        return filePending.stage.publish(reference)
    }

    fun publish(reference: BlobRef, bytes: ByteArray): String {
        reference.validate()
        if (bytes.size.toLong() != reference.byteSize ||
            dev.shinsou.kmp.plugin.Sha256.hex(bytes) != reference.plaintextDigest
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val opaqueName = canonicalOpaqueName(reference)
        val destination = objects.resolve(opaqueName)
        if (fileSystem.exists(destination)) {
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            return opaqueName
        }

        val temporary = staging.resolve("stage-${randomContentUuid()}.tmp")
        try {
            val handle = fileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false)
            try {
                val sink = handle.sink().buffer()
                try {
                    sink.write(bytes)
                    sink.flush()
                    handle.flush()
                } finally {
                    sink.close()
                }
            } finally {
                handle.close()
            }
            if (!verify(reference, temporary)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            fileSystem.atomicMove(temporary, destination)
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            return opaqueName
        } catch (failure: Throwable) {
            runCatching { fileSystem.delete(temporary, mustExist = false) }
            throw failure
        }
    }

    fun read(reference: BlobRef, opaqueName: String): ByteArray? {
        requireCanonicalOpaqueName(reference, opaqueName)
        val path = objects.resolve(opaqueName)
        if (!fileSystem.exists(path) || !verify(reference, path)) return null
        return fileSystem.read(path) { readByteArray() }
    }

    fun openRead(reference: BlobRef, opaqueName: String): DurableContentBlobReadHandle? {
        requireCanonicalOpaqueName(reference, opaqueName)
        val path = objects.resolve(opaqueName)
        if (!verify(reference, path)) return null
        return FileReadHandle(fileSystem.source(path).buffer(), reference.byteSize)
    }

    fun delete(reference: BlobRef, opaqueName: String) {
        requireCanonicalOpaqueName(reference, opaqueName)
        fileSystem.delete(objects.resolve(opaqueName), mustExist = false)
    }

    fun requireCanonicalOpaqueName(reference: BlobRef, opaqueName: String) {
        if (opaqueName != canonicalOpaqueName(reference)) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
    }

    fun verify(reference: BlobRef, opaqueName: String): Boolean {
        requireCanonicalOpaqueName(reference, opaqueName)
        return verify(reference, objects.resolve(opaqueName))
    }

    private fun verify(reference: BlobRef, path: Path): Boolean {
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || metadata.size != reference.byteSize) {
            return false
        }
        val hashing = HashingSource.sha256(fileSystem.source(path))
        val scratch = Buffer()
        return try {
            while (hashing.read(scratch, VERIFY_CHUNK_BYTES) != -1L) scratch.clear()
            hashing.hash.hex() == reference.plaintextDigest
        } finally {
            hashing.close()
        }
    }

    fun canonicalOpaqueName(reference: BlobRef): String = "v1-${reference.blobId}.blob"

    private inner class FileStage(
        override val expectedSizeBytes: Long?,
        private val mediaType: String,
        private val maximumBlobSizeBytes: Long,
        private val temporary: Path,
        private val stagingName: String,
    ) : DurableContentBlobStageHandle {
        val owner: AppPrivateContentBlobFiles get() = this@AppPrivateContentBlobFiles
        private val fileHandle = fileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false)
        private val hashingSink = HashingSink.sha256(fileHandle.sink())
        private val sink = hashingSink.buffer()
        override var bytesWritten: Long = 0L
            private set
        override var isSealed: Boolean = false
            private set
        private var closed = false
        private var published = false

        override fun append(chunk: ByteArray) {
            if (closed || isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is closed")
            }
            if (chunk.isEmpty()) return
            val next = checkedFileSize(bytesWritten, chunk.size.toLong(), maximumBlobSizeBytes)
            if (expectedSizeBytes != null && next > expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, next)
            }
            sink.write(chunk)
            bytesWritten = next
        }

        override fun seal(
            expected: BlobRef?,
            blobIdFactory: () -> String,
        ): DurableContentBlobPendingHandle {
            if (closed || isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is already closed")
            }
            if (expectedSizeBytes != null && bytesWritten != expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, bytesWritten)
            }
            sink.flush()
            fileHandle.flush()
            val digest = hashingSink.hash.hex()
            sink.close()
            fileHandle.close()
            closed = true
            val reference = expected ?: BlobRef(
                blobId = blobIdFactory(),
                schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                digestAlgorithm = BlobRef.SHA_256,
                plaintextDigest = digest,
                byteSize = bytesWritten,
                mediaType = mediaType,
            )
            reference.validate()
            if (reference.byteSize != bytesWritten) {
                throw ContentBlobStoreException.SizeMismatch(reference.byteSize, bytesWritten)
            }
            if (reference.plaintextDigest != digest) {
                throw ContentBlobStoreException.DigestMismatch(reference.plaintextDigest, digest)
            }
            if (reference.mediaType != mediaType) {
                throw ContentBlobStoreException.InvalidStage("Blob media type does not match its stage")
            }
            if (!verify(reference, temporary)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            isSealed = true
            return FilePending(this, reference)
        }

        override fun abort() {
            if (published) return
            if (!closed) {
                runCatching { sink.close() }
                runCatching { fileHandle.close() }
                closed = true
            }
            fileSystem.delete(temporary, mustExist = false)
            activeStageNames.remove(stagingName)
        }

        fun publish(reference: BlobRef): String {
            if (!isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is not a sealed live candidate")
            }
            val opaqueName = canonicalOpaqueName(reference)
            val destination = objects.resolve(opaqueName)
            if (fileSystem.exists(destination)) {
                if (!verify(reference, destination)) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
                fileSystem.delete(temporary, mustExist = false)
            } else {
                fileSystem.atomicMove(temporary, destination)
            }
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            published = true
            activeStageNames.remove(stagingName)
            return opaqueName
        }
    }

    private data class FilePending(
        override val stage: FileStage,
        override val reference: BlobRef,
    ) : DurableContentBlobPendingHandle

    private class FileReadHandle(
        private val source: BufferedSource,
        private val byteSize: Long,
    ) : DurableContentBlobReadHandle {
        private var offset = 0L
        private var closed = false

        override fun readChunk(maxBytes: Int): ByteArray? {
            if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
            require(maxBytes > 0) { "Read chunk size must be positive" }
            if (offset == byteSize) return null
            val count = minOf(maxBytes.toLong(), byteSize - offset)
            return source.readByteArray(count).also { offset += it.size }
        }

        override fun close() {
            if (!closed) {
                closed = true
                source.close()
            }
        }
    }

    private companion object {
        const val VERIFY_CHUNK_BYTES: Long = 64L * 1024L
    }
}

private class SqliteInlineContentBlobReadHandle(
    private val byteSize: Long,
    private val readAt: (offset: Long, maximumBytes: Int) -> ByteArray?,
) : DurableContentBlobReadHandle {
    private var offset = 0L
    private var closed = false

    override fun readChunk(maxBytes: Int): ByteArray? {
        if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
        require(maxBytes > 0) { "Read chunk size must be positive" }
        if (offset == byteSize) return null
        val allowed = minOf(maxBytes.toLong(), byteSize - offset).toInt()
        val chunk = readAt(offset, allowed)
            ?: throw ContentBlobStoreException.InvalidStage("Inline blob disappeared during a read lease")
        if (chunk.isEmpty() || chunk.size > allowed || offset + chunk.size > byteSize) {
            throw ContentBlobStoreException.InvalidStage("Inline blob reader returned an invalid chunk")
        }
        offset += chunk.size
        return chunk
    }

    override fun close() {
        closed = true
    }
}

private class ByteArrayContentBlobReadHandle(
    private val bytes: ByteArray,
) : DurableContentBlobReadHandle {
    private var offset = 0
    private var closed = false

    override fun readChunk(maxBytes: Int): ByteArray? {
        if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
        require(maxBytes > 0) { "Read chunk size must be positive" }
        if (offset == bytes.size) return null
        val end = minOf(bytes.size, offset + maxBytes)
        return bytes.copyOfRange(offset, end).also { offset = end }
    }

    override fun close() {
        closed = true
    }
}

/**
 * Small exportable SHA-256 state used to keep each legacy-body copy/verification slice bounded.
 * Only the eight chaining words and the sub-64-byte tail are persisted; body bytes never enter
 * migration metadata.
 */
private class ResumableSha256 private constructor(
    private val words: IntArray,
    private var tail: ByteArray,
    var totalBytes: Long,
) {
    fun update(input: ByteArray) {
        if (input.isEmpty()) return
        require(totalBytes <= Long.MAX_VALUE - input.size.toLong()) { "SHA-256 input is too large" }
        var offset = 0
        if (tail.isNotEmpty()) {
            val needed = 64 - tail.size
            val copied = minOf(needed, input.size)
            val joined = ByteArray(tail.size + copied)
            tail.copyInto(joined)
            input.copyInto(joined, tail.size, 0, copied)
            tail = joined
            offset += copied
            if (tail.size == 64) {
                processBlock(tail, 0)
                tail = ByteArray(0)
            }
        }
        while (input.size - offset >= 64) {
            processBlock(input, offset)
            offset += 64
        }
        if (offset < input.size) tail = input.copyOfRange(offset, input.size)
        totalBytes += input.size.toLong()
    }

    fun snapshot(): String = buildString {
        append("v1:")
        append(totalBytes)
        append(':')
        words.forEach { append(it.toUInt().toString(16).padStart(8, '0')) }
        append(':')
        if (tail.isEmpty()) append('-') else tail.forEach {
            append((it.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    fun digestHex(): String {
        require(totalBytes <= Long.MAX_VALUE / 8L) { "SHA-256 input is too large" }
        val finished = ResumableSha256(words.copyOf(), tail.copyOf(), totalBytes)
        val paddedSize = if (finished.tail.size < 56) 64 else 128
        val padding = ByteArray(paddedSize)
        finished.tail.copyInto(padding)
        padding[finished.tail.size] = 0x80.toByte()
        val bitLength = totalBytes * 8L
        for (index in 0 until 8) {
            padding[padding.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }
        var offset = 0
        while (offset < padding.size) {
            finished.processBlock(padding, offset)
            offset += 64
        }
        return finished.words.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun processBlock(block: ByteArray, offset: Int) {
        val schedule = IntArray(64)
        for (index in 0 until 16) {
            val at = offset + index * 4
            schedule[index] = ((block[at].toInt() and 0xff) shl 24) or
                ((block[at + 1].toInt() and 0xff) shl 16) or
                ((block[at + 2].toInt() and 0xff) shl 8) or
                (block[at + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val left = rotateRight(schedule[index - 15], 7) xor
                rotateRight(schedule[index - 15], 18) xor (schedule[index - 15] ushr 3)
            val right = rotateRight(schedule[index - 2], 17) xor
                rotateRight(schedule[index - 2], 19) xor (schedule[index - 2] ushr 10)
            schedule[index] = schedule[index - 16] + left + schedule[index - 7] + right
        }
        var a = words[0]
        var b = words[1]
        var c = words[2]
        var d = words[3]
        var e = words[4]
        var f = words[5]
        var g = words[6]
        var h = words[7]
        for (index in 0 until 64) {
            val bigSigma1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val first = h + bigSigma1 + choose + CONSTANTS[index] + schedule[index]
            val bigSigma0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val second = bigSigma0 + majority
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + second
        }
        words[0] += a
        words[1] += b
        words[2] += c
        words[3] += d
        words[4] += e
        words[5] += f
        words[6] += g
        words[7] += h
    }

    companion object {
        fun initial(): ResumableSha256 = ResumableSha256(INITIAL.copyOf(), ByteArray(0), 0L)

        fun restore(snapshot: String): ResumableSha256 {
            val parts = snapshot.split(':')
            require(parts.size == 4 && parts[0] == "v1") { "Invalid SHA-256 migration state" }
            val total = parts[1].toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("Invalid SHA-256 migration byte count")
            require(parts[2].length == 64) { "Invalid SHA-256 migration chaining state" }
            val words = IntArray(8) { index ->
                parts[2].substring(index * 8, index * 8 + 8).toUInt(16).toInt()
            }
            val tail = if (parts[3] == "-") ByteArray(0) else decodeHex(parts[3])
            require(tail.size < 64 && tail.size.toLong() == total % 64L) {
                "Invalid SHA-256 migration tail"
            }
            return ResumableSha256(words, tail, total)
        }

        private fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid SHA-256 migration tail encoding"
            }
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private fun rotateRight(value: Int, count: Int): Int =
            (value ushr count) or (value shl (32 - count))

        private val INITIAL = intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
            0x510e527f,
            0x9b05688c.toInt(),
            0x1f83d9ab,
            0x5be0cd19,
        )

        private val CONSTANTS = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}

/** Focused contract seam proving persisted hash snapshots match the host SHA-256 implementation. */
internal fun resumableContentBlobSha256ForTesting(chunks: List<ByteArray>): String {
    var hasher = ResumableSha256.initial()
    chunks.forEach { chunk ->
        hasher.update(chunk)
        hasher = ResumableSha256.restore(hasher.snapshot())
    }
    return hasher.digestHex()
}

private fun checkedFileSize(current: Long, added: Long, maximum: Long): Long {
    if (current < 0 || added < 0 || added > Long.MAX_VALUE - current) {
        throw ContentBlobStoreException.SizeLimitExceeded(Long.MAX_VALUE, maximum)
    }
    val next = current + added
    if (next > maximum) throw ContentBlobStoreException.SizeLimitExceeded(next, maximum)
    return next
}

private fun requireSqlIdentity(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 256) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}

private fun randomContentUuid(): String {
    val bytes = Random.Default.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private const val TABLE_BLOB_STORE = "content_blob_store"
private const val TABLE_BLOBS = "content_blobs"
private const val TABLE_BLOB_FILE_MIGRATIONS = "content_blob_file_migrations"
private const val TABLE_BLOB_FILE_ORPHANS = "content_blob_file_orphans"
private const val TABLE_BLOB_MAINTENANCE = "content_blob_maintenance"
private const val INLINE_SQL_CHUNK_BYTES: Long = 64L * 1024L
private const val MAXIMUM_CACHED_INLINE_READ_BYTES: Long = INLINE_SQL_CHUNK_BYTES
private val EMPTY_SQLITE_PAYLOAD = ByteArray(0)

private val CANONICAL_BLOB_OBJECT_PATTERN = Regex(
    "^v1-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\.blob$",
)
private val STAGE_FILE_PATTERN = Regex(
    "^stage-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.tmp$",
)
private val MIGRATION_STAGE_FILE_PATTERN = Regex(
    "^migration-v1-[0-9a-f-]{36}-[1-9][0-9]*-[0-9]+\\.tmp$",
)

private fun blobIdFromCanonicalOpaqueName(value: String): String? =
    CANONICAL_BLOB_OBJECT_PATTERN.matchEntire(value)?.groupValues?.get(1)

private val ContentBlobPersistenceJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
