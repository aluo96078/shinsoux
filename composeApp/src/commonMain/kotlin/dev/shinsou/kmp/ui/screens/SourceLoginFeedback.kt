package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SourceLoginResult
import dev.shinsou.kmp.ui.SourceLoginFailureStage

/** Converts a source login result into the two mutually exclusive messages rendered by settings. */
internal data class SourceLoginFeedback(
    val successMessage: String? = null,
    val errorMessage: String? = null,
)

internal fun sourceLoginFeedback(
    result: SourceLoginResult,
    successMessage: String,
    fallbackErrorMessage: String,
    failureMessage: (SourceLoginFailureStage) -> String = { fallbackErrorMessage },
): SourceLoginFeedback = if (result.succeeded) {
    SourceLoginFeedback(successMessage = successMessage)
} else {
    SourceLoginFeedback(
        errorMessage = result.errorMessage?.takeIf(String::isNotBlank)
            ?: result.failureStage?.let(failureMessage)
            ?: fallbackErrorMessage,
    )
}

/** Translation key for a failure stage; the stage deliberately carries no exception details. */
internal fun sourceLoginFailureMessageKey(stage: SourceLoginFailureStage): String = when (stage) {
    SourceLoginFailureStage.PREPARE_SOURCE -> "Unable to prepare the source login."
    SourceLoginFailureStage.READ_CREDENTIALS -> "Unable to read credentials from secure storage."
    SourceLoginFailureStage.WRITE_CREDENTIALS -> "Unable to write credentials to secure storage."
    SourceLoginFailureStage.AUTHENTICATE -> "The source login process failed before returning a response."
    SourceLoginFailureStage.RESTORE_CREDENTIALS -> "Unable to restore the previous credentials after login failed."
    SourceLoginFailureStage.REFRESH_SOURCE_STATE -> "Login finished, but the source state could not be refreshed."
    SourceLoginFailureStage.UNKNOWN -> "The login operation failed unexpectedly."
}
