package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.backup.AutoBackupEntry
import dev.shinsou.kmp.backup.SnapshotRestoreTarget
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.LibraryItem
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiState
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import kotlin.math.roundToInt
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource

enum class MoreDestination {
    Downloads,
    Statistics,
    Settings,
    Backup,
    ContentBackupV2,
    ShuYueMigration,
    About,
}

@Composable
fun MoreScreen(
    downloadCount: Int,
    lastBackupLabel: String?,
    incognitoMode: Boolean,
    downloadOnlyMode: Boolean,
    onOpen: (MoreDestination) -> Unit,
    onImportLocal: (syncContentBodies: Boolean) -> Unit,
    onIncognitoModeChange: (Boolean) -> Unit,
    onDownloadOnlyModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var showLocalImportOptions by remember { mutableStateOf(false) }
    var syncImportedBodies by remember { mutableStateOf(false) }
    val rows = listOf(
        MoreRow(MoreDestination.Downloads, strings.downloads, strings.text("{0} queued or active", downloadCount), Icons.Outlined.Download),
        MoreRow(MoreDestination.Statistics, strings.statistics, strings.text("Library and reading insights"), Icons.Outlined.BarChart),
        MoreRow(MoreDestination.Settings, strings.settings, strings.text("Appearance, reader, sources and privacy"), Icons.Outlined.Settings),
        MoreRow(MoreDestination.Backup, strings.backup, lastBackupLabel ?: strings.text("No backup yet"), Icons.Outlined.Backup),
        MoreRow(MoreDestination.About, strings.about, strings.text("Version, licenses and project links"), Icons.Outlined.Info),
    )
    Column(modifier.fillMaxSize()) {
        ScreenHeader(strings.more)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val activeModes = activeMoreModes(incognitoMode, downloadOnlyMode)
            if (activeModes.isNotEmpty()) {
                item("active-modes") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeModes.forEach { mode ->
                            ActiveModeBanner(mode)
                        }
                    }
                }
            }
            item("incognito-mode") {
                ModeToggleRow(
                    title = strings.text("Incognito mode"),
                    subtitle = strings.text("Do not save reading history or sync trackers"),
                    icon = Icons.Outlined.VisibilityOff,
                    checked = incognitoMode,
                    onCheckedChange = onIncognitoModeChange,
                )
            }
            item("download-only-mode") {
                ModeToggleRow(
                    title = strings.text("Download-only mode"),
                    subtitle = strings.text("Show only titles with offline chapters"),
                    icon = Icons.Outlined.CloudDownload,
                    checked = downloadOnlyMode,
                    onCheckedChange = onDownloadOnlyModeChange,
                )
            }
            item("local-content-import") {
                Surface(
                    onClick = { showLocalImportOptions = true },
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(strings.text("Import local content"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                strings.text("Images, CBZ, ZIP, TXT or EPUB · stored on this device"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
                    }
                }
            }
            items(rows, key = { it.destination.name }) { row ->
                Surface(
                    onClick = { onOpen(row.destination) },
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(row.icon, null) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                row.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    }

    if (showLocalImportOptions) {
        AlertDialog(
            onDismissRequest = { showLocalImportOptions = false },
            title = { Text(strings.text("Import local content")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        strings.text(
                            "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync.",
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            strings.text("Encrypted TXT/EPUB body sync"),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = syncImportedBodies,
                            onCheckedChange = { syncImportedBodies = it },
                        )
                    }
                    Text(
                        strings.text(
                            "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = syncImportedBodies
                        showLocalImportOptions = false
                        syncImportedBodies = false
                        onImportLocal(selected)
                    },
                ) { Text(strings.text("Choose files")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLocalImportOptions = false
                        syncImportedBodies = false
                    },
                ) { Text(strings.cancel) }
            },
        )
    }
}

internal enum class MoreActiveMode {
    INCOGNITO,
    DOWNLOAD_ONLY,
}

/** Stable display order also keeps recomposition keys deterministic when both modes are active. */
internal fun activeMoreModes(
    incognitoMode: Boolean,
    downloadOnlyMode: Boolean,
): List<MoreActiveMode> = buildList {
    if (incognitoMode) add(MoreActiveMode.INCOGNITO)
    if (downloadOnlyMode) add(MoreActiveMode.DOWNLOAD_ONLY)
}

@Composable
private fun ActiveModeBanner(mode: MoreActiveMode) {
    val strings = LocalShinsouStrings.current
    val (icon, label, color) = when (mode) {
        MoreActiveMode.INCOGNITO -> Triple(
            Icons.Outlined.VisibilityOff,
            strings.text("Incognito mode is active"),
            MaterialTheme.colorScheme.tertiary,
        )
        MoreActiveMode.DOWNLOAD_ONLY -> Triple(
            Icons.Outlined.CloudDownload,
            strings.text("Download-only mode is active"),
            MaterialTheme.colorScheme.primary,
        )
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.11f),
        contentColor = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                null,
                Modifier.size(22.dp),
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

private data class MoreRow(
    val destination: MoreDestination,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
fun DownloadsScreen(
    queue: List<DownloadQueueItem>,
    mangaById: Map<Long, Manga>,
    chapterById: Map<Long, Chapter>,
    paused: Boolean,
    onBack: () -> Unit,
    onPauseChange: (Boolean) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.downloads,
            subtitle = strings.text("{0} active · {1} total", queue.count { it.state == DownloadState.DOWNLOADING }, queue.size),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
            actions = {
                IconButton(onClick = { onPauseChange(!paused) }) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) strings.text("Resume") else strings.text("Pause"))
                }
                if (queue.any { it.state == DownloadState.DOWNLOADED }) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.Delete, strings.text("Clear completed"))
                    }
                }
            },
        )
        if (queue.isEmpty()) {
            EmptyState(
                title = strings.text("Download queue is empty"),
                message = strings.text("Download chapters from a title to read them offline."),
                icon = { Icon(Icons.Outlined.CloudDownload, null, Modifier.size(30.dp)) },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(queue.sortedBy { it.position }, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        mangaTitle = mangaById[item.mangaId]?.title ?: strings.text("Unknown title"),
                        chapterName = chapterById[item.chapterId]?.name ?: strings.text("Unknown chapter"),
                        onRetry = { onRetry(item.id) },
                        onRemove = { onRemove(item.id) },
                        onMoveUp = { onMove(item.id, -1) },
                        onMoveDown = { onMove(item.id, 1) },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { confirmClear = false },
            title = { Text(strings.text("Clear completed downloads?")) },
            text = { Text(strings.text("Downloaded files remain available; only completed queue entries are removed.")) },
            confirmButton = {
                TextButton(onClick = { onClearCompleted(); confirmClear = false }) { Text(strings.remove) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadQueueItem,
    mangaTitle: String,
    chapterName: String,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadStateIcon(item.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(mangaTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        chapterName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, strings.text("Move up")) }
                IconButton(onClick = onMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, strings.text("Move down")) }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, strings.remove) }
            }
            Spacer(Modifier.height(7.dp))
            if (item.state == DownloadState.DOWNLOADING || item.progress > 0) {
                LinearProgressIndicator(
                    progress = { item.progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item.state) {
                        DownloadState.QUEUED -> strings.text("Queued")
                        DownloadState.DOWNLOADING -> strings.text("{0}% · {1}/{2} pages", (item.progress * 100).roundToInt(), item.downloadedPages, item.totalPages)
                        DownloadState.PAUSED -> strings.text("Paused")
                        DownloadState.DOWNLOADED -> strings.text("Downloaded")
                        DownloadState.ERROR -> item.errorMessage ?: strings.text("Download failed")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.state == DownloadState.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (item.state == DownloadState.ERROR) {
                    TextButton(onClick = onRetry) { Text(strings.retry) }
                }
            }
        }
    }
}

