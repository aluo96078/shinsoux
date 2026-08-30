package dev.shinsou.kmp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.LibraryDisplayMode
import dev.shinsou.kmp.domain.model.LibraryFilter
import dev.shinsou.kmp.domain.model.LibraryFilterState
import dev.shinsou.kmp.domain.model.LibraryItem
import dev.shinsou.kmp.domain.model.LibrarySettings
import dev.shinsou.kmp.domain.model.LibrarySort
import dev.shinsou.kmp.domain.model.LibrarySortType
import dev.shinsou.kmp.domain.model.SortDirection
import dev.shinsou.kmp.domain.model.commonMangaCategorySelection
import dev.shinsou.kmp.domain.model.normalizeMangaCategorySelection
import dev.shinsou.kmp.domain.model.toggleMangaCategorySelection
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.LibraryContentType
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlin.math.absoluteValue

@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    contentTypes: Map<Long, LibraryContentType> = emptyMap(),
    categories: List<Category>,
    settings: LibrarySettings,
    selectedCategoryId: Long,
    selectedMangaIds: Set<Long>,
    compactChrome: Boolean,
    onCategorySelected: (Long) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    onDeleteCategory: (Long) -> Unit,
    onReorderCategories: (List<Long>) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onSettingsChange: (LibrarySettings) -> Unit,
    onOpenManga: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    continueReadingItem: LibraryItem? = null,
    onRefresh: () -> Unit,
    onMarkSelectedRead: (Set<Long>) -> Unit,
    onMoveSelected: (Set<Long>, Set<Long>) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var query by remember { mutableStateOf("") }
    var filterMenuVisible by remember { mutableStateOf(false) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    var moveDialogVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var manageCategoriesDialogVisible by remember { mutableStateOf(false) }

    val visibleItems by remember(items, query, selectedCategoryId, settings.filter, settings.sort) {
        derivedStateOf {
            items.asSequence()
                .filter {
                    selectedCategoryId == ALL_LIBRARY_CATEGORY_ID ||
                        it.libraryManga.category == selectedCategoryId
                }
                .filter { it.matches(query) }
                .filter { it.matches(settings.filter) }
                .sortedWith(settings.sort.comparator())
                .distinctBy { it.id }
                .toList()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        if (selectedMangaIds.isNotEmpty()) {
            LibrarySelectionHeader(
                count = selectedMangaIds.size,
                allSelected = selectedMangaIds.size == visibleItems.size && visibleItems.isNotEmpty(),
                onClose = { onSelectionChange(emptySet()) },
                onSelectAll = {
                    onSelectionChange(
                        if (selectedMangaIds.size == visibleItems.size) emptySet()
                        else visibleItems.mapTo(linkedSetOf()) { it.id },
                    )
                },
                onMarkRead = { onMarkSelectedRead(selectedMangaIds) },
                onMove = { moveDialogVisible = true },
                onDelete = { deleteDialogVisible = true },
            )
        } else {
            ScreenHeader(
                title = strings.library,
                subtitle = "${visibleItems.size} ${strings.text("titles")}",
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = strings.text("Refresh library"))
                    }
                    Box {
                        IconButton(onClick = { filterMenuVisible = true }) {
                            Icon(
                                Icons.Outlined.FilterList,
                                contentDescription = strings.filter,
                                tint = if (settings.filter.hasActiveFilters) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LibraryFilterMenu(
                            expanded = filterMenuVisible,
                            filter = settings.filter,
                            availableTrackerIds = items.flatMap { it.trackerIds }.distinct().sorted().toSet(),
                            onDismiss = { filterMenuVisible = false },
                            onChange = { onSettingsChange(settings.copy(filter = it)) },
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuVisible = true }) {
                            Icon(Icons.Outlined.Sort, contentDescription = strings.sort)
                        }
                        LibrarySortMenu(
                            expanded = sortMenuVisible,
                            sort = settings.sort,
                            onDismiss = { sortMenuVisible = false },
                            onChange = { onSettingsChange(settings.copy(sort = it)) },
                        )
                    }
                    IconButton(
                        onClick = {
                            val display = if (settings.displayMode == LibraryDisplayMode.LIST) {
                                LibraryDisplayMode.COMPACT_GRID
                            } else {
                                LibraryDisplayMode.LIST
                            }
                            onSettingsChange(settings.copy(displayMode = display))
                        },
                    ) {
                        Crossfade(settings.displayMode, label = "library-layout") { display ->
                            Icon(
                                if (display == LibraryDisplayMode.LIST) Icons.Outlined.GridView else Icons.Outlined.List,
                                contentDescription = strings.text("Change layout"),
                            )
                        }
                    }
                },
            )
        }

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.searchLibrary,
            compact = compactChrome,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )

        CategoryStrip(
            categories = categories,
            selected = selectedCategoryId,
            onSelected = onCategorySelected,
            onManageCategories = { manageCategoriesDialogVisible = true },
        )

        if (visibleItems.isEmpty()) {
            EmptyState(
                title = if (query.isNotBlank() || settings.filter.hasActiveFilters) strings.noMatches else strings.libraryEmpty,
                message = if (query.isNotBlank() || settings.filter.hasActiveFilters) {
                    strings.text("Try a different search or clear the active filters.")
                } else {
                    strings.text("Browse sources and add a title to start reading.")
                },
                icon = { Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.size(30.dp)) },
                action = if (settings.filter.hasActiveFilters) {
                    {
                        OutlinedButton(onClick = { onSettingsChange(settings.copy(filter = LibraryFilter())) }) {
                            Text(strings.clearFilters)
                        }
                    }
                } else null,
            )
        } else {
            AnimatedContent(settings.displayMode, label = "library-content") { mode ->
                if (mode == LibraryDisplayMode.LIST) {
                    LibraryList(
                        items = visibleItems,
                        contentTypes = contentTypes,
                        selectedIds = selectedMangaIds,
                        allowLongPressSelection = !compactChrome,
                        onOpen = onOpenManga,
                        onContinue = onContinueReading,
                        onToggleSelection = { id ->
                            onSelectionChange(selectedMangaIds.toggle(id))
                        },
                    )
                } else {
                    LibraryGrid(
                        items = visibleItems,
                        contentTypes = contentTypes,
                        selectedIds = selectedMangaIds,
                        displayMode = mode,
                        portraitColumns = settings.portraitColumns,
                        landscapeColumns = settings.landscapeColumns,
                        allowLongPressSelection = !compactChrome,
                        onOpen = onOpenManga,
                        onContinue = onContinueReading,
                        onToggleSelection = { id ->
                            onSelectionChange(selectedMangaIds.toggle(id))
                        },
                    )
                }
            }
        }
        }

        if (selectedMangaIds.isEmpty()) {
            continueReadingItem
                ?.takeIf { it.unreadCount > 0 && it.libraryManga.lastRead > 0 }
                ?.let { item ->
                    ContinueReadingBanner(
                        item = item,
                        onClick = { onContinueReading(item.id) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
        }
    }

    if (moveDialogVisible) {
        val commonCategories = commonMangaCategorySelection(
            selectedMangaIds.map { mangaId ->
                items.asSequence()
                    .filter { it.id == mangaId }
                    .mapTo(linkedSetOf()) { it.libraryManga.category }
            },
        )
        CategoryPickerDialog(
            categories = categories,
            selectedCategoryIds = commonCategories,
            onDismiss = { moveDialogVisible = false },
            onConfirm = { categoryIds ->
                onMoveSelected(selectedMangaIds, categoryIds)
                onSelectionChange(emptySet())
                moveDialogVisible = false
            },
        )
    }

    if (deleteDialogVisible) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { deleteDialogVisible = false },
                title = { Text(strings.text("Remove selected titles?")) },
            text = {
                Text(strings.text("{0} selected title(s) will be removed from your library.", selectedMangaIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected(selectedMangaIds)
                        onSelectionChange(emptySet())
                        deleteDialogVisible = false
                    },
                ) { Text(strings.remove, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDialogVisible = false }) { Text(strings.cancel) } },
        )
    }

    if (manageCategoriesDialogVisible) {
        CategoryManagementDialog(
            categories = categories,
            onDismiss = { manageCategoriesDialogVisible = false },
            onCreate = onCreateCategory,
            onRename = onRenameCategory,
            onDelete = onDeleteCategory,
            onReorder = onReorderCategories,
        )
    }
}

