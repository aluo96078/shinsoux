package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

public sealed class RepositoryIndex {
    public data class Plugins(val entries: List<PluginIndexEntry>) : RepositoryIndex()
    public data class Legacy(val entries: List<LegacyExtensionIndexEntry>) : RepositoryIndex()
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
        return decode(url, requestBytes(url), ListSerializer(PluginIndexEntry.serializer()))
    }

    public suspend fun fetchLegacyIndex(baseUrl: String): List<LegacyExtensionIndexEntry> {
        val url = resolve(normalizeBaseUrl(baseUrl), "index.min.json", cacheBust = true)
        return decode(url, requestBytes(url), ListSerializer(LegacyExtensionIndexEntry.serializer()))
    }

    public suspend fun fetchIndex(baseUrl: String): RepositoryIndex =
        try {
            RepositoryIndex.Plugins(fetchPluginIndex(baseUrl))
        } catch (pluginFailure: Throwable) {
            try {
                RepositoryIndex.Legacy(fetchLegacyIndex(baseUrl))
            } catch (legacyFailure: Throwable) {
                legacyFailure.addSuppressed(pluginFailure)
                throw legacyFailure
            }
        }

    public suspend fun downloadPluginScript(baseUrl: String, scriptUrl: String): ByteArray {
        validateRelativePath(scriptUrl)
        val url = resolve(normalizeBaseUrl(baseUrl), scriptUrl, cacheBust = true)
        return requestBytes(url)
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
