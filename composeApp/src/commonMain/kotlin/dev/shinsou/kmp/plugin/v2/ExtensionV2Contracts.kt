package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.ImageLayout
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Capabilities advertised by an extension source and checked by the host before each call. */
@Serializable
public enum class ExtensionCapability {
    CATALOGUE,
    LATEST,
    BROWSE,
    METADATA,
    UNITS,
    CONTENT,
    SEARCH,
    LOGIN,
    FAVORITE,
    PREFERENCES,
}

@Serializable
public enum class HttpMethodV2 {
    GET,
    POST,
    HEAD,
}

/** Request body is a host-resolved reference, never raw credentials or unbounded bytes. */
@Serializable
public data class RequestBodyRefV2(
    val reference: String,
    val mediaType: String,
    val byteSize: Long,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(reference, "Request body reference")
        requireMediaType(mediaType, "Request body media type")
        require(byteSize >= 0 && byteSize <= MAX_REQUEST_BODY_BYTES) {
            "Request body is outside the bounded limit"
        }
    }
}

/** Host-controlled network plan. Cookie/Auth headers and redirect policy are never extension data. */
@Serializable
public data class RemoteRequestPlanV2(
    val method: HttpMethodV2,
    val url: String? = null,
    val baseUri: String? = null,
    val relativePath: String? = null,
    val headerHints: Map<String, String> = emptyMap(),
    val body: RequestBodyRefV2? = null,
    val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) {
    init { validate() }

    public fun validate(): Unit {
        val direct = url != null
        val composed = baseUri != null || relativePath != null
        require(direct xor composed) { "Request must use either an absolute URL or baseUri+relativePath" }
        if (direct) requireRemoteUri(requireNotNull(url), "Request URL")
        if (composed) {
            val base = requireNotNull(baseUri)
            requireRemoteUri(base, "Request base URI")
            require('?' !in base) { "Request base URI must not contain a query" }
            requireSafeRelativePath(requireNotNull(relativePath), "Request relative path")
        }
        require(headerHints.size <= MAX_HEADERS) { "Too many request header hints" }
        headerHints.forEach { (name, value) ->
            requireHeaderHint(name, value)
        }
        require(body == null || method == HttpMethodV2.POST) {
            "Only POST requests may carry a body"
        }
        body?.validate()
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) { "Invalid response byte limit" }
    }

    public val effectiveUri: String
        get() = url ?: "${requireNotNull(baseUri).trimEnd('/')}/${requireNotNull(relativePath)}"
}

@Serializable
public data class ImageTransformPlanV2(
    val transformId: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    init {
        requireSafeIdentifier(transformId, "Image transform id")
        require(parameters.size <= MAX_PARAMETERS)
        require(parameters.keys.all { it.isNotBlank() && it.length <= MAX_IDENTIFIER_LENGTH && it.none(Char::isISOControl) })
        require(parameters.values.all { it.length <= MAX_PARAMETER_VALUE_LENGTH })
        require(parameters.values.all { it.none(Char::isISOControl) })
    }
}

/** Per-page request and transform preserve source-specific semantics such as JM descrambling. */
@Serializable
public data class ImagePageV2(
    val resourceId: String,
    val request: RemoteRequestPlanV2,
    val mediaType: String,
    val transform: ImageTransformPlanV2? = null,
    val spread: String? = null,
    val layout: ImageLayout = ImageLayout.PAGE,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(resourceId, "Image page resource id")
        request.validate()
        requireMediaType(mediaType, "Image page media type")
        require(mediaType.startsWith("image/", ignoreCase = true)) {
            "Image page must use an image media type"
        }
        transform?.let {
            requireSafeIdentifier(it.transformId, "Image transform id")
        }
        spread?.let { requireSafeIdentifier(it, "Image spread hint") }
    }
}

/** A remote resource plan used by text and EPUB payloads before host blob publication. */
@Serializable
public data class RemoteResourceV2(
    val id: String,
    val request: RemoteRequestPlanV2,
    val mediaType: String,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(id, "Remote resource id")
        request.validate()
        requireMediaType(mediaType, "Remote resource media type")
    }
}

@Serializable
public data class RemoteBlobPlanV2(
    val resource: RemoteResourceV2,
    val expectedPlaintextDigest: String? = null,
    val expectedByteSize: Long? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        resource.validate()
        expectedPlaintextDigest?.let {
            require(SHA256_PATTERN.matches(it)) { "Expected body digest must be lowercase SHA-256" }
        }
        require(expectedByteSize == null || expectedByteSize >= 0) {
            "Expected body size must be non-negative"
        }
        require(expectedByteSize == null || expectedByteSize <= resource.request.maxResponseBytes) {
            "Expected body size exceeds request response limit"
        }
    }
}

/** Typed source-side text alternatives; host remains responsible for storage and decoding. */
@Serializable
public sealed interface TextPayloadSourceV2 {
    @Serializable
    @SerialName("inline")
    public data class InlineTextPayload(
        val text: String,
        val charset: String = "UTF-8",
        val mediaType: String = "text/plain",
        val baseUri: String? = null,
    ) : TextPayloadSourceV2 {
        init {
            require(text.encodeToByteArray().size <= MAX_INLINE_TEXT_BYTES) {
                "Inline text exceeds hard byte limit"
            }
            require(charset == "UTF-8") { "Inline text must be canonical UTF-8" }
            requireMediaType(mediaType, "Inline text media type")
            require(mediaType.startsWith("text/", ignoreCase = true)) {
                "Inline text must use a text media type"
            }
            baseUri?.let { requireRemoteUri(it, "Inline text base URI") }
        }
    }

    @Serializable
    @SerialName("host_fetch")
    public data class HostFetchResource(
        val body: RemoteBlobPlanV2,
    ) : TextPayloadSourceV2 {
        init {
            body.validate()
            require(body.resource.mediaType.startsWith("text/", ignoreCase = true)) {
                "Host-fetched text must use a text media type"
            }
        }
    }

    @Serializable
    @SerialName("chunked")
    public data class ChunkedTextPayload(
        val streamId: String,
        val firstCursor: String? = null,
        val charset: String = "UTF-8",
        val mediaType: String = "text/plain",
        val maxChunkBytes: Int = DEFAULT_TEXT_CHUNK_BYTES,
        val maxTotalBytes: Long = DEFAULT_MAX_TEXT_STREAM_BYTES,
        val maxChunks: Int = DEFAULT_MAX_TEXT_STREAM_CHUNKS,
        val cancellationReference: String,
    ) : TextPayloadSourceV2 {
        init {
            requireSafeIdentifier(streamId, "Text stream id")
            firstCursor?.let { requireSafeIdentifier(it, "Text cursor") }
            require(charset == "UTF-8") { "Chunked text must be canonical UTF-8" }
            requireMediaType(mediaType, "Chunked text media type")
            require(mediaType.startsWith("text/", ignoreCase = true)) {
                "Chunked text must use a text media type"
            }
            require(maxChunkBytes in 1..MAX_TEXT_CHUNK_BYTES) { "Invalid text chunk limit" }
            require(maxTotalBytes in 1..MAX_TEXT_STREAM_BYTES) { "Invalid text stream byte limit" }
            require(maxChunks in 1..MAX_TEXT_STREAM_CHUNKS) { "Invalid text stream chunk limit" }
            requireSafeIdentifier(cancellationReference, "Text cancellation reference")
        }
    }
}

