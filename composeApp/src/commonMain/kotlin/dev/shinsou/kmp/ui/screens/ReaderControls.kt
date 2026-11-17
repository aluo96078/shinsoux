package dev.shinsou.kmp.ui.screens

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
import dev.shinsou.kmp.ui.shouldShowReaderVolumeKeySetting
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerNavigationBarsPadding
import kotlinx.coroutines.flow.filter

// R4-4 (docs/architecture/0006): moved out of ReaderScreen.kt without changing it.

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
    val strings = LocalShinsouStrings.current
    // The thumb follows the finger while dragging; the reader position catches up through
    // onPageChange instead of snapping the thumb back to the page the pager has reached so far.
    var sliderDragValue by remember(chapter.id, visible) { mutableStateOf<Float?>(null) }
    val sliderMax = (pageCount - 1).coerceAtLeast(1).toFloat()
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
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
                        Icon(Icons.Outlined.ArrowBack, strings.text("Close reader"), tint = Color.White)
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
                                Icon(Icons.Outlined.OpenInNew, strings.text("Open in browser"), tint = Color.White)
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
                                Icon(Icons.Outlined.Translate, strings.text("Translation"), tint = Color.White)
                            }
                        } else {
                            TranslationToggleButton(translationActive, onTranslationToggle, openTranslation)
                        }
                    }
                    IconButton(onClick = onChapterList) {
                        Icon(Icons.Outlined.MenuBook, strings.text("Chapter list"), tint = Color.White)
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, strings.readerSettings, tint = Color.White)
                    }
                }
            }
            if (showPageControls) Column(
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
                        value = (sliderDragValue ?: page.toFloat()).coerceIn(0f, sliderMax),
                        onValueChange = { value ->
                            val previous = readerSliderPage(sliderDragValue ?: page.toFloat(), pageCount)
                            sliderDragValue = value
                            val target = readerSliderPage(value, pageCount)
                            if (target != null && target != previous) onPageChange(target)
                        },
                        onValueChangeFinished = { sliderDragValue = null },
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
    val strings = LocalShinsouStrings.current
    val safePageCount = pageCount.coerceAtLeast(1)
    val safePage = page.coerceIn(0, safePageCount - 1)
    val sliderEnabled = interactionEnabled && safePageCount > 1
    val sliderInteractionSource = remember { MutableInteractionSource() }
    // Follow the finger while dragging; the reader reports intermediate pages while it seeks.
    var sliderDragValue by remember { mutableStateOf<Float?>(null) }
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledThumbColor = Color.White.copy(alpha = 0.38f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
    )
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
                value = sliderDragValue ?: safePage.toFloat(),
                onValueChange = { value ->
                    val previous = readerSliderPage(sliderDragValue ?: safePage.toFloat(), safePageCount)
                    sliderDragValue = value
                    val target = readerSliderPage(value, safePageCount)
                    if (target != null && target != previous) onPageChange(target)
                },
                onValueChangeFinished = { sliderDragValue = null },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    settings: ReaderSettings,
    textContent: Boolean = false,
    onChange: (ReaderSettings) -> Unit,
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
                Text(strings.text("Reading mode"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val modes = if (textContent) {
                        listOf(
                            ReadingMode.CONTINUOUS_VERTICAL to strings.text("Scroll down"),
                            ReadingMode.PAGER_LTR to strings.text("Turn left"),
                            ReadingMode.PAGER_RTL to strings.text("Turn right"),
                        )
                    } else {
                        listOf(
                            ReadingMode.PAGER_LTR to strings.text("Left to right"),
                            ReadingMode.PAGER_RTL to strings.text("Right to left"),
                            ReadingMode.PAGER_VERTICAL to strings.text("Vertical paging"),
                            ReadingMode.WEBTOON to strings.text("Webtoon"),
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
                                onClick = { onChange(settings.copy(readingMode = mode)) },
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
                    Text(strings.text("Novel typography"), style = MaterialTheme.typography.titleMedium)
                    Text(strings.text("Font size · {0}", fontSize.toInt()))
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        onValueChangeFinished = {
                            if (fontSize != settings.novelFontSizeSp) {
                                onChange(settings.copy(novelFontSizeSp = fontSize))
                            }
                        },
                        valueRange = 14f..30f,
                    )
                    Text(
                        strings.text(
                            "Line height · {0}%",
                            (lineHeight * 100f).toInt(),
                        ),
                    )
                    Slider(
                        value = lineHeight,
                        onValueChange = { lineHeight = it },
                        onValueChangeFinished = {
                            if (lineHeight != settings.novelLineHeightMultiplier) {
                                onChange(settings.copy(novelLineHeightMultiplier = lineHeight))
                            }
                        },
                        valueRange = 1.25f..2.2f,
                    )
                    Text(strings.text("Reading width · {0}", readingWidth.toInt()))
                    Slider(
                        value = readingWidth,
                        onValueChange = { readingWidth = it },
                        onValueChangeFinished = {
                            if (readingWidth != settings.novelMaxWidthDp) {
                                onChange(settings.copy(novelMaxWidthDp = readingWidth))
                            }
                        },
                        valueRange = 520f..920f,
                    )
                }
            } else {
                item { ReaderToggle(strings.text("Show page number"), settings.showPageNumber) { onChange(settings.copy(showPageNumber = it)) } }
            }
            item { ReaderToggle(strings.text("Keep screen on"), settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) } }
            item { ReaderToggle(strings.text("Fullscreen"), settings.fullscreen) { onChange(settings.copy(fullscreen = it)) } }
            if (!textContent) {
                item { ReaderToggle(strings.text("Double-tap to zoom"), settings.doubleTapToZoom) { onChange(settings.copy(doubleTapToZoom = it)) } }
            }
            item { ReaderToggle(strings.text("Skip read chapters"), settings.skipReadChapters) { onChange(settings.copy(skipReadChapters = it)) } }
            item { ReaderToggle(strings.text("Skip filtered chapters"), settings.skipFilteredChapters) { onChange(settings.copy(skipFilteredChapters = it)) } }
            item { ReaderToggle(strings.text("Skip duplicate chapters"), settings.skipDuplicateChapters) { onChange(settings.copy(skipDuplicateChapters = it)) } }
            if (shouldShowReaderVolumeKeySetting()) {
                item { ReaderToggle(strings.text("Volume keys"), settings.volumeKeys) { onChange(settings.copy(volumeKeys = it)) } }
            }
            item {
                ReaderToggle(strings.text("Page turn animation"), settings.animatePageTransitions) {
                    onChange(settings.copy(animatePageTransitions = it))
                }
            }
            if (!textContent) {
                item { ReaderToggle(strings.text("Split tall images"), settings.splitTallImages) { onChange(settings.copy(splitTallImages = it)) } }
            }
            if (!textContent &&
                (settings.readingMode == ReadingMode.WEBTOON ||
                    settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL)
            ) {
                item {
                    Text(strings.text("Webtoon side padding · {0}%", settings.webtoonSidePadding.toInt()))
                    Slider(
                        value = settings.webtoonSidePadding.toFloat(),
                        onValueChange = { onChange(settings.copy(webtoonSidePadding = it.toDouble())) },
                        valueRange = 0f..25f,
                    )
                }
            }
            if (!textContent) item {
                Text(strings.text("Color filter"), style = MaterialTheme.typography.titleMedium)
                ReaderToggle(strings.text("Enable filter"), settings.colorFilter.enabled) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = it)))
                }
                Text(strings.text("Brightness"))
                Slider(
                    value = settings.colorFilter.brightness,
                    onValueChange = {
                        onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, brightness = it)))
                    },
                    valueRange = -1f..1f,
                )
                ReaderToggle(strings.text("Grayscale"), settings.colorFilter.grayscale) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, grayscale = it)))
                }
                ReaderToggle(strings.text("Invert colors"), settings.colorFilter.inverted) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, inverted = it)))
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
