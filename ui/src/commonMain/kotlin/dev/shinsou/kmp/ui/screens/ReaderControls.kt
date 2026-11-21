package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SettingsEdit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.shouldShowReaderVolumeKeySetting
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerNavigationBarsPadding
import kotlinx.coroutines.flow.filter

// R4-4 (docs/architecture/0006): moved out of ReaderScreen.kt without changing it.

/** The gradient top bar: close, title, and the web / favourite / translation / chapter list / settings actions. */
@Composable
private fun BoxScope.ReaderTopBar(
    manga: Manga,
    chapter: Chapter,
    minimal: Boolean,
    inLibrary: Boolean,
    onClose: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onFavorite: () -> Unit,
    onChapterList: () -> Unit,
    onSettings: () -> Unit,
    onTranslation: (() -> Unit)?,
    onTranslationToggle: (() -> Unit)?,
    translationActive: Boolean,
) {
    val strings = LocalShinsouStrings.current
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)),
            )
            // The page stays edge to edge, the controls do not: on iOS the host lays
            // Compose out over the whole screen, so the Dynamic Island (or a landscape
            // notch) would cover them. On Android the reader already reserved the status
            // bar, which this then leaves as it is.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.CLOSE_READER), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    manga.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chapter.name,
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!minimal) {
                onOpenWeb?.let { openWeb ->
                    IconButton(onClick = openWeb) {
                        Icon(Icons.Outlined.OpenInNew, strings.text(StringKey.OPEN_IN_BROWSER), tint = Color.White)
                    }
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (inLibrary) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (inLibrary) strings.unfavorite else strings.favorite,
                        tint = if (inLibrary) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
            onTranslation?.let { openTranslation ->
                if (onTranslationToggle == null) {
                    IconButton(onClick = openTranslation) {
                        Icon(Icons.Outlined.Translate, strings.text(StringKey.TRANSLATION), tint = Color.White)
                    }
                } else {
                    TranslationToggleButton(translationActive, onTranslationToggle, openTranslation)
                }
            }
            IconButton(onClick = onChapterList) {
                Icon(Icons.Outlined.MenuBook, strings.text(StringKey.CHAPTER_LIST), tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, strings.readerSettings, tint = Color.White)
            }
        }
    }
}

/** The gradient bottom bar: page slider and previous / next chapter. [sliderDragValue] lives in the caller. */
@Composable
private fun BoxScope.ReaderPageBar(
    page: Int,
    pageCount: Int,
    sliderMax: Float,
    /** Read when used: the slider's callbacks see the latest drag, not the one of the last composition. */
    sliderDragValue: () -> Float?,
    onSliderDragValue: (Float?) -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPageChange: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))),
            )
            .padding(horizontal = 18.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${page + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = (sliderDragValue() ?: page.toFloat()).coerceIn(0f, sliderMax),
                onValueChange = { value ->
                    val previous = readerSliderPage(sliderDragValue() ?: page.toFloat(), pageCount)
                    onSliderDragValue(value)
                    val target = readerSliderPage(value, pageCount)
                    if (target != null && target != previous) onPageChange(target)
                },
                onValueChangeFinished = { onSliderDragValue(null) },
                enabled = pageCount > 1,
                valueRange = 0f..sliderMax,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text("$pageCount", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
                Icon(Icons.Outlined.ArrowBack, null)
                Spacer(Modifier.width(4.dp))
                Text(strings.previousChapter)
            }
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    "${page + 1} / $pageCount",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            TextButton(onClick = onNextChapter, enabled = hasNextChapter) {
                Text(strings.nextChapter)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ArrowForward, null)
            }
        }
    }
}