/** Bounded result for the host-side cursor stream. */
@Serializable
public data class TextChunkResultV2(
    val utf8Text: String,
    val nextCursor: String?,
    val done: Boolean,
) {
    init {
        validate()
    }

    /** Validates the result against the protocol-wide hard limit. */
    public fun validate(): Unit {
        require(utf8Text.encodeToByteArray().size <= MAX_TEXT_CHUNK_BYTES) {
            "Text chunk exceeds byte limit"
        }
        nextCursor?.let { requireSafeIdentifier(it, "Text next cursor") }
        require(done || nextCursor != null) { "Non-terminal text chunk needs a next cursor" }
        require(!done || nextCursor == null) { "Terminal text chunk must not expose a next cursor" }
        require(done || utf8Text.isNotEmpty()) { "Non-terminal text chunk must make progress" }
    }

    public fun validate(maxChunkBytes: Int): Unit {
        require(maxChunkBytes in 1..MAX_TEXT_CHUNK_BYTES) { "Invalid declared text chunk limit" }
        require(utf8Text.encodeToByteArray().size <= maxChunkBytes) {
            "Text chunk exceeds its declared stream limit"
        }
        validate()
    }
}

/** Host implementation for cursor-based text; cancellation is explicit and idempotent. */
public interface TextChunkStreamV2 {
    public val maxChunkBytes: Int
    public suspend fun next(cursor: String?): TextChunkResultV2
    public fun cancel()
}

@Serializable
public data class PagedResultV2<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
) {
    init { require(items.size <= MAX_PAGE_ITEMS) { "Page result is too large" } }
}

@Serializable
public data class RemoteEpubResourceV2(
    val id: String,
    val href: String,
    val body: RemoteBlobPlanV2,
    val mediaType: String,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(id, "Remote EPUB resource id")
        requireSafeRelativePath(href, "Remote EPUB resource href")
        body.validate()
        require(body.resource.id == id) { "Remote EPUB body resource id must match graph id" }
        requireMediaType(mediaType, "Remote EPUB media type")
        require(body.resource.mediaType == mediaType) { "Remote EPUB media types must agree" }
    }
}

@Serializable
public data class RemoteEpubPackageV2(
    val archive: RemoteBlobPlanV2,
    val packageDocumentId: String,
    val resources: List<RemoteEpubResourceV2>,
    val encryptionDescriptors: List<RemoteEpubEncryptionDescriptor> = emptyList(),
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(packageDocumentId, "Remote EPUB package document id")
        archive.validate()
        require(archive.resource.mediaType.equals(EPUB_ARCHIVE_MEDIA_TYPE, ignoreCase = true)) {
            "Remote EPUB archive must use application/epub+zip"
        }
        require(resources.isNotEmpty() && resources.size <= MAX_RESOURCES) { "Remote EPUB package needs bounded resources" }
        require(resources.map(RemoteEpubResourceV2::id).distinct().size == resources.size) {
            "Remote EPUB resource ids must be unique"
        }
        val ids = resources.mapTo(hashSetOf(), RemoteEpubResourceV2::id)
        require(packageDocumentId in ids) { "Remote EPUB package document must resolve" }
        require(encryptionDescriptors.size <= MAX_RESOURCES) {
            "Remote EPUB package has too many encryption descriptors"
        }
        require(encryptionDescriptors.map(RemoteEpubEncryptionDescriptor::resourceId).distinct().size ==
            encryptionDescriptors.size) { "Remote EPUB encryption resources must be unique" }
        encryptionDescriptors.forEach { descriptor ->
            descriptor.validate()
            require(descriptor.resourceId in ids) { "Remote EPUB encryption references unknown resource" }
        }
        resources.forEach(RemoteEpubResourceV2::validate)
    }
}

@Serializable
public data class RemoteEpubEncryptionDescriptor(
    val resourceId: String,
    val algorithm: String,
    val evidenceReference: String? = null,
) {
    init {
        requireSafeIdentifier(resourceId, "Remote EPUB encrypted resource id")
        requireSafeIdentifier(algorithm, "Remote EPUB encryption algorithm")
        evidenceReference?.let { requireSafeIdentifier(it, "Remote EPUB encryption evidence") }
    }

    public fun validate(): Unit {
        requireSafeIdentifier(resourceId, "Remote EPUB encrypted resource id")
        requireSafeIdentifier(algorithm, "Remote EPUB encryption algorithm")
    }
}

@Serializable
public data class RemoteEpubSpineDocumentV2(
    val id: String,
    val href: String,
    val resourceId: String,
    val linear: Boolean = true,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(id, "Remote EPUB spine id")
        requireSafeRelativePath(href, "Remote EPUB spine href")
        requireSafeIdentifier(resourceId, "Remote EPUB spine resource id")
    }
}

/** Tagged v2 payload returned for one exact source/unit remote identity. */
@Serializable
public sealed interface UnitContentPayload {
    public val schemaVersion: Int
    public val representationId: String
    public val sourceKey: SourceKey
    public val remoteUnitId: String
    public val kind: ContentKind

    @Serializable
    @SerialName("image_sequence")
    public data class ImageSequence(
        override val schemaVersion: Int,
        override val representationId: String,
        override val sourceKey: SourceKey,
        override val remoteUnitId: String,
        val pages: List<ImagePageV2>,
        val progression: ImageProgression = ImageProgression.LEFT_TO_RIGHT,
        val layout: ImageLayout = ImageLayout.PAGE,
    ) : UnitContentPayload {
        override val kind: ContentKind get() = ContentKind.IMAGE_SEQUENCE
        init { validate() }

        public fun validate(): Unit {
            validateHeader(this)
            require(pages.isNotEmpty() && pages.size <= MAX_RESOURCES) {
                "Image payload needs a bounded page list"
            }
            require(pages.map(ImagePageV2::resourceId).distinct().size == pages.size) {
                "Image payload page ids must be unique"
            }
            pages.forEach(ImagePageV2::validate)
        }
    }

