package dev.shinsou.kmp.tts

import dev.shinsou.kmp.desktop.DesktopPlatform
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** macOS `say` and Windows System.Speech adapter. Sensitive text is written only to process stdin. */
internal class DesktopTextToSpeechEngine(
    private val platform: DesktopPlatform = DesktopPlatform.current,
) : PlatformTextToSpeechEngine {
    private val speechMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    override val capability: PlatformSpeechCapability = when (platform) {
        DesktopPlatform.MAC_OS, DesktopPlatform.WINDOWS -> PlatformSpeechCapability.Available
        DesktopPlatform.LINUX -> PlatformSpeechCapability.unavailable("tts_unavailable_linux")
        DesktopPlatform.OTHER -> PlatformSpeechCapability.unavailable("tts_unavailable_platform")
    }

    override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult = withContext(Dispatchers.IO) {
        speechMutex.withLock {
            if (closed.get() || !capability.available) {
                return@withLock PlatformSpeechResult(
                    request.utteranceId,
                    SpeechPlaybackStatus.FAILED,
                    capability.unavailableReasonCode ?: "tts_closed",
                )
            }
            stopRequested.set(false)
            val command = commandFor(request)
            val native = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Throwable) {
                return@withLock PlatformSpeechResult(
                    request.utteranceId,
                    SpeechPlaybackStatus.FAILED,
                    "tts_native_start_failed",
                )
            }
            process = native
            try {
                native.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(request.text)
                }
                // Drain bounded diagnostic output so a native process cannot block on its pipe.
                native.inputStream.use { input ->
                    val buffer = ByteArray(1_024)
                    var total = 0
                    while (total < MAX_DIAGNOSTIC_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_DIAGNOSTIC_BYTES - total))
                        if (read < 0) break
                        total += read
                    }
                }
                val exit = native.waitFor()
                when {
                    stopRequested.get() -> PlatformSpeechResult(
                        request.utteranceId,
                        SpeechPlaybackStatus.CANCELLED,
                    )
                    exit == 0 -> PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.COMPLETED)
                    else -> PlatformSpeechResult(
                        request.utteranceId,
                        SpeechPlaybackStatus.FAILED,
                        "tts_native_exit_$exit",
                    )
                }
            } catch (_: Throwable) {
                PlatformSpeechResult(
                    request.utteranceId,
                    if (stopRequested.get()) SpeechPlaybackStatus.CANCELLED else SpeechPlaybackStatus.FAILED,
                    if (stopRequested.get()) null else "tts_native_io_failed",
                )
            } finally {
                process = null
                native.destroy()
            }
        }
    }

    override fun stop() {
        stopRequested.set(true)
        process?.destroy()
    }

    override fun close() {
        closed.set(true)
        stop()
    }

    private fun commandFor(request: PlatformSpeechRequest): List<String> = when (platform) {
        DesktopPlatform.MAC_OS -> listOf(
            "/usr/bin/say",
            "--rate",
            (DEFAULT_MAC_WORDS_PER_MINUTE * request.rate).toInt().coerceIn(80, 500).toString(),
        )
        DesktopPlatform.WINDOWS -> listOf(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            windowsSpeechScript(request),
        )
        DesktopPlatform.LINUX,
        DesktopPlatform.OTHER,
        -> emptyList()
    }

    private fun windowsSpeechScript(request: PlatformSpeechRequest): String {
        val rate = ((request.rate - 1f) * 5f).toInt().coerceIn(-10, 10)
        return "Add-Type -AssemblyName System.Speech; " +
            "\$t=[Console]::In.ReadToEnd(); " +
            "\$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$s.Rate=$rate; \$s.Speak(\$t); \$s.Dispose()"
    }

    private companion object {
        const val DEFAULT_MAC_WORDS_PER_MINUTE: Int = 175
        const val MAX_DIAGNOSTIC_BYTES: Int = 8_192
    }
}
