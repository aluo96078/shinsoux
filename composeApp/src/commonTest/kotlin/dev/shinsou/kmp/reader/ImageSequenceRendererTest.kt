package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ImagePage
import dev.shinsou.kmp.content.ImageTransform
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageSequenceRendererTest {
    @Test
    fun openingASequenceReadsOnlyTheRequestedPageAndPreservesItsTransform() {
        val backing = InMemoryContentBlobStore()
        val first = backing.put("first-page".encodeToByteArray(), "image/png").reference
        val secondBytes = "second-page".encodeToByteArray()
        val second = backing.put(secondBytes, "image/png").reference
        val observing = ObservingBlobStore(backing)
        val navigation = navigation(
            listOf(
                ImagePage(ResourceRef("page-first", first)),
                ImagePage(
                    resource = ResourceRef("page-second", second),
                    transform = ImageTransform(
                        schemaVersion = ImageTransform.CURRENT_SCHEMA_VERSION,
                        transformId = "reverse_vertical_segments",
                        parameters = mapOf("segmentCount" to "4"),
                    ),
                ),
            ),
        )

        val rendered = ImageRenderPageFactory(observing).load(navigation, 1)

        assertEquals(listOf(second.blobId), observing.openedBlobIds)
        assertEquals("page-second", rendered.resourceId)
        assertContentEquals(secondBytes, rendered.bytes)
        assertEquals(ReaderImageTransform.ReverseVerticalSegments(4), rendered.readerTransform)

        val callerCopy = rendered.bytes
        callerCopy[0] = 0
        assertContentEquals(secondBytes, rendered.bytes)
    }

    @Test
    fun digestMismatchFailsClosedBeforeReturningAnImage() {
        val backing = InMemoryContentBlobStore()
        val expected = backing.put("trusted-body".encodeToByteArray(), "image/jpeg").reference
        val tampering = ObservingBlobStore(
            delegate = backing,
            replacementBytes = mapOf(expected.blobId to "altered-body".encodeToByteArray()),
        )
        val navigation = navigation(
            listOf(ImagePage(ResourceRef("page-tampered", expected))),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ImageRenderPageFactory(tampering).load(navigation, 0)
        }

        assertEquals(listOf(expected.blobId), tampering.openedBlobIds)
        assertEquals(
            "Image reader resource failed integrity verification: page-tampered",
            failure.message,
        )
    }

    @Test
    fun pageLocatorReportsTheStableResourceAndIntraPageOffset() {
        val backing = InMemoryContentBlobStore()
        val first = backing.put(byteArrayOf(1), "image/png").reference
        val second = backing.put(byteArrayOf(2), "image/png").reference
        val navigation = navigation(
            listOf(
                ImagePage(ResourceRef("stable-a", first)),
                ImagePage(ResourceRef("stable-b", second)),
            ),
        )

        val locator = navigation.locator(index = 1, normalizedOffsetFraction = 0.625)

        assertEquals("stable-b", locator.pageResourceId)
        assertEquals(1, locator.pageIndexHint)
        assertEquals(0.625, locator.normalizedOffsetFraction)
        assertEquals(1, navigation.indexOf(locator.copy(pageIndexHint = 99)))
    }

    private fun navigation(pages: List<ImagePage>): ImageSequenceNavigation =
        ImageSequenceNavigation(
            scope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = PublicationKey("11111111-1111-4111-8111-111111111111"),
                acquisitionId = "22222222-2222-4222-8222-222222222222",
                unitId = UnitKey(
                    PublicationKey("11111111-1111-4111-8111-111111111111"),
                    "33333333-3333-4333-8333-333333333333",
                ),
                contentRevision = 1,
            ),
            representation = ContentRepresentation.ImageSequence(
                representationId = "44444444-4444-4444-8444-444444444444",
                pages = pages,
            ),
        )

    private class ObservingBlobStore(
        private val delegate: ContentBlobStore,
        private val replacementBytes: Map<String, ByteArray> = emptyMap(),
    ) : ContentBlobStore by delegate {
        val openedBlobIds = mutableListOf<String>()

        override fun read(reference: BlobRef): ByteArray? {
            openedBlobIds += reference.blobId
            val replacement = replacementBytes[reference.blobId]
            return replacement?.copyOf() ?: delegate.read(reference)
        }
    }
}
