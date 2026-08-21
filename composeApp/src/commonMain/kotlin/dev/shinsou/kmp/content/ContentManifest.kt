package dev.shinsou.kmp.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A content-addressed immutable byte reference. Body bytes never live in a manifest. */
@Serializable
public data class BlobRef(
    val blobId: String,
    val schemaVersion: Int,
    val digestAlgorithm: String,
    val plaintextDigest: String,
    val byteSize: Long,
    val mediaType: String,
) {
    init { validate() }

    /** Compatibility aliases for callers that use digest/size vocabulary. */
    public val sha256: String get() = plaintextDigest
    public val digest: String get() = plaintextDigest
    public val sizeBytes: Long get() = byteSize
    public val byteLength: Long get() = byteSize

    public fun validate(): Unit {
        requireCanonicalUuid(blobId, "Blob id", allowNil = false)
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported blob schema version $schemaVersion"
        }
        require(digestAlgorithm == SHA_256) { "Only SHA-256 blob digests are supported" }
        require(SHA256_PATTERN.matches(plaintextDigest)) {
            "Blob plaintext digest must be lowercase SHA-256 hex"
        }
        require(byteSize >= 0) { "Blob size must be non-negative" }
        requireMediaType(mediaType, "Blob media type")
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public const val SHA_256: String = "SHA-256"
        public const val DEFAULT_MEDIA_TYPE: String = "application/octet-stream"
    }
}

/** A persisted resource always resolves to an immutable blob. */
@Serializable
public data class ResourceRef(
    val id: String,
    val blob: BlobRef,
    /** Must equal [blob.mediaType] when supplied; one media type is authoritative. */
    val mediaType: String = blob.mediaType,
) {
    init { validate() }

    public val stableId: String get() = id

    public fun validate(): Unit {
        requireSafeIdentifier(id, "Resource id")
        blob.validate()
        requireMediaType(mediaType, "Resource media type")
        require(mediaType == blob.mediaType) { "Resource and blob media types must agree" }
    }
}

@Serializable
public enum class ContentKind {
    IMAGE_SEQUENCE,
    PLAIN_TEXT,
    EPUB_SPINE,
}

@Serializable
public enum class ImageProgression {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
}

@Serializable
public enum class ImageLayout {
    PAGE,
    SPREAD,
    WEBTOON,
}

@Serializable
public data class ImageTransform(
    val schemaVersion: Int,
    val transformId: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported image transform schema version" }
        require(transformId in SUPPORTED_TRANSFORM_IDS) { "Unsupported image transform $transformId" }
        requireSafeIdentifier(transformId, "Image transform id")
        require(parameters.size <= MAX_ATTRIBUTES) { "Image transform parameters are too large" }
        require(parameters.keys.all { it.isNotBlank() && it.length <= MAX_IDENTIFIER_LENGTH }) {
            "Image transform parameter keys must be bounded"
        }
        require(parameters.values.all { it.length <= MAX_ATTRIBUTE_VALUE_LENGTH }) {
            "Image transform parameter values must be bounded"
        }
        require(parameters.values.all { it.none(Char::isISOControl) }) {
            "Image transform parameter values must be printable"
        }
        when (transformId) {
            "identity" -> require(parameters.isEmpty()) { "Identity transform takes no parameters" }
            "reverse_vertical_segments" -> {
                val count = parameters["segmentCount"]?.toIntOrNull()
                require(count != null && count > 1 && parameters.size == 1) {
                    "Reverse-segment transform requires segmentCount > 1"
                }
            }
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        /** A caller may mutate its returned copy without changing the host validation authority. */
        public val SUPPORTED_TRANSFORMS: Set<String>
            get() = immutableSetOf(SUPPORTED_TRANSFORM_IDS)
    }
}

@Serializable
public data class ImagePage(
    val resource: ResourceRef,
    val transform: ImageTransform? = null,
    val spread: String? = null,
    val layout: ImageLayout = ImageLayout.PAGE,
) {
    init { validate() }

    public val resourceId: String get() = resource.id

    public fun validate(): Unit {
        resource.validate()
        require(resource.mediaType.startsWith("image/", ignoreCase = true)) {
            "Image page resource must use an image media type"
        }
        transform?.validate()
        spread?.let { requireSafeIdentifier(it, "Image spread hint") }
    }
}

