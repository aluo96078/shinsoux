package dev.shinsou.kmp.plugin.sandbox

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Locates the `shinsou-sandbox` helper built from `sandbox-core` (or `SHINSOU_SANDBOX_BIN`).
 * Tests that need it skip with a printed reason when it is missing.
 */
internal fun sandboxHelperBinary(): File? =
    (System.getenv("SHINSOU_SANDBOX_BIN")?.let(::File) ?: File("../sandbox-core/build/release/shinsou-sandbox"))
        .takeIf { it.canExecute() }

/** Desktop transport over a child process's pipes (the OS sandbox around it is S3's job). */
internal class SandboxProcessTransport(command: List<String>) : SandboxTransport {
    private val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val buffer = ByteArray(64 * 1024)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val n = try { input.read(buffer) } catch (_: java.io.IOException) { -1 }
        if (n <= 0) null else buffer.copyOf(n)
    }

    override fun close() {
        process.destroyForcibly()
    }

    val alive: Boolean get() = process.isAlive
}
