package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ImageLayout
import dev.shinsou.kmp.content.ImageTransform
import dev.shinsou.kmp.plugin.Sha256

/** One verified immutable image body loaded on demand for the unified reader. */
public class ImageRenderPage(
    public val resourceId: String,
    public val mediaType: String,
    public val layout: ImageLayout,
    public val transform: ImageTransform?,
    public val readerTransform: ReaderImageTransform?,
    bytes: ByteArray,
) {
    private val body = bytes.copyOf()

    public val bytes: ByteArray get() = body.copyOf()
    public val byteSize: Int get() = body.size
}

/**
 * Lazy image adapter: opening an image publication does not hydrate the whole sequence. Each
 * visible page is read and digest-checked independently after the caller passes the DISPLAY gate.
 */
public class ImageRenderPageFactory(
    private val blobStore: ContentBlobStore,
) {
    public fun load(navigation: ImageSequenceNavigation, index: Int): ImageRenderPage {
        require(index in 0 until navigation.itemCount) { "Image reader index is out of range" }
        val page = navigation.representation.pages[index]
        val reference = page.resource.blob
        val bytes = blobStore.read(reference)
            ?: throw IllegalArgumentException("Image reader resource is unavailable: ${page.resourceId}")
        require(bytes.size.toLong() == reference.byteSize && Sha256.hex(bytes) == reference.plaintextDigest) {
            "Image reader resource failed integrity verification: ${page.resourceId}"
        }
        return ImageRenderPage(
            resourceId = page.resourceId,
            mediaType = page.resource.mediaType,
            layout = page.layout,
            transform = page.transform,
            readerTransform = page.transform?.toReaderTransform(),
            bytes = bytes,
        )
    }
}

private fun ImageTransform.toReaderTransform(): ReaderImageTransform? = when (transformId) {
    "identity" -> null
    "reverse_vertical_segments" -> ReaderImageTransform.ReverseVerticalSegments(
        requireNotNull(parameters["segmentCount"]?.toIntOrNull()) {
            "Reverse-segment image transform has no valid segment count"
        },
    )
    else -> error("Unsupported image transform: $transformId")
}
