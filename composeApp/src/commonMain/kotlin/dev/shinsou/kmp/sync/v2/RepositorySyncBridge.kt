package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Produces random UUIDs for operation and portable category identities. */
fun interface SyncPortableIdGenerator {
    fun nextId(): String
}

/** Canonical content identities shared by repository projection and live reader reporting. */
fun syncMangaEntityKey(manga: Manga): SyncEntityKey =
    SyncEntityKey.manga(manga.source.toString(), manga.url)

fun syncChapterEntityKey(chapter: Chapter, manga: Manga): SyncEntityKey =
    SyncEntityKey.chapter(manga.source.toString(), chapter.url)

data class SyncEntityKeyRemapResult(
    val localId: Long,
    val oldKey: SyncEntityKey,
    val newKey: SyncEntityKey,
    val draftId: String,
    val hlc: HlcTimestamp,
)

class SyncIdentityMappingNotFoundException(
    val missingKey: SyncEntityKey,
) : IllegalStateException("No local identity mapping exists for ${missingKey.stableString()}")

class SyncEntityKeyRemapConflictException(message: String) : IllegalStateException(message)

/**
 * Central AppSnapshot -> sync/v2 transaction adapter.
 *
 * The repository invokes this observer before publishing a proposed snapshot. Every sync-owned
 * difference and the identity-map changes required to describe it are therefore committed in one
 * LocalSyncStore transaction first. Device-owned collections are intentionally ignored.
 */
