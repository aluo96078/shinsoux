package dev.shinsou.kmp.tts

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** AVSpeechSynthesizer adapter retained for the lifetime of the shared application composition. */
@OptIn(ExperimentalForeignApi::class)
internal class IosTextToSpeechEngine : PlatformTextToSpeechEngine {
    private val synthesizer = AVSpeechSynthesizer()
    private val speechMutex = Mutex()
    private val delegate = SpeechDelegate(::completeActive)

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var activeUtterance: AVSpeechUtterance? = null

    @Volatile
    private var activeCompletion: CompletableDeferred<SpeechPlaybackStatus>? = null

    override val capability: PlatformSpeechCapability
        get() = if (closed) {
            PlatformSpeechCapability.unavailable("tts_closed")
        } else {
            PlatformSpeechCapability.Available
        }

    init {
        synthesizer.delegate = delegate
    }

    override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult = speechMutex.withLock {
        if (closed) return@withLock failed(request, "tts_closed")
        val completion = CompletableDeferred<SpeechPlaybackStatus>()
        val utterance = withContext(Dispatchers.Main) {
            if (closed) return@withContext null
            val nativeUtterance = AVSpeechUtterance(string = request.text)
            if (request.localeTag != null) {
                val voice = AVSpeechSynthesisVoice.voiceWithLanguage(request.localeTag)
                    ?: return@withContext null
                nativeUtterance.voice = voice
            }
            nativeUtterance.rate = (AVSpeechUtteranceDefaultSpeechRate * request.rate)
                .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
            nativeUtterance.pitchMultiplier = request.pitch
            activeUtterance = nativeUtterance
            activeCompletion = completion
            synthesizer.speakUtterance(nativeUtterance)
            nativeUtterance
        } ?: return@withLock failed(request, "tts_locale_unavailable")
        try {
            PlatformSpeechResult(request.utteranceId, completion.await())
        } finally {
            if (activeUtterance === utterance) {
                activeUtterance = null
                activeCompletion = null
            }
        }
    }

    override fun stop() {
        activeCompletion?.complete(SpeechPlaybackStatus.CANCELLED)
        dispatch_async(dispatch_get_main_queue()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        synthesizer.delegate = null
    }

    private fun completeActive(utterance: AVSpeechUtterance, status: SpeechPlaybackStatus) {
        if (activeUtterance === utterance) activeCompletion?.complete(status)
    }

    private fun failed(request: PlatformSpeechRequest, code: String): PlatformSpeechResult =
        PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.FAILED, code)

    private class SpeechDelegate(
        private val completion: (AVSpeechUtterance, SpeechPlaybackStatus) -> Unit,
    ) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            completion(didFinishSpeechUtterance, SpeechPlaybackStatus.COMPLETED)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            completion(didCancelSpeechUtterance, SpeechPlaybackStatus.CANCELLED)
        }
    }
}
