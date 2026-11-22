package dev.shinsou.kmp.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.shinsou.kmp.ui.shinsouIconPainter
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.app.checkBeforeOpening
import dev.shinsou.kmp.app.openShinsouRepository
import dev.shinsou.kmp.backup.ForegroundAutoBackupScheduler
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.DesktopAppFileSystem
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.logging.LogLevel
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.network.DesktopAvifDecoder
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.plugin.DesktopBrowserUserAgentProvider
import dev.shinsou.kmp.plugin.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.desktopSandboxRuntimeFactory
import dev.shinsou.kmp.tts.DesktopTextToSpeechEngine
import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.ShinsouStartupScreen
import dev.shinsou.kmp.ui.screens.SchemaTooNewNotice
import dev.shinsou.kmp.content.SchemaTooNewException
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import java.awt.desktop.AppForegroundEvent
import java.awt.desktop.AppForegroundListener
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.OpenURIHandler
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.files.DesktopAppDirectories
import dev.shinsou.kmp.files.DesktopPlatform

/** How long closing waits for the hidden window to take its screens away. */
private const val UI_STOP_TIMEOUT_MS = 2_000L
private const val VERIFY_DESKTOP_RUNTIME_ARGUMENT = "--verify-desktop-runtime"
private const val VERIFY_DESKTOP_STARTUP_ARGUMENT = "--verify-desktop-startup"
private const val DESKTOP_PROBE_MARKER_ENVIRONMENT = "SHINSOU_DESKTOP_PROBE_MARKER"
private const val DESKTOP_PROBE_TOKEN_ENVIRONMENT = "SHINSOU_DESKTOP_PROBE_TOKEN"

suspend fun main(args: Array<String>) {
    val desktopPlatform = DesktopPlatform.current
    configureDesktopSystemProperties(desktopPlatform)
    // -Dshinsou.log=debug (or info) for more than warnings while chasing a problem
    System.getProperty("shinsou.log")?.let { name -> LogLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        ?.let { Log.minimumLevel = it }
    if (VERIFY_DESKTOP_RUNTIME_ARGUMENT in args) verifyDesktopRuntimeAndExit()
    val verifyDesktopStartup = VERIFY_DESKTOP_STARTUP_ARGUMENT in args
    if (verifyDesktopStartup) startDesktopProbeWatchdog()
    // Packaged URL protocol handlers pass the full URI as an argv entry. Retain it in the same
    // acknowledge-after-consumption flow used by mobile cold starts.
    val initialDeepLink = args.firstNotNullOfOrNull(DeepLinkParser::parse)
    // Windows and Linux start a new process on every launch; hand it to the running one instead
    // (its window may be hidden while downloads continue). Startup probes run on their own.
    val singleInstance = if (desktopPlatform != DesktopPlatform.MAC_OS && !verifyDesktopStartup) {
        acquireSingleInstanceOrExit(args)
    } else {
        null
    }
    desktopProbeStage("before http client")
    val httpClient = createPlatformHttpClient()
    installConfiguredImageLoader(httpClient) {
        add(DesktopAvifDecoder.Factory())
    }

    desktopProbeStage("before application")
    // Set once the stores are open: whatever way the application ends, they are closed below.
    var openedShell: DesktopShell? = null
    try {
        awaitApplication {
            desktopProbeStage("application")
            val sandboxScope = rememberCoroutineScope()
            val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
            var opened by remember { mutableStateOf<DesktopOpened?>(null) }
            // Closing the startup window must not kill the process mid-migration: the library is
            // opened to the end, then closed the ordinary way.
            var quitWhenOpened by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                desktopProbeStage("before composition")
                // The snapshot, the content database (its schema check, migrations and pre-upgrade
                // copy) and the app composition are opened off the UI thread.
                opened = openDesktopApp(httpClient, desktopPlatform, sandboxScope)
            }
            when (val current = opened) {
                null -> Window(
                    onCloseRequest = { quitWhenOpened = true },
                    visible = !quitWhenOpened,
                    title = "Shinsou X",
                    icon = shinsouIconPainter(),
                    state = windowState,
                ) { ShinsouStartupScreen() }
                // A library written by a newer app is left untouched: ask for the update instead (R3-1).
                is DesktopOpened.TooNew -> if (quitWhenOpened) {
                    LaunchedEffect(current) { exitApplication() }
                } else {
                    Window(onCloseRequest = ::exitApplication, title = "Shinsou X") {
                        SchemaTooNewNotice(current.error)
                    }
                }
                is DesktopOpened.Ready -> {
                    val shell = remember(current) {
                        // The loading window was closed: the app starts hidden, as a window closed
                        // later is, so the download queues are restored (and drained) before it quits.
                        DesktopShell(current, ::exitApplication).also { if (quitWhenOpened) it.windowVisible = false }
                    }
                    SideEffect { openedShell = shell }
                    DesktopApplication(
                        shell = shell,
                        state = windowState,
                        launch = DesktopLaunch(desktopPlatform, initialDeepLink, singleInstance, verifyDesktopStartup),
                    )
                }
            }
        }
    } finally {
        // The usual ways out close the services before they exit, which makes this a no-op; it
        // closes them when the application ended otherwise (an exception out of the composition).
        withContext(NonCancellable) { openedShell?.closeIfOpen() }
    }
    exitProcess(0)
}

