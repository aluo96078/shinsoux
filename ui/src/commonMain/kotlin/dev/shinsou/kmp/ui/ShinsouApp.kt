package dev.shinsou.kmp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.plugin.ContentAcquisition
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.ui.components.toDiagnostics
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ProvideShinsouStrings
import dev.shinsou.kmp.ui.theme.ShinsouTheme
import dev.shinsou.kmp.ui.theme.ShinsouThemeMode
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import dev.shinsou.kmp.ui.core.LocalMobileKeyboardDismissEnabled
import dev.shinsou.kmp.ui.core.PluginImageLoader
import dev.shinsou.kmp.ui.core.onUnconsumedBlankTap
import dev.shinsou.kmp.ui.reader.effectiveReaderVolumeKeyMonitoringEnabled

/** Primary, platform-neutral composition entry point. */
@Composable
fun ShinsouApp(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    autoBackupService: AutoBackupService? = null,
    interactionReady: Boolean = true,
) {
    val snapshot by repository.snapshot.collectAsState()
    val pluginImages = remember(appServices.browse) { PluginImageLoader.forSource(SourceCatalogImages(appServices.browse)) }
    val appUpdates = remember(appServices.appUpdates) { appServices.appUpdates?.let(::AppUpdateState) }
    val updateScope = rememberCoroutineScope()
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
                        dev.shinsou.kmp.ui.components.LocalPluginImageLoader provides pluginImages,
                        dev.shinsou.kmp.ui.components.LocalImageSaver provides appServices::saveImage,
                        dev.shinsou.kmp.ui.components.LocalDeveloperDiagnostics provides snapshot.settings.developer.toDiagnostics(),
                        dev.shinsou.kmp.ui.translation.LocalMangaTranslation provides appServices.mangaTranslation,
                        LocalAppUpdates provides appUpdates,
                    ) {
                        ShinsouAppContent(
                            repository = repository,
                            snapshot = snapshot,
                            appServices = appServices,
                            autoBackupService = autoBackupService,
                        )
                        appUpdates?.let { updates ->
                            AppUpdateHost(
                                state = updates,
                                settings = snapshot.settings.updates,
                                onSkip = { version ->
                                    updateScope.launch { repository.updateSettings { it.copy(updates = it.updates.copy(skippedVersion = version)) } }
                                },
                                onOpenUrl = appServices::openExternalUrl,
                            )
                        }
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
/**
 * The app shell: the state holders of [ShinsouAppState], the effects that follow the platform,
 * and the layout for the window's size. Events go to the holders; this only draws their state.
 */
@Composable
private fun ShinsouAppContent(
    repository: ShinsouRepository,
    snapshot: AppSnapshot,
    appServices: ShinsouAppServices,
    autoBackupService: AutoBackupService?,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val queuesPaused = rememberQueuesPaused(snapshot, appServices)
    val app = rememberShinsouAppState(
        ShellHost(repository, appServices, autoBackupService, snackbar, focusRequester),
        snapshot,
        queuesPaused,
        workDispatcher,
    )
    val nav by app.navigator.state.collectAsState()
    // A restored chapter waits while its library page is shown, whatever pane shows it (a layout
    // change replaces the pane): leaving the page gives the reopen up.
    val openPublication = nav.localLibraryPublication
    LaunchedEffect(openPublication?.identityKey) { app.forgetReopenUnlessFor(openPublication) }
    val readerState by app.reader.state.collectAsState()
    val lockState by app.lock.state.collectAsState()
    val selection by app.selection.state.collectAsState()
    val lifecycle by appServices.appLifecycle.collectAsState()
    val loginRequests by appServices.browse.loginRequests.collectAsState()
    val backSwipe = remember { Animatable(0f) }
    val backKey = remember { HardwareBackKeyLatch() }
    val security = snapshot.settings.security
    val capabilities = appServices.securityCapabilities
    val appLockEnabled = securityFeatureEnabled(security.appLockEnabled, capabilities.appLock)
    val readerOpen = readerState.session != null || nav.browseReaderOpen

    LaunchedEffect(queuesPaused) { app.downloadsPaused.value = queuesPaused }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(snapshot.categories, selection.categoryId) { app.selection.retainCategory(snapshot.categories) }
    ShellLifecycleEffects(app, lifecycle, lockState.unlocked)
    ReaderAndTitleEffects(app, readerState, nav, lifecycle)
    PresentationEffects(
        app = app,
        readerOpen = readerOpen,
        readerSettings = snapshot.settings.reader,
        secureScreen = securityFeatureEnabled(security.secureScreen, capabilities.secureScreen),
    )
    AppLockEffects(app, security, appLockEnabled, lifecycle, lockState.unlocked)
    NavigationEventEffects(app, lockState.unlocked, lifecycle == AppLifecycleState.FOREGROUND, backSwipe)

    // Desktop content used to sit on the native window background, which stays light under a dark
    // Material scheme. One themed surface sits behind every navigation branch on all form factors;
    // child Surfaces can still provide their own tonal hierarchy.
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .shellInput(
                app = app,
                interceptVolumeKeys = effectiveReaderVolumeKeyMonitoringEnabled(
                    readerOpen = readerOpen,
                    configured = snapshot.settings.reader.volumeKeys,
                ),
                backKey = backKey,
            )
            // ReaderScreen owns focus for hardware page keys. Its loading/error background has no
            // child pointer consumer, so the app-wide blank-tap observer is off while it is open.
            .onUnconsumedBlankTap(
                enabled = !appServices.prefersDesktopChrome && !readerOpen,
                onBlankTap = app::dismissMobileInput,
            ),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        CompositionLocalProvider(LocalAppUnlocked provides lockState.unlocked) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val phone = maxWidth < 720.dp && !appServices.prefersDesktopChrome
                val splitDetail = maxWidth >= 1050.dp || appServices.prefersDesktopChrome && maxWidth >= 900.dp
                ReaderHost(app, snapshot)
                if (phone) {
                    PhoneShell(app, nav, snapshot, snackbar, backSwipe, readerOpen)
                } else {
                    WideShell(app, nav, snapshot, backSwipe, maxWidth, splitDetail)
                    if (!readerOpen) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(18.dp))
                }
                if (lockState.unlocked) PluginReviewDialogs(app.reviews, nav)
                val login = loginRequests.firstOrNull()
                if (lockState.unlocked && login != null) {
                    PendingSourceLoginDialog(
                        request = login,
                        catalog = appServices.browse,
                        auth = appServices.browse,
                        webSession = appServices.browse,
                    )
                }
                if (lockState.unlocked && lifecycle == AppLifecycleState.FOREGROUND) PluginLogoutDialog(app)
                if (!lockState.unlocked) AppLockOverlay(app, lockState)
            }
        }
    }
}

