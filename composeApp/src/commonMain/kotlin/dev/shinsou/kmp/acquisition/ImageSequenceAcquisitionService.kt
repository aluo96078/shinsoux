package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ImageLayout
import dev.shinsou.kmp.content.ImagePage
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

/** One already-bounded local image. Paths are identity hints only and are never persisted as handles. */
public class LocalImagePageSource(
    public val logicalName: String,
    public val mediaType: String,
    bytes: ByteArray,
    cancellationCheckpoint: () -> Unit = {},
) {
    private val body: ByteArray = bytes.copyWithCancellationCheckpoints(cancellationCheckpoint)

    init {
        require(logicalName.isNotBlank() && logicalName.length <= MAX_LOGICAL_NAME_CHARS) {
            "Local image logical name must be non-blank and bounded"
        }
        require(logicalName.none(Char::isISOControl)) { "Local image logical name contains control characters" }
        require(mediaType in SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES) { "Unsupported local image media type: $mediaType" }
        require(body.isNotEmpty()) { "Local image body is empty" }
    }

    public val byteSize: Int get() = body.size
    public fun copyBytes(): ByteArray = copyBytes(cancellationCheckpoint = {})

    internal fun copyBytes(cancellationCheckpoint: () -> Unit): ByteArray =
        body.copyWithCancellationCheckpoints(cancellationCheckpoint)

    internal fun sha256Hex(cancellationCheckpoint: () -> Unit): String {
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        return try {
            forEachChunk(cancellationCheckpoint) { chunk ->
                buffer.write(chunk)
                hashing.write(buffer, chunk.size.toLong())
            }
            cancellationCheckpoint()
            hashing.hash.hex()
        } finally {
            hashing.close()
        }
    }

    internal fun forEachChunk(
        cancellationCheckpoint: () -> Unit,
        consume: (ByteArray) -> Unit,
    ) {
        var offset = 0
        while (offset < body.size) {
            cancellationCheckpoint()
            val count = minOf(LOCAL_IMAGE_COPY_CHUNK_BYTES, body.size - offset)
            consume(body.copyOfRange(offset, offset + count))
            offset += count
        }
        cancellationCheckpoint()
    }
}

public data class ImageSequenceAcquisitionPolicy(
    val maximumPages: Int = 100_000,
    val maximumPageBytes: Long = 64L * 1024L * 1024L,
    val maximumTotalBytes: Long = 512L * 1024L * 1024L,
) {
    init {
        require(maximumPages in 1..100_000) { "Maximum image count must fit the content manifest" }
        require(maximumPageBytes in 1..Int.MAX_VALUE.toLong()) { "Maximum image size is invalid" }
        require(maximumTotalBytes >= maximumPageBytes && maximumTotalBytes <= Int.MAX_VALUE.toLong()) {
            "Maximum image-sequence size is invalid"
        }
    }
}

public data class ImageSequenceAcquisitionRequest(
    val target: LocalAcquisitionTarget,
    val pages: List<LocalImagePageSource>,
    val packageKind: LocalPackageKind,
    val progression: ImageProgression = ImageProgression.LEFT_TO_RIGHT,
    val layout: ImageLayout = ImageLayout.PAGE,
)

public data class ImageSequenceAcquisitionMetadata(
    val pageCount: Int,
    val totalBytes: Long,
    val packageKind: LocalPackageKind,
)