/**
 * Becomes the one running instance, or hands [args] to the running one and exits. Fails closed:
 * when the lock cannot be established, a second process might already have the database open, so
 * this one must not start.
 */
private fun acquireSingleInstanceOrExit(args: Array<String>): DesktopSingleInstance {
    val launch = runCatching { DesktopSingleInstance.acquire(DesktopAppDirectories.dataRoot, args.toList()) }
    val warning = when (launch.getOrNull()) {
        is DesktopSingleInstance.Companion.Launch.Primary -> null
        DesktopSingleInstance.Companion.Launch.HandedOff -> exitProcess(0)
        DesktopSingleInstance.Companion.Launch.RunningButUnreachable ->
            StringKey.SHINSOU_X_IS_ALREADY_RUNNING_BUT_IS
        null -> StringKey.SHINSOU_X_COULD_NOT_CONFIRM_THAT_NO
    }
    if (warning != null) {
        val strings = shinsouStringsFor(Locale.getDefault().toLanguageTag())
        JOptionPane.showMessageDialog(
            null,
            strings.text(warning, launch.exceptionOrNull()?.message.orEmpty()).trim(),
            "Shinsou X",
            JOptionPane.WARNING_MESSAGE,
        )
        exitProcess(1)
    }
    return (launch.getOrNull() as DesktopSingleInstance.Companion.Launch.Primary).instance
}

/** What `main` learned about this launch before the window opened. */
private class DesktopLaunch(
    val platform: DesktopPlatform,
    val initialDeepLink: dev.shinsou.kmp.ui.ShinsouDeepLink?,
    val singleInstance: DesktopSingleInstance?,
    val verifyDesktopStartup: Boolean,
)

/** The result of opening the app's stores. */
private sealed interface DesktopOpened {
    class TooNew(val error: SchemaTooNewException) : DesktopOpened

    class Ready(val repository: ShinsouRepository, val composition: ShinsouComposition) : DesktopOpened
}