/** What the shell's state holders are made from, besides the snapshot. */
private class ShellHost(
    val repository: ShinsouRepository,
    val appServices: ShinsouAppServices,
    val autoBackupService: AutoBackupService?,
    val snackbar: SnackbarHostState,
    val focusRequester: FocusRequester,
)

/** The shell's state holders, made once per repository and platform services. */
@Composable
private fun rememberShinsouAppState(
    host: ShellHost,
    snapshot: AppSnapshot,
    queuesPaused: Boolean,
    workDispatcher: CoroutineDispatcher,
): ShinsouAppState {
    val currentStrings = rememberUpdatedState(LocalShinsouStrings.current)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissMobileInput = rememberUpdatedState {
        if (!host.appServices.prefersDesktopChrome) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            // Keep global external-keyboard shortcuts active without starting a text-input session.
            host.focusRequester.requestFocus()
        }
    }
    // Process death and configuration changes: what the shell showed comes back (ShellSavedState).
    // Desktop and iOS keep it in their own store across launches (ShellStateStore).
    // A state kept under another Starting screen is not reopened: the user changed the preference
    // in the session that saved it, and the new preference decides where this launch opens.
    val store = host.appServices.shellStateStore
    val launchStartingScreen = remember { snapshot.settings.general.defaultStartingScreen }
    val keeper = rememberSaveable(saver = ShellStateKeeper.Saver) {
        ShellStateKeeper(restored = store?.read()?.let { readStoredShellState(it, launchStartingScreen) })
    }
    val app = remember(host.repository, host.appServices) {
        // Holders may report from their work dispatcher; the snackbar is shown on the UI thread.
        val uiContext = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
        val messages = AppMessages({ currentStrings.value }) { message ->
            withContext(uiContext) { host.snackbar.showSnackbar(message) }
        }
        ShinsouAppState(
            repository = host.repository,
            appServices = host.appServices,
            autoBackupService = host.autoBackupService,
            environment = HolderEnvironment(scope, messages, workDispatcher),
            start = ShellStart(
                section = snapshot.settings.general.defaultStartingScreen,
                appLocked = securityFeatureEnabled(
                    snapshot.settings.security.appLockEnabled,
                    host.appServices.securityCapabilities.appLock,
                ),
                downloadsPaused = queuesPaused,
                dismissMobileInput = { dismissMobileInput.value() },
            ),
        ).also { keeper.app = it }
    }
    LaunchedEffect(app) {
        // Behind the app lock nothing is reopened: a restored reader would load its chapter and
        // record reading history for someone who never unlocked.
        app.lock.state.first { it.unlocked }
        keeper.reopen(app)
    }
    if (store != null) {
        PersistShellState(keeper, store, host.appServices.appLifecycle, launchStartingScreen)
    }
    return app
}

/**
 * Writes the shell's state to [store] whenever the app goes to the background (where iOS may end
 * the process) and when the shell closes (quitting on desktop). The lifecycle is collected rather
 * than recomposed on: a backgrounded app draws no more frames.
 */
@Composable
private fun PersistShellState(
    keeper: ShellStateKeeper,
    store: ShellStateStore,
    lifecycle: StateFlow<AppLifecycleState>,
    /** The Starting screen this launch began with: what the kept state is saved under. */
    startingScreen: MainSection,
) {
    LaunchedEffect(keeper, store, lifecycle) {
        lifecycle.collect { state -> if (state == AppLifecycleState.BACKGROUND) keeper.persist(store, startingScreen) }
    }
    DisposableEffect(keeper, store) { onDispose { keeper.persist(store, startingScreen) } }
}

