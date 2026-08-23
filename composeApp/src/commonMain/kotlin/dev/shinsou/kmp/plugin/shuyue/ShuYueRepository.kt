package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A ShuYue repository can be supplied as an exact index URL or as a repository directory. */
public sealed interface ShuYueRepositoryLocation {
    public val value: String

    public data class IndexUrl(override val value: String) : ShuYueRepositoryLocation

    public data class BaseUrl(override val value: String) : ShuYueRepositoryLocation
}

/**
 * A canonical HTTP(S) origin. Origins are intentionally distinct from runtime source metadata:
 * an origin in this type is only an artifact-fetch policy value.
 */
public class ShuYueOrigin private constructor(
    public val scheme: String,
    public val authority: ShuYueAuthority,
) {
    public val value: String
        get() = "$scheme://${authority.value}"

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is ShuYueOrigin && scheme == other.scheme && authority == other.authority

    override fun hashCode(): Int = 31 * scheme.hashCode() + authority.hashCode()

    public companion object {
        /** Parses an origin-only string (no path, query, fragment, or userinfo). */
        public fun parse(value: String): ShuYueOrigin =
            ShuYueUrlParser.parseAbsolute(value, "origin", forbidQuery = true, originOnly = true).origin

        internal fun fromCanonical(scheme: String, authority: ShuYueAuthority): ShuYueOrigin =
            ShuYueOrigin(scheme, authority)
    }
}

/** A canonical host and optional non-default TCP port. */
public class ShuYueAuthority private constructor(
    public val host: String,
    public val port: Int?,
    public val isIpv6: Boolean,
) {
    public val value: String
        get() = (if (isIpv6) "[$host]" else host) + (port?.let { ":$it" } ?: "")

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is ShuYueAuthority &&
        host == other.host && port == other.port && isIpv6 == other.isIpv6

    override fun hashCode(): Int = ((31 * host.hashCode()) + (port ?: 0)) * 31 + isIpv6.hashCode()

    public companion object {
        /** Parses an authority-only string without scheme, path, query, or userinfo. */
        public fun parse(value: String): ShuYueAuthority = ShuYueUrlParser.parseAuthorityPublic(value)

        internal fun fromCanonical(host: String, port: Int?, isIpv6: Boolean): ShuYueAuthority =
            ShuYueAuthority(host, port, isIpv6)
    }
}

/**
 * Local-only artifact policy used by the development repository path. This deliberately accepts
 * only loopback, RFC1918/link-local addresses and mDNS `.local` names; arbitrary public HTTP
 * origins never bypass the configured reviewed-origin allowlist.
 */
private fun ShuYueOrigin.isLocalNetworkOrigin(): Boolean {
    val host = authority.host.lowercase()
    if (host == "localhost" || host.endsWith(".local")) return true
    if (authority.isIpv6) {
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
    }
    val octets = host.split('.').map(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val first = octets[0]!!
    val second = octets[1]!!
    return first == 0 || first == 10 ||
        first == 127 ||
        first == 169 && second == 254 ||
        first == 172 && second in 16..31 ||
        first == 192 && second == 168
}

/** Bounded input policy for repository artifacts and their JSON metadata. */
public data class ShuYueRepositoryLimits(
    val maxIndexBytes: Long = DEFAULT_MAX_INDEX_BYTES,
    val maxScriptBytes: Long = DEFAULT_MAX_SCRIPT_BYTES,
    val maxPackages: Int = DEFAULT_MAX_PACKAGES,
    val maxSourcesPerPackage: Int = DEFAULT_MAX_SOURCES_PER_PACKAGE,
    val maxTotalSources: Int = DEFAULT_MAX_TOTAL_SOURCES,
    /** Explicit allowlist for repository index/script artifacts only. */
    val allowedArtifactOrigins: Set<String> = emptySet(),
    /** Maximum number of redirect targets a transport may follow for one artifact. */
    val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    val maxJsonNesting: Int = DEFAULT_MAX_JSON_NESTING,
    /** Caps keys retained by the preflight scanner for any one JSON object. */
    val maxJsonObjectMembers: Int = DEFAULT_MAX_JSON_OBJECT_MEMBERS,
    /** Caps elements scanned in generic/unknown JSON arrays. */
    val maxJsonArrayElements: Int = DEFAULT_MAX_JSON_ARRAY_ELEMENTS,
    val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    val maxPackageIdBytes: Int = DEFAULT_MAX_ID_BYTES,
    val maxPackageNameBytes: Int = DEFAULT_MAX_NAME_BYTES,
    val maxPackageVersionBytes: Int = DEFAULT_MAX_VERSION_BYTES,
    val maxPackageLangBytes: Int = DEFAULT_MAX_LANG_BYTES,
    val maxScriptUrlBytes: Int = DEFAULT_MAX_URL_BYTES,
    val maxDescriptionBytes: Int = DEFAULT_MAX_DESCRIPTION_BYTES,
    val maxSourceIdBytes: Int = DEFAULT_MAX_ID_BYTES,
    val maxSourceNameBytes: Int = DEFAULT_MAX_NAME_BYTES,
    val maxSourceLangBytes: Int = DEFAULT_MAX_LANG_BYTES,
    val maxSourceBaseUrlBytes: Int = DEFAULT_MAX_URL_BYTES,
    /**
     * Compatibility alias for the original loader. It is an artifact allowlist, never a
     * runtime source allowlist; source.baseUrl values are merely validated metadata.
     */
    @Deprecated("Use allowedArtifactOrigins; this never applies to source.baseUrl")
    val allowedOrigins: Set<String> = emptySet(),
    /**
     * Allow an explicitly entered loopback/private-network repository origin in addition to
     * [allowedArtifactOrigins]. This is intended for the local/LAN development server; public
     * origins remain governed by the explicit allowlist.
     */
    val allowLocalArtifactOrigins: Boolean = false,
    /** Sidecars are metadata-only and have a smaller independent response bound. */
    val maxSidecarBytes: Long = DEFAULT_MAX_SIDECAR_BYTES,
) {
    init {
        require(maxIndexBytes in 1..MAX_HARD_INDEX_BYTES) { "Invalid ShuYue index byte limit" }
        require(maxScriptBytes in 1..MAX_HARD_SCRIPT_BYTES) { "Invalid ShuYue script byte limit" }
        require(maxSidecarBytes in 1..MAX_HARD_SIDECAR_BYTES) { "Invalid ShuYue sidecar byte limit" }
        require(maxPackages in 1..MAX_HARD_PACKAGES) { "Invalid ShuYue package count limit" }
        require(maxSourcesPerPackage in 1..MAX_HARD_SOURCES_PER_PACKAGE) {
            "Invalid ShuYue source count limit"
        }
        require(maxTotalSources in 1..MAX_HARD_TOTAL_SOURCES) { "Invalid ShuYue source count limit" }
        require(maxRedirects in 0..MAX_HARD_REDIRECTS) { "Invalid ShuYue redirect limit" }
        require(maxJsonNesting in 1..MAX_HARD_JSON_NESTING) { "Invalid ShuYue JSON nesting limit" }
        require(maxJsonObjectMembers in 1..MAX_HARD_JSON_OBJECT_MEMBERS) {
            "Invalid ShuYue JSON object member limit"
        }
        require(maxJsonArrayElements in 1..MAX_HARD_JSON_ARRAY_ELEMENTS) {
            "Invalid ShuYue JSON array element limit"
        }
        require(maxStringBytes in 1..MAX_HARD_STRING_BYTES) { "Invalid ShuYue JSON string limit" }
        require(maxPackageIdBytes in 1..maxStringBytes) { "Invalid ShuYue package id limit" }
        require(maxPackageNameBytes in 1..maxStringBytes) { "Invalid ShuYue package name limit" }
        require(maxPackageVersionBytes in 1..maxStringBytes) { "Invalid ShuYue package version limit" }
        require(maxPackageLangBytes in 1..maxStringBytes) { "Invalid ShuYue package language limit" }
        require(maxScriptUrlBytes in 1..maxStringBytes) { "Invalid ShuYue script URL limit" }
        require(maxDescriptionBytes in 1..maxStringBytes) { "Invalid ShuYue description limit" }
        require(maxSourceIdBytes in 1..maxStringBytes) { "Invalid ShuYue source id limit" }
        require(maxSourceNameBytes in 1..maxStringBytes) { "Invalid ShuYue source name limit" }
        require(maxSourceLangBytes in 1..maxStringBytes) { "Invalid ShuYue source language limit" }
        require(maxSourceBaseUrlBytes in 1..maxStringBytes) { "Invalid ShuYue source URL limit" }
        require(artifactOriginStrings.size <= MAX_ALLOWED_ORIGINS) { "Too many ShuYue allowed origins" }
    }

    /** Canonicalized artifact origins; source origins never enter this set. */
    internal val artifactOriginStrings: Set<String>
        get() = allowedArtifactOrigins + allowedOrigins

    public companion object {
        public const val DEFAULT_MAX_INDEX_BYTES: Long = 2L * 1024L * 1024L
        public const val DEFAULT_MAX_SCRIPT_BYTES: Long = 8L * 1024L * 1024L
        public const val DEFAULT_MAX_SIDECAR_BYTES: Long = 512L * 1024L
        public const val DEFAULT_MAX_PACKAGES: Int = 256
        public const val DEFAULT_MAX_SOURCES_PER_PACKAGE: Int = 256
        public const val DEFAULT_MAX_TOTAL_SOURCES: Int = 2_048
        public const val DEFAULT_MAX_REDIRECTS: Int = 5
        public const val DEFAULT_MAX_JSON_NESTING: Int = 32
        public const val DEFAULT_MAX_JSON_OBJECT_MEMBERS: Int = 64
        public const val DEFAULT_MAX_JSON_ARRAY_ELEMENTS: Int = 4_096
        public const val DEFAULT_MAX_STRING_BYTES: Int = 64 * 1024

        private const val DEFAULT_MAX_ID_BYTES: Int = 4 * 1024
        private const val DEFAULT_MAX_NAME_BYTES: Int = 16 * 1024
        private const val DEFAULT_MAX_VERSION_BYTES: Int = 2 * 1024
        private const val DEFAULT_MAX_LANG_BYTES: Int = 256
        private const val DEFAULT_MAX_URL_BYTES: Int = 8 * 1024
        private const val DEFAULT_MAX_DESCRIPTION_BYTES: Int = 32 * 1024

        private const val MAX_HARD_INDEX_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_HARD_SCRIPT_BYTES: Long = 64L * 1024L * 1024L
        private const val MAX_HARD_SIDECAR_BYTES: Long = 8L * 1024L * 1024L
        private const val MAX_HARD_PACKAGES: Int = 4_096
        private const val MAX_HARD_SOURCES_PER_PACKAGE: Int = 4_096
        private const val MAX_HARD_TOTAL_SOURCES: Int = 16_384
        private const val MAX_HARD_REDIRECTS: Int = 32
        private const val MAX_HARD_JSON_NESTING: Int = 128
        private const val MAX_HARD_JSON_OBJECT_MEMBERS: Int = 4_096
        private const val MAX_HARD_JSON_ARRAY_ELEMENTS: Int = 16_384
        private const val MAX_HARD_STRING_BYTES: Int = 4 * 1024 * 1024
        private const val MAX_ALLOWED_ORIGINS: Int = 256
    }
}

/** A transport response retains every redirect target and the required final URL. */
public data class ShuYueRepositoryResponse(
    val status: Int,
    val body: ByteArray,
    /** Required final URL returned by the transport, even when no redirect occurred. */
    val finalUrl: String,
    /**
     * Every redirect target in order, excluding the initial request. When non-empty, the last
     * target must be exactly [finalUrl].
     */
    val redirectChain: List<String> = emptyList(),
)

/**
 * Host transport contract. Implementations must enforce [allowedArtifactOrigins],
 * [maxRedirects], and [maxBytes] while making the request. Returning a response after an
 * unbounded read or an unauthorized hop is a contract violation; the loader repeats the
 * checks defensively for injectable/test transports.
 */
public data class ShuYueRepositoryRequest(
    val url: String,
    val maxBytes: Long,
    val allowedArtifactOrigins: Set<ShuYueOrigin> = emptySet(),
    val maxRedirects: Int = ShuYueRepositoryLimits.DEFAULT_MAX_REDIRECTS,
)

/** Injectable host transport. Production adapters must enforce every request bound. */
public fun interface ShuYueRepositoryTransport {
    public suspend fun execute(request: ShuYueRepositoryRequest): ShuYueRepositoryResponse
}

/** Capability hints are advisory metadata; the host still controls which operations are permitted. */
@Serializable
public data class ShuYueCapabilityHints(
    val supportsLogin: Boolean = false,
    val supportsLatest: Boolean = false,
    val supportsFavorites: Boolean = false,
)

/**
 * Old Shinsou plugin indexes encode source IDs both as JSON integers and as quoted strings.
 * Source IDs are opaque to ShuYue, so retain the original integer token instead of coercing it
 * through a platform-sized number (which could lose precision for a 64-bit ID).
 */
public object ShuYueStringOrIntegerIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ShuYueStringOrIntegerId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("ShuYue source id must be a string or integer")
        if (primitive.isString) return primitive.content
        val raw = primitive.content
        if (!INTEGER_TOKEN.matches(raw)) {
            throw SerializationException("ShuYue source id '$raw' is not an integer")
        }
        return raw
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    private val INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
}

