package dev.shinsou.kmp.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaStorageTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val bodies = mapOf("a.onnx" to ByteArray(150_000) { (it % 251).toByte() }, "b.txt" to "vocab".toByteArray())
    private val pack = ModelPack("p-v1", bodies.map { (n, b) -> PackFile(n, b.size.toLong(), sha(b)) })

    private fun store(root: File, served: Map<String, ByteArray>) = MangaModelStore(
        root.absolutePath,
        HttpClient(MockEngine { request ->
            val name = request.url.encodedPath.substringAfterLast('/').removePrefix("p-v1-")
            served[name]?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
        }),
        baseUrl = "https://models.test/",
    )

    @Test
    fun aPackIsInstalledOnlyAfterEveryFileMatchesItsChecksum() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manga").toFile()
        val good = store(root, bodies)
        assertNull(good.installed(pack))
        assertEquals(pack.downloadBytes, good.missingBytes(pack))
        good.download(pack) { _, _ -> }
        val files = assertNotNull(good.installed(pack))
        assertTrue(File(files.getValue("a.onnx")).readBytes().contentEquals(bodies.getValue("a.onnx")))
        assertEquals(0, good.missingBytes(pack))

        val other = Files.createTempDirectory("manga").toFile()
        val tampered = store(other, bodies + ("a.onnx" to ByteArray(150_000)))
        assertFailsWith<MangaModelException> { tampered.download(pack) { _, _ -> } }
        assertNull(tampered.installed(pack))
        assertTrue(other.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun pageAnalysesRoundTripAndTheOldestGoFirst() {
        var clock = 0L
        val pages = MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), now = { ++clock }, maxPages = 2)
        val analysis = PageAnalysis(100, 200, listOf(TextRegion(RegionKind.BUBBLE_TEXT, 1, 2, 30, 40, "セリフ")), listOf(intArrayOf(0, 0, 50, 60)))
        pages.put("a", analysis)
        assertEquals(analysis, pages.get("a"))
        pages.put("b", analysis)
        pages.get("a")            // a is now more recent than b
        pages.put("c", analysis)  // over the limit: b goes
        assertNotNull(pages.get("a")); assertNull(pages.get("b")); assertNotNull(pages.get("c"))
    }

    @Test
    fun renderedPagesAreTrimmedToTheirBudget() = runBlocking {
        val root = Files.createTempDirectory("render").toFile()
        val cache = MangaRenderCache(root.absolutePath, maxBytes = 250)
        val k1 = cache.key("h1", "zh-Hant", "offline", MangaOrientation.VERTICAL)
        assertTrue(k1 != cache.key("h1", "zh-Hant", "offline", MangaOrientation.HORIZONTAL))
        cache.put(k1, ByteArray(200))
        Thread.sleep(20)
        val k2 = cache.key("h2", "zh-Hant", "offline", MangaOrientation.VERTICAL)
        cache.put(k2, ByteArray(200))
        assertNull(cache.get(k1))
        assertEquals(200, cache.get(k2)?.size)
    }

    @Test
    fun theLamaPackIsOptionalAndPagesDrawnWithItAreKeptApart() {
        val root = Files.createTempDirectory("manga").toFile()
        fun install(pack: ModelPack) = pack.files.forEach { file ->
            java.io.RandomAccessFile(root.resolve("${pack.id}/${file.name}").apply { parentFile.mkdirs() }, "rw").use { it.setLength(file.size) }
        }
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { respond("") }))
        install(MangaModelCatalog.VISION)
        assertNull(assertNotNull(store.paths()).inpaint, "without the pack, text over artwork stays as drawn")
        install(MangaModelCatalog.INPAINT)
        assertTrue(assertNotNull(store.paths()).inpaint!!.endsWith("lama.onnx"))
        store.remove(MangaModelCatalog.INPAINT)
        assertNull(assertNotNull(store.paths()).inpaint)

        val cache = MangaRenderCache(root.resolve("pages").path)
        val plain = cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL)
        assertEquals(plain, cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL, inpaint = false))
        assertTrue(plain != cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL, inpaint = true))
    }

    @Test
    fun languagePacksAddTheirRecognisersAndFontsAndKeepJapanesePagesWhereTheyWere() {
        val root = Files.createTempDirectory("manga").toFile()
        fun install(pack: ModelPack) = pack.files.forEach { file ->
            java.io.RandomAccessFile(root.resolve("${pack.id}/${file.name}").apply { parentFile.mkdirs() }, "rw").use { it.setLength(file.size) }
        }
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { respond("") }))
        install(MangaModelCatalog.VISION)
        install(MangaModelCatalog.OCR_ZH)
        assertEquals(emptyMap(), assertNotNull(store.paths()).ocr, "a recogniser without the line detector is no use")
        install(MangaModelCatalog.OCR_LINES)
        val zh = assertNotNull(store.paths()).ocr.getValue("zh")
        assertTrue(zh.lineDetector.endsWith("lines.onnx") && zh.recognizer.endsWith("rec.onnx") && zh.characters.endsWith("chars.txt"))
        assertNull(assertNotNull(store.paths()).ocr["en"])
        assertEquals(emptyMap(), assertNotNull(store.paths()).fonts)
        install(MangaModelCatalog.FONT_SC)
        assertTrue(assertNotNull(store.paths()).fonts.getValue("zh-Hans").endsWith("NotoSansSC-VF.ttf"))

        val cache = MangaRenderCache(root.resolve("pages").path)
        val japanese = cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL)
        assertEquals(japanese, cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL, reader = null, font = null))
        assertTrue(japanese != cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL, reader = "ppocr5-zh"))
        assertTrue(japanese != cache.key("hash", "zh-Hant", "offline", MangaOrientation.VERTICAL, font = "font-zh-Hans"))
    }

    @Test
    fun aPackWithDirectoriesDownloadsByAssetNameAndInstallsAsADirectory() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manga").toFile()
        val bodies = mapOf("Manifest.json" to "{}".toByteArray(), "weight.bin" to ByteArray(4_000) { it.toByte() })
        val pack = ModelPack("p-v1", listOf(
            PackFile("model.pkg/Manifest.json", 2, sha(bodies.getValue("Manifest.json"))),
            PackFile("model.pkg/Data/weights/weight.bin", 4_000, sha(bodies.getValue("weight.bin"))),
        ))
        val requested = mutableListOf<String>()
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { request ->
            val name = request.url.encodedPath.substringAfterLast('/').also { requested += it }.removePrefix("p-v1-")
            bodies[name]?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
        }), baseUrl = "https://models.test/")
        store.download(pack) { _, _ -> }
        assertEquals(listOf("p-v1-Manifest.json", "p-v1-weight.bin"), requested)
        assertNotNull(store.installed(pack))
        assertTrue(root.resolve("p-v1/model.pkg/Data/weights/weight.bin").isFile)
    }

    private fun manga(platform: MangaPlatform, store: MangaModelStore, root: File) =
        MangaTranslation(platform, store, MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { 0L }), MangaRenderCache(root.resolve("pages").path),
            object : MangaTextTranslator {
                override suspend fun providerId() = "x"
                override suspend fun translate(request: dev.shinsou.kmp.translation.ChapterTranslationRequest, onProgress: (Int, Int) -> Unit) = error("unused")
            }, { emptyList() })

    private fun zip(entries: Map<String, ByteArray>): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    /** An NPU that needs one model pack and a runtime archive from another server, as on a Snapdragon. */
    private class NpuPlatform(base: MangaPlatform, val pack: ModelPack, val runtime: NativeRuntime) : MangaPlatform by base {
        var prepared = 0
        override fun accelerators() = listOf(
            AcceleratorSupport(MangaAccelerator.CPU, notes = AcceleratorNotes.CPU),
            AcceleratorSupport(MangaAccelerator.GPU, unsupportedReason = "No GPU acceleration on this device"),
            AcceleratorSupport(MangaAccelerator.NPU, packs = listOf(pack), runtime = runtime, notes = AcceleratorNotes.QNN_NPU, weight = 4),
        )
        override fun extractRuntime(archive: String, entries: List<String>, directory: String) {
            java.util.zip.ZipFile(archive).use { z ->
                for (e in entries) File(directory, e.substringAfterLast('/')).writeBytes(z.getInputStream(z.getEntry(e)).readBytes())
            }
        }
        override suspend fun prepare(accelerator: MangaAccelerator, paths: MangaModelPaths) { prepared++ }
    }

    @Test
    fun anAcceleratorDownloadsItsModelsAndOnlyTheRuntimeFilesThisDeviceNeeds() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manga").toFile()
        val archive = zip(mapOf("jni/arm64-v8a/libA.so" to ByteArray(300) { 1 }, "jni/arm64-v8a/libB.so" to ByteArray(200) { 2 }, "jni/arm64-v8a/libOther.so" to ByteArray(9_000)))
        val runtime = NativeRuntime("rt-1", "https://maven.test/rt-1.aar", archive.size.toLong(), sha(archive),
            entries = listOf("jni/arm64-v8a/libA.so", "jni/arm64-v8a/libB.so"), installedBytes = 500)
        val npuPack = ModelPack("npu-v1", listOf(PackFile("detector.onnx", 1_000, sha(ByteArray(1_000) { 7 }))))
        var served = archive
        val asked = mutableListOf<String>()
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { request ->
            asked += request.url.toString()
            when (request.url.toString()) {
                runtime.url -> respond(served)
                "https://models.test/npu-v1-detector.onnx" -> respond(ByteArray(1_000) { 7 })
                else -> respond("", HttpStatusCode.NotFound)
            }
        }), baseUrl = "https://models.test/")
        val platform = NpuPlatform(DesktopMangaPlatform.create(), npuPack, runtime)
        val manga = manga(platform, store, root)
        assertEquals(MangaModelCatalog.VISION.downloadBytes, manga.missingModelBytes(), "the first download is the recognition models alone")

        val before = manga.acceleratorStates().associateBy { it.support.accelerator }
        assertEquals(setOf(MangaAccelerator.CPU), before.filterValues { it.selected }.keys)
        assertEquals(1_500, before.getValue(MangaAccelerator.NPU).missingBytes)
        assertFalse(before.getValue(MangaAccelerator.GPU).support.supported)

        // a tampered archive installs nothing
        served = archive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<MangaModelException> { manga.downloadAccelerator(MangaAccelerator.NPU, { _, _ -> }) }
        assertNull(store.runtimeInstalled(runtime))
        assertEquals(0, platform.prepared)

        served = archive
        var preparing = false
        manga.downloadAccelerator(MangaAccelerator.NPU, { _, _ -> }, onPreparing = { preparing = true })
        assertFalse(preparing, "without the recognition models it is readied by the first chapter")
        assertEquals(0, platform.prepared)
        val directory = File(assertNotNull(store.runtimeInstalled(runtime)))
        assertEquals(setOf("libA.so", "libB.so"), directory.list()!!.toSet(), "only this device's libraries, and not the archive")
        assertTrue(root.walkTopDown().none { it.name.endsWith(".download") || it.name.endsWith(".part") })
        val npu = manga.acceleratorStates().first { it.support.accelerator == MangaAccelerator.NPU }
        assertTrue(npu.installed); assertEquals(0, npu.missingBytes)

        manga.removeAccelerator(MangaAccelerator.NPU)
        assertNull(store.runtimeInstalled(runtime)); assertNull(store.installed(npuPack))
    }

    @Test
    fun theChoiceOfDetectorsIsKeptOnTheDevice() {
        val root = Files.createTempDirectory("manga").toFile()
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        assertEquals(setOf(MangaAccelerator.CPU), store.selectedAccelerators(), "the CPU until something else is chosen")
        store.selectAccelerators(setOf(MangaAccelerator.NPU, MangaAccelerator.CPU))
        val again = MangaModelStore(root.absolutePath, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        assertEquals(setOf(MangaAccelerator.NPU, MangaAccelerator.CPU), again.selectedAccelerators())
        assertFailsWith<IllegalArgumentException> { store.selectAccelerators(emptySet()) }
        assertEquals(MangaCpuMode.BALANCED, store.cpuMode(), "balanced until chosen")
        store.setCpuMode(MangaCpuMode.SAVER)
        assertEquals(MangaCpuMode.SAVER, again.cpuMode())
    }

    @Test
    fun everyDownloadIsListedWithItsSizeAndOldBuildsLeftoversCanBeRemoved() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manga").toFile()
        val runtime = NativeRuntime("rt-1", "https://maven.test/rt-1.aar", 1, "0", entries = listOf("jni/libA.so"), installedBytes = 300)
        val npuPack = ModelPack("npu-v1", listOf(PackFile("detector.onnx", 1_000, sha(ByteArray(1_000) { 7 }))))
        val store = MangaModelStore(root.absolutePath, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val manga = manga(NpuPlatform(DesktopMangaPlatform.create(), npuPack, runtime), store, root)
        MangaModelCatalog.FONT_SC.files.forEach { root.resolve("font-sc-v1/${it.name}").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10)) }
        root.resolve("npu-v1").mkdirs(); root.resolve("npu-v1/detector.onnx.part").writeBytes(ByteArray(400))
        root.resolve("runtime/rt-1").mkdirs(); root.resolve("runtime/rt-1/libA.so").writeBytes(ByteArray(300))
        // what older builds left: a pack, a runtime, and the choices (files) which stay
        root.resolve("vision-v0").mkdirs(); root.resolve("vision-v0/detector.onnx").writeBytes(ByteArray(2_000))
        root.resolve("runtime/rt-0").mkdirs(); root.resolve("runtime/rt-0/libA.so").writeBytes(ByteArray(50))
        store.selectAccelerators(setOf(MangaAccelerator.CPU))

        val listed = manga.resources().associateBy { it.id }
        val font = listed.getValue("font-sc-v1")
        assertEquals(MangaResourceKind.FONT, font.kind); assertEquals(10, font.diskBytes); assertEquals("zh-Hans", font.language)
        val vision = listed.getValue("vision-v1")
        assertEquals(0, vision.diskBytes); assertEquals(MangaModelCatalog.VISION.downloadBytes, vision.missingBytes); assertFalse(vision.installed)
        val npu = listed.getValue("npu-v1+rt-1")
        assertEquals(listOf(MangaAccelerator.NPU), npu.accelerators)
        assertEquals(700, npu.diskBytes, "a partial download counts"); assertEquals(1_000, npu.missingBytes)
        assertEquals(mapOf("runtime/rt-0" to 50L, "vision-v0" to 2_000L),
            listed.values.filter { it.kind == MangaResourceKind.LEFTOVER }.associate { it.id to it.diskBytes })

        manga.removeResource(listed.getValue("vision-v0"))
        manga.removeResource(listed.getValue("runtime/rt-0"))
        manga.removeResource(font)
        manga.removeResource(npu)
        assertFalse(root.resolve("vision-v0").exists()); assertFalse(root.resolve("runtime/rt-0").exists())
        assertFalse(root.resolve("font-sc-v1").exists()); assertFalse(root.resolve("npu-v1").exists()); assertFalse(root.resolve("runtime/rt-1").exists())
        assertTrue(root.resolve("accelerators.txt").isFile, "settings are not resources")
        assertTrue(manga.resources().none { it.kind == MangaResourceKind.LEFTOVER })
        // only a name it listed is removed
        store.removeLeftover("../outside", emptySet())
        assertTrue(root.exists())
    }
}
