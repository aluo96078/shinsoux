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
import dev.shinsou.kmp.backup.shuyue.ShuYueMigrationIssueSeverity
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.authoredMessage
import dev.shinsou.kmp.ui.i18n.StringKey
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
            title = strings.text(StringKey.IMPORT_FROM_SHUYUE),
            subtitle = strings.text(StringKey.REVIEW_FIRST_SCRIPTS_STAY_QUARANTINED_AND_SECRETS),
            leading = {
                IconButton(onClick = onBack, enabled = !state.busy) {
                    Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK))
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
                                Text(strings.text(StringKey.SHUYUE_BACKUP_V1), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (state.phase) {
                                        ShuYueMigrationUiPhase.IDLE -> strings.text(StringKey.NO_BACKUP_SELECTED)
                                        ShuYueMigrationUiPhase.INSPECTING -> strings.text(StringKey.INSPECTING_A_BOUNDED_COPY)
                                        ShuYueMigrationUiPhase.REJECTED -> strings.text(StringKey.THE_BACKUP_WAS_REJECTED)
                                        else -> strings.text(StringKey.VALIDATED_STAGING_PREVIEW_READY)
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
                                if (preview == null) strings.text(StringKey.CHOOSE_BACKUP)
                                else strings.text(StringKey.CHOOSE_ANOTHER_BACKUP),
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
                            Text(strings.text(StringKey.VALIDATED_CONTENTS), style = MaterialTheme.typography.titleMedium)
                            Text(
                                strings.text(
                                    StringKey.X_BOOKS_X_CHAPTERS_X_READING_POSITIONS,
                                    reviewed.counts.books,
                                    reviewed.counts.chapters,
                                    reviewed.counts.progress,
                                ),
                            )
                            Text(
                                strings.text(
                                    StringKey.X_CATEGORIES_X_CHARACTERS_OF_CHAPTER_TEXT,
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
                        Text(strings.text(StringKey.VALIDATION_REPORT), style = MaterialTheme.typography.titleMedium)
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
                                    strings.text(StringKey.VALIDATION_ISSUE_X, issue.code),
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
                            title = strings.text(StringKey.BOOKS),
                            subtitle = if (reviewed.bookSummariesTruncated) {
                                strings.text(StringKey.PREVIEW_TRUNCATED_ONLY_ALL_OR_NONE_SELECTION)
                            } else {
                                strings.text(StringKey.CHAPTER_BODIES_ARE_NOT_SHOWN_IN_THIS)
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
                                        StringKey.X_CHAPTERS_X,
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
                            title = strings.text(StringKey.QUARANTINED_EXTENSION_SCRIPTS),
                            subtitle = strings.text(StringKey.SELECTED_SCRIPTS_ARE_STORED_FOR_LATER_REVIEW),
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
                                        StringKey.VERSION_X_X_BYTES_DIGEST_X,
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
                            Text(strings.text(StringKey.PORTABLE_METADATA), style = MaterialTheme.typography.titleMedium)
                            LabeledSwitch(
                                label = strings.text(StringKey.READING_POSITIONS_X, reviewed.counts.progress),
                                checked = state.includeProgress,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeProgress,
                            )
                            LabeledSwitch(
                                label = strings.text(StringKey.READER_SETTINGS),
                                checked = state.includeReaderSettings,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeReaderSettings,
                            )
                            LabeledSwitch(
                                label = strings.text(StringKey.ENCRYPTED_CHAPTER_BODY_SYNC),
                                checked = state.includeContentBodySync,
                                enabled = selectionsEnabled,
                                onCheckedChange = controller::setIncludeContentBodySync,
                            )
                            Text(
                                strings.text(
                                    StringKey.OFF_BY_DEFAULT_WHEN_ENABLED_ONLY_BODIES,
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
                                    Text(strings.text(StringKey.OPTIONAL_SECRETS), style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    strings.text(StringKey.VALUES_ARE_NEVER_SHOWN_BACKED_UP_SYNCHRONIZED),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                LabeledSwitch(
                                    label = strings.text(StringKey.CREDENTIALS_X, reviewed.secrets.credentialCount),
                                    checked = state.includeCredentials,
                                    enabled = selectionsEnabled && state.secretImportAvailable &&
                                        reviewed.secrets.credentialCount > 0,
                                    onCheckedChange = controller::setIncludeCredentials,
                                )
                                LabeledSwitch(
                                    label = strings.text(StringKey.COOKIES_X, reviewed.secrets.cookieCount),
                                    checked = state.includeCookies,
                                    enabled = selectionsEnabled && state.secretImportAvailable &&
                                        reviewed.secrets.cookieCount > 0,
                                    onCheckedChange = controller::setIncludeCookies,
                                )
                                if (!state.secretImportAvailable) {
                                    Text(
                                        strings.text(StringKey.PROTECTED_PLATFORM_STORAGE_IS_UNAVAILABLE_SO_SECRET),
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
                            Text(strings.text(StringKey.IMPORT_SELECTED_CONTENT))
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
                                    Text(strings.text(StringKey.REVIEW_AND_IMPORT_SELECTED_SECRETS))
                                }
                            } else {
                                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                    Text(strings.text(StringKey.DONE))
                                }
                            }
                        }

                        ShuYueMigrationUiPhase.COMPLETE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ImportCompletedCard(state.contentImport?.replayed == true)
                            Text(
                                strings.text(
                                    StringKey.IMPORTED_X_CREDENTIALS_AND_X_COOKIES_INTO,
                                    state.secretImport?.credentialCount ?: 0,
                                    state.secretImport?.cookieCount ?: 0,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text(strings.text(StringKey.DONE))
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
            title = { Text(strings.text(StringKey.IMPORT_PROTECTED_SECRETS)) },
            text = {
                Text(
                    strings.text(
                        StringKey.THIS_SEPARATE_ACTION_REPLACES_THE_STORED_SHUYUE,
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
                ) { Text(strings.text(StringKey.IMPORT_SECRETS)) }
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

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.text(StringKey.CONTENT_BACKUP_V2),
            subtitle = strings.text(StringKey.VERSIONED_MANIFEST_CHECKSUMS_AND_RIGHTS_FILTERED_BODIES),
            leading = {
                IconButton(onClick = onBack, enabled = !state.busy) {
                    Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK))
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
                                Text(strings.text(StringKey.CREATE_PORTABLE_ARCHIVE), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    strings.text(StringKey.CREDENTIALS_COOKIES_OAUTH_TOKENS_AND_DEVICE_KEYS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        LabeledSwitch(
                            label = strings.text(StringKey.INCLUDE_EXPORTABLE_CONTENT_BODIES),
                            checked = includeContentBlobs,
                            enabled = !state.busy && state.exportAvailable,
                            onCheckedChange = { includeContentBlobs = it },
                        )
                        Text(
                            strings.text(StringKey.EACH_BODY_IS_INCLUDED_ONLY_WHEN_ITS),
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
                            Text(strings.text(StringKey.CREATE_BINARY_ARCHIVE))
                        }
                        if (!state.exportAvailable) {
                            Text(
                                strings.text(StringKey.BINARY_EXPORT_IS_UNAVAILABLE_UNTIL_THE_CONTENT),
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
                        Text(strings.text(StringKey.RESTORE_CONTENT_ARCHIVE), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text(StringKey.THE_COMPLETE_CONTAINER_DECLARED_PATHS_CHECKSUMS_AND),
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
                            Text(strings.text(StringKey.CHOOSE_SHINSOU2_ARCHIVE))
                        }
                    }
                }
            }

            state.failure?.let { failure ->
                item("failure") { PortabilityFailureCard(failure.message) }
            }

            state.lastExport?.let { summary ->
                item("last-export") {
                    ContentBackupSummaryCard(strings.text(StringKey.ARCHIVE_READY), summary)
                }
            }

            state.restoreReview?.let { summary ->
                item("restore-review") {
                    ContentBackupSummaryCard(strings.text(StringKey.VERIFIED_RESTORE_PREVIEW), summary)
                }
                item("restore-action") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { confirmLocalRestore = true },
                            enabled = state.restoreAvailable && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Restore, null)
                            Spacer(Modifier.width(7.dp))
                            Text(strings.text(StringKey.RESTORE_VERIFIED_ARCHIVE))
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
                                strings.text(StringKey.RESTORE_REMAINS_BLOCKED_UNTIL_ONE_SHARED_CONTENT),
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
                                    StringKey.RESTORED_X_PUBLICATIONS_X_ANNOTATIONS_AND_X,
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
                Text(strings.text(StringKey.MOVING_FROM_SHUYUE), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text(StringKey.USE_THE_DEDICATED_STAGED_IMPORTER_FOR_ITS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenShuYueMigration,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.text(StringKey.IMPORT_FROM_SHUYUE))
                }
            }
        }
    }

    if (confirmLocalRestore) {
        AlertDialog(
            onDismissRequest = { confirmLocalRestore = false },
            title = { Text(strings.text(StringKey.RESTORE_THIS_ARCHIVE)) },
            text = { Text(strings.text(StringKey.VERIFIED_PORTABLE_STATE_AND_BODIES_WILL_BE)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLocalRestore = false
                        scope.launch { controller.restore() }
                    },
                ) { Text(strings.restoreBackup) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLocalRestore = false }) { Text(strings.cancel) }
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
                if (replayed) strings.text(StringKey.THIS_EXACT_IMPORT_WAS_ALREADY_COMMITTED_NOTHING)
                else strings.text(StringKey.SELECTED_CONTENT_AND_QUARANTINED_SCRIPTS_WERE_COMMITTED),
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
                    StringKey.X_PUBLICATIONS_X_ANNOTATIONS_X_CONTENT_BODIES,
                    summary.publicationCount,
                    summary.annotationCount,
                    summary.contentBlobCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                strings.text(
                    StringKey.X_X_ATTACHED_MANIFESTS_X_OMITTED,
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

private fun backupV2OmissionLabel(strings: ShinsouStrings, reason: BackupV2OmissionReason): String = when (reason) {
    BackupV2OmissionReason.NOT_REQUESTED -> strings.text(StringKey.NOT_REQUESTED)
    BackupV2OmissionReason.RIGHTS_DENIED -> strings.text(StringKey.RIGHTS_DENIED)
    BackupV2OmissionReason.BLOB_MISSING -> strings.text(StringKey.MISSING)
    BackupV2OmissionReason.BLOB_CORRUPT -> strings.text(StringKey.CORRUPT)
    BackupV2OmissionReason.ARCHIVE_LIMIT -> strings.text(StringKey.ARCHIVE_LIMIT)
}

private fun localizedPortabilityMessage(
    strings: ShinsouStrings,
    message: String,
    diagnosticCode: String? = null,
): String {
    // Only a message whose translation differs from it counts as localized (kept from the string lookup).
    return strings.authoredMessage(message)?.takeIf { it != message } ?: strings.text(
        StringKey.THE_BACKUP_CONTAINS_INVALID_OR_UNSUPPORTED_DATA,
        diagnosticCode ?: "unknown",
    )
}

// An out-of-range time falls back to the raw number; nothing is lost by not logging it.
private fun formatBackupV2Time(epochMillis: Long): String = runCatching {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    "${value.date} ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}.getOrElse { epochMillis.toString() }

private fun formatBackupV2Bytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes >= 1_024 -> "${bytes / 1_024} KB"
    else -> "$bytes B"
}
