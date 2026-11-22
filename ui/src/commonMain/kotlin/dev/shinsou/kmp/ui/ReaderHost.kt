package dev.shinsou.kmp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.manga.MangaChapterTranslation
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.buildReaderChapterNavigation
import dev.shinsou.kmp.translation.TranslationLabels
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.library.CategoryPickerDialog
import dev.shinsou.kmp.ui.reader.MangaReaderTranslationState
import dev.shinsou.kmp.ui.reader.MangaTranslationNotice
import dev.shinsou.kmp.ui.reader.MangaTranslationSheet
import dev.shinsou.kmp.ui.reader.PageReaderTranslation
import dev.shinsou.kmp.ui.reader.ReaderScreen
import dev.shinsou.kmp.ui.reader.ReaderScreenCallbacks
import dev.shinsou.kmp.ui.reader.ReaderScreenContent
import dev.shinsou.kmp.ui.reader.ReaderScreenSession
import dev.shinsou.kmp.ui.reader.UnifiedReaderSlot
import dev.shinsou.kmp.ui.reader.UnifiedContentReader
import dev.shinsou.kmp.ui.reader.UnifiedReaderRenderState
import dev.shinsou.kmp.ui.reader.mangaChapterKey
import dev.shinsou.kmp.ui.reader.mangaTranslationAvailable
import dev.shinsou.kmp.ui.reader.mangaWorkKey
import dev.shinsou.kmp.ui.reader.readerTranslationAvailable
import dev.shinsou.kmp.ui.reader.rememberMangaReaderTranslation
import kotlinx.coroutines.launch
import dev.shinsou.kmp.ui.reader.ContentFeatures

/** The page reader of [ShinsouAppState.reader], over everything else while a session is open. */
@Composable
internal fun ReaderHost(app: ShinsouAppState, snapshot: AppSnapshot) {
    val readerState by app.reader.state.collectAsState()
    val session = readerState.session ?: return
    val manga = snapshot.mangas.firstOrNull { it.id == session.mangaId }
    val chapter = snapshot.chapters.firstOrNull { it.id == session.chapterId }
    if (manga == null || chapter == null) {
        // The title or chapter left the library while it was open.
        SideEffect { app.reader.dropSession(session) }
    } else {
        ReaderSessionContent(app, snapshot, readerState, session, manga, chapter)
    }
}

