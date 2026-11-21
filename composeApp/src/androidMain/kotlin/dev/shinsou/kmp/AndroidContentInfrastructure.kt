package dev.shinsou.kmp

import android.content.Context
import android.content.pm.ApplicationInfo
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.content.ContentSqlThreadGuard
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

    init {
        // A debuggable app (a debug build) fails on content SQL run on the main thread (R3-4).
        ContentSqlThreadGuard.failOnMainThread =
            (applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override fun contentDriver(): SqlDriver = synchronized(driverLock) { databaseDriver() }

    override fun contentBlobDirectory(): String =
        storageDirectory.resolve("content-blobs").absolutePath

    override fun databaseFile(): String = applicationContext.getDatabasePath(DATABASE_NAME).absolutePath

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
