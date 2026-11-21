package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.sync.v3.SyncClient
import dev.shinsou.kmp.tracking.TrackingService
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.plugin.BrowseCallbacks
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.plugin.BrowseSnapshot
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.DEFAULT_IMPORTED_DOCUMENT_LIMITS
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentLimits

private val foregroundLifecycle = MutableStateFlow(AppLifecycleState.FOREGROUND)
/**
 * Host content-plane bridge used by image composables. Plugin-controlled URLs are classified by
 * their exact source before Coil is allowed to see a model; a failed load stays a placeholder.
 *
 * Successfully fetched cover bytes are retained in a bounded LRU cache so a cover that scrolls
 * out of a lazy list and back again is neither re-downloaded nor shown as a placeholder. The cache
 * is bound to the current source authority: any install, removal, update or trust change in the
 * browse snapshot discards every entry, so a revoked source can no longer supply cached bytes.
 */
class PluginImageLoader internal constructor(
    private val catalog: SourceCatalog?,
    maxConcurrentLoads: Int = DEFAULT_PLUGIN_IMAGE_CONCURRENCY,
    maxCachedBytes: Long = DEFAULT_PLUGIN_IMAGE_CACHE_BYTES,
    maxCachedEntries: Int = DEFAULT_PLUGIN_IMAGE_CACHE_ENTRIES,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val permits = Semaphore(maxConcurrentLoads.also {
        require(it > 0) { "Plugin image concurrency must be positive" }
    })

    /**
     * Process-unique id of this loader. Generations restart at zero for every loader while Coil's
     * memory cache is process-wide, so the id keeps a recreated loader's keys from colliding.
     */
    internal val instanceId: Long = nextPluginImageLoaderId()

    // Last snapshot -> token. A racing recomputation is harmless: the token is a pure function.
    @kotlin.concurrent.Volatile
    private var authorityMemo: Pair<BrowseSnapshot?, Any?>? = null

    internal val cache = PluginImageByteCache(maxCachedBytes, maxCachedEntries) {
        authorityOf(catalog?.state?.value)
    }

    /**
     * Source authority as seen by image composables. It emits only when an install, removal,
     * update or trust change happens, so a mounted cover can drop bytes fetched under the old one.
     */
    val authority: Flow<Any?> = catalog?.state?.map(::authorityOf)?.distinctUntilChanged() ?: flowOf(null)

    fun currentAuthority(): Any? = authorityOf(catalog?.state?.value)

    private fun authorityOf(snapshot: BrowseSnapshot?): Any? {
        authorityMemo?.let { (memoSnapshot, token) -> if (memoSnapshot === snapshot) return token }
        return snapshot?.let(::pluginImageAuthority).also { authorityMemo = snapshot to it }
    }

    fun load(
        sourceId: Long,
        url: String?,
        headers: Map<String, String> = emptyMap(),
    ): (suspend () -> ByteArray?)? = url
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            when {
                // Source zero is normally app-owned local content. A remote URL without an exact
                // extension binding is ambiguous, however, so keep it inert instead of handing
                // a beta-era/orphaned plugin cover directly to Coil.
                sourceId == 0L && isRemoteImageUrl(value) -> ({ null })
                sourceId == 0L -> null
                // Every other persisted legacy source is extension-controlled even while its
                // runtime is unloaded; a failed host fetch must remain a placeholder.
                else -> PluginImageRequest(this, pluginImageCacheKey("legacy", sourceId.toString(), value, headers)) {
                    catalog?.loadPluginThumbnail(sourceId, value, headers)
                }
            }
        }

    fun load(
        sourceKey: SourceKey,
        url: String?,
        headers: Map<String, String> = emptyMap(),
    ): (suspend () -> ByteArray?)? = url
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            val scope = listOf(
                sourceKey.contractVersion.toString(),
                sourceKey.packageId,
                sourceKey.sourceId,
                sourceKey.legacyLongId?.toString().orEmpty(),
            ).joinToString("") { "${it.length}:$it" }
            PluginImageRequest(this, pluginImageCacheKey("key", scope, value, headers)) {
                catalog?.loadPluginThumbnail(sourceKey, value, headers)
            }
        }

    internal suspend fun fetch(cacheKey: String, fetch: suspend () -> ByteArray?): PluginImageResult? {
        cache.get(cacheKey)?.let { return it }
        return permits.withPermit {
            // An identical request may have filled the entry while this one waited for a permit.
            cache.get(cacheKey)?.let { return@withPermit it }
            val generation = cache.generation()
            val bytes = withContext(workDispatcher) { fetch() } ?: return@withPermit null
            // The source may have been revoked or replaced while the request was in flight; its
            // bytes are then neither published to the caller nor retained.
            cache.put(cacheKey, bytes, generation)
        }
    }

    companion object {
        val None = PluginImageLoader(null)
    }
}

