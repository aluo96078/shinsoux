package dev.shinsou.kmp.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Apple's Translation framework, reached through Swift (it has no Objective-C API). The app's
 * Swift code implements this on iOS 26 and later and registers it at launch with
 * [registerAppleTranslation]; on older systems nothing is registered and the option is not offered.
 */
public interface AppleTranslationBridge {
    /** Names the models in use, so translations made with different ones are stored apart. */
    public val engineName: String

    /** [completion] gets 0 installed, 1 downloadable, 2 unsupported. */
    public fun status(from: String, to: String, completion: (Int) -> Unit)

    /**
     * [completion] gets the lines translated one for one, or null and a reason. The returned
     * handle stops the work when the reader no longer wants it (a chapter left, a cancelled queue).
     */
    public fun translate(from: String, to: String, lines: List<String>, completion: (List<String>?, String?) -> Unit): AppleTranslationCancellable

    /** Shows the system's download prompt; [completion] gets whether the languages are installed after it. */
    public fun download(from: String, to: String, completion: (Boolean) -> Unit)
}

/** Stops a translation the bridge started; called at most once, possibly after it finished. */
public interface AppleTranslationCancellable {
    public fun cancel()
}

internal object AppleTranslationRegistry {
    @Volatile
    var bridge: AppleTranslationBridge? = null
}

/** Called by Swift at launch, on systems that have the framework. */
public fun registerAppleTranslation(bridge: AppleTranslationBridge) {
    AppleTranslationRegistry.bridge = bridge
}

/**
 * [SystemTranslationEngine] over the registered bridge. Read at each call, so it works however
 * early the app's services were built relative to the Swift registration.
 */
public object IosSystemTranslationEngine : SystemTranslationEngine {
    override val name: String get() = AppleTranslationRegistry.bridge?.engineName ?: "apple"

    private fun bridge(): AppleTranslationBridge =
        AppleTranslationRegistry.bridge ?: throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "This system has no translation framework")

    override suspend fun status(from: String, to: String): SystemLanguageStatus = suspendCancellableCoroutine { continuation ->
        bridge().status(appleLanguage(from), appleLanguage(to)) { code ->
            continuation.resume(
                when (code) {
                    0 -> SystemLanguageStatus.INSTALLED
                    1 -> SystemLanguageStatus.DOWNLOADABLE
                    else -> SystemLanguageStatus.UNSUPPORTED
                },
            )
        }
    }

    override suspend fun translate(from: String, to: String, lines: List<String>): List<String> =
        suspendCancellableCoroutine { continuation ->
            val running = bridge().translate(appleLanguage(from), appleLanguage(to), lines) { answers, reason ->
                if (answers != null) {
                    continuation.resume(answers)
                } else {
                    // The bridge marks a pair whose languages are not installed (yet): the
                    // reader then offers the system's download instead of a failure.
                    val failure = if (reason?.startsWith("notInstalled") == true) TranslationFailure.SYSTEM_LANGUAGE_MISSING else TranslationFailure.ENGINE_FAILED
                    continuation.resumeWithException(
                        TranslationException(failure, "The system translation failed: ${reason ?: "unknown"}"),
                    )
                }
            }
            // A cancelled coroutine stops the system's session too, so it does not go on beside
            // the next chapter's once the reader's lock is released.
            continuation.invokeOnCancellation { running.cancel() }
        }

    override suspend fun download(from: String, to: String): Boolean = try {
        suspendCancellableCoroutine { continuation ->
            bridge().download(appleLanguage(from), appleLanguage(to)) { installed -> continuation.resume(installed) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    /** The reader's language tags as Apple's `Locale.Language` identifiers. */
    private fun appleLanguage(tag: String): String = when (tag) {
        "zh-Hant" -> "zh-Hant"
        "zh-Hans" -> "zh-Hans"
        else -> tag
    }

    /** Offered only once Swift registered the bridge (iOS 26 and later). */
    override val available: Boolean get() = AppleTranslationRegistry.bridge != null
}
