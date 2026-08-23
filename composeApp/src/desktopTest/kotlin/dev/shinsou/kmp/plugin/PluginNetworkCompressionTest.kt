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
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginNetworkCompressionTest {
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
}
