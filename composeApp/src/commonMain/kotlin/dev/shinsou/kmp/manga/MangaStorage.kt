package dev.shinsou.kmp.manga

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** One file of a model pack, pinned by size and SHA-256 (tools/manga-models/manifest.json). */
/**
 * A file of a pack. Its [name] may be a path inside the pack (a Core ML package is a directory);
 * its release asset is named by the pack and the last part of the path, which must be unique.
 */
public data class PackFile(val name: String, val size: Long, val sha256: String) {
    val assetName: String get() = name.substringAfterLast('/')
}

public data class ModelPack(val id: String, val files: List<PackFile>) {
    val downloadBytes: Long get() = files.sumOf { it.size }
}

/** The model packs this build uses (ADR-0005 §5). A new model arrives with an app update. */
public object MangaModelCatalog {
    /** GitHub release that hosts the packs; each pack is a directory of release assets. */
    public const val BASE_URL: String = "https://github.com/aluo96078/shinsoux/releases/download/manga-models-v1/"

    public val VISION: ModelPack = ModelPack(
        "vision-v1",
        listOf(
            PackFile("detector.onnx", 11_120_765, "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"),
            PackFile("encoder.onnx", 6_621_066, "2b924ea810de6b7032af8b72be5de2fe983d29a997241fc93be7ea595b1a21fa"),
            PackFile("decoder.onnx", 29_931_626, "9df27487489519c54c7e8c06e2a35df9750ae737f5e82cd3d3b8b9b5b5de692d"),
            PackFile("vocab.txt", 24_072, "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d"),
            PackFile("NotoSansTC-VF.ttf", 11_941_968, "864727d210d54f2537bbe23b3a839436c3992af72de9322af5270897246bd44f"),
        ),
    )

    /** Phase 2: LaMa for text over the artwork. */
    public val INPAINT: ModelPack = ModelPack(
        "inpaint-v1",
        listOf(PackFile("lama.onnx", 64_246_827, "51adf934172075d602fcbebddbda2aa97174829ecd0e4d7735221bd2e90f4e51")),
    )

    /**
     * The detector (RT-DETRv2 R50) as a Core ML package for the GPU of Apple devices
     * (docs/research/manga-gpu-npu.md): four times as fast as [VISION]'s on an iPhone, and it
     * finds more text drawn over the artwork. Built by tools/manga-models/convert_coreml_detector.py.
     */
    public val COREML_DETECTOR: ModelPack = ModelPack(
        "vision-coreml-v1",
        listOf(
            PackFile("detector.mlpackage/Data/com.apple.CoreML/model.mlmodel", 381_733, "eadf5dff1f89b14126ff3f5584d60d74afea143b8c0a2bd1c014ac077407b6f6"),
            PackFile("detector.mlpackage/Data/com.apple.CoreML/weights/weight.bin", 85_812_352, "8eec2f94d0b30d42dbe1f675ff80256b61ad6d8414b7331385ac1c629f6f0950"),
            PackFile("detector.mlpackage/Manifest.json", 617, "25bf03065597f9dce9baf1c9730c1be8c4226ab2bdfb7e5cf88540a970f2e3b5"),
        ),
    )

    /**
     * The same detector (RT-DETRv2 R50) as fp16 ONNX for Qualcomm's NPU (QNN HTP), compiled on the
     * device the first time; it reads the same as the full-precision model there.
     */
    public val QNN_DETECTOR: ModelPack = ModelPack(
        "vision-qnn-v1",
        listOf(PackFile("detector.onnx", 86_153_146, "66f697d0d1b75d7534967be2691ee07e56b6c81f0689f3bfd6b1597a70424c5c")),
    )

    /** Qualcomm's QNN runtime, the version the app's ONNX Runtime (onnxruntime-android-qnn 1.29) was built with. */
    public const val QNN_RUNTIME_VERSION: String = "2.42.0"
    public const val QNN_RUNTIME_URL: String =
        "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/$QNN_RUNTIME_VERSION/qnn-runtime-$QNN_RUNTIME_VERSION.aar"
    public const val QNN_RUNTIME_SIZE: Long = 63_074_692
    public const val QNN_RUNTIME_SHA256: String = "ecb69bd6a7b1be4717ef10805199fb49ec6b60ca3f481d3068ed9605fba91d5b"
}

/**
 * Model packs on this device: `<root>/<pack id>/<file>`. A file only gets its final name after
 * its size and SHA-256 matched the catalog, so a broken download never reaches the runtime.
 */
