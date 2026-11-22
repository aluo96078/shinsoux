package dev.shinsou.kmp.translation

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.shinsou.kmp.content.ContentCursorRow
import dev.shinsou.kmp.content.ContentRow
import dev.shinsou.kmp.content.SchemaComponent
import dev.shinsou.kmp.content.SchemaMigrator
import dev.shinsou.kmp.content.columnExists
import dev.shinsou.kmp.content.executeStatement
import dev.shinsou.kmp.content.executeUncached
import dev.shinsou.kmp.content.queryStatement
import dev.shinsou.kmp.content.tableExists
import dev.shinsou.kmp.domain.model.TranslationBackup
import dev.shinsou.kmp.translation.sql.TranslationMemoryDatabase
import dev.shinsou.kmp.translation.sql.TranslationMemoryQueries
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One stored translation of one segment through one hop. */
public data class MemoryKey(
    /** [TranslationText.hash] of the text that was sent to the engine. */
    val sourceHash: String,
    val from: String,
    val to: String,
    val engine: String,
)

public data class ChapterRecord(
    val workKey: String,
    val chapterKey: String,
    val to: String,
    val provider: String,
    /** Hash of the whole source text: a chapter the source site revised is no longer "translated". */
    val sourceHash: String,
    val segmentCount: Int,
    val complete: Boolean,
    /** Whether the provider's translations are exempt from eviction. */
    val paid: Boolean,
    val updatedAt: Long,
)

/**
 * The translation memory (ADR-0004 §4.2) in the shared content database. Translations are keyed
 * by what was translated, never by chapter id: a chapter downloaded again, read from another
 * source or partly revised only costs its changed lines. Its tables have their own format version
 * ([TranslationMemorySchema]).
 */
@OptIn(ExperimentalUuidApi::class)
public class TranslationMemoryStore(private val driver: SqlDriver) {
    private val transactions = object : TransacterImpl(driver) {}

    /** The fixed statements (TranslationMemory.sq); the one whose text depends on the call is written below. */
    private val queries = TranslationMemoryQueries(driver)

    init {
        SchemaMigrator.migrate(driver, TranslationMemorySchema.component)
    }

    public fun <T> transaction(block: () -> T): T = transactions.transactionWithResult(noEnclosing = false) { block() }

    // ---- segments ---------------------------------------------------------------------------

    /**
     * The stored translations among [hashes] for one hop; marks them as used at [now].
     * [exportable] says whether the content being read may leave the device in a backup right
     * now. The latest reading decides for the translations it uses: a grant that lost its
     * export right takes them out of backups again.
     */
    public fun lookup(
        hashes: Collection<String>,
        from: String,
        to: String,
        engine: String,
        now: Long,
        exportable: Boolean = false,
    ): Map<String, String> {
        if (hashes.isEmpty()) return emptyMap()
        val found = HashMap<String, String>()
        transaction {
            hashes.distinct().chunked(LOOKUP_CHUNK).forEach { chunk ->
                queries.segmentsIn(from, to, engine, chunk) { hash, text -> hash to text }
                    .executeAsList()
                    .forEach { (hash, text) -> found[hash] = text }
                queries.markSegmentsUsed(now, if (exportable) 1 else 0, from, to, engine, chunk).value
            }
        }
        return found
    }

    /**
     * Stores translations. [paid] entries are never evicted by [evictFree]. Only [exportable]
     * ones (made from content whose grant allows export) are ever written to a backup.
     */
    public fun store(entries: Map<MemoryKey, String>, paid: Boolean, now: Long, exportable: Boolean = false) {
        if (entries.isEmpty()) return
        transaction {
            for ((key, text) in entries) {
                val bytes = text.encodeToByteArray().size.toLong()
                // Two statements rather than an upsert (Android 10's SQLite has none). A translation
                // the user paid for is never replaced by a free one, nor turned into one.
                val paidFlag = if (paid) 1L else 0L
                queries.insertSegmentIfAbsent(key.sourceHash, key.from, key.to, key.engine, text, paidFlag, bytes, now).value
                queries.setSegmentExportable(if (exportable) 1 else 0, key.sourceHash, key.from, key.to, key.engine).value
                queries.replaceSegmentUnlessPaid(
                    text = text,
                    paid = paidFlag,
                    bytes = bytes,
                    now = now,
                    sourceHash = key.sourceHash,
                    from = key.from,
                    to = key.to,
                    engine = key.engine,
                ).value
            }
        }
    }

