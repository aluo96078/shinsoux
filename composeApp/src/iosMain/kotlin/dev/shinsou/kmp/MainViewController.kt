package dev.shinsou.kmp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.IosAppFileSystem
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.plugin.JavaScriptCoreScriptPluginRuntimeFactory
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.tts.IosTextToSpeechEngine
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.UIKit.UIViewController

/** Native entry point consumed by the SwiftUI application host. */
@Suppress("FunctionName")
public fun MainViewController(): UIViewController {
    val container = IosApplicationContainer
    return ComposeUIViewController {
        LaunchedEffect(container) { container.start() }
        val readerProgressReporter by container.readerProgressReporter.collectAsState()
        val syncBoundaryReady by container.syncBoundaryReady.collectAsState()
        App(
            repository = container.repository,
            appServices = container.services,
            autoBackupService = container.autoBackups,
            readerProgressReporter = readerProgressReporter,
            interactionReady = syncBoundaryReady,
        )
    }
}

/** Queues custom-scheme URLs even when Swift receives them before Compose starts collecting. */
public fun handleDeepLink(url: String): Boolean {
    val link = DeepLinkParser.parse(url) ?: return false
    return IosApplicationContainer.services.emitDeepLink(link)
}

/** Read by SwiftUI when producing app-switcher and screen-recording privacy covers. */
public fun isSecureScreenEnabled(): Boolean = IosSecurityState.enabled

/** Receives SwiftUI scene transitions so common app-lock timeout logic is platform-neutral. */
public fun setApplicationForeground(foreground: Boolean) {
    IosApplicationContainer.setApplicationForeground(foreground)
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
 * toggled the setting. The native host uses this value for startup and reader-entry decisions.
 */
public fun isReaderVolumeKeyConfigured(): Boolean =
    IosApplicationContainer.repository.currentSnapshot.settings.reader.volumeKeys

/** Swift's AVAudioSession observer queries this after reader/settings change notifications. */
public fun isReaderVolumeKeyMonitoringEnabled(): Boolean =
    IosReaderVolumeKeyState.monitoringEnabled

/** Delivers one physical button press into the common reader event stream. */
public fun handleReaderVolumeKey(volumeUp: Boolean): Boolean =
    IosApplicationContainer.services.emitReaderVolumeKey(
        if (volumeUp) ReaderVolumeKeyEvent.VOLUME_UP else ReaderVolumeKeyEvent.VOLUME_DOWN,
    )

/** Delivers a native iOS edge-back request to the common navigation layer. */
public fun handleSystemBackGesture(): Boolean = IosApplicationContainer.services.emitSystemBack()

/** Delivers the current iOS edge-back drag fraction to the common navigation layer. */
public fun handleSystemBackGestureProgress(fraction: Float): Boolean =
    IosApplicationContainer.services.emitSystemBackGestureProgress(fraction)

/** Completes or cancels the current iOS edge-back drag. */
public fun handleSystemBackGestureSettled(committed: Boolean): Boolean =
    IosApplicationContainer.services.emitSystemBackGestureSettled(committed)

/** Called synchronously from the SwiftUI App initializer, before any suspend startup work. */
public fun registerAutomaticBackupBackgroundTask(): Boolean =
    IosApplicationContainer.autoBackupScheduler.register()

/** Re-submits the earliest-begin-date hint when the scene moves to the background. */
public fun scheduleAutomaticBackupBackgroundTask(): Boolean =
    IosApplicationContainer.autoBackupScheduler.apply(IosApplicationContainer.repository.currentSnapshot.backupState)

internal object IosApplicationContainer {
    val repository = ShinsouRepository(
        initial = IosSnapshotPersistence.load(),
        persist = IosSnapshotPersistence::save,
    )

    private val syncInfrastructure = IosSyncInfrastructure()

    private val composition = ShinsouComposition(
        repository = repository,
        httpClient = createPlatformHttpClient(),
        pluginKeyValueStore = IosPluginKeyValueStore(),
        fileSystem = IosAppFileSystem(),
        runtimeFactory = JavaScriptCoreScriptPluginRuntimeFactory(),
        syncInfrastructure = syncInfrastructure,
        platformTextToSpeechEngine = IosTextToSpeechEngine(),
        shuYueMigrationSecretStore = IosShuYueMigrationSecretStore(),
    )

    private val snapshotSync = SnapshotSyncController(
        repository = repository,
        transport = IosICloudDriveSnapshotTransport(),
        deviceId = "ios",
        deviceIdProvider = composition::installationDeviceId,
        writerAllowed = composition::isLegacySnapshotWriterAllowed,
    )

    private val startupMutex = Mutex()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val autoBackupScheduler = IosAutoBackupScheduler(
        repository = repository,
        service = composition.autoBackups,
        scope = applicationScope,
    )

    val autoBackups = composition.autoBackups

    val readerProgressReporter = requireNotNull(composition.syncRuntime).readerProgressReporter

    val syncBoundaryReady = composition.syncBoundaryReady

    val services = IosAppServices(
        browse = composition.browse,
        content = composition.content,
        contentFeatures = composition.contentFeatures,
        tracking = composition.tracking,
        cloudflareSync = composition.cloudflareSync,
        syncAwareSnapshotRestore = composition.syncAwareSnapshotRestore,
        portableContentBackupV2 = composition.portableContentBackupV2,
        shuYueMigration = composition.shuYueMigration,
        snapshotSync = snapshotSync,
    )

    private var started = false

    fun setApplicationForeground(foreground: Boolean) {
        services.setApplicationForeground(foreground)
        applicationScope.launch {
            if (foreground) composition.onForeground() else composition.onBackground()
        }
    }

    suspend fun start() = startupMutex.withLock {
        if (started) return@withLock
        composition.start()
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
                    autoBackupScheduler.apply(repository.currentSnapshot.backupState)
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
