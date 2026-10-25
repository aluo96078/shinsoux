package dev.shinsou.kmp.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Opt-in live check for the desktop reviewed browser-image transport. */
class LiveReviewedBrowserImageDesktopTest {
    @Test
    fun fetchesExactReviewedAvifAndDoesNotFallBackToDirectDns() {
        if (System.getenv("SHINSOU_LIVE_BROWSER_IMAGE") != "1") return
        runBlocking {
            val url = "https://i.motiezw.com/1/1615/133085/2911882.avif"
            val request = PluginHttpRequest("GET", url, maxResponseBytes = REVIEWED_BROWSER_IMAGE_MAX_BYTES)
            val resolver = createPlatformPluginHostResolver()
            val addresses = resolver.resolve("i.motiezw.com")
            assertTrue(addresses.isNotEmpty())
            val resolution = PluginHostResolution("i.motiezw.com", addresses)
            validateReviewedBrowserImageRequest(request, resolution)
            val transport = requireNotNull(createPlatformReviewedImageTransport())

            val response = withTimeout(30_000) { transport.executeResolved(request, resolution) }
            assertEquals(200, response.status)
            assertTrue(response.body.isNotEmpty())
            assertTrue(response.body.size <= REVIEWED_BROWSER_IMAGE_MAX_BYTES)
            val type = response.normalizedPluginMediaType()
            assertEquals("image/png", type)
            val decoded = Image.makeFromEncoded(response.body)
            try {
                assertEquals(1350, decoded.width)
                assertEquals(1920, decoded.height)
            } finally {
                decoded.close()
            }
            println("LIVE REVIEWED IMAGE status=${response.status} type=$type bytes=${response.body.size}")

            // A public address belonging to Google DNS is not the reviewed image host. It
            // should fail at the pinned endpoint; a direct fallback would reach the real host.
            val unusable = PluginHostResolution("i.motiezw.com", listOf("8.8.8.8"))
            validateReviewedBrowserImageRequest(request, unusable)
            assertFailsWith<Throwable> {
                withTimeout(5_000) { transport.executeResolved(request, unusable) }
            }
        }
    }
}
