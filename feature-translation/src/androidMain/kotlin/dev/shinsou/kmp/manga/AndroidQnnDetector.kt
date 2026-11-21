package dev.shinsou.kmp.manga

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.shinsou.kmp.logging.Log
import java.io.File

/**
 * The detector (RT-DETRv2 R50, fp16) on a Snapdragon's NPU through ONNX Runtime's QNN execution
 * provider (docs/research/manga-gpu-npu.md): about 55 ms a page on a Snapdragon 8 Gen 3, reading
 * exactly as the full model does on the CPU. The QNN libraries are not shipped with the app: they
 * are downloaded from Maven Central into [runtime] and loaded from there. The graph is compiled
 * for this device's NPU the first time (about half a minute) and kept in the cache directory.
 */
internal object AndroidQnnDetector {
    /** Opens the detector, compiling it for this NPU the first time. */
    fun open(ops: VisionOps, model: String, runtime: String, cacheDirectory: File): OrtQueryDetector {
        val env = OrtEnvironment.getEnvironment()
        loadRuntime(runtime)
        cacheDirectory.mkdirs()
        // the compiled graph belongs to this model and this QNN version
        val compiled = File(cacheDirectory, "detector-${MangaModelCatalog.QNN_DETECTOR.files.single().sha256.take(12)}-qnn${MangaModelCatalog.QNN_RUNTIME_VERSION}_ctx.onnx")
        fun options(compile: Boolean) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2) // for the few nodes the NPU leaves to the CPU
            if (compile) {
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", compiled.path)
                addConfigEntry("ep.context_embed_mode", "0")
            }
            addQnn(mapOf("backend_path" to File(runtime, "libQnnHtp.so").path, "htp_performance_mode" to "burst", "htp_graph_finalization_optimization_mode" to "3"))
        }
        val session = compiled.takeIf { it.exists() }?.let {
            runCatching { env.createSession(it.path, options(compile = false)) }
                .onFailure { error -> Log.warn("QnnDetector", error) { "compiled QNN context unusable; compiling again" } }
                .getOrNull()
        }
            ?: run {
                cacheDirectory.listFiles { f -> f.name.startsWith("detector-") }?.forEach { it.delete() }
                options(compile = true).use { env.createSession(model, it).close() }
                options(compile = false).use { env.createSession(compiled.path, it) }
            }
        return OrtQueryDetector(ops, session, ID)
    }

    const val ID: String = "qnn-r50"

    private var loaded: String? = null

    /**
     * Loads the QNN libraries from [directory] (the backend opens its helpers by name, so they
     * are loaded first) and points the NPU's loader at the directory for the device-side part.
     */
    @Synchronized
    fun loadRuntime(directory: String) {
        if (loaded == directory) return
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$directory;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        val dir = File(directory)
        val stubs = dir.listFiles { f -> f.name.startsWith("libQnnHtpV") && f.name.endsWith("Stub.so") }.orEmpty().sortedBy { it.name }
        for (lib in listOf(File(dir, "libQnnSystem.so")) + stubs + listOf(File(dir, "libQnnHtpPrepare.so"), File(dir, "libQnnHtp.so"))) {
            System.load(lib.path)
        }
        loaded = directory
    }
}