    @Serializable
    @SerialName("inline_text")
    public data class InlineTextPayload(
        override val schemaVersion: Int,
        override val representationId: String,
        override val sourceKey: SourceKey,
        override val remoteUnitId: String,
        val source: TextPayloadSourceV2.InlineTextPayload,
        val blocks: List<TextBlock> = emptyList(),
    ) : UnitContentPayload {
        override val kind: ContentKind get() = ContentKind.PLAIN_TEXT
        init { validate() }
        public fun validate(): Unit {
            validateHeader(this)
            source.validateInline()
            validateBlocks(blocks, source.text.length)
        }
    }

    @Serializable
    @SerialName("host_fetch_text")
    public data class HostFetchTextPayload(
        override val schemaVersion: Int,
        override val representationId: String,
        override val sourceKey: SourceKey,
        override val remoteUnitId: String,
        val source: TextPayloadSourceV2.HostFetchResource,
        val blocks: List<TextBlock> = emptyList(),
    ) : UnitContentPayload {
        override val kind: ContentKind get() = ContentKind.PLAIN_TEXT
        init { validate() }
        public fun validate(): Unit {
            validateHeader(this)
            source.validateHostFetch()
            validateBlocks(blocks)
        }
    }

    @Serializable
    @SerialName("chunked_text")
    public data class ChunkedTextPayload(
        override val schemaVersion: Int,
        override val representationId: String,
        override val sourceKey: SourceKey,
        override val remoteUnitId: String,
        val source: TextPayloadSourceV2.ChunkedTextPayload,
    ) : UnitContentPayload {
        override val kind: ContentKind get() = ContentKind.PLAIN_TEXT
        init { validate() }
        public fun validate(): Unit {
            validateHeader(this)
            source.validateChunked()
        }
    }

    @Serializable
    @SerialName("epub_spine")
    public data class EpubSpine(
        override val schemaVersion: Int,
        override val representationId: String,
        override val sourceKey: SourceKey,
        override val remoteUnitId: String,
        val packageGraph: RemoteEpubPackageV2,
        val documents: List<RemoteEpubSpineDocumentV2>,
    ) : UnitContentPayload {
        override val kind: ContentKind get() = ContentKind.EPUB_SPINE
        init { validate() }
        public fun validate(): Unit {
            validateHeader(this)
            packageGraph.validate()
            require(documents.isNotEmpty() && documents.size <= MAX_RESOURCES) {
                "EPUB payload needs bounded spine documents"
            }
            require(documents.map(RemoteEpubSpineDocumentV2::id).distinct().size == documents.size) {
                "EPUB payload spine ids must be unique"
            }
            val resources = packageGraph.resources.associateBy(RemoteEpubResourceV2::id)
            documents.forEach { document ->
                document.validate()
                val resource = requireNotNull(resources[document.resourceId]) {
                    "EPUB payload spine references unknown resource"
                }
                require(resource.href == document.href) { "EPUB payload spine href mismatch" }
            }
        }
    }
}

/** Exactly one result for one remote unit; representations may include multiple same-kind forms. */
@Serializable
public data class UnitContentResultV2(
    val schemaVersion: Int,
    val sourceKey: SourceKey,
    val remotePublicationId: String,
    val remoteUnitId: String,
    val representations: List<UnitContentPayload>,
) {
    init { validate() }

    public fun validate(): Unit {
        require(schemaVersion == ExtensionPackageV2.CURRENT_CONTRACT_VERSION) {
            "Unsupported unit-content schema version"
        }
        sourceKey.validate()
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        requireSafeIdentifier(remoteUnitId, "Remote unit id")
        require(representations.isNotEmpty()) { "Unit content result needs a representation" }
        require(representations.size <= MAX_REPRESENTATIONS) {
            "Unit content result has too many representations"
        }
        require(representations.map(UnitContentPayload::representationId).distinct().size == representations.size) {
            "Unit content representation ids must be unique"
        }
        representations.forEach { payload ->
            payload.validatePayload()
            require(payload.schemaVersion == schemaVersion)
            require(payload.sourceKey == sourceKey && payload.remoteUnitId == remoteUnitId) {
                "Payload scope does not match unit content result"
            }
        }
    }
}

/** Source descriptor is exact-keyed; no numeric/hash source lookup is permitted. */
@Serializable
public data class SourceDescriptorV2(
    val sourceKey: SourceKey,
    val displayName: String,
    val languageTag: String,
    val supportedContentKinds: Set<ContentKind>,
    val capabilities: Set<ExtensionCapability>,
    val baseUrl: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        sourceKey.validate()
        requirePrintable(displayName, "Source display name")
        requireSafeIdentifier(languageTag, "Source language tag")
        require(supportedContentKinds.isNotEmpty()) { "Source must support at least one content kind" }
        baseUrl?.let { requireRemoteUri(it, "Source base URL") }
        require(supportedContentKinds.all { ExtensionCapability.CONTENT in capabilities }) {
            "A source advertising content kinds must advertise CONTENT"
        }
    }

    public fun binding(remoteId: String, canonicalUrl: String? = null): SourceBinding =
        SourceBinding(sourceKey, remoteId, canonicalUrl)
}

@Serializable
public data class ExtensionPackageV2(
    val contractVersion: Int,
    val packageId: String,
    val version: String,
    val displayName: String,
    val sources: List<SourceDescriptorV2>,
    val capabilities: Set<ExtensionCapability> = emptySet(),
    val supportedContentKinds: Set<ContentKind> = emptySet(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(contractVersion == CURRENT_CONTRACT_VERSION) {
            "Unsupported extension contract version $contractVersion"
        }
        requireSafeIdentifier(packageId, "Extension package id")
        requireSafeIdentifier(version, "Extension version")
        requirePrintable(displayName, "Extension display name")
        require(sources.isNotEmpty() && sources.size <= MAX_SOURCES_PER_PACKAGE) {
            "Extension package needs a bounded source list"
        }
        require(sources.map { it.sourceKey.sourceId }.distinct().size == sources.size) {
            "Extension source ids must be unique within a package"
        }
        sources.forEach { source ->
            source.validate()
            require(source.sourceKey.packageId == packageId) {
                "Source package id does not match extension package id"
            }
        }
        val union = sources.flatMapTo(linkedSetOf()) { it.supportedContentKinds }
        require(supportedContentKinds.isEmpty() || supportedContentKinds.containsAll(union)) {
            "Package supported content kinds must cover its sources"
        }
    }

    public fun supports(kind: ContentKind): Boolean =
        (if (supportedContentKinds.isEmpty()) sources.flatMapTo(linkedSetOf()) { it.supportedContentKinds }
        else supportedContentKinds).contains(kind)

    public fun validatePayload(sourceKey: SourceKey, payload: UnitContentPayload): Unit {
        val descriptor = sources.firstOrNull { it.sourceKey == sourceKey }
            ?: error("Source is not declared by extension package")
        payload.validatePayload()
        require(payload.sourceKey == sourceKey) {
            "Payload source scope does not match the requested package source"
        }
        require(payload.kind in descriptor.supportedContentKinds) {
            "Source does not support payload kind ${payload.kind}"
        }
    }

    public companion object {
        public const val CURRENT_CONTRACT_VERSION: Int = 2
    }
}

