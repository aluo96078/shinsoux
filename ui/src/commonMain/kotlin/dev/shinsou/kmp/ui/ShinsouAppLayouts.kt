package dev.shinsou.kmp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.navigation.BackTarget
import dev.shinsou.kmp.navigation.Destination
import dev.shinsou.kmp.navigation.NavigationState
import dev.shinsou.kmp.navigation.PageEntry
import dev.shinsou.kmp.navigation.WideArrangement
import dev.shinsou.kmp.navigation.wideArrangement
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.browse.BrowseBackNavigation
import dev.shinsou.kmp.ui.browse.ExtensionLibraryHost
import dev.shinsou.kmp.ui.browse.ExtensionPublicationServices
import dev.shinsou.kmp.ui.browse.ExtensionReaderHost
import dev.shinsou.kmp.ui.browse.ExtensionV2PublicationPane
import dev.shinsou.kmp.ui.browse.PublicationFavorites
import dev.shinsou.kmp.ui.browse.PublicationPaneNavigation
import dev.shinsou.kmp.ui.browse.PublicationReaderEvents
import dev.shinsou.kmp.ui.browse.PublicationReaderReopen
import dev.shinsou.kmp.ui.settings.MoreDestination
import dev.shinsou.kmp.ui.browse.ExtensionFavoriteDestination

/** Back-swipe progress of the visible page, shared by the layouts. */
internal typealias BackSwipeProgress = Animatable<Float, AnimationVector1D>

