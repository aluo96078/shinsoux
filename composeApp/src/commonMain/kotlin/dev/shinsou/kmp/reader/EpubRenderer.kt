package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Host gate invoked for every browser read, including reads served from the local lazy cache. */
public fun interface EpubResourceReadGate {
    public fun read(operation: () -> ByteArray?): ByteArray?

    public companion object {
        public val Direct: EpubResourceReadGate = EpubResourceReadGate { operation -> operation() }
    }
}

/** Exact immutable publisher resource loaded only when the browser requests its private URL. */
public class EpubRenderResource internal constructor(
    public val resourceId: String,
    public val href: String,
    public val mediaType: String,
    private val declaredByteSize: Long,
    internal val scriptedContent: Boolean,
    private val readGate: EpubResourceReadGate,
    private val bodyLoader: (maximumBytes: Int, chunkBytes: Int) -> ByteArray,
) {
    public constructor(
        resourceId: String,
        href: String,
        mediaType: String,
        bytes: ByteArray,
    ) : this(
        resourceId = resourceId,
        href = href,
        mediaType = mediaType,
        declaredByteSize = bytes.size.toLong(),
        scriptedContent = mediaType.isScriptMediaType(),
        readGate = EpubResourceReadGate.Direct,
        bodyLoader = eagerBodyLoader(bytes),
    )

    /**
     * Compatibility accessor for small callers. Production browser reads go through a resolver's
     * stricter policy and never install one lazy ByteArray per package resource.
     */
    public val bytes: ByteArray
        get() = gatedRead {
            loadBody(
                maximumBytes = EpubRenderMemoryPolicy.DEFAULT_MAXIMUM_RESOLVED_RESOURCE_BYTES,
                chunkBytes = EpubRenderMemoryPolicy.DEFAULT_READ_CHUNK_BYTES,
            )
        }

    public val byteSize: Long get() = declaredByteSize

    internal fun gatedRead(operation: () -> ByteArray): ByteArray =
        requireNotNull(readGate.read(operation)) {
            "EPUB renderer resource read was denied: $resourceId"
        }

    internal fun loadBody(maximumBytes: Int, chunkBytes: Int): ByteArray {
        require(declaredByteSize <= maximumBytes.toLong()) {
            "EPUB renderer resource exceeds the per-response limit: $resourceId"
        }
        return bodyLoader(maximumBytes, chunkBytes).also { loaded ->
            require(loaded.size.toLong() == declaredByteSize) {
                "EPUB renderer resource size changed while loading: $resourceId"
            }
        }
    }
}

/** Strict process-memory limits for one browser publication resolver. */
public data class EpubRenderMemoryPolicy(
    val maximumResolvedResourceBytes: Int = DEFAULT_MAXIMUM_RESOLVED_RESOURCE_BYTES,
    val maximumDocumentBytes: Int = DEFAULT_MAXIMUM_DOCUMENT_BYTES,
    val maximumCacheBytes: Int = DEFAULT_MAXIMUM_CACHE_BYTES,
    val maximumCachedResourceBytes: Int = DEFAULT_MAXIMUM_CACHED_RESOURCE_BYTES,
    val readChunkBytes: Int = DEFAULT_READ_CHUNK_BYTES,
) {
    init {
        require(maximumResolvedResourceBytes in 1..MAXIMUM_ALLOWED_RESOURCE_BYTES) {
            "EPUB renderer resource limit is invalid"
        }
        require(maximumDocumentBytes in 1..maximumResolvedResourceBytes) {
            "EPUB renderer document limit is invalid"
        }
        require(maximumCacheBytes in 0..MAXIMUM_ALLOWED_CACHE_BYTES) {
            "EPUB renderer cache limit is invalid"
        }
        require(maximumCachedResourceBytes in 0..maximumResolvedResourceBytes) {
            "EPUB renderer cached-resource limit is invalid"
        }
        require(maximumCacheBytes == 0 || maximumCachedResourceBytes <= maximumCacheBytes) {
            "EPUB renderer cached resource cannot exceed the cache"
        }
        require(readChunkBytes in 1..MAXIMUM_READ_CHUNK_BYTES) {
            "EPUB renderer read chunk is invalid"
        }
    }

    public companion object {
        public const val DEFAULT_MAXIMUM_RESOLVED_RESOURCE_BYTES: Int = 32 * 1024 * 1024
        public const val DEFAULT_MAXIMUM_DOCUMENT_BYTES: Int = 8 * 1024 * 1024
        public const val DEFAULT_MAXIMUM_CACHE_BYTES: Int = 8 * 1024 * 1024
        public const val DEFAULT_MAXIMUM_CACHED_RESOURCE_BYTES: Int = 2 * 1024 * 1024
        public const val DEFAULT_READ_CHUNK_BYTES: Int = 64 * 1024
        private const val MAXIMUM_ALLOWED_RESOURCE_BYTES: Int = 64 * 1024 * 1024
        private const val MAXIMUM_ALLOWED_CACHE_BYTES: Int = 32 * 1024 * 1024
        private const val MAXIMUM_READ_CHUNK_BYTES: Int = 1024 * 1024
    }
}

