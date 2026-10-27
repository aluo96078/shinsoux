package dev.shinsou.kmp.ui.screens

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
import dev.shinsou.kmp.ui.PackageSecuritySnapshot
import dev.shinsou.kmp.ui.PublisherTrustState
import dev.shinsou.kmp.ui.RepositoryTrustPrompt
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text

/**
 * ADR-0002 §8.2: before a signed third-party repository is added, the user sees the publisher key's
 * fingerprint and either trusts it on first use or enters the full fingerprint from the publisher.
 */
@Composable
internal fun RepositoryTrustDialog(
    prompt: RepositoryTrustPrompt,
    onTrust: (fingerprint: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var fingerprint by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text("Trust this publisher?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.name, style = MaterialTheme.typography.titleMedium)
                Text(prompt.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strings.text("Publisher key fingerprint"), style = MaterialTheme.typography.labelLarge)
                Text(prompt.displayFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                Text(
                    strings.text("Its packages run in the sandbox with only the permissions you approve. Compare the fingerprint with the one the publisher shows on their own site."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = {
                        Text(
                            if (prompt.fingerprintRequired) strings.text("Full fingerprint (required)")
                            else strings.text("Full fingerprint (optional)"),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !prompt.fingerprintRequired || fingerprint.isNotBlank(),
                onClick = { onTrust(fingerprint.trim().takeIf(String::isNotEmpty)) },
            ) { Text(strings.text("Trust and add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
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
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var matching by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text("Package security")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item("mode") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.text("Always require a fingerprint match"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                strings.text("Publishers trusted on first use stay pending until matched; they install nothing new."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = snapshot.strictMode, enabled = enabled, onCheckedChange = onStrictMode)
                    }
                }
                item("publishers-title") {
                    Text(strings.text("Publishers"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (snapshot.publishers.isEmpty()) {
                    item("no-publishers") { Text(strings.text("No trusted publishers"), style = MaterialTheme.typography.bodySmall) }
                }
                items(snapshot.publishers, key = { "publisher-${it.id}" }) { publisher ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(publisher.displayFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(
                                    when (publisher.state) {
                                        PublisherTrustState.TRUSTED -> strings.text("Trusted")
                                        PublisherTrustState.PENDING_FINGERPRINT -> strings.text("Pending fingerprint match")
                                        PublisherTrustState.KEY_REVOKED -> strings.text("Key revoked by the publisher")
                                        PublisherTrustState.KEY_REPLACED -> strings.text("Key replaced")
                                    },
                                )
                                append(" · ")
                                append(
                                    when {
                                        publisher.official -> strings.text("Official")
                                        publisher.fingerprintVerified -> strings.text("Fingerprint matched")
                                        else -> strings.text("Trusted on first use")
                                    },
                                )
                                if (publisher.blocked) append(" · ").append(strings.text("Blocked"))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(enabled = enabled, onClick = { onPublisherBlocked(publisher.id, !publisher.blocked) }) {
                                Text(if (publisher.blocked) strings.text("Unblock") else strings.text("Block"))
                            }
                            if (!publisher.fingerprintVerified && publisher.state != PublisherTrustState.KEY_REVOKED &&
                                publisher.state != PublisherTrustState.KEY_REPLACED
                            ) {
                                TextButton(enabled = enabled, onClick = { matching = publisher.id; fingerprint = "" }) {
                                    Text(strings.text("Match fingerprint"))
                                }
                            }
                        }
                        if (matching == publisher.id) {
                            OutlinedTextField(
                                value = fingerprint,
                                onValueChange = { fingerprint = it },
                                label = { Text(strings.text("Full fingerprint (required)")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                enabled = enabled && fingerprint.isNotBlank(),
                                onClick = { onConfirmFingerprint(publisher.id, fingerprint.trim()); matching = null },
                            ) { Text(strings.text("Match")) }
                        }
                        HorizontalDivider()
                    }
                }
                item("blocks-title") {
                    Text(strings.text("Blocked on this device"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (snapshot.blocks.isEmpty()) {
                    item("no-blocks") { Text(strings.text("Nothing blocked"), style = MaterialTheme.typography.bodySmall) }
                }
                items(snapshot.blocks, key = { "block-${it.key}" }) { block ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(block.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(enabled = enabled, onClick = { onUnblock(block.key) }) { Text(strings.text("Unblock")) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.text("Close")) } },
    )
}