class RepositorySyncBridge(
    private val localStore: LocalSyncStore,
    private val sessionStore: SyncSessionStore,
    private val idGenerator: SyncPortableIdGenerator,
    private val nowMillis: () -> Long,
    private val eventCodec: SyncEventCodec? = null,
    private val maxEventPlaintextBytes: Int = DEFAULT_MAX_EVENT_PLAINTEXT_BYTES,
    private val maxMutationsPerEvent: Int = DEFAULT_MAX_MUTATIONS_PER_EVENT,
) : SnapshotMutationObserver {
    init {
        require(maxEventPlaintextBytes > 0)
        require(maxMutationsPerEvent > 0)
    }

    override suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot): Unit =
        withContext(Dispatchers.Default) {
            // Snapshot diffing, deterministic sizing and the WAL write can grow with the library.
            // Keep that synchronous durability boundary off the UI dispatcher while the
            // repository still awaits it before publishing the visible snapshot.
            val session = sessionStore.load() ?: return@withContext
            if (session.status != SyncSessionStatus.READY || session.provider != SyncProvider.CLOUDFLARE_V2) {
                return@withContext
            }
            commitDifference(previous, next, session.deviceId)
        }

    /**
     * Durably upgrades one mapped identity after a canonical-ID contract or URL normalizer version
     * changes. All validation happens before an operation id/HLC is allocated. The replica remap,
     * identity-map move and upload draft then commit through one LocalSyncStore transaction.
     */
    suspend fun remapEntityKey(
        oldKey: SyncEntityKey,
        newKey: SyncEntityKey,
    ): SyncEntityKeyRemapResult = withContext(Dispatchers.Default) {
        // Constructing the mutation performs the protocol-level type/version checks without
        // touching the durable clock or journal.
        val mutation = EntityKeyRemap(oldKey, newKey)
        val session = sessionStore.load()
            ?: throw SyncEntityKeyRemapConflictException("Sync is not configured for an identity remap")
        if (session.status != SyncSessionStatus.READY || session.provider != SyncProvider.CLOUDFLARE_V2) {
            throw SyncEntityKeyRemapConflictException("Sync session is not ready for an identity remap")
        }
        requireMutationFits(mutation, session.deviceId)

        localStore.transaction {
            val current = state()
            val localId = current.identityMap.localId(oldKey)
                ?: throw SyncIdentityMappingNotFoundException(oldKey)
            if (oldKey in current.identityMap.blockedKeys || newKey in current.identityMap.blockedKeys) {
                throw SyncIdentityCollisionException(
                    "Cannot remap a blocked sync identity: ${oldKey.stableString()} -> ${newKey.stableString()}",
                )
            }
            current.identityMap.localId(newKey)?.let { targetLocalId ->
                if (targetLocalId != localId) {
                    throw SyncIdentityCollisionException(
                        "Remap target ${newKey.stableString()} belongs to local id $targetLocalId, not $localId",
                    )
                }
            }
            val resolvedOld = current.replica.resolveKey(oldKey)
            if (resolvedOld != oldKey) {
                throw SyncEntityKeyRemapConflictException(
                    "Remap source is stale and already resolves to ${resolvedOld.stableString()}",
                )
            }
            val resolvedNew = current.replica.resolveKey(newKey)
            if (resolvedNew != newKey) {
                throw SyncEntityKeyRemapConflictException(
                    "Remap target is stale and already resolves to ${resolvedNew.stableString()}",
                )
            }

            // Pure precomputation proves the final map update cannot discover a late collision
            // after the event has been reduced.
            val remappedIdentityMap = current.identityMap.remap(oldKey, newKey)
            val operationId = idGenerator.nextId()
            require(operationId.isNotBlank()) { "Generated sync operation id is blank" }
            val now = nowMillis()
            val event = SyncEvent(
                opId = operationId,
                hlc = nextLocalHlc(session.deviceId, now),
                mutations = listOf(mutation),
            )
            eventCodec?.let { codec ->
                if (codec.encodeEvent(event).size > maxEventPlaintextBytes) {
                    throw SyncInvariantViolation("Entity key remap exceeds the event size limit")
                }
            }
            val draft = applyLocalEvent(event = event, nowMillis = now)
            updateIdentityMap(remappedIdentityMap)
            SyncEntityKeyRemapResult(
                localId = localId,
                oldKey = oldKey,
                newKey = newKey,
                draftId = draft.draftId,
                hlc = event.hlc,
            )
        }
    }

    /** Builds the next version with the shared canonical-ID/URL normalizer, then emits its remap. */
    suspend fun upgradeContentIdentity(
        oldKey: SyncEntityKey,
        sourceIdentity: String,
        urlOrCanonicalId: String,
        newVersion: Int,
    ): SyncEntityKeyRemapResult {
        val newKey = when (oldKey.entityType) {
            SyncEntityType.MANGA -> SyncEntityKey.manga(sourceIdentity, urlOrCanonicalId, newVersion)
            SyncEntityType.CHAPTER -> SyncEntityKey.chapter(sourceIdentity, urlOrCanonicalId, newVersion)
            else -> throw IllegalArgumentException("Only manga/chapter identities use the content normalizer")
        }
        return remapEntityKey(oldKey, newKey)
    }

    /** Seeds a newly-created workspace before Cloudflare becomes the active provider. */
    suspend fun initializeReplica(snapshot: AppSnapshot, deviceId: String) {
        require(deviceId.isNotBlank())
        commitDifference(AppSnapshot(), snapshot, deviceId, forceFullProjection = true)
    }

    /**
     * Builds checkpoint zero directly from the current snapshot in one local transaction. This is
     * the only snapshot import that intentionally produces no draft/outbox events because the
     * remote workspace has no event history before its first checkpoint.
     *
     * @return true when this call created the seed, or false for an idempotent retry by the same
     * device.
     */
    suspend fun initializeReplicaForInitialCheckpoint(snapshot: AppSnapshot, deviceId: String): Boolean {
        require(deviceId.isNotBlank())
        return localStore.transaction {
            val current = state()
            current.genesisCheckpointSeed?.let { seed ->
                require(seed.deviceId == deviceId) { "Genesis checkpoint belongs to another device" }
                return@transaction false
            }
            require(isPristineForGenesis(current)) {
                "Initial checkpoint cannot replace an existing replica or event journal"
            }
            val plan = SnapshotMutationPlanner(
                previous = AppSnapshot(),
                next = snapshot,
                initialIdentityMap = current.identityMap,
                idGenerator = idGenerator,
                forceFull = true,
                initialReplica = current.replica,
            ).build()
            if (plan.identityMap != current.identityMap) updateIdentityMap(plan.identityMap)
            val seededAt = nowMillis()
            mutationBatches(plan.mutations, deviceId).forEach { mutations ->
                val event = SyncEvent(
                    opId = idGenerator.nextId(),
                    hlc = nextLocalHlc(deviceId, seededAt),
                    mutations = mutations,
                )
                eventCodec?.let { codec ->
                    if (codec.encodeEvent(event).size > maxEventPlaintextBytes) {
                        throw SyncInvariantViolation("One initial checkpoint batch exceeds the event size limit")
                    }
                }
                applyGenesisSeedEvent(event)
            }
            completeGenesisCheckpointSeed(deviceId, seededAt)
            true
        }
    }

    private suspend fun commitDifference(
        previous: AppSnapshot,
        next: AppSnapshot,
        deviceId: String,
        forceFullProjection: Boolean = false,
    ) {
        localStore.transaction {
            val current = state()
            val forceFull = forceFullProjection || isUninitializedReplica(current.replica)
            val planner = SnapshotMutationPlanner(
                previous = previous,
                next = next,
                initialIdentityMap = current.identityMap,
                idGenerator = idGenerator,
                forceFull = forceFull,
                initialReplica = current.replica,
            )
            val plan = planner.build()
            if (plan.identityMap != current.identityMap) updateIdentityMap(plan.identityMap)
            if (plan.mutations.isNotEmpty()) {
                val now = nowMillis()
                mutationBatches(plan.mutations, deviceId).forEach { mutations ->
                    val event = SyncEvent(
                        opId = idGenerator.nextId(),
                        hlc = nextLocalHlc(deviceId, now),
                        mutations = mutations,
                    )
                    eventCodec?.let { codec ->
                        if (codec.encodeEvent(event).size > maxEventPlaintextBytes) {
                            throw SyncInvariantViolation("One synchronized restore batch exceeds the event size limit")
                        }
                    }
                    applyLocalEvent(event = event, nowMillis = now)
                }
            }
        }
    }

    private fun mutationBatches(mutations: List<SyncMutation>, deviceId: String): List<List<SyncMutation>> {
        val codec = eventCodec ?: return mutations.chunked(maxMutationsPerEvent)
        val result = mutableListOf<List<SyncMutation>>()
        var current = mutableListOf<SyncMutation>()
        mutations.forEach { mutation ->
            val candidate = current + mutation
            val fits = candidate.size <= maxMutationsPerEvent &&
                codec.encodeEvent(conservativeSizingEvent(candidate, deviceId)).size <= maxEventPlaintextBytes
            if (fits) {
                current += mutation
            } else {
                if (current.isNotEmpty()) result += current.toList()
                val single = listOf(mutation)
                if (codec.encodeEvent(conservativeSizingEvent(single, deviceId)).size > maxEventPlaintextBytes) {
                    throw SyncInvariantViolation("A synchronized mutation exceeds the event size limit")
                }
                current = single.toMutableList()
            }
        }
        if (current.isNotEmpty()) result += current.toList()
        return result
    }

    private fun conservativeSizingEvent(mutations: List<SyncMutation>, deviceId: String): SyncEvent = SyncEvent(
        opId = "0".repeat(64),
        hlc = HlcTimestamp(Long.MAX_VALUE, Int.MAX_VALUE, deviceId),
        mutations = mutations,
    )

    private fun requireMutationFits(mutation: SyncMutation, deviceId: String) {
        eventCodec?.let { codec ->
            if (codec.encodeEvent(conservativeSizingEvent(listOf(mutation), deviceId)).size > maxEventPlaintextBytes) {
                throw SyncInvariantViolation("Entity key remap exceeds the event size limit")
            }
        }
    }

    private fun isUninitializedReplica(replica: SyncState): Boolean =
        replica.appliedOpIds.isEmpty() &&
            replica.entities.isEmpty() &&
            replica.categoryMemberships.isEmpty() &&
            replica.readingProgress.isEmpty() &&
            replica.portableSettings.isEmpty()

    private fun isPristineForGenesis(state: LocalSyncStoreState): Boolean =
        state.replica == SyncState() &&
            state.identityMap == SyncIdentityMap() &&
            state.lastLocalHlc == null &&
            state.maxObservedRemoteHlc == null &&
            state.drafts.isEmpty() &&
            state.sealedOutbox.isEmpty() &&
            state.archivedSealedEvents.isEmpty() &&
            state.verifiedReceipts.isEmpty() &&
            state.materializationIssues.isEmpty() &&
            state.repositoryTrustConfirmations.isEmpty() &&
            state.repositoryTrustApprovals.isEmpty() &&
            state.nextDeviceSeq == 1L &&
            state.committedDeviceSeq == 0L &&
            state.sealingIntent == null

    private companion object {
        const val DEFAULT_MAX_EVENT_PLAINTEXT_BYTES = 28 * 1024
        const val DEFAULT_MAX_MUTATIONS_PER_EVENT = 64
    }
}

