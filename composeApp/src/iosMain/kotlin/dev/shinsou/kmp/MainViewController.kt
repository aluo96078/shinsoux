package dev.shinsou.kmp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.app.checkBeforeOpening
import dev.shinsou.kmp.content.SchemaTooNewException
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.persistence.WholeJsonSnapshotStorage
import dev.shinsou.kmp.files.IosAppFileSystem
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.plugin.IosBrowserUserAgentProvider
import dev.shinsou.kmp.tts.IosTextToSpeechEngine
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.RetainedDeepLinkQueue
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.ShinsouStartupScreen
import dev.shinsou.kmp.ui.screens.SchemaTooNewNotice
import dev.shinsou.kmp.plugin.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.iosSandboxRuntimeFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import platform.UIKit.UIViewController

/**
 * Native entry point consumed by the SwiftUI application host. The app's stores open off the main
 * thread ([IosApp]); a progress screen shows until they are.
 */
@Suppress("FunctionName")
public fun MainViewController(): UIViewController {
    IosApp.ensureOpening()
    return ComposeUIViewController {
        val state by IosApp.state.collectAsState()
        when (val current = state) {
            IosAppState.Opening -> ShinsouStartupScreen()
            // A library written by a newer app is left untouched: ask for the update instead (R3-1).
            is IosAppState.TooNew -> SchemaTooNewNotice(current.error)
            is IosAppState.Ready -> {
                val container = current.container
                LaunchedEffect(container) { container.start() }
                App(
                    repository = container.repository,
                    appServices = container.services,
                    autoBackupService = container.autoBackups,
                )
            }
        }
    }
}

/** Queues custom-scheme URLs even when Swift receives them before the stores are open. */
public fun handleDeepLink(url: String): Boolean {
    val link = DeepLinkParser.parse(url) ?: return false
    return IosApp.deliverDeepLink(link)
}

/** Read by SwiftUI when producing app-switcher and screen-recording privacy covers. */
public fun isSecureScreenEnabled(): Boolean = IosSecurityState.enabled

/** Receives SwiftUI scene transitions so common app-lock timeout logic is platform-neutral. */
public fun setApplicationForeground(foreground: Boolean) {
    IosApp.setApplicationForeground(foreground)
}

/** Public presentation state queried by the Swift UIKit host after change notifications. */
public fun currentReaderOrientation(): String? = IosReaderPresentationState.orientation?.name

public fun isReaderFullscreenEnabled(): Boolean = IosReaderPresentationState.fullscreen

/** Native edge-back must not compete with the pager while a manga chapter is open. */
public fun isReaderOpen(): Boolean = IosReaderPresentationState.readerOpen

/** Swift keeps the original app's MPVolumeView/audio infrastructure warm while configured. */
public fun isReaderVolumeKeyInfrastructureEnabled(): Boolean =
    IosReaderVolumeKeyState.infrastructureEnabled

/**
 * Reads the durable reader preference directly from the repository.
 *
 * The infrastructure flag above is only a Compose-to-Swift notification mirror. During a cold
 * launch Compose can dispose and recreate its effect while SwiftUI is installing the root view,
 * so treating that mirror as the source of truth made volume keys stay disabled until the user
 * toggled the setting. The native host uses this value for startup and reader-entry decisions;
 * it is false until the stores are open.
 */
public fun isReaderVolumeKeyConfigured(): Boolean =
    IosApp.container?.repository?.currentSnapshot?.settings?.reader?.volumeKeys == true

/** Swift's AVAudioSession observer queries this after reader/settings change notifications. */
public fun isReaderVolumeKeyMonitoringEnabled(): Boolean =
    IosReaderVolumeKeyState.monitoringEnabled

/** Delivers one physical button press into the common reader event stream. */
public fun handleReaderVolumeKey(volumeUp: Boolean): Boolean =
    IosApp.container?.services?.emitReaderVolumeKey(
        if (volumeUp) ReaderVolumeKeyEvent.VOLUME_UP else ReaderVolumeKeyEvent.VOLUME_DOWN,
    ) == true

