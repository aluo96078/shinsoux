package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.ui.core.SettingsEdit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Owns the browse overlay stack so a child publication cannot outlive the source/search surface
 * that opened it. Desktop keeps parent surfaces mounted beside publication details, which makes
 * it especially important that dismissing a parent also dismisses its child.
 */
internal data class BrowseOverlayState(
    val activeSource: BrowseSource? = null,
    val globalSearchVisible: Boolean = false,
    val activeV2Publication: BrowseManga? = null,
    val activeV2Reader: Boolean = false,
) {
    val hasOverlay: Boolean
        get() = activeSource != null || globalSearchVisible || activeV2Publication != null

    fun openSource(source: BrowseSource): BrowseOverlayState = copy(
        activeSource = source,
        globalSearchVisible = false,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun openGlobalSearch(): BrowseOverlayState = copy(
        activeSource = null,
        globalSearchVisible = true,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun openPublication(publication: BrowseManga): BrowseOverlayState = copy(
        activeV2Publication = publication,
        activeV2Reader = false,
    )

    fun setReaderVisible(visible: Boolean): BrowseOverlayState = copy(activeV2Reader = visible)

    fun closePublication(): BrowseOverlayState = copy(
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeSource(): BrowseOverlayState = copy(
        activeSource = null,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeGlobalSearch(): BrowseOverlayState = copy(
        globalSearchVisible = false,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeAll(): BrowseOverlayState = BrowseOverlayState()
}

/** Which sources and extensions Browse lists, from the settings. */
public class BrowseSourceFilters(
    internal val showNsfw: Boolean,
    internal val enabledLanguages: Set<String>,
    internal val pinnedSourceIds: Set<Long>,
    /** Stable v2 source identities corresponding to [pinnedSourceIds] of v2 sources. */
    internal val pinnedSourceKeys: Set<String> = emptySet(),
) {
    /** Whether [source] may be listed (its rating and language). */
    internal fun admits(nsfw: Boolean, language: String): Boolean =
        (showNsfw || !nsfw) && (enabledLanguages.isEmpty() || language in enabledLanguages)
}

/** What Browse tells the app shell. */
public data class BrowseHost(
    /** A legacy source title: the shell shows it at once while it is resolved. */
    val onOpenManga: (BrowseManga) -> Unit,
    val onSourcePinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    val onSourcePinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit,
    val onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument? = { null },
    val onBackAvailabilityChanged: (Boolean) -> Unit = {},
    /** Closes a legacy detail route owned by a source/search parent outside this composable. */
    val onParentDismissed: () -> Unit = {},
    val onReaderVisibilityChanged: (Boolean) -> Unit = {},
    /** The user started opening a chapter of a publication in the reader (it shows once loaded). */
    val onReaderOpening: () -> Unit = {},
)

/** A position an extension reader reached, for the app's reading history. */
public fun interface ExtensionReaderProgressSink {
    public fun record(title: String, unitTitle: String, locator: ReadingLocator, pageIndex: Int, pageCount: Int?)
}

/** The app's own library of extension publications. */
public class ExtensionLibraryHost(
    /** App-owned library mutation for every extension v2 publication. */
    internal val setFavorite: suspend (BrowseManga, RemotePublicationV2, Boolean) -> Unit = { _, _, _ -> },
    internal val isFavorite: (BrowseManga) -> Boolean = { false },
)

/** What the extension publication page and its reader need from the app. */
public data class ExtensionReaderHost(
    val contentFeatures: ContentFeatures? = null,
    val readerSettings: ReaderSettings = ReaderSettings(),
    val onReaderSettingsChange: (SettingsEdit<ReaderSettings>) -> Unit = {},
    val copyText: (label: String, text: String) -> Boolean = { _, _ -> false },
    val openExternalUrl: (String) -> Unit = {},
    val shareText: (String, String) -> Unit = { _, _ -> },
    val onReaderProgress: ExtensionReaderProgressSink = ExtensionReaderProgressSink { _, _, _, _, _ -> },
    val onReaderProgressFlushed: suspend () -> Unit = {},
    val volumeKeyRouter: ReaderVolumeKeyRouter? = null,
    val library: ExtensionLibraryHost = ExtensionLibraryHost(),
    /** Where plugin and store work of the page and its holders runs: never the UI thread. */
    val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
)

/**
 * R4-3 (docs/architecture/0006): the Browse screen's navigation — its page, search text and the
 * overlay stack of a source catalogue, the global search and an extension publication — and the
 * system Back that walks it. The catalogue and the extension pages have holders of their own.
 */
internal class BrowseScreenState(
    initialSection: BrowseSection,
    initialBackRequest: Long,
) {
    var section by mutableStateOf(initialSection)
        private set
    var query by mutableStateOf("")
    var overlays by mutableStateOf(BrowseOverlayState())
        private set

    /** The source whose sign-in dialog is open over its catalogue. */
    var loginSourceIdentity by mutableStateOf<String?>(null)

    /**
     * A reader is owned by ExtensionV2PublicationPane. A monotonically increasing request lets a
     * system back gesture ask that child to dispose its materialized session before the parent
     * changes the layout back to the publication detail pane.
     */
    var readerBackRequest by mutableLongStateOf(0L)
        private set

    private var handledBackRequest = initialBackRequest

    /** What the shell is told; replaced on every composition. */
    var host: BrowseHost? = null

    /** Stops what the open catalogue is doing; set by the screen. */
    var onCatalogueClosed: () -> Unit = {}

    private fun readerVisibilityChanged(visible: Boolean) = host?.onReaderVisibilityChanged?.invoke(visible)

    private fun parentDismissed() = host?.onParentDismissed?.invoke()

    fun closeActiveSource() {
        val hadSource = overlays.activeSource != null
        onCatalogueClosed()
        overlays = overlays.closeSource()
        readerVisibilityChanged(false)
        if (hadSource) parentDismissed()
    }

    fun closeGlobalSearch() {
        val hadSearch = overlays.globalSearchVisible
        overlays = overlays.closeGlobalSearch()
        readerVisibilityChanged(false)
        if (hadSearch) parentDismissed()
    }

    fun openGlobalSearch() {
        overlays = overlays.openGlobalSearch()
    }

    fun openSource(source: BrowseSource) {
        overlays = overlays.openSource(source)
    }

    fun closePublication() {
        overlays = overlays.closePublication()
        readerVisibilityChanged(false)
    }

    fun readerOpening() {
        host?.onReaderOpening?.invoke()
    }

    fun setReaderVisible(visible: Boolean) {
        overlays = overlays.setReaderVisible(visible)
        readerVisibilityChanged(visible)
    }

    /** Extension titles open over the catalogue; legacy ones are shown by the shell while they resolve. */
    fun openManga(item: BrowseManga) {
        if (item.sourceKey != null) {
            overlays = overlays.openPublication(item)
        } else {
            // Let the app route to an immediate loading preview. Resolving source metadata can be
            // network-bound and must not hold the browse surface in place until it completes.
            host?.onOpenManga?.invoke(item)
        }
    }

    /** Another page was chosen: every overlay closes and the search starts empty. */
    fun selectSection(option: BrowseSection) {
        val parentWasOpen = overlays.activeSource != null || overlays.globalSearchVisible
        onCatalogueClosed()
        overlays = overlays.closeAll()
        readerVisibilityChanged(false)
        if (parentWasOpen) parentDismissed()
        section = option
        query = ""
    }

    /** The shell asked for one Back ([request] changed): the topmost overlay closes. */
    fun onSystemBack(request: Long) {
        if (request == handledBackRequest) return
        handledBackRequest = request
        when {
            overlays.activeV2Reader -> readerBackRequest += 1
            overlays.activeV2Publication != null -> closePublication()
            overlays.activeSource != null -> closeActiveSource()
            overlays.globalSearchVisible -> closeGlobalSearch()
        }
    }
}