data class SnapshotMutationPlan(
    val mutations: List<SyncMutation>,
    val identityMap: SyncIdentityMap,
)

private const val CONTENT_NAMESPACE_PREFIX = "source:"

private data class ContentCanonicalIdentity(
    val entityType: SyncEntityType,
    val namespace: String,
    val canonicalValue: String,
)

private data class ContentIdentityTransition(
    val localId: Long,
    val oldKey: SyncEntityKey,
    val newKey: SyncEntityKey,
    val sourceTerminal: SyncEntityKey,
)

private fun SyncEntityKey.canonicalIdentity(): ContentCanonicalIdentity = ContentCanonicalIdentity(
    entityType = entityType,
    namespace = namespace,
    canonicalValue = canonicalValue,
)

private fun SyncEntityKey.hasSameCanonicalIdentity(other: SyncEntityKey): Boolean =
    canonicalIdentity() == other.canonicalIdentity()

private fun isEmptyReplica(state: SyncState): Boolean =
    state.entities.isEmpty() &&
        state.categoryMemberships.isEmpty() &&
        state.readingProgress.isEmpty() &&
        state.portableSettings.isEmpty() &&
        state.keyRemaps.isEmpty() &&
        state.appliedOpIds.isEmpty()

internal class SnapshotMutationPlanner(
    private val previous: AppSnapshot,
    private val next: AppSnapshot,
    private val initialIdentityMap: SyncIdentityMap,
    private val idGenerator: SyncPortableIdGenerator,
    private val forceFull: Boolean,
    private val initialReplica: SyncState = SyncState(),
) {
    private var identities = initialIdentityMap
    private val identityKeyByLocalId = initialIdentityMap.mappings.associateTo(linkedMapOf()) {
        (it.entityKey.entityType to it.localId) to it.entityKey
    }
    private val mutations = mutableListOf<SyncMutation>()
    private val previousMangaById = previous.mangas.associateBy { it.id }
    private val nextMangaById = next.mangas.associateBy { it.id }
    private val previousChapterById = previous.chapters.associateBy { it.id }
    private val nextChapterById = next.chapters.associateBy { it.id }
    private val previousHistoryByChapter = previous.histories.associateBy { it.chapterId }
    private val nextHistoryByChapter = next.histories.associateBy { it.chapterId }
    private val replicaReferencedKeys: Set<SyncEntityKey> by lazy(::collectReplicaReferencedKeys)
    private val replicaTerminalByReferencedKey: Map<SyncEntityKey, SyncEntityKey> by lazy {
        replicaReferencedKeys.associateWith(initialReplica::resolveKey)
    }
    private val maxReplicaVersionByTerminal: Map<SyncEntityKey, Int> by lazy {
        val result = linkedMapOf<SyncEntityKey, Int>()
        replicaTerminalByReferencedKey.forEach { (key, terminal) ->
            result[terminal] = maxOf(result[terminal] ?: 0, key.version, terminal.version)
        }
        result
    }
    private val replicaTerminalsByCanonicalIdentity: Map<ContentCanonicalIdentity, Set<SyncEntityKey>> by lazy {
        val result = linkedMapOf<ContentCanonicalIdentity, MutableSet<SyncEntityKey>>()
        replicaTerminalByReferencedKey.forEach { (key, terminal) ->
            result.getOrPut(key.canonicalIdentity(), ::linkedSetOf).add(terminal)
        }
        result
    }
    private val initialMappingsByCanonicalIdentity: Map<ContentCanonicalIdentity, List<SyncIdentityMapping>> by lazy {
        initialIdentityMap.mappings.groupBy { it.entityKey.canonicalIdentity() }
    }

    fun build(): SnapshotMutationPlan {
        planContentIdentityMigrations()
        planMangas()
        planChapters()
        planReadingProgress()
        planCategories()
        planMemberships()
        planExtensionRepositories()
        planPortableSettings()
        return SnapshotMutationPlan(mutations.distinct(), identities)
    }

    /**
     * Reconciles stable content identities before any patch or portable id is generated.
     *
     * This is deliberately a complete, generator-free preflight. A collision anywhere in the
     * manga/chapter batch therefore aborts the enclosing LocalSyncStore transaction without
     * advancing the HLC, changing the identity map, creating a draft, or consuming an external id.
     */
    private fun planContentIdentityMigrations() {
        validateInitialIdentityMapStructure()
        validateNextSnapshotContentIdentities()

        val mangaTransitions = retainedMangaTransitions()
        val chapterTransitions = retainedChapterTransitions()
        val transitions = mangaTransitions + chapterTransitions
        transitions.forEach(::validateContentTransition)

        applyContentTransitions(mangaTransitions + chapterTransitions)
    }

    private fun retainedMangaTransitions(): List<ContentIdentityTransition> =
        (previousMangaById.keys intersect nextMangaById.keys)
            .sorted()
            .mapNotNull { localId ->
                val before = requireNotNull(previousMangaById[localId])
                val after = requireNotNull(nextMangaById[localId])
                contentTransition(
                    localId = localId,
                    type = SyncEntityType.MANGA,
                    previousCanonical = syncMangaEntityKey(before),
                    desiredCanonical = syncMangaEntityKey(after),
                )
            }

    private fun retainedChapterTransitions(): List<ContentIdentityTransition> =
        (previousChapterById.keys intersect nextChapterById.keys)
            .sorted()
            .mapNotNull { localId ->
                val before = requireNotNull(previousChapterById[localId])
                val after = requireNotNull(nextChapterById[localId])
                val beforeManga = previousMangaById[before.mangaId]
                    ?: throw SyncInvariantViolation("Chapter ${before.id} had no manga while planning identity migration")
                val afterManga = nextMangaById[after.mangaId]
                    ?: throw SyncInvariantViolation("Chapter ${after.id} has no manga while planning identity migration")
                contentTransition(
                    localId = localId,
                    type = SyncEntityType.CHAPTER,
                    previousCanonical = syncChapterEntityKey(before, beforeManga),
                    desiredCanonical = syncChapterEntityKey(after, afterManga),
                )
            }

    private fun contentTransition(
        localId: Long,
        type: SyncEntityType,
        previousCanonical: SyncEntityKey,
        desiredCanonical: SyncEntityKey,
    ): ContentIdentityTransition? {
        val oldKey = identityKeyByLocalId[type to localId]
        if (oldKey == null) {
            val identityChanged = !previousCanonical.hasSameCanonicalIdentity(desiredCanonical)
            val hasDurableSyncState = initialIdentityMap.mappings.isNotEmpty() || !isEmptyReplica(initialReplica)
            if (identityChanged && hasDurableSyncState) throw SyncIdentityMappingNotFoundException(previousCanonical)
            return null
        }
        if (oldKey.hasSameCanonicalIdentity(desiredCanonical)) return null

        val sourceTerminal = initialReplica.resolveKey(oldKey)
        val highestLineageVersion = maxOf(
            oldKey.version,
            sourceTerminal.version,
            maxReplicaVersionByTerminal[sourceTerminal] ?: 0,
        )
        if (highestLineageVersion == Int.MAX_VALUE) {
            throw SyncEntityKeyRemapConflictException(
                "No higher content identity version remains for ${oldKey.stableString()}",
            )
        }
        val newKey = when (type) {
            SyncEntityType.MANGA -> SyncEntityKey.manga(
                sourceIdentity = desiredCanonical.namespace.removePrefix(CONTENT_NAMESPACE_PREFIX),
                urlOrCanonicalId = desiredCanonical.canonicalValue,
                version = highestLineageVersion + 1,
            )

            SyncEntityType.CHAPTER -> SyncEntityKey.chapter(
                sourceIdentity = desiredCanonical.namespace.removePrefix(CONTENT_NAMESPACE_PREFIX),
                urlOrCanonicalId = desiredCanonical.canonicalValue,
                version = highestLineageVersion + 1,
            )

            else -> throw SyncInvariantViolation("Only manga/chapter identities can migrate from repository snapshots")
        }
        return ContentIdentityTransition(localId, oldKey, newKey, sourceTerminal)
    }

    private fun validateNextSnapshotContentIdentities() {
        fun requireUnique(entries: List<Pair<Long, SyncEntityKey>>) {
            val owners = linkedMapOf<ContentCanonicalIdentity, Long>()
            entries.forEach { (localId, key) ->
                val identity = key.canonicalIdentity()
                // Keep this explicit instead of using Map.putIfAbsent: the common
                // collection API is not available on every Kotlin/Native target.
                val existing = owners[identity]
                if (existing == null) owners[identity] = localId
                if (existing != null && existing != localId) {
                    throw SyncIdentityCollisionException(
                        "Desired ${key.entityType} identity ${key.namespace}|${key.canonicalValue} " +
                            "belongs to both local ids $existing and $localId",
                    )
                }
            }
        }

        requireUnique(next.mangas.map { it.id to syncMangaEntityKey(it) })
        requireUnique(next.chapters.map { chapter ->
            val manga = nextMangaById[chapter.mangaId]
                ?: throw SyncInvariantViolation("Chapter ${chapter.id} has no manga while validating sync identity")
            chapter.id to syncChapterEntityKey(chapter, manga)
        })
    }

    private fun validateInitialIdentityMapStructure() {
        val ownersByKey = linkedMapOf<SyncEntityKey, Long>()
        val keysByLocalId = linkedMapOf<Pair<SyncEntityType, Long>, SyncEntityKey>()
        val ownersByCanonicalIdentity = linkedMapOf<ContentCanonicalIdentity, Long>()
        val ownersByReplicaTerminal = linkedMapOf<SyncEntityKey, Long>()
        initialIdentityMap.mappings.forEach { mapping ->
            val existingOwner = ownersByKey.put(mapping.entityKey, mapping.localId)
            if (existingOwner != null) {
                throw SyncIdentityCollisionException(
                    "Duplicate mapping for ${mapping.entityKey.stableString()}",
                )
            }
            val localIdentity = mapping.entityKey.entityType to mapping.localId
            val existingKey = keysByLocalId.put(localIdentity, mapping.entityKey)
            if (existingKey != null) {
                throw SyncIdentityCollisionException(
                    "Local ${mapping.entityKey.entityType} id ${mapping.localId} has multiple sync identities",
                )
            }
            val existingCanonicalOwner = ownersByCanonicalIdentity.put(
                mapping.entityKey.canonicalIdentity(),
                mapping.localId,
            )
            if (existingCanonicalOwner != null && existingCanonicalOwner != mapping.localId) {
                throw SyncIdentityCollisionException(
                    "Canonical ${mapping.entityKey.entityType} identity " +
                        "${mapping.entityKey.namespace}|${mapping.entityKey.canonicalValue} has multiple local ids",
                )
            }
            val terminal = initialReplica.resolveKey(mapping.entityKey)
            val existingTerminalOwner = ownersByReplicaTerminal.put(terminal, mapping.localId)
            if (existingTerminalOwner != null && existingTerminalOwner != mapping.localId) {
                throw SyncIdentityCollisionException(
                    "Replica lineage ${terminal.stableString()} has multiple local ids",
                )
            }
        }
    }

    private fun validateContentTransition(transition: ContentIdentityTransition) {
        val (localId, oldKey, newKey, sourceTerminal) = transition
        if (oldKey in initialIdentityMap.blockedKeys ||
            initialIdentityMap.blockedKeys.any { it.hasSameCanonicalIdentity(newKey) }
        ) {
            throw SyncIdentityCollisionException(
                "Cannot migrate a blocked sync identity: ${oldKey.stableString()} -> ${newKey.stableString()}",
            )
        }
        initialMappingsByCanonicalIdentity[newKey.canonicalIdentity()]?.firstOrNull { mapping ->
            mapping.localId != localId && mapping.entityKey.hasSameCanonicalIdentity(newKey)
        }?.let { occupied ->
            throw SyncIdentityCollisionException(
                "Desired ${newKey.entityType} identity ${newKey.namespace}|${newKey.canonicalValue} " +
                    "already belongs to local id ${occupied.localId}",
            )
        }
        replicaTerminalsByCanonicalIdentity[newKey.canonicalIdentity()]?.firstOrNull { terminal ->
            terminal != sourceTerminal
        }?.let { occupiedTerminal ->
            throw SyncIdentityCollisionException(
                "Desired ${newKey.entityType} identity ${newKey.namespace}|${newKey.canonicalValue} " +
                    "is occupied by replica lineage ${occupiedTerminal.stableString()}",
            )
        }
    }

    private fun applyContentTransitions(transitions: List<ContentIdentityTransition>) {
        if (transitions.isEmpty()) return
        val transitionByOldKey = transitions.associateBy { it.oldKey }
        identities = identities.copy(
            mappings = identities.mappings.map { mapping ->
                transitionByOldKey[mapping.entityKey]?.let { transition ->
                    SyncIdentityMapping(transition.newKey, transition.localId)
                } ?: mapping
            }.sortedBy { it.entityKey },
            blockedKeys = identities.blockedKeys - transitionByOldKey.keys,
        )
        transitions.forEach { transition ->
            identityKeyByLocalId[transition.oldKey.entityType to transition.localId] = transition.newKey
            mutations += EntityKeyRemap(transition.oldKey, transition.newKey)
        }
    }

    private fun collectReplicaReferencedKeys(): Set<SyncEntityKey> = buildSet {
        addAll(initialReplica.entities.keys)
        addAll(initialReplica.keyRemaps.keys)
        addAll(initialReplica.keyRemaps.values)
        initialReplica.categoryMemberships.keys.forEach {
            add(it.mangaKey)
            add(it.categoryKey)
        }
        initialReplica.readingProgress.forEach { (key, value) ->
            add(key)
            add(value.chapterKey)
            add(value.mangaKey)
        }
        initialReplica.entities.values.forEach { record ->
            record.fields.values.forEach { register ->
                (register.value as? SyncValue.EntityKeyValue)?.let { add(it.value) }
            }
        }
    }

    private fun planMangas() {
        next.mangas.sortedBy { it.id }.forEach { manga ->
            val key = mangaKey(manga)
            val old = previousMangaById[manga.id]
            val patch = SyncMutationFactory.libraryEntry(key, manga)
            val oldPatch = old?.let { SyncMutationFactory.libraryEntry(key, it) }
            if (forceFull || oldPatch?.fields != patch.fields || oldPatch.ensurePresent != patch.ensurePresent) {
                mutations += patch
            }
        }

        (previousMangaById.keys - nextMangaById.keys).sorted().forEach { mangaId ->
            val manga = requireNotNull(previousMangaById[mangaId])
            val key = mangaKey(manga)
            mutations += EntityPresenceSet(key, false)
            previous.chapters.filter { it.mangaId == mangaId }.sortedBy { it.id }.forEach { chapter ->
                val chapterKey = chapterKey(chapter, manga)
                mutations += EntityPresenceSet(chapterKey, false)
                mutations += ReadingProgressPresenceSet(chapterKey, key, false)
            }
            previous.mangaCategories.filter { it.mangaId == mangaId }.sortedBy { it.categoryId }.forEach { link ->
                mutations += CategoryMembershipSet(key, categoryKey(link.categoryId), false)
            }
        }
    }

    private fun planChapters() {
        next.chapters.sortedBy { it.id }.forEach { chapter ->
            val manga = nextMangaById[chapter.mangaId]
                ?: throw SyncInvariantViolation("Chapter ${chapter.id} has no manga while planning sync")
            val mangaKey = mangaKey(manga)
            val key = chapterKey(chapter, manga)
            val old = previousChapterById[chapter.id]
            val patch = SyncMutationFactory.chapter(key, mangaKey, chapter)
            val oldPatch = old?.let { SyncMutationFactory.chapter(key, mangaKey, it) }
            if (forceFull || oldPatch?.fields != patch.fields || oldPatch.ensurePresent != patch.ensurePresent) {
                mutations += patch
            }
        }

        (previousChapterById.keys - nextChapterById.keys).sorted().forEach { chapterId ->
            val chapter = requireNotNull(previousChapterById[chapterId])
            val manga = previousMangaById[chapter.mangaId] ?: return@forEach
            val mangaKey = mangaKey(manga)
            val key = chapterKey(chapter, manga)
            mutations += EntityPresenceSet(key, false)
            mutations += ReadingProgressPresenceSet(key, mangaKey, false)
        }
    }

    private fun planReadingProgress() {
        next.chapters.sortedBy { it.id }.forEach { chapter ->
            val old = previousChapterById[chapter.id]
            val oldHistory = previousHistoryByChapter[chapter.id]
            val history = nextHistoryByChapter[chapter.id]
            val hasMeaningfulProgress = chapter.lastPageRead > 0 || chapter.read || history != null
            if (old == null && !hasMeaningfulProgress) return@forEach
            val positionChanged = (forceFull && hasMeaningfulProgress) ||
                (old != null && old.lastPageRead != chapter.lastPageRead)
            val readChanged = (forceFull && hasMeaningfulProgress) || (old != null && old.read != chapter.read)
            val historyChanged = (forceFull && history != null) || historyValue(oldHistory) != historyValue(history)
            if (!positionChanged && !readChanged && !historyChanged) return@forEach
            val manga = requireNotNull(nextMangaById[chapter.mangaId])
            mutations += ReadingProgressSet(
                chapterKey = chapterKey(chapter, manga),
                mangaKey = mangaKey(manga),
                position = ReaderPosition(
                    readingMode = next.settings.reader.readingMode,
                    pageIndex = chapter.lastPageRead.coerceAtLeast(0),
                    normalizedOffsetFraction = 0.0,
                    resetEpoch = 0,
                ).takeIf { positionChanged },
                readState = chapter.read.takeIf { readChanged },
                historyTouchedAt = when {
                    !historyChanged -> null
                    history == null -> 0L
                    else -> history.lastRead
                },
                sessionId = if (positionChanged) idGenerator.nextId() else null,
            )
        }
    }

    private fun planCategories() {
        next.categories.asSequence().filter { it.id != Category.Default.id }.sortedBy { it.id }.forEach { category ->
            val key = categoryKey(category.id)
            val old = previous.categories.firstOrNull { it.id == category.id }
            val patch = SyncMutationFactory.category(key, category)
            val oldPatch = old?.let { SyncMutationFactory.category(key, it) }
            if (forceFull || oldPatch?.fields != patch.fields || oldPatch.ensurePresent != patch.ensurePresent) {
                mutations += patch
            }
        }
        val nextIds = next.categories.mapTo(mutableSetOf()) { it.id }
        previous.categories.asSequence()
            .filter { it.id != Category.Default.id && it.id !in nextIds }
            .sortedBy { it.id }
            .forEach { category ->
                val key = categoryKey(category.id)
                mutations += EntityPresenceSet(key, false)
                previous.mangaCategories.filter { it.categoryId == category.id }.sortedBy { it.mangaId }.forEach { link ->
                    previousMangaById[link.mangaId]?.let { manga ->
                        mutations += CategoryMembershipSet(mangaKey(manga), key, false)
                    }
                }
            }
    }

    private fun planMemberships() {
        val previousKeys = previous.mangaCategories.mapNotNullTo(linkedSetOf()) { link ->
            val manga = previousMangaById[link.mangaId] ?: return@mapNotNullTo null
            CategoryMembershipKey(mangaKey(manga), categoryKey(link.categoryId))
        }
        val nextKeys = next.mangaCategories.mapNotNullTo(linkedSetOf()) { link ->
            val manga = nextMangaById[link.mangaId] ?: return@mapNotNullTo null
            CategoryMembershipKey(mangaKey(manga), categoryKey(link.categoryId))
        }
        if (forceFull) {
            nextKeys.sorted().forEach { mutations += CategoryMembershipSet(it.mangaKey, it.categoryKey, true) }
        } else {
            (nextKeys - previousKeys).sorted().forEach {
                mutations += CategoryMembershipSet(it.mangaKey, it.categoryKey, true)
            }
            (previousKeys - nextKeys).sorted().forEach {
                mutations += CategoryMembershipSet(it.mangaKey, it.categoryKey, false)
            }
        }
    }

    private fun planExtensionRepositories() {
        val oldByKey = previous.extensionRepositories.associateBy { SyncEntityKey.extensionRepository(it.baseUrl) }
        val newByKey = next.extensionRepositories.associateBy { SyncEntityKey.extensionRepository(it.baseUrl) }
        newByKey.entries.sortedBy { it.key }.forEach { (key, repository) ->
            val patch = SyncMutationFactory.extensionRepository(key, repository)
            val oldPatch = oldByKey[key]?.let { SyncMutationFactory.extensionRepository(key, it) }
            if (forceFull || oldPatch?.fields != patch.fields) mutations += patch
        }
        (oldByKey.keys - newByKey.keys).sorted().forEach {
            mutations += ExtensionRepositoryPresenceSet(it, false)
        }
    }

    private fun planPortableSettings() {
        val before = PortableSettingProjector.encode(previous.settings)
        val after = PortableSettingProjector.encode(next.settings)
        val changes = after.filter { (path, value) -> forceFull || before[path] != value }
        if (changes.isNotEmpty()) {
            mutations += PortableSettingPatch(
                changes.entries.sortedBy { it.key }.associate { it.toPair() },
            )
        }
    }

    private fun mangaKey(manga: Manga): SyncEntityKey {
        identityKeyByLocalId[SyncEntityType.MANGA to manga.id]?.let { return it }
        val key = syncMangaEntityKey(manga)
        identities = identities.bind(key, manga.id)
        identityKeyByLocalId[SyncEntityType.MANGA to manga.id] = key
        return key
    }

    private fun chapterKey(chapter: Chapter, manga: Manga): SyncEntityKey {
        identityKeyByLocalId[SyncEntityType.CHAPTER to chapter.id]?.let { return it }
        val key = syncChapterEntityKey(chapter, manga)
        identities = identities.bind(key, chapter.id)
        identityKeyByLocalId[SyncEntityType.CHAPTER to chapter.id] = key
        return key
    }

    private fun categoryKey(localId: Long): SyncEntityKey {
        if (localId == Category.Default.id) return SyncEntityKey.defaultCategory().also {
            if (identities.localId(it) == null) {
                identities = identities.bind(it, Category.Default.id)
                identityKeyByLocalId[SyncEntityType.CATEGORY to Category.Default.id] = it
            }
        }
        identityKeyByLocalId[SyncEntityType.CATEGORY to localId]?.let { return it }
        val key = SyncEntityKey.category(idGenerator.nextId())
        identities = identities.bind(key, localId)
        identityKeyByLocalId[SyncEntityType.CATEGORY to localId] = key
        return key
    }

    private fun historyValue(history: History?): Pair<Long, Long>? = history?.let { it.lastRead to it.timeRead }

}
