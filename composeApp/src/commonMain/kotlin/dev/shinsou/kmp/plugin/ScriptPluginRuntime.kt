package dev.shinsou.kmp.plugin

public fun interface PluginLogger {
    public fun log(pluginId: String, message: String)

    public companion object {
        public val None: PluginLogger = PluginLogger { _, _ -> }
    }
}

/**
 * Non-blocking host callback exposed to JavaScript sources as `bridge.requestLogin(...)`.
 *
 * A JavaScript invocation owns one runtime worker, so this callback must only enqueue UI work and
 * return immediately. Waiting for credentials here would deadlock when the login dialog calls the
 * same runtime's `login` function.
 */
public fun interface PluginLoginRequester {
    public fun request(sourceId: Long, sourceName: String, reason: String?): Boolean

    public companion object {
        public val None: PluginLoginRequester = PluginLoginRequester { _, _, _ -> false }
    }
}

public data class ScriptPluginEnvironment(
    val network: PluginNetworkClient,
    val storage: PluginStorage,
    val logger: PluginLogger = PluginLogger.None,
    val loginRequester: PluginLoginRequester = PluginLoginRequester.None,
)

/** Injectable boundary implemented by Rhino on JVM and JavaScriptCore on Apple targets. */
public interface ScriptPluginRuntime : CatalogueSource, LoginSource, ConfigurableSource {
    public val pluginId: String
    public val recentLogs: List<String>
    public suspend fun close()
}

public fun interface ScriptPluginRuntimeFactory {
    public suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime
}

public class ScriptRuntimeUnavailableException(message: String) : IllegalStateException(message)

/** Safe fallback for iOS hosts that have not injected their JavaScriptCore factory yet. */
public object NoopScriptPluginRuntimeFactory : ScriptPluginRuntimeFactory {
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = NoopScriptPluginRuntime(manifest)
}

private class NoopScriptPluginRuntime(manifest: PluginManifest) : ScriptPluginRuntime {
    private val source = manifest.sources?.firstOrNull()
    override val pluginId: String = manifest.id
    override val id: Long = source?.id ?: stableSourceId(manifest.id)
    override val name: String = source?.name ?: manifest.name
    override val lang: String = source?.lang ?: manifest.lang
    override val baseUrl: String = source?.baseUrl.orEmpty()
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = emptyPage()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = emptyPage()
    override suspend fun getLatestUpdates(page: Int): MangasPage = emptyPage()
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    override suspend fun login(username: String, password: String): Boolean = false
    override suspend fun logout() = Unit
    override suspend fun close() = Unit

    private fun emptyPage(): MangasPage = MangasPage(emptyList(), false)
}

internal fun stableSourceId(value: String): Long {
    // FNV-1a, deterministic unlike Swift/Kotlin process-randomized hashCode fallbacks.
    var hash = -0x340d631b7bdddcdbL
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash
}
