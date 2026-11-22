package dev.shinsou.kmp.ui.translation

import dev.shinsou.kmp.ui.i18n.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.translation.ChineseScript
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationPreferences
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalAppLanguage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.components.SettingDescription
import dev.shinsou.kmp.ui.components.SettingsList
import dev.shinsou.kmp.ui.core.LocalMangaTranslation

/** The parts of the translation screen, each its own page. */
internal enum class TranslationPage(val icon: ImageVector) {
    Service(Icons.Outlined.Translate),
    Downloads(Icons.Outlined.CloudDownload),
    Manga(Icons.Outlined.Image),
    Stored(Icons.Outlined.Storage),
}

private class TranslationOverview(val service: String, val downloadedBytes: Long, val chapters: Int, val bytes: Long)

/**
 * More → Translate: a short list of what can be set, each opening its own page. Like Settings,
 * a wide window shows the list beside the open page, and a phone stacks them.
 */
@Composable
fun TranslationScreen(
    translation: ReaderTranslation?,
    wideLayout: Boolean,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    onBack: () -> Unit,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onBackAvailabilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val manga = LocalMangaTranslation.current
    val pages = TranslationPage.entries.filter { it != TranslationPage.Manga || manga != null }
    var selected by remember { mutableStateOf<TranslationPage?>(if (wideLayout) TranslationPage.Service else null) }
    var processedSystemBackRequest by remember { mutableStateOf(systemBackRequest) }
    val currentOnBackAvailabilityChanged by rememberUpdatedState(onBackAvailabilityChanged)
    var overview by remember { mutableStateOf<TranslationOverview?>(null) }

    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == processedSystemBackRequest) return@LaunchedEffect
        processedSystemBackRequest = systemBackRequest
        if (!wideLayout && selected != null) selected = null else onBack()
    }
    LaunchedEffect(wideLayout, selected) {
        currentOnBackAvailabilityChanged(!wideLayout && selected != null)
    }
    DisposableEffect(Unit) {
        onDispose { currentOnBackAvailabilityChanged(false) }
    }
    // Read again whenever a page closes, so the list shows what was just changed.
    LaunchedEffect(translation, selected) {
        if (translation == null) return@LaunchedEffect
        val bytes = translation.storedTranslationBytes()
        overview = TranslationOverview(
            service = translation.preferences().provider.label(strings),
            downloadedBytes = translation.installedModels().sumOf { it.downloadBytes } +
                (manga?.let { withContext(workDispatcher) { it.resources().sumOf { r -> r.diskBytes } } } ?: 0L),
            chapters = translation.storedChapters().size,
            bytes = bytes.first + bytes.second,
        )
    }

    if (translation == null) {
        Column(modifier.fillMaxSize()) {
            ScreenHeader(
                title = strings.text(StringKey.TRANSLATE),
                leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) } },
            )
            SettingsList { item { SettingDescription(strings.text(StringKey.TRANSLATION_IS_NOT_AVAILABLE_IN_THIS_VERSION)) } }
        }
        return
    }

    @Composable
    fun list(highlight: TranslationPage?, listModifier: Modifier) = TranslationPageList(
        translation = translation,
        pages = pages,
        selected = highlight,
        overview = overview,
        onSelect = { selected = it },
        onBack = onBack,
        modifier = listModifier,
    )

    @Composable
    fun detail(page: TranslationPage, back: (() -> Unit)?, detailModifier: Modifier) {
        Column(detailModifier) {
            ScreenHeader(
                title = page.title(strings),
                leading = back?.let { callback ->
                    { IconButton(onClick = callback) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) } }
                },
            )
            // each page loads its own snapshot: on a wide layout one opened later shows what changed since
            androidx.compose.runtime.key(page) { TranslationSettingsPane(translation, page) }
        }
    }

    if (wideLayout) {
        val page = selected?.takeIf { it in pages } ?: TranslationPage.Service
        Row(modifier.fillMaxSize()) {
            list(page, Modifier.width(280.dp).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            detail(page, null, Modifier.weight(1f))
        }
    } else {
        Box(modifier.fillMaxSize()) {
            list(null, Modifier.fillMaxSize())
            selected?.let { page ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = size.width * backGestureProgress.coerceIn(0f, 1f) },
                ) {
                    detail(page, { selected = null }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun TranslationPageList(
    translation: ReaderTranslation,
    pages: List<TranslationPage>,
    selected: TranslationPage?,
    overview: TranslationOverview?,
    onSelect: (TranslationPage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Column(modifier) {
        ScreenHeader(
            title = strings.text(StringKey.TRANSLATE),
            leading = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { AutoTranslateSettings(translation) }
            item {
                SettingDescription(
                    strings.text(StringKey.IN_THE_READER_TAP_THE_TRANSLATION_BUTTON),
                )
            }
            items(pages, key = { it.name }) { page ->
                Surface(
                    onClick = { onSelect(page) },
                    shape = RoundedCornerShape(13.dp),
                    color = if (page == selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().testTag("translation-page-${page.name.lowercase()}"),
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
                            Box(contentAlignment = Alignment.Center) { Icon(page.icon, null) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(page.title(strings), style = MaterialTheme.typography.titleMedium)
                            Text(
                                page.summary(strings, overview),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private fun TranslationPage.title(strings: ShinsouStrings): String = when (this) {
    TranslationPage.Service -> strings.text(StringKey.TRANSLATION_SERVICE)
    TranslationPage.Downloads -> strings.text(StringKey.DOWNLOADED_RESOURCES)
    TranslationPage.Manga -> strings.text(StringKey.MANGA_TRANSLATION)
    TranslationPage.Stored -> strings.text(StringKey.STORED_TRANSLATIONS)
}

private fun TranslationPage.summary(strings: ShinsouStrings, overview: TranslationOverview?): String = when (this) {
    TranslationPage.Service -> overview?.service ?: ""
    TranslationPage.Downloads -> when (val bytes = overview?.downloadedBytes) {
        null -> ""
        0L -> strings.text(StringKey.NOTHING_DOWNLOADED)
        else -> strings.text(StringKey.X_ON_THIS_DEVICE, storedSize(bytes))
    }
    TranslationPage.Manga -> strings.text(StringKey.DETECTION_WHILE_READING_AND_CPU_PERFORMANCE)
    TranslationPage.Stored -> overview?.let { strings.text(StringKey.X_CHAPTERS, it.chapters) + " · " + storedSize(it.bytes) } ?: ""
}

/** Whether works not in the app's language start translating as they open (docs/research/auto-translate.md), and which languages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoTranslateSettings(translation: ReaderTranslation) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val appLanguage = LocalAppLanguage.current
    var auto by remember { mutableStateOf<TranslationPreferences?>(null) }
    LaunchedEffect(translation) { auto = translation.preferences() }
    val current = auto ?: return
    val on = current.autoTranslate
    val paid = current.autoTranslatePaid

    fun set(nextOn: Boolean, nextPaid: Boolean) {
        auto = current.copy(autoTranslate = nextOn, autoTranslatePaid = nextPaid)
        scope.launch { translation.setAutoTranslate(nextOn, nextPaid) }
    }

    fun setLanguage(language: String, translated: Boolean) {
        if (ChineseScript.converts(language, ReaderTranslation.targetLanguage(appLanguage))) {
            auto = current.copy(autoTranslateOtherScript = translated)
            scope.launch { translation.setAutoTranslateOtherScript(translated) }
            return
        }
        val skipped = if (translated) current.autoTranslateSkipped - language else current.autoTranslateSkipped + language
        auto = current.copy(autoTranslateSkipped = skipped)
        scope.launch { translation.setAutoTranslateLanguage(language, translated) }
    }

    @Composable
    fun row(title: String, note: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        }
    }

    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            row(
                strings.text(StringKey.TRANSLATE_FOREIGN_LANGUAGE_WORKS_AUTOMATICALLY),
                strings.text(StringKey.A_NOVEL_OR_MANGA_IN_A_LANGUAGE),
                checked = on, enabled = true, tag = "auto-translate",
            ) { set(it, paid) }
            if (on) {
                val target = ReaderTranslation.targetLanguage(appLanguage)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.text(StringKey.LANGUAGES_TRANSLATED_AUTOMATICALLY), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (language in translation.languages.filter { it.tag != target }) {
                            // a reader of Chinese reads the other script too: off until turned on
                            val translated = if (ChineseScript.converts(language.tag, target)) current.autoTranslateOtherScript
                            else language.tag !in current.autoTranslateSkipped
                            FilterChip(
                                selected = translated,
                                onClick = { setLanguage(language.tag, !translated) },
                                label = { Text(language.name) },
                                modifier = Modifier.testTag("auto-translate-language-${language.tag}"),
                            )
                        }
                    }
                }
            }
            row(
                strings.text(StringKey.ALSO_WITH_PAID_SERVICES),
                strings.text(StringKey.OTHERWISE_DEEPL_GOOGLE_AZURE_AND_LANGUAGE_MODELS),
                checked = on && paid, enabled = on, tag = "auto-translate-paid",
            ) { set(on, it) }
        }
    }
}