    /** Bytes of stored translations that cost nothing to produce again. */
    public fun freeBytes(): Long = queries.freeBytes().executeAsList().first()

    public fun paidBytes(): Long = queries.paidBytes().executeAsList().first()

    /**
     * Drops the least recently used free translations until at most [maximumBytes] remain.
     * Translations the user paid for are kept whatever their age.
     */
    public fun evictFree(maximumBytes: Long): Int {
        var excess = freeBytes() - maximumBytes
        if (excess <= 0) return 0
        var removed = 0
        transaction {
            while (excess > 0) {
                val batch = queries.evictionBatch { sourceHash, from, to, engine, bytes ->
                    MemoryKey(sourceHash, from, to, engine) to bytes
                }.executeAsList()
                if (batch.isEmpty()) break
                for ((key, bytes) in batch) {
                    if (excess <= 0) break
                    queries.deleteSegment(key.sourceHash, key.from, key.to, key.engine).value
                    excess -= bytes
                    removed++
                }
            }
            // Which chapters lost a line is not recorded: every chapter of a free provider is
            // checked again (and marked complete again, if it still is) the next time it is opened.
            if (removed > 0) queries.markFreeChaptersIncomplete().value
        }
        return removed
    }

    // ---- chapters ---------------------------------------------------------------------------

    /**
     * [record] as the chapter's, replacing the one kept; with [ifAbsent], only where none is
     * (a part of a chapter must not undo the whole chapter's record).
     */
    public fun saveChapter(record: ChapterRecord, ifAbsent: Boolean = false) {
        val write = if (ifAbsent) queries::insertChapterIfAbsent else queries::replaceChapter
        write(
            record.workKey, record.chapterKey, record.to, record.provider, record.sourceHash, record.segmentCount.toLong(),
            if (record.complete) 1 else 0, if (record.paid) 1 else 0, record.updatedAt,
        ).value
    }

    public fun chapters(workKey: String, to: String): List<ChapterRecord> =
        queries.chapters(workKey, to, ::chapterRecord).executeAsList()

    /** Remembers what the reader calls a work and one of its chapters. */
    public fun saveLabels(workKey: String, chapterKey: String, labels: TranslationLabels) {
        transaction {
            queries.putWorkLabel(workKey, labels.workTitle.take(MAX_LABEL_LENGTH), labels.sourceName?.take(MAX_LABEL_LENGTH)).value
            queries.putChapterLabel(workKey, chapterKey, labels.chapterTitle.take(MAX_LABEL_LENGTH)).value
        }
    }

    /**
     * Which stored translations a chapter's translation is made of. Translations are kept by
     * what was translated, not by chapter, so removing one chapter's needs this record.
     */
    public fun saveChapterSegments(
        workKey: String, chapterKey: String, to: String, provider: String, keys: Collection<MemoryKey>,
        /** Adds [keys] to the lines the chapter already owns rather than replacing them. */
        adding: Boolean = false,
    ) {
        transaction {
            if (!adding) queries.deleteChapterSegments(workKey, chapterKey, to, provider).value
            for (key in keys) {
                queries.insertChapterSegment(workKey, chapterKey, to, provider, key.sourceHash, key.from, key.to, key.engine).value
            }
        }
    }

