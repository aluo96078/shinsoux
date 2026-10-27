package dev.shinsou.kmp.plugin.sandbox

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop's [SandboxTransportProvider] (ADR-0002 §4.4): every [open] starts a new `shinsou-sandbox`
 * helper process for one package. The helper is confined before it reads a byte, so it gets no
 * environment, no descriptors beyond its pipes and no network, files or child processes. macOS:
 * sandbox_init deny-all (`sandbox-core/src/ssc_os_sandbox.c`). Windows: the helper relaunches
 * itself in a Low Privilege AppContainer and a Job object (`ssc_os_windows.c`); each package gets
 * its own AppContainer, so sandboxes of different packages cannot open each other's processes.
 */
internal class DesktopSandboxTransportProvider(
    private val windows: Boolean = isWindows(),
    private val helper: () -> Path = SandboxHelperLocator()::executable,
) : SandboxTransportProvider {
    override suspend fun open(packageId: String): SandboxTransport = withContext(Dispatchers.IO) {
        DesktopSandboxTransport(sandboxHelperCommand(helper(), packageId, windows))
    }
}

/** The helper's command line; on Windows with the package's AppContainer key. */
internal fun sandboxHelperCommand(helper: Path, packageId: String, windows: Boolean = isWindows()): List<String> =
    if (windows) listOf(helper.toString(), "--package-key", sandboxPackageKey(packageId)) else listOf(helper.toString())

/** 32 lowercase hex digits of SHA-256(packageId): a valid, fixed-length AppContainer name part. */
internal fun sandboxPackageKey(packageId: String): String =
    MessageDigest.getInstance("SHA-256").digest(packageId.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }

internal fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.lowercase().startsWith("windows")

/*
 * Windows: CreateProcess derives an AppContainer's own LOCALAPPDATA and TEMP from these and fails
 * without them (S0). They are paths, not secrets; the helper's launcher passes on only these.
 */
private val WINDOWS_HELPER_ENVIRONMENT = setOf("SystemRoot", "SystemDrive", "LOCALAPPDATA", "TEMP", "TMP", "USERPROFILE")

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
internal class DesktopSandboxTransport(command: List<String>, windows: Boolean = isWindows()) : SandboxTransport {
    private val process: Process = ProcessBuilder(command)
        .apply {
            // The helper clears it too; nothing of the host's should reach it.
            if (windows) {
                environment().keys.retainAll { key -> WINDOWS_HELPER_ENVIRONMENT.any { it.equals(key, ignoreCase = true) } }
            } else {
                environment().clear()
            }
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        .start()
    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val buffer = ByteArray(64 * 1024)

    /** The helper's pid (diagnostics and tests). On Windows the launcher's; the confined copy is its child. */
    val pid: Long get() = process.pid()
    val alive: Boolean get() = process.isAlive

    /** Test hook: the helper's processes (on Windows the launcher and its confined copy). */
    internal fun processesForTest(): List<ProcessHandle> = listOf(process.toHandle()) + process.descendants().toList()

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val n = try { input.read(buffer) } catch (_: IOException) { -1 }
        if (n <= 0) null else buffer.copyOf(n)
    }

    override fun close() {
        // Windows: killing the launcher closes the Job, which kills the confined copy with it.
        process.destroyForcibly()
    }

    /** Test hook: the exit code of an ended helper. */
    internal fun exitCodeForTest(): Int = process.exitValue()

    /** Test hook: closes the helper's stdin only, as the host's death would. */
    internal fun closeInputForTest() = output.close()
}

/**
 * Finds the packaged helper and runs it from a private copy: jpackage installs application
 * resources without execute permission (the WKWebView helper does the same). The copy is made once
 * per run, in a directory only this user can enter (Windows: the user's own temp directory; the
 * helper grants its AppContainers read and execute on that directory alone).
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
    private val developmentCandidates: List<Path> = defaultDevelopmentCandidates(osName),
) {
    private val windows = isWindows(osName)
    private val helperName = helperName(windows)

    @Volatile
    private var copy: Path? = null

    fun executable(): Path = copy ?: synchronized(this) { copy ?: prepare().also { copy = it } }

    private fun prepare(): Path {
        val packagedResources = resourcesDirectory?.takeIf(String::isNotBlank)
        if (packagedResources == null) overridePath?.takeIf(String::isNotBlank)?.let { return Path.of(it) } // development and tests
        // Only macOS and Windows ship a helper that is confined by the OS.
        check(windows || osName.lowercase().contains("mac")) { "The plugin sandbox is not available on $osName" }
        val candidates = if (packagedResources != null) listOf(Path.of(packagedResources).resolve(helperName)) else developmentCandidates
        val source = candidates.firstOrNull(Files::isRegularFile)
            ?: throw IllegalStateException("The packaged plugin sandbox helper is missing")
        val directory = Files.createTempDirectory("shinsou-sandbox-")
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

    private companion object {
        const val RESOURCES_PROPERTY = "compose.application.resources.dir"

        fun helperName(windows: Boolean) = if (windows) "shinsou-sandbox.exe" else "shinsou-sandbox"

        fun defaultDevelopmentCandidates(osName: String): List<Path> {
            val windows = isWindows(osName)
            val relative = "generated/desktopAppResources/${if (windows) "windows" else "macos"}/${helperName(windows)}"
            val workingDirectory = System.getProperty("user.dir").orEmpty()
            return listOf(Path.of(workingDirectory, "composeApp/build/$relative"), Path.of(workingDirectory, "build/$relative"))
        }
    }
}