@Serializable
public data class EpubResource(
    val id: String,
    val href: String,
    val resource: ResourceRef,
    val mediaType: String = resource.mediaType,
    val properties: Set<String> = emptySet(),
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(id, "EPUB resource id")
        requireSafeRelativeHref(href, "EPUB resource href")
        resource.validate()
        require(resource.id == id) { "EPUB resource id must match its resource reference id" }
        requireMediaType(mediaType, "EPUB resource media type")
        require(mediaType == resource.mediaType) { "EPUB resource and blob media types must agree" }
        require(properties.size <= MAX_ATTRIBUTES && properties.all {
            it.isNotBlank() && it.length <= MAX_IDENTIFIER_LENGTH && it.none(Char::isISOControl)
        }) { "EPUB resource properties must be bounded and printable" }
    }
}

@Serializable
public data class EpubRendition(
    val layout: String? = null,
    val orientation: String? = null,
    val spread: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        listOf(layout, orientation, spread).filterNotNull().forEach {
            requireSafeIdentifier(it, "EPUB rendition value")
        }
    }
}

@Serializable
public data class EpubEncryption(
    val descriptors: List<EpubEncryptionDescriptor>,
) {
    init { validate() }

    public fun validate(): Unit {
        require(descriptors.isNotEmpty()) { "EPUB encryption needs encrypted resources" }
        require(descriptors.size <= MAX_RESOURCES) { "EPUB encryption has too many descriptors" }
        require(descriptors.map(EpubEncryptionDescriptor::resourceId).distinct().size == descriptors.size) {
            "EPUB encryption resource ids must be unique"
        }
        descriptors.forEach(EpubEncryptionDescriptor::validate)
    }
}

@Serializable
public data class EpubEncryptionDescriptor(
    val resourceId: String,
    val algorithm: String,
    val keyReference: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(resourceId, "EPUB encrypted resource id")
        requireSafeIdentifier(algorithm, "EPUB encryption algorithm")
        keyReference?.let { requireSafeIdentifier(it, "EPUB encryption key reference") }
    }
}

/** Full EPUB package graph retained by an EPUB representation. */
@Serializable
public data class EpubPackage(
    val archive: BlobRef,
    val packageDocumentId: String,
    val resources: List<EpubResource>,
    val renditions: List<EpubRendition> = emptyList(),
    val encryption: EpubEncryption? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        archive.validate()
        require(archive.mediaType.equals(EPUB_ARCHIVE_MEDIA_TYPE, ignoreCase = true)) {
            "EPUB archive must use application/epub+zip"
        }
        requireSafeIdentifier(packageDocumentId, "EPUB package document id")
        require(resources.isNotEmpty()) { "EPUB package must contain resources" }
        require(resources.size <= MAX_RESOURCES) { "EPUB package has too many resources" }
        require(resources.map(EpubResource::id).distinct().size == resources.size) {
            "EPUB package resource ids must be unique"
        }
        require(packageDocumentId in resources.mapTo(hashSetOf(), EpubResource::id)) {
            "EPUB package document id must resolve in package resources"
        }
        resources.forEach(EpubResource::validate)
        require(renditions.size <= MAX_RENDITIONS) { "EPUB package has too many renditions" }
        renditions.forEach(EpubRendition::validate)
        encryption?.validate()
        encryption?.descriptors?.forEach { descriptor ->
            val encryptedId = descriptor.resourceId
            require(encryptedId in resources.mapTo(hashSetOf(), EpubResource::id)) {
                "EPUB encryption references an unknown resource"
            }
        }
    }
}

/** Spine document references package graph resources by id; index is a materialized hint. */
@Serializable
public data class EpubSpineDocument(
    val id: String,
    val href: String,
    val resourceId: String,
    val linear: Boolean = true,
    val pageProgression: ImageProgression = ImageProgression.LEFT_TO_RIGHT,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(id, "EPUB spine document id")
        requireSafeRelativeHref(href, "EPUB spine document href")
        requireSafeIdentifier(resourceId, "EPUB spine resource id")
    }
}

/** Compatibility name for clients that used a spine item before the package graph was added. */
public typealias EpubSpineItem = EpubSpineDocument

@Serializable
public data class TextBlock(
    val blockId: String,
    val startUtf16: Int,
    val endUtf16: Int,
) {
    init { validate() }

    public fun validate(): Unit {
        requireSafeIdentifier(blockId, "Text block id")
        require(startUtf16 >= 0 && endUtf16 >= startUtf16) {
            "Text block UTF-16 range is invalid"
        }
    }
}

/** Tagged representation variants. Every variant has a stable representation id. */
@Serializable
public sealed interface ContentRepresentation {
    public val representationId: String
    public val kind: ContentKind