/** User CSS is intentionally a separate layer and is never merged into publisher resource bytes. */
public data class EpubUserStyleSheet(
    val styleId: String,
    val css: String,
) {
    init {
        require(styleId.isNotBlank() && styleId.length <= MAX_STYLE_ID_LENGTH &&
            styleId.none(Char::isWhitespace) && styleId.none(Char::isISOControl)
        ) { "EPUB user-style id is invalid" }
        require(css.length <= MAX_USER_STYLE_LENGTH && '\u0000' !in css && css.isWellFormedUtf16()) {
            "EPUB user style is too large or contains NUL"
        }
    }
}

/** Mandatory platform-renderer restrictions for untrusted publication documents. */
public data class EpubRenderSecurityPolicy(
    val allowExternalNetwork: Boolean = false,
    val allowScriptedContent: Boolean = false,
    val publicationScheme: String = "shinsou-epub",
) {
    init {
        require(!allowExternalNetwork) { "EPUB external network access must remain disabled" }
        require(!allowScriptedContent) { "EPUB scripted content must remain disabled" }
        require(SAFE_SCHEME.matches(publicationScheme)) { "EPUB publication scheme is invalid" }
    }
}

public class EpubRenderRequest(
    public val navigation: EpubSpineNavigation,
    public val documentIndex: Int,
    public val initialLocator: ReadingLocator.Epub,
    publisherResources: List<EpubRenderResource>,
    userStyleSheets: List<EpubUserStyleSheet> = emptyList(),
    semanticDocument: EpubSemanticDocument? = null,
    public val securityPolicy: EpubRenderSecurityPolicy = EpubRenderSecurityPolicy(),
) {
    public val publisherResources: List<EpubRenderResource> = publisherResources.toList()
    public val userStyleSheets: List<EpubUserStyleSheet> = userStyleSheets.toList()
    public val document: EpubRenderResource
    public val publisherStyleSheets: List<EpubRenderResource>
    public val publicationRootUrl: String
    public val documentUrl: String
    private val semanticLock = SynchronousLock()
    private var loadedSemanticDocument: EpubSemanticDocument? = semanticDocument

    public val semanticDocument: EpubSemanticDocument?
        get() = semanticLock.withLock { loadedSemanticDocument }

    init {
        require(documentIndex in 0 until navigation.itemCount) { "EPUB render document index is out of range" }
        require(navigation.indexOf(initialLocator) == documentIndex) {
            "EPUB initial locator does not belong to the rendered spine document"
        }
        semanticDocument?.let { semantic ->
            val spine = navigation.representation.documents[documentIndex]
            require(
                semantic.representationId == navigation.representationId &&
                    semantic.documentIndex == documentIndex &&
                    semantic.resourceId == spine.resourceId && semantic.resourceHref == spine.href,
            ) { "EPUB semantic document does not match the render request" }
        }
        require(this.publisherResources.isNotEmpty()) { "EPUB renderer needs publisher resources" }
        require(this.publisherResources.map(EpubRenderResource::resourceId).distinct().size ==
            this.publisherResources.size) { "EPUB renderer resource ids must be unique" }
        require(this.publisherResources.map(EpubRenderResource::href).distinct().size ==
            this.publisherResources.size) { "EPUB renderer resource hrefs must be unique" }
        require(this.userStyleSheets.map(EpubUserStyleSheet::styleId).distinct().size ==
            this.userStyleSheets.size) { "EPUB user-style ids must be unique" }
        require(this.userStyleSheets.size <= MAX_USER_STYLE_SHEETS &&
            this.userStyleSheets.sumOf { it.css.length.toLong() } <= MAX_USER_STYLE_TOTAL_LENGTH
        ) { "EPUB user-style layer exceeds its configured bounds" }
        val spineDocument = navigation.representation.documents[documentIndex]
        document = requireNotNull(this.publisherResources.firstOrNull { resource ->
            resource.resourceId == spineDocument.resourceId && resource.href == spineDocument.href
        }) { "EPUB spine document is missing from renderer resources" }
        publisherStyleSheets = this.publisherResources.filter { resource ->
            resource.mediaType.equals("text/css", ignoreCase = true)
        }
        val scope = navigation.scope
        publicationRootUrl = buildString {
            append(securityPolicy.publicationScheme)
            append("://publication/")
            append(scope.publicationId.value)
            append('/')
            append(scope.acquisitionId)
            append('/')
            append(scope.unitId.value)
            append('/')
            append(scope.contentRevision)
            append('/')
            append(navigation.representationId)
            append('/')
        }
        documentUrl = publicationUrl(document)
    }

    public fun resourceByHref(href: String): EpubRenderResource? =
        publisherResources.firstOrNull { it.href == href }

    /** Browser adapters use this URL as their custom-scheme response key and document base URL. */
    public fun publicationUrl(resource: EpubRenderResource): String {
        require(resourceByHref(resource.href)?.resourceId == resource.resourceId) {
            "EPUB render resource does not belong to this request"
        }
        return "$publicationRootUrl${resource.href}"
    }

    public fun resourceByPublicationUrl(url: String): EpubRenderResource? {
        val href = privatePublicationHref(url, publicationRootUrl) ?: return null
        return resourceByHref(href)
    }

    public val initialDocumentProgression: Double
        get() = navigation.documentProgression(initialLocator) ?: 0.0

    public fun locatorForDocumentProgression(progression: Double): ReadingLocator.Epub =
        semanticDocument?.locatorForProgressionWithQuote(navigation, progression)
            ?: navigation.locatorForDocumentProgression(documentIndex, progression)

    public fun rangeForSelection(selection: EpubBrowserSelectionSnapshot): ReadingRange? =
        semanticDocument?.rangeForSelection(
            navigation = navigation,
            exactSelection = selection.text,
            viewportProgression = selection.progression,
        )

    /** Installs rebuildable semantic data without reloading or replacing publisher DOM/CSS. */
    public fun installSemanticDocument(document: EpubSemanticDocument) {
        require(
            document.representationId == navigation.representationId &&
                document.documentIndex == documentIndex &&
                document.resourceId == this.document.resourceId &&
                document.resourceHref == this.document.href,
        ) { "EPUB semantic document does not match the render request" }
        semanticLock.withLock { loadedSemanticDocument = document }
    }
}

