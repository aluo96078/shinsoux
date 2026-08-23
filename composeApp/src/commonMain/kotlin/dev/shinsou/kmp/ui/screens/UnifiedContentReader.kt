package dev.shinsou.kmp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.PlainTextNavigation
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.reader.EpubBrowserView
import dev.shinsou.kmp.reader.EpubBrowserConfiguration
import dev.shinsou.kmp.reader.EpubBrowserReadingMode
import dev.shinsou.kmp.reader.EpubRenderRequest
import dev.shinsou.kmp.reader.EpubResourceReadGate
import dev.shinsou.kmp.reader.EpubSemanticDocument
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.reader.ImageRenderPage
import dev.shinsou.kmp.reader.ImageSequenceNavigation
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.reader.toCoilTransformation
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.search.FullTextDocumentSegment
import dev.shinsou.kmp.search.FullTextSearchHit
import dev.shinsou.kmp.search.EpubSearchAnchor
import dev.shinsou.kmp.search.SearchableTextDocument
import dev.shinsou.kmp.search.fullTextDocumentSegmentsLazy
import dev.shinsou.kmp.tts.SpeakableTextDocument
import dev.shinsou.kmp.tts.EpubSpeakableTextDocument
import dev.shinsou.kmp.tts.SpeechPlaybackStatus
import dev.shinsou.kmp.ui.TypedReaderContentSession
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull

/** Plain-text M4 surface with local body search, paragraph notes, copy, and block-aware TTS. */
@Composable
internal fun UnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatureRuntime,
    copyText: (label: String, text: String) -> Boolean,
    settings: ReaderSettings,
    onLocatorChanged: (ReadingLocator) -> Unit = {},
    requestedPageIndex: Int? = null,
    pageRequestSerial: Long = 0L,
    navigationAction: ReaderTapAction? = null,
    navigationRequestKey: Long = 0L,
    readerControlsVisible: Boolean = false,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit = { _, _ -> },
    onReaderTap: (ReaderTapAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val representation = session.content.representation
    if (representation is ContentRepresentation.ImageSequence) {
        ImageSequenceUnifiedContentReader(
            session = session,
            features = features,
            onLocatorChanged = onLocatorChanged,
            modifier = modifier,
        )
        return
    }
    if (representation is ContentRepresentation.EpubSpine) {
        EpubUnifiedContentReader(
            session = session,
            features = features,
            settings = settings,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            readerControlsVisible = readerControlsVisible,
            onPageIndexChanged = onPageIndexChanged,
            onReaderTap = onReaderTap,
            onLocatorChanged = onLocatorChanged,
            modifier = modifier,
        )
        return
    }
    if (representation !is ContentRepresentation.PlainText) {
        UnsupportedUnifiedContentReader(representation, modifier)
        return
    }
    val canonicalText = requireNotNull(session.canonicalText)
    val access = session.access.withTextCharacters(canonicalText.length)
    val displayAllowed = remember(session, features) {
        runCatching {
            features.operations.display(access, canonicalText.length.toLong()) { true }
        }.getOrDefault(false)
    }
    if (!displayAllowed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text("This content is no longer available under the current rights grant."))
        }
        return
    }

    DisposableEffect(session, features) {
        onDispose { features.textToSpeech.stop() }
    }
    NovelReaderSurface(
        canonicalText = canonicalText,
        navigation = session.content.navigation as PlainTextNavigation,
        initialLocator = session.content.initialLocator,
        settings = settings,
        requestedPageIndex = requestedPageIndex,
        pageRequestSerial = pageRequestSerial,
        onPageChanged = onPageIndexChanged,
        onLocatorChanged = onLocatorChanged,
        onTapAction = onReaderTap,
        modifier = modifier,
    )
}

/** The small rich-text surface used by text chapters. Images stay in the source body as URLs. */
internal sealed interface NovelReaderSegment {
    data class Text(
        val value: String,
        val sourceStartUtf16: Int = 0,
        val sourceEndUtf16: Int = sourceStartUtf16 + value.length,
    ) : NovelReaderSegment

    data class Image(
        val url: String,
        val alt: String,
        val sourceStartUtf16: Int = 0,
        val sourceEndUtf16: Int = sourceStartUtf16,
    ) : NovelReaderSegment
}

private val NOVEL_MARKDOWN_IMAGE = Regex(
    """!\[([^]]*)]\((https?://[^)\s]+)(?:\s+(?:\"[^\"]*\"|'[^']*'))?\)""",
    setOf(RegexOption.IGNORE_CASE),
)
private val NOVEL_HTML_IMAGE = Regex(
    """<img\b[^>]*(?:src|data-src|data-original)\s*=\s*[\"']([^\"']+)[\"'][^>]*>""",
    setOf(RegexOption.IGNORE_CASE),
)

