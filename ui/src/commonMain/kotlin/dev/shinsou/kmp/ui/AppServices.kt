package dev.shinsou.kmp.ui

import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEventSink
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.sync.v3.SyncClient
import dev.shinsou.kmp.tracking.TrackingService
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.shinsou.kmp.plugin.BrowseCallbacks
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.DEFAULT_IMPORTED_DOCUMENT_LIMITS
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentLimits
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.settings.DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES
import dev.shinsou.kmp.ui.settings.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.settings.SecurityFeatureCapability
import dev.shinsou.kmp.ui.settings.copyToByteArray

private val foregroundLifecycle = MutableStateFlow(AppLifecycleState.FOREGROUND)

/** Sharing and the clipboard. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface ClipboardHost {
    fun shareText(title: String, text: String) = Unit

    fun copyText(label: String, text: String): Boolean = false

    suspend fun readClipboardText(): String? = null
}

/** Importing and exporting files through the platform picker. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface DocumentHost {
    suspend fun exportDocument(suggestedName: String, contents: String): Boolean = false

    /** Binary-safe document export. Implementations must not stringify or transcode [contents]. */
    suspend fun exportBinaryDocument(suggestedName: String, contents: ByteArray): Boolean = false

    /**
     * Saves a picture where the platform keeps pictures (the gallery or Photos). Hosts without
     * such a place fall back to an ordinary file export.
     */
    suspend fun saveImage(suggestedName: String, contents: ByteArray): Boolean =
        exportBinaryDocument(suggestedName, contents)

    /**
     * Streaming binary export used by large content archives. Production hosts override this and
     * write directly to their file/content-provider boundary. The bounded default keeps older
     * small-document hosts source-compatible without allowing a large archive heap copy.
     */
    suspend fun exportBinaryDocument(
        suggestedName: String,
        source: BinaryDocumentExportSource,
    ): Boolean {
        if (source.expectedByteSize > DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES) {
            return false
        }
        return exportBinaryDocument(suggestedName, source.copyToByteArray())
    }

    suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits = DEFAULT_IMPORTED_DOCUMENT_LIMITS,
    ): ImportedDocument? = null

    suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits = DEFAULT_IMPORTED_DOCUMENT_LIMITS,
    ): List<ImportedDocument> = emptyList()
}

/** URLs and events the platform application received. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface DeepLinkHost {
    /** Cold or hot stream of URLs/events received by the platform application. */
    val deepLinks: Flow<ShinsouDeepLink>
        get() = emptyFlow()

    /** Called after common navigation has applied the event. */
    fun deepLinkHandled(link: ShinsouDeepLink) = Unit

    /**
     * Whether [link] still waits to be handled. False once another shell of this process (a
     * second Android activity) handled it: a prompt this shell shows for it is then stale.
     */
    fun isDeepLinkPending(link: ShinsouDeepLink): Boolean = true

}

/** Application visibility, system back and closing. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface LifecycleHost {
    /** Current application visibility, supplied by each platform host. */
    val appLifecycle: StateFlow<AppLifecycleState>
        get() = foregroundLifecycle

    /** System back/edge-swipe requests emitted by native hosts (for example iOS edge-pop). */
    val systemBackEvents: Flow<Unit>
        get() = emptyFlow()

    /**
     * Registers the common navigation reducer with a native back dispatcher. Android uses this
     * to decide whether a system-back press should pop an in-app destination or finish the
     * activity; other platforms keep the no-op default and continue using [systemBackEvents].
     */
    fun setSystemBackHandler(handler: (() -> Boolean)?) = Unit

    /**
     * Interactive system-back gesture updates.  Mobile hosts emit progress while the user's
     * finger is down and a final settle event when the gesture is committed or cancelled.  The
     * common UI can therefore move the current destination with the finger instead of waiting for
     * a completed swipe before starting an animation.
     */
    val systemBackGestureEvents: Flow<SystemBackGestureEvent>
        get() = emptyFlow()

    fun requestApplicationClose() = Unit

    /**
     * Where the shell's state ([ShellSavedState]) is kept between launches, on a platform whose own
     * saved state does not outlive the process (desktop, iOS). Null where it does (Android's Bundle).
     */
    val shellStateStore: ShellStateStore?
        get() = null
}

/** Keeps the shell's saved state, as [storedShellStateText] wrote it, across launches. */
interface ShellStateStore {
    /** The state last written; null when there is none or it cannot be read. */
    fun read(): String?

    /** Replaces the kept state. Called on the UI thread when the app goes to the background or closes. */
    fun write(state: String)
}

