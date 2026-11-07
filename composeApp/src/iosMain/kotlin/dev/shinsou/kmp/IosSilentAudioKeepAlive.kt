package dev.shinsou.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.dictionaryWithContentsOfFile
import platform.Foundation.dataWithBytes
import platform.darwin.NSObjectProtocol

/**
 * Keeps the process running in the background by looping silent audio.
 *
 * Used for downloads only when the user opts in: where iOS grants no background task time (an app
 * run inside LiveContainer cannot register BGTaskScheduler identifiers, but its host declares the
 * `audio` background mode), playing audio is the one way to keep downloading. The session mixes
 * with other audio, so the user's music is never interrupted. Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSilentAudioKeepAlive(
    /**
     * True when another app's audio (a call, an alarm) stopped ours; false when it may play again
     * (the interruption ended, or the media services were reset and the player must be rebuilt).
     */
    private val onInterrupted: (Boolean) -> Unit,
) {
    private var player: AVAudioPlayer? = null
    private var observers: List<NSObjectProtocol> = emptyList()

    val isPlaying: Boolean get() = player?.playing == true

    /** Starts the loop; false when audio cannot keep this process alive or could not start. */
    fun start(): Boolean {
        if (isPlaying) return true
        if (!available) return false
        val session = AVAudioSession.sharedInstance()
        val configured = session.setCategory(
            AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionMixWithOthers,
            error = null,
        ) && session.setActive(true, error = null)
        if (!configured) return false
        // A stopped player may belong to media services that have since been reset: start afresh.
        player?.stop()
        val next = AVAudioPlayer(data = silentWav, error = null).apply {
            numberOfLoops = -1
            volume = 0f
            prepareToPlay()
        }
        player = next
        observeSession()
        return next.play()
    }

    /**
     * Stops the loop. The session is left active: the reader's volume-key support shares it, and
     * an idle session that mixes with others costs nothing.
     */
    fun stop() {
        player?.stop()
        player = null
        observers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        observers = emptyList()
    }

    private fun observeSession() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        val session = AVAudioSession.sharedInstance()
        observers = listOf(
            center.addObserverForName(
                name = AVAudioSessionInterruptionNotification,
                `object` = session,
                queue = NSOperationQueue.mainQueue,
            ) { notification: NSNotification? ->
                val type = (notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)
                    ?.unsignedLongValue
                onInterrupted(type == AVAudioSessionInterruptionTypeBegan)
            },
            center.addObserverForName(
                name = AVAudioSessionMediaServicesWereResetNotification,
                `object` = session,
                queue = NSOperationQueue.mainQueue,
            ) { _: NSNotification? ->
                player = null
                onInterrupted(false)
            },
        )
    }

    companion object {
        /**
         * Whether the running process may play audio in the background. iOS reads the
         * `UIBackgroundModes` of the app that owns the process: LiveContainer's own Info.plist
         * when we run inside it (which declares `audio`), ours otherwise (which does not, so
         * that the reader's silent volume-key player never keeps a normal install awake).
         * LiveContainer presents our bundle as the main bundle, so the executable's own
         * Info.plist is read as well.
         */
        val available: Boolean by lazy {
            val executable = (NSProcessInfo.processInfo.arguments.firstOrNull() as? String).orEmpty()
            val hostInfo = executable.substringBeforeLast('/', missingDelimiterValue = "")
                .takeIf(String::isNotEmpty)
                ?.let { NSDictionary.dictionaryWithContentsOfFile("$it/Info.plist") }
            val declared = listOfNotNull(
                hostInfo?.get("UIBackgroundModes"),
                NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes"),
            )
            declared.any { modes -> (modes as? List<*>)?.contains("audio") == true }
        }

        /** 100 ms of 8 kHz unsigned 8-bit mono silence, the same clip the volume-key support plays. */
        private val silentWav: NSData by lazy {
            val sampleRate = 8_000
            val dataSize = sampleRate / 10
            val bytes = ByteArray(44 + dataSize)
            var offset = 0
            fun ascii(text: String) = text.forEach { bytes[offset++] = it.code.toByte() }
            fun int32(value: Int) = repeat(4) { bytes[offset++] = (value shr (8 * it)).toByte() }
            fun int16(value: Int) = repeat(2) { bytes[offset++] = (value shr (8 * it)).toByte() }
            ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); int32(16); int16(1); int16(1); int32(sampleRate); int32(sampleRate); int16(1); int16(8)
            ascii("data"); int32(dataSize)
            bytes.fill(0x80.toByte(), fromIndex = 44)
            bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        }
    }
}
