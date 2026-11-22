package dev.shinsou.kmp.content.hotstate

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.content.ContentSqlThreadGuard
import dev.shinsou.kmp.content.SchemaComponent
import dev.shinsou.kmp.content.SchemaMigrator
import dev.shinsou.kmp.content.hotstate.sql.LegacyHotStateDatabase
import dev.shinsou.kmp.content.hotstate.sql.LegacyHotStateQueries
import dev.shinsou.kmp.content.tableExists
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.persistence.ChapterProgress
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.HotStatePairing
import dev.shinsou.kmp.domain.persistence.HotStateStore
import dev.shinsou.kmp.domain.persistence.HotRows
import dev.shinsou.kmp.domain.persistence.writeRows
import kotlinx.serialization.json.Json

/**
 * C3-2 (docs/architecture/0007 §6.3.2): the hot rows of the app snapshot in the content database
 * (the `legacy_hot_state` table group), beside `sync3_dirty`, so a later step can write both in one
 * transaction. Each call is one transaction, or a part of the caller's when one is open on the
 * driver (C3-5: a content-backup v2 restore writes the restored rows inside its own); never on the
 * UI thread ([ContentSqlThreadGuard]).
 */
class SqlHotStateStore(driver: SqlDriver) : HotStateStore {
    private val transactions = object : TransacterImpl(driver) {}
    private val queries = LegacyHotStateQueries(driver)

    init {
        ContentSqlThreadGuard.checkWhileOpening()
        LegacyHotStateSchema.create(driver).value
    }

    override fun load(): HotState = sql {
        HotState(
            chapterProgress = queries.allProgress { chapterId, read, bookmark, lastPageRead ->
                chapterId to ChapterProgress(read != 0L, bookmark != 0L, lastPageRead.toInt())
            }.executeAsList().toMap(),
            histories = queries.allHistories { historyId, _, chapterId, lastRead, timeRead, locator, pageCount ->
                History(
                    id = historyId,
                    chapterId = chapterId,
                    lastRead = lastRead,
                    timeRead = timeRead,
                    lastLocator = locator?.let { LocatorJson.decodeFromString(ReadingLocator.serializer(), it) },
                    lastPageCount = pageCount?.toInt(),
                )
            }.executeAsList(),
            downloads = queries.allDownloads { id, _, mangaId, chapterId, state, progress, downloaded, total, position,
                    queuedAt, updatedAt, destination, error, visible ->
                DownloadQueueItem(
                    id = id,
                    mangaId = mangaId,
                    chapterId = chapterId,
                    state = downloadState(state),
                    progress = progress,
                    downloadedPages = downloaded.toInt(),
                    totalPages = total.toInt(),
                    position = position.toInt(),
                    queuedAt = queuedAt,
                    updatedAt = updatedAt,
                    destinationPath = destination,
                    errorMessage = error,
                    visibleInQueue = visible != 0L,
                )
            }.executeAsList(),
        )
    }

    override fun apply(delta: HotDelta) {
        if (delta.isEmpty) return
        sql {
            delta.removedProgress.forEach { queries.deleteProgress(it).value }
            delta.progress.forEach { (chapterId, progress) ->
                if (progress.isDefault) {
                    queries.deleteProgress(chapterId).value
                } else {
                    queries.putProgress(chapterId, progress.read.toLong(), progress.bookmark.toLong(), progress.lastPageRead.toLong()).value
                }
            }
            delta.removedHistories.forEach { queries.deleteHistory(it).value }
            delta.histories.forEach { (ordinal, history) ->
                queries.putHistory(
                    history_id = history.id,
                    ordinal = ordinal.toLong(),
                    chapter_id = history.chapterId,
                    last_read = history.lastRead,
                    time_read = history.timeRead,
                    last_locator_json = history.lastLocator?.let { LocatorJson.encodeToString(ReadingLocator.serializer(), it) },
                    last_page_count = history.lastPageCount?.toLong(),
                ).value
            }
            delta.removedDownloads.forEach { queries.deleteDownload(it).value }
            delta.downloads.forEach { (ordinal, item) ->
                queries.putDownload(
                    id = item.id,
                    ordinal = ordinal.toLong(),
                    manga_id = item.mangaId,
                    chapter_id = item.chapterId,
                    state = item.state.name,
                    progress = item.progress,
                    downloaded_pages = item.downloadedPages.toLong(),
                    total_pages = item.totalPages.toLong(),
                    position = item.position.toLong(),
                    queued_at = item.queuedAt,
                    updated_at = item.updatedAt,
                    destination_path = item.destinationPath,
                    error_message = item.errorMessage,
                    visible = item.visibleInQueue.toLong(),
                ).value
            }
        }
    }

