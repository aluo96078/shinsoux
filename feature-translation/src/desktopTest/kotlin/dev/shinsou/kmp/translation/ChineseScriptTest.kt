package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.translation.InMemoryKeyValues as InMemoryPluginKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChineseScriptTest {
    private object NoEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = error("the offline engine must not run")
        override suspend fun unload() = Unit
    }

    private fun reader() = ReaderTranslation(
        TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
        OfflineTranslationPlatform(NoEngine, "/nonexistent"),
        HttpClient(MockEngine { error("nothing may go over the network") }),
        InMemoryPluginKeyValueStore(),
        now = { 1_000L },
    )

    private suspend fun convert(text: String, from: String, to: String): String {
        val provider = ChineseScriptProvider()
        val hop = provider.route(from, to)!!.single()
        return provider.translate(hop, listOf(text)).single()
    }

    @Test
    fun simplifiedIsWrittenAsTaiwanTraditionalWithItsWords() = runBlocking {
        assertEquals("頭髮很長，他說這個軟體的記憶體不夠", convert("头发很长，他说这个软件的内存不够", "zh-Hans", "zh-Hant"))
        assertEquals("我們去印表機那裡列印檔案", convert("我们去打印机那里打印文件", "zh-CN", "zh-TW"))
        assertEquals("裡面的乾淨衣服", convert("里面的干净衣服", "zh-Hans", "zh-Hant"))
    }

    @Test
    fun aTargetSavedForTheWorkDoesNotSendItToAPaidServiceByItself() = runBlocking {
        val reader = reader()
        reader.setProvider(TranslationProviderKind.DEEPL)
        reader.setKey(TranslationProviderKind.DEEPL, "key")
        reader.setAutoTranslate(on = true, paid = false)
        reader.setAutoTranslateOtherScript(true)
        val auto = assertNotNull(reader.autoTranslation("zh-Hans", "zh-TW"))
        // converting the script is free; English would go to DeepL, which may not start by itself
        assertEquals("zh-Hant", reader.autoTarget(auto, "en"))
        reader.setAutoTranslate(on = true, paid = true)
        assertEquals("en", reader.autoTarget(auto, "en"))
        assertEquals("zh-Hant", reader.autoTarget(auto, null))
    }

    @Test
    fun aWordIsNotReadAcrossTheBoundaryOfTheWordBeforeIt() = runBlocking {
        // 面包 (bread) would be matched inside 里面包含 if 里面 were left out of the table
        assertEquals("裡面包含水分", convert("里面包含水分", "zh-Hans", "zh-Hant"))
        assertEquals("外面包著一層紙", convert("外面包着一层纸", "zh-Hans", "zh-Hant"))
        // and not when a phrase inside it was left out too
        assertEquals("遊覽勝地一卷", convert("游览胜地一卷", "zh-Hans", "zh-Hant"))
    }

    @Test
    fun traditionalIsWrittenAsSimplifiedWithMainlandWords() = runBlocking {
        assertEquals("头发很长，他说这个软件的内存不够", convert("頭髮很長，他說這個軟體的記憶體不夠", "zh-Hant", "zh-Hans"))
        assertEquals("我们用鼠标点击信息", convert("我們用滑鼠點選資訊", "zh-TW", "zh-CN"))
        // what is not Chinese passes through
        assertEquals("ZQX1 說：OK!", convert("ZQX1 说：OK!", "zh-Hans", "zh-Hant"))
    }

    @Test
    fun onlyThePairOfScriptsIsConverted() {
        assertTrue(ChineseScript.converts("zh-Hans", "zh-TW"))
        assertTrue(ChineseScript.converts("zh-HK", "zh"))
        assertFalse(ChineseScript.converts("zh-TW", "zh-Hant"))
        assertFalse(ChineseScript.converts("ja", "zh-Hant"))
        assertNull(ChineseScriptProvider().route("en", "zh-Hans"))
    }

    @Test
    fun theScriptIsToldByTheCharactersWrittenOnlyOneWay() {
        assertEquals("zh-Hans", ChineseScript.detect("他们说这是对的"))
        assertEquals("zh-Hant", ChineseScript.detect("他們說這是對的"))
        // a name in the other script does not decide it
        assertEquals("zh-Hant", ChineseScript.detect("張偉說：「我們來這裡找刘」"))
        assertNull(ChineseScript.detect("山中有人"))
        assertNull(ChineseScript.detect("他們說", minimum = 3))
        assertTrue(ChineseScript.unspecified("zh") && ChineseScript.unspecified("ZH "))
        assertFalse(ChineseScript.unspecified("zh-TW") || ChineseScript.unspecified("zh-Hans"))
    }

    @Test
    fun aChapterIsConvertedOnTheDeviceWhateverServiceIsChosen() = runBlocking<Unit> {
        val translation = reader()
        // a paid service with no key: the conversion needs neither
        translation.setProvider(TranslationProviderKind.DEEPL)
        assertTrue(translation.supports("zh-Hans", "zh-Hant"))
        assertTrue(translation.missingModels("zh-Hans", "zh-Hant").isEmpty())
        assertFalse(translation.systemLanguagesMissing("zh-Hans", "zh-Hant"))
        val request = ChapterTranslationRequest("work", "c1", "第一章\n他说这里很好", from = "zh-Hans", to = "zh-Hant")
        val result = translation.translate(request) { _, _ -> }
        assertTrue(result.complete)
        assertEquals("第一章\n他說這裡很好", result.text())
        assertEquals(setOf("c1"), translation.translatedChapterKeys("work", "zh-Hant"))
        assertEquals(result.text(), translation.stored(request)?.text())
        // drawn manga pages are keyed by the conversion, not by the chosen service
        assertEquals(translation.engineIdentity("zh-Hans", "zh-Hant"), translation.engineIdentity("zh-CN", "zh-TW"))
        assertTrue(translation.engineIdentity("zh-Hans", "zh-Hant") != translation.engineIdentity())
        // and nothing is counted against the paid daily limit
        assertEquals(0L, translation.preferences().charactersUsedToday)
    }
}