    @Serializable
    @SerialName("image_sequence")
    public data class ImageSequence(
        override val representationId: String,
        val pages: List<ImagePage>,
        val progression: ImageProgression = ImageProgression.LEFT_TO_RIGHT,
        val layout: ImageLayout = ImageLayout.PAGE,
        val transforms: List<ImageTransform> = emptyList(),
    ) : ContentRepresentation {
        override val kind: ContentKind get() = ContentKind.IMAGE_SEQUENCE

        init { validate() }

        public fun validate(): Unit {
            requireCanonicalUuid(representationId, "Representation id", allowNil = false)
            require(pages.isNotEmpty()) { "Image sequence must contain at least one page" }
            require(pages.size <= MAX_RESOURCES) { "Image sequence has too many pages" }
            require(pages.map(ImagePage::resourceId).distinct().size == pages.size) {
                "Image sequence page ids must be unique"
            }
            pages.forEach(ImagePage::validate)
            require(transforms.size <= MAX_TRANSFORMS) { "Image sequence has too many transforms" }
            transforms.forEach(ImageTransform::validate)
        }
    }

    @Serializable
    @SerialName("plain_text")
    public data class PlainText(
        override val representationId: String,
        /** Canonical stored text is always UTF-8 and is addressed by this immutable blob. */
        val resource: ResourceRef,
        val canonicalUtf16Length: Int,
        val sourceEncoding: String? = null,
        val blocks: List<TextBlock> = emptyList(),
    ) : ContentRepresentation {
        override val kind: ContentKind get() = ContentKind.PLAIN_TEXT

        public val blob: BlobRef get() = resource.blob

        init { validate() }

        public fun validate(): Unit {
            requireCanonicalUuid(representationId, "Representation id", allowNil = false)
            resource.validate()
            require(canonicalUtf16Length >= 0) { "Canonical text length must be non-negative" }
            require(resource.mediaType.equals("text/plain", ignoreCase = true) ||
                resource.mediaType.equals("text/markdown", ignoreCase = true)) {
                "Plain text blob must have a text media type"
            }
            sourceEncoding?.let { requireSafeIdentifier(it, "Source text encoding") }
            require(blocks.isNotEmpty()) { "Plain text requires at least one stable locator block" }
            require(blocks.size <= MAX_RESOURCES) { "Plain text has too many blocks" }
            require(blocks.map(TextBlock::blockId).distinct().size == blocks.size) {
                "Plain text block ids must be unique"
            }
            blocks.forEach(TextBlock::validate)
            require(blocks.all { it.endUtf16 <= canonicalUtf16Length }) {
                "Plain text block exceeds canonical UTF-16 length"
            }
            require(blocks.zipWithNext().all { (a, b) -> a.endUtf16 <= b.startUtf16 }) {
                "Plain text blocks must be ordered and non-overlapping"
            }
        }
    }

    @Serializable
    @SerialName("epub_spine")
    public data class EpubSpine(
        override val representationId: String,
        val packageGraph: EpubPackage,
        val documents: List<EpubSpineDocument>,
    ) : ContentRepresentation {
        override val kind: ContentKind get() = ContentKind.EPUB_SPINE

        public val spine: List<EpubSpineDocument> get() = documents
        public val items: List<EpubSpineDocument> get() = documents

        init { validate() }

        public fun validate(): Unit {
            requireCanonicalUuid(representationId, "Representation id", allowNil = false)
            packageGraph.validate()
            require(documents.isNotEmpty()) { "EPUB spine must contain at least one document" }
            require(documents.size <= MAX_RESOURCES) { "EPUB spine has too many documents" }
            require(documents.map(EpubSpineDocument::id).distinct().size == documents.size) {
                "EPUB spine document ids must be unique"
            }
            val byId = packageGraph.resources.associateBy(EpubResource::id)
            documents.forEach { document ->
                document.validate()
                val resource = requireNotNull(byId[document.resourceId]) {
                    "EPUB spine references unknown resource ${document.resourceId}"
                }
                require(resource.href == document.href) {
                    "EPUB spine href does not match package graph"
                }
            }
        }
    }
}

