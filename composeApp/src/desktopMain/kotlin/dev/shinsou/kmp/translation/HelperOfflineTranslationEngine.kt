package dev.shinsou.kmp.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Desktop's offline engine: the `shinsou-translate` helper (translation-core) as a child process,
 * talking the line protocol described in its source. A process of its own keeps an engine abort
 * (a model file it cannot load) or crash out of the app, and ending it returns all model memory.
 */
public class HelperOfflineTranslationEngine(private val helper: () -> Path) : OfflineTranslationEngine, AutoCloseable {
    private val mutex = Mutex()
    private var process: Process? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null

    override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        // The helper's own bounds: split rather than be refused.
        if (lines.size > MAX_LINES) return lines.chunked(MAX_LINES).flatMap { translate(model, it) }
        val paths = listOf(model.model, model.sourceVocab, model.targetVocab, model.shortlist)
        require(paths.none { '\n' in it || '\r' in it }) { "Model path contains a line break" }
        val request = buildString {
            append("TRANSLATE ").append(lines.size).append('\n')
            paths.forEach { append(it).append('\n') }
            lines.forEach { append(it.replace('\n', ' ').replace('\r', ' ')).append('\n') }
        }
        return mutex.withLock {
            exchange(request) { reader ->
                val header = reader.readLine() ?: throw failed("The translation helper stopped")
                if (header.startsWith("ERROR ")) throw failed(header.removePrefix("ERROR "), restart = false)
                if (header != "OK ${lines.size}") throw failed("Unexpected answer from the translation helper")
                List(lines.size) { reader.readLine() ?: throw failed("The translation helper stopped") }
            }
        }
    }

    override suspend fun unload(): Unit = mutex.withLock { stop() }

    override fun close() {
        stop()
    }

    private suspend fun <T> exchange(request: String, read: (BufferedReader) -> T): T = coroutineScope {
        val running = start()
        var finished = false
        // A blocked read cannot be interrupted: ending the helper is what unblocks it.
        val killer = launch {
            try { awaitCancellation() } finally { if (!finished) stop() }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                val writer = output ?: throw failed("The translation helper stopped")
                try {
                    writer.write(request)
                    writer.flush()
                    read(input ?: throw failed("The translation helper stopped"))
                } catch (error: java.io.IOException) {
                    throw failed("The translation helper stopped", cause = error)
                }
            }
        } catch (error: TranslationException) {
            if (error.restartHelper || !running.isAlive) stop()
            throw error
        } finally {
            finished = true
            killer.cancel()
        }
    }

    private fun start(): Process {
        process?.takeIf(Process::isAlive)?.let { return it }
        stop()
        val executable = try {
            helper()
        } catch (error: Exception) {
            throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "Offline translation is not available here: ${error.message}", error)
        }
        val started = try {
            ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (error: java.io.IOException) {
            throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "The translation helper could not be started", error)
        }
        process = started
        input = started.inputStream.bufferedReader(Charsets.UTF_8)
        output = started.outputStream.bufferedWriter(Charsets.UTF_8)
        return started
    }

    private fun stop() {
        // The process first: a write blocked on a helper that stopped reading holds the writer's
        // lock, and closing the stream would wait for it.
        process?.destroyForcibly()
        runCatching { output?.close() }
        process = null
        input = null
        output = null
    }

    private class HelperFailure(message: String, val restart: Boolean, cause: Throwable?) :
        Exception(message, cause)

    private val TranslationException.restartHelper: Boolean get() = (cause as? HelperFailure)?.restart != false

    private fun failed(message: String, restart: Boolean = true, cause: Throwable? = null) =
        TranslationException(TranslationFailure.ENGINE_FAILED, message, HelperFailure(message, restart, cause))

    private companion object {
        const val MAX_LINES = 4096
    }
}

/**
 * Finds the packaged helper and runs it from a private copy, like the plugin sandbox helper:
 * jpackage installs application resources without execute permission. A packaged app only runs
 * the helper from its own bundle; `SHINSOU_TRANSLATE_BIN` and build outputs count only in
 * development runs, which have no resources directory.
 */
public class TranslationHelperLocator(
    private val resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
    private val overridePath: String? = System.getenv("SHINSOU_TRANSLATE_BIN"),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val windows = osName.lowercase().startsWith("windows")
    private val helperName = if (windows) "shinsou-translate.exe" else "shinsou-translate"

    @Volatile
    private var copy: Path? = null

    public fun executable(): Path = copy ?: synchronized(this) { copy ?: prepare().also { copy = it } }

    private fun prepare(): Path {
        val packaged = resourcesDirectory?.takeIf(String::isNotBlank)
        if (packaged == null) overridePath?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
        val working = System.getProperty("user.dir").orEmpty()
        val relative = "generated/desktopAppResources/${if (windows) "windows" else "macos"}/$helperName"
        val candidates = if (packaged != null) {
            listOf(Path.of(packaged).resolve(helperName))
        } else {
            listOf(Path.of(working, "composeApp/build/$relative"), Path.of(working, "build/$relative"))
        }
        val source = candidates.firstOrNull(Files::isRegularFile) ?: throw IllegalStateException("this build has no translation helper")
        val directory = Files.createTempDirectory("shinsou-translate-")
        val target = directory.resolve(helperName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        if (!windows) {
            Files.setPosixFilePermissions(directory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        }
        directory.toFile().deleteOnExit()
        target.toFile().deleteOnExit()
        return target
    }
}
