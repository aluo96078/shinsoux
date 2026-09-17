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
import dev.shinsou.kmp.ui.WebChallengeEmbeddedPolicy
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
                dispatchError("The native browser could not be initialized.")
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
        } catch (_: Throwable) {
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
                } catch (_: Throwable) {
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

internal class BoundedUtf8LineReader(input: InputStream) {
    private val input = BufferedInputStream(input, IPC_READ_CHUNK_BYTES)
    private val chunk = ByteArray(IPC_READ_CHUNK_BYTES)
    private var position = 0
    private var limit = 0

    /** Returns a newline-delimited UTF-8 frame. EOF with a partial frame fails closed. */
    fun readLine(maxBytes: Int): String? {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        while (true) {
            if (position == limit) {
                limit = input.read(chunk)
                position = 0
                if (limit < 0) {
                    if (bytes.size() == 0) return null
                    throw EOFException("Truncated IPC frame")
                }
            }
            var newline = position
            while (newline < limit && chunk[newline] != '\n'.code.toByte()) newline++
            val count = newline - position
            if (bytes.size() > maxBytes - count) throw IOException("IPC frame exceeds safe limit")
            bytes.write(chunk, position, count)
            position = newline
            if (position < limit) {
                position++
                val framed = bytes.toByteArray().let { raw ->
                    if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
                }
                return try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(framed))
                        .toString()
                } catch (error: Exception) {
                    throw IOException("Invalid UTF-8 IPC frame", error)
                }
            }
        }
    }

    private companion object {
        const val IPC_READ_CHUNK_BYTES: Int = 8 * 1_024
    }
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
private const val WEB_CHALLENGE_STDERR_LINE_MAX_BYTES = 1_024
private const val WEB_CHALLENGE_LAUNCH_MAX_BYTES = 2 * 1_024 * 1_024
internal const val WEB_CHALLENGE_EVENT_MAX_BYTES = 26 * 1_024 * 1_024
private const val WEB_CHALLENGE_COOKIE_MAX_COUNT = 256
private const val WEB_CHALLENGE_COOKIE_AGGREGATE_MAX_BYTES = 1 * 1_024 * 1_024
private const val WEB_CHALLENGE_STORAGE_MAX_COUNT = 8
private const val WEB_CHALLENGE_STORAGE_VALUE_MAX_BYTES = 16 * 1_024
private const val WEB_CHALLENGE_STORAGE_AGGREGATE_MAX_BYTES = 32 * 1_024
private const val WEB_CHALLENGE_USER_AGENT_MAX_BYTES = 1_024
