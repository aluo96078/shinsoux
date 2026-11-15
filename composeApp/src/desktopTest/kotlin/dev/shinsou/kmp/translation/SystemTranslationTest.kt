package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemTranslationTest {
    private object NoEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = error("the offline engine must not run")
        override suspend fun unload() = Unit
    }

    private class FakeSystem(var status: SystemLanguageStatus = SystemLanguageStatus.INSTALLED, override var available: Boolean = true) : SystemTranslationEngine {
        override var name = "fake"
        val calls = ArrayList<Triple<String, String, List<String>>>()
        var downloads = 0

        override suspend fun status(from: String, to: String) = status
        override suspend fun translate(from: String, to: String, lines: List<String>): List<String> {
            calls += Triple(from, to, lines)
            return lines.map { "S($it)" }
        }
        override suspend fun download(from: String, to: String): Boolean {
            downloads++
            status = SystemLanguageStatus.INSTALLED
            return true
        }
    }

    private fun reader(system: SystemTranslationEngine?) = ReaderTranslation(
        TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
        OfflineTranslationPlatform(NoEngine, "/nonexistent"),
        HttpClient(MockEngine { error("nothing may go over the network") }),
        InMemoryPluginKeyValueStore(),
        now = { 1_000L },
        system = system,
    )

    private fun request(chapter: String = "c1") = ChapterTranslationRequest("work", chapter, "한 줄\n두 줄", from = "ko", to = "zh-Hant")

    @Test
    fun theSystemTranslatesDirectlyAndItsTranslationIsKept() = runBlocking<Unit> {
        val system = FakeSystem()
        val translation = reader(system)
        translation.setProvider(TranslationProviderKind.SYSTEM)
        assertEquals(TranslationProviderKind.SYSTEM, translation.activeProvider())
        assertTrue(translation.preferences().systemAvailable)

        val result = translation.translate(request()) { _, _ -> }
        assertTrue(result.complete)
        assertEquals("S(한 줄)\nS(두 줄)", result.text())
        // One call, straight from Korean: no English pivot.
        assertEquals(1, system.calls.size)
        assertEquals("ko", system.calls.single().first)

        // Opening it again reads the stored translation.
        assertEquals(0, translation.translate(request()) { _, _ -> }.translatedSegments)
        assertEquals(1, system.calls.size)
    }

    @Test
    fun languagesNotYetDownloadedAreReportedAndCanBeDownloaded() = runBlocking<Unit> {
        val system = FakeSystem(SystemLanguageStatus.DOWNLOADABLE)
        val translation = reader(system)
        translation.setProvider(TranslationProviderKind.SYSTEM)
        assertTrue(translation.systemLanguagesMissing("ko", "zh-Hant"))
        val failure = assertFailsWith<TranslationException> { translation.translate(request()) { _, _ -> } }
        assertEquals(TranslationFailure.SYSTEM_LANGUAGE_MISSING, failure.failure)

        assertTrue(translation.downloadSystemLanguages("ko", "zh-Hant"))
        assertFalse(translation.systemLanguagesMissing("ko", "zh-Hant"))
        assertTrue(translation.translate(request()) { _, _ -> }.complete)
    }

    @Test
    fun chaptersTheSystemsOtherModelsTranslatedAreNotDoneForTheNewOnes() = runBlocking<Unit> {
        val system = FakeSystem()
        val translation = reader(system)
        translation.setProvider(TranslationProviderKind.SYSTEM)
        translation.translate(request()) { _, _ -> }
        assertEquals(setOf("c1"), translation.translatedChapterKeys("work", "zh-Hant"))

        // The system upgraded to other models: translating ahead must not skip the chapter.
        system.name = "fake-fast"
        assertEquals(emptySet(), translation.translatedChapterKeys("work", "zh-Hant"))
        assertEquals(1, translation.translate(request()) { _, _ -> }.translatedSegments.coerceAtMost(1))
        assertEquals(2, system.calls.size)
        assertEquals(setOf("c1"), translation.translatedChapterKeys("work", "zh-Hant"))
    }

    @Test
    fun drawnMangaPagesAreKeyedByTheEngineNotOnlyTheProvider() = runBlocking<Unit> {
        val system = FakeSystem()
        val translation = reader(system)
        translation.setProvider(TranslationProviderKind.SYSTEM)
        val before = translation.engineIdentity()
        assertTrue(before.startsWith("system:"))
        system.name = "fake-fast"
        assertTrue(translation.engineIdentity() != before, "the system's other models draw other pages")
        translation.setProvider(TranslationProviderKind.OFFLINE)
        assertEquals("offline", translation.engineIdentity())
    }

    @Test
    fun anUnsupportedPairIsSaidSo() = runBlocking<Unit> {
        val translation = reader(FakeSystem(SystemLanguageStatus.UNSUPPORTED))
        translation.setProvider(TranslationProviderKind.SYSTEM)
        val failure = assertFailsWith<TranslationException> { translation.translate(request()) { _, _ -> } }
        assertEquals(TranslationFailure.UNSUPPORTED_PAIR, failure.failure)
    }

    @Test
    fun withoutTheSystemsTranslationTheChoiceFallsBackToTheOfflineEngine() = runBlocking<Unit> {
        for (system in listOf(null, FakeSystem(available = false))) {
            val translation = reader(system)
            translation.setProvider(TranslationProviderKind.SYSTEM)
            assertEquals(TranslationProviderKind.OFFLINE, translation.activeProvider())
            assertFalse(translation.preferences().systemAvailable)
            assertFalse(translation.systemLanguagesMissing("ko", "zh-Hant"))
        }
    }

    @Test
    fun theQueueStopsAWorkWhoseLanguagesAreNotDownloaded() = runBlocking<Unit> {
        val system = FakeSystem(SystemLanguageStatus.DOWNLOADABLE)
        val translation = reader(system)
        translation.setProvider(TranslationProviderKind.SYSTEM)
        val languages = PrefetchLanguages(to = "zh-Hant", from = "ko")
        val jobs = listOf("c1", "c2").map { chapter ->
            TranslationPrefetchJob("work|$chapter", "work", chapter, languages = languages, unit = chapter) {
                PrefetchChapter("work", chapter, "한 줄", exportable = false, labels = null)
            }
        }
        translation.prefetch.enqueue(jobs)
        val entries = kotlinx.coroutines.withTimeout(10_000) {
            translation.prefetch.entries.first { list -> list.none { it.state == PrefetchState.QUEUED || it.state == PrefetchState.RUNNING } }
        }
        assertEquals(listOf(TranslationFailure.SYSTEM_LANGUAGE_MISSING, TranslationFailure.SYSTEM_LANGUAGE_MISSING), entries.map { it.failure })
        assertEquals(0, system.calls.size)
    }
}
