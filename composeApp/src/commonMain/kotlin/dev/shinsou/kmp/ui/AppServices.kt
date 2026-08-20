package dev.shinsou.kmp.ui

import dev.shinsou.kmp.backup.SyncAwareSnapshotRestore
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.local.LocalImportResult
import dev.shinsou.kmp.reader.ReaderImageTransform
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.tracking.TrackingCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private val foregroundLifecycle = MutableStateFlow(AppLifecycleState.FOREGROUND)
private val emptySourceLoginRequests = MutableStateFlow<List<SourceLoginRequest>>(emptyList())

/**
 * Platform and extension operations used by the common UI.
 *
 * The UI intentionally talks to stable, serializable values instead of a JS runtime,
 * an Android Activity, or an Apple framework. Each application target can provide its
 * own implementation at the composition root.
 */
interface ShinsouAppServices {
    val browse: BrowseCallbacks
        get() = BrowseCallbacks.None

    val content: ContentCallbacks
        get() = ContentCallbacks.None

    /** Secure, platform-persisted tracker integration supplied by the application composition. */
    val tracking: TrackingCoordinator?
        get() = null

    /** Complete-snapshot sync; iOS supplies iCloud Drive while other targets report unavailable. */
    val snapshotSync: SnapshotSyncController?
        get() = null

    /** Event-based E2EE sync. Runtime state and one-time secrets stay outside AppSnapshot. */
    val cloudflareSync: CloudflareSyncUiController?
        get() = null

    /** Safe bulk restore/reset policy bound to the same v2 runtime/local store as cloud sync. */
    val syncAwareSnapshotRestore: SyncAwareSnapshotRestore?
        get() = null

    /** Cold or hot stream of URLs/events received by the platform application. */
    val deepLinks: Flow<ShinsouDeepLink>
        get() = emptyFlow()

    /** Current application visibility, supplied by each platform host. */
    val appLifecycle: StateFlow<AppLifecycleState>
        get() = foregroundLifecycle

    /** Reader-only hardware volume button presses emitted by platforms that can intercept them. */
    val readerVolumeKeyEvents: Flow<ReaderVolumeKeyEvent>
        get() = emptyFlow()

    /** System back/edge-swipe requests emitted by native hosts (for example iOS edge-pop). */
    val systemBackEvents: Flow<Unit>
        get() = emptyFlow()

    /**
     * Interactive system-back gesture updates.  Mobile hosts emit progress while the user's
     * finger is down and a final settle event when the gesture is committed or cancelled.  The
     * common UI can therefore move the current destination with the finger instead of waiting for
     * a completed swipe before starting an animation.
     */
    val systemBackGestureEvents: Flow<SystemBackGestureEvent>
        get() = emptyFlow()

    /** Desktop targets use this to opt into denser controls and desktop conventions. */
    val prefersDesktopChrome: Boolean
        get() = false

    /** Security features the current platform can actually enforce. */
    val securityCapabilities: PlatformSecurityCapabilities
        get() = PlatformSecurityCapabilities.Unavailable

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

    fun shareText(title: String, text: String) = Unit

    fun copyText(label: String, text: String): Boolean = false

    suspend fun readClipboardText(): String? = null

    /** Mobile camera scanner; every platform must retain paste/manual-code fallback. */
    suspend fun scanQrCode(): String? = null

    suspend fun exportDocument(suggestedName: String, contents: String): Boolean = false

    suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits = DEFAULT_IMPORTED_DOCUMENT_LIMITS,
    ): ImportedDocument? = null

    suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits = DEFAULT_IMPORTED_DOCUMENT_LIMITS,
    ): List<ImportedDocument> = emptyList()

    fun setKeepScreenOn(enabled: Boolean) = Unit

    fun setSecureScreen(enabled: Boolean) = Unit

    /** Hides or restores platform system chrome while the reader is active. */
    fun setFullscreen(enabled: Boolean) = Unit

    /** Applies a reader-only orientation. Passing null restores the platform's previous policy. */
    fun setReaderOrientation(orientation: ReaderOrientation?) = Unit

    /**
     * Publishes whether the reader is currently presented. Native hosts use this to disable
     * navigation gestures that would otherwise compete with the reader pager.
     */
    fun setReaderOpen(open: Boolean) = Unit

    /** Keeps platform volume-button infrastructure warm while the preference is enabled. */
    fun setReaderVolumeKeyInfrastructureEnabled(enabled: Boolean) = Unit

    /** Starts or stops platform interception. Common UI always supplies the complete gate state. */
    fun setReaderVolumeKeyMonitoringEnabled(enabled: Boolean) = Unit

    fun requestNotificationPermission() = Unit

    suspend fun authenticate(reason: String): Boolean = false

    /** Called after common navigation has applied the event. */
    fun deepLinkHandled(link: ShinsouDeepLink) = Unit

    fun requestApplicationClose() = Unit

    companion object {
        val None: ShinsouAppServices = object : ShinsouAppServices {}
    }
}

