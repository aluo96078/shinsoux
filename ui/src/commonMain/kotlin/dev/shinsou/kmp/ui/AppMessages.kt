package dev.shinsou.kmp.ui

import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

/**
 * R4-3 (docs/architecture/0006): how the app's state holders tell the user something (the
 * snackbar), in the language currently chosen. [strings] is read at the time of the message, so a
 * language change applies without recreating the holders.
 */
internal class AppMessages(
    private val strings: () -> ShinsouStrings,
    private val sink: suspend (String) -> Unit,
) {
    val currentStrings: ShinsouStrings get() = strings()

    suspend fun show(message: String) = sink(message)

    suspend fun show(key: StringKey, vararg values: Any?) = sink(strings().text(key, *values))

    /** [failure]'s own message, or [fallback] when it has none. */
    suspend fun showFailure(failure: Throwable, fallback: StringKey) =
        sink(failure.message ?: strings().text(fallback))
}
