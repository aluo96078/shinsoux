package dev.shinsou.kmp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.UpdateSettings
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.update.AppRelease
import dev.shinsou.kmp.update.AppUpdateSource
import dev.shinsou.kmp.update.UpdateChannel
import kotlinx.coroutines.CancellationException

/** The outcome of the last check, for the settings page. */
public sealed interface UpdateCheckResult {
    public data class Newer(val release: AppRelease) : UpdateCheckResult
    public data object UpToDate : UpdateCheckResult
    public data object Failed : UpdateCheckResult
}

/** Where the app's update check stands: what was found, and whether its prompt is showing. */
@Stable
public class AppUpdateState(private val checker: AppUpdateSource) {
    public val currentVersion: String get() = checker.currentVersion

    internal fun claimLaunchCheck(): Boolean = checker.claimLaunchCheck()
    public var checking: Boolean by mutableStateOf(false)
        private set
    public var result: UpdateCheckResult? by mutableStateOf(null)
        private set

    /** The release the prompt offers; null while none is shown. */
    public var prompt: AppRelease? by mutableStateOf(null)
        internal set

    /**
     * Checks once on the settings' channel. [interactive] (the user asked) shows what was found
     * even when it was skipped before.
     */
    public suspend fun check(settings: UpdateSettings, interactive: Boolean = false): UpdateCheckResult {
        checking = true
        val outcome = try {
            checker.check(if (settings.includeBeta) UpdateChannel.BETA else UpdateChannel.STABLE)
                ?.let { UpdateCheckResult.Newer(it) } ?: UpdateCheckResult.UpToDate
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Exception) {
            Log.warn("Updates", logged) { "The update check failed" }
            UpdateCheckResult.Failed
        } finally {
            checking = false
        }
        result = outcome
        if (outcome is UpdateCheckResult.Newer && (interactive || outcome.release.version.toString() != settings.skippedVersion)) {
            prompt = outcome.release
        }
        return outcome
    }
}

public val LocalAppUpdates: androidx.compose.runtime.ProvidableCompositionLocal<AppUpdateState?> = staticCompositionLocalOf { null }

/** Checks for updates once at launch when [settings] allow it, and shows the prompt for what is found. */
@Composable
public fun AppUpdateHost(
    state: AppUpdateState,
    settings: UpdateSettings,
    onSkip: (version: String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    // Once per launch, with the settings as stored: switching checks on takes effect at the next
    // start ("Check now" checks at once).
    val current by rememberUpdatedState(settings)
    LaunchedEffect(state) {
        // claimed first: checks switched on later in this launch wait for the next one
        if (state.claimLaunchCheck() && current.checkForUpdates) state.check(current)
    }
    val release = state.prompt ?: return
    val strings = LocalShinsouStrings.current
    AlertDialog(
        onDismissRequest = { state.prompt = null },
        title = { Text(strings.text(StringKey.SHINSOU_X_X_IS_AVAILABLE, release.version.toString())) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release.version.isBeta) Text(strings.text(StringKey.BETA_VERSION), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                Text(strings.text(StringKey.YOU_HAVE_X, state.currentVersion), style = MaterialTheme.typography.bodyMedium)
                release.notes.trim().takeIf { it.isNotEmpty() }?.let { notes ->
                    Text(notes.take(2_000), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.prompt = null; onOpenUrl(release.url) }) { Text(strings.text(StringKey.DOWNLOAD)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { state.prompt = null; onSkip(release.version.toString()) }) { Text(strings.text(StringKey.SKIP_THIS_VERSION)) }
                TextButton(onClick = { state.prompt = null }) { Text(strings.text(StringKey.LATER)) }
            }
        },
    )
}