data class SecurityFeatureCapability(
    val available: Boolean,
    val unavailableReason: String? = null,
) {
    init {
        require(available || !unavailableReason.isNullOrBlank()) {
            "An unavailable security capability must explain why it is unavailable."
        }
    }

    companion object {
        val Available = SecurityFeatureCapability(available = true)

        fun unavailable(reason: String) = SecurityFeatureCapability(
            available = false,
            unavailableReason = reason,
        )
    }
}

data class PlatformSecurityCapabilities(
    val appLock: SecurityFeatureCapability,
    val secureScreen: SecurityFeatureCapability,
) {
    companion object {
        val Available = PlatformSecurityCapabilities(
            appLock = SecurityFeatureCapability.Available,
            secureScreen = SecurityFeatureCapability.Available,
        )

        val Unavailable = PlatformSecurityCapabilities(
            appLock = SecurityFeatureCapability.unavailable("Device authentication is unavailable on this platform."),
            secureScreen = SecurityFeatureCapability.unavailable("Secure-screen protection is unavailable on this platform."),
        )
    }
}

/**
 * Mobile hosts can always enforce their secure-screen implementation, but app lock is safe to
 * advertise only while the device can actually perform owner authentication. This value is a
 * runtime capability and must never be copied into the portable snapshot.
 */
internal fun mobileSecurityCapabilities(
    deviceOwnerAuthenticationAvailable: Boolean,
    unavailableReason: String = "Set up a device passcode, PIN, password, or biometric authentication to use app lock.",
): PlatformSecurityCapabilities = PlatformSecurityCapabilities(
    appLock = if (deviceOwnerAuthenticationAvailable) {
        SecurityFeatureCapability.Available
    } else {
        SecurityFeatureCapability.unavailable(unavailableReason)
    },
    secureScreen = SecurityFeatureCapability.Available,
)

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

enum class ReaderVolumeKeyEvent {
    VOLUME_UP,
    VOLUME_DOWN,
}

sealed interface SystemBackGestureEvent {
    data class Progress(val fraction: Float) : SystemBackGestureEvent

    data class Settled(val committed: Boolean) : SystemBackGestureEvent
}

/**
 * Maps hardware buttons to logical reader movement only while the complete common gate is open.
 * Keeping this platform-neutral prevents an iOS callback from bypassing reader state or settings.
 */
fun readerVolumeKeyAction(
    event: ReaderVolumeKeyEvent,
    readerOpen: Boolean,
    volumeKeysEnabled: Boolean,
): ReaderTapAction? {
    if (!readerOpen || !volumeKeysEnabled) return null
    return when (event) {
        ReaderVolumeKeyEvent.VOLUME_UP -> ReaderTapAction.PREVIOUS_PAGE
        ReaderVolumeKeyEvent.VOLUME_DOWN -> ReaderTapAction.NEXT_PAGE
    }
}

sealed interface ShinsouDeepLink {
    data class OpenManga(val mangaId: Long) : ShinsouDeepLink

    data class OpenChapter(
        val mangaId: Long,
        val chapterId: Long,
    ) : ShinsouDeepLink

    data class OpenSection(val section: DeepLinkSection) : ShinsouDeepLink

    data object OpenSettings : ShinsouDeepLink

    /** One-time Cloudflare setup, invite, pairing, recovery, or operator handoff action. */
    data class OpenSyncLink(val payload: SyncLinkPayload) : ShinsouDeepLink
}

/**
 * Retains OS-delivered links in FIFO order until common navigation acknowledges the exact event.
 * This is especially important for one-time sync links received while the app is locked or while
 * an earlier provisioning request is still running. The small bound prevents custom-scheme spam
 * from growing memory without limit; a full queue rejects the new link without disturbing secrets
 * that were already accepted.
 */
