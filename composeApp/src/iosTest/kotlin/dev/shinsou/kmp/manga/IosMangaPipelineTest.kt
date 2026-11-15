package dev.shinsou.kmp.manga

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.create
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The page pipeline on iOS with the real models (ADR-0005 §4). Runs only when Gradle forwards
 * SHINSOU_MANGA_PACKS and SHINSOU_MANGA_PAGE (see the simulator test configuration); the
 * simulator reads them from the host's file system.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMangaPipelineTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    private fun read(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    @Test
    fun readsAndRedrawsAPage() {
        val packs = env("SHINSOU_MANGA_PACKS") ?: return
        val pagePath = env("SHINSOU_MANGA_PAGE") ?: return
        if (!NSFileManager.defaultManager.fileExistsAtPath("$packs/vision-v1/detector.onnx")) return
        val platform = IosMangaPlatform()
        val v = "$packs/vision-v1"
        val paths = MangaModelPaths("$v/detector.onnx", "$v/encoder.onnx", "$v/decoder.onnx", "$v/vocab.txt", "$v/NotoSansTC-VF.ttf")
        MangaPipeline(platform, paths) { read(it)!!.decodeToString().split("\n") }.use { pipeline ->
            val bytes = read(pagePath) ?: error("cannot read $pagePath")
            val image = platform.codec.decode(bytes) ?: error("cannot decode $pagePath")
            var mark = TimeSource.Monotonic.markNow()
            val analysis = pipeline.analyze(image)
            println("MANGA ios analyse=${mark.elapsedNow()} regions=${analysis.regions.size} bubbles=${analysis.bubbles.size}")
            analysis.regions.forEach { println("MANGA ios ${it.kind} ${it.text}") }
            env("SHINSOU_MANGA_EXPECTED")?.let { read(it) }?.let { expected ->
                val lines = expected.decodeToString().lines().filter { it.isNotBlank() }
                // platforms decode JPEG slightly differently and int8 OCR is sensitive to that:
                // the same regions, each line at least 85% the same characters
                assertEquals(lines.size, analysis.regions.size)
                lines.zip(analysis.regions.map { it.text }).forEach { (want, got) -> assertTrue(similarity(want, got) >= 0.85, "$want ≠ $got") }
            }
            val sample = "等一下，你到底在說什麼啊？我完全聽不懂！今天的天氣真好呢。快點過來這邊，大家都在等你了。"
            val translations = analysis.regions.indices.associateWith { i -> sample.substring((i * 7) % 20).take(maxOf(4, analysis.regions[i].text.length * 4 / 5)) }
            mark = TimeSource.Monotonic.markNow()
            val rendered = pipeline.render(image, analysis, translations, MangaOrientation.VERTICAL)
            println("MANGA ios render=${mark.elapsedNow()}")
            val jpeg = platform.codec.encodeJpeg(rendered, 90)
            assertTrue(jpeg.isNotEmpty())
            val out = "${pagePath.substringBeforeLast('.')}-ios-rendered.jpg"
            jpeg.usePinned { NSData.create(bytes = it.addressOf(0), length = jpeg.size.convert()).writeToFile(out, atomically = true) }
            println("MANGA ios wrote $out")
        }
    }

    /** Text over artwork redrawn by LaMa through the C API (SHINSOU_MANGA_ART: pages with such text). */
    @Test
    fun redrawsTextOverArtworkWithLama() {
        val packs = env("SHINSOU_MANGA_PACKS") ?: return
        val art = env("SHINSOU_MANGA_ART") ?: return
        if (!NSFileManager.defaultManager.fileExistsAtPath("$packs/inpaint-v1/lama.onnx")) return
        val platform = IosMangaPlatform()
        val v = "$packs/vision-v1"
        val paths = MangaModelPaths("$v/detector.onnx", "$v/encoder.onnx", "$v/decoder.onnx", "$v/vocab.txt", "$v/NotoSansTC-VF.ttf", inpaint = "$packs/inpaint-v1/lama.onnx")
        MangaPipeline(platform, paths) { read(it)!!.decodeToString().split("\n") }.use { pipeline ->
            val pagePath = "$art/p_025419.jpg"
            val image = platform.codec.decode(read(pagePath) ?: error("cannot read $pagePath")) ?: error("cannot decode $pagePath")
            val analysis = pipeline.analyze(image)
            val free = analysis.regions.filter { it.kind == RegionKind.FREE_TEXT }
            assertTrue(free.isNotEmpty(), "the page has text over artwork")
            val mark = TimeSource.Monotonic.markNow()
            val rendered = pipeline.render(image, analysis, analysis.regions.indices.associateWith { "譯文測試" }, MangaOrientation.VERTICAL)
            println("MANGA ios lama render=${mark.elapsedNow()} free=${free.size}")
            for (r in free) {
                var changed = 0; var total = 0
                for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
                    val i = (y * image.width + x) * 3; total++
                    if ((0 until 3).any { c -> kotlin.math.abs((rendered.pixels[i + c].toInt() and 0xff) - (image.pixels[i + c].toInt() and 0xff)) > 40 }) changed++
                }
                println("MANGA ios free ${r.text} redrawn=${changed * 100 / total}%")
                assertTrue(changed > total / 20, "${r.text} should be redrawn")
            }
            val jpeg = platform.codec.encodeJpeg(rendered, 90)
            val out = "${art.trimEnd('/')}-ios-lama.jpg" // beside the directory, not among its pages
            jpeg.usePinned { NSData.create(bytes = it.addressOf(0), length = jpeg.size.convert()).writeToFile(out, atomically = true) }
        }
    }
}
