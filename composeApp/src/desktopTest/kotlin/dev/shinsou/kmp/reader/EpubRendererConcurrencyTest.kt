package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubRendererConcurrencyTest {
    @Test
    fun concurrentResolvesOfTheSameKeyHydrateExactlyOnce() {
        val body = HTML_BODY.encodeToByteArray()
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val request = request(body) {
            loadCount.incrementAndGet()
            loaderEntered.countDown()
            assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
            body.copyOf()
        }
        val resolver = EpubPublicationResourceResolver(request)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<EpubRenderResponse?> { resolver.resolve(request.documentUrl) }
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<EpubRenderResponse?> {
                secondStarted.countDown()
                resolver.resolve(request.documentUrl)
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            releaseLoader.countDown()

            val firstResponse = assertNotNull(first.get(5, TimeUnit.SECONDS))
            val secondResponse = assertNotNull(second.get(5, TimeUnit.SECONDS))
            assertContentEquals(firstResponse.bytes, secondResponse.bytes)
            assertEquals(1, loadCount.get())
            assertEquals(1, resolver.cachedResourceCount)
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeLinearizesAfterAnAdmittedLoadAndRejectsEveryLaterResolve() {
        val body = HTML_BODY.encodeToByteArray()
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val request = request(body) {
            loadCount.incrementAndGet()
            loaderEntered.countDown()
            assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
            body.copyOf()
        }
        val resolver = EpubPublicationResourceResolver(request)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val resolve = executor.submit<EpubRenderResponse?> { resolver.resolve(request.documentUrl) }
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS))
            val close = executor.submit<Unit> {
                closeStarted.countDown()
                resolver.close()
            }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            releaseLoader.countDown()

            assertNotNull(resolve.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertTrue(resolver.isClosed)
            assertEquals(1, loadCount.get())
            assertEquals(0, resolver.cachedResourceCount)
            assertNull(resolver.resolve(request.documentUrl))
            assertEquals(1, loadCount.get())
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    private fun request(body: ByteArray, loader: () -> ByteArray): EpubRenderRequest {
        val archive = blob(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            bytes = "archive".encodeToByteArray(),
            mediaType = "application/epub+zip",
        )
        val packageBlob = blob(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            bytes = "package".encodeToByteArray(),
            mediaType = "application/oebps-package+xml",
        )
        val documentBlob = blob(
            id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            bytes = body,
            mediaType = "application/xhtml+xml",
        )
        val packageResource = EpubResource(
            id = "package",
            href = "OPS/package.opf",
            resource = ResourceRef("package", packageBlob),
        )
        val documentResource = EpubResource(
            id = "chapter",
            href = "OPS/chapter.xhtml",
            resource = ResourceRef("chapter", documentBlob),
        )
        val representation = ContentRepresentation.EpubSpine(
            representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            packageGraph = EpubPackage(
                archive = archive,
                packageDocumentId = packageResource.id,
                resources = listOf(packageResource, documentResource),
            ),
            documents = listOf(
                EpubSpineDocument("spine", documentResource.href, documentResource.id),
            ),
        )
        val publication = PublicationKey("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val navigation = EpubSpineNavigation(
            scope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = publication,
                acquisitionId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                unitId = UnitKey(publication, "11111111-1111-4111-8111-111111111111"),
                contentRevision = 1,
            ),
            representation = representation,
        )
        val renderResource = EpubRenderResource(
            resourceId = documentResource.id,
            href = documentResource.href,
            mediaType = documentResource.mediaType,
            declaredByteSize = body.size.toLong(),
            scriptedContent = false,
            readGate = EpubResourceReadGate.Direct,
            bodyLoader = { _, _ -> loader() },
        )
        return EpubRenderRequest(
            navigation = navigation,
            documentIndex = 0,
            initialLocator = navigation.locatorAt(0),
            publisherResources = listOf(renderResource),
        )
    }

    private fun blob(id: String, bytes: ByteArray, mediaType: String): BlobRef = BlobRef(
        blobId = id,
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = Sha256.hex(bytes),
        byteSize = bytes.size.toLong(),
        mediaType = mediaType,
    )

    private companion object {
        const val HTML_BODY: String = "<html><head><title>Concurrent</title></head><body>Body</body></html>"
    }
}
