package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import dev.shinsou.kmp.plugin.ungrantedContentHostSuffix
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.shinsou.kmp.ui.ContentFeatures
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.TextBlock
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.content.PlainTextNavigation
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.ReadingRange
import dev.shinsou.kmp.domain.model.TextQuote
import dev.shinsou.kmp.reader.EpubResourceReadGate
import dev.shinsou.kmp.domain.content.EpubSpineNavigation
import dev.shinsou.kmp.reader.ImageRenderPage
import dev.shinsou.kmp.domain.content.ImageSequenceNavigation
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.network.encodedImageHeight
import dev.shinsou.kmp.reader.readerImageTransform
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.search.FullTextDocumentSegment
import dev.shinsou.kmp.search.EpubSearchAnchor
import dev.shinsou.kmp.search.SearchableTextDocument
import dev.shinsou.kmp.search.fullTextDocumentSegmentsLazy
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull

/** Plain-text reader: the paginated chapter surface, with the translation overlay. */
@Composable
private fun PlainTextUnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    settings: ReaderSettings,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    readerControlsVisible: Boolean,
    translationRequestSerial: Long,
    translationToggleSerial: Long,
    onTranslationActiveChanged: (Boolean) -> Unit,
    translationLabels: dev.shinsou.kmp.translation.TranslationLabels?,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val canonicalText = requireNotNull(session.canonicalText)
    val access = session.access.withTextCharacters(canonicalText.length)
    val displayAllowed = remember(session, features) {
        // A rights denial is an expected outcome, shown to the reader just below.
        runCatching {
            features.operations.display(access, canonicalText.length.toLong()) { true }
        }.getOrDefault(false)
    }
    if (!displayAllowed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text(StringKey.THIS_CONTENT_IS_NO_LONGER_AVAILABLE_UNDER))
        }
        return
    }

    DisposableEffect(session, features) {
        onDispose { features.textToSpeech.stop() }
    }
    val scope = session.content.navigation.scope
    // Translations are kept on the device: a grant that allows display but not offline storage
    // gets no translation at all, rather than one that quietly stores the text.
    val translationStorageAllowed = remember(session, features) { readerTranslationAvailable(session, features) }
    val translationState = rememberNovelTranslation(
        translation = features.translation.takeIf { translationStorageAllowed },
        workKey = scope.publicationId.value,
        chapterKey = scope.unitId.value,
        canonicalText = canonicalText,
        exportable = { features.operations.export(access, canonicalText.length.toLong()) { true } },
        labels = translationLabels,
    )
    val display = remember(translationState?.result, translationState?.settings?.mode, canonicalText) {
        val state = translationState ?: return@remember null
        state.result?.let { dev.shinsou.kmp.translation.TranslationDisplayText.build(canonicalText, it, state.settings.mode) }
    }
    // Switching between the original and a translation re-paginates: carry the reader's position
    // across instead of going back to where the chapter was opened.
    val latestLocator = remember(session) { arrayOf<ReadingLocator>(session.content.initialLocator) }
    val anchorLocator = remember(session, display?.text) { latestLocator[0] }
    val reopened = anchorLocator === session.content.initialLocator && display == null
    var translationSheetVisible by remember { mutableStateOf(false) }
    NovelTranslationControls(
        session = session,
        translationState = translationState,
        translationSheetVisible = translationSheetVisible,
        onTranslationSheetVisible = { translationSheetVisible = it },
        translationRequestSerial = translationRequestSerial,
        translationToggleSerial = translationToggleSerial,
        onTranslationActiveChanged = onTranslationActiveChanged,
    )

    Box(modifier.fillMaxSize()) {
        NovelReaderSurface(
            canonicalText = canonicalText,
            navigation = session.content.navigation as PlainTextNavigation,
            initialLocator = anchorLocator,
            settings = settings,
            // Saved visual pages belong to the original text as it was first laid out.
            initialVisualPageIndex = if (reopened) session.initialVisualPageIndex ?: requestedPageIndex else null,
            initialVisualPageCount = if (reopened) session.initialVisualPageCount else null,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            onPageChanged = onPageIndexChanged,
            onLocatorChanged = { locator, pageIndex, pageCount ->
                latestLocator[0] = locator
                onLocatorChanged(locator, pageIndex, pageCount)
            },
            onNavigationBoundary = onNavigationBoundary,
            onTapAction = onReaderTap,
            remoteAssetScope = session.remoteAssetScope,
            display = display,
            modifier = Modifier.fillMaxSize(),
        )
        if (translationState != null) {
            NovelTranslationOverlay(
                state = translationState,
                readerControlsVisible = readerControlsVisible,
                sheetVisible = translationSheetVisible,
                onSheetVisible = { translationSheetVisible = it },
            )
        }
    }
}