/** Immutable unit content metadata; all body content is resolved through ContentBlobStore. */
@Serializable
public data class ContentManifest(
    val manifestId: String,
    val schemaVersion: Int,
    val contentRevision: Long,
    val representations: List<ContentRepresentation>,
    val resources: List<ResourceRef> = emptyList(),
    val declaredSizeBytes: Long? = null,
) {
    init { validate() }

    public val version: Int get() = schemaVersion
    public val kinds: Set<ContentKind>
        get() = immutableSetOf(representations.map(ContentRepresentation::kind))
    public val imageSequences: List<ContentRepresentation.ImageSequence>
        get() = immutableListOf(representations.filterIsInstance<ContentRepresentation.ImageSequence>())
    public val plainTexts: List<ContentRepresentation.PlainText>
        get() = immutableListOf(representations.filterIsInstance<ContentRepresentation.PlainText>())
    public val epubSpines: List<ContentRepresentation.EpubSpine>
        get() = immutableListOf(representations.filterIsInstance<ContentRepresentation.EpubSpine>())

    public val referencedBlobs: List<BlobRef>
        get() = immutableListOf(buildList {
            resources.mapTo(this) { it.blob }
            representations.forEach { representation ->
                when (representation) {
                    is ContentRepresentation.ImageSequence -> representation.pages.mapTo(this) { it.resource.blob }
                    is ContentRepresentation.PlainText -> {
                        add(representation.resource.blob)
                    }
                    is ContentRepresentation.EpubSpine -> {
                        val graph = representation.packageGraph
                        add(graph.archive)
                        graph.resources.mapTo(this) { it.resource.blob }
                    }
                }
            }
        }.distinct())

    public fun validate(): Unit {
        requireCanonicalUuid(manifestId, "Manifest id", allowNil = false)
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported manifest schema version $schemaVersion"
        }
        require(contentRevision >= 0) { "Content revision must be non-negative" }
        require(representations.isNotEmpty()) { "Content manifest needs at least one representation" }
        require(representations.size <= MAX_REPRESENTATIONS) { "Content manifest has too many representations" }
        require(representations.map(ContentRepresentation::representationId).distinct().size == representations.size) {
            "Content representation ids must be unique"
        }
        require(resources.size <= MAX_RESOURCES) { "Manifest has too many resources" }
        require(resources.map(ResourceRef::stableId).distinct().size == resources.size) {
            "Manifest resource ids must be unique"
        }
        require(declaredSizeBytes == null || declaredSizeBytes >= 0) {
            "Declared content size must be non-negative"
        }
        resources.forEach(ResourceRef::validate)
        representations.forEach(ContentRepresentation::validate)

        val nestedResourceCount = representations.fold(resources.size.toLong()) { total, representation ->
            val additional = when (representation) {
                is ContentRepresentation.ImageSequence -> representation.pages.size.toLong()
                is ContentRepresentation.PlainText -> 1L
                is ContentRepresentation.EpubSpine -> representation.packageGraph.resources.size.toLong()
            }
            require(additional <= Long.MAX_VALUE - total) { "Content manifest resource count overflow" }
            total + additional
        }
        require(nestedResourceCount <= MAX_RESOURCES_TOTAL.toLong()) {
            "Content manifest has too many nested resources"
        }

        val nestedResourceIds = buildList {
            resources.mapTo(this) { it.id }
            representations.forEach { representation ->
                when (representation) {
                    is ContentRepresentation.ImageSequence -> representation.pages.mapTo(this) { it.resourceId }
                    is ContentRepresentation.PlainText -> add(representation.resource.id)
                    is ContentRepresentation.EpubSpine -> representation.packageGraph.resources.mapTo(this) { it.id }
                }
            }
        }
        check(nestedResourceIds.size.toLong() == nestedResourceCount)
        require(nestedResourceIds.distinct().size == nestedResourceIds.size) {
            "Resource ids must resolve uniquely across all representations"
        }
        if (declaredSizeBytes != null) {
            val distinctBlobSize = checkedBlobSizeSum(referencedBlobs.distinctBy(BlobRef::blobId))
            require(distinctBlobSize == declaredSizeBytes) {
                "Declared content size must equal the distinct referenced blob sizes"
            }
        }

        // A blob id is an opaque identity. If it appears more than once, all immutable metadata
        // must agree; indexing only by digest would permit a forged id/media type alias.
        val blobRecords = referencedBlobs.groupBy(BlobRef::blobId)
        blobRecords.values.forEach { refs ->
            require(refs.map { Triple(it.plaintextDigest, it.byteSize, it.mediaType) }.distinct().size == 1) {
                "Blob id is used with conflicting digest, size, or media type"
            }
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * A read-only collection whose backing storage is private and whose mutating Kotlin/JVM casts
 * resolve to the unsupported-operation implementations inherited from the read-only base type.
 * The implementation lives in common code so the admission boundary has the same semantics on
 * JVM, Android, iOS and other Kotlin/Native targets.
 */
private class ImmutableSnapshotList<out E> private constructor(
    private val values: Array<Any?>,
) : AbstractList<E>() {
    override val size: Int get() = values.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): E = values[index] as E

    companion object {
        fun <E> from(values: Iterable<E>): List<E> {
            val snapshot = values.toList()
            val copy = arrayOfNulls<Any?>(snapshot.size)
            snapshot.forEachIndexed { index, value -> copy[index] = value }
            return ImmutableSnapshotList(copy)
        }
    }
}

private class ImmutableSnapshotSet<out E> private constructor(
    private val values: List<E>,
) : AbstractSet<E>() {
    override val size: Int get() = values.size
    override fun contains(element: @UnsafeVariance E): Boolean = values.contains(element)
    override fun iterator(): Iterator<E> = values.iterator()

    companion object {
        fun <E> from(values: Iterable<E>): Set<E> =
            ImmutableSnapshotSet(ImmutableSnapshotList.from(values.distinct()))
    }
}

private class ImmutableSnapshotEntry<K, V>(
    override val key: K,
    override val value: V,
) : Map.Entry<K, V> {
    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && key == other.key && value == other.value

    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)

    override fun toString(): String = "$key=$value"
}