/**
 * Error-level opt-in for extension implementation SPI. UI/import/reader consumers use
 * [HostExtensionSourceV2] and cannot accidentally call an unchecked implementation surface.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Extension implementation SPI; application consumers must use ExtensionHostFacadeV2",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExtensionImplementationApi

/** Capability-gated runtime facade; source resolution is exact SourceKey equality. */
@ExtensionImplementationApi
public interface ExtensionSourceV2 {
    public val descriptor: SourceDescriptorV2
    public suspend fun browseOptions(): BrowseOptionsSchemaV2
    /** Returns the editable source filter tree used by the legacy FilterList contract. */
    public suspend fun getFilterList(): BrowseFilterListV2 = emptyList()
    public suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2>
    /** Search with source filters; the two-argument form remains the compatibility default. */
    public suspend fun search(
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = search(query, page)
    public suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2>
    public suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2>
    public suspend fun details(remotePublicationId: String): RemotePublicationV2
    public suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2>
    public suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2
    public suspend fun openTextStream(streamId: String): TextChunkStreamV2
    public suspend fun login(credentials: LoginCredentialsV2): LoginResultV2
    public suspend fun logout()
    public suspend fun preferences(): List<PreferenceV2>
    public suspend fun favorite(remotePublicationId: String, favorite: Boolean)
}

@ExtensionImplementationApi
public interface ExtensionPackageRuntimeV2 {
    public val descriptor: ExtensionPackageV2
    public fun source(sourceKey: SourceKey): ExtensionSourceV2?
}

/**
 * Host-owned runtime boundary. Extension implementations are never handed directly to UI/import
 * code: every call below checks the exact declared source capability, validates inputs, and
 * revalidates bounded/scoped outputs before returning them.
 */
@OptIn(ExtensionImplementationApi::class)
public class ExtensionHostFacadeV2 internal constructor(
    private val runtime: ExtensionPackageRuntimeV2,
) {
    init { runtime.descriptor.validate() }

    public fun source(sourceKey: SourceKey): HostExtensionSourceV2? {
        sourceKey.validate()
        val declared = runtime.descriptor.sources.singleOrNull { it.sourceKey == sourceKey } ?: return null
        val implementation = runtime.source(sourceKey) ?: return null
        require(implementation.descriptor == declared) {
            "Extension runtime returned a source implementation with a different descriptor"
        }
        return HostExtensionSourceV2(declared, implementation)
    }
}

/** Optional host-only dynamic policy hook; it is never visible to plugin code. */
@OptIn(ExtensionImplementationApi::class)
public interface UserInteractionScopedExtensionSourceV2 {
    public suspend fun <T> withUserInteractionContext(block: suspend () -> T): T
    public fun setHostUiAvailable(available: Boolean)
}

/**
 * Optional implementation hints for browser-bound anti-bot challenges.
 *
 * Cookies remain host-owned and no arbitrary source headers cross into the embedded browser
 * surface. Implementations may select an HTTP(S) URL on the source's exact origin because some
 * providers challenge only protected member endpoints rather than their public home page.
 */
@ExtensionImplementationApi
public interface WebChallengeUserAgentSourceV2 {
    public val webChallengeUserAgent: String?
    public val webChallengeUrl: String? get() = null
}

/** Host-only exact artifact authority for native/reviewed event-capable runtimes. */
public interface ArtifactBoundExtensionPackageRuntimeV2 {
    public val artifactIdentity: dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
}

@OptIn(ExtensionImplementationApi::class)
public class HostExtensionSourceV2 internal constructor(
    public val descriptor: SourceDescriptorV2,
    private val implementation: ExtensionSourceV2,
) {
    public suspend fun <T> withUserInteractionContext(block: suspend () -> T): T =
        (implementation as? UserInteractionScopedExtensionSourceV2)?.withUserInteractionContext(block) ?: block()
    public fun setHostUiAvailable(available: Boolean) {
        (implementation as? UserInteractionScopedExtensionSourceV2)?.setHostUiAvailable(available)
    }
    public fun webChallengeUserAgent(): String? {
        val userAgent = (implementation as? WebChallengeUserAgentSourceV2)
            ?.webChallengeUserAgent
            ?: return null
        require(userAgent.isNotBlank()) { "Web challenge User-Agent must not be blank" }
        requireHeaderHint("User-Agent", userAgent)
        return userAgent
    }
    public fun webChallengeUrl(): String? {
        val url = (implementation as? WebChallengeUserAgentSourceV2)
            ?.webChallengeUrl
            ?: return null
        require(url.isNotBlank()) { "Web challenge URL must not be blank" }
        requireRemoteUri(url, "Web challenge URL")
        return url
    }
    public suspend fun browseOptions(): BrowseOptionsSchemaV2 {
        requireCapability(ExtensionCapability.BROWSE)
        return implementation.browseOptions().also(::validateBrowseSchema)
    }

    public suspend fun getFilterList(): BrowseFilterListV2 {
        requireCapability(ExtensionCapability.BROWSE)
        return implementation.getFilterList().also { filters ->
            validateBrowseFilters(filters)
        }
    }

    public suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.SEARCH)
        requireQuery(query)
        requirePage(page)
        return implementation.search(query, page).also(::validatePublicationPage)
    }

    public suspend fun search(
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.SEARCH)
        requireQuery(query)
        validateBrowseOptions(options)
        requirePage(page)
        return implementation.search(query, page, options).also(::validatePublicationPage)
    }

    public suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.LATEST)
        requirePage(page)
        return implementation.latest(page).also(::validatePublicationPage)
    }

    public suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.BROWSE)
        validateBrowseOptions(options)
        requirePage(page)
        return implementation.browse(options, page).also(::validatePublicationPage)
    }

    public suspend fun details(remotePublicationId: String): RemotePublicationV2 {
        requireCapability(ExtensionCapability.METADATA)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        return implementation.details(remotePublicationId).also { result ->
            validateRemotePublication(result)
            require(result.remoteId == remotePublicationId) { "Extension details changed remote identity" }
        }
    }

    public suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> {
        requireCapability(ExtensionCapability.UNITS)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        requirePage(page)
        return implementation.units(remotePublicationId, page).also(::validateUnitPage)
    }

    public suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 {
        requireCapability(ExtensionCapability.CONTENT)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        requireSafeIdentifier(remoteUnitId, "Remote unit id")
        return implementation.content(remotePublicationId, remoteUnitId).also { result ->
            result.validate()
            require(result.sourceKey == descriptor.sourceKey &&
                result.remotePublicationId == remotePublicationId && result.remoteUnitId == remoteUnitId) {
                "Extension content result escaped the requested source/publication/unit scope"
            }
            result.representations.forEach { payload ->
                require(payload.kind in descriptor.supportedContentKinds) {
                    "Extension returned an undeclared content kind"
                }
            }
        }
    }

    public suspend fun openTextStream(plan: TextPayloadSourceV2.ChunkedTextPayload): HostTextChunkStreamV2 {
        requireCapability(ExtensionCapability.CONTENT)
        plan.validateChunked()
        val stream = implementation.openTextStream(plan.streamId)
        return HostTextChunkStreamV2(plan, stream)
    }

    public suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 {
        requireCapability(ExtensionCapability.LOGIN)
        validateCredentials(credentials)
        return implementation.login(credentials)
    }

    public suspend fun logout(): Unit {
        requireCapability(ExtensionCapability.LOGIN)
        implementation.logout()
    }

    public suspend fun preferences(): List<PreferenceV2> {
        requireCapability(ExtensionCapability.PREFERENCES)
        return implementation.preferences().also { preferences ->
            require(preferences.size <= MAX_PREFERENCES) { "Extension returned too many preferences" }
            require(preferences.map(PreferenceV2::key).distinct().size == preferences.size) {
                "Extension returned duplicate preference keys"
            }
            preferences.forEach(::validatePreference)
        }
    }

    public suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit {
        requireCapability(ExtensionCapability.FAVORITE)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        implementation.favorite(remotePublicationId, favorite)
    }

    private fun requireCapability(capability: ExtensionCapability) {
        descriptor.requireCapability(capability)
    }
}