/**
 * Saves the shell's state with the platform (an Android Bundle survives process death), and holds
 * a restored state until the shell, made afresh, reopens it.
 */
private class ShellStateKeeper(private var restored: ShellSavedState?) {
    var app: ShinsouAppState? = null

    /**
     * Reopens the restored state in [app]. It stays the state to save until the reopening is over:
     * a typed local title opens asynchronously, and a save before that would lose what it reopens.
     */
    fun reopen(app: ShinsouAppState) {
        val pending = restored ?: return
        app.restore(pending) { if (restored === pending) restored = null }
    }

    fun persist(store: ShellStateStore, startingScreen: MainSection) {
        // The same bounds as the Bundle: a huge selection is not worth checking row by row at launch.
        current()?.let { store.write(storedShellStateText(it.withinBundleLimits(), startingScreen)) }
    }

    /**
     * What to save: the state being reopened while that is under way (without its chapters in
     * incognito mode), else what is shown.
     */
    fun current(): ShellSavedState? {
        val app = app
        val pending = restored?.takeUnless { app?.restoreAbandoned == true }
        return when {
            pending == null -> app?.savedState()
            app == null -> pending
            else -> app.withoutReadingInIncognito(pending)
        }
    }

    companion object {
        val Saver: Saver<ShellStateKeeper, Any> = Saver(
            // A state not reopened yet is kept as it was restored.
            save = { keeper -> keeper.current()?.withinBundleLimits()?.encode() },
            restore = { encoded -> ShellStateKeeper(ShellSavedState.decode(encoded)) },
        )
    }
}

/**
 * Whether every download queue is paused. Paused rows survive a restart, and the system can pause
 * or resume the queues (background limits), so the toggle follows them rather than starting at
 * "running".
 */
@Composable
private fun rememberQueuesPaused(snapshot: AppSnapshot, appServices: ShinsouAppServices): Boolean {
    val novelDownloadStates by appServices.browse.extensionDownloadsV2.collectAsState()
    return remember(snapshot.downloadQueue, novelDownloadStates) {
        val states = snapshot.downloadQueue.map { it.state } + novelDownloadStates.map { it.state }
        states.any { it == DownloadState.PAUSED } &&
            states.none { it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING }
    }
}

/** What the entry points show while the app's stores are opened, off the UI thread. */
@Composable
fun ShinsouStartupScreen() {
    ShinsouTheme {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

internal suspend fun removeDownloadsForMangas(
    content: ContentCallbacks,
    queue: List<DownloadQueueItem>,
    mangaIds: Set<Long>,
) {
    queue
        .asSequence()
        .filter { it.mangaId in mangaIds }
        .map { it.id }
        .toList()
        .forEach { content.removeDownload(it) }
}

/** Manga and novel downloads that still need work; both queues feed the same Downloads screen. */
@Composable
internal fun pendingDownloadCount(queue: List<DownloadQueueItem>, acquisition: ContentAcquisition): Int {
    val extensionDownloads by acquisition.extensionDownloadsV2.collectAsState()
    // "Queued or active": failed rows wait for the user, not the queue.
    fun DownloadState.waiting() =
        this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING || this == DownloadState.PAUSED
    return queue.count { it.visibleInQueue && it.state.waiting() } +
        extensionDownloads.count { it.state.waiting() }
}

internal data class NavigationItem(
    val section: MainSection,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

internal fun navigationItems(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): List<NavigationItem> = listOf(
    NavigationItem(MainSection.LIBRARY, strings.library, Icons.Outlined.Book, Icons.Filled.Book),
    NavigationItem(MainSection.UPDATES, strings.updates, Icons.Outlined.Update, Icons.Filled.Update),
    NavigationItem(MainSection.HISTORY, strings.history, Icons.Outlined.History, Icons.Filled.History),
    NavigationItem(MainSection.BROWSE, strings.browse, Icons.Outlined.Explore, Icons.Filled.Explore),
    NavigationItem(MainSection.MORE, strings.more, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

private fun ThemeMode.toUiTheme(): ShinsouThemeMode = when (this) {
    ThemeMode.SYSTEM -> ShinsouThemeMode.System
    ThemeMode.LIGHT -> ShinsouThemeMode.Light
    ThemeMode.DARK -> ShinsouThemeMode.Dark
}

/**
 * The language a work's [source] says it is in, when translation knows it; blank for "all", "multi"
 * and local imports, and until the sources are loaded (it follows them, as they load after launch).
 */
@Composable
internal fun rememberSourceLanguage(catalog: SourceCatalog, source: Long): String {
    val sources = catalog.state.collectAsState().value.sources
    return remember(sources, source) {
        sources.firstOrNull { it.id == source }?.language
            ?.takeIf { dev.shinsou.kmp.translation.OfflineModelCatalog.language(it) != null }.orEmpty()
    }
}
