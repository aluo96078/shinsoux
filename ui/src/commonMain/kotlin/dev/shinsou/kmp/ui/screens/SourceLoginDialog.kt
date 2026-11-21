package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.SourceAuth
import dev.shinsou.kmp.plugin.SourceWebSession
import dev.shinsou.kmp.plugin.BrowseSource
import dev.shinsou.kmp.contract.SourceLoginRequest
import dev.shinsou.kmp.plugin.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.challenge.SourceWebChallengeDialog
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch

/** App-wide credential prompt raised asynchronously by an executable source. */
@Composable
internal fun SourceLoginDialog(
    request: SourceLoginRequest,
    source: BrowseSource?,
    auth: SourceAuth,
    webSession: SourceWebSession,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var username by remember(request) { mutableStateOf("") }
    var password by remember(request) { mutableStateOf("") }
    var secretsLoading by remember(request) { mutableStateOf(true) }
    var busy by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }
    var challengeRequest by remember(request) { mutableStateOf<SourceWebChallengeRequest?>(null) }

    LaunchedEffect(request) {
        secretsLoading = true
        errorMessage = null
        runCatchingCancellable { auth.loadSourceSecrets(request.sourceId) }
            .onSuccess { result ->
                username = result.secrets.credential?.username.orEmpty()
                password = result.secrets.credential?.password.orEmpty()
                errorMessage = result.failureStage?.let { stage ->
                    strings.text(sourceLoginFailureMessageKey(stage))
                }
            }
            .onFailure {
                errorMessage = strings.text(StringKey.UNABLE_TO_READ_CREDENTIALS_FROM_SECURE_STORAGE)
            }
        secretsLoading = false
    }

    fun dismiss() {
        if (!busy) request.eventId?.let(auth::dismissSourceLoginEvent)
            ?: auth.dismissSourceLoginRequest(request.sourceId)
    }

    fun submit() {
        if (busy || secretsLoading) return
        if (source?.requiresBrowserSessionLogin != true && (username.isBlank() || password.isEmpty())) return
        busy = true
        errorMessage = null
        scope.launch {
            // Paint the modal and its progress state before the plugin performs synchronous work.
            withFrameNanos { }
            if (source?.requiresBrowserSessionLogin == true) {
                runCatchingCancellable {
                    webSession.sourceWebChallenge(
                        sourceId = request.sourceId,
                        username = username.takeIf(String::isNotBlank),
                        password = password.takeIf(String::isNotEmpty),
                    )
                }.onSuccess { challenge ->
                    if (challenge == null) {
                        errorMessage = strings.text(StringKey.THIS_SOURCE_DOES_NOT_PROVIDE_A_VALID)
                    } else {
                        challengeRequest = challenge
                    }
                }.onFailure { failure ->
                    // The reason (e.g. the page is outside the source's declared browser-session
                    // origins, or the permission was not granted) tells the user or the plugin
                    // author what to fix; the generic text alone hid it.
                    errorMessage = listOfNotNull(
                        strings.text(StringKey.UNABLE_TO_START_THE_WEB_CHALLENGE),
                        failure.message?.takeIf(String::isNotBlank),
                    ).joinToString("\n")
                }
                busy = false
                return@launch
            }
            runCatchingCancellable {
                request.eventId?.let { eventId ->
                    auth.saveSourceEventCredentialsResult(eventId, request.sourceId, username, password)
                } ?: auth.saveSourceCredentialsResult(request.sourceId, username, password)
            }.onSuccess { result ->
                val feedback = sourceLoginFeedback(
                    result = result,
                    successMessage = strings.text(StringKey.LOGIN_SUCCESSFUL),
                    fallbackErrorMessage = strings.text(StringKey.LOGIN_FAILED_CHECK_YOUR_USERNAME_AND_PASSWORD),
                    failureMessage = { stage -> strings.text(sourceLoginFailureMessageKey(stage)) },
                )
                if (result.succeeded) {
                    request.eventId?.let(auth::dismissSourceLoginEvent)
                        ?: auth.dismissSourceLoginRequest(request.sourceId)
                } else {
                    errorMessage = feedback.errorMessage
                }
            }.onFailure {
                // Plugin/runtime failures may contain headers, cookies, stack text, or secrets.
                // Event UI exposes only a stable host-owned message.
                errorMessage = strings.text(StringKey.THE_LOGIN_OPERATION_FAILED_UNEXPECTEDLY)
            }
            busy = false
        }
    }

    challengeRequest?.let { challenge ->
        SourceWebChallengeDialog(
            request = challenge,
            onImport = { importedSession ->
                busy = true
                scope.launch {
                    runCatchingCancellable {
                        webSession.importSourceWebChallengeSession(
                            sourceId = request.sourceId,
                            capability = importedSession.capability,
                            cookies = importedSession.cookies,
                            userAgent = importedSession.userAgent,
                            localStorage = importedSession.localStorage,
                        )
                    }.onSuccess {
                        challengeRequest = null
                        request.eventId?.let(auth::dismissSourceLoginEvent)
                            ?: auth.dismissSourceLoginRequest(request.sourceId)
                    }.onFailure {
                        challengeRequest = null
                        errorMessage = strings.text(StringKey.THE_BROWSER_SESSION_COULD_NOT_BE_IMPORTED)
                    }
                    busy = false
                }
            },
            onDismiss = {
                challengeRequest = null
                scope.launch { webSession.cancelSourceWebChallenge(challenge.capability) }
            },
        )
        return
    }

    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = ::dismiss,
        title = { Text(strings.text(StringKey.LOGIN_REQUIRED)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    request.reason?.takeIf(String::isNotBlank)
                        ?: strings.text(StringKey.SIGN_IN_TO_X_TO_CONTINUE_USING, request.sourceName),
                )
                if (secretsLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(strings.text(StringKey.PREPARING))
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.text(StringKey.USERNAME)) },
                    singleLine = true,
                    enabled = !busy && !secretsLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.text(StringKey.PASSWORD)) },
                    singleLine = true,
                    enabled = !busy && !secretsLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { message ->
                    Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
                if (source?.requiresBrowserSessionLogin == true) {
                    Text(
                        strings.text(
                            StringKey.THIS_SOURCE_MUST_SIGN_IN_THROUGH_ITS,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                enabled = !busy && !secretsLoading &&
                    (source?.requiresBrowserSessionLogin == true ||
                        (username.isNotBlank() && password.isNotEmpty())),
            ) {
                Row(horizontalArrangement = Arrangement.Center) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        strings.text(
                            if (source?.requiresBrowserSessionLogin == true) StringKey.SIGN_IN_IN_BROWSER else StringKey.LOGIN,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !busy) {
                Text(strings.text(StringKey.CANCEL))
            }
        },
    )
}
