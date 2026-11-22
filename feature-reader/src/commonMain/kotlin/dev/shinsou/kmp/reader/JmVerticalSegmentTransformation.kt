package dev.shinsou.kmp.reader

import coil3.Bitmap
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import dev.shinsou.kmp.domain.model.ReaderImageTransform

/**
 * Coil's common transformation contract; platform bitmap operations are supplied by actuals.
 *
 * JM defines its cut lines on the image's natural height (`floor(h / n)` and `h % n`). Coil
 * decodes at the view's size, so [naturalHeight] carries the encoded height (read from the image
 * header, never by a full-size decode) and the cuts are scaled onto the decoded bitmap. Without it
 * the decoded height is used, which can leave seams of a few pixels on downsampled pages.
 */
public class JmVerticalSegmentTransformation(
    public val segmentCount: Int,
    public val naturalHeight: Int? = null,
) : Transformation() {
    init {
        require(segmentCount > 1) { "A segmented image needs at least two segments" }
        require(naturalHeight == null || naturalHeight > 0) { "Natural image height must be positive" }
    }

    override val cacheKey: String =
        "shinsou-jm-reverse-vertical:$segmentCount:${naturalHeight ?: "decoded"}:v3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        reverseVerticalSegments(input, segmentCount, naturalHeight ?: input.height)
}

internal expect fun reverseVerticalSegments(input: Bitmap, segmentCount: Int, naturalHeight: Int): Bitmap

/**
 * Emits JM's reversed slices for a bitmap of [height] rows whose source image is [naturalHeight]
 * rows tall. Cut lines are computed on the natural height and mapped with `round(y * h' / h)`, so
 * adjacent slices share their boundary rows: no gaps and no overlap on either side.
 */
/** `round(row * height / naturalHeight)` in integer arithmetic. */
internal fun scaleSegmentRow(row: Int, height: Int, naturalHeight: Int): Int =
    ((row.toLong() * height * 2 + naturalHeight) / (naturalHeight.toLong() * 2)).toInt()

internal inline fun drawReversedSegments(
    width: Int,
    height: Int,
    segmentCount: Int,
    naturalHeight: Int = height,
    draw: (sourceTop: Int, sourceHeight: Int, destinationTop: Int, destinationHeight: Int) -> Unit,
) {
    if (width <= 0 || height <= 0 || naturalHeight <= segmentCount) return
    val baseHeight = naturalHeight / segmentCount
    val overflow = naturalHeight % segmentCount
    repeat(segmentCount) { index ->
        val sourceTop = naturalHeight - (baseHeight * (index + 1)) - overflow
        val sliceHeight = baseHeight + if (index == 0) overflow else 0
        val destinationTop = baseHeight * index + if (index == 0) 0 else overflow
        val scaledSourceTop = scaleSegmentRow(sourceTop, height, naturalHeight)
        val scaledSourceHeight = scaleSegmentRow(sourceTop + sliceHeight, height, naturalHeight) - scaledSourceTop
        val scaledDestinationTop = scaleSegmentRow(destinationTop, height, naturalHeight)
        val scaledDestinationHeight = scaleSegmentRow(destinationTop + sliceHeight, height, naturalHeight) - scaledDestinationTop
        if (scaledSourceHeight > 0 && scaledDestinationHeight > 0) {
            draw(scaledSourceTop, scaledSourceHeight, scaledDestinationTop, scaledDestinationHeight)
        }
    }
}

internal fun ReaderImageTransform.toCoilTransformation(naturalHeight: Int? = null): Transformation = when (this) {
    is ReaderImageTransform.ReverseVerticalSegments -> JmVerticalSegmentTransformation(segmentCount, naturalHeight)
}

/**
 * Applies [transform] to a reader image request decoded at the view's size. [naturalHeight] is
 * the encoded image height; transformed pages must never be decoded at full size just to learn it.
 */
public fun ImageRequest.Builder.readerImageTransform(
    transform: ReaderImageTransform?,
    naturalHeight: Int?,
): ImageRequest.Builder = apply {
    if (transform == null) return@apply
    transformations(transform.toCoilTransformation(naturalHeight?.takeIf { it > 0 }))
}
