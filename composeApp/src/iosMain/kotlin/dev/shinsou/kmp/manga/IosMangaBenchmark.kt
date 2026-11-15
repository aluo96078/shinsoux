package dev.shinsou.kmp.manga

import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreML.MLComputeUnitsAll
import platform.CoreML.MLComputeUnitsCPUAndGPU
import platform.CoreML.MLComputeUnitsCPUAndNeuralEngine
import platform.CoreML.MLComputeUnitsCPUOnly
import platform.CoreML.MLDictionaryFeatureProvider
import platform.CoreML.MLFeatureValue
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.CoreML.MLMultiArray
import platform.CoreML.MLMultiArrayDataTypeFloat32
import platform.CoreML.compileModelAtURL
import platform.CoreML.create
import platform.CoreML.objectAtIndexedSubscript
import platform.Foundation.NSURL
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.time.TimeSource

/**
 * Measures the page models on an iPhone (ADR-0005 §8), where tests do not run: started only when
 * the app is launched with SHINSOU_MANGA_BENCH_DIR (a directory of pages under Documents), with
 * the manga models already downloaded. Results go to the console, as `MANGABENCH` lines.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun startMangaBenchmarkIfAsked(modelDirectory: String, http: HttpClient) {
    val pagesDir = getenv("SHINSOU_MANGA_BENCH_DIR")?.toKString()?.takeIf { it.isNotBlank() } ?: return
    val documents = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: return
    fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.let { data ->
        ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }
    CoroutineScope(Dispatchers.Default).launch {
        delay(3_000) // let the app settle first
        val log = { line: String -> println("MANGABENCH $line") }
        getenv("SHINSOU_MANGA_COREML")?.toKString()?.takeIf { it.isNotBlank() }?.let { pkg ->
            runCatching { coreMlDetector("$documents/$pkg", "$documents/$pagesDir", log) }.onFailure { log("coreml failed $it") }
            log("done"); return@launch
        }
        runCatching {
            val platform = IosMangaPlatform()
            val dir = "$documents/$pagesDir"
            val files = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) ?: emptyList<Any>()).map { it as String }
                .filter { it.endsWith(".jpg") }.sorted().mapNotNull { read("$dir/$it") }
            val models = MangaModelStore("$modelDirectory/manga", http)
            val paths = models.paths() ?: error("manga models not downloaded")
            val vocab = { p: String -> read(p)!!.decodeToString().split("\n") }
            val after = MangaTuning.of(platform.profile())
            log("profile=${platform.profile()} tuning=$after pages=${files.size}")
            var regions = 0
            val translator = object : MangaTextTranslator {
                override suspend fun providerId() = "fake"
                override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) =
                    request.text.split("\n").let { lines ->
                        regions = lines.count { !request.keep(it) && it.isNotBlank() }
                        dev.shinsou.kmp.translation.ChapterTranslation(
                            lines.map { dev.shinsou.kmp.translation.TranslatedSegment(it, if (request.keep(it) || it.isBlank()) null else "譯文測試", kept = request.keep(it) || it.isBlank()) },
                            0, lines.size,
                        )
                    }
            }
            val variants = listOf(
                "before" to MangaTuning(3, 1, 3, 3, 1),
                "after" to after,
                "after-cpu-detector" to after,
            ) + (getenv("SHINSOU_MANGA_BENCH_SPLITS")?.toKString().orEmpty()).split(",").filter { it.isNotBlank() }.map { split ->
                val (d, w, t) = (split.split("x").map(String::toInt) + 2).take(3)
                "det$d+ocr${w}x$t" to after.copy(detectorThreads = d, ocrWorkers = w, ocrThreads = t)
            }
            for ((name, tuning) in variants) {
                // the same, with detection on the CPU (as without the Core ML pack)
                val cpuOnly = object : MangaPlatform by platform { override fun acceleratedDetector(paths: MangaModelPaths, accelerator: MangaAccelerator): PageDetector? = null }
                val manga = MangaTranslation(
                    if (name.endsWith("cpu-detector")) cpuOnly else platform, models, MangaPageStore(app.cash.sqldelight.driver.native.inMemoryDriver(NoSchema), { 0L }),
                    MangaRenderCache("$documents/bench-renders-$name"), translator, vocab, tuning = tuning,
                )
                manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
                val mark = TimeSource.Monotonic.markNow(); var drawStart = 0L
                manga.translateChapter(
                    MangaChapterRequest("w", "c", files.size, { files[it] }, "ja", "zh-Hant"),
                    onProgress = { if (it is MangaProgress.Drawing && drawStart == 0L) drawStart = mark.elapsedNow().inWholeMilliseconds },
                ) { _, _ -> }
                val total = mark.elapsedNow().inWholeMilliseconds
                log("chapter $name ${total}ms for ${files.size} pages: read ${drawStart}ms, draw ${total - drawStart}ms detector=${manga.lastRun?.detector} regions=$regions")
                manga.release()
                NSFileManager.defaultManager.removeItemAtPath("$documents/bench-renders-$name", null)
            }
            // each model on the CPU and on CoreML (SHINSOU_MANGA_BENCH_ACCEL=1; LaMa on CoreML may end the app)
            if (getenv("SHINSOU_MANGA_BENCH_ACCEL")?.toKString() != "1") { log("done"); return@runCatching }
            val images = files.mapNotNull { platform.codec.decode(it) }
            val words = vocab(paths.vocab)
            for (accelerator in listOf(Accelerator.CPU, Accelerator.COREML)) {
                var mark = TimeSource.Monotonic.markNow()
                val detector = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(paths.detector, 2, accelerator = accelerator))
                val load = mark.elapsedNow().inWholeMilliseconds
                detector.detect(images.first())
                mark = TimeSource.Monotonic.markNow(); val found = images.map { detector.detect(it) }
                log("detector $accelerator load=${load}ms ${mark.elapsedNow().inWholeMilliseconds / images.size}ms/page regions=${found.sumOf { it.size }}")
                val crops = images.zip(found).flatMap { (image, f) ->
                    f.filter { it.kind != RegionKind.BUBBLE }.map { image.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) }.chunked(MangaOcr.BATCH)
                }
                mark = TimeSource.Monotonic.markNow()
                val ocr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(paths.encoder, 2, accelerator = accelerator), platform.onnx.open(paths.decoder, 2, accelerator = accelerator), words)
                val loadOcr = mark.elapsedNow().inWholeMilliseconds
                ocr.read(crops.first())
                mark = TimeSource.Monotonic.markNow(); val text = crops.flatMap { ocr.read(it) }
                log("ocr $accelerator load=${loadOcr}ms ${mark.elapsedNow().inWholeMilliseconds / images.size}ms/page text=${text.take(3)}")
                paths.inpaint?.let { lama ->
                    mark = TimeSource.Monotonic.markNow()
                    val inpainter = MangaInpainter(platform.onnx, platform.onnx.open(lama, 6, variableShapes = true, accelerator = accelerator))
                    val loadLama = mark.elapsedNow().inWholeMilliseconds
                    val mask = ByteArray(256 * 384) { if ((it / 256) % 8 < 3) 1 else 0 }
                    inpainter.inpaint(images[3].copy(), 400, 600, 656, 984, mask)
                    mark = TimeSource.Monotonic.markNow(); repeat(3) { inpainter.inpaint(images[3].copy(), 400, 600, 656, 984, mask) }
                    log("lama $accelerator load=${loadLama}ms ${mark.elapsedNow().inWholeMilliseconds / 3}ms/region")
                }
            }
            log("done")
        }.onFailure { log("failed $it") }
    }
}

private object NoSchema : app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>> {
    override val version: Long = 1
    override fun create(driver: app.cash.sqldelight.db.SqlDriver) = app.cash.sqldelight.db.QueryResult.Value(Unit)
    override fun migrate(driver: app.cash.sqldelight.db.SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: app.cash.sqldelight.db.AfterVersion) =
        app.cash.sqldelight.db.QueryResult.Value(Unit)
}

/**
 * A detector converted to Core ML (an mlpackage under Documents), on each compute unit: its time
 * per page, after the first prediction, and how many regions it finds above the threshold.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun coreMlDetector(pkg: String, pagesDir: String, log: (String) -> Unit) {
    val manga = IosMangaPlatform()
    val files = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(pagesDir, null) ?: emptyList<Any>()).map { it as String }.filter { it.endsWith(".jpg") }.sorted()
    val pages = files.mapNotNull { name ->
        NSData.dataWithContentsOfFile("$pagesDir/$name")?.let { data -> ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } } }
    }.mapNotNull { manga.codec.decode(it) }.map { manga.ops.resize(it, 640, 640) }
    var mark = TimeSource.Monotonic.markNow()
    val compiled = MLModel.compileModelAtURL(NSURL.fileURLWithPath(pkg), null) ?: error("compile failed")
    log("coreml compiled in ${mark.elapsedNow().inWholeMilliseconds}ms pages=${pages.size}")
    val inputs = pages.map { page ->
        val array = MLMultiArray.create(shape = listOf(1, 3, 640, 640), dataType = MLMultiArrayDataTypeFloat32, error = null)!!
        val data = array.dataPointer!!.reinterpret<kotlinx.cinterop.FloatVar>()
        val plane = 640 * 640
        for (i in 0 until plane) { data[i] = page.r(i) / 255f; data[plane + i] = page.g(i) / 255f; data[2 * plane + i] = page.b(i) / 255f }
        MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>("pixels" to MLFeatureValue.featureValueWithMultiArray(array)), error = null)!!
    }
    val units = listOf(
        "ALL" to MLComputeUnitsAll,
        "CPUAndGPU" to MLComputeUnitsCPUAndGPU,
        "CPUAndNeuralEngine" to MLComputeUnitsCPUAndNeuralEngine,
        "CPUOnly" to MLComputeUnitsCPUOnly,
    )
    for ((name, unit) in units) {
        val config = MLModelConfiguration().apply { computeUnits = unit }
        mark = TimeSource.Monotonic.markNow()
        val model = MLModel.modelWithContentsOfURL(compiled, config, null) ?: run { log("coreml $name: load failed"); continue }
        val load = mark.elapsedNow().inWholeMilliseconds
        model.predictionFromFeatures(inputs.first(), null)
        mark = TimeSource.Monotonic.markNow()
        var found = 0
        for (input in inputs) {
            val out = model.predictionFromFeatures(input, null) ?: continue
            val scores = out.featureValueForName("scores")!!.multiArrayValue!!
            val n = scores.count.toInt()
            for (i in 0 until n step 3) {
                val best = maxOf(scores.objectAtIndexedSubscript(i.toLong()).floatValue, scores.objectAtIndexedSubscript(i + 1L).floatValue, scores.objectAtIndexedSubscript(i + 2L).floatValue)
                if (best > 0.45f) found++
            }
        }
        val each = mark.elapsedNow().inWholeMilliseconds / inputs.size
        log("coreml $name load=${load}ms ${each}ms/page (with reading the scores) regions=$found")
    }
}