/**
 * Parses the image markers emitted by Wenku8/ShuYue while keeping all other text lossless.
 * Unsafe schemes are deliberately left as visible text instead of being handed to Coil.
 */
internal fun parseNovelReaderSegments(value: String): List<NovelReaderSegment> {
    if (value.isEmpty()) return emptyList()
    val matches = buildList {
        NOVEL_MARKDOWN_IMAGE.findAll(value).forEach { match ->
            add(
                NovelReaderMatch(
                    rangeStart = match.range.first,
                    rangeEndExclusive = match.range.last + 1,
                    url = match.groupValues[2],
                    alt = match.groupValues[1],
                ),
            )
        }
        NOVEL_HTML_IMAGE.findAll(value).forEach { match ->
            val rawTag = match.value
            add(
                NovelReaderMatch(
                    rangeStart = match.range.first,
                    rangeEndExclusive = match.range.last + 1,
                    url = match.groupValues[1],
                    alt = NOVEL_HTML_ALT.find(rawTag)?.groupValues?.getOrNull(1).orEmpty(),
                ),
            )
        }
    }
        .sortedWith(compareBy<NovelReaderMatch> { it.rangeStart }.thenByDescending { it.rangeEndExclusive })

    if (matches.isEmpty()) return listOf(NovelReaderSegment.Text(value, 0, value.length))
    val result = ArrayList<NovelReaderSegment>(matches.size * 2 + 1)
    var cursor = 0
    matches.forEach { match ->
        if (match.rangeStart < cursor) return@forEach
        if (match.rangeStart > cursor) {
            result += NovelReaderSegment.Text(
                value = value.substring(cursor, match.rangeStart),
                sourceStartUtf16 = cursor,
                sourceEndUtf16 = match.rangeStart,
            )
        }
        val decodedUrl = decodeNovelHtmlEntities(match.url).trim()
        if (isNovelImageUrl(decodedUrl)) {
            result += NovelReaderSegment.Image(
                url = decodedUrl,
                alt = decodeNovelHtmlEntities(match.alt).trim(),
                sourceStartUtf16 = match.rangeStart,
                sourceEndUtf16 = match.rangeEndExclusive,
            )
        } else {
            result += NovelReaderSegment.Text(
                value = value.substring(match.rangeStart, match.rangeEndExclusive),
                sourceStartUtf16 = match.rangeStart,
                sourceEndUtf16 = match.rangeEndExclusive,
            )
        }
        cursor = match.rangeEndExclusive
    }
    if (cursor < value.length) {
        result += NovelReaderSegment.Text(value.substring(cursor), cursor, value.length)
    }
    return result.filterNot { it is NovelReaderSegment.Text && it.value.isEmpty() }
}

private data class NovelReaderMatch(
    val rangeStart: Int,
    val rangeEndExclusive: Int,
    val url: String,
    val alt: String,
)

private val NOVEL_HTML_ALT = Regex(
    """(?:alt|title)\s*=\s*[\"']([^\"']*)[\"']""",
    setOf(RegexOption.IGNORE_CASE),
)

private fun isNovelImageUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun decodeNovelHtmlEntities(value: String): String = value
    .replace("&amp;", "&", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true)
    .replace("&#39;", "'", ignoreCase = true)
    .replace("&lt;", "<", ignoreCase = true)
    .replace("&gt;", ">", ignoreCase = true)

@Composable
private fun NovelTextBlock(text: String) {
    val segments = remember(text) { parseNovelReaderSegments(text) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is NovelReaderSegment.Text -> {
                    if (segment.value.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = segment.value,
                                color = NOVEL_READER_TEXT,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 21.sp,
                                    lineHeight = 39.sp,
                                    letterSpacing = 0.15.sp,
                                ),
                            )
                        }
                    }
                }
                is NovelReaderSegment.Image -> NovelInlineImage(
                    key = "$index:${segment.url}",
                    url = segment.url,
                    alt = segment.alt,
                )
            }
        }
    }
}