/** Publishes a complete local image sequence without routing any body bytes through AppSnapshot. */
public class ImageSequenceAcquisitionService(
    private val blobStore: ContentBlobStore,
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
    private val policy: ImageSequenceAcquisitionPolicy = ImageSequenceAcquisitionPolicy(),
    private val authorizeOfflineStore: (byteCount: Long) -> Unit = {},
    private val cancellationCheckpoint: () -> Unit = {},
) {
    public fun acquire(
        request: ImageSequenceAcquisitionRequest,
    ): ContentAcquisitionResult<ImageSequenceAcquisitionMetadata> {
        val pages = ArrayList<LocalImagePageSource>(request.pages.size)
        request.pages.forEach { page ->
            cancellationCheckpoint()
            pages += page
        }
        cancellationCheckpoint()
        require(pages.isNotEmpty()) { "An image sequence needs at least one page" }
        require(pages.size <= policy.maximumPages) { "Image sequence contains too many pages" }
        val logicalNames = HashSet<String>(pages.size)
        var totalBytes = 0L
        pages.forEach { page ->
            cancellationCheckpoint()
            require(logicalNames.add(page.logicalName)) { "Image sequence logical names must be unique" }
            require(page.byteSize.toLong() <= policy.maximumPageBytes) { "Local image exceeds the page size limit" }
            require(totalBytes <= policy.maximumTotalBytes - page.byteSize.toLong()) {
                "Image sequence exceeds the total size limit"
            }
            totalBytes += page.byteSize.toLong()
            require(totalBytes <= policy.maximumTotalBytes) { "Image sequence exceeds the total size limit" }
        }
        cancellationCheckpoint()

        // Complete validation and the host rights decision before publishing the first body.
        authorizeOfflineStore(totalBytes)
        val published = pages.map(::publishPage)
        val target = request.target
        val imagePages = pages.zip(published).map { (page, receipt) ->
            ImagePage(
                resource = ResourceRef(
                    id = identityDeriver.resourceId(
                        target,
                        LocalContentFormat.IMAGE_SEQUENCE,
                        page.logicalName,
                    ),
                    blob = receipt.reference,
                ),
                layout = request.layout,
            )
        }
        val representation = ContentRepresentation.ImageSequence(
            representationId = identityDeriver.representationId(target, LocalContentFormat.IMAGE_SEQUENCE),
            pages = imagePages,
            progression = request.progression,
            layout = request.layout,
        )
        val manifestDigest = Sha256.hex(
            buildString {
                append("shinsou-image-sequence-manifest-v1\u0000")
                pages.zip(published).forEach { (page, receipt) ->
                    appendFramed(page.logicalName)
                    appendFramed(page.mediaType)
                    appendFramed(receipt.reference.plaintextDigest)
                    appendFramed(receipt.reference.byteSize.toString())
                }
                appendFramed(request.packageKind.name)
                appendFramed(request.progression.name)
                appendFramed(request.layout.name)
            }.encodeToByteArray(),
        )
        val manifest = ContentManifest(
            manifestId = identityDeriver.manifestId(
                target,
                LocalContentFormat.IMAGE_SEQUENCE,
                manifestDigest,
            ),
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = target.contentRevision,
            representations = listOf(representation),
            declaredSizeBytes = totalBytes,
        )
        val unit = PublicationUnit(
            key = UnitKey(
                target.publicationKey,
                identityDeriver.unitId(target, LocalContentFormat.IMAGE_SEQUENCE),
            ),
            title = target.unitTitle,
            manifestRevisions = listOf(manifest),
        )
        val acquisition = Acquisition(
            id = identityDeriver.acquisitionId(target, LocalContentFormat.IMAGE_SEQUENCE),
            origin = AcquisitionOrigin.LocalPackage(request.packageKind),
            units = listOf(unit),
            contentRevision = target.contentRevision,
            acquiredAtEpochMillis = target.acquiredAtEpochMillis,
        )
        return ContentAcquisitionResult(
            publicationDraft = Publication(
                key = target.publicationKey,
                title = target.publicationTitle,
                acquisitions = listOf(acquisition),
            ),
            metadata = ImageSequenceAcquisitionMetadata(pages.size, totalBytes, request.packageKind),
            publishedBlobs = published,
        )
    }

    private fun publishPage(page: LocalImagePageSource): dev.shinsou.kmp.content.BlobPublishReceipt {
        val stage = blobStore.beginStage(page.byteSize.toLong(), page.mediaType)
        return try {
            page.forEachChunk(cancellationCheckpoint, stage::append)
            blobStore.publish(stage.seal())
        } catch (failure: Throwable) {
            stage.abort()
            throw failure
        }
    }
}

private fun ByteArray.copyWithCancellationCheckpoints(
    cancellationCheckpoint: () -> Unit,
): ByteArray {
    val snapshot = ByteArray(size)
    var offset = 0
    while (offset < size) {
        cancellationCheckpoint()
        val count = minOf(LOCAL_IMAGE_COPY_CHUNK_BYTES, size - offset)
        copyInto(snapshot, offset, offset, offset + count)
        offset += count
    }
    cancellationCheckpoint()
    return snapshot
}

private const val LOCAL_IMAGE_COPY_CHUNK_BYTES: Int = 64 * 1024

private fun StringBuilder.appendFramed(value: String) {
    append(value.encodeToByteArray().size)
    append(':')
    append(value)
}

public val SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/avif",
    "image/heic",
    "image/bmp",
)

private const val MAX_LOGICAL_NAME_CHARS: Int = 4_096