/** Opens the snapshot, the content database and the app composition on [ioDispatcher]. */
private suspend fun openDesktopApp(
    httpClient: HttpClient,
    desktopPlatform: DesktopPlatform,
    sandboxScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): DesktopOpened = withContext(ioDispatcher) {
    val contentInfrastructure = DesktopContentInfrastructure()
    val tooNew = contentInfrastructure.checkBeforeOpening()
    if (tooNew != null) {
        DesktopOpened.TooNew(tooNew)
    } else {
        // C3: the snapshot JSON with its hot rows from the content database, on this I/O thread.
        val repository = openShinsouRepository(
            loaded = DesktopPersistence.loadSnapshot(),
            file = DesktopPersistence,
            contentInfrastructure = contentInfrastructure,
            hotStateEnabled = !DesktopPersistence.wholeJsonRequested(),
        )
        DesktopOpened.Ready(
            repository = repository,
            composition = ShinsouComposition(
                repository = repository,
                httpClient = httpClient,
                pluginKeyValueStore = DesktopPluginKeyValueStore(),
                fileSystem = DesktopAppFileSystem(),
                // ADR-0002 §10.1: every package runs in the OS-confined
                // shinsou-sandbox helper.
                runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(desktopSandboxRuntimeFactory(sandboxScope)),
                contentInfrastructure = contentInfrastructure,
                platformTextToSpeechEngine = DesktopTextToSpeechEngine(),
                offlineTranslation = dev.shinsou.kmp.translation.OfflineTranslationPlatform(
                    // The helper is looked for on first use: a build without it reports
                    // offline translation as unavailable instead of failing here.
                    engine = dev.shinsou.kmp.translation.TranslationHelperLocator().let { locator ->
                        dev.shinsou.kmp.translation.HelperOfflineTranslationEngine(helper = locator::executable)
                    },
                    modelDirectory = DesktopAppDirectories.dataRoot.resolve("TranslationModels").toString(),
                ),
                mangaPlatform = dev.shinsou.kmp.manga.DesktopMangaPlatform.create(),
                shuYueMigrationSecretStore = DesktopShuYueMigrationSecretStore(),
                pluginBrowserSessionTransport = dev.shinsou.kmp.plugin.DesktopPluginBrowserSessionTransport(),
                platformBrowserUserAgentProvider = DesktopBrowserUserAgentProvider(),
                syncDeviceInfo = dev.shinsou.kmp.sync.v3.SyncDeviceInfo(
                    defaultName = when (desktopPlatform) {
                        DesktopPlatform.MAC_OS -> "Mac"
                        DesktopPlatform.WINDOWS -> "Windows PC"
                        DesktopPlatform.LINUX -> "Linux PC"
                        DesktopPlatform.OTHER -> "Desktop"
                    },
                    platform = "desktop",
                    appVersion = dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION,
                ),
            ).also { it.openBeforeShowing() },
        )
    }
}

/** The app's window, tray and menus once its stores are open. */
@androidx.compose.runtime.Composable
private fun ApplicationScope.DesktopApplication(
    shell: DesktopShell,
    state: WindowState,
    launch: DesktopLaunch,
) {
    val repository = shell.repository
    val composition = shell.composition
    val snapshot by repository.snapshot.collectAsState()
    val languageTag = snapshot.settings.general.languagePreference
        ?.takeUnless { it.equals("system", ignoreCase = true) }
        ?: Locale.getDefault().toLanguageTag()
    val strings = remember(languageTag) { shinsouStringsFor(languageTag) }
    SideEffect { shell.strings = strings }
    val autoBackupScope = rememberCoroutineScope()
    val autoBackupScheduler = remember(composition, autoBackupScope) {
        ForegroundAutoBackupScheduler(composition.autoBackups, autoBackupScope)
    }
    val trayState = rememberTrayState()
    DesktopBackgroundEffects(shell, launch.platform)
    DesktopTray(shell, trayState, strings)
    val services = remember(composition) {
        DesktopAppServices(
            browse = composition.browse,
            content = composition.content,
            contentFeatures = composition.contentFeatures,
            mangaTranslation = composition.mangaTranslation,
            appUpdates = composition.appUpdates,
            tracking = composition.tracking,
            portableContentBackupV2 = composition.portableContentBackupV2,
            sync = composition.sync,
            shuYueMigration = composition.shuYueMigration,
            closeApplication = shell::requestClose,
            frame = { shell.hostFrame },
            stringsProvider = {
                val preference = repository.snapshot.value.settings.general.languagePreference
                val tag = preference?.takeUnless { it.equals("system", ignoreCase = true) }
                    ?: Locale.getDefault().toLanguageTag()
                shinsouStringsFor(tag)
            },
            shellStateStore = DesktopShellStateStore(DesktopAppDirectories.dataRoot.resolve(SHELL_STATE_FILE)),
        )
    }
    Window(
        onCloseRequest = { shell.closeWindow(trayState) },
        visible = shell.windowVisible,
        title = "Shinsou X",
        icon = shinsouIconPainter(),
        state = state,
        onPreviewKeyEvent = { event -> shell.handleShortcut(launch.platform, event, services) },
    ) {
        SideEffect {
            shell.hostFrame = window
            window.minimumSize = Dimension(900, 600)
        }
        LaunchedEffect(composition) {
            desktopProbeStage("start")
            composition.start()
            desktopProbeStage("started")
            if (launch.verifyDesktopStartup) {
                // The stores were opened and SQLite initialized before this window. Waiting for
                // the next frame proves that the packaged Compose UI can render as well.
                withFrameNanos { }
                desktopProbeStage("frame")
                if (launch.platform == DesktopPlatform.WINDOWS) {
                    verifyWindowsWindowChrome(window)
                }
                desktopProbeStage("chrome")
                writeDesktopProbeMarker(required = true)
                desktopProbeStage("marker")
                shell.closeImmediately()
            }
        }
        LaunchedEffect(services, launch.initialDeepLink) {
            launch.initialDeepLink?.let(services::emitDeepLink)
        }
        LaunchedEffect(services, launch.singleInstance) {
            // The app was launched again: bring the window back and follow its deep link.
            launch.singleInstance?.activations?.collect { arguments ->
                if (shell.closing) return@collect
                shell.showWindow()
                arguments.firstNotNullOfOrNull(DeepLinkParser::parse)?.let(services::emitDeepLink)
            }
        }
        DesktopIntegrationEffect(shell, services, autoBackupScope)
        if (launch.platform.usesNativeMenuBar) DesktopMenuBar(shell, services, strings, launch.platform)
        // Closing takes the screens and the backup schedule away before the services close.
        if (!shell.closing) {
            LaunchedEffect(autoBackupScheduler) { autoBackupScheduler.start() }
            DisposableEffect(composition) {
                onDispose { autoBackupScheduler.stop() }
            }
            App(
                repository = repository,
                appServices = services,
                autoBackupService = composition.autoBackups,
            )
        } else {
            LaunchedEffect(Unit) { shell.uiStopped.complete(Unit) }
        }
    }
}