/**
 * One cache-aware cover load. Requests for the same source, URL and headers compare equal, so a
 * composable that rebuilds its loader on recomposition does not restart (or re-render) the load.
 */
class PluginImageRequest internal constructor(
    private val owner: PluginImageLoader,
    /** Namespaced key; also used as Coil's memory-cache key for the decoded bitmap. */
    val cacheKey: String,
    private val fetch: suspend () -> ByteArray?,
) : suspend () -> ByteArray? {
    /** Non-suspending cache probe for the first composition frame; never starts a fetch. */
    fun cached(): PluginImageResult? = owner.cache.peek(cacheKey)

    fun cachedBytes(): ByteArray? = cached()?.bytes

    /** Loads the bytes together with the authority generation and fetch they came from. */
    suspend fun load(): PluginImageResult? = owner.fetch(cacheKey, fetch)

    /**
     * Coil memory-cache key for [result]'s decoded bytes. It names the exact fetch the bytes came
     * from, so a re-download (after eviction, for an uncached oversized image, or under a new
     * authority) never reuses a bitmap decoded from earlier bytes. A cache hit keeps its fetch id
     * and therefore still hits Coil's decoded entry.
     */
    fun memoryCacheKey(result: PluginImageResult): String = "$cacheKey|l${owner.instanceId}|f${result.fetchId}"

    /** Drops bytes that could not be decoded so a broken response is not retained. */
    suspend fun evict() = owner.cache.remove(cacheKey)

    override suspend fun invoke(): ByteArray? = load()?.bytes

    override fun equals(other: Any?): Boolean =
        other is PluginImageRequest && other.owner === owner && other.cacheKey == cacheKey

    override fun hashCode(): Int = cacheKey.hashCode()
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private val pluginImageLoaderIds = kotlin.concurrent.atomics.AtomicLong(0L)

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private fun nextPluginImageLoaderId(): Long = pluginImageLoaderIds.addAndFetch(1L)

/** Host-fetched image bytes, the source-authority generation they belong to and their fetch. */
class PluginImageResult internal constructor(
    val bytes: ByteArray,
    internal val generation: Long,
    /** Unique per loader for every completed download; retained by cache hits. */
    internal val fetchId: Long,
)

private fun pluginImageCacheKey(
    kind: String,
    scope: String,
    url: String,
    headers: Map<String, String>,
): String = buildString {
    append("shinsou-plugin-image|").append(kind).append('|')
    // Length-prefix every field so no URL or header value can forge another entry's key.
    (listOf(scope, url) + headers.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) })
        .forEach { append(it.length).append(':').append(it) }
}

/** Everything in the browse snapshot that decides whether a source may still serve images. */
private fun pluginImageAuthority(snapshot: BrowseSnapshot): Any = listOf(
    // Disabling a source withdraws its covers as well, even though the host could still fetch them.
    snapshot.sources.mapTo(HashSet()) { listOf(it.identityKey, it.id, it.enabled) },
    snapshot.extensions.mapTo(HashSet()) {
        listOf(it.id, it.version, it.installed, it.trusted, it.sourceIds)
    },
)