private class ImmutableSnapshotMap<K, V> private constructor(
    private val snapshotEntriesList: List<Map.Entry<K, V>>,
) : AbstractMap<K, V>() {
    private val snapshotEntries: Set<Map.Entry<K, V>> =
        ImmutableSnapshotSet.from(snapshotEntriesList)

    override val size: Int get() = snapshotEntriesList.size
    override val entries: Set<Map.Entry<K, V>> get() = snapshotEntries

    override fun get(key: K): V? = snapshotEntriesList.firstOrNull { it.key == key }?.value

    companion object {
        fun <K, V> from(values: Map<K, V>): Map<K, V> =
            ImmutableSnapshotMap(
                ImmutableSnapshotList.from(
                    values.entries.map { ImmutableSnapshotEntry(it.key, it.value) },
                ),
            )
    }
}

/** Common deep-immutable collection constructors used at content admission boundaries. */
internal fun <E> immutableListOf(values: Iterable<E>): List<E> = ImmutableSnapshotList.from(values)

internal fun <E> immutableSetOf(values: Iterable<E>): Set<E> = ImmutableSnapshotSet.from(values)

internal fun <K, V> immutableMapOf(values: Map<K, V>): Map<K, V> = ImmutableSnapshotMap.from(values)

/**
 * Copies every collection in a manifest graph.  A plain `toList()`/`toMap()` is not sufficient:
 * on JVM it may still expose a mutable Java collection through a cast, while on Native it can
 * retain the caller's mutable backing object.  This snapshot owns all list/map/set storage.
 */
internal fun ContentManifest.deepImmutableSnapshot(): ContentManifest = ContentManifest(
    manifestId = manifestId,
    schemaVersion = schemaVersion,
    contentRevision = contentRevision,
    representations = immutableListOf(representations.map(ContentRepresentation::deepImmutableSnapshot)),
    resources = immutableListOf(resources.map(ResourceRef::deepImmutableSnapshot)),
    declaredSizeBytes = declaredSizeBytes,
)

private fun ResourceRef.deepImmutableSnapshot(): ResourceRef = copy(blob = blob.copy())

private fun ImageTransform.deepImmutableSnapshot(): ImageTransform =
    copy(parameters = immutableMapOf(parameters))

private fun ImagePage.deepImmutableSnapshot(): ImagePage = copy(
    resource = resource.deepImmutableSnapshot(),
    transform = transform?.deepImmutableSnapshot(),
)

private fun EpubResource.deepImmutableSnapshot(): EpubResource = copy(
    resource = resource.deepImmutableSnapshot(),
    properties = immutableSetOf(properties),
)

private fun EpubEncryption.deepImmutableSnapshot(): EpubEncryption =
    copy(descriptors = immutableListOf(descriptors.map(EpubEncryptionDescriptor::deepImmutableSnapshot)))

private fun EpubEncryptionDescriptor.deepImmutableSnapshot(): EpubEncryptionDescriptor = copy()