/**
 * Cursor stream guard owned by the host. It prevents cursor cycles and enforces both per-chunk
 * and aggregate limits even when an extension implementation keeps returning valid small chunks.
 */
@OptIn(ExtensionImplementationApi::class)
public class HostTextChunkStreamV2 internal constructor(
    public val plan: TextPayloadSourceV2.ChunkedTextPayload,
    private val implementation: TextChunkStreamV2,
) : TextChunkStreamV2 {
    private val seenCursors = HashSet<String?>()
    private var expectedCursor: String? = plan.firstCursor
    private var chunkCount: Int = 0
    private var totalBytes: Long = 0
    private var terminal: Boolean = false
    private var cancelled: Boolean = false

    override val maxChunkBytes: Int

    init {
        try {
            require(implementation.maxChunkBytes in 1..plan.maxChunkBytes) {
                "Extension text stream declared an invalid chunk bound"
            }
            maxChunkBytes = implementation.maxChunkBytes
        } catch (error: Throwable) {
            runCatching { implementation.cancel() }
            throw error
        }
    }

    override suspend fun next(cursor: String?): TextChunkResultV2 {
        check(!cancelled) { "Text stream is cancelled" }
        check(!terminal) { "Text stream is complete" }
        require(cursor == expectedCursor) { "Text stream cursor does not match the host continuation" }
        if (!seenCursors.add(cursor)) return fail("Text stream cursor cycle detected")
        return try {
            val result = implementation.next(cursor)
            result.validate(maxChunkBytes)
            val bytes = result.utf8Text.encodeToByteArray().size.toLong()
            if (bytes > Long.MAX_VALUE - totalBytes) return fail("Text stream byte count overflow")
            totalBytes += bytes
            chunkCount++
            if (totalBytes > plan.maxTotalBytes) return fail("Text stream exceeded its aggregate byte limit")
            if (chunkCount > plan.maxChunks || chunkCount == plan.maxChunks && !result.done) {
                return fail("Text stream exceeded its chunk limit")
            }
            if (result.nextCursor != null && result.nextCursor in seenCursors) {
                return fail("Text stream cursor cycle detected")
            }
            expectedCursor = result.nextCursor
            terminal = result.done
            result
        } catch (error: Throwable) {
            cancelSafely()
            throw error
        }
    }

    override fun cancel(): Unit {
        if (cancelled) return
        cancelled = true
        implementation.cancel()
    }

    private fun fail(message: String): Nothing {
        cancelSafely()
        throw IllegalArgumentException(message)
    }

    private fun cancelSafely() {
        runCatching { cancel() }
    }
}

@Serializable
public data class BrowseOptionsV2(
    val values: Map<String, String> = emptyMap(),
    /** Structured source filters projected from the legacy FilterList model. */
    val filters: List<BrowseFilterV2> = emptyList(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(values.size <= MAX_PARAMETERS)
        values.forEach { (key, value) ->
            requireSafeIdentifier(key, "Browse option key")
            require(value.length <= MAX_PARAMETER_VALUE_LENGTH && value.none(Char::isISOControl))
        }
        validateBrowseFilters(filters)
    }
}

@Serializable
public data class BrowseOptionsSchemaV2(
    val keys: List<String> = emptyList(),
    /** Editable source filters, in the same order and shape as the legacy FilterList. */
    val filters: List<BrowseFilterV2> = emptyList(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(keys.size <= MAX_PARAMETERS && keys.distinct().size == keys.size)
        keys.forEach { requireSafeIdentifier(it, "Browse option key") }
        validateBrowseFilters(filters)
    }
}

/**
 * Serializable v2 projection of the legacy source FilterList.
 *
 * Keeping this model in the contract (instead of leaking the host UI's BrowseFilter type) lets
 * native and legacy-backed v2 sources expose the same Select/Text/CheckBox/TriState/Group/Sort
 * controls.  The host converts it to its platform-neutral UI model before rendering.
 */
@Serializable
public sealed interface BrowseFilterV2 {
    public val name: String

    @Serializable
    @SerialName("header")
    public data class Header(override val name: String) : BrowseFilterV2

    @Serializable
    @SerialName("separator")
    public data object Separator : BrowseFilterV2 {
        override val name: String = ""
    }

