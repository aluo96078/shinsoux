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
import androidx.compose.ui.input.key.KeyShortcut
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
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.UnavailableSnapshotSyncTransport
import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.desktop.AppForegroundEvent
import java.awt.desktop.AppForegroundListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking

fun main() {
    System.setProperty("apple.awt.application.name", "Shinsou X")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.appearance", "system")

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
        val composition = remember {
            ShinsouComposition(
                repository = repository,
                httpClient = createPlatformHttpClient(),
                pluginKeyValueStore = DesktopPluginKeyValueStore(),
                fileSystem = DesktopAppFileSystem(),
                runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            )
        }
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
                tracking = composition.tracking,
                snapshotSync = SnapshotSyncController(
                    repository = repository,
                    transport = UnavailableSnapshotSyncTransport(
                        "iCloud Drive snapshot sync is available only on iOS.",
                    ),
                    deviceId = "desktop",
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
            LaunchedEffect(autoBackupScheduler) { autoBackupScheduler.start() }
            DisposableEffect(composition) {
                onDispose {
                    autoBackupScheduler.stop()
                    if (closed.compareAndSet(false, true)) runCatching { runBlocking { composition.close() } }
                }
            }
            DisposableEffect(services) {
                val listener = object : AppForegroundListener {
                    override fun appRaisedToForeground(event: AppForegroundEvent) = services.emitForeground()

                    override fun appMovedToBackground(event: AppForegroundEvent) = services.emitBackground()
                }
                val desktop = runCatching {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                }.getOrNull()
                val registered = desktop != null && runCatching {
                    desktop.addAppEventListener(listener)
                    true
                }.getOrDefault(false)
                services.emitForeground()
                onDispose {
                    if (registered && desktop != null) runCatching { desktop.removeAppEventListener(listener) }
                }
            }

            MenuBar {
                Menu(strings.text("Shinsou X")) {
                    Item(strings.text("About Shinsou X"), onClick = { services.openSection(DeepLinkSection.More) })
                    Separator()
                    Item(
                        strings.text("Settings…"),
                        shortcut = KeyShortcut(Key.Comma, meta = true),
                        onClick = services::openSettings,
                    )
                    Separator()
                    Item(
                        strings.text("Quit Shinsou X"),
                        shortcut = KeyShortcut(Key.Q, meta = true),
                        onClick = ::requestClose,
                    )
                }
                Menu(strings.text("Go")) {
                    navigationItem(strings.library, Key.One, DeepLinkSection.Library, services)
                    navigationItem(strings.updates, Key.Two, DeepLinkSection.Updates, services)
                    navigationItem(strings.history, Key.Three, DeepLinkSection.History, services)
                    navigationItem(strings.browse, Key.Four, DeepLinkSection.Browse, services)
                    navigationItem(strings.more, Key.Five, DeepLinkSection.More, services)
                }
                Menu(strings.text("Window")) {
                    Item(strings.text("Center window"), onClick = { window.setLocationRelativeTo(null) })
                    Item(strings.text("Minimize"), shortcut = KeyShortcut(Key.M, meta = true), onClick = { window.isMinimized = true })
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

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.MenuScope.navigationItem(
    title: String,
    key: Key,
    section: DeepLinkSection,
    services: DesktopAppServices,
) {
    Item(
        title,
        shortcut = KeyShortcut(key, meta = true),
        onClick = { services.openSection(section) },
    )
}