@Serializable
public data class EpubBrowserSelectionSnapshot(
    val progression: Double,
    val text: String,
) {
    init {
        require(progression.isFinite() && progression in 0.0..1.0) {
            "EPUB selection progression is invalid"
        }
        require(text.length <= MAX_BROWSER_SELECTION_UTF16 && text.isWellFormedUtf16()) {
            "EPUB browser selection is invalid"
        }
    }
}

/**
 * Platform-neutral viewport event coalescer. Native scroll callbacks may arrive for every pixel;
 * portable locator construction and Compose delivery happen only at a bounded cadence/delta.
 */
public class EpubViewportLocatorCoalescer(
    request: EpubRenderRequest,
    private val minimumIntervalMillis: Long = DEFAULT_LOCATOR_INTERVAL_MILLIS,
    private val minimumProgressionDelta: Double = DEFAULT_LOCATOR_PROGRESSION_DELTA,
) {
    private var activeRequest: EpubRenderRequest = request
    private var lastEmittedAtMillis: Long? = null
    private var lastEmittedProgression: Double? = null

    init {
        require(minimumIntervalMillis in 16..5_000) { "EPUB locator interval is invalid" }
        require(minimumProgressionDelta.isFinite() && minimumProgressionDelta in 0.0..0.25) {
            "EPUB locator progression delta is invalid"
        }
    }

    public fun updateRequest(request: EpubRenderRequest) {
        activeRequest = request
        lastEmittedAtMillis = null
        lastEmittedProgression = null
    }

    public fun offer(
        progression: Double,
        nowMillis: Long,
        force: Boolean = false,
    ): ReadingLocator.Epub? {
        require(progression.isFinite()) { "EPUB viewport progression is invalid" }
        require(nowMillis >= 0) { "EPUB locator event time is invalid" }
        val normalized = progression.coerceIn(0.0, 1.0)
        val previousTime = lastEmittedAtMillis
        val previousProgression = lastEmittedProgression
        val intervalReady = previousTime == null || nowMillis - previousTime >= minimumIntervalMillis
        val distanceReady = previousProgression == null ||
            kotlin.math.abs(normalized - previousProgression) >= minimumProgressionDelta
        if (!force && (!intervalReady || !distanceReady)) return null
        lastEmittedAtMillis = nowMillis
        lastEmittedProgression = normalized
        return activeRequest.locatorForDocumentProgression(normalized)
    }
}

internal fun decodeEpubBrowserSelection(json: String): EpubBrowserSelectionSnapshot? = runCatching {
    BrowserSelectionJson.decodeFromString(EpubBrowserSelectionSnapshot.serializer(), json)
}.getOrNull()

/** Immutable response produced for an embedded browser's custom-scheme callback. */
public class EpubRenderResponse private constructor(
    public val mediaType: String,
    public val textEncoding: String?,
    bytes: ByteArray,
    headers: Map<String, String>,
    takeBodyOwnership: Boolean,
) {
    public constructor(
        mediaType: String,
        textEncoding: String?,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ) : this(mediaType, textEncoding, bytes, headers, takeBodyOwnership = false)

    private val body = if (takeBodyOwnership) bytes else bytes.copyOf()
    public val bytes: ByteArray get() = body.copyOf()
    public val byteSize: Int get() = body.size
    public val headers: Map<String, String> = headers.toMap()

    internal fun unsafeBytes(): ByteArray = body

    internal companion object {
        fun owned(
            mediaType: String,
            textEncoding: String?,
            bytes: ByteArray,
            headers: Map<String, String>,
        ): EpubRenderResponse = EpubRenderResponse(
            mediaType = mediaType,
            textEncoding = textEncoding,
            bytes = bytes,
            headers = headers,
            takeBodyOwnership = true,
        )
    }
}

/**
 * Exact `shinsou-epub` resource map shared by WebView, WKWebView and Desktop WebKit.
 *
 * No fallback URL fetch exists: an unknown URL resolves to null.  Browser adapters turn that into
 * a blocked response.  Publisher resources remain byte-exact in [EpubRenderRequest]; the resolver
 * creates a transient HTML response that adds a CSP and links the separate reader style layer.
 */
