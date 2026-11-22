package dev.shinsou.kmp.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.contracts.BrowseFilter
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.BrowsePage
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.plugin.contracts.BrowseSortSelection
import dev.shinsou.kmp.plugin.contracts.BrowseTriState
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.core.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.flow.collect

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SourceCatalogueScreen(
    source: BrowseSource,
    refreshGeneration: Long,
    page: BrowsePage,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    challengeAvailable: Boolean,
    challengePreparing: Boolean,
    onBack: () -> Unit,
    onWebChallenge: () -> Unit,
    /** Opens the source's account settings; null for a source that has no login. */
    onLogin: (() -> Unit)?,
    /** From the live source row: the row this screen was opened with does not see a later sign-in. */
    signedIn: Boolean,
    onBrowse: (String, SourceCatalogueMode, List<BrowseFilter>?) -> Unit,
    onLoadMore: (String, SourceCatalogueMode, List<BrowseFilter>?) -> Unit,
    onOpenManga: (BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var query by remember(source.identityKey) { mutableStateOf("") }
    var mode by remember(source.identityKey) { mutableStateOf(SourceCatalogueMode.Popular) }
    var appliedFilters by remember(source.identityKey) { mutableStateOf<List<BrowseFilter>?>(null) }
    var filterDialogVisible by remember(source.identityKey) { mutableStateOf(false) }
    // Keep the last submitted request separate from the editable search controls. Otherwise
    // typing a new query while the old result grid is at its end could auto-load another page
    // for a query that the user has not submitted yet.
    var requestedQuery by remember(source.identityKey) { mutableStateOf("") }
    var requestedMode by remember(source.identityKey) { mutableStateOf(SourceCatalogueMode.Popular) }
    var requestedFilters by remember(source.identityKey) { mutableStateOf<List<BrowseFilter>?>(null) }
    val gridState = rememberLazyGridState()

    fun requestBrowse(
        search: String = query,
        selectedMode: SourceCatalogueMode = mode,
        filters: List<BrowseFilter>? = appliedFilters,
    ) {
        requestedQuery = search
        requestedMode = selectedMode
        requestedFilters = filters
        onBrowse(search, selectedMode, filters)
    }

    fun requestLoadMore() {
        onLoadMore(requestedQuery, requestedMode, requestedFilters)
    }

    LaunchedEffect(source.identityKey, refreshGeneration) {
        if (refreshGeneration > 0L) {
            requestBrowse(requestedQuery, requestedMode, requestedFilters)
        }
    }

    val latestPage = rememberUpdatedState(page)
    val latestLoading = rememberUpdatedState(loading)
    val latestLoadingMore = rememberUpdatedState(loadingMore)
    val latestError = rememberUpdatedState(error)
    val latestLoadMore = rememberUpdatedState<() -> Unit> { requestLoadMore() }
    LaunchedEffect(
        source.identityKey,
        page.items.size,
        page.hasNextPage,
        loading,
        loadingMore,
    ) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }.collect { nearEnd ->
            if (
                nearEnd &&
                latestPage.value.hasNextPage &&
                latestError.value == null &&
                !latestLoading.value &&
                !latestLoadingMore.value
            ) {
                latestLoadMore.value()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = source.name,
            subtitle = source.language.uppercase(),
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) }
            },
            actions = {
                // Where a source's content depends on an account, signing in is one tap from its
                // catalogue rather than behind the sources list's menu.
                onLogin?.let { openLogin ->
                    IconButton(onClick = openLogin, modifier = Modifier.testTag("source-login")) {
                        SourceAccountIcon(signedIn)
                    }
                }
                if (source.filters.isNotEmpty()) {
                    IconButton(onClick = { filterDialogVisible = true }) {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = strings.filter,
                            tint = if (appliedFilters != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { requestBrowse() }) {
                    Icon(Icons.Outlined.Refresh, strings.refresh)
                }
            },
        )
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            onSubmit = { requestBrowse() },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == SourceCatalogueMode.Popular,
                onClick = {
                    mode = SourceCatalogueMode.Popular
                    requestBrowse(query, SourceCatalogueMode.Popular, appliedFilters)
                },
                label = { Text(strings.text(StringKey.POPULAR)) },
            )
            if (source.supportsFavorites) {
                FilterChip(
                    selected = mode == SourceCatalogueMode.Favorites,
                    onClick = {
                        mode = SourceCatalogueMode.Favorites
                        query = ""
                        appliedFilters = null
                        requestBrowse("", SourceCatalogueMode.Favorites, null)
                    },
                    label = { Text(strings.myLibrary) },
                )
            }
            if (source.supportsLatest) {
                FilterChip(
                    selected = mode == SourceCatalogueMode.Latest,
                    onClick = {
                        mode = SourceCatalogueMode.Latest
                        requestBrowse(query, SourceCatalogueMode.Latest, appliedFilters)
                    },
                    label = { Text(strings.text(StringKey.LATEST)) },
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (mode == SourceCatalogueMode.Favorites) {
                        requestBrowse("", SourceCatalogueMode.Favorites, null)
                    } else {
                        requestBrowse()
                    }
                },
            ) { Text(if (mode == SourceCatalogueMode.Favorites) strings.text(StringKey.REFRESH) else strings.search) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ShinsouLoadingIndicator()
            }
            error != null && page.items.isEmpty() -> EmptyState(
                title = strings.text(StringKey.SOURCE_ERROR),
                message = error,
                icon = { Icon(Icons.Outlined.Language, null, Modifier.size(30.dp)) },
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { requestBrowse() }) { Text(strings.retry) }
                        if (challengeAvailable) {
                            OutlinedButton(
                                onClick = onWebChallenge,
                                enabled = !challengePreparing,
                            ) {
                                Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (challengePreparing) strings.text(StringKey.PREPARING)
                                    else strings.text(StringKey.WEB_CHALLENGE_CLOUDFLARE),
                                )
                            }
                        }
                    }
                },
            )
            page.items.isEmpty() -> EmptyState(
                title = strings.noMatches,
                message = strings.text(StringKey.TRY_ANOTHER_QUERY_OR_REFRESH_THIS_SOURCE),
                icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
            )
            else -> LazyVerticalGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = gridState,
                columns = GridCells.Adaptive(128.dp),
                contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(page.items, key = BrowseManga::identityKey) { item ->
                    Column(
                        Modifier.combinedClickable(
                            onClick = { onOpenManga(item) },
                            onLongClick = { onOpenManga(item) },
                        ),
                    ) {
                        CoverImage(
                            title = item.title,
                            url = item.thumbnailUrl,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            headers = item.thumbnailHeaders,
                            sourceId = item.sourceId,
                            sourceKey = item.sourceKey,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (page.hasNextPage || loadingMore || error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (error != null) {
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (error != null && challengeAvailable) {
                                OutlinedButton(
                                    onClick = onWebChallenge,
                                    enabled = !challengePreparing,
                                ) {
                                    Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (challengePreparing) strings.text(StringKey.PREPARING)
                                        else strings.text(StringKey.WEB_CHALLENGE_CLOUDFLARE),
                                    )
                                }
                            }
                            if (loadingMore) {
                                ShinsouLoadingIndicator(indicatorModifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            } else if (page.hasNextPage) {
                                OutlinedButton(onClick = ::requestLoadMore) {
                                    Text(strings.text(StringKey.LOAD_MORE))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (filterDialogVisible) {
        SourceFilterDialog(
            sourceName = source.name,
            defaults = source.filters,
            current = appliedFilters ?: source.filters,
            onDismiss = { filterDialogVisible = false },
            onApply = { filters ->
                appliedFilters = filters
                mode = SourceCatalogueMode.Popular
                filterDialogVisible = false
                requestBrowse(query, SourceCatalogueMode.Popular, filters)
            },
            onClear = {
                appliedFilters = null
                filterDialogVisible = false
                requestBrowse(query, mode, null)
            },
        )
    }
}

@Composable
private fun SourceFilterDialog(
    sourceName: String,
    defaults: List<BrowseFilter>,
    current: List<BrowseFilter>,
    onDismiss: () -> Unit,
    onApply: (List<BrowseFilter>) -> Unit,
    onClear: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var working by remember(sourceName, defaults, current) { mutableStateOf(current) }
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text("$sourceName · ${strings.filter}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { working = defaults }) { Text(strings.text(StringKey.RESET)) }
                    TextButton(onClick = onClear) { Text(strings.clearFilters) }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(working.size, key = { index -> "filter:$index:${working[index].name}" }) { index ->
                        SourceFilterEditor(
                            filter = working[index],
                            onChange = { changed ->
                                working = working.toMutableList().apply { this[index] = changed }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(working) }) { Text(strings.text(StringKey.APPLY)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun SourceFilterEditor(
    filter: BrowseFilter,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    when (filter) {
        is BrowseFilter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        BrowseFilter.Separator -> HorizontalDivider()
        is BrowseFilter.Select -> SourceSelectFilter(filter, onChange)
        is BrowseFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = { onChange(filter.copy(state = it)) },
            label = { Text(filter.name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is BrowseFilter.CheckBox -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(filter.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = filter.state,
                onCheckedChange = { onChange(filter.copy(state = it)) },
            )
        }
        is BrowseFilter.TriState -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(filter.name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrowseTriState.entries.forEach { state ->
                    FilterChip(
                        selected = filter.state == state,
                        onClick = { onChange(filter.copy(state = state)) },
                        label = {
                            Text(
                                when (state) {
                                    BrowseTriState.Ignore -> strings.text(StringKey.IGNORE)
                                    BrowseTriState.Include -> strings.text(StringKey.INCLUDE)
                                    BrowseTriState.Exclude -> strings.text(StringKey.EXCLUDE)
                                },
                            )
                        },
                    )
                }
            }
        }
        is BrowseFilter.Group -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(filter.name, style = MaterialTheme.typography.titleSmall)
                filter.filters.forEachIndexed { index, child ->
                    SourceFilterEditor(
                        filter = child,
                        onChange = { changed ->
                            onChange(
                                filter.copy(
                                    filters = filter.filters.toMutableList().apply { this[index] = changed },
                                ),
                            )
                        },
                    )
                }
            }
        }
        is BrowseFilter.Sort -> SourceSortFilter(filter, onChange)
    }
}

@Composable
private fun SourceSelectFilter(
    filter: BrowseFilter.Select,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var expanded by remember(filter.name, filter.values) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(filter.name, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = filter.values.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(filter.values.getOrNull(filter.state) ?: strings.text(StringKey.NOT_SET), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.values.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(filter.copy(state = index))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSortFilter(
    filter: BrowseFilter.Sort,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var expanded by remember(filter.name, filter.values) { mutableStateOf(false) }
    val selection = filter.selection
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(filter.name, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selection?.let { filter.values.getOrNull(it.index) } ?: strings.text(StringKey.NONE), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(strings.text(StringKey.NONE)) },
                    onClick = {
                        onChange(filter.copy(selection = null))
                        expanded = false
                    },
                )
                filter.values.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(
                                filter.copy(
                                    selection = BrowseSortSelection(
                                        index = index,
                                        ascending = selection?.ascending ?: false,
                                    ),
                                ),
                            )
                            expanded = false
                        },
                    )
                }
            }
        }
        selection?.let { selected ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selected.ascending,
                    onClick = { onChange(filter.copy(selection = selected.copy(ascending = true))) },
                    label = { Text(strings.text(StringKey.ASCENDING)) },
                )
                FilterChip(
                    selected = !selected.ascending,
                    onClick = { onChange(filter.copy(selection = selected.copy(ascending = false))) },
                    label = { Text(strings.text(StringKey.DESCENDING)) },
                )
            }
        }
    }
}

/** Applies the user's hand-set read/unread marks over read state that loaded later. */
internal fun Set<String>.withReadMarks(marks: Map<String, Boolean>): Set<String> =
    this - marks.filterValues { !it }.keys + marks.filterValues { it }.keys
