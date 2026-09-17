package dev.shinsou.kmp.network

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CoilImageLoaderTest {
    @Test
    fun configuredLoaderUsesInjectedKtorClientForRemoteImage() = runTest {
        val httpClient = HttpClient(MockEngine {
            respond(PNG_1X1, HttpStatusCode.OK, headers = headersOfContentType())
        })
        val imageLoader = createConfiguredImageLoader(PlatformContext.INSTANCE, httpClient)
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data("https://images.example/cover.webp")
                    .build(),
            )
            assertTrue(result is SuccessResult, result.toString())
            assertEquals(1, result.image.width)
            assertEquals(1, result.image.height)
        } finally {
            imageLoader.shutdown()
            httpClient.close()
        }
    }

    private companion object {
        fun headersOfContentType() = io.ktor.http.headersOf(
            io.ktor.http.HttpHeaders.ContentType,
            ContentType.Image.PNG.toString(),
        )

        // Canonical transparent 1x1 PNG; image extension is deliberately unrelated to the URL.
        val PNG_1X1 = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9c.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )
    }
}