public class EpubPublicationResourceResolver(
    public val request: EpubRenderRequest,
    public val memoryPolicy: EpubRenderMemoryPolicy = EpubRenderMemoryPolicy(),
) {
    private val lifecycleLock = SynchronousLock()
    private val bodyCache = EpubRenderBodyCache(memoryPolicy)
    private val userStyleNamespace: String? = if (request.userStyleSheets.isEmpty()) {
        null
    } else {
        selectUserStyleNamespace(request.publisherResources)
    }
    private val userStyleByteSizes: List<Int> = request.userStyleSheets.map { style ->
        style.css.utf8ByteSizeAtMost(memoryPolicy.maximumResolvedResourceBytes, style.styleId)
    }

    public val userStyleUrls: List<String> = userStyleNamespace?.let { namespace ->
        request.userStyleSheets.indices.map { index ->
            "${request.publicationRootUrl}$namespace$index.css"
        }
    }.orEmpty()
    private val userStyleIndexByUrl: Map<String, Int> = userStyleUrls.withIndex().associate { (index, url) ->
        url to index
    }

    public val isClosed: Boolean get() = lifecycleLock.withLock { bodyCache.isClosed }

    internal val cachedByteSize: Int get() = bodyCache.byteSize
    internal val cachedResourceCount: Int get() = bodyCache.resourceCount

    public fun resolve(url: String): EpubRenderResponse? = lifecycleLock.withLock {
        if (bodyCache.isClosed) return@withLock null
        val normalized = privatePublicationUrl(url, request.publicationRootUrl) ?: return@withLock null
        val styleIndex = userStyleIndexByUrl[normalized]
        if (styleIndex != null) {
            val styleBytes = bodyCache.getOrLoad(EpubRenderCacheKey.UserStyle(styleIndex)) {
                request.userStyleSheets[styleIndex].css.encodeToByteArray().also { encoded ->
                    check(encoded.size == userStyleByteSizes[styleIndex]) {
                        "EPUB user style UTF-8 size changed while encoding"
                    }
                }
            }
            return@withLock EpubRenderResponse.owned(
                mediaType = CSS_MEDIA_TYPE,
                textEncoding = UTF8_ENCODING,
                bytes = styleBytes,
                headers = secureResponseHeaders(CSS_MEDIA_TYPE, request.securityPolicy.publicationScheme),
            )
        }
        val resource = request.resourceByPublicationUrl(normalized) ?: return@withLock null
        if (resource.scriptedContent) return@withLock null
        val maximumBodyBytes = if (resource.isHtmlDocument()) {
            memoryPolicy.maximumDocumentBytes
        } else {
            memoryPolicy.maximumResolvedResourceBytes
        }
        val rendered = resource.gatedRead {
            bodyCache.getOrLoad(EpubRenderCacheKey.Publisher(resource.resourceId)) {
                val publisherBody = resource.loadBody(maximumBodyBytes, memoryPolicy.readChunkBytes)
                val responseBody = if (resource.isHtmlDocument()) {
                    secureHtmlResponse(resource, publisherBody)
                } else {
                    publisherBody
                }
                require(responseBody.size <= memoryPolicy.maximumResolvedResourceBytes) {
                    "EPUB renderer response exceeds the per-response limit: ${resource.resourceId}"
                }
                responseBody
            }
        }
        return@withLock EpubRenderResponse.owned(
            mediaType = resource.mediaType,
            textEncoding = resource.textEncoding(),
            bytes = rendered,
            headers = secureResponseHeaders(resource.mediaType, request.securityPolicy.publicationScheme),
        )
    }

    /** Metadata-only URL admission. It must not hydrate a body on a navigation-policy callback. */
    public fun contains(url: String): Boolean = lifecycleLock.withLock {
        if (bodyCache.isClosed) return@withLock false
        val normalized = privatePublicationUrl(url, request.publicationRootUrl) ?: return@withLock false
        if (normalized in userStyleIndexByUrl) return@withLock true
        request.resourceByPublicationUrl(normalized)?.scriptedContent == false
    }

    /** Maps a browser navigation back to the same stable EPUB spine identity. */
    public fun locatorForUrl(url: String): ReadingLocator.Epub? = lifecycleLock.withLock {
        if (bodyCache.isClosed) return@withLock null
        val normalized = privatePublicationUrl(url, request.publicationRootUrl) ?: return@withLock null
        val resource = request.resourceByPublicationUrl(normalized) ?: return@withLock null
        if (resource.scriptedContent) return@withLock null
        val index = request.navigation.representation.documents.indexOfFirst { document ->
            document.resourceId == resource.resourceId && document.href == resource.href
        }
        if (index >= 0) request.navigation.locatorAt(index) else null
    }

    /**
     * Linearization contract: an already-admitted resolve finishes before close returns; after
     * close returns, every resolve/admission/navigation call fails without hydrating a body.
     */
    public fun close() {
        lifecycleLock.withLock { bodyCache.close() }
    }

    private fun secureHtmlResponse(resource: EpubRenderResource, publisherBody: ByteArray): ByteArray {
        val html = decodeBrowserDocument(publisherBody)
        val normalizedDeclaration = XML_ENCODING.replace(html) { match ->
            match.groupValues[1] + "UTF-8" + match.groupValues[3]
        }
        val head = scanHtmlHead(normalizedDeclaration)
        val openingHeadEnd = head.openingTagEndExclusive
        if (openingHeadEnd == null) {
            throw IllegalArgumentException("EPUB browser document has no bounded head element: ${resource.href}")
        }
        val closingHeadStart = head.closingTagStart
        if (closingHeadStart == null) {
            throw IllegalArgumentException(
                "EPUB browser document has no bounded closing head element: ${resource.href}",
            )
        }
        val styleLinks = userStyleUrls.joinToString(separator = "") { styleUrl ->
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"$styleUrl\"/>"
        }
        val securityLayer = buildString {
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            append(contentSecurityPolicy(request.securityPolicy.publicationScheme))
            append("\"/>")
        }
        val secured = normalizedDeclaration.replaceRange(
            openingHeadEnd,
            openingHeadEnd,
            securityLayer,
        )
        val userStyleInsertion = closingHeadStart + securityLayer.length
        return secured.replaceRange(
            userStyleInsertion,
            userStyleInsertion,
            styleLinks,
        ).encodeToByteArray()
    }
}