/** Delivers a native iOS edge-back request to the common navigation layer. */
public fun handleSystemBackGesture(): Boolean = IosApp.container?.services?.emitSystemBack() == true

/** Delivers the current iOS edge-back drag fraction to the common navigation layer. */
public fun handleSystemBackGestureProgress(fraction: Float): Boolean =
    IosApp.container?.services?.emitSystemBackGestureProgress(fraction) == true

/** Completes or cancels the current iOS edge-back drag. */
public fun handleSystemBackGestureSettled(committed: Boolean): Boolean =
    IosApp.container?.services?.emitSystemBackGestureSettled(committed) == true

/**
 * Called synchronously from the SwiftUI App initializer, before any suspend startup work. The
 * tasks are registered at once (the system requires it before launch ends) and wait for the
 * stores, which start opening here, off the main thread.
 */
public fun registerAutomaticBackupBackgroundTask(): Boolean {
    IosApp.ensureOpening()
    // The sync refresh task shares this launch-time hook so the Swift side stays unchanged.
    IosBackgroundWork.syncScheduler.register()
    return IosBackgroundWork.autoBackupScheduler.register()
}

/** Called synchronously from the SwiftUI App initializer, like the backup task above. */
public fun registerBackgroundDownloadTasks() {
    IosApp.ensureOpening()
    IosBackgroundWork.downloadKeeper.registerTasks()
}

/**
 * Swift's volume-key support shares the app's AVAudioSession and asks this before deactivating
 * it: deactivating would stop the silent audio keeping background downloads alive.
 */
public fun isDownloadSilentAudioInUse(): Boolean = IosBackgroundWork.downloadKeeper.silentAudioInUse

/** Re-submits the earliest-begin-date hint when the scene moves to the background. */
public fun scheduleAutomaticBackupBackgroundTask(): Boolean {
    // Before the stores are open the sync request is remembered and submitted once they are.
    IosBackgroundWork.syncScheduler.schedule()
    val container = IosApp.container ?: return false
    return IosBackgroundWork.autoBackupScheduler.apply(container.repository.currentSnapshot.backupState)
}

/** Where the app is in opening its stores. */
internal sealed interface IosAppState {
    data object Opening : IosAppState

    /** A newer Shinsou X wrote the library: the container is never created (R3-1). */
    class TooNew(val error: SchemaTooNewException) : IosAppState

    class Ready(val container: IosApplicationContainerCore) : IosAppState
}

/**
 * Opens the app's stores once per process, on a background thread: the snapshot, the content
 * database (its schema check, migrations and pre-upgrade copy) and the app composition. Swift hooks
 * that arrive before then are kept (deep links, the scene's foreground state) or answered as
 * "nothing open yet".
 */
internal object IosApp {
    /** Where the container's long-lived work runs. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutableState = MutableStateFlow<IosAppState>(IosAppState.Opening)
    val state: StateFlow<IosAppState> = mutableState.asStateFlow()

    /**
     * The app's deep links, kept from launch: one bounded queue before and after the stores open,
     * so a link accepted while they are opening is retained, not dropped when they are ready.
     */
    private val deepLinks = RetainedDeepLinkQueue()

    /** Main thread only: the scene's foreground state while the stores were opening. */
    private var pendingForeground: Boolean? = null
    private var opening = false

    val container: IosApplicationContainerCore? get() = (state.value as? IosAppState.Ready)?.container

    /** The library was written by a newer app: this one can never open it (until updated). */
    val cannotOpen: Boolean get() = state.value is IosAppState.TooNew

    /** Main thread: starts opening the stores (once). */
    fun ensureOpening() {
        if (opening) return
        opening = true
        // UIKit is main-thread only: read here, before the stores open on a background thread.
        val deviceName = platform.UIKit.UIDevice.currentDevice.name.ifBlank { "iPhone" }
        applicationScope.launch {
            val opened = openIosApp(deepLinks, deviceName)
            withContext(Dispatchers.Main) {
                mutableState.value = opened
                (opened as? IosAppState.Ready)?.container?.let(::deliverPending)
            }
        }
    }

