package dev.shinsou.kmp.ui.challenge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text

@Composable
internal fun SourceWebChallengeDialog(
    request: SourceWebChallengeRequest,
    onImport: (List<SourceCookie>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            when (platformWebChallengeMode) {
                PlatformWebChallengeMode.Embedded -> EmbeddedChallenge(request, onImport, onDismiss)
                PlatformWebChallengeMode.ExternalBrowserOnly -> ExternalBrowserFallback(request, onDismiss)
            }
        }
    }
}

@Composable
private fun EmbeddedChallenge(
    request: SourceWebChallengeRequest,
    onImport: (List<SourceCookie>) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var captureRequest by remember(request) { mutableIntStateOf(0) }
    var loading by remember(request) { mutableStateOf(true) }
    var capturing by remember(request) { mutableStateOf(false) }
    var message by remember(request) {
        mutableStateOf(strings.text("Complete the verification in the browser, then import its cookies."))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Security, null)
            Column(Modifier.weight(1f)) {
                Text(strings.text("Web challenge / Cloudflare"), style = MaterialTheme.typography.titleMedium)
                Text(request.sourceName, style = MaterialTheme.typography.bodySmall)
            }
            if (loading || capturing) CircularProgressIndicator()
        }
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = if (message.startsWith("Error:")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 320.dp)) {
            PlatformWebChallengeView(
                request = request,
                captureRequest = captureRequest,
                onPageLoaded = {
                    loading = false
                    message = strings.text("Verification page loaded. Complete it, then choose Import cookies.")
                },
                onCookiesCaptured = { captured ->
                    capturing = false
                    val safe = normalizeWebChallengeCookies(request.url, captured)
                    if (safe.isEmpty()) {
                        message = strings.text("Error: no usable cookies were found for this source. Complete the challenge and try again.")
                    } else {
                        onImport(safe)
                    }
                },
                onError = { error ->
                    loading = false
                    capturing = false
                    message = strings.text("Error: {0}", error)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
            Button(
                onClick = {
                    capturing = true
                    message = strings.text("Reading cookies from the isolated browser session…")
                    captureRequest += 1
                },
                enabled = !loading && !capturing,
            ) { Text(strings.text("Import cookies")) }
        }
    }
}

@Composable
private fun ExternalBrowserFallback(
    request: SourceWebChallengeRequest,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val uriHandler = LocalUriHandler.current
    var message by remember(request) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.OpenInBrowser, null)
        Text(
            strings.text("Web challenge / Cloudflare"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            strings.text("Desktop does not include an embedded browser whose cookie store can be safely shared with sources. The page can open in your default browser, but those cookies stay in that browser and will not be imported. After verification, add the required cookies manually in Source settings."),
            modifier = Modifier.padding(vertical = 18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss) { Text(strings.close) }
            Button(
                onClick = {
                    runCatching { uriHandler.openUri(request.url) }
                        .onSuccess { message = strings.text("Opened externally. No cookies were imported.") }
                        .onFailure { message = strings.text("Unable to open the default browser: {0}", it.message.orEmpty()) }
                },
            ) { Text(strings.text("Open default browser")) }
        }
    }
}
