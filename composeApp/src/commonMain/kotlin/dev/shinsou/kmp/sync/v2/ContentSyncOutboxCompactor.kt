package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class ContentSyncOutboxCompactionResult(
    val inspected: Int,
    val removed: Int,
    val added: Int,
    val publicationsCompacted: Int,
)

/**
 * Keeps the content outbox proportional to the library instead of to its edit history.
 *
 * Every content commit queues the whole publication graph again (one chapter download re-sends
 * every unit and manifest of the title). The outbox only drains while a sync session is ready,
 * so on a device that never signed in it grew without bound: tens of thousands of drafts that
 * describe about a thousand entities.
 *
 * Publication, acquisition, unit and manifest patches are per-field last-writer-wins merges
 * ([SyncReducer.patchEntity]). Applying a publication's patches in drain order therefore yields
 * exactly the state of one merged patch per entity applied at the position of the last one. The
 * compactor does that per publication, and only where nothing but those patches mentions the
 * publication's entities (a presence change or any other mutation keeps the title as it is).
 *
 * Replacements get new ids, so a draft that was already installed before a crash can never mask
 * the merged content. They keep the id prefix of the title's newest draft, which is what
 * [SharedContentTransactionStore.purgeLocalPublicationBodies] matches when a title leaves the
 * library.
 */
public class ContentSyncOutboxCompactor(
    private val store: SharedContentTransactionStore<SyncDraft>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "Outbox compaction page size must be positive" }
    }

    /** Compacts only once the outbox holds at least [threshold] drafts; null when it was smaller. */
    public fun compactIfLarge(threshold: Int = DEFAULT_THRESHOLD): ContentSyncOutboxCompactionResult? =
        if (store.pendingOutboxCount() < threshold) null else compact()

    public fun compact(): ContentSyncOutboxCompactionResult {
        val scan = scan()
        var removed = 0
        var added = 0
        var publications = 0
        scan.groups().forEach { group ->
            val replacement = compactGroup(group) ?: return@forEach
            removed += store.replaceOutboxDrafts(replacement.removeIds, replacement.drafts)
            added += replacement.drafts.size
            publications++
        }
        return ContentSyncOutboxCompactionResult(
            inspected = scan.inspected,
            removed = removed,
            added = added,
            publicationsCompacted = publications,
        )
    }

    /** First pass: what each draft touches, never holding more than one page of payloads. */
    private fun scan(): Scan {
        val scan = Scan()
        var after: String? = null
        while (true) {
            val page = store.pendingOutboxPage(after, pageSize)
            if (page.isEmpty()) break
            page.forEach(scan::record)
            after = page.last().draftId
        }
        return scan
    }

    private fun compactGroup(group: Group): Replacement? {
        if (group.drafts.size < 2) return null
        val ordered = group.drafts.sortedWith(compareBy<DraftInfo>({ it.createdAtMillis }, { it.draftId }))
        val merged = LinkedHashMap<SyncEntityKey, MergedPatch>()
        val removeIds = linkedSetOf<String>()
        ordered.forEach { info ->
            // Acknowledged by a drain since the scan: it is gone, nothing to merge or remove.
            val draft = store.pendingOutboxDraft(info.draftId) ?: return@forEach
            if (!draft.event.mutations.all(::isMergeablePatch)) return null
            draft.event.mutations.forEach { mutation ->
                val key = mutation.entityKey()
                merged.getOrPut(key) { MergedPatch(mutation) }.add(mutation)
            }
            removeIds += draft.draftId
        }
        if (removeIds.size < 2 || merged.isEmpty()) return null
        val newest = ordered.last { it.draftId in removeIds }
        val prefix = draftIdPrefix(newest.draftId)
        val digest = Sha256.hex(removeIds.sorted().joinToString("\n").encodeToByteArray()).take(DIGEST_CHARS)
        val idFor = { index: Int -> "${prefix}c$digest-${index.toString().padStart(6, '0')}" }
        val sizingId = idFor(0)
        val mutations = merged.values.flatMap { it.toMutations(sizingId) }
        val drafts = CanonicalSyncDraftPacker.pack(
            mutations = mutations,
            createdAtMillis = ordered.filter { it.draftId in removeIds }.maxOf(DraftInfo::createdAtMillis),
            draftId = idFor,
        )
        if (drafts.size >= removeIds.size) return null
        return Replacement(removeIds, drafts)
    }

    private class Replacement(val removeIds: Set<String>, val drafts: List<SyncDraft>)

    private class DraftInfo(val draftId: String, val createdAtMillis: Long, val entities: List<SyncEntityKey>)

    private class Group(val drafts: List<DraftInfo>)

    private class Scan {
        var inspected: Int = 0
        private val mergeable = ArrayList<DraftInfo>()
        private val parentOf = HashMap<SyncEntityKey, SyncEntityKey>()
        /** One instance per entity: a large outbox repeats the same thousand keys many times. */
        private val interned = HashMap<SyncEntityKey, SyncEntityKey>()
        /** Every string an other kind of draft mentions; any entity found here is left alone. */
        private val pinned = HashSet<String>()

        fun record(draft: SyncDraft) {
            inspected++
            val mutations = draft.event.mutations
            if (!mutations.all(::isMergeablePatch)) {
                collectStrings(SCAN_JSON.encodeToJsonElement(SyncDraft.serializer(), draft), pinned)
                return
            }
            val entities = LinkedHashSet<SyncEntityKey>()
            mutations.forEach { mutation ->
                val key = intern(mutation.entityKey())
                entities += key
                mutation.parentKey()?.let { parentOf[key] = intern(it) }
            }
            mergeable += DraftInfo(draft.draftId, draft.createdAtMillis, entities.toList())
        }

        fun groups(): List<Group> {
            val byPublication = LinkedHashMap<SyncEntityKey, MutableList<DraftInfo>>()
            mergeable.forEach { info ->
                val publications = info.entities.mapTo(hashSetOf()) { publicationOf(it) }
                // A draft spanning several titles (or with an unknown parent) stays as it is.
                val publication = publications.singleOrNull() ?: return@forEach
                byPublication.getOrPut(publication) { ArrayList() } += info
            }
            return byPublication.values
                .filter { drafts -> drafts.none { info -> info.entities.any { it.canonicalValue in pinned } } }
                .map(::Group)
        }

        private fun intern(key: SyncEntityKey): SyncEntityKey = interned.getOrPut(key) { key }

        private fun publicationOf(key: SyncEntityKey): SyncEntityKey? {
            var current = key
            repeat(MAX_PARENT_DEPTH) {
                if (current.entityType == SyncEntityType.PUBLICATION) return current
                current = parentOf[current] ?: return null
            }
            return null
        }
    }

    /** Field-wise merge of one entity's patches, in drain order. */
    private class MergedPatch(first: SyncMutation) {
        private val key = first.entityKey()
        private var parent: SyncEntityKey? = first.parentKey()
        private val fields = LinkedHashMap<String, SyncValue>()
        private var ensurePresent = false
        private val kind = first::class

        fun add(mutation: SyncMutation) {
            require(mutation::class == kind) { "One sync entity received patches of different kinds" }
            mutation.parentKey()?.let { parent = it }
            fields.putAll(mutation.patchFields())
            ensurePresent = ensurePresent || mutation.patchEnsurePresent()
        }

        /** Splits the merged fields so each patch still fits one canonical sync event. */
        fun toMutations(sizingId: String): List<SyncMutation> {
            val out = ArrayList<SyncMutation>()
            var current = LinkedHashMap<String, SyncValue>()
            fields.forEach { (name, value) ->
                val candidate = LinkedHashMap(current).apply { put(name, value) }
                if (current.isEmpty() || CanonicalSyncDraftPacker.isMutationPackable(sizingId, build(candidate))) {
                    current = candidate
                } else {
                    out += build(current)
                    current = linkedMapOf(name to value)
                }
            }
            if (current.isNotEmpty() || out.isEmpty()) out += build(current)
            return out
        }

        private fun build(fields: Map<String, SyncValue>): SyncMutation = when (kind) {
            PublicationPatchV2::class -> PublicationPatchV2(key, fields, ensurePresent)
            AcquisitionPatchV2::class -> AcquisitionPatchV2(key, requireNotNull(parent), fields, ensurePresent)
            PublicationUnitPatchV2::class -> PublicationUnitPatchV2(key, requireNotNull(parent), fields, ensurePresent)
            ContentManifestPatchV2::class -> ContentManifestPatchV2(key, requireNotNull(parent), fields, ensurePresent)
            else -> error("Unsupported compacted sync mutation")
        }
    }

    public companion object {
        /** Below this the outbox is left alone; a title's full re-send is about a dozen drafts. */
        public const val DEFAULT_THRESHOLD: Int = 1_000
        public const val DEFAULT_PAGE_SIZE: Int = 64
        private const val DIGEST_CHARS: Int = 16
        private const val MAX_PARENT_DEPTH: Int = 4
        private val SCAN_JSON: Json = Json {
            encodeDefaults = true
            allowStructuredMapKeys = true
        }

        internal fun isMergeablePatch(mutation: SyncMutation): Boolean =
            mutation is PublicationPatchV2 ||
                mutation is AcquisitionPatchV2 ||
                mutation is PublicationUnitPatchV2 ||
                mutation is ContentManifestPatchV2

        private fun draftIdPrefix(draftId: String): String =
            if (':' in draftId) draftId.substringBeforeLast(':') + ":" else "$draftId:"

        private fun collectStrings(element: JsonElement, into: MutableSet<String>) {
            when (element) {
                is JsonPrimitive -> if (element.isString) into += element.content
                is JsonArray -> element.forEach { collectStrings(it, into) }
                is JsonObject -> element.values.forEach { collectStrings(it, into) }
            }
        }
    }
}

