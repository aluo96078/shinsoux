package dev.shinsou.kmp.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Color

class DesktopAvifDecoderTest {
    @Test
    fun factoryRecognizesAvifMimeTypeAndHeaderOnlyOnMacOs() {
        val encoded = avifFixture()
        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        try {
            val byMimeType = sourceResult(encoded, mimeType = "image/avif")
            assertNotNull(
                DesktopAvifDecoder.Factory(decoderProvider = { MacOsAvifImageDecoder { null } })
                    .create(byMimeType, Options(PlatformContext.INSTANCE), imageLoader),
            )

            val bySignature = sourceResult(encoded, mimeType = "application/octet-stream")
            assertNotNull(
                DesktopAvifDecoder.Factory(decoderProvider = { MacOsAvifImageDecoder { null } })
                    .create(bySignature, Options(PlatformContext.INSTANCE), imageLoader),
            )

            val notAvif = sourceResult("not an image".encodeToByteArray(), mimeType = null)
            assertNull(
                DesktopAvifDecoder.Factory(decoderProvider = { MacOsAvifImageDecoder { null } })
                    .create(notAvif, Options(PlatformContext.INSTANCE), imageLoader),
            )

            val windowsResult = sourceResult(encoded, mimeType = "image/avif")
            assertNull(
                DesktopAvifDecoder.Factory(
                    platformName = "Windows 11",
                    decoderProvider = { error("A non-macOS factory must not initialize ImageIO.") },
                ).create(windowsResult, Options(PlatformContext.INSTANCE), imageLoader),
            )
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun macOsImageIoDecodesLicensedAvifFixtureIntoSkiaBitmap() = runTest {
        val encoded = avifFixture()
        assertEquals(FIXTURE_SHA_256, encoded.sha256())

        val native = assertNotNull(JnaMacOsAvifImageDecoder.instance.decode(encoded))
        assertEquals(800, native.width)
        assertEquals(800, native.height)
        assertEquals(native.width * native.height * 4, native.skiaN32PremultipliedPixels.size)
        assertTrue(native.skiaN32PremultipliedPixels.any { it.toInt() != 0 })

        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        try {
            val result = sourceResult(encoded, mimeType = "image/avif")
            val decoder = assertNotNull(
                DesktopAvifDecoder.Factory().create(
                    result,
                    Options(PlatformContext.INSTANCE),
                    imageLoader,
                ),
            )
            val decoded = assertNotNull(decoder.decode())
            assertEquals(800, decoded.image.width)
            assertEquals(800, decoded.image.height)
            assertTrue(!decoded.isSampled)
            val bitmap = (decoded.image as coil3.BitmapImage).bitmap
            val top = bitmap.getColor(400, 50)
            val bottom = bitmap.getColor(400, 750)
            // Correct ICC conversion places the red/magenta sector at 12 o'clock and the
            // green/cyan sector at 6 o'clock. These checks also catch vertical inversion.
            assertTrue(
                Color.getR(top) > Color.getB(top),
                "top=${top.channels()}; bottom=${bottom.channels()}",
            )
            assertTrue(Color.getB(top) > Color.getG(top), "top=${top.channels()}")
            assertTrue(Color.getG(bottom) > Color.getB(bottom), "bottom=${bottom.channels()}")
            assertTrue(Color.getB(bottom) > Color.getR(bottom), "bottom=${bottom.channels()}")
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun registeredCoilLoaderDecodesAvifFileEndToEnd() = runTest {
        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(DesktopAvifDecoder.Factory()) }
            .build()
        try {
            val fixture = assertNotNull(
                javaClass.getResource("/fixtures/red-clock-face.avif"),
                "Missing AVIF fixture.",
            )
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(java.io.File(fixture.toURI()))
                .build()
            val result = imageLoader.execute(request)
            assertTrue(result is SuccessResult, result.toString())
            assertEquals(800, result.image.width)
            assertEquals(800, result.image.height)
        } finally {
            imageLoader.shutdown()
        }
    }

    private fun sourceResult(bytes: ByteArray, mimeType: String?): SourceFetchResult = SourceFetchResult(
        source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
        mimeType = mimeType,
        dataSource = DataSource.MEMORY,
    )

    private fun avifFixture(): ByteArray = assertNotNull(
        javaClass.getResourceAsStream("/fixtures/red-clock-face.avif"),
        "Missing AVIF fixture.",
    ).use { it.readBytes() }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun Int.channels(): String =
        "${Color.getR(this)},${Color.getG(this)},${Color.getB(this)},${Color.getA(this)}"

    private companion object {
        // red-at-12-oclock-with-color-profile-lossy.avif from link-u/avif-sample-images,
        // dual licensed LGPL-2.1/BSD-2-Clause. It is tiny and contains no comic/user content.
        const val FIXTURE_SHA_256 = "79483242f2dca12c4ec18bd33ff8099216b3094fb55a26a909f046b2f9b4ce58"
    }
}