@Composable
internal fun ReaderControls(
    visible: Boolean,
    manga: Manga,
    chapter: Chapter,
    page: Int,
    pageCount: Int,
    showPageControls: Boolean,
    minimal: Boolean = false,
    novelToolbar: Boolean = false,
    inLibrary: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onClose: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onPageChange: (Int) -> Unit,
    onFavorite: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterList: () -> Unit,
    onSettings: () -> Unit,
    onTranslation: (() -> Unit)? = null,
    /** Turns the chapter's translation on or off; [onTranslation] then opens the panel on a long press. */
    onTranslationToggle: (() -> Unit)? = null,
    translationActive: Boolean = false,
) {
    // The thumb follows the finger while dragging; the reader position catches up through
    // onPageChange instead of snapping the thumb back to the page the pager has reached so far.
    var sliderDragValue by remember(chapter.id, visible) { mutableStateOf<Float?>(null) }
    val sliderMax = (pageCount - 1).coerceAtLeast(1).toFloat()
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            ReaderTopBar(manga, chapter, minimal, inLibrary, onClose, onOpenWeb, onFavorite, onChapterList, onSettings,
                onTranslation, onTranslationToggle, translationActive)
            if (showPageControls) ReaderPageBar(
                page, pageCount, sliderMax, { sliderDragValue }, { sliderDragValue = it }, hasPreviousChapter, hasNextChapter,
                onPageChange, onPreviousChapter, onNextChapter,
            )
            if (novelToolbar) NovelReaderProgressControls(
                page = page,
                pageCount = pageCount,
                hasPreviousChapter = hasPreviousChapter,
                hasNextChapter = hasNextChapter,
                onPageChange = onPageChange,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** The progress row: current page, slider and page count. [sliderDragValue] lives in the caller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelProgressSliderRow(
    safePage: Int,
    safePageCount: Int,
    sliderEnabled: Boolean,
    /** Read when used: the slider's callbacks see the latest drag, not the one of the last composition. */
    sliderDragValue: () -> Float?,
    onSliderDragValue: (Float?) -> Unit,
    sliderInteractionSource: MutableInteractionSource,
    onPageChange: (Int) -> Unit,
) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledThumbColor = Color.White.copy(alpha = 0.38f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${safePage + 1}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(28.dp),
        )
        Slider(
            value = sliderDragValue() ?: safePage.toFloat(),
            onValueChange = { value ->
                val previous = readerSliderPage(sliderDragValue() ?: safePage.toFloat(), safePageCount)
                onSliderDragValue(value)
                val target = readerSliderPage(value, safePageCount)
                if (target != null && target != previous) onPageChange(target)
            },
            onValueChangeFinished = { onSliderDragValue(null) },
            valueRange = 0f..(safePageCount - 1).coerceAtLeast(1).toFloat(),
            enabled = sliderEnabled,
            colors = sliderColors,
            interactionSource = sliderInteractionSource,
            thumb = {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(
                            if (sliderEnabled) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.38f),
                            CircleShape,
                        ),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(5.dp),
                    enabled = sliderEnabled,
                    colors = sliderColors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                )
            },
            modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 8.dp),
        )
        Text(
            "$safePageCount",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(28.dp),
        )
    }
}

/** The previous chapter / page position / next chapter row. */
@Composable
private fun NovelChapterNavRow(
    safePage: Int,
    safePageCount: Int,
    interactionEnabled: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TextButton(
                onClick = onPreviousChapter,
                enabled = interactionEnabled && hasPreviousChapter,
            ) {
                Icon(Icons.Outlined.ArrowBack, null)
                Spacer(Modifier.width(4.dp))
                Text(strings.previousChapter, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    "${safePage + 1} / $safePageCount",
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(
                onClick = onNextChapter,
                enabled = interactionEnabled && hasNextChapter,
            ) {
                Text(strings.nextChapter, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ArrowForward, null)
            }
        }
    }
}

