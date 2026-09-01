package dev.shinsou.kmp.network

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.nio.ByteBuffer
import org.aomedia.avif.android.AvifDecoder

/** Self-contained AVIF support for Android versions whose platform decoder predates AVIF. */
internal class AndroidAvifDecoder private constructor(
    private val result: SourceFetchResult,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val encoded = result.source.source().use { source -> source.readByteArray() }
        val buffer = ByteBuffer.allocateDirect(encoded.size).apply {
            put(encoded)
            rewind()
        }
        val decoder = requireNotNull(AvifDecoder.create(buffer)) {
            "Bundled libavif could not decode the AVIF image."
        }
        try {
            val width = decoder.width
            val height = decoder.height
            require(width in 1..MAX_IMAGE_DIMENSION && height in 1..MAX_IMAGE_DIMENSION) {
                "AVIF dimensions are invalid."
            }
            require(width.toLong() * height.toLong() * 4 <= MAX_DECODED_IMAGE_BYTES) {
                "AVIF image is too large to decode safely."
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val status = decoder.nthFrame(0, bitmap)
            check(status == AVIF_RESULT_OK) {
                "Bundled libavif failed to render the AVIF image: ${AvifDecoder.resultToString(status)}"
            }
            return DecodeResult(image = bitmap.asImage(), isSampled = false)
        } finally {
            decoder.release()
        }
    }

    internal class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? = if (result.isAvif()) AndroidAvifDecoder(result) else null
    }
}

private fun SourceFetchResult.isAvif(): Boolean {
    if (mimeType.equals(AVIF_MIME_TYPE, ignoreCase = true)) return true
    val header = source.source().peek().use { peeked ->
        peeked.readByteArray(AVIF_SIGNATURE_BYTES.toLong())
    }
    return header.size >= AVIF_SIGNATURE_BYTES &&
        header.copyOfRange(4, 8).contentEquals(FTYP_BYTES) &&
        header.copyOfRange(8, 12).let { brand ->
            brand.contentEquals(AVIF_BYTES) || brand.contentEquals(AVIS_BYTES)
        }
}

private const val AVIF_RESULT_OK = 0
private const val AVIF_MIME_TYPE = "image/avif"
private const val AVIF_SIGNATURE_BYTES = 12
private const val MAX_IMAGE_DIMENSION = 32_768
private const val MAX_DECODED_IMAGE_BYTES = 512L * 1024L * 1024L
private val FTYP_BYTES = "ftyp".encodeToByteArray()
private val AVIF_BYTES = "avif".encodeToByteArray()
private val AVIS_BYTES = "avis".encodeToByteArray()
