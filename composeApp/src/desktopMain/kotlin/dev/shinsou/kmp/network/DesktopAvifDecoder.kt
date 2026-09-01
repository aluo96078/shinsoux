package dev.shinsou.kmp.network

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.Locale
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * Decodes AVIF through the ImageIO/CoreGraphics implementation bundled with macOS.
 *
 * Compose Desktop's Skia build currently supports PNG, JPEG and WebP, but not AVIF. Some sources
 * (notably BiliManga) serve comic pages exclusively as AVIF, so a successful network response
 * otherwise reaches Coil and fails at the final decode step. Using the system framework keeps the
 * application self-contained and does not require Homebrew or a separately installed codec.
 */
internal class DesktopAvifDecoder private constructor(
    private val result: SourceFetchResult,
    private val decoder: MacOsAvifImageDecoder,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().use { source -> source.readByteArray() }
        val decoded = decoder.decode(bytes)
            ?: error("macOS ImageIO could not decode the AVIF image.")
        val imageInfo = ImageInfo.makeN32(
            decoded.width,
            decoded.height,
            ColorAlphaType.PREMUL,
        )
        val bitmap = Bitmap()
        check(bitmap.installPixels(imageInfo, decoded.skiaN32PremultipliedPixels, decoded.width * 4)) {
            "Skia could not install decoded AVIF pixels."
        }
        bitmap.setImmutable()
        return DecodeResult(image = bitmap.asImage(), isSampled = false)
    }

    internal class Factory(
        private val platformName: String = System.getProperty("os.name").orEmpty(),
        private val decoderProvider: () -> MacOsAvifImageDecoder = { JnaMacOsAvifImageDecoder.instance },
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!platformName.lowercase(Locale.ROOT).let { it.contains("mac") || it.contains("darwin") }) {
                return null
            }
            if (!result.isAvif()) return null
            return DesktopAvifDecoder(result, decoderProvider())
        }
    }
}

private fun SourceFetchResult.isAvif(): Boolean {
    if (mimeType.equals(AVIF_MIME_TYPE, ignoreCase = true)) return true
    // File extensions and MIME types are frequently absent for cached and proxied responses. AVIF
    // is an ISO-BMFF file whose `ftyp` box advertises `avif` or `avis` as the major brand.
    val header = source.source().peek().use { peeked ->
        peeked.readByteArray(AVIF_SIGNATURE_BYTES.toLong())
    }
    if (header.size < AVIF_SIGNATURE_BYTES || !header.copyOfRange(4, 8).contentEquals(FTYP_BYTES)) {
        return false
    }
    return header.copyOfRange(8, 12).let { brand ->
        brand.contentEquals(AVIF_BYTES) || brand.contentEquals(AVIS_BYTES)
    }
}

internal data class DecodedMacOsAvifImage(
    val width: Int,
    val height: Int,
    val skiaN32PremultipliedPixels: ByteArray,
)

internal fun interface MacOsAvifImageDecoder {
    fun decode(encodedBytes: ByteArray): DecodedMacOsAvifImage?
}

