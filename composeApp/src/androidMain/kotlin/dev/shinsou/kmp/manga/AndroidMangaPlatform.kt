package dev.shinsou.kmp.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import java.io.ByteArrayOutputStream
import java.io.File

/** Manga translation on Android: ONNX Runtime and OpenCV for Android, Bitmap for pages. */
public class AndroidMangaPlatform private constructor(private val context: Context) : MangaPlatform {
    // the native libraries load on first use, not at app start
    override val onnx: OnnxRuntime by lazy { JvmOnnxRuntime() }
    override val ops: VisionOps by lazy {
        check(org.opencv.android.OpenCVLoader.initLocal()) { "OpenCV did not load" }
        OpenCvVisionOps()
    }
    override val codec: PageCodec = AndroidPageCodec

    override fun profile(): MangaDeviceProfile {
        val all = Runtime.getRuntime().availableProcessors()
        // big cores by their top frequency (the little ones slow the models down)
        val frequencies = (0 until all).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val big = bigCores(frequencies)?.takeIf { frequencies.size == all } ?: (all * 3 / 4)
        val memory = android.app.ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }.totalMem / 1_000_000
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val hot = android.os.Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        return MangaDeviceProfile(big, all - big, memory, constrained = power.isPowerSaveMode || hot)
    }

    /** The NPU generation of this phone's Snapdragon, when it is one the detector runs on. */
    private val htpArch: Int? by lazy {
        if (android.os.Build.VERSION.SDK_INT < 31 || android.os.Build.SOC_MANUFACTURER != "QTI") null
        else QnnSoc.htpArch(android.os.Build.SOC_MODEL)
    }

    override fun accelerators(): List<AcceleratorSupport> = listOf(
        AcceleratorSupport(MangaAccelerator.CPU, notes = AcceleratorNotes.CPU),
        // the QNN GPU backend gave no speed-up on the detector (docs/research/manga-gpu-npu.md)
        AcceleratorSupport(MangaAccelerator.GPU, unsupportedReason = "No GPU acceleration on this device"),
        htpArch?.let { arch ->
            AcceleratorSupport(
                MangaAccelerator.NPU,
                packs = listOf(MangaModelCatalog.QNN_DETECTOR),
                runtime = QnnSoc.runtime(arch),
                notes = AcceleratorNotes.QNN_NPU,
                weight = 4,
            )
        } ?: AcceleratorSupport(MangaAccelerator.NPU, unsupportedReason = "Needs a Snapdragon 8 Gen 2 or later"),
    )

    private val qnnContexts: File get() = File(context.filesDir, "manga/qnn-context")

    override fun acceleratedDetector(paths: MangaModelPaths, accelerator: MangaAccelerator): PageDetector? {
        if (accelerator != MangaAccelerator.NPU || htpArch == null) return null
        val model = paths.qnnDetector ?: return null
        val runtime = paths.qnnRuntime ?: return null
        return AndroidQnnDetector.open(ops, model, runtime, qnnContexts)
    }

    override fun extractRuntime(archive: String, entries: List<String>, directory: String) {
        val dir = File(directory).apply { mkdirs() }
        val wanted = entries.toSet()
        java.util.zip.ZipFile(archive).use { zip ->
            for (name in wanted) {
                val entry = zip.getEntry(name) ?: throw MangaModelException("$name is missing from the runtime")
                val target = File(dir, name.substringAfterLast('/'))
                val partial = File(dir, target.name + ".part")
                zip.getInputStream(entry).use { input -> partial.outputStream().use { input.copyTo(it) } }
                // native code is loaded only from files the app cannot write
                partial.setReadOnly()
                if (!partial.renameTo(target)) throw MangaModelException("${target.name} could not be kept")
            }
        }
    }

    override fun forget(accelerator: MangaAccelerator) {
        if (accelerator == MangaAccelerator.NPU) qnnContexts.deleteRecursively()
    }

    override suspend fun prepare(accelerator: MangaAccelerator, paths: MangaModelPaths) {
        // compiles the detector for this NPU once (about half a minute) and keeps it
        (acceleratedDetector(paths, accelerator) as? AutoCloseable)?.close()
    }

    // the background group keeps a thread on the little cores
    private val saver by lazy { efficiencyThreadDispatcher { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } }

    override fun efficiencyDispatcher(): kotlinx.coroutines.CoroutineDispatcher = saver

    override fun fontFamily(path: String, weight: Int): FontFamily = FontFamily(
        Font(File(path), FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))),
    )

    override fun fontResolver(): FontFamily.Resolver = createFontFamilyResolver(context)

    public companion object {
        /** Null on devices whose ABI the inference and image libraries are not shipped for (arm64 only). */
        public fun create(context: Context): AndroidMangaPlatform? =
            if ("arm64-v8a" in android.os.Build.SUPPORTED_ABIS) AndroidMangaPlatform(context.applicationContext) else null
    }
}

public object AndroidPageCodec : PageCodec {
    override fun decode(bytes: ByteArray): RgbImage? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return fromBitmap(bitmap)
    }

    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray =
        ByteArrayOutputStream().also { toBitmap(image).compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    override fun toImageBitmap(image: RgbImage): ImageBitmap = toBitmap(image).asImageBitmap()

    override fun fromImageBitmap(bitmap: ImageBitmap): RgbImage = fromBitmap(bitmap.asAndroidBitmap())

    private fun toBitmap(image: RgbImage): Bitmap {
        val px = IntArray(image.width * image.height) { i -> (0xff shl 24) or (image.r(i) shl 16) or (image.g(i) shl 8) or image.b(i) }
        return Bitmap.createBitmap(px, image.width, image.height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun fromBitmap(bitmap: Bitmap): RgbImage {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        return RgbImage(w, h, rgb)
    }
}