@Composable
private fun ReaderSessionContent(
    app: ShinsouAppState,
    snapshot: AppSnapshot,
    readerState: ReaderState,
    session: ReaderSession,
    manga: Manga,
    chapter: Chapter,
) {
    val strings = LocalShinsouStrings.current
    val activeChapter = readerState.activeChapter
    val typedSession = activeChapter.typedSession
    val navigation = buildReaderChapterNavigation(
        chapters = snapshot.chapters.filter { it.mangaId == manga.id },
        currentChapterId = chapter.id,
        manga = manga,
        settings = snapshot.settings.reader,
        downloadedChapterIds = snapshot.downloadQueue
            .asSequence()
            .filter { it.state == DownloadState.DOWNLOADED }
            .mapTo(mutableSetOf()) { it.chapterId },
    )
    LaunchedEffect(session) { app.reader.markPositionInitialized(session) }
    var mangaTranslationOpen by remember(session) { mutableStateOf(false) }
    // ADR-0005: manga translation for the page reader (the typed reader has its own).
    val mangaTranslationState = rememberPageReaderTranslation(app, manga, chapter, activeChapter, typedSession)
    mangaTranslationState?.let { state ->
        MangaTranslationNotice(state, onOpen = { mangaTranslationOpen = true }, modifier = Modifier.zIndex(6f))
        if (mangaTranslationOpen) MangaTranslationSheet(state, onDismiss = { mangaTranslationOpen = false })
    }
    val incognito = snapshot.settings.security.incognitoMode
    val translationAvailable = rememberTypedTranslationAvailable(app.appServices.contentFeatures, app.appServices.mangaTranslation, typedSession)
    ReaderScreen(
        content = ReaderScreenContent(
            manga = manga,
            chapter = chapter,
            pages = mangaTranslationState?.displayPages(activeChapter.pages) ?: activeChapter.pages,
            loading = readerState.showsLoading,
            errorMessage = readerState.error?.message(strings),
            inLibrary = manga.favorite,
            chapters = navigation.storyOrder,
            previousChapter = navigation.previous,
            nextChapter = navigation.next,
        ),
        session = ReaderScreenSession(
            id = session.progressSessionId,
            initialPosition = rememberRestoredPosition(session, readerState, typedSession, snapshot.settings.reader.readingMode),
            positionReportingEnabled = readerState.positionInitializedFor == session,
            systemBackRequest = readerState.backRequest,
        ),
        settings = snapshot.settings.reader,
        callbacks = ReaderScreenCallbacks(
            // close, not transition: Back while moving to another chapter closes instead of being ignored.
            onClose = app.reader::close,
            onRetry = app.reader::retry,
            onOpenWeb = chapterWebAction(app, manga, chapter),
            onSettingsChange = app::updateReaderSettings,
            onPositionChanged = { position ->
                val completedNow = app.reader.recordPagePosition(
                    session = session,
                    chapter = chapter,
                    pageIndex = position.pageIndex,
                    pageCount = activeChapter.pages.size,
                    incognito = incognito,
                )
                if (completedNow) app.mutate { app.afterChapterRead(manga, chapter, navigation.storyOrder) }
            },
            onToggleFavorite = { app.mutate { app.toggleFavoriteInReader(manga) } },
            onPreviousChapter = { navigation.previous?.let { app.reader.transition(manga.id, it.id) } },
            onNextChapter = { navigation.next?.let { app.reader.transition(manga.id, it.id) } },
            onChapterSelected = { chapterId -> app.reader.transition(manga.id, chapterId) },
        ),
        modifier = Modifier.zIndex(5f),
        unified = typedSession?.let { typed ->
            UnifiedReaderSlot(
                content = typed.content,
                initialPageIndex = typed.initialVisualPageIndex,
                initialPageCount = typed.initialVisualPageCount,
                translationAvailable = translationAvailable,
                renderer = { _, rendererModifier, renderState, onPageIndexChanged, onPageCountInvalidated,
                    onNavigationBoundary, onReaderTap ->
                    TypedReaderRenderer(
                        app = app,
                        typed = typed,
                        session = session,
                        manga = manga,
                        chapter = chapter,
                        incognito = incognito,
                        modifier = rendererModifier,
                        renderState = renderState,
                        callbacks = TypedReaderCallbacks(
                            onPageIndexChanged,
                            onPageCountInvalidated,
                            onNavigationBoundary,
                            onReaderTap,
                        ),
                    )
                },
            )
        },
        translation = PageReaderTranslation(
            onOpen = mangaTranslationState?.let { { mangaTranslationOpen = true } },
            onToggle = mangaTranslationState?.let { state -> mangaTranslationToggle(app, state) },
            active = mangaTranslationState?.settings?.shown == true,
            sheetOpen = mangaTranslationOpen,
        ),
        volumeKeyRouter = app.volumeKeyRouter,
    )
    if (readerState.categoryPickerMangaId == manga.id) ReaderCategoryPicker(app, snapshot, manga)
}

/**
 * The page a session restored after process death reopens at (page reader only: typed content
 * restores from its stored locator); null for a session opened anew. Fixed for the session, so a
 * reading-mode change does not move the reader back to it.
 */
@Composable
private fun rememberRestoredPosition(
    session: ReaderSession,
    readerState: ReaderState,
    typedSession: TypedReaderContentSession?,
    readingMode: dev.shinsou.kmp.domain.model.ReadingMode,
): dev.shinsou.kmp.reader.ReaderPosition? {
    val page = readerState.restoredPageIndex?.takeIf { typedSession == null }
    return remember(session, page) { page?.let { dev.shinsou.kmp.reader.ReaderPosition(readingMode, it) } }
}

/** Adding a title from the reader asks for its categories when the settings say so. */
@Composable
private fun ReaderCategoryPicker(app: ShinsouAppState, snapshot: AppSnapshot, manga: Manga) {
    val strings = LocalShinsouStrings.current
    CategoryPickerDialog(
        categories = snapshot.categories,
        selectedCategoryIds = app.repository.categoriesForManga(manga.id).mapTo(linkedSetOf()) { it.id },
        title = strings.text(StringKey.SET_CATEGORIES),
        onDismiss = { app.reader.dismissCategoryPicker() },
        onConfirm = { categoryIds ->
            app.mutate {
                app.actions.stores { app.repository.setMangaCategories(manga.id, categoryIds) }
                app.reader.dismissCategoryPicker()
            }
        },
    )
}

private fun ReaderLoadError.message(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): String = when (this) {
    is ReaderLoadError.Failed -> cause.localizedSourceFailure(strings)
        ?: cause.message ?: strings.text(StringKey.UNABLE_TO_LOAD_CHAPTER_PAGES)
    ReaderLoadError.NoLongerAvailable -> strings.text(StringKey.THIS_CONTENT_IS_NO_LONGER_AVAILABLE_UNDER)
}

/** Opens the chapter's page in the browser; null for local chapters and chapters without one. */
private fun chapterWebAction(app: ShinsouAppState, manga: Manga, chapter: Chapter): (() -> Unit)? =
    if (manga.source == LOCAL_SOURCE_ID || chapter.url.isBlank()) null else { { app.openChapterOnWeb(manga, chapter) } }

