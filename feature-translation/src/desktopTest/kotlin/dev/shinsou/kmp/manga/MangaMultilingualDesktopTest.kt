package dev.shinsou.kmp.manga

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/**
 * PaddleOCR in Kotlin against the P0 measurements (docs/research/manga-multilingual.md): reads the
 * regions P0 found on its sample pages and writes `<run>-kt.json` beside P0's, for its cer.py.
 * Runs only with the P0 directory and the packs built by build_packs.py --with-ocr:
 *   SHINSOU_P0_DIR=/tmp/p0 SHINSOU_MANGA_PACKS=/tmp/manga-packs-p1
 */
class MangaMultilingualDesktopTest {
    private val p0 = System.getenv("SHINSOU_P0_DIR")?.let(::File)?.takeIf { it.resolve("out").isDirectory }
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("ocr-lines-v1").isDirectory }

    @Test
    fun readsP0RegionsLikeP0() {
        if (p0 == null || packs == null) return
        val platform = DesktopMangaPlatform.create()
        val lines = packs.resolve("ocr-lines-v1/lines.onnx").path
        // run, pages, recogniser
        val runs = listOf(
            Triple("zh-manhua", "zh-manhua", "zh"), Triple("zh-webtoon-tiled", "zh-webtoon", "zh"),
            Triple("en-manga-enrec", "en-manga", "en"), Triple("en-comic-enrec", "en-comic", "en"),
        )
        for ((run, set, key) in runs) {
            val source = p0.resolve("out/$run.json").takeIf { it.isFile } ?: continue
            val pack = packs.resolve("ocr-$key-v1")
            val ocr = PaddleOcr(
                platform.onnx, platform.ops, platform.onnx.open(lines, 4, variableShapes = true),
                platform.onnx.open(pack.resolve("rec.onnx").path, 4, variableShapes = true),
                pack.resolve("chars.txt").readText().split("\n"), if (key == "en") OcrScript.LATIN else OcrScript.CHINESE,
            )
            var ms = 0L; var regions = 0
            val pages = Json.parseToJsonElement(source.readText()).jsonArray.map { it.jsonObject }
            val out = buildJsonArray {
                for (page in pages) {
                    val name = page["page"]!!.jsonPrimitive.content
                    val image = platform.codec.decode(p0.resolve("pages/$set/$name").readBytes()) ?: error("cannot decode $name")
                    val boxes = page["regions"]!!.jsonArray.map { r ->
                        val b = r.jsonObject["box"]!!.jsonArray.map { it.jsonPrimitive.int.toFloat() }
                        Box(b[0], b[1], b[2], b[3])
                    }
                    val t = System.nanoTime()
                    val texts = ocr.read(image, boxes)
                    ms += (System.nanoTime() - t) / 1_000_000; regions += boxes.size
                    add(buildJsonObject {
                        put("page", name)
                        put("regions", JsonArray(texts.map { text -> buildJsonObject { put("text", text) } }))
                    })
                }
            }
            p0.resolve("out/$run-kt.json").writeText(out.toString())
            println("P1 $run: ${pages.size} pages, $regions regions, ${ms / maxOf(1, pages.size)} ms/page")
        }
    }
}
