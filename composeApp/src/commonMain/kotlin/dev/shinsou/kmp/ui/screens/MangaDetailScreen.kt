package dev.shinsou.kmp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ChapterFilterState
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.HairlineDivider
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text

@Composable
fun MangaDetailScreen(
    manga: Manga,
    chapters: List<Chapter>,
    downloads: List<DownloadQueueItem>,
    refreshingFromSource: Boolean,
    wideLayout: Boolean,
    selectedChapterIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTracking: () -> Unit,
    onUpdateNotes: (String) -> Unit,
    onChapterFlagsChange: (Long) -> Unit,
    onExcludedScanlatorsChange: (Set<String>) -> Unit,
    onReadChapter: (Long) -> Unit,
    onMarkChaptersRead: (Set<Long>, Boolean) -> Unit,
    onBookmarkChapters: (Set<Long>, Boolean) -> Unit,
    onDownloadChapters: (Set<Long>) -> Unit,
    onDeleteChapters: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val excludedScanlators = manga.excludedScanlators
    val unreadFilter = manga.unreadFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    val downloadedFilter = manga.downloadedFilter
    val sort = ChapterSortOption.fromFlag(manga.sorting)
    val descending = manga.sortDescending
    val downloadedChapterIds = remember(downloads) {
        downloads.filter { it.state == DownloadState.DOWNLOADED }.mapTo(hashSetOf()) { it.chapterId }
    }
    val availableScanlators = remember(chapters) {
        chapters.mapNotNull(Chapter::scanlator).filter(String::isNotBlank).distinct().sorted()
    }
    val visibleChapters = remember(
        chapters,
        downloadedChapterIds,
        excludedScanlators,
        unreadFilter,
        bookmarkedFilter,
        downloadedFilter,
        sort,
        descending,
    ) {
        chapters.asSequence()
            .filter { chapter -> chapter.scanlator?.takeIf(String::isNotBlank) !in excludedScanlators }
            .filter { unreadFilter.matches(it.read, positiveMatch = false) }
            .filter { bookmarkedFilter.matches(it.bookmark) }
            .filter { downloadedFilter.matches(it.id in downloadedChapterIds) }
            .sortedWith(sort.comparator(descending))
            .toList()
    }
    val duplicateIds = remember(visibleChapters) { duplicateChapterIds(visibleChapters) }
    val redundantDuplicateIds = remember(visibleChapters) { redundantDuplicateChapterIds(visibleChapters) }
    val continueChapter = remember(chapters) {
        val storyOrder = chapters.sortedWith(
            compareByDescending<Chapter> { it.sourceOrder }
                .thenBy { it.chapterNumber }
                .thenBy { it.id },
        )
        storyOrder.firstOrNull { !it.read && it.lastPageRead > 0 }
            ?: storyOrder.firstOrNull { !it.read }
            ?: storyOrder.lastOrNull()
    }
    val continueLabel = when {
        continueChapter == null -> null
        continueChapter.lastPageRead > 0 && !continueChapter.read -> strings.continueReading
        chapters.any { !it.read } -> strings.text("Start reading")
        else -> strings.text("Read again")
    }
    val allChapterIds = remember(chapters) { chapters.mapTo(linkedSetOf()) { it.id } }

    val chapterPane: @Composable () -> Unit = {
        ChapterPane(
            manga = manga,
            chapters = visibleChapters,
            allChapterCount = chapters.size,
            downloadedChapterIds = downloadedChapterIds,
            availableScanlators = availableScanlators,
            excludedScanlators = excludedScanlators,
            onExcludedScanlatorsChange = onExcludedScanlatorsChange,
            duplicateChapterIds = duplicateIds,
            redundantDuplicateChapterIds = redundantDuplicateIds,
            selectedChapterIds = selectedChapterIds,
            unreadFilter = unreadFilter,
            bookmarkedFilter = bookmarkedFilter,
            downloadedFilter = downloadedFilter,
            sort = sort,
            descending = descending,
            refreshingFromSource = refreshingFromSource,
            onUnreadFilterChange = { state ->
                onChapterFlagsChange(manga.chapterFlags.withBits(Manga.CHAPTER_UNREAD_MASK, state.unreadBits()))
            },
            onBookmarkFilterChange = { state ->
                onChapterFlagsChange(manga.chapterFlags.withBits(Manga.CHAPTER_BOOKMARKED_MASK, state.bookmarkBits()))
            },
            onDownloadFilterChange = { state ->
                onChapterFlagsChange(manga.chapterFlags.withBits(Manga.CHAPTER_DOWNLOADED_MASK, state.downloadBits()))
            },
            onSortChange = { selectedSort ->
                onChapterFlagsChange(manga.chapterFlags.withBits(Manga.CHAPTER_SORTING_MASK, selectedSort.flag))
            },
            onToggleSortDirection = {
                val direction = if (descending) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC
                onChapterFlagsChange(manga.chapterFlags.withBits(Manga.CHAPTER_SORT_DIR_MASK, direction))
            },
            onSelectionChange = onSelectionChange,
            onReadChapter = onReadChapter,
            onMarkChaptersRead = onMarkChaptersRead,
            onBookmarkChapters = onBookmarkChapters,
            onDownloadChapters = onDownloadChapters,
            onDeleteChapters = onDeleteChapters,
        )
    }

    if (wideLayout) {
        Row(modifier.fillMaxSize()) {
            MangaInfoPane(
                manga = manga,
                showBack = false,
                showClose = true,
                onBack = onBack,
                onToggleFavorite = onToggleFavorite,
                onRefresh = onRefresh,
                onShare = onShare,
                onOpenWeb = onOpenWeb,
                onOpenTracking = onOpenTracking,
                onUpdateNotes = onUpdateNotes,
                onDownloadAll = { onDownloadChapters(allChapterIds) },
                hasChapters = allChapterIds.isNotEmpty(),
                continueLabel = continueLabel,
                onContinueReading = continueChapter?.let { chapter -> { onReadChapter(chapter.id) } },
                refreshingFromSource = refreshingFromSource,
                scrollable = true,
                modifier = Modifier.width(330.dp).fillMaxHeight(),
            )
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) { chapterPane() }
        }
    } else {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "info") {
                MangaInfoPane(
                    manga = manga,
                    showBack = true,
                    onBack = onBack,
                    onToggleFavorite = onToggleFavorite,
                    onRefresh = onRefresh,
                    onShare = onShare,
                    onOpenWeb = onOpenWeb,
                    onOpenTracking = onOpenTracking,
                    onUpdateNotes = onUpdateNotes,
                    onDownloadAll = { onDownloadChapters(allChapterIds) },
                    hasChapters = allChapterIds.isNotEmpty(),
                    continueLabel = continueLabel,
                    onContinueReading = continueChapter?.let { chapter -> { onReadChapter(chapter.id) } },
                    refreshingFromSource = refreshingFromSource,
                    scrollable = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "chapters") {
                Box(Modifier.height((visibleChapters.size * 76 + 180).coerceAtMost(1200).dp)) { chapterPane() }
            }
        }
    }
}

