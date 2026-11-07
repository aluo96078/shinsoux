package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionDownloadQueueV2Test {
    private val owner = Any()

    private fun selection(unitId: String) = ExtensionUnitSelectionV2(
        sourceKey = SOURCE_KEY,
        remotePublicationId = "publication",
        publicationTitle = "Publication",
        unit = RemoteUnitV2(unitId, "Unit $unitId"),
        ownerToken = owner,
    )

    private fun ExtensionDownloadQueueV2.states() = entries.value.associate { it.remoteUnitId to it.state }

    private suspend fun ExtensionDownloadQueueV2.awaitSettled() = withTimeout(5_000) {
        entries.first { list ->
            list.none { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
        }
    }

    @Test
    fun downloadsRunInOrderAndSkipStoredUnits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val fetched = mutableListOf<String>()
        val queue = ExtensionDownloadQueueV2(
            materialize = { fetched += it.unit.remoteId },
            isStored = { it.unit.remoteId == "b" },
            scope = scope,
        )
        queue.enqueue(listOf(selection("a"), selection("b"), selection("c")))
        queue.awaitSettled()

        assertEquals(listOf("a", "c"), fetched)
        assertEquals(
            mapOf("a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADED, "c" to DownloadState.DOWNLOADED),
            queue.states(),
        )
        // Enqueueing the same unit twice keeps one row.
        queue.enqueue(listOf(selection("a")))
        queue.awaitSettled()
        assertEquals(3, queue.entries.value.size)
        scope.cancel()
    }

    @Test
    fun failuresAreReportedAndRetryable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var fail = true
        val queue = ExtensionDownloadQueueV2(
            materialize = { if (fail) error("offline") },
            scope = scope,
        )
        queue.enqueue(listOf(selection("a")))
        queue.awaitSettled()
        val failed = queue.entries.value.single()
        assertEquals(DownloadState.ERROR, failed.state)
        assertEquals("offline", failed.errorMessage)

        fail = false
        queue.retry(failed.id)
        queue.awaitSettled()
        assertEquals(DownloadState.DOWNLOADED, queue.entries.value.single().state)
        scope.cancel()
    }

    @Test
    fun removingTheActiveUnitCancelsItAndTheQueueContinues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val queue = ExtensionDownloadQueueV2(
            materialize = { selection ->
                if (selection.unit.remoteId == "a") {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            },
            scope = scope,
        )
        queue.enqueue(listOf(selection("a"), selection("b")))
        withTimeout(5_000) { started.await() }
        queue.remove(ExtensionDownloadQueueV2.entryId(selection("a")))
        queue.awaitSettled()

        assertEquals(mapOf("b" to DownloadState.DOWNLOADED), queue.states())
        scope.cancel()
    }

    @Test
    fun pauseParksWorkAndResumeFinishesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        var block = true
        val queue = ExtensionDownloadQueueV2(
            materialize = {
                if (block) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            },
            scope = scope,
        )
        queue.enqueue(listOf(selection("a"), selection("b")))
        withTimeout(5_000) { started.await() }
        queue.setPaused(true)
        withTimeout(5_000) {
            queue.entries.first { list -> list.all { it.state == DownloadState.PAUSED } }
        }

        block = false
        queue.setPaused(false)
        queue.awaitSettled()
        assertTrue(queue.entries.value.all { it.state == DownloadState.DOWNLOADED })
        scope.cancel()
    }

    @Test
    fun aRestartedQueueRestoresSavedWorkAndResumesIt() = runBlocking {
        val store = object : ExtensionDownloadQueueStoreV2 {
            var saved: List<ExtensionDownloadRecordV2> = emptyList()
            override suspend fun load() = saved
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) { saved = records }
        }
        val firstScope = CoroutineScope(SupervisorJob())
        val first = ExtensionDownloadQueueV2(
            materialize = { CompletableDeferred<Unit>().await() },
            scope = firstScope,
            store = store,
        )
        first.restore { null }
        first.enqueue(listOf(selection("a"), selection("b")))
        withTimeout(5_000) {
            while (store.saved.size != 2 || store.saved.none { it.state == DownloadState.DOWNLOADING }) {
                kotlinx.coroutines.delay(20)
            }
        }
        // The process dies mid-download.
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob())
        val fetched = mutableListOf<String>()
        val second = ExtensionDownloadQueueV2(
            materialize = { fetched += it.unit.remoteId },
            scope = secondScope,
            store = store,
        )
        second.restore { record -> selection(record.unit.remoteId) }
        second.awaitSettled()

        assertEquals(listOf("a", "b"), fetched)
        assertEquals(mapOf("a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADED), second.states())
        secondScope.cancel()
    }

    @Test
    fun repeatedFailuresPauseTheQueueUntilTheAppReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var offline = true
        val attempts = mutableListOf<String>()
        val queue = ExtensionDownloadQueueV2(
            materialize = {
                attempts += it.unit.remoteId
                if (offline) error("offline")
            },
            scope = scope,
        )
        queue.enqueue((1..6).map { selection("u$it") })
        queue.awaitSettled()

        // Three failures in a row stop the queue instead of failing every unit.
        assertEquals(listOf("u1", "u2", "u3"), attempts)
        assertEquals(List(3) { DownloadState.ERROR } + List(3) { DownloadState.PAUSED }, queue.entries.value.map { it.state })

        offline = false
        queue.resumeAfterFailures()
        queue.awaitSettled()
        assertTrue(queue.entries.value.all { it.state == DownloadState.DOWNLOADED })
        scope.cancel()
    }

    @Test
    fun aQueueThatStoppedOnFailuresRetriesAfterRestart() = runBlocking {
        val store = object : ExtensionDownloadQueueStoreV2 {
            var saved: List<ExtensionDownloadRecordV2> = emptyList()
            override suspend fun load() = saved
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) { saved = records }
        }
        val firstScope = CoroutineScope(SupervisorJob())
        val first = ExtensionDownloadQueueV2(materialize = { error("offline") }, scope = firstScope, store = store)
        first.restore { null }
        first.enqueue((1..4).map { selection("u$it") })
        withTimeout(5_000) {
            while (store.saved.size != 4 || !store.saved.all { it.autoPaused }) kotlinx.coroutines.delay(20)
        }
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob())
        val second = ExtensionDownloadQueueV2(materialize = { }, scope = secondScope, store = store)
        second.restore { selection(it.unit.remoteId) }
        second.awaitSettled()
        assertTrue(second.entries.value.all { it.state == DownloadState.DOWNLOADED })
        secondScope.cancel()
    }

    @Test
    fun removingATitleAlsoDropsItsSavedOnlyRecords() = runBlocking {
        val store = object : ExtensionDownloadQueueStoreV2 {
            var saved: List<ExtensionDownloadRecordV2> = listOf(
                ExtensionDownloadRecordV2(SOURCE_KEY, "publication", "Publication", RemoteUnitV2("a", "Unit a"), DownloadState.QUEUED),
            )
            override suspend fun load() = saved
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) { saved = records }
        }
        val scope = CoroutineScope(SupervisorJob())
        val queue = ExtensionDownloadQueueV2(materialize = { }, scope = scope, store = store)
        queue.restore { null } // source unavailable: kept as a saved-only record
        queue.removePublication(SOURCE_KEY, "publication")
        withTimeout(5_000) { while (store.saved.isNotEmpty()) kotlinx.coroutines.delay(20) }
        scope.cancel()
    }

    @Test
    fun flushSavesAPauseImmediately() = runBlocking {
        val store = object : ExtensionDownloadQueueStoreV2 {
            var saved: List<ExtensionDownloadRecordV2> = emptyList()
            override suspend fun load() = saved
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) { saved = records }
        }
        val scope = CoroutineScope(SupervisorJob())
        val queue = ExtensionDownloadQueueV2(materialize = { }, scope = scope, store = store, startOpen = false)
        queue.restore { it.toSelection() }
        queue.enqueue(listOf(selection("a")))
        queue.setPaused(true)
        // No waiting for the debounce: a pause the system made must be on disk before it returns.
        queue.flush()
        assertEquals(listOf(DownloadState.PAUSED), store.saved.map { it.state })
        scope.cancel()
    }

    @Test
    fun aSlowOlderSaveNeverOverwritesANewerFlush() = runBlocking {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val store = object : ExtensionDownloadQueueStoreV2 {
            var saved: List<ExtensionDownloadRecordV2> = emptyList()
            var saves = 0
            override suspend fun load() = saved
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) {
                if (saves++ == 0) {
                    firstSaveStarted.complete(Unit)
                    releaseFirstSave.await()
                }
                saved = records
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        val queue = ExtensionDownloadQueueV2(materialize = { }, scope = scope, store = store, startOpen = false)
        queue.restore { it.toSelection() }
        queue.enqueue(listOf(selection("a")))
        queue.setPaused(true)
        firstSaveStarted.await() // the debounced writer is stuck saving PAUSED
        queue.setPaused(false)
        val flushed = scope.launch { queue.flush() }
        kotlinx.coroutines.delay(50)
        releaseFirstSave.complete(Unit)
        flushed.join()

        assertEquals(listOf(DownloadState.QUEUED), store.saved.map { it.state })
        scope.cancel()
    }

    @Test
    fun flushFailsWhenTheQueueWasNeverRestored() = runBlocking {
        val store = object : ExtensionDownloadQueueStoreV2 {
            override suspend fun load(): List<ExtensionDownloadRecordV2> = error("unreadable")
            override suspend fun save(records: List<ExtensionDownloadRecordV2>) = error("must not save")
        }
        val scope = CoroutineScope(SupervisorJob())
        val queue = ExtensionDownloadQueueV2(materialize = { }, scope = scope, store = store, startOpen = false)
        queue.restore { it.toSelection() }

        assertTrue(runCatching { queue.flush() }.isFailure)
        scope.cancel()
    }

    @Test
    fun aSystemPauseStopsOnlyTheGivenEntriesAndTheyStayPaused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queue = ExtensionDownloadQueueV2(
            materialize = { if (it.unit.remoteId == "a" && !release.isCompleted) { started.complete(Unit); release.await() } },
            scope = scope,
        )
        queue.enqueue(listOf(selection("a"), selection("b")))
        started.await()
        val a = queue.entries.value.first { it.remoteUnitId == "a" }.id
        val b = queue.entries.value.first { it.remoteUnitId == "b" }.id
        queue.pauseForSystem(setOf(a, b))
        kotlinx.coroutines.delay(100)
        // The cancelled download does not fall back to "queued" and start again.
        assertEquals(mapOf("a" to DownloadState.PAUSED, "b" to DownloadState.PAUSED), queue.states())

        release.complete(Unit)
        queue.resumeAfterSystem(setOf(a, b))
        queue.awaitSettled()
        assertEquals(mapOf("a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADED), queue.states())
        scope.cancel()
    }

    @Test
    fun aClosedQueueHoldsWorkUntilOpened() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val fetched = mutableListOf<String>()
        val queue = ExtensionDownloadQueueV2(materialize = { fetched += it.unit.remoteId }, scope = scope, startOpen = false)
        queue.enqueue(listOf(selection("a")))
        kotlinx.coroutines.delay(100)
        assertEquals(emptyList(), fetched)
        assertEquals(DownloadState.QUEUED, queue.entries.value.single().state)

        queue.open()
        queue.awaitSettled()
        assertEquals(listOf("a"), fetched)
        scope.cancel()
    }

    @Test
    fun aLockedDatabaseIsRetriedInsteadOfPausingTheQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val attempts = mutableMapOf<String, Int>()
        val queue = ExtensionDownloadQueueV2(
            materialize = {
                val attempt = attempts.merge(it.unit.remoteId, 1, Int::plus)!!
                // Each unit first meets the database locked by another connection.
                if (attempt == 1) throw IllegalStateException("[SQLITE_BUSY] The database file is locked (database is locked)")
            },
            scope = scope,
            storageBusyRetryDelayMillis = 10,
        )
        queue.enqueue(listOf(selection("a"), selection("b"), selection("c")))
        withTimeout(5_000) {
            queue.entries.first { list -> list.isNotEmpty() && list.all { it.state == DownloadState.DOWNLOADED } }
        }
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 2), attempts)
        scope.cancel()
    }

    @Test
    fun aStillLockedDatabaseEventuallyFailsTheUnit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var attempts = 0
        val queue = ExtensionDownloadQueueV2(
            materialize = {
                attempts++
                throw IllegalStateException("wrapped", IllegalStateException("[SQLITE_BUSY_SNAPSHOT] database is locked"))
            },
            scope = scope,
            storageBusyRetryDelayMillis = 10,
        )
        queue.enqueue(listOf(selection("a")))
        withTimeout(5_000) {
            queue.entries.first { list -> list.singleOrNull()?.state == DownloadState.ERROR }
        }
        // The first try plus five retries.
        assertEquals(6, attempts)
        scope.cancel()
    }

    private fun ExtensionDownloadRecordV2.toSelection() =
        ExtensionUnitSelectionV2(sourceKey, remotePublicationId, publicationTitle, unit, owner)

    private companion object {
        val SOURCE_KEY = SourceKey(2, "fixture.extension", "opaque-source")
    }
}
