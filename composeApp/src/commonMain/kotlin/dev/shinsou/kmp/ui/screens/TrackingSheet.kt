package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.tracking.TrackUpdate
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.tracking.TrackingProvider
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSheet(
    manga: Manga,
    tracks: List<Track>,
    coordinator: TrackingCoordinator,
    onOpenExternalUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(strings.text("Tracking"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        manga.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, strings.close) }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(coordinator.providers, key = { it.descriptor.id }) { provider ->
                    TrackerProviderCard(
                        manga = manga,
                        provider = provider,
                        track = tracks.firstOrNull { it.trackerId == provider.descriptor.id },
                        coordinator = coordinator,
                        onOpenExternalUrl = onOpenExternalUrl,
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun TrackerProviderCard(
    manga: Manga,
    provider: TrackingProvider,
    track: Track?,
    coordinator: TrackingCoordinator,
    onOpenExternalUrl: (String) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var authenticated by remember(provider.descriptor.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember(provider.descriptor.id) { mutableStateOf(false) }
    var errorMessage by remember(provider.descriptor.id) { mutableStateOf<String?>(null) }
    var callbackOrToken by remember(provider.descriptor.id) { mutableStateOf("") }
    var tokenVisible by remember(provider.descriptor.id) { mutableStateOf(false) }
    var query by remember(manga.id, provider.descriptor.id) { mutableStateOf(manga.title) }
    var searchResults by remember(manga.id, provider.descriptor.id) { mutableStateOf<List<TrackSearch>>(emptyList()) }
    var confirmRemove by remember(provider.descriptor.id) { mutableStateOf(false) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            errorMessage = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errorMessage = error.message ?: strings.text("The tracking operation could not be completed.")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(provider.descriptor.id, provider.configured) {
        if (provider.configured) {
            authenticated = runCatching { coordinator.isAuthenticated(provider.descriptor.id) }.getOrDefault(false)
        } else {
            authenticated = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.descriptor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            !provider.configured -> strings.text("Not configured")
                            authenticated == null -> strings.text("Checking account…")
                            authenticated == true -> strings.text("Signed in")
                            else -> strings.text("Not signed in")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else if (provider.configured && authenticated == true) {
                    TextButton(
                        onClick = {
                            perform {
                                coordinator.logout(provider.descriptor.id)
                                authenticated = false
                                searchResults = emptyList()
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.text("Log out"))
                    }
                }
            }

            if (!provider.configured) {
                Text(
                    provider.configurationMessage ?: strings.text("This tracker is not configured in this build."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (authenticated != true) {
                if (track != null) {
                    Text(
                        strings.text("The existing {0} link is retained. Sign in to sync or edit it.", provider.descriptor.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        runCatching { coordinator.authorizationUrl(provider.descriptor.id) }
                            .getOrNull()
                            ?.let(onOpenExternalUrl)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text("Open {0} login", provider.descriptor.name))
                }
                OutlinedTextField(
                    value = callbackOrToken,
                    onValueChange = { callbackOrToken = it },
                    label = { Text(strings.text("Callback URL or access token")) },
                    supportingText = {
                        Text(strings.text("Paste the redirected URL (the #access_token fragment is parsed) or the token itself. It is stored in platform secure storage."))
                    },
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (tokenVisible) strings.text("Hide token") else strings.text("Show token"),
                            )
                        }
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = callbackOrToken.isNotBlank() && !busy,
                    onClick = {
                        perform {
                            coordinator.completeAuthentication(
                                trackerId = provider.descriptor.id,
                                pastedCallbackOrToken = callbackOrToken,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                            )
                            callbackOrToken = ""
                            authenticated = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.text("Complete login")) }
            } else if (track == null) {
                SearchAndBindPane(
                    query = query,
                    onQueryChange = { query = it },
                    results = searchResults,
                    busy = busy,
                    onSearch = {
                        perform { searchResults = coordinator.search(provider.descriptor.id, query) }
                    },
                    onBind = { result ->
                        perform {
                            coordinator.bind(manga.id, provider.descriptor.id, result)
                            searchResults = emptyList()
                        }
                    },
                )
            } else {
                BoundTrackEditor(
                    track = track,
                    busy = busy,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onRefresh = { perform { coordinator.refresh(manga.id, provider.descriptor.id) } },
                    onUpdate = { update -> perform { coordinator.update(manga.id, provider.descriptor.id, update) } },
                    onRemove = { confirmRemove = true },
                )
            }

            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { confirmRemove = false },
            title = { Text(strings.text("Remove tracking link?")) },
            text = { Text(strings.text("This removes the local {0} link. It does not delete the remote list entry.", provider.descriptor.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        perform { coordinator.remove(manga.id, provider.descriptor.id) }
                    },
                ) { Text(strings.remove, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun SearchAndBindPane(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<TrackSearch>,
    busy: Boolean,
    onSearch: () -> Unit,
    onBind: (TrackSearch) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(strings.text("Search manga")) },
        singleLine = true,
        trailingIcon = {
            IconButton(enabled = query.isNotBlank() && !busy, onClick = onSearch) {
                Icon(Icons.Outlined.Search, strings.search)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = query.isNotBlank() && !busy,
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.search) }

    results.take(MAX_SEARCH_RESULTS).forEachIndexed { index, result ->
        if (index > 0) HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(
                    result.publishingType.takeIf(String::isNotBlank),
                    result.startDate.takeIf(String::isNotBlank),
                    result.totalChapters.takeIf { it > 0 }?.let { "$it ${strings.chapters}" },
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(enabled = !busy, onClick = { onBind(result) }) {
                Icon(Icons.Outlined.Link, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(strings.text("Bind"))
            }
        }
    }
    if (results.size > MAX_SEARCH_RESULTS) {
        Text(
            strings.text("Showing the first {0} results. Refine the search to see more.", MAX_SEARCH_RESULTS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoundTrackEditor(
    track: Track,
    busy: Boolean,
    onOpenExternalUrl: (String) -> Unit,
    onRefresh: () -> Unit,
    onUpdate: (TrackUpdate) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var statusMenu by remember(track.id) { mutableStateOf(false) }
    var scoreDraft by remember(track.id, track.score) { mutableStateOf(track.score.displayNumber()) }
    val currentStatus = TrackStatus.entries.firstOrNull { it.rawValue == track.status }
    val progressMaximum = track.totalChapters.takeIf { it > 0 }?.toDouble()
    val parsedScore = scoreDraft.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (track.totalChapters > 0) {
                Text(
                    "${track.totalChapters} ${strings.chapters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(enabled = !busy, onClick = onRefresh) { Icon(Icons.Outlined.Refresh, strings.text("Refresh tracking")) }
        if (track.remoteUrl.isNotBlank()) {
            IconButton(onClick = { onOpenExternalUrl(track.remoteUrl) }) {
                Icon(Icons.Outlined.OpenInBrowser, strings.text("Open remote page"))
            }
        }
        IconButton(enabled = !busy, onClick = onRemove) { Icon(Icons.Outlined.Delete, strings.text("Remove tracking link")) }
    }
    HorizontalDivider()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.text("Status"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(enabled = !busy, onClick = { statusMenu = true }) {
                Text(strings.text(currentStatus.displayName()))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                TrackStatus.entries.forEach { status ->
                    DropdownMenuItem(
                        text = { Text(strings.text(status.displayName())) },
                        onClick = {
                            statusMenu = false
                            if (status != currentStatus) onUpdate(TrackUpdate(status = status))
                        },
                    )
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.text("Progress"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(
            enabled = !busy && track.lastChapterRead > 0,
            onClick = { onUpdate(TrackUpdate(progress = (track.lastChapterRead - 1).coerceAtLeast(0.0))) },
        ) { Icon(Icons.Outlined.RemoveCircle, strings.text("Decrease progress")) }
        Text(
            buildString {
                append(track.lastChapterRead.displayNumber())
                progressMaximum?.let { append(" / ").append(it.displayNumber()) }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(
            enabled = !busy && (progressMaximum == null || track.lastChapterRead < progressMaximum),
            onClick = {
                val next = track.lastChapterRead + 1
                onUpdate(TrackUpdate(progress = progressMaximum?.let { next.coerceAtMost(it) } ?: next))
            },
        ) { Icon(Icons.Outlined.AddCircle, strings.text("Increase progress")) }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = scoreDraft,
            onValueChange = { scoreDraft = it },
            label = { Text(strings.text("Score (0–10)")) },
            isError = scoreDraft.isNotBlank() && parsedScore == null,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Button(
            enabled = !busy && parsedScore != null && parsedScore != track.score,
            onClick = { parsedScore?.let { onUpdate(TrackUpdate(score = it)) } },
        ) { Text(strings.text("Update")) }
    }
}

private fun TrackStatus?.displayName(): String = when (this) {
    TrackStatus.READING -> "Reading"
    TrackStatus.COMPLETED -> "Completed"
    TrackStatus.ON_HOLD -> "On hold"
    TrackStatus.DROPPED -> "Dropped"
    TrackStatus.PLAN_TO_READ -> "Plan to read"
    TrackStatus.REREADING -> "Rereading"
    null -> "Unknown"
}

private fun Double.displayNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private const val MAX_SEARCH_RESULTS = 10
