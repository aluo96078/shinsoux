package dev.shinsou.kmp.ui.sync

import dev.shinsou.kmp.ui.core.SettingsEdit
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.SyncKindSettings
import dev.shinsou.kmp.sync.v3.ManualSyncResult
import dev.shinsou.kmp.sync.v3.SyncApiException
import dev.shinsou.kmp.sync.v3.SyncServerAddress
import dev.shinsou.kmp.sync.v3.SyncServerAddressException
import dev.shinsou.kmp.sync.v3.SyncUiState
import dev.shinsou.kmp.sync.v3.SyncAccount
import dev.shinsou.kmp.sync.v3.SyncSharing
import dev.shinsou.kmp.sync.v3.WireDevice
import dev.shinsou.kmp.sync.v3.WireInstance
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import dev.shinsou.kmp.ui.components.SettingDescription
import dev.shinsou.kmp.ui.components.SettingsList

/** More → Sync: choose a server, sign in or register, then see status and manage devices. */
@Composable
public fun <S> SyncSettingsPane(
    sync: S?,
    copyText: (label: String, text: String) -> Boolean = { _, _ -> false },
    kinds: SyncKindSettings = SyncKindSettings(),
    onKindsChange: (SettingsEdit<SyncKindSettings>) -> Unit = {},
) where S : SyncAccount, S : SyncSharing {
    val strings = LocalShinsouStrings.current
    if (sync == null) {
        SettingsList { item { SettingDescription(strings.text(StringKey.SYNC_IS_UNAVAILABLE_IN_THIS_BUILD)) } }
        return
    }
    val state by sync.state.collectAsState()
    SettingsList {
        if (state.signedIn) {
            item { SignedInSection(sync, state, kinds, onKindsChange) }
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
private fun SignedOutSection(sync: SyncAccount, state: SyncUiState) {
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
                Text(strings.text(StringKey.SIGNED_OUT), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text(StringKey.THE_SERVER_ENDED_THIS_SESSION_YOUR_LIBRARY),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SyncCard {
            Text(strings.text(StringKey.SYNC_SERVER), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text(StringKey.SYNC_YOUR_LIBRARY_AND_READING_PROGRESS_THROUGH),
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
                label = { Text(strings.text(StringKey.SERVER_ADDRESS)) },
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
                    Text(strings.text(StringKey.CONNECT))
                }
            }
        }
        discovered?.let { (address, instance) ->
            SyncCard {
                Text(instance.name.ifBlank { address.baseUrl }, style = MaterialTheme.typography.titleMedium)
                if (instance.description.isNotBlank()) Text(instance.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings.text(StringKey.SERVER_VERSION_X, instance.version.ifBlank { "?" }) + " · " + registrationLabel(strings, instance.registration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (address.insecureLan) InsecureLanWarning()
            }
            SyncCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !registerMode, onClick = { registerMode = false; error = null }, label = { Text(strings.text(StringKey.SIGN_IN)) })
                    FilterChip(
                        selected = registerMode,
                        onClick = { registerMode = true; error = null },
                        label = { Text(strings.text(StringKey.REGISTER)) },
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.text(StringKey.USERNAME)) },
                    supportingText = if (registerMode) {
                        { Text(strings.text(StringKey.N332_CHARACTERS_AZ_09_AND)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.text(StringKey.PASSWORD)) },
                    supportingText = if (registerMode) {
                        { Text(strings.text(StringKey.AT_LEAST_8_CHARACTERS)) }
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
                        label = { Text(strings.text(StringKey.EMAIL_OPTIONAL)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (instance.registration == "invite") {
                        OutlinedTextField(
                            value = inviteCode,
                            onValueChange = { inviteCode = it },
                            label = { Text(strings.text(StringKey.INVITE_CODE)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = setupCode,
                        onValueChange = { setupCode = it },
                        label = { Text(strings.text(StringKey.SETUP_CODE_FIRST_ADMINISTRATOR_ONLY)) },
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
                        Text(if (registerMode) strings.text(StringKey.CREATE_ACCOUNT) else strings.text(StringKey.SIGN_IN))
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
        strings.text(StringKey.THIS_SERVER_USES_PLAIN_HTTP_ON_YOUR),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Which kinds of data this device sends and applies; all on by default, kept on this device only. */
@Composable
private fun SyncKindsCard(kinds: SyncKindSettings, onChange: (SettingsEdit<SyncKindSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    @Composable
    fun Row(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, hint: String? = null) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    SyncCard {
        Text(strings.text(StringKey.SYNC_ITEMS), style = MaterialTheme.typography.titleMedium)
        Text(
            strings.text(StringKey.CHOOSE_WHAT_THIS_DEVICE_SENDS_TO_YOUR),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(strings.text(StringKey.LIBRARY_TITLES), kinds.library, { onChange { current -> current.copy(library = it) } })
        Row(strings.text(StringKey.READING_PROGRESS_AND_HISTORY), kinds.readingProgress, { onChange { current -> current.copy(readingProgress = it) } })
        Row(strings.text(StringKey.CATEGORIES), kinds.categories, { onChange { current -> current.copy(categories = it) } })
        Row(strings.text(StringKey.REPOSITORY_SUGGESTIONS), kinds.repositories, { onChange { current -> current.copy(repositories = it) } })
        Row(strings.settings, kinds.settings, { onChange { current -> current.copy(settings = it) } })
        Row(
            strings.text(StringKey.INSTALLED_PACKAGES),
            kinds.packages,
            { onChange { current -> current.copy(packages = it) } },
            hint = strings.text(StringKey.PACKAGES_INSTALLED_ON_THIS_DEVICE_ARE_RECORDED),
        )
    }
}

@Composable
private fun SignedInSection(sync: SyncAccount, state: SyncUiState, kinds: SyncKindSettings, onKindsChange: (SettingsEdit<SyncKindSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<WireDevice>?>(null) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WireDevice?>(null) }
    var removing by remember { mutableStateOf<WireDevice?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var syncBusy by remember { mutableStateOf(false) }

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
            Text(strings.text(StringKey.ACCOUNT), style = MaterialTheme.typography.titleMedium)
            Text(strings.text(StringKey.SIGNED_IN_AS_X, state.username.orEmpty()), style = MaterialTheme.typography.bodyLarge)
            Text(
                strings.text(StringKey.SERVER) + ": " + state.serverUrl.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                strings.text(StringKey.THIS_DEVICE) + ": " + state.deviceName.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.insecureLan) InsecureLanWarning()
        }
        SyncCard {
            Text(strings.text(StringKey.STATUS), style = MaterialTheme.typography.titleMedium)
            StatusLine(strings.text(StringKey.LAST_SYNC), state.lastSyncAt?.let(::formatTime) ?: strings.text(StringKey.NEVER))
            StatusLine(strings.text(StringKey.WAITING_TO_UPLOAD), state.pendingCount.toString())
            if (state.pendingRemoteCount > 0) {
                StatusLine(strings.text(StringKey.WAITING_FOR_A_TITLE_OR_CHAPTER_ON), state.pendingRemoteCount.toString())
            }
            if (state.unsyncableCount > 0) {
                StatusLine(strings.text(StringKey.TITLES_THAT_CANNOT_SYNC), state.unsyncableCount.toString())
                Text(
                    strings.text(StringKey.THESE_LIBRARY_TITLES_COME_FROM_A_SOURCE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.rejectedCount > 0) StatusLine(strings.text(StringKey.REJECTED_BY_THE_SERVER), state.rejectedCount.toString())
            StatusLine(
                strings.text(StringKey.LIVE_UPDATES),
                if (state.streamConnected) strings.text(StringKey.CONNECTED) else strings.text(StringKey.NOT_CONNECTED),
            )
            ErrorText(state.lastError?.let { strings.text(StringKey.LAST_ERROR) + ": " + it })
            if (syncBusy) ErrorText(strings.text(StringKey.A_SYNC_IS_ALREADY_RUNNING_TRY_AGAIN))
            Button(enabled = !state.syncing, onClick = {
                scope.launch {
                    syncBusy = sync.syncFromUser() == ManualSyncResult.InProgress
                    refreshTick++
                }
            }) {
                if (state.syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(strings.text(StringKey.SYNC_NOW))
                }
            }
        }
        SyncKindsCard(kinds, onKindsChange)
        if (state.missingSources.isNotEmpty()) {
            SyncCard {
                Text(strings.text(StringKey.EXTENSIONS_TO_INSTALL), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text(StringKey.SOME_TITLES_FROM_YOUR_OTHER_DEVICES_COME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.missingSources.forEach { source ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                source.name + (source.language?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                strings.text(StringKey.X_TITLES, source.titleCount.toString()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            source.repositoryUrl?.let { strings.text(StringKey.FROM_REPOSITORY_X, it) }
                                ?: strings.text(StringKey.PACKAGE_X, source.packageId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        SyncCard {
            Text(strings.text(StringKey.DEVICES), style = MaterialTheme.typography.titleMedium)
            ErrorText(deviceError)
            devices?.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.name + if (device.current) " (" + strings.text(StringKey.THIS_DEVICE_2) + ")" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            device.platform + " · " + strings.text(StringKey.LAST_SEEN) + " " + formatTime(device.lastSeenAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renaming = device }) { Icon(Icons.Outlined.Edit, strings.text(StringKey.RENAME)) }
                    IconButton(onClick = { removing = device }) { Icon(Icons.Outlined.Delete, strings.text(StringKey.SIGN_OUT_DEVICE)) }
                }
            }
            if (devices == null && deviceError == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        OutlinedButton(onClick = { confirmLogout = true }) { Text(strings.text(StringKey.SIGN_OUT_OF_THIS_DEVICE)) }
    }

    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(strings.text(StringKey.RENAME_DEVICE)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    singleLine = true,
                    label = { Text(strings.text(StringKey.DEVICE_NAME)) },
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
            title = { Text(strings.text(StringKey.SIGN_OUT_DEVICE)) },
            text = { Text(strings.text(StringKey.SIGN_OUT_X_IT_WILL_NEED_THE, device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        scope.launch {
                            sync.signOutDevice(device.id, device.current).onFailure { deviceError = it.userMessage(strings) }
                            refreshTick++
                        }
                    },
                ) { Text(strings.text(StringKey.SIGN_OUT)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strings.cancel) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(strings.text(StringKey.SIGN_OUT_OF_THIS_DEVICE)) },
            text = { Text(strings.text(StringKey.YOUR_LIBRARY_STAYS_ON_THIS_DEVICE_CHANGES)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; scope.launch { sync.logout() } }) { Text(strings.text(StringKey.SIGN_OUT)) }
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
    "open" -> strings.text(StringKey.OPEN_REGISTRATION)
    "invite" -> strings.text(StringKey.REGISTRATION_BY_INVITATION)
    else -> strings.text(StringKey.REGISTRATION_CLOSED)
}

internal fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${local.year}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}"
}

internal fun Throwable.userMessage(strings: ShinsouStrings): String = when (this) {
    is SyncServerAddressException -> when (reason) {
        SyncServerAddressException.Reason.EMPTY -> strings.text(StringKey.ENTER_THE_SERVER_ADDRESS)
        SyncServerAddressException.Reason.INSECURE_PUBLIC_HOST ->
            strings.text(StringKey.PLAIN_HTTP_IS_ONLY_ALLOWED_FOR_SERVERS)
        SyncServerAddressException.Reason.UNSUPPORTED_SCHEME -> strings.text(StringKey.USE_AN_HTTP_OR_HTTPS_ADDRESS)
        SyncServerAddressException.Reason.INVALID -> strings.text(StringKey.THIS_SERVER_ADDRESS_IS_NOT_VALID)
    }
    is SyncApiException -> if (isVersionIncompatible) {
        strings.text(StringKey.THIS_APP_AND_THE_SERVER_ARE_NOT)
    } else when (code) {
        "invalid_credentials" -> strings.text(StringKey.WRONG_USERNAME_OR_PASSWORD)
        "username_taken" -> strings.text(StringKey.THAT_USERNAME_IS_ALREADY_TAKEN)
        "email_taken" -> strings.text(StringKey.THAT_EMAIL_IS_ALREADY_USED)
        "registration_closed" -> strings.text(StringKey.THIS_SERVER_IS_NOT_ACCEPTING_NEW_ACCOUNTS)
        "invite_required" -> strings.text(StringKey.THIS_SERVER_REQUIRES_AN_INVITE_CODE)
        "validation_failed" -> strings.text(StringKey.THE_SERVER_REJECTED_THESE_DETAILS_X, message.orEmpty())
        "rate_limited" -> strings.text(StringKey.TOO_MANY_ATTEMPTS_TRY_AGAIN_LATER)
        "network" -> strings.text(StringKey.CANNOT_REACH_THE_SERVER_X, message.orEmpty())
        "forbidden" -> strings.text(StringKey.THE_SERVER_REFUSED_THIS_REQUEST)
        "feature_disabled" -> strings.text(StringKey.SHARING_IS_TURNED_OFF_ON_THIS_SERVER)
        "email_unverified" -> strings.text(StringKey.VERIFY_YOUR_EMAIL_ADDRESS_ON_THE_SERVER_2)
        "user_blocked" -> strings.text(StringKey.UNBLOCK_THIS_PERSON_FIRST)
        "share_refused" -> strings.text(StringKey.THIS_PERSON_DOES_NOT_ACCEPT_SHARES_FROM)
        "unknown_work" -> strings.text(StringKey.THIS_TITLE_HAS_NOT_REACHED_THE_SERVER)
        "quota_exceeded" -> strings.text(StringKey.YOUR_ACCOUNT_HAS_NO_ROOM_FOR_ANOTHER)
        "not_found" -> strings.text(StringKey.THIS_NO_LONGER_EXISTS_ON_THE_SERVER)
        else -> message.orEmpty().ifBlank { code }
    }
    else -> message ?: strings.text(StringKey.SOMETHING_WENT_WRONG)
}
