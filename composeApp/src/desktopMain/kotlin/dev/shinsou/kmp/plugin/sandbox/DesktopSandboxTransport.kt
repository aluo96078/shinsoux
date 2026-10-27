package dev.shinsou.kmp.plugin.sandbox

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop's [SandboxTransportProvider] (ADR-0002 §4.4): every [open] starts a new `shinsou-sandbox`
 * helper process for one package. The helper confines itself before reading a byte (macOS:
 * sandbox_init deny-all, `sandbox-core/src/ssc_os_sandbox.c`), so it gets no environment, no
 * descriptors beyond its pipes and no network, files or child processes.
 */
internal class DesktopSandboxTransportProvider(
    private val helper: () -> Path = SandboxHelperLocator()::executable,
) : SandboxTransportProvider {
    override suspend fun open(packageId: String): SandboxTransport = withContext(Dispatchers.IO) {
        DesktopSandboxTransport(listOf(helper().toString()))
    }
}

/** The §4.6/§7 lifecycle with Desktop's values: at most 8 live helpers (S0: about 6 MiB each). */
internal fun desktopSandboxLifecycle(scope: CoroutineScope): SandboxLifecycle = SandboxLifecycle(
    maxLive = 8,
    nowMs = { System.nanoTime() / 1_000_000 },
    scope = scope,
)

/**
 * Frames to one helper over its stdin/stdout. [close] kills the process, which also ends a pending
 * read or write. If the host itself dies, the helper's stdin reaches end of stream and the driver
 * stops even a running hook at once (no orphan outlives its host).
 */
internal class DesktopSandboxTransport(command: List<String>) : SandboxTransport {
    private val process: Process = ProcessBuilder(command)
        .apply {
            environment().clear() // the helper clears it too; nothing of the host's should reach it
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        .start()
    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val buffer = ByteArray(64 * 1024)

    /** The helper's pid (diagnostics and tests). */
    val pid: Long get() = process.pid()
    val alive: Boolean get() = process.isAlive

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val n = try { input.read(buffer) } catch (_: IOException) { -1 }
        if (n <= 0) null else buffer.copyOf(n)
    }

    override fun close() {
        process.destroyForcibly()
    }

    /** Test hook: closes the helper's stdin only, as the host's death would. */
    internal fun closeInputForTest() = output.close()
}

/**
 * Finds the packaged helper and runs it from a private copy: jpackage installs application
 * resources without execute permission (the WKWebView helper does the same). The copy is made once
 * per run, in a directory only this user can enter.
 *
 * A packaged app (Compose sets [RESOURCES_PROPERTY]) only ever runs the helper inside its own
 * signed bundle: `SHINSOU_SANDBOX_BIN` and build-output paths under the working directory would
 * let whoever controls the launch environment substitute an unconfined binary, so they count only
 * in development runs, which have no resources directory.
 */
internal class SandboxHelperLocator(
    private val resourcesDirectory: String? = System.getProperty(RESOURCES_PROPERTY),
    private val overridePath: String? = System.getenv("SHINSOU_SANDBOX_BIN"),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val developmentCandidates: List<Path> = listOf(
        Path.of(System.getProperty("user.dir").orEmpty(), "composeApp/build/generated/desktopAppResources/macos/$HELPER_NAME"),
        Path.of(System.getProperty("user.dir").orEmpty(), "build/generated/desktopAppResources/macos/$HELPER_NAME"),
    ),
) {
    @Volatile
    private var copy: Path? = null

    fun executable(): Path = copy ?: synchronized(this) { copy ?: prepare().also { copy = it } }

    private fun prepare(): Path {
        val packagedResources = resourcesDirectory?.takeIf(String::isNotBlank)
        if (packagedResources == null) overridePath?.takeIf(String::isNotBlank)?.let { return Path.of(it) } // development and tests
        // Only macOS ships a helper that confines itself; elsewhere there is no sandbox yet (S3 Windows).
        check(osName.lowercase().contains("mac")) { "The plugin sandbox is not available on $osName yet" }
        val candidates = if (packagedResources != null) listOf(Path.of(packagedResources).resolve(HELPER_NAME)) else developmentCandidates
        val source = candidates.firstOrNull(Files::isRegularFile)
            ?: throw IllegalStateException("The packaged plugin sandbox helper is missing")
        val directory = Files.createTempDirectory("shinsou-sandbox-")
        val target = directory.resolve(HELPER_NAME)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(directory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        directory.toFile().deleteOnExit()
        target.toFile().deleteOnExit()
        return target
    }

    private companion object {
        private const val HELPER_NAME = "shinsou-sandbox"
        const val RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
