package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.ALWAYS_ASK_CATEGORY_ID
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.LibraryDisplayMode
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.sync.SnapshotSyncAvailability
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.SnapshotSyncOutcome
import dev.shinsou.kmp.sync.SnapshotSyncPhase
import dev.shinsou.kmp.sync.SnapshotSyncState
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiState
import dev.shinsou.kmp.sync.v2.MaterializationIssueKind
import dev.shinsou.kmp.sync.v2.RepositoryTrustConfirmationStatus
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.components.HairlineDivider
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlin.math.roundToInt

private enum class SettingsSection(val icon: ImageVector) {
    General(Icons.Outlined.Settings),
    Appearance(Icons.Outlined.Palette),
    Library(Icons.Outlined.Book),
    Reader(Icons.Outlined.Brightness4),
    Downloads(Icons.Outlined.Download),
    Tracking(Icons.Outlined.Sync),
    Sync(Icons.Outlined.CloudDownload),
    Browse(Icons.Outlined.Extension),
    Security(Icons.Outlined.Security),
    Advanced(Icons.Outlined.Tune),
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    categories: List<Category>,
    snapshotSync: SnapshotSyncController? = null,
    cloudflareSync: CloudflareSyncUiController? = null,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    securityCapabilities: PlatformSecurityCapabilities = PlatformSecurityCapabilities.Unavailable,
    wideLayout: Boolean,
    onBack: () -> Unit,
    onChange: (AppSettings) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    onDeleteCategory: (Long) -> Unit,
    onReorderCategories: (List<Long>) -> Unit,
    authenticate: suspend (String) -> Boolean,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onBackAvailabilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<SettingsSection?>(if (wideLayout) SettingsSection.General else null) }
    var processedSystemBackRequest by remember { mutableStateOf(systemBackRequest) }
    val currentOnBackAvailabilityChanged by rememberUpdatedState(onBackAvailabilityChanged)

    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == processedSystemBackRequest) return@LaunchedEffect
        processedSystemBackRequest = systemBackRequest
        if (!wideLayout && selected != null) {
            selected = null
        } else {
            onBack()
        }
    }
    LaunchedEffect(wideLayout, selected) {
        currentOnBackAvailabilityChanged(!wideLayout && selected != null)
    }
    DisposableEffect(Unit) {
        onDispose { currentOnBackAvailabilityChanged(false) }
    }

    if (wideLayout) {
        Row(modifier.fillMaxSize()) {
            SettingsSidebar(
                selected = selected ?: SettingsSection.General,
                onSelect = { selected = it },
                onBack = onBack,
                modifier = Modifier.width(245.dp).fillMaxHeight(),
            )
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            SettingsDetail(
                section = selected ?: SettingsSection.General,
                settings = settings,
                categories = categories,
                snapshotSync = snapshotSync,
                cloudflareSync = cloudflareSync,
                appServices = appServices,
                securityCapabilities = securityCapabilities,
                onBack = null,
                onChange = onChange,
                onCreateCategory = onCreateCategory,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                onReorderCategories = onReorderCategories,
                authenticate = authenticate,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Box(modifier.fillMaxSize()) {
            SettingsSidebar(
                selected = null,
                onSelect = { selected = it },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
            selected?.let { section ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
                        },
                ) {
                    SettingsDetail(
                        section = section,
                        settings = settings,
                        categories = categories,
                        snapshotSync = snapshotSync,
                        cloudflareSync = cloudflareSync,
                        appServices = appServices,
                        securityCapabilities = securityCapabilities,
                        onBack = { selected = null },
                        onChange = onChange,
                        onCreateCategory = onCreateCategory,
                        onRenameCategory = onRenameCategory,
                        onDeleteCategory = onDeleteCategory,
                        onReorderCategories = onReorderCategories,
                        authenticate = authenticate,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSidebar(
    selected: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Column(modifier) {
        ScreenHeader(
            title = strings.settings,
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(SettingsSection.entries, key = { it.name }) { section ->
                Surface(
                    onClick = { onSelect(section) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected == section) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Icon(section.icon, null, Modifier.size(20.dp))
                        Text(section.displayName(strings), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    section: SettingsSection,
    settings: AppSettings,
    categories: List<Category>,
    snapshotSync: SnapshotSyncController?,
    cloudflareSync: CloudflareSyncUiController?,
    appServices: ShinsouAppServices,
    securityCapabilities: PlatformSecurityCapabilities,
    onBack: (() -> Unit)?,
    onChange: (AppSettings) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    onDeleteCategory: (Long) -> Unit,
    onReorderCategories: (List<Long>) -> Unit,
    authenticate: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Column(modifier) {
        ScreenHeader(
            title = section.displayName(strings),
            leading = onBack?.let { callback ->
                { IconButton(onClick = callback) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) } }
            },
        )
        when (section) {
            SettingsSection.General -> GeneralSettingsPane(settings, onChange)
            SettingsSection.Appearance -> AppearanceSettingsPane(settings, onChange)
            SettingsSection.Library -> LibrarySettingsPane(
                settings = settings,
                categories = categories,
                onChange = onChange,
                onCreateCategory = onCreateCategory,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                onReorderCategories = onReorderCategories,
            )
            SettingsSection.Reader -> ReaderSettingsPane(settings, onChange)
            SettingsSection.Downloads -> DownloadSettingsPane(settings, onChange)
            SettingsSection.Tracking -> TrackingSettingsPane(settings, onChange)
            SettingsSection.Sync -> SyncSettingsPane(settings, snapshotSync, cloudflareSync, appServices, onChange)
            SettingsSection.Browse -> BrowseSettingsPane(settings, onChange)
            SettingsSection.Security -> SecuritySettingsPane(
                settings = settings,
                capabilities = securityCapabilities,
                onChange = onChange,
                authenticate = authenticate,
            )
            SettingsSection.Advanced -> AdvancedSettingsPane(settings, onChange)
        }
    }
}

@Composable
private fun GeneralSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    val languages = listOf(
        "system" to strings.text("System"),
        "en" to strings.text("English"),
        "zh-Hant" to strings.text("繁體中文"),
        "zh-Hans" to strings.text("简体中文"),
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "pt" to "Português",
    )
    SettingsList {
        item {
            ChoiceSetting(
                title = strings.text("Language"),
                summary = strings.text("Changes apply immediately"),
                selectedLabel = languages.firstOrNull { it.first == (settings.general.languagePreference ?: "system") }?.second ?: strings.text("System"),
                choices = languages.map { it.second },
                onSelected = { label ->
                    val code = languages.first { it.second == label }.first
                    onChange(settings.copy(general = settings.general.copy(languagePreference = code)))
                },
            )
        }
        item {
            ChoiceSetting(
                title = strings.text("Starting screen"),
                selectedLabel = strings.text(settings.general.defaultStartingScreen.name.lowercase().replaceFirstChar { it.uppercase() }),
                choices = MainSection.entries.map { strings.text(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                onSelected = { label ->
                    val rawLabel = MainSection.entries
                        .map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
                        .firstOrNull { strings.text(it) == label }
                        ?: label
                    onChange(
                        settings.copy(
                            general = settings.general.copy(
                                defaultStartingScreen = MainSection.valueOf(rawLabel.uppercase()),
                            ),
                        ),
                    )
                },
            )
        }
        item {
            ToggleSetting(
                strings.text("Confirm before closing"),
                strings.text("Ask before closing the desktop application"),
                settings.general.confirmBeforeClosing,
            ) { onChange(settings.copy(general = settings.general.copy(confirmBeforeClosing = it))) }
        }
        item {
            ChoiceSetting(
                strings.text("Date format"),
                selectedLabel = settings.general.dateFormat,
                choices = listOf("system", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd"),
            ) { onChange(settings.copy(general = settings.general.copy(dateFormat = it))) }
        }
    }
}

@Composable
private fun AppearanceSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ChoiceSetting(
                strings.text("Theme"),
                selectedLabel = strings.text(settings.appearance.theme.name.lowercase().replaceFirstChar { it.uppercase() }),
                choices = ThemeMode.entries.map { strings.text(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
            ) { selectedLabel ->
                val raw = ThemeMode.entries
                    .map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
                    .firstOrNull { strings.text(it) == selectedLabel }
                onChange(settings.copy(appearance = settings.appearance.copy(theme = ThemeMode.valueOf((raw ?: selectedLabel).uppercase()))))
            }
        }
        item {
            ToggleSetting(strings.text("AMOLED black"), strings.text("Use pure black surfaces in dark mode"), settings.appearance.amoledDark) {
                onChange(settings.copy(appearance = settings.appearance.copy(amoledDark = it)))
            }
        }
        item {
            ToggleSetting(strings.text("Relative timestamps"), strings.text("Show “2h ago” instead of a clock time"), settings.appearance.relativeTimestamps) {
                onChange(settings.copy(appearance = settings.appearance.copy(relativeTimestamps = it)))
            }
        }
    }
}

@Composable
private fun LibrarySettingsPane(
    settings: AppSettings,
    categories: List<Category>,
    onChange: (AppSettings) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    onDeleteCategory: (Long) -> Unit,
    onReorderCategories: (List<Long>) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val orderedCategories = categories.sortedBy { it.sort }
    val categoryChoices = remember(orderedCategories, strings) {
        linkedMapOf<String, Long>().apply {
            orderedCategories.forEach { category ->
                put(category.name.ifBlank { strings.text("Default") }, category.id)
            }
            put(strings.text("Always Ask"), ALWAYS_ASK_CATEGORY_ID)
        }
    }
    val selectedCategoryLabel = categoryChoices.entries
        .firstOrNull { it.value == settings.library.defaultCategoryId }
        ?.key
        ?: Category.Default.name
    SettingsList {
        item {
            ChoiceSetting(
                strings.text("Library layout"),
                selectedLabel = strings.text(settings.library.displayMode.pretty()),
                choices = LibraryDisplayMode.entries.map { strings.text(it.pretty()) },
            ) { label ->
                val mode = LibraryDisplayMode.entries.first { strings.text(it.pretty()) == label }
                onChange(settings.copy(library = settings.library.copy(displayMode = mode)))
            }
        }
        item {
            SliderSetting(strings.text("Portrait columns"), settings.library.portraitColumns.toFloat(), 2f..8f, 1) {
                onChange(settings.copy(library = settings.library.copy(portraitColumns = it.roundToInt())))
            }
        }
        item {
            SliderSetting(strings.text("Landscape columns"), settings.library.landscapeColumns.toFloat(), 3f..12f, 1) {
                onChange(settings.copy(library = settings.library.copy(landscapeColumns = it.roundToInt())))
            }
        }
        item {
            ChoiceSetting(
                title = strings.text("Default category"),
                summary = if (settings.library.defaultCategoryId == ALWAYS_ASK_CATEGORY_ID) {
                    strings.text("Choose one or more categories whenever a title is added")
                } else {
                    strings.text("Newly added library titles are placed here")
                },
                selectedLabel = selectedCategoryLabel,
                choices = categoryChoices.keys.toList(),
            ) { label ->
                onChange(
                    settings.copy(
                        library = settings.library.copy(defaultCategoryId = categoryChoices.getValue(label)),
                    ),
                )
            }
        }
        item {
            ChoiceSetting(
                title = strings.text("Category updates"),
                summary = strings.text("Choose which categories participate in library updates"),
                selectedLabel = when (settings.library.categoryUpdateBehaviour) {
                    "selected" -> strings.text("Selected categories only")
                    "none" -> strings.text("Disabled")
                    else -> strings.text("All categories")
                },
                choices = listOf(strings.text("All categories"), strings.text("Selected categories only"), strings.text("Disabled")),
            ) { label ->
                val value = when (label) {
                    strings.text("Selected categories only") -> "selected"
                    strings.text("Disabled") -> "none"
                    else -> "all"
                }
                onChange(settings.copy(library = settings.library.copy(categoryUpdateBehaviour = value)))
            }
        }
        item {
            ToggleSetting(
                strings.text("Wi-Fi only updates"),
                strings.text("Refresh library metadata only on Wi-Fi"),
                "wifi_only" in settings.library.globalUpdateRestrictions,
            ) { enabled ->
                val values = settings.library.globalUpdateRestrictions.withToggle("wifi_only", enabled)
                onChange(settings.copy(library = settings.library.copy(globalUpdateRestrictions = values)))
            }
        }
        item {
            ToggleSetting(
                strings.text("Charging only updates"),
                strings.text("Refresh the library only while this device is charging"),
                "charging_only" in settings.library.globalUpdateRestrictions,
            ) { enabled ->
                val values = settings.library.globalUpdateRestrictions.withToggle("charging_only", enabled)
                onChange(settings.copy(library = settings.library.copy(globalUpdateRestrictions = values)))
            }
        }
        item {
            CategoryManagementSetting(
                categories = orderedCategories.filterNot { it.isSystemCategory },
                onCreate = onCreateCategory,
                onRename = onRenameCategory,
                onDelete = onDeleteCategory,
                onReorder = onReorderCategories,
            )
        }
        item {
            ToggleSetting(strings.text("Download-only mode"), strings.text("Hide titles without offline chapters"), settings.library.downloadOnly) {
                onChange(settings.copy(library = settings.library.copy(downloadOnly = it)))
            }
        }
        item {
            ToggleSetting(strings.text("Refresh metadata automatically"), null, settings.library.autoRefreshMetadata) {
                onChange(settings.copy(library = settings.library.copy(autoRefreshMetadata = it)))
            }
        }
    }
}

@Composable
private fun ReaderSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ChoiceSetting(
                strings.text("Default reading mode"),
                selectedLabel = strings.text(settings.reader.readingMode.pretty()),
                choices = ReadingMode.entries.map { strings.text(it.pretty()) },
            ) { label ->
                onChange(settings.copy(reader = settings.reader.copy(readingMode = ReadingMode.entries.first { strings.text(it.pretty()) == label })))
            }
        }
        item {
            ChoiceSetting(
                strings.text("Orientation"),
                selectedLabel = strings.text(settings.reader.orientation.pretty()),
                choices = ReaderOrientation.entries.map { strings.text(it.pretty()) },
            ) { label ->
                onChange(settings.copy(reader = settings.reader.copy(orientation = ReaderOrientation.entries.first { strings.text(it.pretty()) == label })))
            }
        }
        item { ToggleSetting(strings.text("Double-tap to zoom"), null, settings.reader.doubleTapToZoom) { onChange(settings.copy(reader = settings.reader.copy(doubleTapToZoom = it))) } }
        item {
            ToggleSetting(
                strings.pageTurnAnimation,
                strings.pageTurnAnimationDescription,
                settings.reader.animatePageTransitions,
            ) { enabled ->
                onChange(settings.copy(reader = settings.reader.copy(animatePageTransitions = enabled)))
            }
        }
        item { ToggleSetting(strings.text("Show page number"), null, settings.reader.showPageNumber) { onChange(settings.copy(reader = settings.reader.copy(showPageNumber = it))) } }
        item { ToggleSetting(strings.text("Keep screen on"), null, settings.reader.keepScreenOn) { onChange(settings.copy(reader = settings.reader.copy(keepScreenOn = it))) } }
        item { ToggleSetting(strings.text("Skip read chapters"), null, settings.reader.skipReadChapters) { onChange(settings.copy(reader = settings.reader.copy(skipReadChapters = it))) } }
        item { ToggleSetting(strings.text("Skip filtered chapters"), strings.text("Apply Manga Detail filters while moving between chapters"), settings.reader.skipFilteredChapters) { onChange(settings.copy(reader = settings.reader.copy(skipFilteredChapters = it))) } }
        item { ToggleSetting(strings.text("Skip duplicate chapters"), strings.text("Skip alternate copies with the same chapter number"), settings.reader.skipDuplicateChapters) { onChange(settings.copy(reader = settings.reader.copy(skipDuplicateChapters = it))) } }
        item { ToggleSetting(strings.text("Split tall images"), null, settings.reader.splitTallImages) { onChange(settings.copy(reader = settings.reader.copy(splitTallImages = it))) } }
        item { ToggleSetting(strings.text("Volume keys"), strings.text("Use hardware volume keys to turn pages"), settings.reader.volumeKeys) { onChange(settings.copy(reader = settings.reader.copy(volumeKeys = it))) } }
    }
}

@Composable
private fun DownloadSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text("Wi-Fi only"), null, settings.downloads.wifiOnly) { onChange(settings.copy(downloads = settings.downloads.copy(wifiOnly = it))) } }
        item { ToggleSetting(strings.text("Auto-download new chapters"), null, settings.downloads.autoDownloadNewChapters) { onChange(settings.copy(downloads = settings.downloads.copy(autoDownloadNewChapters = it))) } }
        item { ToggleSetting(strings.text("Delete after reading"), null, settings.downloads.deleteAfterReading) { onChange(settings.copy(downloads = settings.downloads.copy(deleteAfterReading = it))) } }
        item {
            ToggleSetting(
                strings.text("Also delete when marked read"),
                strings.text("Apply automatic deletion to chapters marked read outside the Reader"),
                settings.downloads.removeAfterMarkedRead,
                enabled = settings.downloads.deleteAfterReading,
            ) {
                onChange(settings.copy(downloads = settings.downloads.copy(removeAfterMarkedRead = it)))
            }
        }
        item { SliderSetting(strings.text("Parallel chapters"), settings.downloads.parallelDownloads.toFloat(), 1f..10f, 1) { onChange(settings.copy(downloads = settings.downloads.copy(parallelDownloads = it.roundToInt()))) } }
        item { SliderSetting(strings.text("Parallel pages"), settings.downloads.parallelPages.toFloat(), 1f..12f, 1) { onChange(settings.copy(downloads = settings.downloads.copy(parallelPages = it.roundToInt()))) } }
    }
}

@Composable
private fun TrackingSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text("Auto-sync after reading"), null, settings.tracking.autoSyncAfterRead) { onChange(settings.copy(tracking = settings.tracking.copy(autoSyncAfterRead = it))) } }
        item { ToggleSetting(strings.text("Update chapter progress"), null, settings.tracking.updateProgressAfterRead) { onChange(settings.copy(tracking = settings.tracking.copy(updateProgressAfterRead = it))) } }
        item {
            SettingDescription(strings.text("Tracker accounts are connected from Manga Detail. Authentication tokens remain in platform secure storage."))
        }
    }
}