    /** The container once the stores are open; null when they cannot be (a newer library). */
    suspend fun awaitContainer(): IosApplicationContainerCore? {
        withContext(Dispatchers.Main) { ensureOpening() }
        return (state.first { it != IosAppState.Opening } as? IosAppState.Ready)?.container
    }

    /** Opens the app core and waits until its queues are restored; nothing when it cannot open. */
    suspend fun startAndAwait() {
        val container = awaitContainer() ?: return
        container.start()
        container.awaitStartup()
    }

    /** Main thread. */
    fun deliverDeepLink(link: ShinsouDeepLink): Boolean = when (state.value) {
        is IosAppState.TooNew -> false
        IosAppState.Opening, is IosAppState.Ready -> deepLinks.tryEnqueue(link)
    }

    /** Main thread. */
    fun setApplicationForeground(foreground: Boolean) {
        // The download keeper exists from launch; it follows the scene at once.
        IosBackgroundWork.downloadKeeper.setForeground(foreground)
        val ready = container
        if (ready == null) pendingForeground = foreground else ready.setApplicationForeground(foreground)
    }

    private fun deliverPending(container: IosApplicationContainerCore) {
        IosBackgroundWork.syncScheduler.submitRequestFromBeforeOpen()
        pendingForeground?.let(container::setApplicationForeground)
        pendingForeground = null
    }
}

private suspend fun openIosApp(
    deepLinks: RetainedDeepLinkQueue,
    deviceName: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
): IosAppState =
    withContext(ioDispatcher) {
        val infrastructure = IosContentInfrastructure()
        val tooNew = infrastructure.checkBeforeOpening()
        if (tooNew != null) IosAppState.TooNew(tooNew) else IosAppState.Ready(IosApplicationContainerCore(infrastructure, deepLinks, deviceName))
    }

/**
 * The system's background tasks, registered at launch before the stores are open. Each run first
 * waits for them ([IosApp.startAndAwait]).
 */
internal object IosBackgroundWork {
    val autoBackupScheduler = IosAutoBackupScheduler(
        repository = { IosApp.container?.repository },
        service = { IosApp.container?.autoBackups },
        scope = IosApp.applicationScope,
        awaitApp = { IosApp.awaitContainer() },
        cannotOpen = { IosApp.cannotOpen },
    )

    val syncScheduler = IosSyncScheduler(
        sync = { IosApp.container?.sync },
        scope = IosApp.applicationScope,
        startApp = IosApp::startAndAwait,
        cannotOpen = { IosApp.cannotOpen },
    )

    val downloadKeeper = IosDownloadBackgroundKeeper(
        downloads = { IosApp.container?.backgroundDownloads },
        scope = IosApp.applicationScope,
        startApp = IosApp::startAndAwait,
        strings = {
            val preference = IosApp.container?.repository?.currentSnapshot?.settings?.general?.languagePreference
            val tag = preference
                ?.takeUnless { it.equals("system", ignoreCase = true) }
                ?: (NSLocale.preferredLanguages.firstOrNull() as? String)
                ?: "en"
            shinsouStringsFor(tag)
        },
        silentAudioEnabled = {
            IosApp.container?.repository?.currentSnapshot?.settings?.downloads?.keepAliveWithSilentAudio == true
        },
        cannotOpen = { IosApp.cannotOpen },
    )
}

/** For code that runs only once the stores are open (the app services). */
internal object IosApplicationContainer {
    val repository: ShinsouRepository
        get() = checkNotNull(IosApp.container) { "The app's stores are not open yet" }.repository
}

