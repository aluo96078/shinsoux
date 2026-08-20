package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.sync.v2.ArchivedSealedEvent
import dev.shinsou.kmp.sync.v2.CategoryMembershipKey
import dev.shinsou.kmp.sync.v2.GenesisCheckpointSeed
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.KeyEpochMetadata
import dev.shinsou.kmp.sync.v2.KeyEpochPruningIntent
import dev.shinsou.kmp.sync.v2.LocalSyncStoreState
import dev.shinsou.kmp.sync.v2.LwwRegister
import dev.shinsou.kmp.sync.v2.MaterializationIssue
import dev.shinsou.kmp.sync.v2.ReadingProgressState
import dev.shinsou.kmp.sync.v2.RepositoryTrustConfirmation
import dev.shinsou.kmp.sync.v2.SealedOutboxEvent
import dev.shinsou.kmp.sync.v2.SealingIntent
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SyncEntityRecord
import dev.shinsou.kmp.sync.v2.SyncIdentityMap
import dev.shinsou.kmp.sync.v2.SyncIdentityMapping
import dev.shinsou.kmp.sync.v2.SyncReceipt
import dev.shinsou.kmp.sync.v2.SyncState
import dev.shinsou.kmp.sync.v2.SyncStatePersistence
import dev.shinsou.kmp.sync.v2.SyncValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * SQLite/WAL persistence for the local sync journal.
 *
 * Format v2 deliberately keeps every independently changing entry in its own row. A normal draft,
 * receipt, cursor, or projection update therefore does not serialize and replace the complete
 * replica. All changed rows and their scalar manifest still commit in one SQLite transaction.
 * Secret key bytes are references only and never enter this database.
 */
