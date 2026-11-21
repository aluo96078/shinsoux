package dev.shinsou.kmp.manga

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File

/**
 * Manga translation on the desktop: ONNX Runtime's Java API, OpenCV through bytedeco's build of
 * its Java API, and Skia for pages and text.
 */
public class DesktopMangaPlatform private constructor() : MangaPlatform {
    // the native libraries load on first use, not at app start
    override val onnx: OnnxRuntime by lazy { JvmOnnxRuntime() }
    override val ops: VisionOps by lazy {
        org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java::class.java)
        OpenCvVisionOps()
    }
    override val codec: PageCodec = SkiaPageCodec

    override fun profile(): MangaDeviceProfile = desktopProfile

    private val desktopProfile: MangaDeviceProfile by lazy {
        val logical = Runtime.getRuntime().availableProcessors()
        // A runtime without the management modules (jlink) has no answer: assume a typical machine.
        val memory = try {
            (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                ?.totalMemorySize?.div(1_000_000)
        } catch (_: LinkageError) {
            // Feature detection: a JVM without the com.sun MXBean falls back to the default below.
            null
        } ?: 8_000L
        // Apple silicon reports its performance and efficiency cores; elsewhere a core runs two threads
        // Feature detection: an Intel Mac has no perflevel keys and falls back to the estimate below.
        val apple = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) runCatching {
            val out = ProcessBuilder("sysctl", "-n", "hw.perflevel0.physicalcpu", "hw.perflevel1.physicalcpu")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
            out.getOrNull(0)?.trim()?.toInt()?.let { it to (out.getOrNull(1)?.trim()?.toIntOrNull() ?: 0) }
        }.getOrNull() else null
        val (performance, efficiency) = apple ?: (maxOf(1, logical / 2) to 0)
        MangaDeviceProfile(performance, efficiency, memory)
    }

    /** Apple silicon: Core ML runs the R50 detector through ONNX Runtime (docs/research/manga-gpu-npu.md). */
    private val appleSilicon: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac") &&
        System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }

    override fun accelerators(): List<AcceleratorSupport> {
        if (!appleSilicon) return super.accelerators()
        val pack = listOf(MangaModelCatalog.QNN_DETECTOR) // the same ONNX model as Qualcomm's NPU
        return listOf(
            AcceleratorSupport(MangaAccelerator.CPU, notes = AcceleratorNotes.CPU),
            AcceleratorSupport(MangaAccelerator.GPU, packs = pack, notes = AcceleratorNotes.MAC_GPU, weight = 4),
            // through ONNX Runtime the Neural Engine is slower than the CPU's detector and less precise
            AcceleratorSupport(MangaAccelerator.NPU, packs = pack, notes = AcceleratorNotes.MAC_NPU, weight = 1),
        )
    }

    override fun acceleratedDetector(paths: MangaModelPaths, accelerator: MangaAccelerator): PageDetector? {
        if (!appleSilicon || accelerator == MangaAccelerator.CPU) return null
        val model = paths.qnnDetector ?: return null
        val (units, id) = when (accelerator) {
            MangaAccelerator.GPU -> "CPUAndGPU" to "coreml-r50-mac"
            else -> "CPUAndNeuralEngine" to "coreml-r50-mac-ane"
        }
        // Core ML compiles the model the first time (some seconds) and keeps it beside the model,
        // so it goes when the model is removed
        val cache = File(model).resolveSibling("coreml-$units").apply { mkdirs() }
        val options = ai.onnxruntime.OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2) // for the few nodes Core ML leaves to the CPU
            addCoreML(mapOf("ModelFormat" to "MLProgram", "MLComputeUnits" to units, "ModelCacheDirectory" to cache.path))
        }
        val session = options.use { ortEnvironment().createSession(model, it) }
        return OrtQueryDetector(ops, session, id)
    }

    override suspend fun prepare(accelerator: MangaAccelerator, paths: MangaModelPaths) {
        (acceleratedDetector(paths, accelerator) as? AutoCloseable)?.close()
    }

    private val saver by lazy { efficiencyThreadDispatcher(setup = efficiencyCoreRequest(System.getProperty("os.name").orEmpty())) }

    override fun efficiencyDispatcher(fallback: kotlinx.coroutines.CoroutineDispatcher): kotlinx.coroutines.CoroutineDispatcher =
        saver

    override fun fontFamily(path: String, weight: Int): FontFamily = FontFamily(
        Font(File(path), FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))),
    )

    override fun fontResolver(): FontFamily.Resolver = createFontFamilyResolver()

    public companion object {
        public fun create(): DesktopMangaPlatform = DesktopMangaPlatform()
    }
}

private const val QOS_CLASS_BACKGROUND = 0x09
private const val THREAD_MODE_BACKGROUND_BEGIN = 0x00010000

/**
 * The call that asks [os] to keep the saver thread on efficiency cores. A native library that is
 * missing, or a call it refuses, throws: [efficiencyThreadDispatcher] reports that and the thread
 * keeps only its lowest priority. A refusal the call returns as a status is turned into a throw too.
 */
internal fun efficiencyCoreRequest(os: String): () -> Unit = when {
    // background quality of service: the efficiency cores only, on Apple silicon
    os.startsWith("Mac") -> {
        {
            val status = com.sun.jna.NativeLibrary.getInstance("System")
                .getFunction("pthread_set_qos_class_self_np").invokeInt(arrayOf<Any>(QOS_CLASS_BACKGROUND, 0))
            check(status == 0) { "pthread_set_qos_class_self_np returned $status" }
        }
    }
    // background mode: Windows puts such threads on efficiency cores where it has them
    os.startsWith("Windows") -> {
        {
            val kernel32 = com.sun.jna.NativeLibrary.getInstance("kernel32")
            val self = kernel32.getFunction("GetCurrentThread").invokePointer(arrayOf())
            val done = kernel32.getFunction("SetThreadPriority").invokeInt(arrayOf<Any>(self, THREAD_MODE_BACKGROUND_BEGIN))
            check(done != 0) { "SetThreadPriority(THREAD_MODE_BACKGROUND_BEGIN) failed" }
        }
    }
    else -> {
        {} // the lowest priority is all
    }
}

/** Page decoding and encoding through Skia (desktop and iOS draw with it too). */
public object SkiaPageCodec : PageCodec {
    // A decode probe: bytes Skia cannot read are not a page; the caller treats null as such.
    override fun decode(bytes: ByteArray): RgbImage? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        val bitmap = Bitmap().apply { allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)) }
        image.readPixels(bitmap)
        fromRgba(bitmap.width, bitmap.height, bitmap.readPixels()!!)
    }.getOrNull()

    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray {
        val bitmap = bitmapOf(image)
        return Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.JPEG, quality)!!.bytes
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
