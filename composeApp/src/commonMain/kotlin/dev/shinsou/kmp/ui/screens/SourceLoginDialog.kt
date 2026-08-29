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
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.ui.SourceLoginRequest
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch

/** App-wide credential prompt raised asynchronously by an executable source. */
@Composable
internal fun SourceLoginDialog(
    request: SourceLoginRequest,
    source: BrowseSource?,
    callbacks: BrowseCallbacks,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var username by remember(request) { mutableStateOf("") }
    var password by remember(request) { mutableStateOf("") }
    var secretsLoading by remember(request) { mutableStateOf(true) }
    var busy by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        secretsLoading = true
        errorMessage = null
        runCatching { callbacks.loadSourceSecrets(request.sourceId) }
            .onSuccess { result ->
                username = result.secrets.credential?.username.orEmpty()
                password = result.secrets.credential?.password.orEmpty()
                errorMessage = result.failureStage?.let { stage ->
                    strings.text(sourceLoginFailureMessageKey(stage))
                }
            }
            .onFailure {
                errorMessage = strings.text("Unable to read credentials from secure storage.")
            }
        secretsLoading = false
    }

    fun dismiss() {
        if (!busy) request.eventId?.let(callbacks::dismissSourceLoginEvent)
            ?: callbacks.dismissSourceLoginRequest(request.sourceId)
    }

    fun submit() {
        if (busy || secretsLoading || username.isBlank() || password.isEmpty()) return
        busy = true
        errorMessage = null
        scope.launch {
            // Paint the modal and its progress state before the plugin performs synchronous work.
            withFrameNanos { }
            runCatching {
                request.eventId?.let { eventId ->
                    callbacks.saveSourceEventCredentialsResult(eventId, request.sourceId, username, password)
                } ?: callbacks.saveSourceCredentialsResult(request.sourceId, username, password)
            }.onSuccess { result ->
                val feedback = sourceLoginFeedback(
                    result = result,
                    successMessage = strings.text("Login successful."),
                    fallbackErrorMessage = strings.text("Login failed. Check your username and password."),
                    failureMessage = { stage -> strings.text(sourceLoginFailureMessageKey(stage)) },
                )
                if (result.succeeded) {
                    request.eventId?.let(callbacks::dismissSourceLoginEvent)
                        ?: callbacks.dismissSourceLoginRequest(request.sourceId)
                } else {
                    errorMessage = feedback.errorMessage
                }
            }.onFailure {
                // Plugin/runtime failures may contain headers, cookies, stack text, or secrets.
                // Event UI exposes only a stable host-owned message.
                errorMessage = strings.text("The login operation failed unexpectedly.")
            }
            busy = false
        }
    }

    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = ::dismiss,
        title = { Text(strings.text("Login required")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    request.reason?.takeIf(String::isNotBlank)
                        ?: strings.text("Sign in to {0} to continue using this source.", request.sourceName),
                )
                if (secretsLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(strings.text("Preparing…"))
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.text("Username")) },
                    singleLine = true,
                    enabled = !busy && !secretsLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.text("Password")) },
                    singleLine = true,
                    enabled = !busy && !secretsLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { message ->
                    Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                enabled = username.isNotBlank() && password.isNotEmpty() && !busy && !secretsLoading,
            ) {
                Row(horizontalArrangement = Arrangement.Center) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(strings.text("Login"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !busy) {
                Text(strings.text("Cancel"))
            }
        },
    )
}