    /**
     * Removes one chapter's stored translation: its record and every stored line that no other
     * chapter's translation also uses. A chapter translated before lines were tracked only loses
     * its record. Returns the bytes of translation removed.
     */
    public fun deleteChapterTranslation(workKey: String, chapterKey: String, to: String, provider: String): Long = transaction {
        val before = freeBytes() + paidBytes()
        queries.deleteLinesOnlyChapterUses(workKey = workKey, chapterKey = chapterKey, to = to, provider = provider).value
        queries.deleteChapterSegments(workKey, chapterKey, to, provider).value
        queries.deleteChapter(workKey, chapterKey, to, provider).value
        // Names go with the last record that used them.
        queries.deleteUnusedChapterLabel(workKey, chapterKey).value
        queries.deleteUnusedWorkLabel(workKey).value
        before - (freeBytes() + paidBytes())
    }

    /** Removes every stored chapter translation of a work; its glossary and settings stay. */
    public fun deleteWorkTranslations(workKey: String): Long = transaction {
        queries.workChapters(workKey) { chapter, to, provider -> Triple(chapter, to, provider) }
            .executeAsList()
            .sumOf { (chapter, to, provider) -> deleteChapterTranslation(workKey, chapter, to, provider) }
    }

    /** Chapters with a stored translation, most recently translated first. */
    public fun storedChapters(limit: Int): List<StoredChapterTranslation> = queries.storedChapters(limit.toLong()) {
            workKey, chapterKey, to, provider, segmentCount, complete, paid, updatedAt, workTitle, chapterTitle, source ->
        StoredChapterTranslation(
            workKey = workKey,
            chapterKey = chapterKey,
            to = to,
            provider = provider,
            lineCount = segmentCount.toInt(),
            complete = complete == 1L,
            paid = paid == 1L,
            updatedAt = updatedAt,
            workTitle = workTitle,
            chapterTitle = chapterTitle,
            sourceName = source,
        )
    }.executeAsList()

    /** Chapters of [workKey] with a complete translation; [to] and [provider] narrow it when given. */
    /**
     * With [engine], a chapter counts only if none of its recorded lines came from another
     * engine (chapters recorded before lines were tracked count as they are).
     */
    public fun completeChapterKeys(
        workKey: String, to: String?, provider: String?, engine: String? = null,
        /**
         * The languages the hops of the pair start from, the chapter's own first: only a chapter
         * whose lines were translated from these, and from the first among them, is counted.
         */
        hopsFrom: List<String>? = null,
    ): Set<String> {
        val own = "SELECT 1 FROM tm_chapter_segment s WHERE s.work_key = c.work_key AND s.chapter_key = c.chapter_key " +
            "AND s.chapter_to = c.to_lang AND s.provider = c.provider"
        val languages = if (hopsFrom.isNullOrEmpty()) "" else
            // a chapter from before its lines were recorded cannot tell: it is counted, as it was
            " AND (NOT EXISTS ($own) OR EXISTS ($own AND s.from_lang = ?) AND NOT EXISTS ($own AND s.from_lang NOT IN (${hopsFrom.joinToString(", ") { "?" }})))"
        return namedQuery(
            "SELECT DISTINCT chapter_key FROM tm_chapter c WHERE work_key = ? AND complete = 1 AND segment_count > 0 " +
                "AND (? IS NULL OR to_lang = ?) AND (? IS NULL OR provider = ?) " +
                "AND (? IS NULL OR NOT EXISTS ($own AND s.engine <> ?))" + languages,
            "tm_chapter",
            7 + if (hopsFrom.isNullOrEmpty()) 0 else 1 + hopsFrom.size,
            {
                bindString(0, workKey); bindString(1, to); bindString(2, to); bindString(3, provider); bindString(4, provider)
                bindString(5, engine); bindString(6, engine)
                if (!hopsFrom.isNullOrEmpty()) {
                    bindString(7, hopsFrom.first())
                    hopsFrom.forEachIndexed { i, language -> bindString(8 + i, language) }
                }
            },
        ) { it.string("chapter_key") }.toHashSet()
    }

