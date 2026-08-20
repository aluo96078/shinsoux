package dev.shinsou.kmp.sync.v2

/** Pure, order-independent CRDT reducer. It never writes an AppSnapshot directly. */
object SyncReducer {
    fun reduce(initial: SyncState, event: SyncEvent): SyncState {
        if (event.opId in initial.appliedOpIds) return initial
        require(event.schemaVersion <= SYNC_STATE_SCHEMA_VERSION) {
            "Sync event schema ${event.schemaVersion} requires a newer reader"
        }
        var state = initial
        event.mutations.forEach { mutation ->
            state = applyMutation(state, mutation, event.hlc)
        }
        return state.copy(appliedOpIds = state.appliedOpIds + event.opId).normalized()
    }

    fun reduceCommitted(initial: SyncState, committed: CommittedSyncEvent): SyncState {
        if (committed.workspaceSeq <= initial.throughWorkspaceSeq) {
            if (committed.event.opId in initial.appliedOpIds) return initial
            throw SyncInvariantViolation(
                "Workspace sequence ${committed.workspaceSeq} is behind cursor ${initial.throughWorkspaceSeq} but op is unknown",
            )
        }
        val expected = initial.throughWorkspaceSeq + 1
        if (committed.workspaceSeq != expected) {
            throw SyncSequenceGapException(expected = expected, actual = committed.workspaceSeq)
        }
        return reduce(initial, committed.event).copy(throughWorkspaceSeq = committed.workspaceSeq).normalized()
    }

    fun reduceCommitted(initial: SyncState, events: Iterable<CommittedSyncEvent>): SyncState {
        var state = initial
        events.forEach { state = reduceCommitted(state, it) }
        return state
    }

    private fun applyMutation(state: SyncState, mutation: SyncMutation, hlc: HlcTimestamp): SyncState = when (mutation) {
        is LibraryEntryPatch -> patchEntity(
            state = state,
            rawKey = mutation.key,
            fields = mutation.fields,
            ensurePresent = mutation.ensurePresent,
            hlc = hlc,
        )

        is ChapterStatePatch -> {
            val mangaKey = state.resolveKey(mutation.mangaKey)
            patchEntity(
                state = state,
                rawKey = mutation.key,
                fields = mutation.fields + (
                    SyncFields.Chapter.MANGA_KEY to SyncValue.EntityKeyValue(mangaKey)
                    ),
                ensurePresent = mutation.ensurePresent,
                hlc = hlc,
            )
        }

        is CategoryPatch -> patchEntity(
            state = state,
            rawKey = mutation.key,
            fields = mutation.fields,
            ensurePresent = mutation.ensurePresent,
            hlc = hlc,
        )

        is ExtensionRepositoryPatch -> patchEntity(
            state = state,
            rawKey = mutation.key,
            fields = mutation.fields,
            ensurePresent = mutation.ensurePresent,
            hlc = hlc,
        )

        is EntityPresenceSet -> setPresence(state, mutation.key, mutation.present, hlc)
        is ExtensionRepositoryPresenceSet -> setPresence(state, mutation.key, mutation.present, hlc)
        is CategoryMembershipSet -> setMembership(state, mutation, hlc)
        is ReadingProgressSet -> setReadingProgress(state, mutation, hlc)
        is ReadingProgressPresenceSet -> setReadingProgressPresence(state, mutation, hlc)
        is PortableSettingPatch -> patchSettings(state, mutation, hlc)
        is EntityKeyRemap -> remapKey(state, mutation)
    }

    private fun patchEntity(
        state: SyncState,
        rawKey: SyncEntityKey,
        fields: Map<String, SyncValue>,
        ensurePresent: Boolean,
        hlc: HlcTimestamp,
    ): SyncState {
        val key = state.resolveKey(rawKey)
        var record = state.entities[key] ?: SyncEntityRecord(key)
        record = record.mergeFields(rewriteFieldKeys(fields, state), hlc)
        if (ensurePresent) record = record.setPresence(true, hlc)
        return state.copy(entities = state.entities + (key to record))
    }

    private fun setPresence(
        state: SyncState,
        rawKey: SyncEntityKey,
        present: Boolean,
        hlc: HlcTimestamp,
    ): SyncState {
        val key = state.resolveKey(rawKey)
        if (key == SyncEntityKey.defaultCategory() && !present) {
            throw SyncInvariantViolation("The default category cannot be tombstoned")
        }
        val record = (state.entities[key] ?: SyncEntityRecord(key)).setPresence(present, hlc)
        return state.copy(entities = state.entities + (key to record))
    }

    private fun setMembership(
        state: SyncState,
        mutation: CategoryMembershipSet,
        hlc: HlcTimestamp,
    ): SyncState {
        val key = CategoryMembershipKey(
            mangaKey = state.resolveKey(mutation.mangaKey),
            categoryKey = state.resolveKey(mutation.categoryKey),
        )
        val register = state.categoryMemberships[key].mergeValue(mutation.present, hlc)
        return state.copy(categoryMemberships = state.categoryMemberships + (key to register))
    }

    private fun setReadingProgress(
        state: SyncState,
        mutation: ReadingProgressSet,
        hlc: HlcTimestamp,
    ): SyncState {
        val chapterKey = state.resolveKey(mutation.chapterKey)
        val mangaKey = state.resolveKey(mutation.mangaKey)
        val current = state.readingProgress[chapterKey]
        if (current != null && current.mangaKey != mangaKey) {
            throw SyncInvariantViolation("A chapter cannot have reading progress under two manga parents")
        }
        val incoming = ReadingProgressState(
            chapterKey = chapterKey,
            mangaKey = mangaKey,
            position = mutation.position?.let {
                ReadingPositionRegister(
                    position = it,
                    hlc = hlc,
                    sessionId = requireNotNull(mutation.sessionId),
                )
            },
            readState = mutation.readState?.let { LwwRegister(it, hlc) },
            historyTouchedAt = mutation.historyTouchedAt?.let { LwwRegister(it, hlc) },
            presence = LwwRegister(true, hlc),
        )
        return state.copy(
            readingProgress = state.readingProgress + (
                chapterKey to (current?.merge(incoming) ?: incoming)
                ),
        )
    }