@Composable
private fun NovelInlineImage(
    key: String,
    url: String,
    alt: String,
) {
    val context = LocalPlatformContext.current
    var failed by remember(key) { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 560.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).build(),
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            onLoading = { failed = false },
            onSuccess = { failed = false },
            onError = { failed = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 560.dp),
        )
        if (failed) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = NOVEL_READER_TEXT,
            ) {
                Text(
                    text = "圖片載入失敗",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * Visits reader search work one block and one bounded segment at a time. Callers invoke this only
 * after an explicit search submission and from [Dispatchers.Default].
 */
internal suspend fun forEachReaderSearchSegment(
    representationId: String,
    blocks: List<TextBlock>,
    canonicalText: String,
    action: suspend (FullTextDocumentSegment) -> Unit,
) {
    blocks.forEach { block ->
        currentCoroutineContext().ensureActive()
        for (segment in fullTextDocumentSegmentsLazy(representationId, block, canonicalText)) {
            currentCoroutineContext().ensureActive()
            action(segment)
            // One bounded segment owns at most one SQLite transaction. Give cancellation and other
            // reader work a scheduling point before creating the next derived segment.
            yield()
        }
    }
}

/** Lazy image-sequence adapter sharing the same stable locator contract as text and EPUB. */
@Composable
private fun ImageSequenceUnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatureRuntime,
    onLocatorChanged: (ReadingLocator) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val navigation = session.content.navigation as? ImageSequenceNavigation
    if (navigation == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text("The image navigation graph is unavailable."))
        }
        return
    }
    val initial = session.content.initialLocator as? ReadingLocator.Image
        ?: navigation.locatorAt(0)
    val initialIndex = navigation.indexOf(initial) ?: 0
    val listState = rememberLazyListState()

    LaunchedEffect(session, listState) {
        listState.scrollToItem(initialIndex)
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (visible == null || visible.size <= 0) null else {
                val fraction = (-visible.offset).toDouble().div(visible.size.toDouble()).coerceIn(0.0, 1.0)
                visible.index to fraction
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (index, fraction) ->
                onLocatorChanged(navigation.locator(index, fraction))
            }
    }

    LazyColumn(modifier.fillMaxSize(), state = listState) {
        itemsIndexed(
            navigation.representation.pages,
            key = { _, page -> page.resourceId },
        ) { index, page ->
            ImageSequencePage(
                session = session,
                features = features,
                navigation = navigation,
                index = index,
                contentDescription = strings.text("Page {0}", index + 1),
                modifier = Modifier.fillMaxWidth(),
            )
            if (page.layout != dev.shinsou.kmp.content.ImageLayout.WEBTOON) HorizontalDivider()
        }
    }
}