@Composable
private fun DownloadStateIcon(state: DownloadState) {
    val (icon, tint) = when (state) {
        DownloadState.QUEUED -> Icons.Outlined.Download to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadState.DOWNLOADING -> Icons.Outlined.Download to MaterialTheme.colorScheme.primary
        DownloadState.PAUSED -> Icons.Outlined.Pause to MaterialTheme.colorScheme.tertiary
        DownloadState.DOWNLOADED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        DownloadState.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.14f),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
fun StatisticsScreen(
    library: List<LibraryItem>,
    chapters: List<Chapter>,
    tracks: List<Track>,
    categories: List<Category>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val favoriteIds = library.mapTo(hashSetOf()) { it.id }
    val libraryChapters = chapters.filter { it.mangaId in favoriteIds }
    val read = libraryChapters.count { it.read }
    val progress = if (libraryChapters.isEmpty()) 0f else read.toFloat() / libraryChapters.size
    val uniqueLibrary = library.distinctBy { it.id }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.statistics,
            subtitle = strings.text("A snapshot of your collection"),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(strings.text("Titles"), uniqueLibrary.size.toString(), Icons.Outlined.Info, Modifier.weight(1f))
                    StatCard(strings.chapters, libraryChapters.size.toString(), Icons.Outlined.BarChart, Modifier.weight(1f))
                    StatCard(strings.text("Tracking"), tracks.size.toString(), Icons.Outlined.Refresh, Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(strings.text("Reading progress"), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            strings.text("{0} of {1} chapters read · {2}%", read, libraryChapters.size, (progress * 100).roundToInt()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(strings.text("Categories"), style = MaterialTheme.typography.titleLarge)
            }
            items(categories.sortedBy { it.sort }, key = { it.id }) { category ->
                val count = library.count { it.libraryManga.category == category.id }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(category.name.ifBlank { strings.text("Default") }, modifier = Modifier.weight(1f))
                    Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BackupScreen(
    state: BackupState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onAutomaticChange: (Boolean) -> Unit,
    automaticBackups: List<AutoBackupEntry> = emptyList(),
    onIntervalChange: (Int) -> Unit = {},
    onRetentionChange: (Int) -> Unit = {},
    onCreateAutomatic: () -> Unit = {},
    onRefreshAutomatic: () -> Unit = {},
    onRestoreAutomatic: (AutoBackupEntry) -> Unit = {},
    onDeleteAutomatic: (AutoBackupEntry) -> Unit = {},
    cloudflareSync: CloudflareSyncUiController? = null,
    onRestoreWithTarget: ((SnapshotRestoreTarget) -> Unit)? = null,
    onRestoreAutomaticWithTarget: ((AutoBackupEntry, SnapshotRestoreTarget) -> Unit)? = null,
    onResetWithTarget: ((SnapshotRestoreTarget) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val observedCloudflareState = cloudflareSync?.state?.collectAsState()
    val cloudflareState = observedCloudflareState?.value ?: CloudflareSyncUiState()
    val cloudflareConfigured = cloudflareState.configured
    var pendingRestore by remember { mutableStateOf<AutoBackupEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<AutoBackupEntry?>(null) }
    var pendingTargetOperation by remember { mutableStateOf<BackupReplacementOperation?>(null) }
    var confirmLocalReset by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.backup,
            subtitle = state.lastFileName ?: strings.text("Portable Shinsou X JSON snapshot"),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (state.status) {
                                    BackupStatus.FAILED -> Icons.Outlined.ErrorOutline
                                    BackupStatus.RESTORING -> Icons.Outlined.Restore
                                    else -> Icons.Outlined.Backup
                                },
                                null,
                                tint = if (state.status == BackupStatus.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strings.text("Backup status"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    strings.text(state.status.name.lowercase().replaceFirstChar { it.uppercase() }),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.status == BackupStatus.CREATING || state.status == BackupStatus.RESTORING) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                        }
                        state.errorMessage?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Backup, null)
                        Spacer(Modifier.width(7.dp))
                        Text(strings.createBackup)
                    }
                    OutlinedButton(
                        onClick = {
                            if (cloudflareConfigured) {
                                pendingTargetOperation = BackupReplacementOperation.PortableRestore
                            } else {
                                onRestore()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Restore, null)
                        Spacer(Modifier.width(7.dp))
                        Text(strings.text("Restore / import"))
                    }
                }
            }
            if (cloudflareConfigured) {
                item {
                    SyncedBackupSafetyCard(cloudflareState)
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(strings.text("Automatic backups"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    strings.text("Recoverable snapshots in private app storage"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(state.automaticEnabled, onAutomaticChange)
                        }
                        HorizontalDivider()
                        BackupSettingStepper(
                            title = strings.text("Backup interval"),
                            value = strings.text("Every {0} hours", state.intervalHours),
                            strings = strings,
                            decreaseEnabled = previousInterval(state.intervalHours) != state.intervalHours,
                            increaseEnabled = nextInterval(state.intervalHours) != state.intervalHours,
                            onDecrease = { onIntervalChange(previousInterval(state.intervalHours)) },
                            onIncrease = { onIntervalChange(nextInterval(state.intervalHours)) },
                        )
                        BackupSettingStepper(
                            title = strings.text("Stored backups"),
                            value = strings.text("Keep {0}", state.retainedBackupCount.coerceAtLeast(1)),
                            strings = strings,
                            decreaseEnabled = state.retainedBackupCount > 1,
                            increaseEnabled = state.retainedBackupCount < MAX_RETAINED_BACKUPS,
                            onDecrease = { onRetentionChange((state.retainedBackupCount - 1).coerceAtLeast(1)) },
                            onIncrease = { onRetentionChange((state.retainedBackupCount + 1).coerceAtMost(MAX_RETAINED_BACKUPS)) },
                        )
                        OutlinedButton(
                            onClick = onCreateAutomatic,
                            enabled = state.status != BackupStatus.CREATING && state.status != BackupStatus.RESTORING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Backup, null)
                            Spacer(Modifier.width(7.dp))
                            Text(strings.text("Back up now"))
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.text("Saved automatic backups"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text("{0} stored on this device", automaticBackups.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshAutomatic) {
                        Icon(Icons.Outlined.Refresh, strings.text("Refresh automatic backups"))
                    }
                }
            }
            if (automaticBackups.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            strings.text("No automatic backups yet. The first one is created when the platform scheduler runs, or you can create one now."),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(automaticBackups, key = { it.fileName }) { backup ->
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (backup.recoverable) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                null,
                                tint = if (backup.recoverable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(formatAutomaticBackupTime(backup.createdAt), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (backup.recoverable) {
                                        "${formatByteCount(backup.sizeBytes)}${backup.appVersion.takeIf(String::isNotBlank)?.let { " · v$it" }.orEmpty()}"
                                    } else {
                                        backup.errorMessage ?: strings.text("Damaged backup")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (backup.recoverable) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (cloudflareConfigured) {
                                        pendingTargetOperation = BackupReplacementOperation.AutomaticRestore(backup)
                                    } else {
                                        pendingRestore = backup
                                    }
                                },
                                enabled = backup.recoverable,
                            ) {
                                Icon(Icons.Outlined.Restore, strings.text("Restore {0}", backup.fileName))
                            }
                            IconButton(onClick = { pendingDelete = backup }) {
                                Icon(Icons.Outlined.Delete, strings.text("Delete {0}", backup.fileName))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    strings.text("Backups include your library, chapters, reading history, categories, tracking records, downloads queue, settings, and extension repositories. Android and iOS decide the exact background execution time; missed work is retried by the platform."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.text("Reset application data"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (cloudflareConfigured) {
                                strings.text("Choose whether to create synchronized deletions for the workspace or leave it before resetting only this device.")
                            } else {
                                strings.text("Removes the library, reading history, categories, settings and queued records from this device.")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(
                            onClick = {
                                if (cloudflareConfigured) {
                                    pendingTargetOperation = BackupReplacementOperation.Reset
                                } else {
                                    confirmLocalReset = true
                                }
                            },
                            enabled = onResetWithTarget != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Delete, null)
                            Spacer(Modifier.width(7.dp))
                            Text(strings.text("Reset data"))
                        }
                        if (onResetWithTarget == null) {
                            Text(
                                strings.text("Safe reset is unavailable until the synchronization runtime is connected."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
    pendingRestore?.let { backup ->
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { pendingRestore = null },
            title = { Text(strings.text("Restore automatic backup?")) },
            text = { Text(strings.text("Current library data will be replaced with the snapshot from {0}.", formatAutomaticBackupTime(backup.createdAt))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        onRestoreAutomatic(backup)
                    },
                ) { Text(strings.restoreBackup) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(strings.cancel) } },
        )
    }
    pendingDelete?.let { backup ->
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { pendingDelete = null },
            title = { Text(strings.text("Delete automatic backup?")) },
            text = { Text(strings.text("{0} will be permanently removed from this device.", backup.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteAutomatic(backup)
                    },
                ) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(strings.cancel) } },
        )
    }
    pendingTargetOperation?.let { operation ->
        val handlerAvailable = when (operation) {
            BackupReplacementOperation.PortableRestore -> onRestoreWithTarget != null
            is BackupReplacementOperation.AutomaticRestore -> onRestoreAutomaticWithTarget != null
            BackupReplacementOperation.Reset -> onResetWithTarget != null
        }
        val safety = backupReplacementSafety(cloudflareState.status, handlerAvailable)
        SyncedReplacementTargetDialog(
            operation = operation,
            safety = safety,
            onDismiss = { pendingTargetOperation = null },
            onSelect = { target ->
                pendingTargetOperation = null
                when (operation) {
                    BackupReplacementOperation.PortableRestore -> onRestoreWithTarget?.invoke(target)
                    is BackupReplacementOperation.AutomaticRestore -> {
                        onRestoreAutomaticWithTarget?.invoke(operation.backup, target)
                    }
                    BackupReplacementOperation.Reset -> onResetWithTarget?.invoke(target)
                }
            },
        )
    }
    if (confirmLocalReset) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { confirmLocalReset = false },
            title = { Text(strings.text("Reset this device?")) },
            text = { Text(strings.text("This permanently removes the current library data and settings from this device.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLocalReset = false
                        onResetWithTarget?.invoke(SnapshotRestoreTarget.THIS_DEVICE_ONLY)
                    },
                ) { Text(strings.text("Reset data"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLocalReset = false }) { Text(strings.cancel) } },
        )
    }
}

private sealed interface BackupReplacementOperation {
    data object PortableRestore : BackupReplacementOperation
    data class AutomaticRestore(val backup: AutoBackupEntry) : BackupReplacementOperation
    data object Reset : BackupReplacementOperation
}

internal data class BackupReplacementSafety(
    val directReplacementAllowed: Boolean,
    val allDevicesEnabled: Boolean,
    val thisDeviceEnabled: Boolean,
)

/** Pure policy used by the UI so a missing callback or non-ready workspace always fails closed. */
internal fun backupReplacementSafety(
    status: SyncSessionStatus,
    safeHandlerAvailable: Boolean,
): BackupReplacementSafety {
    val configured = status != SyncSessionStatus.NOT_CONFIGURED
    return BackupReplacementSafety(
        directReplacementAllowed = !configured,
        allDevicesEnabled = configured && status == SyncSessionStatus.READY && safeHandlerAvailable,
        thisDeviceEnabled = configured && safeHandlerAvailable,
    )
}

@Composable
private fun SyncedBackupSafetyCard(state: CloudflareSyncUiState) {
    val strings = LocalShinsouStrings.current
    val pending = state.pendingDrafts + state.pendingUploads
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.text("Cloudflare workspace protection"), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text("Restore, import and reset never overwrite the synchronized snapshot directly. You must choose a scope first."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (pending > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    strings.text("{0} durable change batches are still queued or uploading. Other devices are not yet guaranteed to be updated.", pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    strings.text("A completed restore means the local transaction is safe; remote completion is confirmed by sync status."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SyncedReplacementTargetDialog(
    operation: BackupReplacementOperation,
    safety: BackupReplacementSafety,
    onDismiss: () -> Unit,
    onSelect: (SnapshotRestoreTarget) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val isReset = operation == BackupReplacementOperation.Reset
    val title = when (operation) {
        BackupReplacementOperation.PortableRestore -> strings.text("Where should this backup be restored?")
        is BackupReplacementOperation.AutomaticRestore -> strings.text("Where should the backup from {0} be restored?", formatAutomaticBackupTime(operation.backup.createdAt))
        BackupReplacementOperation.Reset -> strings.text("Where should data be reset?")
    }
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    strings.text("Choose explicitly. These actions have different synchronization and membership effects."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { onSelect(SnapshotRestoreTarget.ALL_SYNCED_DEVICES) },
                    enabled = safety.allDevicesEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isReset) strings.text("Reset on all synced devices")
                        else strings.text("Restore and sync to all devices"),
                    )
                }
                Text(
                    if (isReset) {
                        strings.text("Creates durable tombstones and uploads them in batches. Other devices change only after synchronization completes.")
                    } else {
                        strings.text("Converts additions, updates and deletions into durable mutations, then uploads them in batches.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    strings.text("Downloads, Local source files, extension packages and credentials remain device-local."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!safety.allDevicesEnabled) {
                    Text(
                        strings.text("Syncing to all devices requires a Ready workspace and an available sync-safe handler."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    onClick = { onSelect(SnapshotRestoreTarget.THIS_DEVICE_ONLY) },
                    enabled = safety.thisDeviceEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isReset) strings.text("Leave workspace and reset this device")
                        else strings.text("Leave workspace and restore this device"),
                    )
                }
                Text(
                    strings.text("This device leaves the workspace first and stops receiving its updates; only then is local data replaced."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!safety.thisDeviceEnabled) {
                    Text(
                        strings.text("The sync-safe restore handler is unavailable, so direct replacement remains blocked."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun BackupSettingStepper(
    title: String,
    value: String,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDecrease, enabled = decreaseEnabled) {
            Icon(Icons.Outlined.KeyboardArrowDown, strings.text("Decrease {0}", title))
        }
        IconButton(onClick = onIncrease, enabled = increaseEnabled) {
            Icon(Icons.Outlined.KeyboardArrowUp, strings.text("Increase {0}", title))
        }
    }
}

private fun previousInterval(current: Int): Int = BACKUP_INTERVAL_OPTIONS.lastOrNull { it < current }
    ?: BACKUP_INTERVAL_OPTIONS.first()

private fun nextInterval(current: Int): Int = BACKUP_INTERVAL_OPTIONS.firstOrNull { it > current }
    ?: BACKUP_INTERVAL_OPTIONS.last()

private fun formatAutomaticBackupTime(epochMillis: Long): String = runCatching {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    "${value.date} ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}.getOrElse { epochMillis.toString() }

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private val BACKUP_INTERVAL_OPTIONS = listOf(1, 6, 12, 24, 48, 72, 168)
private const val MAX_RETAINED_BACKUPS = 20

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var privacyVisible by remember { mutableStateOf(false) }
    if (privacyVisible) {
        PrivacyPolicyScreen(
            onBack = { privacyVisible = false },
            modifier = modifier,
        )
        return
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.about,
            subtitle = strings.text("Shinsou X · version 1.0.0"),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(112.dp),
                ) {
                    Image(
                        painter = painterResource(Res.drawable.shinsou_icon),
                        contentDescription = "Shinsou X",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp)),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("Shinsou X", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    strings.text("A private, extensible manga library and reader for Android, iOS, and desktop."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                AboutLink(strings.text("GitHub · source code")) {
                    onOpenUrl("https://github.com/aluo96078/shinsoux")
                }
                AboutLink(strings.text("Contact developer")) {
                    onOpenUrl("mailto:aluo96078@gmail.com?subject=Shinsou%20Feedback")
                }
                AboutLink(strings.text("Open-source license")) {
                    onOpenUrl("https://github.com/aluo96078/shinsoux/blob/master/LICENSE")
                }
                AboutLink(strings.text("Privacy policy")) { privacyVisible = true }
            }
        }
    }
}

@Composable
private fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.text("Privacy policy"),
            subtitle = strings.text("Last updated August 19, 2026"),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text(
                    strings.text("Shinsou X is a local-first manga library and reader. It does not operate an analytics or advertising service."),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                PrivacySection(
                    strings.text("Data stored on your device"),
                    strings.text("Library records, reading history, settings, downloaded pages, installed extensions and automatic backups are stored in app-private storage. Portable backups intentionally exclude passwords, cookies and OAuth tokens."),
                )
            }
            item {
                PrivacySection(
                    strings.text("Network and extensions"),
                    strings.text("When you browse or read, Shinsou X contacts the source and extension repository you selected. Those third parties receive normal request information such as your IP address and user agent and are governed by their own policies."),
                )
            }
            item {
                PrivacySection(
                    strings.text("Cloud synchronization"),
                    strings.text("iCloud Drive synchronization is opt-in and available only on iOS. If enabled, a Shinsou X backup snapshot is stored in your private iCloud container and handled under your Apple account settings."),
                )
            }
            item {
                PrivacySection(
                    strings.text("Permissions and security"),
                    strings.text("File access is used only for documents you choose to import or export. Notification, biometric lock and secure-screen features are optional. Credentials and tracker tokens use platform-protected storage."),
                )
            }
            item {
                PrivacySection(
                    strings.text("Contact"),
                    strings.text("Questions about this policy can be sent to aluo96078@gmail.com."),
                )
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutLink(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.OpenInNew, null)
        }
    }
}
