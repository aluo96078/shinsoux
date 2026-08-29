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
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.shuyue.KeyValueShuYueReviewedStoreV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueArtifactIdentityV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionPermissionV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueQuarantinedScriptV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewStatusV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueScriptProvenanceV2
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
import kotlinx.coroutines.launch
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
        val applicationIcon = painterResource(Res.drawable.shinsou_icon)
        Window(
            onCloseRequest = ::requestClose,
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
                composition.start()
                if (verifyDesktopStartup) {
                    // The constructor above has opened and initialized SQLite. Waiting for the
                    // next frame proves that the packaged Compose UI can render as well.
                    withFrameNanos { }
                    if (desktopPlatform == DesktopPlatform.WINDOWS) {
                        verifyWindowsWindowChrome(window)
                    }
                    writeDesktopProbeMarker(required = true)
                    closeImmediately()
                }
            }
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
                readerProgressReporter = readerProgressReporter,
                interactionReady = syncBoundaryReady,
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
    verifyPackagedShuYueQuarantineRoundTrip()
    Toolkit.getDefaultToolkit()
    writeDesktopProbeMarker(required = false)
    println("Shinsou X desktop runtime verification passed.")
    System.out.flush()
    exitProcess(0)
}

/** Exercises durable security state from the ProGuard-processed release JAR used by installers. */
private fun verifyPackagedShuYueQuarantineRoundTrip() = runBlocking {
    val bytes = "packaged ShuYue quarantine probe".encodeToByteArray()
    val identity = ShuYueArtifactIdentityV2(
        packageId = "dev.shinsou.runtime-probe",
        version = "1.0.0",
        versionCode = 1,
        sha256 = Sha256.hex(bytes),
    )
    val record = ShuYueQuarantinedScriptV2(
        quarantineId = "runtime-probe-${identity.sha256}",
        identity = identity,
        sourceIds = listOf("runtime-probe-source"),
        bytes = bytes,
        provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
        stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
    )
    val permissions = ShuYueExecutionPermissionV2.entries.toSet()
    val backing = InMemoryPluginKeyValueStore()
    KeyValueShuYueReviewedStoreV2(backing).also { store ->
        store.put(record)
        store.approve(identity, permissions)
    }

    KeyValueShuYueReviewedStoreV2(backing).also { reopened ->
        val restored = checkNotNull(reopened.get(record.quarantineId)) {
            "Packaged ShuYue quarantine disappeared after durable round-trip."
        }
        check(restored.identity == identity && restored.copyBytes().contentEquals(bytes)) {
            "Packaged ShuYue quarantine changed after durable round-trip."
        }
        check(reopened.grantedPermissions(identity) == permissions) {
            "Packaged ShuYue permissions changed after durable round-trip."
        }
    }

    // These calls specifically require the synthetic values() method that release shrinking used
    // to remove. Keep them in the packaged probe even though durable parsing no longer relies on it.
    check(ShuYueScriptProvenanceV2.valueOf("LEGACY_BACKUP") == ShuYueScriptProvenanceV2.LEGACY_BACKUP)
    check(ShuYueReviewStatusV2.valueOf("REVIEWED") == ShuYueReviewStatusV2.REVIEWED)
    check(ShuYueExecutionPermissionV2.valueOf("NETWORK") == ShuYueExecutionPermissionV2.NETWORK)
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