/** The app's graph; made on a background thread by [IosApp] once the content database is usable. */
internal class IosApplicationContainerCore(
    contentInfrastructure: IosContentInfrastructure,
    deepLinks: RetainedDeepLinkQueue,
    /** UIDevice's name, read on the main thread: this is made on a background one. */
    deviceName: String,
) {
    val repository = ShinsouRepository(
        initial = IosSnapshotPersistence.load(),
        storage = WholeJsonSnapshotStorage(IosSnapshotPersistence::save),
    )

    private val httpClient = createPlatformHttpClient().also(::installConfiguredImageLoader)

    /** Idle stops and LRU of the plugin sandboxes; lives as long as the app. */
    private val sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val composition = ShinsouComposition(
        repository = repository,
        httpClient = httpClient,
        pluginKeyValueStore = IosPluginKeyValueStore(),
        fileSystem = IosAppFileSystem(),
        // ADR-0002 §10.1: every package runs in the WASM sandbox (§4.5),
        // one interpreter instance each.
        runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(iosSandboxRuntimeFactory(sandboxScope)),
        contentInfrastructure = contentInfrastructure,
        platformTextToSpeechEngine = IosTextToSpeechEngine(),
        // Apple's Translation framework, through the bridge Swift registers on iOS 26 and later.
        systemTranslation = dev.shinsou.kmp.translation.IosSystemTranslationEngine,
        offlineTranslation = dev.shinsou.kmp.translation.OfflineTranslationPlatform(
            engine = dev.shinsou.kmp.translation.IosOfflineTranslationEngine(),
            modelDirectory = iosTranslationModelDirectory(),
            memory = dev.shinsou.kmp.translation.IosTranslationMemoryGuard(),
        ),
        mangaPlatform = dev.shinsou.kmp.manga.IosMangaPlatform(),
        shuYueMigrationSecretStore = IosShuYueMigrationSecretStore(),
        pluginBrowserSessionTransport = dev.shinsou.kmp.plugin.IosPluginBrowserSessionTransport(),
        platformBrowserUserAgentProvider = IosBrowserUserAgentProvider(),
        syncDeviceInfo = dev.shinsou.kmp.sync.v3.SyncDeviceInfo(
            defaultName = deviceName,
            platform = "ios",
            appVersion = dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION,
        ),
    )

    private val startupMutex = Mutex()
    private val applicationScope = IosApp.applicationScope

    val autoBackups = composition.autoBackups
    val sync = composition.sync
    val backgroundDownloads = composition.backgroundDownloads

    val services = IosAppServices(
        pendingDeepLinks = deepLinks,
        browse = composition.browse,
        content = composition.content,
        contentFeatures = composition.contentFeatures,
        mangaTranslation = composition.mangaTranslation,
        appUpdates = composition.appUpdates,
        tracking = composition.tracking,
        portableContentBackupV2 = composition.portableContentBackupV2,
        sync = composition.sync,
        shuYueMigration = composition.shuYueMigration,
    )

    init {
        // a measuring run, only when launched for one (SHINSOU_MANGA_BENCH_DIR)
        dev.shinsou.kmp.manga.startMangaBenchmarkIfAsked(iosTranslationModelDirectory()) { io.ktor.client.HttpClient(io.ktor.client.engine.darwin.Darwin) }
    }

    private var started = false

    /** Main thread. */
    fun setApplicationForeground(foreground: Boolean) {
        services.setApplicationForeground(foreground)
        applicationScope.launch {
            if (foreground) composition.onForeground() else composition.onBackground()
        }
    }

    suspend fun awaitStartup() = composition.awaitStartup()

    suspend fun start(): Unit = startupMutex.withLock {
        if (started) return@withLock
        composition.start()
        IosBackgroundWork.downloadKeeper.start()
        applicationScope.launch {
            repository.snapshot.collectLatest(IosWidgetPublisher::publish)
        }
        applicationScope.launch {
            repository.snapshot
                .map { snapshot ->
                    snapshot.backupState.let { state ->
                        IosBackupScheduleKey(
                            enabled = state.automaticEnabled,
                            intervalHours = state.intervalHours,
                            lastBackupAt = state.lastBackupAt,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect {
                    IosBackgroundWork.autoBackupScheduler.apply(repository.currentSnapshot.backupState)
                }
        }
        started = true
    }
}

private data class IosBackupScheduleKey(
    val enabled: Boolean,
    val intervalHours: Int,
    val lastBackupAt: Long?,
)
