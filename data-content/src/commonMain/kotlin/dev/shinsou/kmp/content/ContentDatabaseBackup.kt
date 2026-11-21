package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.logging.Log
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random

/** Beside the database file: the copy taken before its last upgrade. */
internal const val BEFORE_UPGRADE_SUFFIX = ".before-upgrade"

/**
 * Copies the content database to `<file>.before-upgrade` when opening the stores is about to
 * upgrade a table group in it (R3-1, risk table of docs/architecture/0006), so an upgrade step
 * that goes wrong can be undone by hand. Runs before any store opens, so nothing writes while it
 * copies; the write-ahead log is folded into the file first, and copied too if some of it stayed.
 * `<copy>.of` records the formats the copy was taken of and a mark written into the database once
 * it was copied: a start that finds the same database still in those formats (a background worker
 * that opens one store, before the app itself is opened) does not copy it again, and another
 * database put in its place, in the same formats or not, is copied. A copy that fails is logged and the app starts anyway: it is a safety net, not a gate.
 * Returns the copy, or null when nothing is upgraded, there is no file, or the copy failed.
 */
internal fun backUpBeforeUpgrade(
    driver: SqlDriver,
    databaseFile: String?,
    components: List<SchemaComponent>,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): String? {
    if (databaseFile == null || !SchemaMigrator.upgrades(driver, components)) return null
    val copy = "$databaseFile$BEFORE_UPGRADE_SUFFIX"
    val formats = SchemaMigrator.formats(driver, components)
    val mark = SchemaVersionStore.Shared(COPIED_MARK)
    var replaced = false
    return runCatching {
        val marked = if (tableExists(driver, VERSIONS_TABLE)) mark.read(driver) else null
        if (marked != null && alreadyCopied(fileSystem, copy, "$formats\n$marked")) return@runCatching copy
        // a pragma only runs once its row is stepped to
        driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor -> QueryResult.Value(cursor.next().value) }, 0)
        val wal = "$databaseFile-wal".toPath()
        val hasLog = (fileSystem.metadataOrNull(wal)?.size ?: 0L) > 0L
        // Both written beside the last copy first, so one cut short never replaces it.
        fileSystem.copy(databaseFile.toPath(), "$copy.partial".toPath())
        if (hasLog) fileSystem.copy(wal, "$copy-wal.partial".toPath())
        fileSystem.delete("$copy.of".toPath(), mustExist = false)
        replaced = true
        fileSystem.atomicMove("$copy.partial".toPath(), copy.toPath())
        if (hasLog) {
            fileSystem.atomicMove("$copy-wal.partial".toPath(), "$copy-wal".toPath())
        } else {
            fileSystem.delete("$copy-wal".toPath(), mustExist = false)
        }
        // Written after the copy, so the copy itself (put back by hand) does not carry it.
        val written = Random.nextLong()
        mark.write(driver, written)
        fileSystem.write("$copy.of".toPath()) { writeUtf8("$formats\n$written") }
        copy
    }.onFailure { error ->
        // Nothing half-made is left to be taken for the backup: not a copy cut short, and not a
        // new file beside the log of the copy before it.
        val leftovers = listOf("$copy.partial", "$copy-wal.partial") + if (replaced) listOf(copy, "$copy-wal", "$copy.of") else emptyList()
        // Best-effort cleanup; the copy failure itself is logged just below.
        leftovers.forEach { path -> runCatching { fileSystem.delete(path.toPath(), mustExist = false) } }
        Log.warn("ContentDatabase", error) { "Could not copy the database before upgrading it" }
    }.getOrNull()
}

/** A row of the versions table that is no table group: which copy this database was taken into. */
private const val COPIED_MARK = "before-upgrade-copy"

private fun alreadyCopied(fileSystem: FileSystem, copy: String, formats: String): Boolean =
    fileSystem.exists(copy.toPath()) &&
        // An unreadable marker means "not copied yet": the copy is simply taken again.
        runCatching { fileSystem.read("$copy.of".toPath()) { readUtf8() } }.getOrNull() == formats
