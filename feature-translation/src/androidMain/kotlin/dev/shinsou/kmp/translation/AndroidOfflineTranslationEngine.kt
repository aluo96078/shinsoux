package dev.shinsou.kmp.translation

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** JNI surface of `libshinsou_translate.so` (translation-core/src/stc_jni.cpp). Text is UTF-8 bytes. */
internal object NativeTranslationCore {
    val available: Boolean by lazy {
        // Only arm64-v8a carries the library (see androidMain/cpp/CMakeLists.txt).
        runCatching { System.loadLibrary("shinsou_translate") }.isSuccess
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun unload(handle: Long)
    external fun translate(
        handle: Long,
        model: ByteArray,
        sourceVocab: ByteArray,
        targetVocab: ByteArray,
        shortlist: ByteArray,
        lines: Array<ByteArray>,
    ): Array<ByteArray>?
}

/**
 * Android's offline engine: the translation core loaded into the app process, on one thread of
 * its own (the engine is not re-entrant, and a chapter takes seconds). Model files are verified
 * against the catalog when they are downloaded; [OfflineModelFiles.installed] checks their size
 * again before they reach the engine, which aborts the process on a file it cannot load.
 */
public class AndroidOfflineTranslationEngine : OfflineTranslationEngine, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "shinsou-translate").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var handle = 0L

    override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (!NativeTranslationCore.available) {
            throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "Offline translation is not available on this device")
        }
        return withContext(dispatcher) {
            if (handle == 0L) handle = NativeTranslationCore.create()
            if (handle == 0L) throw TranslationException(TranslationFailure.ENGINE_FAILED, "The translation engine could not start")
            val outputs = try {
                NativeTranslationCore.translate(
                    handle,
                    model.model.toByteArray(),
                    model.sourceVocab.toByteArray(),
                    model.targetVocab.toByteArray(),
                    model.shortlist.toByteArray(),
                    Array(lines.size) { lines[it].replace('\n', ' ').replace('\r', ' ').toByteArray() },
                )
            } catch (error: IllegalStateException) {
                throw TranslationException(TranslationFailure.ENGINE_FAILED, error.message ?: "The translation engine failed", error)
            } catch (error: OutOfMemoryError) {
                NativeTranslationCore.unload(handle)
                throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "Not enough memory for offline translation", error)
            }
            if (outputs == null || outputs.size != lines.size) {
                throw TranslationException(TranslationFailure.ENGINE_FAILED, "The translation engine returned no result")
            }
            outputs.map { it.toString(Charsets.UTF_8) }
        }
    }

    override suspend fun unload(): Unit = withContext(dispatcher) {
        if (handle != 0L) NativeTranslationCore.unload(handle)
    }

    override fun close() {
        executor.execute {
            if (handle != 0L) NativeTranslationCore.destroy(handle)
            handle = 0L
        }
        executor.shutdown()
    }
}