/** Window and screen behaviour while reading. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface DisplayHost {
    /** Desktop targets use this to opt into denser controls and desktop conventions. */
    val prefersDesktopChrome: Boolean
        get() = false

    fun setKeepScreenOn(enabled: Boolean) = Unit

    /** Hides or restores platform system chrome while the reader is active. */
    fun setFullscreen(enabled: Boolean) = Unit

    /** Applies a reader-only orientation. Passing null restores the platform's previous policy. */
    fun setReaderOrientation(orientation: ReaderOrientation?) = Unit

    /**
     * Publishes whether the reader is currently presented. Native hosts use this to disable
     * navigation gestures that would otherwise compete with the reader pager.
     */
    fun setReaderOpen(open: Boolean) = Unit
}

/** App lock and screen protection. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface SecurityHost {
    /** Security features the current platform can actually enforce. */
    val securityCapabilities: PlatformSecurityCapabilities
        get() = PlatformSecurityCapabilities.Unavailable

    fun setSecureScreen(enabled: Boolean) = Unit

    suspend fun authenticate(reason: String): Boolean = false
}

/** Hardware volume keys in the reader. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface InputHost {
    /** Reader-only hardware volume button presses emitted by platforms that can intercept them. */
    val readerVolumeKeyEvents: Flow<ReaderVolumeKeyEvent>
        get() = emptyFlow()

    /** Keeps platform volume-button infrastructure warm while the preference is enabled. */
    fun setReaderVolumeKeyInfrastructureEnabled(enabled: Boolean) = Unit

    /** Starts or stops platform interception. Common UI always supplies the complete gate state. */
    fun setReaderVolumeKeyMonitoringEnabled(enabled: Boolean) = Unit

    /** Installs the active common-reader dispatcher for synchronous native hardware delivery. */
    fun setReaderVolumeKeyEventSink(sink: ReaderVolumeKeyEventSink?) = Unit
}

/** Opening a URL outside the app. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface ExternalNavigationHost {
    fun openExternalUrl(url: String) = Unit

    /**
     * Opens a URL and reports whether the platform accepted the request.  Existing callers use
     * [openExternalUrl] as a fire-and-forget action; provisioning uses this explicit result so a
     * browser-launch failure is visible instead of looking like a stalled deployment.
     */
    fun tryOpenExternalUrl(url: String): Boolean {
        openExternalUrl(url)
        return true
    }
}

/** Notifications and background-work restrictions. Part of [ShinsouAppServices] (R4-2, docs/architecture/0006). */
interface BackgroundWorkHost {
    fun requestNotificationPermission() = Unit

    /** True when a platform setting the user can change (Android battery optimization) may stop background downloads. */
    fun backgroundDownloadsRestricted(): Boolean = false

    /** Opens the setting that lifts [backgroundDownloadsRestricted]. */
    fun openBackgroundDownloadSettings() = Unit
}

/**
 * Platform and extension operations used by the common UI.
 *
 * The UI intentionally talks to stable, serializable values instead of a JS runtime,
 * an Android Activity, or an Apple framework. Each application target can provide its
 * own implementation at the composition root.
 */
interface ShinsouAppServices :
    ClipboardHost,
    DocumentHost,
    DeepLinkHost,
    LifecycleHost,
    DisplayHost,
    SecurityHost,
    InputHost,
    ExternalNavigationHost,
    BackgroundWorkHost {
    val browse: BrowseCallbacks
        get() = BrowseCallbacks.None

    val content: ContentCallbacks
        get() = ContentCallbacks.None

    /** Null only in previews/tests or a host that has not supplied the shared SQLite foundation. */
    val contentFeatures: ContentFeatures?
        get() = null

    /** Checks GitHub for a newer version of the app; null where the host offers no checks. */
    val appUpdates: dev.shinsou.kmp.update.AppUpdateSource?
        get() = null

    /** On-device manga translation (ADR-0005); null where translation itself is unavailable. */
    val mangaTranslation: dev.shinsou.kmp.manga.MangaTranslationService?
        get() = null

    /** Secure, platform-persisted tracker integration supplied by the application composition. */
    val tracking: TrackingService?
        get() = null

    /** Checksummed binary content archive controller; null only when shared atomic storage is absent. */
    val portableContentBackupV2: PortableContentBackupV2UiController?
        get() = null

    /** Sync v3 account, status and manual sync; null only in previews/tests without shared storage. */
    val sync: SyncClient?
        get() = null

    /** Staged, redacted ShuYue v1 migration controller. */
    val shuYueMigration: ShuYueMigrationUiController?
        get() = null

    companion object {
        val None: ShinsouAppServices = object : ShinsouAppServices {}
    }
}

/** Existing app-lock settings must become active when device authentication is added or recovers. */
internal fun shouldLockOnAuthenticationAvailabilityChange(
    appLockConfigured: Boolean,
    wasAvailable: Boolean,
    isAvailable: Boolean,
): Boolean = appLockConfigured && !wasAvailable && isAvailable

internal fun securityFeatureEnabled(
    configured: Boolean,
    capability: SecurityFeatureCapability,
): Boolean = configured && capability.available

enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND,
}

sealed interface SystemBackGestureEvent {
    data class Progress(val fraction: Float) : SystemBackGestureEvent

