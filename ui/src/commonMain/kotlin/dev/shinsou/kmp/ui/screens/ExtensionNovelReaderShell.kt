package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SettingsEdit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.ui.ContentFeatures
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.ui.ReaderProgressPosition
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ReaderVolumeKeyHandlerSlot
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.ui.effectiveReaderVolumeKeysEnabled
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import dev.shinsou.kmp.ui.readerMayCrossChapterBoundary
import dev.shinsou.kmp.ui.readerStatusBarsPadding
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

internal data class PendingExtensionRepresentationSelection(
    val selection: ExtensionUnitSelectionV2,
    val representationIds: List<String>,
    val openReader: Boolean,
)

private class LatestExtensionReaderProgress {
    var position: ReaderProgressPosition? = null
}

internal enum class ExtensionChapterFilter {
    ALL,
    UNREAD,
    READ,
    BOOKMARKED,
    DOWNLOADED,
}

internal fun extensionContinueUnitId(
    unitIds: List<String>,
    completedUnitIds: Set<String>,
    resumeUnitId: String?,
): String? = resumeUnitId
    ?.takeIf(unitIds::contains)
    ?: unitIds.firstOrNull { it !in completedUnitIds }
    ?: unitIds.lastOrNull()

/** How long leaving the reader, or moving to another chapter, waits for its progress to be written. */
internal const val READER_PROGRESS_FLUSH_WAIT_MS: Long = 2_000L

/**
 * Waits until the progress observed so far is on disk, but never longer than
 * [READER_PROGRESS_FLUSH_WAIT_MS]: a write held up (a large sync applying, a slow disk) must not
 * keep the user in the reader behind "Loading". The progress stays queued in the app-wide lane,
 * which writes it once it can.
 */
