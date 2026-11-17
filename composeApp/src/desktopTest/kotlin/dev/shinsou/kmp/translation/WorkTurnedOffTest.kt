package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTurnedOffTest {
    @Test
    fun onlyAWorkSwitchedOffStaysOff() {
        assertTrue(WorkTranslationSettings.startsByItself(null))
        // saved while off for another reason: a language picked, the display changed
        assertTrue(WorkTranslationSettings.startsByItself(WorkTranslationSettings(false, "ja", "zh-Hans")))
        assertFalse(WorkTranslationSettings.startsByItself(WorkTranslationSettings(false, "ja", "zh-Hant", turnedOff = true)))
        assertFalse(WorkTranslationSettings.startsByItself(WorkTranslationSettings(true, "ja", "zh-Hant")))
    }

    @Test
    fun theMarkIsStoredAndASettingsTableFromBeforeItGainsIt() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE tm_work(work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, mode TEXT NOT NULL) WITHOUT ROWID",
            0,
        )
        driver.execute(null, "INSERT INTO tm_work VALUES ('old', 0, 'ja', 'zh-Hant', 'TRANSLATION')", 0)
        val store = TranslationMemoryStore(driver)
        assertEquals(WorkTranslationSettings(false, "ja", "zh-Hant"), store.workSettings("old"))

        store.saveWorkSettings("off", WorkTranslationSettings(false, "ja", "zh-Hant", turnedOff = true))
        assertTrue(store.workSettings("off")!!.turnedOff)
        assertTrue(store.exportBackup(includePaid = false, includeFree = false, segmentByteBudget = 0).works.single { it.workKey == "off" }.turnedOff)
    }

    @Test
    fun aPickedSourceLanguageIsStoredAndOneFromBeforeTheMarkOtherThanJapaneseCountsAsPicked() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE tm_work(work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, mode TEXT NOT NULL) WITHOUT ROWID",
            0,
        )
        driver.execute(null, "INSERT INTO tm_work VALUES ('ja', 0, 'ja', 'zh-Hant', 'TRANSLATION')", 0)
        driver.execute(null, "INSERT INTO tm_work VALUES ('en', 0, 'en', 'zh-Hant', 'TRANSLATION')", 0)
        val store = TranslationMemoryStore(driver)
        assertFalse(store.workSettings("ja")!!.fromPicked)
        assertTrue(store.workSettings("en")!!.fromPicked)

        store.saveWorkSettings("picked", WorkTranslationSettings(false, "ja", "zh-Hant", fromPicked = true))
        assertTrue(store.workSettings("picked")!!.fromPicked)
        val backup = store.exportBackup(includePaid = false, includeFree = false, segmentByteBudget = 0)
        assertEquals(true, backup.works.single { it.workKey == "picked" }.fromPicked)

        // a backup from before the mark: as the migration reads it
        val restored = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        restored.importBackup(
            TranslationBackup(works = listOf(TranslationBackup.Work("a", false, "ja", "zh-Hant", "TRANSLATION"), TranslationBackup.Work("b", false, "en", "zh-Hant", "TRANSLATION"))),
            now = 0,
        )
        assertFalse(restored.workSettings("a")!!.fromPicked)
        assertTrue(restored.workSettings("b")!!.fromPicked)
    }

    @Test
    fun theToolbarButtonShowsTheTranslationKeptBehindTheOriginal() {
        val original = WorkTranslationSettings(true, "ja", "zh-Hant", mode = TranslationDisplayMode.ORIGINAL)
        assertFalse(original.shown)
        assertEquals(WorkTranslationSettings(true, "ja", "zh-Hant", mode = TranslationDisplayMode.TRANSLATION), original.toggled())
        val bilingual = WorkTranslationSettings(true, "ja", "zh-Hant", mode = TranslationDisplayMode.BILINGUAL)
        assertTrue(bilingual.shown)
        assertEquals(bilingual.copy(enabled = false), bilingual.toggled())
        assertEquals(bilingual, bilingual.copy(enabled = false).toggled())
    }
}