    /** Forgets a work's chapter records and glossary. Segment translations stay: they may serve another source of the same text. */
    public fun forgetWork(workKey: String) {
        transaction {
            queries.forgetWorkChapters(workKey).value
            queries.forgetWorkGlossary(workKey).value
            queries.forgetWorkSettings(workKey).value
            queries.forgetWorkLabels(workKey).value
            queries.forgetWorkChapterSegments(workKey).value
        }
    }

    // ---- device preferences -----------------------------------------------------------------

    /** A device-wide translation preference (the chosen provider, the daily limit, today's usage). */
    public fun preference(name: String): String? = meta("pref.$name")

    public fun setPreference(name: String, value: String) {
        setMeta("pref.$name", value)
    }

    // ---- per-work settings ------------------------------------------------------------------

    public fun workSettings(workKey: String): WorkTranslationSettings? =
        queries.workSettings(workKey) { enabled, from, to, mode, turnedOff, fromPicked ->
            WorkTranslationSettings(
                enabled = enabled == 1L,
                from = from,
                to = to,
                mode = TranslationDisplayMode.entries.firstOrNull { it.name == mode } ?: TranslationDisplayMode.TRANSLATION,
                turnedOff = turnedOff == 1L,
                fromPicked = fromPicked == 1L,
            )
        }.executeAsList().firstOrNull()

    public fun saveWorkSettings(workKey: String, settings: WorkTranslationSettings) {
        queries.putWorkSettings(
            workKey, if (settings.enabled) 1 else 0, settings.from, settings.to, settings.mode.name,
            if (settings.turnedOff) 1 else 0, if (settings.fromPicked) 1 else 0,
        ).value
    }

    // ---- glossary ---------------------------------------------------------------------------

    public fun glossary(workKey: String, to: String): List<GlossaryEntry> =
        queries.glossary(workKey, to) { id, source, target -> GlossaryEntry(id.toInt(), source, target) }.executeAsList()

    /**
     * Adds a term or changes its rendering. An existing source term keeps its id (and so its code
     * in stored translations); a new one gets the next id, never one that was used before.
     */
    public fun putGlossaryTerm(workKey: String, to: String, source: String, target: String): GlossaryEntry = transaction {
        val term = source.trim()
        require(term.isNotEmpty()) { "Glossary source term is blank" }
        val existing = queries.glossaryTermId(workKey, to, term).executeAsList().firstOrNull()?.toInt()
        val id = existing ?: run {
            val next = (meta("glossary.next.$workKey.$to")?.toIntOrNull() ?: 1)
            require(next <= GlossaryEntry.MAX_ID) { "This work's glossary is full" }
            setMeta("glossary.next.$workKey.$to", (next + 1).toString())
            next
        }
        queries.putGlossaryTerm(workKey, to, id.toLong(), term, target.trim()).value
        GlossaryEntry(id, term, target.trim())
    }

    public fun removeGlossaryTerm(workKey: String, to: String, id: Int) {
        queries.removeGlossaryTerm(workKey, to, id.toLong()).value
    }

    /**
     * What stored translations of this glossary's lines are keyed by. A glossary restored from a
     * backup brings its own: lines cached under another glossary's codes can then never be read
     * back with the wrong names.
     */
    public fun glossarySalt(workKey: String, to: String): String = transaction {
        meta("glossary.salt.$workKey.$to") ?: Uuid.random().toString().also { setMeta("glossary.salt.$workKey.$to", it) }
    }

    // ---- backup -----------------------------------------------------------------------------

    /**
     * Everything a backup carries. Stored translations go newest first until [segmentByteBudget]
     * of backup file is used up; a chapter record is only included when its provider's
     * translations are. Translations of content whose grant does not allow export stay out.
     */
    public fun exportBackup(includePaid: Boolean, includeFree: Boolean, segmentByteBudget: Long): TranslationBackup = transaction {
        val works = exportWorks()
        val glossaries = exportGlossaries()
        val wanted = buildList { if (includePaid) add(1L); if (includeFree) add(0L) }
        val (segments, truncated) = exportSegments(wanted, segmentByteBudget)
        val chapters = exportChapters(wanted, segments, truncated)
        TranslationBackup(works, glossaries, chapters, segments, truncated)
    }

