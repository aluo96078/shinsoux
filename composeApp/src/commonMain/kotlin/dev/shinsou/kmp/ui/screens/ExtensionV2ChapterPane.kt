package dev.shinsou.kmp.ui.screens

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@OptIn(ExperimentalFoundationApi::class)
/** What the chapter list shows and does; built once for either layout. */
internal class ExtensionV2ChapterPaneModel(
    val units: List<ExtensionUnitSelectionV2>,
    val selectedUnitIds: Set<String>,
    val readUnitIds: Set<String>,
    val inProgressUnits: Map<String, ExtensionUnitPageProgressV2>,
    val bookmarkedUnitIds: Set<String>,
    val downloadedUnitIds: Set<String>,
    val chapterFilter: ExtensionChapterFilter,
    val reverseWebsiteOrder: Boolean,
    val loading: Boolean,
    val loadingMore: Boolean,
    val loadError: String?,
    val hasNextPage: Boolean,
    val operationMessage: String?,
    val operationFailed: Boolean,
    val busyUnitId: String?,
    val openingReaderUnitId: String?,
    val queuedUnitIds: Set<String>,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit,
    val onReadUnit: (ExtensionUnitSelectionV2) -> Unit,
    val onDownloadUnits: (List<ExtensionUnitSelectionV2>) -> Unit,
    val onTranslateUnits: ((List<ExtensionUnitSelectionV2>) -> Unit)? = null,
    val translatedUnitIds: Set<String> = emptySet(),
    val prefetchByUnit: Map<String, dev.shinsou.kmp.translation.PrefetchEntry> = emptyMap(),
    val onSelectionChange: (Set<String>) -> Unit,
    val onReadStateChange: (Set<String>, Boolean) -> Unit,
    val onBookmarkStateChange: (Set<String>, Boolean) -> Unit,
    val onDeleteDownloads: (Set<String>) -> Unit,
    val onFilterChange: (ExtensionChapterFilter) -> Unit,
    val onToggleWebsiteOrder: () -> Unit,
)

/** The chapters [model] shows, filtered and in the order chosen. */
@Composable
internal fun rememberVisibleChapterUnits(model: ExtensionV2ChapterPaneModel): List<ExtensionUnitSelectionV2> = remember(
    model.units,
    model.readUnitIds,
    model.bookmarkedUnitIds,
    model.downloadedUnitIds,
    model.chapterFilter,
    model.reverseWebsiteOrder,
) {
    val filtered = model.units.asSequence()
        .filter { selection ->
            when (model.chapterFilter) {
                ExtensionChapterFilter.ALL -> true
                ExtensionChapterFilter.UNREAD -> selection.unit.remoteId !in model.readUnitIds
                ExtensionChapterFilter.READ -> selection.unit.remoteId in model.readUnitIds
                ExtensionChapterFilter.BOOKMARKED -> selection.unit.remoteId in model.bookmarkedUnitIds
                ExtensionChapterFilter.DOWNLOADED -> selection.unit.remoteId in model.downloadedUnitIds
            }
        }
        .toList()
    websiteOrderedItems(filtered, model.reverseWebsiteOrder)
}

/** The chapter list as its own scrolling column (the wide layout's right-hand side). */
@Composable
internal fun ExtensionV2ChapterPane(
    model: ExtensionV2ChapterPaneModel,
    visibleUnits: List<ExtensionUnitSelectionV2>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 12.dp)) {
        extensionV2ChapterItems(model, visibleUnits, horizontalPadding = 14.dp)
    }
}

/**
 * The chapter list as items of a lazy list: the heading, then one item per chapter, then the
 * "load more" footer. Only the chapters on screen are composed.
 */
internal fun LazyListScope.extensionV2ChapterItems(
    model: ExtensionV2ChapterPaneModel,
    visibleUnits: List<ExtensionUnitSelectionV2>,
    horizontalPadding: Dp,
) {
    item("extension-chapters-header") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExtensionV2ChapterHeader(model, visibleUnits)
        }
    }
    val units = model.units
    val status = when {
        model.loading && units.isEmpty() -> 0
        model.loadError != null && units.isEmpty() -> 1
        units.isEmpty() -> 2
        visibleUnits.isEmpty() -> 3
        else -> -1
    }
    if (status >= 0) {
        item("extension-chapters-status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                ExtensionV2ChapterStatus(model, visibleUnits)
            }
        }
    } else {
        items(visibleUnits, key = { "chapter:" + it.unit.remoteId }) { selection ->
            Box(Modifier.padding(horizontal = horizontalPadding, vertical = 2.dp)) {
                ExtensionV2ChapterRow(model, selection)
            }
        }
    }
    if (model.hasNextPage || model.loadingMore || model.loadError != null) {
        item("extension-chapters-footer") {
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                ExtensionV2ChapterFooter(model)
            }
        }
    }
}

