package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.shinsou.kmp.domain.content.BlobRef

// R4-4 (docs/architecture/0006): moved out of SqlDriverContentBlobStore.kt without changing it.

internal class SqlDriverContentBlobDurability(
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
            driver.executeStatement(
                sql = "INSERT OR IGNORE INTO $TABLE_BLOB_STORE(singleton_id, store_instance_id) VALUES (1, ?)",
                parameters = 1,
            ) { bindString(0, created) }.value
            readStoreInstanceId() ?: error("Content blob store identity was not durable after insertion")
        }
        requireSqlIdentity(storeInstanceId, "Content blob store instance id")
    }

    override fun loadMetadata(): List<DurableContentBlob> = driver.queryStatement(
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

    override fun readPayload(blob: DurableContentBlob): ByteArray? = driver.queryStatement(
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
                        dev.shinsou.kmp.crypto.Sha256.hex(payload) != blob.reference.plaintextDigest
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
    ): ByteArray? = driver.queryStatement(
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
        val row = driver.queryStatement(
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
            val updated = driver.executeStatement(
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
        // SqliteUpsert: Android 10's SQLite has no `ON CONFLICT ... DO UPDATE`.
        blobUpsert.run(
            listOf(
                snapshot.reference.blobId,
                codec.encodeToString(snapshot.reference),
                sqlitePayload,
                opaqueName,
                snapshot.incarnation,
                snapshot.generation,
                snapshot.publishedAtEpochMillis,
                snapshot.lifecycleState.name,
                snapshot.discoveredAtEpochMillis,
            ),
        ) { sql, parameters, bind -> driver.executeStatement(sql = sql, parameters = parameters, binders = bind).value }
    }

    override fun delete(blob: DurableContentBlob) {
        val opaqueName = readOpaqueName(blob)
        driver.executeStatement(
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

        val migrated = driver.executeStatement(
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
        return driver.queryStatement(
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
    ): ByteArray = driver.queryStatement(
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

    private fun loadInlineMigrationProgress(): InlineBodyMigrationProgress? = driver.queryStatement(
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
                            // An unknown stored phase name means the row is corrupt.
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
        driver.executeStatement(
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
    ): Boolean = driver.executeStatement(
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
        return driver.executeStatement(
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
    ): Boolean = driver.executeStatement(
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
        driver.executeStatement(
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
        driver.executeStatement(
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
            driver.queryStatement(
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
            driver.queryStatement(
                sql = "SELECT count(*) FROM $TABLE_BLOB_FILE_MIGRATIONS WHERE staged_name = ?",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(requireNotNull(cursor.getLong(0)) != 0L)
                },
                parameters = 1,
                binders = { bindString(0, file.opaqueName) },
            ).value
    }

    private fun loadCrashFileCursor(): ContentBlobCrashFileCursor = driver.queryStatement(
        sql = "SELECT scan_kind, scan_after_name FROM $TABLE_BLOB_MAINTENANCE WHERE singleton_id = 1",
        mapper = { cursor ->
            check(cursor.next().value)
            val kind = try {
                ContentBlobCrashFileKind.valueOf(requireNotNull(cursor.getString(0)))
            } catch (_: IllegalArgumentException) {
                // An unknown stored scan kind means the cursor is invalid.
                throw ContentBlobStoreException.InvalidStage("Invalid blob crash-file scan cursor")
            }
            QueryResult.Value(ContentBlobCrashFileCursor(kind, cursor.getString(1)))
        },
        parameters = 0,
    ).value

    private fun storeCrashFileCursor(kind: ContentBlobCrashFileKind, afterName: String?) {
        driver.executeStatement(
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
    ): ContentBlobCrashFileObservation? = driver.queryStatement(
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
        // SqliteUpsert: Android 10's SQLite has no `ON CONFLICT ... DO UPDATE`.
        crashFileUpsert.run(
            listOf(file.kind.name, file.opaqueName, file.byteSize, file.modifiedAtEpochMillis, discoveredAt),
        ) { sql, parameters, bind -> driver.executeStatement(sql = sql, parameters = parameters, binders = bind).value }
    }

    private fun deleteCrashFileObservation(kind: ContentBlobCrashFileKind, opaqueName: String) {
        driver.executeStatement(
            sql = "DELETE FROM $TABLE_BLOB_FILE_ORPHANS WHERE file_kind = ? AND opaque_name = ?",
            parameters = 2,
        ) {
            bindString(0, kind.name)
            bindString(1, opaqueName)
        }.value
    }

    private fun existingMetadata(blobId: String): DurableContentBlob? = driver.queryStatement(
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

    private fun readStoreInstanceId(): String? = driver.queryStatement(
        sql = "SELECT store_instance_id FROM $TABLE_BLOB_STORE WHERE singleton_id = 1",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    private fun readOpaqueName(blob: DurableContentBlob): String? = driver.queryStatement(
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

private fun requireSqlIdentity(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 256) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}

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

private val blobUpsert = SqliteUpsert(
    TABLE_BLOBS,
    listOf(
        "blob_id", "reference_json", "payload", "opaque_name", "incarnation", "generation",
        "published_at_epoch_millis", "lifecycle_state", "discovered_at_epoch_millis",
    ),
    keyCount = 1,
)

private val crashFileUpsert = SqliteUpsert(
    TABLE_BLOB_FILE_ORPHANS,
    listOf("file_kind", "opaque_name", "byte_size", "modified_at_epoch_millis", "discovered_at_epoch_millis"),
    keyCount = 2,
)
