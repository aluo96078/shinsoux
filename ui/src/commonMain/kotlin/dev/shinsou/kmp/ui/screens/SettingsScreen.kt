package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SettingsEdit
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
import androidx.compose.material.icons.outlined.Code
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
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.i18n.authoredOrExternal
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.shouldShowReaderVolumeKeySetting
import dev.shinsou.kmp.ui.components.HairlineDivider
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsSection(val icon: ImageVector) {
    General(Icons.Outlined.Settings),
    Appearance(Icons.Outlined.Palette),
    Library(Icons.Outlined.Book),
    Reader(Icons.Outlined.Brightness4),
    Downloads(Icons.Outlined.Download),
    Tracking(Icons.Outlined.Sync),
    Browse(Icons.Outlined.Extension),
    Security(Icons.Outlined.Security),
    Advanced(Icons.Outlined.Tune),
    Developer(Icons.Outlined.Code),
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    categories: List<Category>,
    securityCapabilities: PlatformSecurityCapabilities = PlatformSecurityCapabilities.Unavailable,
    wideLayout: Boolean,
    onBack: () -> Unit,
    onChange: (SettingsEdit<AppSettings>) -> Unit,
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
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) } },
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
    securityCapabilities: PlatformSecurityCapabilities,
    onBack: (() -> Unit)?,
    onChange: (SettingsEdit<AppSettings>) -> Unit,
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
                { IconButton(onClick = callback) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) } }
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
            SettingsSection.Browse -> BrowseSettingsPane(settings, onChange)
            SettingsSection.Security -> SecuritySettingsPane(
                settings = settings,
                capabilities = securityCapabilities,
                onChange = onChange,
                authenticate = authenticate,
            )
            SettingsSection.Advanced -> AdvancedSettingsPane(settings, onChange)
            SettingsSection.Developer -> DeveloperSettingsPane(settings, onChange)
        }
    }
}

@Composable
private fun GeneralSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    val languages = listOf(
        "system" to strings.text(StringKey.SYSTEM),
        "en" to strings.text(StringKey.ENGLISH),
        "zh-Hant" to strings.text(StringKey.TRADITIONAL_CHINESE_NAME),
        "zh-Hans" to strings.text(StringKey.SIMPLIFIED_CHINESE_NAME),
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
                title = strings.text(StringKey.LANGUAGE),
                summary = strings.text(StringKey.CHANGES_APPLY_IMMEDIATELY),
                selectedLabel = languages.firstOrNull { it.first == (settings.general.languagePreference ?: "system") }?.second ?: strings.text(StringKey.SYSTEM),
                choices = languages.map { it.second },
                onSelected = { label ->
                    val code = languages.first { it.second == label }.first
                    onChange { current -> current.copy(general = current.general.copy(languagePreference = code)) }
                },
            )
        }
        item {
            ChoiceSetting(
                title = strings.text(StringKey.STARTING_SCREEN),
                selectedLabel = strings.text(settings.general.defaultStartingScreen.labelKey()),
                choices = MainSection.entries.map { strings.text(it.labelKey()) },
                onSelected = { label ->
                    val section = MainSection.entries.firstOrNull { strings.text(it.labelKey()) == label }
                    if (section != null) {
                        onChange { current -> current.copy(general = current.general.copy(defaultStartingScreen = section)) }
                    }
                },
            )
        }
        item {
            ToggleSetting(
                strings.text(StringKey.CONFIRM_BEFORE_CLOSING),
                strings.text(StringKey.ASK_BEFORE_CLOSING_THE_DESKTOP_APPLICATION),
                settings.general.confirmBeforeClosing,
            ) { onChange { current -> current.copy(general = current.general.copy(confirmBeforeClosing = it)) } }
        }
        item {
            ChoiceSetting(
                strings.text(StringKey.DATE_FORMAT),
                selectedLabel = settings.general.dateFormat,
                choices = listOf("system", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd"),
            ) { onChange { current -> current.copy(general = current.general.copy(dateFormat = it)) } }
        }
        appUpdateSettings(settings, onChange)
    }
}