/** The legacy index intentionally keeps all IDs as opaque strings. */
@Serializable
public data class ShuYueRepositorySource(
    @Serializable(with = ShuYueStringOrIntegerIdSerializer::class)
    val id: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val supportsLogin: Boolean = false,
    val supportsLatest: Boolean = false,
    val supportsFavorites: Boolean = false,
    val type: String? = null,
    val contentType: String? = null,
) {
    public val capabilities: ShuYueCapabilityHints
        get() = ShuYueCapabilityHints(supportsLogin, supportsLatest, supportsFavorites)

    /** Strictly parsed runtime origin; this value is metadata, not an artifact-fetch grant. */
    public val runtimeOrigin: ShuYueOrigin
        get() = ShuYueUrlParser.parseAbsolute(baseUrl, "source base URL", forbidQuery = true).origin

    public fun sourceKey(packageId: String): SourceKey =
        SourceKey(
            contractVersion = SourceKey.CURRENT_CONTRACT_VERSION,
            packageId = packageId,
            sourceId = id,
        )
}

/** Strictly decoded package entry. [scriptUrl] remains the exact query-bearing index value. */
@Serializable
public data class ShuYueRepositoryEntry(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val lang: String,
    val nsfw: Int = 0,
    val scriptUrl: String,
    /** Optional repository artwork; older hosts may safely ignore it. */
    val iconUrl: String? = null,
    val description: String? = null,
    val sources: List<ShuYueRepositorySource> = emptyList(),
    val type: String? = null,
    val contentType: String? = null,
    /** V2 capability declaration retained for sidecar/admission parity checks. */
    val capabilities: Set<String> = emptySet(),
    /** Set only by a unified repository document; it is not a security/trust signal. */
    val contract: String? = null,
    /** V2 exact-artifact metadata retained through reviewed admission. */
    val sha256: String? = null,
    /** V2 artifact byte count retained for an exact download check. */
    val byteSize: Int? = null,
    val sidecarUrl: String? = null,
    val systemEvents: dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration? = null,
    val requestedHostPermissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission> = emptySet(),
    val installable: Boolean = true,
    val referenceOnly: Boolean = false,
    val legacyCompatibilityOnly: Boolean = false,
    @kotlinx.serialization.Transient
    public val resolvedScriptUrl: String = "",
    @kotlinx.serialization.Transient
    public val resolvedSidecarUrl: String = "",
) {
    public val sourceKeys: List<SourceKey>
        get() = sources.map { it.sourceKey(id) }

    public val resolvedContentType: dev.shinsou.kmp.plugin.PluginContentType
        get() = dev.shinsou.kmp.plugin.PluginContentType.resolve(
            packageType = contentType ?: type,
            sourceTypes = sources.map { it.contentType ?: it.type },
        )
}

/**
 * A loaded index is an opaque sealed view owned by the loader that created it. The private
 * implementation additionally requires object identity with an entry in its immutable snapshot,
 * so a forged/copy entry cannot be used to fetch an artifact.
 */
public sealed interface ShuYueRepositoryIndex {
    public val requestedIndexUrl: String
    public val finalIndexUrl: String
    public val entries: List<ShuYueRepositoryEntry>
}

/** Metadata retained with an unexecuted script package during migration/quarantine. */
public data class ShuYueScriptQuarantineMetadata(
    val packageId: String,
    val version: String,
    val versionCode: Int,
    val sourceIds: List<String>,
    val scriptUrl: String,
    val resolvedUrl: String,
    val indexUrl: String,
    val downloadedFinalUrl: String,
    /** Exact sidecar declaration retained with the inert artifact, when the index supplied one. */
    val sidecarUrl: String? = null,
    val resolvedSidecarUrl: String? = null,
    val sidecarDownloadedFinalUrl: String? = null,
    val sidecarSha256: String? = null,
    val sidecarByteSize: Int? = null,
)

/**
 * Downloaded bytes are inert until a later trust/capability review explicitly admits them.
 * Both the property getter and [copyBytes] return defensive copies; the digest is bound to the
 * private immutable backing bytes and cannot be supplied or altered by callers.
 */
public sealed interface ShuYueScriptDownload {
    public val metadata: ShuYueScriptQuarantineMetadata
    public val sha256: String
    public val bytes: ByteArray
    public fun copyBytes(): ByteArray
}

