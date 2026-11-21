package dev.shinsou.kmp.manga

import ai.onnxruntime.OrtEnvironment
import dev.shinsou.kmp.logging.Log

/**
 * The process's ONNX Runtime environment, with the runtime's own usage telemetry switched off:
 * pages are translated on the device and nothing about it is reported to Microsoft. (Android's
 * telemetry start-up provider is removed from the app manifest as well.)
 */
internal fun ortEnvironment(): OrtEnvironment = OrtEnvironment.getEnvironment().also { env ->
    runCatching { env.setTelemetry(false) }
        .onFailure { Log.warn("Manga", it) { "Switching ONNX Runtime telemetry off failed" } }
}
