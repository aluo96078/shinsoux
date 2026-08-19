package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.ImportedDocumentReadException
import dev.shinsou.kmp.ui.AppLifecycleState
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.SecurityFeatureCapability
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.readBoundedImportedBytes
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

internal class DesktopAppServices(
    override val browse: BrowseCallbacks = BrowseCallbacks.None,
    override val content: ContentCallbacks = ContentCallbacks.None,
    override val tracking: TrackingCoordinator? = null,
    override val snapshotSync: SnapshotSyncController? = null,
    private val closeApplication: () -> Unit,
    private val frame: () -> Frame? = { null },
    private val stringsProvider: () -> ShinsouStrings = { ShinsouStrings() },
    private val platform: DesktopPlatform = DesktopPlatform.current,
) : ShinsouAppServices {
    private val pendingDeepLink = MutableStateFlow<ShinsouDeepLink?>(null)
    private val lifecycleState = MutableStateFlow(AppLifecycleState.FOREGROUND)

    override val deepLinks: Flow<ShinsouDeepLink> = pendingDeepLink.filterNotNull()
    override val appLifecycle: StateFlow<AppLifecycleState> = lifecycleState
    override val prefersDesktopChrome: Boolean = true
    override val securityCapabilities: PlatformSecurityCapabilities = PlatformSecurityCapabilities(
        appLock = SecurityFeatureCapability.unavailable(
            "Device authentication is unavailable on this platform.",
        ),
        secureScreen = SecurityFeatureCapability.unavailable(
            "Secure-screen protection is unavailable on this platform.",
        ),
    )

    fun emitForeground() {
        lifecycleState.value = AppLifecycleState.FOREGROUND
    }

    fun emitBackground() {
        lifecycleState.value = AppLifecycleState.BACKGROUND
    }

    fun openSection(section: DeepLinkSection) {
        pendingDeepLink.value = ShinsouDeepLink.OpenSection(section)
    }

    fun openSettings() {
        pendingDeepLink.value = ShinsouDeepLink.OpenSettings
    }

    override fun deepLinkHandled(link: ShinsouDeepLink) {
        if (pendingDeepLink.value == link) pendingDeepLink.value = null
    }

    override fun openExternalUrl(url: String) {
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching
            val desktop = Desktop.getDesktop()
            val uri = URI(url)
            if (uri.scheme.equals("mailto", ignoreCase = true) && desktop.isSupported(Desktop.Action.MAIL)) {
                desktop.mail(uri)
            } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri)
            }
        }
    }

    override fun shareText(title: String, text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override suspend fun exportDocument(suggestedName: String, contents: String): Boolean {
        val selected = chooseFile(
            title = stringsProvider().text("Export {0}", suggestedName),
            mode = FileDialog.SAVE,
            suggestedName = suggestedName,
        ) ?: return false
        return runCatching {
            selected.parent?.let(Files::createDirectories)
            Files.writeString(selected, contents, StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    override suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): ImportedDocument? {
        val selected = chooseFile(
            title = stringsProvider().text("Import Shinsou X data"),
            mode = FileDialog.LOAD,
            acceptedExtensions = acceptedExtensions,
        ) ?: return null
        return withContext(Dispatchers.IO) { readImportedDocument(selected, limits) }
    }

    override suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): List<ImportedDocument> {
        val selected = chooseFiles(stringsProvider().text("Choose local manga"), acceptedExtensions)
        return withContext(Dispatchers.IO) {
            var acceptedBytes = 0L
            selected.map { path ->
                readImportedDocument(path, limits, acceptedBytes).also { acceptedBytes += it.contents.size }
            }
        }
    }

    override suspend fun authenticate(reason: String): Boolean = false

    override fun requestApplicationClose() = closeApplication()

    private fun readImportedDocument(
        path: Path,
        limits: ImportedDocumentLimits,
        previouslyAcceptedBytes: Long = 0,
    ): ImportedDocument {
        val name = path.fileName?.toString().orEmpty().ifBlank { "document" }
        try {
            if (!Files.isRegularFile(path)) {
                throw ImportedDocumentReadException("“$name” is not a readable regular file.")
            }
            val declaredSize = Files.size(path)
            return Files.newInputStream(path).use { input ->
                ImportedDocument(
                    name = name,
                    contents = readBoundedImportedBytes(
                        name = name,
                        declaredSize = declaredSize,
                        limits = limits,
                        previouslyAcceptedBytes = previouslyAcceptedBytes,
                        read = input::read,
                    ),
                )
            }
        } catch (error: ImportedDocumentReadException) {
            throw error
        } catch (error: Throwable) {
            throw ImportedDocumentReadException("Unable to read “$name”.", error)
        }
    }

    private suspend fun chooseFile(
        title: String,
        mode: Int,
        suggestedName: String? = null,
        acceptedExtensions: Set<String> = emptySet(),
    ): Path? = chooseFiles(title, acceptedExtensions, mode, suggestedName, multiple = false).firstOrNull()

    private suspend fun chooseFiles(
        title: String,
        acceptedExtensions: Set<String>,
        mode: Int = FileDialog.LOAD,
        suggestedName: String? = null,
        multiple: Boolean = true,
    ): List<Path> {
        val result = CompletableDeferred<List<Path>>()
        val normalizedExtensions = normalizeAcceptedExtensions(acceptedExtensions)
        SwingUtilities.invokeLater {
            val dialog = FileDialog(frame(), title, mode).apply {
                isMultipleMode = multiple
                file = suggestedName
                // Windows' native FileDialog does not honor FilenameFilter. Keep it as a
                // convenience on supported platforms, then enforce the same rule after the
                // native picker returns on every operating system.
                if (normalizedExtensions.isNotEmpty() && platform != DesktopPlatform.WINDOWS) {
                    filenameFilter = java.io.FilenameFilter { _, name ->
                        extensionOf(name) in normalizedExtensions
                    }
                }
                isVisible = true
            }
            val files = if (dialog.files.isNotEmpty()) {
                dialog.files.map { it.toPath() }
            } else {
                dialog.file?.let { fileName ->
                    listOf(dialog.directory?.let { Path.of(it, fileName) } ?: Path.of(fileName))
                }.orEmpty()
            }
            dialog.dispose()
            result.complete(filterAcceptedFileSelections(files, normalizedExtensions))
        }
        return result.await()
    }
}

internal object DesktopPersistence {
    private val directory: Path by lazy { DesktopAppDirectories.dataRoot }
    private val stateFile: Path get() = directory.resolve("shinsou-state.json")

    fun loadState(): String? = runCatching {
        if (Files.exists(stateFile)) Files.readString(stateFile, StandardCharsets.UTF_8) else null
    }.getOrNull()

    fun saveState(payload: String) {
        Files.createDirectories(directory)
        val temporary = directory.resolve("shinsou-state.json.tmp")
        Files.writeString(temporary, payload, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun filterAcceptedFileSelections(
    files: List<Path>,
    acceptedExtensions: Set<String>,
): List<Path> {
    val normalizedExtensions = normalizeAcceptedExtensions(acceptedExtensions)
    if (normalizedExtensions.isEmpty()) return files
    return files.filter { extensionOf(it.fileName?.toString().orEmpty()) in normalizedExtensions }
}

private fun normalizeAcceptedExtensions(extensions: Set<String>): Set<String> = extensions
    .mapNotNull { extension ->
        extension.trim().trimStart('.').takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
    }
    .toSet()

private fun extensionOf(fileName: String): String = fileName
    .substringAfterLast('.', missingDelimiterValue = "")
    .lowercase(Locale.ROOT)
