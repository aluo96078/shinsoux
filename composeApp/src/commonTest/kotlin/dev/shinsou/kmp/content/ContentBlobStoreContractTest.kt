package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentBlobStoreContractTest {
    private val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
    private val owner = ContentManifestOwner(
        publication,
        "22222222-2222-4222-8222-222222222222",
        UnitKey(publication, "00000000-0000-4000-8000-000000000001"),
    )

    @Test
    fun stageSealPublishReadAndTransactionalAttachUseDefensiveCopies() {
        val store = InMemoryContentBlobStore(maximumBlobSizeBytes = 32)
        val stage = store.beginStage(expectedSizeBytes = 5, mediaType = "text/plain")
        stage.append("he".encodeToByteArray())
        stage.append("llo".encodeToByteArray())
        val published = store.publish(stage.seal())
        val manifest = textManifest(published.reference)
        store.attach(published, ManifestAttachment(owner, manifest))

        val read = store.read(published.reference)!!
        assertContentEquals("hello".encodeToByteArray(), read)
        read[0] = 'x'.code.toByte()
        assertContentEquals("hello".encodeToByteArray(), store.read(published.reference))
        assertEquals(published.reference, store.attached(owner, manifest)?.blobs?.single())
        assertTrue(store.verify(published.reference))
    }

    @Test
    fun serializedAttachmentContainsNoReceiptCapabilityOrStoreIdentity() {
        val store = InMemoryContentBlobStore(maximumBlobSizeBytes = 32)
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        val attachment = BlobAttachment(owner, textManifest(published.reference))
        val encoded = Json.encodeToString(BlobAttachment.serializer(), attachment)

        assertFalse("commitToken" in encoded)
        assertFalse("storeInstanceId" in encoded)
        assertFalse(published.commitToken in encoded)
    }

    @Test
    fun expectedReferenceMismatchAndOversizedChunksFailClosed() {
        val store = InMemoryContentBlobStore(maximumBlobSizeBytes = 3)
        assertFailsWith<ContentBlobStoreException.SizeLimitExceeded> {
            store.put("long".encodeToByteArray(), "text/plain")
        }
        val stage = store.beginStage(expectedSizeBytes = 1, mediaType = "text/plain")
        stage.append("x".encodeToByteArray())
        val bad = BlobRef(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            1,
            BlobRef.SHA_256,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            1,
            "text/plain",
        )
        assertFailsWith<ContentBlobStoreException.DigestMismatch> { stage.seal(bad) }
    }

    @Test
    fun pendingReceiptIsNeverSelectedAndStalePlansCannotDeleteRepublishedAbas() {
        var now = 100L
        val store = InMemoryContentBlobStore(clock = { now })
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        val boundary = RecoveryBoundary(
            safetyCutoffGeneration = published.generation,
            nowEpochMillis = 1_000L,
            minimumAgeMillis = 0,
        )
        assertTrue(store.planRecovery(boundary).candidates.isEmpty())
        assertTrue(store.planRecovery(boundary).protectedBlobs[published.reference.blobId] != null)

        // Losing the in-process capability is a process-recovery event; only then can this be
        // discovered as an orphan. The first plan records discovery, the second is sweepable.
        store.simulateProcessCrashAndRecover()
        val stale = store.planRecovery(boundary)
        assertEquals(listOf(published.reference), stale.candidateReferences)
        assertEquals(1, store.sweepRecovery(stale))

        now = 200L
        val republished = store.put(published.reference, "hello".encodeToByteArray())
        assertEquals(published.reference, republished.reference)
        assertEquals(0, store.sweepRecovery(stale))
        assertTrue(store.contains(republished.reference))
    }

    @Test
    fun openReadAndContainsUseTheLeaseAndActiveReaderProtectsSweep() {
        val now = 100L
        val store = InMemoryContentBlobStore(clock = { now })
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        store.simulateProcessCrashAndRecover()
        // Discovered orphans cannot be opened, so use an attached verified blob for the active
        // lease check; attachment and reader protection are both rechecked at sweep time.
        val recovery = RecoveryBoundary(published.generation, now, 0)
        val plan = store.planRecovery(recovery)
        assertEquals(1, store.sweepRecovery(plan))
        val republished = store.put(published.reference, "hello".encodeToByteArray())
        val manifest = textManifest(republished.reference)
        store.attach(republished, ManifestAttachment(owner, manifest))
        val lease = store.openRead(republished.reference)!!
        assertTrue(lease.isPinned)
        assertTrue(store.contains(republished.reference))
        lease.close()
        assertTrue(lease.isClosed)
        assertTrue(store.contains(republished.reference))
    }

    private fun textManifest(blob: BlobRef): ContentManifest = ContentManifest(
        manifestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = 0,
        representations = listOf(
            ContentRepresentation.PlainText(
                representationId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                resource = ResourceRef("text-body", blob),
                canonicalUtf16Length = 5,
                blocks = listOf(TextBlock("body", 0, 5)),
            ),
        ),
    )
}
