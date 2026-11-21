package dev.shinsou.kmp.manga

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The [MangaPipeline] chapters and language probes share (0007 C5): built by [create] when one is
 * first needed, held by each chapter until it ends, so the models never close under it. One
 * [release]d while held is marked stale and replaced once no chapter holds it.
 */
internal class MangaPipelines(private val create: () -> MangaPipeline) {
    private var pipeline: MangaPipeline? = null
    private val lock = Mutex()

    /** Chapters using each pipeline: a replaced one closes when its last chapter ends. */
    private val holders = mutableMapOf<MangaPipeline, Int>()
    private var stale = false

    /** Chapters holding any pipeline: files they read are deleted only once there are none. */
    private val held = MutableStateFlow(0)
    private val removing = MutableStateFlow(0)

    /**
     * Runs [block] with the pipeline, taken on [work] (building it reads the model files) and let
     * go afterwards even when cancelled: it is held from inside the worker's context, so a call
     * cancelled on the way back still lets it go.
     */
    suspend fun <T> holding(work: CoroutineDispatcher, block: suspend (MangaPipeline) -> T): T {
        var leased: MangaPipeline? = null
        try {
            val pipeline = withContext(work) { acquire().also { leased = it } }
            return block(pipeline)
        } finally {
            leased?.let { withContext(NonCancellable) { done(it) } }
        }
    }

    /**
     * The pipeline for one chapter, held until [done]: a chapter keeps the one it started with, so
     * the models never close under it. One marked stale is replaced once no chapter holds it.
     */
    private suspend fun acquire(): MangaPipeline {
        while (true) {
            // no chapter starts while models are being deleted
            removing.first { it == 0 }
            lock.withLock { if (removing.value == 0) return pipelineFor() }
        }
    }

    private fun pipelineFor(): MangaPipeline {
        if (stale) {
            stale = false
            pipeline?.let { old -> if (old !in holders) old.close() }
            pipeline = null
        }
        val current = pipeline ?: create().also { pipeline = it }
        holders[current] = (holders[current] ?: 0) + 1
        held.value++
        return current
    }

    private suspend fun done(used: MangaPipeline): Unit = lock.withLock {
        held.value--
        val left = (holders[used] ?: 1) - 1
        if (left > 0) {
            holders[used] = left
            return@withLock
        }
        holders.remove(used)
        if (used !== pipeline) used.close()
    }

    /**
     * Runs [delete] once the chapters still reading (one translated ahead may be waiting on its
     * translation, its fonts not yet loaded) have ended, no new one starting until it is done:
     * what they read is deleted only after, and none opens it half deleted.
     */
    suspend fun whileIdle(delete: suspend () -> Unit) {
        removing.update { it + 1 }
        try {
            release()
            held.first { it == 0 }
            // the last chapter has ended: its models close before their files go
            release()
            delete()
        } finally {
            removing.update { it - 1 }
        }
    }

    /** Frees the models' memory once no chapter is using them; they load again on the next chapter. */
    suspend fun release(): Unit = lock.withLock {
        val current = pipeline ?: return@withLock
        if (current in holders) {
            stale = true
        } else {
            current.close()
            pipeline = null
            stale = false
        }
    }
}
