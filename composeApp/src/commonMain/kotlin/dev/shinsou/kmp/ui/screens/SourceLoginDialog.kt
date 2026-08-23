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
    var username by remember(request.sourceId, source?.credential?.username) {
        mutableStateOf(source?.credential?.username.orEmpty())
    }
    var password by remember(request.sourceId, source?.credential?.password) {
        mutableStateOf(source?.credential?.password.orEmpty())
    }
    var busy by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }

    fun dismiss() {
        if (!busy) request.eventId?.let(callbacks::dismissSourceLoginEvent)
            ?: callbacks.dismissSourceLoginRequest(request.sourceId)
    }

    fun submit() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        errorMessage = null
        scope.launch {
            // Paint the modal and its progress state before the plugin performs synchronous work.
            withFrameNanos { }
            runCatching {
                request.eventId?.let { eventId ->
                    callbacks.saveSourceEventCredentials(eventId, request.sourceId, username, password)
                } ?: callbacks.saveSourceCredentials(request.sourceId, username, password)
            }.onSuccess { success ->
                if (success) {
                    request.eventId?.let(callbacks::dismissSourceLoginEvent)
                        ?: callbacks.dismissSourceLoginRequest(request.sourceId)
                } else {
                    errorMessage = strings.text("Login failed. Check your username and password.")
                }
            }.onFailure {
                // Plugin/runtime failures may contain headers, cookies, stack text, or secrets.
                // Event UI exposes only a stable host-owned message.
                errorMessage = strings.text("Unable to save credentials")
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
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.text("Username")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.text("Password")) },
                    singleLine = true,
                    enabled = !busy,
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
                enabled = username.isNotBlank() && password.isNotEmpty() && !busy,
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
