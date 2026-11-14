package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.ui.PluginPermissionDetails
import dev.shinsou.kmp.ui.PublisherTrustState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.localizedLabel
import dev.shinsou.kmp.ui.i18n.text

/** Granted with the installation: running the script and reaching its declared origins (ADR-0002 §6.2). */
private val ORDINARY_RUNTIME = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK)

/** Low-risk host requests, granted with the declaration (they are rate limited and show no UI). */
private val ORDINARY_HOST = setOf(PluginHostPermission.REPORT_DIAGNOSTIC, PluginHostPermission.REQUEST_SOURCE_REFRESH)

/** How many origins are listed before the rest is folded into a count. */
private const val ORIGINS_SHOWN = 5

/**
 * The host-drawn permission page (ADR-0002 §6.5) for installing or updating a plugin, or, with
 * [current], for withdrawing items from source settings. The publisher's key comes before the
 * package name (names prove nothing); every sensitive item is its own switch, off by default.
 */
@Composable
internal fun PluginPermissionReviewDialog(
    review: PluginEventGrantReview,
    details: PluginPermissionDetails?,
    /** The current grant when opened from settings: items can only be switched off there. */
    current: PluginGrantSelection? = null,
    onConfirm: (PluginGrantSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val sensitiveRuntime = review.requestedRuntimePermissions.filterNot { it in ORDINARY_RUNTIME }.sortedBy { it.name }
    val sensitiveHost = review.requestedPermissions.filterNot { it in ORDINARY_HOST }.sortedBy { it.name }
    val suffixes = review.requestedContentHostSuffixes.sorted()
    var runtime by remember(review, current) { mutableStateOf(current?.runtimePermissions ?: emptySet()) }
    var host by remember(review, current) { mutableStateOf(current?.hostPermissions ?: emptySet()) }
    var hosts by remember(review, current) { mutableStateOf(current?.contentHostSuffixes ?: emptySet()) }

    fun selection() = PluginGrantSelection(
        hostPermissions = (host + review.requestedPermissions.filter { it in ORDINARY_HOST }) intersect review.requestedPermissions,
        runtimePermissions = (runtime + review.requestedRuntimePermissions.filter { it in ORDINARY_RUNTIME }) intersect
            review.requestedRuntimePermissions,
        contentHostSuffixes = hosts intersect review.requestedContentHostSuffixes,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(if (current == null) "Review plugin permissions" else "Plugin permissions")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PublisherBlock(details, strings)
                Text(
                    "${details?.packageName ?: review.artifact.packageId} · v${details?.version ?: review.artifact.version}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(strings.text("Runs isolated from the app and your other plugins."), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()

                Text(strings.text("Connects to"), fontWeight = FontWeight.SemiBold)
                OriginList(details?.requestOrigins.orEmpty(), strings)
                if (details?.contentOrigins.orEmpty().isNotEmpty()) {
                    Text(strings.text("Downloads images and text from"), style = MaterialTheme.typography.labelMedium)
                    OriginList(details?.contentOrigins.orEmpty(), strings)
                }
                Text(
                    strings.text("Always allowed: run the plugin and connect to the sites above."),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (sensitiveRuntime.isNotEmpty() || sensitiveHost.isNotEmpty() || suffixes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(strings.text("Sensitive permissions"), fontWeight = FontWeight.SemiBold)
                    Text(strings.text("Each one is off until you turn it on."), style = MaterialTheme.typography.bodySmall)
                }
                sensitiveRuntime.forEach { permission ->
                    PermissionSwitch(
                        title = permission.localizedLabel(strings),
                        risk = permission.risk(strings, details),
                        checked = permission in runtime,
                        enabled = current == null || permission in current.runtimePermissions,
                        onChange = { on -> runtime = if (on) runtime + permission else runtime - permission },
                    )
                }
                sensitiveHost.forEach { permission ->
                    PermissionSwitch(
                        title = permission.localizedLabel(strings),
                        risk = strings.text("Lets the plugin ask the app to show this screen."),
                        checked = permission in host,
                        enabled = current == null || permission in current.hostPermissions,
                        onChange = { on -> host = if (on) host + permission else host - permission },
                    )
                }
                suffixes.forEach { suffix ->
                    PermissionSwitch(
                        title = strings.text("Download from every site under {0}", suffix.displaySuffix(strings)),
                        risk = strings.text("Images and text only, without cookies or your account."),
                        checked = suffix in hosts,
                        // Content families can be switched on later too: they only fetch images
                        // and text, without cookies or the account, and only the ones declared.
                        enabled = true,
                        onChange = { on -> hosts = if (on) hosts + suffix else hosts - suffix },
                    )
                }
                if (current == null) {
                    Text(
                        strings.text("These permissions apply only to this exact version; you can withdraw them later in the plugin's permissions."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection()) }) {
                Text(if (current == null) strings.install else strings.text("Save permissions"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun PublisherBlock(details: PluginPermissionDetails?, strings: ShinsouStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(strings.text("Publisher"), style = MaterialTheme.typography.labelMedium)
        val fingerprint = details?.publisherDisplayFingerprint
        if (fingerprint == null && details?.inDevelopment == true) {
            Text(strings.text("In development"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(strings.text("Unsigned development build: runs only while developer mode is on."), style = MaterialTheme.typography.bodySmall)
        } else if (fingerprint == null) {
            Text(strings.text("No publisher key: this plugin cannot run"), color = MaterialTheme.colorScheme.error)
        } else {
            Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Text(
                when (details.publisherState) {
                    PublisherTrustState.TRUSTED -> strings.text(
                        if (details.fingerprintVerified) "Publisher fingerprint matched" else "Trusted on first use (not verified)",
                    )
                    PublisherTrustState.PENDING_FINGERPRINT -> strings.text("Waiting for a fingerprint match")
                    PublisherTrustState.KEY_REVOKED -> strings.text("Publisher key revoked")
                    PublisherTrustState.KEY_REPLACED -> strings.text("Publisher key replaced")
                    null -> strings.text("Unknown publisher")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (details.publisherState == PublisherTrustState.TRUSTED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        details?.repositoryUrl?.let { url ->
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OriginList(origins: List<String>, strings: ShinsouStrings) {
    if (origins.isEmpty()) {
        Text(strings.text("No sites"), style = MaterialTheme.typography.bodySmall)
        return
    }
    origins.take(ORIGINS_SHOWN).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (origins.size > ORIGINS_SHOWN) {
        Text(strings.text("…and {0} more ({1} in total)", origins.size - ORIGINS_SHOWN, origins.size), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionSwitch(title: String, risk: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(risk, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

private fun PluginRuntimePermission.risk(strings: ShinsouStrings, details: PluginPermissionDetails?): String = when (this) {
    PluginRuntimePermission.CREDENTIAL_ACCESS -> strings.text(
        "Your account name and password will be sent to: {0}",
        details?.credentialOrigins?.joinToString().orEmpty().ifEmpty { "—" },
    )
    PluginRuntimePermission.COOKIE_STORAGE -> strings.text("Keeps login cookies for its sites and sends them with its requests.")
    PluginRuntimePermission.COOKIE_READ -> strings.text("Lets the plugin read those cookies, so it could copy your login.")
    PluginRuntimePermission.LOGIN_PROMPT -> strings.text("Lets the plugin ask you to log in.")
    PluginRuntimePermission.FAVORITE_MUTATION -> strings.text("Lets the plugin change your favorites on the site.")
    PluginRuntimePermission.BROWSER_CHALLENGE -> if (details?.browserLoginOrigins.isNullOrEmpty()) {
        strings.text("Lets the plugin open a browser page to pass the site's checks.")
    } else {
        strings.text(
            "Lets the plugin open a browser page to pass the site's checks and to sign in. While signing in, that page may also go to: {0}",
            details.browserLoginOrigins.joinToString(),
        )
    }
    PluginRuntimePermission.BROWSER_IMAGES -> strings.text(
        "Loads its images in a private browser without your cookies, from: {0}",
        details?.browserImageOrigins?.joinToString().orEmpty().ifEmpty { "—" },
    )
    PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK -> ""
}

/** `hath.network:1024-65535` reads as the host family with its port range spelled out. */
internal fun String.displaySuffix(strings: ShinsouStrings): String {
    val base = removeSuffix(":1024-65535")
    return if (base == this) this else strings.text("{0} (any port from 1024)", base)
}
