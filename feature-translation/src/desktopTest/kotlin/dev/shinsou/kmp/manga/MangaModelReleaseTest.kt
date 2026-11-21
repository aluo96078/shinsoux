package dev.shinsou.kmp.manga

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The published model packs (GitHub Release `manga-models-v1`) download and match the hashes
 * pinned in [MangaModelCatalog]. Opt-in, because it downloads about 124 MB: SHINSOU_MANGA_RELEASE=1.
 */
class MangaModelReleaseTest {
    @Test
    fun thePublishedPacksDownloadAndMatchTheirPins() = runBlocking {
        if (System.getenv("SHINSOU_MANGA_RELEASE") != "1") return@runBlocking
        val root = Files.createTempDirectory("manga-release").toFile()
        val http = HttpClient(OkHttp)
        try {
            val store = MangaModelStore(root.path, http)
            for (pack in listOf(MangaModelCatalog.VISION, MangaModelCatalog.INPAINT)) {
                val started = System.nanoTime()
                store.download(pack) { _, _ -> }
                println("MANGA release ${pack.id}: ${pack.downloadBytes} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
                assertNotNull(store.installed(pack), "${pack.id} installed")
                assertEquals(0, store.missingBytes(pack))
            }
            assertNotNull(store.paths()?.inpaint)
        } finally {
            http.close()
            root.deleteRecursively()
        }
    }
}