private fun SyncMutation.entityKey(): SyncEntityKey = when (this) {
    is PublicationPatchV2 -> key
    is AcquisitionPatchV2 -> key
    is PublicationUnitPatchV2 -> key
    is ContentManifestPatchV2 -> key
    else -> error("Not a mergeable content patch")
}

private fun SyncMutation.parentKey(): SyncEntityKey? = when (this) {
    is PublicationPatchV2 -> null
    is AcquisitionPatchV2 -> publicationKey
    is PublicationUnitPatchV2 -> acquisitionKey
    is ContentManifestPatchV2 -> unitKey
    else -> error("Not a mergeable content patch")
}

private fun SyncMutation.patchFields(): Map<String, SyncValue> = when (this) {
    is PublicationPatchV2 -> fields
    is AcquisitionPatchV2 -> fields
    is PublicationUnitPatchV2 -> fields
    is ContentManifestPatchV2 -> fields
    else -> error("Not a mergeable content patch")
}

private fun SyncMutation.patchEnsurePresent(): Boolean = when (this) {
    is PublicationPatchV2 -> ensurePresent
    is AcquisitionPatchV2 -> ensurePresent
    is PublicationUnitPatchV2 -> ensurePresent
    is ContentManifestPatchV2 -> ensurePresent
    else -> error("Not a mergeable content patch")
}