class SqlDriverSyncStatePersistence(
    private val driver: SqlDriver,
    json: Json = SyncPersistenceJson,
) : SyncStatePersistence {
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
    }
    private val transactions = object : SuspendingTransacterImpl(driver) {}
    private var cacheInitialized = false
    private var cachedState: LocalSyncStoreState? = null
    private var cachedV2 = false

    init {
        // Desktop opens a bare JDBC driver, while Android and Native normally call SqlSchema first.
        // IF NOT EXISTS makes the same constructor safe for both paths and preserves a v1 row.
        SyncLocalSchema.create(driver).value
        val journalMode = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode = WAL",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
            parameters = 0,
        ).value
        check(journalMode.equals("wal", ignoreCase = true)) {
            "SQLite did not enable WAL for the local sync store"
        }
        driver.execute(null, "PRAGMA synchronous = FULL", 0).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
    }

    override suspend fun load(): LocalSyncStoreState? {
        loadV2OrNull()?.let { state ->
            cachedState = state
            cachedV2 = true
            cacheInitialized = true
            return state
        }

        val legacy = loadLegacyV1OrNull()
        if (legacy == null) {
            cachedState = null
            cachedV2 = false
            cacheInitialized = true
            return null
        }

        // Lazy migration is one transaction. Until it commits, the untouched v1 singleton remains
        // a complete recovery source. The final statement removes it only after all v2 shards and
        // the v2 manifest have been written.
        persistDiff(previous = null, next = legacy, forceMeta = true, deleteLegacy = true)
        cachedState = legacy
        cachedV2 = true
        cacheInitialized = true
        return legacy
    }

    override suspend fun saveAtomically(state: LocalSyncStoreState) {
        val validated = state.validate()
        if (!cacheInitialized) load()
        val previous = cachedState
        persistDiff(
            previous = previous,
            next = validated,
            forceMeta = !cachedV2,
            deleteLegacy = !cachedV2,
        )
        // Never advance the diff baseline before SQLite reports a successful commit.
        cachedState = validated
        cachedV2 = true
        cacheInitialized = true
    }

    fun close() = driver.close()

    private suspend fun loadV2OrNull(): LocalSyncStoreState? {
        val meta = selectSingleton(
            table = TABLE_META,
            payloadColumn = "scalars_json",
        ) ?: return null
        if (meta.first != LOCAL_SYNC_FORMAT_VERSION) {
            throw corrupt("Unsupported local sync database format ${meta.first}")
        }
        val encodedScalars = meta.second ?: throw corrupt("The v2 scalar manifest is null")

        return decodeDurable("The v2 local sync database contains invalid or incomplete shards") {
            val scalars = codec.decodeFromString(LocalSyncScalars.serializer(), encodedScalars)
            check(
                codec.encodeToJsonElement(LocalSyncScalars.serializer(), scalars) ==
                    codec.parseToJsonElement(encodedScalars),
            ) { "The v2 scalar manifest is incomplete or non-canonical" }
            val counts = scalars.shardCounts

            val entities = readShardTable(
                TABLE_ENTITIES,
                counts.entities,
                EntityShard.serializer(),
            ) { it.key.stableString() }.toUniqueMap("replica entity", EntityShard::key, EntityShard::record)
            entities.forEach { (key, record) ->
                check(key == record.key) { "Replica entity map key/body mismatch" }
            }

            val memberships = readShardTable(
                TABLE_MEMBERSHIPS,
                counts.memberships,
                MembershipShard.serializer(),
            ) { membershipRowKey(it.key) }.toUniqueMap(
                "category membership",
                MembershipShard::key,
                MembershipShard::value,
            )

            val progress = readShardTable(
                TABLE_READING_PROGRESS,
                counts.readingProgress,
                ReadingProgressShard.serializer(),
            ) { it.key.stableString() }.toUniqueMap(
                "reading progress",
                ReadingProgressShard::key,
                ReadingProgressShard::value,
            )
            progress.forEach { (key, value) ->
                check(key == value.chapterKey) { "Reading-progress map key/body mismatch" }
            }

            val settings = readShardTable(
                TABLE_SETTINGS,
                counts.settings,
                SettingShard.serializer(),
            ) { it.name }.toUniqueMap("portable setting", SettingShard::name, SettingShard::value)

            val remaps = readShardTable(
                TABLE_REMAPS,
                counts.remaps,
                RemapShard.serializer(),
            ) { it.oldKey.stableString() }.toUniqueMap("entity remap", RemapShard::oldKey, RemapShard::newKey)

            val appliedOpIds = readShardTable(
                TABLE_APPLIED_OPS,
                counts.appliedOpIds,
                AppliedOpShard.serializer(),
            ) { it.opId }.mapTo(linkedSetOf(), AppliedOpShard::opId)
            check(appliedOpIds.size == counts.appliedOpIds) { "Duplicate applied operation id" }

            val identityMappings = readShardTable(
                TABLE_IDENTITY_MAPPINGS,
                counts.identityMappings,
                IndexedIdentityMapping.serializer(),
            ) { indexRowKey(it.index) }.orderedValues(
                "identity mapping",
                IndexedIdentityMapping::index,
                IndexedIdentityMapping::mapping,
            )

            val blockedKeys = readShardTable(
                TABLE_BLOCKED_KEYS,
                counts.blockedKeys,
                BlockedKeyShard.serializer(),
            ) { it.key.stableString() }.mapTo(linkedSetOf(), BlockedKeyShard::key)
            check(blockedKeys.size == counts.blockedKeys) { "Duplicate blocked identity key" }

            val drafts = readShardTable(
                TABLE_DRAFTS,
                counts.drafts,
                DraftShard.serializer(),
            ) { it.draftId }.toUniqueMap("draft", DraftShard::draftId, DraftShard::draft)
            drafts.forEach { (key, value) -> check(key == value.draftId) { "Draft map key/body mismatch" } }

            val sealedOutbox = readShardTable(
                TABLE_SEALED_OUTBOX,
                counts.sealedOutbox,
                OutboxShard.serializer(),
            ) { sequenceRowKey(it.deviceSeq) }.toUniqueMap(
                "sealed outbox",
                OutboxShard::deviceSeq,
                OutboxShard::event,
            )
            sealedOutbox.forEach { (key, value) ->
                check(key == value.deviceSeq) { "Outbox map key/header mismatch" }
            }

            val archivedEvents = readShardTable(
                TABLE_ARCHIVED_EVENTS,
                counts.archivedEvents,
                IndexedArchivedEvent.serializer(),
            ) { indexRowKey(it.index) }.orderedValues(
                "archived event",
                IndexedArchivedEvent::index,
                IndexedArchivedEvent::event,
            )

            val receipts = readShardTable(
                TABLE_RECEIPTS,
                counts.receipts,
                ReceiptShard.serializer(),
            ) { sequenceRowKey(it.deviceSeq) }.toUniqueMap("receipt", ReceiptShard::deviceSeq, ReceiptShard::receipt)
            receipts.forEach { (key, value) -> check(key == value.deviceSeq) { "Receipt map key/body mismatch" } }

            val keyEpochs = readShardTable(
                TABLE_KEY_EPOCHS,
                counts.keyEpochs,
                KeyEpochShard.serializer(),
            ) { epochRowKey(it.epoch) }.toUniqueMap("key epoch", KeyEpochShard::epoch, KeyEpochShard::metadata)
            keyEpochs.forEach { (key, value) -> check(key == value.epoch) { "Key-epoch map key/body mismatch" } }

            val issues = readShardTable(
                TABLE_MATERIALIZATION_ISSUES,
                counts.materializationIssues,
                IndexedMaterializationIssue.serializer(),
            ) { indexRowKey(it.index) }.orderedValues(
                "materialization issue",
                IndexedMaterializationIssue::index,
                IndexedMaterializationIssue::issue,
            )

            val confirmations = readShardTable(
                TABLE_TRUST_CONFIRMATIONS,
                counts.trustConfirmations,
                IndexedTrustConfirmation.serializer(),
            ) { it.confirmation.baseUrl }.orderedValues(
                "repository trust confirmation",
                IndexedTrustConfirmation::index,
                IndexedTrustConfirmation::confirmation,
            )

            val approvals = readShardTable(
                TABLE_TRUST_APPROVALS,
                counts.trustApprovals,
                IndexedTrustConfirmation.serializer(),
            ) { it.confirmation.baseUrl }.orderedValues(
                "repository trust approval",
                IndexedTrustConfirmation::index,
                IndexedTrustConfirmation::confirmation,
            )

            LocalSyncStoreState(
                replica = SyncState(
                    schemaVersion = scalars.replicaSchemaVersion,
                    keyEpoch = scalars.replicaKeyEpoch,
                    throughWorkspaceSeq = scalars.throughWorkspaceSeq,
                    previousStableCheckpointHash = scalars.previousStableCheckpointHash,
                    entities = entities,
                    categoryMemberships = memberships,
                    readingProgress = progress,
                    portableSettings = settings,
                    keyRemaps = remaps,
                    appliedOpIds = appliedOpIds,
                ),
                lastLocalHlc = scalars.lastLocalHlc,
                maxObservedRemoteHlc = scalars.maxObservedRemoteHlc,
                identityMap = SyncIdentityMap(identityMappings, blockedKeys),
                drafts = drafts,
                sealedOutbox = sealedOutbox,
                archivedSealedEvents = archivedEvents,
                nextDeviceSeq = scalars.nextDeviceSeq,
                committedDeviceSeq = scalars.committedDeviceSeq,
                verifiedReceipts = receipts,
                activeKeyEpoch = scalars.activeKeyEpoch,
                keyEpochs = keyEpochs,
                recoveryBaseKeyEpoch = scalars.recoveryBaseKeyEpoch,
                serverRequiredKeyEpochs = scalars.serverRequiredKeyEpochs,
                keyEpochPruningIntent = scalars.keyEpochPruningIntent,
                sealingIntent = scalars.sealingIntent,
                genesisCheckpointSeed = scalars.genesisCheckpointSeed,
                materializationIssues = issues,
                repositoryTrustConfirmations = confirmations,
                repositoryTrustApprovals = approvals,
                materializationPending = scalars.materializationPending,
            ).validate()
        }
    }

    private suspend fun loadLegacyV1OrNull(): LocalSyncStoreState? {
        val legacy = selectSingleton(TABLE_LEGACY, "state_json") ?: return null
        if (legacy.first != LEGACY_SYNC_FORMAT_VERSION) {
            throw corrupt("Unsupported legacy local sync database format ${legacy.first}")
        }
        val encoded = legacy.second ?: throw corrupt("The legacy local sync state is null")
        return decodeDurable("The legacy local sync database contains invalid state") {
            codec.decodeFromString(LocalSyncStoreState.serializer(), encoded).validate()
        }
    }

    private suspend fun selectSingleton(table: String, payloadColumn: String): Pair<Long, String?>? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT format_version, $payloadColumn FROM $table WHERE singleton_id = 1",
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0)!! to cursor.getString(1) else null,
                )
            },
            parameters = 0,
        ).await()

    private suspend fun <T> readShardTable(
        table: String,
        expectedCount: Int,
        serializer: KSerializer<T>,
        rowKey: (T) -> String,
    ): List<T> {
        val rows = driver.executeQuery(
            identifier = null,
            sql = "SELECT row_key, payload_json FROM $table ORDER BY row_key",
            mapper = { cursor ->
                val result = mutableListOf<Pair<String?, String?>>()
                while (cursor.next().value) result += cursor.getString(0) to cursor.getString(1)
                QueryResult.Value(result)
            },
            parameters = 0,
        ).await()
        check(rows.size == expectedCount) {
            "Shard count mismatch for $table: expected $expectedCount, found ${rows.size}"
        }
        return rows.map { (databaseKey, encoded) ->
            check(databaseKey != null) { "Null row key in $table" }
            check(encoded != null) { "Null shard payload in $table" }
            val persisted = codec.decodeFromString(PersistedShard.serializer(), encoded)
            check(persisted.rowKey == databaseKey) { "Database/payload row key mismatch in $table" }
            val decoded = codec.decodeFromJsonElement(serializer, persisted.payload)
            check(codec.encodeToJsonElement(serializer, decoded) == persisted.payload) {
                "Incomplete or non-canonical shard payload in $table"
            }
            check(rowKey(decoded) == databaseKey) { "Decoded row key mismatch in $table" }
            decoded
        }
    }

    private suspend fun persistDiff(
        previous: LocalSyncStoreState?,
        next: LocalSyncStoreState,
        forceMeta: Boolean,
        deleteLegacy: Boolean,
    ) {
        val previousScalars = previous?.toScalars()
        val nextScalars = next.toScalars()
        val encodedScalars = if (forceMeta || previousScalars != nextScalars) {
            encode(LocalSyncScalars.serializer(), nextScalars, "local sync scalar manifest")
        } else {
            null
        }

        transactions.transaction {
            diffMap(
                TABLE_ENTITIES,
                previous?.replica?.entities,
                next.replica.entities,
                { it.stableString() },
                { key, record ->
                    require(key == record.key) { "Replica entity map key/body mismatch" }
                    EntityShard(key, record)
                },
                EntityShard.serializer(),
            )
            diffMap(
                TABLE_MEMBERSHIPS,
                previous?.replica?.categoryMemberships,
                next.replica.categoryMemberships,
                ::membershipRowKey,
                ::MembershipShard,
                MembershipShard.serializer(),
            )
            diffMap(
                TABLE_READING_PROGRESS,
                previous?.replica?.readingProgress,
                next.replica.readingProgress,
                { it.stableString() },
                { key, progress ->
                    require(key == progress.chapterKey) { "Reading-progress map key/body mismatch" }
                    ReadingProgressShard(key, progress)
                },
                ReadingProgressShard.serializer(),
            )
            diffMap(
                TABLE_SETTINGS,
                previous?.replica?.portableSettings,
                next.replica.portableSettings,
                { it },
                ::SettingShard,
                SettingShard.serializer(),
            )
            diffMap(
                TABLE_REMAPS,
                previous?.replica?.keyRemaps,
                next.replica.keyRemaps,
                { it.stableString() },
                ::RemapShard,
                RemapShard.serializer(),
            )
            diffSet(
                TABLE_APPLIED_OPS,
                previous?.replica?.appliedOpIds,
                next.replica.appliedOpIds,
                { it },
                ::AppliedOpShard,
                AppliedOpShard.serializer(),
            )
            diffList(
                TABLE_IDENTITY_MAPPINGS,
                previous?.identityMap?.mappings,
                next.identityMap.mappings,
                { index, _ -> indexRowKey(index) },
                ::IndexedIdentityMapping,
                IndexedIdentityMapping.serializer(),
            )
            diffSet(
                TABLE_BLOCKED_KEYS,
                previous?.identityMap?.blockedKeys,
                next.identityMap.blockedKeys,
                { it.stableString() },
                ::BlockedKeyShard,
                BlockedKeyShard.serializer(),
            )
            diffMap(
                TABLE_DRAFTS,
                previous?.drafts,
                next.drafts,
                { it },
                { key, draft ->
                    require(key == draft.draftId) { "Draft map key/body mismatch" }
                    DraftShard(key, draft)
                },
                DraftShard.serializer(),
            )
            diffMap(
                TABLE_SEALED_OUTBOX,
                previous?.sealedOutbox,
                next.sealedOutbox,
                ::sequenceRowKey,
                { sequence, event ->
                    require(sequence == event.deviceSeq) { "Outbox map key/header mismatch" }
                    OutboxShard(sequence, event)
                },
                OutboxShard.serializer(),
            )
            diffList(
                TABLE_ARCHIVED_EVENTS,
                previous?.archivedSealedEvents,
                next.archivedSealedEvents,
                { index, _ -> indexRowKey(index) },
                ::IndexedArchivedEvent,
                IndexedArchivedEvent.serializer(),
            )
            diffMap(
                TABLE_RECEIPTS,
                previous?.verifiedReceipts,
                next.verifiedReceipts,
                ::sequenceRowKey,
                { sequence, receipt ->
                    require(sequence == receipt.deviceSeq) { "Receipt map key/body mismatch" }
                    ReceiptShard(sequence, receipt)
                },
                ReceiptShard.serializer(),
            )
            diffMap(
                TABLE_KEY_EPOCHS,
                previous?.keyEpochs,
                next.keyEpochs,
                ::epochRowKey,
                { epoch, metadata ->
                    require(epoch == metadata.epoch) { "Key-epoch map key/body mismatch" }
                    KeyEpochShard(epoch, metadata)
                },
                KeyEpochShard.serializer(),
            )
            diffList(
                TABLE_MATERIALIZATION_ISSUES,
                previous?.materializationIssues,
                next.materializationIssues,
                { index, _ -> indexRowKey(index) },
                ::IndexedMaterializationIssue,
                IndexedMaterializationIssue.serializer(),
            )
            diffList(
                TABLE_TRUST_CONFIRMATIONS,
                previous?.repositoryTrustConfirmations,
                next.repositoryTrustConfirmations,
                { _, confirmation -> confirmation.baseUrl },
                ::IndexedTrustConfirmation,
                IndexedTrustConfirmation.serializer(),
            )
            diffList(
                TABLE_TRUST_APPROVALS,
                previous?.repositoryTrustApprovals,
                next.repositoryTrustApprovals,
                { _, confirmation -> confirmation.baseUrl },
                ::IndexedTrustConfirmation,
                IndexedTrustConfirmation.serializer(),
            )

            encodedScalars?.let { upsertMeta(it) }
            if (deleteLegacy) {
                driver.execute(null, "DELETE FROM $TABLE_LEGACY WHERE singleton_id = 1", 0).await()
            }
        }
    }

    private suspend fun <K, V, T> diffMap(
        table: String,
        previous: Map<K, V>?,
        next: Map<K, V>,
        rowKey: (K) -> String,
        shard: (K, V) -> T,
        serializer: KSerializer<T>,
    ) {
        if (previous === next) return
        val before = previous.orEmpty()
        before.keys.forEach { key ->
            if (!next.containsKey(key)) deleteRow(table, rowKey(key))
        }
        next.forEach { (key, value) ->
            if (!before.containsKey(key) || before[key] != value) {
                upsertRow(table, rowKey(key), serializer, shard(key, value))
            }
        }
    }

    private suspend fun <T, P> diffSet(
        table: String,
        previous: Set<T>?,
        next: Set<T>,
        rowKey: (T) -> String,
        shard: (T) -> P,
        serializer: KSerializer<P>,
    ) {
        if (previous === next) return
        val before = previous.orEmpty()
        before.forEach { value -> if (value !in next) deleteRow(table, rowKey(value)) }
        next.forEach { value ->
            if (value !in before) upsertRow(table, rowKey(value), serializer, shard(value))
        }
    }

    private suspend fun <T, P> diffList(
        table: String,
        previous: List<T>?,
        next: List<T>,
        rowKey: (Int, T) -> String,
        shard: (Int, T) -> P,
        serializer: KSerializer<P>,
    ) {
        if (previous === next) return
        val before = previous.orEmpty()
        val common = minOf(before.size, next.size)

        // Delete moved/removed keys first so a reorder cannot collide with a still-present row.
        before.forEachIndexed { index, value ->
            val oldKey = rowKey(index, value)
            val keep = index < common && oldKey == rowKey(index, next[index])
            if (!keep) deleteRow(table, oldKey)
        }
        next.forEachIndexed { index, value ->
            val newKey = rowKey(index, value)
            val unchanged = index < common &&
                newKey == rowKey(index, before[index]) &&
                value == before[index]
            if (!unchanged) upsertRow(table, newKey, serializer, shard(index, value))
        }
    }

    private suspend fun <T> upsertRow(
        table: String,
        rowKey: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        require(rowKey.isNotEmpty()) { "A sync shard row key cannot be empty" }
        val payload = encodeShard(rowKey, serializer, value)
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO $table(row_key, payload_json)
                VALUES (?, ?)
                ON CONFLICT(row_key) DO UPDATE SET payload_json = excluded.payload_json
            """.trimIndent(),
            parameters = 2,
        ) {
            bindString(0, rowKey)
            bindString(1, payload)
        }.await()
    }

    private suspend fun deleteRow(table: String, rowKey: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $table WHERE row_key = ?",
            parameters = 1,
        ) { bindString(0, rowKey) }.await()
    }

    private suspend fun upsertMeta(encodedScalars: String) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO $TABLE_META(singleton_id, format_version, scalars_json)
                VALUES (1, ?, ?)
                ON CONFLICT(singleton_id) DO UPDATE SET
                  format_version = excluded.format_version,
                  scalars_json = excluded.scalars_json
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, LOCAL_SYNC_FORMAT_VERSION)
            bindString(1, encodedScalars)
        }.await()
    }

    private fun <T> encodeShard(rowKey: String, serializer: KSerializer<T>, value: T): String =
        encode(
            PersistedShard.serializer(),
            PersistedShard(rowKey, codec.encodeToJsonElement(serializer, value)),
            "sync shard $rowKey",
        )

    private fun <T> encode(serializer: KSerializer<T>, value: T, label: String): String = try {
        codec.encodeToString(serializer, value)
    } catch (error: SerializationException) {
        throw IllegalArgumentException("The $label could not be encoded", error)
    }

    private fun LocalSyncStoreState.toScalars(): LocalSyncScalars = LocalSyncScalars(
        replicaSchemaVersion = replica.schemaVersion,
        replicaKeyEpoch = replica.keyEpoch,
        throughWorkspaceSeq = replica.throughWorkspaceSeq,
        previousStableCheckpointHash = replica.previousStableCheckpointHash,
        lastLocalHlc = lastLocalHlc,
        maxObservedRemoteHlc = maxObservedRemoteHlc,
        nextDeviceSeq = nextDeviceSeq,
        committedDeviceSeq = committedDeviceSeq,
        activeKeyEpoch = activeKeyEpoch,
        recoveryBaseKeyEpoch = recoveryBaseKeyEpoch,
        serverRequiredKeyEpochs = serverRequiredKeyEpochs,
        keyEpochPruningIntent = keyEpochPruningIntent,
        sealingIntent = sealingIntent,
        genesisCheckpointSeed = genesisCheckpointSeed,
        materializationPending = materializationPending,
        shardCounts = ShardCounts(
            entities = replica.entities.size,
            memberships = replica.categoryMemberships.size,
            readingProgress = replica.readingProgress.size,
            settings = replica.portableSettings.size,
            remaps = replica.keyRemaps.size,
            appliedOpIds = replica.appliedOpIds.size,
            identityMappings = identityMap.mappings.size,
            blockedKeys = identityMap.blockedKeys.size,
            drafts = drafts.size,
            sealedOutbox = sealedOutbox.size,
            archivedEvents = archivedSealedEvents.size,
            receipts = verifiedReceipts.size,
            keyEpochs = keyEpochs.size,
            materializationIssues = materializationIssues.size,
            trustConfirmations = repositoryTrustConfirmations.size,
            trustApprovals = repositoryTrustApprovals.size,
        ),
    )

    private fun membershipRowKey(key: CategoryMembershipKey): String =
        codec.encodeToString(CategoryMembershipKey.serializer(), key)
}

/** Public schema lets Android and Native drivers create and migrate the same v2 database. */
object SyncLocalSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = LOCAL_SYNC_FORMAT_VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        createLegacyTable(driver)
        createV2Tables(driver)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Sync database cannot migrate backwards" }
        if (oldVersion < 1L) createLegacyTable(driver)
        if (oldVersion < 2L && newVersion >= 2L) createV2Tables(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }

    private fun createLegacyTable(driver: SqlDriver) {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS $TABLE_LEGACY(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL,
                  state_json TEXT NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        ).value
    }

    private fun createV2Tables(driver: SqlDriver) {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS $TABLE_META(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL,
                  scalars_json TEXT NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        ).value
        SHARD_TABLES.forEach { table ->
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE IF NOT EXISTS $table(
                      row_key TEXT NOT NULL PRIMARY KEY,
                      payload_json TEXT NOT NULL
                    ) WITHOUT ROWID
                """.trimIndent(),
                parameters = 0,
            ).value
        }
    }
}