/**
 * The reader's translation controls: the panel opens on a press of the toolbar button, the other
 * press turns the translation on or off, and the shell is told whether the chapter is shown translated.
 */
@Composable
private fun NovelTranslationControls(
    session: TypedReaderContentSession,
    translationState: NovelTranslationState?,
    translationSheetVisible: Boolean,
    onTranslationSheetVisible: (Boolean) -> Unit,
    translationRequestSerial: Long,
    translationToggleSerial: Long,
    onTranslationActiveChanged: (Boolean) -> Unit,
) {
    // a closed sheet leaves focus nowhere: the reader takes it back so its keys page again
    val returnFocus = LocalReaderFocusReturn.current
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(translationSheetVisible) {
        if (translationSheetVisible) sheetWasOpen = true
        else if (sheetWasOpen) { kotlinx.coroutines.delay(50); returnFocus() }
    }
    var handledTranslationRequest by remember { mutableStateOf(translationRequestSerial) }
    LaunchedEffect(translationRequestSerial) {
        // Only a press of the button opens the panel. The reader's shell starts its counter
        // again for every chapter, and that reset must not be read as a press.
        val pressed = translationRequestSerial > handledTranslationRequest
        handledTranslationRequest = translationRequestSerial
        if (pressed) onTranslationSheetVisible(true)
    }
    var handledTranslationToggle by remember { mutableStateOf(translationToggleSerial) }
    LaunchedEffect(translationToggleSerial) {
        val pressed = translationToggleSerial > handledTranslationToggle
        handledTranslationToggle = translationToggleSerial
        val state = translationState ?: return@LaunchedEffect
        if (pressed && state.loaded) state.change(state.settings.toggled())
    }
    val translationActive = translationState?.let { it.settings.shown } == true
    // the shell starts each chapter unlit, so a new chapter is told again
    LaunchedEffect(translationActive, session) { onTranslationActiveChanged(translationActive) }
}

