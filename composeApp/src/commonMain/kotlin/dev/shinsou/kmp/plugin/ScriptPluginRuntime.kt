package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.ScopedPluginSystemEventSink
import dev.shinsou.kmp.plugin.events.PluginSystemEventNegotiation
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventContextRegistry
import dev.shinsou.kmp.plugin.events.PluginSystemEventEnvelope
import dev.shinsou.kmp.plugin.events.PluginSystemEventKind
import dev.shinsou.kmp.plugin.events.PluginSystemEventNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val browserSessionTransport: PluginBrowserSessionTransport = PluginBrowserSessionTransport.Unavailable,
    /** Same platform resolver used by direct plugin egress; unavailable means fail closed. */
    val hostResolver: PluginHostResolver = PluginHostResolver.Unavailable,
    /** Explicit non-production escape hatch for WebViews without address pinning. */
    val allowDeveloperUnpinnedBrowserSession: Boolean = false,
    val logger: PluginLogger = PluginLogger.None,
    val loginRequester: PluginLoginRequester = PluginLoginRequester.None,
    /** Optional v1 system-event ingress shared by Rhino and JavaScriptCore adapters. */
    val systemEventSink: ScopedPluginSystemEventSink? = null,
    /** Host-injected scope; transports must never derive this from plugin JSON. */
    val boundPluginScope: BoundPluginScope? = null,
    /** Host-owned short-lived ACTIVE_CONTEXT issuer used only during visible V2 invocations. */
    val systemEventContextRegistry: PluginEventContextRegistry? = null,
    val systemEventNegotiation: PluginSystemEventNegotiation? = null,
    val systemEventDeclaration: PluginSystemEventDeclaration? = null,
    /** Host-owned execution/resource policy. Repository code cannot override these limits. */
    val executionLimits: PluginExecutionLimits = PluginExecutionLimits(),
    /**
     * Host-resolved service permissions; engines must check this before every sensitive port.
     * Direct embedders receive no capability unless they explicitly grant one.
     */
    val runtimePermissions: Set<PluginRuntimePermission> = emptySet(),
    /**
     * Host-issued, exact-byte provenance for in-process engines without an OS memory sandbox.
     * Its constructor is not part of the public API and generic repository loading clears it.
     * Any reviewed-only execution profile is carried inside this capability so a copied
     * environment cannot enable it without also passing the final exact-match check.
     */
    val inProcessScriptProvenance: InProcessScriptProvenance? = null,
)

/**
 * Unforgeable-by-repository admission for one exact evaluated script and source binding.
 *
 * A repository manifest, signature, permission grant, or user trust toggle is deliberately
 * insufficient to construct this object. The host-reviewed loader issues it only after matching
 * downloaded artifact bytes against its built-in catalogue. Engines must still call
 * [requireExactMatch] immediately before allocating an in-process runtime.
 */
public class InProcessScriptProvenance internal constructor(
    private val artifact: PluginArtifactIdentity,
    private val sourceKey: SourceKey,
    private val evaluatedScriptSha256: String,
    private val executionLimits: PluginExecutionLimits? = null,
) {
    internal fun requireExactMatch(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry?,
    ): PluginExecutionLimits? {
        require(manifest.id == artifact.packageId) { "Reviewed script package identity mismatch" }
        require(manifest.version == artifact.version) { "Reviewed script version identity mismatch" }
        require(
            (manifest.versionCode ?: PluginVerifier.versionInt(manifest.version)) == artifact.versionCode,
        ) { "Reviewed script version-code identity mismatch" }
        require(manifest.signature.trim().lowercase() == artifact.sha256) {
            "Reviewed script artifact digest identity mismatch"
        }
        require(source != null) { "Reviewed in-process script requires an exact source binding" }
        require(sourceKey.packageId == artifact.packageId) { "Reviewed source package identity mismatch" }
        require((source.canonicalSourceId ?: source.id.toString()) == sourceKey.sourceId) {
            "Reviewed source identity mismatch"
        }
        require(Sha256.hex(script.encodeToByteArray()) == evaluatedScriptSha256) {
            "Reviewed evaluated-script digest mismatch"
        }
        return executionLimits
    }

    internal companion object {
        fun reviewedArtifact(
            artifact: PluginArtifactIdentity,
            sourceKey: SourceKey,
            evaluatedScript: String,
            executionLimits: PluginExecutionLimits? = null,
        ): InProcessScriptProvenance = InProcessScriptProvenance(
            artifact = artifact,
            sourceKey = sourceKey,
            evaluatedScriptSha256 = Sha256.hex(evaluatedScript.encodeToByteArray()),
            executionLimits = executionLimits,
        )
    }
}