class SyncPersistenceCorruptException(message: String, cause: Throwable) : IllegalStateException(message, cause)

private inline fun <T> decodeDurable(message: String, block: () -> T): T = try {
    block()
} catch (error: SyncPersistenceCorruptException) {
    throw error
} catch (error: Throwable) {
    throw SyncPersistenceCorruptException(message, error)
}

private fun corrupt(message: String): SyncPersistenceCorruptException =
    SyncPersistenceCorruptException(message, IllegalStateException(message))

private fun <T, K, V> List<T>.toUniqueMap(
    label: String,
    key: (T) -> K,
    value: (T) -> V,
): Map<K, V> {
    val result = linkedMapOf<K, V>()
    forEach { entry ->
        val entryKey = key(entry)
        check(!result.containsKey(entryKey)) { "Duplicate $label key" }
        result[entryKey] = value(entry)
    }
    return result
}

private fun <T, V> List<T>.orderedValues(
    label: String,
    index: (T) -> Int,
    value: (T) -> V,
): List<V> {
    val ordered = sortedBy(index)
    ordered.forEachIndexed { expected, entry ->
        check(index(entry) == expected) { "Missing, duplicate, or non-canonical $label index" }
    }
    return ordered.map(value)
}

private fun sequenceRowKey(sequence: Long): String {
    require(sequence >= 0) { "A persisted sequence cannot be negative" }
    return sequence.toString().padStart(NUMERIC_ROW_KEY_WIDTH, '0')
}