private fun mangaTranslationToggle(app: ShinsouAppState, state: MangaReaderTranslationState): () -> Unit = {
    state.settings?.let { current -> app.environment.scope.launch { state.update(current.toggled()) } }
}

/** True when the typed chapter can be translated: text, or (ADR-0005) image chapters through manga translation. */
@Composable
private fun rememberTypedTranslationAvailable(
    features: ContentFeatures?,
    manga: MangaChapterTranslation?,
    typed: TypedReaderContentSession?,
): Boolean {
    return remember(typed, features, manga) {
        typed != null &&
            (readerTranslationAvailable(typed, features) || (manga != null && mangaTranslationAvailable(typed, features)))
    }
}

/** Manga translation of the page reader; null for typed content or where translation is unavailable. */
@Composable
private fun rememberPageReaderTranslation(
    app: ShinsouAppState,
    manga: Manga,
    chapter: Chapter,
    activeChapter: ReaderChapter,
    typedSession: TypedReaderContentSession?,
): MangaReaderTranslationState? {
    val mangaTranslation = app.appServices.mangaTranslation
    val textTranslation = app.appServices.contentFeatures?.translation
    if (typedSession != null || mangaTranslation == null || textTranslation == null) return null
    val strings = LocalShinsouStrings.current
    return rememberMangaReaderTranslation(
        manga = mangaTranslation,
        translation = textTranslation,
        workKey = mangaWorkKey(manga),
        chapterKey = mangaChapterKey(chapter),
        pages = activeChapter.pages,
        exportable = false,
        // "all", "multi" and local imports say nothing: their first pages are read instead
        workLanguage = rememberSourceLanguage(app.appServices.browse, manga.source),
        labels = remember(manga.title, chapter.name, manga.source) {
            TranslationLabels(
                workTitle = manga.title,
                chapterTitle = chapter.name,
                sourceName = if (manga.source == LOCAL_SOURCE_ID) strings.text(StringKey.LOCAL) else null,
            )
        },
    )
}

/** The callbacks [ReaderScreen] hands its typed-content renderer. */
private class TypedReaderCallbacks(
    val onPageIndexChanged: (Int, Int) -> Unit,
    val onPageCountInvalidated: () -> Unit,
    val onNavigationBoundary: (ReaderTapAction) -> Unit,
    val onReaderTap: (ReaderTapAction) -> Unit,
)

@Composable
private fun TypedReaderRenderer(
    app: ShinsouAppState,
    typed: TypedReaderContentSession,
    session: ReaderSession,
    manga: Manga,
    chapter: Chapter,
    incognito: Boolean,
    modifier: Modifier,
    renderState: UnifiedReaderRenderState,
    callbacks: TypedReaderCallbacks,
) {
    val strings = LocalShinsouStrings.current
    val appServices = app.appServices
    val features = appServices.contentFeatures
    if (features == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.text(StringKey.PROTECTED_READER_FEATURES_ARE_UNAVAILABLE))
        }
        return
    }
    UnifiedContentReader(
        session = typed,
        features = features,
        copyText = appServices::copyText,
        settings = renderState.settings,
        requestedPageIndex = renderState.requestedPageIndex,
        pageRequestSerial = renderState.pageRequestSerial,
        navigationAction = renderState.navigationAction,
        navigationRequestKey = renderState.navigationRequestKey,
        readerControlsVisible = renderState.controlsVisible,
        translationRequestSerial = renderState.translationRequestSerial,
        translationToggleSerial = renderState.translationToggleSerial,
        onTranslationActiveChanged = renderState.onTranslationActiveChanged,
        mangaTranslation = appServices.mangaTranslation,
        // "all", "multi" and local imports say nothing: their first pages are read instead
        workLanguage = rememberSourceLanguage(appServices.browse, manga.source),
        translationLabels = remember(manga.title, chapter.name, manga.source) {
            TranslationLabels(
                workTitle = manga.title,
                chapterTitle = chapter.name,
                sourceName = if (manga.source == LOCAL_SOURCE_ID) strings.text(StringKey.LOCAL) else null,
            )
        },
        onPageIndexChanged = callbacks.onPageIndexChanged,
        onPageCountInvalidated = callbacks.onPageCountInvalidated,
        onNavigationBoundary = callbacks.onNavigationBoundary,
        onReaderTap = callbacks.onReaderTap,
        onLocatorChanged = { locator, pageIndex, pageCount ->
            app.reader.recordLocator(
                session = session,
                chapter = chapter,
                navigation = typed.content.navigation,
                position = ReaderLocatorPosition(locator, pageIndex, pageCount),
                incognito = incognito,
            )
        },
        modifier = modifier,
    )
}