/** Access-order cache whose total and per-entry bounds are independent of package resource count. */
private sealed interface EpubRenderCacheKey {
    data class Publisher(val resourceId: String) : EpubRenderCacheKey
    data class UserStyle(val index: Int) : EpubRenderCacheKey
}

private class EpubRenderBodyCache(
    private val policy: EpubRenderMemoryPolicy,
) {
    private val lock = SynchronousLock()
    private val bodies = LinkedHashMap<EpubRenderCacheKey, ByteArray>()
    private var cachedBytes = 0
    private var closed = false

    val isClosed: Boolean get() = lock.withLock { closed }
    val byteSize: Int get() = lock.withLock { cachedBytes }
    val resourceCount: Int get() = lock.withLock { bodies.size }

    /**
     * Loading is deliberately performed under the cache lock. This strictly serializes bounded
     * hydration, guarantees one loader invocation per cached key, and gives close one unambiguous
     * point after every admitted in-flight load.
     */
    fun getOrLoad(key: EpubRenderCacheKey, loader: () -> ByteArray): ByteArray = lock.withLock {
        check(!closed) { "EPUB publication resolver is closed" }
        bodies.remove(key)?.let { cached ->
            bodies[key] = cached
            return@withLock cached
        }

        val loaded = loader()
        require(loaded.size <= policy.maximumResolvedResourceBytes) {
            "EPUB renderer cache input exceeds the per-response limit"
        }
        if (loaded.size > policy.maximumCachedResourceBytes || policy.maximumCacheBytes == 0) {
            return@withLock loaded
        }
        while (cachedBytes + loaded.size > policy.maximumCacheBytes && bodies.isNotEmpty()) {
            val eldestKey = bodies.keys.first()
            val eldestSize = requireNotNull(bodies[eldestKey]).size
            bodies.remove(eldestKey)
            cachedBytes -= eldestSize
        }
        if (loaded.size <= policy.maximumCacheBytes) {
            bodies[key] = loaded
            cachedBytes += loaded.size
        }
        loaded
    }

    fun close() {
        lock.withLock {
            if (!closed) {
                closed = true
                bodies.clear()
                cachedBytes = 0
            }
        }
    }
}

/** Factory creates a metadata-only request; each requested resource is read and verified lazily. */
public class EpubRenderRequestFactory(
    private val blobStore: ContentBlobStore,
) {
    public fun create(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        initialLocator: ReadingLocator.Epub = navigation.locatorAt(documentIndex),
        userStyleSheets: List<EpubUserStyleSheet> = emptyList(),
        semanticDocument: EpubSemanticDocument? = null,
        resourceReadGate: EpubResourceReadGate = EpubResourceReadGate.Direct,
    ): EpubRenderRequest {
        val resources = navigation.representation.packageGraph.resources.map { resource ->
            lazyResource(resource, resourceReadGate)
        }
        return EpubRenderRequest(
            navigation = navigation,
            documentIndex = documentIndex,
            initialLocator = initialLocator,
            publisherResources = resources,
            userStyleSheets = userStyleSheets,
            semanticDocument = semanticDocument,
        )
    }

    private fun lazyResource(
        resource: EpubResource,
        resourceReadGate: EpubResourceReadGate,
    ): EpubRenderResource {
        val reference = resource.resource.blob
        return EpubRenderResource(
            resourceId = resource.id,
            href = resource.href,
            mediaType = resource.mediaType,
            declaredByteSize = reference.byteSize,
            scriptedContent = resource.properties.any { it.equals("scripted", ignoreCase = true) } ||
                resource.mediaType.isScriptMediaType(),
            readGate = resourceReadGate,
            bodyLoader = { maximumBytes, chunkBytes ->
                blobStore.readExactForRenderer(
                    reference = reference,
                    resourceId = resource.id,
                    maximumBytes = maximumBytes,
                    chunkBytes = chunkBytes,
                )
            },
        )
    }
}

/** Android WebView, iOS WKWebView and desktop browser engines implement this testable seam. */
public interface EpubBrowserRenderer {
    public suspend fun open(request: EpubRenderRequest): EpubBrowserRenderSession
}

public interface EpubBrowserRenderSession {
    public val currentLocator: ReadingLocator.Epub
    public suspend fun navigate(locator: ReadingLocator.Epub)
    public fun close()
}

