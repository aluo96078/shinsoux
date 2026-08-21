package dev.shinsou.kmp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.shinsou.kmp.backup.AutoBackupEntry
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.BackupV2ArchiveSource
import dev.shinsou.kmp.backup.DEFAULT_MAX_ARCHIVE_BYTES
import dev.shinsou.kmp.backup.SnapshotRestoreTarget
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.SnapshotBackupService
import dev.shinsou.kmp.backup.createBackupEnvelope
import dev.shinsou.kmp.backup.restoreBackup
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.download.downloadIdsToRemoveAfterMarkedRead
import dev.shinsou.kmp.domain.model.shouldAskForCategoriesOnFavorite
import dev.shinsou.kmp.local.LOCAL_CONTENT_EXTENSIONS
import dev.shinsou.kmp.local.LOCAL_IMPORTED_DOCUMENT_LIMITS
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.migration.shuyue.ShuYueBackupV1Limits
import dev.shinsou.kmp.reader.buildReaderChapterNavigation
import dev.shinsou.kmp.reader.ReaderPositionUpdateDecision
import dev.shinsou.kmp.reader.readerPositionUpdateDecision
import dev.shinsou.kmp.reader.readerTrackingProgress
import dev.shinsou.kmp.reader.readerStoryOrderComparator
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.ContentProgressKeyV2
import dev.shinsou.kmp.sync.v2.ReaderPosition
import dev.shinsou.kmp.sync.v2.ReaderProgressReporter
import dev.shinsou.kmp.sync.v2.ReadingPositionRegister
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.syncChapterEntityKey
import dev.shinsou.kmp.sync.v2.syncMangaEntityKey
import dev.shinsou.kmp.sync.provisioning.asProvisioningControllerInput
import dev.shinsou.kmp.tracking.TrackUpdate
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ProvideShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.screens.AboutScreen
import dev.shinsou.kmp.ui.screens.ALL_LIBRARY_CATEGORY_ID
import dev.shinsou.kmp.ui.screens.BackupScreen
import dev.shinsou.kmp.ui.screens.BrowseScreen
import dev.shinsou.kmp.ui.screens.MAX_COOKIE_FILE_BYTES
import dev.shinsou.kmp.ui.screens.CategoryPickerDialog
import dev.shinsou.kmp.ui.screens.ContentBackupV2Screen
import dev.shinsou.kmp.ui.screens.DownloadsScreen
import dev.shinsou.kmp.ui.screens.HistoryScreen
import dev.shinsou.kmp.ui.screens.LibraryScreen
import dev.shinsou.kmp.ui.screens.MangaDetailScreen
import dev.shinsou.kmp.ui.screens.MoreDestination
import dev.shinsou.kmp.ui.screens.MoreScreen
import dev.shinsou.kmp.ui.screens.ReaderScreen
import dev.shinsou.kmp.ui.screens.SettingsScreen
import dev.shinsou.kmp.ui.screens.ShuYueMigrationScreen
import dev.shinsou.kmp.ui.screens.SourceLoginDialog
import dev.shinsou.kmp.ui.screens.StatisticsScreen
import dev.shinsou.kmp.ui.screens.TrackingSheet
import dev.shinsou.kmp.ui.screens.UpdatesScreen
import dev.shinsou.kmp.ui.screens.UnifiedContentReader
import dev.shinsou.kmp.ui.theme.ShinsouTheme
import dev.shinsou.kmp.ui.theme.ShinsouThemeMode
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** Primary, platform-neutral composition entry point. */
@Composable
fun ShinsouApp(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    autoBackupService: AutoBackupService? = null,
    readerProgressReporter: ReaderProgressReporter? = null,
    interactionReady: Boolean = true,
) {
    val snapshot by repository.snapshot.collectAsState()
    ProvideShinsouStrings(snapshot.settings.general.languagePreference) {
        ShinsouTheme(
            mode = snapshot.settings.appearance.theme.toUiTheme(),
            amoled = snapshot.settings.appearance.amoledDark,
            compact = appServices.prefersDesktopChrome,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (interactionReady) {
                    CompositionLocalProvider(
                        LocalMobileKeyboardDismissEnabled provides !appServices.prefersDesktopChrome,
                    ) {
                        ShinsouAppContent(
                            repository = repository,
                            snapshot = snapshot,
                            appServices = appServices,
                            autoBackupService = autoBackupService,
                            readerProgressReporter = readerProgressReporter,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                LaunchAnimationOverlay()
            }
        }
    }
}

/**
 * A short visual hand-off from the native launch screen to the already-composed app.
 *
 * The app content is deliberately rendered underneath this overlay from the first frame. The
 * animation follows Compose's motion-duration scale, so reduced-motion settings remove it without
 * adding a fixed startup delay.
 */
@Composable
private fun LaunchAnimationOverlay() {
    var visible by remember { mutableStateOf(true) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 700,
                easing = LinearEasing,
            ),
        )
        visible = false
    }

    if (!visible) return

    val timeline = progress.value
    val overlayAlpha = ((1f - timeline) / 0.32f).coerceIn(0f, 1f)
    val logoAlpha = (timeline / 0.20f).coerceIn(0f, 1f)
    val scaleProgress = FastOutSlowInEasing.transform((timeline / 0.68f).coerceIn(0f, 1f))
    val logoScale = 0.84f + (0.16f * scaleProgress)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f)
            .graphicsLayer { alpha = overlayAlpha }
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.shinsou_icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(128.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                }
                .clip(RoundedCornerShape(28.dp)),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ShinsouAppContent(
    repository: ShinsouRepository,
    snapshot: AppSnapshot,
    appServices: ShinsouAppServices,
    autoBackupService: AutoBackupService?,
    readerProgressReporter: ReaderProgressReporter?,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    var section by remember { mutableStateOf(snapshot.settings.general.defaultStartingScreen) }
    var selectedMangaId by remember { mutableStateOf<Long?>(null) }
    var readerSession by remember { mutableStateOf<ReaderSession?>(null) }
    var readerChapter by remember { mutableStateOf(ReaderChapter()) }
    var readerChapterSession by remember { mutableStateOf<ReaderSession?>(null) }
    var readerLoading by remember { mutableStateOf(false) }
    var readerError by remember { mutableStateOf<String?>(null) }
    var readerReloadKey by remember { mutableStateOf(0) }
    var readerSessionSerial by remember { mutableStateOf(0L) }
    var readerTransitionInFlight by remember { mutableStateOf(false) }
    var readerPositionInitializedFor by remember { mutableStateOf<ReaderSession?>(null) }
    var readerInitialPosition by remember { mutableStateOf<ReaderPosition?>(null) }
    var readerLastObservedHlc by remember { mutableStateOf<HlcTimestamp?>(null) }
    var readerRemotePosition by remember { mutableStateOf<ReadingPositionRegister?>(null) }
    var readerCategoryPickerMangaId by remember { mutableStateOf<Long?>(null) }
    var readerBackRequest by remember { mutableStateOf(0L) }
    var detailBackRequest by remember { mutableStateOf(0L) }
    var detailNestedBackAvailable by remember { mutableStateOf(false) }
    var moreDestination by remember { mutableStateOf<MoreDestination?>(null) }
    var moreBackRequest by remember { mutableStateOf(0L) }
    var moreNestedBackAvailable by remember { mutableStateOf(false) }
    var browseBackRequest by remember { mutableStateOf(0L) }
    var browseBackAvailable by remember { mutableStateOf(false) }
    var pendingBrowseManga by remember { mutableStateOf<BrowseManga?>(null) }
    var pendingBrowseRequest by remember { mutableStateOf(0L) }
    val backSwipeProgress = remember { Animatable(0f) }
    var selectedCategoryId by remember { mutableStateOf(ALL_LIBRARY_CATEGORY_ID) }
    val appFocusRequester = remember { FocusRequester() }
    var hardwareBackKeyHeld by remember { mutableStateOf(false) }
    var selectedLibraryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedChapterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var downloadsPaused by remember { mutableStateOf(false) }
    val securityCapabilities = appServices.securityCapabilities
    val appLockAuthenticationAvailable = securityCapabilities.appLock.available
    val effectiveAppLockEnabled = securityFeatureEnabled(
        configured = snapshot.settings.security.appLockEnabled,
        capability = securityCapabilities.appLock,
    )
    val effectiveSecureScreen = securityFeatureEnabled(
        configured = snapshot.settings.security.secureScreen,
        capability = securityCapabilities.secureScreen,
    )
    var unlocked by remember(appServices) { mutableStateOf(!effectiveAppLockEnabled) }
    var unlocking by remember { mutableStateOf(false) }
    var previousAppLockAuthenticationAvailable by remember(appServices) {
        mutableStateOf(appLockAuthenticationAvailable)
    }
    val completedReaderChapterIds = remember { mutableSetOf<Long>() }
    val appLifecycle by appServices.appLifecycle.collectAsState()
    val sourceLoginRequests by appServices.browse.loginRequests.collectAsState()
    val pendingSourceLoginRequest = sourceLoginRequests.firstOrNull()
    val lockLifecycleTracker = remember(appServices) { AppLockLifecycleTracker() }

    LaunchedEffect(Unit) { appFocusRequester.requestFocus() }
    LaunchedEffect(snapshot.categories, selectedCategoryId) {
        if (
            selectedCategoryId != ALL_LIBRARY_CATEGORY_ID &&
            snapshot.categories.none { it.id == selectedCategoryId && !it.isSystemCategory }
        ) {
            selectedCategoryId = ALL_LIBRARY_CATEGORY_ID
        }
    }

    fun mutate(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure {
                snackbar.showSnackbar(it.message ?: strings.text("The operation could not be completed."))
            }
        }
    }

    fun dismissMobileInput() {
        if (appServices.prefersDesktopChrome) return
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        // Keep global external-keyboard shortcuts active without creating a text-input session.
        appFocusRequester.requestFocus()
    }

    fun selectSection(next: MainSection) {
        dismissMobileInput()
        section = next
        selectedMangaId = null
        moreDestination = null
        detailBackRequest = 0L
        detailNestedBackAvailable = false
        moreBackRequest = 0L
        moreNestedBackAvailable = false
        browseBackRequest = 0L
        if (next != MainSection.BROWSE) browseBackAvailable = false
        selectedLibraryIds = emptySet()
        selectedChapterIds = emptySet()
    }

    fun openManga(mangaId: Long) {
        // The resolver persists the manga before returning, but the Compose snapshot can still
        // be one frame behind.  Guarding against the old snapshot drops the first click and makes
        // users click the same card twice.  DetailPane will render as the state flow catches up.
        dismissMobileInput()
        pendingBrowseRequest++
        pendingBrowseManga = null
        selectedMangaId = mangaId
        moreDestination = null
        detailBackRequest = 0L
        detailNestedBackAvailable = false
        moreBackRequest = 0L
        moreNestedBackAvailable = false
        selectedChapterIds = emptySet()
    }

    fun openBrowseManga(item: BrowseManga) {
        // Enter a lightweight preview route immediately.  Resolving a source manga can start a
        // plugin and perform network I/O; waiting for that work before changing the screen made a
        // perfectly normal tap feel like it was ignored.
        if (pendingBrowseManga != null) return
        dismissMobileInput()
        pendingBrowseManga = item
        val request = pendingBrowseRequest + 1
        pendingBrowseRequest = request
        selectedMangaId = null
        moreDestination = null
    }

    fun openMore(destination: MoreDestination) {
        dismissMobileInput()
        moreBackRequest = 0L
        moreNestedBackAvailable = false
        moreDestination = destination
        selectedMangaId = null
    }

    fun closeRemovedLibraryTitles(mangaIds: Set<Long>) {
        if (selectedMangaId in mangaIds) {
            selectedMangaId = null
            selectedChapterIds = emptySet()
        }
    }

    fun resetReaderPositionState() {
        readerPositionInitializedFor = null
        readerInitialPosition = null
        readerLastObservedHlc = null
        readerRemotePosition = null
    }

    fun openReader(mangaId: Long, chapterId: Long) {
        if (snapshot.mangas.any { it.id == mangaId } && snapshot.chapters.any { it.id == chapterId }) {
            dismissMobileInput()
            // A back request is a one-shot event. Reset the monotonically increasing token when
            // creating a new reader session so a previously handled edge swipe cannot immediately
            // close the next session during its first composition.
            readerBackRequest = 0L
            detailBackRequest = 0L
            detailNestedBackAvailable = false
            resetReaderPositionState()
            readerSessionSerial++
            readerSession = ReaderSession(
                mangaId = mangaId,
                chapterId = chapterId,
                progressSessionId = readerProgressReporter?.newReaderSessionId()
                    ?: "local-reader-$readerSessionSerial",
            )
            selectedMangaId = mangaId
        }
    }

    fun transitionReader(targetMangaId: Long? = null, targetChapterId: Long? = null) {
        val closing = readerSession ?: return
        if (readerTransitionInFlight) return
        val target = if (targetMangaId != null && targetChapterId != null) {
            readerSessionSerial++
            ReaderSession(
                mangaId = targetMangaId,
                chapterId = targetChapterId,
                progressSessionId = readerProgressReporter?.newReaderSessionId()
                    ?: "local-reader-$readerSessionSerial",
            )
        } else {
            null
        }
        val reporter = readerProgressReporter
        if (reporter == null) {
            resetReaderPositionState()
            readerSession = target
            return
        }
        readerTransitionInFlight = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    reporter.flushReaderSession(closing.progressSessionId)
                }
            }
                .onFailure { failure ->
                    snackbar.showSnackbar(
                        failure.message ?: strings.text("The latest reading position is queued for retry."),
                    )
                }
            if (readerSession == closing) {
                resetReaderPositionState()
                readerSession = target
            }
            readerTransitionInFlight = false
        }
    }

    LaunchedEffect(pendingBrowseManga, pendingBrowseRequest, appServices.browse) {
        val item = pendingBrowseManga ?: return@LaunchedEffect
        val request = pendingBrowseRequest
        // The pending page has already entered composition. Waiting for the next frame guarantees
        // its background, cover and progress UI are drawn before plugin startup/network parsing
        // can occupy the main thread prior to its first suspension point.
        withFrameNanos { }
        if (pendingBrowseRequest != request || pendingBrowseManga != item) return@LaunchedEffect
        val result = runCatching { appServices.browse.resolveManga(item) }
        if (pendingBrowseRequest != request || pendingBrowseManga != item) return@LaunchedEffect
        result.onSuccess { mangaId ->
            pendingBrowseManga = null
            if (mangaId != null) {
                detailBackRequest = 0L
                detailNestedBackAvailable = false
                selectedMangaId = mangaId
                selectedChapterIds = emptySet()
            } else {
                snackbar.showSnackbar(strings.text("Unable to open this title."))
            }
        }.onFailure { error ->
            pendingBrowseManga = null
            snackbar.showSnackbar(error.message ?: strings.text("Unable to open this title."))
        }
    }

    LaunchedEffect(readerSession, readerReloadKey) {
        val session = readerSession ?: return@LaunchedEffect
        readerLoading = true
        readerError = null
        readerChapterSession = null
        // Reader chrome/loading state must reach the screen before chapter/plugin I/O begins.
        withFrameNanos { }
        try {
            val loadedChapter = withContext(Dispatchers.Default) {
                val loaded = appServices.content.loadReaderChapter(session.mangaId, session.chapterId)
                val restored = loaded.typedSession?.let { typed ->
                    val features = requireNotNull(appServices.contentFeatures) {
                        "Protected reader content is unavailable without the host content runtime"
                    }
                    val cleanupAccess = typed.canonicalText?.let { text ->
                        typed.access.copy(
                            context = typed.access.context.copy(textCharacters = text.length.toLong()),
                        )
                    } ?: typed.access
                    features.cleanupRevokedDerivedData(typed.content.navigation.scope, cleanupAccess)
                    check(
                        features.operations.display(
                            request = typed.access,
                            textCharacters = typed.canonicalText?.length?.toLong(),
                        ) { true },
                    )
                    val navigation = typed.content.navigation
                    val localIndex = repository.chapter(session.chapterId)?.lastPageRead
                        ?.coerceIn(0, navigation.itemCount - 1)
                        ?: 0
                    val localLocator = navigation.locatorAt(localIndex)
                    val remoteLocator = readerProgressReporter
                        ?.currentContentReadingLocator(ContentProgressKeyV2.from(typed.content.initialLocator))
                        ?.value
                        ?.takeIf { navigation.indexOf(it) != null }
                    TypedReaderContentSession(
                        content = typed.content.copy(initialLocator = remoteLocator ?: localLocator),
                        canonicalText = typed.canonicalText,
                        access = typed.access,
                    )
                }
                if (restored == null) loaded else loaded.copy(typedSession = restored)
            }
            if (readerSession == session) {
                readerChapter = loadedChapter
                readerChapterSession = session
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (readerSession == session) {
                readerChapter = ReaderChapter()
                readerChapterSession = session
                readerError = failure.message ?: strings.text("Unable to load chapter pages.")
            }
        } finally {
            if (readerSession == session) readerLoading = false
        }
    }

    LaunchedEffect(appLifecycle, readerChapter.typedSession, readerChapterSession) {
        if (appLifecycle != AppLifecycleState.FOREGROUND) return@LaunchedEffect
        val typed = readerChapter.typedSession ?: return@LaunchedEffect
        val features = appServices.contentFeatures ?: return@LaunchedEffect
        val textLength = typed.canonicalText?.length
        val cleanupAccess = textLength?.let { length ->
            typed.access.copy(
                context = typed.access.context.copy(textCharacters = length.toLong()),
            )
        } ?: typed.access
        try {
            withContext(Dispatchers.Default) {
                features.cleanupRevokedDerivedData(typed.content.navigation.scope, cleanupAccess)
                check(features.operations.display(typed.access, textLength?.toLong()) { true })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            features.textToSpeech.stop()
            readerChapter = ReaderChapter()
            readerError = strings.text("This content is no longer available under the current rights grant.")
        }
    }

    LaunchedEffect(effectiveSecureScreen) {
        appServices.setSecureScreen(effectiveSecureScreen)
    }
    LaunchedEffect(appLockAuthenticationAvailable, snapshot.settings.security.appLockEnabled) {
        val shouldLock = shouldLockOnAuthenticationAvailabilityChange(
            appLockConfigured = snapshot.settings.security.appLockEnabled,
            wasAvailable = previousAppLockAuthenticationAvailable,
            isAvailable = appLockAuthenticationAvailable,
        )
        previousAppLockAuthenticationAvailable = appLockAuthenticationAvailable
        if (shouldLock) unlocked = false
    }
    LaunchedEffect(readerSession, snapshot.settings.reader.keepScreenOn) {
        appServices.setKeepScreenOn(readerSession != null && snapshot.settings.reader.keepScreenOn)
    }
    LaunchedEffect(readerSession, snapshot.settings.reader.fullscreen) {
        appServices.setFullscreen(readerSession != null && snapshot.settings.reader.fullscreen)
    }
    val readerOpen = readerSession != null
    val interceptReaderVolumeKeys = readerOpen && snapshot.settings.reader.volumeKeys
    DisposableEffect(appServices, readerOpen) {
        // Publish the reader gate as part of the composition commit.  A launched coroutine leaves
        // a short window where iOS can begin its native edge recognizer after the reader appears.
        appServices.setReaderOpen(readerOpen)
        if (readerOpen) {
            appServices.setFullscreen(snapshot.settings.reader.fullscreen)
            appServices.setReaderOrientation(snapshot.settings.reader.orientation)
        }
        onDispose {
            if (readerOpen) {
                appServices.setReaderOpen(false)
                appServices.setFullscreen(false)
                appServices.setReaderOrientation(null)
            }
        }
    }
    LaunchedEffect(appServices, readerOpen, snapshot.settings.reader.orientation) {
        if (readerOpen) appServices.setReaderOrientation(snapshot.settings.reader.orientation)
    }
    DisposableEffect(appServices, snapshot.settings.reader.volumeKeys) {
        // Match the original iOS implementation: prepare AVAudioSession, silent playback, and
        // MPVolumeView as soon as the preference is enabled, before the reader starts listening.
        appServices.setReaderVolumeKeyInfrastructureEnabled(snapshot.settings.reader.volumeKeys)
        onDispose { appServices.setReaderVolumeKeyInfrastructureEnabled(false) }
    }
    DisposableEffect(appServices, interceptReaderVolumeKeys) {
        appServices.setReaderVolumeKeyMonitoringEnabled(interceptReaderVolumeKeys)
        onDispose { appServices.setReaderVolumeKeyMonitoringEnabled(false) }
    }
    LaunchedEffect(
        appLifecycle,
        effectiveAppLockEnabled,
        snapshot.settings.security.lockAfterSeconds,
    ) {
        val shouldLock = lockLifecycleTracker.onLifecycleChanged(
            state = appLifecycle,
            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
            appLockEnabled = effectiveAppLockEnabled,
            lockAfterSeconds = snapshot.settings.security.lockAfterSeconds,
        )
        if (shouldLock) unlocked = false
    }
    LaunchedEffect(
        appLifecycle,
        snapshot.settings.sync.enabled,
        snapshot.settings.sync.syncOnForeground,
        appServices.snapshotSync,
    ) {
        if (
            appLifecycle == AppLifecycleState.FOREGROUND &&
            snapshot.settings.sync.enabled &&
            snapshot.settings.sync.syncOnForeground
        ) {
            appServices.snapshotSync?.let { controller ->
                // A pulled snapshot may change sync settings. Finish the current atomic cycle before
                // Compose restarts this effect so the remote file is never left half-updated.
                withContext(NonCancellable) { controller.sync() }
            }
        }
    }
    LaunchedEffect(effectiveAppLockEnabled, unlocked, appLifecycle) {
        if (
            effectiveAppLockEnabled &&
            !unlocked &&
            appLifecycle == AppLifecycleState.FOREGROUND
        ) {
            unlocking = true
            unlocked = appServices.authenticate(strings.text("Unlock Shinsou X"))
            unlocking = false
        } else if (!effectiveAppLockEnabled) {
            unlocked = true
        }
    }
    LaunchedEffect(appServices, unlocked) {
        // Every platform retains the current deep link until deepLinkHandled is called. Do not
        // consume one-time provisioning material while the application is still locked.
        if (!unlocked) return@LaunchedEffect
        appServices.deepLinks.collect { link ->
            try {
                when (link) {
                    is ShinsouDeepLink.OpenManga -> openManga(link.mangaId)
                    is ShinsouDeepLink.OpenChapter -> {
                        val mangaId = link.mangaId.takeIf { it >= 0 }
                            ?: snapshot.chapters.firstOrNull { it.id == link.chapterId }?.mangaId
                        if (mangaId != null) openReader(mangaId, link.chapterId)
                    }
                    is ShinsouDeepLink.OpenSection -> selectSection(link.section.toMainSection())
                    ShinsouDeepLink.OpenSettings -> {
                        section = MainSection.MORE
                        selectedMangaId = null
                        moreBackRequest = 0L
                        moreDestination = MoreDestination.Settings
                    }
                    is ShinsouDeepLink.OpenSyncLink -> {
                        // Navigate first so the controller's progress and any safe diagnostic are
                        // visible while claim/checkpoint verification runs. The payload never
                        // enters saved Compose state or AppSnapshot.
                        section = MainSection.MORE
                        selectedMangaId = null
                        moreBackRequest = 0L
                        moreDestination = MoreDestination.Settings
                        val controller = appServices.cloudflareSync
                            ?: error("Encrypted sync is unavailable in this runtime")
                        if (link.payload.action == SyncLinkAction.RECOVERY) {
                            val recoveryKit = link.payload.oneTimeSecret
                                ?: error("Recovery link is missing its Recovery Kit")
                            recoveryKit.useSuspending(controller::importRecoveryKit)
                        } else {
                            link.payload.asProvisioningControllerInput().useSuspending(
                                controller::submitOneTimeLinkOrCode,
                            )
                        }
                    }
                }
                // A setup/pair/recovery link is acknowledged only after the controller has
                // durably queued or fully consumed it. Failures remain pending for a later retry.
                appServices.deepLinkHandled(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: strings.text("Unable to open this link."))
            }
        }
    }

    val systemBackHandler = rememberUpdatedState {
        when {
            unlocked && pendingSourceLoginRequest != null -> {
                appServices.browse.dismissSourceLoginRequest(pendingSourceLoginRequest.sourceId)
                true
            }
            readerCategoryPickerMangaId != null -> {
                readerCategoryPickerMangaId = null
                true
            }
            readerSession != null -> {
                readerBackRequest++
                true
            }
            selectedMangaId != null -> {
                detailBackRequest++
                true
            }
            moreDestination != null -> {
                moreBackRequest++
                true
            }
            pendingBrowseManga != null -> {
                pendingBrowseRequest++
                pendingBrowseManga = null
                true
            }
            section == MainSection.BROWSE && browseBackAvailable -> {
                browseBackRequest++
                true
            }
            selectedLibraryIds.isNotEmpty() -> {
                selectedLibraryIds = emptySet()
                true
            }
            else -> false
        }
    }
    LaunchedEffect(appServices) {
        appServices.systemBackEvents.collect { systemBackHandler.value() }
    }
    LaunchedEffect(appServices) {
        appServices.systemBackGestureEvents.collect { event ->
            when (event) {
                is SystemBackGestureEvent.Progress -> {
                    if (readerSession == null && pendingSourceLoginRequest == null) {
                        backSwipeProgress.snapTo(event.fraction)
                    }
                }

                is SystemBackGestureEvent.Settled -> {
                    val gestureMovesVisiblePage = when {
                        pendingSourceLoginRequest != null -> false
                        pendingBrowseManga != null -> true
                        selectedMangaId != null -> !detailNestedBackAvailable
                        // Settings supplies its own horizontally sliding child when nested; all
                        // other More destinations slide the outer destination surface.
                        moreDestination != null -> true
                        section == MainSection.BROWSE && browseBackAvailable -> true
                        else -> false
                    }
                    if (event.committed && readerSession == null && gestureMovesVisiblePage) {
                        backSwipeProgress.animateTo(1f, animationSpec = tween(160))
                        systemBackHandler.value()
                    } else {
                        if (event.committed && readerSession == null) systemBackHandler.value()
                        backSwipeProgress.animateTo(0f, animationSpec = tween(180))
                    }
                }
            }
        }
    }

    // Desktop content used to sit directly on the native window background.
    // That background is light even when the Material scheme is dark, so only
    // selected cards appeared dark and inherited text colors looked wrong.
    // Keep one themed surface behind every navigation branch on all form
    // factors; child Surfaces can still provide their own tonal hierarchy.
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(appFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isBackKey = event.key == Key.Escape ||
                    event.key == Key.Back ||
                    event.key == Key.NavigatePrevious
                when {
                    !isBackKey -> false
                    event.type == KeyEventType.KeyUp && hardwareBackKeyHeld -> {
                        hardwareBackKeyHeld = false
                        true
                    }
                    event.type == KeyEventType.KeyDown && hardwareBackKeyHeld -> true
                    event.type == KeyEventType.KeyDown -> {
                        systemBackHandler.value().also { handled -> hardwareBackKeyHeld = handled }
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isBackPressed) {
                            if (systemBackHandler.value()) event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            // ReaderScreen owns focus for hardware page keys. Its loading/error background has no
            // child pointer consumer, so the app-wide blank-tap observer is disabled while open.
            .onUnconsumedBlankTap(
                enabled = !appServices.prefersDesktopChrome && readerSession == null,
                onBlankTap = ::dismissMobileInput,
            ),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val phone = maxWidth < 720.dp && !appServices.prefersDesktopChrome
        val splitDetail = maxWidth >= 1050.dp || appServices.prefersDesktopChrome && maxWidth >= 900.dp

        if (readerSession != null) {
            val session = readerSession!!
            val manga = snapshot.mangas.firstOrNull { it.id == session.mangaId }
            val chapter = snapshot.chapters.firstOrNull { it.id == session.chapterId }
            if (manga == null || chapter == null) {
                readerSession = null
            } else {
                val activeReaderChapter = readerChapter.takeIf { readerChapterSession == session } ?: ReaderChapter()
                val typedReaderSession = activeReaderChapter.typedSession
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
                LaunchedEffect(session, snapshot.revision, readerProgressReporter) {
                    val initialized = readerPositionInitializedFor == session
                    val observedHlc = readerLastObservedHlc.takeIf { initialized }
                    val incoming = readerProgressReporter?.let { reporter ->
                        runCatching {
                            reporter.currentReadingPosition(syncChapterEntityKey(chapter, manga))
                        }.getOrNull()
                    }
                    when (
                        readerPositionUpdateDecision(
                            initialized = initialized,
                            activeSessionId = session.progressSessionId,
                            lastObservedHlc = observedHlc,
                            incoming = incoming,
                        )
                    ) {
                        ReaderPositionUpdateDecision.RESTORE_ON_OPEN -> {
                            readerInitialPosition = incoming?.position
                            readerRemotePosition = null
                        }
                        ReaderPositionUpdateDecision.OFFER_APPLY -> {
                            readerRemotePosition = incoming
                        }
                        ReaderPositionUpdateDecision.IGNORE -> {
                            if (
                                incoming != null &&
                                incoming.sessionId == session.progressSessionId &&
                                (observedHlc == null || incoming.hlc > observedHlc)
                            ) {
                                readerRemotePosition = null
                            }
                        }
                    }
                    if (!initialized) {
                        readerPositionInitializedFor = session
                        readerInitialPosition = incoming?.position
                        readerLastObservedHlc = incoming?.hlc
                        readerRemotePosition = null
                    } else if (
                        incoming != null &&
                        (observedHlc == null || incoming.hlc > observedHlc)
                    ) {
                        readerLastObservedHlc = incoming.hlc
                    }
                }
                ReaderScreen(
                    manga = manga,
                    chapter = chapter,
                    pages = activeReaderChapter.pages,
                    settings = snapshot.settings.reader,
                    loading = readerLoading || readerChapterSession != session,
                    errorMessage = readerError,
                    inLibrary = manga.favorite,
                    chapters = navigation.storyOrder,
                    previousChapter = navigation.previous,
                    nextChapter = navigation.next,
                    readerSessionId = session.progressSessionId,
                    initialPosition = readerInitialPosition,
                    remotePositionSuggestion = readerRemotePosition?.position,
                    positionReportingEnabled = readerPositionInitializedFor == session,
                    volumeKeyEvents = appServices.readerVolumeKeyEvents,
                    systemBackRequest = readerBackRequest,
                    onClose = { transitionReader() },
                    onRetry = { readerReloadKey++ },
                    onOpenWeb = if (manga.source == LOCAL_SOURCE_ID || chapter.url.isBlank()) {
                        null
                    } else {
                        {
                            mutate {
                                appServices.content
                                    .resolveChapterOriginalUrl(manga.id, chapter.id)
                                    ?.let(appServices::openExternalUrl)
                            }
                        }
                    },
                    onSettingsChange = { readerSettings ->
                        mutate { repository.updateSettings { it.copy(reader = readerSettings) } }
                    },
                    onPositionChanged = { position ->
                        if (!snapshot.settings.security.incognitoMode) {
                            val completedNow = activeReaderChapter.pages.isNotEmpty() &&
                                position.pageIndex == activeReaderChapter.pages.lastIndex &&
                                !chapter.read &&
                                completedReaderChapterIds.add(chapter.id)
                            mutate {
                                val readAt = Clock.System.now().toEpochMilliseconds()
                                val reporter = readerProgressReporter
                                if (reporter != null) {
                                    val result = withContext(Dispatchers.Default) {
                                        reporter.recordReadingProgress(
                                            chapterKey = syncChapterEntityKey(chapter, manga),
                                            mangaKey = syncMangaEntityKey(manga),
                                            readingMode = position.readingMode,
                                            pageIndex = position.pageIndex,
                                            normalizedOffsetFraction = position.normalizedOffsetFraction,
                                            sessionId = session.progressSessionId,
                                            completed = chapter.read || completedNow,
                                            historyTouchedAt = readAt,
                                        )
                                    }
                                    result.positionRegister?.hlc?.let { reportedHlc ->
                                        if (readerLastObservedHlc == null || reportedHlc > requireNotNull(readerLastObservedHlc)) {
                                            readerLastObservedHlc = reportedHlc
                                        }
                                    }
                                } else {
                                    repository.markChapterProgress(
                                        chapterId = chapter.id,
                                        lastPageRead = position.pageIndex,
                                        read = chapter.read || completedNow,
                                        readAt = readAt,
                                    )
                                }
                                if (completedNow && snapshot.settings.downloads.deleteAfterReading) {
                                    snapshot.downloadQueue
                                        .filter { it.chapterId == chapter.id && it.state == DownloadState.DOWNLOADED }
                                        .forEach { appServices.content.removeDownload(it.id) }
                                }
                                val trackingSettings = snapshot.settings.tracking
                                val tracking = appServices.tracking
                                if (
                                    completedNow &&
                                    trackingSettings.autoSyncAfterRead &&
                                    trackingSettings.updateProgressAfterRead &&
                                    tracking != null
                                ) {
                                    val progress = readerTrackingProgress(chapter, navigation.storyOrder)
                                    val failures = snapshot.tracks
                                        .filter { it.mangaId == manga.id }
                                        .mapNotNull { track ->
                                            runCatching {
                                                tracking.update(
                                                    mangaId = manga.id,
                                                    trackerId = track.trackerId,
                                                    update = TrackUpdate(progress = maxOf(track.lastChapterRead, progress)),
                                                )
                                            }.exceptionOrNull()
                                        }
                                    failures.firstOrNull()?.let { failure ->
                                        throw IllegalStateException(
                                            strings.text(
                                                "Chapter completed, but one or more trackers could not be updated: {0}",
                                                failure.message ?: strings.text("unknown tracker error"),
                                            ),
                                            failure,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onApplyRemotePosition = {
                        readerRemotePosition = null
                    },
                    onDismissRemotePositionSuggestion = {
                        readerRemotePosition = null
                    },
                    onToggleFavorite = {
                        mutate {
                            val updated = repository.toggleMangaFavorite(manga.id)
                            if (!updated.favorite) {
                                readerProgressReporter?.let { reporter ->
                                    withContext(Dispatchers.Default) {
                                        reporter.flushReaderSession(session.progressSessionId)
                                    }
                                }
                                readerCategoryPickerMangaId = null
                                resetReaderPositionState()
                                readerSession = null
                                selectedMangaId = null
                                selectedChapterIds = emptySet()
                                removeDownloadsForMangas(
                                    appServices = appServices,
                                    queue = snapshot.downloadQueue,
                                    mangaIds = setOf(manga.id),
                                )
                            } else {
                                val current = repository.currentSnapshot
                                if (
                                    shouldAskForCategoriesOnFavorite(
                                        configuredCategoryId = current.settings.library.defaultCategoryId,
                                        availableCategoryIds = current.categories.map { it.id },
                                    )
                                ) {
                                    readerCategoryPickerMangaId = manga.id
                                }
                            }
                        }
                    },
                    onPreviousChapter = {
                        navigation.previous?.let { transitionReader(manga.id, it.id) }
                    },
                    onNextChapter = {
                        navigation.next?.let { transitionReader(manga.id, it.id) }
                    },
                    onChapterSelected = { chapterId -> transitionReader(manga.id, chapterId) },
                    modifier = Modifier.zIndex(5f),
                    unifiedReaderContent = typedReaderSession?.content,
                    unifiedReaderRenderer = typedReaderSession?.let { typed ->
                        { _, rendererModifier ->
                            val features = appServices.contentFeatures
                            if (features == null) {
                                Box(rendererModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(strings.text("Protected reader features are unavailable."))
                                }
                            } else {
                                UnifiedContentReader(
                                    session = typed,
                                    features = features,
                                    copyText = appServices::copyText,
                                    onLocatorChanged = { locator ->
                                        if (!snapshot.settings.security.incognitoMode) {
                                            typed.content.navigation.indexOf(locator)?.let { index ->
                                                val completed = index == typed.content.navigation.itemCount - 1
                                                mutate {
                                                    val readAt = Clock.System.now().toEpochMilliseconds()
                                                    readerProgressReporter?.recordContentReadingProgress(
                                                        locator = locator,
                                                        sessionId = session.progressSessionId,
                                                        completed = completed,
                                                        historyTouchedAt = readAt,
                                                    )
                                                    repository.markChapterProgress(
                                                        chapterId = chapter.id,
                                                        lastPageRead = index,
                                                        read = chapter.read || completed,
                                                        readAt = readAt,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = rendererModifier,
                                )
                            }
                        }
                    },
                )

                if (readerCategoryPickerMangaId == manga.id) {
                    CategoryPickerDialog(
                        categories = snapshot.categories,
                        selectedCategoryIds = repository.categoriesForManga(manga.id)
                            .mapTo(linkedSetOf()) { it.id },
                        title = strings.text("Set categories"),
                        onDismiss = { readerCategoryPickerMangaId = null },
                        onConfirm = { categoryIds ->
                            mutate {
                                repository.setMangaCategories(manga.id, categoryIds)
                                readerCategoryPickerMangaId = null
                            }
                        },
                    )
                }
            }
        }

        if (phone) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        navigationItems(strings).forEach { item ->
                            NavigationBarItem(
                                selected = section == item.section && selectedMangaId == null && moreDestination == null,
                                onClick = { selectSection(item.section) },
                                icon = {
                                    Icon(
                                        if (section == item.section) item.selectedIcon else item.icon,
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    val phoneDestination = when {
                        pendingBrowseManga != null -> PhoneDestination.Pending(pendingBrowseManga!!)
                        selectedMangaId != null -> PhoneDestination.Manga(selectedMangaId!!)
                        moreDestination != null -> PhoneDestination.More(moreDestination!!)
                        else -> PhoneDestination.Section
                    }
                    LaunchedEffect(
                        phoneDestination,
                        browseBackAvailable,
                        detailNestedBackAvailable,
                        moreNestedBackAvailable,
                    ) {
                        // A committed gesture has already moved the active surface off-screen.
                        // Reset only after its route (or deepest nested route) actually changes.
                        if (backSwipeProgress.value > 0f) backSwipeProgress.snapTo(0f)
                    }

                    // Keep the section surface mounted beneath pushed destinations.  Apart from
                    // making the interactive edge gesture possible, this preserves a source
                    // catalogue's selected source, query, page and loaded results when a title is
                    // opened and then dismissed.
                    SectionPane(
                        section = section,
                        snapshot = snapshot,
                        repository = repository,
                        appServices = appServices,
                        compactChrome = false,
                        selectedCategoryId = selectedCategoryId,
                        onCategorySelected = { selectedCategoryId = it },
                        selectedLibraryIds = selectedLibraryIds,
                        onLibrarySelectionChange = { selectedLibraryIds = it },
                        onOpenManga = ::openManga,
                        onOpenBrowseManga = ::openBrowseManga,
                        onReadChapter = ::openReader,
                        onOpenMore = ::openMore,
                        onLibraryTitlesRemoved = ::closeRemovedLibraryTitles,
                        browseSystemBackRequest = browseBackRequest,
                        browseBackGestureProgress = if (
                            phoneDestination == PhoneDestination.Section && section == MainSection.BROWSE
                        ) {
                            backSwipeProgress.value
                        } else {
                            0f
                        },
                        onBrowseBackAvailabilityChanged = { browseBackAvailable = it },
                        mutate = ::mutate,
                    )

                    AnimatedContent(
                        targetState = phoneDestination,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val returning = targetState is PhoneDestination.Section &&
                                initialState !is PhoneDestination.Section
                            val interactiveReturn = returning && backSwipeProgress.value >= 0.99f
                            val resolvedPendingTitle = initialState is PhoneDestination.Pending &&
                                targetState is PhoneDestination.Manga
                            if (interactiveReturn || resolvedPendingTitle) {
                                // The gesture itself completed the pop. Keeping AnimatedContent's
                                // exit here would jump the page back to x=0 and slide it out twice.
                                // Likewise, replacing a lightweight pending shell with its seeded
                                // detail record is the same destination, not a second navigation.
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (returning) {
                                (slideInHorizontally(
                                    animationSpec = tween(220),
                                    initialOffsetX = { -it / 4 },
                                ) + fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(220),
                                        targetOffsetX = { it },
                                    ) + fadeOut(tween(150)))
                            } else {
                                (slideInHorizontally(
                                    animationSpec = tween(220),
                                    initialOffsetX = { it },
                                ) + fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(220),
                                        targetOffsetX = { -it / 4 },
                                    ) + fadeOut(tween(150)))
                            }
                        },
                        label = "phone-navigation",
                    ) { destination ->
                        val edgeOffset = when (destination) {
                            PhoneDestination.Section -> 0f
                            is PhoneDestination.Pending -> backSwipeProgress.value
                            is PhoneDestination.Manga -> {
                                if (detailNestedBackAvailable) 0f else backSwipeProgress.value
                            }
                            is PhoneDestination.More -> {
                                if (moreNestedBackAvailable) 0f else backSwipeProgress.value
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = size.width * edgeOffset },
                        ) {
                            if (destination == PhoneDestination.Section) {
                                Spacer(Modifier.fillMaxSize())
                            } else {
                                // Pushed destinations must be opaque. The section is deliberately
                                // kept mounted underneath for interactive pop, but it must only be
                                // revealed where the foreground page has actually been dragged.
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background,
                                    contentColor = MaterialTheme.colorScheme.onBackground,
                                ) {
                                    when (destination) {
                                        is PhoneDestination.Pending -> PendingMangaDetailPane(
                                            item = destination.item,
                                            onBack = {
                                                pendingBrowseRequest++
                                                pendingBrowseManga = null
                                            },
                                        )
                                        is PhoneDestination.Manga -> DetailPane(
                                            mangaId = destination.id,
                                            snapshot = snapshot,
                                            repository = repository,
                                            appServices = appServices,
                                            wideLayout = false,
                                            systemBackRequest = detailBackRequest,
                                            onNestedBackAvailabilityChanged = { detailNestedBackAvailable = it },
                                            selectedChapterIds = selectedChapterIds,
                                            onSelectionChange = { selectedChapterIds = it },
                                            onBack = {
                                                selectedMangaId = null
                                                selectedChapterIds = emptySet()
                                            },
                                            onRead = ::openReader,
                                            mutate = ::mutate,
                                        )
                                        is PhoneDestination.More -> MoreDestinationPane(
                                            destination = destination.destination,
                                            snapshot = snapshot,
                                            repository = repository,
                                            appServices = appServices,
                                            securityCapabilities = securityCapabilities,
                                            autoBackupService = autoBackupService,
                                            wideLayout = false,
                                            systemBackRequest = moreBackRequest,
                                            backGestureProgress = if (moreNestedBackAvailable) {
                                                backSwipeProgress.value
                                            } else {
                                                0f
                                            },
                                            onNestedBackAvailabilityChanged = { moreNestedBackAvailable = it },
                                            downloadsPaused = downloadsPaused,
                                            onDownloadsPausedChange = { downloadsPaused = it },
                                            onOpenDestination = { moreDestination = it },
                                            onBack = { moreDestination = null },
                                            mutate = ::mutate,
                                        )
                                        PhoneDestination.Section -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                DesktopSidebar(
                    selected = section,
                    onSelect = ::selectSection,
                    downloadCount = snapshot.downloadQueue.count { it.state != DownloadState.DOWNLOADED },
                    modifier = Modifier.width(228.dp).fillMaxHeight(),
                )
                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.weight(if (splitDetail && selectedMangaId != null) 0.9f else 1f).fillMaxHeight()) {
                    if (moreDestination != null) {
                        MoreDestinationPane(
                            destination = moreDestination!!,
                            snapshot = snapshot,
                            repository = repository,
                            appServices = appServices,
                            securityCapabilities = securityCapabilities,
                            autoBackupService = autoBackupService,
                            wideLayout = windowWidth >= 1100.dp,
                            systemBackRequest = moreBackRequest,
                            backGestureProgress = if (moreNestedBackAvailable) {
                                backSwipeProgress.value
                            } else {
                                0f
                            },
                            onNestedBackAvailabilityChanged = { moreNestedBackAvailable = it },
                            downloadsPaused = downloadsPaused,
                            onDownloadsPausedChange = { downloadsPaused = it },
                            onOpenDestination = { moreDestination = it },
                            onBack = { moreDestination = null },
                            mutate = ::mutate,
                        )
                    } else {
                        // Keep the browse surface mounted while a source title is resolving or
                        // while its details are open.  Replacing BrowseScreen here used to reset
                        // the active extension catalogue, so closing a title unexpectedly jumped
                        // back to the extension list instead of returning to the previous results.
                        Box(Modifier.fillMaxSize()) {
                            SectionPane(
                                section = section,
                                snapshot = snapshot,
                                repository = repository,
                                appServices = appServices,
                                compactChrome = true,
                                selectedCategoryId = selectedCategoryId,
                                onCategorySelected = { selectedCategoryId = it },
                                selectedLibraryIds = selectedLibraryIds,
                                onLibrarySelectionChange = { selectedLibraryIds = it },
                                onOpenManga = ::openManga,
                                onOpenBrowseManga = ::openBrowseManga,
                                onReadChapter = ::openReader,
                                onOpenMore = ::openMore,
                                onLibraryTitlesRemoved = ::closeRemovedLibraryTitles,
                                browseSystemBackRequest = browseBackRequest,
                                browseBackGestureProgress = if (section == MainSection.BROWSE) {
                                    backSwipeProgress.value
                                } else {
                                    0f
                                },
                                onBrowseBackAvailabilityChanged = { browseBackAvailable = it },
                                mutate = ::mutate,
                            )
                            if (pendingBrowseManga != null) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background,
                                    contentColor = MaterialTheme.colorScheme.onBackground,
                                ) {
                                    PendingMangaDetailPane(
                                        item = pendingBrowseManga!!,
                                        onBack = {
                                            pendingBrowseRequest++
                                            pendingBrowseManga = null
                                        },
                                    )
                                }
                            } else if (selectedMangaId != null && !splitDetail) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background,
                                    contentColor = MaterialTheme.colorScheme.onBackground,
                                ) {
                                    DetailPane(
                                        mangaId = selectedMangaId!!,
                                        snapshot = snapshot,
                                        repository = repository,
                                        appServices = appServices,
                                        wideLayout = true,
                                        systemBackRequest = detailBackRequest,
                                        onNestedBackAvailabilityChanged = { detailNestedBackAvailable = it },
                                        selectedChapterIds = selectedChapterIds,
                                        onSelectionChange = { selectedChapterIds = it },
                                        onBack = {
                                            selectedMangaId = null
                                            selectedChapterIds = emptySet()
                                        },
                                        onRead = ::openReader,
                                        mutate = ::mutate,
                                    )
                                }
                            }
                        }
                    }
                }
                if (splitDetail && selectedMangaId != null && moreDestination == null) {
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    Surface(
                        modifier = Modifier.weight(1.1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        DetailPane(
                            mangaId = selectedMangaId!!,
                            snapshot = snapshot,
                            repository = repository,
                            appServices = appServices,
                            wideLayout = true,
                            systemBackRequest = detailBackRequest,
                            onNestedBackAvailabilityChanged = { detailNestedBackAvailable = it },
                            selectedChapterIds = selectedChapterIds,
                            onSelectionChange = { selectedChapterIds = it },
                            onBack = {
                                selectedMangaId = null
                                selectedChapterIds = emptySet()
                            },
                            onRead = ::openReader,
                            mutate = ::mutate,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(18.dp))
        }

        if (
            unlocked &&
            appLifecycle == AppLifecycleState.FOREGROUND &&
            pendingSourceLoginRequest != null
        ) {
            PendingSourceLoginDialog(
                request = pendingSourceLoginRequest,
                callbacks = appServices.browse,
            )
        }

        if (!unlocked) {
            Surface(
                modifier = Modifier.fillMaxSize().zIndex(10f),
                color = MaterialTheme.colorScheme.surface,
            ) {
                EmptyState(
                    title = strings.text("Shinsou X is locked"),
                    message = strings.text("Authenticate to continue."),
                    icon = {
                        if (unlocking) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                        else Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp))
                    },
                    action = {
                        Button(
                            enabled = !unlocking,
                            onClick = {
                                scope.launch {
                                    unlocking = true
                                    unlocked = appServices.authenticate(strings.text("Unlock Shinsou X"))
                                    unlocking = false
                                }
                            },
                        ) { Text(strings.text("Unlock")) }
                    },
                )
            }
        }
        }
    }
}

/**
 * Browse state changes during every extension refresh/install. Subscribe only while the login
 * prompt is visible so those updates do not invalidate the entire application composition.
 */
@Composable
private fun PendingSourceLoginDialog(
    request: SourceLoginRequest,
    callbacks: BrowseCallbacks,
) {
    val browseSnapshot by callbacks.state.collectAsState()
    SourceLoginDialog(
        request = request,
        source = browseSnapshot.sources.firstOrNull { it.id == request.sourceId },
        callbacks = callbacks,
    )
}

@Composable
private fun SectionPane(
    section: MainSection,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    compactChrome: Boolean,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit,
    selectedLibraryIds: Set<Long>,
    onLibrarySelectionChange: (Set<Long>) -> Unit,
    onOpenManga: (Long) -> Unit,
    onOpenBrowseManga: (BrowseManga) -> Unit,
    onReadChapter: (Long, Long) -> Unit,
    onOpenMore: (MoreDestination) -> Unit,
    onLibraryTitlesRemoved: (Set<Long>) -> Unit,
    browseSystemBackRequest: Long,
    browseBackGestureProgress: Float,
    onBrowseBackAvailabilityChanged: (Boolean) -> Unit,
    mutate: (suspend () -> Unit) -> Unit,
) {
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            androidx.compose.animation.fadeIn(tween(140)) togetherWith
                androidx.compose.animation.fadeOut(tween(100))
        },
        label = "main-section",
    ) { current ->
        when (current) {
            MainSection.LIBRARY -> {
                val libraryItems = remember(snapshot.revision) {
                    repository.libraryItems().let { items ->
                        if (snapshot.settings.library.downloadOnly) items.filter { it.downloadCount > 0 } else items
                    }
                }
                val continueReadingChapter = latestHistoryChapter(
                    snapshot = snapshot,
                    allowedMangaIds = libraryItems.mapTo(linkedSetOf()) { it.libraryManga.manga.id },
                )
                val continueReadingItem = continueReadingChapter?.let { latestChapter ->
                    libraryItems.firstOrNull { it.libraryManga.manga.id == latestChapter.mangaId }
                }
                LibraryScreen(
                    items = libraryItems,
                    categories = snapshot.categories.sortedBy { it.sort },
                    settings = snapshot.settings.library,
                    selectedCategoryId = selectedCategoryId,
                    selectedMangaIds = selectedLibraryIds,
                    compactChrome = compactChrome,
                    onCategorySelected = onCategorySelected,
                    onCreateCategory = { name ->
                        mutate { repository.createCategory(name) }
                    },
                    onRenameCategory = { category, name ->
                        mutate { repository.renameCategory(category.id, name) }
                    },
                    onDeleteCategory = { categoryId -> mutate { repository.deleteCategory(categoryId) } },
                    onReorderCategories = { ids ->
                        mutate { repository.reorderCategories(listOf(Category.Default.id) + ids) }
                    },
                    onSelectionChange = onLibrarySelectionChange,
                    onSettingsChange = { library -> mutate { repository.updateSettings { it.copy(library = library) } } },
                    onOpenManga = onOpenManga,
                    onContinueReading = { mangaId ->
                        continueChapter(snapshot, mangaId)?.let { onReadChapter(mangaId, it.id) }
                    },
                    continueReadingItem = continueReadingItem,
                    onRefresh = {
                        val ids = snapshot.mangas.filter { it.favorite }.mapTo(linkedSetOf()) { it.id }
                        mutate { appServices.content.refreshLibrary(ids) }
                    },
                    onMarkSelectedRead = { ids ->
                        mutate {
                            markChaptersRead(
                                repository = repository,
                                appServices = appServices,
                                snapshot = snapshot,
                                chapterIds = snapshot.chapters
                                    .filterTo(linkedSetOf()) { it.mangaId in ids }
                                    .mapTo(linkedSetOf()) { it.id },
                                read = true,
                            )
                        }
                    },
                    onMoveSelected = { ids, categoryIds ->
                        mutate { ids.forEach { repository.setMangaCategories(it, categoryIds) } }
                    },
                    onDeleteSelected = { ids ->
                        onLibraryTitlesRemoved(ids)
                        mutate {
                            removeDownloadsForMangas(
                                appServices = appServices,
                                queue = snapshot.downloadQueue,
                                mangaIds = ids,
                            )
                            ids.forEach { repository.patchManga(it, MangaPatch(favorite = false)) }
                        }
                    },
                )
            }
            MainSection.UPDATES -> UpdatesScreen(
                updates = remember(snapshot.revision) { repository.recentUpdates() },
                allManga = snapshot.mangas,
                allChapters = snapshot.chapters,
                onRefresh = {
                    mutate {
                        appServices.content.refreshLibrary(snapshot.mangas.filter { it.favorite }.mapTo(linkedSetOf()) { it.id })
                    }
                },
                onOpenManga = onOpenManga,
                onReadChapter = onReadChapter,
                onDownloadChapter = { mangaId, chapterId ->
                    mutate { appServices.content.enqueueDownload(mangaId, chapterId) }
                },
                onMarkChaptersRead = { chapterIds, read ->
                    mutate {
                        markChaptersRead(repository, appServices, snapshot, chapterIds, read)
                    }
                },
                onDownloadChapters = { chapterIds ->
                    mutate {
                        snapshot.chapters.filter { it.id in chapterIds }.forEach { chapter ->
                            appServices.content.enqueueDownload(chapter.mangaId, chapter.id)
                        }
                    }
                },
                onDeleteChapterDownloads = { chapterIds ->
                    mutate {
                        snapshot.downloadQueue.filter { it.chapterId in chapterIds }.forEach { item ->
                            appServices.content.removeDownload(item.id)
                        }
                    }
                },
                onToggleChapterBookmarks = { chapterIds ->
                    mutate {
                        snapshot.chapters.filter { it.id in chapterIds }.forEach { chapter ->
                            repository.patchChapter(chapter.id, ChapterPatch(bookmark = !chapter.bookmark))
                        }
                    }
                },
            )
            MainSection.HISTORY -> HistoryScreen(
                history = remember(snapshot.revision) { repository.history() },
                onOpenManga = onOpenManga,
                onResumeChapter = onReadChapter,
                onDeleteChapterHistory = { chapterId -> mutate { repository.deleteHistory(chapterId) } },
                onClearHistory = { mutate { repository.clearHistory() } },
            )
            MainSection.BROWSE -> BrowseScreen(
                callbacks = appServices.browse,
                showNsfw = snapshot.settings.browse.showNsfwSources,
                enabledLanguages = snapshot.settings.browse.enabledLanguages,
                pinnedSourceIds = snapshot.settings.browse.pinnedSourceIds,
                onSourcePinnedChange = { sourceId, pinned ->
                    mutate {
                        repository.updateSettings { settings ->
                            val pinnedIds = if (pinned) {
                                settings.browse.pinnedSourceIds + sourceId
                            } else {
                                settings.browse.pinnedSourceIds - sourceId
                            }
                            settings.copy(browse = settings.browse.copy(pinnedSourceIds = pinnedIds))
                        }
                    }
                },
                onOpenManga = onOpenBrowseManga,
                contentFeatures = appServices.contentFeatures,
                copyText = appServices::copyText,
                systemBackRequest = browseSystemBackRequest,
                backGestureProgress = browseBackGestureProgress,
                onBackAvailabilityChanged = onBrowseBackAvailabilityChanged,
                onImportDocument = { acceptedExtensions ->
                    appServices.importDocument(
                        acceptedExtensions = acceptedExtensions,
                        limits = ImportedDocumentLimits(MAX_COOKIE_FILE_BYTES.toLong()),
                    )
                },
            )
            MainSection.MORE -> MoreScreen(
                downloadCount = snapshot.downloadQueue.count { it.state != DownloadState.DOWNLOADED },
                lastBackupLabel = snapshot.backupState.lastFileName,
                incognitoMode = snapshot.settings.security.incognitoMode,
                downloadOnlyMode = snapshot.settings.library.downloadOnly,
                onOpen = onOpenMore,
                onImportLocal = { syncContentBodies ->
                    mutate {
                        val documents = appServices.pickLocalFiles(
                            acceptedExtensions = LOCAL_CONTENT_EXTENSIONS,
                            limits = LOCAL_IMPORTED_DOCUMENT_LIMITS,
                        )
                        val imported = appServices.content.importLocalDocuments(
                            documents = documents,
                            syncContentBodies = syncContentBodies,
                        )
                        imported.firstOrNull()?.let { onOpenManga(it.mangaId) }
                    }
                },
                onIncognitoModeChange = { enabled ->
                    mutate {
                        repository.updateSettings { settings ->
                            settings.copy(security = settings.security.copy(incognitoMode = enabled))
                        }
                    }
                },
                onDownloadOnlyModeChange = { enabled ->
                    mutate {
                        repository.updateSettings { settings ->
                            settings.copy(library = settings.library.copy(downloadOnly = enabled))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PendingMangaDetailPane(
    item: BrowseManga,
    onBack: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = item.title,
            subtitle = strings.text("Loading title details"),
            leading = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, strings.text("Back"))
                }
            },
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CoverImage(
                    title = item.title,
                    url = item.thumbnailUrl,
                    headers = item.thumbnailHeaders,
                    modifier = Modifier.width(160.dp).aspectRatio(2f / 3f),
                )
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                Text(
                    strings.text("Loading title details"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailPane(
    mangaId: Long,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    wideLayout: Boolean,
    systemBackRequest: Long = 0L,
    onNestedBackAvailabilityChanged: (Boolean) -> Unit = {},
    selectedChapterIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    onRead: (Long, Long) -> Unit,
    mutate: (suspend () -> Unit) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var trackingSheetVisible by remember(mangaId) { mutableStateOf(false) }
    var categoryPickerVisible by remember(mangaId) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(mangaId) { mutableStateOf(systemBackRequest) }
    val nestedBackAvailable = trackingSheetVisible || categoryPickerVisible
    val currentBackCallback = rememberUpdatedState(onBack)
    val currentNestedBackCallback = rememberUpdatedState(onNestedBackAvailabilityChanged)
    LaunchedEffect(nestedBackAvailable) {
        currentNestedBackCallback.value(nestedBackAvailable)
    }
    DisposableEffect(mangaId) {
        onDispose { currentNestedBackCallback.value(false) }
    }
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == handledSystemBackRequest) return@LaunchedEffect
        handledSystemBackRequest = systemBackRequest
        when {
            trackingSheetVisible -> trackingSheetVisible = false
            categoryPickerVisible -> categoryPickerVisible = false
            else -> currentBackCallback.value()
        }
    }
    val manga = snapshot.mangas.firstOrNull { it.id == mangaId }
    if (manga == null) {
        EmptyState(
            strings.text("Title not found"),
            strings.text("This title may have been removed."),
            icon = { Icon(Icons.Outlined.Book, null) },
        )
        return
    }
    val chapters = snapshot.chapters.filter { it.mangaId == mangaId }
    val downloads = snapshot.downloadQueue.filter { it.mangaId == mangaId }
    var refreshingFromSource by remember(mangaId) {
        mutableStateOf(!manga.initialized || chapters.isEmpty())
    }
    fun refreshFromSource() {
        if (refreshingFromSource) return
        refreshingFromSource = true
        mutate {
            try {
                appServices.content.refreshManga(manga.id)
            } finally {
                refreshingFromSource = false
            }
        }
    }
    LaunchedEffect(mangaId) {
        if (!manga.initialized || chapters.isEmpty()) {
            // The seed detail surface must be committed before extension startup, HTTP and HTML
            // parsing begin. Remote execution itself is dispatched away from Main by the source
            // runtime/coordinator, so the shell and its progress feedback remain responsive.
            withFrameNanos { }
            // This starts true so the very first detail frame already contains progress UI.
            // Clear and immediately re-enter through the guarded action to launch the request.
            refreshingFromSource = false
            refreshFromSource()
        }
    }
    fun withOriginalUrl(action: (String) -> Unit) {
        scope.launch {
            val resolved = runCatching { appServices.content.resolveMangaOriginalUrl(manga.id) }.getOrNull()
                ?: manga.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            resolved?.let(action)
        }
    }
    MangaDetailScreen(
        manga = manga,
        chapters = chapters,
        downloads = downloads,
        refreshingFromSource = refreshingFromSource,
        wideLayout = wideLayout,
        selectedChapterIds = selectedChapterIds,
        onSelectionChange = onSelectionChange,
        onBack = onBack,
        onToggleFavorite = {
            mutate {
                val updated = repository.toggleMangaFavorite(manga.id)
                if (!updated.favorite) {
                    categoryPickerVisible = false
                    onBack()
                    removeDownloadsForMangas(
                        appServices = appServices,
                        queue = snapshot.downloadQueue,
                        mangaIds = setOf(manga.id),
                    )
                } else {
                    val current = repository.currentSnapshot
                    if (shouldAskForCategoriesOnFavorite(
                            configuredCategoryId = current.settings.library.defaultCategoryId,
                            availableCategoryIds = current.categories.map { it.id },
                        )
                    ) {
                        categoryPickerVisible = true
                    }
                }
            }
        },
        onRefresh = ::refreshFromSource,
        onShare = { withOriginalUrl { appServices.shareText(manga.title, it) } },
        onOpenWeb = { withOriginalUrl(appServices::openExternalUrl) },
        onOpenTracking = {
            if (appServices.tracking != null) {
                trackingSheetVisible = true
            } else {
                snapshot.tracks.firstOrNull { it.mangaId == manga.id }?.remoteUrl?.takeIf { it.isNotBlank() }
                    ?.let(appServices::openExternalUrl)
            }
        },
        onUpdateNotes = { notes -> mutate { repository.patchManga(manga.id, MangaPatch(notes = notes)) } },
        onChapterFlagsChange = { flags ->
            mutate { repository.patchManga(manga.id, MangaPatch(chapterFlags = flags)) }
        },
        onExcludedScanlatorsChange = { excluded ->
            mutate { repository.patchManga(manga.id, MangaPatch(excludedScanlators = excluded)) }
        },
        onReadChapter = { onRead(manga.id, it) },
        onMarkChaptersRead = { ids, read ->
            mutate { markChaptersRead(repository, appServices, snapshot, ids, read) }
        },
        onBookmarkChapters = { ids, bookmark ->
            mutate { ids.forEach { repository.patchChapter(it, ChapterPatch(bookmark = bookmark)) } }
        },
        onDownloadChapters = { ids ->
            mutate { ids.forEach { appServices.content.enqueueDownload(manga.id, it) } }
        },
        onDeleteChapters = { ids ->
            mutate {
                snapshot.downloadQueue.filter { it.chapterId in ids }.forEach {
                    appServices.content.removeDownload(it.id)
                }
            }
        },
    )

    if (categoryPickerVisible) {
        CategoryPickerDialog(
            categories = snapshot.categories,
            selectedCategoryIds = repository.categoriesForManga(manga.id).mapTo(linkedSetOf()) { it.id },
            title = strings.text("Set categories"),
            onDismiss = {
                // The title was already added. Cancelling keeps it safely in Default, matching the
                // original mobile flow instead of leaving favorite/category state inconsistent.
                categoryPickerVisible = false
            },
            onConfirm = { categoryIds ->
                mutate {
                    repository.setMangaCategories(manga.id, categoryIds)
                    categoryPickerVisible = false
                }
            },
        )
    }

    val tracking = appServices.tracking
    if (trackingSheetVisible && tracking != null) {
        TrackingSheet(
            manga = manga,
            tracks = snapshot.tracks.filter { it.mangaId == manga.id },
            coordinator = tracking,
            onOpenExternalUrl = appServices::openExternalUrl,
            onDismiss = { trackingSheetVisible = false },
        )
    }
}

private suspend fun markChaptersRead(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    snapshot: AppSnapshot,
    chapterIds: Set<Long>,
    read: Boolean,
) {
    chapterIds.forEach { chapterId -> repository.patchChapter(chapterId, ChapterPatch(read = read)) }
    downloadIdsToRemoveAfterMarkedRead(
        settings = snapshot.settings.downloads,
        queue = snapshot.downloadQueue,
        chapterIds = chapterIds,
        markedRead = read,
    ).forEach { downloadId -> appServices.content.removeDownload(downloadId) }
}

private suspend fun removeDownloadsForMangas(
    appServices: ShinsouAppServices,
    queue: List<DownloadQueueItem>,
    mangaIds: Set<Long>,
) {
    queue
        .asSequence()
        .filter { it.mangaId in mangaIds }
        .map { it.id }
        .toList()
        .forEach { appServices.content.removeDownload(it) }
}

@Composable
private fun MoreDestinationPane(
    destination: MoreDestination,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    securityCapabilities: PlatformSecurityCapabilities,
    autoBackupService: AutoBackupService?,
    wideLayout: Boolean,
    downloadsPaused: Boolean,
    onDownloadsPausedChange: (Boolean) -> Unit,
    onOpenDestination: (MoreDestination) -> Unit,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onNestedBackAvailabilityChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    mutate: (suspend () -> Unit) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var automaticBackups by remember(autoBackupService) {
        mutableStateOf<List<AutoBackupEntry>>(emptyList())
    }
    var settingsNestedBackAvailable by remember(destination) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(destination) { mutableStateOf(systemBackRequest) }
    val nestedBackAvailable = destination == MoreDestination.Settings && settingsNestedBackAvailable
    val currentNestedBackCallback = rememberUpdatedState(onNestedBackAvailabilityChanged)
    LaunchedEffect(nestedBackAvailable) {
        currentNestedBackCallback.value(nestedBackAvailable)
    }
    DisposableEffect(destination) {
        onDispose { currentNestedBackCallback.value(false) }
    }
    LaunchedEffect(destination, autoBackupService, snapshot.backupState.lastBackupAt) {
        if (destination == MoreDestination.Backup && autoBackupService != null) {
            automaticBackups = autoBackupService.listBackups()
        }
    }
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == handledSystemBackRequest) return@LaunchedEffect
        handledSystemBackRequest = systemBackRequest
        if (!nestedBackAvailable) onBack()
    }

    when (destination) {
        MoreDestination.Downloads -> DownloadsScreen(
            queue = snapshot.downloadQueue.filter { it.visibleInQueue },
            mangaById = snapshot.mangas.associateBy { it.id },
            chapterById = snapshot.chapters.associateBy { it.id },
            paused = downloadsPaused,
            onBack = onBack,
            onPauseChange = { paused ->
                onDownloadsPausedChange(paused)
                mutate {
                    appServices.content.pauseDownloads(paused)
                }
            },
            onRetry = { id ->
                mutate { appServices.content.retryDownload(id) }
            },
            onRemove = { mutate { appServices.content.removeDownload(it) } },
            onMove = { id, delta ->
                val ordered = snapshot.downloadQueue
                    .filter { it.visibleInQueue }
                    .sortedBy { it.position }
                    .map { it.id }
                    .toMutableList()
                val current = ordered.indexOf(id)
                val target = (current + delta).coerceIn(0, ordered.lastIndex)
                if (current >= 0 && target != current) {
                    ordered.removeAt(current)
                    ordered.add(target, id)
                    mutate { appServices.content.reorderDownloads(ordered) }
                }
            },
            onClearCompleted = { mutate { appServices.content.clearCompletedDownloads() } },
        )
        MoreDestination.Statistics -> StatisticsScreen(
            library = remember(snapshot.revision) { repository.libraryItems() },
            chapters = snapshot.chapters,
            tracks = snapshot.tracks,
            categories = snapshot.categories,
            onBack = onBack,
        )
        MoreDestination.Settings -> SettingsScreen(
            settings = snapshot.settings,
            categories = snapshot.categories.sortedBy { it.sort },
            snapshotSync = appServices.snapshotSync,
            cloudflareSync = appServices.cloudflareSync,
            appServices = appServices,
            securityCapabilities = securityCapabilities,
            wideLayout = wideLayout,
            systemBackRequest = systemBackRequest,
            backGestureProgress = if (settingsNestedBackAvailable) backGestureProgress else 0f,
            onBackAvailabilityChanged = { settingsNestedBackAvailable = it },
            onBack = onBack,
            onChange = { mutate { repository.setSettings(it) } },
            onCreateCategory = { name ->
                mutate { repository.createCategory(name) }
            },
            onRenameCategory = { category, name ->
                mutate { repository.renameCategory(category.id, name) }
            },
            onDeleteCategory = { categoryId -> mutate { repository.deleteCategory(categoryId) } },
            onReorderCategories = { ids ->
                mutate { repository.reorderCategories(listOf(Category.Default.id) + ids) }
            },
            authenticate = appServices::authenticate,
        )
        MoreDestination.Backup -> BackupScreen(
            state = snapshot.backupState,
            automaticBackups = automaticBackups,
            cloudflareSync = appServices.cloudflareSync,
            onBack = onBack,
            onCreate = {
                mutate {
                    repository.setBackupState(snapshot.backupState.copy(status = BackupStatus.CREATING, errorMessage = null))
                    val createdAt = Clock.System.now().toEpochMilliseconds()
                    val name = "shinsou_$createdAt.shinsoubackup"
                    val payload = SnapshotBackupService.encode(
                        repository.createBackupEnvelope(createdAt, appVersion = "1.0.0"),
                    )
                    val saved = appServices.exportDocument(name, payload)
                    repository.setBackupState(
                        snapshot.backupState.copy(
                            status = if (saved) BackupStatus.COMPLETED else BackupStatus.FAILED,
                            lastBackupAt = if (saved) Clock.System.now().toEpochMilliseconds() else snapshot.backupState.lastBackupAt,
                            lastFileName = if (saved) name else snapshot.backupState.lastFileName,
                            errorMessage = if (saved) null else strings.text("Backup export was cancelled."),
                        ),
                    )
                }
            },
            onRestore = {
                mutate {
                    val document = appServices.importDocument(setOf("json", "shinsoubackup")) ?: return@mutate
                    repository.setBackupState(
                        repository.currentSnapshot.backupState.copy(
                            status = BackupStatus.RESTORING,
                            errorMessage = null,
                        ),
                    )
                    val encoded = document.contents.decodeToString()
                    val envelope = runCatching { SnapshotBackupService.decode(encoded) }.getOrNull()
                    val coordinator = appServices.syncAwareSnapshotRestore
                    if (coordinator != null) {
                        if (envelope != null) {
                            coordinator.restoreBackup(
                                envelope = envelope,
                                restoredAt = Clock.System.now().toEpochMilliseconds(),
                                target = SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                            )
                        } else {
                            coordinator.importSnapshot(encoded, SnapshotRestoreTarget.THIS_DEVICE_ONLY)
                        }
                    } else {
                        // Preview/test roots without platform sync infrastructure retain the
                        // legacy local-only path. Every production root supplies a coordinator.
                        if (envelope != null) repository.restoreBackup(envelope)
                        else repository.importSnapshot(encoded)
                    }
                    repository.setBackupState(
                        repository.currentSnapshot.backupState.copy(
                            status = BackupStatus.COMPLETED,
                            lastRestoreAt = Clock.System.now().toEpochMilliseconds(),
                            lastFileName = document.name,
                        ),
                    )
                }
            },
            onAutomaticChange = { enabled ->
                mutate {
                    val current = repository.currentSnapshot.backupState
                    repository.setBackupState(current.copy(automaticEnabled = enabled))
                }
            },
            onIntervalChange = { intervalHours ->
                mutate {
                    val current = repository.currentSnapshot.backupState
                    repository.setBackupState(current.copy(intervalHours = intervalHours.coerceAtLeast(1)))
                }
            },
            onRetentionChange = { retainedCount ->
                mutate {
                    val current = repository.currentSnapshot.backupState
                    repository.setBackupState(current.copy(retainedBackupCount = retainedCount.coerceAtLeast(1)))
                    if (autoBackupService != null) {
                        automaticBackups = autoBackupService.enforceRetention()
                    }
                }
            },
            onCreateAutomatic = {
                mutate {
                    if (autoBackupService != null) {
                        autoBackupService.createNow()
                        automaticBackups = autoBackupService.listBackups()
                    }
                }
            },
            onRefreshAutomatic = {
                mutate {
                    if (autoBackupService != null) {
                        automaticBackups = autoBackupService.listBackups()
                    }
                }
            },
            onRestoreAutomatic = { backup ->
                mutate {
                    if (autoBackupService != null) {
                        val coordinator = appServices.syncAwareSnapshotRestore
                        if (coordinator != null) {
                            autoBackupService.restore(
                                fileName = backup.fileName,
                                target = SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                                coordinator = coordinator,
                            )
                        } else {
                            autoBackupService.restore(backup.fileName)
                        }
                        automaticBackups = autoBackupService.listBackups()
                    }
                }
            },
            onRestoreWithTarget = appServices.syncAwareSnapshotRestore?.let { coordinator ->
                { target ->
                    mutate {
                        val document = appServices.importDocument(setOf("json", "shinsoubackup"))
                            ?: return@mutate
                        repository.setBackupState(
                            repository.currentSnapshot.backupState.copy(
                                status = BackupStatus.RESTORING,
                                errorMessage = null,
                            ),
                        )
                        val encoded = document.contents.decodeToString()
                        val envelope = runCatching { SnapshotBackupService.decode(encoded) }.getOrNull()
                        if (envelope != null) {
                            coordinator.restoreBackup(
                                envelope = envelope,
                                restoredAt = Clock.System.now().toEpochMilliseconds(),
                                target = target,
                            )
                        } else {
                            coordinator.importSnapshot(encoded, target)
                        }
                        repository.setBackupState(
                            repository.currentSnapshot.backupState.copy(
                                status = BackupStatus.COMPLETED,
                                lastRestoreAt = Clock.System.now().toEpochMilliseconds(),
                                lastFileName = document.name,
                                errorMessage = null,
                            ),
                        )
                    }
                }
            },
            onRestoreAutomaticWithTarget = if (
                autoBackupService != null && appServices.syncAwareSnapshotRestore != null
            ) {
                { backup, target ->
                    mutate {
                        autoBackupService.restore(
                            fileName = backup.fileName,
                            target = target,
                            coordinator = requireNotNull(appServices.syncAwareSnapshotRestore),
                        )
                        automaticBackups = autoBackupService.listBackups()
                    }
                }
            } else {
                null
            },
            onResetWithTarget = appServices.syncAwareSnapshotRestore?.let { coordinator ->
                { target -> mutate { coordinator.reset(target) } }
            },
            onDeleteAutomatic = { backup ->
                mutate {
                    if (autoBackupService != null) {
                        autoBackupService.delete(backup.fileName)
                        automaticBackups = autoBackupService.listBackups()
                    }
                }
            },
        )
        MoreDestination.ContentBackupV2 -> {
            val controller = appServices.portableContentBackupV2
            if (controller == null) {
                PortabilityUnavailablePane(
                    title = strings.text("Content backup v2"),
                    message = strings.text(
                        "Content backup is unavailable until the shared content storage is connected.",
                    ),
                    onBack = onBack,
                )
            } else {
                val observedCloudflareState = appServices.cloudflareSync?.state?.collectAsState()
                ContentBackupV2Screen(
                    controller = controller,
                    syncStatus = observedCloudflareState?.value?.status
                        ?: SyncSessionStatus.NOT_CONFIGURED,
                    onChooseRestoreArchive = {
                        mutate {
                            val document = appServices.importDocument(
                                acceptedExtensions = setOf("shinsou2"),
                                limits = ImportedDocumentLimits(
                                    maxBytesPerFile = DEFAULT_MAX_ARCHIVE_BYTES,
                                    randomAccessExtensions = setOf("shinsou2"),
                                ),
                            ) ?: return@mutate
                            controller.inspectForRestore(
                                object : BackupV2ArchiveSource {
                                    override val byteSize: Long get() = document.byteSize
                                    override fun read(offset: Long, byteCount: Int): ByteArray =
                                        document.source.read(offset, byteCount)
                                },
                            )
                        }
                    },
                    onExportReady = { artifact ->
                        mutate {
                            appServices.exportBinaryDocument(
                                artifact.suggestedFileName,
                                artifact,
                            )
                        }
                    },
                    onOpenShuYueMigration = {
                        onOpenDestination(MoreDestination.ShuYueMigration)
                    },
                    onBack = onBack,
                )
            }
        }
        MoreDestination.ShuYueMigration -> {
            val controller = appServices.shuYueMigration
            if (controller == null) {
                PortabilityUnavailablePane(
                    title = strings.text("Import from ShuYue"),
                    message = strings.text(
                        "ShuYue migration is unavailable until the shared content storage is connected.",
                    ),
                    onBack = onBack,
                )
            } else {
                ShuYueMigrationScreen(
                    controller = controller,
                    onChooseBackup = {
                        mutate {
                            val document = appServices.importDocument(
                                acceptedExtensions = setOf("json"),
                                limits = ImportedDocumentLimits(
                                    ShuYueBackupV1Limits.Default.maxRawBytes.toLong(),
                                ),
                            ) ?: return@mutate
                            controller.inspect(document.contents)
                        }
                    },
                    onBack = onBack,
                )
            }
        }
        MoreDestination.About -> AboutScreen(onBack, appServices::openExternalUrl)
    }
}

@Composable
private fun PortabilityUnavailablePane(
    title: String,
    message: String,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = title,
            leading = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, LocalShinsouStrings.current.text("Back"))
                }
            },
        )
        EmptyState(
            title = LocalShinsouStrings.current.text("Unavailable"),
            message = message,
            icon = { Icon(Icons.Outlined.Lock, null) },
        )
    }
}

@Composable
private fun DesktopSidebar(
    selected: MainSection,
    onSelect: (MainSection) -> Unit,
    downloadCount: Int,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.shinsou_icon),
                    contentDescription = "Shinsou X",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Shinsou X", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(strings.library, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            navigationItems(strings).forEach { item ->
                val active = selected == item.section
                Surface(
                    onClick = { onSelect(item.section) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (active) item.selectedIcon else item.icon,
                            null,
                            Modifier.size(19.dp),
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.section == MainSection.MORE && downloadCount > 0) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                                Text(
                                    downloadCount.coerceAtMost(99).toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Text(
                strings.text("Shinsou X 1.0"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(11.dp),
            )
        }
    }
}

private data class NavigationItem(
    val section: MainSection,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private sealed interface PhoneDestination {
    data object Section : PhoneDestination
    data class Pending(val item: BrowseManga) : PhoneDestination
    data class Manga(val id: Long) : PhoneDestination
    data class More(val destination: MoreDestination) : PhoneDestination
}

private fun navigationItems(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): List<NavigationItem> = listOf(
    NavigationItem(MainSection.LIBRARY, strings.library, Icons.Outlined.Book, Icons.Filled.Book),
    NavigationItem(MainSection.UPDATES, strings.updates, Icons.Outlined.Update, Icons.Filled.Update),
    NavigationItem(MainSection.HISTORY, strings.history, Icons.Outlined.History, Icons.Filled.History),
    NavigationItem(MainSection.BROWSE, strings.browse, Icons.Outlined.Explore, Icons.Filled.Explore),
    NavigationItem(MainSection.MORE, strings.more, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

private data class ReaderSession(
    val mangaId: Long,
    val chapterId: Long,
    val progressSessionId: String,
)

internal fun latestHistoryChapter(
    snapshot: AppSnapshot,
    allowedMangaIds: Set<Long>? = null,
): Chapter? {
    val chapterById = snapshot.chapters.associateBy(Chapter::id)
    return snapshot.histories
        .asSequence()
        .mapNotNull { history -> chapterById[history.chapterId]?.let { chapter -> history to chapter } }
        .filter { (_, chapter) -> allowedMangaIds == null || chapter.mangaId in allowedMangaIds }
        .maxWithOrNull(compareBy<Pair<dev.shinsou.kmp.domain.model.History, Chapter>> { it.first.lastRead }
            .thenBy { it.first.id })
        ?.second
}

internal fun continueChapter(snapshot: AppSnapshot, mangaId: Long): Chapter? {
    latestHistoryChapter(snapshot, setOf(mangaId))?.let { return it }
    val chapters = snapshot.chapters.filter { it.mangaId == mangaId }
        .sortedWith(readerStoryOrderComparator)
    return chapters.firstOrNull { !it.read } ?: chapters.lastOrNull()
}

private fun ThemeMode.toUiTheme(): ShinsouThemeMode = when (this) {
    ThemeMode.SYSTEM -> ShinsouThemeMode.System
    ThemeMode.LIGHT -> ShinsouThemeMode.Light
    ThemeMode.DARK -> ShinsouThemeMode.Dark
}

private fun DeepLinkSection.toMainSection(): MainSection = when (this) {
    DeepLinkSection.Library -> MainSection.LIBRARY
    DeepLinkSection.Updates -> MainSection.UPDATES
    DeepLinkSection.History -> MainSection.HISTORY
    DeepLinkSection.Browse -> MainSection.BROWSE
    DeepLinkSection.More -> MainSection.MORE
}
