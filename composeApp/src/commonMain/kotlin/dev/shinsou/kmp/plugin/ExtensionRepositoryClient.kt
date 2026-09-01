package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryEntry
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositorySource
import kotlin.time.Clock

public sealed class RepositoryIndex {
    public data class Plugins(val entries: List<PluginIndexEntry>) : RepositoryIndex()
    public data class Legacy(val entries: List<LegacyExtensionIndexEntry>) : RepositoryIndex()
    public data class Combined(
        val plugins: List<PluginIndexEntry>,
        val shuyue: List<dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryEntry>,
    ) : RepositoryIndex()
}

public sealed class ExtensionRepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class InvalidUrl(value: String) : ExtensionRepositoryException("Invalid repository URL or path: $value")
    public class Http(public val status: Int, url: String) :
        ExtensionRepositoryException("HTTP $status while fetching $url")
    public class InvalidDocument(url: String, cause: Throwable) :
        ExtensionRepositoryException("Invalid repository document at $url", cause)
}

public class ExtensionRepositoryClient(
    private val client: HttpClient,
    private val json: Json = PluginJson,
    private val cacheToken: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    public suspend fun fetchRepository(baseUrl: String): ExtensionRepository {
        val normalized = normalizeBaseUrl(baseUrl)
        val url = resolve(normalized, "repo.json", cacheBust = false)
        val document = decode<RepositoryDocument>(url, requestBytes(url))
        return ExtensionRepository(
            baseUrl = normalized,
            name = document.meta.name,
            shortName = document.meta.shortName,
            website = document.meta.website ?: normalized,
            signingKeyFingerprint = document.meta.signingKeyFingerprint.orEmpty(),
        )
    }

    public suspend fun fetchPluginIndex(baseUrl: String): List<PluginIndexEntry> {
        val url = resolve(normalizeBaseUrl(baseUrl), "index.json", cacheBust = true)
        val bytes = requestBytes(url)
        val root = parseRoot(url, bytes)
        if (root is JsonObject && isUnifiedEnvelope(root)) {
            val document = decode<UnifiedRepositoryDocument>(url, bytes)
            return document.shinsou + document.legacy
        }
        if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == "shinsou-extension-v2") {
            return decodeV2Index(url, root).plugins
        }
        return decode(url, bytes, ListSerializer(PluginIndexEntry.serializer()))
    }

    public suspend fun fetchLegacyIndex(baseUrl: String): List<LegacyExtensionIndexEntry> {
        val url = resolve(normalizeBaseUrl(baseUrl), "index.min.json", cacheBust = true)
        return decode(url, requestBytes(url), ListSerializer(LegacyExtensionIndexEntry.serializer()))
    }

    public suspend fun fetchIndex(baseUrl: String): RepositoryIndex =
        run {
            val normalized = normalizeBaseUrl(baseUrl)
            val url = resolve(normalized, "index.json", cacheBust = true)
            try {
                val bytes = requestBytes(url)
                val root = parseRoot(url, bytes)
                if (root is JsonObject && isUnifiedEnvelope(root)) {
                    val document = decode<UnifiedRepositoryDocument>(url, bytes)
                    return@run RepositoryIndex.Combined(
                        plugins = document.shinsou + document.legacy,
                        shuyue = document.shuyue,
                    )
                }
                if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == "shinsou-extension-v2") {
                    return@run decodeV2Index(url, root)
                }
                RepositoryIndex.Plugins(decode(url, bytes, ListSerializer(PluginIndexEntry.serializer())))
            } catch (pluginFailure: Throwable) {
                try {
                    val legacyUrl = resolve(normalized, "index.min.json", cacheBust = true)
                    RepositoryIndex.Legacy(
                        decode(legacyUrl, requestBytes(legacyUrl), ListSerializer(LegacyExtensionIndexEntry.serializer())),
                    )
                } catch (legacyFailure: Throwable) {
                    legacyFailure.addSuppressed(pluginFailure)
                    throw legacyFailure
                }
            }
        }

    /** Decodes the host-consumable V2 repository without projecting 64-bit ids through Double. */
    private fun decodeV2Index(url: String, root: JsonObject): RepositoryIndex.Combined = try {
        require(root["contractVersion"]?.jsonPrimitive?.intOrNull == 2) { "Unsupported V2 contract" }
        val packages = root["packages"] as? JsonArray ?: error("V2 packages must be an array")
        val shinsou = mutableListOf<PluginIndexEntry>()
        val shuyue = mutableListOf<ShuYueRepositoryEntry>()
        packages.forEach { element ->
            val pkg = element.jsonObject
            val contract = pkg["contract"]?.jsonPrimitive?.contentOrNull
            if (contract == "shuyue") {
                val capabilityIds = pkg.stringSet("capabilities")
                val eventObject = pkg["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
                require(eventObject.requiredString("protocol") == "dev.shinsou.system")
                val eventDeclaration = PluginSystemEventDeclaration(
                    minVersion = eventObject["minVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing minVersion"),
                    maxVersion = eventObject["maxVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing maxVersion"),
                    required = eventObject.stringSet("required"),
                    optional = eventObject.stringSet("optional"),
                )
                val requested = pkg.stringSet("requestedHostPermissions").mapTo(linkedSetOf()) {
                    PluginHostPermission.valueOf(it)
                }
                shuyue += ShuYueRepositoryEntry(
                    id = pkg.requiredString("id"),
                    name = pkg.requiredString("name"),
                    version = pkg.requiredString("version"),
                    versionCode = pkg["versionCode"]?.jsonPrimitive?.intOrNull ?: error("Missing versionCode"),
                    lang = pkg.requiredString("lang"),
                    nsfw = if (pkg["nsfw"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0,
                    scriptUrl = pkg.requiredString("scriptUrl"),
                    sources = (pkg["sources"] as? JsonArray).orEmpty().map { sourceElement ->
                        val source = sourceElement.jsonObject
                        ShuYueRepositorySource(
                            id = source.requiredString("sourceId"),
                            name = source.requiredString("name"),
                            lang = source.requiredString("lang"),
                            baseUrl = source.requiredString("baseUrl"),
                            supportsLogin = "LOGIN" in capabilityIds,
                            supportsLatest = "LATEST" in capabilityIds,
                            supportsFavorites = "FAVORITE" in capabilityIds,
                            contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                        )
                    },
                    contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                    contract = contract,
                    sha256 = pkg.requiredString("sha256"),
                    sidecarUrl = pkg.requiredString("sidecarUrl"),
                    systemEvents = eventDeclaration,
                    requestedHostPermissions = requested,
                    installable = pkg["installable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    referenceOnly = pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    legacyCompatibilityOnly = pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                return@forEach
            }
            // Reference-only/non-installable Shinsou packages may intentionally use opaque
            // source IDs (for example, a login capability sample). They are not executable
            // legacy-adapter entries and must not make the installable half of a mixed V2 index
            // fail while coercing source IDs to Long. Existing installed packages are still
            // projected from packageStore by PluginManager.refresh().
            if (pkg["installable"]?.jsonPrimitive?.booleanOrNull == false ||
                pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull == true ||
                pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                return@forEach
            }
            val events = pkg["systemEvents"]?.jsonObject?.let { eventObject ->
                require(eventObject.requiredString("protocol") == "dev.shinsou.system")
                PluginSystemEventDeclaration(
                    minVersion = eventObject["minVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing minVersion"),
                    maxVersion = eventObject["maxVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing maxVersion"),
                    required = eventObject.stringSet("required"),
                    optional = eventObject.stringSet("optional"),
                )
            }
            val requested = (pkg["requestedHostPermissions"] as? JsonArray).orEmpty().mapTo(linkedSetOf()) {
                PluginHostPermission.valueOf(it.jsonPrimitive.content)
            }
            shinsou += PluginIndexEntry(
                id = pkg.requiredString("id"),
                name = pkg.requiredString("name"),
                version = pkg.requiredString("version"),
                versionCode = pkg["versionCode"]?.jsonPrimitive?.intOrNull ?: error("Missing versionCode"),
                lang = pkg.requiredString("lang"),
                nsfw = if (pkg["nsfw"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0,
                scriptUrl = pkg.requiredString("scriptUrl"),
                description = pkg["description"]?.jsonPrimitive?.contentOrNull,
                sources = (pkg["sources"] as? JsonArray).orEmpty().map { sourceElement ->
                    val source = sourceElement.jsonObject
                    val rawId = source.requiredString("sourceId")
                    SourceIndexEntry(
                        name = source.requiredString("name"),
                        lang = source.requiredString("lang"),
                        id = rawId.toLongOrNull() ?: error("Invalid lossless source id '$rawId'"),
                        baseUrl = source["baseUrl"]?.jsonPrimitive?.contentOrNull,
                        contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                        browserSessionOrigins = source.stringSet("browserSessionOrigins"),
                    )
                },
                sha256 = pkg.requiredString("sha256"),
                byteSize = pkg["byteSize"]?.jsonPrimitive?.intOrNull ?: error("Missing byteSize"),
                contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                contract = contract,
                sidecarUrl = pkg.requiredString("sidecarUrl"),
                systemEvents = events,
                requestedHostPermissions = requested,
                installable = pkg["installable"]?.jsonPrimitive?.booleanOrNull ?: true,
                referenceOnly = pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                legacyCompatibilityOnly = pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        RepositoryIndex.Combined(shinsou, shuyue)
    } catch (error: Throwable) {
        throw ExtensionRepositoryException.InvalidDocument(url, error)
    }

    public suspend fun downloadPluginScript(baseUrl: String, scriptUrl: String): ByteArray {
        validateRelativePath(scriptUrl)
        val url = resolve(normalizeBaseUrl(baseUrl), scriptUrl, cacheBust = true)
        return requestBytes(url)
    }

    /**
     * Reads and cross-checks a V2 sidecar before artifact download. Repository declarations are
     * review input only; this method never turns requested permissions into an execution grant.
     */
    public suspend fun verifyPluginV2Sidecar(baseUrl: String, entry: PluginIndexEntry) {
        val sidecarPath = entry.sidecarUrl ?: return
        validateRelativePath(sidecarPath)
        val url = resolve(normalizeBaseUrl(baseUrl), sidecarPath, cacheBust = true)
        val sidecar = parseRoot(url, requestBytes(url)) as? JsonObject
            ?: throw ExtensionRepositoryException.InvalidDocument(url, IllegalArgumentException("V2 sidecar must be an object"))
        try {
            require(sidecar.requiredString("format") == "shinsou-extension-sidecar-v2")
            require(sidecar["contractVersion"]?.jsonPrimitive?.intOrNull == 2)
            require(sidecar.requiredString("packageId") == entry.id)
            require(sidecar.requiredString("version") == entry.version)
            require(sidecar["versionCode"]?.jsonPrimitive?.intOrNull == entry.versionCode)
            val artifact = sidecar["artifact"]?.jsonObject ?: error("Missing artifact")
            require(artifact.requiredString("scriptUrl") == entry.scriptUrl)
            require(artifact.requiredString("sha256") == entry.sha256)
            require(artifact["byteSize"]?.jsonPrimitive?.intOrNull == entry.byteSize)
            val content = sidecar["content"]?.jsonObject ?: error("Missing content")
            require(content["contractVersion"]?.jsonPrimitive?.intOrNull == 2)
            require(content.requiredString("type") == entry.contentType)
            val sidecarSources = sidecar["sources"] as? JsonArray ?: error("Missing sources")
            require(sidecarSources.size == entry.sources.orEmpty().size)
            entry.sources.orEmpty().forEach { expected ->
                val match = sidecarSources.map { it.jsonObject }.single { source ->
                    source.requiredString("sourceId") == expected.id.toString()
                }
                val sourceKey = match["sourceKey"]?.jsonObject ?: error("Missing sourceKey")
                require(sourceKey.requiredString("packageId") == entry.id)
                require(sourceKey.requiredString("sourceId") == expected.id.toString())
                require(match.stringSet("browserSessionOrigins") == expected.browserSessionOrigins)
            }
            val events = sidecar["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
            require(events.requiredString("protocol") == "dev.shinsou.system")
            val declaration = PluginSystemEventDeclaration(
                minVersion = events["minVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing minVersion"),
                maxVersion = events["maxVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing maxVersion"),
                required = events.stringSet("required"),
                optional = events.stringSet("optional"),
            )
            require(declaration == entry.systemEvents)
            val requested = sidecar.stringSet("requestedHostPermissions").mapTo(linkedSetOf()) {
                PluginHostPermission.valueOf(it)
            }
            require(requested == entry.requestedHostPermissions)
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }
    }

    public fun resolveAssetUrl(baseUrl: String, relativePath: String): String {
        validateRelativePath(relativePath)
        return resolve(normalizeBaseUrl(baseUrl), relativePath, cacheBust = false)
    }

    private suspend fun requestBytes(url: String): ByteArray {
        val response: HttpResponse = client.get(url)
        if (response.status.value !in 200..299) throw ExtensionRepositoryException.Http(response.status.value, url)
        return response.body<ByteArray>()
    }

    private inline fun <reified T> decode(url: String, bytes: ByteArray): T =
        try {
            json.decodeFromString(bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun <T> decode(url: String, bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun parseRoot(url: String, bytes: ByteArray): kotlinx.serialization.json.JsonElement =
        try {
            json.parseToJsonElement(bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun isUnifiedEnvelope(root: JsonObject): Boolean =
        root["format"]?.toString()?.contains("unified", ignoreCase = true) == true ||
            root["shinsou"] is JsonArray || root["legacy"] is JsonArray || root["shuyue"] is JsonArray

    private fun resolve(baseUrl: String, path: String, cacheBust: Boolean): String {
        val resolved = "$baseUrl/${path.trimStart('/')}"
        if (!cacheBust) return resolved
        return resolved + (if ('?' in resolved) "&" else "?") + "_t=${cacheToken()}"
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw ExtensionRepositoryException.InvalidUrl(value)
        }
        return trimmed
    }

    private fun validateRelativePath(path: String) {
        val route = path.substringBefore('?').substringBefore('#')
        val segments = route.replace('\\', '/').split('/')
        if (route.isBlank() || route.startsWith('/') || "://" in route || segments.any { it == ".." }) {
            throw ExtensionRepositoryException.InvalidUrl(path)
        }
    }
}

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Missing or invalid '$key'")

private fun JsonObject.stringSet(key: String): Set<String> =
    ((this[key] as? JsonArray) ?: JsonArray(emptyList())).mapTo(linkedSetOf()) {
        it.jsonPrimitive.content
    }