    private fun exportWorks(): List<TranslationBackup.Work> =
        queries.allWorkSettings { workKey, enabled, from, to, mode, turnedOff, fromPicked ->
            TranslationBackup.Work(workKey, enabled == 1L, from, to, mode, turnedOff == 1L, fromPicked == 1L)
        }.executeAsList()

    private fun exportGlossaries(): List<TranslationBackup.Glossary> {
        val terms = queries.allGlossaryTerms { workKey, to, id, source, target ->
            Triple(workKey, to, TranslationBackup.Term(id.toInt(), source, target))
        }.executeAsList()
        val byScope = terms.groupBy { it.first to it.second }
        // A glossary that was emptied is still a glossary: restoring it must clear the old names.
        val emptied = queries.glossaryCounterKeys().executeAsList()
            .map { it.removePrefix("glossary.next.") }
            .filter { '.' in it }
            .map { it.substringBeforeLast('.') to it.substringAfterLast('.') }
            .filter { it !in byScope }
        return (byScope.keys + emptied).map { scope ->
            val (workKey, to) = scope
            val rows = byScope[scope].orEmpty()
            TranslationBackup.Glossary(
                workKey = workKey,
                to = to,
                salt = glossarySalt(workKey, to),
                nextId = meta("glossary.next.$workKey.$to")?.toIntOrNull() ?: ((rows.maxOfOrNull { it.third.id } ?: 0) + 1),
                terms = rows.map { it.third },
            )
        }
    }

    /**
     * The stored translations of the [wanted] kinds (paid first), newest first, until
     * [segmentByteBudget] of backup file is used up; and whether some were left out.
     */
    private fun exportSegments(wanted: List<Long>, segmentByteBudget: Long): Pair<List<TranslationBackup.Segment>, Boolean> {
        val segments = ArrayList<TranslationBackup.Segment>()
        var truncated = false
        var used = 0L
        for (paid in wanted) {
            if (truncated) break
            // Row by row, stopping at the budget: paid translations are never evicted, and
            // reading all of them just to drop most would defeat the bound.
            val rows = queries.exportableSegments(paid) { sourceHash, from, to, engine, text, bytes ->
                TranslationBackup.Segment(sourceHash, from, to, engine, text, paid == 1L) to bytes
            }
            rows.execute { cursor ->
                while (cursor.next().value) {
                    val (segment, bytes) = rows.mapper(cursor)
                    // What the row costs in the file: its text (escapes included) and its keys.
                    val cost = bytes + segment.text.count { it == '"' || it == '\\' || it < ' ' } * 5L +
                        segment.from.length + segment.to.length + segment.engine.length + BACKUP_ROW_OVERHEAD_BYTES
                    if (used + cost > segmentByteBudget) {
                        truncated = true
                        break
                    }
                    segments += segment
                    used += cost
                }
                QueryResult.Value(Unit)
            }.value
        }
        return segments to truncated
    }

