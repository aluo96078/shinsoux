package dev.shinsou.kmp.manga

import io.ktor.client.engine.mock.respond
import java.io.File
import kotlin.test.Test

/**
 * Speed of the page models by thread count (ADR-0005), to tune them per device. Runs where
 * SHINSOU_MANGA_BENCH=1 with SHINSOU_MANGA_PACKS and SHINSOU_MANGA_ART (a directory of pages).
 */
class MangaBenchmarkDesktopTest {
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("vision-v1").isDirectory }
    private val pages = System.getenv("SHINSOU_MANGA_ART")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun threadsPerModel() {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || pages == null) return
        val platform = DesktopMangaPlatform.create()
        val v = packs.resolve("vision-v1")
        val images = pages.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { platform.codec.decode(it.readBytes())!! }
        val vocab = v.resolve("vocab.txt").readLines()
        // the regions to read, found once
        val detections = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 4)).let { d -> images.map { d.detect(it) } }
        val crops = images.zip(detections).map { (image, found) ->
            found.filter { it.kind != RegionKind.BUBBLE }.map { image.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) }
        }
        println("BENCH pages=${images.size} regions=${crops.sumOf { it.size }} cores=${Runtime.getRuntime().availableProcessors()}")
        for (threads in listOf(1, 2, 4, 6, 8, 12)) {
            val detector = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, threads))
            val ocr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(v.resolve("encoder.onnx").path, threads), platform.onnx.open(v.resolve("decoder.onnx").path, threads), vocab)
            images.forEach { detector.detect(it) }; ocr.read(crops.first().take(MangaOcr.BATCH)) // warm
            val d = time { images.forEach { detector.detect(it) } }
            val o = time { crops.forEach { page -> page.chunked(MangaOcr.BATCH).forEach { ocr.read(it) } } }
            println("BENCH threads=$threads detect=${d / images.size}ms/page ocr=${o / images.size}ms/page")
        }
    }

    @Test
    fun parallelOcrWorkers() {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || pages == null) return
        val platform = DesktopMangaPlatform.create()
        val v = packs.resolve("vision-v1")
        val images = pages.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { platform.codec.decode(it.readBytes())!! }
        val vocab = v.resolve("vocab.txt").readLines()
        val detections = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 4)).let { d -> images.map { d.detect(it) } }
        val batches = images.zip(detections).flatMap { (image, found) ->
            found.filter { it.kind != RegionKind.BUBBLE }.map { image.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) }.chunked(MangaOcr.BATCH)
        }
        val expected = run {
            val ocr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(v.resolve("encoder.onnx").path, 4), platform.onnx.open(v.resolve("decoder.onnx").path, 4), vocab)
            batches.map { ocr.read(it) }
        }
        for ((workers, threads) in listOf(1 to 4, 2 to 2, 2 to 3, 2 to 4, 3 to 2, 4 to 2)) {
            val ocrs = List(workers) { MangaOcr(platform.onnx, platform.ops, platform.onnx.open(v.resolve("encoder.onnx").path, threads), platform.onnx.open(v.resolve("decoder.onnx").path, threads), vocab) }
            ocrs.forEach { it.read(batches.first()) } // warm
            val results = arrayOfNulls<List<String>>(batches.size)
            val next = java.util.concurrent.atomic.AtomicInteger()
            val ms = time {
                ocrs.map { ocr -> Thread { while (true) { val i = next.getAndIncrement(); if (i >= batches.size) break; results[i] = ocr.read(batches[i]) } }.also { it.start() } }.forEach { it.join() }
            }
            println("BENCH ocr workers=$workers threads=$threads ${ms / images.size}ms/page same=${results.toList() == expected}")
        }
    }

    /** A whole chapter in each CPU mode, the CPU's detector alone. */
    @Test
    fun chapterInEachCpuMode() = kotlinx.coroutines.runBlocking {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || pages == null) return@runBlocking
        val platform = DesktopMangaPlatform.create()
        val files = pages.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { it.readBytes() }
        val root = java.nio.file.Files.createTempDirectory("manga-modes").toFile()
        packs.resolve("vision-v1").copyRecursively(root.resolve("vision-v1"))
        val translator = object : MangaTextTranslator {
            override suspend fun providerId() = "fake"
            override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) =
                request.text.split("\n").let { lines -> dev.shinsou.kmp.translation.ChapterTranslation(lines.map { dev.shinsou.kmp.translation.TranslatedSegment(it, if (it.isBlank()) null else "譯文", kept = it.isBlank()) }, 0, lines.size) }
        }
        for (round in 1..2) for (mode in MangaCpuMode.entries) {
            val tuning = MangaTuning.of(platform.profile(), mode)
            val manga = MangaTranslation(
                platform, MangaModelStore(root.path, io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("") })),
                MangaPageStore(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
                MangaRenderCache(root.resolve("pages-$round-$mode").path), translator, { File(it).readLines() }, tuning = tuning,
            )
            manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
            val ms = time { manga.translateChapter(MangaChapterRequest("w", "c", files.size, { files[it] }, "ja", "zh-Hant")) { _, _ -> } }
            println("BENCH mode round=$round $mode ${ms}ms for ${files.size} pages $tuning ${manga.lastRun}")
            manga.release()
        }
        root.deleteRecursively()
    }

    /** Each model on each accelerator, against the CPU's results (SHINSOU_MANGA_FP32: the fp32 originals). */
    @Test
    fun accelerators() {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || pages == null) return
        val platform = DesktopMangaPlatform.create()
        val v = packs.resolve("vision-v1")
        val fp32 = System.getenv("SHINSOU_MANGA_FP32")?.let(::File)
        val images = pages.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { platform.codec.decode(it.readBytes())!! }
        val vocab = v.resolve("vocab.txt").readLines()
        val cpuDetector = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 4))
        val detections = images.map { cpuDetector.detect(it) }
        val batches = images.zip(detections).flatMap { (image, found) ->
            found.filter { it.kind != RegionKind.BUBBLE }.map { image.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) }.chunked(MangaOcr.BATCH)
        }
        val cpuOcr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(v.resolve("encoder.onnx").path, 4), platform.onnx.open(v.resolve("decoder.onnx").path, 4), vocab)
        val expectedText = batches.flatMap { cpuOcr.read(it) }
        for (accelerator in Accelerator.entries.filter { it != Accelerator.NNAPI }) {
            var t = System.nanoTime()
            val detector = MangaDetector(platform.onnx, platform.ops, platform.onnx.open(v.resolve("detector.onnx").path, 4, accelerator = accelerator))
            val load = (System.nanoTime() - t) / 1_000_000
            images.first().let { detector.detect(it) }
            var found: List<List<Detection>> = emptyList()
            val d = time { found = images.map { detector.detect(it) } }
            println("BENCH detector int8 $accelerator load=${load}ms ${d / images.size}ms/page sameBoxes=${found.map { it.size } == detections.map { it.size }}")
            val variants = listOf("int8" to (v.resolve("encoder.onnx") to v.resolve("decoder.onnx"))) +
                listOfNotNull(fp32?.let { "fp32" to (it.resolve("past_encoder_model.onnx") to it.resolve("past_decoder_model_merged.onnx")) })
            for ((name, files) in variants) {
                t = System.nanoTime()
                val ocr = MangaOcr(platform.onnx, platform.ops, platform.onnx.open(files.first.path, 4, accelerator = accelerator), platform.onnx.open(files.second.path, 4, accelerator = accelerator), vocab)
                val loadOcr = (System.nanoTime() - t) / 1_000_000
                ocr.read(batches.first())
                var text: List<String> = emptyList()
                val o = time { text = batches.flatMap { ocr.read(it) } }
                val agree = text.zip(expectedText).count { (a, b) -> similarity(a, b) >= 0.85 }
                println("BENCH ocr $name $accelerator load=${loadOcr}ms ${o / images.size}ms/page agree=$agree/${expectedText.size}")
            }
        }
    }

    /** A chapter end to end, its pages slow to load (a network source), before and after tuning. */
    @Test
    fun chapterBeforeAndAfterTuning() = kotlinx.coroutines.runBlocking {
        if (System.getenv("SHINSOU_MANGA_BENCH") != "1" || packs == null || pages == null) return@runBlocking
        val platform = DesktopMangaPlatform.create()
        val files = pages.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().map { it.readBytes() }
        val translator = object : MangaTextTranslator {
            override suspend fun providerId() = "fake"
            override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) =
                request.text.split("\n").let { lines ->
                    dev.shinsou.kmp.translation.ChapterTranslation(lines.map { dev.shinsou.kmp.translation.TranslatedSegment(it, if (request.keep(it) || it.isBlank()) null else "譯文", kept = request.keep(it) || it.isBlank()) }, 0, lines.size)
                }
        }
        val before = MangaTuning(detectorThreads = 4, ocrWorkers = 1, ocrThreads = 4, inpaintThreads = 4, pageLoadsAhead = 1)
        val after = MangaTuning.of(platform.profile())
        println("BENCH profile=${platform.profile()} tuning=$after")
        val variants = listOf("before" to before, "after" to after) + (System.getenv("SHINSOU_MANGA_BENCH_SPLITS") ?: "").split(",").filter { it.isNotBlank() }.map { split ->
            val (d, w) = split.split("x").map(String::toInt)
            "det$d+ocr${w}x2" to after.copy(detectorThreads = d, ocrWorkers = w)
        }
        val delays = (System.getenv("SHINSOU_MANGA_BENCH_DELAYS") ?: "0,300").split(",").map(String::toLong)
        for (delay in delays) for ((name, tuning) in variants) {
            val root = java.nio.file.Files.createTempDirectory("bench").toFile()
            packs.resolve("vision-v1").copyRecursively(root.resolve("models/vision-v1"))
            val manga = MangaTranslation(
                platform, MangaModelStore(root.resolve("models").path, io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("") })),
                MangaPageStore(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
                MangaRenderCache(root.resolve("pages").path), translator, { File(it).readLines() }, tuning = tuning,
            )
            // warm the models on another chapter first
            manga.translateChapter(MangaChapterRequest("w", "warm", 1, { files.first() }, "ja", "zh-Hant")) { _, _ -> }
            val t = System.nanoTime()
            var readDone = 0L; var drawStart = 0L
            manga.translateChapter(
                MangaChapterRequest("w", "c", files.size, { i -> kotlinx.coroutines.delay(delay); files[i].copyOf().also { it[it.size - 1] = it[it.size - 1] } }, "ja", "zh-Hant", orientation = MangaOrientation.VERTICAL),
                onProgress = { p ->
                    val now = (System.nanoTime() - t) / 1_000_000
                    if (p is MangaProgress.Translating && readDone == 0L) readDone = now
                    if (p is MangaProgress.Drawing && drawStart == 0L) drawStart = now
                },
            ) { _, _ -> }
            val total = (System.nanoTime() - t) / 1_000_000
            println("BENCH chapter delay=${delay}ms $name ${total}ms for ${files.size} pages: read ${readDone}ms, translate ${drawStart - readDone}ms, draw ${total - drawStart}ms")
            manga.release()
        }
    }

    private inline fun time(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }
}
