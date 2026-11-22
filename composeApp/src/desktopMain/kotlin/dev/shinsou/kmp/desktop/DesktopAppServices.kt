package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.BEFORE_HOT_STATE_SUFFIX
import dev.shinsou.kmp.data.SnapshotFile
import dev.shinsou.kmp.data.WHOLE_JSON_SWITCH_FILE
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.ui.ShellStateStore
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.plugin.BrowseCallbacks
import dev.shinsou.kmp.ui.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.ByteArrayBinaryDocumentExportSource
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentLimits
import dev.shinsou.kmp.contract.ImportedDocumentReadException
import dev.shinsou.kmp.contract.ImportedDocumentSource
import dev.shinsou.kmp.ui.AppLifecycleState
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.SecurityFeatureCapability
import dev.shinsou.kmp.ui.RetainedDeepLinkQueue
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.contract.readBoundedImportedBytes
import dev.shinsou.kmp.contract.requireImportedDocumentSize
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.writeCheckedTo
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.files.DesktopAppDirectories
import dev.shinsou.kmp.files.DesktopPlatform

internal class DesktopAppServices(
    override val browse: BrowseCallbacks = BrowseCallbacks.None,
    override val content: ContentCallbacks = ContentCallbacks.None,
    override val contentFeatures: ContentFeatures? = null,
    override val mangaTranslation: dev.shinsou.kmp.manga.MangaTranslation? = null,
    override val appUpdates: dev.shinsou.kmp.update.AppUpdateChecker? = null,
    override val tracking: TrackingCoordinator? = null,
    override val portableContentBackupV2: PortableContentBackupV2UiController? = null,
    override val sync: SyncV3Service? = null,
    override val shuYueMigration: ShuYueMigrationUiController? = null,
    private val closeApplication: () -> Unit,
    private val frame: () -> Frame? = { null },
    private val stringsProvider: () -> ShinsouStrings = { shinsouStringsFor("en") },
    private val platform: DesktopPlatform = DesktopPlatform.current,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val shellStateStore: ShellStateStore? = null,
) : ShinsouAppServices {
    private val pendingDeepLinks = RetainedDeepLinkQueue()
    private val lifecycleState = MutableStateFlow(AppLifecycleState.FOREGROUND)

    override val deepLinks: Flow<ShinsouDeepLink> = pendingDeepLinks.events
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
        pendingDeepLinks.tryEnqueue(ShinsouDeepLink.OpenSection(section))
    }

    fun openSettings() {
        pendingDeepLinks.tryEnqueue(ShinsouDeepLink.OpenSettings)
    }

    fun emitDeepLink(link: ShinsouDeepLink): Boolean = pendingDeepLinks.tryEnqueue(link)

    override fun deepLinkHandled(link: ShinsouDeepLink) {
        pendingDeepLinks.handled(link)
    }

    override fun openExternalUrl(url: String) {
        tryOpenExternalUrl(url)
    }

    // A desktop that cannot open the link is an expected outcome; the caller reports false.
    override fun tryOpenExternalUrl(url: String): Boolean = runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            val uri = URI(url)
            if (uri.scheme.equals("mailto", ignoreCase = true) && desktop.isSupported(Desktop.Action.MAIL)) {
                desktop.mail(uri)
            } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri)
            } else {
                return@runCatching false
            }
            true
        }.getOrDefault(false)

    override fun shareText(title: String, text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun copyText(label: String, text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.onFailure { Log.warn("Desktop", it) { "Copying text to the clipboard failed" } }.getOrDefault(false)

    // A clipboard holding no text (or busy elsewhere) reads as empty; that is not a failure.
    override suspend fun readClipboardText(): String? = runCatchingCancellable {
        Toolkit.getDefaultToolkit().systemClipboard
            .getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override suspend fun exportDocument(suggestedName: String, contents: String): Boolean {
        val selected = chooseFile(
            title = stringsProvider().text(StringKey.EXPORT_X, suggestedName),
            mode = FileDialog.SAVE,
            suggestedName = suggestedName,
        ) ?: return false
        return runCatchingCancellable {
            selected.parent?.let(Files::createDirectories)
            Files.writeString(selected, contents, StandardCharsets.UTF_8)
            true
        }.onFailure { Log.warn("Desktop", it) { "Exporting a document failed" } }.getOrDefault(false)
    }

    override suspend fun exportBinaryDocument(suggestedName: String, contents: ByteArray): Boolean {
        return exportBinaryDocument(
            suggestedName,
            ByteArrayBinaryDocumentExportSource(contents),
        )
    }

    override suspend fun exportBinaryDocument(
        suggestedName: String,
        source: BinaryDocumentExportSource,
    ): Boolean {
        val selected = chooseFile(
            title = stringsProvider().text(StringKey.EXPORT_X, suggestedName),
            mode = FileDialog.SAVE,
            suggestedName = suggestedName,
        ) ?: return false
        return withContext(ioDispatcher) {
            writeBinaryDocument(selected, source)
        }
    }

    override suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): ImportedDocument? {
        val selected = chooseFile(
            title = stringsProvider().text(StringKey.IMPORT_SHINSOU_X_DATA),
            mode = FileDialog.LOAD,
            acceptedExtensions = acceptedExtensions,
        ) ?: return null
        return withContext(ioDispatcher) { readImportedDocument(selected, limits) }
    }

    override suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): List<ImportedDocument> {
        val selected = chooseFiles(stringsProvider().text(StringKey.CHOOSE_LOCAL_MANGA), acceptedExtensions)
        return withContext(ioDispatcher) {
            var acceptedBytes = 0L
            selected.map { path ->
                readImportedDocument(path, limits, acceptedBytes).also { acceptedBytes += it.byteSize }
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
            if (limits.prefersRandomAccess(name)) {
                requireImportedDocumentSize(name, declaredSize, limits, previouslyAcceptedBytes)
                return ImportedDocument(name, DesktopPathImportedDocumentSource(path, declaredSize))
            }
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

    private class DesktopPathImportedDocumentSource(
        private val path: Path,
        override val byteSize: Long,
    ) : ImportedDocumentSource {
        override fun read(offset: Long, byteCount: Int): ByteArray = synchronized(this) {
            require(offset in 0..byteSize && byteCount >= 0 &&
                byteCount.toLong() <= byteSize - offset) { "Imported document read is out of bounds" }
            if (!Files.isRegularFile(path) || Files.size(path) != byteSize) {
                throw ImportedDocumentReadException("The selected file changed while it was being imported.")
            }
            val output = ByteArray(byteCount)
            if (byteCount == 0) return@synchronized output
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val buffer = ByteBuffer.wrap(output)
                    var position = offset
                    while (buffer.hasRemaining()) {
                        val count = channel.read(buffer, position)
                        if (count <= 0) {
                            throw ImportedDocumentReadException(
                                "The selected file changed while it was being imported.",
                            )
                        }
                        position += count
                    }
                }
                if (Files.size(path) != byteSize) {
                    throw ImportedDocumentReadException("The selected file changed while it was being imported.")
                }
                output
            } catch (error: ImportedDocumentReadException) {
                throw error
            } catch (error: Throwable) {
                throw ImportedDocumentReadException("Unable to read the selected file.", error)
            }
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

/** Streams to a sibling temporary file so a failed source never truncates an existing export. */
internal fun writeBinaryDocument(
    selected: Path,
    source: BinaryDocumentExportSource,
): Boolean = runCatching {
    val target = selected.toAbsolutePath()
    val directory = requireNotNull(target.parent) { "Binary export has no parent directory" }
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".shinsou-export-", ".tmp")
    try {
        Files.newOutputStream(
            temporary,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).buffered(64 * 1024).use { output ->
            source.writeCheckedTo(BinaryDocumentExportSink { chunk -> output.write(chunk) })
            output.flush()
        }
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Atomic moves are optional on this file system; a plain replace is enough.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    true
}.onFailure { Log.warn("Desktop", it) { "Exporting a binary document failed" } }.getOrDefault(false)

internal object DesktopPersistence : SnapshotFile {
    private val directory: Path by lazy { DesktopAppDirectories.dataRoot }
    private val stateFile: Path get() = directory.resolve("shinsou-state.json")

    override fun save(json: String) = saveState(json)

    override fun keepCopyBeforeHotState(): Boolean = keepCopyBeforeHotState(stateFile)

    /**
     * The C3 off-switch: a file named [WHOLE_JSON_SWITCH_FILE] beside the state file, or the
     * `shinsou.wholeJsonSnapshot` system property set to true.
     */
    fun wholeJsonRequested(): Boolean =
        System.getProperty("shinsou.wholeJsonSnapshot").toBoolean() || Files.exists(directory.resolve(WHOLE_JSON_SWITCH_FILE))

    /** C3 (docs/architecture/0007 §6.3.5): `<file>.before-hot-state`, taken once. */
    internal fun keepCopyBeforeHotState(file: Path): Boolean {
        val copy = file.resolveSibling(file.fileName.toString() + BEFORE_HOT_STATE_SUFFIX)
        if (Files.exists(copy) || !Files.exists(file)) return true
        val partial = copy.resolveSibling(copy.fileName.toString() + ".partial")
        return try {
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(partial, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(partial, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (logged: java.io.IOException) {
            Log.warn("Desktop", logged) { "Copying the app state before moving its hot rows failed" }
            false
        }
    }

    /**
     * The saved state; null when there is none, or it could not be decoded: the caller tells a
     * fresh install from a lost JSON by the hot state it finds (C3). A file that exists but cannot
     * be read fails the start instead: starting empty would let the next save replace the user's
     * library. A file that reads but does not decode is moved aside (kept recoverable).
     */
    fun readSnapshot(): AppSnapshot? = readSnapshot(stateFile)

    /** [readSnapshot] of [file], or a fresh state when there is none. */
    internal fun loadSnapshot(file: Path): AppSnapshot = readSnapshot(file) ?: AppSnapshot()

    internal fun readSnapshot(file: Path): AppSnapshot? {
        if (!Files.exists(file)) return null
        val payload = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Shinsou X could not read its saved state at $file", error)
        }
        return try {
            AppSnapshotJson.decode(payload)
        } catch (logged: Exception) {
            // Not Throwable: an OutOfMemoryError says nothing about the file.
            Log.warn("Desktop", logged) { "Saved app state could not be decoded; quarantining it and starting empty" }
            val quarantine = file.resolveSibling("shinsou-state.corrupt-${System.currentTimeMillis()}.json")
            Files.move(file, quarantine, StandardCopyOption.REPLACE_EXISTING)
            null
        }
    }

    fun saveState(payload: String) {
        Files.createDirectories(directory)
        val temporary = directory.resolve("shinsou-state.json.tmp")
        // Forced to disk before the rename: a power loss must leave the old state or the new one,
        // never an empty file under the real name.
        java.nio.channels.FileChannel.open(
            temporary,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Atomic moves are optional on this file system; a plain replace is enough.
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
