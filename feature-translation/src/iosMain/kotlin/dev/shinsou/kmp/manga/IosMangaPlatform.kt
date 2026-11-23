package dev.shinsou.kmp.manga

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import dev.shinsou.kmp.logging.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/** Manga translation on iOS: ONNX Runtime's C API, OpenCV's Objective-C API, Skia for pages and text. */
public class IosMangaPlatform : MangaPlatform {
    override val onnx: OnnxRuntime by lazy { IosOnnxRuntime() }
    override val ops: VisionOps by lazy { IosVisionOps() }
    override val codec: PageCodec = IosSkiaPageCodec

    // Core ML runs the detector on the GPU or the Neural Engine, from one pack
    override fun accelerators(): List<AcceleratorSupport> = listOf(
        AcceleratorSupport(MangaAccelerator.CPU, notes = AcceleratorNotes.CPU),
        AcceleratorSupport(MangaAccelerator.GPU, packs = listOf(MangaModelCatalog.COREML_DETECTOR), notes = AcceleratorNotes.COREML_GPU, weight = 4),
        AcceleratorSupport(MangaAccelerator.NPU, packs = listOf(MangaModelCatalog.COREML_DETECTOR), notes = AcceleratorNotes.COREML_NPU, weight = 4),
    )

    override fun acceleratedDetector(paths: MangaModelPaths, accelerator: MangaAccelerator): PageDetector? {
        val path = paths.coreMlDetector ?: return null
        val units = when (accelerator) {
            MangaAccelerator.GPU -> IosCoreMlDetector.Units.GPU
            MangaAccelerator.NPU -> IosCoreMlDetector.Units.NEURAL_ENGINE
            MangaAccelerator.CPU -> return null
        }
        return runCatching { IosCoreMlDetector(ops, path, units) }
            .onFailure { Log.warn("MangaPlatform", it) { "Core ML detector on $units could not start" } }
            .getOrNull()
    }

    // background quality of service: the efficiency cores only. Set on every task, since the
    // thread is the dispatcher's own; the call costs next to nothing.
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
    private val saver: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val thread = kotlinx.coroutines.newSingleThreadContext("manga-saver")
        object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: kotlinx.coroutines.Runnable) =
                thread.dispatch(context, kotlinx.coroutines.Runnable { platform.posix.pthread_set_qos_class_self_np(platform.posix.QOS_CLASS_BACKGROUND, 0); block.run() })
        }
    }

    override fun efficiencyDispatcher(fallback: kotlinx.coroutines.CoroutineDispatcher): kotlinx.coroutines.CoroutineDispatcher =
        saver

    @OptIn(ExperimentalForeignApi::class)
    override fun profile(): MangaDeviceProfile {
        fun sysctl(name: String): Int? = memScoped {
            val value = alloc<IntVar>(); val size = alloc<ULongVar>().also { it.value = sizeOf<IntVar>().convert() }
            if (platform.darwin.sysctlbyname(name, value.ptr, size.ptr, null, 0u) == 0) value.value else null
        }
        val info = NSProcessInfo.processInfo
        val all = info.activeProcessorCount.toInt()
        val performance = sysctl("hw.perflevel0.physicalcpu") ?: maxOf(1, all / 2)
        val hot = info.thermalState.value >= NSProcessInfoThermalState.NSProcessInfoThermalStateSerious.value
        return MangaDeviceProfile(
            performance, sysctl("hw.perflevel1.physicalcpu") ?: (all - performance),
            (info.physicalMemory / 1_000_000u).toLong(), constrained = info.lowPowerModeEnabled || hot,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun fontFamily(path: String, weight: Int): FontFamily {
        val data = NSData.dataWithContentsOfFile(path) ?: throw MangaModelException("cannot read $path")
        val bytes = ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
        return FontFamily(Font(identity = "$path@$weight", data = bytes, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))))
    }

    override fun fontResolver(): FontFamily.Resolver = createFontFamilyResolver()
}

