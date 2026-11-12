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
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.backup.ForegroundAutoBackupScheduler
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.DesktopAppFileSystem
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.network.DesktopAvifDecoder
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.plugin.DesktopBrowserUserAgentProvider
import dev.shinsou.kmp.plugin.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.desktopSandboxRuntimeFactory
import dev.shinsou.kmp.tts.DesktopTextToSpeechEngine
import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource

private const val VERIFY_DESKTOP_RUNTIME_ARGUMENT = "--verify-desktop-runtime"
private const val VERIFY_DESKTOP_STARTUP_ARGUMENT = "--verify-desktop-startup"
private const val DESKTOP_PROBE_MARKER_ENVIRONMENT = "SHINSOU_DESKTOP_PROBE_MARKER"
private const val DESKTOP_PROBE_TOKEN_ENVIRONMENT = "SHINSOU_DESKTOP_PROBE_TOKEN"

fun main(args: Array<String>) {
    val desktopPlatform = DesktopPlatform.current
    configureDesktopSystemProperties(desktopPlatform)
    if (VERIFY_DESKTOP_RUNTIME_ARGUMENT in args) verifyDesktopRuntimeAndExit()
    val verifyDesktopStartup = VERIFY_DESKTOP_STARTUP_ARGUMENT in args
    if (verifyDesktopStartup) startDesktopProbeWatchdog()
    // Packaged URL protocol handlers pass the full URI as an argv entry. Retain it in the same
    // acknowledge-after-consumption flow used by mobile cold starts.
    val initialDeepLink = args.firstNotNullOfOrNull(DeepLinkParser::parse)
    // Windows and Linux start a new process on every launch; hand it to the running one instead
    // (its window may be hidden while downloads continue). Startup probes run on their own.
    val singleInstance: DesktopSingleInstance? = if (desktopPlatform != DesktopPlatform.MAC_OS && !verifyDesktopStartup) {
        // Fails closed: when the lock cannot be established, a second process might already have
        // the database open, so this one must not start.
        val launch = runCatching { DesktopSingleInstance.acquire(DesktopAppDirectories.dataRoot, args.toList()) }
        val warning = when (launch.getOrNull()) {
            is DesktopSingleInstance.Companion.Launch.Primary -> null
            DesktopSingleInstance.Companion.Launch.HandedOff -> exitProcess(0)
            DesktopSingleInstance.Companion.Launch.RunningButUnreachable ->
                "Shinsou X is already running but is not responding. Close it in Task Manager, then open Shinsou X again."
            null -> "Shinsou X could not confirm that no other copy is running, so it did not start. {0}"
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
        (launch.getOrNull() as DesktopSingleInstance.Companion.Launch.Primary).instance
    } else {
        null
    }
    desktopProbeStage("before http client")
    val httpClient = createPlatformHttpClient()
    installConfiguredImageLoader(httpClient) {
        add(DesktopAvifDecoder.Factory())
    }

    desktopProbeStage("before application")
    application {
        desktopProbeStage("application")
        val repository = remember {
            val initial = DesktopPersistence.loadState()?.let { payload ->
                runCatching { AppSnapshotJson.decode(payload) }.getOrNull()
            } ?: AppSnapshot()
            ShinsouRepository(initial, DesktopPersistence::saveState)
        }
        val snapshot by repository.snapshot.collectAsState()
        val languageTag = snapshot.settings.general.languagePreference
            ?.takeUnless { it.equals("system", ignoreCase = true) }
            ?: Locale.getDefault().toLanguageTag()
        val strings = remember(languageTag) { shinsouStringsFor(languageTag) }
        val contentInfrastructure = remember { DesktopContentInfrastructure() }
        val sandboxScope = rememberCoroutineScope()
        desktopProbeStage("before composition")
        val composition = remember {
            ShinsouComposition(
                repository = repository,
                httpClient = httpClient,
                pluginKeyValueStore = DesktopPluginKeyValueStore(),
                fileSystem = DesktopAppFileSystem(),
                // ADR-0002 §10.1: every package runs in the OS-confined
                // shinsou-sandbox helper.
                runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(desktopSandboxRuntimeFactory(sandboxScope)),
                contentInfrastructure = contentInfrastructure,
                platformTextToSpeechEngine = DesktopTextToSpeechEngine(),
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
                    appVersion = "1.0.1-beta.21",
                ),
            )
        }
        val autoBackupScope = rememberCoroutineScope()
        val autoBackupScheduler = remember(composition, autoBackupScope) {
            ForegroundAutoBackupScheduler(composition.autoBackups, autoBackupScope)
        }
        val closed = remember { AtomicBoolean(false) }
        val closePromptOpen = remember { AtomicBoolean(false) }
        var hostFrame by remember { mutableStateOf<Frame?>(null) }
        // Closing the window while chapters are downloading only hides it; the downloads go on,
        // shown in the menu bar / system tray, and the app quits once they are done.
        var windowVisible by remember { mutableStateOf(true) }
        var backgroundNoticeShown by remember { mutableStateOf(false) }
        val trayState = rememberTrayState()
        val downloadStatus by composition.backgroundDownloads.status.collectAsState()
        val keepAwake = remember { DesktopKeepAwake.forPlatform(desktopPlatform) }
        lateinit var services: DesktopAppServices

        fun closeImmediately() {
            if (closed.compareAndSet(false, true)) runCatching {
                runBlocking { composition.close { step -> desktopProbeStage("close: $step") } }
            }
            desktopProbeStage("exitApplication")
            exitApplication()
        }

        fun requestClose() {
            if (closed.get()) return
            if (!repository.snapshot.value.settings.general.confirmBeforeClosing) {
                closeImmediately()
                return
            }
            if (!closePromptOpen.compareAndSet(false, true)) return
            val confirmAndClose = {
                try {
                    val choice = JOptionPane.showConfirmDialog(
                        hostFrame,
                        strings.text("Quit Shinsou X?") ,
                        strings.text("Quit Shinsou X"),
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

        fun showWindow() {
            windowVisible = true
            hostFrame?.let { frame ->
                if (frame.extendedState and Frame.ICONIFIED != 0) {
                    frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                }
                frame.toFront()
                frame.requestFocus()
            }
            runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    Desktop.getDesktop().requestForeground(true)
                }
            }
        }

        fun closeWindow() {
            if (closed.get()) return
            // Before startup has restored the queues, an empty queue proves nothing: hide now and
            // let the auto-quit below decide once they are restored.
            if (composition.isStartupComplete() && composition.backgroundDownloads.currentStatus() == null) {
                requestClose()
                return
            }
            windowVisible = false
            if (!backgroundNoticeShown && composition.backgroundDownloads.currentStatus() != null) {
                backgroundNoticeShown = true
                trayState.sendNotification(
                    Notification(
                        title = "Shinsou X",
                        message = strings.text("Downloads continue in the background. Shinsou X quits when they finish."),
                    ),
                )
            }
        }

        // Held only while chapters are queued or downloading.
        LaunchedEffect(composition, keepAwake) {
            composition.backgroundDownloads.status
                .map { it != null }
                .distinctUntilChanged()
                .collect { downloading ->
                    withContext(Dispatchers.IO) {
                        if (downloading) keepAwake.hold("Downloading chapters") else keepAwake.release()
                    }
                }
        }
        DisposableEffect(keepAwake) { onDispose { keepAwake.release() } }

        // The window was closed during downloads: quit once they are done (a short grace period
        // covers a queue that briefly empties between two works).
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
                    if (!windowVisible && composition.backgroundDownloads.currentStatus() == null) closeImmediately()
                }
            }
        }

        if (downloadStatus != null || !windowVisible) {
            val shown = downloadStatus
            val progress = shown?.let { strings.text("Downloading {0} / {1}", it.done, it.total) }
            Tray(
                icon = painterResource(Res.drawable.shinsou_icon),
                state = trayState,
                tooltip = listOfNotNull("Shinsou X", progress).joinToString(" · "),
                onAction = ::showWindow,
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
                    if (works.size > 5) Item(strings.text("{0} more titles", works.size - 5), enabled = false, onClick = {})
                }
                if (shown != null) Separator()
                Item(strings.text("Open Shinsou X"), onClick = ::showWindow)
                Item(strings.text("Quit Shinsou X"), onClick = ::requestClose)
            }
        }

        services = remember(composition) {
            DesktopAppServices(
                browse = composition.browse,
                content = composition.content,
                contentFeatures = composition.contentFeatures,
                tracking = composition.tracking,
                portableContentBackupV2 = composition.portableContentBackupV2,
                sync = composition.sync,
                shuYueMigration = composition.shuYueMigration,
                closeApplication = ::requestClose,
                frame = { hostFrame },
                stringsProvider = {
                    val preference = repository.snapshot.value.settings.general.languagePreference
                    val tag = preference?.takeUnless { it.equals("system", ignoreCase = true) }
                        ?: Locale.getDefault().toLanguageTag()
                    shinsouStringsFor(tag)
                },
            )
        }

        val state = rememberWindowState(width = 1280.dp, height = 820.dp)
        val applicationIcon = painterResource(Res.drawable.shinsou_icon)
        Window(
            onCloseRequest = ::closeWindow,
            visible = windowVisible,
            title = "Shinsou X",
            icon = applicationIcon,
            state = state,
            onPreviewKeyEvent = { event ->
                val action = desktopShortcutAction(
                    platform = desktopPlatform,
                    key = event.key,
                    type = event.type,
                    ctrlPressed = event.isCtrlPressed,
                    metaPressed = event.isMetaPressed,
                    altPressed = event.isAltPressed,
                    shiftPressed = event.isShiftPressed,
                ) ?: return@Window false

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
                true
            },
        ) {
            SideEffect {
                hostFrame = window
                window.minimumSize = Dimension(900, 600)
            }
            LaunchedEffect(composition) {
                desktopProbeStage("start")
                composition.start()
                desktopProbeStage("started")
                if (verifyDesktopStartup) {
                    // The constructor above has opened and initialized SQLite. Waiting for the
                    // next frame proves that the packaged Compose UI can render as well.
                    withFrameNanos { }
                    desktopProbeStage("frame")
                    if (desktopPlatform == DesktopPlatform.WINDOWS) {
                        verifyWindowsWindowChrome(window)
                    }
                    desktopProbeStage("chrome")
                    writeDesktopProbeMarker(required = true)
                    desktopProbeStage("marker")
                    closeImmediately()
                }
            }
            LaunchedEffect(services, initialDeepLink) {
                initialDeepLink?.let(services::emitDeepLink)
            }
            LaunchedEffect(services, singleInstance) {
                // The app was launched again: bring the window back and follow its deep link.
                singleInstance?.activations?.collect { arguments ->
                    showWindow()
                    arguments.firstNotNullOfOrNull(DeepLinkParser::parse)?.let(services::emitDeepLink)
                }
            }
            LaunchedEffect(autoBackupScheduler) { autoBackupScheduler.start() }
            DisposableEffect(composition) {
                onDispose {
                    autoBackupScheduler.stop()
                    if (closed.compareAndSet(false, true)) runCatching { runBlocking { composition.close() } }
                }
            }
            DisposableEffect(services) {
                val listener = object : AppForegroundListener, AppReopenedListener {
                    // Clicking the Dock icon while the window is hidden for background downloads.
                    override fun appReopened(event: AppReopenedEvent) {
                        if (!windowVisible) SwingUtilities.invokeLater(::showWindow)
                    }

                    override fun appRaisedToForeground(event: AppForegroundEvent) {
                        services.emitForeground()
                        autoBackupScope.launch { composition.onForeground() }
                    }

                    override fun appMovedToBackground(event: AppForegroundEvent) {
                        services.emitBackground()
                        autoBackupScope.launch { composition.onBackground() }
                    }
                }
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
                                DeepLinkParser.parse(event.uri.toString())?.let(services::emitDeepLink)
                            },
                        )
                        true
                    }.getOrDefault(false)
                services.emitForeground()
                autoBackupScope.launch { composition.onForeground() }
                onDispose {
                    if (registered && desktop != null) runCatching { desktop.removeAppEventListener(listener) }
                    if (uriHandlerRegistered && desktop != null) {
                        runCatching { desktop.setOpenURIHandler(null) }
                    }
                }
            }

            if (desktopPlatform.usesNativeMenuBar) {
                MenuBar {
                    Menu(strings.text("Shinsou X")) {
                        Item(strings.text("About Shinsou X"), onClick = { services.openSection(DeepLinkSection.More) })
                        Separator()
                        Item(
                            strings.text("Settings…"),
                            shortcut = desktopPlatform.primaryShortcut(Key.Comma),
                            onClick = services::openSettings,
                        )
                        Separator()
                        Item(
                            strings.text("Quit Shinsou X"),
                            shortcut = desktopPlatform.primaryShortcut(Key.Q),
                            onClick = ::requestClose,
                        )
                    }
                    Menu(strings.text("Go")) {
                        navigationItem(strings.library, Key.One, DeepLinkSection.Library, desktopPlatform, services)
                        navigationItem(strings.updates, Key.Two, DeepLinkSection.Updates, desktopPlatform, services)
                        navigationItem(strings.history, Key.Three, DeepLinkSection.History, desktopPlatform, services)
                        navigationItem(strings.browse, Key.Four, DeepLinkSection.Browse, desktopPlatform, services)
                        navigationItem(strings.more, Key.Five, DeepLinkSection.More, desktopPlatform, services)
                    }
                    Menu(strings.text("Window")) {
                        Item(strings.text("Center window"), onClick = { window.setLocationRelativeTo(null) })
                        Item(
                            strings.text("Minimize"),
                            shortcut = desktopPlatform.primaryShortcut(Key.M),
                            onClick = { window.isMinimized = true },
                        )
                    }
                }
            }

            App(
                repository = repository,
                appServices = services,
                autoBackupService = composition.autoBackups,
            )
        }
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
        "dev.shinsou.kmp.ui.screens.ReaderScreenKt",
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
    runCatching { Files.writeString(dumpPath, "main started\n", StandardCharsets.UTF_8) }
    Thread({
        try {
            Thread.sleep(60_000)
        } catch (_: InterruptedException) {
            return@Thread
        }
        val dump = buildString {
            Thread.getAllStackTraces().forEach { (thread, frames) ->
                appendLine("\"${thread.name}\" ${thread.state}")
                frames.forEach { appendLine("    at $it") }
                appendLine()
            }
        }
        runCatching { Files.writeString(dumpPath, dump, StandardCharsets.UTF_8) }
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