private val SAFE_SCHEME = Regex("[a-z][a-z0-9+.-]{1,31}")
private const val MAX_STYLE_ID_LENGTH: Int = 256
private const val MAX_USER_STYLE_LENGTH: Int = 1_048_576
private const val MAX_USER_STYLE_SHEETS: Int = 32
private const val MAX_USER_STYLE_TOTAL_LENGTH: Long = 4L * 1_048_576
private const val READER_STYLE_NAMESPACE_BASE: String = ".shinsou-reader/user-style-layer"
private const val CSS_MEDIA_TYPE: String = "text/css"
private const val UTF8_ENCODING: String = "UTF-8"
private const val MAX_BROWSER_SELECTION_UTF16: Int = 256
private const val DEFAULT_LOCATOR_INTERVAL_MILLIS: Long = 120L
private const val DEFAULT_LOCATOR_PROGRESSION_DELTA: Double = 0.002
private const val CONTENT_SECURITY_POLICY_PREFIX: String =
    "default-src 'none'; script-src 'none'; connect-src 'none'; object-src 'none'; " +
        "frame-src 'none'; child-src 'none'; worker-src 'none'; manifest-src 'none'; " +
        "base-uri 'none'; form-action 'none'; "
private const val MAX_PRIVATE_PUBLICATION_URL_LENGTH: Int = 16 * 1024
private const val MAX_SCANNED_MARKUP_SECTION_CHARS: Int = 1_048_576

private val XML_ENCODING = Regex("(?i)(<\\?xml[^>]*?encoding\\s*=\\s*([\"']))[^\"']+([\"'][^>]*?\\?>)")
private val URI_SCHEME = Regex("(?i)^[a-z][a-z0-9+.-]*:")
private val DANGEROUS_ENCODED_PATH = Regex("(?i)%(?:00|25|2e|2f|5c)")
private val BrowserSelectionJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
private val HTML_RAW_TEXT_ELEMENTS: Set<String> = setOf("script", "style", "title", "textarea")
private val HTML_VOID_ELEMENTS: Set<String> = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
    "source", "track", "wbr",
)

private data class HtmlHeadScan(
    val openingTagEndExclusive: Int?,
    val closingTagStart: Int?,
)

private data class HtmlMarkupTag(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val endExclusive: Int,
)

/**
 * Bounded, allocation-light markup scanner used only to find actual head tags. It never treats
 * strings inside comments, CDATA, processing instructions, declarations, quoted attributes, or
 * raw-text elements as markup. Full HTML parsing remains the browser engine's responsibility.
 */
private fun scanHtmlHead(source: String): HtmlHeadScan {
    var cursor = 0
    var openingHeadEnd: Int? = null
    var openingHeadDepth: Int? = null
    var rawTextElement: String? = null
    val openElements = ArrayList<String>()
    while (cursor < source.length) {
        val opening = source.indexOf('<', cursor)
        if (opening < 0) break

        if (rawTextElement != null) {
            val closingEnd = scanRawTextClosingTag(source, opening, rawTextElement)
            if (closingEnd != null) {
                closeScannedElement(openElements, rawTextElement)
                rawTextElement = null
                cursor = closingEnd
            } else {
                cursor = opening + 1
            }
            continue
        }

        cursor = when {
            source.startsWith("<!--", opening) ->
                boundedDelimitedEnd(source, opening + 4, "-->", "comment")
            source.startsWith("<![CDATA[", opening) ->
                boundedDelimitedEnd(source, opening + 9, "]]>", "CDATA section")
            source.startsWith("<?", opening) ->
                boundedDelimitedEnd(source, opening + 2, "?>", "processing instruction")
            source.startsWith("<!", opening) -> scanMarkupDeclarationEnd(source, opening)
            else -> {
                val tag = scanHtmlTag(source, opening)
                if (tag == null) {
                    opening + 1
                } else {
                    val tagName = tag.name.lowercase()
                    if (tag.closing) {
                        if (openingHeadEnd != null && tagName == "head" &&
                            openingHeadDepth == openElements.size && openElements.lastOrNull() == "head"
                        ) {
                            return HtmlHeadScan(openingHeadEnd, opening)
                        }
                        closeScannedElement(openElements, tagName)
                    } else {
                        val parent = openElements.lastOrNull()
                        val isDocumentHead = openingHeadEnd == null && !tag.selfClosing && tagName == "head" &&
                            (parent == null || parent == "html")
                        if (isDocumentHead) {
                            openingHeadEnd = tag.endExclusive
                        }
                        if (!tag.selfClosing && tagName !in HTML_VOID_ELEMENTS) {
                            openElements += tagName
                            if (isDocumentHead) openingHeadDepth = openElements.size
                        }
                        if (!tag.selfClosing && tagName in HTML_RAW_TEXT_ELEMENTS) rawTextElement = tagName
                    }
                    tag.endExclusive
                }
            }
        }
    }
    return HtmlHeadScan(openingHeadEnd, null)
}

private fun closeScannedElement(openElements: ArrayList<String>, name: String) {
    val matchingIndex = openElements.indexOfLast { it == name }
    if (matchingIndex < 0) return
    while (openElements.size > matchingIndex) openElements.removeAt(openElements.lastIndex)
}

/**
 * Raw-text content is not markup. Only a complete matching end tag can leave raw-text mode; every
 * other `<`, including malformed tag-shaped CSS or JavaScript string data, remains inert text.
 */
private fun scanRawTextClosingTag(source: String, opening: Int, elementName: String): Int? {
    val nameStart = opening + 2
    if (opening + 1 >= source.length || source[opening + 1] != '/' ||
        nameStart + elementName.length > source.length ||
        !source.regionMatches(nameStart, elementName, 0, elementName.length, ignoreCase = true)
    ) {
        return null
    }
    var index = nameStart + elementName.length
    while (index < source.length && source[index].isWhitespace()) {
        if (index - opening > MAX_SCANNED_MARKUP_SECTION_CHARS) return null
        index++
    }
    return if (index < source.length && source[index] == '>') index + 1 else null
}

