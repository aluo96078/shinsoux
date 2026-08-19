package dev.shinsou.kmp

import android.content.Context
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-backed app state with one-time migration from the original SharedPreferences value. */
internal class AndroidSnapshotPersistence(context: Context) {
    private val applicationContext = context.applicationContext
    private val stateFile = File(applicationContext.filesDir, STATE_FILE_NAME)
    private val lock = Any()

    fun load(): AppSnapshot = synchronized(lock) {
        loadStateFile()?.let { return@synchronized it }

        val legacyPreferences = applicationContext.getSharedPreferences(
            LEGACY_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val legacyPayload = legacyPreferences.getString(LEGACY_STATE_KEY, null)
            ?: return@synchronized AppSnapshot()
        val migrated = runCatching { AppSnapshotJson.decode(legacyPayload) }.getOrNull()
            ?: return@synchronized AppSnapshot()

        runCatching { saveLocked(legacyPayload) }
            .onSuccess {
                legacyPreferences.edit().remove(LEGACY_STATE_KEY).commit()
            }
        migrated
    }

    fun save(payload: String) = synchronized(lock) {
        saveLocked(payload)
    }

    private fun loadStateFile(): AppSnapshot? {
        if (!stateFile.isFile) return null
        val payload = runCatching { stateFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { AppSnapshotJson.decode(payload) }.getOrElse {
            quarantineCorruptState()
            null
        }
    }

    private fun saveLocked(payload: String) {
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, "$STATE_FILE_NAME.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                stateFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                stateFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun quarantineCorruptState() {
        val quarantine = File(
            stateFile.parentFile,
            "shinsou-state.corrupt-${System.currentTimeMillis()}.json",
        )
        runCatching {
            Files.move(
                stateFile.toPath(),
                quarantine.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val STATE_FILE_NAME = "shinsou-state.json"
        const val LEGACY_PREFERENCES = "shinsou-app-state"
        const val LEGACY_STATE_KEY = "snapshot"
    }
}