@Composable
private fun ContinueReadingBanner(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                title = item.libraryManga.manga.title,
                url = item.libraryManga.manga.thumbnailUrl,
                modifier = Modifier.size(width = 40.dp, height = 56.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    strings.continueReading,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.libraryManga.manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.unreadCount} ${strings.chapters}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = strings.text("Continue {0}", item.libraryManga.manga.title),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun CategoryStrip(
    categories: List<Category>,
    selected: Long,
    onSelected: (Long) -> Unit,
    onManageCategories: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val customCategories = remember(categories) {
        categories.filterNot { it.isSystemCategory }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selected == ALL_LIBRARY_CATEGORY_ID,
                    onClick = { onSelected(ALL_LIBRARY_CATEGORY_ID) },
                    label = { Text(strings.all) },
                )
            }
            items(customCategories, key = { it.id }) { category ->
                FilterChip(
                    selected = selected == category.id,
                    onClick = { onSelected(category.id) },
                    label = { Text(category.name) },
                )
            }
        }
        AssistChip(
            onClick = onManageCategories,
            label = { Text(strings.text("Manage categories"), maxLines = 1) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.padding(end = 20.dp),
        )
    }
}

@Composable
private fun CategoryManagementDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val customCategories = categories.filterNot { it.isSystemCategory }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var name by remember { mutableStateOf("") }
    var expandedCategoryMenuId by remember { mutableStateOf<Long?>(null) }

    fun move(categoryId: Long, delta: Int) {
        val ids = customCategories.map { it.id }.toMutableList()
        val from = ids.indexOf(categoryId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from >= 0 && from != to) {
            val moved = ids.removeAt(from)
            ids.add(to, moved)
            onReorder(ids)
        }
    }

    when {
        creating -> CategoryNameDialog(
            title = strings.text("New category"),
            value = name,
            onValueChange = { name = it },
            onDismiss = { creating = false },
            onConfirm = {
                onCreate(name.trim())
                creating = false
            },
        )
        editing != null -> {
            val category = editing ?: return
            CategoryNameDialog(
                title = strings.text("Rename category"),
                value = name,
                onValueChange = { name = it },
                onDismiss = { editing = null },
                onConfirm = {
                    onRename(category, name.trim())
                    editing = null
                },
            )
        }
        deleting != null -> {
            val category = deleting ?: return
            AlertDialog(
                modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
                onDismissRequest = { deleting = null },
                title = { Text(strings.text("Delete {0}?", category.name)) },
                text = { Text(strings.text("Titles in this category will be moved to the default category.")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(category.id)
                            deleting = null
                        },
                    ) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(strings.cancel) }
                },
            )
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.text("Manage categories")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            name = ""
                            creating = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.text("Add category"))
                    }
                    if (customCategories.isEmpty()) {
                        Text(
                            strings.text("No custom categories"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                            itemsIndexed(customCategories, key = { _, category -> category.id }) { index, category ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        category.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box {
                                        IconButton(onClick = { expandedCategoryMenuId = category.id }) {
                                            Icon(Icons.Outlined.MoreVert, strings.text("Manage categories"))
                                        }
                                        DropdownMenu(
                                            expanded = expandedCategoryMenuId == category.id,
                                            onDismissRequest = { expandedCategoryMenuId = null },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(strings.text("Move {0} up", category.name)) },
                                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) },
                                                enabled = index > 0,
                                                onClick = {
                                                    expandedCategoryMenuId = null
                                                    move(category.id, -1)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(strings.text("Move {0} down", category.name)) },
                                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                                                enabled = index < customCategories.lastIndex,
                                                onClick = {
                                                    expandedCategoryMenuId = null
                                                    move(category.id, 1)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(strings.text("Rename category")) },
                                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                                onClick = {
                                                    expandedCategoryMenuId = null
                                                    name = category.name
                                                    editing = category
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(strings.delete, color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Outlined.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                    )
                                                },
                                                onClick = {
                                                    expandedCategoryMenuId = null
                                                    deleting = category
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(strings.done) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(
    items: List<LibraryItem>,
    contentTypes: Map<Long, LibraryContentType>,
    selectedIds: Set<Long>,
    displayMode: LibraryDisplayMode,
    portraitColumns: Int,
    landscapeColumns: Int,
    allowLongPressSelection: Boolean,
    onOpen: (Long) -> Unit,
    onContinue: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnCount = if (maxWidth > maxHeight) landscapeColumns else portraitColumns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount.coerceAtLeast(1)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val manga = item.libraryManga.manga
                val selected = manga.id in selectedIds
                Column(
                    modifier = Modifier
                        .librarySecondarySelection(selectedIds) { onToggleSelection(manga.id) }
                        .combinedClickable(
                            onLongClick = if (allowLongPressSelection) {
                                { onToggleSelection(manga.id) }
                            } else {
                                null
                            },
                            onClick = {
                                if (selectedIds.isNotEmpty()) onToggleSelection(manga.id)
                                else onOpen(manga.id)
                            },
                        ),
                ) {
                    Box {
                        CoverImage(
                            title = manga.title,
                            url = manga.thumbnailUrl,
                            selected = selected,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        )
                        LibraryContentTypeBadge(
                            type = contentTypes[manga.id] ?: LibraryContentType.UNKNOWN,
                            modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
                        )
                        MangaBadges(
                            unread = item.libraryManga.unreadCount,
                            downloads = item.downloadCount.toInt(),
                            modifier = Modifier.align(Alignment.BottomStart).padding(7.dp),
                        )
                        Surface(
                            onClick = { onContinue(manga.id) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                            shadowElevation = 3.dp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = strings.text("Continue {0}", manga.title),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                    if (displayMode != LibraryDisplayMode.COVER_ONLY_GRID) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            manga.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) {
                            Text(
                                manga.author ?: strings.text("Unknown author"),
                                style = MaterialTheme.typography.bodyMedium,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryList(
    items: List<LibraryItem>,
    contentTypes: Map<Long, LibraryContentType>,
    selectedIds: Set<Long>,
    allowLongPressSelection: Boolean,
    onOpen: (Long) -> Unit,
    onContinue: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val manga = item.libraryManga.manga
            val selected = manga.id in selectedIds
            val background by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                label = "library-row-selection",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background, RoundedCornerShape(12.dp))
                    .librarySecondarySelection(selectedIds) { onToggleSelection(manga.id) }
                    .combinedClickable(
                        onLongClick = if (allowLongPressSelection) {
                            { onToggleSelection(manga.id) }
                        } else {
                            null
                        },
                        onClick = {
                            if (selectedIds.isNotEmpty()) onToggleSelection(manga.id)
                            else onOpen(manga.id)
                        },
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverImage(
                    title = manga.title,
                    url = manga.thumbnailUrl,
                    selected = selected,
                    modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
                )
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        LibraryContentTypeBadge(contentTypes[manga.id] ?: LibraryContentType.UNKNOWN)
                        Text(
                            manga.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        buildString {
                            append("${item.libraryManga.totalChapters} ${strings.text("chapters")}")
                            manga.author?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    MangaBadges(item.libraryManga.unreadCount, item.downloadCount.toInt())
                }
                IconButton(onClick = { onContinue(manga.id) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = strings.text("Continue {0}", manga.title))
                }
            }
        }
    }
}

/**
 * Mouse/stylus secondary-click selection for library items.
 *
 * [combinedClickable] intentionally models touch long-press, but it does not expose a secondary
 * button action. Consume the secondary press at the initial pointer pass so it cannot also open
 * the title, then toggle selection. Touch devices simply never produce this button event and keep
 * their long-press path.
 */
private fun Modifier.librarySecondarySelection(
    selectionState: Set<Long>,
    onSecondaryClick: () -> Unit,
): Modifier = pointerInput(selectionState) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
            event.changes.forEach { it.consume() }
            onSecondaryClick()
        }
    }
}

@Composable
private fun MangaBadges(
    unread: Int,
    downloads: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (unread > 0) {
            MiniBadge(text = if (unread > 999) "999+" else unread.toString(), icon = null)
        }
        if (downloads > 0) {
            MiniBadge(text = downloads.toString(), icon = Icons.Outlined.Download)
        }
    }
}

@Composable
private fun LibraryContentTypeBadge(
    type: LibraryContentType,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val label = when (type) {
        LibraryContentType.MANGA -> strings.text("Manga")
        LibraryContentType.NOVEL -> strings.text("Novel")
        LibraryContentType.MIXED -> strings.text("Mixed")
        LibraryContentType.UNKNOWN -> strings.text("Unknown type")
    }
    Surface(
        modifier = modifier,
        color = when (type) {
            LibraryContentType.MANGA -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
            LibraryContentType.NOVEL -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
            LibraryContentType.MIXED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
            LibraryContentType.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
        },
        contentColor = when (type) {
            LibraryContentType.MANGA -> MaterialTheme.colorScheme.onTertiaryContainer
            LibraryContentType.NOVEL -> MaterialTheme.colorScheme.onSecondaryContainer
            LibraryContentType.MIXED -> MaterialTheme.colorScheme.onPrimaryContainer
            LibraryContentType.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(7.dp),
        shadowElevation = 1.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniBadge(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(7.dp),
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LibrarySelectionHeader(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Check, contentDescription = strings.done) }
            Text("$count ${strings.selected}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) {
                Icon(if (allSelected) Icons.Outlined.CheckCircle else Icons.Outlined.SelectAll, strings.selectAll)
            }
            IconButton(onClick = onMarkRead) { Icon(Icons.Outlined.Bookmark, strings.markRead) }
            IconButton(onClick = onMove) { Icon(Icons.Outlined.MoreHoriz, strings.moveToCategory) }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, strings.remove, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LibraryFilterMenu(
    expanded: Boolean,
    filter: LibraryFilter,
    availableTrackerIds: Set<Int>,
    onDismiss: () -> Unit,
    onChange: (LibraryFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            strings.filter,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        FilterMenuItem(strings.text("Downloaded"), filter.downloaded) { onChange(filter.copy(downloaded = it)) }
        FilterMenuItem(strings.text("Unread"), filter.unread) { onChange(filter.copy(unread = it)) }
        FilterMenuItem(strings.text("Started"), filter.started) { onChange(filter.copy(started = it)) }
        FilterMenuItem(strings.text("Bookmarked"), filter.bookmarked) { onChange(filter.copy(bookmarked = it)) }
        FilterMenuItem(strings.text("Completed"), filter.completed) { onChange(filter.copy(completed = it)) }
        availableTrackerIds.forEach { trackerId ->
            FilterMenuItem(trackerName(trackerId), filter.trackerFilter(trackerId)) {
                onChange(filter.withTrackerFilter(trackerId, it))
            }
        }
        if (filter.hasActiveFilters) {
            DropdownMenuItem(
                text = { Text(strings.clearFilters, color = MaterialTheme.colorScheme.primary) },
                onClick = { onChange(LibraryFilter()) },
            )
        }
    }
}

@Composable
private fun FilterMenuItem(
    label: String,
    state: LibraryFilterState,
    onChange: (LibraryFilterState) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                Text(
                    when (state) {
                        LibraryFilterState.DISABLED -> strings.text("Any")
                        LibraryFilterState.INCLUDE -> strings.text("Include")
                        LibraryFilterState.EXCLUDE -> strings.text("Exclude")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        LibraryFilterState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        LibraryFilterState.INCLUDE -> MaterialTheme.colorScheme.primary
                        LibraryFilterState.EXCLUDE -> MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        onClick = { onChange(state.next()) },
    )
}

@Composable
private fun LibrarySortMenu(
    expanded: Boolean,
    sort: LibrarySort,
    onDismiss: () -> Unit,
    onChange: (LibrarySort) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.sort, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(sort.copy(direction = sort.direction.toggled())) }) {
                Icon(
                    if (sort.direction == SortDirection.ASCENDING) Icons.Outlined.ArrowUpward
                    else Icons.Outlined.ArrowDownward,
                    contentDescription = strings.text("Reverse sort"),
                )
            }
        }
        LibrarySortType.entries.forEach { type ->
            DropdownMenuItem(
                text = { Text(type.displayName(strings)) },
                leadingIcon = {
                    if (type == sort.type) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    else Spacer(Modifier.size(18.dp))
                },
                onClick = {
                    onChange(sort.copy(type = type))
                    onDismiss()
                },
            )
        }
    }
}

@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    selectedCategoryIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    title: String? = null,
) {
    val strings = LocalShinsouStrings.current
    val pickerCategories = remember(categories) {
        categories
            .filterNot { it.isSystemCategory }
            .sortedWith(compareBy<Category> { it.sort }.thenBy { it.id })
    }
    var selection by remember(selectedCategoryIds, pickerCategories) {
        mutableStateOf(normalizeMangaCategorySelection(selectedCategoryIds))
    }
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text(title ?: strings.moveToCategory) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pickerCategories.isEmpty()) {
                    Text(
                        strings.text("No custom categories"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    pickerCategories.forEach { category ->
                        Surface(
                            onClick = {
                                selection = toggleMangaCategorySelection(selection, category.id)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = category.id in selection,
                                    onCheckedChange = null,
                                )
                                Text(category.name, Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalizeMangaCategorySelection(selection)) }) {
                Text(strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private fun LibraryItem.matches(filter: LibraryFilter): Boolean {
    fun LibraryFilterState.accepts(value: Boolean): Boolean = when (this) {
        LibraryFilterState.DISABLED -> true
        LibraryFilterState.INCLUDE -> value
        LibraryFilterState.EXCLUDE -> !value
    }
    val manga = libraryManga.manga
    return filter.downloaded.accepts(downloadCount > 0) &&
        filter.unread.accepts(libraryManga.unreadCount > 0) &&
        filter.started.accepts(libraryManga.hasStarted) &&
        filter.bookmarked.accepts(libraryManga.hasBookmarks) &&
        filter.completed.accepts(manga.status == 2L) &&
        filter.trackerFilters.all { (trackerId, state) -> state.accepts(trackerId in trackerIds) }
}

private fun trackerName(id: Int): String = when (id) {
    1 -> "MyAnimeList"
    3 -> "Kitsu"
    4 -> "Shikimori"
    5 -> "Bangumi"
    6 -> "MangaUpdates"
    7 -> "Komga"
    8 -> "Kavita"
    9 -> "Suwayomi"
    else -> "Tracker $id"
}

private fun LibrarySort.comparator(): Comparator<LibraryItem> {
    val base = when (type) {
        LibrarySortType.ALPHABETICAL -> compareBy<LibraryItem> { it.libraryManga.manga.title.lowercase() }
        LibrarySortType.LAST_READ -> compareBy { it.libraryManga.lastRead }
        LibrarySortType.LAST_UPDATE -> compareBy { it.libraryManga.manga.lastUpdate }
        LibrarySortType.UNREAD_COUNT -> compareBy { it.libraryManga.unreadCount }
        LibrarySortType.TOTAL_CHAPTERS -> compareBy { it.libraryManga.totalChapters }
        LibrarySortType.LATEST_CHAPTER -> compareBy { it.libraryManga.latestUpload }
        LibrarySortType.CHAPTER_FETCH_DATE -> compareBy { it.libraryManga.chapterFetchedAt }
        LibrarySortType.DATE_ADDED -> compareBy { it.libraryManga.manga.dateAdded }
        LibrarySortType.TRACKER_MEAN -> compareBy { it.libraryManga.manga.title.lowercase() }
        LibrarySortType.RANDOM -> compareBy { (it.id xor randomSeed).hashCode().absoluteValue }
    }
    return if (direction == SortDirection.ASCENDING) base else base.reversed()
}

private fun LibrarySortType.displayName(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): String {
    val key = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    return strings.text(key)
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

internal const val ALL_LIBRARY_CATEGORY_ID: Long = Long.MIN_VALUE