public class MangaModelStore(
    root: String,
    private val http: HttpClient,
    private val baseUrl: String = MangaModelCatalog.BASE_URL,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val root: Path = root.toPath()
    private val downloads = Mutex()

    private fun directory(pack: ModelPack): Path = root / pack.id

    /** The pack's files by name, or null while any is missing. */
    public fun installed(pack: ModelPack): Map<String, String>? = pack.files.associate { file ->
        val path = directory(pack) / file.name
        val size = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size
        if (size != file.size) return null
        file.name to path.toString()
    }

    public fun paths(): MangaModelPaths? = installed(MangaModelCatalog.VISION)?.let {
        MangaModelPaths(
            it.getValue("detector.onnx"), it.getValue("encoder.onnx"), it.getValue("decoder.onnx"), it.getValue("vocab.txt"), it.getValue("NotoSansTC-VF.ttf"),
            inpaint = installed(MangaModelCatalog.INPAINT)?.getValue("lama.onnx"),
            coreMlDetector = installed(MangaModelCatalog.COREML_DETECTOR)?.let { (directory(MangaModelCatalog.COREML_DETECTOR) / "detector.mlpackage").toString() },
            qnnDetector = installed(MangaModelCatalog.QNN_DETECTOR)?.getValue("detector.onnx"),
        )
    }

    public fun missingBytes(pack: ModelPack): Long = pack.files.filter { file ->
        fileSystem.metadataOrNull(directory(pack) / file.name)?.size != file.size
    }.sumOf { it.size }

    public suspend fun download(pack: ModelPack, onProgress: (received: Long, total: Long) -> Unit): Unit = downloads.withLock {
        val directory = directory(pack)
        fileSystem.createDirectories(directory)
        var received = 0L
        for (file in pack.files) {
            val target = directory / file.name
            target.parent?.let { fileSystem.createDirectories(it) }
            if (fileSystem.metadataOrNull(target)?.size == file.size) {
                received += file.size; onProgress(received, pack.downloadBytes); continue
            }
            val before = received
            fetch(baseUrl + pack.id + "-" + file.assetName, target, file.name, file.size, file.sha256) { written -> onProgress(before + written, pack.downloadBytes) }
            received += file.size
        }
    }

    /** Downloads [url] into [target] only if it is [size] bytes with the SHA-256 [sha256]. */
    private suspend fun fetch(url: String, target: Path, name: String, size: Long, sha256: String, onProgress: (written: Long) -> Unit) {
        val partial = target.parent!! / (target.name + ".part")
        try {
            var written = 0L
            val hashing = HashingSink.sha256(fileSystem.sink(partial))
            try {
                hashing.buffer().use { output ->
                    http.prepareGet(url) {
                        // the client's deadline is for API calls; a stalled connection still times out
                        if (http.pluginOrNull(HttpTimeout) != null) timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = 60_000
                        }
                    }.execute { response ->
                        if (!response.status.isSuccess()) throw MangaModelException("Model download failed: HTTP ${response.status.value}")
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            written += read
                            if (written > size) throw MangaModelException("$name is larger than expected")
                            output.write(buffer, 0, read)
                            onProgress(written)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: MangaModelException) {
                throw error
            } catch (error: Exception) {
                throw MangaModelException("Model download failed: ${error.message}", error)
            }
            if (written != size || hashing.hash.hex() != sha256) throw MangaModelException("$name did not match its pinned checksum")
            fileSystem.atomicMove(partial, target)
        } finally {
            fileSystem.delete(partial, mustExist = false)
        }
    }

    private fun runtimeDirectory(runtime: NativeRuntime): Path = root / "runtime" / runtime.id

    /** The directory of [runtime]'s libraries, or null while they are not all there. */
    public fun runtimeInstalled(runtime: NativeRuntime): String? {
        val directory = runtimeDirectory(runtime)
        val complete = runtime.entries.all { fileSystem.exists(directory / it.substringAfterLast('/')) }
        return directory.toString().takeIf { complete }
    }

    /**
     * Fetches [runtime]'s archive from its publisher, checks it, and has [extract] keep this
     * device's [NativeRuntime.entries] (by their file names) in the runtime's directory; the
     * archive itself is not kept.
     */
    public suspend fun downloadRuntime(
        runtime: NativeRuntime,
        extract: (archive: String, entries: List<String>, directory: String) -> Unit,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Unit = downloads.withLock {
        if (runtimeInstalled(runtime) != null) return@withLock
        val directory = runtimeDirectory(runtime)
        fileSystem.deleteRecursively(directory, mustExist = false)
        fileSystem.createDirectories(directory)
        val archive = root / "runtime" / "${runtime.id}.download"
        try {
            fetch(runtime.url, archive, runtime.id, runtime.size, runtime.sha256) { written -> onProgress(written, runtime.size) }
            extract(archive.toString(), runtime.entries, directory.toString())
            if (runtimeInstalled(runtime) == null) throw MangaModelException("${runtime.id} is missing files")
        } catch (error: Exception) {
            fileSystem.deleteRecursively(directory, mustExist = false)
            throw error
        } finally {
            fileSystem.delete(archive, mustExist = false)
        }
    }

    public fun removeRuntime(runtime: NativeRuntime) {
        fileSystem.deleteRecursively(runtimeDirectory(runtime), mustExist = false)
    }

    /** Where detection runs on this device, as the user chose (device-local); the CPU by default. */
    public fun selectedAccelerators(): Set<MangaAccelerator> =
        runCatching { fileSystem.read(root / "accelerators.txt") { readUtf8() } }.getOrNull()
            ?.split(',')?.mapNotNull { name -> MangaAccelerator.entries.firstOrNull { it.name == name.trim() } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(MangaAccelerator.CPU)

    public fun selectAccelerators(accelerators: Set<MangaAccelerator>) {
        require(accelerators.isNotEmpty()) { "at least one" }
        fileSystem.createDirectories(root)
        fileSystem.write(root / "accelerators.txt") { writeUtf8(accelerators.joinToString(",") { it.name }) }
    }

    /** How hard the CPU works on pages, chosen on this device; balanced until chosen. */
    public fun cpuMode(): MangaCpuMode =
        runCatching { fileSystem.read(root / "cpu-mode.txt") { readUtf8() } }.getOrNull()
            ?.let { name -> MangaCpuMode.entries.firstOrNull { it.name == name.trim() } } ?: MangaCpuMode.BALANCED

    public fun setCpuMode(mode: MangaCpuMode) {
        fileSystem.createDirectories(root)
        fileSystem.write(root / "cpu-mode.txt") { writeUtf8(mode.name) }
    }

    public fun remove(pack: ModelPack) {
        fileSystem.deleteRecursively(directory(pack), mustExist = false)
    }
}

public class MangaModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The content hash pages are cached under. */
public fun pageHash(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

/**
 * Per-page OCR results, keyed by the page's image hash and the pipeline version (ADR-0005 §4).
 * Shares the translation memory's database but not its backups; least recently used pages go
 * first once [maxPages] is passed.
 */
private val PAGE_KEYS: kotlinx.serialization.KSerializer<List<String>> = kotlinx.serialization.builtins.ListSerializer(String.serializer())

public class MangaPageStore(private val driver: SqlDriver, private val now: () -> Long, private val maxPages: Int = 5_000) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS manga_ocr_page(
                image_hash TEXT NOT NULL,
                version TEXT NOT NULL,
                analysis TEXT NOT NULL,
                used_at INTEGER NOT NULL,
                PRIMARY KEY(image_hash, version)
            )
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS manga_chapter_render(
                work_key TEXT NOT NULL,
                chapter_key TEXT NOT NULL,
                variant TEXT NOT NULL,
                page_keys TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(work_key, chapter_key, variant)
            )
            """.trimIndent(),
            0,
        ).value
    }

    /**
     * The drawn pages of a whole chapter in one translation [variant] (language, provider and
     * layout), by page: a chapter opened again is shown from them without loading its pages.
     */
    public fun chapterRenders(workKey: String, chapterKey: String, variant: String): List<String>? {
        val found = driver.executeQuery(null, "SELECT page_keys FROM manga_chapter_render WHERE work_key = ? AND chapter_key = ? AND variant = ?", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 3) { bindString(0, workKey); bindString(1, chapterKey); bindString(2, variant) }.value ?: return null
        return runCatching { json.decodeFromString(PAGE_KEYS, found) }.getOrNull()
    }

    public fun putChapterRenders(workKey: String, chapterKey: String, variant: String, pageKeys: List<String>) {
        driver.execute(null, "INSERT OR REPLACE INTO manga_chapter_render(work_key, chapter_key, variant, page_keys, updated_at) VALUES (?, ?, ?, ?, ?)", 5) {
            bindString(0, workKey); bindString(1, chapterKey); bindString(2, variant)
            bindString(3, json.encodeToString(PAGE_KEYS, pageKeys)); bindLong(4, now())
        }.value
    }

    /** [version]: [VERSION], with the detector's id when another detector read the page. */
    public fun get(imageHash: String, version: String = VERSION): PageAnalysis? {
        val found = driver.executeQuery(null, "SELECT analysis FROM manga_ocr_page WHERE image_hash = ? AND version = ?", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 2) { bindString(0, imageHash); bindString(1, version) }.value ?: return null
        driver.execute(null, "UPDATE manga_ocr_page SET used_at = ? WHERE image_hash = ? AND version = ?", 3) {
            bindLong(0, now()); bindString(1, imageHash); bindString(2, version)
        }.value
        return runCatching { json.decodeFromString(PageAnalysis.serializer(), found) }.getOrNull()
    }

    public fun put(imageHash: String, analysis: PageAnalysis, version: String = VERSION) {
        driver.execute(null, "INSERT OR REPLACE INTO manga_ocr_page(image_hash, version, analysis, used_at) VALUES (?, ?, ?, ?)", 4) {
            bindString(0, imageHash); bindString(1, version); bindString(2, json.encodeToString(PageAnalysis.serializer(), analysis)); bindLong(3, now())
        }.value
        driver.execute(
            null,
            "DELETE FROM manga_ocr_page WHERE rowid IN (SELECT rowid FROM manga_ocr_page ORDER BY used_at DESC LIMIT -1 OFFSET ?)",
            1,
        ) { bindLong(0, maxPages.toLong()) }.value
    }

    public fun clear() {
        driver.execute(null, "DELETE FROM manga_ocr_page", 0).value
        driver.execute(null, "DELETE FROM manga_chapter_render", 0).value
    }

    public companion object {
        /** Bumped when the models, their quantisation or the batching change what a page reads. */
        public const val VERSION: String = "vision-v1"
    }
}

/**
 * Translated pages as JPEG files, keyed by page hash, target language, provider and layout. The
 * oldest files go once the cache passes [maxBytes].
 */
public class MangaRenderCache(root: String, private val maxBytes: Long = 300L * 1024 * 1024, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val root: Path = root.toPath()
    private val lock = Mutex()

    private fun path(key: String): Path = root / "$key.jpg"

    /** [detector]: the detector's id when it is not the default one (pages it read look different). */
    /**
     * A drawn page's key. [translation] is the text drawn on it: the same image translated with
     * another work's names and terms is another page, not the first work's drawing.
     */
    public fun key(
        imageHash: String, to: String, provider: String, orientation: MangaOrientation,
        inpaint: Boolean = false, detector: String? = null, translation: String? = null,
    ): String =
        listOf(imageHash, to, provider, orientation.name, LAYOUT_VERSION)
            .plus(listOfNotNull(if (inpaint) MangaModelCatalog.INPAINT.id else null, detector, translation?.let { "t" + pageHash(it.encodeToByteArray()) }))
            .joinToString("-").let { pageHash(it.encodeToByteArray()) }

    public fun get(key: String): ByteArray? = runCatching { fileSystem.read(path(key)) { readByteArray() } }.getOrNull()

    public suspend fun put(key: String, jpeg: ByteArray): Unit = lock.withLock {
        fileSystem.createDirectories(root)
        val target = path(key); val partial = root / "$key.part"
        fileSystem.write(partial) { write(jpeg) }
        fileSystem.atomicMove(partial, target)
        trim()
    }

    public fun clear() {
        fileSystem.deleteRecursively(root, mustExist = false)
    }

    private fun trim() {
        val files = fileSystem.listOrNull(root)?.filter { it.name.endsWith(".jpg") } ?: return
        val sized = files.mapNotNull { p -> fileSystem.metadataOrNull(p)?.let { Triple(p, it.size ?: 0L, it.lastModifiedAtMillis ?: 0L) } }
        var total = sized.sumOf { it.second }
        for ((p, size, _) in sized.sortedBy { it.third }) {
            if (total <= maxBytes) break
            fileSystem.delete(p, mustExist = false); total -= size
        }
    }

    public companion object {
        /** Bumped when the eraser, layout or renderer change what a page looks like. */
        public const val LAYOUT_VERSION: String = "3"
    }
}
