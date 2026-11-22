@file:OptIn(dev.shinsou.kmp.testing.TestOnlyApi::class)

package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.local.LocalContentManager
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
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.domain.model.ReaderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dev.shinsou.kmp.translation.PrefetchState
import org.junit.Rule
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.shinsou.kmp.ui.reader.UiReaderTestAccess
import dev.shinsou.kmp.ui.reader.UnifiedContentReader
import dev.shinsou.kmp.ui.reader.mangaTranslationAvailable
import dev.shinsou.kmp.ui.reader.prefetchChapter

/**
 * Manga translation in the unified reader's image chapters (ADR-0005): a local image import,
 * read through the unified reader with the real models and a marking offline engine. Runs where
 * SHINSOU_MANGA_PACKS (with the font-sc pack) and SHINSOU_MANGA_PAGE are set.
 */
class UnifiedImageTranslationTest {
    @get:Rule
    val rule = createComposeRule()

    // the chapters are translated into Simplified Chinese, drawn with its font: skipped (and said so) without it
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("vision-v1").isDirectory }?.takeIf {
        val font = dev.shinsou.kmp.manga.MangaModelCatalog.FONT_SC.id
        it.resolve(font).isDirectory.also { found -> if (!found) println("MANGA skipped: $font is not among the packs") }
    }
    private val page = System.getenv("SHINSOU_MANGA_PAGE")?.let(::File)?.takeIf { it.isFile }

    private object MarkingEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = lines.map { "譯$it" }
        override suspend fun unload() {}
    }

    private class NoFiles : AppFileSystem {
        override suspend fun write(relativePath: String, bytes: ByteArray) = Unit
        override suspend fun read(relativePath: String): ByteArray? = null
        override suspend fun exists(relativePath: String): Boolean = false
        override suspend fun delete(relativePath: String): Boolean = false
        override suspend fun deleteTree(relativeDirectory: String): Boolean = false
        override suspend fun list(relativeDirectory: String): List<String> = emptyList()
        override fun uri(relativePath: String): String = "memory://$relativePath"
    }

    private class Fixture(
        val translation: ReaderTranslation,
        val manga: MangaTranslation,
        val renders: File,
        val features: ContentFeatureRuntime,
        val session: dev.shinsou.kmp.contract.TypedReaderContentSession,
    )

    private fun fixture(packs: File, page: File): Fixture {
        val root = Files.createTempDirectory("unified-manga").toFile()
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
        // the Simplified Chinese drawn into needs its font
        dev.shinsou.kmp.manga.MangaModelCatalog.FONT_SC.id.let { font -> packs.resolve(font).copyRecursively(root.resolve("manga/$font")) }
        val renders = root.resolve("pages")
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(),
            MangaModelStore(root.resolve("manga").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(renders.path), translation.asMangaTranslator(), { File(it).readLines() },
        )
        val foundation = ContentFoundationRuntime(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), syncModeProvider = { ContentSyncMode.V2_ACTIVE })
        val local = LocalContentManager(repository = ShinsouRepository(), fileSystem = NoFiles(), contentFoundation = foundation, now = { 1_000 })
        val bytes = page.readBytes()
        val session = runBlocking {
            val imported = local.importLocalDocuments(listOf(ImportedDocument("001.jpg", bytes), ImportedDocument("002.jpg", bytes)), syncContentBodies = false).single()
            assertNotNull(local.loadReaderChapter(imported.mangaId, imported.chapterId).typedSession)
        }
        val features = ContentFeatureRuntime(foundation, nowEpochMillis = { 1_000 }, translation = translation)
        return Fixture(translation, manga, renders, features, session)
    }

    @Test
    fun anImageChapterIsTranslatedInTheUnifiedReader() {
        if (packs == null || page == null) return
        val f = fixture(packs, page)
        val manga = f.manga; val renders = f.renders; val features = f.features; val session = f.session
        assertTrue(UiReaderTestAccess.mangaTranslationAvailable(session, features), "a local import may be kept on the device")

        var serial by androidx.compose.runtime.mutableLongStateOf(0L)
        rule.setContent {
            Box(Modifier.size(500.dp, 700.dp).testTag("reader")) {
                UiReaderTestAccess.UnifiedContentReader(
                    session = session, features = features, settings = ReaderSettings(),
                    translationRequestSerial = serial, mangaTranslation = manga, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // the original page is on screen first
        fun shot() = rule.onNodeWithTag("reader").captureToImage().asSkiaBitmap()
        fun drawn(bitmap: org.jetbrains.skia.Bitmap) = bitmap.readPixels()!!.let { p -> p.indices.count { it % 4 != 3 && p[it] != (-1).toByte() } > p.size / 20 }
        rule.waitUntil(timeoutMillis = 30_000) { drawn(shot()) }

        // the toolbar's button opens the manga panel; switching it on translates the chapter
        serial++
        rule.waitForIdle()
        rule.onNodeWithText("Translate manga pages").assertExists()
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        val before = shot() // with the sheet and its scrim, like the shot after
        rule.onNode(isToggleable()).performClick()
        // both pages are the same image, so they share one drawn page in the cache
        rule.waitUntil(timeoutMillis = 60_000) { (renders.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0) >= 1 }
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        val after = rule.onNodeWithTag("reader").captureToImage().asSkiaBitmap()

        // the top of the reader, above the sheet: the same page, its text drawn over
        val a = before.readPixels()!!; val b = after.readPixels()!!
        // pixels that clearly changed (a redrawn JPEG shifts every pixel a little)
        val top = a.size / 4 * 2 / 5
        val changed = (0 until top).count { px -> (0 until 3).any { c -> kotlin.math.abs((a[px * 4 + c].toInt() and 0xff) - (b[px * 4 + c].toInt() and 0xff)) > 60 } }.toDouble() / top
        println("MANGA unified changed=${"%.3f".format(changed)}")
        File("build/manga-test").apply { mkdirs() }.let { dir ->
            for ((name, bitmap) in listOf("unified-before" to before, "unified-after" to after)) {
                dir.resolve("$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            }
        }
        assertTrue(changed in 0.005..0.5, "the page on screen should be the translated one (changed $changed)")
    }

    @Test
    fun anImageChapterQueuedAheadIsTranslatedAsMangaPages() = runBlocking {
        if (packs == null || page == null) return@runBlocking
        val f = fixture(packs, page)
        val job = dev.shinsou.kmp.translation.TranslationPrefetchJob(
            id = "image-chapter", group = "work", title = "第1話",
            languages = dev.shinsou.kmp.translation.PrefetchLanguages(to = "zh-Hans", from = null),
            load = { UiReaderTestAccess.prefetchChapter(f.session, f.features, labels = null, manga = f.manga) },
        )
        kotlin.test.assertEquals(1, f.translation.prefetch.enqueue(listOf(job)))
        kotlinx.coroutines.withTimeout(120_000) {
            f.translation.prefetch.entries.first { list -> list.any { it.id == job.id && it.state != PrefetchState.QUEUED && it.state != PrefetchState.RUNNING } }
        }
        val entry = f.translation.prefetch.entries.value.first { it.id == job.id }
        kotlin.test.assertEquals(PrefetchState.DONE, entry.state, "failure ${entry.failure}")
        kotlin.test.assertTrue(entry.fetched)
        assertTrue((f.renders.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0) >= 1, "the pages are drawn ahead")
        // the work opens translated, into the language chosen when queueing, from Japanese
        val settings = f.translation.settings(f.session.content.navigation.scope.publicationId.value)!!
        assertTrue(settings.enabled); kotlin.test.assertEquals("ja", settings.from); kotlin.test.assertEquals("zh-Hans", settings.to)
        // and the chapter counts as translated, as a text chapter would: the list marks it, and queueing it again skips it
        val scope = f.session.content.navigation.scope
        assertTrue(scope.unitId.value in f.translation.translatedChapterKeys(scope.publicationId.value, "zh-Hans"))
        assertTrue(scope.unitId.value !in f.translation.translatedChapterKeys(scope.publicationId.value, "ko"))

        // without manga translation an image chapter is still not something to queue
        kotlin.test.assertFailsWith<dev.shinsou.kmp.translation.PrefetchNotTranslatable> {
            UiReaderTestAccess.prefetchChapter(f.session, f.features, labels = null)
        }
    }
}
