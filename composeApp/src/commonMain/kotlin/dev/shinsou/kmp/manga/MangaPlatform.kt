package dev.shinsou.kmp.manga

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily

/**
 * What a platform provides for manga translation (ADR-0005 §4): model inference, the image
 * operations the fill-back needs, page decoding and the font the translation is drawn in.
 * Everything else, from detection to typesetting, is common code.
 */
public interface MangaPlatform {
    public val onnx: OnnxRuntime
    public val ops: VisionOps
    public val codec: PageCodec

    /** A font family read from a font file, at the given weight (the font may be variable). */
    public fun fontFamily(path: String, weight: Int): FontFamily

    /** Resolves font families outside composition, for drawing text on a page. */
    public fun fontResolver(): FontFamily.Resolver

    /**
     * The ways this device can run detection (docs/research/manga-gpu-npu.md): always the CPU;
     * the GPU and the NPU where the platform has a runtime for them, otherwise listed with the
     * reason they are not available.
     */
    public fun accelerators(): List<AcceleratorSupport> = listOf(
        AcceleratorSupport(MangaAccelerator.CPU, notes = AcceleratorNotes.CPU),
        AcceleratorSupport(MangaAccelerator.GPU, unsupportedReason = "No GPU acceleration on this device"),
        AcceleratorSupport(MangaAccelerator.NPU, unsupportedReason = "No NPU acceleration on this device"),
    )

    /**
     * A detector on [accelerator] (not the CPU, whose detector is common), when its models (and
     * runtime) are installed; null when it cannot run here.
     */
    public fun acceleratedDetector(paths: MangaModelPaths, accelerator: MangaAccelerator): PageDetector? = null

    /** Keeps [entries] (by their file names) of a downloaded runtime archive in [directory]. */
    public fun extractRuntime(archive: String, entries: List<String>, directory: String): Unit =
        throw MangaModelException("This platform installs no native runtime")

    /**
     * Readies [accelerator] once its downloads are in (Qualcomm's NPU compiles the detector for
     * this device, about half a minute, and keeps the result); quick when it is ready already.
     */
    public suspend fun prepare(accelerator: MangaAccelerator, paths: MangaModelPaths): Unit = Unit

    /** Deletes what [prepare] kept for [accelerator] (the NPU's compiled detector), once it is removed. */
    public fun forget(accelerator: MangaAccelerator): Unit = Unit

    /**
     * One thread for [MangaCpuMode.SAVER], on an efficiency core where the system can be asked to
     * keep it there (Apple's background quality of service, Android's background group).
     */
    public fun efficiencyDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** What this device can give the models, read when a pipeline is built (see [MangaTuning]). */
    public fun profile(): MangaDeviceProfile = MangaDeviceProfile(performanceCores = 4, memoryMb = 4_000)
}

/** An RGB image, three bytes per pixel, rows top to bottom. */
public class RgbImage(public val width: Int, public val height: Int, public val pixels: ByteArray) {
    init {
        require(pixels.size == width * height * 3) { "expected ${width * height * 3} bytes, got ${pixels.size}" }
    }

    public fun r(i: Int): Int = pixels[i * 3].toInt() and 0xff
    public fun g(i: Int): Int = pixels[i * 3 + 1].toInt() and 0xff
    public fun b(i: Int): Int = pixels[i * 3 + 2].toInt() and 0xff

    /** A copy of the rectangle [x0, x1) × [y0, y1), clamped to the image. */
    public fun crop(x0: Int, y0: Int, x1: Int, y1: Int): RgbImage {
        val ax = x0.coerceIn(0, width - 1); val ay = y0.coerceIn(0, height - 1)
        val bx = x1.coerceIn(ax + 1, width); val by = y1.coerceIn(ay + 1, height)
        val w = bx - ax; val h = by - ay
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) pixels.copyInto(out, y * w * 3, ((ay + y) * width + ax) * 3, ((ay + y) * width + bx) * 3)
        return RgbImage(w, h, out)
    }

    /** Writes [src] into this image with its top-left corner at (x0, y0). */
    public fun paste(src: RgbImage, x0: Int, y0: Int) {
        for (y in 0 until src.height) {
            val ty = y0 + y
            if (ty < 0 || ty >= height) continue
            val sx = maxOf(0, -x0); val ex = minOf(src.width, width - x0)
            if (ex <= sx) continue
            src.pixels.copyInto(pixels, (ty * width + x0 + sx) * 3, (y * src.width + sx) * 3, (y * src.width + ex) * 3)
        }
    }

    public fun copy(): RgbImage = RgbImage(width, height, pixels.copyOf())
}