internal class RetainedDeepLinkQueue(
    private val maximumPending: Int = 32,
) {
    private val mutableState = MutableStateFlow(QueueState())

    init {
        require(maximumPending > 0)
    }

    val events: Flow<ShinsouDeepLink> = mutableState
        .map { it.pending.firstOrNull()?.link }
        .filterNotNull()

    fun tryEnqueue(link: ShinsouDeepLink): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.pending.size >= maximumPending) return false
            val queued = QueuedDeepLink(current.nextId, link)
            val updated = QueueState(current.nextId + 1, current.pending + queued)
            if (mutableState.compareAndSet(current, updated)) return true
        }
    }

    fun handled(link: ShinsouDeepLink) {
        while (true) {
            val current = mutableState.value
            if (current.pending.firstOrNull()?.link !== link) return
            val updated = current.copy(pending = current.pending.drop(1))
            if (mutableState.compareAndSet(current, updated)) return
        }
    }

    private data class QueueState(
        val nextId: Long = 0,
        val pending: List<QueuedDeepLink> = emptyList(),
    )

    private data class QueuedDeepLink(
        val id: Long,
        val link: ShinsouDeepLink,
    )
}

/**
 * Parsed sync links deliberately keep secrets out of AppSnapshot and navigation route strings.
 * Callers must consume the payload once and must never include it in logs or error messages.
 */
class SyncLinkPayload(
    val action: SyncLinkAction,
    val endpoint: String,
    val oneTimeSecret: EphemeralSyncPayload?,
    val sessionId: String? = null,
    val instanceId: String? = null,
    val userId: String? = null,
    val workspaceId: String? = null,
) {
    override fun toString(): String =
        "SyncLinkPayload(action=$action, endpoint=$endpoint, sessionId=$sessionId, " +
            "instanceId=$instanceId, userId=$userId, workspaceId=$workspaceId, " +
            "oneTimeSecret=${if (oneTimeSecret == null) "absent" else "REDACTED"})"
}

enum class SyncLinkAction {
    SETUP,
    INVITE,
    PAIR,
    RECOVERY,
    EMERGENCY_RESET,
}

enum class DeepLinkSection {
    Library,
    Updates,
    History,
    Browse,
    More,
}

data class ImportedDocument(
    val name: String,
    val contents: ByteArray,
)

/** Network/source operations needed by detail, updates, downloads, and the reader. */
interface ContentCallbacks {
    /** Copies selected image or ZIP-based comic documents into the built-in source 0. */
    suspend fun importLocalDocuments(documents: List<ImportedDocument>): List<LocalImportResult> = emptyList()

    suspend fun refreshLibrary(mangaIds: Set<Long>) = Unit

    suspend fun refreshManga(mangaId: Long) = Unit

    /** Resolves a persisted source-relative manga URL into the canonical external URL. */
    suspend fun resolveMangaOriginalUrl(mangaId: Long): String? = null

    /** Resolves a persisted chapter URL into a safe HTTP(S) URL suitable for an external browser. */
    suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? = null

    suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter = ReaderChapter()

    suspend fun enqueueDownload(mangaId: Long, chapterId: Long) = Unit

    suspend fun retryDownload(itemId: String) = Unit

    suspend fun removeDownload(itemId: String) = Unit

    suspend fun reorderDownloads(orderedIds: List<String>) = Unit

    suspend fun clearCompletedDownloads() = Unit

    suspend fun pauseDownloads(paused: Boolean) = Unit

    companion object {
        val None: ContentCallbacks = object : ContentCallbacks {}
    }
}

data class ReaderChapter(
    val pages: List<ReaderPage> = emptyList(),
    val referer: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
)

data class ReaderPage(
    val index: Int,
    val imageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val local: Boolean = false,
    val imageTransform: ReaderImageTransform? = null,
)

/** A non-blocking login prompt requested by an executable source. */
data class SourceLoginRequest(
    val sourceId: Long,
    val sourceName: String,
    val reason: String? = null,
)

/** Extension/source bridge. Plugin modules adapt their own models to these DTOs. */
interface BrowseCallbacks {
    val state: StateFlow<BrowseSnapshot>

    /** FIFO login prompts requested by sources, deduplicated by source id. */
    val loginRequests: StateFlow<List<SourceLoginRequest>>
        get() = emptySourceLoginRequests

    fun dismissSourceLoginRequest(sourceId: Long) = Unit

    suspend fun refresh() = Unit

    suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean) = Unit

    suspend fun addRepository(url: String): BrowseRepository? = null

    suspend fun removeRepository(repositoryId: String) = Unit

    suspend fun selectRepository(repositoryId: String?) = Unit

    suspend fun browseSource(
        sourceId: Long,
        query: String = "",
        page: Int = 1,
        filters: List<BrowseFilter>? = null,
    ): BrowsePage = BrowsePage()

    /** Loads the source's latest-updates catalogue when that capability is advertised. */
    suspend fun browseSourceLatest(
        sourceId: Long,
        page: Int = 1,
    ): BrowsePage = browseSource(sourceId = sourceId, page = page)

    /** Returns the local manga id when the bridge imported or resolved the item. */
    suspend fun resolveManga(item: BrowseManga): Long? = null

    suspend fun installExtension(extensionId: String) = Unit

    suspend fun uninstallExtension(extensionId: String) = Unit

    suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean) = Unit

    suspend fun migrateManga(mangaId: Long, target: BrowseManga) = Unit

    suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>) = Unit

    /** Saves raw credentials, or invokes the plugin login contract when it advertises support. */
    suspend fun saveSourceCredentials(sourceId: Long, username: String, password: String): Boolean = false

    suspend fun logoutSource(sourceId: Long) = Unit

    suspend fun setSourceCookie(sourceId: Long, cookie: SourceCookie) = Unit

    suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String) = Unit

    suspend fun clearSourceCookies(sourceId: Long) = Unit

    /**
     * Builds the browser challenge request with the exact source URL, network user agent, and
     * source-isolated cookies used by Browse/Reader/Download.
     */
    suspend fun sourceWebChallenge(sourceId: Long): SourceWebChallengeRequest? = null

    companion object {
        private val emptyState = MutableStateFlow(BrowseSnapshot())

        val None: BrowseCallbacks = object : BrowseCallbacks {
            override val state: StateFlow<BrowseSnapshot> = emptyState
        }
    }
}

data class BrowseSnapshot(
    val repositories: List<BrowseRepository> = emptyList(),
    val selectedRepositoryId: String? = null,
    val sources: List<BrowseSource> = emptyList(),
    val extensions: List<BrowseExtension> = emptyList(),
    val migrations: List<MigrationCandidate> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class BrowseRepository(
    val id: String,
    val url: String,
    val name: String,
    val website: String? = null,
    val signingFingerprint: String? = null,
    val official: Boolean = false,
)

data class BrowseSource(
    val id: Long,
    val name: String,
    val language: String,
    val baseUrl: String = "",
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val isNsfw: Boolean = false,
    val supportsLatest: Boolean = true,
    val supportsLogin: Boolean = false,
    val credential: SourceCredential? = null,
    val cookies: List<SourceCookie> = emptyList(),
    val preferences: List<SourcePreference> = emptyList(),
    val filters: List<BrowseFilter> = emptyList(),
)

/** Stable UI-side representation of executable source filters, preserving recursive order/state. */
sealed interface BrowseFilter {
    val name: String

    data class Header(override val name: String) : BrowseFilter

    data object Separator : BrowseFilter {
        override val name: String = ""
    }

    data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : BrowseFilter

    data class Text(
        override val name: String,
        val state: String,
    ) : BrowseFilter

    data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : BrowseFilter

    data class TriState(
        override val name: String,
        val state: BrowseTriState,
    ) : BrowseFilter

    data class Group(
        override val name: String,
        val filters: List<BrowseFilter>,
    ) : BrowseFilter

    data class Sort(
        override val name: String,
        val values: List<String>,
        val selection: BrowseSortSelection?,
    ) : BrowseFilter
}

enum class BrowseTriState {
    Ignore,
    Include,
    Exclude,
}

data class BrowseSortSelection(
    val index: Int,
    val ascending: Boolean,
)

data class SourceCredential(
    val username: String,
    val password: String,
)

data class SourceCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    /** Host-only cookies must never be sent to subdomains. */
    val hostOnly: Boolean = !domain.startsWith('.'),
)

data class SourceWebChallengeRequest(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val userAgent: String,
    val cookies: List<SourceCookie> = emptyList(),
)

data class SourcePreference(
    val key: String,
    val title: String,
    val summary: String? = null,
    val value: String = "",
    val choices: List<String> = emptyList(),
    val choiceValues: List<String> = choices,
    val kind: SourcePreferenceKind = SourcePreferenceKind.Text,
)

enum class SourcePreferenceKind {
    Text,
    Toggle,
    Choice,
    MultiChoice,
}

data class BrowseExtension(
    val id: String,
    val name: String,
    val version: String,
    val language: String,
    val iconUrl: String? = null,
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
    val trusted: Boolean = false,
    val isNsfw: Boolean = false,
    val sourceIds: List<Long> = emptyList(),
)

data class BrowseManga(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    /** Headers prepared by the source request pipeline for the cover image. */
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    val author: String? = null,
)

data class BrowsePage(
    val items: List<BrowseManga> = emptyList(),
    val hasNextPage: Boolean = false,
)

data class MigrationCandidate(
    val mangaId: Long,
    val title: String,
    val thumbnailUrl: String? = null,
    val currentSourceName: String,
    val suggestions: List<BrowseManga> = emptyList(),
)
