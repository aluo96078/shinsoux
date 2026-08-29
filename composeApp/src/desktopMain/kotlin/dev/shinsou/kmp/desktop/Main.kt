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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.backup.ForegroundAutoBackupScheduler
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.DesktopAppFileSystem
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.UnavailableSnapshotSyncTransport
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
import java.awt.desktop.OpenURIHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val VERIFY_DESKTOP_RUNTIME_ARGUMENT = "--verify-desktop-runtime"

fun main(args: Array<String>) {
    val desktopPlatform = DesktopPlatform.current
    configureDesktopSystemProperties(desktopPlatform)
    if (VERIFY_DESKTOP_RUNTIME_ARGUMENT in args) verifyDesktopRuntimeAndExit()
    // Packaged URL protocol handlers pass the full URI as an argv entry. Retain it in the same
    // acknowledge-after-consumption flow used by mobile cold starts.
    val initialDeepLink = args.firstNotNullOfOrNull(DeepLinkParser::parse)

    application {
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
        val syncInfrastructure = remember { DesktopSyncInfrastructure() }
        val composition = remember {
            ShinsouComposition(
                repository = repository,
                httpClient = createPlatformHttpClient(),
                pluginKeyValueStore = DesktopPluginKeyValueStore(),
                fileSystem = DesktopAppFileSystem(),
                runtimeFactory = RhinoScriptPluginRuntimeFactory(),
                syncInfrastructure = syncInfrastructure,
                platformTextToSpeechEngine = DesktopTextToSpeechEngine(),
                shuYueMigrationSecretStore = DesktopShuYueMigrationSecretStore(),
            )
        }
        val syncRuntime = requireNotNull(composition.syncRuntime)
        val readerProgressReporter by syncRuntime.readerProgressReporter.collectAsState()
        val syncBoundaryReady by composition.syncBoundaryReady.collectAsState()
        val autoBackupScope = rememberCoroutineScope()
        val autoBackupScheduler = remember(composition, autoBackupScope) {
            ForegroundAutoBackupScheduler(composition.autoBackups, autoBackupScope)
        }
        val closed = remember { AtomicBoolean(false) }
        val closePromptOpen = remember { AtomicBoolean(false) }
        var hostFrame by remember { mutableStateOf<Frame?>(null) }
        lateinit var services: DesktopAppServices

        fun closeImmediately() {
            if (closed.compareAndSet(false, true)) runCatching { runBlocking { composition.close() } }
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

        services = remember(composition) {
            DesktopAppServices(
                browse = composition.browse,
                content = composition.content,
                contentFeatures = composition.contentFeatures,
                tracking = composition.tracking,
                cloudflareSync = composition.cloudflareSync,
                syncAwareSnapshotRestore = composition.syncAwareSnapshotRestore,
                portableContentBackupV2 = composition.portableContentBackupV2,
                shuYueMigration = composition.shuYueMigration,
                snapshotSync = SnapshotSyncController(
                    repository = repository,
                    transport = UnavailableSnapshotSyncTransport(
                        "iCloud Drive snapshot sync is available only on iOS.",
                    ),
                    deviceId = "desktop",
                    deviceIdProvider = composition::installationDeviceId,
                    writerAllowed = composition::isLegacySnapshotWriterAllowed,
                ),
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
        Window(
            onCloseRequest = ::requestClose,
            title = "Shinsou X",
            state = state,
        ) {
            SideEffect {
                hostFrame = window
                window.minimumSize = Dimension(900, 600)
            }
            LaunchedEffect(composition) { composition.start() }
            LaunchedEffect(services, initialDeepLink) {
                initialDeepLink?.let(services::emitDeepLink)
            }
            LaunchedEffect(autoBackupScheduler) { autoBackupScheduler.start() }
            DisposableEffect(composition) {
                onDispose {
                    autoBackupScheduler.stop()
                    if (closed.compareAndSet(false, true)) runCatching { runBlocking { composition.close() } }
                }
            }
            DisposableEffect(services) {
                val listener = object : AppForegroundListener {
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

            App(
                repository = repository,
                appServices = services,
                autoBackupService = composition.autoBackups,
                readerProgressReporter = readerProgressReporter,
                interactionReady = syncBoundaryReady,
            )
        }
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
    Toolkit.getDefaultToolkit()
    println("Shinsou X desktop runtime verification passed.")
    System.out.flush()
    exitProcess(0)
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
