package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.ui.ShellStateStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Keeps the shell's state in one small file in the app's data directory, so quitting and starting
 * again reopens what was shown. The file is replaced whole (written beside it, then moved over it):
 * a crash mid-write leaves the previous state, never half of one.
 */
internal class DesktopShellStateStore(private val file: Path) : ShellStateStore {
    override fun read(): String? = try {
        if (Files.isRegularFile(file)) Files.readString(file) else null
    } catch (error: IOException) {
        Log.warnKindOnly(TAG, error) { "The saved shell state could not be read" }
        null
    }

    override fun write(state: String) {
        val partial = file.resolveSibling("${file.fileName}.partial")
        try {
            Files.createDirectories(file.parent)
            Files.writeString(partial, state)
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            Log.warnKindOnly(TAG, error) { "The shell state could not be saved" }
        }
    }

    private companion object {
        const val TAG = "DesktopShellState"
    }
}
