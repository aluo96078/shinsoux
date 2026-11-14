package dev.shinsou.kmp.translation

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
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
 * source or partly revised only costs its changed lines. Tables are created idempotently and do
 * not touch the content schema version.
 */
@OptIn(ExperimentalUuidApi::class)
public class TranslationMemoryStore(private val driver: SqlDriver) {
    private val transactions = object : TransacterImpl(driver) {}

    init {
        SCHEMA.forEach { driver.execute(null, it, 0).value }
        // Added after the table first shipped in test builds; what was stored before stays out of backups.
        val columns = query("PRAGMA table_info(tm_segment)", 0, null) { it.getString(1) }
        if ("exportable" !in columns) exec("ALTER TABLE tm_segment ADD COLUMN exportable INTEGER NOT NULL DEFAULT 0", 0, null)
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
                val marks = chunk.joinToString(",") { "?" }
                query(
                    "SELECT source_hash, text FROM tm_segment WHERE from_lang = ? AND to_lang = ? AND engine = ? AND source_hash IN ($marks)",
                    3 + chunk.size,
                    {
                        bindString(0, from); bindString(1, to); bindString(2, engine)
                        chunk.forEachIndexed { index, hash -> bindString(3 + index, hash) }
                    },
                ) { it.getString(0)!! to it.getString(1)!! }.forEach { (hash, text) -> found[hash] = text }
                exec(
                    "UPDATE tm_segment SET used_at = ?, exportable = ? " +
                        "WHERE from_lang = ? AND to_lang = ? AND engine = ? AND source_hash IN ($marks)",
                    5 + chunk.size,
                ) {
                    bindLong(0, now); bindLong(1, if (exportable) 1 else 0); bindString(2, from); bindString(3, to); bindString(4, engine)
                    chunk.forEachIndexed { index, hash -> bindString(5 + index, hash) }
                }
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
                exec(
                    "INSERT OR IGNORE INTO tm_segment(source_hash, from_lang, to_lang, engine, text, paid, bytes, used_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    8,
                ) {
                    bindString(0, key.sourceHash); bindString(1, key.from); bindString(2, key.to); bindString(3, key.engine)
                    bindString(4, text); bindLong(5, if (paid) 1 else 0); bindLong(6, bytes); bindLong(7, now)
                }
                exec("UPDATE tm_segment SET exportable = ? WHERE source_hash = ? AND from_lang = ? AND to_lang = ? AND engine = ?", 5) {
                    bindLong(0, if (exportable) 1 else 0)
                    bindString(1, key.sourceHash); bindString(2, key.from); bindString(3, key.to); bindString(4, key.engine)
                }
                exec(
                    "UPDATE tm_segment SET text = ?, paid = ?, bytes = ?, used_at = ? " +
                        "WHERE source_hash = ? AND from_lang = ? AND to_lang = ? AND engine = ? AND paid <= ?",
                    9,
                ) {
                    bindString(0, text); bindLong(1, if (paid) 1 else 0); bindLong(2, bytes); bindLong(3, now)
                    bindString(4, key.sourceHash); bindString(5, key.from); bindString(6, key.to); bindString(7, key.engine)
                    bindLong(8, if (paid) 1 else 0)
                }
            }
        }
    }

    /** Bytes of stored translations that cost nothing to produce again. */
    public fun freeBytes(): Long = query("SELECT COALESCE(SUM(bytes), 0) FROM tm_segment WHERE paid = 0", 0, null) { it.getLong(0)!! }.first()

    public fun paidBytes(): Long = query("SELECT COALESCE(SUM(bytes), 0) FROM tm_segment WHERE paid = 1", 0, null) { it.getLong(0)!! }.first()

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
                val batch = query(
                    "SELECT source_hash, from_lang, to_lang, engine, bytes FROM tm_segment WHERE paid = 0 ORDER BY used_at, source_hash LIMIT $EVICT_BATCH",
                    0,
                    null,
                ) { MemoryKey(it.getString(0)!!, it.getString(1)!!, it.getString(2)!!, it.getString(3)!!) to it.getLong(4)!! }
                if (batch.isEmpty()) break
                for ((key, bytes) in batch) {
                    if (excess <= 0) break
                    exec("DELETE FROM tm_segment WHERE source_hash = ? AND from_lang = ? AND to_lang = ? AND engine = ?", 4) {
                        bindString(0, key.sourceHash); bindString(1, key.from); bindString(2, key.to); bindString(3, key.engine)
                    }
                    excess -= bytes
                    removed++
                }
            }
            // Which chapters lost a line is not recorded: every chapter of a free provider is
            // checked again (and marked complete again, if it still is) the next time it is opened.
            if (removed > 0) exec("UPDATE tm_chapter SET complete = 0 WHERE paid = 0", 0, null)
        }
        return removed
    }

    // ---- chapters ---------------------------------------------------------------------------

    public fun saveChapter(record: ChapterRecord) {
        exec(
            "INSERT OR REPLACE INTO tm_chapter(work_key, chapter_key, to_lang, provider, source_hash, segment_count, complete, paid, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9,
        ) {
            bindString(0, record.workKey); bindString(1, record.chapterKey); bindString(2, record.to); bindString(3, record.provider)
            bindString(4, record.sourceHash); bindLong(5, record.segmentCount.toLong())
            bindLong(6, if (record.complete) 1 else 0); bindLong(7, if (record.paid) 1 else 0); bindLong(8, record.updatedAt)
        }
    }

    public fun chapters(workKey: String, to: String): List<ChapterRecord> = query(
        "SELECT work_key, chapter_key, to_lang, provider, source_hash, segment_count, complete, paid, updated_at " +
            "FROM tm_chapter WHERE work_key = ? AND to_lang = ? ORDER BY chapter_key, provider",
        2,
        { bindString(0, workKey); bindString(1, to) },
    ) { it.readChapter() }

    /** Remembers what the reader calls a work and one of its chapters. */
    public fun saveLabels(workKey: String, chapterKey: String, labels: TranslationLabels) {
        transaction {
            exec("INSERT OR REPLACE INTO tm_label(work_key, chapter_key, title, source) VALUES (?, '', ?, ?)", 3) {
                bindString(0, workKey); bindString(1, labels.workTitle.take(MAX_LABEL_LENGTH)); bindString(2, labels.sourceName?.take(MAX_LABEL_LENGTH))
            }
            exec("INSERT OR REPLACE INTO tm_label(work_key, chapter_key, title, source) VALUES (?, ?, ?, NULL)", 3) {
                bindString(0, workKey); bindString(1, chapterKey); bindString(2, labels.chapterTitle.take(MAX_LABEL_LENGTH))
            }
        }
    }

    /**
     * Which stored translations a chapter's translation is made of. Translations are kept by
     * what was translated, not by chapter, so removing one chapter's needs this record.
     */
    public fun saveChapterSegments(workKey: String, chapterKey: String, to: String, provider: String, keys: Collection<MemoryKey>) {
        transaction {
            exec("DELETE FROM tm_chapter_segment WHERE work_key = ? AND chapter_key = ? AND chapter_to = ? AND provider = ?", 4) {
                bindString(0, workKey); bindString(1, chapterKey); bindString(2, to); bindString(3, provider)
            }
            for (key in keys) {
                exec(
                    "INSERT OR IGNORE INTO tm_chapter_segment(work_key, chapter_key, chapter_to, provider, source_hash, from_lang, to_lang, engine) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    8,
                ) {
                    bindString(0, workKey); bindString(1, chapterKey); bindString(2, to); bindString(3, provider)
                    bindString(4, key.sourceHash); bindString(5, key.from); bindString(6, key.to); bindString(7, key.engine)
                }
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
        val same = "work_key = ? AND chapter_key = ? AND chapter_to = ? AND provider = ?"
        exec(
            "DELETE FROM tm_segment WHERE EXISTS (" +
                "SELECT 1 FROM tm_chapter_segment m WHERE m.work_key = ? AND m.chapter_key = ? AND m.chapter_to = ? AND m.provider = ?" +
                " AND m.source_hash = tm_segment.source_hash AND m.from_lang = tm_segment.from_lang " +
                "AND m.to_lang = tm_segment.to_lang AND m.engine = tm_segment.engine) " +
                "AND NOT EXISTS (SELECT 1 FROM tm_chapter_segment o WHERE o.source_hash = tm_segment.source_hash " +
                "AND o.from_lang = tm_segment.from_lang AND o.to_lang = tm_segment.to_lang AND o.engine = tm_segment.engine " +
                "AND NOT (o.work_key = ? AND o.chapter_key = ? AND o.chapter_to = ? AND o.provider = ?))",
            8,
        ) {
            bindString(0, workKey); bindString(1, chapterKey); bindString(2, to); bindString(3, provider)
            bindString(4, workKey); bindString(5, chapterKey); bindString(6, to); bindString(7, provider)
        }
        exec("DELETE FROM tm_chapter_segment WHERE $same", 4) {
            bindString(0, workKey); bindString(1, chapterKey); bindString(2, to); bindString(3, provider)
        }
        exec("DELETE FROM tm_chapter WHERE work_key = ? AND chapter_key = ? AND to_lang = ? AND provider = ?", 4) {
            bindString(0, workKey); bindString(1, chapterKey); bindString(2, to); bindString(3, provider)
        }
        // Names go with the last record that used them.
        exec("DELETE FROM tm_label WHERE work_key = ? AND chapter_key = ? AND NOT EXISTS (SELECT 1 FROM tm_chapter c WHERE c.work_key = ? AND c.chapter_key = ?)", 4) {
            bindString(0, workKey); bindString(1, chapterKey); bindString(2, workKey); bindString(3, chapterKey)
        }
        exec("DELETE FROM tm_label WHERE work_key = ? AND chapter_key = '' AND NOT EXISTS (SELECT 1 FROM tm_chapter c WHERE c.work_key = ?)", 2) {
            bindString(0, workKey); bindString(1, workKey)
        }
        before - (freeBytes() + paidBytes())
    }

    /** Removes every stored chapter translation of a work; its glossary and settings stay. */
    public fun deleteWorkTranslations(workKey: String): Long = transaction {
        query("SELECT chapter_key, to_lang, provider FROM tm_chapter WHERE work_key = ?", 1, { bindString(0, workKey) }) {
            Triple(it.getString(0)!!, it.getString(1)!!, it.getString(2)!!)
        }.sumOf { (chapter, to, provider) -> deleteChapterTranslation(workKey, chapter, to, provider) }
    }

    /** Chapters with a stored translation, most recently translated first. */
    public fun storedChapters(limit: Int): List<StoredChapterTranslation> = query(
        "SELECT c.work_key, c.chapter_key, c.to_lang, c.provider, c.segment_count, c.complete, c.paid, c.updated_at, w.title, l.title, w.source " +
            "FROM tm_chapter c " +
            "LEFT JOIN tm_label w ON w.work_key = c.work_key AND w.chapter_key = '' " +
            "LEFT JOIN tm_label l ON l.work_key = c.work_key AND l.chapter_key = c.chapter_key " +
            "WHERE c.segment_count > 0 ORDER BY c.updated_at DESC, c.work_key, c.chapter_key LIMIT ?",
        1,
        { bindLong(0, limit.toLong()) },
    ) {
        StoredChapterTranslation(
            workKey = it.getString(0)!!, chapterKey = it.getString(1)!!, to = it.getString(2)!!, provider = it.getString(3)!!,
            lineCount = it.getLong(4)!!.toInt(), complete = it.getLong(5) == 1L, paid = it.getLong(6) == 1L, updatedAt = it.getLong(7)!!,
            workTitle = it.getString(8), chapterTitle = it.getString(9), sourceName = it.getString(10),
        )
    }

    /** Forgets a work's chapter records and glossary. Segment translations stay: they may serve another source of the same text. */
    public fun forgetWork(workKey: String) {
        transaction {
            exec("DELETE FROM tm_chapter WHERE work_key = ?", 1) { bindString(0, workKey) }
            exec("DELETE FROM tm_glossary WHERE work_key = ?", 1) { bindString(0, workKey) }
            exec("DELETE FROM tm_work WHERE work_key = ?", 1) { bindString(0, workKey) }
            exec("DELETE FROM tm_label WHERE work_key = ?", 1) { bindString(0, workKey) }
            exec("DELETE FROM tm_chapter_segment WHERE work_key = ?", 1) { bindString(0, workKey) }
        }
    }

    // ---- device preferences -----------------------------------------------------------------

    /** A device-wide translation preference (the chosen provider, the daily limit, today's usage). */
    public fun preference(name: String): String? = meta("pref.$name")

    public fun setPreference(name: String, value: String) {
        setMeta("pref.$name", value)
    }

    // ---- per-work settings ------------------------------------------------------------------

    public fun workSettings(workKey: String): WorkTranslationSettings? = query(
        "SELECT enabled, from_lang, to_lang, mode FROM tm_work WHERE work_key = ?", 1, { bindString(0, workKey) },
    ) {
        WorkTranslationSettings(
            enabled = it.getLong(0) == 1L,
            from = it.getString(1)!!,
            to = it.getString(2)!!,
            mode = TranslationDisplayMode.entries.firstOrNull { mode -> mode.name == it.getString(3) } ?: TranslationDisplayMode.TRANSLATION,
        )
    }.firstOrNull()

    public fun saveWorkSettings(workKey: String, settings: WorkTranslationSettings) {
        exec("INSERT OR REPLACE INTO tm_work(work_key, enabled, from_lang, to_lang, mode) VALUES (?, ?, ?, ?, ?)", 5) {
            bindString(0, workKey); bindLong(1, if (settings.enabled) 1 else 0)
            bindString(2, settings.from); bindString(3, settings.to); bindString(4, settings.mode.name)
        }
    }

    // ---- glossary ---------------------------------------------------------------------------

    public fun glossary(workKey: String, to: String): List<GlossaryEntry> = query(
        "SELECT id, source, target FROM tm_glossary WHERE work_key = ? AND to_lang = ? ORDER BY id",
        2,
        { bindString(0, workKey); bindString(1, to) },
    ) { GlossaryEntry(it.getLong(0)!!.toInt(), it.getString(1)!!, it.getString(2)!!) }

    /**
     * Adds a term or changes its rendering. An existing source term keeps its id (and so its code
     * in stored translations); a new one gets the next id, never one that was used before.
     */
    public fun putGlossaryTerm(workKey: String, to: String, source: String, target: String): GlossaryEntry = transaction {
        val term = source.trim()
        require(term.isNotEmpty()) { "Glossary source term is blank" }
        val existing = query(
            "SELECT id FROM tm_glossary WHERE work_key = ? AND to_lang = ? AND source = ?",
            3,
            { bindString(0, workKey); bindString(1, to); bindString(2, term) },
        ) { it.getLong(0)!!.toInt() }.firstOrNull()
        val id = existing ?: run {
            val next = (meta("glossary.next.$workKey.$to")?.toIntOrNull() ?: 1)
            require(next <= GlossaryEntry.MAX_ID) { "This work's glossary is full" }
            setMeta("glossary.next.$workKey.$to", (next + 1).toString())
            next
        }
        exec("INSERT OR REPLACE INTO tm_glossary(work_key, to_lang, id, source, target) VALUES (?, ?, ?, ?, ?)", 5) {
            bindString(0, workKey); bindString(1, to); bindLong(2, id.toLong()); bindString(3, term); bindString(4, target.trim())
        }
        GlossaryEntry(id, term, target.trim())
    }

    public fun removeGlossaryTerm(workKey: String, to: String, id: Int) {
        exec("DELETE FROM tm_glossary WHERE work_key = ? AND to_lang = ? AND id = ?", 3) {
            bindString(0, workKey); bindString(1, to); bindLong(2, id.toLong())
        }
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
        val works = query("SELECT work_key, enabled, from_lang, to_lang, mode FROM tm_work ORDER BY work_key", 0, null) {
            TranslationBackup.Work(it.getString(0)!!, it.getLong(1) == 1L, it.getString(2)!!, it.getString(3)!!, it.getString(4)!!)
        }
        val terms = query("SELECT work_key, to_lang, id, source, target FROM tm_glossary ORDER BY work_key, to_lang, id", 0, null) {
            Triple(it.getString(0)!!, it.getString(1)!!, TranslationBackup.Term(it.getLong(2)!!.toInt(), it.getString(3)!!, it.getString(4)!!))
        }
        val byScope = terms.groupBy { it.first to it.second }
        // A glossary that was emptied is still a glossary: restoring it must clear the old names.
        val emptied = query("SELECT k FROM tm_meta WHERE k LIKE 'glossary.next.%'", 0, null) { it.getString(0)!! }
            .map { it.removePrefix("glossary.next.") }
            .filter { '.' in it }
            .map { it.substringBeforeLast('.') to it.substringAfterLast('.') }
            .filter { it !in byScope }
        val glossaries = (byScope.keys + emptied).map { scope ->
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
        val wanted = buildList { if (includePaid) add(1L); if (includeFree) add(0L) }
        val segments = ArrayList<TranslationBackup.Segment>()
        var truncated = false
        var used = 0L
        for (paid in wanted) {
            if (truncated) break
            // Row by row, stopping at the budget: paid translations are never evicted, and
            // reading all of them just to drop most would defeat the bound.
            driver.executeQuery(
                identifier = null,
                sql = "SELECT source_hash, from_lang, to_lang, engine, text, bytes FROM tm_segment " +
                    "WHERE paid = ? AND exportable = 1 ORDER BY used_at DESC, source_hash",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val segment = TranslationBackup.Segment(
                            cursor.getString(0)!!, cursor.getString(1)!!, cursor.getString(2)!!, cursor.getString(3)!!, cursor.getString(4)!!, paid == 1L,
                        )
                        // What the row costs in the file: its text (escapes included) and its keys.
                        val cost = cursor.getLong(5)!! + segment.text.count { it == '"' || it == '\\' || it < ' ' } * 5L +
                            segment.from.length + segment.to.length + segment.engine.length + BACKUP_ROW_OVERHEAD_BYTES
                        if (used + cost > segmentByteBudget) {
                            truncated = true
                            break
                        }
                        segments += segment
                        used += cost
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
                binders = { bindLong(0, paid) },
            ).value
        }
        val segmentIndex = HashMap<MemoryKey, Int>(segments.size * 2)
        segments.forEachIndexed { index, segment -> segmentIndex[MemoryKey(segment.sourceHash, segment.from, segment.to, segment.engine)] = index }
        val chapters = if (wanted.isEmpty()) emptyList() else query(
            "SELECT work_key, chapter_key, to_lang, provider, source_hash, segment_count, complete, paid, updated_at FROM tm_chapter ORDER BY work_key, chapter_key",
            0,
            null,
        ) { it.readChapter() }.filter { (if (it.paid) 1L else 0L) in wanted }.map {
            // Whether it is still complete is checked when the chapter is opened.
            val lines = query(
                "SELECT source_hash, from_lang, to_lang, engine FROM tm_chapter_segment WHERE work_key = ? AND chapter_key = ? AND chapter_to = ? AND provider = ?",
                4,
                { bindString(0, it.workKey); bindString(1, it.chapterKey); bindString(2, it.to); bindString(3, it.provider) },
            ) { row -> MemoryKey(row.getString(0)!!, row.getString(1)!!, row.getString(2)!!, row.getString(3)!!) }
                .mapNotNull(segmentIndex::get).sorted()
            val names = query(
                "SELECT chapter_key, title, source FROM tm_label WHERE work_key = ? AND chapter_key IN ('', ?)",
                2,
                { bindString(0, it.workKey); bindString(1, it.chapterKey) },
            ) { row -> Triple(row.getString(0)!!, row.getString(1), row.getString(2)) }
            val work = names.firstOrNull { name -> name.first.isEmpty() }
            TranslationBackup.Chapter(
                it.workKey, it.chapterKey, it.to, it.provider, it.sourceHash, it.segmentCount, it.complete && !truncated, it.paid, it.updatedAt,
                segmentIndexes = lines,
                workTitle = work?.second,
                chapterTitle = names.firstOrNull { name -> name.first == it.chapterKey && name.first.isNotEmpty() }?.second,
                sourceName = work?.third,
            )
        }
        TranslationBackup(works, glossaries, chapters, segments, truncated)
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
                saveWorkSettings(work.workKey, WorkTranslationSettings(work.enabled, work.from, work.to, mode))
            }
            for (glossary in backup.glossaries) {
                exec("DELETE FROM tm_glossary WHERE work_key = ? AND to_lang = ?", 2) { bindString(0, glossary.workKey); bindString(1, glossary.to) }
                setMeta("glossary.salt.${glossary.workKey}.${glossary.to}", glossary.salt)
                setMeta("glossary.next.${glossary.workKey}.${glossary.to}", glossary.nextId.toString())
                for (term in glossary.terms) {
                    exec("INSERT OR REPLACE INTO tm_glossary(work_key, to_lang, id, source, target) VALUES (?, ?, ?, ?, ?)", 5) {
                        bindString(0, glossary.workKey); bindString(1, glossary.to); bindLong(2, term.id.toLong())
                        bindString(3, term.source.trim()); bindString(4, term.target.trim())
                    }
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
                if (it.workTitle != null && it.chapterTitle != null) {
                    saveLabels(it.workKey, it.chapterKey, TranslationLabels(it.workTitle, it.chapterTitle, it.sourceName))
                }
            }
            for ((paid, group) in backup.segments.groupBy { it.paid }) {
                // A backup only ever holds translations that were allowed out.
                store(group.associate { MemoryKey(it.sourceHash, it.from, it.to, it.engine) to it.text }, paid, now, exportable = true)
            }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun meta(key: String): String? = query("SELECT v FROM tm_meta WHERE k = ?", 1, { bindString(0, key) }) { it.getString(0) }.firstOrNull()

    private fun setMeta(key: String, value: String) {
        exec("INSERT OR REPLACE INTO tm_meta(k, v) VALUES (?, ?)", 2) { bindString(0, key); bindString(1, value) }
    }

    private fun SqlCursor.readChapter() = ChapterRecord(
        workKey = getString(0)!!, chapterKey = getString(1)!!, to = getString(2)!!, provider = getString(3)!!,
        sourceHash = getString(4)!!, segmentCount = getLong(5)!!.toInt(), complete = getLong(6) == 1L, paid = getLong(7) == 1L, updatedAt = getLong(8)!!,
    )

    private fun exec(sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) {
        driver.execute(null, sql, parameters, binders).value
    }

    private fun <T> query(
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
        map: (SqlCursor) -> T,
    ): List<T> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = ArrayList<T>()
            while (cursor.next().value) rows += map(cursor)
            QueryResult.Value(rows)
        },
        parameters = parameters,
        binders = binders,
    ).value

    private companion object {
        const val LOOKUP_CHUNK = 400
        const val EVICT_BATCH = 200
        const val MAX_LABEL_LENGTH = 300

        /** The hash, field names, punctuation and indentation of one stored translation in a backup file. */
        const val BACKUP_ROW_OVERHEAD_BYTES = 400L
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS tm_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
            """
            CREATE TABLE IF NOT EXISTS tm_segment(
              source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, engine TEXT NOT NULL,
              text TEXT NOT NULL, paid INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL, used_at INTEGER NOT NULL,
              PRIMARY KEY(source_hash, from_lang, to_lang, engine)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS tm_segment_free_age ON tm_segment(used_at) WHERE paid = 0",
            """
            CREATE TABLE IF NOT EXISTS tm_chapter(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, to_lang TEXT NOT NULL, provider TEXT NOT NULL,
              source_hash TEXT NOT NULL, segment_count INTEGER NOT NULL, complete INTEGER NOT NULL,
              paid INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
              PRIMARY KEY(work_key, chapter_key, to_lang, provider)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tm_glossary(
              work_key TEXT NOT NULL, to_lang TEXT NOT NULL, id INTEGER NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL,
              PRIMARY KEY(work_key, to_lang, id)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS tm_glossary_source ON tm_glossary(work_key, to_lang, source)",
            // Which stored lines each chapter's translation uses, so one chapter's can be removed.
            """
            CREATE TABLE IF NOT EXISTS tm_chapter_segment(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, chapter_to TEXT NOT NULL, provider TEXT NOT NULL,
              source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, engine TEXT NOT NULL,
              PRIMARY KEY(work_key, chapter_key, chapter_to, provider, source_hash, from_lang, to_lang, engine)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS tm_chapter_segment_line ON tm_chapter_segment(source_hash, from_lang, to_lang, engine)",
            // Names for the settings page; chapter_key '' is the work itself.
            """
            CREATE TABLE IF NOT EXISTS tm_label(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, title TEXT NOT NULL, source TEXT,
              PRIMARY KEY(work_key, chapter_key)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tm_work(
              work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL,
              from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, mode TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }
}