/**
 * The window of the opened app: whether it is shown, and how it closes. Closing the window while
 * chapters download only hides it; the downloads go on, shown in the menu bar / system tray, and
 * the app quits once they are done.
 */
private class DesktopShell(
    opened: DesktopOpened.Ready,
    private val exitApplication: () -> Unit,
) {
    val repository = opened.repository
    val composition = opened.composition
    private val closed = AtomicBoolean(false)
    private val closePromptOpen = AtomicBoolean(false)
    private var backgroundNoticeShown = false
    var hostFrame by mutableStateOf<Frame?>(null)
    var windowVisible by mutableStateOf(true)

    /** Set once closing began: nothing reopens the window or reaches the services after that. */
    var closing by mutableStateOf(false)
        private set
    var strings: ShinsouStrings = shinsouStringsFor(Locale.getDefault().toLanguageTag())

    /**
     * The app's screens, while they may still run: closing takes them out of the composition, and
     * the services close once they are gone (or after [UI_STOP_TIMEOUT_MS] if the hidden window
     * does not recompose), so nothing on screen reaches a service that is closing.
     */
    val uiStopped = CompletableDeferred<Unit>()

    /**
     * Closing the services runs on its own scope, not the application's: the application ending
     * (even by an exception) cannot cut it short, and `main` waits for it before the process exits.
     */
    private val servicesClosed = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(start = CoroutineStart.LAZY) {
        withTimeoutOrNull(UI_STOP_TIMEOUT_MS) { uiStopped.await() }
        runCatchingCancellable { composition.close { step -> desktopProbeStage("close: $step") } }
            .onFailure { Log.warn("Desktop", it) { "Closing the app services on exit failed" } }
    }

    /**
     * Closes the app services, then the application. The window is hidden first: what it would
     * still let the user do would reach services that are closing.
     */
    fun closeImmediately() {
        if (!closed.compareAndSet(false, true)) return
        closing = true
        windowVisible = false
        servicesClosed.start()
        servicesClosed.invokeOnCompletion {
            SwingUtilities.invokeLater {
                desktopProbeStage("exitApplication")
                exitApplication()
            }
        }
    }

    /**
     * The application ended: the services are closed now if nothing closed them, and a close
     * already under way is waited for, so its last writes reach the disk before the process exits.
     */
    suspend fun closeIfOpen() {
        // The composition is gone: there is no screen left to wait for.
        uiStopped.complete(Unit)
        closed.set(true)
        servicesClosed.join()
    }

    fun requestClose() {
        if (closed.get()) return
        if (!repository.snapshot.value.settings.general.confirmBeforeClosing) {
            closeImmediately()
        } else if (closePromptOpen.compareAndSet(false, true)) {
            val confirmAndClose = {
                try {
                    val choice = JOptionPane.showConfirmDialog(
                        hostFrame,
                        strings.text(StringKey.QUIT_SHINSOU_X_2),
                        strings.text(StringKey.QUIT_SHINSOU_X),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                    )
                    if (choice == JOptionPane.YES_OPTION) closeImmediately()
                } finally {
                    closePromptOpen.set(false)
                }
            }
            if (SwingUtilities.isEventDispatchThread()) confirmAndClose() else SwingUtilities.invokeLater(confirmAndClose)
        }
    }

    fun showWindow() {
        if (closed.get()) return
        windowVisible = true
        hostFrame?.let { frame ->
            if (frame.extendedState and Frame.ICONIFIED != 0) {
                frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
            frame.toFront()
            frame.requestFocus()
        }
        // Optional: a desktop that refuses to raise the window leaves it where it is.
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                Desktop.getDesktop().requestForeground(true)
            }
        }
    }

    fun closeWindow(trayState: TrayState) {
        if (closed.get()) return
        // Before startup has restored the queues, an empty queue proves nothing: hide now and
        // let the auto-quit decide once they are restored.
        if (composition.isStartupComplete() && composition.backgroundDownloads.currentStatus() == null) {
            requestClose()
        } else {
            windowVisible = false
            if (!backgroundNoticeShown && composition.backgroundDownloads.currentStatus() != null) {
                backgroundNoticeShown = true
                trayState.sendNotification(
                    Notification(
                        title = "Shinsou X",
                        message = strings.text(StringKey.DOWNLOADS_CONTINUE_IN_THE_BACKGROUND_SHINSOU_X),
                    ),
                )
            }
        }
    }

    fun handleShortcut(platform: DesktopPlatform, event: KeyEvent, services: DesktopAppServices): Boolean {
        val action = desktopShortcutAction(
            platform = platform,
            key = event.key,
            type = event.type,
            ctrlPressed = event.isCtrlPressed,
            metaPressed = event.isMetaPressed,
            altPressed = event.isAltPressed,
            shiftPressed = event.isShiftPressed,
        ) ?: return false
        when (action) {
            DesktopShortcutAction.OPEN_LIBRARY -> services.openSection(DeepLinkSection.Library)
            DesktopShortcutAction.OPEN_UPDATES -> services.openSection(DeepLinkSection.Updates)
            DesktopShortcutAction.OPEN_HISTORY -> services.openSection(DeepLinkSection.History)
            DesktopShortcutAction.OPEN_BROWSE -> services.openSection(DeepLinkSection.Browse)
            DesktopShortcutAction.OPEN_MORE -> services.openSection(DeepLinkSection.More)
            DesktopShortcutAction.OPEN_SETTINGS -> services.openSettings()
            DesktopShortcutAction.MINIMIZE -> hostFrame?.let { frame ->
                frame.extendedState = frame.extendedState or Frame.ICONIFIED
            }
            DesktopShortcutAction.QUIT -> requestClose()
        }
        return true
    }
}

