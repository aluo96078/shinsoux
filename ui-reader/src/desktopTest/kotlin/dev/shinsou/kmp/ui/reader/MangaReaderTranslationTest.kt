package dev.shinsou.kmp.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asSkiaBitmap
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.manga.DesktopMangaPlatform
import dev.shinsou.kmp.manga.MangaModelStore
import dev.shinsou.kmp.manga.MangaPageStore
import dev.shinsou.kmp.manga.MangaRenderCache
import dev.shinsou.kmp.manga.MangaTranslation
import dev.shinsou.kmp.manga.asMangaTranslator
import dev.shinsou.kmp.translation.OfflineModelCatalog
import dev.shinsou.kmp.translation.OfflineModelPaths
import dev.shinsou.kmp.translation.OfflineTranslationEngine
import dev.shinsou.kmp.translation.OfflineTranslationPlatform
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationMemoryStore
import dev.shinsou.kmp.contract.ReaderPage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.Rule
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.flow.first

/**
 * Manga translation in the page reader, driven through its panel with the real models and the
 * offline provider (an engine that marks what it translated). Runs where SHINSOU_MANGA_PACKS and
 * SHINSOU_MANGA_PAGE are set, like the pipeline tests.
 */
@OptIn(ExperimentalTestApi::class)
class MangaReaderTranslationTest {
    @get:Rule
    val rule = createComposeRule()

    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("vision-v1").isDirectory }
    private val page = System.getenv("SHINSOU_MANGA_PAGE")?.let(::File)?.takeIf { it.isFile }

    private object MarkingEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = lines.map { "譯$it" }
        override suspend fun unload() {}
    }

    /** Leaves every other line without an answer until [whole] is set. */
    private class GappyEngine : OfflineTranslationEngine {
        @Volatile var whole = false
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> =
            lines.mapIndexed { i, line -> if (whole || i % 2 == 0) "譯$line" else "" }
        override suspend fun unload() {}
    }

    @Test
    fun aLanguagePickedForTheWorkWhileItWasOffIsKeptWhenItStartsByItself() {
        val off = dev.shinsou.kmp.translation.WorkTranslationSettings(enabled = false, from = "en", to = "zh-Hant", fromPicked = true)
        kotlin.test.assertEquals("en", MangaReaderTranslationState.pickedFrom(off))
        // Japanese picked by the reader is a choice too
        kotlin.test.assertEquals("ja", MangaReaderTranslationState.pickedFrom(off.copy(from = MangaReaderTranslationState.SOURCE_LANGUAGE)))
        // the language the work was taken to be in is not a choice, nor one pages cannot be read in
        kotlin.test.assertNull(MangaReaderTranslationState.pickedFrom(off.copy(fromPicked = false)))
        kotlin.test.assertNull(MangaReaderTranslationState.pickedFrom(off.copy(from = "xx")))
        kotlin.test.assertNull(MangaReaderTranslationState.pickedFrom(null))
    }

    @Test
    fun aWorkThatDoesNotStartByItselfStillReadsItsPagesInTheLanguageItsSourceSays() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("manga-source-language").toFile()
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, root.resolve("text").path),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        translation.setAutoTranslate(on = false, paid = false)
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translation.asMangaTranslator(), { File(it).readLines() },
        )
        val state = MangaReaderTranslationState(manga, translation, "work")
        state.load("zh-Hant")
        state.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals("en", state.settings?.from, "turning it on reads English, not Japanese")
        kotlin.test.assertEquals(false, state.settings?.enabled)
        kotlin.test.assertNull(translation.settings("work"), "nothing is saved until the reader changes something")

        // a language the reader picks is theirs, whatever the source says
        state.update(state.settings!!.copy(from = "zh-Hans"))
        state.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals("zh-Hans", state.settings?.from)
        val reopened = MangaReaderTranslationState(manga, translation, "work")
        reopened.load("zh-Hant")
        reopened.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals("zh-Hans", reopened.settings?.from)

        // Japanese picked by hand, then switched off, is kept over what the source says
        reopened.update(reopened.settings!!.copy(from = "ja", enabled = true))
        reopened.update(reopened.settings!!.copy(enabled = false))
        val again = MangaReaderTranslationState(manga, translation, "work")
        again.load("zh-Hant")
        again.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals("ja", again.settings?.from)

        // a Chinese source that does not name the script: taken to be the other one until its pages tell
        val chinese = MangaReaderTranslationState(manga, translation, "chinese")
        chinese.load("zh-Hant")
        chinese.autoStart("zh", "zh-Hant", readsJapanese = { null }, readsChineseScript = { null })
        kotlin.test.assertEquals("zh-Hans", chinese.settings?.from, "turning it on offers the Chinese recogniser, not Japanese")
        chinese.update(chinese.settings!!.copy(enabled = true))
        kotlin.test.assertNull(MangaReaderTranslationState.pickedFrom(translation.settings("chinese")), "a script only taken is not a choice")
        // once told, a script that is the reader's own leaves it off
        chinese.autoStart("zh", "zh-Hant", readsJapanese = { null }, readsChineseScript = { "zh-Hant" })
        kotlin.test.assertEquals("zh-Hant", translation.settings("chinese")?.from)
        kotlin.test.assertEquals(false, translation.settings("chinese")?.enabled)
        kotlin.test.assertEquals(false, translation.settings("chinese")?.turnedOff)

        // for a reader of another language the script decides the model it is translated by
        val english = MangaReaderTranslationState(manga, translation, "chinese-en")
        english.load("en")
        english.autoStart("zh", "en", readsJapanese = { null }, readsChineseScript = { "zh-Hant" })
        kotlin.test.assertEquals("zh-Hant", english.settings?.from, "the Traditional model, not the Simplified one zh stands for")
        kotlin.test.assertEquals("en", english.settings?.to)
    }

    @Test
    fun aWorkStillStartsByItselfWhenItsSourceSaysItsLanguageLateAndAPickDuringTheProbeIsKept() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("manga-late-language").toFile()
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, root.resolve("text").path),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        translation.setAutoTranslate(on = true, paid = false)
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translation.asMangaTranslator(), { File(it).readLines() },
        )
        // read in Japanese: a work whose language is not known yet is taken to be the reader's own
        val late = MangaReaderTranslationState(manga, translation, "late")
        late.load("ja")
        late.autoStart("", "ja", readsJapanese = { true })
        kotlin.test.assertEquals(false, late.settings?.enabled)
        late.autoStart("zh-Hans", "ja", readsJapanese = { null })
        kotlin.test.assertEquals(true, late.settings?.enabled, "the source saying Chinese once loaded still starts it")
        kotlin.test.assertEquals("zh-Hans", late.settings?.from)
        kotlin.test.assertEquals("en", MangaReaderTranslationState(manga, translation, "ja-app").apply { load("ja") }.settings?.to, "a Japanese reader's default is not Japanese to Japanese")
        // that default is not taken for the reader's choice: a Chinese work starts into Japanese
        val chineseForJapanese = MangaReaderTranslationState(manga, translation, "zh-for-ja")
        chineseForJapanese.load("ja")
        chineseForJapanese.autoStart("zh-Hans", "ja", readsJapanese = { null })
        kotlin.test.assertEquals(true, chineseForJapanese.settings?.enabled)
        kotlin.test.assertEquals("ja", chineseForJapanese.settings?.to)

        // pages not read as Japanese before the source names the language: it still starts once it does
        val unread = MangaReaderTranslationState(manga, translation, "unread")
        unread.load("zh-Hant")
        unread.autoStart("", "zh-Hant", readsJapanese = { false })
        kotlin.test.assertEquals(false, unread.settings?.enabled)
        unread.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals(true, unread.settings?.enabled)
        kotlin.test.assertEquals("en", unread.settings?.from)

        // or once the reader picks the language by hand, the source still saying nothing
        val chosen = MangaReaderTranslationState(manga, translation, "chosen")
        chosen.load("zh-Hant")
        chosen.autoStart("", "zh-Hant", readsJapanese = { false })
        chosen.update(chosen.settings!!.copy(from = "en", fromPicked = true))
        chosen.autoStart("", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals(true, chosen.settings?.enabled)

        // a source naming the reader's own language, corrected by hand while off, still starts
        val own = MangaReaderTranslationState(manga, translation, "own")
        own.load("zh-Hant")
        own.autoStart("zh-Hant", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals(false, own.settings?.enabled)
        own.update(own.settings!!.copy(from = "ja", fromPicked = true))
        own.autoStart("zh-Hant", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals(true, own.settings?.enabled)

        // a language picked while the first pages are read is not overwritten by what they tell
        val probed = MangaReaderTranslationState(manga, translation, "probed")
        probed.load("zh-Hant")
        probed.autoStart("", "zh-Hant", readsJapanese = { probed.update(probed.settings!!.copy(from = "en")); true })
        kotlin.test.assertEquals("en", probed.settings?.from)
        kotlin.test.assertEquals(false, probed.settings?.enabled)

        // Japanese, shown before the source says otherwise, picked by hand is kept too
        val same = MangaReaderTranslationState(manga, translation, "same")
        same.load("zh-Hant")
        same.update(same.settings!!.copy(from = "ja", fromPicked = true))
        same.autoStart("en", "zh-Hant", readsJapanese = { null })
        kotlin.test.assertEquals("ja", same.settings?.from)
    }

    @Test
    fun aChapterLeftPartlyUntranslatedKeepsItsPagesAndOffersRetry() {
        if (packs == null || page == null) return
        val root = Files.createTempDirectory("manga-reader-partial").toFile()
        val textModels = File(root, "text").apply { mkdirs() }
        for (model in OfflineModelCatalog.models) {
            val directory = File(textModels, model.id).apply { mkdirs() }
            model.files.forEach { file -> RandomAccessFile(File(directory, file.name), "rw").use { it.setLength(file.size) } }
        }
        val engine = GappyEngine()
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(engine, textModels.absolutePath),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        packs.resolve("vision-v1").copyRecursively(root.resolve("manga/vision-v1"))
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translation.asMangaTranslator(), { File(it).readLines() },
        )
        val bytes = page.readBytes()
        val pages = listOf(ReaderPage(index = 0, imageBytes = bytes))
        lateinit var state: MangaReaderTranslationState
        rule.setContent {
            state = rememberMangaReaderTranslation(manga, translation, "work", "chapter", pages, exportable = false, labels = null)
            Column {
                MangaTranslationPanel(state)
                Text("${state.displayPages(pages).count { it.imageBytes !== bytes }}", Modifier.testTag("translated-pages"))
            }
        }
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(timeoutMillis = 120_000) { state.failure != null || state.finished }
        kotlin.test.assertFalse(state.finished, "a chapter with untranslated lines is not done")
        rule.onNodeWithText("Some lines or pages were left untranslated. Retry to translate them.").assertExists()
        rule.onNodeWithTag("translated-pages").assertTextEquals("1")

        // translated in full on retry
        engine.whole = true
        rule.onNodeWithText("Retry").performClick()
        rule.waitUntil(timeoutMillis = 120_000) { state.finished }
        kotlin.test.assertNull(state.failure)
    }

    @Test
    fun switchingTranslationOnReplacesThePagesAndOriginalShowsThemAgain() {
        if (packs == null || page == null) return
        val root = Files.createTempDirectory("manga-reader").toFile()
        val textModels = File(root, "text").apply { mkdirs() }
        for (model in OfflineModelCatalog.models) {
            val directory = File(textModels, model.id).apply { mkdirs() }
            model.files.forEach { file -> RandomAccessFile(File(directory, file.name), "rw").use { it.setLength(file.size) } }
        }
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, textModels.absolutePath),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        packs.resolve("vision-v1").copyRecursively(root.resolve("manga/vision-v1"))
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path),
            translation.asMangaTranslator(),
            { File(it).readLines() },
        )
        val bytes = page.readBytes()
        val pages = listOf(ReaderPage(index = 0, imageBytes = bytes), ReaderPage(index = 1, imageBytes = bytes))
        rule.setContent {
            val state = rememberMangaReaderTranslation(manga, translation, "work", "chapter", pages, exportable = false, labels = null)
            Column {
                MangaTranslationPanel(state)
                val shown = state.displayPages(pages)
                Text("${shown.count { it.imageBytes !== bytes }}", Modifier.testTag("translated-pages"))
            }
        }
        rule.onNodeWithTag("translated-pages").assertTextEquals("0")
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(timeoutMillis = 120_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag("translated-pages").and(androidx.compose.ui.test.hasText("2"))).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Original").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("translated-pages").assertTextEquals("0")
        rule.onNodeWithText("Translation").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("translated-pages").assertTextEquals("2")
    }

    /** A look at the panel in Traditional Chinese (written to build/manga-test for review). */
    @Test
    fun thePanelReadsInTraditionalChinese() {
        val root = Files.createTempDirectory("manga-panel").toFile()
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, root.absolutePath),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { 0L }),
            MangaRenderCache(root.resolve("pages").path), translation.asMangaTranslator(), { emptyList() },
        )
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(dev.shinsou.kmp.ui.i18n.LocalShinsouStrings provides dev.shinsou.kmp.ui.i18n.shinsouStringsFor("zh-TW")) {
                androidx.compose.material3.MaterialTheme {
                    androidx.compose.material3.Surface {
                        val state = rememberMangaReaderTranslation(manga, translation, "work", "chapter", emptyList(), exportable = false, labels = null)
                        MangaTranslationPanel(state, Modifier.testTag("panel"))
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("翻譯漫畫頁面").assertExists()
        rule.onNodeWithText("直書").assertExists()
        val image = rule.onNodeWithTag("panel").captureToImage()
        val out = File("build/manga-test").apply { mkdirs() }.resolve("manga-panel-zh-TW.png")
        out.writeBytes(org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun aChapterTranslatedAheadOpensTranslatedFromTheCaches() = kotlinx.coroutines.runBlocking {
        if (packs == null || page == null) return@runBlocking
        val root = Files.createTempDirectory("manga-prefetch").toFile()
        val textModels = File(root, "text").apply { mkdirs() }
        for (model in OfflineModelCatalog.models) {
            val directory = File(textModels, model.id).apply { mkdirs() }
            model.files.forEach { file -> RandomAccessFile(File(directory, file.name), "rw").use { it.setLength(file.size) } }
        }
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, textModels.absolutePath),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        packs.resolve("vision-v1").copyRecursively(root.resolve("manga/vision-v1"))
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path),
            translation.asMangaTranslator(),
            { File(it).readLines() },
        )
        val bytes = page.readBytes()
        val pages = listOf(ReaderPage(index = 0, imageBytes = bytes), ReaderPage(index = 1, imageBytes = bytes))
        val work = dev.shinsou.kmp.domain.model.Manga(id = 1, source = 7, url = "/title")
        val chapter = dev.shinsou.kmp.domain.model.Chapter(id = 11, mangaId = 1, url = "/title/1", name = "第1話")
        val job = mangaPrefetchJob(manga, translation, work, chapter, labels = null, loadPages = { pages })
        kotlin.test.assertEquals(1, translation.prefetch.enqueue(listOf(job)))
        val t = System.nanoTime()
        kotlinx.coroutines.withTimeout(120_000) {
            translation.prefetch.entries.first { list -> list.any { it.id == job.id && it.state != dev.shinsou.kmp.translation.PrefetchState.QUEUED && it.state != dev.shinsou.kmp.translation.PrefetchState.RUNNING } }
        }
        val entry = translation.prefetch.entries.value.first { it.id == job.id }
        kotlin.test.assertEquals(dev.shinsou.kmp.translation.PrefetchState.DONE, entry.state, "failure ${entry.failure}")
        println("MANGA prefetch=${(System.nanoTime() - t) / 1_000_000}ms")
        // the work now opens translated, into the default language
        val settings = translation.settings(mangaWorkKey(work))!!
        kotlin.test.assertTrue(settings.enabled); kotlin.test.assertEquals("zh-Hant", settings.to)
        // and the chapter list marks it translated
        kotlin.test.assertEquals(setOf(mangaChapterKey(chapter)), translation.translatedChapterKeys(mangaWorkKey(work)))

        // the reader then finds every page drawn already
        val opened = System.nanoTime(); var shown = 0
        manga.translateChapter(
            dev.shinsou.kmp.manga.MangaChapterRequest(mangaWorkKey(work), mangaChapterKey(chapter), 2, { bytes }, "ja", "zh-Hant"),
        ) { _, jpeg -> if (jpeg != null) shown++ }
        val openMs = (System.nanoTime() - opened) / 1_000_000
        println("MANGA reader after prefetch=${openMs}ms")
        kotlin.test.assertEquals(2, shown)
        kotlin.test.assertTrue(openMs < 1_000, "took ${openMs}ms")
    }

    @Test
    fun missingOfflineModelsAreOfferedForDownloadInsteadOfFailing() {
        if (packs == null || page == null) return
        val root = Files.createTempDirectory("manga-text-models").toFile()
        val textModels = File(root, "text").apply { mkdirs() }
        val translation = ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(MarkingEngine, textModels.absolutePath),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        packs.resolve("vision-v1").copyRecursively(root.resolve("manga/vision-v1"))
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translation.asMangaTranslator(), { File(it).readLines() },
        )
        val pages = listOf(ReaderPage(index = 0, imageBytes = page.readBytes()))
        lateinit var state: MangaReaderTranslationState
        rule.setContent {
            state = rememberMangaReaderTranslation(manga, translation, "work", "chapter", pages, exportable = false, labels = null)
            Column {
                MangaTranslationPanel(state)
                Text("${state.displayPages(pages).count { it.imageBytes !== pages[0].imageBytes }}", Modifier.testTag("translated-pages"))
            }
        }
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(timeoutMillis = 30_000) { state.textModelBytes > 0 }
        rule.onNodeWithText("Download models").assertExists()
        kotlin.test.assertNull(state.failure, "a missing model is not a failure")
        rule.onNodeWithTag("translated-pages").assertTextEquals("0")

        // once the models are on the device, the chapter is translated
        for (model in OfflineModelCatalog.models) {
            val directory = File(textModels, model.id).apply { mkdirs() }
            model.files.forEach { file -> RandomAccessFile(File(directory, file.name), "rw").use { it.setLength(file.size) } }
        }
        state.retry()
        rule.waitUntil(timeoutMillis = 120_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag("translated-pages").and(androidx.compose.ui.test.hasText("1"))).fetchSemanticsNodes().isNotEmpty()
        }
        kotlin.test.assertEquals(0L, state.textModelBytes)
    }
}
