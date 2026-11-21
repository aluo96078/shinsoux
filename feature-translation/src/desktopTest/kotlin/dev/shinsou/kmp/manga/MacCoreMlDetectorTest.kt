package dev.shinsou.kmp.manga

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The R50 detector through Core ML on Apple silicon, against the same model on the CPU. Runs where
 * SHINSOU_MANGA_PACKS holds vision-v1 and vision-qnn-v1 and SHINSOU_MANGA_PAGES holds pages.
 */
class MacCoreMlDetectorTest {
    @Test
    fun theGpuFindsWhatTheCpuFinds() {
        val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File) ?: return
        val pages = System.getenv("SHINSOU_MANGA_PAGES")?.let(::File)?.listFiles { f -> f.name.endsWith(".jpg") }?.sorted() ?: return
        val model = packs.resolve("vision-qnn-v1/detector.onnx").takeIf { it.isFile } ?: return
        val platform = DesktopMangaPlatform.create()
        val gpu = platform.accelerators().first { it.accelerator == MangaAccelerator.GPU }
        if (!gpu.supported) return // not Apple silicon
        val v = packs.resolve("vision-v1")
        val paths = MangaModelPaths(v.resolve("detector.onnx").path, v.resolve("encoder.onnx").path, v.resolve("decoder.onnx").path, v.resolve("vocab.txt").path, v.resolve("NotoSansTC-VF.ttf").path, qnnDetector = model.path)
        val images = pages.map { platform.codec.decode(it.readBytes())!! }
        val cpu = OrtQueryDetector(platform.ops, ortEnvironment().createSession(model.path), "cpu")
        val expected = images.map { cpu.detect(it) }
        for (accelerator in listOf(MangaAccelerator.GPU, MangaAccelerator.NPU)) {
            val detector = platform.acceleratedDetector(paths, accelerator)!!
            detector.detect(images.first())
            val t = System.nanoTime()
            val found = images.map { detector.detect(it) }
            println("MAC $accelerator ${detector.id} ${(System.nanoTime() - t) / 1_000_000 / images.size} ms/page, ${found.sumOf { it.size }} vs ${expected.sumOf { it.size }} regions")
            if (accelerator == MangaAccelerator.GPU) {
                assertTrue(detector.id == "coreml-r50-mac")
                val total = expected.sumOf { it.size }
                assertTrue(kotlin.math.abs(found.sumOf { it.size } - total) <= total / 20, "the GPU finds about what the CPU finds")
            }
            (detector as AutoCloseable).close()
        }
        assertTrue(model.resolveSibling("coreml-CPUAndGPU").list().orEmpty().isNotEmpty(), "the compiled model is kept beside the model")
    }
}
