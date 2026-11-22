package dev.shinsou.kmp.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.contracts.SourceAuth
import dev.shinsou.kmp.plugin.contracts.SourceWebSession
import dev.shinsou.kmp.plugin.contracts.SourceSettings
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.contract.SourceCredential
import dev.shinsou.kmp.contract.SourcePreferenceKind
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.challenge.SourceWebChallengeDialog
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.core.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.authoredOrExternal
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@Composable
internal fun SourceSettingsDialog(
    source: BrowseSource,
    settings: SourceSettings,
    auth: SourceAuth,
    webSession: SourceWebSession,
    onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument?,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val settingsSections = sourceSettingsSections(source)
    var values by remember(source.id, source.preferences) {
        mutableStateOf(source.preferences.associate { it.key to it.value })
    }
    var credential by remember(source.id) { mutableStateOf<SourceCredential?>(null) }
    var sourceCookies by remember(source.id) { mutableStateOf(emptyList<SourceCookie>()) }
    var secretsLoading by remember(source.id) { mutableStateOf(true) }
    var secretsError by remember(source.id) { mutableStateOf<String?>(null) }
    var username by remember(source.id) { mutableStateOf("") }
    var password by remember(source.id) { mutableStateOf("") }
    var loginBusy by remember(source.id) { mutableStateOf(false) }
    var loginMessage by remember(source.id) { mutableStateOf<String?>(null) }
    var loginError by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieName by remember(source.id) { mutableStateOf("") }
    var cookieValue by remember(source.id) { mutableStateOf("") }
    var cookieDomain by remember(source.id) { mutableStateOf(defaultCookieDomain(source.baseUrl)) }
    var challengeRequest by remember(source.id) { mutableStateOf<SourceWebChallengeRequest?>(null) }
    var challengeBusy by remember(source.id) { mutableStateOf(false) }
    var challengeMessage by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieImportBusy by remember(source.id) { mutableStateOf(false) }
    var cookieImportMessage by remember(source.id) { mutableStateOf<String?>(null) }

    fun openWebChallenge() {
        challengeBusy = true
        challengeMessage = null
        scope.launch {
            withFrameNanos { }
            runCatchingCancellable {
                webSession.sourceWebChallenge(
                    sourceId = source.id,
                    username = username,
                    password = password,
                )
            }
                .onSuccess { request ->
                    if (request == null) {
                        challengeMessage = strings.text(StringKey.THIS_SOURCE_DOES_NOT_PROVIDE_A_VALID)
                    } else {
                        challengeRequest = request
                    }
                }
                .onFailure { error ->
                    challengeMessage = error.message ?: strings.text(StringKey.UNABLE_TO_START_THE_WEB_CHALLENGE)
                }
            challengeBusy = false
        }
    }

    LaunchedEffect(source.id) {
        secretsLoading = true
        secretsError = null
        runCatchingCancellable { auth.loadSourceSecrets(source.id) }
            .onSuccess { result ->
                credential = result.secrets.credential
                sourceCookies = result.secrets.cookies
                username = result.secrets.credential?.username.orEmpty()
                password = result.secrets.credential?.password.orEmpty()
                secretsError = result.failureStage?.let { stage ->
                    strings.text(sourceLoginFailureMessageKey(stage))
                }
            }
            .onFailure {
                secretsError = strings.text(StringKey.UNABLE_TO_READ_CREDENTIALS_FROM_SECURE_STORAGE)
            }
        secretsLoading = false
    }

    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text("${source.name} ${strings.settings}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (settingsSections.credentials) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(strings.text(StringKey.CREDENTIALS), style = MaterialTheme.typography.titleMedium)
                            if (secretsLoading) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(
                                        strings.text(StringKey.PREPARING),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            secretsError?.let { message ->
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text(strings.text(StringKey.USERNAME)) },
                                singleLine = true,
                                enabled = !secretsLoading && !loginBusy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(strings.text(StringKey.PASSWORD)) },
                                singleLine = true,
                                enabled = !secretsLoading && !loginBusy,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (settingsSections.browserSessionLogin) {
                                            loginMessage = null
                                            loginError = null
                                            openWebChallenge()
                                            return@Button
                                        }
                                        loginBusy = true
                                        loginMessage = null
                                        loginError = null
                                        scope.launch {
                                            withFrameNanos { }
                                            runCatchingCancellable {
                                                auth.saveSourceCredentialsResult(source.id, username, password)
                                            }.onSuccess { result ->
                                                val feedback = sourceLoginFeedback(
                                                    result = result,
                                                    successMessage = strings.text(StringKey.LOGIN_SUCCESSFUL),
                                                    fallbackErrorMessage = strings.text(
                                                        StringKey.LOGIN_FAILED_CHECK_YOUR_USERNAME_AND_PASSWORD,
                                                    ),
                                                    failureMessage = { stage ->
                                                        strings.text(sourceLoginFailureMessageKey(stage))
                                                    },
                                                )
                                                loginMessage = feedback.successMessage
                                                loginError = feedback.errorMessage
                                                if (result.succeeded) {
                                                    credential = SourceCredential(username, password)
                                                }
                                            }.onFailure {
                                                // Never expose raw runtime/transport errors because they may
                                                // contain headers, cookies, stack text, or credentials.
                                                loginError = strings.text(StringKey.THE_LOGIN_OPERATION_FAILED_UNEXPECTEDLY)
                                            }
                                            loginBusy = false
                                        }
                                    },
                                    enabled = !secretsLoading && !loginBusy && !challengeBusy &&
                                        (settingsSections.browserSessionLogin ||
                                            (username.isNotBlank() && password.isNotEmpty())),
                                ) {
                                    Text(
                                        strings.text(
                                            if (settingsSections.browserSessionLogin) StringKey.SIGN_IN_IN_BROWSER else StringKey.LOGIN,
                                        ),
                                    )
                                }
                                if (credential != null) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                withFrameNanos { }
                                                runCatchingCancellable { auth.logoutSource(source.id) }
                                                    .onSuccess {
                                                        credential = null
                                                        loginMessage = null
                                                        username = ""
                                                        password = ""
                                                    }
                                                    .onFailure {
                                                        loginError = strings.text(
                                                            StringKey.THE_LOGIN_OPERATION_FAILED_UNEXPECTEDLY,
                                                        )
                                                    }
                                            }
                                        },
                                    ) { Text(strings.text(StringKey.LOGOUT)) }
                                }
                            }
                            loginMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            loginError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (settingsSections.browserSessionLogin) {
                                Text(
                                    strings.text(
                                        StringKey.THIS_SOURCE_MUST_SIGN_IN_THROUGH_ITS,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (settingsSections.preferences) {
                    item { Text(strings.text(StringKey.PREFERENCES), style = MaterialTheme.typography.titleMedium) }
                    items(source.preferences, key = { "preference:${it.key}" }) { preference ->
                        Column {
                            Text(strings.authoredOrExternal(preference.title), style = MaterialTheme.typography.titleSmall)
                            preference.summary?.let {
                                Text(
                                    strings.authoredOrExternal(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when (preference.kind) {
                                SourcePreferenceKind.Text -> OutlinedTextField(
                                    value = values[preference.key].orEmpty(),
                                    onValueChange = { values = values + (preference.key to it) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SourcePreferenceKind.Toggle -> Switch(
                                    checked = values[preference.key].toBoolean(),
                                    onCheckedChange = { values = values + (preference.key to it.toString()) },
                                )
                                SourcePreferenceKind.Choice -> Column {
                                    preference.choices.forEachIndexed { index, label ->
                                        val choiceValue = preference.choiceValues.getOrElse(index) { label }
                                        FilterChip(
                                            selected = values[preference.key] == choiceValue,
                                            onClick = { values = values + (preference.key to choiceValue) },
                                            label = { Text(strings.authoredOrExternal(label), maxLines = 1) },
                                        )
                                    }
                                }
                                SourcePreferenceKind.MultiChoice -> {
                                    val selected = values[preference.key].orEmpty()
                                        .split(',')
                                        .filter { it.isNotBlank() }
                                        .toSet()
                                    Column {
                                        preference.choices.forEachIndexed { index, label ->
                                            val choiceValue = preference.choiceValues.getOrElse(index) { label }
                                            FilterChip(
                                                selected = choiceValue in selected,
                                                onClick = {
                                                    val updated = if (choiceValue in selected) {
                                                        selected - choiceValue
                                                    } else {
                                                        selected + choiceValue
                                                    }
                                                    values = values + (
                                                        preference.key to updated.sorted().joinToString(",")
                                                    )
                                                },
                                                label = { Text(strings.authoredOrExternal(label), maxLines = 1) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                strings.text(
                                    if (settingsSections.browserSessionLogin) StringKey.BROWSER_SESSION else StringKey.COOKIES,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(
                                onClick = { openWebChallenge() },
                                enabled = !challengeBusy && !secretsLoading,
                            ) {
                                Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                                Text(if (challengeBusy) strings.text(StringKey.PREPARING) else strings.text(StringKey.WEB_CHALLENGE_CLOUDFLARE))
                            }
                            Text(
                                strings.text(
                                    StringKey.USES_A_BROWSER_COMPATIBLE_USER_AGENT_IF,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    cookieImportBusy = true
                                    cookieImportMessage = null
                                    scope.launch {
                                        withFrameNanos { }
                                        runCatchingCancellable {
                                            val document = onImportDocument(setOf("json", "txt"))
                                                ?: return@runCatchingCancellable null
                                            require(document.contents.size <= MAX_COOKIE_FILE_BYTES) {
                                                strings.text(StringKey.COOKIE_FILE_IS_LARGER_THAN_1_MIB)
                                            }
                                            val content = document.contents.decodeToString(throwOnInvalidSequence = true)
                                            val importedCookies = CookieFileParser.parse(content, source.baseUrl)
                                            require(importedCookies.isNotEmpty()) {
                                                strings.text(StringKey.NO_VALID_COOKIES_FOR_X_WERE_FOUND, defaultCookieDomain(source.baseUrl).trimStart('.'))
                                            }
                                            importedCookies.forEach { cookie -> webSession.setSourceCookie(source.id, cookie) }
                                            importedCookies
                                        }.onSuccess { importedCookies ->
                                            if (importedCookies != null) {
                                                sourceCookies = importedCookies.fold(sourceCookies) { saved, cookie ->
                                                    saved.upsert(cookie)
                                                }
                                                cookieImportMessage = strings.text(
                                                    StringKey.IMPORTED_X_COOKIE_S,
                                                    importedCookies.size,
                                                )
                                            }
                                        }.onFailure { error ->
                                            cookieImportMessage = strings.text(StringKey.ERROR_X, error.message ?: strings.text(StringKey.UNABLE_TO_IMPORT_COOKIES))
                                        }
                                        cookieImportBusy = false
                                    }
                                },
                                enabled = !cookieImportBusy,
                            ) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Text(if (cookieImportBusy) strings.text(StringKey.IMPORTING) else strings.text(StringKey.IMPORT_COOKIES_TXT_JSON))
                            }
                            cookieImportMessage?.let { message ->
                                Text(
                                    message,
                                    color = if (message.startsWith("Error", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            challengeMessage?.let { message ->
                                Text(
                                    message,
                                    color = if (message.startsWith("Error", ignoreCase = true) ||
                                        message.startsWith("Unable", ignoreCase = true)
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!secretsLoading && sourceCookies.isEmpty()) {
                                Text(strings.text(StringKey.NO_COOKIES_SAVED), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(
                        sourceCookies,
                        key = { "cookie:${it.domain}:${it.path}:${it.name}" },
                    ) { cookie ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cookie.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${cookie.value} · ${cookie.domain}${cookie.path}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        runCatchingCancellable {
                                            webSession.deleteSourceCookie(source.id, cookie.name, cookie.domain)
                                        }.onSuccess {
                                            sourceCookies = sourceCookies.filterNot { saved ->
                                                saved.name == cookie.name &&
                                                    saved.domain.equals(cookie.domain, ignoreCase = true)
                                            }
                                        }.onFailure {
                                            cookieImportMessage = strings.text(StringKey.ERROR_X, strings.text(StringKey.UNABLE_TO_SAVE_BROWSER_COOKIES))
                                        }
                                    }
                                },
                            ) { Icon(Icons.Outlined.Delete, strings.delete) }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(
                                value = cookieName,
                                onValueChange = { cookieName = it },
                                label = { Text(strings.text(StringKey.COOKIE_NAME)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = cookieValue,
                                onValueChange = { cookieValue = it },
                                label = { Text(strings.text(StringKey.COOKIE_VALUE)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = cookieDomain,
                                onValueChange = { cookieDomain = it },
                                label = { Text(strings.text(StringKey.COOKIE_DOMAIN)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val added = SourceCookie(cookieName, cookieValue, cookieDomain)
                                            runCatchingCancellable { webSession.setSourceCookie(source.id, added) }
                                                .onSuccess {
                                                    sourceCookies = sourceCookies.upsert(added)
                                                    cookieName = ""
                                                    cookieValue = ""
                                                }
                                                .onFailure {
                                                    cookieImportMessage = strings.text(
                                                        StringKey.ERROR_X,
                                                        strings.text(StringKey.UNABLE_TO_SAVE_BROWSER_COOKIES),
                                                    )
                                                }
                                        }
                                    },
                                    enabled = cookieName.isNotBlank() && cookieValue.isNotEmpty() && cookieDomain.isNotBlank(),
                                ) { Text(strings.text(StringKey.ADD_COOKIE)) }
                                if (sourceCookies.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                runCatchingCancellable { webSession.clearSourceCookies(source.id) }
                                                    .onSuccess { sourceCookies = emptyList() }
                                                    .onFailure {
                                                        cookieImportMessage = strings.text(
                                                            StringKey.ERROR_X,
                                                            strings.text(StringKey.UNABLE_TO_SAVE_BROWSER_COOKIES),
                                                        )
                                                    }
                                            }
                                        },
                                    ) { Text(strings.text(StringKey.CLEAR_COOKIES)) }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveSourcePreferences(source.id, values)
                        onDismiss()
                    }
                },
            ) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )

    challengeRequest?.let { request ->
        SourceWebChallengeDialog(
            request = request,
            onImport = { importedSession ->
                scope.launch {
                    challengeBusy = true
                    runCatchingCancellable {
                        webSession.importSourceWebChallengeSession(
                            sourceId = source.id,
                            capability = importedSession.capability,
                            cookies = importedSession.cookies,
                            userAgent = importedSession.userAgent,
                            localStorage = importedSession.localStorage,
                        )
                    }.onSuccess {
                        sourceCookies = importedSession.cookies.fold(sourceCookies) { saved, cookie -> saved.upsert(cookie) }
                        challengeMessage = if (importedSession.localStorage.isNotEmpty()) {
                            strings.text(StringKey.BROWSER_SESSION_IMPORTED_SUCCESSFULLY)
                        } else {
                            strings.text(StringKey.IMPORTED_X_COOKIE_S, importedSession.cookies.size)
                        }
                        challengeRequest = null
                    }.onFailure { error ->
                        // Import authority is one-shot and was consumed even when validation
                        // failed, so close this request instead of presenting a doomed retry.
                        challengeRequest = null
                        challengeMessage = strings.text(
                            StringKey.ERROR_X,
                            error.message ?: strings.text(StringKey.UNABLE_TO_SAVE_BROWSER_COOKIES),
                        )
                    }
                    challengeBusy = false
                }
            },
            onDismiss = {
                challengeRequest = null
                challengeMessage = strings.text(StringKey.WEB_CHALLENGE_CANCELLED_NO_BROWSER_SESSION_WAS)
                scope.launch { webSession.cancelSourceWebChallenge(request.capability) }
            },
        )
    }
}

private fun List<SourceCookie>.upsert(cookie: SourceCookie): List<SourceCookie> =
    filterNot { saved ->
        saved.name == cookie.name &&
            saved.domain.equals(cookie.domain, ignoreCase = true) &&
            saved.path == cookie.path
    } + cookie

private fun defaultCookieDomain(baseUrl: String): String {
    val host = baseUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
    return host.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
}

@Composable
internal fun SourceIcon(source: BrowseSource) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(source.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}