public sealed class ShuYueRepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class InvalidUrl(public val value: String, message: String = "Invalid ShuYue URL") :
        ShuYueRepositoryException("$message: $value")

    public class OriginNotAllowed(public val origin: String) :
        ShuYueRepositoryException("ShuYue artifact origin is not allowlisted: $origin")

    public class Http(public val status: Int, public val url: String) :
        ShuYueRepositoryException("HTTP $status while fetching ShuYue resource")

    public class BodyTooLarge(public val url: String, public val actualBytes: Long, public val maxBytes: Long) :
        ShuYueRepositoryException("ShuYue response exceeds its bounded byte limit")

    public class LimitExceeded(public val resource: String, public val actual: Long, public val max: Long) :
        ShuYueRepositoryException("ShuYue $resource exceeds its bounded limit")

    public class InvalidDocument(public val url: String, cause: Throwable) :
        ShuYueRepositoryException("Invalid ShuYue repository index", cause)

    public class DuplicateJsonKey(public val key: String) :
        ShuYueRepositoryException("Duplicate ShuYue JSON object key: $key")

    public class DuplicateIdentity(public val identity: String) :
        ShuYueRepositoryException("Duplicate ShuYue package/source identity: $identity")

    public class InvalidMetadata(public val field: String, message: String) :
        ShuYueRepositoryException("Invalid ShuYue metadata $field: $message")
}

/**
 * Loads a ShuYue index with no implicit network or JavaScript behavior. Only this injectable
 * transport obtains bytes; downloaded scripts are returned as quarantine artifacts.
 */