internal suspend fun awaitProgressFlush(flush: suspend () -> Unit) {
    if (withTimeoutOrNull(READER_PROGRESS_FLUSH_WAIT_MS) { flush() } == null) {
        Log.warn("Reader") { "Reading progress was still being written when the reader moved on" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionNovelReaderShell(
    title: String,
    currentUnitTitle: String,
    sourceName: String?,
    /** The work's language as its source gives it, blank when it does not say. */
    workLanguage: String,
    units: List<ExtensionUnitSelectionV2>,
    activeUnitIndex: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenUnit: (ExtensionUnitSelectionV2) -> Unit,
    session: TypedReaderContentSession,
    features: ContentFeatures,
    copyText: (label: String, text: String) -> Boolean,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (SettingsEdit<ReaderSettings>) -> Unit,
    onCompleted: () -> Unit,
    onReaderProgress: (ReaderProgressPosition) -> Unit,
    onReaderProgressFlushed: suspend () -> Unit,
    volumeKeyRouter: ReaderVolumeKeyRouter?,
    systemBackRequest: Long,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val hasCurrent = activeUnitIndex in units.indices
    val textContent = session.content.representation is ContentRepresentation.PlainText ||
        session.content.representation is ContentRepresentation.EpubSpine
    val epubReader = session.content.representation is ContentRepresentation.EpubSpine
    val imageReader = session.content.representation is ContentRepresentation.ImageSequence
    val initialPage = session.initialVisualPageIndex
        ?: session.content.navigation.indexOf(session.content.initialLocator)
        ?: 0
    var controlsVisible by remember(session) { mutableStateOf(false) }
    var settingsVisible by remember(session) { mutableStateOf(false) }
    var chapterListVisible by remember(session) { mutableStateOf(false) }
    val translationAvailable = remember(session, features) { readerTranslationAvailable(session, features) }
    // Not keyed by the chapter: a counter that starts again would look like a new press.
    var translationRequest by remember { mutableStateOf(0L) }
    var translationToggle by remember { mutableStateOf(0L) }
    var translationActive by remember(session) { mutableStateOf(false) }
    var progressTransitionInFlight by remember(session) { mutableStateOf(false) }
    var closeRequested by remember(session) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(session) { mutableStateOf(systemBackRequest) }
    var currentPage by remember(session) { mutableStateOf(initialPage) }
    var pageCount by remember(session) {
        mutableStateOf(
            when {
                imageReader -> session.content.navigation.itemCount.coerceAtLeast(1)
                else -> session.initialVisualPageCount ?: initialPage.plus(1).coerceAtLeast(1)
            },
        )
    }
    var pageCountMeasured by remember(
        session,
        readerSettings.readingMode,
        readerSettings.novelFontSizeSp,
        readerSettings.novelLineHeightMultiplier,
        readerSettings.novelMaxWidthDp,
    ) {
        mutableStateOf(imageReader)
    }
    var pageRequest by remember(session) {
        mutableStateOf(UnifiedReaderPageRequest(initialPage))
    }
    var readerNavigationAction by remember(session) { mutableStateOf<ReaderTapAction?>(null) }
    var readerNavigationRequestKey by remember(session) { mutableStateOf(0L) }
    val focusRequester = remember { FocusRequester() }
    val heldNavigationKeys = remember(session) { mutableSetOf<Key>() }
    val latestProgress = remember(session) {
        LatestExtensionReaderProgress().apply {
            position = ReaderProgressPosition(
                session.content.initialLocator,
                initialPage.coerceAtLeast(0),
                session.initialVisualPageCount,
            )
        }
    }
    val currentProgressCallback by rememberUpdatedState(onReaderProgress)

    fun commitLatestProgress() {
        latestProgress.position?.let(currentProgressCallback)
    }

    fun closeReader() {
        if (progressTransitionInFlight) {
            // Moving to another chapter: Back closes once its progress is written instead of
            // being dropped (the reader could not be left while a chapter loaded).
            closeRequested = true
            return
        }
        progressTransitionInFlight = true
        commitLatestProgress()
        // Keep the surface mounted until the app-owned local progress lane confirms that the
        // final locator/page pair is durable. This also covers fast Escape/back close sequences.
        scope.launch {
            try {
                awaitProgressFlush(onReaderProgressFlushed)
                onBack()
            } finally {
                closeRequested = false
                progressTransitionInFlight = false
            }
        }
    }

    fun openUnitAfterProgressFlush(selection: ExtensionUnitSelectionV2) {
        if (progressTransitionInFlight || busy) return
        progressTransitionInFlight = true
        commitLatestProgress()
        scope.launch {
            try {
                awaitProgressFlush(onReaderProgressFlushed)
                if (closeRequested) onBack() else onOpenUnit(selection)
            } finally {
                closeRequested = false
                progressTransitionInFlight = false
            }
        }
    }

    fun handleReaderBack() {
        when {
            chapterListVisible -> chapterListVisible = false
            settingsVisible -> settingsVisible = false
            else -> closeReader()
        }
    }

    fun requestPage(index: Int) {
        when {
            index !in 0 until pageCount &&
                !readerMayCrossChapterBoundary(
                    proseReader = textContent,
                    pageCountMeasured = pageCountMeasured,
                    interactionBlocked = busy || progressTransitionInFlight,
                ) -> Unit
            index < 0 && hasCurrent && activeUnitIndex > 0 && !busy -> {
                openUnitAfterProgressFlush(units[activeUnitIndex - 1])
            }
            index >= pageCount && hasCurrent && activeUnitIndex + 1 < units.size && !busy -> {
                openUnitAfterProgressFlush(units[activeUnitIndex + 1])
            }
            index in 0 until pageCount -> {
                currentPage = index
                pageRequest = pageRequest.request(index)
            }
        }
    }

    fun handleTap(action: ReaderTapAction) {
        if (progressTransitionInFlight || busy) return
        val imagePage = pageRequest.navigationIndex(currentPage)
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> requestPage(
                (if (imageReader) imagePage else currentPage) - 1,
            )
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
            ReaderTapAction.NEXT_PAGE -> requestPage(
                (if (imageReader) imagePage else currentPage) + 1,
            )
        }
    }

    fun handleNavigationBoundary(action: ReaderTapAction) {
        if (progressTransitionInFlight || busy) return
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (hasCurrent && activeUnitIndex > 0) {
                    openUnitAfterProgressFlush(units[activeUnitIndex - 1])
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (hasCurrent && activeUnitIndex + 1 < units.size) {
                    openUnitAfterProgressFlush(units[activeUnitIndex + 1])
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
        }
    }

    fun handleVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        val action = readerVolumeKeyAction(
            event = event,
            readerOpen = true,
            volumeKeysEnabled = effectiveReaderVolumeKeysEnabled(readerSettings.volumeKeys),
        ) ?: return false
        if (progressTransitionInFlight || busy) return false
        if (imageReader) {
            val target = imageSequenceNavigationTarget(
                currentIndex = pageRequest.navigationIndex(currentPage),
                pageCount = pageCount,
                action = action,
            )
            if (target != null) requestPage(target) else handleNavigationBoundary(action)
            return true
        }
        readerNavigationAction = action
        readerNavigationRequestKey++
        return true
    }

    val volumeKeyHandlerSlot = remember(session) { ReaderVolumeKeyHandlerSlot() }
    volumeKeyHandlerSlot.update(::handleVolumeKey)
    DisposableEffect(volumeKeyRouter, volumeKeyHandlerSlot) {
        val registration = volumeKeyRouter?.register(volumeKeyHandlerSlot::dispatch)
        onDispose { registration?.unregister() }
    }

    LaunchedEffect(session, settingsVisible) {
        heldNavigationKeys.clear()
        if (!settingsVisible) focusRequester.requestFocus()
    }

    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == 0L || systemBackRequest == handledSystemBackRequest) {
            return@LaunchedEffect
        }
        handledSystemBackRequest = systemBackRequest
        handleReaderBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF211D1B))
            // Android is edge-to-edge; SwiftUI already supplies the iOS safe-area frame.
            .readerStatusBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    heldNavigationKeys.remove(event.key)
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!readerPreviewKeyIsHostOwned(
                        key = event.key,
                        embeddedSurfaceOwnsPaging = epubReader,
                    )
                ) {
                    return@onPreviewKeyEvent false
                }
                val handledNavigationKey = when (event.key) {
                    Key.VolumeDown, Key.VolumeUp,
                    -> effectiveReaderVolumeKeysEnabled(readerSettings.volumeKeys)
                    Key.DirectionDown, Key.NumPadDirectionDown,
                    Key.PageDown, Key.NumPadPageDown,
                    Key.Spacebar,
                    Key.DirectionUp, Key.NumPadDirectionUp,
                    Key.PageUp, Key.NumPadPageUp,
                    -> true
                    Key.DirectionRight, Key.NumPadDirectionRight,
                    Key.DirectionLeft, Key.NumPadDirectionLeft,
                    -> !isNovelContinuousMode(readerSettings.readingMode)
                    Key.Escape, Key.Back, Key.NavigatePrevious,
                    -> true
                    else -> false
                }
                if (handledNavigationKey && !heldNavigationKeys.add(event.key)) {
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.VolumeDown -> handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN)
                    Key.VolumeUp -> handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_UP)
                    Key.DirectionDown, Key.NumPadDirectionDown,
                    Key.PageDown, Key.NumPadPageDown,
                    Key.Spacebar,
                    -> {
                        handleTap(ReaderTapAction.NEXT_PAGE)
                        true
                    }
                    Key.DirectionUp, Key.NumPadDirectionUp,
                    Key.PageUp, Key.NumPadPageUp,
                    -> {
                        handleTap(ReaderTapAction.PREVIOUS_PAGE)
                        true
                    }
                    Key.DirectionRight, Key.NumPadDirectionRight -> {
                        if (isNovelContinuousMode(readerSettings.readingMode)) return@onPreviewKeyEvent false
                        handleTap(
                            if (readerSettings.readingMode == dev.shinsou.kmp.domain.model.ReadingMode.PAGER_RTL) {
                                ReaderTapAction.PREVIOUS_PAGE
                            } else {
                                ReaderTapAction.NEXT_PAGE
                            },
                        )
                        true
                    }
                    Key.DirectionLeft, Key.NumPadDirectionLeft -> {
                        if (isNovelContinuousMode(readerSettings.readingMode)) return@onPreviewKeyEvent false
                        handleTap(
                            if (readerSettings.readingMode == dev.shinsou.kmp.domain.model.ReadingMode.PAGER_RTL) {
                                ReaderTapAction.NEXT_PAGE
                            } else {
                                ReaderTapAction.PREVIOUS_PAGE
                            },
                        )
                        true
                    }
                    Key.Escape, Key.Back, Key.NavigatePrevious -> {
                        handleReaderBack()
                        true
                    }
                    else -> false
                }
            },
    ) {
        UnifiedContentReader(
            session = session,
            features = features,
            copyText = copyText,
            settings = readerSettings,
            requestedPageIndex = pageRequest.targetIndex,
            pageRequestSerial = pageRequest.serial,
            navigationAction = readerNavigationAction,
            navigationRequestKey = readerNavigationRequestKey,
            readerControlsVisible = controlsVisible,
            translationRequestSerial = translationRequest,
            translationToggleSerial = translationToggle,
            onTranslationActiveChanged = { translationActive = it },
            workLanguage = workLanguage,
            translationLabels = remember(title, currentUnitTitle, sourceName) {
                dev.shinsou.kmp.translation.TranslationLabels(title, currentUnitTitle, sourceName)
            },
            onPageIndexChanged = { index, count ->
                pageCountMeasured = true
                currentPage = index
                pageRequest = pageRequest.observe(index)
                pageCount = count.coerceAtLeast(1)
                latestProgress.position = latestProgress.position?.copy(
                    pageIndex = index.coerceAtLeast(0),
                    pageCount = count.coerceAtLeast(1),
                )
            },
            onPageCountInvalidated = { pageCountMeasured = false },
            onNavigationBoundary = ::handleNavigationBoundary,
            onLocatorChanged = { locator, pageIndex, documentPageCount ->
                val position = ReaderProgressPosition(
                    locator,
                    pageIndex.coerceAtLeast(0),
                    documentPageCount,
                )
                latestProgress.position = position
                onReaderProgress(position)
                val navigation = session.content.navigation
                if (navigation.indexOf(locator) == navigation.itemCount - 1 &&
                    (locator.progression ?: 0.0) >= 0.995
                ) {
                    onCompleted()
                }
            },
            onReaderTap = ::handleTap,
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.88f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::closeReader) {
                        Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.CLOSE_READER))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            currentUnitTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (translationAvailable) {
                        TranslationToggleButton(
                            active = translationActive,
                            onToggle = { translationToggle++ },
                            onOpen = { translationRequest++ },
                            tint = androidx.compose.material3.LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { chapterListVisible = true }, enabled = units.isNotEmpty()) {
                        Icon(Icons.Outlined.MenuBook, strings.chapters)
                    }
                    IconButton(onClick = { settingsVisible = true }) {
                        Icon(Icons.Outlined.Settings, strings.settings)
                    }
                }
            }
            // Keep plain-text progress controls as an overlay. EPUB retains its own semantic
            // search/speech/note overlay inside UnifiedContentReader.
            if (!epubReader) {
                NovelReaderProgressControls(
                    page = currentPage,
                    pageCount = pageCount,
                    hasPreviousChapter = hasCurrent && activeUnitIndex > 0,
                    hasNextChapter = hasCurrent && activeUnitIndex + 1 < units.size,
                    onPageChange = ::requestPage,
                    onPreviousChapter = {
                        units.getOrNull(activeUnitIndex - 1)?.let(::openUnitAfterProgressFlush)
                    },
                    onNextChapter = {
                        units.getOrNull(activeUnitIndex + 1)?.let(::openUnitAfterProgressFlush)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    interactionEnabled = !busy && !progressTransitionInFlight,
                )
            }
        }

        if (progressTransitionInFlight || busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text(strings.text(StringKey.LOADING), fontWeight = FontWeight.Medium)
                    }
                }
                // The controls cannot be shown while a chapter loads: without this a touch screen
                // had no way out of the reader until the chapter arrived.
                IconButton(
                    onClick = ::closeReader,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                ) {
                    Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.CLOSE_READER), tint = Color.White)
                }
            }
        }
    }

    if (settingsVisible) {
        ReaderSettingsSheet(
            settings = readerSettings,
            textContent = textContent,
            onChange = onReaderSettingsChange,
            onDismiss = { settingsVisible = false },
        )
    }
    if (chapterListVisible) {
        ModalBottomSheet(onDismissRequest = { chapterListVisible = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    strings.chapters,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(units, key = { it.unit.remoteId }) { selection ->
                        val selected = selection.unit.remoteId ==
                            units.getOrNull(activeUnitIndex)?.unit?.remoteId
                        TextButton(
                            onClick = {
                                chapterListVisible = false
                                if (!selected) openUnitAfterProgressFlush(selection)
                            },
                            enabled = !progressTransitionInFlight && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selection.unit.title,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asked before any chapter is queued: which language the work is in, which to translate into,
 * and which service will do it (and so where the text goes and whether it costs). Starts from
 * the work's own translation settings when it has them.
 */
@Composable
internal fun PrefetchSetupDialog(
    translation: dev.shinsou.kmp.translation.ReaderTranslation,
    workKey: String,
    chapterCount: Int,
    onConfirm: (from: String?, to: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val systemLanguage = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
    var loaded by remember(workKey) { mutableStateOf(false) }
    // Null is "detect from each chapter", the choice for a work that was never set up.
    var from by remember(workKey) { mutableStateOf<String?>(null) }
    var to by remember(workKey) { mutableStateOf(dev.shinsou.kmp.translation.ReaderTranslation.targetLanguage(systemLanguage)) }
    var preferences by remember { mutableStateOf<dev.shinsou.kmp.translation.TranslationPreferences?>(null) }
    LaunchedEffect(workKey) {
        translation.settings(workKey)?.let { saved ->
            from = saved.from
            to = saved.to
        }
        preferences = runCatchingCancellable { translation.preferences() }
            .onFailure { Log.warn("Translation", it) { "Loading translation preferences failed" } }
            .getOrNull()
        loaded = true
    }
    val provider = preferences?.provider
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(StringKey.TRANSLATE_CHAPTERS_AHEAD)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("prefetch-setup"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(strings.text(StringKey.X_CHAPTERS_WILL_BE_TRANSLATED_AND_KEPT, chapterCount))
                Text(strings.text(StringKey.ORIGINAL_LANGUAGE), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = from == null, onClick = { from = null }, label = { Text(strings.text(StringKey.DETECT)) })
                    translation.languages.forEach { language ->
                        FilterChip(selected = from == language.tag, onClick = { from = language.tag }, label = { Text(language.name) })
                    }
                }
                Text(strings.text(StringKey.TRANSLATE_INTO), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    translation.languages.forEach { language ->
                        FilterChip(selected = to == language.tag, onClick = { to = language.tag }, label = { Text(language.name) })
                    }
                }
                Text(strings.text(StringKey.TRANSLATION_SERVICE), style = MaterialTheme.typography.titleSmall)
                Text(
                    when (provider) {
                        null -> "…"
                        dev.shinsou.kmp.translation.TranslationProviderKind.OFFLINE,
                        dev.shinsou.kmp.translation.TranslationProviderKind.SYSTEM ->
                            strings.text(StringKey.X_FREE_AND_ON_THIS_DEVICE_NOTHING, provider.label(strings))
                        else -> strings.text(
                            StringKey.X_THE_CHAPTERS_TEXT_IS_SENT_TO,
                            provider.label(strings),
                            preferences?.charactersUsedToday ?: 0,
                            preferences?.dailyCharacterLimit?.takeIf { it > 0 }?.toString() ?: strings.text(StringKey.NO_LIMIT),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    strings.text(StringKey.THE_SERVICE_AND_THE_DAILY_LIMIT_ARE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (from != null && from == to) {
                    Text(strings.text(StringKey.THE_TWO_LANGUAGES_ARE_THE_SAME), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(from, to) },
                enabled = loaded && from != to,
                modifier = Modifier.testTag("prefetch-start"),
            ) { Text(strings.text(StringKey.START_TRANSLATING)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/** One chapter's place in the translation queue, under its title. */
@Composable
internal fun PrefetchChapterStatus(entry: dev.shinsou.kmp.translation.PrefetchEntry) {
    val strings = LocalShinsouStrings.current
    val (text, error) = when (entry.state) {
        dev.shinsou.kmp.translation.PrefetchState.QUEUED -> strings.text(StringKey.WAITING_TO_BE_TRANSLATED) to false
        dev.shinsou.kmp.translation.PrefetchState.RUNNING ->
            (if (entry.total > 0) strings.text(StringKey.TRANSLATING_X_OF_X_LINES, entry.done, entry.total) else strings.text(StringKey.TRANSLATING)) to false
        dev.shinsou.kmp.translation.PrefetchState.DONE -> return
        dev.shinsou.kmp.translation.PrefetchState.FAILED -> strings.text(StringKey.NOT_TRANSLATED_X, prefetchFailureText(strings, entry.failure)) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun prefetchFailureText(strings: ShinsouStrings, failure: dev.shinsou.kmp.translation.TranslationFailure?): String = when (failure) {
    null -> strings.text(StringKey.THE_CHAPTER_COULD_NOT_BE_FETCHED_OR)
    dev.shinsou.kmp.translation.TranslationFailure.MODEL_MISSING ->
        strings.text(StringKey.THE_TRANSLATION_MODELS_ARE_NOT_DOWNLOADED_OPEN)
    dev.shinsou.kmp.translation.TranslationFailure.UNSUPPORTED_PAIR -> strings.text(StringKey.THESE_TWO_LANGUAGES_ARE_NOT_SUPPORTED)
    dev.shinsou.kmp.translation.TranslationFailure.DEVICE_UNSUPPORTED -> strings.text(StringKey.OFFLINE_TRANSLATION_IS_NOT_AVAILABLE_ON_THIS_2)
    dev.shinsou.kmp.translation.TranslationFailure.LOW_MEMORY -> strings.text(StringKey.NOT_ENOUGH_FREE_MEMORY_RIGHT_NOW)
    dev.shinsou.kmp.translation.TranslationFailure.INVALID_KEY -> strings.text(StringKey.THE_API_KEY_IS_MISSING_OR_WAS_2)
    dev.shinsou.kmp.translation.TranslationFailure.QUOTA_EXCEEDED -> strings.text(StringKey.THE_QUOTA_OF_THE_API_KEY_IS)
    dev.shinsou.kmp.translation.TranslationFailure.RATE_LIMITED -> strings.text(StringKey.THE_TRANSLATION_SERVICE_ASKED_TO_SLOW_DOWN)
    dev.shinsou.kmp.translation.TranslationFailure.DAILY_LIMIT -> strings.text(StringKey.TODAY_S_LIMIT_FOR_PAID_TRANSLATION_IS_2)
    dev.shinsou.kmp.translation.TranslationFailure.PROVIDER_UNAVAILABLE -> strings.text(StringKey.THE_TRANSLATION_SERVICE_COULD_NOT_BE_REACHED_2)
    dev.shinsou.kmp.translation.TranslationFailure.ENGINE_FAILED -> strings.text(StringKey.THE_TRANSLATION_STOPPED_BEFORE_IT_FINISHED_2)
    dev.shinsou.kmp.translation.TranslationFailure.NOT_CONFIGURED -> strings.text(StringKey.THE_AI_MODEL_SERVICE_NEEDS_ITS_ADDRESS)
    dev.shinsou.kmp.translation.TranslationFailure.REFUSED -> strings.text(StringKey.THE_MODEL_DECLINED_TO_TRANSLATE_THIS_TEXT)
    dev.shinsou.kmp.translation.TranslationFailure.SYSTEM_LANGUAGE_MISSING ->
        strings.text(StringKey.THE_SYSTEM_S_LANGUAGES_ARE_NOT_DOWNLOADED)
}

/** The work's translation queue at a glance: how far it is, what stopped it, and a way out. */
@Composable
internal fun PrefetchProgress(
    entries: List<dev.shinsou.kmp.translation.PrefetchEntry>,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val done = entries.count { it.state == dev.shinsou.kmp.translation.PrefetchState.DONE }
    val failed = entries.filter { it.state == dev.shinsou.kmp.translation.PrefetchState.FAILED }
    val pending = entries.size - done - failed.size
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("prefetch-progress"),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pending > 0) strings.text(StringKey.TRANSLATING_AHEAD_X_OF_X_CHAPTERS, done, entries.size)
                    else strings.text(StringKey.TRANSLATED_AHEAD_X_OF_X_CHAPTERS, done, entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (pending > 0) {
                    TextButton(onClick = onCancel) { Text(strings.cancel) }
                } else {
                    TextButton(onClick = onClear) { Text(strings.text(StringKey.DISMISS)) }
                }
            }
            if (pending > 0) {
                LinearProgressIndicator(
                    progress = { ((done + failed.size).toFloat() / entries.size.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            failed.firstOrNull()?.let { first ->
                Text(
                    strings.text(StringKey.X_CHAPTERS_WERE_NOT_TRANSLATED_X, failed.size, prefetchFailureText(strings, first.failure)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
