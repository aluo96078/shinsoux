package dev.shinsou.kmp.manga

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The page models on a real device (ADR-0005 §8): the device's tuning against the fixed one used
 * before, and each model on each accelerator against the CPU. Opt-in, with the packs and pages
 * copied into the app's files (see tools/manga-models/README.md):
 * `-e shinsouMangaBench true`; files/manga-device/{vision-v1,inpaint-v1,fp32,pages}.
 */
@RunWith(AndroidJUnit4::class)
class MangaBenchmarkDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "manga-device")

    private fun assumeBench() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shinsouMangaBench") == "true")
        assumeTrue(root.resolve("vision-v1/detector.onnx").isFile && root.resolve("pages").isDirectory)
    }

    private val translator = object : MangaTextTranslator {
        override suspend fun providerId() = "fake"
        override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) =
            request.text.split("\n").let { lines ->
                dev.shinsou.kmp.translation.ChapterTranslation(
                    lines.map { dev.shinsou.kmp.translation.TranslatedSegment(it, if (request.keep(it) || it.isBlank()) null else "譯文測試", kept = request.keep(it) || it.isBlank()) },
                    0, lines.size,
                )
            }
    }

    @Test
    fun chapterBeforeAndAfterTuning() = runBlocking {
        assumeBench()
        val platform = requireNotNull(AndroidMangaPlatform.create(context))
        val files = root.resolve("pages").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { it.readBytes() }
        val before = MangaTuning(detectorThreads = 3, ocrWorkers = 1, ocrThreads = 3, inpaintThreads = 3, pageLoadsAhead = 1)
        val after = MangaTuning.of(platform.profile())
        Log.i(TAG, "profile=${platform.profile()} tuning=$after")
        val args = InstrumentationRegistry.getArguments()
        val splits = args.getString("splits").orEmpty().split(",").filter { it.isNotBlank() }.map { split ->
            val (d, w) = split.split("x").map(String::toInt)
            "det$d+ocr${w}x2" to after.copy(detectorThreads = d, ocrWorkers = w)
        }
        val delays = (args.getString("delays") ?: "0,300").split(",").map(String::toLong)
        for (delayMs in delays) for ((name, tuning) in listOf("before" to before, "after" to after) + splits) {
            val dir = File(context.cacheDir, "bench-$name-$delayMs").apply { deleteRecursively(); mkdirs() }
            val manga = MangaTranslation(
                platform, MangaModelStore(root.path, HttpClient(MockEngine { respond("") })),
                MangaPageStore(AndroidSqliteDriver(NoSchema, context, name = null), { System.currentTimeMillis() }),
                MangaRenderCache(dir.path), translator, { File(it).readLines() }, tuning = tuning,
            )
            manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
            val t = System.nanoTime(); var drawStart = 0L
            manga.translateChapter(
                MangaChapterRequest("w", "c", files.size, { i -> delay(delayMs); files[i] }, "ja", "zh-Hant"),
                onProgress = { if (it is MangaProgress.Drawing && drawStart == 0L) drawStart = (System.nanoTime() - t) / 1_000_000 },
            ) { _, _ -> }
            val total = (System.nanoTime() - t) / 1_000_000
            Log.i(TAG, "chapter delay=${delayMs}ms $name ${total}ms for ${files.size} pages: read ${drawStart}ms, draw ${total - drawStart}ms")
            manga.release(); dir.deleteRecursively()
        }
    }

    /** A whole chapter in each CPU mode, the CPU's detector alone. */
    @Test
    fun chapterInEachCpuMode() = runBlocking {
        assumeBench()
        val platform = requireNotNull(AndroidMangaPlatform.create(context))
        val files = root.resolve("pages").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { it.readBytes() }
        val store = MangaModelStore(root.path, HttpClient(MockEngine { respond("") }))
        store.selectAccelerators(setOf(MangaAccelerator.CPU))
        for (round in 1..2) for (mode in MangaCpuMode.entries) {
            val tuning = MangaTuning.of(platform.profile(), mode)
            val dir = File(context.cacheDir, "bench-mode").apply { deleteRecursively(); mkdirs() }
            val manga = MangaTranslation(
                platform, store, MangaPageStore(AndroidSqliteDriver(NoSchema, context, name = null), { System.currentTimeMillis() }),
                MangaRenderCache(dir.path), translator, { File(it).readLines() }, tuning = tuning,
            )
            manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
            val t = System.nanoTime()
            manga.translateChapter(MangaChapterRequest("w", "c", files.size, { i -> files[i] }, "ja", "zh-Hant")) { _, _ -> }
            Log.i(TAG, "mode round=$round $mode ${(System.nanoTime() - t) / 1_000_000}ms for ${files.size} pages $tuning ${manga.lastRun}")
            manga.release(); dir.deleteRecursively()
        }
    }

    /**
     * A whole chapter on each choice of detectors (docs/research/manga-gpu-npu.md). Needs, besides
     * the packs, vision-qnn-v1 and the QNN runtime in runtime/qnn-runtime-<version>-v<arch>.
     */
    @Test
    fun chapterOnEachDetectorChoice() = runBlocking {
        assumeBench()
        val platform = requireNotNull(AndroidMangaPlatform.create(context))
        val npu = platform.accelerators().first { it.accelerator == MangaAccelerator.NPU }
        assumeTrue(npu.supported && root.resolve("vision-qnn-v1/detector.onnx").isFile)
        val files = root.resolve("pages").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { it.readBytes() }
        val store = MangaModelStore(root.path, HttpClient(MockEngine { respond("") }))
        assumeTrue(store.runtimeInstalled(npu.runtime!!) != null)
        val choices = listOf(setOf(MangaAccelerator.CPU), setOf(MangaAccelerator.NPU), setOf(MangaAccelerator.CPU, MangaAccelerator.NPU))
        for (round in 1..2) for (choice in choices) {
            store.selectAccelerators(choice)
            val dir = File(context.cacheDir, "bench-choice").apply { deleteRecursively(); mkdirs() }
            val manga = MangaTranslation(
                platform, store, MangaPageStore(AndroidSqliteDriver(NoSchema, context, name = null), { System.currentTimeMillis() }),
                MangaRenderCache(dir.path), translator, { File(it).readLines() },
            )
            var t = System.nanoTime()
            manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
            val warm = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            manga.translateChapter(MangaChapterRequest("w", "c", files.size, { i -> files[i] }, "ja", "zh-Hant")) { _, _ -> }
            val total = (System.nanoTime() - t) / 1_000_000
            Log.i(TAG, "choice round=$round ${choice.joinToString("+")} first page ${warm}ms, chapter ${total}ms for ${files.size} pages: ${manga.lastRun}")
            manga.release(); dir.deleteRecursively()
        }
    }

    @Test
    fun accelerators() {
        assumeBench()
        val platform = requireNotNull(AndroidMangaPlatform.create(context))
        val v = root.resolve("vision-v1")
        val images = root.resolve("pages").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { platform.codec.decode(it.readBytes())!! }
        val vocab = v.resolve("vocab.txt").readLines()
        val cpu = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 3))
        val detections = images.map { cpu.detect(it) }
        val batches = images.zip(detections).flatMap { (image, found) ->
            found.filter { it.kind != RegionKind.BUBBLE }.map { image.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) }.chunked(MangaOcr.BATCH)
        }
        val expected = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(v.resolve("encoder.onnx").path, 2), platform.onnx.open(v.resolve("decoder.onnx").path, 2), vocab).let { o -> batches.flatMap { o.read(it) } }
        val fp32 = root.resolve("fp32")
        for (accelerator in listOf(Accelerator.CPU, Accelerator.NNAPI, Accelerator.XNNPACK)) {
            var t = System.nanoTime()
            val detector = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 3, accelerator = accelerator))
            val load = (System.nanoTime() - t) / 1_000_000
            detector.detect(images.first())
            t = System.nanoTime(); val found = images.map { detector.detect(it) }
            Log.i(TAG, "detector int8 $accelerator load=${load}ms ${(System.nanoTime() - t) / 1_000_000 / images.size}ms/page sameBoxes=${found.map { it.size } == detections.map { it.size }}")
            val variants = listOf("int8" to (v.resolve("encoder.onnx") to v.resolve("decoder.onnx"))) +
                listOfNotNull(fp32.resolve("past_encoder_model.onnx").takeIf { it.isFile }?.let { "fp32" to (it to fp32.resolve("past_decoder_model_merged.onnx")) })
            for ((name, files) in variants) {
                t = System.nanoTime()
                val ocr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(files.first.path, 2, accelerator = accelerator), platform.onnx.open(files.second.path, 2, accelerator = accelerator), vocab)
                val loadOcr = (System.nanoTime() - t) / 1_000_000
                ocr.read(batches.first())
                t = System.nanoTime(); val text = batches.flatMap { ocr.read(it) }
                val agree = text.zip(expected).count { (a, b) -> a == b }
                Log.i(TAG, "ocr $name $accelerator load=${loadOcr}ms ${(System.nanoTime() - t) / 1_000_000 / images.size}ms/page sameText=$agree/${expected.size}")
            }
            // LaMa on one region of text over artwork (a 256 by 384 box with its context)
            for (lama in listOfNotNull(root.resolve("inpaint-v1/lama.onnx").takeIf { it.isFile }, fp32.resolve("lama.onnx").takeIf { it.isFile })) {
                t = System.nanoTime()
                val inpainter = MangaInpainter(platform.onnx, platform.onnx.open(lama.path, 6, variableShapes = true, accelerator = accelerator))
                val loadLama = (System.nanoTime() - t) / 1_000_000
                val page = images[3]; val mask = ByteArray(256 * 384) { if ((it / 256) % 8 < 3) 1 else 0 }
                inpainter.inpaint(page.copy(), 400, 600, 656, 984, mask)
                t = System.nanoTime(); repeat(3) { inpainter.inpaint(page.copy(), 400, 600, 656, 984, mask) }
                Log.i(TAG, "lama ${lama.parentFile!!.name} $accelerator load=${loadLama}ms ${(System.nanoTime() - t) / 1_000_000 / 3}ms/region")
            }
        }
    }

    private object NoSchema : app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: app.cash.sqldelight.db.SqlDriver) = app.cash.sqldelight.db.QueryResult.Value(Unit)
        override fun migrate(driver: app.cash.sqldelight.db.SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: app.cash.sqldelight.db.AfterVersion) =
            app.cash.sqldelight.db.QueryResult.Value(Unit)
    }

    private companion object {
        const val TAG = "MangaBench"
    }
}
