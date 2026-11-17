package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.toDiagnostics
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.domain.model.shouldAskForCategoriesOnFavorite
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.reader.buildReaderChapterNavigation
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.reader.readerTrackingProgress
import dev.shinsou.kmp.plugin.admits
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.isPlaceholderPublicationTitle
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import dev.shinsou.kmp.tracking.TrackUpdate
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.LocalPluginImageLoader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ProvideShinsouStrings
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.screens.ALL_LIBRARY_CATEGORY_ID
import dev.shinsou.kmp.ui.screens.BrowseScreen
import dev.shinsou.kmp.ui.screens.PluginPermissionReviewDialog
import dev.shinsou.kmp.ui.screens.CategoryPickerDialog
import dev.shinsou.kmp.ui.screens.ExtensionV2PublicationPane
import dev.shinsou.kmp.ui.screens.MoreDestination
import dev.shinsou.kmp.ui.screens.ReaderScreen
import dev.shinsou.kmp.ui.screens.UnifiedContentReader
import dev.shinsou.kmp.ui.screens.readerTranslationAvailable
import dev.shinsou.kmp.ui.theme.ShinsouTheme
import dev.shinsou.kmp.ui.theme.ShinsouThemeMode
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import dev.shinsou.kmp.contract.BrowseCallbacks
import dev.shinsou.kmp.contract.BrowseExtension
import dev.shinsou.kmp.contract.BrowseManga
import dev.shinsou.kmp.contract.PluginPermissionDetails
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.RepositoryTrustPrompt
import dev.shinsou.kmp.contract.TypedReaderContentSession

