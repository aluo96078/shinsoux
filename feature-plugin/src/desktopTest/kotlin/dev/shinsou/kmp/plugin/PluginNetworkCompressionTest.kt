package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

class PluginNetworkCompressionTest {
    @Test
    fun pinnedTransportPreservesEmptyPostAsAnExplicitZeroByteBody() {
        val postBody = assertNotNull(
            pinnedOkHttpRequestBody(PluginHttpRequest("POST", "https://relay.example/")),
        )

        assertEquals(0L, postBody.contentLength())
        assertNull(pinnedOkHttpRequestBody(PluginHttpRequest("GET", "https://relay.example/")))
    }

    @Test
    fun pinnedTransportDecodesExplicitDeflateAndRemovesCompressedFraming() {
        val payload = "deflate relay payload"
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { deflate -> deflate.write(payload.encodeToByteArray()) }
        }.toByteArray()

        val decoded = decodePinnedPluginResponseBody(
            contentEncoding = "deflate",
            declaredLength = compressed.size.toLong(),
            input = ByteArrayInputStream(compressed),
        )

        assertEquals(payload, decoded.decodeToString())
    }

    @Test
    fun pinnedTransportDecodesExplicitGzipAndRemovesCompressedFraming() {
        val payload = "relay payload"
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(payload.encodeToByteArray()) }
        }.toByteArray()

        val decoded = decodePinnedPluginResponseBody(
            contentEncoding = "gzip",
            declaredLength = compressed.size.toLong(),
            input = ByteArrayInputStream(compressed),
        )

        assertEquals(payload, decoded.decodeToString())
    }

    @Test
    fun gzipRelayResponsesAreDecodedBeforePluginParsing() = kotlinx.coroutines.test.runTest {
        val payload = "<response><item aid=\"2756\" /></response>"
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(payload.encodeToByteArray()) }
        }.toByteArray()
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(compressed),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentEncoding, "gzip"),
            )
        }) {
            install(ContentEncoding) { gzip() }
        }
        try {
            val response = KtorPluginHttpTransport(client).execute(
                PluginHttpRequest("POST", "https://wenku8-relay.mewx.org/"),
            )
            assertEquals(payload, response.bodyText())
        } finally {
            client.close()
        }
    }

    @Test
    fun pinnedGzipStopsAtThePerRequestDecodedByteBudget() {
        val payload = Random(7).nextBytes(1_024 * 1_024)
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(payload) }
        }.toByteArray()
        val tracked = CountingInputStream(ByteArrayInputStream(compressed))

        assertFailsWith<IllegalArgumentException> {
            decodePinnedPluginResponseBody(
                contentEncoding = "gzip",
                // Chunked/unknown compressed framing forces the decoded streaming path to prove
                // it enforces the request limit independently of Content-Length.
                declaredLength = null,
                input = tracked,
                maxResponseBytes = 128,
            )
        }

        // The incompressible body leaves roughly one MiB of compressed input available. Reading
        // only its first small gzip buffer proves the decoder rejected at the 128-byte decoded
        // budget instead of materializing the complete wire response first.
        assertTrue(compressed.size > 1_000_000)
        assertTrue(tracked.bytesRead < compressed.size)
    }

    @Test
    fun ktorTransportAppliesSmallDecodedBudgetToCompressedStream() = kotlinx.coroutines.test.runTest {
        val payload = ByteArray(64 * 1_024) { 'b'.code.toByte() }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(payload) }
        }.toByteArray()
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(compressed),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentEncoding, "gzip"),
            )
        }) {
            install(ContentEncoding) { gzip() }
        }
        try {
            assertFailsWith<IllegalArgumentException> {
                KtorPluginHttpTransport(client).execute(
                    PluginHttpRequest(
                        "GET",
                        "https://compressed.example/",
                        maxResponseBytes = 128,
                    ),
                )
            }
        } finally {
            client.close()
        }
    }
}

private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var bytesRead: Int = 0
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) bytesRead += it }

    override fun close() = delegate.close()
}