/** A rectangle in page pixels. */
public data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    val area: Float get() = maxOf(0f, width) * maxOf(0f, height)

    public fun overlap(o: Box): Float = maxOf(0f, minOf(x1, o.x1) - maxOf(x0, o.x0)) * maxOf(0f, minOf(y1, o.y1) - maxOf(y0, o.y0))

    public fun iou(o: Box): Float {
        val i = overlap(o)
        return if (i <= 0f) 0f else i / (area + o.area - i)
    }
}

/** Decodes page images and encodes the translated pages. */
public interface PageCodec {
    /** Decodes JPEG, PNG, WebP (and whatever else the platform reads) to RGB. Null when it cannot. */
    public fun decode(bytes: ByteArray): RgbImage?

    public fun encodeJpeg(image: RgbImage, quality: Int = 90): ByteArray

    /**
     * A drawn page (JPEG, as cached) in a form the app's image loader shows. Unchanged where its
     * Skia reads JPEG; iOS's Skia has no JPEG decoder, so there it becomes PNG.
     */
    public fun forDisplay(jpeg: ByteArray): ByteArray = jpeg

    public fun toImageBitmap(image: RgbImage): ImageBitmap

    public fun fromImageBitmap(bitmap: ImageBitmap): RgbImage
}

/**
 * The image operations the fill-back uses, backed by OpenCV on every platform. Masks are one byte
 * per pixel (0 or non-zero), row-major.
 */
public interface VisionOps {
    /** Resizes with bilinear interpolation ([area] uses pixel-area averaging, better for shrinking). */
    public fun resize(image: RgbImage, width: Int, height: Int, area: Boolean = false): RgbImage

    /** Keeps the image operations to the calling thread ([MangaCpuMode.SAVER]), or lets them spread again. */
    public fun singleThreaded(on: Boolean): Unit = Unit

    /** k-means over [samples] (n × 3 floats); returns the k × 3 centres. */
    public fun kmeans(samples: FloatArray, k: Int, attempts: Int): FloatArray

    /** Areas of the 8-connected components of [mask], background excluded. */
    public fun componentAreas(mask: ByteArray, width: Int, height: Int): IntArray

    /**
     * Pixels reachable from (x, y) whose colour stays within [tolerance] per channel of the seed's
     * colour (a fixed range, so it cannot creep through anti-aliased outlines).
     */
    public fun floodFill(image: RgbImage, x: Int, y: Int, tolerance: Int): ByteArray

    /** The largest external contour of [mask], filled (holes such as glyphs included). */
    public fun fillLargestContour(mask: ByteArray, width: Int, height: Int): ByteArray

    public fun morphology(mask: ByteArray, width: Int, height: Int, op: Morph, kernel: Int, ellipse: Boolean = false, iterations: Int = 1): ByteArray

    /** Euclidean distance (3×3 mask) of each set pixel to the nearest unset one. */
    public fun distanceTransform(mask: ByteArray, width: Int, height: Int): FloatArray

    /** Telea inpainting of the pixels set in [mask]. */
    public fun inpaint(image: RgbImage, mask: ByteArray, radius: Double): RgbImage
}

public enum class Morph { DILATE, ERODE, CLOSE }

/** Where a model runs. Anything but the CPU is tried first and falls back to it when unavailable. */
public enum class Accelerator { CPU, COREML, NNAPI, XNNPACK }

/** Loads models and makes tensors. Values stay in native memory between runs. */
public interface OnnxRuntime {
    /**
     * Opens a model. [variableShapes]: every run has a different input size (LaMa), so the
     * session neither keeps an arena nor plans memory per shape, which would only grow.
     */
    public fun open(path: String, threads: Int, variableShapes: Boolean = false, accelerator: Accelerator = Accelerator.CPU): OnnxSession

    public fun floats(data: FloatArray, shape: LongArray): OnnxValue

    public fun longs(data: LongArray, shape: LongArray): OnnxValue

    public fun bools(data: BooleanArray, shape: LongArray): OnnxValue
}

/** A tensor owned by the runtime. Close it unless it belongs to an [OnnxOutputs]. */
public interface OnnxValue : AutoCloseable {
    public val shape: LongArray

    public fun floats(): FloatArray

    public fun longs(): LongArray
}

public interface OnnxSession : AutoCloseable {
    public val inputNames: List<String>
    public val outputNames: List<String>

    /** Runs the model. The inputs stay owned by the caller; the outputs belong to the result. */
    public fun run(inputs: Map<String, OnnxValue>): OnnxOutputs
}

/** The outputs of one run; closing it releases them all. */
public interface OnnxOutputs : AutoCloseable {
    public operator fun get(name: String): OnnxValue
}
