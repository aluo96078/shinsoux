package dev.shinsou.kmp.manga

import io.ktor.client.engine.mock.respond
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Text over artwork redrawn by LaMa (the optional `inpaint-v1` pack, ADR-0005 §2), with the real
 * models. Runs when the packs and a directory of pages with text over artwork are on this machine:
 *   SHINSOU_MANGA_PACKS=/path/to/packs (with inpaint-v1), SHINSOU_MANGA_ART=/path/to/pages
 * Each page is drawn without and with LaMa into build/manga-test/art for a look.
 */
class MangaInpaintDesktopTest {
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("vision-v1").isDirectory && it.resolve("inpaint-v1").isDirectory }
    private val art = System.getenv("SHINSOU_MANGA_ART")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun textOverArtworkIsTranslatedOnlyWithLama() {
        if (packs == null || art == null) return
        val platform = DesktopMangaPlatform.create()
        val v = packs.resolve("vision-v1")
        val plain = MangaModelPaths(
            detector = v.resolve("detector.onnx").path, encoder = v.resolve("encoder.onnx").path,
            decoder = v.resolve("decoder.onnx").path, vocab = v.resolve("vocab.txt").path, font = v.resolve("NotoSansTC-VF.ttf").path,
        )
        val withLama = plain.copy(inpaint = packs.resolve("inpaint-v1/lama.onnx").path)
        val out = File("build/manga-test/art").apply { mkdirs() }
        var freeRegions = 0; var redrawn = 0; var lamaMs = 0L
        MangaPipeline(platform, plain) { File(it).readLines() }.use { without ->
            MangaPipeline(platform, withLama) { File(it).readLines() }.use { with ->
                assertTrue(with.inpaints && !without.inpaints)
                for (file in art.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted()) {
                    val image = platform.codec.decode(file.readBytes()) ?: error("cannot decode $file")
                    val analysis = without.analyze(image)
                    val translations = analysis.regions.indices.associateWith { "譯文測試" }
                    val a = without.render(image, analysis, translations, MangaOrientation.VERTICAL)
                    val t = System.nanoTime()
                    val b = with.render(image, analysis, translations, MangaOrientation.VERTICAL)
                    lamaMs += (System.nanoTime() - t) / 1_000_000
                    for (r in analysis.regions.filter { it.kind == RegionKind.FREE_TEXT }) {
                        freeRegions++
                        // inside the box, without LaMa the page is untouched; with it the text is redrawn
                        val box = r.box
                        var same = 0; var changed = 0; var total = 0
                        for (y in box.y0.toInt() until box.y1.toInt()) for (x in box.x0.toInt() until box.x1.toInt()) {
                            val i = (y * image.width + x) * 3
                            total++
                            if ((0 until 3).all { a.pixels[i + it] == image.pixels[i + it] }) same++
                            if ((0 until 3).any { c -> kotlin.math.abs((b.pixels[i + c].toInt() and 0xff) - (image.pixels[i + c].toInt() and 0xff)) > 40 }) changed++
                        }
                        println("MANGA ${file.name} free ${r.text} untouched=${"%.2f".format(same.toDouble() / total)} redrawn=${"%.2f".format(changed.toDouble() / total)}")
                        if (changed > total / 20) redrawn++
                    }
                    out.resolve(file.nameWithoutExtension + "-plain.jpg").writeBytes(platform.codec.encodeJpeg(a, 90))
                    out.resolve(file.nameWithoutExtension + "-lama.jpg").writeBytes(platform.codec.encodeJpeg(b, 90))
                }
            }
        }
        println("MANGA free=$freeRegions redrawn=$redrawn render-with-lama=${lamaMs}ms")
        assertTrue(freeRegions > 0, "the pages should have text over artwork")
        assertTrue(redrawn >= freeRegions * 2 / 3, "most text over artwork should be redrawn ($redrawn of $freeRegions)")
    }

    @Test
    fun aChapterIsDrawnAgainWithLamaOnceItsPackIsDownloaded() = kotlinx.coroutines.runBlocking {
        if (packs == null || art == null) return@runBlocking
        val page = art.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().first { it.name == "p_025419.jpg" }.readBytes()
        val root = java.nio.file.Files.createTempDirectory("manga-lama").toFile()
        packs.resolve("vision-v1").copyRecursively(root.resolve("models/vision-v1"))
        val lama = packs.resolve("inpaint-v1/lama.onnx").readBytes()
        val served = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            if (request.url.encodedPath.endsWith("inpaint-v1-lama.onnx")) respond(lama)
            else respond("", io.ktor.http.HttpStatusCode.NotFound)
        })
        val translator = object : MangaTextTranslator {
            override suspend fun providerId() = "fake"
            override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) =
                request.text.split("\n").let { lines ->
                    dev.shinsou.kmp.translation.ChapterTranslation(
                        lines.map { if (request.keep(it) || it.isBlank()) dev.shinsou.kmp.translation.TranslatedSegment(it, null, kept = true) else dev.shinsou.kmp.translation.TranslatedSegment(it, "譯文測試") },
                        0, lines.size,
                    )
                }
        }
        val renders = root.resolve("pages")
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(), MangaModelStore(root.resolve("models").path, served),
            MangaPageStore(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(renders.path), translator, { File(it).readLines() },
        )
        val request = MangaChapterRequest("work", "chapter", 1, { page }, "ja", "zh-Hant")
        var before: ByteArray? = null
        manga.translateChapter(request) { _, jpeg -> before = jpeg }
        kotlin.test.assertFalse(manga.inpaintInstalled())
        kotlin.test.assertEquals(MangaModelCatalog.INPAINT.downloadBytes, manga.missingInpaintBytes())

        manga.downloadInpaint { _, _ -> }
        assertTrue(manga.inpaintInstalled())
        var after: ByteArray? = null
        manga.translateChapter(request) { _, jpeg -> after = jpeg }
        // a page of its own in the cache, next to the one drawn without LaMa
        kotlin.test.assertEquals(2, renders.listFiles()!!.count { it.name.endsWith(".jpg") })
        assertTrue(!before!!.contentEquals(after!!))

        manga.removeInpaint()
        kotlin.test.assertFalse(manga.inpaintInstalled())
        var again: ByteArray? = null
        manga.translateChapter(request) { _, jpeg -> again = jpeg }
        assertTrue(before!!.contentEquals(again!!), "without the pack the page drawn before is used again")
        manga.release()
    }
}
