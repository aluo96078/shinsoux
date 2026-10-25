@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.shinsou.kmp.plugin

import kotlinx.cinterop.toKString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runMode
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.posix.getenv
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in live check for the exact reviewed BiliManga browser-image path. */
class LiveReviewedBrowserImageTest {
    @Test
    fun fetchesExactReviewedAvifTwiceThroughPinnedBrowserTransport() {
        if (getenv("SHINSOU_LIVE_BROWSER_IMAGE")?.toKString() != "1") return
        val completed = CompletableDeferred<Result<Unit>>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            completed.complete(runCatching {
            val nativeResolver = createPlatformPluginHostResolver()
            val nativeUrl = "https://www.bilimanga.net/"
            val nativeRequest = PluginHttpRequest("GET", nativeUrl, maxResponseBytes = 512 * 1024)
            val nativeAddresses = nativeResolver.resolve("www.bilimanga.net")
            assertTrue(nativeAddresses.isNotEmpty())
            assertTrue(nativeAddresses.all { isPluginIpLiteral(it) && !isBlockedPluginAddress(it) })
            val nativeResolution = PluginHostResolution("www.bilimanga.net", nativeAddresses)
            val nativeTransport = requireNotNull(createPlatformPinnedPluginHttpTransport())
            val nativeResponse = withTimeout(30_000) {
                nativeTransport.executeResolved(nativeRequest, nativeResolution)
            }
            assertEquals(200, nativeResponse.status)
            assertEquals("text/html", nativeResponse.normalizedPluginMediaType())
            assertTrue(nativeResponse.body.isNotEmpty())
            val url = "https://i.motiezw.com/1/1615/133085/2911882.avif"
            val request = PluginHttpRequest("GET", url, maxResponseBytes = REVIEWED_BROWSER_IMAGE_MAX_BYTES)
            val addresses = createPlatformPluginHostResolver().resolve("i.motiezw.com")
            assertTrue(addresses.isNotEmpty())
            val resolution = PluginHostResolution("i.motiezw.com", addresses)
            validateReviewedBrowserImageRequest(request, resolution)
            val transport = requireNotNull(createPlatformReviewedImageTransport())
            repeat(2) {
                val response = withTimeout(30_000) { transport.executeResolved(request, resolution) }
                assertEquals(200, response.status)
                val type = response.normalizedPluginMediaType()
                assertEquals("image/png", type)
                assertTrue(response.body.isNotEmpty())
                assertTrue(response.body.size <= REVIEWED_BROWSER_IMAGE_MAX_BYTES)
                assertTrue(response.imageBodyForDecoderOrNull() != null)
                val image = response.body.usePinned { pinned ->
                    UIImage.imageWithData(NSData.create(bytes = pinned.addressOf(0), length = response.body.size.toULong()))
                }
                assertTrue(image != null)
                val cgImage = requireNotNull(image!!.CGImage)
                assertEquals(1350, CGImageGetWidth(cgImage).toInt())
                assertEquals(1920, CGImageGetHeight(cgImage).toInt())
                println("LIVE REVIEWED IMAGE status=${response.status} type=$type bytes=${response.body.size}")
            }
            val unusable = PluginHostResolution("i.motiezw.com", listOf("8.8.8.8"))
            validateReviewedBrowserImageRequest(request, unusable)
            kotlin.test.assertFailsWith<Throwable> {
                withTimeout(5_000) { transport.executeResolved(request, unusable) }
            }
            Unit
            })
        }
        val deadline = NSDate().timeIntervalSince1970 + 60.0
        while (!completed.isCompleted && NSDate().timeIntervalSince1970 < deadline) {
            NSRunLoop.mainRunLoop.runMode(
                NSDefaultRunLoopMode,
                beforeDate = NSDate().dateByAddingTimeInterval(0.05),
            )
        }
        try {
            check(completed.isCompleted) { "Reviewed browser image live test timed out" }
            completed.getCompleted().getOrThrow()
        } finally {
            if (!completed.isCompleted) job.cancel()
        }
    }
}
