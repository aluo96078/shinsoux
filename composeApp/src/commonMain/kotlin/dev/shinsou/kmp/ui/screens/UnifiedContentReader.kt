package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.reader.EpubBrowserView
import dev.shinsou.kmp.reader.EpubRenderRequest
import dev.shinsou.kmp.reader.EpubResourceReadGate
import dev.shinsou.kmp.reader.EpubSemanticDocument
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.reader.ImageRenderPage
import dev.shinsou.kmp.reader.ImageSequenceNavigation
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
    onLocatorChanged: (ReadingLocator) -> Unit = {},
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

    // Keep the canonical body as the single backing string. Paragraph substrings and search
    // segment descriptors are created only for visible rows or after an explicit search submit,
    // never while composition is trying to draw the first LazyColumn items.
    val blocks = remember(representation) { representation.blocks }
    val initialBlockIndex = remember(session) {
        session.content.navigation.indexOf(session.content.initialLocator)?.coerceAtLeast(0) ?: 0
    }
    var selectedBlockIndex by remember(session) { mutableStateOf(initialBlockIndex) }
    var searchOpen by remember(session) { mutableStateOf(false) }
    var query by remember(session) { mutableStateOf("") }
    var searchHits by remember(session) { mutableStateOf<List<FullTextSearchHit>>(emptyList()) }
    var noteBlockIndex by remember(session) { mutableStateOf<Int?>(null) }
    var noteBody by remember(session) { mutableStateOf("") }
    var annotations by remember(session) { mutableStateOf<List<ContentAnnotation>>(emptyList()) }
    var busy by remember(session) { mutableStateOf(false) }
    var searchBusy by remember(session) { mutableStateOf(false) }
    var currentSearchIndexReady by remember(session) { mutableStateOf(false) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val readerListState = rememberLazyListState()

    LaunchedEffect(session, features, strings) {
        val loadedAnnotations = withContext(Dispatchers.Default) {
            features.cleanupRevokedDerivedData(session.content.navigation.scope, access)
            try {
                features.annotations.list(session.content.navigation.scope, access)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        }
        annotations = loadedAnnotations
    }
    LaunchedEffect(session, readerListState) {
        if (blocks.isNotEmpty()) readerListState.scrollToItem(initialBlockIndex.coerceIn(blocks.indices))
        snapshotFlow { readerListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index in blocks.indices) {
                    selectedBlockIndex = index
                    onLocatorChanged(session.content.navigation.locatorAt(index))
                }
            }
    }
    DisposableEffect(session, features) {
        onDispose { features.textToSpeech.stop() }
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { searchOpen = !searchOpen }) { Text(strings.text("Search text")) }
                    TextButton(
                        enabled = blocks.isNotEmpty() && !busy && features.speechCapability.available,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                try {
                                    for (index in selectedBlockIndex until blocks.size) {
                                        val block = blocks[index]
                                        val text = canonicalText.substring(block.startUtf16, block.endUtf16)
                                        if (text.isEmpty()) continue
                                        selectedBlockIndex = index
                                        val result = features.textToSpeech.speak(
                                            SpeakableTextDocument(
                                                scope = session.content.navigation.scope,
                                                resourceId = representation.resource.id,
                                                blockId = block.blockId,
                                                text = text,
                                                access = access,
                                                baseOffsetUtf16 = block.startUtf16,
                                                canonicalDocumentUtf16Length = canonicalText.length,
                                            ),
                                        )
                                        if (result.finalStatus != SpeechPlaybackStatus.COMPLETED) break
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    message = strings.text(
                                        "Speech stopped because it is unavailable or no longer permitted.",
                                    )
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(
                            when {
                                !features.speechCapability.available -> strings.text("Speech unavailable")
                                busy -> strings.text("Speaking…")
                                else -> strings.text("Speak from paragraph")
                            },
                        )
                    }
                    if (busy) TextButton(onClick = features.textToSpeech::stop) { Text(strings.text("Stop")) }
                }
                if (searchOpen) {
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
                            onClick = {
                                val submittedQuery = query
                                scope.launch {
                                    searchBusy = true
                                    message = null
                                    searchHits = try {
                                        val (indexReady, hits) = withContext(Dispatchers.Default) {
                                            if (!currentSearchIndexReady) {
                                                forEachReaderSearchSegment(
                                                    representationId = representation.representationId,
                                                    blocks = blocks,
                                                    canonicalText = canonicalText,
                                                ) { segment ->
                                                    val current = features.searchIndex.isCurrent(
                                                        documentId = segment.documentId,
                                                        scope = session.content.navigation.scope,
                                                        resourceId = representation.resource.id,
                                                        blockId = segment.blockId,
                                                        baseOffsetUtf16 = segment.startUtf16,
                                                        canonicalDocumentUtf16Length = canonicalText.length,
                                                        access = access,
                                                    )
                                                    if (!current) {
                                                        features.searchIndex.upsertForeground(
                                                            SearchableTextDocument(
                                                                documentId = segment.documentId,
                                                                scope = session.content.navigation.scope,
                                                                resourceId = representation.resource.id,
                                                                blockId = segment.blockId,
                                                                text = canonicalText.substring(
                                                                    segment.startUtf16,
                                                                    segment.endUtf16,
                                                                ),
                                                                access = access,
                                                                baseOffsetUtf16 = segment.startUtf16,
                                                                canonicalDocumentUtf16Length = canonicalText.length,
                                                            ),
                                                        )
                                                    }
                                                    // One bounded document owns one SQLite
                                                    // transaction; cancellation is observed before
                                                    // the next slice begins.
                                                }
                                            }
                                            true to features.searchIndex.searchForegroundInResource(
                                                query = submittedQuery,
                                                scope = session.content.navigation.scope,
                                                resourceId = representation.resource.id,
                                                limit = 100,
                                            )
                                        }
                                        currentSearchIndexReady = indexReady
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
                            },
                        ) {
                            if (searchBusy) {
                                CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                            } else {
                                Text(strings.text("Find"))
                            }
                        }
                    }
                    searchHits.forEach { hit ->
                        TextButton(
                            onClick = {
                                val target = representation.blocks.indexOfFirst {
                                    it.blockId == hit.locator.blockId
                                }.coerceAtLeast(0)
                                selectedBlockIndex = target
                                scope.launch { readerListState.animateScrollToItem(target) }
                            },
                        ) { Text(hit.snippet) }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 4.dp))
            }
        }

        LazyColumn(Modifier.fillMaxSize(), state = readerListState) {
            itemsIndexed(blocks, key = { _, item -> item.blockId }) { index, block ->
                val text = remember(canonicalText, block) {
                    canonicalText.substring(block.startUtf16, block.endUtf16)
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = text.isNotEmpty(),
                            onClick = {
                                selectedBlockIndex = index
                                noteBlockIndex = index
                                noteBody = ""
                            },
                        ) { Text(strings.text("Add note")) }
                        TextButton(
                            enabled = text.isNotEmpty(),
                            onClick = {
                                selectedBlockIndex = index
                                val copied = runCatching {
                                    features.operations.copy(access, text.length.toLong()) {
                                        copyText(strings.text("Reader paragraph"), text)
                                    }
                                }.getOrDefault(false)
                                if (!copied) message = strings.text("Copy is unavailable or not permitted.")
                            },
                        ) { Text(strings.text("Copy")) }
                        val count = annotations.count { it.range.start.let { locator ->
                            locator is ReadingLocator.Text && locator.blockId == block.blockId
                        } }
                        if (count > 0) {
                            val key = if (count == 1) "{0} note" else "{0} notes"
                            Text(strings.text(key, count))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    noteBlockIndex?.let { index ->
        val block = blocks[index]
        val text = canonicalText.substring(block.startUtf16, block.endUtf16)
        Surface(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text("Add paragraph note"), style = MaterialTheme.typography.titleLarge)
                Text(text.takeUtf16Safe(240))
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
                            val submittedBody = noteBody
                            scope.launch {
                                val created = try {
                                    withContext(Dispatchers.Default) {
                                        features.annotations.create(
                                            annotationId = randomAnnotationUuid(),
                                            kind = ContentAnnotationKind.NOTE,
                                            range = blockRange(
                                                scope = session.content.navigation.scope,
                                                representation = representation,
                                                block = block,
                                                canonicalText = canonicalText,
                                            ),
                                            access = access,
                                            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                            body = submittedBody,
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
                                    noteBlockIndex = null
                                }
                            }
                        },
                    ) { Text(strings.save) }
                    TextButton(onClick = { noteBlockIndex = null }) { Text(strings.cancel) }
                }
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
        onLocatorChanged(locator)
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !loading && currentIndex > 0,
                        onClick = { openLocator(navigation.locatorAt(currentIndex - 1)) },
                    ) { Text(strings.text("Previous")) }
                    Text("${currentIndex + 1} / ${navigation.itemCount}", Modifier.weight(1f))
                    TextButton(onClick = { searchOpen = !searchOpen }) {
                        Text(strings.text("Search text"))
                    }
                    TextButton(
                        enabled = semanticDocument != null && !speaking && features.speechCapability.available,
                        onClick = {
                            val semantic = semanticDocument ?: return@TextButton
                            scope.launch {
                                speaking = true
                                message = null
                                try {
                                    val startBlock = semantic.blockFor(currentLocator)
                                        ?: semantic.blocks.first()
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
                                    message = strings.text(
                                        "Speech stopped because it is unavailable or no longer permitted.",
                                    )
                                } finally {
                                    speaking = false
                                }
                            }
                        },
                    ) { Text(strings.text("Speak from paragraph")) }
                    if (speaking) TextButton(onClick = features.textToSpeech::stop) {
                        Text(strings.text("Stop"))
                    }
                    TextButton(
                        enabled = semanticDocument != null,
                        onClick = {
                            val existing = selectedRange
                            if (existing != null) {
                                noteRange = existing
                                noteBody = ""
                            } else {
                                selectionRequestKey++
                            }
                        },
                    ) { Text(strings.text("Add note")) }
                    TextButton(
                        enabled = !loading && currentIndex + 1 < navigation.itemCount,
                        onClick = { openLocator(navigation.locatorAt(currentIndex + 1)) },
                    ) { Text(strings.text("Next")) }
                }
                if (searchOpen) {
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
                            onClick = {
                                val submitted = query
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
                            },
                        ) { Text(strings.text("Find")) }
                    }
                    searchHits.forEach { hit ->
                        val locator = hit.locator as? ReadingLocator.Epub ?: return@forEach
                        TextButton(onClick = { openLocator(locator) }) { Text(hit.snippet) }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            request?.let { activeRequest ->
                EpubBrowserView(
                    request = activeRequest,
                    modifier = Modifier.fillMaxSize(),
                    selectionRequestKey = selectionRequestKey,
                    onLocatorChanged = { locator ->
                        currentLocator = locator
                        selectedRange = null
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
                    onError = { message = strings.text("The EPUB resources could not be opened.") },
                )
            }
            if (loading) CircularProgressIndicator()
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