    @Serializable
    @SerialName("select")
    public data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("text")
    public data class Text(
        override val name: String,
        val state: String,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("checkBox")
    public data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("triState")
    public data class TriState(
        override val name: String,
        val state: BrowseTriStateV2,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("group")
    public data class Group(
        override val name: String,
        val filters: List<BrowseFilterV2>,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("sort")
    public data class Sort(
        override val name: String,
        val values: List<String>,
        val selection: BrowseSortSelectionV2?,
    ) : BrowseFilterV2
}

@Serializable
public enum class BrowseTriStateV2 {
    IGNORE,
    INCLUDE,
    EXCLUDE,
}

@Serializable
public data class BrowseSortSelectionV2(
    val index: Int,
    val ascending: Boolean,
)

/** Naming aliases for callers migrating from the v1 Filter/FilterList API. */
public typealias BrowseFilterListV2 = List<BrowseFilterV2>
public typealias FilterV2 = BrowseFilterV2
public typealias FilterListV2 = BrowseFilterListV2
public typealias TriStateValueV2 = BrowseTriStateV2
public typealias SortSelectionV2 = BrowseSortSelectionV2

/** Capability predicate used by the host-owned [HostExtensionSourceV2] boundary. */
public fun SourceDescriptorV2.requireCapability(capability: ExtensionCapability) {
    require(capability in capabilities) {
        "Source ${sourceKey.canonicalId} does not advertise $capability"
    }
}

@Serializable
public data class RemotePublicationV2(
    val remoteId: String,
    val title: String,
    val url: String? = null,
    /** Optional catalogue metadata used by the host's native publication detail surface. */
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(remoteId, "Remote publication id")
        requirePrintable(title, "Remote publication title")
        url?.let { requireRemoteUri(it, "Remote publication URL") }
        thumbnailUrl?.let { requireRemoteUri(it, "Remote publication thumbnail URL") }
        author?.let { requireOptionalPrintable(it, "Remote publication author") }
        artist?.let { requireOptionalPrintable(it, "Remote publication artist") }
        description?.let { requireOptionalPrintable(it, "Remote publication description") }
        genre?.let { values ->
            require(values.size <= MAX_PARAMETERS) { "Remote publication genre list is too large" }
            values.forEach { requireOptionalPrintable(it, "Remote publication genre") }
        }
        status?.let { requireOptionalPrintable(it, "Remote publication status") }
    }
}

@Serializable
public data class RemoteUnitV2(
    val remoteId: String,
    val title: String,
    val url: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(remoteId, "Remote unit id")
        requirePrintable(title, "Remote unit title")
        url?.let { requireRemoteUri(it, "Remote unit URL") }
    }
}

@Serializable
public data class LoginCredentialsV2(
    val usernameReference: String,
    val passwordReference: String,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(usernameReference, "Username secret reference")
        requireSafeIdentifier(passwordReference, "Password secret reference")
    }
}

@Serializable
public data class LoginResultV2(
    val loggedIn: Boolean,
    val errorMessage: String? = null,
) {
    init {
        errorMessage?.let { message ->
            require(message.length <= 512 && message.none(Char::isISOControl)) {
                "Login error message must be bounded and printable"
            }
        }
    }
}

@Serializable
public data class PreferenceV2(
    val key: String,
    val label: String,
    val value: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(key, "Preference key")
        requirePrintable(label, "Preference label")
        value?.let { require(it.length <= MAX_PARAMETER_VALUE_LENGTH) }
    }
}

/** Explicit v1 adapters keep legacy Manga/Chapter and ShuYue text source APIs out of v2 identity. */
public interface LegacyMangaSourceAdapterV2 {
    public suspend fun mapManga(remotePublicationId: String): RemotePublicationV2
    public suspend fun mapChapters(remotePublicationId: String): List<RemoteUnitV2>

    public suspend fun chapterPage(
        remotePublicationId: String,
        page: Int,
        pageSize: Int = MAX_PAGE_ITEMS,
    ): PagedResultV2<RemoteUnitV2> {
        requireSafeIdentifier(remotePublicationId, "Legacy publication id")
        requirePage(page)
        require(pageSize in 1..MAX_PAGE_ITEMS) { "Legacy adapter page size is outside the host bound" }
        val chapters = mapChapters(remotePublicationId)
        require(chapters.size <= MAX_LEGACY_ADAPTER_UNITS) { "Legacy adapter returned too many units" }
        require(chapters.map(RemoteUnitV2::remoteId).distinct().size == chapters.size) {
            "Legacy adapter returned duplicate unit identities"
        }
        chapters.forEach(RemoteUnitV2::validate)
        val fromLong = page.toLong() * pageSize.toLong()
        if (fromLong >= chapters.size) return PagedResultV2(emptyList(), hasNextPage = false)
        val from = fromLong.toInt()
        val to = minOf(chapters.size, from + pageSize)
        return PagedResultV2(chapters.subList(from, to).toList(), hasNextPage = to < chapters.size)
    }
}

public interface ShuYueTextSourceAdapterV2 {
    public suspend fun chapterText(remoteUnitId: String): TextPayloadSourceV2.InlineTextPayload

    /** Converts the real ShuYue chapterText seam into an exact, scoped v2 content result. */
    public suspend fun contentResult(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
        representationId: String = "shuyue-inline-text",
    ): UnitContentResultV2 {
        sourceKey.validate()
        requireSafeIdentifier(remotePublicationId, "ShuYue publication id")
        requireSafeIdentifier(remoteUnitId, "ShuYue unit id")
        requireSafeIdentifier(representationId, "ShuYue representation id")
        val source = chapterText(remoteUnitId)
        source.validateInline()
        return UnitContentResultV2(
            schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            sourceKey = sourceKey,
            remotePublicationId = remotePublicationId,
            remoteUnitId = remoteUnitId,
            representations = listOf(
                UnitContentPayload.InlineTextPayload(
                    schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                    representationId = representationId,
                    sourceKey = sourceKey,
                    remoteUnitId = remoteUnitId,
                    source = source,
                    blocks = listOf(TextBlock("body", 0, source.text.length)),
                ),
            ),
        )
    }
}

private fun UnitContentPayload.validatePayload(): Unit = when (this) {
    is UnitContentPayload.ImageSequence -> validate()
    is UnitContentPayload.InlineTextPayload -> validate()
    is UnitContentPayload.HostFetchTextPayload -> validate()
    is UnitContentPayload.ChunkedTextPayload -> validate()
    is UnitContentPayload.EpubSpine -> validate()
}

private fun validateHeader(payload: UnitContentPayload) {
    require(payload.schemaVersion == ExtensionPackageV2.CURRENT_CONTRACT_VERSION) {
        "Unsupported unit content payload schema version"
    }
    requireSafeIdentifier(payload.representationId, "Representation id")
    payload.sourceKey.validate()
    requireSafeIdentifier(payload.remoteUnitId, "Remote unit id")
}

private fun TextPayloadSourceV2.InlineTextPayload.validateInline() {
    require(text.encodeToByteArray().size <= MAX_INLINE_TEXT_BYTES)
    require(charset == "UTF-8")
    requireMediaType(mediaType, "Inline text media type")
    require(mediaType.startsWith("text/", ignoreCase = true))
    baseUri?.let { requireRemoteUri(it, "Inline text base URI") }
}

