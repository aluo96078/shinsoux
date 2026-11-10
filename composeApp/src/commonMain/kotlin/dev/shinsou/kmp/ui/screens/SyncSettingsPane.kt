package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.sync.v3.SyncApiException
import dev.shinsou.kmp.sync.v3.SyncServerAddress
import dev.shinsou.kmp.sync.v3.SyncServerAddressException
import dev.shinsou.kmp.sync.v3.SyncUiState
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.sync.v3.WireDevice
import dev.shinsou.kmp.sync.v3.WireInstance
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Settings → Sync: choose a server, sign in or register, then see status and manage devices. */
@Composable
internal fun SyncSettingsPane(
    sync: SyncV3Service?,
    copyText: (label: String, text: String) -> Boolean = { _, _ -> false },
) {
    val strings = LocalShinsouStrings.current
    if (sync == null) {
        SettingsList { item { SettingDescription(strings.text("Sync is unavailable in this build.")) } }
        return
    }
    val state by sync.state.collectAsState()
    SettingsList {
        if (state.signedIn) {
            item { SignedInSection(sync, state) }
            if (state.sharingEnabled) item { SharingSection(sync, copyText) }
        } else {
            item { SignedOutSection(sync, state) }
        }
    }
}

@Composable
internal fun SyncCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignedOutSection(sync: SyncV3Service, state: SyncUiState) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var addressInput by remember { mutableStateOf(state.serverUrl.orEmpty()) }
    var discovered by remember { mutableStateOf<Pair<SyncServerAddress, WireInstance>?>(null) }
    var registerMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(state.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.sessionExpired) {
            SyncCard {
                Text(strings.text("Signed out"), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text("The server ended this session. Your library on this device is untouched; sign in again to resume syncing."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SyncCard {
            Text(strings.text("Sync server"), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text("Sync your library and reading progress through a Shinsou sync server you choose. Your data is readable by that server's operator."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = addressInput,
                onValueChange = {
                    addressInput = it
                    discovered = null
                    error = null
                },
                label = { Text(strings.text("Server address")) },
                placeholder = { Text("https://sync.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(error.takeIf { discovered == null })
            Button(
                enabled = !busy && addressInput.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        sync.discover(addressInput).fold(
                            onSuccess = { discovered = it },
                            onFailure = { error = it.userMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) {
                if (busy && discovered == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(strings.text("Connect"))
                }
            }
        }
        discovered?.let { (address, instance) ->
            SyncCard {
                Text(instance.name.ifBlank { address.baseUrl }, style = MaterialTheme.typography.titleMedium)
                if (instance.description.isNotBlank()) Text(instance.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings.text("Server version {0}", instance.version.ifBlank { "?" }) + " · " + registrationLabel(strings, instance.registration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (address.insecureLan) InsecureLanWarning()
            }
            SyncCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !registerMode, onClick = { registerMode = false; error = null }, label = { Text(strings.text("Sign in")) })
                    FilterChip(
                        selected = registerMode,
                        onClick = { registerMode = true; error = null },
                        label = { Text(strings.text("Register")) },
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.text("Username")) },
                    supportingText = if (registerMode) {
                        { Text(strings.text("3–32 characters: a–z, 0–9 and _")) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.text("Password")) },
                    supportingText = if (registerMode) {
                        { Text(strings.text("At least 8 characters")) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (registerMode) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(strings.text("Email (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (instance.registration == "invite") {
                        OutlinedTextField(
                            value = inviteCode,
                            onValueChange = { inviteCode = it },
                            label = { Text(strings.text("Invite code")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = setupCode,
                        onValueChange = { setupCode = it },
                        label = { Text(strings.text("Setup code (first administrator only)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ErrorText(error)
                Button(
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val result = if (registerMode) {
                                sync.register(address, username, password, email, inviteCode, setupCode)
                            } else {
                                sync.login(address, username, password)
                            }
                            result.onFailure { error = it.userMessage(strings) }
                            if (result.isSuccess) password = ""
                            busy = false
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (registerMode) strings.text("Create account") else strings.text("Sign in"))
                    }
                }
            }
        }
    }
}

@Composable
private fun InsecureLanWarning() {
    val strings = LocalShinsouStrings.current
    Text(
        strings.text("This server uses plain HTTP on your local network. Traffic is not encrypted; use it only on a network you trust."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SignedInSection(sync: SyncV3Service, state: SyncUiState) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<WireDevice>?>(null) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WireDevice?>(null) }
    var removing by remember { mutableStateOf<WireDevice?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // The operator can turn sharing on or off at any time; ask again whenever this page opens.
    LaunchedEffect(state.serverUrl) { sync.refreshFeatures() }
    LaunchedEffect(refreshTick, state.signedIn) {
        sync.listDevices().fold(
            onSuccess = { devices = it; deviceError = null },
            onFailure = { deviceError = it.userMessage(strings) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SyncCard {
            Text(strings.text("Account"), style = MaterialTheme.typography.titleMedium)
            Text(strings.text("Signed in as {0}", state.username.orEmpty()), style = MaterialTheme.typography.bodyLarge)
            Text(
                strings.text("Server") + ": " + state.serverUrl.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                strings.text("This device") + ": " + state.deviceName.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.insecureLan) InsecureLanWarning()
        }
        SyncCard {
            Text(strings.text("Status"), style = MaterialTheme.typography.titleMedium)
            StatusLine(strings.text("Last sync"), state.lastSyncAt?.let(::formatTime) ?: strings.text("Never"))
            StatusLine(strings.text("Waiting to upload"), state.pendingCount.toString())
            if (state.pendingRemoteCount > 0) {
                StatusLine(strings.text("Waiting for a title or chapter on this device"), state.pendingRemoteCount.toString())
            }
            if (state.unsyncableCount > 0) {
                StatusLine(strings.text("Titles that cannot sync"), state.unsyncableCount.toString())
                Text(
                    strings.text("These library titles come from a source that is not installed, so they have no portable identity yet."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.rejectedCount > 0) StatusLine(strings.text("Rejected by the server"), state.rejectedCount.toString())
            StatusLine(
                strings.text("Live updates"),
                if (state.streamConnected) strings.text("Connected") else strings.text("Not connected"),
            )
            ErrorText(state.lastError?.let { strings.text("Last error") + ": " + it })
            Button(enabled = !state.syncing, onClick = { scope.launch { sync.syncNow(); refreshTick++ } }) {
                if (state.syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(strings.text("Sync now"))
                }
            }
        }
        SyncCard {
            Text(strings.text("Devices"), style = MaterialTheme.typography.titleMedium)
            ErrorText(deviceError)
            devices?.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.name + if (device.current) " (" + strings.text("this device") + ")" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            device.platform + " · " + strings.text("Last seen") + " " + formatTime(device.lastSeenAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renaming = device }) { Icon(Icons.Outlined.Edit, strings.text("Rename")) }
                    IconButton(onClick = { removing = device }) { Icon(Icons.Outlined.Delete, strings.text("Sign out device")) }
                }
            }
            if (devices == null && deviceError == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        OutlinedButton(onClick = { confirmLogout = true }) { Text(strings.text("Sign out of this device")) }
    }

    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(strings.text("Rename device")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    singleLine = true,
                    label = { Text(strings.text("Device name")) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renaming = null
                        scope.launch {
                            sync.renameDevice(device.id, name).onFailure { deviceError = it.userMessage(strings) }
                            refreshTick++
                        }
                    },
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(strings.cancel) } },
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(strings.text("Sign out device")) },
            text = { Text(strings.text("Sign out {0}? It will need the password to sync again.", device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        scope.launch {
                            sync.signOutDevice(device.id, device.current).onFailure { deviceError = it.userMessage(strings) }
                            refreshTick++
                        }
                    },
                ) { Text(strings.text("Sign out")) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strings.cancel) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(strings.text("Sign out of this device")) },
            text = { Text(strings.text("Your library stays on this device. Changes made while signed out are uploaded the next time you sign in.")) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; scope.launch { sync.logout() } }) { Text(strings.text("Sign out")) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun registrationLabel(strings: ShinsouStrings, policy: String): String = when (policy) {
    "open" -> strings.text("Open registration")
    "invite" -> strings.text("Registration by invitation")
    else -> strings.text("Registration closed")
}

internal fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${local.year}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}"
}

internal fun Throwable.userMessage(strings: ShinsouStrings): String = when (this) {
    is SyncServerAddressException -> when (reason) {
        SyncServerAddressException.Reason.EMPTY -> strings.text("Enter the server address.")
        SyncServerAddressException.Reason.INSECURE_PUBLIC_HOST ->
            strings.text("Plain HTTP is only allowed for servers on your local network. Use an https:// address.")
        SyncServerAddressException.Reason.UNSUPPORTED_SCHEME -> strings.text("Use an http:// or https:// address.")
        SyncServerAddressException.Reason.INVALID -> strings.text("This server address is not valid.")
    }
    is SyncApiException -> when (code) {
        "invalid_credentials" -> strings.text("Wrong username or password.")
        "username_taken" -> strings.text("That username is already taken.")
        "email_taken" -> strings.text("That email is already used.")
        "registration_closed" -> strings.text("This server is not accepting new accounts.")
        "invite_required" -> strings.text("This server requires an invite code.")
        "validation_failed" -> strings.text("The server rejected these details: {0}", message.orEmpty())
        "rate_limited" -> strings.text("Too many attempts. Try again later.")
        "unsupported_api_version" -> strings.text("This app and the server are not compatible. Update the app or the server.")
        "network" -> strings.text("Cannot reach the server: {0}", message.orEmpty())
        "forbidden" -> strings.text("The server refused this request.")
        "feature_disabled" -> strings.text("Sharing is turned off on this server.")
        "email_unverified" -> strings.text("Verify your email address on the server's website to use sharing.")
        "user_blocked" -> strings.text("Unblock this person first.")
        "share_refused" -> strings.text("This person does not accept shares from you.")
        "unknown_work" -> strings.text("This title has not reached the server yet. Sync, then try again.")
        "quota_exceeded" -> strings.text("Your account has no room for another library entry.")
        "not_found" -> strings.text("This no longer exists on the server.")
        else -> message.orEmpty().ifBlank { code }
    }
    else -> message ?: strings.text("Something went wrong.")
}