    /** The chapter records of the [wanted] kinds, each with its lines as indexes into [segments]. */
    private fun exportChapters(wanted: List<Long>, segments: List<TranslationBackup.Segment>, truncated: Boolean): List<TranslationBackup.Chapter> {
        val segmentIndex = HashMap<MemoryKey, Int>(segments.size * 2)
        segments.forEachIndexed { index, segment -> segmentIndex[MemoryKey(segment.sourceHash, segment.from, segment.to, segment.engine)] = index }
        return if (wanted.isEmpty()) emptyList() else queries.allChapters(::chapterRecord).executeAsList().filter { (if (it.paid) 1L else 0L) in wanted }.map {
            // Whether it is still complete is checked when the chapter is opened.
            val lines = queries.chapterLines(it.workKey, it.chapterKey, it.to, it.provider, ::MemoryKey)
                .executeAsList()
                .mapNotNull(segmentIndex::get)
                .sorted()
            val names = queries.chapterLabels(it.workKey, it.chapterKey) { chapterKey, title, source ->
                Triple(chapterKey, title, source)
            }.executeAsList()
            val work = names.firstOrNull { name -> name.first.isEmpty() }
            TranslationBackup.Chapter(
                it.workKey, it.chapterKey, it.to, it.provider, it.sourceHash, it.segmentCount, it.complete && !truncated, it.paid, it.updatedAt,
                segmentIndexes = lines,
                workTitle = work?.second,
                chapterTitle = names.firstOrNull { name -> name.first == it.chapterKey && name.first.isNotEmpty() }?.second,
                sourceName = work?.third,
            )
        }
    }

