package dev.shinsou.kmp.backup

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStage
import dev.shinsou.kmp.content.ContentAliasMutation
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.content.ContentPortableAuxiliaryState
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PortableContentBackupV2Test {
    @Test
    fun archiveRoundTripKeepsMetadataAndBodiesInSeparateChecksummedEntries() {
        val fixture = fixture()
        val auxiliary = ContentPortableAuxiliaryState(
            metadata = listOf(ContentMetadataMutation("migration.shuyue.category.fixture", "Novel")),
            aliases = listOf(ContentAliasMutation("shuyue-v1-book:fixture", PUBLICATION_ID)),
            migrations = listOf(
                ContentMigrationLedgerMutation(
                    namespace = "shuyue.backup.v1",
                    sourceDigestSha256 = "1".repeat(64),
                    resultFingerprintSha256 = "2".repeat(64),
                ),
            ),
        )
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
                auxiliary = auxiliary,
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = fixture.blobStore,
            operationGate = FixedGate(RightsDecision.ALLOW),
            createdAtEpochMillis = 10,
            appVersion = "test",
        )

        assertEquals(1, created.envelope.manifest.attachments.size)
        assertEquals(2, created.envelope.manifest.entries.size)
        assertTrue(created.archive.paths.contains("manifest.json"))
        assertFalse(created.archive.read("metadata/portable-state-v2.json")!!.decodeToString()
            .contains(fixture.text))

        val encoded = BackupV2BinaryCodec.encode(created.archive)
        val decoded = BackupV2BinaryCodec.decode(encoded)
        val inspected = PortableContentBackupV2Service.inspect(decoded)
        assertEquals(listOf(fixture.publication), inspected.portableState.publications)
        assertEquals(auxiliary, inspected.portableState.auxiliary)
        assertContentEquals(fixture.text.encodeToByteArray(), decoded.read("blobs/${fixture.blobId}.bin"))

        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024)
        val published = PortableContentBackupV2Service.publishBodies(decoded, destination)
        assertEquals(1, published.receipts.size)
        assertEquals(listOf(fixture.attachment), published.attachments)
        assertContentEquals(fixture.text.encodeToByteArray(), destination.read(published.receipts.single().reference))
    }

    @Test
    fun portableStateWithoutAuxiliaryFieldStillDecodesAsBackupV2() {
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val encodedWithoutDefaultField = json.encodeToString(
            BackupV2PortableState(legacySnapshot = AppSnapshot()),
        )
        assertFalse(encodedWithoutDefaultField.contains("auxiliary"))

        val decoded = json.decodeFromString<BackupV2PortableState>(encodedWithoutDefaultField)

        assertEquals(ContentPortableAuxiliaryState(), decoded.auxiliary)
    }

    @Test
    fun auxiliaryOrderingDoesNotChangeTheArchiveChecksum() {
        val first = ContentPortableAuxiliaryState(
            metadata = listOf(
                ContentMetadataMutation("migration.test.b", "2"),
                ContentMetadataMutation("migration.test.a", "1"),
            ),
            aliases = listOf(
                ContentAliasMutation("legacy:b", "target-b"),
                ContentAliasMutation("legacy:a", "target-a"),
            ),
            migrations = listOf(
                ContentMigrationLedgerMutation(
                    namespace = "migration.test.b",
                    sourceDigestSha256 = "2".repeat(64),
                    resultFingerprintSha256 = "4".repeat(64),
                ),
                ContentMigrationLedgerMutation(
                    namespace = "migration.test.a",
                    sourceDigestSha256 = "1".repeat(64),
                    resultFingerprintSha256 = "3".repeat(64),
                ),
            ),
        )
        val second = first.copy(
            metadata = first.metadata.reversed(),
            aliases = first.aliases.reversed(),
            migrations = first.migrations.reversed(),
        )
        fun create(auxiliary: ContentPortableAuxiliaryState): BackupV2CreateResult =
            PortableContentBackupV2Service.create(
                state = BackupV2PortableState(
                    legacySnapshot = AppSnapshot(),
                    auxiliary = auxiliary,
                ),
                candidates = emptyList(),
                blobStore = InMemoryContentBlobStore(),
                operationGate = FixedGate(RightsDecision.ALLOW),
                createdAtEpochMillis = 10,
            )

        assertEquals(create(first).envelope, create(second).envelope)
    }

    @Test
    fun deniedExportOmitsTheCompleteManifestGraph() {
        val fixture = fixture()
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = fixture.blobStore,
            operationGate = FixedGate(RightsDecision.DENY),
            createdAtEpochMillis = 10,
        )

        assertTrue(created.envelope.manifest.attachments.isEmpty())
        assertEquals(BackupV2OmissionReason.RIGHTS_DENIED, created.omittedAttachments.single().reason)
        assertFalse(created.archive.paths.any { it.startsWith("blobs/") })
        assertEquals(1, PortableContentBackupV2Service.inspect(created.archive).portableState.publications.size)
    }

    @Test
    fun deniedExportAlsoRemovesAnnotationBodyAndQuoteFromMetadata() {
        val fixture = fixture()
        val quote = TextQuote(exact = "licensed excerpt", prefix = "before ", suffix = " after")
        val readingScope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = fixture.publication.key,
            acquisitionId = ACQUISITION_ID,
            unitId = fixture.attachment.owner.unitKey,
            contentRevision = 1,
        )
        val annotation = ContentAnnotation(
            schemaVersion = ContentAnnotation.CURRENT_SCHEMA_VERSION,
            annotationId = ANNOTATION_ID,
            kind = ContentAnnotationKind.NOTE,
            range = ReadingRange(
                start = ReadingLocator.Text(
                    schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                    scope = readingScope,
                    resourceId = "body",
                    blockId = "paragraph-0",
                    offset = 0,
                    quote = quote,
                ),
                end = ReadingLocator.Text(
                    schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                    scope = readingScope,
                    resourceId = "body",
                    blockId = "paragraph-0",
                    offset = quote.exact.length,
                ),
                quote = quote,
            ),
            body = "private annotation body",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
                annotations = listOf(annotation),
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = fixture.blobStore,
            operationGate = FixedGate(RightsDecision.DENY),
            createdAtEpochMillis = 10,
            policy = BackupV2CreatePolicy(includeContentBlobs = false),
        )

        val metadata = requireNotNull(created.archive.read("metadata/portable-state-v2.json")).decodeToString()
        assertTrue(PortableContentBackupV2Service.inspect(created.archive).portableState.annotations.isEmpty())
        assertFalse(metadata.contains("private annotation body"))
        assertFalse(metadata.contains("licensed excerpt"))
    }

    @Test
    fun tamperedEntryAndUndeclaredPathFailClosed() {
        val fixture = fixture()
        val archive = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = fixture.blobStore,
            operationGate = FixedGate(RightsDecision.ALLOW),
            createdAtEpochMillis = 10,
        ).archive
        val encoded = BackupV2BinaryCodec.encode(archive)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        assertFailsWith<BackupFormatException> { BackupV2BinaryCodec.decode(encoded) }
    }

    @Test
    fun largeBodyEncodeDecodeAndPublishStayWithinChunkBoundary() {
        val body = buildString(200_000) { repeat(200_000) { append(('a'.code + (it % 23)).toChar()) } }
        val fixture = fixture(body)
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = fixture.blobStore,
            operationGate = FixedGate(RightsDecision.ALLOW),
            createdAtEpochMillis = 10,
        )
        val chunks = ArrayList<ByteArray>()
        var maximumEncodedChunk = 0
        val encodedSize = BackupV2BinaryCodec.encodeTo(created.archive, BackupV2BinarySink { chunk ->
            maximumEncodedChunk = maxOf(maximumEncodedChunk, chunk.size)
            chunks += chunk.copyOf()
        })
        val encoded = ByteArray(encodedSize.toInt())
        var encodedOffset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(encoded, encodedOffset)
            encodedOffset += chunk.size
        }
        assertTrue(maximumEncodedChunk <= 64 * 1024)

        val source = RecordingBackupSource(encoded)
        val decoded = BackupV2BinaryCodec.decode(source)
        assertTrue(source.maximumReadBytes <= 64 * 1024)

        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = body.length.toLong() + 1024)
        var maximumPublishedChunk = 0
        val trackingDestination = object : ContentBlobStore by destination {
            override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
                val stage = destination.beginStage(expectedSizeBytes, mediaType)
                return object : ContentBlobStage by stage {
                    override fun append(chunk: ByteArray) {
                        maximumPublishedChunk = maxOf(maximumPublishedChunk, chunk.size)
                        stage.append(chunk)
                    }
                }
            }
        }
        val published = PortableContentBackupV2Service.publishBodies(decoded, trackingDestination)
        assertTrue(maximumPublishedChunk <= 64 * 1024)
        assertContentEquals(body.encodeToByteArray(), destination.read(published.receipts.single().reference))
    }

    @Test
    fun archiveLimitOmitsOversizeBodyWithoutOpeningIt() {
        val fixture = fixture("x".repeat(128 * 1024))
        var bodyOpenCount = 0
        val trackingStore = object : ContentBlobStore by fixture.blobStore {
            override fun openRead(reference: dev.shinsou.kmp.content.BlobRef): dev.shinsou.kmp.content.BlobReadLease? {
                bodyOpenCount++
                return fixture.blobStore.openRead(reference)
            }
        }

        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(
                legacySnapshot = AppSnapshot(),
                publications = listOf(fixture.publication),
            ),
            candidates = listOf(BackupV2AttachmentCandidate(fixture.attachment, fixture.access)),
            blobStore = trackingStore,
            operationGate = FixedGate(RightsDecision.ALLOW),
            createdAtEpochMillis = 10,
            policy = BackupV2CreatePolicy(maximumArchiveBytes = 64 * 1024),
        )

        assertEquals(BackupV2OmissionReason.ARCHIVE_LIMIT, created.omittedAttachments.single().reason)
        assertEquals(0, bodyOpenCount)
    }

    private fun fixture(text: String = "hello portable body"): Fixture {
        val store = InMemoryContentBlobStore(maximumBlobSizeBytes = maxOf(1024L, text.encodeToByteArray().size.toLong()))
        val published = store.put(text.encodeToByteArray(), "text/plain")
        val publicationKey = PublicationKey(PUBLICATION_ID)
        val unitKey = UnitKey(publicationKey, UNIT_ID)
        val manifest = ContentManifest(
            manifestId = MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 1,
            representations = listOf(
                ContentRepresentation.PlainText(
                    representationId = REPRESENTATION_ID,
                    resource = ResourceRef("body", published.reference),
                    canonicalUtf16Length = text.length,
                    sourceEncoding = "UTF-8",
                    blocks = listOf(TextBlock("paragraph-0", 0, text.length)),
                ),
            ),
            declaredSizeBytes = published.reference.byteSize,
        )
        val publication = Publication(
            key = publicationKey,
            title = "Portable fixture",
            acquisitions = listOf(
                Acquisition(
                    id = ACQUISITION_ID,
                    origin = AcquisitionOrigin.LocalText,
                    units = listOf(
                        PublicationUnit(
                            key = unitKey,
                            title = "Chapter",
                            manifestRevisions = listOf(manifest),
                            ordinal = 0,
                        ),
                    ),
                    contentRevision = 1,
                ),
            ),
        )
        val attachment = ManifestAttachment(
            ContentManifestOwner(publicationKey, ACQUISITION_ID, unitKey),
            manifest,
        )
        val access = ContentAccessRequest(
            grantReference = null,
            scope = RightsScope(
                publicationId = publicationKey,
                acquisitionId = ACQUISITION_ID,
                unitId = unitKey,
                manifestId = MANIFEST_ID,
                contentRevision = 1,
            ),
        )
        return Fixture(store, text, published.reference.blobId, publication, attachment, access)
    }

    private data class Fixture(
        val blobStore: ContentBlobStore,
        val text: String,
        val blobId: String,
        val publication: Publication,
        val attachment: ManifestAttachment,
        val access: ContentAccessRequest,
    )

    private class FixedGate(private val decision: RightsDecision) : ContentOperationGate {
        override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision = decision
        override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) {
            if (decision != RightsDecision.ALLOW) throw ContentOperationDeniedException(operation)
        }
        override fun <T> execute(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: () -> T,
        ): T {
            requireAllowed(request, operation)
            return block()
        }
        override suspend fun <T> executeSuspending(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: suspend () -> T,
        ): T {
            requireAllowed(request, operation)
            return block()
        }
    }

    private class RecordingBackupSource(private val bytes: ByteArray) : BackupV2ArchiveSource {
        var maximumReadBytes: Int = 0
            private set
        override val byteSize: Long get() = bytes.size.toLong()
        override fun read(offset: Long, byteCount: Int): ByteArray {
            maximumReadBytes = maxOf(maximumReadBytes, byteCount)
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        }
    }

    private companion object {
        const val PUBLICATION_ID = "10000000-0000-5000-8000-000000000001"
        const val ACQUISITION_ID = "10000000-0000-5000-8000-000000000002"
        const val UNIT_ID = "10000000-0000-5000-8000-000000000003"
        const val MANIFEST_ID = "10000000-0000-5000-8000-000000000004"
        const val REPRESENTATION_ID = "10000000-0000-5000-8000-000000000005"
        const val ANNOTATION_ID = "10000000-0000-5000-8000-000000000006"
    }
}