    data class Settled(val committed: Boolean) : SystemBackGestureEvent
}

sealed interface ShinsouDeepLink {
    data class OpenManga(val mangaId: Long) : ShinsouDeepLink

    data class OpenChapter(
        val mangaId: Long,
        val chapterId: Long,
    ) : ShinsouDeepLink

    data class OpenSection(val section: DeepLinkSection) : ShinsouDeepLink

    data object OpenSettings : ShinsouDeepLink

    /**
     * ADR-0002 §8.1 `shinsou://add-repository?url=…&fp=…`: a publisher's repository and, optionally,
     * the full fingerprint to match. Nothing is trusted until the user confirms the key.
     */
    data class AddRepository(val repositoryUrl: String, val fingerprint: String?) : ShinsouDeepLink

    /**
     * `shinsou://add-dev-repository?url=…` from `shinsou-plugin serve` (ADR-0002 §8.1): an
     * unsigned development server. Shown for confirmation; it is admitted only while developer
     * mode is on and the address is this device or one the user entered.
     */
    data class AddDevRepository(val repositoryUrl: String) : ShinsouDeepLink
}

// R5-1: public for the platform AppServices of the app module; the bound stays this module's.
/**
 * Retains OS-delivered links in FIFO order until common navigation acknowledges the exact event.
 * The small bound prevents custom-scheme spam
 * from growing memory without limit; a full queue rejects the new link without disturbing secrets
 * that were already accepted.
 */
public class RetainedDeepLinkQueue internal constructor(
    private val maximumPending: Int,
) {
    public constructor() : this(maximumPending = 32)

    private val mutableState = MutableStateFlow(QueueState())

    init {
        require(maximumPending > 0)
    }

    val events: Flow<ShinsouDeepLink> = eventsWhere { true }

    /**
     * The oldest waiting link whose tag [accepts]: where several shells share the queue (Android
     * activities side by side), each takes the links its own window received.
     */
    fun eventsWhere(accepts: (tag: Any?) -> Boolean): Flow<ShinsouDeepLink> = mutableState
        .map { state -> state.pending.firstOrNull { accepts(it.tag) }?.link }
        .filterNotNull()

    /**
     * Queues [link]; [tag] is the receiver's own note about it (where it came from). Each receiver
     * ([sameReceiver] tells its links apart) may have [maximumPending] waiting, so receivers that
     * are not taking links (stopped, locked) cannot crowd out another's; all of them together, a
     * few times that.
     */
    fun tryEnqueue(link: ShinsouDeepLink, tag: Any? = null, sameReceiver: (tag: Any?) -> Boolean = { true }): Boolean {
        while (true) {
            val current = mutableState.value
            val full = current.pending.count { sameReceiver(it.tag) } >= maximumPending ||
                current.pending.size >= maximumPending * MAX_RECEIVERS
            if (full) return false
            val queued = QueuedDeepLink(current.nextId, link, tag)
            val updated = QueueState(current.nextId + 1, current.pending + queued)
            if (mutableState.compareAndSet(current, updated)) return true
        }
    }

    /** Whether a link waits to be handled. */
    val hasPending: Boolean get() = mutableState.value.pending.isNotEmpty()

    /** The links waiting to be handled, oldest first. */
    val pendingLinks: List<ShinsouDeepLink> get() = mutableState.value.pending.map { it.link }

    /** The tags of the links waiting to be handled, oldest first. */
    val pendingTags: List<Any?> get() = mutableState.value.pending.map { it.tag }

    /** Drops the waiting links whose tag [matches]: whoever received them gave them up. */
    fun discardWhere(matches: (tag: Any?) -> Boolean) {
        while (true) {
            val current = mutableState.value
            val updated = current.copy(pending = current.pending.filterNot { matches(it.tag) })
            if (mutableState.compareAndSet(current, updated)) return
        }
    }

    /**
     * The exact [link] event (the oldest waiting one whose tag [accepts]) was handled; returns
     * its tag, or null when it is no longer waiting (a stale acknowledgement changes nothing).
     */
    fun handled(link: ShinsouDeepLink, accepts: (tag: Any?) -> Boolean = { true }): Any? {
        while (true) {
            val current = mutableState.value
            val entry = current.pending.firstOrNull { accepts(it.tag) }?.takeIf { it.link === link } ?: return null
            val updated = current.copy(pending = current.pending - entry)
            if (mutableState.compareAndSet(current, updated)) return entry.tag
        }
    }

    private companion object {
        /** How many receivers' worth of links the queue holds in all. */
        const val MAX_RECEIVERS = 4
    }

    private data class QueueState(
        val nextId: Long = 0,
        val pending: List<QueuedDeepLink> = emptyList(),
    )

    private data class QueuedDeepLink(
        val id: Long,
        val link: ShinsouDeepLink,
        val tag: Any?,
    )
}

enum class DeepLinkSection {
    Library,
    Updates,
    History,
    Browse,
    More,
}