@Composable
private fun MangaInfoPane(
    manga: Manga,
    showBack: Boolean,
    showClose: Boolean = false,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTracking: () -> Unit,
    onUpdateNotes: (String) -> Unit,
    onDownloadAll: () -> Unit,
    hasChapters: Boolean,
    continueLabel: String?,
    onContinueReading: (() -> Unit)?,
    refreshingFromSource: Boolean,
    scrollable: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var editingNotes by remember(manga.id) { mutableStateOf(false) }
    var notesDraft by remember(manga.id, manga.notes) { mutableStateOf(manga.notes) }
    val contentModifier = modifier
        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
    Column(
        contentModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) }
            } else if (showClose) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, strings.close) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !refreshingFromSource) {
                if (refreshingFromSource) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, strings.refresh)
                }
            }
            IconButton(onClick = onOpenWeb) { Icon(Icons.Outlined.OpenInBrowser, strings.text("Open original page")) }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, strings.share) }
        }
        CoverImage(
            manga.title,
            manga.thumbnailUrl,
            modifier = Modifier.width(186.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            manga.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            manga.author ?: manga.artist ?: strings.text("Unknown author"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = onToggleFavorite) {
                Icon(
                    if (manga.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (manga.favorite) strings.unfavorite else strings.favorite)
            }
            OutlinedButton(onClick = onOpenTracking) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.text("Track"))
            }
        }
        if (hasChapters) {
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = onDownloadAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.text("Download all chapters"))
            }
        }
        if (continueLabel != null && onContinueReading != null) {
            Spacer(Modifier.height(9.dp))
            OutlinedButton(onClick = onContinueReading, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(continueLabel)
            }
        }
        Spacer(Modifier.height(18.dp))
        manga.genre?.takeIf { it.isNotEmpty() }?.let { genres ->
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(genres) { genre -> AssistChip(onClick = {}, label = { Text(genre) }) }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            manga.description?.takeIf { it.isNotBlank() } ?: strings.text("No description available."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text("Notes"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            notesDraft = manga.notes
                            editingNotes = true
                        },
                    ) { Icon(Icons.Outlined.Edit, strings.text("Edit notes")) }
                }
                Text(
                    manga.notes.ifBlank { strings.text("Add a private note for this title.") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (manga.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    if (editingNotes) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { editingNotes = false },
            title = { Text(strings.text("Manga notes")) },
            text = {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    label = { Text(strings.text("Notes")) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateNotes(notesDraft.trim())
                        editingNotes = false
                    },
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onClick = { editingNotes = false }) { Text(strings.cancel) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterPane(
    manga: Manga,
    chapters: List<Chapter>,
    allChapterCount: Int,
    downloadedChapterIds: Set<Long>,
    availableScanlators: List<String>,
    excludedScanlators: Set<String>,
    onExcludedScanlatorsChange: (Set<String>) -> Unit,
    duplicateChapterIds: Set<Long>,
    redundantDuplicateChapterIds: Set<Long>,
    selectedChapterIds: Set<Long>,
    unreadFilter: ChapterFilterState,
    bookmarkedFilter: ChapterFilterState,
    downloadedFilter: ChapterFilterState,
    sort: ChapterSortOption,
    descending: Boolean,
    refreshingFromSource: Boolean,
    onUnreadFilterChange: (ChapterFilterState) -> Unit,
    onBookmarkFilterChange: (ChapterFilterState) -> Unit,
    onDownloadFilterChange: (ChapterFilterState) -> Unit,
    onSortChange: (ChapterSortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onReadChapter: (Long) -> Unit,
    onMarkChaptersRead: (Set<Long>, Boolean) -> Unit,
    onBookmarkChapters: (Set<Long>, Boolean) -> Unit,
    onDownloadChapters: (Set<Long>) -> Unit,
    onDeleteChapters: (Set<Long>) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var sortMenuVisible by remember { mutableStateOf(false) }
    var scanlatorFilterVisible by remember { mutableStateOf(false) }
    val selectedChapters = chapters.filter { it.id in selectedChapterIds }
    val markSelectedRead = selectedChapters.isEmpty() || selectedChapters.any { !it.read }
    val bookmarkSelected = selectedChapters.isEmpty() || selectedChapters.any { !it.bookmark }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                Column(Modifier.fillMaxWidth()) {
                    if (selectedChapterIds.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onSelectionChange(emptySet()) }) {
                                Icon(Icons.Filled.Check, strings.done)
                            }
                            Text(
                                "${selectedChapterIds.size} ${strings.selected}",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onSelectionChange(chapters.mapTo(linkedSetOf()) { it.id }) }) {
                                Icon(Icons.Outlined.SelectAll, strings.text("Select all visible chapters"))
                            }
                            IconButton(onClick = { onMarkChaptersRead(selectedChapterIds, markSelectedRead) }) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    if (markSelectedRead) strings.markRead else strings.markUnread,
                                )
                            }
                            IconButton(onClick = { onBookmarkChapters(selectedChapterIds, bookmarkSelected) }) {
                                Icon(
                                    if (bookmarkSelected) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    if (bookmarkSelected) strings.text("Bookmark") else strings.text("Remove bookmarks"),
                                )
                            }
                            IconButton(onClick = { onDownloadChapters(selectedChapterIds) }) {
                                Icon(Icons.Outlined.Download, strings.download)
                            }
                            IconButton(onClick = { onDeleteChapters(selectedChapterIds) }) {
                                Icon(Icons.Outlined.Delete, strings.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(strings.chapters, style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    if (chapters.size == allChapterCount) {
                                        strings.text("{0} total", allChapterCount)
                                    } else {
                                        strings.text("{0} of {1}", chapters.size, allChapterCount)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (redundantDuplicateChapterIds.isNotEmpty()) {
                                IconButton(
                                    onClick = { onMarkChaptersRead(redundantDuplicateChapterIds, true) },
                                ) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        strings.text("Mark duplicate chapter copies read"),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { sortMenuVisible = true }) {
                                    Icon(Icons.Outlined.Sort, strings.text("Sort by {0}", strings.text(sort.label)))
                                }
                                DropdownMenu(sortMenuVisible, onDismissRequest = { sortMenuVisible = false }) {
                                    ChapterSortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(strings.text(option.label)) },
                                            leadingIcon = if (option == sort) {
                                                { Icon(Icons.Filled.Check, null) }
                                            } else null,
                                            onClick = {
                                                onSortChange(option)
                                                sortMenuVisible = false
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (descending) strings.text("Descending") else strings.text("Ascending")) },
                                        onClick = {
                                            onToggleSortDirection()
                                            sortMenuVisible = false
                                        },
                                    )
                                }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            item {
                                FilterChip(
                                    selected = unreadFilter != ChapterFilterState.DISABLED,
                                    onClick = { onUnreadFilterChange(unreadFilter.next()) },
                                    label = { Text(unreadFilter.label(strings.text("Unread"), strings.text("Read"))) },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = bookmarkedFilter != ChapterFilterState.DISABLED,
                                    onClick = { onBookmarkFilterChange(bookmarkedFilter.next()) },
                                    label = { Text(bookmarkedFilter.label(strings.text("Bookmarked"), strings.text("Not bookmarked"))) },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = downloadedFilter != ChapterFilterState.DISABLED,
                                    onClick = { onDownloadFilterChange(downloadedFilter.next()) },
                                    label = { Text(downloadedFilter.label(strings.text("Downloaded"), strings.text("Not downloaded"))) },
                                )
                            }
                            if (availableScanlators.isNotEmpty()) {
                                item {
                                    FilterChip(
                                        selected = excludedScanlators.isNotEmpty(),
                                        onClick = { scanlatorFilterVisible = true },
                                        label = {
                                            Text(
                                                if (excludedScanlators.isEmpty()) strings.text("Scanlators")
                                                else strings.text("{0} hidden", excludedScanlators.size),
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.People, null, Modifier.size(17.dp)) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (chapters.isEmpty() && refreshingFromSource) {
            item {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                }
            }
        } else if (chapters.isEmpty()) {
            item {
                EmptyState(
                    title = strings.text("No chapters"),
                    message = strings.text("Refresh the title or change the chapter filters."),
                    icon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(30.dp)) },
                )
            }
        } else {
            items(
                chapterPresentationItems(chapters),
                key = { entry ->
                    when (entry) {
                        is ChapterPresentationItem.ChapterRow -> "chapter-${entry.chapter.id}"
                        is ChapterPresentationItem.MissingRange -> "gap-${entry.position}"
                    }
                },
            ) { entry ->
                when (entry) {
                    is ChapterPresentationItem.MissingRange -> MissingChapterDivider(entry)
                    is ChapterPresentationItem.ChapterRow -> {
                        val chapter = entry.chapter
                        val selected = chapter.id in selectedChapterIds
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onLongClick = { onSelectionChange(selectedChapterIds.toggle(chapter.id)) },
                                onClick = {
                                    if (selectedChapterIds.isNotEmpty()) {
                                        onSelectionChange(selectedChapterIds.toggle(chapter.id))
                                    } else {
                                        onReadChapter(chapter.id)
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
                                            chapter.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold,
                                            color = if (chapter.read) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (chapter.id in duplicateChapterIds) {
                                            Spacer(Modifier.width(5.dp))
                                            Icon(
                                                Icons.Outlined.ContentCopy,
                                                strings.text("Duplicate chapter number"),
                                                Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        chapter.scanlator?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (chapter.lastPageRead > 0 && !chapter.read) {
                                            Text(strings.text("Page {0}", chapter.lastPageRead + 1), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                if (chapter.bookmark) Icon(Icons.Filled.Favorite, strings.text("Bookmarked"), Modifier.size(17.dp))
                                if (chapter.id in downloadedChapterIds) {
                                    Icon(Icons.Outlined.Download, strings.text("Downloaded"), Modifier.size(17.dp))
                                }
                                IconButton(onClick = { onReadChapter(chapter.id) }) {
                                    Icon(Icons.Filled.PlayArrow, strings.text("Read {0}", chapter.name))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (scanlatorFilterVisible) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { scanlatorFilterVisible = false },
            title = { Text(strings.text("Scanlators")) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(availableScanlators, key = { it }) { scanlator ->
                        FilterChip(
                            selected = scanlator !in excludedScanlators,
                            onClick = {
                                onExcludedScanlatorsChange(
                                    if (scanlator in excludedScanlators) excludedScanlators - scanlator
                                    else excludedScanlators + scanlator,
                                )
                            },
                            label = { Text(scanlator, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    if (scanlator in excludedScanlators) Icons.Outlined.People else Icons.Filled.Check,
                                    null,
                                    Modifier.size(17.dp),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scanlatorFilterVisible = false }) { Text(strings.done) }
            },
            dismissButton = {
                if (excludedScanlators.isNotEmpty()) {
                    TextButton(onClick = { onExcludedScanlatorsChange(emptySet()) }) { Text(strings.text("Show all")) }
                }
            },
        )
    }
}

@Composable
private fun MissingChapterDivider(range: ChapterPresentationItem.MissingRange) {
    val strings = LocalShinsouStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HairlineDivider(Modifier.weight(1f))
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            missingChapterLabel(range.lowerExistingChapter, range.upperExistingChapter, strings),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        HairlineDivider(Modifier.weight(1f))
    }
}

private fun missingChapterLabel(
    lower: Double,
    upper: Double,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): String {
    val start = formatChapterNumber(lower + 1.0)
    val end = formatChapterNumber(upper - 1.0)
    return if (start == end) strings.text("Missing ch. {0}", start)
    else strings.text("Missing ch. {0}–{1}", start, end)
}

private fun formatChapterNumber(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private enum class ChapterSortOption(val label: String, val flag: Long) {
    Source("Source order", Manga.CHAPTER_SORTING_SOURCE),
    Number("Chapter number", Manga.CHAPTER_SORTING_NUMBER),
    UploadDate("Upload date", Manga.CHAPTER_SORTING_UPLOAD_DATE),
    Alphabet("Alphabetical", Manga.CHAPTER_SORTING_ALPHABET),
    ;

    fun comparator(descending: Boolean): Comparator<Chapter> {
        val comparator = when (this) {
            Source -> compareBy<Chapter> { it.sourceOrder }.thenBy { it.chapterNumber }
            Number -> compareBy<Chapter> { it.chapterNumber }.thenBy { it.sourceOrder }
            UploadDate -> compareBy<Chapter> { it.dateUpload }.thenBy { it.sourceOrder }
            Alphabet -> compareBy<Chapter> { it.name.lowercase() }.thenBy { it.sourceOrder }
        }
        return if (descending) comparator.reversed() else comparator
    }

    companion object {
        fun fromFlag(flag: Long): ChapterSortOption = entries.firstOrNull { it.flag == flag } ?: Source
    }
}

private fun ChapterFilterState.matches(actual: Boolean, positiveMatch: Boolean = true): Boolean = when (this) {
    ChapterFilterState.DISABLED -> true
    ChapterFilterState.INCLUDE -> actual == positiveMatch
    ChapterFilterState.EXCLUDE -> actual != positiveMatch
}

private fun ChapterFilterState.next(): ChapterFilterState = when (this) {
    ChapterFilterState.DISABLED -> ChapterFilterState.INCLUDE
    ChapterFilterState.INCLUDE -> ChapterFilterState.EXCLUDE
    ChapterFilterState.EXCLUDE -> ChapterFilterState.DISABLED
}

private fun ChapterFilterState.label(included: String, excluded: String): String = when (this) {
    ChapterFilterState.DISABLED, ChapterFilterState.INCLUDE -> included
    ChapterFilterState.EXCLUDE -> excluded
}

private fun ChapterFilterState.unreadBits(): Long = when (this) {
    ChapterFilterState.DISABLED -> Manga.SHOW_ALL
    ChapterFilterState.INCLUDE -> Manga.CHAPTER_SHOW_UNREAD
    ChapterFilterState.EXCLUDE -> Manga.CHAPTER_SHOW_READ
}

private fun ChapterFilterState.downloadBits(): Long = when (this) {
    ChapterFilterState.DISABLED -> Manga.SHOW_ALL
    ChapterFilterState.INCLUDE -> Manga.CHAPTER_SHOW_DOWNLOADED
    ChapterFilterState.EXCLUDE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
}

private fun ChapterFilterState.bookmarkBits(): Long = when (this) {
    ChapterFilterState.DISABLED -> Manga.SHOW_ALL
    ChapterFilterState.INCLUDE -> Manga.CHAPTER_SHOW_BOOKMARKED
    ChapterFilterState.EXCLUDE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
}

private fun Long.withBits(mask: Long, value: Long): Long = (this and mask.inv()) or (value and mask)
