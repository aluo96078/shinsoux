package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The whole offline path against the real engine: catalog, installed model files, the helper
 * process and the translation core. Opt-in, because it needs about 100 MB of models: set
 * SHINSOU_MT_MODELS to a directory holding `ja-en/` and `en-zh-Hant/` with the catalog's files
 * (the phase 0 spike's `models/`), and build `translation-core` first. Skipped everywhere else.
 */
class OfflineEngineSpikeTest {
    private fun fixture(): Triple<OfflineTranslationProvider, HelperOfflineTranslationEngine, File>? {
        val models = System.getenv("SHINSOU_MT_MODELS")?.let(::File)?.takeIf(File::isDirectory) ?: return null
        val helper = listOf("translation-core/build/release/shinsou-translate", "../translation-core/build/release/shinsou-translate")
            .map(::File).firstOrNull(File::canExecute) ?: return null
        val root = Files.createTempDirectory("shinsou-mt-models").toFile()
        for (model in OfflineModelCatalog.route("ja", "zh-TW").orEmpty()) {
            val directory = File(root, model.id).apply { mkdirs() }
            for (file in model.files) {
                val source = File(models, "${model.from}-${model.to}/${file.name}")
                // Real files: the store does not follow links when it checks a model is installed.
                if (source.isFile) source.copyTo(File(directory, file.name), overwrite = true)
            }
        }
        val files = object : OfflineModelFiles {
            private val delegate = OfflineModelStore(root.absolutePath, io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network in this test") }))
            override fun installed(model: OfflineModel) = delegate.installed(model)
            override suspend fun download(model: OfflineModel, onProgress: (Long, Long) -> Unit) = error("no network in this test")
            override fun remove(model: OfflineModel) = Unit
        }
        root.deleteOnExit()
        val engine = HelperOfflineTranslationEngine { Path.of(helper.absolutePath) }
        return Triple(OfflineTranslationProvider(files, engine), engine, root)
    }

    @Test
    fun aJapanesePassageReachesTraditionalChineseWithGlossaryNamesIntact() = runBlocking<Unit> {
        val (provider, engine, _) = fixture() ?: return@runBlocking
        engine.use {
            assertTrue(provider.missingModels("ja", "zh-TW").isEmpty(), "the spike models match the catalog")
            val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
            val service = TranslationService(store)
            store.putGlossaryTerm("work", "zh-TW", "葵", "小葵")
            store.putGlossaryTerm("work", "zh-TW", "健太", "健太")
            val text = """
                葵は剣を抜き、健太の前に立ちはだかった。「ここから先は通さない」
                「葵、神殿まであと57日だ」と健太は地図を見ながら言った。

                その夜、葵は王都の城壁で健太を待っていた。
                「ねえ、本当に行くの？」と葵は不安そうに尋ねた。
            """.trimIndent()
            val request = ChapterTranslationRequest("work", "c1", text, "ja", "zh-TW")

            val result = service.translate(request, provider)

            println("OFFLINE-ENGINE\n" + result.text())
            assertTrue(result.complete)
            val translated = result.segments.mapNotNull { it.translation }
            assertEquals(4, translated.size)
            assertTrue(translated.all { "小葵" in it }, translated.toString())
            assertTrue(translated.none { Regex("N\\d+X").containsMatchIn(it) }, translated.toString())
            assertEquals(0, service.translate(request, provider).translatedSegments)
        }
    }

    @Test
    fun aMissingModelFileIsReportedAndTheEngineRecovers() = runBlocking<Unit> {
        val (provider, engine, root) = fixture() ?: return@runBlocking
        engine.use {
            val model = OfflineModelCatalog.model("ja", "en")!!
            val paths = OfflineModelPaths("$root/missing.bin", "$root/missing.spm", "$root/missing.spm", "$root/missing.bin")
            val failure = assertFailsWith<TranslationException> { engine.translate(paths, listOf("葵")) }
            assertEquals(TranslationFailure.ENGINE_FAILED, failure.failure)
            // The helper aborted on the bad model; the next call starts a new one.
            assertEquals(1, provider.translate(TranslationHop("ja", "en", model.engine), listOf("葵は寝た。")).size)
        }
    }
}