public class ShuYueRepositoryIndexLoader(
    private val transport: ShuYueRepositoryTransport,
    private val limits: ShuYueRepositoryLimits = ShuYueRepositoryLimits(),
) {
    private val provenanceToken: Any = Any()
    private val configuredAllowedOrigins: Set<ShuYueOrigin> by lazy {
        ReadOnlySetSnapshot(
            limits.artifactOriginStrings.map {
                ShuYueUrlParser.parseAbsolute(
                    it,
                    "allowed artifact origin",
                    forbidQuery = true,
                    originOnly = true,
                ).origin
            },
        )
    }

    public suspend fun load(location: ShuYueRepositoryLocation): ShuYueRepositoryIndex {
        val requestUrl = when (location) {
            is ShuYueRepositoryLocation.IndexUrl -> {
                ShuYueUrlParser.parseAbsolute(location.value, "index URL")
                location.value
            }

            is ShuYueRepositoryLocation.BaseUrl -> resolveBaseIndexUrl(location.value)
        }
        val request = ShuYueUrlParser.parseAbsolute(requestUrl, "index URL")
        val fetchedOrigins = artifactOriginsFor(request.origin)
        requireAllowed(request.origin, fetchedOrigins)
        val response = transport.execute(
            ShuYueRepositoryRequest(
                url = requestUrl,
                maxBytes = limits.maxIndexBytes,
                allowedArtifactOrigins = ReadOnlySetSnapshot(fetchedOrigins),
                maxRedirects = limits.maxRedirects,
            ),
        )
        val final = validateResponse(request, response, "index", fetchedOrigins, limits.maxIndexBytes)
        val entries = decodeEntries(final.url, response.body)
        validateEntries(entries)
        val resolvedEntries = entries.map { entry ->
            val resolved = resolveRelativeScript(final, entry.scriptUrl)
            requireAllowed(ShuYueUrlParser.parseAbsolute(resolved, "script URL").origin, fetchedOrigins)
            val resolvedSidecar = entry.sidecarUrl?.let { sidecarUrl ->
                val sidecar = resolveRelativeResource(final, sidecarUrl, "sidecarUrl")
                requireAllowed(ShuYueUrlParser.parseAbsolute(sidecar, "sidecar URL").origin, fetchedOrigins)
                sidecar
            }.orEmpty()
            entry.copy(
                sources = ReadOnlyListSnapshot(entry.sources),
                resolvedScriptUrl = resolved,
                resolvedSidecarUrl = resolvedSidecar,
            )
        }
        return LoadedRepositoryIndex(
            requestedIndexUrl = requestUrl,
            finalIndexUrl = final.url,
            entries = resolvedEntries,
        )
    }

    /** Fetches one script into an inert quarantine result; this method never evaluates JavaScript. */
    public suspend fun downloadScript(
        index: ShuYueRepositoryIndex,
        entry: ShuYueRepositoryEntry,
    ): ShuYueScriptDownload {
        val loadedIndex = index as? LoadedRepositoryIndex
        if (loadedIndex == null || !loadedIndex.belongsTo(provenanceToken)) {
            throw ShuYueRepositoryException.InvalidMetadata("index", "index was not loaded by this loader")
        }
        // Identity, rather than equal field values, is the capability check.
        val matching = loadedIndex.authorizedEntry(entry)
            ?: throw ShuYueRepositoryException.InvalidMetadata("entry", "entry is not from this loaded index")
        val resolvedUrl = matching.resolvedScriptUrl.ifEmpty {
            resolveRelativeScript(ShuYueUrlParser.parseAbsolute(index.finalIndexUrl, "final index URL"), matching.scriptUrl)
        }
        val resolved = ShuYueUrlParser.parseAbsolute(resolvedUrl, "script URL")
        val fetchedOrigins = artifactOriginsFor(ShuYueUrlParser.parseAbsolute(index.finalIndexUrl, "final index URL").origin)
        requireAllowed(resolved.origin, fetchedOrigins)
        val sidecar = matching.sidecarUrl?.let { sidecarReference ->
            val sidecarUrl = matching.resolvedSidecarUrl.ifEmpty {
                resolveRelativeResource(
                    ShuYueUrlParser.parseAbsolute(index.finalIndexUrl, "final index URL"),
                    sidecarReference,
                    "sidecarUrl",
                )
            }
            val sidecarAbsolute = ShuYueUrlParser.parseAbsolute(sidecarUrl, "sidecar URL")
            requireAllowed(sidecarAbsolute.origin, fetchedOrigins)
            val sidecarResponse = transport.execute(
                ShuYueRepositoryRequest(
                    url = sidecarUrl,
                    maxBytes = limits.maxSidecarBytes,
                    allowedArtifactOrigins = ReadOnlySetSnapshot(fetchedOrigins),
                    maxRedirects = limits.maxRedirects,
                ),
            )
            val sidecarFinal = validateResponse(
                sidecarAbsolute,
                sidecarResponse,
                "sidecar",
                fetchedOrigins,
                limits.maxSidecarBytes,
            )
            verifySidecar(matching, sidecarResponse.body, sidecarUrl)
            SidecarDownloadMetadata(
                referenceUrl = sidecarReference,
                resolvedUrl = sidecarUrl,
                downloadedFinalUrl = sidecarFinal.url,
                sha256 = Sha256.hex(sidecarResponse.body),
                byteSize = sidecarResponse.body.size,
            )
        }
        val response = transport.execute(
            ShuYueRepositoryRequest(
                url = resolvedUrl,
                maxBytes = limits.maxScriptBytes,
                allowedArtifactOrigins = ReadOnlySetSnapshot(fetchedOrigins),
                maxRedirects = limits.maxRedirects,
            ),
        )
        val downloaded = validateResponse(resolved, response, "script", fetchedOrigins, limits.maxScriptBytes)
        matching.byteSize?.let { expected ->
            if (response.body.size != expected) {
                throw ShuYueRepositoryException.InvalidMetadata(
                    "byteSize",
                    "script size ${response.body.size} does not match indexed size $expected",
                )
            }
        }
        matching.sha256?.let { expected ->
            if (Sha256.hex(response.body) != expected) {
                throw ShuYueRepositoryException.InvalidMetadata(
                    "sha256",
                    "script digest does not match indexed digest",
                )
            }
        }
        return LoadedScriptDownload(
            metadata = ShuYueScriptQuarantineMetadata(
                packageId = matching.id,
                version = matching.version,
                versionCode = matching.versionCode,
                sourceIds = matching.sources.map(ShuYueRepositorySource::id),
                scriptUrl = matching.scriptUrl,
                resolvedUrl = resolvedUrl,
                indexUrl = index.finalIndexUrl,
                downloadedFinalUrl = downloaded.url,
                sidecarUrl = sidecar?.referenceUrl,
                resolvedSidecarUrl = sidecar?.resolvedUrl,
                sidecarDownloadedFinalUrl = sidecar?.downloadedFinalUrl,
                sidecarSha256 = sidecar?.sha256,
                sidecarByteSize = sidecar?.byteSize,
            ),
            bytes = response.body,
        )
    }

    private fun artifactOriginsFor(requestedOrigin: ShuYueOrigin): Set<ShuYueOrigin> {
        if (configuredAllowedOrigins.isEmpty()) return ReadOnlySetSnapshot(listOf(requestedOrigin))
        if (requestedOrigin in configuredAllowedOrigins) return configuredAllowedOrigins
        if (limits.allowLocalArtifactOrigins && requestedOrigin.isLocalNetworkOrigin()) {
            return ReadOnlySetSnapshot(configuredAllowedOrigins + requestedOrigin)
        }
        return configuredAllowedOrigins
    }

    private inner class LoadedRepositoryIndex(
        override val requestedIndexUrl: String,
        override val finalIndexUrl: String,
        entries: List<ShuYueRepositoryEntry>,
    ) : ShuYueRepositoryIndex {
        private val ownerToken: Any = provenanceToken
        private val authorizedEntries: List<ShuYueRepositoryEntry> = ReadOnlyListSnapshot(entries)
        override val entries: List<ShuYueRepositoryEntry>
            get() = authorizedEntries

        fun belongsTo(candidate: Any): Boolean = ownerToken === candidate

        fun authorizedEntry(candidate: ShuYueRepositoryEntry): ShuYueRepositoryEntry? =
            authorizedEntries.firstOrNull { it === candidate }
    }

    private class LoadedScriptDownload(
        metadata: ShuYueScriptQuarantineMetadata,
        bytes: ByteArray,
    ) : ShuYueScriptDownload {
        override val metadata: ShuYueScriptQuarantineMetadata = metadata.copy(
            sourceIds = ReadOnlyListSnapshot(metadata.sourceIds),
        )
        private val backingBytes: ByteArray = bytes.copyOf()
        override val sha256: String = Sha256.hex(backingBytes)
        override val bytes: ByteArray
            get() = backingBytes.copyOf()

        override fun copyBytes(): ByteArray = backingBytes.copyOf()
    }

    private data class SidecarDownloadMetadata(
        val referenceUrl: String,
        val resolvedUrl: String,
        val downloadedFinalUrl: String,
        val sha256: String,
        val byteSize: Int,
    )

    /**
     * Sidecars are declarations, not executable input.  Still, a V2 script is not admitted from
     * a sidecar-bearing index until the declaration is bound to the exact index entry.  In
     * particular, a sidecar cannot silently change the digest, source identity, content type, or
     * host-event request that the index advertised.
     */
    private fun verifySidecar(entry: ShuYueRepositoryEntry, body: ByteArray, url: String) {
        val text = try {
            body.decodeToString(throwOnInvalidSequence = true)
        } catch (error: Throwable) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        }
        try {
            ShuYueRepositoryJsonPreflight.scan(text, limits, url)
            val root = StrictShuYueJson.parseToJsonElement(text) as? JsonObject
                ?: sidecarInvalid("root", "sidecar must be an object")
            if (root.requiredString("format") != "shinsou-extension-sidecar-v2") {
                sidecarInvalid("format", "unsupported sidecar format")
            }
            if (root.requiredInt("contractVersion") != 2) {
                sidecarInvalid("contractVersion", "unsupported sidecar contract")
            }
            if (root.optionalString("contract")?.let { it != "shuyue" } == true) {
                sidecarInvalid("contract", "sidecar contract does not match ShuYue")
            }
            if (root.requiredString("packageId") != entry.id) sidecarMismatch("packageId")
            if (root.requiredString("version") != entry.version) sidecarMismatch("version")
            if (root.requiredInt("versionCode") != entry.versionCode) sidecarMismatch("versionCode")
            root.optionalString("name")?.let { if (it != entry.name) sidecarMismatch("name") }
            root.optionalString("lang")?.let { if (it != entry.lang) sidecarMismatch("lang") }
            root.optionalBoolean("nsfw")?.let { if (it != (entry.nsfw == 1)) sidecarMismatch("nsfw") }
            root.optionalBoolean("installable")?.let { if (it != entry.installable) sidecarMismatch("installable") }
            root.optionalBoolean("referenceOnly")?.let { if (it != entry.referenceOnly) sidecarMismatch("referenceOnly") }
            root.optionalBoolean("legacyCompatibilityOnly")?.let {
                if (it != entry.legacyCompatibilityOnly) sidecarMismatch("legacyCompatibilityOnly")
            }

            val artifact = root.requiredObject("artifact")
            if (artifact.requiredString("scriptUrl") != entry.scriptUrl) sidecarMismatch("artifact.scriptUrl")
            val indexedDigest = entry.sha256 ?: sidecarInvalid("artifact.sha256", "index omitted artifact digest")
            if (artifact.requiredString("sha256") != indexedDigest) sidecarMismatch("artifact.sha256")
            val indexedSize = entry.byteSize ?: sidecarInvalid("artifact.byteSize", "index omitted artifact size")
            if (artifact.requiredInt("byteSize") != indexedSize) sidecarMismatch("artifact.byteSize")

            val content = root.requiredObject("content")
            if (content.requiredInt("contractVersion") != 2) sidecarInvalid("content.contractVersion", "unsupported content contract")
            val indexedContentType = entry.contentType ?: entry.type
            indexedContentType?.let { expected ->
                if (content.requiredString("type") != expected) sidecarMismatch("content.type")
            }

            if (entry.capabilities.isNotEmpty() && root.requiredStringSet("capabilities") != entry.capabilities) {
                sidecarMismatch("capabilities")
            }
            verifySidecarSources(entry, root.requiredArray("sources"))

            val events = root.requiredObject("systemEvents")
            val declaration = parseSidecarEvents(events)
            if (declaration != entry.systemEvents) sidecarMismatch("systemEvents")
            if (root.requiredStringSet("requestedHostPermissions") != entry.requestedHostPermissions.map { it.name }.toSet()) {
                sidecarMismatch("requestedHostPermissions")
            }
        } catch (error: ShuYueRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ShuYueRepositoryException.InvalidMetadata("sidecar", error.message ?: "invalid sidecar")
        }
    }

    private fun verifySidecarSources(entry: ShuYueRepositoryEntry, sidecars: JsonArray) {
        if (sidecars.size != entry.sources.size) sidecarMismatch("sources")
        entry.sources.forEach { expected ->
            val matches = sidecars.map { it as? JsonObject ?: sidecarInvalid("sources", "source must be an object") }
                .filter { it.requiredString("sourceId") == expected.id }
            if (matches.size != 1) sidecarMismatch("sources")
            val source = matches.single()
            val sourceKey = source.requiredObject("sourceKey")
            if (sourceKey.requiredString("packageId") != entry.id) sidecarMismatch("sources.sourceKey.packageId")
            if (sourceKey.requiredString("sourceId") != expected.id) sidecarMismatch("sources.sourceKey.sourceId")
            source.optionalString("name")?.let { if (it != expected.name) sidecarMismatch("sources.name") }
            source.optionalString("lang")?.let { if (it != expected.lang) sidecarMismatch("sources.lang") }
            source.optionalString("baseUrl")?.let { if (it != expected.baseUrl) sidecarMismatch("sources.baseUrl") }
        }
    }

    private fun parseSidecarEvents(events: JsonObject): dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration {
        if (events.requiredString("protocol") != dev.shinsou.kmp.plugin.events.PluginSystemEventProtocol.NAME) {
            sidecarInvalid("systemEvents.protocol", "unsupported event protocol")
        }
        return try {
            dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration(
                minVersion = events.requiredInt("minVersion"),
                maxVersion = events.requiredInt("maxVersion"),
                required = events.requiredStringSet("required"),
                optional = events.requiredStringSet("optional"),
            )
        } catch (error: Throwable) {
            throw ShuYueRepositoryException.InvalidMetadata("systemEvents", error.message ?: "invalid event declaration")
        }
    }

    private fun JsonObject.requiredObject(field: String): JsonObject =
        requiredElement(field) as? JsonObject ?: sidecarInvalid(field, "must be an object")

    private fun JsonObject.requiredArray(field: String): JsonArray =
        requiredElement(field) as? JsonArray ?: sidecarInvalid(field, "must be an array")

    private fun JsonObject.requiredInt(field: String): Int {
        val value = requiredElement(field) as? JsonPrimitive
            ?: sidecarInvalid(field, "must be an integer")
        if (value.isString) sidecarInvalid(field, "must be an integer")
        return value.content.toIntOrNull() ?: sidecarInvalid(field, "must be an integer")
    }

    private fun JsonObject.optionalString(field: String): String? =
        this[field]?.takeUnless { it is JsonNull }?.let { value ->
            val primitive = value as? JsonPrimitive ?: sidecarInvalid(field, "must be a string")
            if (!primitive.isString) sidecarInvalid(field, "must be a string")
            primitive.content
        }

    private fun JsonObject.optionalBoolean(field: String): Boolean? =
        this[field]?.takeUnless { it is JsonNull }?.let { value ->
            val primitive = value as? JsonPrimitive ?: sidecarInvalid(field, "must be a boolean")
            if (primitive.isString || primitive.content !in setOf("true", "false")) {
                sidecarInvalid(field, "must be a boolean")
            }
            primitive.content == "true"
        }

    private fun JsonObject.requiredStringSet(field: String): Set<String> {
        val values = requiredArray(field)
        return values.map { value ->
            val primitive = value as? JsonPrimitive ?: sidecarInvalid(field, "entries must be strings")
            if (!primitive.isString) sidecarInvalid(field, "entries must be strings")
            primitive.content
        }.toSet()
    }

    private fun sidecarMismatch(field: String): Nothing =
        throw ShuYueRepositoryException.InvalidMetadata("sidecar.$field", "sidecar does not match indexed metadata")

    private fun sidecarInvalid(field: String, message: String): Nothing =
        throw ShuYueRepositoryException.InvalidMetadata("sidecar.$field", message)

    private fun resolveBaseIndexUrl(value: String): String {
        val base = ShuYueUrlParser.parseAbsolute(value, "base URL", forbidQuery = true)
        val path = base.path.trimEnd('/').ifEmpty { "" }
        return buildAbsolute(base, "$path/index.json")
    }

    private fun decodeEntries(url: String, body: ByteArray): List<ShuYueRepositoryEntry> {
        val text = try {
            body.decodeToString(throwOnInvalidSequence = true)
        } catch (error: IllegalArgumentException) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        } catch (error: Exception) {
            // JVM reports malformed UTF-8 as MalformedInputException; other targets use
            // IllegalArgumentException. Both are rejected before JSON materialization.
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        }
        try {
            ShuYueRepositoryJsonPreflight.scan(text, limits, url)
        } catch (error: ShuYueRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        }
        return try {
            val root = StrictShuYueJson.parseToJsonElement(text)
            when {
                root is JsonArray -> decodeLegacyEntries(root)
                root is JsonObject && isV2Index(root) -> decodeV2Entries(root)
                root is JsonObject -> {
                    val entriesElement = root["shuyue"] ?: root["entries"]
                        ?: throw SerializationException("ShuYue repository must be an array or unified envelope")
                    decodeLegacyEntries(entriesElement)
                }
                else -> throw SerializationException("ShuYue repository must be an array or unified envelope")
            }
        } catch (error: SerializationException) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        } catch (error: IllegalArgumentException) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        }
    }

    private fun decodeLegacyEntries(entriesElement: JsonElement): List<ShuYueRepositoryEntry> =
        StrictShuYueJson.decodeFromJsonElement(
            ListSerializer(ShuYueRepositoryEntry.serializer()),
            entriesElement,
        )

    /** V2 package objects are normalized to the deliberately smaller legacy ShuYue model. */
    private fun decodeV2Entries(root: JsonObject): List<ShuYueRepositoryEntry> {
        val packages = root["packages"] as? JsonArray
            ?: throw SerializationException("V2 repository packages must be an array")
        return packages.mapNotNull { element ->
            val packageObject = element.asObject("package")
            if (packageObject.requiredString("contract") != "shuyue") return@mapNotNull null
            val capabilities = packageObject.optionalStringArray("capabilities").toSet()
            val packageContentType = packageObject["contentType"]
                ?.takeUnless { it is JsonNull }
                ?: packageObject["type"]?.takeUnless { it is JsonNull }
            val packageHasContentType = packageObject["contentType"]?.let { it !is JsonNull } == true
            val normalized = buildJsonObject {
                put("id", packageObject.requiredElement("id"))
                put("name", packageObject.requiredElement("name"))
                put("version", packageObject.requiredElement("version"))
                put("versionCode", packageObject.requiredElement("versionCode"))
                put("lang", packageObject.requiredElement("lang"))
                put("nsfw", JsonPrimitive(if (packageObject.requiredBoolean("nsfw")) 1 else 0))
                put("scriptUrl", packageObject.requiredElement("scriptUrl"))
                packageObject.copyIfPresent(this, "iconUrl")
                packageObject.copyIfPresent(this, "description")
                packageObject.copyIfPresent(this, "type")
                packageObject.copyIfPresent(this, "contentType")
                packageObject.copyIfPresent(this, "capabilities")
                packageContentType?.let { contentType ->
                    if (!packageHasContentType) put("contentType", contentType)
                }
                put("contract", JsonPrimitive("shuyue"))
                packageObject.copyIfPresent(this, "sha256")
                packageObject.copyIfPresent(this, "byteSize")
                packageObject.copyIfPresent(this, "sidecarUrl")
                packageObject["systemEvents"]?.takeUnless { it is JsonNull }?.let { declaration ->
                    put("systemEvents", normalizeSystemEvents(declaration.asObject("systemEvents")))
                }
                packageObject.copyIfPresent(this, "requestedHostPermissions")
                packageObject.copyBooleanIfPresent(this, "installable")
                packageObject.copyBooleanIfPresent(this, "referenceOnly")
                packageObject.copyBooleanIfPresent(this, "legacyCompatibilityOnly")
                put(
                    "sources",
                    normalizeV2Sources(
                        packageObject["sources"] ?: throw SerializationException("V2 package sources are required"),
                        capabilities,
                        packageContentType,
                    ),
                )
            }
            StrictShuYueJson.decodeFromJsonElement(ShuYueRepositoryEntry.serializer(), normalized)
        }
    }

    private fun normalizeV2Sources(
        element: JsonElement,
        packageCapabilities: Set<String>,
        packageContentType: JsonElement?,
    ): JsonArray {
        val sources = element as? JsonArray
            ?: throw SerializationException("V2 package sources must be an array")
        return JsonArray(sources.map { sourceElement ->
            val source = sourceElement.asObject("source")
            val sourceHasType = source["type"]?.let { it !is JsonNull } == true
            val sourceHasContentType = source["contentType"]?.let { it !is JsonNull } == true
            buildJsonObject {
                put("id", source.requiredElement("sourceId"))
                put("name", source.requiredElement("name"))
                put("lang", source.requiredElement("lang"))
                put("baseUrl", source.requiredElement("baseUrl"))
                put("supportsLogin", JsonPrimitive("LOGIN" in packageCapabilities))
                put("supportsLatest", JsonPrimitive("LATEST" in packageCapabilities))
                put(
                    "supportsFavorites",
                    JsonPrimitive("FAVORITE" in packageCapabilities || "FAVORITES" in packageCapabilities),
                )
                source.copyIfPresent(this, "type")
                source.copyIfPresent(this, "contentType")
                if (!sourceHasType && !sourceHasContentType) {
                    packageContentType?.let { put("contentType", it) }
                }
            }
        })
    }

    private fun normalizeSystemEvents(events: JsonObject): JsonObject = buildJsonObject {
        put("minVersion", events.requiredElement("minVersion"))
        put("maxVersion", events.requiredElement("maxVersion"))
        events["required"]?.takeUnless { it is JsonNull }?.let { put("required", it) }
        events["optional"]?.takeUnless { it is JsonNull }?.let { put("optional", it) }
    }

    private fun isV2Index(root: JsonObject): Boolean {
        val format = root["format"] as? JsonPrimitive
        val contractVersion = root["contractVersion"] as? JsonPrimitive
        return format?.content == "shinsou-extension-v2" && contractVersion?.content == "2"
    }

    private fun JsonElement.asObject(field: String): JsonObject = this as? JsonObject
        ?: throw SerializationException("V2 $field must be an object")

    private fun JsonObject.requiredElement(field: String): JsonElement = this[field]
        ?.takeUnless { it is JsonNull }
        ?: throw SerializationException("V2 $field is required")

    private fun JsonObject.requiredString(field: String): String {
        val primitive = requiredElement(field) as? JsonPrimitive
            ?: throw SerializationException("V2 $field must be a string")
        if (!primitive.isString) throw SerializationException("V2 $field must be a string")
        return primitive.content
    }

    private fun JsonObject.requiredBoolean(field: String): Boolean {
        val primitive = requiredElement(field) as? JsonPrimitive
            ?: throw SerializationException("V2 $field must be a boolean")
        if (primitive.isString || (primitive.content != "true" && primitive.content != "false")) {
            throw SerializationException("V2 $field must be a boolean")
        }
        return primitive.content == "true"
    }

    private fun JsonObject.optionalStringArray(field: String): List<String> {
        val element = this[field]?.takeUnless { it is JsonNull } ?: return emptyList()
        val array = element as? JsonArray ?: throw SerializationException("V2 $field must be an array")
        return array.map { item ->
            val primitive = item as? JsonPrimitive
                ?: throw SerializationException("V2 $field entries must be strings")
            if (!primitive.isString) throw SerializationException("V2 $field entries must be strings")
            primitive.content
        }
    }

    private fun JsonObject.copyIfPresent(target: JsonObjectBuilder, field: String) {
        this[field]?.takeUnless { it is JsonNull }?.let { target.put(field, it) }
    }

    private fun JsonObject.copyBooleanIfPresent(target: JsonObjectBuilder, field: String) {
        this[field]?.takeUnless { it is JsonNull }?.let {
            target.put(field, JsonPrimitive(requiredBoolean(field)))
        }
    }

    private fun validateEntries(entries: List<ShuYueRepositoryEntry>) {
        if (entries.size > limits.maxPackages) {
            throw ShuYueRepositoryException.LimitExceeded("packages", entries.size.toLong(), limits.maxPackages.toLong())
        }
        val packageIds = HashSet<String>(entries.size)
        var totalSources = 0
        entries.forEach { entry ->
            validateText(entry.id, "package id", limits.maxPackageIdBytes)
            validateText(entry.name, "package name", limits.maxPackageNameBytes)
            validateText(entry.version, "package version", limits.maxPackageVersionBytes)
            validateText(entry.lang, "package lang", limits.maxPackageLangBytes)
            validateText(entry.scriptUrl, "scriptUrl", limits.maxScriptUrlBytes)
            entry.iconUrl?.let { validateText(it, "iconUrl", limits.maxScriptUrlBytes, allowBlank = true) }
            if (entry.versionCode <= 0) invalidMetadata("versionCode", "must be positive")
            if (entry.nsfw !in 0..1) invalidMetadata("nsfw", "must be 0 or 1")
            entry.sha256?.let { digest ->
                if (!SHA256.matches(digest)) invalidMetadata("sha256", "must be lowercase SHA-256")
            }
            entry.byteSize?.let { size ->
                if (size <= 0) invalidMetadata("byteSize", "must be positive")
            }
            if (!packageIds.add(entry.id)) throw ShuYueRepositoryException.DuplicateIdentity("package:${entry.id}")
            if (entry.sources.isEmpty()) invalidMetadata("sources", "must not be empty")
            if (entry.sources.size > limits.maxSourcesPerPackage) {
                throw ShuYueRepositoryException.LimitExceeded(
                    "sources:${entry.id}", entry.sources.size.toLong(), limits.maxSourcesPerPackage.toLong(),
                )
            }
            validateRelativeScriptReference(entry.scriptUrl)
            entry.description?.let { validateText(it, "description", limits.maxDescriptionBytes, allowBlank = true) }
            val sourceIds = HashSet<String>(entry.sources.size)
            entry.sources.forEach { source ->
                validateText(source.id, "source id", limits.maxSourceIdBytes)
                validateText(source.name, "source name", limits.maxSourceNameBytes)
                validateText(source.lang, "source lang", limits.maxSourceLangBytes)
                validateText(source.baseUrl, "source base URL", limits.maxSourceBaseUrlBytes)
                if (!sourceIds.add(source.id)) throw ShuYueRepositoryException.DuplicateIdentity("source:${entry.id}:${source.id}")
                // Deliberately syntax-only: runtime source origins never grant artifact fetch.
                ShuYueUrlParser.parseAbsolute(source.baseUrl, "source base URL", forbidQuery = true)
            }
            totalSources += entry.sources.size
            if (totalSources > limits.maxTotalSources) {
                throw ShuYueRepositoryException.LimitExceeded("sources", totalSources.toLong(), limits.maxTotalSources.toLong())
            }
        }
    }

    private fun validateText(value: String, field: String, maxBytes: Int, allowBlank: Boolean = false) {
        if ((!allowBlank && value.isBlank()) || value.any { it.isISOControl() }) invalidMetadata(field, "blank/control characters")
        val size = value.encodeToByteArray().size
        if (size > maxBytes) throw ShuYueRepositoryException.LimitExceeded("string:$field", size.toLong(), maxBytes.toLong())
    }

    private fun invalidMetadata(field: String, message: String): Nothing =
        throw ShuYueRepositoryException.InvalidMetadata(field, message)

    private fun requireSuccess(response: ShuYueRepositoryResponse, url: String) {
        if (response.status !in 200..299) throw ShuYueRepositoryException.Http(response.status, url)
    }

    private fun validateResponse(
        request: AbsoluteUrl,
        response: ShuYueRepositoryResponse,
        resource: String,
        allowedOrigins: Set<ShuYueOrigin>,
        maxBytes: Long,
    ): AbsoluteUrl {
        requireSuccess(response, request.url)
        requireSize(response.body, request.url, maxBytes)
        if (response.redirectChain.size > limits.maxRedirects) {
            throw ShuYueRepositoryException.LimitExceeded(
                "$resource redirects", response.redirectChain.size.toLong(), limits.maxRedirects.toLong(),
            )
        }
        val chain = response.redirectChain.map { hop ->
            val parsed = ShuYueUrlParser.parseAbsolute(hop, "$resource redirect")
            requireAllowed(parsed.origin, allowedOrigins)
            parsed
        }
        val finalRaw = response.finalUrl
        if (finalRaw.isEmpty()) {
            throw ShuYueRepositoryException.InvalidMetadata("finalUrl", "$resource response must include a final URL")
        }
        val final = ShuYueUrlParser.parseAbsolute(finalRaw, "final $resource URL")
        requireAllowed(final.origin, allowedOrigins)
        if (chain.isEmpty()) {
            // A changed final URL without a reported chain is an incomplete transport trace.
            if (final.url != request.url) {
                throw ShuYueRepositoryException.InvalidMetadata(
                    "redirectChain",
                    "$resource response omitted redirects before finalUrl",
                )
            }
        } else if (chain.last().url != final.url) {
            throw ShuYueRepositoryException.InvalidMetadata(
                "redirectChain",
                "$resource response redirect trace does not end at finalUrl",
            )
        }
        val visited = HashSet<String>(chain.size + 1)
        if (!visited.add(request.url) || chain.any { !visited.add(it.url) }) {
            throw ShuYueRepositoryException.InvalidMetadata("redirectChain", "redirect loop")
        }
        return final
    }

    private fun requireSize(body: ByteArray, url: String, maxBytes: Long) {
        if (body.size.toLong() > maxBytes) throw ShuYueRepositoryException.BodyTooLarge(url, body.size.toLong(), maxBytes)
    }

    private fun requireAllowed(origin: ShuYueOrigin, allowedOrigins: Set<ShuYueOrigin>) {
        if (origin !in allowedOrigins) throw ShuYueRepositoryException.OriginNotAllowed(origin.value)
    }

    private fun buildAbsolute(base: AbsoluteUrl, path: String, query: String = ""): String = buildString {
        append(base.origin.value)
        append(if (path.startsWith('/')) path else "/$path")
        if (query.isNotEmpty()) append('?').append(query)
    }

    private fun resolveRelativeScript(base: AbsoluteUrl, reference: String): String {
        return resolveRelativeResource(base, reference, "scriptUrl")
    }

    private fun resolveRelativeResource(base: AbsoluteUrl, reference: String, field: String): String {
        validateRelativeResourceReference(reference, field)
        val queryIndex = reference.indexOf('?')
        val referencePath = if (queryIndex < 0) reference else reference.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else reference.substring(queryIndex + 1)
        val directory = if (base.path.endsWith('/')) base.path else base.path.substringBeforeLast('/', "/") + "/"
        return buildAbsolute(base, directory + referencePath, query)
    }

    private fun validateRelativeScriptReference(reference: String) {
        validateRelativeResourceReference(reference, "scriptUrl")
    }

    private fun validateRelativeResourceReference(reference: String, field: String) {
        if (reference.isEmpty() || reference.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) {
            throw ShuYueRepositoryException.InvalidUrl(reference, "$field contains an unsafe character")
        }
        if ('#' in reference || reference.startsWith('/') || reference.startsWith("//") || SCRIPT_SCHEME.containsMatchIn(reference)) {
            throw ShuYueRepositoryException.InvalidUrl(reference, "$field must be a relative path without a fragment")
        }
        val queryIndex = reference.indexOf('?')
        val path = if (queryIndex < 0) reference else reference.substring(0, queryIndex)
        if (path.isBlank()) throw ShuYueRepositoryException.InvalidUrl(reference, "$field path is empty")
        ShuYueUrlParser.validateEncodedPath(path, field, rooted = false)
        if (queryIndex >= 0) {
            ShuYueUrlParser.validatePercentEncoding(
                reference.substring(queryIndex + 1),
                "$field query",
                includeDots = false,
            )
        }
    }

    private companion object {
        val SCRIPT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val StrictShuYueJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}

