package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.PROMOTE_CHUNK
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Turns the marks of local edits into key-addressed records ready to push. [installedPackages] is
 * what this device last reported as installed, null until it reported.
 */
@OptIn(ExperimentalUuidApi::class)
internal class SyncPromoter(
    private val context: SyncV3Context,
    private val installedPackages: () -> List<AccountPackage>?,
) {
    private val repository = context.repository
    private val store = context.store
    private val identity = context.identity

    /** Turns observed edits (by local ref) into key-addressed records with their current values. */
    fun promoteAll() {
        // A kind switched off here keeps its edits marked: they are promoted, with the clocks they
        // were made at, once the switch is turned back on.
        val enabled = context.enabledKinds()
        val refs = store.dirtyRefs(Int.MAX_VALUE).filter { (kind, _) -> kind in enabled }
        if (refs.isEmpty()) return
        val snapshot = repository.currentSnapshot
        identity.resetCache()
        val outgoing = SyncOutgoing(snapshot, identity, ::categoryKeyFor)
        // Tombstones first: a title removed and re-added under a new local id must end up live.
        val (gone, present) = refs.partition { (kind, ref) -> entityMissing(kind, ref, outgoing) }
        (gone + present).chunked(PROMOTE_CHUNK).forEach { chunk ->
            store.transaction { chunk.forEach { (kind, ref) -> promoteRef(kind, ref, outgoing) } }
        }
    }

    private fun entityMissing(kind: String, ref: String, out: SyncOutgoing): Boolean {
        if (kind == SyncKinds.REPOSITORY) return ref.substring(2) !in out.repositoryByUrl
        if (kind == SyncKinds.SETTING || kind == SyncKinds.PACKAGE) return false
        val id = ref.substring(2).toLongOrNull() ?: return true
        return when (kind) {
            SyncKinds.CATEGORY -> id !in out.categoryById
            SyncKinds.UNIT -> id !in out.chapterById
            else -> id !in out.mangaById
        }
    }

    private fun categoryKeyFor(localId: Long): String? {
        if (localId == Category.Default.id) return "default"
        return store.categoryUuid(localId) ?: Uuid.random().toString().also { store.bindCategory(localId, it) }
    }

    private fun promoteRef(kind: String, ref: String, out: SyncOutgoing) {
        val rows = store.dirtyFor(kind, ref)
        if (rows.isEmpty()) return
        if (kind == SyncKinds.REPOSITORY) {
            val url = ref.substring(2)
            // The newest delete mark decides: "remove from the account" followed by adding the
            // repository again before the next push must go out live, not as a tombstone.
            val removedFromAccount = rows
                .filter { it.field == DirtyEntry.DELETED_FIELD }
                .maxWithOrNull { a, b -> LwwMerge.compare(a.clock, b.clock) }
                ?.aux == "true"
            val repo = out.repositoryByUrl[url]
            if (removedFromAccount) return tombstone(kind, rows)
            // Deleted here only: the account keeps suggesting it, so nothing goes out.
            if (repo == null) return store.deleteDirty(rows)
            return promoteInto(kind, url, rows, null, null, null, live = true) { field -> out.repositoryValue(repo, field) }
        }
        if (kind == SyncKinds.PACKAGE) {
            val packageId = ref.substring(2)
            // Not reported yet: keep the marks until it is. Uninstalled since: nothing goes out,
            // the account keeps the package (see packagesChanged).
            val installed = installedPackages() ?: return
            val pkg = installed.firstOrNull { it.packageId == packageId } ?: return store.deleteDirty(rows)
            return promoteInto(kind, packageId, rows, null, null, null, live = true) { field -> packageValue(pkg, field) }
        }
        if (kind == SyncKinds.SETTING) {
            val path = ref.substring(2)
            if (!PortableSettings.isPortable(path)) { store.deleteDirty(rows); return }
            return promoteInto(kind, path, rows, null, null, null, live = true) { field -> out.settingValue(path, field) }
        }
        // A removal marked with its own record key (see removedRef): the id may belong to
        // another entity by now.
        if (REMOVED_REF_SEPARATOR in ref) return tombstone(kind, rows)
        val id = ref.substring(2).toLongOrNull() ?: run { store.deleteDirty(rows); return }
        when (kind) {
            SyncKinds.CATEGORY -> {
                val category = out.categoryById[id]
                if (category == null) return tombstone(kind, rows)
                val key = categoryKeyFor(id) ?: return
                promoteInto(kind, key, rows, null, null, null, live = true) { field -> out.categoryValue(category, field) }
            }
            SyncKinds.UNIT -> {
                val chapter = out.chapterById[id]
                if (chapter == null) { store.deleteDirty(rows); return }
                val manga = out.mangaById[chapter.mangaId]
                val work = manga?.let(identity::workOf)
                if (manga == null || work == null) {
                    // Chapters of an unresolved title stay dirty and are counted as unsyncable.
                    if (manga == null) store.deleteDirty(rows)
                    return
                }
                if (work !is SyncedWork.Sourced) { store.deleteDirty(rows); return }
                val unitId = identity.unitRemoteId(manga, work, chapter) ?: return
                store.rememberWork(manga.id, work.recordKey, work.key.hash)
                val workJson = SyncWireJson.encodeToString(WireWork.serializer(), identity.describe(manga, work))
                promoteInto(kind, work.key.unitHash(unitId), rows, work.key.hash, unitId, workJson, live = true) { field ->
                    out.unitValue(chapter, field)
                }
            }
            else -> { // library, history, progress
                val manga = out.mangaById[id]
                if (manga == null) return tombstone(kind, rows)
                val work = identity.workOf(manga) ?: return
                val hash = (work as? SyncedWork.Sourced)?.key?.hash
                store.rememberWork(manga.id, work.recordKey, hash)
                val workJson = (work as? SyncedWork.Sourced)?.let {
                    SyncWireJson.encodeToString(WireWork.serializer(), identity.describe(manga, it))
                }
                val live = when (kind) {
                    SyncKinds.LIBRARY -> manga.favorite
                    else -> out.historyValue(manga, work, "lastReadAt") != null
                }
                promoteInto(kind, work.recordKey, rows, null, null, workJson, live) { field ->
                    when (kind) {
                        SyncKinds.LIBRARY -> out.libraryValue(manga, field)
                        SyncKinds.HISTORY -> out.historyValue(manga, work, field)
                        else -> out.progressValue(manga, work, field)
                    }
                }
                if (kind == SyncKinds.LIBRARY && work is SyncedWork.Local && manga.favorite) {
                    promoteLocalLibraryExtras(work.recordKey, manga, rows)
                }
            }
        }
    }

    private fun promoteInto(
        kind: String,
        key: String,
        rows: List<DirtyEntry>,
        workHash: String?,
        unitRemoteId: String?,
        workJson: String?,
        live: Boolean,
        valueOf: (String) -> JsonElement?,
    ) {
        val existing = store.loadRecord(kind, key)
        if (existing == null && !live) {
            // Never synced and already gone: there is nothing to delete on the server.
            store.deleteDirty(rows)
            return
        }
        var state = existing?.state ?: LwwMerge.LocalState()
        val fields = LinkedHashMap(state.fields)
        var deleted = state.deleted
        var cleared = state.cleared
        var revived = false
        // The delete register first, so the fields know whether a live mark goes out with them.
        for (row in rows.sortedBy { if (it.field == DirtyEntry.DELETED_FIELD) 0 else 1 }) {
            if (row.field == DirtyEntry.DELETED_FIELD) {
                val known = deleted
                val newer = known == null || LwwMerge.compare(row.clock, known.clock) > 0
                if (!newer) continue
                if (!live) {
                    deleted = LwwMerge.LocalDeleted(true, row.clock, dirty = true)
                    if (cleared == null || LwwMerge.compare(row.clock, cleared) > 0) cleared = row.clock
                } else {
                    // A tombstone can sit on the account without this device having pulled it yet
                    // (another device removed the title, or the repository was removed from the
                    // account), so the "live" mark is sent whatever is known here: otherwise only
                    // the fields would go out, the newer re-add would lose to the older removal
                    // on the next pull, and the title would vanish from this device.
                    deleted = LwwMerge.LocalDeleted(false, row.clock, dirty = true)
                    revived = true
                }
                continue
            }
            if (!live) continue
            val value = valueOf(row.field) ?: continue
            val current = fields[row.field]
            if (current != null) {
                if (LwwMerge.compare(current.clock, row.clock) >= 0) continue
                // With a live mark going out, an unchanged value is sent again too: if the
                // account holds a removal this device has not pulled, its floor would otherwise
                // drop the old clocks and leave a live record without its fields.
                if (!revived && !current.dirty && current.value == value) continue
            }
            fields[row.field] = LwwMerge.LocalField(value, row.clock, dirty = true)
        }
        if (cleared != null) {
            val floor = cleared
            fields.entries.removeAll { LwwMerge.compare(it.value.clock, floor) <= 0 }
        }
        state = LwwMerge.LocalState(fields, deleted, cleared)
        val record = (existing ?: StoredRecord(kind, key)).copy(
            workHash = workHash ?: existing?.workHash,
            unitRemoteId = unitRemoteId ?: existing?.unitRemoteId,
            workJson = workJson ?: existing?.workJson,
            state = state,
            rejectReason = null,
            rejectedAt = null,
        )
        store.saveRecord(record)
        store.deleteDirty(rows)
    }

    /** `local:` library records carry title/author/kind themselves (records.md §2). */
    private fun promoteLocalLibraryExtras(key: String, manga: Manga, rows: List<DirtyEntry>) {
        val record = store.loadRecord(SyncKinds.LIBRARY, key) ?: return
        val clock = rows.map { it.clock }.maxWithOrNull { a, b -> LwwMerge.compare(a, b) } ?: return
        val author = manga.author?.trim()?.takeIf { it.isNotEmpty() }
        val extras: Map<String, JsonElement> = mapOf(
            "title" to JsonPrimitive(manga.title.trim().ifEmpty { "Untitled" }.truncated(SyncIdentityResolver.MAX_TITLE_CHARS)),
            "author" to (author?.let { JsonPrimitive(it.truncated(SyncIdentityResolver.MAX_TITLE_CHARS)) } ?: JsonNull),
            "contentKind" to JsonPrimitive(identity.contentKindOf(manga, SyncedWork.Local(key.removePrefix("local:")))),
        )
        val fields = LinkedHashMap(record.state.fields)
        var changed = false
        for ((name, value) in extras) {
            val current = fields[name]
            if (current != null && (current.value == value || LwwMerge.compare(current.clock, clock) >= 0)) continue
            fields[name] = LwwMerge.LocalField(value, clock, dirty = true)
            changed = true
        }
        if (changed) store.saveRecord(record.copy(state = record.state.copy(fields = fields)))
    }

    private fun tombstone(kind: String, rows: List<DirtyEntry>) {
        for (row in rows) {
            val key = row.tombKey
            if (row.field == DirtyEntry.DELETED_FIELD && row.aux == "true" && key != null) {
                val existing = store.loadRecord(kind, key)
                if (existing != null) {
                    val known = existing.state.deleted
                    if (known == null || LwwMerge.compare(row.clock, known.clock) > 0) {
                        val cleared = existing.state.cleared.let { c -> if (c == null || LwwMerge.compare(row.clock, c) > 0) row.clock else c }
                        val remaining = existing.state.fields.filterValues { LwwMerge.compare(it.clock, cleared) > 0 }
                        store.saveRecord(
                            existing.copy(
                                state = LwwMerge.LocalState(remaining, LwwMerge.LocalDeleted(true, row.clock, dirty = true), cleared),
                                rejectReason = null,
                                rejectedAt = null,
                            ),
                        )
                    }
                }
            }
        }
        store.deleteDirty(rows)
    }
}
