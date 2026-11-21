package dev.shinsou.kmp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.PluginPermissionDetails
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.screens.PluginPermissionReviewDialog
import dev.shinsou.kmp.ui.screens.RepositoryTrustDialog
import kotlinx.coroutines.launch

/**
 * The dialogs of [PluginReviewCoordinator]: a package's first-access review and repository links.
 * [page] is what the shell shows: a review whose title open no longer stands once it changed goes.
 */
@Composable
internal fun PluginReviewDialogs(reviews: PluginReviewCoordinator<*>, page: Any?) {
    val strings = LocalShinsouStrings.current
    val state by reviews.state.collectAsState()
    state.linkedRepositoryTrust?.let { prompt ->
        RepositoryTrustDialog(
            prompt = prompt,
            onTrust = reviews::trustLinkedRepository,
            onDismiss = reviews::declineLinkedRepository,
        )
    }
    state.linkedUnsignedRepository?.let { url ->
        AlertDialog(
            onDismissRequest = reviews::declineLinkedUnsignedRepository,
            title = { Text(strings.text(StringKey.ADD_REPOSITORY)) },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = reviews::addLinkedUnsignedRepository) { Text(strings.text(StringKey.ADD)) }
            },
            dismissButton = {
                TextButton(onClick = reviews::declineLinkedUnsignedRepository) { Text(strings.cancel) }
            },
        )
    }
    state.sourceReviewGate?.let { gate ->
        var details by remember(gate) { mutableStateOf<PluginPermissionDetails?>(null) }
        LaunchedEffect(gate) { details = reviews.permissionDetails(gate) }
        LaunchedEffect(gate, page) { reviews.dropStaleReview() }
        PluginPermissionReviewDialog(
            review = gate.review,
            details = details,
            onConfirm = { selection -> reviews.approveReview(gate, selection) },
            onDismiss = reviews::dismissReview,
        )
    }
}

/** An extension asks to sign out of its source; shown only while the app is unlocked and on screen. */
@Composable
internal fun PluginLogoutDialog(app: ShinsouAppState) {
    val strings = LocalShinsouStrings.current
    val browse = app.appServices.browse
    val confirmations by browse.logoutConfirmations.collectAsState()
    val request = confirmations.firstOrNull() ?: return
    var confirming by remember(request.eventId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!confirming) browse.dismissPluginLogout(request.eventId) },
        title = { Text(strings.text(StringKey.LOGOUT)) },
        text = {
            Text(
                request.message?.takeIf(String::isNotBlank)
                    ?: strings.text(StringKey.LOG_OUT_OF_X, request.sourceName),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !confirming,
                onClick = {
                    confirming = true
                    app.environment.scope.launch {
                        runCatchingCancellable { browse.confirmPluginLogout(request.eventId) }
                            .onFailure { Log.warn("Extension", it) { "Confirming an extension logout request failed" } }
                        confirming = false
                    }
                },
            ) { Text(strings.text(StringKey.LOGOUT)) }
        },
        dismissButton = {
            TextButton(
                enabled = !confirming,
                onClick = { browse.dismissPluginLogout(request.eventId) },
            ) { Text(strings.cancel) }
        },
    )
}

/** Covers the app while it is locked. */
@Composable
internal fun AppLockOverlay(app: ShinsouAppState, state: AppLockState) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = Modifier.fillMaxSize().zIndex(10f),
        color = MaterialTheme.colorScheme.surface,
    ) {
        EmptyState(
            title = strings.text(StringKey.SHINSOU_X_IS_LOCKED),
            message = strings.text(StringKey.AUTHENTICATE_TO_CONTINUE),
            icon = {
                if (state.unlocking) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp))
                }
            },
            action = {
                Button(
                    enabled = !state.unlocking,
                    onClick = { app.environment.scope.launch { app.lock.unlock() } },
                ) { Text(strings.text(StringKey.UNLOCK)) }
            },
        )
    }
}