/** Checking GitHub for a newer version: the switches, and a check on demand. */
private fun androidx.compose.foundation.lazy.LazyListScope.appUpdateSettings(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    item {
        val strings = LocalShinsouStrings.current
        val updates = dev.shinsou.kmp.ui.LocalAppUpdates.current ?: return@item
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val values = settings.updates
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.text(StringKey.APP_UPDATES), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ToggleSetting(
                strings.text(StringKey.CHECK_FOR_UPDATES),
                strings.text(StringKey.LOOK_FOR_A_NEWER_VERSION_ON_GITHUB),
                values.checkForUpdates,
            ) { onChange { current -> current.copy(updates = current.updates.copy(checkForUpdates = it)) } }
            ToggleSetting(
                strings.text(StringKey.INCLUDE_BETA_VERSIONS),
                strings.text(StringKey.OFFER_THE_NEWEST_BETA_TOO_UNLESS_A),
                values.includeBeta,
                enabled = values.checkForUpdates,
            ) { onChange { current -> current.copy(updates = current.updates.copy(includeBeta = it)) } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val status = if (updates.checking) strings.text(StringKey.CHECKING) else when (val result = updates.result) {
                    is dev.shinsou.kmp.ui.UpdateCheckResult.Newer -> strings.text(StringKey.VERSION_X_IS_AVAILABLE, result.release.version.toString())
                    dev.shinsou.kmp.ui.UpdateCheckResult.UpToDate -> strings.text(StringKey.YOU_HAVE_THE_NEWEST_VERSION_X, updates.currentVersion)
                    dev.shinsou.kmp.ui.UpdateCheckResult.Failed -> strings.text(StringKey.THE_CHECK_FAILED_CHECK_THE_CONNECTION_AND)
                    null -> strings.text(StringKey.YOU_HAVE_X, updates.currentVersion)
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(
                    onClick = { scope.launch { updates.check(values, interactive = true) } },
                    enabled = !updates.checking,
                ) { Text(strings.text(StringKey.CHECK_NOW)) }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ChoiceSetting(
                strings.text(StringKey.THEME),
                selectedLabel = strings.text(settings.appearance.theme.labelKey()),
                choices = ThemeMode.entries.map { strings.text(it.labelKey()) },
            ) { selectedLabel ->
                val theme = ThemeMode.entries.firstOrNull { strings.text(it.labelKey()) == selectedLabel }
                if (theme != null) onChange { current -> current.copy(appearance = current.appearance.copy(theme = theme)) }
            }
        }
        item {
            ToggleSetting(strings.text(StringKey.AMOLED_BLACK), strings.text(StringKey.USE_PURE_BLACK_SURFACES_IN_DARK_MODE), settings.appearance.amoledDark) {
                onChange { current -> current.copy(appearance = current.appearance.copy(amoledDark = it)) }
            }
        }
        item {
            ToggleSetting(strings.text(StringKey.RELATIVE_TIMESTAMPS), strings.text(StringKey.SHOW_2H_AGO_INSTEAD_OF_A_CLOCK), settings.appearance.relativeTimestamps) {
                onChange { current -> current.copy(appearance = current.appearance.copy(relativeTimestamps = it)) }
            }
        }
    }
}

/** The library grid: layout and the portrait / landscape column counts. */
private fun androidx.compose.foundation.lazy.LazyListScope.libraryLayoutSettings(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    item {
        val strings = LocalShinsouStrings.current
        ChoiceSetting(
            strings.text(StringKey.LIBRARY_LAYOUT),
            selectedLabel = strings.text(settings.library.displayMode.labelKey()),
            choices = LibraryDisplayMode.entries.map { strings.text(it.labelKey()) },
        ) { label ->
            val mode = LibraryDisplayMode.entries.first { strings.text(it.labelKey()) == label }
            onChange { current -> current.copy(library = current.library.copy(displayMode = mode)) }
        }
    }
    item {
        val strings = LocalShinsouStrings.current
        SliderSetting(strings.text(StringKey.PORTRAIT_COLUMNS), settings.library.portraitColumns.toFloat(), 2f..8f, 1) {
            onChange { current -> current.copy(library = current.library.copy(portraitColumns = it.roundToInt())) }
        }
    }
    item {
        val strings = LocalShinsouStrings.current
        SliderSetting(strings.text(StringKey.LANDSCAPE_COLUMNS), settings.library.landscapeColumns.toFloat(), 3f..12f, 1) {
            onChange { current -> current.copy(library = current.library.copy(landscapeColumns = it.roundToInt())) }
        }
    }
}

/** Which categories take part in library updates and the Wi-Fi / charging restrictions. */
private fun androidx.compose.foundation.lazy.LazyListScope.libraryUpdateSettings(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    item {
        val strings = LocalShinsouStrings.current
        ChoiceSetting(
            title = strings.text(StringKey.CATEGORY_UPDATES),
            summary = strings.text(StringKey.CHOOSE_WHICH_CATEGORIES_PARTICIPATE_IN_LIBRARY_UPDATES),
            selectedLabel = when (settings.library.categoryUpdateBehaviour) {
                "selected" -> strings.text(StringKey.SELECTED_CATEGORIES_ONLY)
                "none" -> strings.text(StringKey.DISABLED)
                else -> strings.text(StringKey.ALL_CATEGORIES)
            },
            choices = listOf(strings.text(StringKey.ALL_CATEGORIES), strings.text(StringKey.SELECTED_CATEGORIES_ONLY), strings.text(StringKey.DISABLED)),
        ) { label ->
            val value = when (label) {
                strings.text(StringKey.SELECTED_CATEGORIES_ONLY) -> "selected"
                strings.text(StringKey.DISABLED) -> "none"
                else -> "all"
            }
            onChange { current -> current.copy(library = current.library.copy(categoryUpdateBehaviour = value)) }
        }
    }
    item {
        val strings = LocalShinsouStrings.current
        ToggleSetting(
            strings.text(StringKey.WI_FI_ONLY_UPDATES),
            strings.text(StringKey.REFRESH_LIBRARY_METADATA_ONLY_ON_WI_FI),
            "wifi_only" in settings.library.globalUpdateRestrictions,
        ) { enabled ->
            onChange { current ->
                val restrictions = current.library.globalUpdateRestrictions.withToggle("wifi_only", enabled)
                current.copy(library = current.library.copy(globalUpdateRestrictions = restrictions))
            }
        }
    }
    item {
        val strings = LocalShinsouStrings.current
        ToggleSetting(
            strings.text(StringKey.CHARGING_ONLY_UPDATES),
            strings.text(StringKey.REFRESH_THE_LIBRARY_ONLY_WHILE_THIS_DEVICE),
            "charging_only" in settings.library.globalUpdateRestrictions,
        ) { enabled ->
            onChange { current ->
                val restrictions = current.library.globalUpdateRestrictions.withToggle("charging_only", enabled)
                current.copy(library = current.library.copy(globalUpdateRestrictions = restrictions))
            }
        }
    }
}

@Composable
private fun LibrarySettingsPane(
    settings: AppSettings,
    categories: List<Category>,
    onChange: (SettingsEdit<AppSettings>) -> Unit,
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
                put(category.name.ifBlank { strings.text(StringKey.DEFAULT) }, category.id)
            }
            put(strings.text(StringKey.ALWAYS_ASK), ALWAYS_ASK_CATEGORY_ID)
        }
    }
    val selectedCategoryLabel = categoryChoices.entries
        .firstOrNull { it.value == settings.library.defaultCategoryId }
        ?.key
        ?: Category.Default.name
    SettingsList {
        libraryLayoutSettings(settings, onChange)
        item {
            ChoiceSetting(
                title = strings.text(StringKey.DEFAULT_CATEGORY),
                summary = if (settings.library.defaultCategoryId == ALWAYS_ASK_CATEGORY_ID) {
                    strings.text(StringKey.CHOOSE_ONE_OR_MORE_CATEGORIES_WHENEVER_A)
                } else {
                    strings.text(StringKey.NEWLY_ADDED_LIBRARY_TITLES_ARE_PLACED_HERE)
                },
                selectedLabel = selectedCategoryLabel,
                choices = categoryChoices.keys.toList(),
            ) { label ->
                onChange { current ->
                    current.copy(library = current.library.copy(defaultCategoryId = categoryChoices.getValue(label)))
                }
            }
        }
        libraryUpdateSettings(settings, onChange)
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
            ToggleSetting(strings.text(StringKey.DOWNLOAD_ONLY_MODE), strings.text(StringKey.HIDE_TITLES_WITHOUT_OFFLINE_CHAPTERS), settings.library.downloadOnly) {
                onChange { current -> current.copy(library = current.library.copy(downloadOnly = it)) }
            }
        }
        item {
            ToggleSetting(strings.text(StringKey.REFRESH_METADATA_AUTOMATICALLY), null, settings.library.autoRefreshMetadata) {
                onChange { current -> current.copy(library = current.library.copy(autoRefreshMetadata = it)) }
            }
        }
    }
}