/** Keeps the computer awake while chapters download, and quits a hidden window once they are done. */
@androidx.compose.runtime.Composable
private fun DesktopBackgroundEffects(shell: DesktopShell, platform: DesktopPlatform) {
    val composition = shell.composition
    val keepAwake = remember { DesktopKeepAwake.forPlatform(platform) }
    // Held only while chapters are queued or downloading.
    LaunchedEffect(composition, keepAwake) {
        composition.backgroundDownloads.status
            .map { it != null }
            .distinctUntilChanged()
            .collect { downloading -> applyKeepAwake(keepAwake, downloading) }
    }
    DisposableEffect(keepAwake) { onDispose { keepAwake.release() } }
    // The window was closed during downloads: quit once they are done (a short grace period
    // covers a queue that briefly empties between two works).
    val windowVisible = shell.windowVisible
    LaunchedEffect(windowVisible) {
        if (windowVisible) return@LaunchedEffect
        while (!composition.isStartupComplete()) {
            composition.awaitStartup()
            delay(100)
        }
        composition.backgroundDownloads.liveStatus.collectLatest { value ->
            if (value == null) {
                delay(3_000)
                // Re-read the queues themselves: work queued in the meantime keeps the app open.
                if (!shell.windowVisible && composition.backgroundDownloads.currentStatus() == null) shell.closeImmediately()
            }
        }
    }
}