    override fun clear(): Unit = sql {
        queries.deleteAllProgress().value
        queries.deleteAllHistories().value
        queries.deleteAllDownloads().value
        queries.deleteAllMeta().value
    }

    override fun pairing(): HotStatePairing? = sql {
        val revision = metaLong(META_PAIRED_JSON_REVISION)
        val token = queries.meta(META_TOKEN).executeAsOneOrNull()
        if (revision != null && token != null) {
            HotStatePairing(
                jsonRevision = revision,
                token = token,
                pendingJsonRevision = metaLong(META_PENDING_JSON_REVISION),
                hotRevision = metaLong(META_HOT_REVISION),
                journaledJsonRevision = metaLong(META_JOURNALED_JSON_REVISION),
            )
        } else {
            null
        }
    }

    override fun setPairing(pairing: HotStatePairing?): Unit = sql {
        PAIRING_KEYS.forEach { queries.deleteMeta(it).value }
        if (pairing?.journaledJsonRevision == null) queries.deleteMeta(META_JOURNALED_JSON).value
        if (pairing != null) {
            queries.putMeta(META_PAIRED_JSON_REVISION, pairing.jsonRevision.toString()).value
            queries.putMeta(META_TOKEN, pairing.token).value
            pairing.pendingJsonRevision?.let { queries.putMeta(META_PENDING_JSON_REVISION, it.toString()).value }
            pairing.hotRevision?.let { queries.putMeta(META_HOT_REVISION, it.toString()).value }
            pairing.journaledJsonRevision?.let { queries.putMeta(META_JOURNALED_JSON_REVISION, it.toString()).value }
        }
    }

    override fun journaledJson(): String? = sql { queries.meta(META_JOURNALED_JSON).executeAsOneOrNull() }

    override fun setJournaledJson(json: String?): Unit = sql {
        if (json == null) queries.deleteMeta(META_JOURNALED_JSON).value else queries.putMeta(META_JOURNALED_JSON, json).value
    }

    override fun commit(rows: HotRows, pairing: HotStatePairing): Unit = sql {
        writeRows(rows)
        setPairing(pairing)
    }

    private fun metaLong(key: String): Long? = queries.meta(key).executeAsOneOrNull()?.toLongOrNull()

    private inline fun <T> sql(crossinline block: () -> T): T {
        ContentSqlThreadGuard.check()
        return transactions.transactionWithResult(noEnclosing = false) { block() }
    }

    private companion object {
        const val META_PAIRED_JSON_REVISION = "paired_json_revision"
        const val META_TOKEN = "token"
        const val META_PENDING_JSON_REVISION = "pending_json_revision"
        const val META_HOT_REVISION = "hot_revision"
        const val META_JOURNALED_JSON_REVISION = "journaled_json_revision"

        /** The whole snapshot JSON a restore committed with its rows (C3-5), until a whole write pairs one. */
        const val META_JOURNALED_JSON = "journaled_json"

        val PAIRING_KEYS = listOf(
            META_PAIRED_JSON_REVISION,
            META_TOKEN,
            META_PENDING_JSON_REVISION,
            META_HOT_REVISION,
            META_JOURNALED_JSON_REVISION,
        )

        val LocatorJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** A state a later version added reads as an error, which the download manager retries. */
        fun downloadState(name: String): DownloadState =
            DownloadState.entries.firstOrNull { it.name == name } ?: DownloadState.ERROR
    }
}

private fun Boolean.toLong(): Long = if (this) 1L else 0L

/** The `legacy_hot_state` table group, versioned on its own ([SchemaMigrator]). */
object LegacyHotStateSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    val component: SchemaComponent = SchemaComponent(
        name = "legacy_hot_state",
        version = version,
        // The generated DDL (LegacyHotState.sq); its own Schema version is not used.
        create = { driver -> LegacyHotStateDatabase.Schema.create(driver).value },
        unrecordedVersion = { driver -> if (tableExists(driver, "legacy_hot_state_meta")) 1 else null },
    )

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaMigrator.migrate(driver, component)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Hot state tables cannot migrate backwards" }
        create(driver)
        return QueryResult.Unit
    }
}