/** A Kotlin read-only view whose JVM mutation methods fail instead of exposing an ArrayList. */
private class ReadOnlyListSnapshot<T>(values: Iterable<T>) : AbstractList<T>() {
    private val backing: List<T> = values.toList()

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T = backing[index]
}

/** A Kotlin read-only view with a wrapped iterator so its private LinkedHashSet cannot be mutated. */
private class ReadOnlySetSnapshot<T>(values: Iterable<T>) : AbstractSet<T>() {
    private val backing: Set<T> = values.toSet()

    override val size: Int
        get() = backing.size

    override fun contains(element: T): Boolean = backing.contains(element)

    override fun iterator(): Iterator<T> {
        val iterator = backing.iterator()
        return object : Iterator<T> {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): T = iterator.next()
        }
    }
}

internal data class AbsoluteUrl(
    val url: String,
    val origin: ShuYueOrigin,
    val path: String,
)

/** Canonical URL parser shared by artifact policy and source metadata validation. */
internal object ShuYueUrlParser {
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val hostPattern = Regex("^[A-Za-z0-9.-]+$")

    fun parseAbsolute(value: String, field: String, forbidQuery: Boolean = false, originOnly: Boolean = false): AbsoluteUrl {
        if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) {
            throw ShuYueRepositoryException.InvalidUrl(value, "$field contains an unsafe character")
        }
        if ('#' in value) throw ShuYueRepositoryException.InvalidUrl(value, "$field must not contain a fragment")
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0 || value.indexOf("://", schemeEnd + 3) >= 0) throw ShuYueRepositoryException.InvalidUrl(value, "$field is not an absolute URL")
        val schemeRaw = value.substring(0, schemeEnd)
        if (!schemePattern.matches(schemeRaw)) throw ShuYueRepositoryException.InvalidUrl(value, "$field has an invalid scheme")
        val scheme = schemeRaw.lowercase()
        if (scheme !in setOf("http", "https")) throw ShuYueRepositoryException.InvalidUrl(value, "$field must use HTTP(S)")
        val afterScheme = value.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authorityRaw = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
        if (authorityRaw.isEmpty()) throw ShuYueRepositoryException.InvalidUrl(value, "$field has no authority")
        val authority = parseAuthority(authorityRaw, field)
        val tail = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)
        val queryIndex = tail.indexOf('?')
        val rawPath = when {
            queryIndex < 0 -> tail.ifEmpty { "/" }
            queryIndex == 0 -> "/"
            else -> tail.substring(0, queryIndex)
        }
        if (!rawPath.startsWith('/')) throw ShuYueRepositoryException.InvalidUrl(value, "$field path must be rooted")
        validateEncodedPath(rawPath, field, rooted = true)
        if (queryIndex >= 0) {
            if (forbidQuery) throw ShuYueRepositoryException.InvalidUrl(value, "$field must not contain a query")
            validatePercentEncoding(tail.substring(queryIndex + 1), "$field query", includeDots = false)
        }
        if (originOnly && (rawPath != "/" || value.endsWith('/'))) throw ShuYueRepositoryException.InvalidUrl(value, "$field must contain only scheme and authority")
        if (originOnly && queryIndex >= 0) throw ShuYueRepositoryException.InvalidUrl(value, "$field must contain only scheme and authority")
        val canonicalAuthority = if ((scheme == "https" && authority.port == 443) || (scheme == "http" && authority.port == 80)) {
            ShuYueAuthority.fromCanonical(authority.host, null, authority.isIpv6)
        } else {
            authority
        }
        return AbsoluteUrl(value, ShuYueOrigin.fromCanonical(scheme, canonicalAuthority), rawPath)
    }

    fun parseAuthorityPublic(value: String): ShuYueAuthority {
        if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            throw ShuYueRepositoryException.InvalidUrl(value, "authority contains an unsafe character")
        }
        return parseAuthority(value, "authority")
    }

    private fun parseAuthority(raw: String, field: String): ShuYueAuthority {
        if ('@' in raw || '%' in raw) throw ShuYueRepositoryException.InvalidUrl(raw, "$field must not contain userinfo or encoded host bytes")
        if (raw.startsWith('[')) {
            val close = raw.indexOf(']')
            if (close <= 1 || !isValidIpv6(raw.substring(1, close))) throw ShuYueRepositoryException.InvalidUrl(raw, "$field has malformed IPv6")
            val suffix = raw.substring(close + 1)
            val port = parsePortSuffix(suffix, field)
            return ShuYueAuthority.fromCanonical(raw.substring(1, close).lowercase(), port, isIpv6 = true)
        }
        if ('[' in raw || ']' in raw || raw.count { it == ':' } > 1) throw ShuYueRepositoryException.InvalidUrl(raw, "$field is ambiguous")
        val colon = raw.indexOf(':')
        val host = if (colon < 0) raw else raw.substring(0, colon)
        if (host.isEmpty() || !hostPattern.matches(host) || host.startsWith('.') || host.endsWith('.') || ".." in host) {
            throw ShuYueRepositoryException.InvalidUrl(raw, "$field has an invalid host")
        }
        val port = if (colon < 0) null else parsePortSuffix(raw.substring(colon), field)
        return ShuYueAuthority.fromCanonical(host.lowercase(), port, isIpv6 = false)
    }

    private fun parsePortSuffix(suffix: String, field: String): Int? {
        if (suffix.isEmpty()) return null
        if (!suffix.startsWith(':') || suffix.length == 1 || suffix.substring(1).any { !it.isDigit() }) throw ShuYueRepositoryException.InvalidUrl(suffix, "$field has an invalid port")
        val port = suffix.substring(1).toIntOrNull() ?: throw ShuYueRepositoryException.InvalidUrl(suffix, "$field has an invalid port")
        if (port !in 1..65535) throw ShuYueRepositoryException.InvalidUrl(suffix, "$field port must be 1..65535")
        return port
    }

    private fun isValidIpv6(host: String): Boolean {
        if (host.isEmpty() || host.any { !(it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.') }) return false
        val hasCompression = host.contains("::")
        if (hasCompression && host.indexOf("::") != host.lastIndexOf("::")) return false
        val groups = if (hasCompression) {
            val left = host.substringBefore("::")
            val right = host.substringAfter("::")
            if (left.endsWith(':') || right.startsWith(':')) return false
            (if (left.isEmpty()) emptyList() else left.split(':')) + (if (right.isEmpty()) emptyList() else right.split(':'))
        } else {
            if (host.startsWith(':') || host.endsWith(':')) return false
            host.split(':')
        }
        if (groups.any { it.isEmpty() || ('.' !in it && (it.length !in 1..4 || it.any { c -> !c.isDigit() && c.lowercaseChar() !in 'a'..'f' })) }) return false
        val ipv4 = groups.count { '.' in it }
        if (ipv4 > 1 || (ipv4 == 1 && !validIpv4(groups.last()))) return false
        val effectiveGroupCount = groups.size + if (ipv4 == 1) 1 else 0
        return if (hasCompression) effectiveGroupCount < 8 else effectiveGroupCount == 8
    }

    private fun validIpv4(value: String): Boolean {
        val pieces = value.split('.')
        return pieces.size == 4 && pieces.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
    }

    fun validateEncodedPath(path: String, field: String, rooted: Boolean) {
        if (rooted && !path.startsWith('/')) throw ShuYueRepositoryException.InvalidUrl(path, "$field path must be rooted")
        if (path.split('/').any { it == "." || it == ".." }) throw ShuYueRepositoryException.InvalidUrl(path, "$field contains traversal")
        path.split('/').forEach { segment -> validatePercentEncoding(segment, field, includeDots = true) }
    }

    fun validatePercentEncoding(value: String, field: String, includeDots: Boolean) {
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length || value[index + 1].digitToIntOrNull(16) == null || value[index + 2].digitToIntOrNull(16) == null) throw ShuYueRepositoryException.InvalidUrl(value, "$field contains a malformed percent escape")
                val byte = value.substring(index + 1, index + 3).toInt(16)
                if (byte == '%'.code || byte == '/'.code || byte == '\\'.code || byte < 0x20 || byte == 0x7f || includeDots && byte == '.'.code) throw ShuYueRepositoryException.InvalidUrl(value, "$field contains an encoded separator/control/traversal")
                index += 3
            } else index++
        }
    }
}