/** The menu bar / system tray item, while chapters download or the window is hidden. */
@androidx.compose.runtime.Composable
private fun ApplicationScope.DesktopTray(shell: DesktopShell, trayState: TrayState, strings: ShinsouStrings) {
    val downloadStatus by shell.composition.backgroundDownloads.status.collectAsState()
    if (shell.closing || (downloadStatus == null && shell.windowVisible)) return
    val shown = downloadStatus
    val progress = shown?.let { strings.text(StringKey.DOWNLOADING_X_X, it.done, it.total) }
    Tray(
        icon = shinsouIconPainter(),
        state = trayState,
        tooltip = listOfNotNull("Shinsou X", progress).joinToString(" · "),
        onAction = shell::showWindow,
    ) {
        if (progress != null) Item(progress, enabled = false, onClick = {})
        // One line per title, so several series are told apart.
        val works = shown?.works.orEmpty()
        if (works.isEmpty()) {
            shown?.current?.let { Item(it, enabled = false, onClick = {}) }
        } else {
            works.take(5).forEach { work ->
                val line = listOfNotNull("${work.title}  ${work.done} / ${work.total}", work.chapter)
                    .joinToString(" · ")
                Item(line, enabled = false, onClick = {})
            }
            if (works.size > 5) Item(strings.text(StringKey.X_MORE_TITLES, works.size - 5), enabled = false, onClick = {})
        }
        if (shown != null) Separator()
        Item(strings.text(StringKey.OPEN_SHINSOU_X), onClick = shell::showWindow)
        Item(strings.text(StringKey.QUIT_SHINSOU_X), onClick = shell::requestClose)
    }
}