/**
 * Size- and count-bounded LRU of encoded image bytes. Each entry is charged for its key and a
 * fixed overhead as well as its payload, so many tiny responses cannot exceed the budget.
 *
 * [authority] returns a token for the current source authority; whenever it differs from the
 * token the entries were stored under, the whole cache is discarded and the generation advances.
 * [peek] never suspends: when the lock is contended it reports a miss and the caller falls back
 * to the suspending path.
 */
internal class PluginImageByteCache(
    private val maxBytes: Long,
    private val maxEntries: Int = DEFAULT_PLUGIN_IMAGE_CACHE_ENTRIES,
    private val authority: () -> Any? = { null },
) {
    private val lock = Mutex()
    // Insertion-ordered; an access re-inserts the entry so the eldest key is least recently used.
    private val entries = LinkedHashMap<String, PluginImageResult>()
    private var lastFetchId = 0L
    private var totalBytes = 0L
    private var authorityToken: Any? = null
    private var authorityChecked = false

    /** Advances whenever the cache is discarded for an authority change; guarded by [lock]. */
    private var currentGeneration: Long = 0L

    init {
        require(maxBytes >= 0) { "Plugin image cache size must not be negative" }
        require(maxEntries >= 0) { "Plugin image cache entry limit must not be negative" }
    }

    fun peek(key: String): PluginImageResult? {
        if (!lock.tryLock()) return null
        try {
            syncAuthority()
            return touch(key)
        } finally {
            lock.unlock()
        }
    }

    suspend fun get(key: String): PluginImageResult? = lock.withLock {
        syncAuthority()
        touch(key)
    }

    suspend fun generation(): Long = lock.withLock {
        syncAuthority()
        currentGeneration
    }

    /**
     * Stores [bytes] fetched under [generation] and returns them as a result with a fresh fetch id.
     * Returns null when the authority changed since, in which case the bytes must not be used.
     * An entry too large to cache is still returned, just not retained.
     */
    suspend fun put(key: String, bytes: ByteArray, generation: Long): PluginImageResult? = lock.withLock {
        syncAuthority()
        if (generation != currentGeneration) return@withLock null
        val result = PluginImageResult(bytes, generation, ++lastFetchId)
        val cost = entryCost(key, bytes)
        // A single oversized image (or key) would otherwise flush most of the visible covers.
        if (key.length > MAX_KEY_LENGTH || cost > maxBytes / 4 || maxEntries == 0) return@withLock result
        entries.remove(key)?.let { totalBytes -= entryCost(key, it.bytes) }
        entries[key] = result
        totalBytes += cost
        val eldest = entries.entries.iterator()
        while ((totalBytes > maxBytes || entries.size > maxEntries) && eldest.hasNext()) {
            val evicted = eldest.next()
            totalBytes -= entryCost(evicted.key, evicted.value.bytes)
            eldest.remove()
        }
        result
    }

    suspend fun remove(key: String) {
        lock.withLock { entries.remove(key)?.let { totalBytes -= entryCost(key, it.bytes) } }
    }

    suspend fun size(): Long = lock.withLock { totalBytes }

    suspend fun count(): Int = lock.withLock { entries.size }

    private fun syncAuthority() {
        val token = authority()
        if (authorityChecked && token == authorityToken) return
        if (authorityChecked) {
            entries.clear()
            totalBytes = 0
            currentGeneration++
        }
        authorityToken = token
        authorityChecked = true
    }

    private fun touch(key: String): PluginImageResult? {
        val result = entries.remove(key) ?: return null
        entries[key] = result
        return result
    }

    private fun entryCost(key: String, bytes: ByteArray): Long =
        bytes.size.toLong() + key.length.toLong() * 2 + ENTRY_OVERHEAD_BYTES

    private companion object {
        const val ENTRY_OVERHEAD_BYTES = 96L
        const val MAX_KEY_LENGTH = 4_096
    }
}

