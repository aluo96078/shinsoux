package dev.shinsou.kmp

import android.content.Context
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.BEFORE_HOT_STATE_SUFFIX
import dev.shinsou.kmp.data.SnapshotFile
import dev.shinsou.kmp.data.WHOLE_JSON_SWITCH_FILE
import dev.shinsou.kmp.logging.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-backed app state with one-time migration from the original SharedPreferences value. */
internal class AndroidSnapshotPersistence(context: Context) : SnapshotFile {
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
        val migrated = runCatching { AppSnapshotJson.decode(legacyPayload) }
            .onFailure { Log.warn("Storage", it) { "Legacy app state could not be decoded; starting empty" } }
            .getOrNull()
            ?: return@synchronized AppSnapshot()

        runCatching { saveLocked(legacyPayload) }
            .onFailure { Log.warn("Storage", it) { "Migrating legacy app state to its file failed; keeping the legacy copy" } }
            .onSuccess {
                legacyPreferences.edit().remove(LEGACY_STATE_KEY).commit()
            }
        migrated
    }

    override fun save(json: String) = synchronized(lock) {
        saveLocked(json)
    }

    /** C3 (docs/architecture/0007 §6.3.5): the copy left out of Android's backups (backup_rules.xml). */
    override fun keepCopyBeforeHotState(): Boolean = synchronized(lock) {
        val copy = File(stateFile.parentFile, "$STATE_FILE_NAME$BEFORE_HOT_STATE_SUFFIX")
        if (copy.isFile || !stateFile.isFile) return@synchronized true
        val partial = File(stateFile.parentFile, "${copy.name}.partial")
        try {
            FileInputStream(stateFile).use { input ->
                FileOutputStream(partial, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            Files.move(partial.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (logged: IOException) {
            Log.warn("Storage", logged) { "Copying the app state before moving its hot rows failed" }
            false
        }
    }

    /**
     * The C3 off-switch: a file named [WHOLE_JSON_SWITCH_FILE] in the app's files directory
     * (`adb shell run-as <package> touch files/shinsou-state.whole-json` on a debuggable build).
     */
    fun wholeJsonRequested(): Boolean = File(stateFile.parentFile, WHOLE_JSON_SWITCH_FILE).exists()

    private fun loadStateFile(): AppSnapshot? {
        if (!stateFile.isFile) return null
        // A read failure propagates: returning "no state" here would start an empty library and
        // let the next save replace the real file.
        val payload = stateFile.readText(StandardCharsets.UTF_8)
        return try {
            AppSnapshotJson.decode(payload)
        } catch (logged: Exception) {
            // Not Throwable: an OutOfMemoryError says nothing about the file.
            Log.warn("Storage", logged) { "Saved app state could not be decoded; quarantining it and starting empty" }
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
            // Atomic moves are optional on this file system; a plain replace is enough.
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
        }.onFailure { Log.warn("Storage", it) { "Quarantining the corrupt app state file failed" } }
    }

    private companion object {
        const val STATE_FILE_NAME = "shinsou-state.json"
        const val LEGACY_PREFERENCES = "shinsou-app-state"
        const val LEGACY_STATE_KEY = "snapshot"
    }
}
