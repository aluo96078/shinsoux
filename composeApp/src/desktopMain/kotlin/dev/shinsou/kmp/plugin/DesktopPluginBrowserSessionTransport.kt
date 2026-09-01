package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.challenge.MacOsWebChallengeHelperLocator
import dev.shinsou.kmp.ui.challenge.webChallengeProcessCommand
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.web.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Browser-backed Desktop transport. macOS uses native WKWebView so TLS and HTTP identity match
 * Safari/WebKit; other Desktop hosts retain JavaFX WebKit as their platform fallback.
 */
public class DesktopPluginBrowserSessionTransport : PluginBrowserSessionTransport {
    private val delegate: PluginBrowserSessionTransport =
        if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
            MacOsPluginBrowserSessionTransport()
        } else {
            JavaFxPluginBrowserSessionTransport()
        }

    override suspend fun execute(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
    ): PluginHttpResponse = delegate.execute(sourceId, sourceOrigin, allowedOrigins, request)

    override suspend fun close() {
        delegate.close()
    }
}

/** Native WKWebView transport hosted in the already-packaged macOS browser helper. */
private class MacOsPluginBrowserSessionTransport(
    private val helperLocator: MacOsWebChallengeHelperLocator = MacOsWebChallengeHelperLocator(),
) : PluginBrowserSessionTransport {
    private val mutex = Mutex()
    private val states = mutableMapOf<Long, MacOsBrowserSessionState>()

    override suspend fun execute(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
    ): PluginHttpResponse {
        val prepared = preparePluginBrowserSessionRequest(sourceOrigin, allowedOrigins, request)
        return mutex.withLock {
            val existing = states[sourceId]
            val state = if (existing?.sourceOrigin == prepared.sourceOrigin) {
                existing
            } else {
                existing?.release()
                createState(prepared.sourceOrigin).also { states[sourceId] = it }
            }
            state.ready.await()
            executePluginBrowserSessionFetch(prepared, state::evaluate)
        }
    }

    private suspend fun createState(sourceOrigin: String): MacOsBrowserSessionState =
        withContext(Dispatchers.IO) {
            val helper = helperLocator.prepareExecutableCopy()
            try {
                val process = ProcessBuilder(webChallengeProcessCommand(helper)).start()
                MacOsBrowserSessionState(sourceOrigin, helper, process).also { state ->
                    state.start()
                    state.sendLaunch()
                }
            } catch (error: Throwable) {
                deleteNativeHelperCopy(helper)
                throw error
            }
        }

    override suspend fun close() {
        mutex.withLock {
            states.values.forEach { it.release() }
            states.clear()
        }
    }
}

private class MacOsBrowserSessionState(
    val sourceOrigin: String,
    private val helperCopy: Path,
    private val process: Process,
) {
    val ready: CompletableDeferred<Unit> = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val nextEvaluationId = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val commands = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

    fun start() {
        Thread(::readEvents, "shinsou-wkwebview-session-events").apply {
            isDaemon = true
            start()
        }
        // Always drain stderr. Native diagnostics are intentionally not projected into plugin
        // errors because they can contain WebKit implementation details.
        Thread(
            {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    while (reader.readLine() != null) {
                        // Intentionally discarded.
                    }
                }
            },
            "shinsou-wkwebview-session-stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun sendLaunch() {
        writeProtocolLine(
            NATIVE_BROWSER_SESSION_JSON.encodeToString(
                NativeBrowserSessionLaunch(url = "$sourceOrigin/robots.txt"),
            ),
        )
    }

    suspend fun evaluate(script: String): String? {
        check(script.encodeToByteArray().size <= NATIVE_BROWSER_SESSION_MAX_SCRIPT_BYTES) {
            "Browser-session script is too large"
        }
        check(!closed.get()) { "Browser-session helper is closed" }
        val id = nextEvaluationId.incrementAndGet().toString(36)
        val result = CompletableDeferred<String?>()
        pending[id] = result
        try {
            writeProtocolLine(
                NATIVE_BROWSER_SESSION_JSON.encodeToString(
                    NativeBrowserSessionCommand(type = "evaluate", id = id, script = script),
                ),
            )
            return result.await()
        } finally {
            pending.remove(id, result)
        }
    }

    suspend fun release(): Unit = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        runCatching {
            writeProtocolLine(
                NATIVE_BROWSER_SESSION_JSON.encodeToString(NativeBrowserSessionCommand(type = "close")),
            )
        }
        runCatching { commands.close() }
        if (process.isAlive) process.destroy()
        val error = IllegalStateException("Browser-session helper was closed")
        if (!ready.isCompleted) ready.completeExceptionally(error)
        failPending(error)
        deleteNativeHelperCopy(helperCopy)
    }

    private fun readEvents() {
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val event = runCatching {
                        NATIVE_BROWSER_SESSION_JSON.decodeFromString<NativeBrowserSessionEvent>(line)
                    }.getOrElse {
                        fail(IllegalStateException("Native browser-session response was invalid"))
                        return@forEach
                    }
                    when (event.type) {
                        "ready" -> Unit
                        "loaded" -> if (!ready.isCompleted) ready.complete(Unit)
                        "evaluated" -> event.id?.let { id -> pending.remove(id)?.complete(event.value) }
                        "error" -> {
                            val error = IllegalStateException(
                                event.message?.takeIf(String::isNotBlank)
                                    ?: "Native browser-session request failed",
                            )
                            val handled = event.id?.let { id -> pending.remove(id)?.completeExceptionally(error) }
                                ?: false
                            if (!handled) fail(error)
                        }
                        "closed" -> if (!closed.get()) fail(
                            IllegalStateException("Native browser-session helper closed unexpectedly"),
                        )
                    }
                }
            }
            if (!closed.get()) fail(IllegalStateException("Native browser-session helper stopped unexpectedly"))
        } finally {
            if (!closed.get()) runCatching { process.destroy() }
        }
    }

    private fun fail(error: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        failPending(error)
    }

    private fun failPending(error: Throwable) {
        pending.entries.toList().forEach { (id, deferred) ->
            if (pending.remove(id, deferred)) deferred.completeExceptionally(error)
        }
    }

    private fun writeProtocolLine(value: String) = synchronized(commands) {
        check(!closed.get() || value.contains("\"type\":\"close\"")) {
            "Browser-session helper is closed"
        }
        commands.write(value)
        commands.newLine()
        commands.flush()
    }
}

