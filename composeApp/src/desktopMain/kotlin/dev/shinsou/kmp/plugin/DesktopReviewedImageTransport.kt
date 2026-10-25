package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.challenge.MacOsWebChallengeHelperLocator
import dev.shinsou.kmp.ui.challenge.BoundedUtf8LineReader
import dev.shinsou.kmp.ui.challenge.webChallengeProcessCommand
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public actual fun createPlatformReviewedImageTransport(): PluginHttpTransport? {
    if (!System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) return null
    val major = System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: return null
    return if (major >= 14) DesktopReviewedImageTransport() else null
}

private class DesktopReviewedImageTransport(
    private val locator: MacOsWebChallengeHelperLocator = MacOsWebChallengeHelperLocator(),
) : PluginHttpTransport {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
        throw IllegalStateException("Reviewed browser image transport requires validated DNS")

    override suspend fun executeResolved(
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse {
        validateReviewedBrowserImageRequest(request, resolution)
        return withContext(Dispatchers.IO) {
            val helper = locator.prepareExecutableCopy()
            var process: Process? = null
            var stage = "ready"
            try {
                withTimeout(30_000) {
                    process = ProcessBuilder(webChallengeProcessCommand(helper)).start()
                    val child = requireNotNull(process)
                    val commands = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))
                    val reader = BoundedUtf8LineReader(child.inputStream)
                    Thread({ child.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) }, "reviewed-image-stderr").apply {
                        isDaemon = true; start()
                    }
                    val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
                    val token = tokenBytes.joinToString("") { "%02x".format(it) }
                    commands.write(REVIEWED_IMAGE_JSON.encodeToString(ReviewedImageLaunch(
                        url = REVIEWED_BROWSER_IMAGE_ORIGIN,
                        allowedNavigationOrigins = listOf(REVIEWED_BROWSER_IMAGE_ORIGIN),
                        allowedSubresourceOrigins = listOf(REVIEWED_BROWSER_IMAGE_ORIGIN),
                        reviewedAddresses = resolution.addresses,
                        proxyToken = token,
                    )))
                    commands.newLine(); commands.flush()
                    suspend fun readEvent(): ReviewedImageEvent {
                        val line = suspendCancellableCoroutine { continuation ->
                            val thread = Thread({
                                try {
                                    val value = requireNotNull(reader.readLine(REVIEWED_IMAGE_EVENT_MAX_BYTES)) {
                                        "Reviewed image helper stopped"
                                    }
                                    if (continuation.isActive) continuation.resume(value)
                                } catch (error: Throwable) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                            }, "reviewed-image-event")
                            thread.isDaemon = true
                            continuation.invokeOnCancellation { child.destroyForcibly() }
                            thread.start()
                        }
                        return REVIEWED_IMAGE_JSON.decodeFromString<ReviewedImageEvent>(line).also {
                            require(it.isValid()) { "Invalid reviewed image helper event" }
                        }
                    }
                    require(readEvent().type == "ready") { "Reviewed image helper did not start" }
                    stage = "loaded"
                    require(readEvent().type == "loaded") { "Reviewed image document did not load" }
                    stage = "poll"
                    suspend fun evaluate(id: String, script: String): String? {
                        commands.write(REVIEWED_IMAGE_JSON.encodeToString(ReviewedImageCommand("evaluate", id, script)))
                        commands.newLine(); commands.flush()
                        val event = readEvent()
                        require(event.type == "evaluated" && event.id == id) { "Reviewed image evaluation failed" }
                        return event.value
                    }
                    require(evaluate("start", reviewedBrowserImageStartScript(request)) == "started")
                    var raw: String? = null
                    for (attempt in 0 until 300) {
                        raw = evaluate("poll", reviewedBrowserImagePollScript())
                        if (raw != null && raw != "null" && raw != "undefined") break
                        delay(50)
                    }
                    runCatching { evaluate("cleanup", reviewedBrowserImageCleanupScript()) }
                    decodeReviewedBrowserImageResult(raw, request.maxResponseBytes)
                        ?: throw IllegalStateException("Reviewed image request timed out")
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("Reviewed image transport timed out during $stage", error)
            } finally {
                process?.destroy()
                process?.waitFor(1, TimeUnit.SECONDS)
                if (process?.isAlive == true) process?.destroyForcibly()
                runCatching { Files.deleteIfExists(helper) }
                runCatching { Files.deleteIfExists(helper.parent) }
            }
        }
    }
}

@Serializable
private data class ReviewedImageLaunch(
    val mode: String = "reviewedImage", val url: String,
    val sourceName: String = "Reviewed image", val userAgent: String = "",
    val cookies: List<String> = emptyList(), val localStorageKeys: List<String> = emptyList(),
    val allowedNavigationOrigins: List<String>, val allowedSubresourceOrigins: List<String>,
    val reviewedAddresses: List<String>, val proxyToken: String,
)

@Serializable private data class ReviewedImageCommand(val type: String, val id: String, val script: String)
@Serializable private data class ReviewedImageEvent(val type: String, val id: String? = null, val value: String? = null)
private fun ReviewedImageEvent.isValid(): Boolean = when (type) {
    "ready", "loaded", "closed" -> id == null && value == null
    "evaluated" -> id != null && id.length in 1..16 && value?.length?.let { it <= REVIEWED_IMAGE_EVENT_MAX_BYTES } != false
    else -> false
}
private val REVIEWED_IMAGE_JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
private const val REVIEWED_IMAGE_EVENT_MAX_BYTES = 5_594_432
