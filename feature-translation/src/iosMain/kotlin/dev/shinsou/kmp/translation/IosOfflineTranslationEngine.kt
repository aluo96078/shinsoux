package dev.shinsou.kmp.translation

import dev.shinsou.interop.translation.stc_create
import dev.shinsou.interop.translation.stc_destroy
import cnames.structs.stc_engine
import dev.shinsou.interop.translation.stc_free
import dev.shinsou.interop.translation.stc_free_lines
import dev.shinsou.interop.translation.stc_model
import dev.shinsou.interop.translation.stc_translate
import dev.shinsou.interop.translation.stc_unload
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * iOS's offline engine: the translation core linked into the app, on one thread of its own (the
 * engine is not re-entrant, and a chapter takes seconds). Model files are verified against the
 * catalog when they are downloaded; [OfflineModelFiles.installed] checks their size again before
 * they reach the engine, which aborts the process on a file it cannot load.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
public class IosOfflineTranslationEngine : OfflineTranslationEngine {
    private val thread = newSingleThreadContext("shinsou-translate")
    private var engine: CPointer<stc_engine>? = null

    override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        return withContext(thread) {
            val handle = engine ?: stc_create()?.also { engine = it }
                ?: throw TranslationException(TranslationFailure.ENGINE_FAILED, "The translation engine could not start")
            memScoped {
                val paths = alloc<stc_model>()
                paths.model = model.model.cstr.ptr
                paths.src_vocab = model.sourceVocab.cstr.ptr
                paths.trg_vocab = model.targetVocab.cstr.ptr
                paths.shortlist = model.shortlist.cstr.ptr
                val inputs = allocArray<CPointerVar<ByteVar>>(lines.size)
                lines.forEachIndexed { index, line -> inputs[index] = line.replace('\n', ' ').replace('\r', ' ').cstr.ptr }
                val outputs = alloc<CPointerVar<CPointerVar<ByteVar>>>()
                val error = alloc<CPointerVar<ByteVar>>()
                if (stc_translate(handle, paths.ptr, inputs, lines.size.toULong(), outputs.ptr, error.ptr) != 0) {
                    val message = error.value?.toKString() ?: "The translation engine failed"
                    stc_free(error.value)
                    throw TranslationException(TranslationFailure.ENGINE_FAILED, message)
                }
                val result = outputs.value ?: throw TranslationException(TranslationFailure.ENGINE_FAILED, "The translation engine returned no result")
                try {
                    List(lines.size) { index -> result[index]?.toKString().orEmpty() }
                } finally {
                    stc_free_lines(result, lines.size.toULong())
                }
            }
        }
    }

    override suspend fun unload(): Unit = withContext(thread) {
        engine?.let { stc_unload(it) }
    }

    /** Releases the engine and its thread. The engine must not be used afterwards. */
    public suspend fun close() {
        withContext(thread) {
            engine?.let { stc_destroy(it) }
            engine = null
        }
        thread.close()
    }
}
