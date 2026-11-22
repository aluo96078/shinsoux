package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.ui.core.LocalAppUnlocked
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
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.contracts.PluginPermissionDetails
import dev.shinsou.kmp.plugin.contracts.PublisherTrustState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.i18n.displaySuffix

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
public fun PluginPermissionReviewDialog(
    review: PluginEventGrantReview,
    details: PluginPermissionDetails?,
    /** The current grant when opened from settings: items can only be switched off there. */
    current: PluginGrantSelection? = null,
    onConfirm: (PluginGrantSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    // Never above the lock overlay (a dialog is its own window): the review waits for the unlock.
    if (!LocalAppUnlocked.current) return
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
        title = { Text(strings.text(if (current == null) StringKey.REVIEW_PLUGIN_PERMISSIONS else StringKey.PLUGIN_PERMISSIONS)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PublisherBlock(details, strings)
                Text(
                    "${details?.packageName ?: review.artifact.packageId} · v${details?.version ?: review.artifact.version}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(strings.text(StringKey.RUNS_ISOLATED_FROM_THE_APP_AND_YOUR), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()

                Text(strings.text(StringKey.CONNECTS_TO), fontWeight = FontWeight.SemiBold)
                OriginList(details?.requestOrigins.orEmpty(), strings)
                if (details?.contentOrigins.orEmpty().isNotEmpty()) {
                    Text(strings.text(StringKey.DOWNLOADS_IMAGES_AND_TEXT_FROM), style = MaterialTheme.typography.labelMedium)
                    OriginList(details?.contentOrigins.orEmpty(), strings)
                }
                Text(
                    strings.text(StringKey.ALWAYS_ALLOWED_RUN_THE_PLUGIN_AND_CONNECT),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (sensitiveRuntime.isNotEmpty() || sensitiveHost.isNotEmpty() || suffixes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(strings.text(StringKey.SENSITIVE_PERMISSIONS), fontWeight = FontWeight.SemiBold)
                    Text(strings.text(StringKey.EACH_ONE_IS_OFF_UNTIL_YOU_TURN), style = MaterialTheme.typography.bodySmall)
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
                        risk = strings.text(StringKey.LETS_THE_PLUGIN_ASK_THE_APP_TO),
                        checked = permission in host,
                        enabled = current == null || permission in current.hostPermissions,
                        onChange = { on -> host = if (on) host + permission else host - permission },
                    )
                }
                suffixes.forEach { suffix ->
                    PermissionSwitch(
                        title = strings.text(StringKey.DOWNLOAD_FROM_EVERY_SITE_UNDER_X, suffix.displaySuffix(strings)),
                        risk = strings.text(StringKey.IMAGES_AND_TEXT_ONLY_WITHOUT_COOKIES_OR),
                        checked = suffix in hosts,
                        // Content families can be switched on later too: they only fetch images
                        // and text, without cookies or the account, and only the ones declared.
                        enabled = true,
                        onChange = { on -> hosts = if (on) hosts + suffix else hosts - suffix },
                    )
                }
                if (current == null) {
                    Text(
                        strings.text(StringKey.THESE_PERMISSIONS_APPLY_ONLY_TO_THIS_EXACT),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection()) }) {
                Text(if (current == null) strings.install else strings.text(StringKey.SAVE_PERMISSIONS))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun PublisherBlock(details: PluginPermissionDetails?, strings: ShinsouStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(strings.text(StringKey.PUBLISHER), style = MaterialTheme.typography.labelMedium)
        val fingerprint = details?.publisherDisplayFingerprint
        if (fingerprint == null && details?.inDevelopment == true) {
            Text(strings.text(StringKey.IN_DEVELOPMENT), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(strings.text(StringKey.UNSIGNED_DEVELOPMENT_BUILD_RUNS_ONLY_WHILE_DEVELOPER), style = MaterialTheme.typography.bodySmall)
        } else if (fingerprint == null) {
            Text(strings.text(StringKey.NO_PUBLISHER_KEY_THIS_PLUGIN_CANNOT_RUN), color = MaterialTheme.colorScheme.error)
        } else {
            Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Text(
                when (details.publisherState) {
                    PublisherTrustState.TRUSTED -> strings.text(
                        if (details.fingerprintVerified) StringKey.PUBLISHER_FINGERPRINT_MATCHED else StringKey.TRUSTED_ON_FIRST_USE_NOT_VERIFIED,
                    )
                    PublisherTrustState.PENDING_FINGERPRINT -> strings.text(StringKey.WAITING_FOR_A_FINGERPRINT_MATCH)
                    PublisherTrustState.KEY_REVOKED -> strings.text(StringKey.PUBLISHER_KEY_REVOKED)
                    PublisherTrustState.KEY_REPLACED -> strings.text(StringKey.PUBLISHER_KEY_REPLACED)
                    null -> strings.text(StringKey.UNKNOWN_PUBLISHER)
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
        Text(strings.text(StringKey.NO_SITES), style = MaterialTheme.typography.bodySmall)
        return
    }
    origins.take(ORIGINS_SHOWN).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (origins.size > ORIGINS_SHOWN) {
        Text(strings.text(StringKey.AND_X_MORE_X_IN_TOTAL, origins.size - ORIGINS_SHOWN, origins.size), style = MaterialTheme.typography.bodySmall)
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
        StringKey.YOUR_ACCOUNT_NAME_AND_PASSWORD_WILL_BE,
        details?.credentialOrigins?.joinToString().orEmpty().ifEmpty { "—" },
    )
    PluginRuntimePermission.COOKIE_STORAGE -> strings.text(StringKey.KEEPS_LOGIN_COOKIES_FOR_ITS_SITES_AND)
    PluginRuntimePermission.COOKIE_READ -> strings.text(StringKey.LETS_THE_PLUGIN_READ_THOSE_COOKIES_SO)
    PluginRuntimePermission.LOGIN_PROMPT -> strings.text(StringKey.LETS_THE_PLUGIN_ASK_YOU_TO_LOG)
    PluginRuntimePermission.FAVORITE_MUTATION -> strings.text(StringKey.LETS_THE_PLUGIN_CHANGE_YOUR_FAVORITES_ON)
    PluginRuntimePermission.BROWSER_CHALLENGE -> if (details?.browserLoginOrigins.isNullOrEmpty()) {
        strings.text(StringKey.LETS_THE_PLUGIN_OPEN_A_BROWSER_PAGE_2)
    } else {
        strings.text(
            StringKey.LETS_THE_PLUGIN_OPEN_A_BROWSER_PAGE,
            details.browserLoginOrigins.joinToString(),
        )
    }
    PluginRuntimePermission.BROWSER_IMAGES -> strings.text(
        StringKey.LOADS_ITS_IMAGES_IN_A_PRIVATE_BROWSER,
        details?.browserImageOrigins?.joinToString().orEmpty().ifEmpty { "—" },
    )
    PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK -> ""
}