/** Over the page: the translation progress bar, its notice and, when opened, the translation panel. */
@Composable
private fun BoxScope.NovelTranslationOverlay(
    state: NovelTranslationState,
    readerControlsVisible: Boolean,
    sheetVisible: Boolean,
    onSheetVisible: (Boolean) -> Unit,
) {
    val progress = state.progress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { (progress.first.toFloat() / progress.second.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
    NovelTranslationNotice(
        state = state,
        onOpen = { onSheetVisible(true) },
        // Under the reader's top bar when it is showing.
        modifier = Modifier.align(Alignment.TopCenter).padding(top = if (readerControlsVisible) 72.dp else 14.dp),
    )
    if (sheetVisible) {
        NovelTranslationSheet(state) { onSheetVisible(false) }
    }
}

/** Plain-text M4 surface with local body search, paragraph notes, copy, and block-aware TTS. */
@Composable
internal fun UnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    copyText: (label: String, text: String) -> Boolean,
    settings: ReaderSettings,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit = { _, _, _ -> },
    requestedPageIndex: Int? = null,
    pageRequestSerial: Long = 0L,
    navigationAction: ReaderTapAction? = null,
    navigationRequestKey: Long = 0L,
    readerControlsVisible: Boolean = false,
    /** Bumped by the reader's toolbar to open the translation panel. */
    translationRequestSerial: Long = 0L,
    /** Bumped by the reader's toolbar to turn the translation on or off. */
    translationToggleSerial: Long = 0L,
    /** Told whether the chapter is shown translated. */
    onTranslationActiveChanged: (Boolean) -> Unit = {},
    /** What the reader's shell calls this work and chapter; kept with a stored translation. */
    translationLabels: dev.shinsou.kmp.translation.TranslationLabels? = null,
    /** Manga translation for image chapters (ADR-0005); null leaves them untranslated. */
    mangaTranslation: dev.shinsou.kmp.manga.MangaTranslationService? = null,
    /** The work's language as its source gives it, blank when it does not say (its first pages are read then). */
    workLanguage: String = "",
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit = { _, _ -> },
    onPageCountInvalidated: () -> Unit = {},
    onNavigationBoundary: (ReaderTapAction) -> Unit = {},
    onReaderTap: (ReaderTapAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val representation = session.content.representation
    if (representation is ContentRepresentation.ImageSequence) {
        ImageSequenceUnifiedContentReader(
            session = session,
            features = features,
            mangaTranslation = mangaTranslation,
            workLanguage = workLanguage,
            translationRequestSerial = translationRequestSerial,
            translationToggleSerial = translationToggleSerial,
            onTranslationActiveChanged = onTranslationActiveChanged,
            translationLabels = translationLabels,
            readerControlsVisible = readerControlsVisible,
            settings = settings,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            onPageIndexChanged = onPageIndexChanged,
            onNavigationBoundary = onNavigationBoundary,
            onReaderTap = onReaderTap,
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
            onPageCountInvalidated = onPageCountInvalidated,
            onNavigationBoundary = onNavigationBoundary,
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
    PlainTextUnifiedContentReader(
        session = session,
        features = features,
        settings = settings,
        onLocatorChanged = onLocatorChanged,
        requestedPageIndex = requestedPageIndex,
        pageRequestSerial = pageRequestSerial,
        navigationAction = navigationAction,
        navigationRequestKey = navigationRequestKey,
        readerControlsVisible = readerControlsVisible,
        translationRequestSerial = translationRequestSerial,
        translationToggleSerial = translationToggleSerial,
        onTranslationActiveChanged = onTranslationActiveChanged,
        translationLabels = translationLabels,
        onPageIndexChanged = onPageIndexChanged,
        onNavigationBoundary = onNavigationBoundary,
        onReaderTap = onReaderTap,
        modifier = modifier,
    )
}

/**
 * Whether the chapter in [session] can be translated: plain text, on a build with translation,
 * under a grant that allows keeping the text on the device. The reader's toolbar shows its
 * translation button on the same condition the reader itself uses.
 */
internal fun readerTranslationAvailable(session: TypedReaderContentSession, features: ContentFeatures?): Boolean {
    if (features?.translation == null) return false
    if (session.content.representation !is ContentRepresentation.PlainText) return false
    val text = session.canonicalText ?: return false
    val access = session.access.withTextCharacters(text.length)
    // A rights check that fails means "may not be kept", the safe answer.
    return runCatching { features.operations.offlineStore(access, text.length.toLong()) { true } }.getOrDefault(false)
}

/**
 * The chapter in [session] as the translation queue needs it, under the same conditions the
 * reader translates it: plain text whose grant allows keeping it, with the reader's own notion
 * of which lines are not prose.
 */
internal suspend fun prefetchChapter(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    labels: dev.shinsou.kmp.translation.TranslationLabels?,
    /** Translates image chapters too, as manga pages (ADR-0005); without it only text is. */
    manga: dev.shinsou.kmp.manga.MangaTranslationService? = null,
    /** The work's language as its source gives it, and the app's: chosen from as the reader would. */
    workLanguage: String? = null,
    appLanguage: String? = null,
): dev.shinsou.kmp.translation.PrefetchChapter {
    val translation = features.translation
    if (manga != null && translation != null && mangaTranslationAvailable(session, features)) {
        val scope = session.content.navigation.scope
        val navigation = session.content.navigation as ImageSequenceNavigation
        return dev.shinsou.kmp.translation.PrefetchChapter(
            workKey = scope.publicationId.value,
            chapterKey = scope.unitId.value,
            text = "",
            exportable = false,
            labels = labels,
            pages = { from, to, progress ->
                translateMangaChapterAhead(
                    manga, translation, scope.publicationId.value, scope.unitId.value, to, navigation.itemCount,
                    loadPage = { i -> imageSequencePageBytes(session, features, navigation, i) }, labels = labels, progress = progress,
                    workLanguage = workLanguage, appLanguage = appLanguage, chosenFrom = from,
                )
            },
        )
    }
    if (!readerTranslationAvailable(session, features)) {
        throw dev.shinsou.kmp.translation.PrefetchNotTranslatable("This chapter is not text that may be translated and kept")
    }
    val text = requireNotNull(session.canonicalText)
    val access = session.access.withTextCharacters(text.length)
    val scope = session.content.navigation.scope
    return dev.shinsou.kmp.translation.PrefetchChapter(
        workKey = scope.publicationId.value,
        chapterKey = scope.unitId.value,
        text = text,
        // A rights check that fails means "not exportable", the safe answer.
        exportable = runCatchingCancellable { features.operations.export(access, text.length.toLong()) { true } }.getOrDefault(false),
        labels = labels,
        keep = { line -> parseNovelReaderSegments(line).any { it is NovelReaderSegment.Image } },
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
                        // Not selectable, like the paged novel reader's text.
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
                is NovelReaderSegment.Image -> NovelInlineImage(
                    key = "$index:${segment.url}",
                    url = segment.url,
                    alt = segment.alt,
                    remoteAssetScope = null,
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
    remoteAssetScope: dev.shinsou.kmp.contract.ReaderRemoteImageFetcher?,
) {
    val context = LocalPlatformContext.current
    val image = rememberNovelRemoteImage(url, remoteAssetScope, key)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 560.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        image.bytes?.let { body -> AsyncImage(
            model = ImageRequest.Builder(context).data(body).build(),
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            onLoading = { image.failed = false },
            onSuccess = { image.failed = false },
            onError = { image.failed = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 560.dp),
        ) }
        if (image.failed) {
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

/** The image chapter's pages in the layout the reading mode asks for, with page translations provided to them. */
@Composable
private fun ImageSequenceLayoutContent(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    initialIndex: Int,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    translatedPage: (Int) -> ByteArray?,
) {
    CompositionLocalProvider(LocalTranslatedImagePage provides translatedPage) {
        // Recreate pager state when direction/layout changes. requestedPageIndex is kept in the
        // reader shell and makes the new container start on the page that was actually visible.
        key(session.content.representation.representationId, settings.readingMode) {
            when (imageSequenceReaderLayout(settings.readingMode)) {
                ImageSequenceReaderLayout.HORIZONTAL_PAGER -> ImageSequenceHorizontalPager(
                    session = session,
                    features = features,
                    navigation = navigation,
                    initialIndex = initialIndex,
                    settings = settings,
                    requestedPageIndex = requestedPageIndex,
                    pageRequestSerial = pageRequestSerial,
                    navigationAction = navigationAction,
                    navigationRequestKey = navigationRequestKey,
                    onPageIndexChanged = onPageIndexChanged,
                    onNavigationBoundary = onNavigationBoundary,
                    onReaderTap = onReaderTap,
                    onLocatorChanged = onLocatorChanged,
                    modifier = Modifier.fillMaxSize(),
                )

                ImageSequenceReaderLayout.VERTICAL_PAGER -> ImageSequenceVerticalPager(
                    session = session,
                    features = features,
                    navigation = navigation,
                    initialIndex = initialIndex,
                    settings = settings,
                    requestedPageIndex = requestedPageIndex,
                    pageRequestSerial = pageRequestSerial,
                    navigationAction = navigationAction,
                    navigationRequestKey = navigationRequestKey,
                    onPageIndexChanged = onPageIndexChanged,
                    onNavigationBoundary = onNavigationBoundary,
                    onReaderTap = onReaderTap,
                    onLocatorChanged = onLocatorChanged,
                    modifier = Modifier.fillMaxSize(),
                )

                ImageSequenceReaderLayout.CONTINUOUS -> ImageSequenceContinuousReader(
                    session = session,
                    features = features,
                    navigation = navigation,
                    initialIndex = initialIndex,
                    settings = settings,
                    requestedPageIndex = requestedPageIndex,
                    pageRequestSerial = pageRequestSerial,
                    navigationAction = navigationAction,
                    navigationRequestKey = navigationRequestKey,
                    onPageIndexChanged = onPageIndexChanged,
                    onNavigationBoundary = onNavigationBoundary,
                    onReaderTap = onReaderTap,
                    onLocatorChanged = onLocatorChanged,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The image reader's translation controls: focus returns to the reader when the panel closes or a
 * translation ends, a press of the toolbar button opens the panel or toggles the translation, and
 * the shell is told whether the chapter is shown translated.
 */
@Composable
private fun MangaTranslationControls(
    session: TypedReaderContentSession,
    mangaState: MangaReaderTranslationState?,
    translationSheetVisible: Boolean,
    onTranslationSheetVisible: (Boolean) -> Unit,
    translationRequestSerial: Long,
    translationToggleSerial: Long,
    onTranslationActiveChanged: (Boolean) -> Unit,
) {
    // a closed sheet leaves focus nowhere: the reader takes it back so its keys page again
    val returnFocus = LocalReaderFocusReturn.current
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(translationSheetVisible) {
        if (translationSheetVisible) sheetWasOpen = true
        else if (sheetWasOpen) { kotlinx.coroutines.delay(50); returnFocus() }
    }
    // when a translation ends its notice goes; whatever held the focus then, the reader takes it
    LaunchedEffect(mangaState?.finished, mangaState?.failure) {
        if (mangaState?.finished == true && !translationSheetVisible) returnFocus()
    }
    var handledTranslationRequest by remember { mutableStateOf(translationRequestSerial) }
    LaunchedEffect(translationRequestSerial) {
        // Only a press of the button opens the panel (the shell resets its counter per chapter).
        val pressed = translationRequestSerial > handledTranslationRequest
        handledTranslationRequest = translationRequestSerial
        if (pressed && mangaState != null) onTranslationSheetVisible(true)
    }
    var handledTranslationToggle by remember { mutableStateOf(translationToggleSerial) }
    LaunchedEffect(translationToggleSerial) {
        val pressed = translationToggleSerial > handledTranslationToggle
        handledTranslationToggle = translationToggleSerial
        val state = mangaState ?: return@LaunchedEffect
        val current = state.settings ?: return@LaunchedEffect
        if (pressed) state.update(current.toggled())
    }
    val translationActive = mangaState?.settings?.shown == true
    // the shell starts each chapter unlit, so a new chapter is told again
    LaunchedEffect(translationActive, session) { onTranslationActiveChanged(translationActive) }
}

/** Image-sequence adapter sharing the same stable locator contract as text and EPUB. */
@Composable
private fun ImageSequenceUnifiedContentReader(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    mangaTranslation: dev.shinsou.kmp.manga.MangaTranslationService?,
    workLanguage: String,
    translationRequestSerial: Long,
    translationToggleSerial: Long,
    onTranslationActiveChanged: (Boolean) -> Unit,
    translationLabels: dev.shinsou.kmp.translation.TranslationLabels?,
    readerControlsVisible: Boolean,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val navigation = session.content.navigation as? ImageSequenceNavigation
    if (navigation == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text(StringKey.THE_IMAGE_NAVIGATION_GRAPH_IS_UNAVAILABLE))
        }
        return
    }
    val initial = session.content.initialLocator as? ReadingLocator.Image
        ?: navigation.locatorAt(0)
    val initialIndex = (requestedPageIndex ?: session.initialVisualPageIndex
        ?: navigation.indexOf(initial) ?: 0).coerceIn(0, navigation.itemCount - 1)

    // ADR-0005: translated pages are kept on the device, so only a grant that allows storing the
    // chapter offline gets them.
    val textTranslation = features.translation
    val mangaState = if (mangaTranslation != null && textTranslation != null &&
        remember(session, features) { mangaTranslationAvailable(session, features) }
    ) {
        val scope = session.content.navigation.scope
        rememberMangaReaderTranslation(
            manga = mangaTranslation,
            translation = textTranslation,
            workKey = scope.publicationId.value,
            chapterKey = scope.unitId.value,
            pageCount = navigation.itemCount,
            pagesKey = session,
            loadPage = { i -> imageSequencePageBytes(session, features, navigation, i) },
            exportable = false,
            labels = translationLabels,
            workLanguage = workLanguage,
        )
    } else {
        null
    }
    var translationSheetVisible by remember { mutableStateOf(false) }
    MangaTranslationControls(
        session = session,
        mangaState = mangaState,
        translationSheetVisible = translationSheetVisible,
        onTranslationSheetVisible = { translationSheetVisible = it },
        translationRequestSerial = translationRequestSerial,
        translationToggleSerial = translationToggleSerial,
        onTranslationActiveChanged = onTranslationActiveChanged,
    )

    Box(modifier.fillMaxSize()) {
        ImageSequenceLayoutContent(
            session = session,
            features = features,
            navigation = navigation,
            initialIndex = initialIndex,
            settings = settings,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            onPageIndexChanged = onPageIndexChanged,
            onNavigationBoundary = onNavigationBoundary,
            onReaderTap = onReaderTap,
            onLocatorChanged = onLocatorChanged,
            translatedPage = { index -> mangaState?.translatedPage(index) },
        )
        if (mangaState != null) {
            MangaTranslationNotice(
                state = mangaState,
                onOpen = { translationSheetVisible = true },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (readerControlsVisible) 16.dp else 0.dp),
            )
            if (translationSheetVisible) MangaTranslationSheet(mangaState) { translationSheetVisible = false }
        }
    }
}

/** Page n's translated image in the image reader, when there is one to show. */
private val LocalTranslatedImagePage = androidx.compose.runtime.staticCompositionLocalOf<(Int) -> ByteArray?> { { null } }

/**
 * Whether the image chapter in [session] can be translated (ADR-0005): a build with translation,
 * under a grant that allows keeping the chapter's pages on the device.
 */
/**
 * The bytes of an image chapter's page as stored, for its translation: null where it cannot be
 * shown or is shown through a reader transform (a scrambled page is not the picture itself).
 */
internal suspend fun imageSequencePageBytes(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    index: Int,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ByteArray? = withContext(workDispatcher) {
    runCatchingCancellable {
        features.operations.display(session.access) { features.imageRenderPages.load(navigation, index) }
    }.onFailure { Log.warn("Reader", it) { "Loading an image page for translation failed" } }.getOrNull()?.takeIf { it.readerTransform == null }?.bytes
}

internal fun mangaTranslationAvailable(session: TypedReaderContentSession, features: ContentFeatures?): Boolean {
    if (features?.translation == null) return false
    val representation = session.content.representation as? ContentRepresentation.ImageSequence ?: return false
    val bytes = representation.pages.sumOf { it.resource.blob.byteSize }
    // A rights check that fails means "may not be kept", the safe answer.
    return runCatching { features.operations.offlineStore(session.access, bytes) { true } }.getOrDefault(false)
}

internal enum class ImageSequenceReaderLayout {
    HORIZONTAL_PAGER,
    VERTICAL_PAGER,
    CONTINUOUS,
}

internal fun imageSequenceReaderLayout(mode: ReadingMode): ImageSequenceReaderLayout = when (mode) {
    ReadingMode.PAGER_LTR,
    ReadingMode.PAGER_RTL,
    -> ImageSequenceReaderLayout.HORIZONTAL_PAGER

    ReadingMode.PAGER_VERTICAL -> ImageSequenceReaderLayout.VERTICAL_PAGER
    ReadingMode.WEBTOON,
    ReadingMode.CONTINUOUS_VERTICAL,
    -> ImageSequenceReaderLayout.CONTINUOUS
}

private fun Modifier.imageSequenceReaderTapTarget(
    readingMode: ReadingMode,
    onReaderTap: (ReaderTapAction) -> Unit,
): Modifier = pointerInput(readingMode, onReaderTap) {
    detectTapGestures { position ->
        onReaderTap(
            readerTapAction(
                horizontalPosition = position.x,
                viewportWidth = size.width.toFloat(),
                readingMode = readingMode,
            ),
        )
    }
}

@Composable
private fun ImageSequenceHorizontalPager(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    initialIndex: Int,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val pageCount = navigation.itemCount
    val pagerState = rememberPagerState(
        initialPage = readerPhysicalPageIndex(initialIndex, pageCount, settings.readingMode),
    ) { pageCount }
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onReaderTap)

    LaunchedEffect(session, pagerState, settings.readingMode) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { physicalIndex ->
                val index = readerLogicalPageIndex(physicalIndex, pageCount, settings.readingMode)
                onPageIndexChanged(index, pageCount)
                onLocatorChanged(navigation.locatorAt(index), index, pageCount)
            }
    }
    LaunchedEffect(session, pageRequestSerial, pagerState, settings.readingMode) {
        val target = requestedImageSequencePageIndex(
            pageRequestSerial = pageRequestSerial,
            requestedPageIndex = requestedPageIndex,
            pageCount = pageCount,
        ) ?: return@LaunchedEffect
        val physicalTarget = readerPhysicalPageIndex(target, pageCount, settings.readingMode)
        if (pagerState.currentPage != physicalTarget || pagerState.currentPageOffsetFraction != 0f) {
            // Slider seeks jump directly; only adjacent turns animate through intermediate pages.
            if (readerPageSeekAnimates(pagerState.currentPage, physicalTarget, settings.animatePageTransitions)) {
                pagerState.animateScrollToPage(physicalTarget)
            } else {
                pagerState.scrollToPage(physicalTarget)
            }
        }
    }
    LaunchedEffect(session, navigationRequestKey, pagerState, settings.readingMode) {
        val action = navigationAction
        if (
            navigationRequestKey <= 0L ||
            navigationRequestKey == initialNavigationRequestKey ||
            action == null
        ) return@LaunchedEffect
        val current = readerLogicalPageIndex(
            pagerState.currentPage,
            pageCount,
            settings.readingMode,
        )
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (current <= 0) {
                    latestOnNavigationBoundary(action)
                } else {
                    val target = readerPhysicalPageIndex(current - 1, pageCount, settings.readingMode)
                    if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
                    else pagerState.scrollToPage(target)
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (current >= pageCount - 1) {
                    latestOnNavigationBoundary(action)
                } else {
                    val target = readerPhysicalPageIndex(current + 1, pageCount, settings.readingMode)
                    if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
                    else pagerState.scrollToPage(target)
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 2,
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
        ),
        modifier = modifier
            .fillMaxSize()
            .imageSequenceReaderTapTarget(settings.readingMode, onReaderTap),
    ) { physicalIndex ->
        val index = readerLogicalPageIndex(physicalIndex, pageCount, settings.readingMode)
        ImageSequencePage(
            session = session,
            features = features,
            navigation = navigation,
            index = index,
            contentDescription = strings.text(StringKey.PAGE_X, index + 1),
            fitInsideViewport = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ImageSequenceVerticalPager(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    initialIndex: Int,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val pageCount = navigation.itemCount
    val pagerState = rememberPagerState(initialPage = initialIndex) { pageCount }
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onReaderTap)

    LaunchedEffect(session, pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { index ->
                onPageIndexChanged(index, pageCount)
                onLocatorChanged(navigation.locatorAt(index), index, pageCount)
            }
    }
    LaunchedEffect(session, pageRequestSerial, pagerState) {
        val target = requestedImageSequencePageIndex(
            pageRequestSerial = pageRequestSerial,
            requestedPageIndex = requestedPageIndex,
            pageCount = pageCount,
        ) ?: return@LaunchedEffect
        if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
            if (readerPageSeekAnimates(pagerState.currentPage, target, settings.animatePageTransitions)) {
                pagerState.animateScrollToPage(target)
            } else {
                pagerState.scrollToPage(target)
            }
        }
    }
    LaunchedEffect(session, navigationRequestKey, pagerState) {
        val action = navigationAction
        if (
            navigationRequestKey <= 0L ||
            navigationRequestKey == initialNavigationRequestKey ||
            action == null
        ) return@LaunchedEffect
        val current = pagerState.currentPage
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (current <= 0) {
                    latestOnNavigationBoundary(action)
                } else {
                    val target = current - 1
                    if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
                    else pagerState.scrollToPage(target)
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (current >= pageCount - 1) {
                    latestOnNavigationBoundary(action)
                } else {
                    val target = current + 1
                    if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
                    else pagerState.scrollToPage(target)
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
        }
    }

    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 2,
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
        ),
        modifier = modifier
            .fillMaxSize()
            .imageSequenceReaderTapTarget(settings.readingMode, onReaderTap),
    ) { index ->
        ImageSequencePage(
            session = session,
            features = features,
            navigation = navigation,
            index = index,
            contentDescription = strings.text(StringKey.PAGE_X, index + 1),
            fitInsideViewport = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ImageSequenceContinuousReader(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    initialIndex: Int,
    settings: ReaderSettings,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    onPageIndexChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int?) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalShinsouStrings.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onReaderTap)

    LaunchedEffect(session, listState) {
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
                onPageIndexChanged(index, navigation.itemCount)
                onLocatorChanged(navigation.locator(index, fraction), index, navigation.itemCount)
            }
    }
    LaunchedEffect(session, pageRequestSerial, listState) {
        val target = requestedImageSequencePageIndex(
            pageRequestSerial = pageRequestSerial,
            requestedPageIndex = requestedPageIndex,
            pageCount = navigation.itemCount,
        )
        if (
            target != null &&
            (listState.firstVisibleItemIndex != target || listState.firstVisibleItemScrollOffset != 0)
        ) {
            // Unloaded pages have placeholder heights, so a long animated scroll can stop short.
            if (readerPageSeekAnimates(listState.firstVisibleItemIndex, target, settings.animatePageTransitions)) {
                listState.animateScrollToItem(target)
            } else {
                listState.scrollToItem(target)
            }
        }
    }
    LaunchedEffect(session, navigationRequestKey, listState) {
        val action = navigationAction
        if (
            navigationRequestKey <= 0L ||
            navigationRequestKey == initialNavigationRequestKey ||
            action == null
        ) return@LaunchedEffect
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (!listState.canScrollBackward) {
                    latestOnNavigationBoundary(action)
                } else {
                    val viewport = (
                        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        ).coerceAtLeast(1)
                    if (settings.animatePageTransitions) listState.animateScrollBy(-viewport.toFloat())
                    else listState.scrollBy(-viewport.toFloat())
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (!listState.canScrollForward) {
                    latestOnNavigationBoundary(action)
                } else {
                    val viewport = (
                        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        ).coerceAtLeast(1)
                    if (settings.animatePageTransitions) listState.animateScrollBy(viewport.toFloat())
                    else listState.scrollBy(viewport.toFloat())
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imageSequenceReaderTapTarget(settings.readingMode, onReaderTap),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            navigation.representation.pages,
            key = { _, page -> page.resourceId },
        ) { index, page ->
            ImageSequencePage(
                session = session,
                features = features,
                navigation = navigation,
                index = index,
                contentDescription = strings.text(StringKey.PAGE_X, index + 1),
                fitInsideViewport = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (page.layout != dev.shinsou.kmp.domain.content.ImageLayout.WEBTOON) HorizontalDivider()
        }
    }
}

@Composable
private fun ImageSequencePage(
    session: TypedReaderContentSession,
    features: ContentFeatures,
    navigation: ImageSequenceNavigation,
    index: Int,
    contentDescription: String,
    fitInsideViewport: Boolean,
    modifier: Modifier,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val strings = LocalShinsouStrings.current
    val platformContext = LocalPlatformContext.current
    var page by remember(session, index) { mutableStateOf<ImageRenderPage?>(null) }
    var error by remember(session, index) { mutableStateOf<String?>(null) }
    var decodedAspectRatio by remember(session, index) { mutableStateOf<Float?>(null) }
    LaunchedEffect(session, index, features, strings) {
        page = null
        error = null
        decodedAspectRatio = null
        val loaded = withContext(workDispatcher) {
            runCatchingCancellable {
                features.operations.display(session.access) {
                    features.imageRenderPages.load(navigation, index)
                }
            }
        }
        loaded.onSuccess { page = it }
            .onFailure { failure ->
                error = failure.ungrantedContentHostSuffix()?.let { suffix ->
                    strings.text(StringKey.ITS_IMAGES_COME_FROM_X_WHICH_THIS, suffix.displaySuffix(strings))
                } ?: strings.text(StringKey.THE_IMAGE_RESOURCE_COULD_NOT_BE_OPENED)
            }
    }
    val translated = LocalTranslatedImagePage.current(index)
    Box(modifier, contentAlignment = Alignment.Center) {
        val loaded = page
        when {
            loaded != null -> {
                val imageRequest = remember(loaded, translated, platformContext) {
                    if (translated != null) {
                        // the translated page is drawn from the plain image: no transform left to apply
                        ImageRequest.Builder(platformContext).data(translated).build()
                    } else {
                        ImageRequest.Builder(platformContext)
                            .data(loaded.bytes)
                            .readerImageTransform(
                                loaded.readerTransform,
                                loaded.readerTransform?.let { encodedImageHeight(loaded.bytes) },
                            )
                            .build()
                    }
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = contentDescription,
                    modifier = if (fitInsideViewport) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth().then(
                            decodedAspectRatio?.let { ratio ->
                                Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = false)
                            } ?: Modifier,
                        )
                    },
                    contentScale = ContentScale.Fit,
                    onSuccess = { success ->
                        decodedAspectRatio = imageSequencePageAspectRatio(
                            width = success.result.image.width,
                            height = success.result.image.height,
                        )
                    },
                )
            }
            error != null -> Text(
                text = requireNotNull(error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            // The page is read from a verified local blob, not requested from the network, so the
            // diagnostic is the processing time only.
            else -> ShinsouLoadingIndicator(Modifier.padding(24.dp))
        }
    }
}

/** Keeps decoded image pages proportional when their parent has an unbounded scrolling height. */
internal fun imageSequencePageAspectRatio(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    return width.toFloat().div(height.toFloat()).takeIf { it.isFinite() && it > 0f }
}

internal fun requestedImageSequencePageIndex(
    pageRequestSerial: Long,
    requestedPageIndex: Int?,
    pageCount: Int,
): Int? {
    if (pageRequestSerial <= 0L || requestedPageIndex == null || pageCount <= 0) return null
    return requestedPageIndex.coerceIn(0, pageCount - 1)
}

/**
 * Keeps an explicit page command independent from progress reported by an in-flight animation.
 * A mismatching progress callback belongs to the old visual position and must not replace the
 * newer target. Once the target is observed, later callbacks again follow manual navigation.
 */
internal data class UnifiedReaderPageRequest(
    val targetIndex: Int,
    val serial: Long = 0L,
    val pending: Boolean = false,
) {
    fun request(targetIndex: Int): UnifiedReaderPageRequest = copy(
        targetIndex = targetIndex,
        serial = serial + 1L,
        pending = true,
    )

    fun observe(pageIndex: Int): UnifiedReaderPageRequest = when {
        pending && pageIndex != targetIndex -> this
        else -> copy(targetIndex = pageIndex, pending = false)
    }

    fun navigationIndex(observedIndex: Int): Int = if (pending) targetIndex else observedIndex
}

internal fun imageSequenceNavigationTarget(
    currentIndex: Int,
    pageCount: Int,
    action: ReaderTapAction,
): Int? {
    if (pageCount <= 0) return null
    return when (action) {
        ReaderTapAction.PREVIOUS_PAGE -> (currentIndex - 1).takeIf { currentIndex > 0 }
        ReaderTapAction.NEXT_PAGE -> (currentIndex + 1).takeIf { currentIndex < pageCount - 1 }
        ReaderTapAction.TOGGLE_CHROME -> null
    }
}

/** Explicit reader search work; never invoked while the EPUB surface is painting its first frame. */
internal suspend fun indexEpubForReader(
    features: ContentFeatures,
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
                        StringKey.EPUB_RENDERING_IS_UNAVAILABLE_UNTIL_THIS_PLATFORM,
                    )
                is ContentRepresentation.ImageSequence ->
                    strings.text(StringKey.THIS_IMAGE_REPRESENTATION_HAS_NOT_BEEN_MATERIALIZED)
                is ContentRepresentation.PlainText -> error("Handled above")
            },
        )
    }
}

private fun blockRange(
    scope: dev.shinsou.kmp.domain.model.ReadingScope,
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

internal fun progression(offset: Int, length: Int): Double = if (length == 0) 0.0 else offset.toDouble() / length

internal fun safeBoundaryAtOrBefore(text: String, requested: Int): Int {
    var offset = requested.coerceIn(0, text.length)
    if (offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()) {
        offset--
    }
    return offset
}
