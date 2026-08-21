package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.validate
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

fun interface SyncOperationIdGenerator {
    /** Must return an unpredictable, installation-unique id (normally a random UUID). */
    fun nextId(): String
}

fun interface RemoteOutboxFlusher {
    suspend fun flush()
}

data class ReaderReportResult(
    val recorded: Boolean,
    val draftId: String? = null,
    val sealed: Boolean = false,
    val positionRegister: ReadingPositionRegister? = null,
)

data class ContentReaderReportResult(
    val recorded: Boolean,
    val draftId: String? = null,
    val sealed: Boolean = false,
    val locatorRegister: LwwRegister<ReadingLocator>? = null,
)

/**
 * Conflates only unsealed mutations from the same reader session. Once sealed, the next position
 * necessarily becomes a new immutable event.
 */
class ReaderProgressReporter(
    private val localStore: LocalSyncStore,
    private val sessionStore: SyncSessionStore,
    private val crypto: SyncCrypto,
    private val projectionSink: SyncProjectionSink,
    private val remoteOutboxFlusher: RemoteOutboxFlusher,
    private val operationIdGenerator: SyncOperationIdGenerator,
    private val nowMillis: () -> Long,
    private val isIncognito: () -> Boolean,
    private val beforeBackgroundSeal: suspend () -> Unit = {},
    private val scope: CoroutineScope? = null,
    private val sealIntervalMillis: Long = 1_250,
    private val backgroundRemoteTimeoutMillis: Long = 3_000,
) : SyncBackgroundFlusher {
    private val mutex = Mutex()
    /** Serializes remote flush requests without putting network I/O on the reader's call stack. */
    private val remoteFlushScheduleMutex = Mutex()
    private val lastSealedAtBySession = mutableMapOf<String, Long>()
    private val scheduledSealBySession = mutableMapOf<String, Job>()
    private var remoteFlushJob: Job? = null
    @Volatile
    private var closed = false

    init {
        require(sealIntervalMillis in 250..10_000) { "Reader seal interval is outside a useful range" }
        require(backgroundRemoteTimeoutMillis > 0) { "Background remote timeout must be positive" }
    }

    /** Creates the stable id used to distinguish one open-reader session from another. */
    fun newReaderSessionId(): String {
        check(!closed) { "Reader progress reporter is closed" }
        return operationIdGenerator.nextId().also { sessionId ->
        require(sessionId.isNotBlank() && '|' !in sessionId) { "Generated reader session id is invalid" }
        }
    }

    /** Returns the winning local/remote register after resolving any versioned key remap. */
    suspend fun currentReadingPosition(chapterKey: SyncEntityKey): ReadingPositionRegister? {
        val state = localStore.readState().replica
        return state.readingProgress[state.resolveKey(chapterKey)]?.position
    }

    /** Winning TXT/EPUB/image-v2 locator, independent from legacy chapter page indexes. */
    suspend fun currentContentReadingLocator(key: ContentProgressKeyV2): LwwRegister<ReadingLocator>? =
        localStore.readState().replica.contentReadingProgress[key]?.locator

    /**
     * Records a stable typed locator without translating it through a lossy legacy page index.
     * Drafts from one open reader session coalesce until sealed exactly like manga progress.
     */
    suspend fun recordContentReadingProgress(
        locator: ReadingLocator,
        sessionId: String,
        completed: Boolean,
        historyTouchedAt: Long,
    ): ContentReaderReportResult = locked {
        ensureOpen()
        if (isIncognito()) return@locked ContentReaderReportResult(recorded = false)
        require(sessionId.isNotBlank() && '|' !in sessionId) { "Invalid reader session id" }
        locator.validate()
        require(historyTouchedAt >= 0) { "Content reader history time cannot be negative" }
        val key = ContentProgressKeyV2.from(locator)
        val now = nowMillis()
        val deviceId = requireReadyDeviceId()
        val draft = localStore.transaction {
            val hlc = nextLocalHlc(deviceId, now)
            val event = SyncEvent(
                opId = operationIdGenerator.nextId(),
                hlc = hlc,
                mutations = listOf(
                    ContentReadingProgressSetV2(
                        locator = locator,
                        readState = true.takeIf { completed },
                        historyTouchedAtEpochMillis = historyTouchedAt,
                    ),
                ),
            )
            applyLocalEvent(event, now, contentReaderCoalescingKey(sessionId, key))
        }
        flushProjectionIfPending()
        val locatorRegister = currentContentReadingLocator(key)
        val previousSeal = lastSealedAtBySession.getOrPut(sessionId) { now }
        val due = now - previousSeal >= sealIntervalMillis
        val sealed = if (due) trySealDraft(draft.draftId, sessionId) else false
        if (sealed) requestRemoteFlush() else scheduleSeal(sessionId)
        ContentReaderReportResult(
            recorded = true,
            draftId = draft.draftId,
            sealed = sealed,
            locatorRegister = locatorRegister,
        )
    }

    suspend fun recordReadingProgress(
        chapterKey: SyncEntityKey,
        mangaKey: SyncEntityKey,
        readingMode: ReadingMode,
        pageIndex: Int,
        normalizedOffsetFraction: Double,
        sessionId: String,
        completed: Boolean,
        historyTouchedAt: Long,
    ): ReaderReportResult = locked {
        ensureOpen()
        if (isIncognito()) return@locked ReaderReportResult(recorded = false)
        require(sessionId.isNotBlank()) { "Reader session id cannot be blank" }
        require('|' !in sessionId) { "Reader session id contains a reserved delimiter" }
        val now = nowMillis()
        val deviceId = requireReadyDeviceId()
        val draft = localStore.transaction {
            val keys = resolveReaderKeys(chapterKey, mangaKey)
            val resetEpoch = state().replica.readingProgress[keys.chapter]?.position?.position?.resetEpoch ?: 0
            val hlc = nextLocalHlc(deviceId, now)
            val event = SyncEvent(
                opId = operationIdGenerator.nextId(),
                hlc = hlc,
                mutations = listOf(
                    ReadingProgressSet(
                        chapterKey = keys.chapter,
                        mangaKey = keys.manga,
                        position = ReaderPosition(
                            readingMode = readingMode,
                            pageIndex = pageIndex,
                            normalizedOffsetFraction = normalizedOffsetFraction,
                            resetEpoch = resetEpoch,
                        ),
                        readState = true.takeIf { completed },
                        historyTouchedAt = historyTouchedAt,
                        sessionId = sessionId,
                    ),
                ),
            )
            applyLocalEvent(event, now, readerCoalescingKey(sessionId, keys.chapter))
        }
        flushProjectionIfPending()
        val positionRegister = currentReadingPosition(chapterKey)
        val previousSeal = lastSealedAtBySession.getOrPut(sessionId) { now }
        val due = now - previousSeal >= sealIntervalMillis
        val sealed = if (due) trySealDraft(draft.draftId, sessionId) else false
        if (sealed) {
            requestRemoteFlush()
        } else {
            scheduleSeal(sessionId)
        }
        ReaderReportResult(
            recorded = true,
            draftId = draft.draftId,
            sealed = sealed,
            positionRegister = positionRegister,
        )
    }

    /** Mark-unread updates only the independent read register and deliberately preserves position. */
    suspend fun markUnread(
        chapterKey: SyncEntityKey,
        mangaKey: SyncEntityKey,
        sessionId: String,
    ): ReaderReportResult = locked {
        ensureOpen()
        if (isIncognito()) return@locked ReaderReportResult(recorded = false)
        require(sessionId.isNotBlank() && '|' !in sessionId) { "Invalid reader session id" }
        val now = nowMillis()
        val deviceId = requireReadyDeviceId()
        val draft = localStore.transaction {
            val keys = resolveReaderKeys(chapterKey, mangaKey)
            val event = SyncEvent(
                opId = operationIdGenerator.nextId(),
                hlc = nextLocalHlc(deviceId, now),
                mutations = listOf(
                    ReadingProgressSet(
                        chapterKey = keys.chapter,
                        mangaKey = keys.manga,
                        readState = false,
                    ),
                ),
            )
            applyLocalEvent(event, now, readerCoalescingKey(sessionId, keys.chapter))
        }
        flushProjectionIfPending()
        scheduleSeal(sessionId)
        ReaderReportResult(recorded = true, draftId = draft.draftId)
    }

    /** Explicit reset wins over every old page through resetEpoch, even if an old event has a later wall time. */
    suspend fun resetProgress(
        chapterKey: SyncEntityKey,
        mangaKey: SyncEntityKey,
        readingMode: ReadingMode,
        sessionId: String,
        historyTouchedAt: Long? = null,
    ): ReaderReportResult = locked {
        ensureOpen()
        if (isIncognito()) return@locked ReaderReportResult(recorded = false)
        require(sessionId.isNotBlank() && '|' !in sessionId) { "Invalid reader session id" }
        val now = nowMillis()
        val deviceId = requireReadyDeviceId()
        val draft = localStore.transaction {
            val keys = resolveReaderKeys(chapterKey, mangaKey)
            val previousEpoch = state().replica.readingProgress[keys.chapter]?.position?.position?.resetEpoch ?: 0
            check(previousEpoch < Long.MAX_VALUE) { "Reader reset epoch exhausted" }
            val event = SyncEvent(
                opId = operationIdGenerator.nextId(),
                hlc = nextLocalHlc(deviceId, now),
                mutations = listOf(
                    ReadingProgressSet(
                        chapterKey = keys.chapter,
                        mangaKey = keys.manga,
                        position = ReaderPosition(readingMode, 0, 0.0, previousEpoch + 1),
                        readState = false,
                        historyTouchedAt = historyTouchedAt,
                        sessionId = sessionId,
                    ),
                ),
            )
            applyLocalEvent(event, now, readerCoalescingKey(sessionId, keys.chapter))
        }
        flushProjectionIfPending()
        scheduleSeal(sessionId)
        ReaderReportResult(
            recorded = true,
            draftId = draft.draftId,
            positionRegister = currentReadingPosition(chapterKey),
        )
    }

    /** Page settle timers can call this without manufacturing another progress mutation. */
    suspend fun sealDueDrafts(): Int = locked {
        ensureOpen()
        if (isIncognito()) return@locked 0
        val now = nowMillis()
        val due = localStore.readState().drafts.values.filter { draft ->
            val sessionId = draft.readerSessionId() ?: return@filter false
            now - (lastSealedAtBySession[sessionId] ?: draft.createdAtMillis) >= sealIntervalMillis
        }
        var count = 0
        due.forEach { draft ->
            val sessionId = requireNotNull(draft.readerSessionId())
            if (trySealDraft(draft.draftId, sessionId)) count += 1
        }
        if (count > 0) {
            requestRemoteFlush()
        }
        count
    }

    suspend fun flushReaderSession(sessionId: String): Unit = locked {
        ensureOpen()
        scheduledSealBySession.remove(sessionId)?.cancel()
        awaitProjectionAndSeal(select = { it.readerSessionId() == sessionId })
        awaitRemoteFlush()
    }

    /** Implements the architecture's exact bounded background order. */
    override suspend fun flushForBackground(): Unit = locked {
        if (closed) return@locked
        scheduledSealBySession.values.forEach { it.cancel() }
        scheduledSealBySession.clear()
        // Acquiring this mutex first awaits every in-flight reader mutation.
        awaitProjectionAndSeal(
            select = { it.readerSessionId() != null },
            beforeSeal = beforeBackgroundSeal,
        )
    }

    /** Cancels component-owned timers and permanently prevents their finally blocks rescheduling. */
    suspend fun close(): Unit = locked {
        if (closed) return@locked
        closed = true
        scheduledSealBySession.values.forEach { it.cancel() }
        scheduledSealBySession.clear()
        val pendingRemoteFlush = remoteFlushScheduleMutex.withLock {
            remoteFlushJob?.also { it.cancel() }.also { remoteFlushJob = null }
        }
        pendingRemoteFlush?.join()
        lastSealedAtBySession.clear()
    }

    /**
     * Requests a conflated remote flush. The local seal has already committed, so a slow
     * capability request or catch-up must never delay a page settle or a reader progress callback.
     */
    private suspend fun requestRemoteFlush(): Job? {
        val targetScope = scope
        if (targetScope == null) {
            // The production graph always supplies a scope. Keep the small test/preview graph
            // deterministic when it deliberately omits one.
            withTimeoutOrNull(backgroundRemoteTimeoutMillis) { remoteOutboxFlusher.flush() }
            return null
        }
        return remoteFlushScheduleMutex.withLock {
            if (closed) return@withLock null
            remoteFlushJob?.takeIf { it.isActive }?.let { return@withLock it }
            lateinit var launched: Job
            launched = targetScope.launch {
                try {
                    withTimeoutOrNull(backgroundRemoteTimeoutMillis) { remoteOutboxFlusher.flush() }
                } finally {
                    remoteFlushScheduleMutex.withLock {
                        if (remoteFlushJob === currentCoroutineContext()[Job]) {
                            remoteFlushJob = null
                        }
                    }
                }
            }
            remoteFlushJob = launched
            launched
        }
    }

    private suspend fun awaitRemoteFlush() {
        val job = requestRemoteFlush()
        if (job != null) {
            withTimeoutOrNull(backgroundRemoteTimeoutMillis) { job.join() }
        }
    }

    private suspend fun awaitProjectionAndSeal(
        select: (SyncDraft) -> Boolean,
        beforeSeal: suspend () -> Unit = {},
    ) {
        flushProjectionIfPending()
        // The repository view is the local recovery boundary. It must reach disk before an
        // immutable event can become eligible for remote upload.
        beforeSeal()
        val drafts = localStore.readState().drafts.values
            .filter(select)
            .sortedWith(compareBy<SyncDraft> { it.createdAtMillis }.thenBy { it.draftId })
        if (drafts.isEmpty()) return
        val session = requireReadySession()
        val state = localStore.readState()
        val sealer = crypto.prepareEventSealer(session, state.activeKeyEpoch)
        try {
            val context = EventSealContext(session.instanceId, session.workspaceId, session.deviceId)
            drafts.forEach { draft ->
                if (draft.draftId in localStore.readState().drafts) {
                    localStore.sealDraft(
                        draft.draftId,
                        context,
                        localStore.readState().activeKeyEpoch,
                        nowMillis(),
                        sealer,
                    )
                    draft.readerSessionId()?.let { lastSealedAtBySession[it] = nowMillis() }
                }
            }
        } finally {
            sealer.close()
        }
    }

    private suspend fun flushProjectionIfPending() {
        repeat(MAX_PROJECTION_RETRIES) {
            val before = localStore.readState()
            if (!before.materializationPending) return
            projectionSink.flush(before)
            val marked = localStore.transaction {
                markMaterialized(
                    expectedReplica = before.replica,
                    expectedIdentityMap = before.identityMap,
                    expectedRepositoryTrustConfirmations = before.repositoryTrustConfirmations,
                    expectedRepositoryTrustApprovals = before.repositoryTrustApprovals,
                )
            }
            if (marked) return
        }
        throw IllegalStateException("Reader projection kept changing before it could be marked durable")
    }

    private fun scheduleSeal(sessionId: String) {
        if (closed) return
        val targetScope = scope ?: return
        if (scheduledSealBySession[sessionId]?.isActive == true) return
        scheduledSealBySession[sessionId] = targetScope.launch {
            try {
                delay(sealIntervalMillis)
                sealDueDrafts()
            } finally {
                locked {
                    scheduledSealBySession.remove(sessionId)
                    val stillPending = !closed && !isIncognito() && localStore.readState().drafts.values.any {
                        it.readerSessionId() == sessionId
                    }
                    if (stillPending) scheduleSeal(sessionId)
                }
            }
        }
    }

    private suspend fun trySealDraft(draftId: String, sessionId: String): Boolean = try {
        val session = requireReadySession()
        val state = localStore.readState()
        if (draftId !in state.drafts) return false
        val sealer = crypto.prepareEventSealer(session, state.activeKeyEpoch)
        try {
            localStore.sealDraft(
                draftId = draftId,
                context = EventSealContext(session.instanceId, session.workspaceId, session.deviceId),
                keyEpoch = state.activeKeyEpoch,
                nowMillis = nowMillis(),
                sealer = sealer,
            )
            lastSealedAtBySession[sessionId] = nowMillis()
            true
        } finally {
            sealer.close()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Reader interaction remains local-first; the durable draft is retried on lifecycle flush.
        false
    }

    private suspend fun requireReadyDeviceId(): String = requireReadySession().deviceId

    /** Resolve both references from the exact transaction snapshot used to read/write progress. */
    private fun SyncStoreTransaction.resolveReaderKeys(
        chapterKey: SyncEntityKey,
        mangaKey: SyncEntityKey,
    ): ResolvedReaderKeys {
        val replica = state().replica
        return ResolvedReaderKeys(
            chapter = replica.resolveKey(chapterKey),
            manga = replica.resolveKey(mangaKey),
        )
    }

    private fun ensureOpen() {
        check(!closed) { "Reader progress reporter is closed" }
    }

    private suspend fun requireReadySession(): SyncSession {
        val session = sessionStore.load() ?: throw IllegalStateException("Sync is not configured")
        require(session.status == SyncSessionStatus.READY) { "Sync session is not ready" }
        return session
    }

    private fun readerCoalescingKey(sessionId: String, chapterKey: SyncEntityKey): String =
        "reader|$sessionId|${chapterKey.stableString()}"

    private fun contentReaderCoalescingKey(sessionId: String, key: ContentProgressKeyV2): String =
        "content-reader|$sessionId|${key.stableString()}"

    private fun SyncDraft.readerSessionId(): String? {
        val key = coalescingKey ?: return null
        val prefix = when {
            key.startsWith("reader|") -> "reader|"
            key.startsWith("content-reader|") -> "content-reader|"
            else -> return null
        }
        return key.removePrefix(prefix).substringBefore('|').takeIf { it.isNotBlank() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val MAX_PROJECTION_RETRIES = 3
    }

    private data class ResolvedReaderKeys(
        val chapter: SyncEntityKey,
        val manga: SyncEntityKey,
    )
}
