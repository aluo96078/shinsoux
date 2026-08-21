package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.backup.BackupV2OmissionReason
import dev.shinsou.kmp.backup.SnapshotRestoreTarget
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationIssueSeverity
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2ExportArtifact
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2Summary
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiPhase
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiPhase
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Full redacted review and explicit-consent surface for one prepared ShuYue v1 import. */
@Composable
public fun ShuYueMigrationScreen(
    controller: ShuYueMigrationUiController,
    onChooseBackup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmSecretImport by remember { mutableStateOf(false) }
    val preview = state.preview
    val selectionsEnabled = state.phase == ShuYueMigrationUiPhase.REVIEW

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.text("Import from ShuYue"),
            subtitle = strings.text("Review first; scripts stay quarantined and secrets stay excluded"),
            leading = {
                IconButton(onClick = onBack, enabled = !state.busy) {
                    Icon(Icons.Outlined.ArrowBack, strings.text("Back"))
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("source") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strings.text("ShuYue backup v1"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (state.phase) {
                                        ShuYueMigrationUiPhase.IDLE -> strings.text("No backup selected")
                                        ShuYueMigrationUiPhase.INSPECTING -> strings.text("Inspecting a bounded copy…")
                                        ShuYueMigrationUiPhase.REJECTED -> strings.text("The backup was rejected")
                                        else -> strings.text("Validated staging preview ready")
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        }
                        OutlinedButton(
                            onClick = onChooseBackup,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (preview == null) strings.text("Choose backup")
                                else strings.text("Choose another backup"),
                            )
                        }
                    }
                }
            }

            state.failure?.let { failure ->
                item("failure") {
                    PortabilityFailureCard(failure.message)
                }
            }

            preview?.let { reviewed ->
                item("summary") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(strings.text("Validated contents"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                strings.text(
                                    "{0} books · {1} chapters · {2} reading positions",
                                    reviewed.counts.books,
                                    reviewed.counts.chapters,
                                    reviewed.counts.progress,
                                ),
                            )
                            Text(
                                strings.text(
                                    "{0} categories · {1} characters of chapter text",
                                    reviewed.counts.categories,
                                    reviewed.counts.totalChapterChars,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (reviewed.issues.isNotEmpty()) {
                    item("issues-heading") {
                        Text(strings.text("Validation report"), style = MaterialTheme.typography.titleMedium)
                    }
                    items(reviewed.issues, key = { issue ->
                        "${issue.severity}:${issue.code}:${issue.entityRef?.kind}:${issue.entityRef?.index}"
                    }) { issue ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp),
                            color = if (issue.severity == ShuYueMigrationIssueSeverity.ERROR) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    strings.text("Validation issue ({0})", issue.code),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    localizedPortabilityMessage(strings, issue.message, issue.code),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (reviewed.bookSummaries.isNotEmpty()) {
                    item("books-heading") {
                        SelectionHeading(
                            title = strings.text("Books"),
                            subtitle = if (reviewed.bookSummariesTruncated) {
                                strings.text("Preview truncated; only all-or-none selection is available")
                            } else {
                                strings.text("Chapter bodies are not shown in this report")
                            },
                            checked = state.selectedBookIds == null ||
                                state.selectedBookIds?.size == reviewed.bookSummaries.size,
                            enabled = selectionsEnabled,
                            onCheckedChange = controller::selectAllBooks,
                        )
                    }
                    items(reviewed.bookSummaries, key = { it.id }) { book ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.selectedBookIds == null || book.id in state.selectedBookIds.orEmpty(),
                                onCheckedChange = { controller.setBookSelected(book.id, it) },
                                enabled = selectionsEnabled && !reviewed.bookSummariesTruncated,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    strings.text(
                                        "{0} chapters · {1}",
                                        book.chapterCount,
                                        book.author ?: book.category,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (reviewed.quarantinedPlugins.isNotEmpty()) {
                    item("plugins-heading") {
                        SelectionHeading(
                            title = strings.text("Quarantined extension scripts"),
                            subtitle = strings.text("Selected scripts are stored for later review and are never executed by import"),
                            checked = state.selectedPluginDigests == null ||
                                state.selectedPluginDigests?.size == reviewed.quarantinedPlugins.size,
                            enabled = selectionsEnabled,
                            onCheckedChange = controller::selectAllQuarantinedPlugins,
                        )
                    }
                    items(reviewed.quarantinedPlugins, key = { "${it.sha256}:${it.origin}:${it.ordinal}" }) { plugin ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.selectedPluginDigests == null ||
                                    plugin.sha256 in state.selectedPluginDigests.orEmpty(),
                                onCheckedChange = {
                                    controller.setQuarantinedPluginSelected(plugin.sha256, it)
                                },
                                enabled = selectionsEnabled && !reviewed.quarantinedPluginsTruncated,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(plugin.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    strings.text(
                                        "Version {0} · {1} bytes · digest {2}…",
                                        plugin.version,
                                        plugin.scriptByteCount,
                                        plugin.sha256.take(8),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item("portable-options") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(strings.text("Portable metadata"), style = MaterialTheme.typography.titleMedium)
                            LabeledSwitch(
                                label = strings.text("Reading positions ({0})", reviewed.counts.progress),
                                checked = state.includeProgress,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeProgress,
                            )
                            LabeledSwitch(
                                label = strings.text("Reader settings"),
                                checked = state.includeReaderSettings,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeReaderSettings,
                            )
                            LabeledSwitch(
                                label = strings.text("Encrypted chapter body sync"),
                                checked = state.includeContentBodySync,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeContentBodySync,
                            )
                            Text(
                                strings.text(
                                    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (reviewed.secrets.credentialCount > 0 || reviewed.secrets.cookieCount > 0) {
                    item("secrets") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Security, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.text("Optional secrets"), style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    strings.text("Values are never shown, backed up, synchronized, or imported automatically."),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                LabeledSwitch(
                                    label = strings.text("Credentials ({0})", reviewed.secrets.credentialCount),
                                    checked = state.includeCredentials,
                                    enabled = selectionsEnabled && state.secretImportAvailable &&
                                        reviewed.secrets.credentialCount > 0,
                                    onCheckedChange = controller::setIncludeCredentials,
                                )
                                LabeledSwitch(
                                    label = strings.text("Cookies ({0})", reviewed.secrets.cookieCount),
                                    checked = state.includeCookies,
                                    enabled = selectionsEnabled && state.secretImportAvailable &&
                                        reviewed.secrets.cookieCount > 0,
                                    onCheckedChange = controller::setIncludeCookies,
                                )
                                if (!state.secretImportAvailable) {
                                    Text(
                                        strings.text("Protected platform storage is unavailable, so secret import is blocked."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                item("commit") {
                    when (state.phase) {
                        ShuYueMigrationUiPhase.REVIEW,
                        ShuYueMigrationUiPhase.IMPORTING_CONTENT,
                        -> Button(
                            onClick = { scope.launch { controller.importContent() } },
                            enabled = state.phase == ShuYueMigrationUiPhase.REVIEW,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.phase == ShuYueMigrationUiPhase.IMPORTING_CONTENT) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(strings.text("Import selected content"))
                        }

                        ShuYueMigrationUiPhase.CONTENT_IMPORTED,
                        ShuYueMigrationUiPhase.IMPORTING_SECRETS,
                        -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ImportCompletedCard(state.contentImport?.replayed == true)
                            if ((state.includeCredentials || state.includeCookies) && state.secretImportAvailable) {
                                Button(
                                    onClick = { confirmSecretImport = true },
                                    enabled = state.phase == ShuYueMigrationUiPhase.CONTENT_IMPORTED,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (state.phase == ShuYueMigrationUiPhase.IMPORTING_SECRETS) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(strings.text("Review and import selected secrets"))
                                }
                            } else {
                                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                    Text(strings.text("Done"))
                                }
                            }
                        }

                        ShuYueMigrationUiPhase.COMPLETE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ImportCompletedCard(state.contentImport?.replayed == true)
                            Text(
                                strings.text(
                                    "Imported {0} credentials and {1} cookies into protected storage.",
                                    state.secretImport?.credentialCount ?: 0,
                                    state.secretImport?.cookieCount ?: 0,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text(strings.text("Done"))
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    if (confirmSecretImport) {
        AlertDialog(
            onDismissRequest = { confirmSecretImport = false },
            title = { Text(strings.text("Import protected secrets?")) },
            text = {
                Text(
                    strings.text(
                        "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSecretImport = false
                        scope.launch {
                            controller.importSelectedSecrets(Clock.System.now().toEpochMilliseconds())
                        }
                    },
                ) { Text(strings.text("Import secrets")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSecretImport = false }) { Text(strings.cancel) }
            },
        )
    }
}

/** Checksummed binary archive UI. File pickers/savers remain platform callbacks. */
@Composable
public fun ContentBackupV2Screen(
    controller: PortableContentBackupV2UiController,
    syncStatus: SyncSessionStatus,
    onChooseRestoreArchive: () -> Unit,
    onExportReady: (PortableContentBackupV2ExportArtifact) -> Unit,
    onOpenShuYueMigration: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var includeContentBlobs by remember { mutableStateOf(true) }
    var confirmLocalRestore by remember { mutableStateOf(false) }
    var chooseSyncedTarget by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.text("Content backup v2"),
            subtitle = strings.text("Versioned manifest, checksums, and rights-filtered bodies"),
            leading = {
                IconButton(onClick = onBack, enabled = !state.busy) {
                    Icon(Icons.Outlined.ArrowBack, strings.text("Back"))
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("export") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strings.text("Create portable archive"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    strings.text("Credentials, cookies, OAuth tokens, and device keys are always excluded."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        LabeledSwitch(
                            label = strings.text("Include exportable content bodies"),
                            checked = includeContentBlobs,
                            enabled = !state.busy && state.exportAvailable,
                            onCheckedChange = { includeContentBlobs = it },
                        )
                        Text(
                            strings.text("Each body is included only when its rights grant permits export; omissions are recorded in the manifest."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    controller.createExport(includeContentBlobs)?.let(onExportReady)
                                }
                            },
                            enabled = !state.busy && state.exportAvailable,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.phase == PortableContentBackupV2UiPhase.EXPORTING) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(strings.text("Create binary archive"))
                        }
                        if (!state.exportAvailable) {
                            Text(
                                strings.text("Binary export is unavailable until the content-backup service is connected."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item("restore") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.text("Restore content archive"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text("The complete container, declared paths, checksums, and portable state are validated before restore is enabled."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onChooseRestoreArchive,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null)
                            Spacer(Modifier.width(7.dp))
                            Text(strings.text("Choose .shinsou2 archive"))
                        }
                    }
                }
            }

            state.failure?.let { failure ->
                item("failure") { PortabilityFailureCard(failure.message) }
            }

            state.lastExport?.let { summary ->
                item("last-export") {
                    ContentBackupSummaryCard(strings.text("Archive ready"), summary)
                }
            }

            state.restoreReview?.let { summary ->
                item("restore-review") {
                    ContentBackupSummaryCard(strings.text("Verified restore preview"), summary)
                }
                item("restore-action") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (syncStatus == SyncSessionStatus.NOT_CONFIGURED) {
                                    confirmLocalRestore = true
                                } else {
                                    chooseSyncedTarget = true
                                }
                            },
                            enabled = state.restoreAvailable && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Restore, null)
                            Spacer(Modifier.width(7.dp))
                            Text(strings.text("Restore verified archive"))
                        }
                        OutlinedButton(
                            onClick = controller::discardRestoreReview,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.cancel)
                        }
                        if (!state.restoreAvailable) {
                            Text(
                                strings.text("Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            state.restoreResult?.let { result ->
                item("restore-complete") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                strings.text(
                                    "Restored {0} publications, {1} annotations, and {2} content bodies.",
                                    result.publicationCount,
                                    result.annotationCount,
                                    result.contentBlobCount,
                                ),
                            )
                        }
                    }
                }
            }

            item("shuyue") {
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(strings.text("Moving from ShuYue?"), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text("Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenShuYueMigration,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.text("Import from ShuYue"))
                }
            }
        }
    }

    if (confirmLocalRestore) {
        AlertDialog(
            onDismissRequest = { confirmLocalRestore = false },
            title = { Text(strings.text("Restore this archive?")) },
            text = { Text(strings.text("Verified portable state and bodies will be committed on this device.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLocalRestore = false
                        scope.launch { controller.restore(SnapshotRestoreTarget.THIS_DEVICE_ONLY) }
                    },
                ) { Text(strings.restoreBackup) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLocalRestore = false }) { Text(strings.cancel) }
            },
        )
    }

    if (chooseSyncedTarget) {
        ContentBackupV2TargetDialog(
            allDevicesEnabled = syncStatus == SyncSessionStatus.READY && state.restoreAvailable,
            thisDeviceEnabled = state.restoreAvailable,
            onDismiss = { chooseSyncedTarget = false },
            onSelect = { target ->
                chooseSyncedTarget = false
                scope.launch { controller.restore(target) }
            },
        )
    }
}

@Composable
private fun SelectionHeading(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PortabilityFailureCard(message: String) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(
                localizedPortabilityMessage(strings, message),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ImportCompletedCard(replayed: Boolean) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (replayed) strings.text("This exact import was already committed; nothing was duplicated.")
                else strings.text("Selected content and quarantined scripts were committed transactionally."),
            )
        }
    }
}

@Composable
private fun ContentBackupSummaryCard(title: String, summary: PortableContentBackupV2Summary) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(formatBackupV2Time(summary.createdAtEpochMillis))
            Text(
                strings.text(
                    "{0} publications · {1} annotations · {2} content bodies",
                    summary.publicationCount,
                    summary.annotationCount,
                    summary.contentBlobCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                strings.text(
                    "{0} · {1} attached manifests · {2} omitted",
                    formatBackupV2Bytes(summary.archiveBytes),
                    summary.attachmentCount,
                    summary.omittedAttachmentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.omittedByReason.isNotEmpty()) {
                Text(
                    summary.omittedByReason.entries
                        .sortedBy { it.key.name }
                        .joinToString(" · ") { (reason, count) ->
                            "${backupV2OmissionLabel(strings, reason)}: $count"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContentBackupV2TargetDialog(
    allDevicesEnabled: Boolean,
    thisDeviceEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SnapshotRestoreTarget) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text("Where should this archive be restored?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSelect(SnapshotRestoreTarget.ALL_SYNCED_DEVICES) },
                    enabled = allDevicesEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.text("Restore and sync to all devices")) }
                Text(
                    strings.text("This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onSelect(SnapshotRestoreTarget.THIS_DEVICE_ONLY) },
                    enabled = thisDeviceEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.text("Leave workspace and restore this device")) }
                Text(
                    strings.text("The device must leave the workspace before its local state is replaced."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private fun backupV2OmissionLabel(strings: ShinsouStrings, reason: BackupV2OmissionReason): String = when (reason) {
    BackupV2OmissionReason.NOT_REQUESTED -> strings.text("Not requested")
    BackupV2OmissionReason.RIGHTS_DENIED -> strings.text("Rights denied")
    BackupV2OmissionReason.BLOB_MISSING -> strings.text("Missing")
    BackupV2OmissionReason.BLOB_CORRUPT -> strings.text("Corrupt")
    BackupV2OmissionReason.ARCHIVE_LIMIT -> strings.text("Archive limit")
}

private fun localizedPortabilityMessage(
    strings: ShinsouStrings,
    message: String,
    diagnosticCode: String? = null,
): String {
    val translated = strings.text(message)
    return if (translated != message) translated else strings.text(
        "The backup contains invalid or unsupported data. Review code {0}.",
        diagnosticCode ?: "unknown",
    )
}

private fun formatBackupV2Time(epochMillis: Long): String = runCatching {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    "${value.date} ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}.getOrElse { epochMillis.toString() }

private fun formatBackupV2Bytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes >= 1_024 -> "${bytes / 1_024} KB"
    else -> "$bytes B"
}