/**
 * A bounded, allocation-light JSON token scan. It runs after bounded UTF-8 decoding but before
 * kotlinx.serialization materializes package/source objects. It rejects duplicate keys, deep
 * nesting, oversized strings, object/array cardinality, and package/source counts early.
 */
internal object ShuYueRepositoryJsonPreflight {
    fun scan(text: String, limits: ShuYueRepositoryLimits, url: String) {
        try {
            Parser(text, limits).scan()
        } catch (error: ShuYueRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        }
    }

    private class Parser(private val text: String, private val limits: ShuYueRepositoryLimits) {
        private var index: Int = 0
        private var totalSources: Int = 0

        fun scan() {
            skipWhitespace()
            when (peek()) {
                '[' -> parsePackageArray(2)
                '{' -> parseEnvelope()
                else -> fail("repository root must be an array or unified envelope")
            }
            skipWhitespace()
            if (index != text.length) fail("trailing JSON content")
        }

        private fun parsePackageArray(objectDepth: Int) {
            expect('['); skipWhitespace()
            var packages = 0
            if (peek() != ']') {
                while (true) {
                    packages++
                    if (packages > limits.maxPackages) throw ShuYueRepositoryException.LimitExceeded("packages", packages.toLong(), limits.maxPackages.toLong())
                    parseObject(ObjectKind.PACKAGE, objectDepth); skipWhitespace()
                    if (consume(',')) { skipWhitespace(); continue }; break
                }
            }
            expect(']')
        }