private fun epochRowKey(epoch: Int): String {
    require(epoch >= 0) { "A persisted epoch cannot be negative" }
    return epoch.toString().padStart(NUMERIC_ROW_KEY_WIDTH, '0')
}

private fun indexRowKey(index: Int): String {
    require(index >= 0) { "A persisted list index cannot be negative" }
    return index.toString().padStart(NUMERIC_ROW_KEY_WIDTH, '0')
}

@Serializable
private data class PersistedShard(val rowKey: String, val payload: JsonElement)

@Serializable
private data class LocalSyncScalars(
    val replicaSchemaVersion: Int,
    val replicaKeyEpoch: Int,
    val throughWorkspaceSeq: Long,
    val previousStableCheckpointHash: String?,
    val lastLocalHlc: HlcTimestamp?,
    val maxObservedRemoteHlc: HlcTimestamp?,
    val nextDeviceSeq: Long,
    val committedDeviceSeq: Long,
    val activeKeyEpoch: Int,
    val recoveryBaseKeyEpoch: Int,
    val serverRequiredKeyEpochs: List<Int>,
    val keyEpochPruningIntent: KeyEpochPruningIntent?,
    val sealingIntent: SealingIntent?,
    val genesisCheckpointSeed: GenesisCheckpointSeed?,
    val materializationPending: Boolean,
    val shardCounts: ShardCounts,
)

