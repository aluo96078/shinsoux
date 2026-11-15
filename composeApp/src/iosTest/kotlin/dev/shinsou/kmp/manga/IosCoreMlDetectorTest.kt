package dev.shinsou.kmp.manga

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class IosCoreMlDetectorTest {
    @Test
    fun halfFloatsReadBack() {
        assertEquals(0f, IosCoreMlDetector.halfToFloat(0))
        assertEquals(1f, IosCoreMlDetector.halfToFloat(0x3c00))
        assertEquals(-2f, IosCoreMlDetector.halfToFloat(0xc000))
        assertEquals(0.5f, IosCoreMlDetector.halfToFloat(0x3800))
        assertEquals(65504f, IosCoreMlDetector.halfToFloat(0x7bff))
        assertEquals(5.9604645E-8f, IosCoreMlDetector.halfToFloat(0x0001))
    }

    /** The Core ML detector against the CPU's on a real page (SHINSOU_MANGA_PACKS with vision-coreml-v1). */
    @Test
    fun theGpuDetectorFindsWhatTheCpuDetectorFinds() {
        val packs = getenv("SHINSOU_MANGA_PACKS")?.toKString() ?: return
        val pagePath = getenv("SHINSOU_MANGA_PAGE")?.toKString() ?: return
        val coreMl = "$packs/vision-coreml-v1/detector.mlpackage"
        if (!NSFileManager.defaultManager.fileExistsAtPath(coreMl)) return
        val platform = IosMangaPlatform()
        val data = NSData.dataWithContentsOfFile(pagePath)!!
        val bytes = ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
        val page = platform.codec.decode(bytes)!!
        val v = "$packs/vision-v1"
        val paths = MangaModelPaths("$v/detector.onnx", "$v/encoder.onnx", "$v/decoder.onnx", "$v/vocab.txt", "$v/NotoSansTC-VF.ttf", coreMlDetector = coreMl)
        // the simulator's GPU returns zeros: the platform refuses it there, so the model is checked on the CPU
        val onSimulator = platform.acceleratedDetector(paths, MangaAccelerator.GPU) == null
        println("MANGA ios coreml gpu usable=${!onSimulator}")
        val gpu = IosCoreMlDetector(platform.ops, coreMl, IosCoreMlDetector.Units.CPU)
        assertEquals("coreml-r50-cpu", gpu.id)
        val cpu = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(paths.detector, 2))
        gpu.detect(page)
        val mark = TimeSource.Monotonic.markNow()
        val found = gpu.detect(page)
        println("MANGA ios coreml detect=${mark.elapsedNow()} regions=${found.size}")
        val reference = cpu.detect(page)
        for (kind in listOf(RegionKind.BUBBLE, RegionKind.BUBBLE_TEXT)) {
            val a = reference.filter { it.kind == kind }; val b = found.filter { it.kind == kind }
            val matched = a.count { r -> b.any { it.box.iou(r.box) >= 0.5f } }
            println("MANGA ios $kind cpu=${a.size} gpu=${b.size} matched=$matched")
            assertTrue(matched * 4 >= a.size * 3, "$kind: $matched of ${a.size}")
        }
        // and the whole pipeline reads with it
        val chosen = listOf(AcceleratorSupport(MangaAccelerator.GPU, weight = 4))
        MangaPipeline(platform, paths, accelerators = chosen) { p -> NSData.dataWithContentsOfFile(p)!!.let { d -> ByteArray(d.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) } } }.decodeToString().split("\n") }.use { pipeline ->
            // where the GPU cannot run it, the pipeline falls back to the CPU's detector
            assertEquals(if (onSimulator) MangaDetector.ID else "coreml-r50", pipeline.detectorId)
            val analysis = pipeline.analyze(page)
            println("MANGA ios coreml pipeline regions=${analysis.regions.size} texts=${analysis.regions.map { it.text }.take(3)}")
            assertTrue(analysis.regions.isNotEmpty())
        }
    }

}