/**
 * Novel progress chrome mirrors the manga reader: page scrubbing stays within the current chapter,
 * while the labelled edge actions always change chapters. The caller places this over the reader
 * surface, so showing or hiding controls never changes pagination or the visible text geometry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderProgressControls(
    page: Int,
    pageCount: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPageChange: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
) {
    val safePageCount = pageCount.coerceAtLeast(1)
    val safePage = page.coerceIn(0, safePageCount - 1)
    val sliderEnabled = interactionEnabled && safePageCount > 1
    val sliderInteractionSource = remember { MutableInteractionSource() }
    // Follow the finger while dragging; the reader reports intermediate pages while it seeks.
    var sliderDragValue by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.18f to Color.Black.copy(alpha = 0.86f),
                        1f to Color.Black.copy(alpha = 0.96f),
                    ),
                ),
            )
            .readerNavigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        NovelProgressSliderRow(
            safePage, safePageCount, sliderEnabled, { sliderDragValue }, { sliderDragValue = it }, sliderInteractionSource, onPageChange,
        )
        NovelChapterNavRow(
            safePage, safePageCount, interactionEnabled, hasPreviousChapter, hasNextChapter, onPreviousChapter, onNextChapter,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    settings: ReaderSettings,
    textContent: Boolean = false,
    onChange: (SettingsEdit<ReaderSettings>) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(strings.readerSettings, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(strings.text(StringKey.READING_MODE), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val modes = if (textContent) {
                        listOf(
                            ReadingMode.CONTINUOUS_VERTICAL to strings.text(StringKey.SCROLL_DOWN),
                            ReadingMode.PAGER_LTR to strings.text(StringKey.TURN_LEFT),
                            ReadingMode.PAGER_RTL to strings.text(StringKey.TURN_RIGHT),
                        )
                    } else {
                        listOf(
                            ReadingMode.PAGER_LTR to strings.text(StringKey.LEFT_TO_RIGHT),
                            ReadingMode.PAGER_RTL to strings.text(StringKey.RIGHT_TO_LEFT),
                            ReadingMode.PAGER_VERTICAL to strings.text(StringKey.VERTICAL_PAGING),
                            ReadingMode.WEBTOON to strings.text(StringKey.WEBTOON),
                        )
                    }
                    modes.forEach { (mode, label) ->
                        item(key = mode.name) {
                            FilterChip(
                                selected = if (textContent && mode == ReadingMode.CONTINUOUS_VERTICAL) {
                                    isNovelContinuousMode(settings.readingMode)
                                } else {
                                    settings.readingMode == mode ||
                                        (mode == ReadingMode.WEBTOON &&
                                            settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL)
                                },
                                onClick = { onChange { current -> current.copy(readingMode = mode) } },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        when (mode) {
                                            ReadingMode.PAGER_LTR, ReadingMode.PAGER_RTL -> Icons.Outlined.SwapHoriz
                                            ReadingMode.PAGER_VERTICAL -> Icons.Outlined.SwapVert
                                            else -> Icons.Outlined.MenuBook
                                        },
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (textContent) {
                item {
                    var fontSize by remember(settings.novelFontSizeSp) {
                        mutableFloatStateOf(settings.novelFontSizeSp)
                    }
                    var lineHeight by remember(settings.novelLineHeightMultiplier) {
                        mutableFloatStateOf(settings.novelLineHeightMultiplier)
                    }
                    var readingWidth by remember(settings.novelMaxWidthDp) {
                        mutableFloatStateOf(settings.novelMaxWidthDp)
                    }
                    Text(strings.text(StringKey.NOVEL_TYPOGRAPHY), style = MaterialTheme.typography.titleMedium)
                    Text(strings.text(StringKey.FONT_SIZE_X, fontSize.toInt()))
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        onValueChangeFinished = {
                            if (fontSize != settings.novelFontSizeSp) {
                                onChange { current -> current.copy(novelFontSizeSp = fontSize) }
                            }
                        },
                        valueRange = 14f..30f,
                    )
                    Text(
                        strings.text(
                            StringKey.LINE_HEIGHT_X,
                            (lineHeight * 100f).toInt(),
                        ),
                    )
                    Slider(
                        value = lineHeight,
                        onValueChange = { lineHeight = it },
                        onValueChangeFinished = {
                            if (lineHeight != settings.novelLineHeightMultiplier) {
                                onChange { current -> current.copy(novelLineHeightMultiplier = lineHeight) }
                            }
                        },
                        valueRange = 1.25f..2.2f,
                    )
                    Text(strings.text(StringKey.READING_WIDTH_X, readingWidth.toInt()))
                    Slider(
                        value = readingWidth,
                        onValueChange = { readingWidth = it },
                        onValueChangeFinished = {
                            if (readingWidth != settings.novelMaxWidthDp) {
                                onChange { current -> current.copy(novelMaxWidthDp = readingWidth) }
                            }
                        },
                        valueRange = 520f..920f,
                    )
                }
            } else {
                item { ReaderToggle(strings.text(StringKey.SHOW_PAGE_NUMBER), settings.showPageNumber) { onChange { current -> current.copy(showPageNumber = it) } } }
            }
            item { ReaderToggle(strings.text(StringKey.KEEP_SCREEN_ON), settings.keepScreenOn) { onChange { current -> current.copy(keepScreenOn = it) } } }
            item { ReaderToggle(strings.text(StringKey.FULLSCREEN), settings.fullscreen) { onChange { current -> current.copy(fullscreen = it) } } }
            if (!textContent) {
                item { ReaderToggle(strings.text(StringKey.DOUBLE_TAP_TO_ZOOM), settings.doubleTapToZoom) { onChange { current -> current.copy(doubleTapToZoom = it) } } }
            }
            item { ReaderToggle(strings.text(StringKey.SKIP_READ_CHAPTERS), settings.skipReadChapters) { onChange { current -> current.copy(skipReadChapters = it) } } }
            item { ReaderToggle(strings.text(StringKey.SKIP_FILTERED_CHAPTERS), settings.skipFilteredChapters) { onChange { current -> current.copy(skipFilteredChapters = it) } } }
            item { ReaderToggle(strings.text(StringKey.SKIP_DUPLICATE_CHAPTERS), settings.skipDuplicateChapters) { onChange { current -> current.copy(skipDuplicateChapters = it) } } }
            if (shouldShowReaderVolumeKeySetting()) {
                item { ReaderToggle(strings.text(StringKey.VOLUME_KEYS), settings.volumeKeys) { onChange { current -> current.copy(volumeKeys = it) } } }
            }
            item {
                ReaderToggle(strings.text(StringKey.PAGE_TURN_ANIMATION), settings.animatePageTransitions) {
                    onChange { current -> current.copy(animatePageTransitions = it) }
                }
            }
            if (!textContent) {
                item { ReaderToggle(strings.text(StringKey.SPLIT_TALL_IMAGES), settings.splitTallImages) { onChange { current -> current.copy(splitTallImages = it) } } }
            }
            if (!textContent &&
                (settings.readingMode == ReadingMode.WEBTOON ||
                    settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL)
            ) {
                item {
                    Text(strings.text(StringKey.WEBTOON_SIDE_PADDING_X, settings.webtoonSidePadding.toInt()))
                    Slider(
                        value = settings.webtoonSidePadding.toFloat(),
                        onValueChange = { onChange { current -> current.copy(webtoonSidePadding = it.toDouble()) } },
                        valueRange = 0f..25f,
                    )
                }
            }
            if (!textContent) item {
                Text(strings.text(StringKey.COLOR_FILTER), style = MaterialTheme.typography.titleMedium)
                ReaderToggle(strings.text(StringKey.ENABLE_FILTER), settings.colorFilter.enabled) {
                    onChange { current -> current.copy(colorFilter = current.colorFilter.copy(enabled = it)) }
                }
                Text(strings.text(StringKey.BRIGHTNESS))
                Slider(
                    value = settings.colorFilter.brightness,
                    onValueChange = {
                        onChange { current -> current.copy(colorFilter = current.colorFilter.copy(enabled = true, brightness = it)) }
                    },
                    valueRange = -1f..1f,
                )
                ReaderToggle(strings.text(StringKey.GRAYSCALE), settings.colorFilter.grayscale) {
                    onChange { current -> current.copy(colorFilter = current.colorFilter.copy(enabled = true, grayscale = it)) }
                }
                ReaderToggle(strings.text(StringKey.INVERT_COLORS), settings.colorFilter.inverted) {
                    onChange { current -> current.copy(colorFilter = current.colorFilter.copy(enabled = true, inverted = it)) }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(strings.done) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ReaderToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}