@Serializable
private data class ShardCounts(
    val entities: Int,
    val memberships: Int,
    val readingProgress: Int,
    val settings: Int,
    val remaps: Int,
    val appliedOpIds: Int,
    val identityMappings: Int,
    val blockedKeys: Int,
    val drafts: Int,
    val sealedOutbox: Int,
    val archivedEvents: Int,
    val receipts: Int,
    val keyEpochs: Int,
    val materializationIssues: Int,
    val trustConfirmations: Int,
    val trustApprovals: Int,
) {
    init {
        require(
            listOf(
                entities,
                memberships,
                readingProgress,
                settings,
                remaps,
                appliedOpIds,
                identityMappings,
                blockedKeys,
                drafts,
                sealedOutbox,
                archivedEvents,
                receipts,
                keyEpochs,
                materializationIssues,
                trustConfirmations,
                trustApprovals,
            ).all { it >= 0 },
        ) { "A shard count cannot be negative" }
    }
}

@Serializable private data class EntityShard(val key: SyncEntityKey, val record: SyncEntityRecord)
@Serializable private data class MembershipShard(
    val key: CategoryMembershipKey,
    val value: LwwRegister<Boolean>,
)
@Serializable private data class ReadingProgressShard(val key: SyncEntityKey, val value: ReadingProgressState)
@Serializable private data class SettingShard(val name: String, val value: LwwRegister<SyncValue>)
@Serializable private data class RemapShard(val oldKey: SyncEntityKey, val newKey: SyncEntityKey)
@Serializable private data class AppliedOpShard(val opId: String)
@Serializable private data class IndexedIdentityMapping(val index: Int, val mapping: SyncIdentityMapping)
@Serializable private data class BlockedKeyShard(val key: SyncEntityKey)
@Serializable private data class DraftShard(val draftId: String, val draft: SyncDraft)
@Serializable private data class OutboxShard(val deviceSeq: Long, val event: SealedOutboxEvent)
@Serializable private data class IndexedArchivedEvent(val index: Int, val event: ArchivedSealedEvent)
@Serializable private data class ReceiptShard(val deviceSeq: Long, val receipt: SyncReceipt)
@Serializable private data class KeyEpochShard(val epoch: Int, val metadata: KeyEpochMetadata)
@Serializable private data class IndexedMaterializationIssue(val index: Int, val issue: MaterializationIssue)
@Serializable private data class IndexedTrustConfirmation(
    val index: Int,
    val confirmation: RepositoryTrustConfirmation,
)

