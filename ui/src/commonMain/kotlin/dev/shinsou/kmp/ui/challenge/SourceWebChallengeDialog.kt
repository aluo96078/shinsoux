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
import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.plugin.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

@Composable
internal fun SourceWebChallengeDialog(
    request: SourceWebChallengeRequest,
    onImport: (WebChallengeCapture) -> Unit,
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
            when {
                platformWebChallengeMode == PlatformWebChallengeMode.Embedded &&
                    request.allowsEmbeddedWebChallenge() -> EmbeddedChallenge(request, onImport, onDismiss)
                else -> ExternalBrowserFallback(request, onDismiss)
            }
        }
    }
}

@Composable
private fun EmbeddedChallenge(
    request: SourceWebChallengeRequest,
    onImport: (WebChallengeCapture) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var captureRequest by remember(request) { mutableIntStateOf(0) }
    var loading by remember(request) { mutableStateOf(true) }
    var capturing by remember(request) { mutableStateOf(false) }
    var errorVisible by remember(request) { mutableStateOf(false) }
    var message by remember(request) {
        mutableStateOf(
            strings.text(
                StringKey.COMPLETE_VERIFICATION_AND_SIGN_IN_TO_THE,
            ),
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Security, null)
            Column(Modifier.weight(1f)) {
                Text(strings.text(StringKey.WEB_CHALLENGE_CLOUDFLARE), style = MaterialTheme.typography.titleMedium)
                Text(request.sourceName, style = MaterialTheme.typography.bodySmall)
            }
            if (loading || capturing) CircularProgressIndicator()
        }
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = if (errorVisible) {
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
                    errorVisible = false
                    message = strings.text(
                        StringKey.VERIFICATION_PAGE_LOADED_COMPLETE_VERIFICATION_AND_WEBSITE,
                    )
                },
                onSessionCaptured = { captured ->
                    capturing = false
                    val safe = normalizeWebChallengeCookies(request.url, captured.cookies)
                    val safeStorage = normalizeWebChallengeLocalStorage(
                        captured.localStorage,
                        request.localStorageKeys,
                    )
                    val userAgent = normalizeWebChallengeUserAgent(captured.userAgent)
                    val missingStorage = request.requiredLocalStorageKeys.filter { key ->
                        safeStorage[key].isNullOrEmpty()
                    }
                    if (safe.isEmpty() && safeStorage.isEmpty()) {
                        errorVisible = true
                        message = strings.text(StringKey.ERROR_NO_USABLE_BROWSER_SESSION_WAS_FOUND)
                    } else if (userAgent == null) {
                        errorVisible = true
                        message = strings.text(StringKey.ERROR_THE_BROWSER_USER_AGENT_COULD_NOT)
                    } else if (request.requiredCookieName != null &&
                        safe.none { it.name.equals(request.requiredCookieName, ignoreCase = true) }
                    ) {
                        errorVisible = true
                        message = strings.text(
                            StringKey.ERROR_CLOUDFLARE_VERIFICATION_IS_NOT_COMPLETE_BECAUSE,
                            request.requiredCookieName,
                        )
                    } else if (missingStorage.isNotEmpty()) {
                        errorVisible = true
                        message = strings.text(
                            StringKey.ERROR_WEBSITE_SIGN_IN_IS_NOT_COMPLETE,
                        )
                    } else {
                        onImport(
                            WebChallengeCapture(
                                capability = captured.capability,
                                cookies = safe,
                                userAgent = userAgent,
                                localStorage = safeStorage,
                            ),
                        )
                    }
                },
                onError = { error ->
                    loading = false
                    capturing = false
                    errorVisible = true
                    message = strings.text(StringKey.ERROR_X, error)
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
                    errorVisible = false
                    message = strings.text(StringKey.READING_THE_ISOLATED_BROWSER_SESSION)
                    captureRequest += 1
                },
                enabled = !loading && !capturing,
            ) { Text(strings.text(StringKey.IMPORT_BROWSER_SESSION)) }
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
            strings.text(StringKey.WEB_CHALLENGE_CLOUDFLARE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            strings.text(StringKey.AN_IN_APP_VERIFICATION_BROWSER_IS_UNAVAILABLE),
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
                        .onSuccess { message = strings.text(StringKey.OPENED_EXTERNALLY_NO_COOKIES_WERE_IMPORTED) }
                        .onFailure { message = strings.text(StringKey.UNABLE_TO_OPEN_THE_DEFAULT_BROWSER_X, it.message.orEmpty()) }
                },
            ) { Text(strings.text(StringKey.OPEN_DEFAULT_BROWSER)) }
        }
    }
}