    private fun setReadingProgressPresence(
        state: SyncState,
        mutation: ReadingProgressPresenceSet,
        hlc: HlcTimestamp,
    ): SyncState {
        val chapterKey = state.resolveKey(mutation.chapterKey)
        val mangaKey = state.resolveKey(mutation.mangaKey)
        val current = state.readingProgress[chapterKey]
        if (current == null) {
            val tombstone = ReadingProgressState(
                chapterKey = chapterKey,
                mangaKey = mangaKey,
                presence = LwwRegister(mutation.present, hlc),
            )
            return state.copy(readingProgress = state.readingProgress + (chapterKey to tombstone))
        }
        if (current.mangaKey != mangaKey) throw SyncInvariantViolation("Progress presence uses a conflicting manga parent")
        return state.copy(
            readingProgress = state.readingProgress + (
                chapterKey to current.copy(presence = current.presence.mergeValue(mutation.present, hlc))
                ),
        )
    }

    private fun patchSettings(
        state: SyncState,
        mutation: PortableSettingPatch,
        hlc: HlcTimestamp,
    ): SyncState {
        val settings = state.portableSettings.toMutableMap()
        mutation.fields.entries.sortedBy { it.key }.forEach { (name, value) ->
            settings[name] = settings[name].mergeValue(value, hlc)
        }
        return state.copy(portableSettings = settings.deterministicallySorted())
    }

    private fun remapKey(state: SyncState, mutation: EntityKeyRemap): SyncState {
        val resolvedSource = state.resolveKey(mutation.oldKey)
        val resolvedTarget = state.resolveKey(mutation.newKey)
        if (resolvedSource == resolvedTarget) return state
        require(resolvedSource.entityType == resolvedTarget.entityType) { "Cannot remap across entity types" }

        // Concurrent devices can legitimately publish v1 -> v2 and v1 -> v3 before seeing each
        // other. Always joining the currently resolved source into the event's target would make
        // the result arrival-order dependent and can even downgrade v3 back to v2. The terminal
        // key is therefore a commutative max: the highest normalizer version wins, with the stable
        // key order breaking a same-version fork deterministically.
        val newKey = maxOf(resolvedSource, resolvedTarget)
        val oldKey = if (newKey == resolvedSource) resolvedTarget else resolvedSource

        val entities = state.entities.toMutableMap()
        val oldRecord = entities.remove(oldKey)
        val newRecord = entities[newKey]
        if (oldRecord != null) {
            val moved = oldRecord.copy(
                key = newKey,
                fields = rewriteFieldKey(oldRecord.fields, oldKey, newKey),
            )
            entities[newKey] = newRecord?.merge(moved) ?: moved
        }
        entities.replaceAllValues { record ->
            record.copy(fields = rewriteFieldKey(record.fields, oldKey, newKey))
        }

        val memberships = linkedMapOf<CategoryMembershipKey, LwwRegister<Boolean>>()
        state.categoryMemberships.forEach { (key, register) ->
            val rewritten = CategoryMembershipKey(
                mangaKey = if (key.mangaKey == oldKey) newKey else key.mangaKey,
                categoryKey = if (key.categoryKey == oldKey) newKey else key.categoryKey,
            )
            val existing = memberships[rewritten]
            memberships[rewritten] = existing?.merge(register) ?: register
        }

        val progress = linkedMapOf<SyncEntityKey, ReadingProgressState>()
        state.readingProgress.forEach { (key, value) ->
            val rewrittenKey = if (key == oldKey) newKey else key
            val rewritten = value.copy(
                chapterKey = if (value.chapterKey == oldKey) newKey else value.chapterKey,
                mangaKey = if (value.mangaKey == oldKey) newKey else value.mangaKey,
            )
            val existing = progress[rewrittenKey]
            progress[rewrittenKey] = existing?.merge(rewritten) ?: rewritten
        }

        val remaps = (
            state.keyRemaps.mapValues { (_, target) -> if (target == oldKey) newKey else target } +
                (oldKey to newKey)
            ).deterministicallySorted()
        return state.copy(
            entities = entities,
            categoryMemberships = memberships,
            readingProgress = progress,
            keyRemaps = remaps,
        )
    }

    private fun rewriteFieldKeys(fields: Map<String, SyncValue>, state: SyncState): Map<String, SyncValue> =
        fields.mapValues { (_, value) ->
            if (value is SyncValue.EntityKeyValue) value.copy(value = state.resolveKey(value.value)) else value
        }

    private fun rewriteFieldKey(
        fields: Map<String, LwwRegister<SyncValue>>,
        oldKey: SyncEntityKey,
        newKey: SyncEntityKey,
    ): Map<String, LwwRegister<SyncValue>> = fields.mapValues { (_, register) ->
        val value = register.value
        if (value is SyncValue.EntityKeyValue && value.value == oldKey) {
            register.copy(value = SyncValue.EntityKeyValue(newKey))
        } else {
            register
        }
    }

    private inline fun <K, V> MutableMap<K, V>.replaceAllValues(transform: (V) -> V) {
        keys.toList().forEach { key -> this[key] = transform(getValue(key)) }
    }
}

class SyncSequenceGapException(
    val expected: Long,
    val actual: Long,
) : IllegalStateException("Expected workspace sequence $expected but received $actual")