@Composable
internal fun ExtensionV2ChapterHeader(model: ExtensionV2ChapterPaneModel, visibleUnits: List<ExtensionUnitSelectionV2>) {
    val units = model.units
    val selectedUnitIds = model.selectedUnitIds
    val readUnitIds = model.readUnitIds
    val inProgressUnits = model.inProgressUnits
    val bookmarkedUnitIds = model.bookmarkedUnitIds
    val downloadedUnitIds = model.downloadedUnitIds
    val chapterFilter = model.chapterFilter
    val reverseWebsiteOrder = model.reverseWebsiteOrder
    val loading = model.loading
    val loadingMore = model.loadingMore
    val loadError = model.loadError
    val hasNextPage = model.hasNextPage
    val operationMessage = model.operationMessage
    val operationFailed = model.operationFailed
    val busyUnitId = model.busyUnitId
    val openingReaderUnitId = model.openingReaderUnitId
    val queuedUnitIds = model.queuedUnitIds
    val onRetry = model.onRetry
    val onLoadMore = model.onLoadMore
    val onReadUnit = model.onReadUnit
    val onDownloadUnits = model.onDownloadUnits
    val onTranslateUnits = model.onTranslateUnits
    val translatedUnitIds = model.translatedUnitIds
    val prefetchByUnit = model.prefetchByUnit
    val onSelectionChange = model.onSelectionChange
    val onReadStateChange = model.onReadStateChange
    val onBookmarkStateChange = model.onBookmarkStateChange
    val onDeleteDownloads = model.onDeleteDownloads
    val onFilterChange = model.onFilterChange
    val onToggleWebsiteOrder = model.onToggleWebsiteOrder
    val strings = LocalShinsouStrings.current
    var sortMenuVisible by remember { mutableStateOf(false) }
    val selectedVisible = visibleUnits.filter { it.unit.remoteId in selectedUnitIds }
    val markSelectedRead = selectedVisible.isEmpty() || selectedVisible.any { it.unit.remoteId !in readUnitIds }
    val bookmarkSelected = selectedVisible.isEmpty() || selectedVisible.any { it.unit.remoteId !in bookmarkedUnitIds }
    if (selectedUnitIds.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSelectionChange(emptySet()) }) {
                Icon(Icons.Filled.Check, strings.done)
            }
            Text(
                "${selectedUnitIds.size} ${strings.selected}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onSelectionChange(visibleUnits.mapTo(linkedSetOf()) { it.unit.remoteId }) }) {
                Icon(Icons.Outlined.SelectAll, strings.text("Select all visible chapters"))
            }
            IconButton(onClick = { onReadStateChange(selectedUnitIds, markSelectedRead) }) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    if (markSelectedRead) strings.markRead else strings.markUnread,
                )
            }
            IconButton(onClick = { onBookmarkStateChange(selectedUnitIds, bookmarkSelected) }) {
                Icon(
                    if (bookmarkSelected) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (bookmarkSelected) strings.text("Bookmark") else strings.text("Remove bookmarks"),
                )
            }
            IconButton(onClick = { onDownloadUnits(selectedVisible) }) {
                Icon(Icons.Outlined.Download, strings.download)
            }
            if (onTranslateUnits != null) {
                IconButton(onClick = { onTranslateUnits(selectedVisible) }, modifier = Modifier.testTag("translate-selected-chapters")) {
                    Icon(Icons.Outlined.Translate, strings.text("Translate"))
                }
            }
            IconButton(onClick = { onDeleteDownloads(selectedUnitIds) }) {
                Icon(Icons.Outlined.Delete, strings.delete, tint = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.chapters, style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (hasNextPage) strings.text("{0} loaded", units.size) else strings.text("{0} total", units.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { sortMenuVisible = true }) {
                    Icon(Icons.Outlined.Sort, strings.text("Sort chapters"))
                }
                DropdownMenu(sortMenuVisible, onDismissRequest = { sortMenuVisible = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (reverseWebsiteOrder) strings.text("Reverse website order")
                                else strings.text("Website order"),
                            )
                        },
                        onClick = {
                            onToggleWebsiteOrder()
                            sortMenuVisible = false
                        },
                    )
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                FilterChip(
                    selected = chapterFilter == ExtensionChapterFilter.UNREAD,
                    onClick = {
                        onFilterChange(
                            if (chapterFilter == ExtensionChapterFilter.UNREAD) ExtensionChapterFilter.ALL
                            else ExtensionChapterFilter.UNREAD,
                        )
                    },
                    label = { Text(strings.text("Unread")) },
                )
            }
            item {
                FilterChip(
                    selected = chapterFilter == ExtensionChapterFilter.BOOKMARKED,
                    onClick = {
                        onFilterChange(
                            if (chapterFilter == ExtensionChapterFilter.BOOKMARKED) ExtensionChapterFilter.ALL
                            else ExtensionChapterFilter.BOOKMARKED,
                        )
                    },
                    label = { Text(strings.text("Bookmarked")) },
                )
            }
            item {
                FilterChip(
                    selected = chapterFilter == ExtensionChapterFilter.DOWNLOADED,
                    onClick = {
                        onFilterChange(
                            if (chapterFilter == ExtensionChapterFilter.DOWNLOADED) ExtensionChapterFilter.ALL
                            else ExtensionChapterFilter.DOWNLOADED,
                        )
                    },
                    label = { Text(strings.text("Downloaded")) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    operationMessage?.let { message ->
        Text(
            message,
            color = if (operationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun ExtensionV2ChapterStatus(model: ExtensionV2ChapterPaneModel, visibleUnits: List<ExtensionUnitSelectionV2>) {
    val units = model.units
    val selectedUnitIds = model.selectedUnitIds
    val readUnitIds = model.readUnitIds
    val inProgressUnits = model.inProgressUnits
    val bookmarkedUnitIds = model.bookmarkedUnitIds
    val downloadedUnitIds = model.downloadedUnitIds
    val chapterFilter = model.chapterFilter
    val reverseWebsiteOrder = model.reverseWebsiteOrder
    val loading = model.loading
    val loadingMore = model.loadingMore
    val loadError = model.loadError
    val hasNextPage = model.hasNextPage
    val operationMessage = model.operationMessage
    val operationFailed = model.operationFailed
    val busyUnitId = model.busyUnitId
    val openingReaderUnitId = model.openingReaderUnitId
    val queuedUnitIds = model.queuedUnitIds
    val onRetry = model.onRetry
    val onLoadMore = model.onLoadMore
    val onReadUnit = model.onReadUnit
    val onDownloadUnits = model.onDownloadUnits
    val onTranslateUnits = model.onTranslateUnits
    val translatedUnitIds = model.translatedUnitIds
    val prefetchByUnit = model.prefetchByUnit
    val onSelectionChange = model.onSelectionChange
    val onReadStateChange = model.onReadStateChange
    val onBookmarkStateChange = model.onBookmarkStateChange
    val onDeleteDownloads = model.onDeleteDownloads
    val onFilterChange = model.onFilterChange
    val onToggleWebsiteOrder = model.onToggleWebsiteOrder
    val strings = LocalShinsouStrings.current
    when {
        loading && units.isEmpty() -> Box(
            Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center,
        ) { ShinsouLoadingIndicator() }
        loadError != null && units.isEmpty() -> EmptyState(
            title = strings.text("Unable to load chapters"),
            message = loadError,
            icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
            action = { Button(onClick = onRetry) { Text(strings.retry) } },
        )
        units.isEmpty() -> EmptyState(
            title = strings.text("No chapters"),
            message = strings.text("This extension did not return any readable units."),
            icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
        )
        visibleUnits.isEmpty() -> EmptyState(
            title = strings.text("No matching chapters"),
            message = strings.text("Change the chapter filter to see more."),
            icon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(30.dp)) },
        )
    }
}

@Composable
internal fun ExtensionV2ChapterRow(model: ExtensionV2ChapterPaneModel, selection: ExtensionUnitSelectionV2) {
    val units = model.units
    val selectedUnitIds = model.selectedUnitIds
    val readUnitIds = model.readUnitIds
    val inProgressUnits = model.inProgressUnits
    val bookmarkedUnitIds = model.bookmarkedUnitIds
    val downloadedUnitIds = model.downloadedUnitIds
    val chapterFilter = model.chapterFilter
    val reverseWebsiteOrder = model.reverseWebsiteOrder
    val loading = model.loading
    val loadingMore = model.loadingMore
    val loadError = model.loadError
    val hasNextPage = model.hasNextPage
    val operationMessage = model.operationMessage
    val operationFailed = model.operationFailed
    val busyUnitId = model.busyUnitId
    val openingReaderUnitId = model.openingReaderUnitId
    val queuedUnitIds = model.queuedUnitIds
    val onRetry = model.onRetry
    val onLoadMore = model.onLoadMore
    val onReadUnit = model.onReadUnit
    val onDownloadUnits = model.onDownloadUnits
    val onTranslateUnits = model.onTranslateUnits
    val translatedUnitIds = model.translatedUnitIds
    val prefetchByUnit = model.prefetchByUnit
    val onSelectionChange = model.onSelectionChange
    val onReadStateChange = model.onReadStateChange
    val onBookmarkStateChange = model.onBookmarkStateChange
    val onDeleteDownloads = model.onDeleteDownloads
    val onFilterChange = model.onFilterChange
    val onToggleWebsiteOrder = model.onToggleWebsiteOrder
    val strings = LocalShinsouStrings.current
    val unitId = selection.unit.remoteId
    val selected = unitId in selectedUnitIds
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onLongClick = { onSelectionChange(selectedUnitIds.toggleExtensionUnit(unitId)) },
            onClick = {
                if (selectedUnitIds.isNotEmpty()) {
                    onSelectionChange(selectedUnitIds.toggleExtensionUnit(unitId))
                } else {
                    onReadUnit(selection)
                }
            },
        ),
    ) {
        Row(
            Modifier.padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selection.unit.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unitId in readUnitIds) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (unitId in readUnitIds) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unitId in bookmarkedUnitIds) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Filled.Favorite, strings.text("Bookmarked"), Modifier.size(15.dp))
                    }
                }
                val unitProgress = inProgressUnits[unitId]?.takeIf { unitId !in readUnitIds }
                when {
                    unitProgress != null -> Text(
                        readingProgressPageLabel(strings, unitProgress.pageIndex, unitProgress.pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    unitId in readUnitIds -> Text(
                        strings.text("Read"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                prefetchByUnit[unitId]?.let { entry -> PrefetchChapterStatus(entry) }
            }
            if (unitId in translatedUnitIds || prefetchByUnit[unitId]?.state == dev.shinsou.kmp.translation.PrefetchState.DONE) {
                Icon(Icons.Outlined.Translate, strings.text("Translated"), Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (unitId in downloadedUnitIds) {
                Icon(Icons.Outlined.Download, strings.text("Downloaded"), Modifier.size(17.dp))
            }
            IconButton(
                onClick = { onDownloadUnits(listOf(selection)) },
                enabled = busyUnitId == null && unitId !in queuedUnitIds,
            ) {
                if (unitId in queuedUnitIds || (busyUnitId == unitId && openingReaderUnitId == null)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Download, strings.download)
                }
            }
            IconButton(onClick = { onReadUnit(selection) }, enabled = busyUnitId == null) {
                if (openingReaderUnitId == unitId) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, strings.text("Start reading"))
                }
            }
        }
    }
}

