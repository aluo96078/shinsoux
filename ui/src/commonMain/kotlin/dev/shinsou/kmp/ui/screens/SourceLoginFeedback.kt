package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.contract.SourceLoginResult
import dev.shinsou.kmp.contract.SourceLoginFailureStage
import dev.shinsou.kmp.ui.i18n.StringKey

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
internal fun sourceLoginFailureMessageKey(stage: SourceLoginFailureStage): StringKey = when (stage) {
    SourceLoginFailureStage.PREPARE_SOURCE -> StringKey.UNABLE_TO_PREPARE_THE_SOURCE_LOGIN
    SourceLoginFailureStage.READ_CREDENTIALS -> StringKey.UNABLE_TO_READ_CREDENTIALS_FROM_SECURE_STORAGE
    SourceLoginFailureStage.WRITE_CREDENTIALS -> StringKey.UNABLE_TO_WRITE_CREDENTIALS_TO_SECURE_STORAGE
    SourceLoginFailureStage.AUTHENTICATE -> StringKey.THE_SOURCE_LOGIN_PROCESS_FAILED_BEFORE_RETURNING
    SourceLoginFailureStage.RESTORE_CREDENTIALS -> StringKey.UNABLE_TO_RESTORE_THE_PREVIOUS_CREDENTIALS_AFTER
    SourceLoginFailureStage.REFRESH_SOURCE_STATE -> StringKey.LOGIN_FINISHED_BUT_THE_SOURCE_STATE_COULD
    SourceLoginFailureStage.UNKNOWN -> StringKey.THE_LOGIN_OPERATION_FAILED_UNEXPECTEDLY
}
