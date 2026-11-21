package dev.shinsou.kmp.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.test.Test

/**
 * GPU and Neural Engine research on a Mac (ADR-0005 §8): each model alone on the CPU and on CoreML
 * as an MLProgram, by compute unit, with its outputs compared to the CPU's. Runs where
 * SHINSOU_MANGA_BENCH=1, SHINSOU_MANGA_PACKS and SHINSOU_MANGA_FP32 (the fp32 originals) are set.
 */
class MangaAcceleratorResearchTest {
    private val env = OrtEnvironment.getEnvironment()
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)
    private val fp32 = System.getenv("SHINSOU_MANGA_FP32")?.let(::File)

    private class Case(val name: String, val file: File, val inputs: () -> Map<String, OnnxTensor>)

    private fun floats(shape: LongArray, seed: Int = 1): OnnxTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        val r = java.util.Random(seed.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { r.nextFloat() }), shape)
    }

    private fun mask(h: Long, w: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray((h * w).toInt()) { i -> if ((i / w.toInt()) % 8 < 3) 1f else 0f }), longArrayOf(1, 1, h, w))

    private fun session(file: File, units: String?): OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(4)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (units != null) o.addCoreML(mapOf("ModelFormat" to "MLProgram", "MLComputeUnits" to units, "RequireStaticInputShapes" to "0"))
        env.createSession(file.path, o)
    }

    private fun run(session: OrtSession, inputs: Map<String, OnnxTensor>): FloatArray =
        session.run(inputs).use { out ->
            out.map { it.value as OnnxTensor }.first { it.info.type == ai.onnxruntime.OnnxJavaType.FLOAT }.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        }

    @Test
    fun modelsOnTheGpuAndNeuralEngine() {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || fp32 == null) return
        val v = packs.resolve("vision-v1")
        val sizes = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1131, 800)), longArrayOf(1, 2))
        val cases = listOf(
            Case("detector int8 (1 page)", v.resolve("detector.onnx")) { mapOf("images" to floats(longArrayOf(1, 3, 640, 640)), "orig_target_sizes" to sizes) },
            Case("ocr encoder int8 (batch 8)", v.resolve("encoder.onnx")) { mapOf("pixel_values" to floats(longArrayOf(8, 3, 224, 224))) },
            Case("ocr encoder fp32 (batch 8)", fp32.resolve("past_encoder_model.onnx")) { mapOf("pixel_values" to floats(longArrayOf(8, 3, 224, 224))) },
            Case("lama int8 (384x512 region)", packs.resolve("inpaint-v1/lama.onnx")) { mapOf("image" to floats(longArrayOf(1, 3, 512, 384)), "mask" to mask(512, 384)) },
            Case("lama fp32 (384x512 region)", fp32.resolve("lama-manga-dynamic.onnx")) { mapOf("image" to floats(longArrayOf(1, 3, 512, 384)), "mask" to mask(512, 384)) },
        )
        for (case in cases) {
            val inputs = case.inputs()
            val reference = session(case.file, null).use { s -> run(s, inputs); val t = System.nanoTime(); repeat(5) { run(s, inputs) }; println("RESEARCH ${case.name} CPU ${(System.nanoTime() - t) / 5_000_000}ms"); run(s, inputs) }
            for (units in listOf("ALL", "CPUAndGPU", "CPUAndNeuralEngine")) {
                runCatching {
                    var t = System.nanoTime()
                    session(case.file, units).use { s ->
                        val out = run(s, inputs); val load = (System.nanoTime() - t) / 1_000_000
                        t = System.nanoTime(); repeat(5) { run(s, inputs) }
                        val ms = (System.nanoTime() - t) / 5_000_000
                        val err = out.indices.maxOfOrNull { kotlin.math.abs(out[it] - reference[it]) } ?: 0f
                        val scale = reference.maxOfOrNull { kotlin.math.abs(it) } ?: 1f
                        println("RESEARCH ${case.name} CoreML/$units first=${load}ms ${ms}ms maxErr=${"%.4f".format(err / scale)} (relative)")
                    }
                }.onFailure { println("RESEARCH ${case.name} CoreML/$units failed: ${it.message?.take(160)}") }
            }
        }
    }
}
