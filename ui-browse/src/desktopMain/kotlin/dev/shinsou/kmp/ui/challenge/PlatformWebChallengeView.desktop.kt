package dev.shinsou.kmp.ui.challenge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.plugin.SourceWebChallengeRequest
import dev.shinsou.kmp.contract.WebChallengeEmbeddedPolicy
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.shinsou.kmp.plugin.BoundedUtf8LineReader
import dev.shinsou.kmp.plugin.MacOsWebChallengeHelperLocator
import dev.shinsou.kmp.plugin.webChallengeProcessCommand

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

    fun openWindow() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        Thread(::launch, "shinsou-wkwebview-launch").apply {
            isDaemon = true
            start()
        }
    }

    private fun launch() {
        try {
            require(request.url.toHttpUri() != null && request.allowsEmbeddedWebChallenge()) {
                "Embedded browser access is not authorized for this source."
            }
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
                runCatching { writer.close() } // closing during shutdown; the helper is destroyed next
                child.destroy()
                return
            }
            writeLine(writer, webChallengeLaunchLine(request))
            startStderrReader(child)
            readEvents(child)
        } catch (error: Throwable) {
            if (!closed.get()) {
                val diagnostic = webChallengeInitializationDiagnostic(error)
                Log.error("WebChallenge") { "native browser initialization failed: $diagnostic" }
                dispatchError("The native browser could not be initialized.")
            }
        } finally {
            commands.getAndSet(null)?.let { runCatching { it.close() } } // shutdown: "already closed" is fine
            process.getAndSet(null)?.let { child ->
                if (child.isAlive) child.destroy()
            }
            deleteExecutableCopy()
        }
    }

    private fun readEvents(child: Process) {
        try {
            val reader = BoundedUtf8LineReader(child.inputStream)
            while (!closed.get()) {
                val line = reader.readLine(WEB_CHALLENGE_EVENT_MAX_BYTES) ?: break
                val event = WEB_CHALLENGE_JSON.decodeFromString<NativeHelperEvent>(line)
                check(event.isValidForChallenge(request.localStorageKeys))
                when (event.type) {
                    "ready" -> Unit
                    "loaded" -> if (loaded.compareAndSet(false, true)) dispatch(onPageLoaded)
                    "cookies" -> {
                        val captured = event.cookies.orEmpty().map(NativeCookiePayload::toSourceCookie)
                        val userAgent = event.userAgent.orEmpty()
                        val localStorage = event.localStorage.orEmpty()
                        dispatch {
                            onSessionCaptured(
                                WebChallengeCapture(request.capability, captured, userAgent, localStorage),
                            )
                        }
                    }
                    "closed" -> if (!closed.get()) {
                        dispatchError(
                            "The verification window was closed. Reopen it to continue the Cloudflare challenge.",
                        )
                    }
                    "error" -> dispatchError("The native browser encountered an error.")
                }
            }
        } catch (logged: Throwable) {
            Log.warn("WebChallenge", logged) { "Reading native browser events failed; stopping the helper" }
            if (!closed.get()) {
                child.destroyForcibly()
                dispatchError("The native browser returned an invalid response.")
            }
        }
        val exitCode = child.waitFor()
        if (!closed.get() && exitCode != 0) {
            dispatchError("The native browser stopped unexpectedly.")
        }
    }

    private fun startStderrReader(child: Process) {
        Thread(
            {
                try {
                    val reader = BoundedUtf8LineReader(child.errorStream)
                    while (true) {
                        reader.readLine(WEB_CHALLENGE_STDERR_LINE_MAX_BYTES) ?: break
                        // Native diagnostics are deliberately not projected into UI errors.
                    }
                } catch (logged: Throwable) {
                    Log.warn("WebChallenge", logged) { "Reading native browser diagnostics failed; stopping the helper" }
                    if (!closed.get()) child.destroyForcibly()
                }
            },
            "shinsou-wkwebview-stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun capture() {
        if (closed.get()) return
        val writer = commands.get()
        if (writer == null) {
            dispatchError("The native browser is still opening.")
            return
        }
        runCatching { writeLine(writer, "capture") }
            .onFailure { Log.warn("WebChallenge", it) { "Asking the native browser for its cookies failed" } }
            .onFailure { dispatchError("The browser cookies could not be read.") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.getAndSet(null)?.let { writer ->
            // Shutdown is best effort: the helper may already have exited; it is destroyed next.
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
            // Best-effort cleanup of a temporary copy; a leftover is removed on a later run.
            runCatching { Files.deleteIfExists(path) }
            runCatching { Files.deleteIfExists(path.parent) }
        }
    }

    private fun dispatchError(message: String) = dispatch { onError(message) }

    private fun dispatch(block: () -> Unit) {
        SwingUtilities.invokeLater(block)
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
    val embeddedPolicy: String = WebChallengeEmbeddedPolicy.DENY.name,
    val allowedNavigationOrigins: List<String> = emptyList(),
    val allowedSubresourceOrigins: List<String> = emptyList(),
)

@Serializable
private data class NativeHelperEvent(
    val type: String,
    val message: String? = null,
    val cookies: List<NativeCookiePayload>? = null,
    val userAgent: String? = null,
    val localStorage: Map<String, String>? = null,
)

private fun NativeHelperEvent.isValidForChallenge(allowedStorageKeys: List<String>): Boolean {
    if (type.encodeToByteArray().size > 16) return false
    return when (type) {
        "ready", "loaded", "closed" ->
            message == null && cookies == null && userAgent == null && localStorage == null
        "error" -> message != null && message.encodeToByteArray().size <= 512 &&
            cookies == null && userAgent == null && localStorage == null
        "cookies" -> {
            val actualCookies = cookies ?: return false
            val agent = userAgent ?: return false
            val storage = localStorage ?: return false
            val allowlist = normalizeWebChallengeLocalStorageKeys(allowedStorageKeys).toSet()
            actualCookies.size <= WEB_CHALLENGE_COOKIE_MAX_COUNT &&
                agent.isNotBlank() && agent.utf8ByteCountAtMost(WEB_CHALLENGE_USER_AGENT_MAX_BYTES) != null &&
                agent.none { it.code < 32 || it.code == 127 } &&
                actualCookies.all(NativeCookiePayload::isValidCapturedCookie) &&
                actualCookies.sumOf(NativeCookiePayload::boundedByteSize) <=
                WEB_CHALLENGE_COOKIE_AGGREGATE_MAX_BYTES &&
                storage.size <= WEB_CHALLENGE_STORAGE_MAX_COUNT && storage.keys.all(allowlist::contains) &&
                storage.entries.all { (key, value) ->
                    key.utf8ByteCountAtMost(64) != null &&
                        value.utf8ByteCountAtMost(WEB_CHALLENGE_STORAGE_VALUE_MAX_BYTES) != null
                } && storage.entries.sumOf { (key, value) ->
                    key.utf8ByteCountAtMost(64)!! + value.utf8ByteCountAtMost(WEB_CHALLENGE_STORAGE_VALUE_MAX_BYTES)!!
                } <= WEB_CHALLENGE_STORAGE_AGGREGATE_MAX_BYTES && message == null
        }
        else -> false
    }
}

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
    fun boundedByteSize(): Int = name.utf8ByteCountAtMost(256)!! + value.utf8ByteCountAtMost(8_192)!! +
        domain.utf8ByteCountAtMost(255)!! + path.utf8ByteCountAtMost(2_048)!!

    fun isValidCapturedCookie(): Boolean =
        name.isNotEmpty() && value.isNotEmpty() && domain.isNotEmpty() &&
            name.utf8ByteCountAtMost(256) != null && value.utf8ByteCountAtMost(8_192) != null &&
            domain.utf8ByteCountAtMost(255) != null && path.utf8ByteCountAtMost(2_048) != null &&
            name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" } &&
            value.none { it.code < 32 || it.code == 127 || it == ';' } &&
            domain.none { it.code < 32 || it.code == 127 } &&
            path.startsWith('/') && path.none { it.code < 32 || it.code == 127 }

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

/** Counts a String without allocating a second attacker-sized UTF-8 buffer. */
private fun String.utf8ByteCountAtMost(maximum: Int): Int? {
    var bytes = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        val width = when {
            character.code < 0x80 -> 1
            character.code < 0x800 -> 2
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        if (bytes > maximum - width) return null
        bytes += width
        index++
    }
    return bytes
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
            embeddedPolicy = request.embeddedPolicy.name,
            allowedNavigationOrigins = request.allowedNavigationOrigins.sorted(),
            allowedSubresourceOrigins = request.allowedSubresourceOrigins.sorted(),
        ),
    ).also { line ->
        check(line.utf8ByteCountAtMost(WEB_CHALLENGE_LAUNCH_MAX_BYTES) != null) {
            "Native browser launch request is too large"
        }
    }
}

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

// A parse probe: an address that is not an http(s) URI answers null.
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
private const val WEB_CHALLENGE_STDERR_LINE_MAX_BYTES = 1_024
private const val WEB_CHALLENGE_LAUNCH_MAX_BYTES = 2 * 1_024 * 1_024
internal const val WEB_CHALLENGE_EVENT_MAX_BYTES = 26 * 1_024 * 1_024
private const val WEB_CHALLENGE_COOKIE_MAX_COUNT = 256
private const val WEB_CHALLENGE_COOKIE_AGGREGATE_MAX_BYTES = 1 * 1_024 * 1_024
private const val WEB_CHALLENGE_STORAGE_MAX_COUNT = 8
private const val WEB_CHALLENGE_STORAGE_VALUE_MAX_BYTES = 16 * 1_024
private const val WEB_CHALLENGE_STORAGE_AGGREGATE_MAX_BYTES = 32 * 1_024
private const val WEB_CHALLENGE_USER_AGENT_MAX_BYTES = 1_024