        private fun parseEnvelope() {
            checkDepth(1); expect('{'); skipWhitespace()
            val keys = HashSet<String>()
            var members = 0
            if (peek() != '}') {
                while (true) {
                    members++
                    if (members > limits.maxJsonObjectMembers) {
                        throw ShuYueRepositoryException.LimitExceeded(
                            "JSON object members",
                            members.toLong(),
                            limits.maxJsonObjectMembers.toLong(),
                        )
                    }
                    val key = parseString("object key", limits.maxStringBytes).value
                    if (!keys.add(key)) throw ShuYueRepositoryException.DuplicateJsonKey(key)
                    skipWhitespace(); expect(':'); skipWhitespace()
                    if (key == "shuyue" || key == "entries" || key == "packages") parsePackageArray(3)
                    else parseValue(ObjectKind.GENERIC, key, 2)
                    skipWhitespace()
                    if (consume(',')) { skipWhitespace(); continue }
                    break
                }
            }
            expect('}')
        }

        private fun parseObject(kind: ObjectKind, depth: Int) {
            checkDepth(depth); expect('{'); skipWhitespace(); val keys = HashSet<String>(); var members = 0
            if (peek() != '}') {
                while (true) {
                    members++
                    if (members > limits.maxJsonObjectMembers) {
                        throw ShuYueRepositoryException.LimitExceeded(
                            "JSON object members",
                            members.toLong(),
                            limits.maxJsonObjectMembers.toLong(),
                        )
                    }
                    val key = parseString("object key", limits.maxStringBytes).value
                    if (!keys.add(key)) throw ShuYueRepositoryException.DuplicateJsonKey(key)
                    skipWhitespace(); expect(':'); skipWhitespace()
                    if (kind == ObjectKind.PACKAGE && key == "sources") parseSourcesArray(depth + 1)
                    else parseValue(kind, key, depth + 1)
                    skipWhitespace(); if (consume(',')) { skipWhitespace(); continue }; break
                }
            }
            expect('}')
        }

