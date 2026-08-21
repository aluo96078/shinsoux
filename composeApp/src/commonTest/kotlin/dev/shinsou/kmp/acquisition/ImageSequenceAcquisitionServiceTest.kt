package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

class ImageSequenceAcquisitionServiceTest {
    @Test
    fun publishesExactImageSequenceGraphWithStableResources() {
        var sequence = 0
        val store = InMemoryContentBlobStore(
            blobIdFactory = { "00000000-0000-4000-8000-${(++sequence).toString().padStart(12, '0')}" },
            commitTokenFactory = { "receipt-${++sequence}" },
            configuredStoreInstanceId = "image-acquisition-test",
        )
        val result = ImageSequenceAcquisitionService(store).acquire(
            ImageSequenceAcquisitionRequest(
                target = target(),
                pages = listOf(
                    LocalImagePageSource("001.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
                    LocalImagePageSource("002.png", "image/png", byteArrayOf(4, 5)),
                ),
                packageKind = LocalPackageKind.CBZ,
            ),
        )

        val representation = result.representation as ContentRepresentation.ImageSequence
        assertEquals(2, representation.pages.size)
        assertEquals(2, representation.pages.map { it.resourceId }.distinct().size)
        assertEquals(AcquisitionOrigin.LocalPackage(LocalPackageKind.CBZ), result.acquisition.origin)
        assertEquals(5, result.manifest.declaredSizeBytes)
        assertEquals(result.manifest.referencedBlobs, result.publishedBlobs.map { it.reference })
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(representation.pages[0].resource.blob))
        assertContentEquals(byteArrayOf(4, 5), store.read(representation.pages[1].resource.blob))
    }

    @Test
    fun validatesTheWholeSequenceBeforePublishingAnyBody() {
        val store = InMemoryContentBlobStore(configuredStoreInstanceId = "image-limit-test")
        val service = ImageSequenceAcquisitionService(
            blobStore = store,
            policy = ImageSequenceAcquisitionPolicy(
                maximumPages = 2,
                maximumPageBytes = 3,
                maximumTotalBytes = 4,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            service.acquire(
                ImageSequenceAcquisitionRequest(
                    target = target(),
                    pages = listOf(
                        LocalImagePageSource("one.png", "image/png", byteArrayOf(1, 2, 3)),
                        LocalImagePageSource("two.png", "image/png", byteArrayOf(4, 5)),
                    ),
                    packageKind = LocalPackageKind.IMAGES,
                ),
            )
        }
        assertEquals(0, store.count)
    }

    @Test
    fun cancellationAbortsTheActivePageStageAtTheNext64KiBBoundary() {
        val store = InMemoryContentBlobStore(configuredStoreInstanceId = "image-cancellation-test")
        var checkpoints = 0
        var publishing = false
        val service = ImageSequenceAcquisitionService(
            blobStore = store,
            authorizeOfflineStore = { publishing = true },
            cancellationCheckpoint = {
                if (publishing) {
                    checkpoints++
                    if (checkpoints == 2) throw CancellationException("image import left")
                }
            },
        )

        assertFailsWith<CancellationException> {
            service.acquire(
                ImageSequenceAcquisitionRequest(
                    target = target(),
                    pages = listOf(
                        LocalImagePageSource(
                            "large.png",
                            "image/png",
                            ByteArray(128 * 1024),
                        ),
                    ),
                    packageKind = LocalPackageKind.IMAGES,
                ),
            )
        }

        assertEquals(2, checkpoints)
        assertEquals(0, store.count)
        assertEquals(0, store.pendingReceiptCount)
    }

    private fun target(): LocalAcquisitionTarget = LocalAcquisitionTarget(
        publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
        publicationTitle = "Images",
        stableImportId = "images:fixture",
        acquiredAtEpochMillis = 100,
    )
}
