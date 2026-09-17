package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64

class ReviewedBrowserImagePolicyTest {
    private fun resolution(vararg addresses: String) = PluginHostResolution("i.motiezw.com", addresses.toList())
    private fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), bytes: Int = 4 * 1024 * 1024) =
        PluginHttpRequest(method, url, headers = headers, maxResponseBytes = bytes)

    @Test fun acceptsCanonicalReviewedImageAndRejectsUntrustedVariants() {
        validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif"), resolution("93.184.216.34"))
        listOf(
            "https://evil.motiezw.com/1/1615/133085/2911882.avif",
            "http://i.motiezw.com/1/1615/133085/2911882.avif",
            "https://i.motiezw.com/1/1615/133085/2911882.avif?q=1",
            "https://i.motiezw.com/1/1615/133085/2911882.avif#x",
            "https://user:i@i.motiezw.com/1/1615/133085/2911882.avif",
            "https://i.motiezw.com/a/b/c/d.avif",
        ).forEach { url -> assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request(url), resolution("93.184.216.34")) } }
    }

    @Test fun rejectsNonGetHeadersBodiesOversizeAndUnsafeResolution() {
        assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif", "POST"), resolution("93.184.216.34")) }
        assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif", headers = mapOf("Referer" to "x")), resolution("93.184.216.34")) }
        assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif", bytes = 4 * 1024 * 1024 + 1), resolution("93.184.216.34")) }
        assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif"), resolution("10.0.0.1")) }
        assertFailsWith<IllegalArgumentException> {
            validateReviewedBrowserImageRequest(
                request("https://i.motiezw.com/1/1615/133085/2911882.avif"),
                resolution("93.184.216.34", "10.0.0.1"),
            )
        }
        assertFailsWith<IllegalArgumentException> { validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif"), PluginHostResolution("other.example", listOf("93.184.216.34"))) }
    }

    @Test fun decoderIsBoundedAndAcceptsOnlyRasterResults() {
        assertNull(decodeReviewedBrowserImageResult(null, 100))
        assertNull(decodeReviewedBrowserImageResult("null", 100))
        val encoded = "aGVsbG8="
        val result = decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\",\"bodyBase64\":\"$encoded\"}", 100)
        assertNotNull(result)
        assertEquals(200, result.status)
        assertTrue(result.body.contentEquals("hello".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"error\":\"failed\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":403,\"contentType\":\"image/png\",\"bodyBase64\":\"$encoded\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":403,\"contentType\":\"image/png\",\"bodyBase64\":\"\"}", 100) }
        val oversized = Base64.encode(ByteArray(101) { 1 })
        assertFailsWith<IllegalArgumentException> {
            decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\",\"bodyBase64\":\"$oversized\"}", 100)
        }
        assertFailsWith<IllegalArgumentException> {
            decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\",\"bodyBase64\":\"not-base64!\"}", 100)
        }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"text/html\",\"bodyBase64\":\"$encoded\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\",\"bodyBase64\":\"$encoded\",\"extra\":1}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":\"200\",\"contentType\":\"image/png\",\"bodyBase64\":\"$encoded\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":123,\"bodyBase64\":\"$encoded\"}", 100) }
        assertFailsWith<IllegalArgumentException> { decodeReviewedBrowserImageResult("{\"status\":200,\"contentType\":\"image/png\",\"bodyBase64\":123}", 100) }
        val detail = assertFailsWith<IllegalArgumentException> {
            decodeReviewedBrowserImageResult("{\"error\":\"secret-token\",\"stage\":\"secret-stage\"}", 100)
        }
        assertTrue("secret-token" !in detail.message.orEmpty())
        assertTrue("secret-stage" !in detail.message.orEmpty())
    }

    @Test fun scriptsContainBrowserFetchGuardsAndCleanup() {
        val script = reviewedBrowserImageStartScript(request("https://i.motiezw.com/1/1615/133085/2911882.avif", bytes = 100))
        assertTrue("credentials: 'omit'" in script)
        assertTrue("redirect: 'error'" in script)
        assertTrue("referrerPolicy: 'no-referrer'" in script)
        assertTrue("AbortController" in script)
        assertTrue("total > 100" in script)
        assertTrue("JSON.stringify" in reviewedBrowserImagePollScript() || reviewedBrowserImagePollScript().contains("__shinsouImage"))
        assertTrue("abort" in reviewedBrowserImageCleanupScript())
        assertTrue(reviewedBrowserImageCleanupScript().startsWith("(() => {"))
        assertTrue(reviewedBrowserImageCleanupScript().endsWith("})()"))
        assertTrue("8192" in script)
        assertTrue("bitmap.width * bitmap.height > $REVIEWED_BROWSER_IMAGE_MAX_PIXELS" in script)
        assertTrue("canvas.width = 0" in script)
        assertTrue("bitmap.close()" in script)
        assertTrue("png.size" in script)
    }

    @Test fun rejectsMoreThanThirtyTwoResolvedDnsAnswers() {
        val addresses = List(33) { index -> "93.184.216.${index + 1}" }
        assertFailsWith<IllegalArgumentException> {
            validateReviewedBrowserImageRequest(request("https://i.motiezw.com/1/1615/133085/2911882.avif"), resolution(*addresses.toTypedArray()))
        }
    }
}
