package dev.shinsou.kmp.manga

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LaMa on a real device (ADR-0005 §2): the page pipeline draws pages with text over artwork with
 * and without the optional `inpaint-v1` pack and logs the time it takes. Opt-in, with the packs
 * and pages copied into the app's files beforehand (see tools/manga-models/README.md):
 * `-Pandroid.testInstrumentationRunnerArguments.shinsouMangaDevice=true`.
 *   files/manga-device/vision-v1, files/manga-device/inpaint-v1, files/manga-device/pages/
 * Pages drawn with LaMa are written to files/manga-device/out for a look.
 */
@RunWith(AndroidJUnit4::class)
class MangaLamaDeviceTest {
    @Test
    fun textOverArtworkIsRedrawnWithLama() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shinsouMangaDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "manga-device")
        assumeTrue(root.resolve("inpaint-v1/lama.onnx").isFile && root.resolve("vision-v1/detector.onnx").isFile)
        val platform = assertNotNull(AndroidMangaPlatform.create(context), "an arm64 device")
        val v = root.resolve("vision-v1")
        val plain = MangaModelPaths(
            detector = v.resolve("detector.onnx").path, encoder = v.resolve("encoder.onnx").path,
            decoder = v.resolve("decoder.onnx").path, vocab = v.resolve("vocab.txt").path, font = v.resolve("NotoSansTC-VF.ttf").path,
        )
        val withLama = plain.copy(inpaint = root.resolve("inpaint-v1/lama.onnx").path)
        val out = root.resolve("out").apply { mkdirs() }
        val pages = root.resolve("pages").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted()
        assumeTrue(pages.isNotEmpty())
        Log.i(TAG, "device=${android.os.Build.MODEL} profile=${platform.profile()} pages=${pages.size}")

        var free = 0; var redrawn = 0; var plainMs = 0L; var lamaMs = 0L
        val heapBefore = heapMb()
        var heapPeak = heapBefore
        MangaPipeline(platform, plain) { File(it).readLines() }.use { without ->
            MangaPipeline(platform, withLama) { File(it).readLines() }.use { with ->
                // the first page loads the models: drawn once more afterwards, so its time counts too
                val warm = platform.codec.decode(pages.first().readBytes())!!
                val warmAnalysis = without.analyze(warm)
                val warmText = warmAnalysis.regions.indices.associateWith { "譯文測試" }
                var t = System.nanoTime(); without.render(warm, warmAnalysis, warmText, MangaOrientation.VERTICAL)
                Log.i(TAG, "first render (loads the font) ${(System.nanoTime() - t) / 1_000_000} ms; native heap ${heapMb()} MB with one pipeline")
                t = System.nanoTime(); with.render(warm, warmAnalysis, warmText, MangaOrientation.VERTICAL)
                Log.i(TAG, "first render with LaMa (loads the model) ${(System.nanoTime() - t) / 1_000_000} ms; native heap ${heapMb()} MB")

                for (file in pages) {
                    val image = platform.codec.decode(file.readBytes())!!
                    val analysis = without.analyze(image)
                    val text = analysis.regions.indices.associateWith { "譯文測試" }
                    val freeHere = analysis.regions.filter { it.kind == RegionKind.FREE_TEXT }
                    t = System.nanoTime()
                    without.render(image, analysis, text, MangaOrientation.VERTICAL)
                    val p = (System.nanoTime() - t) / 1_000_000
                    t = System.nanoTime()
                    val b = with.render(image, analysis, text, MangaOrientation.VERTICAL)
                    val l = (System.nanoTime() - t) / 1_000_000
                    heapPeak = maxOf(heapPeak, heapMb())
                    plainMs += p; lamaMs += l
                    var redrawnHere = 0
                    for (r in freeHere) {
                        free++
                        var changed = 0; var total = 0
                        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
                            val i = (y * image.width + x) * 3; total++
                            if ((0 until 3).any { c -> abs((b.pixels[i + c].toInt() and 0xff) - (image.pixels[i + c].toInt() and 0xff)) > 40 }) changed++
                        }
                        if (changed > total / 20) { redrawn++; redrawnHere++ }
                    }
                    val largest = freeHere.maxOfOrNull { (it.x1 - it.x0 + 128) * (it.y1 - it.y0 + 128) / 1000 } ?: 0
                    Log.i(TAG, "${file.name}: regions=${analysis.regions.size} over-art=${freeHere.size} redrawn=$redrawnHere render=$p ms, with LaMa=$l ms; largest LaMa input ${largest}k px; native heap ${heapMb()} MB")
                    out.resolve(file.nameWithoutExtension + "-lama.jpg").writeBytes(platform.codec.encodeJpeg(b, 90))
                }
            }
        }
        Log.i(TAG, "over-art regions=$free redrawn=$redrawn; render total=$plainMs ms, with LaMa=$lamaMs ms (+${(lamaMs - plainMs) / maxOf(1, free)} ms a region)")
        Log.i(TAG, "native heap MB: before=$heapBefore peak=$heapPeak")
        assertTrue(free > 0, "the pages should have text over artwork")
        assertTrue(redrawn >= free * 2 / 3, "most text over artwork should be redrawn ($redrawn of $free)")
    }

    private fun heapMb() = Debug.getNativeHeapAllocatedSize() / 1_000_000

    private companion object {
        const val TAG = "MangaLamaDevice"
    }
}