@Composable
private fun ReaderSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ChoiceSetting(
                strings.text(StringKey.DEFAULT_READING_MODE),
                selectedLabel = strings.text(settings.reader.readingMode.labelKey()),
                choices = ReadingMode.entries.map { strings.text(it.labelKey()) },
            ) { label ->
                onChange { current -> current.copy(reader = current.reader.copy(readingMode = ReadingMode.entries.first { strings.text(it.labelKey()) == label })) }
            }
        }
        item {
            ChoiceSetting(
                strings.text(StringKey.ORIENTATION),
                selectedLabel = strings.text(settings.reader.orientation.labelKey()),
                choices = ReaderOrientation.entries.map { strings.text(it.labelKey()) },
            ) { label ->
                onChange { current -> current.copy(reader = current.reader.copy(orientation = ReaderOrientation.entries.first { strings.text(it.labelKey()) == label })) }
            }
        }
        item { ToggleSetting(strings.text(StringKey.DOUBLE_TAP_TO_ZOOM), null, settings.reader.doubleTapToZoom) { onChange { current -> current.copy(reader = current.reader.copy(doubleTapToZoom = it)) } } }
        item {
            ToggleSetting(
                strings.pageTurnAnimation,
                strings.pageTurnAnimationDescription,
                settings.reader.animatePageTransitions,
            ) { enabled ->
                onChange { current -> current.copy(reader = current.reader.copy(animatePageTransitions = enabled)) }
            }
        }
        item { ToggleSetting(strings.text(StringKey.SHOW_PAGE_NUMBER), null, settings.reader.showPageNumber) { onChange { current -> current.copy(reader = current.reader.copy(showPageNumber = it)) } } }
        item { ToggleSetting(strings.text(StringKey.KEEP_SCREEN_ON), null, settings.reader.keepScreenOn) { onChange { current -> current.copy(reader = current.reader.copy(keepScreenOn = it)) } } }
        item { ToggleSetting(strings.text(StringKey.SKIP_READ_CHAPTERS), null, settings.reader.skipReadChapters) { onChange { current -> current.copy(reader = current.reader.copy(skipReadChapters = it)) } } }
        item { ToggleSetting(strings.text(StringKey.SKIP_FILTERED_CHAPTERS), strings.text(StringKey.APPLY_MANGA_DETAIL_FILTERS_WHILE_MOVING_BETWEEN), settings.reader.skipFilteredChapters) { onChange { current -> current.copy(reader = current.reader.copy(skipFilteredChapters = it)) } } }
        item { ToggleSetting(strings.text(StringKey.SKIP_DUPLICATE_CHAPTERS), strings.text(StringKey.SKIP_ALTERNATE_COPIES_WITH_THE_SAME_CHAPTER), settings.reader.skipDuplicateChapters) { onChange { current -> current.copy(reader = current.reader.copy(skipDuplicateChapters = it)) } } }
        item { ToggleSetting(strings.text(StringKey.SPLIT_TALL_IMAGES), null, settings.reader.splitTallImages) { onChange { current -> current.copy(reader = current.reader.copy(splitTallImages = it)) } } }
        if (shouldShowReaderVolumeKeySetting()) {
            item { ToggleSetting(strings.text(StringKey.VOLUME_KEYS), strings.text(StringKey.USE_HARDWARE_VOLUME_KEYS_TO_TURN_PAGES), settings.reader.volumeKeys) { onChange { current -> current.copy(reader = current.reader.copy(volumeKeys = it)) } } }
        }
    }
}