/** JNA facade over public CoreFoundation, ImageIO, and CoreGraphics APIs. */
internal class JnaMacOsAvifImageDecoder private constructor(
    private val coreFoundation: CoreFoundationFramework,
    private val imageIo: ImageIoFramework,
    private val coreGraphics: CoreGraphicsFramework,
) : MacOsAvifImageDecoder {
    override fun decode(encodedBytes: ByteArray): DecodedMacOsAvifImage? {
        if (encodedBytes.isEmpty()) return null
        val data = coreFoundation.CFDataCreate(null, encodedBytes, encodedBytes.size.toLong())
            ?: return null
        try {
            val source = imageIo.CGImageSourceCreateWithData(data, null) ?: return null
            try {
                val image = imageIo.CGImageSourceCreateImageAtIndex(source, 0, null) ?: return null
                try {
                    val width = coreGraphics.CGImageGetWidth(image).validImageDimension() ?: return null
                    val height = coreGraphics.CGImageGetHeight(image).validImageDimension() ?: return null
                    val byteCount = validPixelByteCount(width, height) ?: return null
                    Memory(byteCount.toLong()).use { pixelMemory ->
                        val colorSpace = coreGraphics.CGColorSpaceCreateDeviceRGB() ?: return null
                        try {
                            // N32 on little-endian desktop Skia is physically BGRA. CoreGraphics
                            // writes that exact byte layout for premultiplied-first, little-endian.
                            val context = coreGraphics.CGBitmapContextCreate(
                                pixelMemory,
                                width.toLong(),
                                height.toLong(),
                                BITS_PER_COMPONENT,
                                (width * BYTES_PER_PIXEL).toLong(),
                                colorSpace,
                                BITMAP_INFO_BGRA_PREMULTIPLIED,
                            ) ?: return null
                            try {
                                coreGraphics.CGContextDrawImage(
                                    context,
                                    CoreGraphicsRect(0.0, 0.0, width.toDouble(), height.toDouble()),
                                    image,
                                )
                                return DecodedMacOsAvifImage(
                                    width = width,
                                    height = height,
                                    skiaN32PremultipliedPixels = pixelMemory.getByteArray(0, byteCount),
                                )
                            } finally {
                                coreGraphics.CGContextRelease(context)
                            }
                        } finally {
                            coreGraphics.CGColorSpaceRelease(colorSpace)
                        }
                    }
                } finally {
                    coreGraphics.CGImageRelease(image)
                }
            } finally {
                coreFoundation.CFRelease(source)
            }
        } finally {
            coreFoundation.CFRelease(data)
        }
    }

    internal interface CoreFoundationFramework : Library {
        fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: Long): Pointer?

        fun CFRelease(value: Pointer)
    }

    internal interface ImageIoFramework : Library {
        fun CGImageSourceCreateWithData(data: Pointer, options: Pointer?): Pointer?

        fun CGImageSourceCreateImageAtIndex(source: Pointer, index: Long, options: Pointer?): Pointer?
    }

    internal interface CoreGraphicsFramework : Library {
        fun CGImageGetWidth(image: Pointer): Long

        fun CGImageGetHeight(image: Pointer): Long

        fun CGColorSpaceCreateDeviceRGB(): Pointer?

        fun CGColorSpaceRelease(colorSpace: Pointer)

        fun CGBitmapContextCreate(
            data: Pointer,
            width: Long,
            height: Long,
            bitsPerComponent: Long,
            bytesPerRow: Long,
            colorSpace: Pointer,
            bitmapInfo: Int,
        ): Pointer?

        fun CGContextDrawImage(context: Pointer, rect: CoreGraphicsRect, image: Pointer)

        fun CGContextRelease(context: Pointer)

        fun CGImageRelease(image: Pointer)
    }

    internal class CoreGraphicsRect() : com.sun.jna.Structure(), com.sun.jna.Structure.ByValue {
        @JvmField
        var x: Double = 0.0

        @JvmField
        var y: Double = 0.0

        @JvmField
        var width: Double = 0.0

        @JvmField
        var height: Double = 0.0

        constructor(x: Double, y: Double, width: Double, height: Double) : this() {
            this.x = x
            this.y = y
            this.width = width
            this.height = height
        }

        override fun getFieldOrder(): List<String> = listOf("x", "y", "width", "height")
    }

    companion object {
        val instance: JnaMacOsAvifImageDecoder by lazy {
            check(System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).let {
                it.contains("mac") || it.contains("darwin")
            }) { "macOS ImageIO AVIF decoding is unavailable on this operating system." }
            JnaMacOsAvifImageDecoder(
                coreFoundation = Native.load(CORE_FOUNDATION_FRAMEWORK, CoreFoundationFramework::class.java),
                imageIo = Native.load(IMAGE_IO_FRAMEWORK, ImageIoFramework::class.java),
                coreGraphics = Native.load(CORE_GRAPHICS_FRAMEWORK, CoreGraphicsFramework::class.java),
            )
        }
    }
}


private fun Long.validImageDimension(): Int? =
    takeIf { it in 1..MAX_IMAGE_DIMENSION.toLong() }?.toInt()

private fun validPixelByteCount(width: Int, height: Int): Int? {
    val bytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
    return bytes.takeIf { it in 1..MAX_DECODED_IMAGE_BYTES }?.toInt()
}

private const val AVIF_MIME_TYPE = "image/avif"
private const val AVIF_SIGNATURE_BYTES = 12
private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_COMPONENT = 8L
private const val MAX_IMAGE_DIMENSION = 32_768
private const val MAX_DECODED_IMAGE_BYTES = 512L * 1024L * 1024L
private const val CORE_FOUNDATION_FRAMEWORK =
    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val IMAGE_IO_FRAMEWORK = "/System/Library/Frameworks/ImageIO.framework/ImageIO"
private const val CORE_GRAPHICS_FRAMEWORK =
    "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
private const val K_CG_IMAGE_ALPHA_PREMULTIPLIED_FIRST = 2
private const val K_CG_BITMAP_BYTE_ORDER_32_LITTLE = 2 shl 12
private const val BITMAP_INFO_BGRA_PREMULTIPLIED =
    K_CG_IMAGE_ALPHA_PREMULTIPLIED_FIRST or K_CG_BITMAP_BYTE_ORDER_32_LITTLE
private val FTYP_BYTES = "ftyp".encodeToByteArray()
private val AVIF_BYTES = "avif".encodeToByteArray()
private val AVIS_BYTES = "avis".encodeToByteArray()