private fun EpubPackage.deepImmutableSnapshot(): EpubPackage = copy(
    archive = archive.copy(),
    resources = immutableListOf(resources.map(EpubResource::deepImmutableSnapshot)),
    renditions = immutableListOf(renditions.map(EpubRendition::deepImmutableSnapshot)),
    encryption = encryption?.deepImmutableSnapshot(),
)

private fun EpubRendition.deepImmutableSnapshot(): EpubRendition = copy()

private fun EpubSpineDocument.deepImmutableSnapshot(): EpubSpineDocument = copy()

private fun TextBlock.deepImmutableSnapshot(): TextBlock = copy()

private fun ContentRepresentation.deepImmutableSnapshot(): ContentRepresentation = when (this) {
    is ContentRepresentation.ImageSequence -> copy(
        pages = immutableListOf(pages.map(ImagePage::deepImmutableSnapshot)),
        transforms = immutableListOf(transforms.map(ImageTransform::deepImmutableSnapshot)),
    )
    is ContentRepresentation.PlainText -> copy(
        resource = resource.deepImmutableSnapshot(),
        blocks = immutableListOf(blocks.map(TextBlock::deepImmutableSnapshot)),
    )
    is ContentRepresentation.EpubSpine -> copy(
        packageGraph = packageGraph.deepImmutableSnapshot(),
        documents = immutableListOf(documents.map(EpubSpineDocument::deepImmutableSnapshot)),
    )
}

private const val MAX_IDENTIFIER_LENGTH: Int = 512
private const val MAX_ATTRIBUTE_VALUE_LENGTH: Int = 4096
private const val MAX_ATTRIBUTES: Int = 128
private const val MAX_RESOURCES: Int = 100_000
private const val MAX_RESOURCES_TOTAL: Int = 100_000
private const val MAX_REPRESENTATIONS: Int = 32
private const val MAX_TRANSFORMS: Int = 16
private const val MAX_RENDITIONS: Int = 64
private const val EPUB_ARCHIVE_MEDIA_TYPE: String = "application/epub+zip"
private const val ZERO_UUID: String = "00000000-0000-0000-0000-000000000000"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val MEDIA_TYPE_PATTERN = Regex("[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+/[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+")

private fun requireCanonicalUuid(value: String, label: String, allowNil: Boolean) {
    require(UUID_PATTERN.matches(value) && (allowNil || value != ZERO_UUID)) {
        "$label must be a lowercase canonical UUID"
    }
}

private fun requireSafeIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) {
        "$label must be non-blank and bounded"
    }
    require(value.none(Char::isISOControl) && value.none { it.isWhitespace() }) {
        "$label contains unsafe characters"
    }
}

private fun requireMediaType(value: String, label: String) {
    require(value.length <= 256 && MEDIA_TYPE_PATTERN.matches(value)) { "$label is invalid" }
}

private fun requireSafeRelativeHref(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4096) { "$label must be bounded and non-blank" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
    require(!value.contains('\\') && !value.startsWith('/') && !value.startsWith("//")) {
        "$label must be a safe relative path"
    }
    val decoded = percentDecodeForValidation(value.substringBefore('#').substringBefore('?'))
    require(!decoded.startsWith('/') && !decoded.contains('\\') &&
        decoded.none(Char::isISOControl) &&
        !decoded.contains("://") &&
        !decoded.matches(Regex("(?i)^[A-Za-z][A-Za-z0-9+.-]*:.*"))) {
        "$label must not contain a URI scheme"
    }
    require(decoded.split('/').none { it == ".." }) { "$label contains traversal" }
}

private fun ContentRepresentation.validate(): Unit = when (this) {
    is ContentRepresentation.ImageSequence -> validate()
    is ContentRepresentation.PlainText -> validate()
    is ContentRepresentation.EpubSpine -> validate()
}

private fun percentDecodeForValidation(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "Incomplete percent escape in href" }
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            require(byte != null) { "Invalid percent escape in href" }
            require(byte != '%'.code) { "Nested percent escape in href" }
            output.append(byte.toChar())
            index += 3
            continue
        }
        output.append(value[index++])
    }
    return output.toString()
}

private fun checkedBlobSizeSum(references: List<BlobRef>): Long {
    var total = 0L
    references.forEach { reference ->
        require(reference.byteSize <= Long.MAX_VALUE - total) {
            "Referenced blob sizes overflow the manifest size range"
        }
        total += reference.byteSize
    }
    return total
}

private val SUPPORTED_TRANSFORM_IDS: Set<String> = setOf("identity", "reverse_vertical_segments")
