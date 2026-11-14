package dev.shinsou.kmp.translation

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline translation path on a real device (ADR-0004): downloads the pinned Japanese to
 * English and English to Traditional Chinese models (about 105 MB) from Mozilla's CDN, verifies
 * them, and translates through the JNI engine. Opt-in, because of the download:
 * `-Pandroid.testInstrumentationRunnerArguments.shinsouOfflineTranslation=true`.
 */
@RunWith(AndroidJUnit4::class)
class OfflineTranslationDeviceTest {
    @Test
    fun modelsDownloadVerifyAndTranslateJapaneseToTraditionalChinese() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shinsouOfflineTranslation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-models-test").apply { deleteRecursively() }
        val http = HttpClient(OkHttp)
        val files = OfflineModelStore(root.absolutePath, http)
        val engine = AndroidOfflineTranslationEngine()
        try {
            val provider = OfflineTranslationProvider(files, engine)
            val missing = provider.missingModels("ja", "zh-TW")
            assertEquals(2, missing.size)
            val downloadStarted = System.currentTimeMillis()
            missing.forEach { files.download(it) }
            Log.i(TAG, "downloaded ${missing.sumOf { it.downloadBytes }} bytes in ${System.currentTimeMillis() - downloadStarted} ms")
            assertTrue(provider.missingModels("ja", "zh-TW").isEmpty())

            val driver = AndroidSqliteDriver(NoSchema, context, name = null)
            val store = TranslationMemoryStore(driver)
            store.putGlossaryTerm("work", "zh-TW", "葵", "小葵")
            val service = TranslationService(store)
            val passage = listOf(
                "葵は剣を抜き、健太の前に立ちはだかった。「ここから先は通さない」",
                "「葵、神殿まであと57日だ」と健太は地図を見ながら言った。",
                "その夜、葵は王都の城壁で健太を待っていた。",
                "𠮷野家で葵は牛丼を食べた。😀",
            )
            val chapter = (1..75).joinToString("\n") { round -> passage.joinToString("\n") { "$it（$round）" } }
            val request = ChapterTranslationRequest("work", "c1", chapter, "ja", "zh-TW")

            val before = nativeHeapMb()
            val started = System.currentTimeMillis()
            val result = service.translate(request, provider)
            val elapsed = System.currentTimeMillis() - started
            val loaded = nativeHeapMb()
            engine.unload()
            val unloaded = nativeHeapMb()

            Log.i(TAG, "translated ${chapter.length} chars, ${result.translatedSegments} lines in $elapsed ms")
            Log.i(TAG, "native heap MB: before=$before loaded=$loaded after unload=$unloaded; device RAM MB=${totalRamMb(context)}")
            result.segments.take(4).forEach { Log.i(TAG, "  ${it.translation}") }
            assertTrue(result.complete)
            assertEquals(300, result.translatedSegments)
            assertTrue(result.segments.count { "小葵" in it.translation.orEmpty() } >= 280, "glossary names survive in most lines")
            assertTrue(result.segments.none { Regex("N\\d+X").containsMatchIn(it.translation.orEmpty()) })
            assertEquals(0, service.translate(request, provider).translatedSegments, "the second read comes from the memory")
            assertTrue(unloaded < loaded, "unloading returns the model's memory")
        } finally {
            engine.close()
            http.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theMemoryGuardRefusesSmallOrBusyDevicesAndAllowsThisOne() {
        val required = OfflineTranslationProvider.MODEL_MEMORY_BYTES
        val gb = 1024L * 1024 * 1024
        fun status(total: Long, available: Long, threshold: Long = 200L * 1024 * 1024, low: Boolean = false) =
            AndroidTranslationMemoryGuard.status(total, available, threshold, low, required)
        assertEquals(TranslationMemoryStatus.DEVICE_TOO_SMALL, status(total = 2 * gb, available = 1 * gb))
        assertEquals(TranslationMemoryStatus.LOW_NOW, status(total = 6 * gb, available = 500L * 1024 * 1024))
        assertEquals(TranslationMemoryStatus.LOW_NOW, status(total = 6 * gb, available = 3 * gb, low = true))
        assertEquals(TranslationMemoryStatus.ENOUGH, status(total = 6 * gb, available = 2 * gb))
        // The device the tests run on is expected to have room; a failure here names what it reported.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        Log.i(TAG, "memory: total=${info.totalMem / 1048576} MB available=${info.availMem / 1048576} MB threshold=${info.threshold / 1048576} MB low=${info.lowMemory}")
        // Only a device that is meant to run the engine is held to it: a 2 GB emulator is rightly refused.
        org.junit.Assume.assumeTrue("needs a device with at least 6 GB", info.totalMem >= 5 * gb)
        assertEquals(TranslationMemoryStatus.ENOUGH, AndroidTranslationMemoryGuard(context).status(required), "reported: $info")
    }

    private fun nativeHeapMb(): Long = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

    private fun totalRamMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private object NoSchema : app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: app.cash.sqldelight.db.SqlDriver) = app.cash.sqldelight.db.QueryResult.Unit
        override fun migrate(
            driver: app.cash.sqldelight.db.SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: app.cash.sqldelight.db.AfterVersion,
        ) = app.cash.sqldelight.db.QueryResult.Unit
    }

    private companion object {
        const val TAG = "SHINSOU-MT"
    }
}