private val SyncPersistenceJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}

private const val LEGACY_SYNC_FORMAT_VERSION = 1L
private const val LOCAL_SYNC_FORMAT_VERSION = 2L
private const val NUMERIC_ROW_KEY_WIDTH = 20

private const val TABLE_LEGACY = "sync_local_state"
private const val TABLE_META = "sync_local_meta"
private const val TABLE_ENTITIES = "sync_replica_entities"
private const val TABLE_MEMBERSHIPS = "sync_replica_memberships"
private const val TABLE_READING_PROGRESS = "sync_replica_reading_progress"
private const val TABLE_SETTINGS = "sync_replica_settings"
private const val TABLE_REMAPS = "sync_replica_remaps"
private const val TABLE_APPLIED_OPS = "sync_replica_applied_ops"
private const val TABLE_IDENTITY_MAPPINGS = "sync_identity_mappings"
private const val TABLE_BLOCKED_KEYS = "sync_identity_blocked_keys"
private const val TABLE_DRAFTS = "sync_drafts"
private const val TABLE_SEALED_OUTBOX = "sync_sealed_outbox"
private const val TABLE_ARCHIVED_EVENTS = "sync_archived_events"
private const val TABLE_RECEIPTS = "sync_receipts"
private const val TABLE_KEY_EPOCHS = "sync_key_epochs"
private const val TABLE_MATERIALIZATION_ISSUES = "sync_materialization_issues"
private const val TABLE_TRUST_CONFIRMATIONS = "sync_repository_trust_confirmations"
private const val TABLE_TRUST_APPROVALS = "sync_repository_trust_approvals"

private val SHARD_TABLES = listOf(
    TABLE_ENTITIES,
    TABLE_MEMBERSHIPS,
    TABLE_READING_PROGRESS,
    TABLE_SETTINGS,
    TABLE_REMAPS,
    TABLE_APPLIED_OPS,
    TABLE_IDENTITY_MAPPINGS,
    TABLE_BLOCKED_KEYS,
    TABLE_DRAFTS,
    TABLE_SEALED_OUTBOX,
    TABLE_ARCHIVED_EVENTS,
    TABLE_RECEIPTS,
    TABLE_KEY_EPOCHS,
    TABLE_MATERIALIZATION_ISSUES,
    TABLE_TRUST_CONFIRMATIONS,
    TABLE_TRUST_APPROVALS,
)