@Composable
private fun SyncSettingsPane(
    settings: AppSettings,
    legacyController: SnapshotSyncController?,
    cloudflareController: CloudflareSyncUiController?,
    appServices: ShinsouAppServices,
    onChange: (AppSettings) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val observedLegacyState = legacyController?.state?.collectAsState()
    val legacyState = observedLegacyState?.value ?: SnapshotSyncState(
        capability = dev.shinsou.kmp.sync.SnapshotSyncCapability(
            availability = SnapshotSyncAvailability.UNAVAILABLE,
            detail = strings.text("iCloud Drive snapshot sync is unavailable on this platform."),
        ),
        phase = SnapshotSyncPhase.UNAVAILABLE,
    )
    val observedCloudflareState = cloudflareController?.state?.collectAsState()
    val cloudflareState = observedCloudflareState?.value ?: CloudflareSyncUiState()
    val capability = legacyState.capability
    val legacyBusy = legacyState.phase == SnapshotSyncPhase.CHECKING || legacyState.phase == SnapshotSyncPhase.SYNCING
    val legacyBlocked = cloudflareState.configured
    var linkOrCode by remember { mutableStateOf("") }
    var localDiagnostic by remember { mutableStateOf<String?>(null) }
    var localShare by remember { mutableStateOf<dev.shinsou.kmp.sync.v2.SyncShareRequest?>(null) }
    var revokeDeviceId by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var quotaMaxUsers by remember { mutableStateOf("") }
    var quotaMaxWorkspaces by remember { mutableStateOf("") }
    var quotaMaxDevices by remember { mutableStateOf("") }
    var quotaWorkspaceMiB by remember { mutableStateOf("") }
    var quotaEventKiB by remember { mutableStateOf("") }
    var quotaCheckpointMiB by remember { mutableStateOf("") }

    val runCloudflareAction: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            localDiagnostic = null
            runCatching { action() }.onFailure { error ->
                localDiagnostic = error.message ?: strings.text("Sync operation failed.")
            }
        }
    }

    LaunchedEffect(legacyController) { legacyController?.refreshCapability() }
    LaunchedEffect(cloudflareState.adminUsage?.quota) {
        cloudflareState.adminUsage?.quota?.let { quota ->
            quotaMaxUsers = quota.maxUsers.toString()
            quotaMaxWorkspaces = quota.maxWorkspacesPerUser.toString()
            quotaMaxDevices = quota.maxDevicesPerUser.toString()
            quotaWorkspaceMiB = (quota.maxWorkspaceBytes / MEBIBYTE).toString()
            quotaEventKiB = (quota.maxEventBytes / KIBIBYTE).toString()
            quotaCheckpointMiB = (quota.maxCheckpointBytes / MEBIBYTE).toString()
        }
    }
    LaunchedEffect(legacyBlocked, settings.sync.enabled) {
        if (legacyBlocked && settings.sync.enabled) {
            onChange(settings.copy(sync = settings.sync.copy(enabled = false, syncOnForeground = false)))
        }
    }

    SettingsList {
        item {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.text("Cloudflare encrypted sync"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.text(cloudflareStatusLabel(cloudflareState.status)),
                        color = when (cloudflareState.status) {
                            SyncSessionStatus.READY -> MaterialTheme.colorScheme.primary
                            SyncSessionStatus.ERROR, SyncSessionStatus.REVOKED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    cloudflareState.endpoint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (cloudflareState.ready) {
                        Text(
                            strings.text(
                                "Cursor {0}/{1} · {2} pending changes · {3} pending uploads",
                                cloudflareState.cursor,
                                cloudflareState.remoteHead,
                                cloudflareState.pendingDrafts,
                                cloudflareState.pendingUploads,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    (localDiagnostic ?: cloudflareState.diagnostic)?.let { message ->
                        Text(strings.text(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (cloudflareController == null) {
                        Text(
                            strings.text("Encrypted event sync is unavailable in this runtime."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SettingDescription(strings.text(SYNC_SCOPE_DISCLOSURE))
        }
        if (cloudflareState.materializationIssues.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.text("Synchronized data needs repair"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text(
                                "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded.",
                                cloudflareState.materializationIssues.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(
                            enabled = cloudflareState.ready && !cloudflareState.busy,
                            onClick = { runCloudflareAction { cloudflareController?.retryMaterialization() } },
                        ) { Text(strings.text("Retry validation")) }
                    }
                }
            }
            items(
                cloudflareState.materializationIssues,
                key = { "materialization-${it.kind}-${it.key?.stableString().orEmpty()}-${it.message}" },
            ) { issue ->
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            strings.text(
                                when (issue.kind) {
                                    MaterializationIssueKind.ORPHAN -> "Missing synchronized dependency"
                                    MaterializationIssueKind.IDENTITY_COLLISION -> "Identity mapping collision"
                                    MaterializationIssueKind.INVALID_FIELD -> "Invalid synchronized record"
                                },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        issue.key?.let {
                            Text(
                                it.stableString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(strings.text(issue.message), style = MaterialTheme.typography.bodySmall)
                        if (issue.kind == MaterializationIssueKind.IDENTITY_COLLISION && issue.key != null) {
                            TextButton(
                                enabled = cloudflareState.ready && !cloudflareState.busy,
                                onClick = {
                                    runCloudflareAction {
                                        cloudflareController?.repairIdentityCollision(issue.key)
                                    }
                                },
                            ) { Text(strings.text("Repair local identity mapping")) }
                        }
                    }
                }
            }
        }
        if (cloudflareState.repositoryTrustConfirmations.isNotEmpty()) {
            items(
                cloudflareState.repositoryTrustConfirmations,
                key = { "repository-trust-${it.baseUrl}-${it.proposedFingerprint}" },
            ) { confirmation ->
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.text("Confirm repository signing key"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text(
                                "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint.",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(confirmation.baseUrl, style = MaterialTheme.typography.bodySmall)
                        Text(
                            strings.text(
                                "Trusted: {0}",
                                confirmation.trustedFingerprint.ifBlank { strings.text("None on this device") },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            strings.text("Proposed: {0}", confirmation.proposedFingerprint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (confirmation.status == RepositoryTrustConfirmationStatus.REJECTED) {
                            Text(
                                strings.text("Rejected on this device"),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TextButton(
                                enabled = cloudflareState.ready && !cloudflareState.busy,
                                onClick = {
                                    runCloudflareAction {
                                        cloudflareController?.acceptRepositoryTrust(
                                            confirmation.baseUrl,
                                            confirmation.proposedFingerprint,
                                        )
                                    }
                                },
                            ) { Text(strings.text("Trust exact fingerprint")) }
                            if (confirmation.status != RepositoryTrustConfirmationStatus.REJECTED) {
                                TextButton(
                                    enabled = cloudflareState.ready && !cloudflareState.busy,
                                    onClick = {
                                        runCloudflareAction {
                                            cloudflareController?.rejectRepositoryTrust(
                                                confirmation.baseUrl,
                                                confirmation.proposedFingerprint,
                                            )
                                        }
                                    },
                                ) { Text(strings.text("Reject"), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        if (!cloudflareState.ready) {
            if (cloudflareState.status == SyncSessionStatus.DEPLOYING) {
                item {
                    Text(
                        strings.text(
                            "The deployment page is required to create your private sync service. The bootstrap secret is kept on this device and can be reused if you reopen the page.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = linkOrCode,
                    onValueChange = { linkOrCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.text("Setup, invite, pairing or emergency handoff link / code")) },
                    minLines = 1,
                    maxLines = 3,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TextButton(
                        enabled = cloudflareController != null && linkOrCode.isNotBlank() && !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction {
                                cloudflareController?.submitOneTimeLinkOrCode(linkOrCode.trim())
                                linkOrCode = ""
                            }
                        },
                    ) { Text(strings.text("Connect")) }
                    TextButton(
                        enabled = cloudflareController != null && !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction {
                                appServices.readClipboardText()?.takeIf { it.isNotBlank() }?.let { linkOrCode = it }
                            }
                        },
                    ) { Text(strings.text("Paste")) }
                    TextButton(
                        enabled = cloudflareController != null && !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction {
                                appServices.scanQrCode()?.takeIf { it.isNotBlank() }?.let { scanned ->
                                    cloudflareController?.submitOneTimeLinkOrCode(scanned)
                                }
                            }
                        },
                    ) { Text(strings.text("Scan QR")) }
                    TextButton(
                        enabled = cloudflareController != null &&
                            !cloudflareState.busy &&
                            cloudflareState.status in setOf(
                                SyncSessionStatus.NOT_CONFIGURED,
                                SyncSessionStatus.DEPLOYING,
                            ),
                        onClick = {
                            runCloudflareAction {
                                val deployment = requireNotNull(cloudflareController).beginDeployment()
                                val copied = deployment.bootstrapSecret.use { secret ->
                                    appServices.copyText(strings.text("Bootstrap secret"), secret)
                                }
                                val opened = appServices.tryOpenExternalUrl(deployment.deployUrl)
                                when {
                                    !copied && !opened -> {
                                        localDiagnostic = strings.text(
                                            "Could not copy the bootstrap secret or open the deployment page.",
                                        )
                                    }
                                    !copied -> {
                                        localDiagnostic = strings.text(
                                            "Could not copy the bootstrap secret. Copying is required before deployment.",
                                        )
                                    }
                                    !opened -> {
                                        localDiagnostic = strings.text(
                                            "Could not open the deployment page. Tap Open deployment page to retry.",
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            strings.text(
                                if (cloudflareState.status == SyncSessionStatus.DEPLOYING) {
                                    "Open deployment page"
                                } else {
                                    "Create sync service"
                                },
                            ),
                        )
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.text("Lost every device?"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.text("Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            enabled = cloudflareController != null && !cloudflareState.busy,
                            onClick = {
                                runCloudflareAction {
                                    val document = appServices.importDocument(
                                        setOf("json", "shinsourecovery", "txt"),
                                    )
                                    if (document != null) {
                                        requireNotNull(cloudflareController).importRecoveryKit(
                                            document.contents.decodeToString(),
                                        )
                                    }
                                }
                            },
                        ) { Text(strings.text("Import Recovery Kit")) }
                        if (cloudflareState.configured) {
                            TextButton(
                                enabled = cloudflareController != null && !cloudflareState.busy,
                                onClick = { confirmLeave = true },
                            ) {
                                Text(
                                    strings.text("Leave or clear pending workspace"),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = { runCloudflareAction { cloudflareController?.syncNow() } },
                    ) { Text(strings.text("Sync now")) }
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction { localShare = cloudflareController?.createDevicePairing() }
                        },
                    ) { Text(strings.text("Add device")) }
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction { localShare = cloudflareController?.createUserInvite() }
                        },
                    ) { Text(strings.text("Invite user")) }
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = { runCloudflareAction { cloudflareController?.refreshDevices() } },
                    ) { Text(strings.text("Refresh devices")) }
                }
            }
            items(cloudflareState.pairingCandidates, key = { "pairing-${it.pairingId}" }) { candidate ->
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(strings.text("Approve new device"), style = MaterialTheme.typography.titleMedium)
                        Text("${candidate.displayName} · ${candidate.platform}")
                        Text(
                            candidate.confirmationCode,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TextButton(
                                onClick = {
                                    runCloudflareAction {
                                        cloudflareController?.approvePairing(candidate.pairingId, true)
                                    }
                                },
                            ) { Text(strings.text("Allow")) }
                            TextButton(
                                onClick = {
                                    runCloudflareAction {
                                        cloudflareController?.approvePairing(candidate.pairingId, false)
                                    }
                                },
                            ) { Text(strings.text("Deny")) }
                        }
                    }
                }
            }
            cloudflareState.adminUsage?.let { usage ->
                item {
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings.text("Instance usage and quota"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                strings.text(
                                    "{0} users · {1} devices · {2} workspaces",
                                    usage.totals.activeUsers,
                                    usage.totals.activeDevices,
                                    usage.totals.activeWorkspaces,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                strings.text(
                                    "Stored {0} · reserved {1}",
                                    formatSyncByteCount(usage.totals.committedBytes),
                                    formatSyncByteCount(usage.totals.reservedBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                AdminQuotaField("Users", quotaMaxUsers) { quotaMaxUsers = it }
                                AdminQuotaField("Workspaces / user", quotaMaxWorkspaces) { quotaMaxWorkspaces = it }
                                AdminQuotaField("Devices / user", quotaMaxDevices) { quotaMaxDevices = it }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                AdminQuotaField("Workspace MiB", quotaWorkspaceMiB) { quotaWorkspaceMiB = it }
                                AdminQuotaField("Event KiB", quotaEventKiB) { quotaEventKiB = it }
                                AdminQuotaField("Checkpoint MiB", quotaCheckpointMiB) { quotaCheckpointMiB = it }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TextButton(
                                    enabled = !cloudflareState.busy,
                                    onClick = {
                                        runCloudflareAction {
                                            val quota = SyncAdminQuota(
                                                maxUsers = quotaMaxUsers.requirePositiveInt(),
                                                maxWorkspacesPerUser = quotaMaxWorkspaces.requirePositiveInt(),
                                                maxDevicesPerUser = quotaMaxDevices.requirePositiveInt(),
                                                maxWorkspaceBytes = quotaWorkspaceMiB.requirePositiveLong() * MEBIBYTE,
                                                maxEventBytes = (quotaEventKiB.requirePositiveLong() * KIBIBYTE)
                                                    .requireIntRange(),
                                                maxCheckpointBytes = (quotaCheckpointMiB.requirePositiveLong() * MEBIBYTE)
                                                    .requireIntRange(),
                                            )
                                            requireNotNull(cloudflareController).updateAdminQuota(quota)
                                        }
                                    },
                                ) { Text(strings.text("Save quota")) }
                                TextButton(
                                    enabled = !cloudflareState.busy,
                                    onClick = {
                                        runCloudflareAction {
                                            requireNotNull(cloudflareController).refreshAdminUsage()
                                        }
                                    },
                                ) { Text(strings.text("Refresh usage")) }
                            }
                            usage.daily.firstOrNull()?.let { daily ->
                                Text(
                                    strings.text(
                                        "{0}: {1} events ({2}), {3} checkpoints ({4})",
                                        daily.day,
                                        daily.eventsWritten,
                                        formatSyncByteCount(daily.eventBytesWritten),
                                        daily.checkpointsWritten,
                                        formatSyncByteCount(daily.checkpointBytesWritten),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                strings.text("This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(cloudflareState.devices, key = { "device-${it.deviceId}" }) { device ->
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.displayName + if (device.currentDevice) strings.text(" (this device)") else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${device.platform} · " + if (device.revoked) strings.text("Revoked") else strings.text("Active"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!device.currentDevice && !device.revoked) {
                            TextButton(onClick = { revokeDeviceId = device.deviceId }) {
                                Text(strings.text("Revoke"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction {
                                val kit = requireNotNull(cloudflareController).exportRecoveryKit()
                                kit.encoded.useSuspending { encoded ->
                                    appServices.exportDocument(kit.suggestedFileName, encoded)
                                }
                            }
                        },
                    ) { Text(strings.text("Export Recovery Kit")) }
                    TextButton(
                        enabled = !cloudflareState.busy,
                        onClick = {
                            runCloudflareAction {
                                val document = appServices.importDocument(setOf("json", "shinsourecovery", "txt"))
                                if (document != null) {
                                    cloudflareController?.importRecoveryKit(document.contents.decodeToString())
                                }
                            }
                        },
                    ) { Text(strings.text("Import Recovery Kit")) }
                    TextButton(onClick = { confirmLeave = true }) {
                        Text(strings.text("Leave workspace"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(strings.text("Legacy iCloud snapshot"), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(strings.text("iCloud Drive snapshot"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (capability.availability) {
                            SnapshotSyncAvailability.CHECKING -> strings.text("Checking availability…")
                            SnapshotSyncAvailability.AVAILABLE -> strings.text("Available")
                            SnapshotSyncAvailability.UNAVAILABLE -> strings.text("Unavailable")
                        },
                        color = if (capability.available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        strings.text(capability.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = legacyController != null && !legacyBusy && !legacyBlocked,
                        onClick = { scope.launch { legacyController?.refreshCapability() } },
                    ) { Text(strings.text("Check again")) }
                }
            }
        }
        item {
            ToggleSetting(
                title = strings.text("Enable snapshot sync"),
                summary = strings.text("Merge one versioned Shinsou X backup file through iCloud Drive"),
                checked = settings.sync.enabled,
                enabled = !legacyBlocked && (capability.available || settings.sync.enabled),
            ) { enabled ->
                onChange(settings.copy(sync = settings.sync.copy(enabled = enabled)))
            }
        }
        item {
            ToggleSetting(
                title = strings.text("Sync when app enters foreground"),
                summary = strings.text("Runs only when sync is enabled; identical snapshots do not write again"),
                checked = settings.sync.syncOnForeground,
                enabled = !legacyBlocked && capability.available && settings.sync.enabled,
            ) { enabled ->
                onChange(settings.copy(sync = settings.sync.copy(syncOnForeground = enabled)))
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.text("Sync status"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (legacyState.phase) {
                                SnapshotSyncPhase.IDLE -> strings.text("Idle")
                                SnapshotSyncPhase.CHECKING -> strings.text("Checking availability")
                                SnapshotSyncPhase.SYNCING -> strings.text("Pulling, merging and uploading")
                                SnapshotSyncPhase.SUCCESS -> strings.text("Last sync succeeded")
                                SnapshotSyncPhase.ERROR -> strings.text("Last sync failed")
                                SnapshotSyncPhase.UNAVAILABLE -> strings.text("Unavailable")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = legacyController != null && !legacyBlocked && capability.available && settings.sync.enabled && !legacyBusy,
                        onClick = { scope.launch { legacyController?.sync() } },
                    ) { Text(strings.text("Sync now")) }
                }
            }
        }
        legacyState.lastResult?.let { result ->
            item {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (result.outcome == SnapshotSyncOutcome.ERROR) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(strings.text("Last result"), style = MaterialTheme.typography.titleMedium)
                        Text(strings.text(result.message), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            strings.text("Revision {0} → {1} · {2} conflicts", result.localRevision, result.mergedRevision, result.conflictCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result.conflicts.take(3).forEach { conflict ->
                            Text(
                                "${conflict.entity} ${conflict.key}: ${conflict.resolution}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingDescription(
                if (legacyBlocked) {
                    strings.text("iCloud snapshot writing is disabled while Cloudflare event sync is configured.")
                } else {
                    strings.text("This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization.")
                },
            )
        }
    }

    val share = cloudflareState.activeShare ?: localShare
    share?.let { request ->
        val payload = request.payload.use { it }
        AlertDialog(
            onDismissRequest = {
                localShare = null
                runCloudflareAction { cloudflareController?.dismissShare() }
            },
            title = { Text(strings.text(request.title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painter = rememberQrCodePainter(payload),
                        contentDescription = strings.text("Sync QR code"),
                        modifier = Modifier.size(240.dp),
                    )
                    request.confirmationCode?.let { code ->
                        Text(code, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        strings.text("This is a one-time secret. Do not post it publicly."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { appServices.shareText(request.title, payload) }) {
                    Text(strings.text("Share"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { appServices.copyText(strings.text(request.title), payload) },
                ) { Text(strings.text("Copy")) }
            },
        )
    }

    revokeDeviceId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { revokeDeviceId = null },
            title = { Text(strings.text("Revoke this device?")) },
            text = { Text(strings.text("The device will lose future access and the workspace key will rotate.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeDeviceId = null
                        runCloudflareAction { cloudflareController?.revokeDevice(deviceId) }
                    },
                ) { Text(strings.text("Revoke"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeDeviceId = null }) { Text(strings.cancel) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(strings.text("Leave synced workspace?")) },
            text = { Text(strings.text("This device will stop receiving updates. Local data remains until you restore or reset it.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        runCloudflareAction { cloudflareController?.leaveWorkspace() }
                    },
                ) { Text(strings.text("Leave"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(strings.cancel) } },
        )
    }
}

internal const val SYNC_SCOPE_DISCLOSURE =
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized."

private fun cloudflareStatusLabel(status: SyncSessionStatus): String = when (status) {
    SyncSessionStatus.NOT_CONFIGURED -> "Not configured"
    SyncSessionStatus.DEPLOYING -> "Deploying"
    SyncSessionStatus.LINKING -> "Linking"
    SyncSessionStatus.READY -> "Ready"
    SyncSessionStatus.REVOKED -> "Device revoked"
    SyncSessionStatus.ERROR -> "Error"
}

@Composable
private fun BrowseSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text("Check extension updates"), null, settings.browse.checkExtensionUpdates) { onChange(settings.copy(browse = settings.browse.copy(checkExtensionUpdates = it))) } }
        item { ToggleSetting(strings.text("Show NSFW sources"), strings.text("Hidden by default"), settings.browse.showNsfwSources) { onChange(settings.copy(browse = settings.browse.copy(showNsfwSources = it))) } }
        item {
            Text(strings.text("Enabled languages"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = settings.browse.enabledLanguages.isEmpty(),
                    onClick = {
                        onChange(settings.copy(browse = settings.browse.copy(enabledLanguages = emptySet())))
                    },
                    label = { Text(strings.all) },
                )
                listOf("en", "zh", "ja", "ko", "es").forEach { language ->
                    FilterChip(
                        selected = language in settings.browse.enabledLanguages,
                        onClick = {
                            val values = if (language in settings.browse.enabledLanguages) {
                                settings.browse.enabledLanguages - language
                            } else {
                                settings.browse.enabledLanguages + language
                            }
                            onChange(settings.copy(browse = settings.browse.copy(enabledLanguages = values)))
                        },
                        label = { Text(language.uppercase()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecuritySettingsPane(
    settings: AppSettings,
    capabilities: PlatformSecurityCapabilities,
    onChange: (AppSettings) -> Unit,
    authenticate: suspend (String) -> Boolean,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    SettingsList {
        item {
            ToggleSetting(
                title = strings.text("App lock"),
                summary = capabilities.appLock.unavailableReason?.let(strings::text)
                    ?: strings.text("Require device authentication"),
                checked = settings.security.appLockEnabled && capabilities.appLock.available,
                enabled = capabilities.appLock.available,
            ) { enabled ->
                if (!enabled) {
                    onChange(settings.copy(security = settings.security.copy(appLockEnabled = false)))
                } else {
                    scope.launch {
                        if (authenticate(strings.text("Enable Shinsou X app lock"))) {
                            onChange(settings.copy(security = settings.security.copy(appLockEnabled = true)))
                        }
                    }
                }
            }
        }
        if (capabilities.appLock.available) {
            item {
                ChoiceSetting(
                    strings.text("Lock after"),
                    selectedLabel = if (settings.security.lockAfterSeconds == 0) {
                        strings.text("Immediately")
                    } else {
                        "${settings.security.lockAfterSeconds}s"
                    },
                    choices = listOf("Immediately", "10s", "30s", "60s", "300s").map { strings.text(it) },
                ) { label ->
                    val seconds = if (label == strings.text("Immediately")) 0 else label.removeSuffix("s").toIntOrNull() ?: 0
                    onChange(settings.copy(security = settings.security.copy(lockAfterSeconds = seconds)))
                }
            }
        }
        item {
            ToggleSetting(
                title = strings.text("Secure screen"),
                summary = capabilities.secureScreen.unavailableReason?.let(strings::text)
                    ?: strings.text("Hide content in system previews"),
                checked = settings.security.secureScreen && capabilities.secureScreen.available,
                enabled = capabilities.secureScreen.available,
            ) {
                onChange(settings.copy(security = settings.security.copy(secureScreen = it)))
            }
        }
        item { ToggleSetting(strings.text("Incognito mode"), strings.text("Do not save history or sync trackers"), settings.security.incognitoMode) { onChange(settings.copy(security = settings.security.copy(incognitoMode = it))) } }
    }
}

@Composable
private fun AdvancedSettingsPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ToggleSetting(
                strings.text("DNS over HTTPS"),
                strings.text("Saved for platforms with a hostname-aware DNS resolver; direct IP rewriting cannot preserve TLS SNI safely"),
                settings.advanced.dnsOverHttps,
            ) { onChange(settings.copy(advanced = settings.advanced.copy(dnsOverHttps = it))) }
        }
        item { ToggleSetting(strings.text("Cloudflare Worker proxy"), null, settings.advanced.proxyEnabled) { onChange(settings.copy(advanced = settings.advanced.copy(proxyEnabled = it))) } }
        item {
            OutlinedTextField(
                value = settings.advanced.proxyWorkerUrl,
                onValueChange = { onChange(settings.copy(advanced = settings.advanced.copy(proxyWorkerUrl = it))) },
                label = { Text(strings.text("Proxy URL")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = settings.advanced.proxyApiKey,
                onValueChange = { onChange(settings.copy(advanced = settings.advanced.copy(proxyApiKey = it))) },
                label = { Text(strings.text("Proxy API key")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = settings.advanced.customUserAgent,
                onValueChange = { onChange(settings.copy(advanced = settings.advanced.copy(customUserAgent = it))) },
                label = { Text(strings.text("Custom User-Agent")) },
                supportingText = { Text(strings.text("Source or request headers take priority when a plugin supplies its own User-Agent.")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SettingDescription(strings.text("Credentials and tracker tokens are stored by each platform's secure storage adapter, not in the portable snapshot.")) }
    }
}

@Composable
private fun CategoryManagementSetting(
    categories: List<Category>,
    onCreate: (String) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var name by remember { mutableStateOf("") }

    fun move(categoryId: Long, delta: Int) {
        val ids = categories.map { it.id }.toMutableList()
        val from = ids.indexOf(categoryId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from >= 0 && from != to) {
            val moved = ids.removeAt(from)
            ids.add(to, moved)
            onReorder(ids)
        }
    }

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.text("Manage categories"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.text("Create, rename, reorder, or remove library categories"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        name = ""
                        creating = true
                    },
                ) { Icon(Icons.Outlined.Add, strings.text("Add category")) }
            }
            if (categories.isEmpty()) {
                Text(
                    strings.text("No custom categories"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                categories.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(enabled = index > 0, onClick = { move(category.id, -1) }) {
                            Icon(Icons.Outlined.KeyboardArrowUp, strings.text("Move {0} up", category.name))
                        }
                        IconButton(enabled = index < categories.lastIndex, onClick = { move(category.id, 1) }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, strings.text("Move {0} down", category.name))
                        }
                        IconButton(
                            onClick = {
                                name = category.name
                                editing = category
                            },
                        ) { Icon(Icons.Outlined.Edit, strings.text("Rename {0}", category.name)) }
                        IconButton(onClick = { deleting = category }) {
                            Icon(Icons.Outlined.Delete, strings.text("Delete {0}", category.name))
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        CategoryNameDialog(
            title = strings.text("New category"),
            value = name,
            onValueChange = { name = it },
            onDismiss = { creating = false },
            onConfirm = {
                onCreate(name.trim())
                creating = false
            },
        )
    }
    editing?.let { category ->
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
    deleting?.let { category ->
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
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.cancel) } },
        )
    }
}

@Composable
internal fun CategoryNameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(strings.text("Category name")) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = onConfirm) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun ToggleSetting(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked, onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ChoiceSetting(
    title: String,
    summary: String? = null,
    selectedLabel: String,
    choices: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    summary?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(selectedLabel, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        onSelected(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(value.roundToInt().toString(), color = MaterialTheme.colorScheme.primary)
            }
            Slider(value, onChange, valueRange = range, steps = ((range.endInclusive - range.start).roundToInt() - 1).coerceAtLeast(0))
        }
    }
}

@Composable
private fun SettingDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

@Composable
private fun AdminQuotaField(label: String, value: String, onValueChange: (String) -> Unit) {
    val strings = LocalShinsouStrings.current
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 12 && candidate.all(Char::isDigit)) onValueChange(candidate)
        },
        label = { Text(strings.text(label)) },
        singleLine = true,
        modifier = Modifier.width(150.dp),
    )
}

private fun String.requirePositiveLong(): Long =
    toLongOrNull()?.takeIf { it > 0 } ?: error("invalid_quota_value")

private fun String.requirePositiveInt(): Int =
    toIntOrNull()?.takeIf { it > 0 } ?: error("invalid_quota_value")

private fun Long.requireIntRange(): Int =
    takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: error("invalid_quota_value")

private fun formatSyncByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GiB"
    bytes >= MEBIBYTE -> "${bytes / MEBIBYTE} MiB"
    bytes >= KIBIBYTE -> "${bytes / KIBIBYTE} KiB"
    else -> "$bytes B"
}

private const val KIBIBYTE = 1024L
private const val MEBIBYTE = 1024L * KIBIBYTE

private fun SettingsSection.displayName(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): String = when (this) {
    SettingsSection.General -> strings.text("General")
    SettingsSection.Appearance -> strings.text("Appearance")
    SettingsSection.Library -> strings.library
    SettingsSection.Reader -> strings.text("Reader")
    SettingsSection.Downloads -> strings.downloads
    SettingsSection.Tracking -> strings.text("Tracking")
    SettingsSection.Sync -> strings.text("Sync")
    SettingsSection.Browse -> strings.browse
    SettingsSection.Security -> strings.text("Security")
    SettingsSection.Advanced -> strings.text("Advanced")
}

private fun Enum<*>.pretty(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun Set<String>.withToggle(value: String, enabled: Boolean): Set<String> =
    if (enabled) this + value else this - value
