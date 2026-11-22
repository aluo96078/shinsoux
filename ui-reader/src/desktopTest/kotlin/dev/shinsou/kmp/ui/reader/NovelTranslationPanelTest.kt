package dev.shinsou.kmp.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.translation.OfflineModelCatalog
import dev.shinsou.kmp.translation.OfflineModelPaths
import dev.shinsou.kmp.translation.OfflineTranslationEngine
import dev.shinsou.kmp.translation.OfflineTranslationPlatform
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationDisplayText
import dev.shinsou.kmp.translation.TranslationMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.Rule
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** The reader's translation panel driven through its UI, with an engine that marks what it translated. */
@OptIn(ExperimentalTestApi::class)
class NovelTranslationPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val chapter = "葵は剣を抜いた。\n\n健太は寝た。"

    private class MarkingEngine : OfflineTranslationEngine {
        val models = ArrayList<String>()
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> {
            models += File(model.model).parentFile.name
            return lines.map { "T($it)" }
        }
        var unloads = 0
        override suspend fun unload() { unloads++ }
    }

    private fun translation(
        root: File,
        engine: OfflineTranslationEngine,
        installed: Boolean,
        auto: Boolean = false,
        memory: dev.shinsou.kmp.translation.TranslationMemoryGuard = dev.shinsou.kmp.translation.TranslationMemoryGuard.Unrestricted,
    ): ReaderTranslation {
        if (installed) {
            // Files of the pinned sizes: the store checks size here; content is the engine's concern.
            for (model in OfflineModelCatalog.models) {
                val directory = File(root, model.id).apply { mkdirs() }
                model.files.forEach { file -> RandomAccessFile(File(directory, file.name), "rw").use { it.setLength(file.size) } }
            }
        }
        return ReaderTranslation(
            memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            platform = OfflineTranslationPlatform(engine, root.absolutePath, memory),
            http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        ).also { kotlinx.coroutines.runBlocking { it.setup.setAutoTranslate(auto, paid = false) } }
    }

    private var noticeTaps = 0

    private fun notice(): String? = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("translation-notice"), useUnmergedTree = false)
        .fetchSemanticsNodes().firstOrNull()?.let { node ->
            val config = node.config
            if (androidx.compose.ui.semantics.SemanticsProperties.Text in config) {
                config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
            } else {
                ""
            }
        }

    private fun show(translation: ReaderTranslation) {
        rule.setContent {
            val state = rememberNovelTranslation(translation, workKey = "work", chapterKey = "c1", canonicalText = chapter)!!
            val display = remember(state.result, state.settings.mode) {
                state.result?.let { TranslationDisplayText.build(chapter, it, state.settings.mode) }
            }
            Column {
                Text(display?.text ?: chapter, Modifier.testTag("body"))
                NovelTranslationNotice(state, onOpen = { noticeTaps++ })
                NovelTranslationPanel(state)
            }
        }
    }

    private fun body(): String {
        val config = rule.onNodeWithTag("body").fetchSemanticsNode().config
        val texts = if (androidx.compose.ui.semantics.SemanticsProperties.Text in config) config[androidx.compose.ui.semantics.SemanticsProperties.Text] else emptyList()
        return texts.joinToString("") { it.text }
    }

    @Test
    fun switchingTranslationOnTranslatesTheChapterAndAGlossaryTermChangesIt() {
        val engine = MarkingEngine()
        show(translation(Files.createTempDirectory("mt-panel").toFile(), engine, installed = true))
        rule.onNodeWithTag("body").assertTextContains(chapter)

        // Nothing is said over the page while translation is off.
        assertTrue(notice() == null, "no notice while translation is off: ${notice()}")
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10_000) { "T(" in body() }
        // The reader is told it happened, without opening the panel.
        rule.waitUntil(5_000) { notice() == "Translated" }
        // Japanese was detected; every line went through the engine and blank lines stayed blank.
        assertTrue(engine.models.first().startsWith("ja-en-"), engine.models.toString())
        assertTrue(body().lines().size == 3 && body().lines()[1].isEmpty(), body())
        assertTrue(engine.unloads >= 1, "the model is released once the chapter is translated")

        rule.onNodeWithTag("translation-panel").performScrollToNode(hasText("In the original"))
        rule.onNodeWithText("In the original").performTextInput("葵")
        rule.onNodeWithText("Shown as").performTextInput("小葵")
        rule.onNodeWithTag("translation-panel").performScrollToNode(hasText("Add"))
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(10_000) { "小葵" in body() }
        // One hop or two: the machine's language decides the target, so only the name is checked.
        assertTrue(body().lines().first().let { it.startsWith("T(") && "(小葵は剣を抜いた。)" in it }, body())

        rule.onNodeWithTag("translation-panel").performScrollToNode(hasText("Original only"))
        rule.onNodeWithText("Original only").performClick()
        rule.waitUntil(5_000) { body() == chapter }
    }

    @Test
    fun tooLittleFreeMemoryIsReportedAndTranslationResumesWhenThereIsRoom() {
        val engine = MarkingEngine()
        var status = dev.shinsou.kmp.translation.TranslationMemoryStatus.LOW_NOW
        show(translation(Files.createTempDirectory("mt-panel").toFile(), engine, installed = true) { status })

        rule.onNode(isToggleable()).performClick()
        val message = "Not enough free memory to translate right now. Close other apps and try again."
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(engine.models.isEmpty(), "no model was loaded")
        rule.onNodeWithTag("body").assertTextContains(chapter)
        // Over the page too, and tapping it leads to the panel.
        assertTrue(notice() == "Not translated: not enough free memory right now.", notice().toString())
        rule.onNodeWithTag("translation-notice").performClick()
        assertTrue(noticeTaps == 1)

        status = dev.shinsou.kmp.translation.TranslationMemoryStatus.ENOUGH
        rule.onNodeWithTag("translation-panel").performScrollToNode(hasText("Retry"))
        rule.onNodeWithText("Retry").performClick()
        rule.waitUntil(10_000) { "T(" in body() }
    }

    @Test
    fun aDeviceThatCanNeverHoldAModelIsToldSoBeforeAnythingIsDownloaded() {
        val engine = MarkingEngine()
        show(
            translation(Files.createTempDirectory("mt-panel").toFile(), engine, installed = false) {
                dev.shinsou.kmp.translation.TranslationMemoryStatus.DEVICE_TOO_SMALL
            },
        )

        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Offline translation is not available on this device.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasText("Download models")).fetchSemanticsNodes().isEmpty())
        assertTrue(engine.models.isEmpty())
    }

    @Test
    fun aLanguagePairWithoutModelsOffersTheDownloadInsteadOfTranslating() {
        val engine = MarkingEngine()
        show(translation(Files.createTempDirectory("mt-panel").toFile(), engine, installed = false))

        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Download models")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(engine.models.isEmpty())
        rule.onNodeWithTag("body").assertTextContains(chapter)

        // The download fails here (no network in tests): the panel says so and stays usable.
        rule.onNodeWithTag("translation-panel").performScrollToNode(hasText("Download models"))
        rule.onNodeWithText("Download models").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("The download failed. Check the connection and try again.")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aChapterThatCannotBeTranslatedYetSaysSoOverThePage() {
        show(translation(Files.createTempDirectory("mt-panel").toFile(), MarkingEngine(), installed = false))
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10_000) { notice()?.startsWith("Not translated: translation models must be downloaded first") == true }
        rule.onNodeWithTag("body").assertTextContains(chapter)
    }

    @Test
    fun aWorkInAnotherLanguageTranslatesAsItOpensAndTurningItOffIsRemembered() {
        val engine = MarkingEngine()
        val translation = translation(Files.createTempDirectory("mt-panel").toFile(), engine, installed = true, auto = true)
        show(translation)

        // Japanese, read in English: nothing to press.
        rule.waitUntil(10_000) { "T(" in body() }
        assertTrue(kotlinx.coroutines.runBlocking { translation.chapters.settings("work") } == null, "starting by itself saves nothing")

        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(5_000) { body() == chapter }
        rule.waitUntil(5_000) { kotlinx.coroutines.runBlocking { translation.chapters.settings("work") }?.enabled == false }
    }

    @Test
    fun aWorkOpenedOnAPageInTheReadersLanguageStartsOnTheFirstChapterThatIsNot() {
        val translation = translation(Files.createTempDirectory("mt-panel").toFile(), MarkingEngine(), installed = true, auto = true)
        val english = "Foreword. Thanks for reading."
        var opened by androidx.compose.runtime.mutableStateOf("c0" to english)
        rule.setContent {
            val state = rememberNovelTranslation(translation, workKey = "work", chapterKey = opened.first, canonicalText = opened.second)!!
            val display = remember(state.result, state.settings.mode) {
                state.result?.let { TranslationDisplayText.build(opened.second, it, state.settings.mode) }
            }
            Text(display?.text ?: opened.second, Modifier.testTag("body"))
        }
        rule.waitForIdle()
        assertTrue("T(" !in body(), "a page in the reader's own language is left as it is")

        opened = "c1" to chapter
        rule.waitUntil(10_000) { "T(" in body() }
        assertTrue(kotlinx.coroutines.runBlocking { translation.chapters.settings("work") } == null, "starting by itself saves nothing")
    }

    @Test
    fun aTargetPickedWhileItWasOffIsKeptWhenALaterChapterStartsByItself() {
        val translation = translation(Files.createTempDirectory("mt-panel").toFile(), MarkingEngine(), installed = true, auto = true)
        var opened by androidx.compose.runtime.mutableStateOf("c0" to "Foreword. Thanks for reading.")
        var state: NovelTranslationState? = null
        rule.setContent {
            state = rememberNovelTranslation(translation, workKey = "work", chapterKey = opened.first, canonicalText = opened.second)!!
        }
        rule.waitUntil(5_000) { state?.loaded == true }
        rule.runOnIdle { state!!.change(state!!.settings.copy(to = "zh-Hans")) }
        opened = "c1" to chapter
        rule.waitUntil(5_000) { state!!.settings.enabled }
        assertTrue(state!!.settings.to == "zh-Hans", "the target picked while it was off, not the app's language: ${state!!.settings.to}")
    }

    @Test
    fun aWorkThatStartedByItselfFollowsTheLanguageOfALaterChapter() {
        val translation = translation(Files.createTempDirectory("mt-panel").toFile(), MarkingEngine(), installed = true, auto = true)
        var opened by androidx.compose.runtime.mutableStateOf("c0" to chapter)
        var state: NovelTranslationState? = null
        rule.setContent {
            state = rememberNovelTranslation(translation, workKey = "work", chapterKey = opened.first, canonicalText = opened.second)!!
        }
        rule.waitUntil(5_000) { state?.settings?.enabled == true && state!!.settings.from == "ja" }
        // a title page of a few characters is not enough to say the work changed language
        opened = "c1" to "第二章 再会之日 序幕与回忆"
        rule.waitUntil(5_000) { state!!.decidedFor?.startsWith("zh") == true }
        rule.waitForIdle()
        assertTrue(state!!.settings.enabled && state!!.settings.from == "ja", "a short page changed it: ${state!!.settings}")
        opened = "c2" to "他拔出了剑。然后健太睡着了，这个故事还没有结束。".repeat(4)
        rule.waitUntil(5_000) { opened.first == "c2" && state!!.settings.from.startsWith("zh") }
        rule.waitForIdle()
        assertTrue(state!!.settings.from.startsWith("zh"), "not read as the language of the chapter it started on: ${state!!.settings}")
        assertTrue(kotlinx.coroutines.runBlocking { translation.chapters.settings("work") } == null, "still nothing saved")
    }

    @Test
    fun aPageOfPicturesIsNotReadAsALanguage() {
        val pictures = "![](https://cdn.example/illustrations/volume-one/001.jpg)\n\n<img src=\"https://cdn.example/illustrations/002.png\" alt=\"\">"
        assertTrue(ReaderTranslation.detectLanguageOrNull(pictures) == null, "markup and addresses are not writing")
        assertTrue(ReaderTranslation.detectLanguageOrNull("") == null)
        assertTrue(ReaderTranslation.detectLanguageOrNull(chapter) == "ja")
        assertTrue(ReaderTranslation.detectLanguageOrNull("Foreword. Thanks for reading.") == "en")
    }

    @Test
    fun theDetectedLanguagePickedByHandIsKeptOnALaterChapter() {
        val translation = translation(Files.createTempDirectory("mt-panel").toFile(), MarkingEngine(), installed = true, auto = true)
        var opened by androidx.compose.runtime.mutableStateOf("c0" to "Foreword. Thanks for reading.")
        var state: NovelTranslationState? = null
        rule.setContent {
            state = rememberNovelTranslation(translation, workKey = "work", chapterKey = opened.first, canonicalText = opened.second)!!
        }
        rule.waitUntil(5_000) { state?.loaded == true && state!!.settings.from == "en" }
        rule.runOnIdle { state!!.change(state!!.settings.copy(from = "en", fromPicked = true)) }
        opened = "c1" to chapter
        // decided for the later, Japanese chapter: only then does "kept" say anything
        rule.waitUntil(5_000) { state!!.decidedFor == "ja" }
        rule.waitForIdle()
        assertTrue(state!!.settings.from == "en" && !state!!.settings.enabled, "English picked by hand stays: ${state!!.settings}")
    }
}
