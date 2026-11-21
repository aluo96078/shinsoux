package dev.shinsou.kmp.translation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.access
import platform.posix.F_OK
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS binding of the offline engine against real models, on the simulator. Opt-in, because
 * it needs about 100 MB of models: set SIMCTL_CHILD_SHINSOU_MT_MODELS to a directory holding
 * `ja-en/` and `en-zh-Hant/` with the catalog's files. Skipped everywhere else.
 */
@OptIn(ExperimentalForeignApi::class)
class IosOfflineTranslationEngineTest {
    @Test
    fun japaneseReachesTraditionalChineseThroughTheLinkedEngine() = runBlocking<Unit> {
        val models = getenv("SHINSOU_MT_MODELS")?.toKString()?.takeIf(String::isNotBlank) ?: return@runBlocking
        fun paths(model: OfflineModel): OfflineModelPaths {
            fun path(file: OfflineModelFile) = "$models/${model.from}-${model.to}/${file.name}".also {
                assertEquals(0, access(it, F_OK), "missing $it")
            }
            return OfflineModelPaths(path(model.model), path(model.sourceVocab), path(model.targetVocab), path(model.shortlist))
        }
        val route = OfflineModelCatalog.route("ja", "zh-TW")!!
        val engine = IosOfflineTranslationEngine()
        try {
            val lines = listOf("葵は剣を抜いた。", "「ねえ、本当に行くの？」と彼女は尋ねた。😀")
            val english = engine.translate(paths(route[0]), lines)
            val chinese = engine.translate(paths(route[1]), english)
            println("IOS-ENGINE $english -> $chinese")
            assertEquals(2, chinese.size)
            assertTrue(chinese.all { line -> line.any { it in '一'..'鿿' } }, chinese.toString())
            assertTrue("😀" in chinese[1], "text outside the BMP survives the round trip: $chinese")
            engine.unload()
            assertEquals(1, engine.translate(paths(route[0]), listOf("葵は寝た。")).size, "usable again after unloading")
        } finally {
            engine.close()
        }
    }
}
