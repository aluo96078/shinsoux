package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
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
        )
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
        assertTrue(body().lines().first().endsWith("(小葵は剣を抜いた。))"), body())

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
}
