package dev.shinsou.kmp.ui.core

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What [PluginImageLoader] needs of the source catalogue (0008): the source authority and the
 * host's image fetch. The shell supplies it from the browse catalogue, so the design system
 * knows no plugin type and a plugin ABI change does not recompile it.
 */
interface PluginImageSource {
    /**
     * A token for the current source authority (the installs, removals, updates, trust and
     * enabled state of the sources): equal tokens mean the same sources may still serve the same
     * images. Read on every cache access, so it must be cheap.
     */
    fun currentAuthority(): Any?

    /** [currentAuthority] as it changes: emits only when an install, removal, update or trust change happens. */
    val authority: Flow<Any?>

    /**
     * Fetches a plugin-owned image through the host content plane: bounded bytes only, or null.
     * The UI image loaders never receive a plugin-controlled remote URL.
     */
    suspend fun loadThumbnail(sourceId: Long, url: String, headers: Map<String, String>): ByteArray?

    suspend fun loadThumbnail(sourceKey: SourceKey, url: String, headers: Map<String, String>): ByteArray?
}

/**
 * Host content-plane bridge used by image composables. Plugin-controlled URLs are classified by
 * their exact source before Coil is allowed to see a model; a failed load stays a placeholder.
 *
 * Successfully fetched cover bytes are retained in a bounded LRU cache so a cover that scrolls
 * out of a lazy list and back again is neither re-downloaded nor shown as a placeholder. The cache
 * is bound to the current source authority ([PluginImageSource.currentAuthority]): any install,
 * removal, update or trust change discards every entry, so a revoked source can no longer supply cached bytes.
 */
class PluginImageLoader internal constructor(
    private val source: PluginImageSource?,
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

    internal val cache = PluginImageByteCache(maxCachedBytes, maxCachedEntries) { source?.currentAuthority() }

    /**
     * Source authority as seen by image composables. It emits only when an install, removal,
     * update or trust change happens, so a mounted cover can drop bytes fetched under the old one.
     */
    val authority: Flow<Any?> = source?.authority ?: flowOf(null)

    fun currentAuthority(): Any? = source?.currentAuthority()

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
                    source?.loadThumbnail(sourceId, value, headers)
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
                source?.loadThumbnail(sourceKey, value, headers)
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

        /** C1 (0007): the shell's loader for [source], with the default limits (the constructor's tuning stays internal). */
        fun forSource(source: PluginImageSource?): PluginImageLoader = PluginImageLoader(source)
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
