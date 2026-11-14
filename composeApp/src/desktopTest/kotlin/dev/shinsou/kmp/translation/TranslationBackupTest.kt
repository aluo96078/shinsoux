package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.backup.BackupFormatException
import dev.shinsou.kmp.backup.SnapshotBackupService
import dev.shinsou.kmp.data.AppSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationBackupTest {
    private class Marking(override val id: String, override val paid: Boolean) : TranslationProvider {
        val sent = ArrayList<String>()
        override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "$id:1"))
        override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
            sent += segments
            return segments.map { "$id($it)" }
        }
    }

    private fun device(): Pair<TranslationMemoryStore, TranslationService> {
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        var clock = 1_000L
        return store to TranslationService(store, now = { clock++ })
    }

    private fun request(text: String, chapter: String, exportable: Boolean = true) =
        ChapterTranslationRequest("work", chapter, text, "ja", "zh-Hant", exportable = exportable)

    private suspend fun populate(store: TranslationMemoryStore, service: TranslationService) {
        store.saveWorkSettings("work", WorkTranslationSettings(true, "ja", "zh-Hant", TranslationDisplayMode.BILINGUAL))
        store.putGlossaryTerm("work", "zh-Hant", "葵", "小葵")
        store.putGlossaryTerm("work", "zh-Hant", "健太", "阿健")
        service.translate(request("葵は寝た\n空が青い", "free-chapter"), Marking("free", paid = false))
        service.translate(request("健太は起きた", "paid-chapter"), Marking("paid", paid = true))
    }

    @Test
    fun glossariesAndSettingsAlwaysTravelAndStoredTranslationsFollowTheTwoSwitches() = runBlocking<Unit> {
        val (store, service) = device()
        populate(store, service)

        val neither = store.exportBackup(includePaid = false, includeFree = false, segmentByteBudget = Long.MAX_VALUE)
        assertEquals(listOf("葵", "健太"), neither.glossaries.single().terms.map { it.source })
        assertEquals("BILINGUAL", neither.works.single().mode)
        assertTrue(neither.segments.isEmpty() && neither.chapters.isEmpty())

        val paidOnly = store.exportBackup(includePaid = true, includeFree = false, segmentByteBudget = Long.MAX_VALUE)
        assertTrue(paidOnly.segments.isNotEmpty() && paidOnly.segments.all { it.paid })
        assertEquals(listOf("paid-chapter"), paidOnly.chapters.map { it.chapterKey })

        val both = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE)
        assertEquals(3, both.segments.size)
        assertEquals(setOf("free-chapter", "paid-chapter"), both.chapters.map { it.chapterKey }.toSet())
        assertFalse(both.segmentsTruncated)

        val tight = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = 30)
        assertTrue(tight.segmentsTruncated && tight.segments.size < 3)
        assertTrue(tight.chapters.none { it.complete }, "a chapter that may have lost lines is checked again")
    }

    @Test
    fun aRestoredBackupReadsBackWithoutTranslatingAgainAndWithTheSameNames() = runBlocking<Unit> {
        val (store, service) = device()
        populate(store, service)
        val envelope = SnapshotBackupService.create(AppSnapshot(), createdAt = 5).copy(
            translations = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE),
        )
        // Through the file format, as a real backup goes.
        val decoded = SnapshotBackupService.decode(SnapshotBackupService.encode(envelope))

        val (other, otherService) = device()
        other.importBackup(decoded.translations!!, now = 9)

        assertEquals(WorkTranslationSettings(true, "ja", "zh-Hant", TranslationDisplayMode.BILINGUAL), other.workSettings("work"))
        val free = Marking("free", paid = false)
        val restored = otherService.translate(request("葵は寝た\n空が青い", "free-chapter"), free)
        assertTrue(free.sent.isEmpty(), "nothing is translated again: ${free.sent}")
        assertEquals(listOf("free(小葵は寝た)", "free(空が青い)"), restored.segments.map { it.translation })
        val paid = Marking("paid", paid = true)
        assertEquals("paid(阿健は起きた)", otherService.translate(request("健太は起きた", "paid-chapter"), paid).segments.single().translation)
        assertTrue(paid.sent.isEmpty())
        // New terms continue after the restored ids, so codes in stored lines stay unambiguous.
        assertEquals(3, other.putGlossaryTerm("work", "zh-Hant", "蓮", "小蓮").id)
    }

    @Test
    fun aGlossaryFromABackupReplacesTheLocalOneAndLocalLinesCachedUnderItAreNotMisread() = runBlocking<Unit> {
        val (source, sourceService) = device()
        source.putGlossaryTerm("work", "zh-Hant", "健太", "阿健") // id 1 on the device that made the backup
        sourceService.translate(request("健太は起きた", "c1"), Marking("free", paid = false))
        val backup = source.exportBackup(includePaid = false, includeFree = false, segmentByteBudget = 0)

        val (local, localService) = device()
        local.putGlossaryTerm("work", "zh-Hant", "葵", "小葵") // id 1 here means another name
        val provider = Marking("free", paid = false)
        assertEquals("free(小葵は寝た)", localService.translate(request("葵は寝た", "c2"), provider).segments.single().translation)

        local.importBackup(backup, now = 9)

        assertEquals(listOf("健太"), local.glossary("work", "zh-Hant").map { it.source })
        provider.sent.clear()
        // "葵" is no longer a term: the line cached as "N1X..." must not come back as 阿健.
        val after = localService.translate(request("葵は寝た", "c2"), provider)
        assertEquals("free(葵は寝た)", after.segments.single().translation)
        assertEquals(listOf("葵は寝た"), provider.sent)
    }

    @Test
    fun translationsOfContentThatMayNotBeExportedStayOutOfBackups() = runBlocking<Unit> {
        val (store, service) = device()
        val provider = Marking("free", paid = false)
        service.translate(request("秘密の章\n空が青い", "protected", exportable = false), provider)
        assertTrue(store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE).segments.isEmpty())

        // The same line read in content that may be exported is allowed out; the protected one is not.
        service.translate(request("空が青い", "open", exportable = true), provider)
        val backup = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE)
        assertEquals(listOf("free(空が青い)"), backup.segments.map { it.text })

        // A grant that lost its export right takes the translation out again the next time it is read.
        service.translate(request("空が青い", "open", exportable = false), provider)
        assertTrue(store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE).segments.isEmpty())
    }

    @Test
    fun aGlossaryThatWasEmptiedIsRestoredAsEmpty() = runBlocking<Unit> {
        val (source, _) = device()
        val term = source.putGlossaryTerm("work.with.dots", "zh-Hant", "葵", "小葵")
        source.removeGlossaryTerm("work.with.dots", "zh-Hant", term.id)
        val backup = source.exportBackup(includePaid = false, includeFree = false, segmentByteBudget = 0)
        assertEquals(TranslationBackup.Glossary("work.with.dots", "zh-Hant", backup.glossaries.single().salt, nextId = 2, terms = emptyList()), backup.glossaries.single())

        val (other, _) = device()
        other.putGlossaryTerm("work.with.dots", "zh-Hant", "葵", "舊譯名")
        other.importBackup(backup.validate(), now = 9)
        assertTrue(other.glossary("work.with.dots", "zh-Hant").isEmpty())
    }

    @Test
    fun theBudgetCountsWhatARowCostsInTheFileNotOnlyItsText() = runBlocking<Unit> {
        val (store, service) = device()
        service.translate(request((1..200).joinToString("\n") { "行$it" }, "long"), Marking("free", paid = false))
        val budget = 20_000L
        val backup = store.exportBackup(includePaid = false, includeFree = true, segmentByteBudget = budget)
        assertTrue(backup.segmentsTruncated)
        val file = SnapshotBackupService.encode(SnapshotBackupService.create(AppSnapshot(), createdAt = 5).copy(translations = backup.copy(chapters = emptyList())))
        val empty = SnapshotBackupService.encode(SnapshotBackupService.create(AppSnapshot(), createdAt = 5))
        val size = file.encodeToByteArray().size - empty.encodeToByteArray().size
        assertTrue(size <= budget, "the segments must fit the budget in the file: $size bytes for ${backup.segments.size} rows")
        assertTrue(size >= budget / 2, "and the estimate must not waste most of it: $size")
    }

    @Test
    fun aRestoredChapterIsListedByNameAndCanBeRemovedWithoutOpeningItAgain() = runBlocking<Unit> {
        val (store, service) = device()
        val provider = Marking("paid", paid = true)
        service.translate(request("空が青い\n雨が降る", "c1").copy(labels = TranslationLabels("作品甲", "第一話", "來源")), provider)
        val backup = SnapshotBackupService.decode(
            SnapshotBackupService.encode(
                SnapshotBackupService.create(AppSnapshot(), createdAt = 5).copy(
                    translations = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE),
                ),
            ),
        ).translations!!
        assertEquals(2, backup.chapters.single().segmentIndexes.size)

        val (other, _) = device()
        other.importBackup(backup, now = 9)
        val listed = other.storedChapters(10).single()
        assertEquals(listOf("作品甲", "第一話", "來源"), listOf(listed.workTitle, listed.chapterTitle, listed.sourceName))
        assertTrue(other.paidBytes() > 0)
        assertTrue(other.deleteWorkTranslations("work") > 0)
        assertEquals(0L, other.paidBytes())
        assertTrue(other.storedChapters(10).isEmpty())
    }

    @Test
    fun backupsWithoutTranslationDataStillRestoreAndDamagedTranslationDataIsRefused() {
        val plain = SnapshotBackupService.encode(SnapshotBackupService.create(AppSnapshot(), createdAt = 5))
        assertFalse("translations\":{" in plain)
        assertNull(SnapshotBackupService.decode(plain).translations)

        val broken = SnapshotBackupService.create(AppSnapshot(), createdAt = 5).copy(
            translations = TranslationBackup(
                glossaries = listOf(TranslationBackup.Glossary("work", "zh-Hant", salt = "s", nextId = 2, terms = listOf(TranslationBackup.Term(5, "葵", "小葵")))),
            ),
        )
        assertFailsWith<BackupFormatException> { broken.validate() }
    }
}