@Serializable
private data class NativeBrowserSessionLaunch(
    val mode: String = "browserSession",
    val url: String,
    val sourceName: String = "Shinsou browser session",
    val userAgent: String = "",
    val cookies: List<NativeBrowserSessionCookie> = emptyList(),
    val localStorageKeys: List<String> = emptyList(),
)

@Serializable
private data class NativeBrowserSessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = true,
)

@Serializable
private data class NativeBrowserSessionCommand(
    val type: String,
    val id: String? = null,
    val script: String? = null,
)

@Serializable
private data class NativeBrowserSessionEvent(
    val type: String,
    val message: String? = null,
    val id: String? = null,
    val value: String? = null,
)

private val NATIVE_BROWSER_SESSION_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
// A 512 KiB request body can expand several times when it is JSON-escaped into the Fetch script.
private const val NATIVE_BROWSER_SESSION_MAX_SCRIPT_BYTES: Int = 4 * 1_024 * 1_024

private fun deleteNativeHelperCopy(path: Path) {
    runCatching { Files.deleteIfExists(path) }
    runCatching { Files.deleteIfExists(path.parent) }
}

/** JavaFX WebKit fallback for non-macOS Desktop hosts. */
private class JavaFxPluginBrowserSessionTransport : PluginBrowserSessionTransport {
    private val initialized = AtomicBoolean(false)
    private val mutex = Mutex()
    private val states = mutableMapOf<Long, JavaFxBrowserSessionState>()

    override suspend fun execute(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
    ): PluginHttpResponse {
        val prepared = preparePluginBrowserSessionRequest(sourceOrigin, allowedOrigins, request)
        return mutex.withLock {
            ensureJavaFx()
            val existing = states[sourceId]
            val state = if (existing?.sourceOrigin == prepared.sourceOrigin) {
                existing
            } else {
                existing?.release()
                createState(prepared.sourceOrigin).also { states[sourceId] = it }
            }
            state.ready.await()
            executePluginBrowserSessionFetch(prepared, state::evaluate)
        }
    }

    private fun ensureJavaFx() {
        if (initialized.compareAndSet(false, true)) JFXPanel()
    }

    private suspend fun createState(sourceOrigin: String): JavaFxBrowserSessionState {
        val created = CompletableDeferred<JavaFxBrowserSessionState>()
        Platform.runLater {
            val ready = CompletableDeferred<Unit>()
            val webView = WebView().apply {
                isContextMenuEnabled = false
                engine.isJavaScriptEnabled = true
                engine.loadWorker.stateProperty().addListener { _, _, next ->
                    when (next) {
                        Worker.State.SUCCEEDED -> if (!ready.isCompleted) ready.complete(Unit)
                        Worker.State.CANCELLED, Worker.State.FAILED -> if (!ready.isCompleted) {
                            ready.completeExceptionally(
                                IllegalStateException("Browser-session bootstrap failed"),
                            )
                        }
                        else -> Unit
                    }
                }
                engine.load("$sourceOrigin/robots.txt")
            }
            created.complete(JavaFxBrowserSessionState(sourceOrigin, webView, ready))
        }
        return created.await()
    }

    override suspend fun close() {
        mutex.withLock {
            states.values.forEach { it.release() }
            states.clear()
        }
    }
}

private class JavaFxBrowserSessionState(
    val sourceOrigin: String,
    val webView: WebView,
    val ready: CompletableDeferred<Unit>,
) {
    suspend fun evaluate(script: String): String? {
        val result = CompletableDeferred<String?>()
        Platform.runLater {
            runCatching { webView.engine.executeScript(script)?.toString() }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.await()
    }

    suspend fun release() {
        val released = CompletableDeferred<Unit>()
        Platform.runLater {
            if (!ready.isCompleted) ready.cancel()
            webView.engine.load(null)
            released.complete(Unit)
        }
        released.await()
    }
}