private fun scanHtmlTag(source: String, opening: Int): HtmlMarkupTag? {
    var index = opening + 1
    if (index >= source.length) return null
    val closing = source[index] == '/'
    if (closing) index++
    if (index >= source.length || source[index].isWhitespace() || source[index] in "<>/\"'") return null
    val nameStart = index
    while (index < source.length && !source[index].isWhitespace() && source[index] != '/' && source[index] != '>') {
        if (source[index] == '<' || source[index] == '\'' || source[index] == '"') return null
        index++
    }
    val name = source.substring(nameStart, index)
    var quote: Char? = null
    var lastNonWhitespace: Char? = null
    while (index < source.length) {
        require(index - opening <= MAX_SCANNED_MARKUP_SECTION_CHARS) {
            "EPUB browser markup tag exceeds the scanner limit"
        }
        val value = source[index]
        when {
            quote != null && value == quote -> quote = null
            quote != null -> Unit
            value == '\'' || value == '"' -> quote = value
            value == '>' -> return HtmlMarkupTag(
                name = name,
                closing = closing,
                selfClosing = !closing && lastNonWhitespace == '/',
                endExclusive = index + 1,
            )
            !value.isWhitespace() -> lastNonWhitespace = value
        }
        index++
    }
    throw IllegalArgumentException("EPUB browser document contains an unterminated markup tag")
}

private fun boundedDelimitedEnd(
    source: String,
    contentStart: Int,
    delimiter: String,
    label: String,
): Int {
    val limit = minOf(source.length, contentStart + MAX_SCANNED_MARKUP_SECTION_CHARS)
    var index = contentStart
    while (index + delimiter.length <= limit) {
        if (source.regionMatches(index, delimiter, 0, delimiter.length)) return index + delimiter.length
        index++
    }
    throw IllegalArgumentException("EPUB browser $label is unterminated or exceeds the scanner limit")
}

/** Skips DOCTYPE/internal-subset syntax as inert markup; no DTD or entity is ever interpreted. */
private fun scanMarkupDeclarationEnd(source: String, opening: Int): Int {
    var index = opening + 2
    var quote: Char? = null
    var subsetDepth = 0
    while (index < source.length) {
        require(index - opening <= MAX_SCANNED_MARKUP_SECTION_CHARS) {
            "EPUB browser markup declaration exceeds the scanner limit"
        }
        val value = source[index]
        when {
            quote != null && value == quote -> quote = null
            quote != null -> Unit
            value == '\'' || value == '"' -> quote = value
            value == '[' -> subsetDepth++
            value == ']' && subsetDepth > 0 -> subsetDepth--
            value == '>' && subsetDepth == 0 -> return index + 1
        }
        index++
    }
    throw IllegalArgumentException("EPUB browser markup declaration is unterminated")
}

private fun eagerBodyLoader(bytes: ByteArray): (Int, Int) -> ByteArray {
    val snapshot = bytes.copyOf()
    return { maximumBytes, _ ->
        require(snapshot.size <= maximumBytes) { "EPUB renderer resource exceeds the per-response limit" }
        snapshot.copyOf()
    }
}

/** Chooses the first deterministic virtual directory not owned by any publisher resource in O(n). */
private fun selectUserStyleNamespace(resources: List<EpubRenderResource>): String {
    val occupiedSuffixes = HashSet<Int>()
    resources.forEach { resource ->
        userStyleNamespaceSuffix(resource.href, resources.size)?.let(occupiedSuffixes::add)
    }
    var suffix = 0
    while (suffix in occupiedSuffixes) suffix++
    check(suffix <= resources.size) { "EPUB user-style namespace selection exhausted" }
    val directory = READER_STYLE_NAMESPACE_BASE + if (suffix == 0) "" else "-$suffix"
    return "$directory/"
}

/** Returns only canonical suffixes that could affect the first n+1 namespace candidates. */
private fun userStyleNamespaceSuffix(href: String, maximumRelevantSuffix: Int): Int? {
    if (href == READER_STYLE_NAMESPACE_BASE || href.startsWith("$READER_STYLE_NAMESPACE_BASE/")) {
        return 0
    }
    val numberedPrefix = "$READER_STYLE_NAMESPACE_BASE-"
    if (!href.startsWith(numberedPrefix)) return null
    var index = numberedPrefix.length
    if (index >= href.length || href[index] !in '1'..'9') return null
    var suffix = 0
    while (index < href.length && href[index] in '0'..'9') {
        val digit = href[index] - '0'
        if (suffix > (maximumRelevantSuffix - digit) / 10) return null
        suffix = suffix * 10 + digit
        index++
    }
    if (suffix > maximumRelevantSuffix || (index < href.length && href[index] != '/')) return null
    return suffix
}

/** Computes the exact UTF-8 response size before allocating a body buffer. */
private fun String.utf8ByteSizeAtMost(maximumBytes: Int, styleId: String): Int {
    var byteCount = 0
    var index = 0
    while (index < length) {
        val width = when {
            this[index].code <= 0x7f -> 1
            this[index].code <= 0x7ff -> 2
            this[index].isHighSurrogate() -> {
                check(index + 1 < length && this[index + 1].isLowSurrogate()) {
                    "EPUB user style contains malformed UTF-16"
                }
                index++
                4
            }
            else -> 3
        }
        require(byteCount <= maximumBytes - width) {
            "EPUB user style exceeds the per-response limit: $styleId"
        }
        byteCount += width
        index++
    }
    return byteCount
}