/** Pages decoded and encoded by the system; Compose bitmaps through Skia. */
public object IosSkiaPageCodec : PageCodec {
    /** Decoded by the system (UIKit / ImageIO): Skia on iOS carries no JPEG decoder. */
    @OptIn(ExperimentalForeignApi::class)
    override fun decode(bytes: ByteArray): RgbImage? {
        if (bytes.isEmpty()) return null
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        val cg = UIImage.imageWithData(data)?.CGImage ?: return null
        val w = CGImageGetWidth(cg).toInt(); val h = CGImageGetHeight(cg).toInt()
        val rgba = ByteArray(w * h * 4)
        rgba.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val ctx = CGBitmapContextCreate(pinned.addressOf(0), w.convert(), h.convert(), 8u, (w * 4).convert(), space,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
            CGContextRelease(ctx); CGColorSpaceRelease(space)
        }
        return fromRgba(w, h, rgba)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray {
        val w = image.width; val h = image.height
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) { rgba[i * 4] = image.pixels[i * 3]; rgba[i * 4 + 1] = image.pixels[i * 3 + 1]; rgba[i * 4 + 2] = image.pixels[i * 3 + 2]; rgba[i * 4 + 3] = -1 }
        val jpeg = rgba.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val ctx = CGBitmapContextCreate(pinned.addressOf(0), w.convert(), h.convert(), 8u, (w * 4).convert(), space,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
            val cg = CGBitmapContextCreateImage(ctx)
            val data = UIImageJPEGRepresentation(UIImage.imageWithCGImage(cg), quality / 100.0)
            CGImageRelease(cg); CGContextRelease(ctx); CGColorSpaceRelease(space)
            data
        } ?: throw MangaModelException("JPEG encoding failed")
        return ByteArray(jpeg.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), jpeg.bytes, jpeg.length) } }
    }

    /**
     * The reader's images go through Skia. The Skia of Compose 1.11 reads JPEG on iOS, so a page
     * it reads is handed over as it is; one it cannot (an older Skia had no JPEG decoder on iOS,
     * ADR-0005) is turned into PNG, which it always reads.
     */
    override fun forDisplay(jpeg: ByteArray): ByteArray = if (skiaReads(jpeg)) jpeg else pngOf(jpeg) ?: jpeg

    private fun skiaReads(encoded: ByteArray): Boolean =
        runCatching {
            val image = Image.makeFromEncoded(encoded)
            try {
                image.width > 0 && image.height > 0
            } finally {
                image.close()
            }
        }.getOrDefault(false)

    @OptIn(ExperimentalForeignApi::class)
    private fun pngOf(jpeg: ByteArray): ByteArray? {
        val png = jpeg.usePinned { NSData.create(bytes = it.addressOf(0), length = jpeg.size.convert()) }
            .let { UIImage.imageWithData(it) }?.let { UIImagePNGRepresentation(it) }
        return png?.let { data -> ByteArray(data.length.toInt()).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } } }
    }

    override fun toImageBitmap(image: RgbImage): ImageBitmap = bitmapOf(image).asComposeImageBitmap()

    override fun fromImageBitmap(bitmap: ImageBitmap): RgbImage {
        val skia = bitmap.asSkiaBitmap()
        val info = ImageInfo(skia.width, skia.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        return fromRgba(skia.width, skia.height, skia.readPixels(info, skia.width * 4, 0, 0)!!)
    }

    private fun bitmapOf(image: RgbImage): Bitmap {
        val rgba = ByteArray(image.width * image.height * 4)
        for (i in 0 until image.width * image.height) {
            rgba[i * 4] = image.pixels[i * 3]; rgba[i * 4 + 1] = image.pixels[i * 3 + 1]; rgba[i * 4 + 2] = image.pixels[i * 3 + 2]; rgba[i * 4 + 3] = -1
        }
        return Bitmap().apply {
            allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
            installPixels(rgba)
        }
    }

    private fun fromRgba(width: Int, height: Int, rgba: ByteArray): RgbImage {
        val rgb = ByteArray(width * height * 3)
        for (i in 0 until width * height) { rgb[i * 3] = rgba[i * 4]; rgb[i * 3 + 1] = rgba[i * 4 + 1]; rgb[i * 3 + 2] = rgba[i * 4 + 2] }
        return RgbImage(width, height, rgb)
    }
}
