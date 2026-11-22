package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.ReadingRange
import dev.shinsou.kmp.reader.EpubBrowserView
import dev.shinsou.kmp.reader.EpubBrowserConfiguration
import dev.shinsou.kmp.reader.EpubBrowserReadingMode
import dev.shinsou.kmp.reader.EpubRenderRequest
import dev.shinsou.kmp.reader.EpubResourceReadGate
import dev.shinsou.kmp.reader.EpubSemanticDocument
import dev.shinsou.kmp.domain.content.EpubSpineNavigation
import dev.shinsou.kmp.search.FullTextSearchHit
import dev.shinsou.kmp.tts.EpubSpeakableTextDocument
import dev.shinsou.kmp.tts.SpeechPlaybackStatus
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

// R4-4 (docs/architecture/0006): moved out of UnifiedContentReader.kt without changing it.

/** Full-package EPUB surface. Publisher resources stay exact and are served only by the private scheme. */
@Composable
internal fun EpubUnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    readerControlsVisible: Boolean,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onPageCountInvalidated: () -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val navigation = session.content.navigation as? EpubSpineNavigation
    if (navigation == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text(StringKey.THE_EPUB_NAVIGATION_GRAPH_IS_UNAVAILABLE))
        }
        return
    }
    val contentInitialLocator = session.content.initialLocator as? ReadingLocator.Epub ?: navigation.locatorAt(0)
    // A visual EPUB page is local to one spine document and must never be interpreted as the
    // spine index itself. The locator chooses the document and restores progression within it.
    val initialIndex = (navigation.indexOf(contentInitialLocator) ?: 0).coerceIn(0, navigation.itemCount - 1)
    val initialLocator = contentInitialLocator.takeIf { navigation.indexOf(it) == initialIndex }
        ?: navigation.locatorAt(initialIndex)
    var currentIndex by remember(session) { mutableStateOf(initialIndex) }
    var currentVisualPageIndex by remember(session) {
        mutableStateOf(session.initialVisualPageIndex ?: 0)
    }
    var currentVisualPageCount by remember(session) {
        mutableStateOf(
            session.initialVisualPageCount
                ?.takeIf { currentVisualPageIndex in 0 until it }
                ?: (currentVisualPageIndex + 1),
        )
    }
    var viewportPageCountMeasured by remember(session) { mutableStateOf(false) }
    val restoredDocumentProgression = remember(session, initialLocator) {
        val pageIndex = session.initialVisualPageIndex
        val pageCount = session.initialVisualPageCount
        when {
            pageIndex == null || pageCount == null -> initialLocator.progression
            pageCount <= 1 -> 0.0
            else -> pageIndex.toDouble()
                .div((pageCount - 1).toDouble())
                .coerceIn(0.0, 1.0)
        }
    }
    val restoredInitialLocator = remember(session, initialLocator, restoredDocumentProgression) {
        initialLocator.copy(progression = restoredDocumentProgression)
    }
    var pendingLocator by remember(session) { mutableStateOf(restoredInitialLocator) }
    var navigationRevision by remember(session) { mutableStateOf(0) }
    var currentLocator by remember(session) { mutableStateOf(restoredInitialLocator) }
    var request by remember(session) { mutableStateOf<EpubRenderRequest?>(null) }
    var semanticDocument by remember(session) { mutableStateOf<EpubSemanticDocument?>(null) }
    var selectionRequestKey by remember(session) { mutableStateOf(0L) }
    var selectedRange by remember(session) { mutableStateOf<ReadingRange?>(null) }
    var noteRange by remember(session) { mutableStateOf<ReadingRange?>(null) }
    var noteBody by remember(session) { mutableStateOf("") }
    var annotations by remember(session) { mutableStateOf<List<ContentAnnotation>>(emptyList()) }
    var searchOpen by remember(session) { mutableStateOf(false) }
    var query by remember(session) { mutableStateOf("") }
    var searchHits by remember(session) { mutableStateOf<List<FullTextSearchHit>>(emptyList()) }
    var searchIndexReady by remember(session) { mutableStateOf(false) }
    var loading by remember(session) { mutableStateOf(true) }
    var searchBusy by remember(session) { mutableStateOf(false) }
    var speaking by remember(session) { mutableStateOf(false) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browserConfiguration = remember(
        settings.readingMode,
        settings.animatePageTransitions,
        settings.novelFontSizeSp,
        settings.novelLineHeightMultiplier,
        settings.novelMaxWidthDp,
    ) {
        settings.toEpubBrowserConfiguration()
    }

    LaunchedEffect(session, features) {
        annotations = withContext(workDispatcher) {
            features.cleanupRevokedDerivedData(navigation.scope, session.access)
            try {
                features.annotations.list(navigation.scope, session.access)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Reader", logged) { "Loading the EPUB annotations failed; starting with none" }
                emptyList()
            }
        }
    }

    // Render metadata is published before XHTML semantics are extracted. The browser can paint
    // publisher CSS immediately; cancellable text/CFI derivation then fills search/TTS/notes.
    LaunchedEffect(session, features, currentIndex, navigationRevision) {
        loading = true
        semanticDocument = null
        selectedRange = null
        message = null
        val locator = pendingLocator.takeIf { navigation.indexOf(it) == currentIndex }
            ?: navigation.locatorAt(currentIndex)
        val loadedRequest = withContext(workDispatcher) {
            runCatchingCancellable {
                features.operations.display(session.access) {
                    features.epubRenderRequests.create(
                        navigation = navigation,
                        documentIndex = currentIndex,
                        initialLocator = locator,
                        resourceReadGate = EpubResourceReadGate { read ->
                            features.operations.display(session.access) { read() }
                        },
                    )
                }
            }
        }.getOrElse {
            Log.warn("Reader", it) { "Opening the EPUB render request failed" }
            request = null
            loading = false
            message = strings.text(StringKey.THE_EPUB_RESOURCES_COULD_NOT_BE_OPENED)
            return@LaunchedEffect
        }
        request = loadedRequest
        currentLocator = locator
        loading = false
        yield()
        val semantic = try {
            withContext(workDispatcher) {
                features.operations.displaySuspending(session.access) {
                    features.epubSemanticDocuments.createCancellable(
                        navigation = navigation,
                        documentIndex = currentIndex,
                        resourceReadGate = EpubResourceReadGate { read ->
                            features.operations.display(session.access) { read() }
                        },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Reader", logged) { "Extracting EPUB text semantics failed; keeping rendered pages only" }
            null
        }
        if (semantic != null && request === loadedRequest) {
            loadedRequest.installSemanticDocument(semantic)
            semanticDocument = semantic
            val quoted = loadedRequest.locatorForDocumentProgression(
                currentLocator.progression ?: loadedRequest.initialDocumentProgression,
            )
            currentLocator = quoted
            onLocatorChanged(
                quoted,
                currentVisualPageIndex,
                currentVisualPageCount.takeIf { viewportPageCountMeasured },
            )
        }
    }

    DisposableEffect(session, features) {
        onDispose { features.textToSpeech.stop() }
    }

    fun openLocator(locator: ReadingLocator.Epub) {
        val index = navigation.indexOf(locator) ?: return
        // A rights denial is an expected outcome, reported to the reader just below.
        val allowed = runCatching { features.operations.display(session.access) { true } }.getOrDefault(false)
        if (!allowed) {
            message = strings.text(StringKey.THIS_CONTENT_IS_NO_LONGER_AVAILABLE_UNDER)
            return
        }
        pendingLocator = locator
        currentIndex = index
        currentVisualPageIndex = 0
        currentVisualPageCount = 1
        // The new spine document has not published its viewport yet. Keeping this provisional
        // state unmeasured prevents an immediate second hardware press from escaping the chapter
        // merely because the placeholder visual page count is one.
        viewportPageCountMeasured = false
        onPageCountInvalidated()
        navigationRevision++
        onLocatorChanged(locator, 0, null)
    }

    fun handleBrowserAction(action: ReaderTapAction) {
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (currentIndex > 0) openLocator(navigation.locatorAt(currentIndex - 1))
                else onNavigationBoundary(action)
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (currentIndex + 1 < navigation.itemCount) {
                    openLocator(navigation.locatorAt(currentIndex + 1))
                } else {
                    onNavigationBoundary(action)
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> onReaderTap(action)
        }
    }

    fun startSpeech() {
        val semantic = semanticDocument ?: return
        scope.launch {
            speaking = true
            message = null
            try {
                val startBlock = semantic.blockFor(currentLocator) ?: semantic.blocks.first()
                val startIndex = semantic.blocks.indexOf(startBlock).coerceAtLeast(0)
                for (blockIndex in startIndex until semantic.blocks.size) {
                    currentCoroutineContext().ensureActive()
                    val block = semantic.blocks[blockIndex]
                    currentLocator = semantic.locatorForOffset(navigation, block.startUtf16)
                    onLocatorChanged(
                        currentLocator,
                        currentVisualPageIndex,
                        currentVisualPageCount.takeIf { viewportPageCountMeasured },
                    )
                    val result = features.textToSpeech.speak(
                        EpubSpeakableTextDocument(
                            navigation = navigation,
                            semanticDocument = semantic,
                            block = block,
                            access = session.access,
                        ),
                    )
                    if (result.finalStatus != SpeechPlaybackStatus.COMPLETED) break
                    yield()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Reader", logged) { "Reading the EPUB aloud failed" }
                message = strings.text(StringKey.SPEECH_STOPPED_BECAUSE_IT_IS_UNAVAILABLE_OR)
            } finally {
                speaking = false
            }
        }
    }

    fun requestNote() {
        val existing = selectedRange
        if (existing != null) {
            noteRange = existing
            noteBody = ""
        } else {
            selectionRequestKey++
        }
    }

    fun submitSearch() {
        val submitted = query.takeIf(String::isNotBlank) ?: return
        scope.launch {
            searchBusy = true
            message = null
            searchHits = try {
                val hits = withContext(workDispatcher) {
                    if (!searchIndexReady) {
                        indexEpubForReader(
                            features = features,
                            navigation = navigation,
                            access = session.access,
                        )
                    }
                    features.searchIndex.searchForegroundInScope(
                        query = submitted,
                        scope = navigation.scope,
                        limit = 100,
                    )
                }
                searchIndexReady = true
                hits
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Reader", logged) { "Full-text search in the EPUB failed" }
                message = strings.text(StringKey.FULL_TEXT_SEARCH_IS_UNAVAILABLE_FOR_THIS)
                emptyList()
            } finally {
                searchBusy = false
            }
        }
    }

    LaunchedEffect(session, currentVisualPageIndex, currentVisualPageCount, viewportPageCountMeasured) {
        if (viewportPageCountMeasured) {
            onPageIndexChanged(currentVisualPageIndex, currentVisualPageCount)
        }
    }

    LaunchedEffect(readerControlsVisible) {
        if (!readerControlsVisible) searchOpen = false
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        request?.let { activeRequest ->
            EpubBrowserView(
                request = activeRequest,
                modifier = Modifier.fillMaxSize(),
                configuration = browserConfiguration,
                navigationAction = navigationAction,
                navigationRequestKey = navigationRequestKey,
                selectionRequestKey = selectionRequestKey,
                onLocatorChanged = { locator ->
                    currentLocator = locator
                    selectedRange = null
                    val observedIndex = navigation.indexOf(locator)
                    observedIndex?.let { index ->
                        if (index != currentIndex) {
                            pendingLocator = locator
                            currentIndex = index
                            navigationRevision++
                        }
                    }
                },
                onViewportChanged = { locator, documentPageIndex, documentPageCount ->
                    currentLocator = locator
                    selectedRange = null
                    val observedIndex = navigation.indexOf(locator)
                    observedIndex?.let { index ->
                        if (index != currentIndex) {
                            pendingLocator = locator
                            currentIndex = index
                            navigationRevision++
                        }
                    }
                    currentVisualPageIndex = documentPageIndex
                    currentVisualPageCount = documentPageCount
                    viewportPageCountMeasured = true
                    // EPUB visual pages are local to one spine document. The semantic locator
                    // determines the document; the page pair restores the viewport inside it.
                    onPageIndexChanged(documentPageIndex, documentPageCount)
                    onLocatorChanged(locator, documentPageIndex, documentPageCount)
                },
                onSelectionChanged = { range ->
                    selectedRange = range
                    if (range == null) {
                        message = strings.text(StringKey.THE_NOTE_COULD_NOT_BE_SAVED_OR)
                    } else {
                        noteRange = range
                        noteBody = ""
                    }
                },
                onReaderTap = ::handleBrowserAction,
                onError = { message = strings.text(StringKey.THE_EPUB_RESOURCES_COULD_NOT_BE_OPENED) },
            )
        }
        if (loading) ShinsouLoadingIndicator()

        AnimatedVisibility(
            visible = readerControlsVisible && !searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                modifier = Modifier.readerNavigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { searchOpen = true }) {
                        Text(strings.text(StringKey.SEARCH_TEXT))
                    }
                    TextButton(
                        enabled = semanticDocument != null && features.speechCapability.available,
                        onClick = {
                            if (speaking) features.textToSpeech.stop() else startSpeech()
                        },
                    ) {
                        Text(strings.text(if (speaking) StringKey.STOP else StringKey.SPEAK_FROM_PARAGRAPH))
                    }
                    TextButton(enabled = semanticDocument != null, onClick = ::requestNote) {
                        Text(strings.text(StringKey.ADD_NOTE))
                    }
                }
            }
        }

        if (readerControlsVisible && searchOpen) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(18.dp),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(strings.text(StringKey.SEARCH_THIS_BOOK)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = query.isNotBlank() && !searchBusy,
                            onClick = ::submitSearch,
                        ) { Text(strings.text(StringKey.FIND)) }
                        TextButton(onClick = { searchOpen = false }) {
                            Text(strings.cancel)
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(searchHits) { _, hit ->
                            val locator = hit.locator as? ReadingLocator.Epub ?: return@itemsIndexed
                            TextButton(
                                onClick = {
                                    openLocator(locator)
                                    searchOpen = false
                                },
                            ) {
                                Text(hit.snippet)
                            }
                        }
                    }
                }
            }
        }

        message?.let { currentMessage ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(18.dp),
            ) {
                Text(currentMessage, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }

    noteRange?.let { range ->
        Surface(modifier = Modifier.fillMaxSize().padding(24.dp), tonalElevation = 8.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(StringKey.ADD_PARAGRAPH_NOTE), style = MaterialTheme.typography.titleLarge)
                Text(range.quote?.exact.orEmpty().takeUtf16Safe(240))
                OutlinedTextField(
                    value = noteBody,
                    onValueChange = { noteBody = it.take(16_384) },
                    label = { Text(strings.text(StringKey.NOTE)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = noteBody.isNotBlank(),
                        onClick = {
                            val submitted = noteBody
                            scope.launch {
                                val created = try {
                                    withContext(workDispatcher) {
                                        features.annotations.create(
                                            annotationId = randomAnnotationUuid(),
                                            kind = ContentAnnotationKind.NOTE,
                                            range = range,
                                            access = session.access,
                                            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                            body = submitted,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (logged: Throwable) {
                                    Log.warn("Reader", logged) { "Saving the EPUB note failed" }
                                    null
                                }
                                if (created == null) {
                                    message = strings.text(StringKey.THE_NOTE_COULD_NOT_BE_SAVED_OR)
                                } else {
                                    annotations = annotations + created
                                    noteRange = null
                                    selectedRange = null
                                }
                            }
                        },
                    ) { Text(strings.save) }
                    TextButton(onClick = { noteRange = null }) { Text(strings.cancel) }
                }
            }
        }
    }
}

private fun ReaderSettings.toEpubBrowserConfiguration(): EpubBrowserConfiguration =
    EpubBrowserConfiguration(
        readingMode = when (readingMode) {
            ReadingMode.PAGER_LTR -> EpubBrowserReadingMode.PAGED_LEFT_TO_RIGHT
            ReadingMode.PAGER_RTL -> EpubBrowserReadingMode.PAGED_RIGHT_TO_LEFT
            ReadingMode.PAGER_VERTICAL -> EpubBrowserReadingMode.PAGED_VERTICAL
            ReadingMode.WEBTOON,
            ReadingMode.CONTINUOUS_VERTICAL,
            -> EpubBrowserReadingMode.CONTINUOUS_VERTICAL
        },
        animatePageTransitions = animatePageTransitions,
        fontSizeSp = novelFontSizeSp.coerceIn(12f, 40f),
        lineHeightMultiplier = novelLineHeightMultiplier.coerceIn(1.1f, 3f),
        maxContentWidthDp = novelMaxWidthDp.coerceIn(420f, 1_200f),
    )

private fun String.takeUtf16Safe(maxChars: Int): String =
    substring(0, safeBoundaryAtOrBefore(this, minOf(length, maxChars)))

private fun randomAnnotationUuid(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { value -> (value.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