private fun contentSecurityPolicy(scheme: String): String =
    CONTENT_SECURITY_POLICY_PREFIX + "style-src $scheme: 'unsafe-inline'; " +
        "img-src $scheme: data:; font-src $scheme: data:; media-src $scheme:; navigate-to $scheme:"

private fun secureResponseHeaders(mediaType: String, scheme: String): Map<String, String> = mapOf(
    "Content-Security-Policy" to contentSecurityPolicy(scheme),
    "Cache-Control" to "no-store",
    "Cross-Origin-Resource-Policy" to "same-origin",
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
    "Content-Type" to mediaType,
)

private fun EpubRenderResource.isHtmlDocument(): Boolean =
    mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
        mediaType.equals("text/html", ignoreCase = true)

private fun String.isScriptMediaType(): Boolean {
    val normalized = lowercase().substringBefore(';').trim()
    return normalized == "application/javascript" ||
        normalized == "application/ecmascript" ||
        normalized == "application/x-javascript" ||
        normalized == "text/javascript" ||
        normalized == "text/ecmascript" ||
        normalized.endsWith("+javascript")
}

private fun EpubRenderResource.textEncoding(): String? = when {
    isHtmlDocument() -> UTF8_ENCODING
    else -> null
}

private fun decodeBrowserDocument(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
        return decodeUtf16(bytes, littleEndian = true)
    }
    if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
        return decodeUtf16(bytes, littleEndian = false)
    }
    val offset = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
        bytes[2] == 0xbf.toByte()
    ) 3 else 0
    return bytes.decodeToString(
        startIndex = offset,
        endIndex = bytes.size,
        throwOnInvalidSequence = true,
    )
        .also { require(it.isWellFormedUtf16()) { "EPUB browser document contains malformed Unicode" } }
}

/**
 * Validates the private scheme again at the last resolver boundary. The manifest already carries
 * canonical relative hrefs, but a browser may normalize or double-encode an attacker-controlled
 * navigation before invoking the scheme callback.
 */
private fun privatePublicationUrl(url: String, publicationRootUrl: String): String? {
    if (url.length > MAX_PRIVATE_PUBLICATION_URL_LENGTH ||
        url.any { it.isISOControl() || it.isWhitespace() } ||
        '\\' in url ||
        !url.startsWith(publicationRootUrl)
    ) {
        return null
    }
    val normalized = url.substringBefore('#').substringBefore('?')
    val href = normalized.removePrefix(publicationRootUrl)
    if (href.isBlank() || DANGEROUS_ENCODED_PATH.containsMatchIn(href)) return null
    if (href.split('/').any { it == "." || it == ".." }) return null
    if (href.startsWith('/') || href.startsWith("//") || URI_SCHEME.containsMatchIn(href)) return null
    return normalized
}

private fun privatePublicationHref(url: String, publicationRootUrl: String): String? =
    privatePublicationUrl(url, publicationRootUrl)?.removePrefix(publicationRootUrl)

/** Reads one exact immutable lease into one bounded response buffer and always releases its pin. */
private fun ContentBlobStore.readExactForRenderer(
    reference: BlobRef,
    resourceId: String,
    maximumBytes: Int,
    chunkBytes: Int,
): ByteArray {
    require(reference.byteSize <= maximumBytes.toLong()) {
        "EPUB renderer resource exceeds the per-response limit: $resourceId"
    }
    val expectedSize = reference.byteSize.toInt()
    val lease = openRead(reference)
        ?: throw IllegalArgumentException("EPUB renderer resource blob is unavailable: $resourceId")
    return try {
        require(lease.reference == reference) {
            "EPUB renderer resource lease identity changed while loading: $resourceId"
        }
        val output = ByteArray(expectedSize)
        var offset = 0
        while (offset < output.size) {
            val requested = minOf(chunkBytes, output.size - offset)
            val chunk = lease.readChunk(requested)
                ?: throw IllegalArgumentException(
                    "EPUB renderer resource ended before its declared size: $resourceId",
                )
            require(chunk.isNotEmpty() && chunk.size <= requested) {
                "EPUB renderer resource lease returned an invalid chunk: $resourceId"
            }
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }
        require(lease.readChunk(1) == null) {
            "EPUB renderer resource exceeded its declared size: $resourceId"
        }
        require(Sha256.hex(output) == reference.plaintextDigest) {
            "EPUB renderer resource failed integrity verification: $resourceId"
        }
        output
    } finally {
        lease.close()
    }
}

private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String {
    require((bytes.size - 2) % 2 == 0) { "EPUB UTF-16 browser document has an odd byte length" }
    val characters = CharArray((bytes.size - 2) / 2)
    var source = 2
    characters.indices.forEach { destination ->
        val first = bytes[source].toInt() and 0xff
        val second = bytes[source + 1].toInt() and 0xff
        characters[destination] = if (littleEndian) {
            (first or (second shl 8)).toChar()
        } else {
            ((first shl 8) or second).toChar()
        }
        source += 2
    }
    return characters.concatToString().also {
        require(it.isWellFormedUtf16()) { "EPUB UTF-16 browser document contains malformed Unicode" }
    }
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            this[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}