/** Primary, platform-neutral composition entry point. */
@Composable
fun ShinsouApp(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    autoBackupService: AutoBackupService? = null,
    interactionReady: Boolean = true,
) {
    val snapshot by repository.snapshot.collectAsState()
    val pluginImages = remember(appServices.browse) { PluginImageLoader(appServices.browse) }
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
                        dev.shinsou.kmp.ui.screens.LocalMangaTranslation provides appServices.mangaTranslation,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ShinsouAppContent(
    repository: ShinsouRepository,
    snapshot: AppSnapshot,
    appServices: ShinsouAppServices,
    autoBackupService: AutoBackupService?,
) {
    val strings = LocalShinsouStrings.current
    val pluginImages = LocalPluginImageLoader.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    /** The permission review shown when a title of a package still waiting for approval is opened. */
    var sourceReviewGate by remember { mutableStateOf<SourceReviewGate?>(null) }
    /** ADR-0002 §8.1: a repository opened from an add-repository link, waiting for the user. */
    var linkedRepositoryTrust by remember { mutableStateOf<RepositoryTrustPrompt?>(null) }
    /** An add-repository link to a repository without a signing key: added only once confirmed. */
    var linkedUnsignedRepository by remember { mutableStateOf<String?>(null) }
    /** The add-repository link behind those dialogs: stays pending until added or declined. */
    var pendingRepositoryLink by remember { mutableStateOf<ShinsouDeepLink?>(null) }
    /** Acknowledges exactly [link] (the one a dialog was opened for), never whatever came later. */
    fun repositoryLinkDone(link: ShinsouDeepLink?) {
        link?.let(appServices::deepLinkHandled)
        if (pendingRepositoryLink == link) pendingRepositoryLink = null
    }
    val linkScope = rememberCoroutineScope()
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
    var readerCategoryPickerMangaId by remember { mutableStateOf<Long?>(null) }
    var readerBackRequest by remember { mutableStateOf(0L) }
    var detailBackRequest by remember { mutableStateOf(0L) }
    var detailNestedBackAvailable by remember { mutableStateOf(false) }
    var moreDestination by remember { mutableStateOf<MoreDestination?>(null) }
    var moreBackRequest by remember { mutableStateOf(0L) }
    var moreNestedBackAvailable by remember { mutableStateOf(false) }
    var browseBackRequest by remember { mutableStateOf(0L) }
    var browseBackAvailable by remember { mutableStateOf(false) }
    var browseReaderOpen by remember { mutableStateOf(false) }
    var pendingBrowseManga by remember { mutableStateOf<BrowseManga?>(null) }
    var browseOwnedLegacyMangaId by remember { mutableStateOf<Long?>(null) }
    var localLibraryPublication by remember { mutableStateOf<BrowseManga?>(null) }
    var localLibraryReaderBackRequest by remember { mutableStateOf(0L) }
    var recoveringLocalLibraryMangaId by remember { mutableStateOf<Long?>(null) }
    var localLibraryRecoveryRequest by remember { mutableStateOf(0L) }
    var pendingBrowseRequest by remember { mutableStateOf(0L) }
    val backSwipeProgress = remember { Animatable(0f) }
    var selectedCategoryId by remember { mutableStateOf(ALL_LIBRARY_CATEGORY_ID) }
    val appFocusRequester = remember { FocusRequester() }
    var hardwareBackKeyHeld by remember { mutableStateOf(false) }
    val appVolumeKeyPressTracker = remember { ReaderVolumeKeyPressTracker() }
    var selectedLibraryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedChapterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // Paused rows survive a restart, so the toggle must start from them rather than "running".
    val novelDownloadStates by appServices.browse.extensionDownloadsV2.collectAsState()
    // Follows the queues, so a pause or resume made by the system (background limits) shows too.
    val queuesPaused = remember(snapshot.downloadQueue, novelDownloadStates) {
        val states = snapshot.downloadQueue.map { it.state } + novelDownloadStates.map { it.state }
        states.any { it == DownloadState.PAUSED } &&
            states.none { it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING }
    }
    var downloadsPaused by remember { mutableStateOf(queuesPaused) }
    LaunchedEffect(queuesPaused) { downloadsPaused = queuesPaused }
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
    val v2ReaderProgressSessions = remember { mutableMapOf<String, String>() }
    val readerVolumeKeyRouter = remember { ReaderVolumeKeyRouter() }
    val readerProgressObservationClock = remember { ReaderProgressObservationClock() }
    val readerLocalProgressCoordinator = remember(repository, snackbar, strings) {
        ReaderLocalProgressCoordinator(
            repository = repository,
            onFailure = { failure ->
                snackbar.showSnackbar(
                    failure.message ?: strings.text("The latest reading position could not be saved."),
                )
            },
        )
    }
    val v2ReaderProgressCoordinator = remember(repository) {
        V2ReaderProgressCoordinator(repository = repository)
    }
    val appLifecycle by appServices.appLifecycle.collectAsState()
    val sourceLoginRequests by appServices.browse.loginRequests.collectAsState()
    val sourceRefreshInvalidations by appServices.browse.sourceRefreshInvalidations.collectAsState()
    val pluginLogoutConfirmations by appServices.browse.logoutConfirmations.collectAsState()
    val pendingSourceLoginRequest = sourceLoginRequests.firstOrNull()
    val lockLifecycleTracker = remember(appServices) { AppLockLifecycleTracker() }

    LaunchedEffect(appLifecycle, unlocked) {
        val available = appLifecycle == AppLifecycleState.FOREGROUND && unlocked
        appServices.browse.setPluginUiAvailable(available)
        if (!available) {
            appServices.browse.dismissAllPluginLogouts()
        }
    }

    LaunchedEffect(v2ReaderProgressCoordinator) {
        v2ReaderProgressCoordinator.run()
    }

    LaunchedEffect(readerLocalProgressCoordinator) {
        readerLocalProgressCoordinator.run()
    }

    DisposableEffect(appServices, readerVolumeKeyRouter) {
        // Android can synchronously hand Activity key events to this router. Other platforms keep
        // using readerVolumeKeyEvents below, but both paths still resolve to one active owner.
        appServices.setReaderVolumeKeyEventSink(readerVolumeKeyRouter)
        onDispose { appServices.setReaderVolumeKeyEventSink(null) }
    }

    LaunchedEffect(appServices, readerVolumeKeyRouter) {
        appServices.readerVolumeKeyEvents.collect(readerVolumeKeyRouter::dispatch)
    }

    LaunchedEffect(appLifecycle, v2ReaderProgressCoordinator) {
        if (appLifecycle == AppLifecycleState.FOREGROUND) return@LaunchedEffect
        // The extension reader callback is intentionally non-blocking. Before the process can be
        // suspended, place a barrier behind every queued local event and force its snapshot write.
        runCatchingCancellable { readerLocalProgressCoordinator.flushLocal() }
        runCatchingCancellable { v2ReaderProgressCoordinator.flushLocal() }
    }

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
            runCatchingCancellable { block() }.onFailure {
                snackbar.showSnackbar(it.message ?: strings.text("The operation could not be completed."))
            }
        }
    }

    fun isV2PublicationInLocalLibrary(item: BrowseManga): Boolean {
        val sourceKey = item.sourceKey ?: return false
        val remoteId = item.remotePublicationId ?: return false
        val publicationKey = extensionPublicationKey(sourceKey, remoteId)
        return repository.currentSnapshot.mangas.any {
            it.source == LOCAL_SOURCE_ID &&
                (decodeExtensionLibraryPublicationUrl(it.url)?.publicationKey == publicationKey ||
                    decodeTypedLocalPublicationUrl(it.url) == publicationKey) &&
                it.favorite
        }
    }

    suspend fun toggleV2PublicationLocalLibrary(
        item: BrowseManga,
        publication: RemotePublicationV2,
        favorite: Boolean,
    ) {
        val publication = publication.withoutScriptObjectText()
        val sourceKey = requireNotNull(item.sourceKey) { "Publication is missing its source identity" }
        val remoteId = requireNotNull(item.remotePublicationId) {
            "Publication is missing its remote identity"
        }
        val publicationKey = extensionPublicationKey(sourceKey, remoteId)
        val localUrl = encodeExtensionLibraryPublicationUrl(sourceKey, remoteId)
        val now = Clock.System.now().toEpochMilliseconds()
        val current = repository.currentSnapshot.mangas.firstOrNull {
            it.source == LOCAL_SOURCE_ID &&
                (decodeExtensionLibraryPublicationUrl(it.url)?.publicationKey == publicationKey ||
                    decodeTypedLocalPublicationUrl(it.url) == publicationKey)
        }
        if (current == null) {
            if (!favorite) return
            repository.upsertManga(
                Manga(
                    source = LOCAL_SOURCE_ID,
                    favorite = true,
                    dateAdded = now,
                    url = localUrl,
                    title = publication.title,
                    author = publication.author,
                    artist = publication.artist,
                    description = publication.description,
                    genre = publication.genre ?: listOf("Extension"),
                    thumbnailUrl = publication.thumbnailUrl,
                    updateStrategy = 1,
                    initialized = true,
                    lastModifiedAt = now,
                    favoriteModifiedAt = now,
                    version = 1,
                ),
            )
        } else {
            repository.updateManga(current.id) { stored ->
                val favoriteChanged = favorite != stored.favorite
                val modifiedAt = maxOf(
                    now,
                    stored.lastModifiedAt,
                    stored.favoriteModifiedAt ?: Long.MIN_VALUE,
                )
                stored.copy(
                    url = localUrl,
                    favorite = favorite,
                    // Never replace a real title with a source's "Name #id" stand-in.
                    title = if (
                        isPlaceholderPublicationTitle(publication.title, remoteId) &&
                        !isPlaceholderPublicationTitle(stored.title, remoteId)
                    ) {
                        stored.title
                    } else {
                        publication.title
                    },
                    author = publication.author ?: stored.author,
                    artist = publication.artist ?: stored.artist,
                    description = publication.description ?: stored.description.withoutScriptObjectText(),
                    genre = publication.genre ?: stored.genre,
                    thumbnailUrl = publication.thumbnailUrl ?: stored.thumbnailUrl,
                    initialized = true,
                    lastModifiedAt = if (favoriteChanged) modifiedAt else now,
                    favoriteModifiedAt = if (favoriteChanged) modifiedAt else stored.favoriteModifiedAt,
                    version = stored.version + 1,
                )
            }
        }
        // Like manga, a title leaving the library takes its pending downloads with it.
        if (!favorite) {
            appServices.browse.removeExtensionPublicationDownloadsV2(sourceKey, remoteId)
            // Free the offline copy too; a purge failure must not break unfavoriting.
            runCatchingCancellable { appServices.browse.purgeExtensionPublicationContentV2(sourceKey, remoteId) }
        }
    }

    fun ExtensionLibraryBindingV2.toBrowseManga(manga: Manga): BrowseManga = BrowseManga(
        sourceId = sourceKey.legacyLongId ?: Long.MIN_VALUE,
        url = "",
        title = manga.title,
        thumbnailUrl = manga.thumbnailUrl,
        author = manga.author,
        artist = manga.artist,
        description = manga.description,
        genre = manga.genre,
        sourceKey = sourceKey,
        remotePublicationId = remotePublicationId,
    )

    /**
     * Runs [proceed] once the package [packageId] may run. A package pre-installed from the account
     * has no runtime until its permissions are reviewed, so opening its title first shows that
     * review (approving continues, dismissing stays where the user was). Anything else proceeds at
     * once, without waiting on the plugin manager.
     */
    fun afterFirstAccessReview(packageId: String, proceed: () -> Unit) {
        val extension = appServices.browse.state.value.extensions.firstOrNull { it.id == packageId }
        if (extension == null || !extension.installed || extension.trusted || extension.inDevelopment) {
            proceed()
            return
        }
        scope.launch {
            val review = try {
                appServices.browse.pendingPluginEventGrantReview(packageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (review == null) proceed() else sourceReviewGate = SourceReviewGate(extension, review, proceed)
        }
    }

    fun openLocalExtensionManga(manga: Manga): Boolean {
        return when (
            val route = localLibraryExtensionRoute(
                manga = manga,
                hasLocalChapters = repository.currentSnapshot.chapters.any { it.mangaId == manga.id },
                legacyBinding = appServices.browse::extensionLibraryBindingV2,
            )
        ) {
            LocalLibraryExtensionRoute.LocalDetail -> false
            is LocalLibraryExtensionRoute.Open -> {
                afterFirstAccessReview(route.binding.sourceKey.packageId) {
                    section = localLibraryExtensionHostSection()
                    if (route.migrateLegacyUrl) {
                        mutate {
                            repository.updateManga(manga.id) { stored ->
                                stored.copy(
                                    url = encodeExtensionLibraryPublicationUrl(
                                        route.binding.sourceKey,
                                        route.binding.remotePublicationId,
                                    ),
                                )
                            }
                        }
                    }
                    selectedMangaId = null
                    moreDestination = null
                    localLibraryReaderBackRequest = 0L
                    localLibraryPublication = route.binding.toBrowseManga(manga)
                }
                true
            }
            is LocalLibraryExtensionRoute.RecoverLegacy -> {
                val hostSection = localLibraryExtensionHostSection()
                // UUID-only beta rows cannot be reversed directly. Search enabled v2 sources and
                // accept only a candidate whose deterministic publication UUID is an exact match.
                // The request token prevents a cancelled recovery from navigating after Back.
                val request = localLibraryRecoveryRequest + 1
                localLibraryRecoveryRequest = request
                recoveringLocalLibraryMangaId = manga.id
                scope.launch {
                    try {
                        val searched = appServices.browse.recoverExtensionLibraryBindingV2(
                            route.publicationKey,
                            manga.title,
                        )
                        if (
                            localLibraryRecoveryRequest != request ||
                            recoveringLocalLibraryMangaId != manga.id
                        ) {
                            return@launch
                        }
                        if (searched == null) {
                            recoveringLocalLibraryMangaId = null
                            snackbar.showSnackbar(
                                strings.text(
                                    "Unable to recover this legacy extension favorite. Remove it and add the title again from its source.",
                                ),
                            )
                            return@launch
                        }
                        if (
                            localLibraryRecoveryRequest != request ||
                            recoveringLocalLibraryMangaId != manga.id
                        ) {
                            return@launch
                        }
                        val repaired = runCatchingCancellable {
                            repository.updateManga(manga.id) { stored ->
                                stored.copy(
                                    url = encodeExtensionLibraryPublicationUrl(
                                        searched.sourceKey,
                                        searched.remotePublicationId,
                                    ),
                                )
                            }
                        }
                        if (
                            localLibraryRecoveryRequest != request ||
                            recoveringLocalLibraryMangaId != manga.id
                        ) {
                            return@launch
                        }
                        section = hostSection
                        selectedMangaId = null
                        moreDestination = null
                        localLibraryReaderBackRequest = 0L
                        localLibraryPublication = searched.toBrowseManga(repository.manga(manga.id) ?: manga)
                        recoveringLocalLibraryMangaId = null
                        repaired.exceptionOrNull()?.let {
                            snackbar.showSnackbar(
                                strings.text("The title opened, but its repaired extension identity could not be saved."),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (
                            localLibraryRecoveryRequest == request &&
                            recoveringLocalLibraryMangaId == manga.id
                        ) {
                            recoveringLocalLibraryMangaId = null
                            snackbar.showSnackbar(
                                error.message ?: strings.text(
                                    "Unable to recover this legacy extension favorite. Remove it and add the title again from its source.",
                                ),
                            )
                        }
                    } finally {
                        if (
                            localLibraryRecoveryRequest == request &&
                            recoveringLocalLibraryMangaId == manga.id
                        ) {
                            recoveringLocalLibraryMangaId = null
                        }
                    }
                }
                true
            }
        }
    }

    fun recordV2ReaderProgress(
        title: String,
        unitTitle: String,
        locator: ReadingLocator,
        pageIndex: Int,
        pageCount: Int?,
    ) {
        if (snapshot.settings.security.incognitoMode) return
        val sessionKey = "${locator.scope.publicationId.value}:${locator.scope.unitId.value}"
        val progressSessionId = v2ReaderProgressSessions.getOrPut(sessionKey) {
            "v2-reader-${sessionKey.hashCode()}"
        }
        v2ReaderProgressCoordinator.enqueue(
            V2ReaderProgressEvent(
                title = title,
                unitTitle = unitTitle,
                locator = locator,
                pageIndex = pageIndex.coerceAtLeast(0),
                pageCount = pageCount,
                progressSessionId = progressSessionId,
                readAt = readerProgressObservationClock.next(Clock.System.now().toEpochMilliseconds()),
            ),
        )
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
        if (recoveringLocalLibraryMangaId != null) {
            localLibraryRecoveryRequest++
            recoveringLocalLibraryMangaId = null
        }
        localLibraryPublication = null
        browseReaderOpen = false
        browseOwnedLegacyMangaId = null
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
        val manga = repository.currentSnapshot.mangas.firstOrNull { it.id == mangaId }
        dismissMobileInput()
        browseOwnedLegacyMangaId = null
        if (manga != null && openLocalExtensionManga(manga)) return
        if (manga != null) {
            val hostSection = legacyLibraryFavoriteHostSection(section, manga.favorite)
            if (hostSection != section) {
                section = hostSection
                browseBackRequest = 0L
                browseBackAvailable = false
            }
        }
        localLibraryPublication = null
        browseReaderOpen = false
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
        localLibraryPublication = null
        browseReaderOpen = false
        browseOwnedLegacyMangaId = null
        pendingBrowseManga = item
        val request = pendingBrowseRequest + 1
        pendingBrowseRequest = request
        selectedMangaId = null
        moreDestination = null
    }

    fun closeBrowseParentDetail() {
        // Legacy source details live in the app shell while their catalogue/search parent lives
        // inside BrowseScreen. Invalidate an unresolved child first so it cannot navigate after
        // its parent has closed, then remove only the resolved child owned by that parent.
        pendingBrowseRequest++
        pendingBrowseManga = null
        val previousSelection = selectedMangaId
        selectedMangaId = selectedMangaAfterBrowseParentDismissed(
            selectedMangaId = previousSelection,
            browseOwnedMangaId = browseOwnedLegacyMangaId,
        )
        if (selectedMangaId != previousSelection) {
            selectedChapterIds = emptySet()
            detailBackRequest = 0L
            detailNestedBackAvailable = false
        }
        browseOwnedLegacyMangaId = null
    }

    fun openMore(destination: MoreDestination) {
        dismissMobileInput()
        localLibraryPublication = null
        browseReaderOpen = false
        moreBackRequest = 0L
        moreNestedBackAvailable = false
        moreDestination = destination
        selectedMangaId = null
    }

    fun closeRemovedLibraryTitles(mangaIds: Set<Long>) {
        val openExtensionKey = localLibraryPublication?.let { item ->
            val sourceKey = item.sourceKey ?: return@let null
            val remoteId = item.remotePublicationId ?: return@let null
            extensionPublicationKey(sourceKey, remoteId)
        }
        if (
            openExtensionKey != null &&
            repository.currentSnapshot.mangas.any { manga ->
                manga.id in mangaIds &&
                    (decodeExtensionLibraryPublicationUrl(manga.url)?.publicationKey == openExtensionKey ||
                        decodeTypedLocalPublicationUrl(manga.url) == openExtensionKey)
            }
        ) {
            localLibraryPublication = null
            browseReaderOpen = false
        }
        if (selectedMangaId in mangaIds) {
            selectedMangaId = null
            selectedChapterIds = emptySet()
        }
    }

    fun resetReaderPositionState() {
        readerPositionInitializedFor = null
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
                progressSessionId = "local-reader-$readerSessionSerial",
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
                progressSessionId = "local-reader-$readerSessionSerial",
            )
        } else {
            null
        }
        readerTransitionInFlight = true
        scope.launch {
            runCatchingCancellable { readerLocalProgressCoordinator.flushLocal() }
                .onFailure { failure ->
                    snackbar.showSnackbar(
                        failure.message ?: strings.text("The latest reading position could not be saved."),
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
        val result = runCatchingCancellable { appServices.browse.resolveManga(item) }
        if (pendingBrowseRequest != request || pendingBrowseManga != item) return@LaunchedEffect
        result.onSuccess { mangaId ->
            pendingBrowseManga = null
            if (mangaId != null) {
                detailBackRequest = 0L
                detailNestedBackAvailable = false
                browseOwnedLegacyMangaId = mangaId
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
                    val localVisualPageIndex = repository.chapter(session.chapterId)?.lastPageRead
                        ?.coerceAtLeast(0)
                    val localHistory = repository.currentSnapshot.histories
                        .firstOrNull { it.chapterId == session.chapterId }
                    val localLocator = localHistory?.lastLocator
                        ?.takeIf { navigation.indexOf(it) != null }
                    TypedReaderContentSession(
                        content = typed.content.copy(
                            initialLocator = localLocator ?: typed.content.initialLocator,
                        ),
                        canonicalText = typed.canonicalText,
                        access = typed.access,
                        initialVisualPageIndex = localVisualPageIndex,
                        initialVisualPageCount = localHistory?.lastPageCount,
                        remoteAssetScope = typed.remoteAssetScope,
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
                readerError = failure.localizedSourceFailure(strings)
                    ?: failure.message ?: strings.text("Unable to load chapter pages.")
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
    val readerOpen = readerSession != null || browseReaderOpen
    LaunchedEffect(readerOpen, snapshot.settings.reader.keepScreenOn) {
        appServices.setKeepScreenOn(readerOpen && snapshot.settings.reader.keepScreenOn)
    }
    LaunchedEffect(readerOpen, snapshot.settings.reader.fullscreen) {
        appServices.setFullscreen(readerOpen && snapshot.settings.reader.fullscreen)
    }
    val effectiveVolumeKeysEnabled = effectiveReaderVolumeKeysEnabled(snapshot.settings.reader.volumeKeys)
    val interceptReaderVolumeKeys = effectiveReaderVolumeKeyMonitoringEnabled(
        readerOpen = readerOpen,
        configured = snapshot.settings.reader.volumeKeys,
    )
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
    DisposableEffect(appServices, effectiveVolumeKeysEnabled) {
        // Match the original iOS implementation: prepare AVAudioSession, silent playback, and
        // MPVolumeView as soon as the preference is enabled, before the reader starts listening.
        appServices.setReaderVolumeKeyInfrastructureEnabled(effectiveVolumeKeysEnabled)
        onDispose { appServices.setReaderVolumeKeyInfrastructureEnabled(false) }
    }
    DisposableEffect(appServices, interceptReaderVolumeKeys) {
        appServices.setReaderVolumeKeyMonitoringEnabled(interceptReaderVolumeKeys)
        onDispose { appServices.setReaderVolumeKeyMonitoringEnabled(false) }
    }
    LaunchedEffect(interceptReaderVolumeKeys) {
        if (!interceptReaderVolumeKeys) appVolumeKeyPressTracker.clear()
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
        // Every platform retains the current deep link until deepLinkHandled is called. Links
        // are handled only once the application is unlocked.
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
                    is ShinsouDeepLink.AddRepository -> {
                        // The queue re-emits its head whenever another link arrives: this one is
                        // already waiting for the user.
                        if (link == pendingRepositoryLink) return@collect
                        // A link never adds anything by itself: the user confirms every one. A link's
                        // fingerprint is matched against the key the repository presents.
                        selectSection(MainSection.BROWSE)
                        val prompt = appServices.browse.inspectRepositoryTrust(link.repositoryUrl)
                        when {
                            prompt != null -> linkedRepositoryTrust = prompt.copy(linkedFingerprint = link.fingerprint)
                            link.fingerprint != null -> {
                                // A definite refusal: report it and let the next link through.
                                appServices.deepLinkHandled(link)
                                snackbar.showSnackbar(strings.text("This repository presents no signing key to match the link's fingerprint."))
                                return@collect
                            }
                            else -> linkedUnsignedRepository = link.repositoryUrl
                        }
                        // Acknowledged by the dialog: once added, or once the user declines.
                        pendingRepositoryLink = link
                        return@collect
                    }
                    is ShinsouDeepLink.AddDevRepository -> {
                        if (link == pendingRepositoryLink) return@collect
                        selectSection(MainSection.BROWSE)
                        val developer = appServices.browse.packageSecurity().developerMode
                        val admitted = developer != null && dev.shinsou.kmp.plugin.PluginDeveloperModeState(developer.enabled, developer.lanHosts)
                            .admits(link.repositoryUrl)
                        if (!admitted) {
                            // Only this device or an address the user entered: never any other server.
                            appServices.deepLinkHandled(link)
                            snackbar.showSnackbar(strings.text("To add this development server, turn on developer mode in Package security and add its address there (this device is always allowed)."))
                            return@collect
                        }
                        // Confirmed like any unsigned repository; admission still checks the address.
                        linkedUnsignedRepository = link.repositoryUrl
                        pendingRepositoryLink = link
                        return@collect
                    }
                }
                // Failures remain pending for a later retry.
                appServices.deepLinkHandled(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: strings.text("Unable to open this link."))
            }
        }
    }
    linkedRepositoryTrust?.let { prompt ->
        val forLink = pendingRepositoryLink
        dev.shinsou.kmp.ui.screens.RepositoryTrustDialog(
            prompt = prompt,
            onTrust = { fingerprint ->
                linkedRepositoryTrust = null
                linkScope.launch {
                    try {
                        appServices.browse.trustAndAddRepository(prompt, fingerprint)
                        repositoryLinkDone(forLink)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        snackbar.showSnackbar(failure.message ?: strings.text("Unable to open this link."))
                    }
                }
            },
            onDismiss = {
                linkedRepositoryTrust = null
                repositoryLinkDone(forLink) // declined
            },
        )
    }
    linkedUnsignedRepository?.let { url ->
        val forLink = pendingRepositoryLink
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                linkedUnsignedRepository = null
                repositoryLinkDone(forLink) // declined
            },
            title = { Text(strings.text("Add repository")) },
            text = { Text(url) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    linkedUnsignedRepository = null
                    linkScope.launch {
                        try {
                            appServices.browse.addRepository(url)
                            repositoryLinkDone(forLink)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            snackbar.showSnackbar(failure.message ?: strings.text("Unable to open this link."))
                        }
                    }
                }) { Text(strings.text("Add")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    linkedUnsignedRepository = null
                    repositoryLinkDone(forLink) // declined
                }) { Text(strings.cancel) }
            },
        )
    }

    val systemBackHandler = rememberUpdatedState {
        when {
            unlocked && pendingSourceLoginRequest != null -> {
                pendingSourceLoginRequest.eventId?.let(appServices.browse::dismissSourceLoginEvent)
                    ?: appServices.browse.dismissSourceLoginRequest(pendingSourceLoginRequest.sourceId)
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
            localLibraryPublication != null && browseReaderOpen -> {
                localLibraryReaderBackRequest++
                true
            }
            localLibraryPublication != null -> {
                localLibraryPublication = null
                true
            }
            recoveringLocalLibraryMangaId != null -> {
                localLibraryRecoveryRequest++
                recoveringLocalLibraryMangaId = null
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
    DisposableEffect(appServices) {
        appServices.setSystemBackHandler { systemBackHandler.value() }
        onDispose { appServices.setSystemBackHandler(null) }
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
                        recoveringLocalLibraryMangaId != null -> true
                        localLibraryPublication != null && !browseReaderOpen -> true
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
                val volumeEvent = when (event.key) {
                    Key.VolumeUp -> ReaderVolumeKeyEvent.VOLUME_UP
                    Key.VolumeDown -> ReaderVolumeKeyEvent.VOLUME_DOWN
                    else -> null
                }
                if (volumeEvent != null) {
                    // LiveContainer delivers hardware volume buttons as HID to the focused
                    // Compose view. Manga/image readers do not always reclaim focus, so the
                    // app surface must own this path and forward it to the mounted reader.
                    if (!interceptReaderVolumeKeys) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        appVolumeKeyPressTracker.release(volumeEvent)
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (!appVolumeKeyPressTracker.shouldDispatchDown(volumeEvent, repeatCount = 0)) {
                        return@onPreviewKeyEvent true
                    }
                    readerVolumeKeyRouter.dispatch(volumeEvent)
                    return@onPreviewKeyEvent true
                }
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
                enabled = !appServices.prefersDesktopChrome && !readerOpen,
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
                LaunchedEffect(session) {
                    // Reading progress is local only: the reader restores from the chapter itself.
                    if (readerPositionInitializedFor != session) readerPositionInitializedFor = session
                }
                // ADR-0005: manga translation for the page reader (not the typed reader, which has its own).
                val mangaTranslation = appServices.mangaTranslation
                val textTranslation = appServices.contentFeatures?.translation
                var mangaTranslationOpen by remember(session) { mutableStateOf(false) }
                val mangaTranslationState = if (typedReaderSession == null && mangaTranslation != null && textTranslation != null) {
                    dev.shinsou.kmp.ui.screens.rememberMangaReaderTranslation(
                        manga = mangaTranslation,
                        translation = textTranslation,
                        workKey = dev.shinsou.kmp.ui.screens.mangaWorkKey(manga),
                        chapterKey = dev.shinsou.kmp.ui.screens.mangaChapterKey(chapter),
                        pages = activeReaderChapter.pages,
                        exportable = false,
                        // "all", "multi" and local imports say nothing: their first pages are read instead
                        workLanguage = rememberSourceLanguage(appServices.browse, manga.source),
                        labels = remember(manga.title, chapter.name, manga.source) {
                            dev.shinsou.kmp.translation.TranslationLabels(
                                workTitle = manga.title,
                                chapterTitle = chapter.name,
                                sourceName = if (manga.source == LOCAL_SOURCE_ID) strings.text("Local") else null,
                            )
                        },
                    )
                } else {
                    null
                }
                mangaTranslationState?.let { state ->
                    dev.shinsou.kmp.ui.screens.MangaTranslationNotice(state, onOpen = { mangaTranslationOpen = true }, modifier = Modifier.zIndex(6f))
                    if (mangaTranslationOpen) {
                        dev.shinsou.kmp.ui.screens.MangaTranslationSheet(state, onDismiss = { mangaTranslationOpen = false })
                    }
                }
                ReaderScreen(
                    manga = manga,
                    chapter = chapter,
                    pages = mangaTranslationState?.displayPages(activeReaderChapter.pages) ?: activeReaderChapter.pages,
                    onMangaTranslation = mangaTranslationState?.let { { mangaTranslationOpen = true } },
                    onMangaTranslationToggle = mangaTranslationState?.let { state ->
                        {
                            state.settings?.let { current -> scope.launch { state.update(current.toggled()) } }
                            Unit
                        }
                    },
                    mangaTranslationActive = mangaTranslationState?.settings?.shown == true,
                    overlayOpen = mangaTranslationOpen,
                    settings = snapshot.settings.reader,
                    loading = readerTransitionInFlight ||
                        readerLoading ||
                        readerChapterSession != session,
                    errorMessage = readerError,
                    inLibrary = manga.favorite,
                    chapters = navigation.storyOrder,
                    previousChapter = navigation.previous,
                    nextChapter = navigation.next,
                    readerSessionId = session.progressSessionId,
                    positionReportingEnabled = readerPositionInitializedFor == session,
                    volumeKeyRouter = readerVolumeKeyRouter,
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
                        if (
                            !snapshot.settings.security.incognitoMode &&
                            readerProgressSessionIsActive(
                                activeSession = readerSession,
                                callbackSession = session,
                                transitionInFlight = readerTransitionInFlight,
                            )
                        ) {
                            val readAt = readerProgressObservationClock.next(
                                Clock.System.now().toEpochMilliseconds(),
                            )
                            val completedNow = activeReaderChapter.pages.isNotEmpty() &&
                                position.pageIndex == activeReaderChapter.pages.lastIndex &&
                                !chapter.read &&
                                completedReaderChapterIds.add(chapter.id)
                            readerLocalProgressCoordinator.enqueue(
                                ReaderLocalProgressEvent(
                                    chapterId = chapter.id,
                                    pageIndex = position.pageIndex,
                                    read = chapter.read || completedNow,
                                    readAt = readAt,
                                    pageCount = activeReaderChapter.pages.size.takeIf { it > 0 },
                                ),
                            )
                            mutate {
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
                                            runCatchingCancellable {
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
                    onToggleFavorite = {
                        mutate {
                            val updated = repository.toggleMangaFavorite(manga.id)
                            if (!updated.favorite) {
                                readerCategoryPickerMangaId = null
                                selectedMangaId = null
                                selectedChapterIds = emptySet()
                                removeDownloadsForMangas(
                                    appServices = appServices,
                                    queue = snapshot.downloadQueue,
                                    mangaIds = setOf(manga.id),
                                )
                                transitionReader()
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
                    unifiedReaderInitialPageIndex = typedReaderSession?.initialVisualPageIndex,
                    unifiedReaderInitialPageCount = typedReaderSession?.initialVisualPageCount,
                    unifiedReaderTranslationAvailable = typedReaderSession?.let { typed ->
                        remember(typed, appServices.contentFeatures, appServices.mangaTranslation) {
                            readerTranslationAvailable(typed, appServices.contentFeatures) ||
                                // ADR-0005: image chapters, through manga translation
                                (appServices.mangaTranslation != null && dev.shinsou.kmp.ui.screens.mangaTranslationAvailable(typed, appServices.contentFeatures))
                        }
                    } ?: false,
                    unifiedReaderRenderer = typedReaderSession?.let { typed ->
                        { _, rendererModifier, renderState, onPageIndexChanged, onPageCountInvalidated,
                            onNavigationBoundary, onReaderTap ->
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
                                        dev.shinsou.kmp.translation.TranslationLabels(
                                            workTitle = manga.title,
                                            chapterTitle = chapter.name,
                                            sourceName = if (manga.source == LOCAL_SOURCE_ID) strings.text("Local") else null,
                                        )
                                    },
                                    onPageIndexChanged = onPageIndexChanged,
                                    onPageCountInvalidated = onPageCountInvalidated,
                                    onNavigationBoundary = onNavigationBoundary,
                                    onReaderTap = onReaderTap,
                                    onLocatorChanged = { locator, pageIndex, pageCount ->
                                        if (
                                            !snapshot.settings.security.incognitoMode &&
                                            readerProgressSessionIsActive(
                                                activeSession = readerSession,
                                                callbackSession = session,
                                                transitionInFlight = readerTransitionInFlight,
                                            )
                                        ) {
                                            typed.content.navigation.indexOf(locator)?.let { semanticIndex ->
                                                val readAt = readerProgressObservationClock.next(
                                                    Clock.System.now().toEpochMilliseconds(),
                                                )
                                                val completed =
                                                    semanticIndex == typed.content.navigation.itemCount - 1 &&
                                                        (locator.progression ?: 0.0) >= 0.995
                                                readerLocalProgressCoordinator.enqueue(
                                                    ReaderLocalProgressEvent(
                                                        chapterId = chapter.id,
                                                        pageIndex = pageIndex.coerceAtLeast(0),
                                                        read = chapter.read || completed,
                                                        readAt = readAt,
                                                        locator = locator,
                                                        pageCount = pageCount,
                                                    ),
                                                )
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
                snackbarHost = {
                    if (!readerOpen) SnackbarHost(snackbar)
                },
                bottomBar = {
                    if (!browseReaderOpen) {
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
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    val phoneDestination = when {
                        recoveringLocalLibraryMangaId != null -> PhoneDestination.Recovering(
                            recoveringLocalLibraryMangaId!!,
                        )
                        localLibraryPublication != null -> PhoneDestination.LocalExtension(
                            localLibraryPublication!!,
                        )
                        pendingBrowseManga != null -> PhoneDestination.Pending(pendingBrowseManga!!)
                        selectedMangaId != null -> PhoneDestination.Manga(selectedMangaId!!)
                        moreDestination != null -> PhoneDestination.More(moreDestination!!)
                        else -> PhoneDestination.Section
                    }
                    LaunchedEffect(
                        phoneDestination,
                        browseBackAvailable,
                        browseBackRequest,
                        detailNestedBackAvailable,
                        moreNestedBackAvailable,
                    ) {
                        // A committed gesture has already moved the active surface off-screen.
                        // Reset after its route (or deepest nested route) changes. Browse owns
                        // nested publication routes, so its availability can remain true when a
                        // publication closes back to an active source catalogue; the request key
                        // is the route-change signal in that case.
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
                        onBrowseParentDismissed = ::closeBrowseParentDetail,
                        onBrowseReaderVisibilityChanged = { browseReaderOpen = it },
                        onBrowseReaderProgress = ::recordV2ReaderProgress,
                        onBrowseReaderProgressFlushed = v2ReaderProgressCoordinator::flushLocal,
                        readerVolumeKeyRouter = readerVolumeKeyRouter,
                        onToggleLocalLibrary = ::toggleV2PublicationLocalLibrary,
                        isLocalLibraryFavorite = ::isV2PublicationInLocalLibrary,
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
                            is PhoneDestination.Recovering -> backSwipeProgress.value
                            is PhoneDestination.LocalExtension -> backSwipeProgress.value
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
                                        is PhoneDestination.Recovering -> repository.currentSnapshot.mangas
                                            .firstOrNull { it.id == destination.id }
                                            ?.let { recovering ->
                                                PendingMangaDetailPane(
                                                    item = BrowseManga(
                                                        sourceId = LOCAL_SOURCE_ID,
                                                        url = recovering.url,
                                                        title = recovering.title,
                                                        thumbnailUrl = recovering.thumbnailUrl,
                                                    ),
                                                    onBack = {
                                                        localLibraryRecoveryRequest++
                                                        recoveringLocalLibraryMangaId = null
                                                    },
                                                )
                                            }
                                        is PhoneDestination.LocalExtension -> ExtensionV2PublicationPane(
                                            callbacks = appServices.browse,
                                            sync = appServices.sync,
                                            item = destination.item,
                                            supportsFavorite = true,
                                            favoriteDestination = ExtensionFavoriteDestination.LOCAL_LIBRARY,
                                            localLibrary = true,
                                            localLibraryFavorite = isV2PublicationInLocalLibrary(destination.item),
                                            onToggleLocalLibrary = ::toggleV2PublicationLocalLibrary,
                                            refreshGeneration = destination.item.sourceKey
                                                ?.let(sourceRefreshInvalidations::get) ?: 0L,
                                            contentFeatures = appServices.contentFeatures,
                                            copyText = appServices::copyText,
                                            readerSettings = snapshot.settings.reader,
                                            onReaderSettingsChange = { readerSettings ->
                                                mutate {
                                                    repository.updateSettings { it.copy(reader = readerSettings) }
                                                }
                                            },
                                            onOpenExternalUrl = appServices::openExternalUrl,
                                            onShareText = appServices::shareText,
                                            onReaderVisibilityChanged = { browseReaderOpen = it },
                                            onReaderProgress = ::recordV2ReaderProgress,
                                            onReaderProgressFlushed = v2ReaderProgressCoordinator::flushLocal,
                                            volumeKeyRouter = readerVolumeKeyRouter,
                                            readerBackRequest = localLibraryReaderBackRequest,
                                            onBack = {
                                                browseReaderOpen = false
                                                localLibraryPublication = null
                                            },
                                        )
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
                if (!browseReaderOpen) {
                    DesktopSidebar(
                        selected = section,
                        onSelect = ::selectSection,
                        downloadCount = pendingDownloadCount(snapshot.downloadQueue, appServices),
                        modifier = Modifier.width(228.dp).fillMaxHeight(),
                    )
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                }
                val mainContentModifier = if (
                    localLibraryPublication != null && (browseReaderOpen || !splitDetail)
                ) {
                    Modifier.width(0.dp).fillMaxHeight()
                } else {
                    Modifier
                        .weight(
                            if (
                                !browseReaderOpen &&
                                splitDetail &&
                                (selectedMangaId != null || localLibraryPublication != null)
                            ) {
                                0.9f
                            } else {
                                1f
                            },
                        )
                        .fillMaxHeight()
                }
                Box(
                    mainContentModifier,
                ) {
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
                                onBrowseParentDismissed = ::closeBrowseParentDetail,
                                onBrowseReaderVisibilityChanged = { browseReaderOpen = it },
                                onBrowseReaderProgress = ::recordV2ReaderProgress,
                                onBrowseReaderProgressFlushed = v2ReaderProgressCoordinator::flushLocal,
                                readerVolumeKeyRouter = readerVolumeKeyRouter,
                                onToggleLocalLibrary = ::toggleV2PublicationLocalLibrary,
                                isLocalLibraryFavorite = ::isV2PublicationInLocalLibrary,
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
                            } else if (recoveringLocalLibraryMangaId != null) {
                                repository.currentSnapshot.mangas
                                    .firstOrNull { it.id == recoveringLocalLibraryMangaId }
                                    ?.let { recovering ->
                                        Surface(
                                            modifier = Modifier.fillMaxSize(),
                                            color = MaterialTheme.colorScheme.background,
                                            contentColor = MaterialTheme.colorScheme.onBackground,
                                        ) {
                                            PendingMangaDetailPane(
                                                item = BrowseManga(
                                                    sourceId = LOCAL_SOURCE_ID,
                                                    url = recovering.url,
                                                    title = recovering.title,
                                                    thumbnailUrl = recovering.thumbnailUrl,
                                                ),
                                                onBack = {
                                                    localLibraryRecoveryRequest++
                                                    recoveringLocalLibraryMangaId = null
                                                },
                                            )
                                        }
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
                if (localLibraryPublication != null && moreDestination == null) {
                    if (!browseReaderOpen && splitDetail) {
                        VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    }
                    Surface(
                        modifier = Modifier
                            .weight(if (!browseReaderOpen && splitDetail) 1.1f else 1f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        val publication = localLibraryPublication!!
                        ExtensionV2PublicationPane(
                            callbacks = appServices.browse,
                            sync = appServices.sync,
                            item = publication,
                            supportsFavorite = true,
                            favoriteDestination = ExtensionFavoriteDestination.LOCAL_LIBRARY,
                            localLibrary = true,
                            localLibraryFavorite = isV2PublicationInLocalLibrary(publication),
                            onToggleLocalLibrary = ::toggleV2PublicationLocalLibrary,
                            refreshGeneration = publication.sourceKey
                                ?.let(sourceRefreshInvalidations::get) ?: 0L,
                            contentFeatures = appServices.contentFeatures,
                            copyText = appServices::copyText,
                            readerSettings = snapshot.settings.reader,
                            onReaderSettingsChange = { readerSettings ->
                                mutate {
                                    repository.updateSettings { it.copy(reader = readerSettings) }
                                }
                            },
                            onOpenExternalUrl = appServices::openExternalUrl,
                            onShareText = appServices::shareText,
                            onReaderVisibilityChanged = { browseReaderOpen = it },
                            onReaderProgress = ::recordV2ReaderProgress,
                            onReaderProgressFlushed = v2ReaderProgressCoordinator::flushLocal,
                            volumeKeyRouter = readerVolumeKeyRouter,
                            readerBackRequest = localLibraryReaderBackRequest,
                            onBack = {
                                browseReaderOpen = false
                                localLibraryPublication = null
                            },
                        )
                    }
                } else if (!browseReaderOpen && splitDetail && selectedMangaId != null && moreDestination == null) {
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
            if (!readerOpen) {
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(18.dp))
            }
        }

        sourceReviewGate?.let { gate ->
            var details by remember(gate) { mutableStateOf<PluginPermissionDetails?>(null) }
            LaunchedEffect(gate) {
                details = runCatchingCancellable { appServices.browse.pluginPermissionDetails(gate.extension.id, gate.review) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
            PluginPermissionReviewDialog(
                review = gate.review,
                details = details,
                onConfirm = { selection ->
                    sourceReviewGate = null
                    scope.launch {
                        try {
                            appServices.browse.approvePluginEventGrantReview(
                                gate.extension.id,
                                gate.review,
                                selection.hostPermissions,
                                selection,
                            )
                            // The package runs now: titles and progress that waited for it can apply.
                            appServices.sync?.sourcesChanged()
                            gate.proceed()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            snackbar.showSnackbar(error.message ?: error::class.simpleName.orEmpty())
                        }
                    }
                },
                onDismiss = { sourceReviewGate = null },
            )
        }

        if (unlocked && pendingSourceLoginRequest != null) {
            PendingSourceLoginDialog(
                request = pendingSourceLoginRequest,
                callbacks = appServices.browse,
            )
        }

        if (unlocked && appLifecycle == AppLifecycleState.FOREGROUND) {
            if (pluginLogoutConfirmations.isNotEmpty()) {
                val request = pluginLogoutConfirmations.first()
                var confirming by remember(request.eventId) { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { if (!confirming) appServices.browse.dismissPluginLogout(request.eventId) },
                    title = { Text(strings.text("Logout")) },
                    text = {
                        Text(request.message?.takeIf(String::isNotBlank)
                            ?: strings.text("Log out of {0}?", request.sourceName))
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !confirming,
                            onClick = {
                                confirming = true
                                scope.launch {
                                    runCatchingCancellable { appServices.browse.confirmPluginLogout(request.eventId) }
                                    confirming = false
                                }
                            },
                        ) { Text(strings.text("Logout")) }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !confirming,
                            onClick = { appServices.browse.dismissPluginLogout(request.eventId) },
                        ) { Text(strings.cancel) }
                    },
                )
            }
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

internal suspend fun removeDownloadsForMangas(
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

/** Manga and novel downloads that still need work; both queues feed the same Downloads screen. */
@Composable
internal fun pendingDownloadCount(queue: List<DownloadQueueItem>, appServices: ShinsouAppServices): Int {
    val extensionDownloads by appServices.browse.extensionDownloadsV2.collectAsState()
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

private sealed interface PhoneDestination {
    data object Section : PhoneDestination
    data class Recovering(val id: Long) : PhoneDestination
    data class LocalExtension(val item: BrowseManga) : PhoneDestination
    data class Pending(val item: BrowseManga) : PhoneDestination
    data class Manga(val id: Long) : PhoneDestination
    data class More(val destination: MoreDestination) : PhoneDestination
}

internal fun navigationItems(strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings): List<NavigationItem> = listOf(
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

/** A title of a package waiting for its permission review was opened; [proceed] opens it once approved. */
private class SourceReviewGate(
    val extension: BrowseExtension,
    val review: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
    val proceed: () -> Unit,
)

/**
 * The language a work's [source] says it is in, when translation knows it; blank for "all", "multi"
 * and local imports, and until the sources are loaded (it follows them, as they load after launch).
 */
@Composable
internal fun rememberSourceLanguage(browse: BrowseCallbacks, source: Long): String {
    val sources = browse.state.collectAsState().value.sources
    return remember(sources, source) {
        sources.firstOrNull { it.id == source }?.language
            ?.takeIf { dev.shinsou.kmp.translation.OfflineModelCatalog.language(it) != null }.orEmpty()
    }
}
