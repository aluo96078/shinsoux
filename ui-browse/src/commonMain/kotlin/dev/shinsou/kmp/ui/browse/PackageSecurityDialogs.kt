package dev.shinsou.kmp.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.contracts.PackagePublisher
import dev.shinsou.kmp.plugin.contracts.PackageSecuritySnapshot
import dev.shinsou.kmp.plugin.contracts.PluginDeveloperModeSettings
import dev.shinsou.kmp.plugin.contracts.PublisherTrustState
import dev.shinsou.kmp.plugin.contracts.RepositoryTrustPrompt
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

/**
 * ADR-0002 §8.2: before a signed third-party repository is added, the user sees the publisher key's
 * fingerprint and either trusts it on first use or enters the full fingerprint from the publisher.
 */
@Composable
public fun RepositoryTrustDialog(
    prompt: RepositoryTrustPrompt,
    onTrust: (fingerprint: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    // A link's fingerprint is filled in: trusting then means matching it, not trusting on first use.
    var fingerprint by remember(prompt) { mutableStateOf(prompt.linkedFingerprint.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(StringKey.TRUST_THIS_PUBLISHER)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.name, style = MaterialTheme.typography.titleMedium)
                Text(prompt.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strings.text(StringKey.PUBLISHER_KEY_FINGERPRINT), style = MaterialTheme.typography.labelLarge)
                Text(prompt.displayFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                Text(
                    strings.text(StringKey.ITS_PACKAGES_RUN_IN_THE_SANDBOX_WITH),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    // A link's fingerprint is the one to match: shown, not editable.
                    readOnly = prompt.linkedFingerprint != null,
                    label = {
                        Text(
                            if (prompt.fingerprintRequired || prompt.linkedFingerprint != null) strings.text(StringKey.FULL_FINGERPRINT_REQUIRED)
                            else strings.text(StringKey.FULL_FINGERPRINT_OPTIONAL),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (!prompt.fingerprintRequired && prompt.linkedFingerprint == null) || fingerprint.isNotBlank(),
                onClick = { onTrust(fingerprint.trim().takeIf(String::isNotEmpty)) },
            ) { Text(strings.text(StringKey.TRUST_AND_ADD)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/** One trusted publisher: its fingerprint, state, block / match actions and the inline fingerprint match field. */
@Composable
private fun PublisherTrustRow(
    publisher: PackagePublisher,
    enabled: Boolean,
    matching: String?,
    fingerprint: String,
    onMatching: (String?) -> Unit,
    onFingerprint: (String) -> Unit,
    onPublisherBlocked: (publisherId: String, blocked: Boolean) -> Unit,
    onConfirmFingerprint: (publisherId: String, fingerprint: String) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(publisher.displayFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append(
                    when (publisher.state) {
                        PublisherTrustState.TRUSTED -> strings.text(StringKey.TRUSTED)
                        PublisherTrustState.PENDING_FINGERPRINT -> strings.text(StringKey.PENDING_FINGERPRINT_MATCH)
                        PublisherTrustState.KEY_REVOKED -> strings.text(StringKey.KEY_REVOKED_BY_THE_PUBLISHER)
                        PublisherTrustState.KEY_REPLACED -> strings.text(StringKey.KEY_REPLACED)
                    },
                )
                append(" · ")
                append(
                    when {
                        publisher.fingerprintVerified -> strings.text(StringKey.FINGERPRINT_MATCHED)
                        else -> strings.text(StringKey.TRUSTED_ON_FIRST_USE)
                    },
                )
                if (publisher.blocked) append(" · ").append(strings.text(StringKey.BLOCKED))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(enabled = enabled, onClick = { onPublisherBlocked(publisher.id, !publisher.blocked) }) {
                Text(if (publisher.blocked) strings.text(StringKey.UNBLOCK) else strings.text(StringKey.BLOCK))
            }
            if (!publisher.fingerprintVerified && publisher.state != PublisherTrustState.KEY_REVOKED &&
                publisher.state != PublisherTrustState.KEY_REPLACED
            ) {
                TextButton(enabled = enabled, onClick = { onMatching(publisher.id); onFingerprint("") }) {
                    Text(strings.text(StringKey.MATCH_FINGERPRINT))
                }
            }
        }
        if (matching == publisher.id) {
            OutlinedTextField(
                value = fingerprint,
                onValueChange = onFingerprint,
                label = { Text(strings.text(StringKey.FULL_FINGERPRINT_REQUIRED)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = enabled && fingerprint.isNotBlank(),
                onClick = { onConfirmFingerprint(publisher.id, fingerprint.trim()); onMatching(null) },
            ) { Text(strings.text(StringKey.MATCH)) }
        }
        HorizontalDivider()
    }
}

/** The developer-mode switch row. */
@Composable
private fun DeveloperModeSwitchRow(developer: PluginDeveloperModeSettings, enabled: Boolean, onDeveloperMode: (Boolean) -> Unit) {
    val strings = LocalShinsouStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(strings.text(StringKey.DEVELOPER_MODE), style = MaterialTheme.typography.titleSmall)
            Text(
                strings.text(StringKey.LOAD_UNSIGNED_PLUGINS_FROM_A_DEVELOPMENT_SERVER),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = developer.enabled, enabled = enabled, onCheckedChange = onDeveloperMode)
    }
}

/** The editable development server addresses and the button that saves them. */
@Composable
private fun DeveloperHostsEditor(
    developerHosts: String,
    onDeveloperHostsChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column {
        OutlinedTextField(
            value = developerHosts,
            onValueChange = onDeveloperHostsChange,
            label = { Text(strings.text(StringKey.DEVELOPMENT_SERVER_ADDRESSES)) },
            supportingText = { Text(strings.text(StringKey.PRIVATE_NETWORK_ADDRESSES_ONE_PER_LINE_FOR)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = enabled, onClick = onSave) { Text(strings.text(StringKey.SAVE_ADDRESSES)) }
    }
}

/** ADR-0002 §8.2, §8.4: the trust mode, trusted publishers and the device's block list. */
@Composable
internal fun PackageSecurityDialog(
    snapshot: PackageSecuritySnapshot,
    enabled: Boolean,
    onStrictMode: (Boolean) -> Unit,
    onPublisherBlocked: (publisherId: String, blocked: Boolean) -> Unit,
    onConfirmFingerprint: (publisherId: String, fingerprint: String) -> Unit,
    onUnblock: (key: String) -> Unit,
    onDeveloperMode: (Boolean) -> Unit = {},
    onDeveloperHosts: (List<String>) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var developerHosts by remember(snapshot.developerMode?.lanHosts) {
        mutableStateOf(snapshot.developerMode?.lanHosts.orEmpty().joinToString("\n"))
    }
    var matching by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(StringKey.PACKAGE_SECURITY)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item("mode") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.text(StringKey.ALWAYS_REQUIRE_A_FINGERPRINT_MATCH), style = MaterialTheme.typography.titleSmall)
                            Text(
                                strings.text(StringKey.PUBLISHERS_TRUSTED_ON_FIRST_USE_STAY_PENDING),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = snapshot.strictMode, enabled = enabled, onCheckedChange = onStrictMode)
                    }
                }
                item("publishers-title") {
                    Text(strings.text(StringKey.PUBLISHERS), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (snapshot.publishers.isEmpty()) {
                    item("no-publishers") { Text(strings.text(StringKey.NO_TRUSTED_PUBLISHERS), style = MaterialTheme.typography.bodySmall) }
                }
                items(snapshot.publishers, key = { "publisher-${it.id}" }) { publisher ->
                    PublisherTrustRow(
                        publisher = publisher,
                        enabled = enabled,
                        matching = matching,
                        fingerprint = fingerprint,
                        onMatching = { matching = it },
                        onFingerprint = { fingerprint = it },
                        onPublisherBlocked = onPublisherBlocked,
                        onConfirmFingerprint = onConfirmFingerprint,
                    )
                }
                snapshot.developerMode?.let { developer ->
                    item("developer-title") { DeveloperModeSwitchRow(developer, enabled, onDeveloperMode) }
                    if (developer.enabled) {
                        item("developer-hosts") {
                            DeveloperHostsEditor(
                                developerHosts = developerHosts,
                                onDeveloperHostsChange = { developerHosts = it },
                                enabled = enabled,
                                onSave = { onDeveloperHosts(developerHosts.lines().map(String::trim).filter(String::isNotEmpty)) },
                            )
                        }
                    }
                }
                item("blocks-title") {
                    Text(strings.text(StringKey.BLOCKED_ON_THIS_DEVICE), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (snapshot.blocks.isEmpty()) {
                    item("no-blocks") { Text(strings.text(StringKey.NOTHING_BLOCKED), style = MaterialTheme.typography.bodySmall) }
                }
                items(snapshot.blocks, key = { "block-${it.key}" }) { block ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(block.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(enabled = enabled, onClick = { onUnblock(block.key) }) { Text(strings.text(StringKey.UNBLOCK)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.text(StringKey.CLOSE)) } },
    )
}
