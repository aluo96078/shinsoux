package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.contract.BrowseCallbacks
import dev.shinsou.kmp.contract.BrowseManga
import dev.shinsou.kmp.contract.BrowseSource
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.contract.MigrationCandidate
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@Composable
internal fun MigrationPane(
    migrations: List<MigrationCandidate>,
    sources: List<BrowseSource>,
    callbacks: BrowseCallbacks,
    onMigrate: (MigrationCandidate, BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    if (migrations.isEmpty()) {
        EmptyState(
            title = strings.text("Nothing to migrate"),
            message = strings.text("Migration suggestions appear after compatible sources are installed."),
            icon = { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(30.dp)) },
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(migrations, key = { it.mangaId }) { candidate ->
            MigrationCandidateCard(candidate, sources, callbacks, onMigrate)
        }
    }
}

@Composable
private fun MigrationCandidateCard(
    candidate: MigrationCandidate,
    sources: List<BrowseSource>,
    callbacks: BrowseCallbacks,
    onMigrate: (MigrationCandidate, BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    // The current source is not installed, so its kind is unknown: the user names it instead of
    // mixing manga and novel sources.
    var chosenType by remember(candidate.mangaId, candidate.contentType) { mutableStateOf(candidate.contentType) }
    val targetSources = remember(sources, candidate, chosenType) {
        eligibleMigrationSources(sources, candidate.copy(contentType = chosenType))
    }
    val sourceByIdentity = remember(targetSources) { targetSources.associateBy(BrowseSource::identityKey) }
    // Null searches every source of the same kind at once, by the title as it stands.
    var selectedSourceKey by remember(candidate.mangaId, targetSources) { mutableStateOf<String?>(null) }
    val selectedSource = selectedSourceKey?.let(sourceByIdentity::get)
    var sourceMenuVisible by remember(candidate.mangaId) { mutableStateOf(false) }
    var searchQuery by remember(candidate.mangaId) { mutableStateOf(candidate.title) }
    fun BrowseManga.migrationSource(): BrowseSource? =
        targetSources.firstOrNull { source ->
            if (sourceKey != null) source.sourceKey == sourceKey else source.sourceKey == null && source.id == sourceId
        }
    var searchResults by remember(candidate.mangaId, selectedSourceKey, candidate.suggestions) {
        mutableStateOf(
            rankBrowseResults(
                candidate.title,
                candidate.suggestions.filter { item ->
                    item.migrationSource()?.let { selectedSource == null || it == selectedSource } == true
                },
            ),
        )
    }
    var searchLoading by remember(candidate.mangaId) { mutableStateOf(false) }
    var searchError by remember(candidate.mangaId) { mutableStateOf<String?>(null) }
    var pendingTarget by remember(candidate.mangaId) { mutableStateOf<BrowseManga?>(null) }

    suspend fun searchOne(source: BrowseSource, query: String): List<BrowseManga> {
        val key = source.sourceKey
        return if (key != null) {
            callbacks.searchSourceV2(key, query, page = 0).items.map { it.toBrowseManga(source) }
        } else {
            callbacks.browseSource(source.id, query, page = 1).items
        }
    }

    fun searchSelectedSource() {
        val searched = selectedSource?.let(::listOf) ?: targetSources
        val submittedQuery = searchQuery.trim()
        if (searched.isEmpty() || submittedQuery.isEmpty() || searchLoading) return
        searchLoading = true
        searchError = null
        scope.launch {
            withFrameNanos { }
            val outcomes = withContext(Dispatchers.Default) {
                val permits = Semaphore(MIGRATION_SEARCH_PARALLELISM)
                searched.map { source ->
                    async {
                        permits.withPermit {
                            try {
                                searchOne(source, submittedQuery) to null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                emptyList<BrowseManga>() to (
                                    error.localizedSourceFailure(strings)
                                        ?: error.message ?: strings.text("Unable to search {0}", source.name)
                                    )
                            }
                        }
                    }
                }.awaitAll()
            }
            val found = outcomes.flatMap { it.first }
            val failures = outcomes.mapNotNull { it.second }
            searchResults = rankBrowseResults(submittedQuery, found)
            searchError = when {
                failures.isEmpty() -> null
                found.isEmpty() && failures.size == searched.size -> failures.first()
                else -> strings.text("{0} of {1} sources could not be searched", failures.size.toString(), searched.size.toString())
            }
            searchLoading = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.titleLarge)
            Text(
                strings.text("Current source: {0}", candidate.currentSourceName) + " · " +
                    strings.text("Type: {0}", candidate.contentType.migrationLabel(strings)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.contentType == PluginContentType.BOTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text("This title is a"), style = MaterialTheme.typography.bodySmall)
                    listOf(PluginContentType.MANGA, PluginContentType.NOVEL).forEach { type ->
                        FilterChip(
                            selected = chosenType == type,
                            onClick = { chosenType = if (chosenType == type) PluginContentType.BOTH else type },
                            label = { Text(type.migrationLabel(strings)) },
                        )
                    }
                }
            }
            if (targetSources.isEmpty()) {
                Text(
                    strings.text("Install or enable another compatible source to migrate this title."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The source picker gets its own line: sharing one with the title field left the
                // field a couple of characters wide on phones.
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { sourceMenuVisible = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            selectedSource?.name
                                ?: strings.text("All {0} sources ({1})", chosenType.migrationLabel(strings), targetSources.size.toString()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuVisible,
                        onDismissRequest = { sourceMenuVisible = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.text("All {0} sources ({1})", chosenType.migrationLabel(strings), targetSources.size.toString())) },
                            onClick = {
                                selectedSourceKey = null
                                sourceMenuVisible = false
                                searchError = null
                            },
                        )
                        targetSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text("${source.name} · ${source.language.uppercase()}") },
                                onClick = {
                                    selectedSourceKey = source.identityKey
                                    sourceMenuVisible = false
                                    searchError = null
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(strings.text("Search title")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = ::searchSelectedSource,
                        enabled = searchQuery.isNotBlank() && !searchLoading,
                    ) {
                        if (searchLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Search, strings.search, Modifier.size(18.dp))
                        }
                    }
                }
                searchError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!searchLoading && searchResults.isEmpty() && searchError == null) {
                    Text(
                        strings.text("Choose a target source, adjust the title if needed, then search."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (searchResults.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(searchResults.take(MIGRATION_RESULT_LIMIT), key = { "migration:${it.identityKey}" }) { target ->
                            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                BrowseResultCard(target, onClick = { pendingTarget = target })
                                target.migrationSource()?.let { source ->
                                    Text(
                                        source.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingTarget?.let { target ->
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { pendingTarget = null },
            title = {
                Text(
                    if (chosenType == PluginContentType.NOVEL) strings.text("Migrate to this novel?")
                    else strings.text("Migrate to this manga?"),
                )
            },
            text = {
                Text(
                    strings.text("Replace “{0}” with “{1}”? Read progress, categories and tracking links stay attached to this library entry.", candidate.title, target.title),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTarget = null
                        onMigrate(candidate, target)
                    },
                ) { Text(strings.migration, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTarget = null }) { Text(strings.cancel) }
            },
        )
    }
}
