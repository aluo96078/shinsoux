package dev.shinsou.kmp.translation

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineModelStoreTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(name: String, body: ByteArray) = OfflineModelFile(name, "models/$name", body.size.toLong(), sha(body))

    private val bodies = mapOf(
        "model.bin" to ByteArray(200_000) { (it % 251).toByte() },
        "vocab.spm" to "vocab".toByteArray(),
        "lex.bin" to "lex".toByteArray(),
    )
    private val model = OfflineModel(
        from = "ja", to = "en", version = "t",
        model = file("model.bin", bodies.getValue("model.bin")),
        sourceVocab = file("vocab.spm", bodies.getValue("vocab.spm")),
        targetVocab = file("vocab.spm", bodies.getValue("vocab.spm")),
        shortlist = file("lex.bin", bodies.getValue("lex.bin")),
    )

    private fun files(root: File, served: Map<String, ByteArray>, requests: MutableList<String> = ArrayList()) = OfflineModelStore(
        root.absolutePath,
        HttpClient(MockEngine { request ->
            requests += request.url.toString()
            served[request.url.encodedPath.substringAfterLast('/')]?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
        }),
        baseUrl = "https://models.test/",
    )

    @Test
    fun aModelIsDownloadedOnceVerifiedAndThenReportedAsInstalled() = runBlocking<Unit> {
        val root = Files.createTempDirectory("models").toFile()
        val requests = ArrayList<String>()
        val store = files(root, bodies, requests)
        assertNull(store.installed(model))
        val progress = ArrayList<Pair<Long, Long>>()

        store.download(model) { received, total -> progress += received to total }

        val paths = assertNotNull(store.installed(model))
        assertEquals(paths.sourceVocab, paths.targetVocab)
        assertTrue(File(paths.model).readBytes().contentEquals(bodies.getValue("model.bin")))
        assertEquals(3, requests.size, "the shared vocabulary is fetched once")
        assertEquals(model.downloadBytes to model.downloadBytes, progress.last())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })

        requests.clear()
        store.download(model)
        assertTrue(requests.isEmpty(), "installed files are not fetched again")

        store.remove(model)
        assertNull(store.installed(model))
    }

    @Test
    fun aFileThatDoesNotMatchItsPinnedChecksumIsNeverInstalled() = runBlocking<Unit> {
        val root = Files.createTempDirectory("models").toFile()
        val tampered = bodies + ("model.bin" to ByteArray(200_000) { 7 })
        val store = files(root, tampered)

        assertFailsWith<TranslationException> { store.download(model) }

        assertNull(store.installed(model))
        assertTrue(root.walkTopDown().none { it.name == "model.bin" || it.name.endsWith(".part") })

        // Too long: refused before the whole body is written.
        val oversized = files(root, bodies + ("model.bin" to ByteArray(400_000)))
        assertFailsWith<TranslationException> { oversized.download(model) }
        assertNull(oversized.installed(model))
        // Unreachable.
        assertFailsWith<TranslationException> { files(root, emptyMap()).download(model) }
    }
}
