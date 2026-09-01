package dev.shinsou.kmp.ui.challenge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual val platformWebChallengeMode: PlatformWebChallengeMode =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        PlatformWebChallengeMode.Embedded
    } else {
        PlatformWebChallengeMode.ExternalBrowserOnly
    }

@Composable
internal actual fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onSessionCaptured: (WebChallengeCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val currentPageLoaded = rememberUpdatedState(onPageLoaded)
    val currentSessionCaptured = rememberUpdatedState(onSessionCaptured)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request) {
        MacOsWebChallengeState(
            request = request,
            onPageLoaded = { currentPageLoaded.value.invoke() },
            onSessionCaptured = { currentSessionCaptured.value.invoke(it) },
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state) {
        state.openWindow()
    }
    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) state.capture()
    }
    DisposableEffect(state) {
        onDispose(state::close)
    }

    // WKWebView owns a separate native window. This Compose area intentionally remains empty so
    // Skiko and WebKit never share a macOS rendering surface.
    Box(modifier.fillMaxSize())
}

private class MacOsWebChallengeState(
    private val request: SourceWebChallengeRequest,
    private val onPageLoaded: () -> Unit,
    private val onSessionCaptured: (WebChallengeCapture) -> Unit,
    private val onError: (String) -> Unit,
    private val helperLocator: MacOsWebChallengeHelperLocator = MacOsWebChallengeHelperLocator(),
) {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val process = AtomicReference<Process?>(null)
    private val commands = AtomicReference<BufferedWriter?>(null)
    private val executableCopy = AtomicReference<Path?>(null)
    private val stderrTail = ArrayDeque<String>()
    private val stderrLock = Any()

    fun openWindow() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        Thread(::launch, "shinsou-wkwebview-launch").apply {
            isDaemon = true
            start()
        }
    }

    private fun launch() {
        try {
            require(request.url.toHttpUri() != null) { "The source URL is invalid." }
            val helper = helperLocator.prepareExecutableCopy().also(executableCopy::set)
            if (closed.get()) return
            val child = ProcessBuilder(webChallengeProcessCommand(helper)).start()
            process.set(child)
            if (closed.get()) {
                child.destroy()
                return
            }
            val writer = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))
            commands.set(writer)
            if (closed.get()) {
                commands.compareAndSet(writer, null)
                runCatching { writer.close() }
                child.destroy()
                return
            }
            writeLine(writer, webChallengeLaunchLine(request))
            startStderrReader(child)
            readEvents(child)
        } catch (error: Throwable) {
            if (!closed.get()) {
                val diagnostic = webChallengeInitializationDiagnostic(error)
                System.err.println("Native browser initialization failed: $diagnostic")
                dispatchError("The native browser could not be initialized ($diagnostic).")
            }
        } finally {
            commands.getAndSet(null)?.let { runCatching { it.close() } }
            process.getAndSet(null)?.let { child ->
                if (child.isAlive) child.destroy()
            }
            deleteExecutableCopy()
        }
    }

    private fun readEvents(child: Process) {
        BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                if (closed.get()) return@forEach
                val event = runCatching { WEB_CHALLENGE_JSON.decodeFromString<NativeHelperEvent>(line) }
                    .getOrElse {
                        dispatchError("The native browser returned an invalid response.")
                        return@forEach
                    }
                when (event.type) {
                    "ready" -> Unit
                    "loaded" -> if (loaded.compareAndSet(false, true)) dispatch(onPageLoaded)
                    "cookies" -> {
                        val captured = event.cookies.orEmpty().map(NativeCookiePayload::toSourceCookie)
                        val userAgent = event.userAgent.orEmpty()
                        val localStorage = event.localStorage.orEmpty()
                        dispatch { onSessionCaptured(WebChallengeCapture(captured, userAgent, localStorage)) }
                    }
                    "closed" -> if (!closed.get()) {
                        dispatchError(
                            "The verification window was closed. Reopen it to continue the Cloudflare challenge.",
                        )
                    }
                    "error" -> dispatchError(
                        event.message?.takeIf(String::isNotBlank)
                            ?: "The native browser encountered an unknown error.",
                    )
                }
            }
        }
        val exitCode = child.waitFor()
        if (!closed.get() && exitCode != 0) {
            val suffix = stderrDiagnostic()?.let { " ($it)" }.orEmpty()
            dispatchError("The native browser stopped unexpectedly$suffix.")
        }
    }

    private fun startStderrReader(child: Process) {
        Thread(
            {
                BufferedReader(InputStreamReader(child.errorStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { raw ->
                        val safe = sanitizeDiagnosticLine(raw) ?: return@forEach
                        synchronized(stderrLock) {
                            if (stderrTail.size == WEB_CHALLENGE_STDERR_MAX_LINES) {
                                stderrTail.removeFirst()
                            }
                            stderrTail.addLast(safe)
                        }
                    }
                }
            },
            "shinsou-wkwebview-stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun stderrDiagnostic(): String? = synchronized(stderrLock) {
        stderrTail.lastOrNull()
    }

    fun capture() {
        if (closed.get()) return
        val writer = commands.get()
        if (writer == null) {
            dispatchError("The native browser is still opening.")
            return
        }
        runCatching { writeLine(writer, "capture") }
            .onFailure { dispatchError("The browser cookies could not be read.") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.getAndSet(null)?.let { writer ->
            runCatching { writeLine(writer, "close") }
            runCatching { writer.close() }
        }
        process.getAndSet(null)?.let { child ->
            if (child.isAlive) child.destroy()
        }
        deleteExecutableCopy()
    }

    private fun deleteExecutableCopy() {
        executableCopy.getAndSet(null)?.let { path ->
            runCatching { Files.deleteIfExists(path) }
            runCatching { Files.deleteIfExists(path.parent) }
        }
    }

    private fun dispatchError(message: String) = dispatch { onError(message) }

    private fun dispatch(block: () -> Unit) {
        SwingUtilities.invokeLater(block)
    }
}

internal class MacOsWebChallengeHelperLocator(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val resourcesDirectory: String? = System.getProperty(WEB_CHALLENGE_RESOURCES_PROPERTY),
    private val developmentCandidates: List<Path> = defaultDevelopmentHelperCandidates(),
) {
    fun resolve(): Path {
        check(osName.lowercase().contains("mac")) {
            "The native WKWebView helper is available only on macOS"
        }
        val candidates = buildList {
            resourcesDirectory
                ?.takeIf(String::isNotBlank)
                ?.let { add(Path.of(it).resolve(WEB_CHALLENGE_HELPER_NAME)) }
            addAll(developmentCandidates)
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: throw IllegalStateException("The packaged WKWebView helper is missing")
    }

    fun prepareExecutableCopy(): Path {
        val source = resolve()
        val directory = Files.createTempDirectory("shinsou-wkwebview-")
        val target = directory.resolve(WEB_CHALLENGE_HELPER_NAME)
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            runCatching {
                Files.setPosixFilePermissions(
                    target,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }.getOrElse {
                check(target.toFile().setExecutable(true, true)) {
                    "The WKWebView helper is not executable"
                }
            }
            return target
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(target) }
            runCatching { Files.deleteIfExists(directory) }
            throw error
        }
    }
}

@Serializable
private data class NativeChallengeLaunch(
    val url: String,
    val sourceName: String,
    val userAgent: String,
    val cookies: List<NativeCookiePayload>,
    val localStorageKeys: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
)

@Serializable
private data class NativeHelperEvent(
    val type: String,
    val message: String? = null,
    val cookies: List<NativeCookiePayload>? = null,
    val userAgent: String? = null,
    val localStorage: Map<String, String>? = null,
)

@Serializable
private data class NativeCookiePayload(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = !domain.startsWith('.'),
) {
    fun toSourceCookie(): SourceCookie = SourceCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAtEpochMillis = expiresAtEpochMillis,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
    )
}

internal fun webChallengeLaunchLine(request: SourceWebChallengeRequest): String {
    val credentials = request.username
        ?.takeIf(String::isNotBlank)
        ?.let { username ->
            request.password
                ?.takeIf(String::isNotEmpty)
                ?.let { password -> username to password }
        }
    return WEB_CHALLENGE_JSON.encodeToString(
        NativeChallengeLaunch(
            url = request.url,
            sourceName = request.sourceName,
            userAgent = request.userAgent,
            cookies = webChallengeSeedCookies(request).map { cookie ->
                NativeCookiePayload(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                )
            },
            localStorageKeys = normalizeWebChallengeLocalStorageKeys(request.localStorageKeys),
            username = credentials?.first,
            password = credentials?.second,
        ),
    )
}

/** Keeps the launch command free of URLs, credentials, and cookies; those travel over stdin. */
internal fun webChallengeProcessCommand(helper: Path): List<String> =
    listOf(helper.toAbsolutePath().toString())

internal fun webChallengeInitializationDiagnostic(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(12)
    val relevant = chain.lastOrNull { cause ->
        cause is java.io.IOException ||
            cause is SecurityException ||
            cause is IllegalStateException
    } ?: error
    val type = relevant::class.simpleName ?: "runtime error"
    val detail = sanitizeDiagnosticLine(relevant.message.orEmpty())
    return if (detail == null) type else "$type: $detail"
}

private fun sanitizeDiagnosticLine(raw: String): String? {
    val line = raw.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
    // Native diagnostics are never allowed to echo protocol JSON, URLs, cookies, or credentials.
    if (line.contains('{') || line.contains('}') || line.contains("http://") || line.contains("https://")) {
        return "native runtime error"
    }
    return line.take(160)
}

private fun writeLine(writer: BufferedWriter, value: String) = synchronized(writer) {
    writer.write(value)
    writer.newLine()
    writer.flush()
}

private fun defaultDevelopmentHelperCandidates(): List<Path> {
    val workingDirectory = Path.of(System.getProperty("user.dir").orEmpty())
    return listOf(
        workingDirectory.resolve("composeApp/build/generated/desktopAppResources/macos/$WEB_CHALLENGE_HELPER_NAME"),
        workingDirectory.resolve("build/generated/desktopAppResources/macos/$WEB_CHALLENGE_HELPER_NAME"),
    )
}

private fun String.toHttpUri(): URI? = runCatching { URI.create(trim()) }
    .getOrNull()
    ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }

private val WEB_CHALLENGE_JSON = Json {
    ignoreUnknownKeys = false
    // Swift's Codable launch payload has no Kotlin-style default-value metadata. Always include
    // every non-null CookiePayload field (path and boolean flags included), otherwise a perfectly
    // valid stored cookie using defaults makes JSONDecoder reject the entire launch request.
    encodeDefaults = true
    explicitNulls = false
}
private const val WEB_CHALLENGE_HELPER_NAME = "shinsou-web-challenge"
private const val WEB_CHALLENGE_RESOURCES_PROPERTY = "compose.application.resources.dir"
private const val WEB_CHALLENGE_STDERR_MAX_LINES = 8