@Composable
private fun DownloadSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text(StringKey.WI_FI_ONLY), null, settings.downloads.wifiOnly) { onChange { current -> current.copy(downloads = current.downloads.copy(wifiOnly = it)) } } }
        item { ToggleSetting(strings.text(StringKey.AUTO_DOWNLOAD_NEW_CHAPTERS), null, settings.downloads.autoDownloadNewChapters) { onChange { current -> current.copy(downloads = current.downloads.copy(autoDownloadNewChapters = it)) } } }
        item { ToggleSetting(strings.text(StringKey.DELETE_AFTER_READING), null, settings.downloads.deleteAfterReading) { onChange { current -> current.copy(downloads = current.downloads.copy(deleteAfterReading = it)) } } }
        item {
            ToggleSetting(
                strings.text(StringKey.ALSO_DELETE_WHEN_MARKED_READ),
                strings.text(StringKey.APPLY_AUTOMATIC_DELETION_TO_CHAPTERS_MARKED_READ),
                settings.downloads.removeAfterMarkedRead,
                enabled = settings.downloads.deleteAfterReading,
            ) {
                onChange { current -> current.copy(downloads = current.downloads.copy(removeAfterMarkedRead = it)) }
            }
        }
        item { SliderSetting(strings.text(StringKey.PARALLEL_CHAPTERS), settings.downloads.parallelDownloads.toFloat(), 1f..10f, 1) { onChange { current -> current.copy(downloads = current.downloads.copy(parallelDownloads = it.roundToInt())) } } }
        item { SliderSetting(strings.text(StringKey.PARALLEL_PAGES), settings.downloads.parallelPages.toFloat(), 1f..12f, 1) { onChange { current -> current.copy(downloads = current.downloads.copy(parallelPages = it.roundToInt())) } } }
        if (dev.shinsou.kmp.ui.platformSupportsSilentAudioKeepAlive) {
            item {
                ToggleSetting(
                    strings.text(StringKey.KEEP_DOWNLOADING_WITH_SILENT_AUDIO),
                    strings.text(StringKey.PLAYS_SILENT_AUDIO_WHILE_DOWNLOADING_IN_THE),
                    settings.downloads.keepAliveWithSilentAudio,
                ) {
                    onChange { current -> current.copy(downloads = current.downloads.copy(keepAliveWithSilentAudio = it)) }
                }
            }
        }
    }
}