private fun TextPayloadSourceV2.HostFetchResource.validateHostFetch() {
    body.validate()
    require(body.resource.mediaType.startsWith("text/", ignoreCase = true))
}

private fun TextPayloadSourceV2.ChunkedTextPayload.validateChunked() {
    require(charset == "UTF-8")
    requireMediaType(mediaType, "Chunked text media type")
    require(mediaType.startsWith("text/", ignoreCase = true))
    require(maxChunkBytes in 1..MAX_TEXT_CHUNK_BYTES)
    require(maxTotalBytes in 1..MAX_TEXT_STREAM_BYTES)
    require(maxChunks in 1..MAX_TEXT_STREAM_CHUNKS)
}

private fun validateBlocks(blocks: List<TextBlock>, textUtf16Length: Int? = null) {
    require(blocks.isNotEmpty()) { "Text payload requires at least one stable locator block" }
    require(blocks.size <= MAX_RESOURCES)
    require(blocks.map(TextBlock::blockId).distinct().size == blocks.size)
    blocks.forEach(TextBlock::validate)
    textUtf16Length?.let { length ->
        require(blocks.all { it.endUtf16 <= length }) { "Inline text block exceeds text length" }
    }
    require(blocks.zipWithNext().all { (a, b) -> a.endUtf16 <= b.startUtf16 })
}

private fun validateBrowseSchema(schema: BrowseOptionsSchemaV2) {
    schema.validate()
}

private fun validateBrowseOptions(options: BrowseOptionsV2) {
    options.validate()
}

private fun validateBrowseFilters(filters: List<BrowseFilterV2>, depth: Int = 0) {
    require(depth <= MAX_FILTER_DEPTH) { "Browse filter nesting is too deep" }
    require(filters.size <= MAX_PARAMETERS) { "Browse filter list is too large" }
    filters.forEach { filter ->
        if (filter !is BrowseFilterV2.Separator) {
            requirePrintable(filter.name, "Browse filter name")
        }
        when (filter) {
            is BrowseFilterV2.Header -> Unit
            BrowseFilterV2.Separator -> Unit
            is BrowseFilterV2.Select -> {
                validateFilterValues(filter.values)
                require(filter.state >= 0 && (filter.state in filter.values.indices || filter.values.isEmpty())) {
                    "Browse select filter state is outside its values"
                }
            }
            is BrowseFilterV2.Text -> validateFilterState(filter.state, "Browse text filter state")
            is BrowseFilterV2.CheckBox -> Unit
            is BrowseFilterV2.TriState -> Unit
            is BrowseFilterV2.Group -> validateBrowseFilters(filter.filters, depth + 1)
            is BrowseFilterV2.Sort -> {
                validateFilterValues(filter.values)
                filter.selection?.let { selection ->
                    require(selection.index >= 0 && (selection.index in filter.values.indices || filter.values.isEmpty())) {
                        "Browse sort filter selection is outside its values"
                    }
                }
            }
        }
    }
}

private fun validateFilterValues(values: List<String>) {
    require(values.size <= MAX_PARAMETERS) { "Browse filter values are too large" }
    values.forEach { validateFilterState(it, "Browse filter value") }
}

private fun validateFilterState(value: String, label: String) {
    require(value.length <= MAX_PARAMETER_VALUE_LENGTH && value.none(Char::isISOControl)) {
        "$label is outside the bounded limit"
    }
}

private fun validatePublicationPage(page: PagedResultV2<RemotePublicationV2>) {
    require(page.items.size <= MAX_PAGE_ITEMS) { "Extension publication page is too large" }
    require(page.items.map(RemotePublicationV2::remoteId).distinct().size == page.items.size) {
        "Extension publication page contains duplicate identities"
    }
    page.items.forEach(::validateRemotePublication)
}

private fun validateUnitPage(page: PagedResultV2<RemoteUnitV2>) {
    require(page.items.size <= MAX_PAGE_ITEMS) { "Extension unit page is too large" }
    require(page.items.map(RemoteUnitV2::remoteId).distinct().size == page.items.size) {
        "Extension unit page contains duplicate identities"
    }
    page.items.forEach(RemoteUnitV2::validate)
}

private fun validateRemotePublication(publication: RemotePublicationV2) {
    publication.validate()
}

private fun validateCredentials(credentials: LoginCredentialsV2) {
    credentials.validate()
}

private fun validatePreference(preference: PreferenceV2) {
    preference.validate()
}

private fun requireQuery(query: String) {
    require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH && query.none(Char::isISOControl)) {
        "Search query must be bounded and printable"
    }
}

private fun requirePage(page: Int) {
    require(page in 0..MAX_PAGE_NUMBER) { "Page index is outside the host bound" }
}

private fun requireCanonicalUuid(value: String, label: String) {
    require(UUID_PATTERN.matches(value) && value != NIL_UUID) { "$label must be lowercase non-NIL UUID" }
}

private fun requireSafeIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
    require(value.none { it.isWhitespace() }) { "$label must not contain whitespace" }
}

private fun requirePrintable(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_STRING_LENGTH && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
}