    /**
     * Applies a backup with the app's restore semantics: a work's settings and each glossary in
     * the backup replace what is here; stored translations are added (a paid one is never
     * replaced by a free one).
     */
    public fun importBackup(backup: TranslationBackup, now: Long) {
        backup.validate()
        transaction {
            for (work in backup.works) {
                val mode = TranslationDisplayMode.entries.firstOrNull { it.name == work.mode } ?: TranslationDisplayMode.TRANSLATION
                saveWorkSettings(work.workKey, WorkTranslationSettings(work.enabled, work.from, work.to, mode, work.turnedOff, work.fromPicked ?: (work.from != "ja")))
            }
            for (glossary in backup.glossaries) {
                queries.clearGlossary(glossary.workKey, glossary.to).value
                setMeta("glossary.salt.${glossary.workKey}.${glossary.to}", glossary.salt)
                setMeta("glossary.next.${glossary.workKey}.${glossary.to}", glossary.nextId.toString())
                for (term in glossary.terms) {
                    queries.putGlossaryTerm(glossary.workKey, glossary.to, term.id.toLong(), term.source.trim(), term.target.trim()).value
                }
            }
            backup.chapters.forEach {
                saveChapter(ChapterRecord(it.workKey, it.chapterKey, it.to, it.provider, it.sourceHash, it.segmentCount, it.complete, it.paid, it.updatedAt))
                // Which lines the chapter uses and what it is called: listed and removable without opening it again.
                if (it.segmentIndexes.isNotEmpty()) {
                    saveChapterSegments(
                        it.workKey, it.chapterKey, it.to, it.provider,
                        it.segmentIndexes.map { index -> backup.segments[index].let { s -> MemoryKey(s.sourceHash, s.from, s.to, s.engine) } },
                    )
                }
                val workTitle = it.workTitle
                val chapterTitle = it.chapterTitle
                if (workTitle != null && chapterTitle != null) {
                    saveLabels(it.workKey, it.chapterKey, TranslationLabels(workTitle, chapterTitle, it.sourceName))
                }
            }
            for ((paid, group) in backup.segments.groupBy { it.paid }) {
                // A backup only ever holds translations that were allowed out.
                store(group.associate { MemoryKey(it.sourceHash, it.from, it.to, it.engine) to it.text }, paid, now, exportable = true)
            }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun meta(key: String): String? = queries.meta(key).executeAsList().firstOrNull()

    private fun setMeta(key: String, value: String) {
        queries.putMeta(key, value).value
    }

    @Suppress("LongParameterList") // The columns of a tm_chapter row, as the generated statements hand them over.
    private fun chapterRecord(
        workKey: String,
        chapterKey: String,
        to: String,
        provider: String,
        sourceHash: String,
        segmentCount: Long,
        complete: Long,
        paid: Long,
        updatedAt: Long,
    ) = ChapterRecord(workKey, chapterKey, to, provider, sourceHash, segmentCount.toInt(), complete == 1L, paid == 1L, updatedAt)

    private fun <T> query(
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
        map: (SqlCursor) -> T,
    ): List<T> = driver.queryStatement(
        sql = sql,
        mapper = { cursor ->
            val rows = ArrayList<T>()
            while (cursor.next().value) rows += map(cursor)
            QueryResult.Value(rows)
        },
        parameters = parameters,
        binders = binders,
    ).value

    /**
     * [query], with each row read by column name instead of by position. The names are those of
     * [sql]'s own projection, so they cannot drift from it; [table] names the rows in errors.
     */
    private fun <T> namedQuery(
        sql: String,
        table: String,
        parameters: Int = 0,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
        map: (ContentCursorRow) -> T,
    ): List<T> {
        val columns = ContentRow.selectColumns(sql)
        return query(sql, parameters, binders) { cursor -> map(ContentCursorRow(cursor, table, columns)) }
    }

    private companion object {
        const val LOOKUP_CHUNK = 400
        const val MAX_LABEL_LENGTH = 300

        /** The hash, field names, punctuation and indentation of one stored translation in a backup file. */
        const val BACKUP_ROW_OVERHEAD_BYTES = 400L
    }
}

/**
 * The translation memory tables (R3-1). Formats 2 to 4 each added a column at the end of its table;
 * [create] makes the newest tables (TranslationMemory.sq lists those columns last), so a new
 * database and an upgraded one are the same (GeneratedSchemaMatchesMigratorTest).
 */
public object TranslationMemorySchema {
    public const val VERSION: Long = 4

    public val component: SchemaComponent = SchemaComponent(
        name = "translation_memory",
        version = VERSION,
        create = ::create,
        steps = mapOf(2L to ::addExportable, 3L to ::addTurnedOff, 4L to ::addFromPicked),
        unrecordedVersion = ::unrecordedVersion,
    )

    /** The generated DDL (TranslationMemory.sq, format 4); its own Schema version is not used. */
    private fun create(driver: SqlDriver) {
        TranslationMemoryDatabase.Schema.create(driver).value
        // A table group from before the version record may hold some tables of an older format.
        addExportable(driver)
        addTurnedOff(driver)
        addFromPicked(driver)
    }

    /** Added after the table first shipped in test builds; what was stored before stays out of backups. */
    private fun addExportable(driver: SqlDriver) {
        addColumn(driver, "tm_segment", "exportable")
    }

    /** Before it, a work switched off could not be told from one saved while off: both may start again. */
    private fun addTurnedOff(driver: SqlDriver) {
        addColumn(driver, "tm_work", "turned_off")
    }

    /** Before it, a source language other than the default Japanese was taken as the reader's choice. */
    private fun addFromPicked(driver: SqlDriver) {
        if (addColumn(driver, "tm_work", "from_picked")) {
            driver.executeStatement("UPDATE tm_work SET from_picked = 1 WHERE from_lang != 'ja'", 0).value
        }
    }

    /**
     * Adds an `INTEGER NOT NULL DEFAULT 0` column unless it is there. A missing table is made first,
     * in the newest format (the column is then there): the same table an older one upgraded to.
     */
    private fun addColumn(driver: SqlDriver, table: String, column: String): Boolean {
        if (!tableExists(driver, table)) TranslationMemoryDatabase.Schema.create(driver).value
        if (columnExists(driver, table, column)) return false
        driver.executeUncached("ALTER TABLE $table ADD COLUMN $column INTEGER NOT NULL DEFAULT 0", 0).value
        return true
    }

    /** The format of a database from before the version record, told by its columns. */
    private fun unrecordedVersion(driver: SqlDriver): Long? = when {
        !tableExists(driver, "tm_segment") -> null
        columnExists(driver, "tm_work", "from_picked") -> 4
        columnExists(driver, "tm_work", "turned_off") -> 3
        columnExists(driver, "tm_segment", "exportable") -> 2
        else -> 1
    }
}
