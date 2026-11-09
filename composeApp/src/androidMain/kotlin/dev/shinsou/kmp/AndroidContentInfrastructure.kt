package dev.shinsou.kmp

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.content.PlatformContentDatabaseSchema
import dev.shinsou.kmp.content.PlatformContentInfrastructure

/**
 * Android content storage: one SQLite database plus a no-backup blob directory. The database name
 * and directory predate the removal of sync and are kept so existing libraries open unchanged.
 */
internal class AndroidContentInfrastructure(context: Context) : PlatformContentInfrastructure {
    private val applicationContext = context.applicationContext
    private val storageDirectory = applicationContext.noBackupFilesDir.resolve("SyncV2").apply { mkdirs() }

    private val driverLock = Any()
    private var openDriver: SqlDriver? = null

    override fun contentDriver(): SqlDriver = synchronized(driverLock) { databaseDriver() }

    override fun contentBlobDirectory(): String =
        storageDirectory.resolve("content-blobs").absolutePath

    override fun close() = synchronized(driverLock) {
        openDriver?.close()
        openDriver = null
    }

    private fun databaseDriver(): SqlDriver = openDriver ?: AndroidSqliteDriver(
        schema = PlatformContentDatabaseSchema,
        context = applicationContext,
        name = DATABASE_NAME,
    ).also { openDriver = it }

    private companion object {
        const val DATABASE_NAME = "shinsou-sync-v2.db"
    }
}