private fun requireOptionalPrintable(value: String, label: String) {
    require(value.length <= MAX_STRING_LENGTH && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
}

/**
 * Normalizes untrusted v1 metadata before it crosses the v2 publication boundary.
 *
 * Legacy ShuYue/Shinsou scripts commonly return HTML-derived descriptions containing line
 * breaks, NULs, or much more text than a compact catalogue card can carry. The v2 contract still
 * rejects control characters and unbounded strings, so adapters must make that compatibility
 * conversion explicitly instead of weakening the host-side validator.
 */
internal fun String.toBoundedRemoteMetadata(): String = buildString(minOf(length, MAX_STRING_LENGTH)) {
    for (character in this@toBoundedRemoteMetadata) {
        append(if (character.isISOControl()) ' ' else character)
        if (length == MAX_STRING_LENGTH) break
    }
}.trim()

internal fun String?.toOptionalBoundedRemoteMetadata(): String? =
    this?.toBoundedRemoteMetadata()?.takeIf(String::isNotBlank)

private fun requireMediaType(value: String, label: String) {
    require(value.length <= 256 && MEDIA_TYPE_PATTERN.matches(value)) { "$label is invalid" }
}

private fun requireRemoteUri(value: String, label: String) {
    require(value.length <= MAX_URI_LENGTH && value.none { it.isISOControl() || it.isWhitespace() } && '\\' !in value) {
        "$label must be a bounded printable URI"
    }
    require('#' !in value) { "$label must not contain a fragment" }
    validatePercentEscapes(value, label, rejectEncodedPathSeparators = false)
    val parsed = runCatching { Url(value) }.getOrElse {
        throw IllegalArgumentException("$label must be an absolute HTTP(S) URI", it)
    }
    val scheme = parsed.protocol.name.lowercase()
    require(scheme == "http" || scheme == "https") { "$label must use HTTP(S)" }
    require(parsed.host.isNotBlank()) { "$label must contain a host" }
    val authority = authorityOf(value)
    require('@' !in authority && '%' !in authority && authority.isNotBlank()) {
        "$label must contain a canonical authority without userinfo"
    }
    validateAuthority(authority, label)
}

private fun requireSafeRelativePath(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_URI_LENGTH && !value.contains('\\') &&
        value.none { it.isISOControl() || it.isWhitespace() }) {
        "$label must be bounded and safe"
    }
    require('#' !in value) { "$label must not contain a fragment" }
    require(!value.startsWith('/') && !value.startsWith("//") && !SCHEME_PREFIX.containsMatchIn(value)) {
        "$label must be relative"
    }
    val path = value.substringBefore('?')
    require(path.isNotBlank()) { "$label path must not be blank" }
    validatePercentEscapes(value, label, rejectEncodedPathSeparators = true)
    val decoded = percentDecodeStrict(path, label)
    require(!decoded.startsWith('/') && !decoded.startsWith("//") && '\\' !in decoded &&
        decoded.none(Char::isISOControl) && !SCHEME_PREFIX.containsMatchIn(decoded) &&
        "://" !in decoded) {
        "$label must remain a safe relative path after decoding"
    }
    require(decoded.split('/').none { it == "." || it == ".." }) {
        "$label contains traversal"
    }
}

private fun requireHeaderHint(name: String, value: String) {
    require(name.length in 1..128 && value.length <= MAX_HEADER_VALUE_LENGTH && RFC_TOKEN.matches(name)) {
        "Request header hint is outside bounds"
    }
    require(name.none(Char::isISOControl) && value.none(Char::isISOControl)) {
        "Request header hint contains control characters"
    }
    require(name.lowercase() in SAFE_HEADER_HINTS) { "Header is not allowed as a source hint" }
}

private fun percentDecodeStrict(value: String, label: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "$label contains an incomplete percent escape" }
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            require(decoded != null) { "$label contains an invalid percent escape" }
            require(decoded != '%'.code) { "$label contains a nested percent escape" }
            output.append(decoded.toChar())
            index += 3
            continue
        }
        output.append(value[index++])
    }
    return output.toString()
}

private fun validatePercentEscapes(value: String, label: String, rejectEncodedPathSeparators: Boolean) {
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            index++
            continue
        }
        require(index + 2 < value.length) { "$label contains an incomplete percent escape" }
        val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
        require(decoded != null) { "$label contains an invalid percent escape" }
        require(decoded != '%'.code && decoded !in 0x00..0x1f && decoded != 0x7f) {
            "$label contains an unsafe encoded character"
        }
        if (rejectEncodedPathSeparators) {
            require(decoded != '/'.code && decoded != '\\'.code && decoded != ':'.code) {
                "$label contains an encoded separator or scheme delimiter"
            }
        }
        index += 3
    }
}

private fun authorityOf(value: String): String {
    val marker = value.indexOf("://")
    require(marker > 0) { "URI is not absolute" }
    val rest = value.substring(marker + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    return if (end < 0) rest else rest.substring(0, end)
}

private fun validateAuthority(authority: String, label: String) {
    val portText = if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        require(close > 1 && authority.indexOf('[', 1) < 0 && authority.indexOf(']', close + 1) < 0) {
            "$label contains malformed IPv6 authority"
        }
        val suffix = authority.substring(close + 1)
        require(suffix.isEmpty() || suffix.startsWith(':')) { "$label contains malformed IPv6 authority" }
        suffix.takeIf(String::isNotEmpty)?.substring(1)
    } else {
        require(authority.count { it == ':' } <= 1) { "$label contains malformed authority" }
        authority.substringAfter(':', "").takeIf { ':' in authority }
    }
    if (portText != null) {
        require(portText.isNotEmpty() && portText.all(Char::isDigit) && !portText.startsWith('+')) {
            "$label contains an invalid port"
        }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65_535) { "$label contains an invalid port" }
    }
}

private fun String.substringBeforeAnyOf(vararg delimiters: Char): String {
    val index = indexOfFirst { it in delimiters }
    return if (index < 0) this else substring(0, index)
}

private const val MAX_STRING_LENGTH = 512
private const val MAX_IDENTIFIER_LENGTH = 512
private const val MAX_URI_LENGTH = 4_096
private const val MAX_HEADER_VALUE_LENGTH = 1_024
private const val MAX_HEADERS = 32
private const val MAX_PARAMETERS = 128
private const val MAX_PARAMETER_VALUE_LENGTH = 4_096
private const val MAX_FILTER_DEPTH = 8
private const val MAX_REQUEST_BODY_BYTES = 8L * 1024L * 1024L
private const val MAX_RESPONSE_BYTES = 128L * 1024L * 1024L
private const val MAX_INLINE_TEXT_BYTES = 4L * 1024L * 1024L
private const val MAX_TEXT_CHUNK_BYTES = 256 * 1024
private const val DEFAULT_TEXT_CHUNK_BYTES = 64 * 1024
private const val MAX_TEXT_STREAM_BYTES = 512L * 1024L * 1024L
private const val DEFAULT_MAX_TEXT_STREAM_BYTES = 64L * 1024L * 1024L
private const val MAX_TEXT_STREAM_CHUNKS = 65_536
private const val DEFAULT_MAX_TEXT_STREAM_CHUNKS = 4_096
private const val MAX_RESOURCES = 100_000
private const val MAX_REPRESENTATIONS = 32
private const val MAX_SOURCES_PER_PACKAGE = 256
private const val MAX_PAGE_ITEMS = 100
private const val MAX_PAGE_NUMBER = 1_000_000
private const val MAX_QUERY_LENGTH = 4_096
private const val MAX_PREFERENCES = 256
private const val MAX_LEGACY_ADAPTER_UNITS = 100_000
private const val EPUB_ARCHIVE_MEDIA_TYPE = "application/epub+zip"
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val MEDIA_TYPE_PATTERN = Regex("[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+/[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+")
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val SAFE_HEADER_HINTS = setOf(
    "accept", "accept-language", "content-type", "referer", "user-agent", "origin",
    "x-requested-with",
)
private val RFC_TOKEN = Regex("[!#${'$'}%&'*+.^_`|~0-9A-Za-z-]+")