/** The desktop's own app events: foreground changes, the Dock icon and opened URLs. */
@androidx.compose.runtime.Composable
private fun DesktopIntegrationEffect(shell: DesktopShell, services: DesktopAppServices, scope: CoroutineScope) {
    val composition = shell.composition
    DisposableEffect(services) {
        val listener = object : AppForegroundListener, AppReopenedListener {
            // Clicking the Dock icon while the window is hidden for background downloads.
            override fun appReopened(event: AppReopenedEvent) {
                if (!shell.windowVisible) SwingUtilities.invokeLater(shell::showWindow)
            }

            override fun appRaisedToForeground(event: AppForegroundEvent) {
                if (shell.closing) return
                services.emitForeground()
                scope.launch { composition.onForeground() }
            }

            override fun appMovedToBackground(event: AppForegroundEvent) {
                if (shell.closing) return
                services.emitBackground()
                scope.launch { composition.onBackground() }
            }
        }
        // Feature detection: a desktop without these integrations simply goes without them.
        val desktop = runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        }.getOrNull()
        val registered = desktop != null && runCatching {
            desktop.addAppEventListener(listener)
            true
        }.getOrDefault(false)
        val uriHandlerRegistered = desktop != null &&
            desktop.isSupported(Desktop.Action.APP_OPEN_URI) && runCatching {
                desktop.setOpenURIHandler(
                    OpenURIHandler { event ->
                        if (shell.closing) return@OpenURIHandler
                        DeepLinkParser.parse(event.uri.toString())?.let(services::emitDeepLink)
                    },
                )
                true
            }.getOrDefault(false)
        // The Dock's Quit, Cmd+Q from the app menu and logging out close the app its own way (saving
        // and closing the services) instead of the JVM exiting at once.
        val quitHandlerRegistered = desktop != null &&
            desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER) && runCatching {
                desktop.setQuitHandler { _, response ->
                    response.cancelQuit()
                    SwingUtilities.invokeLater(shell::requestClose)
                }
                true
            }.getOrDefault(false)
        services.emitForeground()
        scope.launch { composition.onForeground() }
        onDispose {
            if (quitHandlerRegistered && desktop != null) runCatching { desktop.setQuitHandler(null) }
            // Unregistering on dispose is best effort: the app is leaving the composition.
            if (registered && desktop != null) runCatching { desktop.removeAppEventListener(listener) }
            if (uriHandlerRegistered && desktop != null) {
                runCatching { desktop.setOpenURIHandler(null) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FrameWindowScope.DesktopMenuBar(
    shell: DesktopShell,
    services: DesktopAppServices,
    strings: ShinsouStrings,
    platform: DesktopPlatform,
) {
    MenuBar {
        Menu(strings.text(StringKey.SHINSOU_X)) {
            Item(strings.text(StringKey.ABOUT_SHINSOU_X), onClick = { services.openSection(DeepLinkSection.More) })
            Separator()
            Item(
                strings.text(StringKey.SETTINGS_2),
                shortcut = platform.primaryShortcut(Key.Comma),
                onClick = services::openSettings,
            )
            Separator()
            Item(
                strings.text(StringKey.QUIT_SHINSOU_X),
                shortcut = platform.primaryShortcut(Key.Q),
                onClick = shell::requestClose,
            )
        }
        Menu(strings.text(StringKey.GO)) {
            navigationItem(strings.library, Key.One, DeepLinkSection.Library, platform, services)
            navigationItem(strings.updates, Key.Two, DeepLinkSection.Updates, platform, services)
            navigationItem(strings.history, Key.Three, DeepLinkSection.History, platform, services)
            navigationItem(strings.browse, Key.Four, DeepLinkSection.Browse, platform, services)
            navigationItem(strings.more, Key.Five, DeepLinkSection.More, platform, services)
        }
        Menu(strings.text(StringKey.WINDOW)) {
            Item(strings.text(StringKey.CENTER_WINDOW), onClick = { window.setLocationRelativeTo(null) })
            Item(
                strings.text(StringKey.MINIMIZE),
                shortcut = platform.primaryShortcut(Key.M),
                onClick = { window.isMinimized = true },
            )
        }
    }
}

/** Holds the keep-awake assertion while chapters download; the platform call may block. */
private suspend fun applyKeepAwake(
    keepAwake: DesktopKeepAwake,
    downloading: Boolean,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    withContext(ioDispatcher) {
        if (downloading) keepAwake.hold("Downloading chapters") else keepAwake.release()
    }
}

private fun verifyWindowsWindowChrome(window: javax.swing.JFrame) {
    check(window.iconImages.any { image -> image.getWidth(null) > 0 && image.getHeight(null) > 0 }) {
        "The Windows window does not have a rendered application icon."
    }
    check(window.jMenuBar == null) {
        "The Windows window unexpectedly contains a native menu bar."
    }
}

/**
 * Hidden packaged-runtime probe used by Windows CI. Initializing AWT with Access Bridge forced on
 * reproduces the otherwise machine-specific startup failure before any application data is read.
 */
private fun verifyDesktopRuntimeAndExit(): Nothing {
    check(ModuleLayer.boot().findModule("jdk.accessibility").isPresent) {
        "The packaged desktop runtime is missing jdk.accessibility."
    }
    // JavaFX's Marlin rasterizer needs sun.misc.Unsafe; without it WebView pages fail to render.
    check(ModuleLayer.boot().findModule("jdk.unsupported").isPresent) {
        "The packaged desktop runtime is missing jdk.unsupported."
    }
    // Every repository and publisher signature goes through libsodium. Load it from the packaged
    // jar, whose install path contains a space, and prove a signature round trip.
    runBlocking { dev.shinsou.kmp.crypto.SodiumPrimitives.initialize() }
    val probeKeys = dev.shinsou.kmp.crypto.SodiumPrimitives.generateEd25519KeyPair()
    val probeMessage = "Shinsou X runtime probe".encodeToByteArray()
    try {
        val probeSignature = dev.shinsou.kmp.crypto.SodiumPrimitives.signEd25519(probeMessage, probeKeys.privateKey)
        check(dev.shinsou.kmp.crypto.SodiumPrimitives.verifyEd25519(probeMessage, probeSignature, probeKeys.publicKey)) {
            "The packaged libsodium did not verify its own Ed25519 signature."
        }
    } finally {
        probeKeys.privateKey.fill(0)
    }
    // ReaderScreen is one of the largest Compose-generated classes in the application. A prior
    // ProGuard output rebuilt an invalid stack-map frame in this class, so the packaged app opened
    // normally but failed with VerifyError only when a chapter entered the reader. Force the JVM
    // to verify the release class during the existing installer/runtime probe as well.
    Class.forName(
        "dev.shinsou.kmp.ui.reader.ReaderScreenKt",
        false,
        Thread.currentThread().contextClassLoader,
    )
    // URL provider metadata must survive packaging too. Merely loading the provider class
    // misses ServiceLoader resource failures that otherwise appear only on first reader use.
    check(java.util.ServiceLoader.load(java.net.spi.URLStreamHandlerProvider::class.java)
        .any { it is dev.shinsou.kmp.reader.protocol.EpubUrlStreamHandlerProvider }) {
        "The packaged EPUB URL protocol provider is unavailable."
    }
    check(java.net.URI.create("shinsou-epub://runtime-probe.invalid/index.xhtml").toURL().protocol == "shinsou-epub")
    check(java.net.URI.create("https://runtime-probe.invalid/").toURL().protocol == "https")
    Toolkit.getDefaultToolkit()
    writeDesktopProbeMarker(required = false)
    println("Shinsou X desktop runtime verification passed.")
    System.out.flush()
    exitProcess(0)
}

/**
 * Startup probe only: records that `main` ran and, if the probe is still running after 60 s,
 * where every thread is (next to the probe marker), so a stalled packaged start can be diagnosed
 * on CI where no debugger can attach.
 */
/** Startup probe only: appends a stage name next to the probe marker (see the watchdog). */
private fun desktopProbeStage(stage: String) {
    val markerValue = System.getenv(DESKTOP_PROBE_MARKER_ENVIRONMENT)?.takeIf(String::isNotBlank) ?: return
    // Test-probe diagnostics only (set by an environment variable); a failed write is harmless.
    runCatching {
        Files.writeString(
            Path.of("$markerValue.stages"), "${System.currentTimeMillis()} $stage\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
        )
    }
}

private fun startDesktopProbeWatchdog() {
    val markerValue = System.getenv(DESKTOP_PROBE_MARKER_ENVIRONMENT)?.takeIf(String::isNotBlank) ?: return
    val dumpPath = Path.of("$markerValue.threads")
    // Test-probe diagnostics only (set by an environment variable); a failed write is harmless.
    runCatching { Files.writeString(dumpPath, "main started\n", StandardCharsets.UTF_8) }
    Thread({
        try {
            Thread.sleep(60_000)
        } catch (_: InterruptedException) {
            // The watchdog is a daemon thread; an interrupt means the process is exiting.
            return@Thread
        }
        val dump = buildString {
            Thread.getAllStackTraces().forEach { (thread, frames) ->
                appendLine("\"${thread.name}\" ${thread.state}")
                frames.forEach { appendLine("    at $it") }
                appendLine()
            }
        }
        runCatching { Files.writeString(dumpPath, dump, StandardCharsets.UTF_8) } // probe diagnostics only
    }, "desktop-probe-watchdog").apply {
        isDaemon = true
        start()
    }
}

private fun writeDesktopProbeMarker(required: Boolean) {
    val markerValue = System.getenv(DESKTOP_PROBE_MARKER_ENVIRONMENT)
    val token = System.getenv(DESKTOP_PROBE_TOKEN_ENVIRONMENT)
    if (!required && markerValue.isNullOrBlank() && token.isNullOrBlank()) return
    check(!markerValue.isNullOrBlank() && !token.isNullOrBlank()) {
        "$DESKTOP_PROBE_MARKER_ENVIRONMENT and $DESKTOP_PROBE_TOKEN_ENVIRONMENT must both be set."
    }
    check(token.length <= 1024 && '\n' !in token && '\r' !in token) {
        "Desktop probe token is invalid."
    }

    val marker = Path.of(markerValue).toAbsolutePath().normalize()
    val parent = checkNotNull(marker.parent) { "Desktop probe marker has no parent directory." }
    Files.createDirectories(parent)
    val temporary = marker.resolveSibling("${marker.fileName}.${UUID.randomUUID()}.tmp")
    try {
        Files.writeString(temporary, token, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                marker,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Atomic moves are optional on this file system; a plain replace is enough.
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.MenuScope.navigationItem(
    title: String,
    key: Key,
    section: DeepLinkSection,
    platform: DesktopPlatform,
    services: DesktopAppServices,
) {
    Item(
        title,
        shortcut = platform.primaryShortcut(key),
        onClick = { services.openSection(section) },
    )
}

/** The shell's state between launches ([DesktopShellStateStore]). */
private const val SHELL_STATE_FILE = "shell-state.txt"