@Composable
private fun ImageSequencePage(
    session: TypedReaderContentSession,
    features: ContentFeatureRuntime,
    navigation: ImageSequenceNavigation,
    index: Int,
    contentDescription: String,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val platformContext = LocalPlatformContext.current
    var page by remember(session, index) { mutableStateOf<ImageRenderPage?>(null) }
    var error by remember(session, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, index, features, strings) {
        page = null
        error = null
        val loaded = withContext(Dispatchers.Default) {
            runCatching {
                features.operations.display(session.access) {
                    features.imageRenderPages.load(navigation, index)
                }
            }
        }
        loaded.onSuccess { page = it }
            .onFailure { error = strings.text("The image resource could not be opened.") }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val loaded = page
        when {
            loaded != null -> AsyncImage(
                model = ImageRequest.Builder(platformContext)
                    .data(loaded.bytes)
                    .apply {
                        loaded.readerTransform?.let { transform ->
                            transformations(transform.toCoilTransformation())
                        }
                    }
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
            error != null -> Text(
                text = requireNotNull(error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> CircularProgressIndicator(Modifier.padding(24.dp))
        }
    }
}

/** Full-package EPUB surface. Publisher resources stay exact and are served only by the private scheme. */
@Composable
private fun EpubUnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatureRuntime,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    readerControlsVisible: Boolean,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val navigation = session.content.navigation as? EpubSpineNavigation
    if (navigation == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text("The EPUB navigation graph is unavailable."))
        }
        return
    }
    val initialLocator = session.content.initialLocator as? ReadingLocator.Epub ?: navigation.locatorAt(0)
    var currentIndex by remember(session) { mutableStateOf(navigation.indexOf(initialLocator) ?: 0) }
    var pendingLocator by remember(session) { mutableStateOf(initialLocator) }
    var navigationRevision by remember(session) { mutableStateOf(0) }
    var currentLocator by remember(session) { mutableStateOf(initialLocator) }
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
        annotations = withContext(Dispatchers.Default) {
            features.cleanupRevokedDerivedData(navigation.scope, session.access)
            try {
                features.annotations.list(navigation.scope, session.access)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
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
        val loadedRequest = withContext(Dispatchers.Default) {
            runCatching {
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
            request = null
            loading = false
            message = strings.text("The EPUB resources could not be opened.")
            return@LaunchedEffect
        }
        request = loadedRequest
        currentLocator = locator
        loading = false
        yield()
        val semantic = try {
            withContext(Dispatchers.Default) {
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
        } catch (_: Throwable) {
            null
        }
        if (semantic != null && request === loadedRequest) {
            loadedRequest.installSemanticDocument(semantic)
            semanticDocument = semantic
            val quoted = loadedRequest.locatorForDocumentProgression(
                currentLocator.progression ?: loadedRequest.initialDocumentProgression,
            )
            currentLocator = quoted
            onLocatorChanged(quoted)
        }
    }

    DisposableEffect(session, features) {
        onDispose { features.textToSpeech.stop() }
    }

    fun openLocator(locator: ReadingLocator.Epub) {
        val index = navigation.indexOf(locator) ?: return
        val allowed = runCatching { features.operations.display(session.access) { true } }.getOrDefault(false)
        if (!allowed) {
            message = strings.text("This content is no longer available under the current rights grant.")
            return
        }
        pendingLocator = locator
        currentIndex = index
        navigationRevision++
        onPageIndexChanged(index, navigation.itemCount)
        onLocatorChanged(locator)
    }

    fun handleBrowserAction(action: ReaderTapAction) {
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (currentIndex > 0) openLocator(navigation.locatorAt(currentIndex - 1))
                else onReaderTap(action)
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (currentIndex + 1 < navigation.itemCount) {
                    openLocator(navigation.locatorAt(currentIndex + 1))
                } else {
                    onReaderTap(action)
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
                    onLocatorChanged(currentLocator)
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
            } catch (_: Throwable) {
                message = strings.text("Speech stopped because it is unavailable or no longer permitted.")
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
                val hits = withContext(Dispatchers.Default) {
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
            } catch (_: Throwable) {
                message = strings.text("Full-text search is unavailable for this content.")
                emptyList()
            } finally {
                searchBusy = false
            }
        }
    }

    LaunchedEffect(session, currentIndex, navigation.itemCount) {
        onPageIndexChanged(currentIndex, navigation.itemCount)
    }
    LaunchedEffect(session, requestedPageIndex, pageRequestSerial) {
        if (pageRequestSerial <= 0L) return@LaunchedEffect
        val target = requestedPageIndex?.coerceIn(0, navigation.itemCount - 1) ?: return@LaunchedEffect
        if (target != currentIndex) openLocator(navigation.locatorAt(target))
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
                    navigation.indexOf(locator)?.let { observedIndex ->
                        if (observedIndex != currentIndex) {
                            pendingLocator = locator
                            currentIndex = observedIndex
                            navigationRevision++
                            onPageIndexChanged(observedIndex, navigation.itemCount)
                        }
                    }
                    onLocatorChanged(locator)
                },
                onSelectionChanged = { range ->
                    selectedRange = range
                    if (range == null) {
                        message = strings.text("The note could not be saved or is no longer permitted.")
                    } else {
                        noteRange = range
                        noteBody = ""
                    }
                },
                onReaderTap = ::handleBrowserAction,
                onError = { message = strings.text("The EPUB resources could not be opened.") },
            )
        }
        if (loading) CircularProgressIndicator()

        AnimatedVisibility(
            visible = readerControlsVisible && !searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { searchOpen = true }) {
                        Text(strings.text("Search text"))
                    }
                    TextButton(
                        enabled = semanticDocument != null && features.speechCapability.available,
                        onClick = {
                            if (speaking) features.textToSpeech.stop() else startSpeech()
                        },
                    ) {
                        Text(strings.text(if (speaking) "Stop" else "Speak from paragraph"))
                    }
                    TextButton(enabled = semanticDocument != null, onClick = ::requestNote) {
                        Text(strings.text("Add note"))
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
                            label = { Text(strings.text("Search this book")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = query.isNotBlank() && !searchBusy,
                            onClick = ::submitSearch,
                        ) { Text(strings.text("Find")) }
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
                Text(strings.text("Add paragraph note"), style = MaterialTheme.typography.titleLarge)
                Text(range.quote?.exact.orEmpty().takeUtf16Safe(240))
                OutlinedTextField(
                    value = noteBody,
                    onValueChange = { noteBody = it.take(16_384) },
                    label = { Text(strings.text("Note")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = noteBody.isNotBlank(),
                        onClick = {
                            val submitted = noteBody
                            scope.launch {
                                val created = try {
                                    withContext(Dispatchers.Default) {
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
                                } catch (_: Throwable) {
                                    null
                                }
                                if (created == null) {
                                    message = strings.text("The note could not be saved or is no longer permitted.")
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

/** Explicit reader search work; never invoked while the EPUB surface is painting its first frame. */
internal suspend fun indexEpubForReader(
    features: ContentFeatureRuntime,
    navigation: EpubSpineNavigation,
    access: ContentAccessRequest,
) {
    navigation.representation.documents.indices.forEach { documentIndex ->
        currentCoroutineContext().ensureActive()
        val semantic = features.operations.displaySuspending(access) {
            features.epubSemanticDocuments.createCancellable(
                navigation = navigation,
                documentIndex = documentIndex,
                resourceReadGate = EpubResourceReadGate { read ->
                    features.operations.display(access) { read() }
                },
            )
        }
        semantic.blocks.forEach { block ->
            val textBlock = TextBlock(block.blockId, block.startUtf16, block.endUtf16)
            for (segment in fullTextDocumentSegmentsLazy(
                navigation.representationId,
                textBlock,
                semantic.canonicalText,
            )) {
                currentCoroutineContext().ensureActive()
                val anchor = EpubSearchAnchor(
                    resourceHref = semantic.resourceHref,
                    cfiBase = block.cfiBase,
                    blockStartUtf16 = block.startUtf16,
                )
                val current = features.searchIndex.isCurrent(
                    documentId = segment.documentId,
                    scope = navigation.scope,
                    resourceId = semantic.resourceId,
                    blockId = segment.blockId,
                    baseOffsetUtf16 = segment.startUtf16,
                    canonicalDocumentUtf16Length = semantic.canonicalText.length,
                    access = access,
                    epubAnchor = anchor,
                )
                if (!current) {
                    features.searchIndex.upsertForeground(
                        SearchableTextDocument(
                            documentId = segment.documentId,
                            scope = navigation.scope,
                            resourceId = semantic.resourceId,
                            blockId = segment.blockId,
                            text = semantic.canonicalText.substring(segment.startUtf16, segment.endUtf16),
                            access = access,
                            baseOffsetUtf16 = segment.startUtf16,
                            canonicalDocumentUtf16Length = semantic.canonicalText.length,
                            epubAnchor = anchor,
                        ),
                    )
                }
                yield()
            }
        }
    }
}

@Composable
private fun UnsupportedUnifiedContentReader(
    representation: ContentRepresentation,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            when (representation) {
                is ContentRepresentation.EpubSpine ->
                    strings.text(
                        "EPUB rendering is unavailable until this platform supplies its secure browser renderer.",
                    )
                is ContentRepresentation.ImageSequence ->
                    strings.text("This image representation has not been materialized into reader pages.")
                is ContentRepresentation.PlainText -> error("Handled above")
            },
        )
    }
}

private fun blockRange(
    scope: dev.shinsou.kmp.reader.ReadingScope,
    representation: ContentRepresentation.PlainText,
    block: TextBlock,
    canonicalText: String,
): ReadingRange {
    require(block.endUtf16 > block.startUtf16) { "Cannot annotate an empty text block" }
    val quoteEnd = safeBoundaryAtOrBefore(canonicalText, minOf(block.endUtf16, block.startUtf16 + 256))
    val suffixEnd = safeBoundaryAtOrBefore(canonicalText, minOf(canonicalText.length, quoteEnd + 64))
    val quote = TextQuote(
        exact = canonicalText.substring(block.startUtf16, quoteEnd),
        suffix = canonicalText.substring(quoteEnd, suffixEnd),
    )
    val length = canonicalText.length
    val start = ReadingLocator.Text(
        schemaVersion = scope.schemaVersion,
        scope = scope,
        resourceId = representation.resource.id,
        blockId = block.blockId,
        offset = block.startUtf16,
        progression = progression(block.startUtf16, length),
        quote = quote,
    )
    val end = ReadingLocator.Text(
        schemaVersion = scope.schemaVersion,
        scope = scope,
        resourceId = representation.resource.id,
        blockId = block.blockId,
        offset = block.endUtf16,
        progression = progression(block.endUtf16, length),
    )
    return ReadingRange(start, end, quote)
}

private fun ContentAccessRequest.withTextCharacters(length: Int): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = length.toLong(),
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun progression(offset: Int, length: Int): Double = if (length == 0) 0.0 else offset.toDouble() / length

private fun safeBoundaryAtOrBefore(text: String, requested: Int): Int {
    var offset = requested.coerceIn(0, text.length)
    if (offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()) {
        offset--
    }
    return offset
}

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