@Composable
private fun TrackingSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text(StringKey.AUTO_SYNC_AFTER_READING), null, settings.tracking.autoSyncAfterRead) { onChange { current -> current.copy(tracking = current.tracking.copy(autoSyncAfterRead = it)) } } }
        item { ToggleSetting(strings.text(StringKey.UPDATE_CHAPTER_PROGRESS), null, settings.tracking.updateProgressAfterRead) { onChange { current -> current.copy(tracking = current.tracking.copy(updateProgressAfterRead = it)) } } }
        item {
            SettingDescription(strings.text(StringKey.TRACKER_ACCOUNTS_ARE_CONNECTED_FROM_MANGA_DETAIL))
        }
    }
}

@Composable
private fun BrowseSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item { ToggleSetting(strings.text(StringKey.CHECK_EXTENSION_UPDATES), null, settings.browse.checkExtensionUpdates) { onChange { current -> current.copy(browse = current.browse.copy(checkExtensionUpdates = it)) } } }
        item { ToggleSetting(strings.text(StringKey.SHOW_NSFW_SOURCES), strings.text(StringKey.HIDDEN_BY_DEFAULT), settings.browse.showNsfwSources) { onChange { current -> current.copy(browse = current.browse.copy(showNsfwSources = it)) } } }
        item {
            Text(strings.text(StringKey.ENABLED_LANGUAGES), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = settings.browse.enabledLanguages.isEmpty(),
                    onClick = {
                        onChange { current -> current.copy(browse = current.browse.copy(enabledLanguages = emptySet())) }
                    },
                    label = { Text(strings.all) },
                )
                listOf("en", "zh", "ja", "ko", "es").forEach { language ->
                    FilterChip(
                        selected = language in settings.browse.enabledLanguages,
                        onClick = {
                            // The chip's own state decides: on or off, whatever else changed meanwhile.
                            val enable = language !in settings.browse.enabledLanguages
                            onChange { current ->
                                val languages = current.browse.enabledLanguages
                                current.copy(browse = current.browse.copy(enabledLanguages = if (enable) languages + language else languages - language))
                            }
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
    onChange: (SettingsEdit<AppSettings>) -> Unit,
    authenticate: suspend (String) -> Boolean,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    SettingsList {
        item {
            ToggleSetting(
                title = strings.text(StringKey.APP_LOCK),
                summary = capabilities.appLock.unavailableReason?.let(strings::authoredOrExternal)
                    ?: strings.text(StringKey.REQUIRE_DEVICE_AUTHENTICATION),
                checked = settings.security.appLockEnabled && capabilities.appLock.available,
                enabled = capabilities.appLock.available,
            ) { enabled ->
                if (!enabled) {
                    onChange { current -> current.copy(security = current.security.copy(appLockEnabled = false)) }
                } else {
                    scope.launch {
                        if (authenticate(strings.text(StringKey.ENABLE_SHINSOU_X_APP_LOCK))) {
                            onChange { current -> current.copy(security = current.security.copy(appLockEnabled = true)) }
                        }
                    }
                }
            }
        }
        if (capabilities.appLock.available) {
            item {
                ChoiceSetting(
                    strings.text(StringKey.LOCK_AFTER),
                    selectedLabel = if (settings.security.lockAfterSeconds == 0) {
                        strings.text(StringKey.IMMEDIATELY)
                    } else {
                        "${settings.security.lockAfterSeconds}s"
                    },
                    choices = listOf(strings.text(StringKey.IMMEDIATELY), "10s", "30s", "60s", "300s"),
                ) { label ->
                    val seconds = if (label == strings.text(StringKey.IMMEDIATELY)) 0 else label.removeSuffix("s").toIntOrNull() ?: 0
                    onChange { current -> current.copy(security = current.security.copy(lockAfterSeconds = seconds)) }
                }
            }
        }
        item {
            ToggleSetting(
                title = strings.text(StringKey.SECURE_SCREEN),
                summary = capabilities.secureScreen.unavailableReason?.let(strings::authoredOrExternal)
                    ?: strings.text(StringKey.HIDE_CONTENT_IN_SYSTEM_PREVIEWS),
                checked = settings.security.secureScreen && capabilities.secureScreen.available,
                enabled = capabilities.secureScreen.available,
            ) {
                onChange { current -> current.copy(security = current.security.copy(secureScreen = it)) }
            }
        }
        item { ToggleSetting(strings.text(StringKey.INCOGNITO_MODE), strings.text(StringKey.DO_NOT_SAVE_HISTORY_OR_SYNC_TRACKERS), settings.security.incognitoMode) { onChange { current -> current.copy(security = current.security.copy(incognitoMode = it)) } } }
    }
}

@Composable
private fun DeveloperSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    val developer = settings.developer
    SettingsList {
        item {
            ToggleSetting(
                strings.text(StringKey.DEVELOPER_MODE),
                strings.text(StringKey.DIAGNOSTIC_OVERLAYS_FOR_TROUBLESHOOTING_STORED_ON_THIS),
                developer.enabled,
            ) { onChange { current -> current.copy(developer = current.developer.copy(enabled = it)) } }
        }
        item {
            ToggleSetting(
                strings.text(StringKey.SHOW_ELAPSED_TIME_UNDER_LOADING_INDICATORS),
                null,
                developer.showLoadingElapsed,
                enabled = developer.enabled,
            ) { onChange { current -> current.copy(developer = current.developer.copy(showLoadingElapsed = it)) } }
        }
        item {
            ToggleSetting(
                strings.text(StringKey.SHOW_URL_AND_ELAPSED_REQUEST_TIME_WHILE),
                null,
                developer.showImageRequests,
                enabled = developer.enabled,
            ) { onChange { current -> current.copy(developer = current.developer.copy(showImageRequests = it)) } }
        }
    }
}

@Composable
private fun AdvancedSettingsPane(settings: AppSettings, onChange: (SettingsEdit<AppSettings>) -> Unit) {
    val strings = LocalShinsouStrings.current
    SettingsList {
        item {
            ToggleSetting(
                strings.text(StringKey.DNS_OVER_HTTPS),
                strings.text(StringKey.SAVED_FOR_PLATFORMS_WITH_A_HOSTNAME_AWARE),
                settings.advanced.dnsOverHttps,
            ) { onChange { current -> current.copy(advanced = current.advanced.copy(dnsOverHttps = it)) } }
        }
        item {
            ToggleSetting(
                strings.text(StringKey.CLOUDFLARE_WORKER_PROXY),
                strings.text(StringKey.DEFAULT_FOR_SOURCES_SET_TO_FOLLOW_GLOBAL),
                settings.advanced.proxyEnabled,
            ) { enabled ->
                onChange { current -> current.copy(advanced = current.advanced.copy(proxyEnabled = enabled)) }
            }
        }
        item {
            OutlinedTextField(
                value = settings.advanced.proxyWorkerUrl,
                onValueChange = { onChange { current -> current.copy(advanced = current.advanced.copy(proxyWorkerUrl = it)) } },
                label = { Text(strings.text(StringKey.PROXY_URL)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = settings.advanced.proxyApiKey,
                onValueChange = { onChange { current -> current.copy(advanced = current.advanced.copy(proxyApiKey = it)) } },
                label = { Text(strings.text(StringKey.PROXY_API_KEY)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = settings.advanced.customUserAgent,
                onValueChange = { onChange { current -> current.copy(advanced = current.advanced.copy(customUserAgent = it)) } },
                label = { Text(strings.text(StringKey.CUSTOM_USER_AGENT)) },
                supportingText = {
                    Text(
                        strings.text(
                            StringKey.LEAVE_BLANK_TO_USE_THIS_DEVICE_S,
                        ),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SettingDescription(strings.text(StringKey.CREDENTIALS_AND_TRACKER_TOKENS_ARE_STORED_BY)) }
    }
}

/** One custom category: its name with move up / down, rename and delete actions. */
@Composable
private fun CategoryManageRow(
    category: Category,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (delta: Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(enabled = canMoveUp, onClick = { onMove(-1) }) {
            Icon(Icons.Outlined.KeyboardArrowUp, strings.text(StringKey.MOVE_X_UP, category.name))
        }
        IconButton(enabled = canMoveDown, onClick = { onMove(1) }) {
            Icon(Icons.Outlined.KeyboardArrowDown, strings.text(StringKey.MOVE_X_DOWN, category.name))
        }
        IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, strings.text(StringKey.RENAME_X, category.name)) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, strings.text(StringKey.DELETE_X, category.name))
        }
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
                    Text(strings.text(StringKey.MANAGE_CATEGORIES), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.text(StringKey.CREATE_RENAME_REORDER_OR_REMOVE_LIBRARY_CATEGORIES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        name = ""
                        creating = true
                    },
                ) { Icon(Icons.Outlined.Add, strings.text(StringKey.ADD_CATEGORY)) }
            }
            if (categories.isEmpty()) {
                Text(
                    strings.text(StringKey.NO_CUSTOM_CATEGORIES),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                categories.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    CategoryManageRow(
                        category = category,
                        canMoveUp = index > 0,
                        canMoveDown = index < categories.lastIndex,
                        onMove = { delta -> move(category.id, delta) },
                        onRename = {
                            name = category.name
                            editing = category
                        },
                        onDelete = { deleting = category },
                    )
                }
            }
        }
    }

    if (creating) {
        CategoryNameDialog(
            title = strings.text(StringKey.NEW_CATEGORY),
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
            title = strings.text(StringKey.RENAME_CATEGORY),
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
            title = { Text(strings.text(StringKey.DELETE_X_2, category.name)) },
            text = { Text(strings.text(StringKey.TITLES_IN_THIS_CATEGORY_WILL_BE_MOVED)) },
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
                label = { Text(strings.text(StringKey.CATEGORY_NAME)) },
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
internal fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
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
internal fun ChoiceSetting(
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
internal fun SettingDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
    )
}


private fun SettingsSection.displayName(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): String = when (this) {
    SettingsSection.General -> strings.text(StringKey.GENERAL)
    SettingsSection.Appearance -> strings.text(StringKey.APPEARANCE)
    SettingsSection.Library -> strings.library
    SettingsSection.Reader -> strings.text(StringKey.READER)
    SettingsSection.Downloads -> strings.downloads
    SettingsSection.Tracking -> strings.text(StringKey.TRACKING)
    SettingsSection.Browse -> strings.browse
    SettingsSection.Security -> strings.text(StringKey.SECURITY)
    SettingsSection.Advanced -> strings.text(StringKey.ADVANCED)
    SettingsSection.Developer -> strings.text(StringKey.DEVELOPER_MODE)
}

private fun MainSection.labelKey(): StringKey = when (this) {
    MainSection.LIBRARY -> StringKey.LIBRARY
    MainSection.UPDATES -> StringKey.UPDATES
    MainSection.HISTORY -> StringKey.HISTORY
    MainSection.BROWSE -> StringKey.BROWSE
    MainSection.MORE -> StringKey.MORE
}

private fun ThemeMode.labelKey(): StringKey = when (this) {
    ThemeMode.SYSTEM -> StringKey.SYSTEM
    ThemeMode.LIGHT -> StringKey.LIGHT
    ThemeMode.DARK -> StringKey.DARK
}

private fun LibraryDisplayMode.labelKey(): StringKey = when (this) {
    LibraryDisplayMode.COMPACT_GRID -> StringKey.COMPACT_GRID
    LibraryDisplayMode.COMFORTABLE_GRID -> StringKey.COMFORTABLE_GRID
    LibraryDisplayMode.LIST -> StringKey.LIST
    LibraryDisplayMode.COVER_ONLY_GRID -> StringKey.COVER_ONLY_GRID
}

private fun ReadingMode.labelKey(): StringKey = when (this) {
    ReadingMode.PAGER_LTR -> StringKey.PAGER_LTR
    ReadingMode.PAGER_RTL -> StringKey.PAGER_RTL
    ReadingMode.PAGER_VERTICAL -> StringKey.PAGER_VERTICAL
    ReadingMode.WEBTOON -> StringKey.WEBTOON
    ReadingMode.CONTINUOUS_VERTICAL -> StringKey.CONTINUOUS_VERTICAL
}

private fun ReaderOrientation.labelKey(): StringKey = when (this) {
    ReaderOrientation.FREE -> StringKey.FREE
    ReaderOrientation.PORTRAIT -> StringKey.PORTRAIT
    ReaderOrientation.LANDSCAPE -> StringKey.LANDSCAPE
    ReaderOrientation.SENSOR_PORTRAIT -> StringKey.SENSOR_PORTRAIT
    ReaderOrientation.SENSOR_LANDSCAPE -> StringKey.SENSOR_LANDSCAPE
}

private fun Set<String>.withToggle(value: String, enabled: Boolean): Set<String> =
    if (enabled) this + value else this - value