@Composable
internal fun ExtensionV2ChapterFooter(model: ExtensionV2ChapterPaneModel) {
    val units = model.units
    val selectedUnitIds = model.selectedUnitIds
    val readUnitIds = model.readUnitIds
    val inProgressUnits = model.inProgressUnits
    val bookmarkedUnitIds = model.bookmarkedUnitIds
    val downloadedUnitIds = model.downloadedUnitIds
    val chapterFilter = model.chapterFilter
    val reverseWebsiteOrder = model.reverseWebsiteOrder
    val loading = model.loading
    val loadingMore = model.loadingMore
    val loadError = model.loadError
    val hasNextPage = model.hasNextPage
    val operationMessage = model.operationMessage
    val operationFailed = model.operationFailed
    val busyUnitId = model.busyUnitId
    val openingReaderUnitId = model.openingReaderUnitId
    val queuedUnitIds = model.queuedUnitIds
    val onRetry = model.onRetry
    val onLoadMore = model.onLoadMore
    val onReadUnit = model.onReadUnit
    val onDownloadUnits = model.onDownloadUnits
    val onTranslateUnits = model.onTranslateUnits
    val translatedUnitIds = model.translatedUnitIds
    val prefetchByUnit = model.prefetchByUnit
    val onSelectionChange = model.onSelectionChange
    val onReadStateChange = model.onReadStateChange
    val onBookmarkStateChange = model.onBookmarkStateChange
    val onDeleteDownloads = model.onDeleteDownloads
    val onFilterChange = model.onFilterChange
    val onToggleWebsiteOrder = model.onToggleWebsiteOrder
    val strings = LocalShinsouStrings.current
    if (hasNextPage || loadingMore || loadError != null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loadingMore) {
                ShinsouLoadingIndicator(indicatorModifier = Modifier.size(26.dp), strokeWidth = 3.dp)
            } else if (hasNextPage) {
                OutlinedButton(onClick = onLoadMore) { Text(strings.text("Load more")) }
            }
        }
    }
}

private fun Set<String>.toggleExtensionUnit(id: String): Set<String> =
    if (id in this) this - id else this + id
