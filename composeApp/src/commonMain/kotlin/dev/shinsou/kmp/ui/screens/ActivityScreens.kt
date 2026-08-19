package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.HistoryItem
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.UpdateItem
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlin.math.max
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UpdatesScreen(
    updates: List<UpdateItem>,
    onRefresh: () -> Unit,
    onOpenManga: (Long) -> Unit,
    onReadChapter: (Long, Long) -> Unit,
    onDownloadChapter: (Long, Long) -> Unit,
    allManga: List<Manga> = updates.map(UpdateItem::manga).distinctBy(Manga::id),
    allChapters: List<Chapter> = updates.map(UpdateItem::chapter),
    onMarkChaptersRead: (Set<Long>, Boolean) -> Unit = { _, _ -> },
    onDownloadChapters: (Set<Long>) -> Unit = {},
    onDeleteChapterDownloads: (Set<Long>) -> Unit = {},
    onToggleChapterBookmarks: (Set<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var selectedTab by remember { mutableStateOf(UpdatesTab.RECENT) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val updateIds = remember(updates) { updates.mapTo(linkedSetOf()) { it.chapter.id } }

    LaunchedEffect(updateIds) {
        selectedIds = selectedIds.intersect(updateIds)
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun toggleSelection(chapterId: Long) {
        selectedIds = if (chapterId in selectedIds) selectedIds - chapterId else selectedIds + chapterId
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.updates,
            subtitle = when {
                selectionMode -> strings.text("{0} selected", selectedIds.size)
                updates.isEmpty() -> strings.noUpdates
                else -> strings.text("{0} new chapters", updates.size)
            },
            actions = {
                if (selectionMode) {
                    TextButton(
                        onClick = {
                            selectedIds = if (selectedIds.size == updateIds.size) emptySet() else updateIds
                        },
                    ) { Text(if (selectedIds.size == updateIds.size) strings.text("Deselect all") else strings.selectAll) }
                    TextButton(onClick = ::exitSelection) { Text(strings.cancel) }
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = strings.refresh)
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == UpdatesTab.RECENT,
                onClick = { selectedTab = UpdatesTab.RECENT },
                label = { Text(strings.text("Recent")) },
                leadingIcon = { Icon(Icons.Outlined.Update, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = selectedTab == UpdatesTab.UPCOMING,
                onClick = {
                    selectedTab = UpdatesTab.UPCOMING
                    exitSelection()
                },
                label = { Text(strings.text("Upcoming")) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)) },
            )
        }

        when (selectedTab) {
            UpdatesTab.RECENT -> Box(Modifier.weight(1f)) {
                if (updates.isEmpty()) {
                    EmptyState(
                        title = strings.noUpdates,
                        message = strings.text("Refresh your library to look for newly published chapters."),
                        icon = { Icon(Icons.Outlined.Update, null, Modifier.size(30.dp)) },
                        action = { Button(onClick = onRefresh) { Text(strings.refresh) } },
                    )
                } else {
                    val grouped = updates
                        .groupBy { it.discoveredAt.dayBucket() }
                        .entries
                        .sortedByDescending { it.key }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        grouped.forEach { (day, itemsForDay) ->
                            stickyHeader(key = "day-$day") {
                                DayHeader(
                                    day = day,
                                    timestamp = itemsForDay.firstOrNull()?.discoveredAt ?: 0,
                                    strings = strings,
                                )
                            }
                            items(itemsForDay, key = { it.chapter.id }) { item ->
                                val selected = item.chapter.id in selectedIds
                                ActivityRow(
                                    title = item.manga.title,
                                    subtitle = item.chapter.name,
                                    detail = relativeTime(item.discoveredAt, strings),
                                    coverUrl = item.manga.thumbnailUrl,
                                    selectionMode = selectionMode,
                                    selected = selected,
                                    onClick = {
                                        if (selectionMode) toggleSelection(item.chapter.id)
                                        else onReadChapter(item.manga.id, item.chapter.id)
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedIds = selectedIds + item.chapter.id
                                    },
                                    primaryAction = {
                                        if (!selectionMode) {
                                            IconButton(onClick = { onReadChapter(item.manga.id, item.chapter.id) }) {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = strings.text("Read chapter"))
                                            }
                                        }
                                    },
                                    secondaryAction = {
                                        if (!selectionMode) {
                                            IconButton(onClick = { onDownloadChapter(item.manga.id, item.chapter.id) }) {
                                                Icon(Icons.Outlined.Download, contentDescription = strings.download)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            UpdatesTab.UPCOMING -> UpcomingPane(
                predictions = remember(allManga, allChapters) { predictUpcomingManga(allManga, allChapters) },
                onOpenManga = onOpenManga,
                modifier = Modifier.weight(1f),
            )
        }

        if (selectionMode && selectedTab == UpdatesTab.RECENT) {
            UpdateBatchToolbar(
                onMarkRead = {
                    onMarkChaptersRead(selectedIds, true)
                    exitSelection()
                },
                onMarkUnread = {
                    onMarkChaptersRead(selectedIds, false)
                    exitSelection()
                },
                onDownload = {
                    onDownloadChapters(selectedIds)
                    exitSelection()
                },
                onDelete = {
                    onDeleteChapterDownloads(selectedIds)
                    exitSelection()
                },
                onBookmark = {
                    onToggleChapterBookmarks(selectedIds)
                    exitSelection()
                },
                strings = strings,
            )
        }
    }
}

private enum class UpdatesTab {
    RECENT,
    UPCOMING,
}

@Composable
private fun UpdateBatchToolbar(
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onBookmark: () -> Unit,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 5.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UpdateBatchAction(Icons.Outlined.CheckCircle, strings.text("Read"), onMarkRead)
            UpdateBatchAction(Icons.Outlined.RadioButtonUnchecked, strings.text("Unread"), onMarkUnread)
            UpdateBatchAction(Icons.Outlined.Download, strings.download, onDownload)
            UpdateBatchAction(Icons.Outlined.Delete, strings.delete, onDelete)
            UpdateBatchAction(Icons.Outlined.Bookmark, strings.text("Bookmark"), onBookmark)
        }
    }
}

@Composable
private fun RowScope.UpdateBatchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun UpcomingPane(
    predictions: List<UpcomingPrediction>,
    onOpenManga: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val today = remember(timeZone) {
        Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            .toLocalDateTime(timeZone)
            .date
    }
    var displayedMonth by remember { mutableStateOf(firstDayOfMonth(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    val predictionsByDate = remember(predictions, timeZone) {
        predictions.groupBy {
            Instant.fromEpochMilliseconds(it.expectedAt).toLocalDateTime(timeZone).date
        }
    }
    val firstOffset = remember(displayedMonth) { (displayedMonth.dayOfWeek.ordinal + 1) % 7 }
    val calendarDays = remember(displayedMonth, firstOffset) {
        val firstEpochDay = displayedMonth.toEpochDays()
        List(42) { index -> LocalDate.fromEpochDays(firstEpochDay + index - firstOffset) }
    }
    val selectedPredictions = predictionsByDate[selectedDate].orEmpty()

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    displayedMonth = shiftMonth(displayedMonth, -1)
                    selectedDate = displayedMonth
                },
            ) { Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = strings.text("Previous month")) }
            Text(
                monthTitle(displayedMonth, strings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    displayedMonth = shiftMonth(displayedMonth, 1)
                    selectedDate = displayedMonth
                },
            ) { Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = strings.text("Next month")) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                )
            }
        }
        calendarDays.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                week.forEach { date ->
                    CalendarDayCell(
                        date = date,
                        currentMonth = date.year == displayedMonth.year && date.monthNumber == displayedMonth.monthNumber,
                        selected = date == selectedDate,
                        today = date == today,
                        hasUpdates = predictionsByDate[date].orEmpty().isNotEmpty(),
                        onClick = {
                            selectedDate = date
                            if (date.monthNumber != displayedMonth.monthNumber || date.year != displayedMonth.year) {
                                displayedMonth = firstDayOfMonth(date)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(
                "${selectedDate.monthNumber}/${selectedDate.dayOfMonth}/${selectedDate.year}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
        if (selectedPredictions.isEmpty()) {
            EmptyState(
                title = strings.text("No expected updates"),
                message = if (predictions.isEmpty()) {
                    strings.text("At least two chapter upload dates are needed to predict a schedule.")
                } else {
                    strings.text("No title is expected to update on this date.")
                },
                icon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(30.dp)) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(selectedPredictions, key = { it.manga.id }) { prediction ->
                    ActivityRow(
                        title = prediction.manga.title,
                        subtitle = prediction.averageIntervalDays?.let { strings.text("Usually every {0} days", it) }
                            ?: strings.text("Estimated from the last library update"),
                        detail = strings.text("Expected today"),
                        coverUrl = prediction.manga.thumbnailUrl,
                        onClick = { onOpenManga(prediction.manga.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    currentMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    hasUpdates: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val circleColor = when {
        selected -> MaterialTheme.colorScheme.primary
        today -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        !currentMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        today -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier.height(43.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(circleColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(date.dayOfMonth.toString(), color = textColor, style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.size(4.dp).background(
                if (hasUpdates) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape,
            ),
        )
    }
}

private fun firstDayOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.monthNumber, 1)

private fun shiftMonth(date: LocalDate, amount: Int): LocalDate {
    val zeroBased = date.year * 12 + date.monthNumber - 1 + amount
    return LocalDate(zeroBased / 12, zeroBased % 12 + 1, 1)
}

private fun monthTitle(
    date: LocalDate,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): String = "${strings.text(MONTH_NAMES[date.monthNumber - 1])} ${date.year}"

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

@Composable
fun HistoryScreen(
    history: List<HistoryItem>,
    onOpenManga: (Long) -> Unit,
    onResumeChapter: (Long, Long) -> Unit,
    onDeleteChapterHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = remember(history, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) history else history.filter {
            it.manga.title.lowercase().contains(normalized) || it.chapter.name.lowercase().contains(normalized)
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.history,
            subtitle = if (history.isEmpty()) strings.noHistory else "${history.size} chapters",
            actions = {
                if (history.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = strings.text("Clear history"))
                    }
                }
            },
        )
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (filtered.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) strings.noHistory else strings.noMatches,
                message = if (query.isBlank()) strings.text("Titles you read will appear here.") else strings.text("Try a different search."),
                icon = { Icon(Icons.Outlined.History, null, Modifier.size(30.dp)) },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.chapter.id }) { item ->
                    ActivityRow(
                        title = item.manga.title,
                        subtitle = item.chapter.name,
                        detail = relativeTime(item.lastRead, strings),
                        coverUrl = item.manga.thumbnailUrl,
                        onClick = { onOpenManga(item.manga.id) },
                        primaryAction = {
                            IconButton(onClick = { onResumeChapter(item.manga.id, item.chapter.id) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = strings.continueReading)
                            }
                        },
                        secondaryAction = {
                            IconButton(onClick = { onDeleteChapterHistory(item.chapter.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = strings.delete)
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { confirmClear = false },
            title = { Text(strings.text("Clear reading history?")) },
            text = { Text(strings.text("This removes all history entries but does not mark chapters unread.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    },
                ) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(strings.cancel) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityRow(
    title: String,
    subtitle: String,
    detail: String,
    coverUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    primaryAction: @Composable () -> Unit = {},
    secondaryAction: @Composable () -> Unit = {},
) {
    val strings = LocalShinsouStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick ?: onClick,
        ),
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) strings.text("Selected") else strings.text("Not selected"),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CoverImage(title, coverUrl, Modifier.width(44.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            secondaryAction()
            primaryAction()
        }
    }
}

@Composable
private fun DayHeader(
    day: Long,
    timestamp: Long,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
) {
    val today = Clock.System.now().toEpochMilliseconds().dayBucket()
    val label = when (today - day) {
        0L -> strings.text("Today")
        1L -> strings.text("Yesterday")
        else -> strings.text("{0} days ago", today - day)
    }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
        )
    }
}

private fun Long.dayBucket(): Long = if (this <= 0) 0 else this / MILLIS_PER_DAY

private fun relativeTime(
    timestamp: Long,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): String {
    if (timestamp <= 0) return strings.text("Unknown time")
    val delta = max(0, Clock.System.now().toEpochMilliseconds() - timestamp)
    return when {
        delta < 60_000 -> strings.text("Just now")
        delta < 3_600_000 -> strings.text("{0}m ago", delta / 60_000)
        delta < MILLIS_PER_DAY -> strings.text("{0}h ago", delta / 3_600_000)
        else -> strings.text("{0}d ago", delta / MILLIS_PER_DAY)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
