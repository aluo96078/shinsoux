package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.APPLY_ATTEMPTS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.APPLY_CHUNK
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.APPLY_ORDER
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_CURSOR
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_DESCRIPTORS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_RESYNC
import kotlin.concurrent.Volatile

/** How [SyncPuller] reaches beyond the sync tables. */
internal class SyncPullHooks(
    val resolveCreatableSource: suspend (packageId: String, sourceId: String) -> SourceKey?,
    /** Asks for another automatic cycle. */
    val signalDirty: () -> Unit,
)

/**
 * The pull and apply side: merges server records into the sync tables, prunes on a resync and
 * projects what is unapplied onto the library. Owns the "nothing changed since" apply marker.
 */
internal class SyncPuller(
    private val context: SyncV3Context,
    private val tokens: SyncSessionTokens,
    private val clocks: SyncClockKeeper,
    hooks: SyncPullHooks,
) {
    private val repository = context.repository
    private val store = context.store
    private val identity = context.identity
    private val api = context.api
    private val nowMillis = context.nowMillis
    private val resolveCreatableSource = hooks.resolveCreatableSource
    private val signalDirty = hooks.signalDirty
    @Volatile private var lastApplyRevision = -1L

    suspend fun pullAll(fromZero: Boolean = false, seen: MutableSet<String>? = null) {
        var since = if (fromZero) 0L else context.cursor
        val url = context.baseUrl
        while (true) {
            val page = tokens.authed { api.changes(url, it, since, SyncApi.PAGE_SIZE) }
            ingest(page.records, page.nextSince)
            seen?.let { set -> page.records.forEach { set += seenKey(it.kind, it.key) } }
            // A server that says there is more without moving the cursor would loop forever.
            if (page.hasMore && page.nextSince <= since) {
                throw SyncApiException(502, "bad_response", "The sync server did not advance past $since")
            }
            since = page.nextSince
            if (!page.hasMore) break
        }
    }

    private fun seenKey(kind: String, key: String) = "$kind\u0000$key"

    /** Merges server states into the sync tables; projection onto the library happens later. */
    fun ingest(records: List<WireRecord>, cursorAfter: Long?) {
        val remoteClocks = ArrayList<String>()
        var undescribed = false
        store.transaction {
            for (wr in records) {
                val previous = store.loadRecord(wr.kind, wr.key)
                val remote = runCatching { wr.toRecordState() }
                    .onFailure { Log.warn("Sync", it) { "A remote sync record could not be read; skipping it" } }
                    .getOrNull() ?: continue
                val merged = LwwMerge.applyRemote(previous?.state, remote)
                remoteClocks += remote.fields.values.map { it.clock }
                remote.deleted?.let { remoteClocks += it.clock }
                remote.cleared?.let { remoteClocks += it }
                val needsProjection = previous == null || previous.unapplied || projectionDiffers(previous.state, merged)
                // Another device synced this title without knowing its source or repository, and this one knows.
                wr.work?.let { work ->
                    val known = identity.withSource(work)
                    if ((work.sourceName == null && known.sourceName != null) ||
                        (work.repositoryUrl == null && known.repositoryUrl != null)
                    ) {
                        undescribed = true
                    }
                }
                store.saveRecord(
                    StoredRecord(
                        kind = wr.kind,
                        key = wr.key,
                        workHash = wr.work?.hash ?: previous?.workHash,
                        unitRemoteId = wr.unitRemoteId ?: previous?.unitRemoteId,
                        workJson = wr.work?.let { SyncWireJson.encodeToString(WireWork.serializer(), it) } ?: previous?.workJson,
                        state = merged,
                        seq = wr.seq,
                        unapplied = needsProjection,
                        pendingReason = if (needsProjection && previous?.unapplied != true) null else previous?.pendingReason,
                        rejectReason = if (merged.hasDirty()) previous?.rejectReason else null,
                        rejectedAt = if (merged.hasDirty()) previous?.rejectedAt else null,
                    ),
                )
            }
            if (undescribed) store.setMeta(META_DESCRIPTORS, null)
            cursorAfter?.let { store.setMeta(META_CURSOR, it.toString()) }
            // With the cursor: a cursor that moved past records whose clocks are not in the
            // floor would let a later clock correction go below what the server holds.
            store.noteAcknowledgedClocks(remoteClocks)
        }
        clocks.observeRemote(remoteClocks)
    }

    private fun projectionDiffers(a: LwwMerge.LocalState, b: LwwMerge.LocalState): Boolean {
        if (a.isLive != b.isLive) return true
        if (a.fields.size != b.fields.size) return true
        for ((name, field) in b.fields) if (a.fields[name]?.value != field.value) return true
        return false
    }

    /** 410: pull everything again and drop local records the server no longer has. */
    suspend fun resync() {
        store.setMeta(META_RESYNC, "1")
        val seen = HashSet<String>()
        pullAll(fromZero = true, seen = seen)
        val pruned = ArrayList<Pair<String, String>>()
        val enabled = context.enabledKinds()
        // A kind that is off keeps its stored records as they are (they are not applied here).
        for (kind in APPLY_ORDER.filter { it in enabled }) {
            for (key in store.recordKeys(kind).filterNot { seenKey(kind, it) in seen }) {
                // One edited since the last sync is left alone: it is pushed again.
                val record = store.loadRecord(kind, key)?.takeUnless { it.state.hasDirty() } ?: continue
                val tick = clocks.nextClock()
                store.saveRecord(
                    record.copy(
                        state = LwwMerge.LocalState(emptyMap(), LwwMerge.LocalDeleted(true, tick, dirty = false), tick),
                        unapplied = true,
                        pendingReason = null,
                    ),
                )
                pruned += kind to key
            }
        }
        applyUnapplied()
        pruned.forEach { (kind, key) -> store.deleteRecord(kind, key) }
        store.setMeta(META_RESYNC, null)
    }

    // ---- apply ------------------------------------------------------------------------------

    /** Projects merged-but-unapplied records onto the library, parking those that cannot apply yet. */
    suspend fun applyUnapplied(force: Boolean = true) {
        val off = context.disabledKinds()
        val unapplied = store.unappliedCount(off)
        if (unapplied == 0) return
        // Everything left is waiting for a title, chapter or source and nothing changed locally
        // since the last attempt: re-evaluating (an O(library) index build) cannot succeed.
        if (!force && unapplied == store.pendingRemoteCount(off) && repository.currentSnapshot.revision == lastApplyRevision) return
        val startSnapshot = repository.currentSnapshot
        identity.resetCache()
        val baseIndex = SyncApplier.buildWorkIndex(startSnapshot, identity)
        var starved = false
        // Records of a kind switched off stay unapplied; switching it on applies them.
        for (kind in APPLY_ORDER.filter { it !in off }) {
            var after = ""
            while (true) {
                val chunk = store.unappliedRecords(kind, after, APPLY_CHUNK)
                if (chunk.isEmpty()) break
                after = chunk.last().key
                if (!applyChunk(chunk, baseIndex)) starved = true
            }
        }
        if (starved) {
            // Local edits kept winning the snapshot race. The records stay marked unapplied; ask
            // for another automatic cycle instead of waiting for an unrelated trigger, and make
            // sure that cycle does not take the "nothing changed" shortcut above.
            lastApplyRevision = -1L
            signalDirty()
        } else {
            lastApplyRevision = repository.currentSnapshot.revision
        }
    }

    /** False when every attempt lost the race against a local edit; the chunk stays unapplied. */
    private suspend fun applyChunk(chunk: List<StoredRecord>, baseIndex: MutableMap<String, Long>): Boolean {
        val creatable = if (chunk.first().kind == SyncKinds.LIBRARY) creatableFor(chunk, baseIndex) else emptyMap()
        repeat(APPLY_ATTEMPTS) {
            val snapshot = repository.currentSnapshot
            identity.resetCache()
            val index = HashMap(baseIndex)
            val context = SyncApplyContext(
                identity = identity,
                categoryBindings = store.allCategoryBindings().entries.associate { it.value to it.key },
                creatable = creatable,
                dirtyFields = { kind, ref -> store.dirtyFor(kind, ref).mapTo(HashSet()) { it.field } },
                nowMillis = nowMillis(),
            )
            val result = SyncApplier(snapshot, context, index).apply(chunk)
            if (result.changed && repository.replaceSnapshotFromSyncIfRevision(snapshot.revision, result.snapshot) == null) {
                return@repeat // a local edit won the race: recompute against the newer snapshot
            }
            store.transaction {
                for (record in chunk) {
                    val outcome = result.outcomes[record.kind to record.key]
                    store.setApplied(record.kind, record.key, (outcome as? ApplyOutcome.Pending)?.reason)
                    if (record.kind == SyncKinds.LIBRARY && outcome == ApplyOutcome.Applied) {
                        index[record.key]?.let { store.rememberWork(it, record.key, record.key.takeIf { k -> !k.startsWith("local:") }) }
                    }
                }
                result.newBindings.forEach { (uuid, localId) -> store.bindCategory(localId, uuid) }
                result.removedBindings.forEach { uuid -> store.categoryLocalId(uuid)?.let(store::unbindCategory) }
            }
            baseIndex.putAll(index)
            return true
        }
        return false
    }

    private suspend fun creatableFor(chunk: List<StoredRecord>, index: Map<String, Long>): Map<String, SourceKey> {
        val out = HashMap<String, SourceKey>()
        val perSource = HashMap<Pair<String, String>, SourceKey?>()
        for (record in chunk) {
            if (!record.state.isLive || record.key in index || record.key.startsWith("local:")) continue
            // A work that does not decode stays pending; projection reports it as "work_unknown".
            val work = record.workJson?.let { runCatchingCancellable { SyncWireJson.decodeFromString(WireWork.serializer(), it) }.getOrNull() }
            val source = work?.let { perSource.getOrPut(it.packageId to it.sourceId) { creatableSourceOrNull(it) } }
            if (source != null) out[record.key] = source
        }
        return out
    }

    private suspend fun creatableSourceOrNull(work: WireWork): SourceKey? =
        runCatchingCancellable { resolveCreatableSource(work.packageId, work.sourceId) }
            .onFailure { Log.warn("Sync", it) { "Resolving an installed source for a synced work failed" } }
            .getOrNull()
}
