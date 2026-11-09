package dev.shinsou.kmp.desktop

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.content.PlatformContentInfrastructure
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Desktop content storage. The directory and database name predate the removal of sync and are
 * kept so existing libraries open unchanged.
 */
internal class DesktopContentInfrastructure(
    private val directory: Path = DesktopAppDirectories.dataRoot.resolve("SyncV2"),
) : PlatformContentInfrastructure {
    private val driverLock = Any()
    private var openDriver: SqlDriver? = null

    init {
        Files.createDirectories(directory)
        setOwnerOnlyDirectory(directory)
    }

    override fun contentDriver(): SqlDriver = synchronized(driverLock) { databaseDriver() }

    override fun contentBlobDirectory(): String =
        directory.resolve("content-blobs").toAbsolutePath().normalize().toString()

    override fun close() = synchronized(driverLock) {
        openDriver?.close()
        openDriver = null
    }

    private fun databaseDriver(): SqlDriver = openDriver ?: run {
        val database = directory.resolve("local-sync.db").toAbsolutePath().normalize()
        // Release shrinking cannot infer JDBC's service-provider edge. Force initialization
        // before SQLDelight asks DriverManager for the connection. The release keep rule retains
        // this runtime-only class and its native bridge.
        Class.forName("org.sqlite.JDBC")
        DesktopSqliteDriver("jdbc:sqlite:$database").also { openDriver = it }
    }
}

private fun setOwnerOnlyDirectory(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}