        private fun parseSourcesArray(depth: Int) {
            checkDepth(depth); expect('['); skipWhitespace(); var count = 0
            if (peek() != ']') {
                while (true) {
                    count++; totalSources++
                    if (count > limits.maxSourcesPerPackage) throw ShuYueRepositoryException.LimitExceeded("sources", count.toLong(), limits.maxSourcesPerPackage.toLong())
                    if (totalSources > limits.maxTotalSources) throw ShuYueRepositoryException.LimitExceeded("sources", totalSources.toLong(), limits.maxTotalSources.toLong())
                    parseObject(ObjectKind.SOURCE, depth + 1); skipWhitespace()
                    if (consume(',')) { skipWhitespace(); continue }; break
                }
            }
            expect(']')
        }

        private fun parseValue(parent: ObjectKind, field: String, depth: Int) {
            when (peek()) {
                '"' -> parseString(field, fieldLimit(parent, field))
                '{' -> parseObject(ObjectKind.GENERIC, depth)
                '[' -> parseArray(depth)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail("invalid JSON value")
            }
        }

        private fun parseArray(depth: Int) {
            checkDepth(depth); expect('['); skipWhitespace(); var elements = 0
            if (peek() != ']') {
                while (true) {
                    elements++
                    if (elements > limits.maxJsonArrayElements) {
                        throw ShuYueRepositoryException.LimitExceeded(
                            "JSON array elements",
                            elements.toLong(),
                            limits.maxJsonArrayElements.toLong(),
                        )
                    }
                    parseValue(ObjectKind.GENERIC, "value", depth + 1); skipWhitespace()
                    if (consume(',')) { skipWhitespace(); continue }; break
                }
            }
            expect(']')
        }

        private fun fieldLimit(parent: ObjectKind, field: String): Int = when (parent) {
            ObjectKind.PACKAGE -> when (field) {
                "id" -> limits.maxPackageIdBytes; "name" -> limits.maxPackageNameBytes; "version" -> limits.maxPackageVersionBytes
                "lang" -> limits.maxPackageLangBytes; "scriptUrl" -> limits.maxScriptUrlBytes; "description" -> limits.maxDescriptionBytes
                else -> limits.maxStringBytes
            }
            ObjectKind.SOURCE -> when (field) {
                "id" -> limits.maxSourceIdBytes; "name" -> limits.maxSourceNameBytes; "lang" -> limits.maxSourceLangBytes
                "baseUrl" -> limits.maxSourceBaseUrlBytes; else -> limits.maxStringBytes
            }
            else -> limits.maxStringBytes
        }

        private fun parseString(field: String, maxBytes: Int): StringToken {
            expect('"'); val output = StringBuilder(); var bytes = 0; var encodedChars = 0
            while (true) {
                if (index >= text.length) fail("unterminated JSON string")
                val char = text[index++]; encodedChars++
                if (char == '"') break
                if (char == '\\') {
                    if (index >= text.length) fail("unterminated JSON escape")
                    val escaped = text[index++]; encodedChars++
                    when (escaped) {
                        '"', '\\', '/' -> { output.append(escaped); bytes++ }
                        'b' -> { output.append('\b'); bytes++ }; 'f' -> { output.append('\u000c'); bytes++ }
                        'n' -> { output.append('\n'); bytes++ }; 'r' -> { output.append('\r'); bytes++ }; 't' -> { output.append('\t'); bytes++ }
                        'u' -> {
                            if (index + 4 > text.length) fail("short unicode escape")
                            val code = text.substring(index, index + 4).toIntOrNull(16) ?: fail("invalid unicode escape")
                            index += 4; encodedChars += 4; val unit = code.toChar()
                            if (unit.isHighSurrogate()) {
                                if (index + 6 > text.length || text[index] != '\\' || text[index + 1] != 'u') fail("unpaired high surrogate")
                                val low = text.substring(index + 2, index + 6).toIntOrNull(16) ?: fail("invalid low surrogate escape")
                                if (!low.toChar().isLowSurrogate()) fail("invalid low surrogate")
                                index += 6; encodedChars += 6; output.append(unit).append(low.toChar()); bytes += 4
                            } else if (unit.isLowSurrogate()) fail("unpaired low surrogate")
                            else { output.append(unit); bytes += utf8Bytes(unit.code) }
                        }
                        else -> fail("invalid JSON escape")
                    }
                } else {
                    if (char.code < 0x20 || char.isSurrogate()) fail("invalid JSON string character")
                    output.append(char); bytes += utf8Bytes(char.code)
                }
                if (bytes > maxBytes) throw ShuYueRepositoryException.LimitExceeded("string:$field", bytes.toLong(), maxBytes.toLong())
                if (encodedChars > maxBytes * 8L + 16L) throw ShuYueRepositoryException.LimitExceeded("encoded-string:$field", encodedChars.toLong(), maxBytes * 8L + 16L)
            }
            return StringToken(output.toString())
        }

        private fun number() {
            val start = index; consume('-')
            if (consume('0')) { if (peek()?.isDigit() == true) fail("leading zero in number") }
            else { if (peek() !in '1'..'9') fail("invalid number"); while (peek()?.isDigit() == true) index++ }
            if (consume('.')) { if (peek()?.isDigit() != true) fail("invalid number fraction"); while (peek()?.isDigit() == true) index++ }
            if (peek() == 'e' || peek() == 'E') { index++; if (peek() == '+' || peek() == '-') index++; if (peek()?.isDigit() != true) fail("invalid number exponent"); while (peek()?.isDigit() == true) index++ }
            if (index - start > 128) fail("number is too long")
        }

        private fun literal(value: String) { if (!text.startsWith(value, index)) fail("invalid JSON literal"); index += value.length }
        private fun checkDepth(depth: Int) { if (depth > limits.maxJsonNesting) throw ShuYueRepositoryException.LimitExceeded("JSON nesting", depth.toLong(), limits.maxJsonNesting.toLong()) }
        private fun expect(char: Char) { if (!consume(char)) fail("expected '$char'") }
        private fun consume(char: Char): Boolean { if (peek() == char) { index++; return true }; return false }
        private fun peek(): Char? = text.getOrNull(index)
        private fun skipWhitespace() { while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') index++ }
        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $index")
        private data class StringToken(val value: String)
        private enum class ObjectKind { PACKAGE, SOURCE, GENERIC }
        private fun utf8Bytes(codePoint: Int): Int = when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; else -> 3 }
    }
}

private val SHA256: Regex = Regex("[0-9a-f]{64}")