/** Fail-closed guard shared by both script engines before crossing a sensitive host boundary. */
internal fun ScriptPluginEnvironment.requireRuntimePermission(permission: PluginRuntimePermission) {
    require(permission in runtimePermissions) {
        "Plugin runtime lacks ${permission.name} permission"
    }
}

/** Resolves the current host-issued handle without exposing publication/unit identity. */
internal fun ScriptPluginEnvironment.currentSystemEventContext(): String? {
    val scope = boundPluginScope ?: return null
    // Once the host installs the opaque registry, a missing active invocation must stay missing;
    // falling back to the legacy scope field would re-enable a predictable long-lived reference.
    return if (systemEventContextRegistry != null) {
        systemEventContextRegistry.current(scope)
    } else {
        scope.invocationContext
    }
}

/** Compatibility shim for legacy `bridge.requestLogin`; it never grants any other capability. */
internal fun submitLegacyLoginCompatibility(
    environment: ScriptPluginEnvironment,
    supportsLogin: Boolean,
    reason: String?,
): Boolean {
    if (!supportsLogin) return false
    val sink = environment.systemEventSink ?: return false
    val scope = environment.boundPluginScope ?: return false
    val envelope = PluginSystemEventEnvelope(
        protocol = "dev.shinsou.system",
        version = 1,
        kind = PluginSystemEventKind.COMMAND,
        name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
        id = "compat-${scope.runtimeGeneration}",
        payloadVersion = 1,
        payload = buildJsonObject {
            reason?.trim()?.takeIf(String::isNotEmpty)?.let { put("fallbackMessage", it) }
        },
    )
    val receipt = runCatching {
        sink.submit(scope, PluginJson.encodeToString(PluginSystemEventEnvelope.serializer(), envelope).encodeToByteArray())
    }.getOrNull() ?: return false
    return receipt.disposition == PluginEventDisposition.ACCEPTED ||
        receipt.disposition == PluginEventDisposition.DEDUPLICATED
}

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

    /**
     * Creates the executable runtime for one exact source declared by [manifest].
     *
     * The original v1 factory surface models one package as one source.  Keeping [create] lets
     * existing embedders continue to provide that implementation, while package managers and v2
     * adapters use this exact-source entry point.  The default narrows the manifest before calling
     * the v1 factory, so even an older factory cannot silently select a different sibling source.
     */
    public suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        manifest.requireDeclaredExecutableSource(source)
        return create(script, manifest.copy(sources = listOf(source)), environment).also { runtime ->
            require(runtime.id == source.id) {
                "Plugin '${manifest.id}' runtime identity does not match requested source ${source.id}"
            }
        }
    }
}

public class ScriptRuntimeUnavailableException(message: String) : IllegalStateException(message)

/** Safe fallback for iOS hosts that have not injected their JavaScriptCore factory yet. */
public object NoopScriptPluginRuntimeFactory : ScriptPluginRuntimeFactory {
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = NoopScriptPluginRuntime(manifest, manifest.requireLegacyExecutableSource())

    override suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        manifest.requireDeclaredExecutableSource(source)
        return NoopScriptPluginRuntime(manifest, source)
    }
}

private class NoopScriptPluginRuntime(
    manifest: PluginManifest,
    source: SourceIndexEntry?,
) : ScriptPluginRuntime {
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

/** Resolves only the unambiguous v1 package shape; multi-source callers must be explicit. */
internal fun PluginManifest.requireLegacyExecutableSource(): SourceIndexEntry? {
    val declared = sources.orEmpty()
    require(declared.size <= 1) {
        "Plugin '$id' declares ${declared.size} sources; use createForSource for an exact source"
    }
    return declared.singleOrNull()
}

/** Exact declaration check shared by platform factories and lifecycle management. */
internal fun PluginManifest.requireDeclaredExecutableSource(source: SourceIndexEntry): SourceIndexEntry {
    val matches = sources.orEmpty().filter { it.id == source.id }
    require(matches.size == 1 && matches.single() == source) {
        "Source ${source.id} is not exactly declared by plugin '$id'"
    }
    return matches.single()
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
