package dev.shinsou.kmp.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialized Android TextToSpeech adapter; each service segment is one flushed native utterance. */
internal class AndroidTextToSpeechEngine(
    context: Context,
) : PlatformTextToSpeechEngine {
    private val ready = CompletableDeferred<TextToSpeech?>()
    private val speechMutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var nativeEngine: TextToSpeech? = null

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    private var activeCompletion: CompletableDeferred<SpeechPlaybackStatus>? = null

    override val capability: PlatformSpeechCapability
        get() = if (closed.get()) {
            PlatformSpeechCapability.unavailable("tts_closed")
        } else {
            PlatformSpeechCapability.Available
        }

    init {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val engine = created
            if (status == TextToSpeech.SUCCESS && engine != null && !closed.get()) {
                nativeEngine = engine
                engine.setOnUtteranceProgressListener(progressListener)
                ready.complete(engine)
            } else {
                engine?.shutdown()
                ready.complete(null)
            }
        }
        nativeEngine = created
    }

    override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult = speechMutex.withLock {
        if (closed.get()) return@withLock failed(request, "tts_closed")
        val engine = ready.await() ?: return@withLock failed(request, "tts_android_init_failed")
        request.localeTag?.let { tag ->
            val locale = Locale.forLanguageTag(tag)
            if (locale.language.isBlank() || engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                return@withLock failed(request, "tts_locale_unavailable")
            }
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                return@withLock failed(request, "tts_locale_unavailable")
            }
        }
        if (engine.setSpeechRate(request.rate) == TextToSpeech.ERROR) {
            return@withLock failed(request, "tts_rate_rejected")
        }
        if (engine.setPitch(request.pitch) == TextToSpeech.ERROR) {
            return@withLock failed(request, "tts_pitch_rejected")
        }

        val completion = CompletableDeferred<SpeechPlaybackStatus>()
        activeUtteranceId = request.utteranceId
        activeCompletion = completion
        val accepted = engine.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            request.utteranceId,
        )
        if (accepted == TextToSpeech.ERROR) {
            clearActive(request.utteranceId)
            return@withLock failed(request, "tts_android_speak_rejected")
        }
        try {
            PlatformSpeechResult(request.utteranceId, completion.await())
        } finally {
            clearActive(request.utteranceId)
        }
    }

    override fun stop() {
        activeCompletion?.complete(SpeechPlaybackStatus.CANCELLED)
        nativeEngine?.stop()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        if (!ready.isCompleted) ready.complete(null)
        nativeEngine?.shutdown()
        nativeEngine = null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            complete(utteranceId, SpeechPlaybackStatus.COMPLETED)
        }

        @Deprecated("Android calls the error-code overload on current releases")
        override fun onError(utteranceId: String?) {
            complete(utteranceId, SpeechPlaybackStatus.FAILED)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            complete(utteranceId, SpeechPlaybackStatus.FAILED)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            complete(utteranceId, SpeechPlaybackStatus.CANCELLED)
        }
    }

    private fun complete(utteranceId: String?, status: SpeechPlaybackStatus) {
        if (utteranceId != null && utteranceId == activeUtteranceId) {
            activeCompletion?.complete(status)
        }
    }

    private fun clearActive(utteranceId: String) {
        if (activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            activeCompletion = null
        }
    }

    private fun failed(request: PlatformSpeechRequest, code: String): PlatformSpeechResult =
        PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.FAILED, code)
}