/** Phones: a bottom bar, and pushed pages that slide over the still-mounted section. */
@Composable
internal fun PhoneShell(
    app: ShinsouAppState,
    nav: NavigationState,
    snapshot: AppSnapshot,
    snackbar: SnackbarHostState,
    backSwipe: BackSwipeProgress,
    readerOpen: Boolean,
) {
    Scaffold(
        snackbarHost = { if (!readerOpen) SnackbarHost(snackbar) },
        bottomBar = { if (!nav.browseReaderOpen) PhoneNavigationBar(app, nav) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val destination = nav.destination
            LaunchedEffect(
                nav.section,
                destination,
                nav.browseBackAvailable,
                nav.backRequests[BackTarget.BROWSE],
                nav.detailNestedBackAvailable,
                nav.moreNestedBackAvailable,
            ) {
                // A committed gesture has already moved the active surface off-screen. Reset after
                // its route (or deepest nested route) changes. Browse owns nested publication
                // routes, so its availability can stay true when a publication closes back to an
                // active source catalogue; the request key is the route-change signal then. (Back
                // counts never reset, so a change of section is a key of its own.)
                if (backSwipe.value > 0f) backSwipe.snapTo(0f)
            }
            // The section stays mounted beneath pushed pages: this makes the interactive edge
            // gesture possible and keeps a catalogue's source, query, page and results when a
            // title is opened and then dismissed.
            AppSectionPane(
                app = app,
                nav = nav,
                snapshot = snapshot,
                compactChrome = false,
                browseBackGestureProgress = if (destination == Destination.Section && nav.section == MainSection.BROWSE) {
                    backSwipe.value
                } else {
                    0f
                },
            )
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { phoneTransition(backSwipe.value) },
                label = "phone-navigation",
            ) { shown ->
                val edgeOffset = edgeOffset(shown, nav, backSwipe.value)
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * edgeOffset }) {
                    if (shown == Destination.Section) {
                        Spacer(Modifier.fillMaxSize())
                    } else {
                        // Pushed pages are opaque: the section beneath is only revealed where the
                        // page has actually been dragged away.
                        OpaquePage { PhonePushedPage(app, shown, nav, snapshot, backSwipe) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneNavigationBar(app: ShinsouAppState, nav: NavigationState) {
    val strings = LocalShinsouStrings.current
    NavigationBar {
        navigationItems(strings).forEach { item ->
            NavigationBarItem(
                selected = nav.section == item.section && nav.selectedMangaId == null && nav.moreDestination == null,
                onClick = { app.selectSection(item.section) },
                icon = {
                    Icon(
                        if (nav.section == item.section) item.selectedIcon else item.icon,
                        contentDescription = item.label,
                    )
                },
                label = { Text(item.label, maxLines = 1) },
            )
        }
    }
}

/** How far [shown] follows the back-swipe; a page with its own nested Back stays put. */
private fun edgeOffset(shown: Destination, nav: NavigationState, backSwipe: Float): Float = when (shown) {
    Destination.Section -> 0f
    is Destination.Manga -> if (nav.detailNestedBackAvailable) 0f else backSwipe
    is Destination.More -> if (nav.moreNestedBackAvailable) 0f else backSwipe
    else -> backSwipe
}

private fun AnimatedContentTransitionScope<Destination>.phoneTransition(backSwipe: Float): ContentTransform {
    val returning = targetState is Destination.Section && initialState !is Destination.Section
    val interactiveReturn = returning && backSwipe >= 0.99f
    val resolvedPendingTitle = initialState is Destination.Pending && targetState is Destination.Manga
    return when {
        // The gesture itself completed the pop: AnimatedContent's exit would jump the page back
        // and slide it out twice. Likewise a pending shell replaced by its detail record is the
        // same page, not a second navigation.
        interactiveReturn || resolvedPendingTitle -> EnterTransition.None togetherWith ExitTransition.None
        returning -> (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(150)))
        else -> (slideInHorizontally(tween(220)) { it } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(150)))
    }
}

@Composable
private fun PhonePushedPage(
    app: ShinsouAppState,
    destination: Destination,
    nav: NavigationState,
    snapshot: AppSnapshot,
    backSwipe: BackSwipeProgress,
) {
    when (destination) {
        is Destination.Recovering -> recoveringItem(app, destination.mangaId)?.let { item ->
            PendingMangaDetailPane(item = item, onBack = app.navigator::cancelRecovery)
        }
        is Destination.LocalExtension -> LocalExtensionPane(app, destination.item, nav, snapshot)
        is Destination.Pending -> PendingMangaDetailPane(item = destination.item, onBack = app.navigator::cancelPendingBrowse)
        is Destination.Manga -> AppDetailPane(app, destination.mangaId, nav, snapshot, wideLayout = false)
        is Destination.More -> AppMorePane(
            app = app,
            destination = destination.destination,
            nav = nav,
            snapshot = snapshot,
            wideLayout = false,
            backGestureProgress = if (nav.moreNestedBackAvailable) backSwipe.value else 0f,
        )
        Destination.Section -> Unit
    }
}

/** Tablets and desktops: a sidebar, the section, and a detail pane beside it when there is room. */
@Composable
internal fun WideShell(
    app: ShinsouAppState,
    nav: NavigationState,
    snapshot: AppSnapshot,
    backSwipe: BackSwipeProgress,
    windowWidth: Dp,
    splitDetail: Boolean,
) {
    Row(Modifier.fillMaxSize()) {
        if (!nav.browseReaderOpen) {
            DesktopSidebar(
                selected = nav.section,
                onSelect = app::selectSection,
                downloadCount = pendingDownloadCount(app.repository, app.appServices.browse),
                modifier = Modifier.width(228.dp).fillMaxHeight(),
            )
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
        }
        // 0008: what is drawn, and in front of what, comes from the same PageLayer order Back uses.
        val arrangement = wideArrangement(nav, splitDetail)
        val publicationCoversMain = arrangement.side != null && !arrangement.sideBeside
        val mainModifier = if (publicationCoversMain) {
            Modifier.width(0.dp).fillMaxHeight()
        } else {
            Modifier.weight(if (arrangement.sideBeside && arrangement.side != null) 0.9f else 1f).fillMaxHeight()
        }
        Box(mainModifier) {
            WideMainPane(app, nav, snapshot, backSwipe, arrangement, windowWidth)
        }
        WideDetailColumn(app, nav, snapshot, arrangement)
    }
}

/**
 * The main area: the More page when one is open, else the section. The browse surface stays
 * mounted while a source title resolves or its details are open: replacing it used to reset the
 * active catalogue, so closing a title jumped back to the extension list instead of the previous
 * results. The arrangement's cover page is drawn over either.
 */
@Composable
private fun WideMainPane(
    app: ShinsouAppState,
    nav: NavigationState,
    snapshot: AppSnapshot,
    backSwipe: BackSwipeProgress,
    arrangement: WideArrangement,
    windowWidth: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        val moreDestination = nav.moreDestination
        if (moreDestination != null) {
            AppMorePane(
                app = app,
                destination = moreDestination,
                nav = nav,
                snapshot = snapshot,
                wideLayout = windowWidth >= 1100.dp,
                backGestureProgress = if (nav.moreNestedBackAvailable) backSwipe.value else 0f,
            )
        } else {
            AppSectionPane(
                app = app,
                nav = nav,
                snapshot = snapshot,
                compactChrome = true,
                browseBackGestureProgress = if (nav.section == MainSection.BROWSE) backSwipe.value else 0f,
            )
        }
        when (val cover = arrangement.cover) {
            is PageEntry.Pending -> OpaquePage {
                PendingMangaDetailPane(item = cover.item, onBack = app.navigator::cancelPendingBrowse)
            }
            is PageEntry.Recovering -> recoveringItem(app, cover.mangaId)?.let { item ->
                OpaquePage { PendingMangaDetailPane(item = item, onBack = app.navigator::cancelRecovery) }
            }
            is PageEntry.Detail -> OpaquePage {
                AppDetailPane(app, cover.mangaId, nav, snapshot, wideLayout = true)
            }
            else -> Unit
        }
    }
}

@Composable
private fun RowScope.WideDetailColumn(
    app: ShinsouAppState,
    nav: NavigationState,
    snapshot: AppSnapshot,
    arrangement: WideArrangement,
) {
    val side = arrangement.side
    val besideMain = arrangement.sideBeside
    when (side) {
        is PageEntry.LocalExtension -> {
            if (besideMain) VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            Surface(
                modifier = Modifier.weight(if (besideMain) 1.1f else 1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                LocalExtensionPane(app, side.item, nav, snapshot)
            }
        }
        is PageEntry.Detail -> {
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            Surface(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                AppDetailPane(app, side.mangaId, nav, snapshot, wideLayout = true)
            }
        }
        else -> Unit
    }
}

/** A page drawn over the section: opaque, in the app's background colour. */
@Composable
private fun OpaquePage(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        content = content,
    )
}

@Composable
private fun AppSectionPane(
    app: ShinsouAppState,
    nav: NavigationState,
    snapshot: AppSnapshot,
    compactChrome: Boolean,
    browseBackGestureProgress: Float,
) {
    val selection by app.selection.state.collectAsState()
    SectionPane(
        section = nav.section,
        snapshot = snapshot,
        repository = app.repository,
        appServices = app.appServices,
        compactChrome = compactChrome,
        selectedCategoryId = selection.categoryId,
        onCategorySelected = app.selection::selectCategory,
        selectedLibraryIds = selection.libraryIds,
        onLibrarySelectionChange = app.selection::selectLibraryTitles,
        onOpenManga = app::openManga,
        onOpenBrowseManga = app::openBrowseManga,
        onReadChapter = app::openReader,
        onContinueExtensionUnit = app::continueExtensionUnit,
        onOpenMore = app::openMore,
        onLibraryTitlesRemoved = app::closeRemovedLibraryTitles,
        browse = BrowseSectionHooks(
            navigation = BrowseBackNavigation(nav.backRequests[BackTarget.BROWSE], browseBackGestureProgress),
            onBackAvailabilityChanged = app.navigator::setBrowseBackAvailable,
            onParentDismissed = app.navigator::closeBrowseParentDetail,
            onReaderVisibilityChanged = app.navigator::setBrowseReaderOpen,
            onReaderProgress = app::recordV2ReaderProgress,
            onReaderProgressFlushed = app.v2Progress::flushLocal,
            volumeKeyRouter = app.volumeKeyRouter,
            library = ExtensionLibraryHost(app.localLibrary::setFavorite, app.localLibrary::isFavorite),
        ),
        actions = app.actions,
        workDispatcher = app.environment.workDispatcher,
    )
}

@Composable
private fun AppDetailPane(
    app: ShinsouAppState,
    mangaId: Long,
    nav: NavigationState,
    snapshot: AppSnapshot,
    wideLayout: Boolean,
) {
    val selection by app.selection.state.collectAsState()
    DetailPane(
        mangaId = mangaId,
        snapshot = snapshot,
        repository = app.repository,
        appServices = app.appServices,
        wideLayout = wideLayout,
        systemBackRequest = nav.backRequests[BackTarget.DETAIL],
        onNestedBackAvailabilityChanged = app.navigator::setDetailNestedBackAvailable,
        selectedChapterIds = selection.chapterIds,
        onSelectionChange = app.selection::selectChapters,
        onBack = app.navigator::closeDetail,
        onRead = app::openReader,
        actions = app.actions,
        workDispatcher = app.environment.workDispatcher,
        onUnfavorited = { app.navigator.closeDetailOf(mangaId) },
    )
}

@Composable
private fun AppMorePane(
    app: ShinsouAppState,
    destination: MoreDestination,
    nav: NavigationState,
    snapshot: AppSnapshot,
    wideLayout: Boolean,
    backGestureProgress: Float,
) {
    val downloadsPaused by app.downloadsPaused.collectAsState()
    MoreDestinationPane(
        destination = destination,
        snapshot = snapshot,
        repository = app.repository,
        appServices = app.appServices,
        securityCapabilities = app.appServices.securityCapabilities,
        autoBackupService = app.autoBackupService,
        wideLayout = wideLayout,
        systemBackRequest = nav.backRequests[BackTarget.MORE],
        backGestureProgress = backGestureProgress,
        onNestedBackAvailabilityChanged = app.navigator::setMoreNestedBackAvailable,
        downloadsPaused = downloadsPaused,
        onDownloadsPausedChange = { app.downloadsPaused.value = it },
        onOpenDestination = app.navigator::setMoreDestination,
        onBack = { app.navigator.setMoreDestination(null) },
        actions = app.actions,
    )
}

@Composable
private fun LocalExtensionPane(
    app: ShinsouAppState,
    item: BrowseManga,
    nav: NavigationState,
    snapshot: AppSnapshot,
) {
    val browse = app.appServices.browse
    val refreshInvalidations by browse.sourceRefreshInvalidations.collectAsState()
    ExtensionV2PublicationPane(
        services = ExtensionPublicationServices(browse, browse, browse, browse),
        item = item,
        favorites = PublicationFavorites(
            supportsFavorite = true,
            destination = ExtensionFavoriteDestination.LOCAL_LIBRARY,
            localLibrary = true,
            localLibraryFavorite = app.localLibrary.isFavorite(item),
        ),
        reader = ExtensionReaderHost(
            contentFeatures = app.appServices.contentFeatures,
            readerSettings = snapshot.settings.reader,
            onReaderSettingsChange = app::updateReaderSettings,
            copyText = app.appServices::copyText,
            openExternalUrl = app.appServices::openExternalUrl,
            shareText = app.appServices::shareText,
            onReaderProgress = app::recordV2ReaderProgress,
            onReaderProgressFlushed = app.v2Progress::flushLocal,
            volumeKeyRouter = app.volumeKeyRouter,
            library = ExtensionLibraryHost(app.localLibrary::setFavorite, app.localLibrary::isFavorite),
            workDispatcher = app.environment.workDispatcher,
        ),
        navigation = PublicationPaneNavigation(
            refreshGeneration = item.sourceKey?.let(refreshInvalidations::get) ?: 0L,
            readerBackRequest = nav.backRequests[BackTarget.LOCAL_EXTENSION_READER],
            onBack = app.navigator::closeLocalExtension,
            reader = PublicationReaderEvents(
                onVisibilityChanged = app.navigator::setBrowseReaderOpen,
                onUnitChanged = { unitId -> app.onLocalExtensionReaderUnit(item, unitId) },
                isShown = { app.navigator.state.value.localLibraryPublication?.identityKey == item.identityKey },
            ),
            // One request object per pending chapter: the page handles each request once.
            reopen = app.pendingLocalExtensionUnit?.takeIf { app.pendingReopenFor(item) != null }.let { wanted ->
                remember(wanted) {
                    wanted?.let { pending ->
                        PublicationReaderReopen(
                            unit = pending.unit,
                            userRequested = pending.userRequested,
                            // Opening a title (this one again included) gives the saved chapter up.
                            stillWanted = { app.pendingLocalExtensionUnit === pending },
                            onHandled = { app.localExtensionUnitReopened(pending) },
                        )
                    }
                }
            },
        ),
        sync = app.appServices.sync,
    )
}

/** The placeholder shown while a beta-era favorite [mangaId] is repaired; null once it is gone. */
private fun recoveringItem(app: ShinsouAppState, mangaId: Long): BrowseManga? =
    app.repository.currentSnapshot.mangas.firstOrNull { it.id == mangaId }?.let { recovering ->
        BrowseManga(
            sourceId = LOCAL_SOURCE_ID,
            url = recovering.url,
            title = recovering.title,
            thumbnailUrl = recovering.thumbnailUrl,
        )
    }
