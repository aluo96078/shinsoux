package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
) {
    init {
        require(maxIndexBytes in 1..MAX_HARD_INDEX_BYTES) { "Invalid ShuYue index byte limit" }
        require(maxScriptBytes in 1..MAX_HARD_SCRIPT_BYTES) { "Invalid ShuYue script byte limit" }
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

/** The legacy index intentionally keeps all IDs as opaque strings. */
@Serializable
public data class ShuYueRepositorySource(
    val id: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val supportsLogin: Boolean = false,
    val supportsLatest: Boolean = false,
    val supportsFavorites: Boolean = false,
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
    val description: String? = null,
    val sources: List<ShuYueRepositorySource> = emptyList(),
    @kotlinx.serialization.Transient
    public val resolvedScriptUrl: String = "",
) {
    public val sourceKeys: List<SourceKey>
        get() = sources.map { it.sourceKey(id) }
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
            entry.copy(sources = ReadOnlyListSnapshot(entry.sources), resolvedScriptUrl = resolved)
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
        val response = transport.execute(
            ShuYueRepositoryRequest(
                url = resolvedUrl,
                maxBytes = limits.maxScriptBytes,
                allowedArtifactOrigins = ReadOnlySetSnapshot(fetchedOrigins),
                maxRedirects = limits.maxRedirects,
            ),
        )
        val downloaded = validateResponse(resolved, response, "script", fetchedOrigins, limits.maxScriptBytes)
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
            ),
            bytes = response.body,
        )
    }

    private fun artifactOriginsFor(requestedOrigin: ShuYueOrigin): Set<ShuYueOrigin> =
        configuredAllowedOrigins.ifEmpty { ReadOnlySetSnapshot(listOf(requestedOrigin)) }

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
            StrictShuYueJson.decodeFromString(ListSerializer(ShuYueRepositoryEntry.serializer()), text)
        } catch (error: SerializationException) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
        } catch (error: IllegalArgumentException) {
            throw ShuYueRepositoryException.InvalidDocument(url, error)
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
            if (entry.versionCode <= 0) invalidMetadata("versionCode", "must be positive")
            if (entry.nsfw !in 0..1) invalidMetadata("nsfw", "must be 0 or 1")
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
        validateRelativeScriptReference(reference)
        val queryIndex = reference.indexOf('?')
        val referencePath = if (queryIndex < 0) reference else reference.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else reference.substring(queryIndex + 1)
        val directory = if (base.path.endsWith('/')) base.path else base.path.substringBeforeLast('/', "/") + "/"
        return buildAbsolute(base, directory + referencePath, query)
    }

    private fun validateRelativeScriptReference(reference: String) {
        if (reference.isEmpty() || reference.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) {
            throw ShuYueRepositoryException.InvalidUrl(reference, "scriptUrl contains an unsafe character")
        }
        if ('#' in reference || reference.startsWith('/') || reference.startsWith("//") || SCRIPT_SCHEME.containsMatchIn(reference)) {
            throw ShuYueRepositoryException.InvalidUrl(reference, "scriptUrl must be a relative path without a fragment")
        }
        val queryIndex = reference.indexOf('?')
        val path = if (queryIndex < 0) reference else reference.substring(0, queryIndex)
        if (path.isBlank()) throw ShuYueRepositoryException.InvalidUrl(reference, "scriptUrl path is empty")
        ShuYueUrlParser.validateEncodedPath(path, "scriptUrl", rooted = false)
        if (queryIndex >= 0) ShuYueUrlParser.validatePercentEncoding(reference.substring(queryIndex + 1), "scriptUrl query", includeDots = false)
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
            skipWhitespace(); expect('['); skipWhitespace()
            var packages = 0
            if (peek() != ']') {
                while (true) {
                    packages++
                    if (packages > limits.maxPackages) throw ShuYueRepositoryException.LimitExceeded("packages", packages.toLong(), limits.maxPackages.toLong())
                    parseObject(ObjectKind.PACKAGE, 2); skipWhitespace()
                    if (consume(',')) { skipWhitespace(); continue }; break
                }
            }
            expect(']'); skipWhitespace()
            if (index != text.length) fail("trailing JSON content")
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