/** Bounds decoded cover bytes and sockets retained by a fast-scrolling catalogue. */
internal const val DEFAULT_PLUGIN_IMAGE_CONCURRENCY: Int = 6

/** Encoded cover bytes kept in memory (roughly several hundred typical covers). */
internal const val DEFAULT_PLUGIN_IMAGE_CACHE_BYTES: Long = 32L * 1_024 * 1_024

/** Upper bound on retained covers regardless of how small each response is. */
internal const val DEFAULT_PLUGIN_IMAGE_CACHE_ENTRIES: Int = 512

private fun isRemoteImageUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

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
public fun mobileSecurityCapabilities(
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

fun interface ReaderVolumeKeyEventSink {
    /** Returns true when the currently mounted reader accepted the physical press. */
    fun dispatch(event: ReaderVolumeKeyEvent): Boolean
}

/**
 * Routes every platform volume-button event to exactly one mounted reader.
 *
 * Compose can temporarily retain an outgoing destination while the next reader is already
 * mounted. A broadcast flow lets both destinations react to the same key press. Registrations are
 * therefore ordered: the newest reader owns input, disposing an older registration cannot clear
 * the newer owner, and disposing the newer owner cannot reactivate an outgoing reader.
 */
class ReaderVolumeKeyRouter : ReaderVolumeKeyEventSink {
    private class Owner(
        val handler: (ReaderVolumeKeyEvent) -> Boolean,
    )

    class Registration internal constructor(
        private val unregisterAction: () -> Unit,
    ) {
        private var registered = true

        fun unregister() {
            if (!registered) return
            registered = false
            unregisterAction()
        }
    }

    private var activeOwner: Owner? = null
    fun register(handler: (ReaderVolumeKeyEvent) -> Boolean): Registration {
        val owner = Owner(handler)
        activeOwner = owner
        return Registration {
            if (activeOwner === owner) activeOwner = null
        }
    }

    override fun dispatch(event: ReaderVolumeKeyEvent): Boolean {
        val owner = activeOwner ?: return false
        return owner.handler(event)
    }
}

/** Stable router target that follows the latest state of one reader chapter or extension unit. */
internal class ReaderVolumeKeyHandlerSlot {
    private var handler: (ReaderVolumeKeyEvent) -> Boolean = { false }

    fun update(handler: (ReaderVolumeKeyEvent) -> Boolean) {
        this.handler = handler
    }

    fun dispatch(event: ReaderVolumeKeyEvent): Boolean = handler(event)
}

/** De-duplicates a native DOWN/repeat/UP sequence into one logical reader action. */
public class ReaderVolumeKeyPressTracker {
    private val heldKeys = mutableSetOf<ReaderVolumeKeyEvent>()

    fun shouldDispatchDown(event: ReaderVolumeKeyEvent, repeatCount: Int): Boolean {
        if (repeatCount != 0) return false
        return heldKeys.add(event)
    }

    fun release(event: ReaderVolumeKeyEvent) {
        heldKeys.remove(event)
    }

    fun clear() {
        heldKeys.clear()
    }
}

/** Rejects callbacks emitted by a reader surface after its immutable session was replaced. */
internal fun readerProgressSessionIsActive(
    activeSession: Any?,
    callbackSession: Any,
    transitionInFlight: Boolean,
): Boolean = activeSession === callbackSession && !transitionInFlight

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

/**
 * iOS applies a real system volume change unless this returns true. Consume the press whenever
 * a reader is open and volume-key paging is live, even if the current page cannot move.
 */
fun readerVolumeKeyShouldBeConsumed(
    readerOpen: Boolean,
    volumeKeysEnabled: Boolean,
    monitoringEnabled: Boolean,
): Boolean = readerOpen && volumeKeysEnabled && monitoringEnabled

internal fun readerMayCrossChapterBoundary(
    proseReader: Boolean,
    pageCountMeasured: Boolean,
    interactionBlocked: Boolean,
): Boolean = !interactionBlocked && (!proseReader || pageCountMeasured)

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
