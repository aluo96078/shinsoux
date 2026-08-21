package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.reader.EpubPublicationResourceResolver
import dev.shinsou.kmp.reader.EpubRenderRequestFactory
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.reader.EpubUserStyleSheet
import dev.shinsou.kmp.reader.ReadingScope
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class BoundedEpubArchiveExtractorTest {
    @Test
    fun productionExtractorInflatesStoredAndDeflatedEntriesWithExactCompressedSizes() {
        val fixture = linkedMapOf(
            "mimetype" to EPUB_MIME.encodeToByteArray(),
            "META-INF/container.xml" to CONTAINER.encodeToByteArray(),
            "OPS/package.opf" to PACKAGE.encodeToByteArray(),
            "OPS/Text/chapter.xhtml" to CHAPTER.encodeToByteArray(),
            "OPS/Styles/main.css" to CSS.encodeToByteArray(),
        )
        val archive = zip(fixture, storedPaths = setOf("mimetype"))
        val extracted = BoundedEpubArchiveExtractor().extract(archive, EpubArchiveLimits())

        assertEquals(fixture.keys.sorted(), extracted.map(EpubArchiveEntry::path))
        extracted.forEach { entry ->
            assertContentEquals(fixture.getValue(entry.path), entry.bytes)
            assertTrue(entry.compressedSizeBytes > 0)
        }
        assertEquals(
            fixture.getValue("mimetype").size.toLong(),
            extracted.single { it.path == "mimetype" }.compressedSizeBytes,
        )

        val store = InMemoryContentBlobStore()
        val acquired = EpubAcquisitionService(
            blobStore = store,
            archiveExtractor = BoundedEpubArchiveExtractor(),
        ).acquire(EpubAcquisitionRequest(target(), archive))
        val representation = acquired.representation as ContentRepresentation.EpubSpine
        assertEquals("OPS/Text/chapter.xhtml", representation.documents.single().href)
        assertEquals(fixture.size, representation.packageGraph.resources.size)

        val navigation = EpubSpineNavigation(
            ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = acquired.publicationDraft.key,
                acquisitionId = acquired.acquisition.id,
                unitId = acquired.unit.key,
                contentRevision = acquired.manifest.contentRevision,
            ),
            representation,
        )
        val request = EpubRenderRequestFactory(store).create(
            navigation = navigation,
            documentIndex = 0,
            userStyleSheets = listOf(EpubUserStyleSheet("reader", "body { font-size: 150%; }")),
        )
        val resolver = EpubPublicationResourceResolver(request)
        val renderedDocument = requireNotNull(resolver.resolve(request.documentUrl)).bytes.decodeToString()
        assertTrue("Content-Security-Policy" in renderedDocument)
        assertTrue(resolver.userStyleUrls.single() in renderedDocument)
        assertContentEquals(
            "body { font-size: 150%; }".encodeToByteArray(),
            requireNotNull(resolver.resolve(resolver.userStyleUrls.single())).bytes,
        )
        assertNull(resolver.resolve("https://example.test/tracker"))
    }

    @Test
    fun centralDirectoryLimitsRejectBombBeforeInflation() {
        val archive = zip(mapOf("bomb.xhtml" to ByteArray(1_000_000) { 'A'.code.toByte() }))
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            BoundedEpubArchiveExtractor().extract(
                archive,
                EpubArchiveLimits(maximumCompressionRatio = 2.0),
            )
        }
    }

    @Test
    fun rawInflaterChecksCancellationBetween64KiBOutputSlices() {
        val expanded = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val compressed = rawDeflate(expanded)
        var checkpoints = 0

        assertFailsWith<CancellationException> {
            platformInflateRawDeflate(compressed, expanded.size) {
                checkpoints++
                if (checkpoints == 2) throw CancellationException("EPUB import left")
            }
        }

        assertEquals(2, checkpoints)
    }

    @Test
    fun productionExtractorReadsLargeArchiveByBoundedRandomAccessRegions() {
        val entries = (0 until 8).associate { index ->
            "payload-$index.bin" to ByteArray(32 * 1024) { byte -> ((byte + index) % 251).toByte() }
        }
        val archive = zip(entries, storedPaths = entries.keys)
        val source = RecordingSource(archive)

        val extracted = BoundedEpubArchiveExtractor().extract(source, EpubArchiveLimits())

        assertEquals(entries.keys.sorted(), extracted.map(EpubArchiveEntry::path))
        assertTrue(source.maximumReadBytes <= 65_557)
        assertTrue(source.maximumReadBytes < archive.size)
    }

    @Test
    fun productionEntriesReplayOneVerifiedBodyInsteadOfRetainingAllExpandedBodies() {
        val entries = linkedMapOf(
            "first.bin" to ByteArray(48 * 1024) { 1 },
            "second.bin" to ByteArray(48 * 1024) { 2 },
        )
        val source = RecordingSource(zip(entries, storedPaths = entries.keys))
        val extracted = BoundedEpubArchiveExtractor().extract(source, EpubArchiveLimits())
        val bytesReadAfterValidation = source.totalReadBytes

        assertContentEquals(entries.getValue("first.bin"), extracted.first().bytes)
        val bytesReadAfterFirstReplay = source.totalReadBytes
        assertTrue(bytesReadAfterFirstReplay > bytesReadAfterValidation)

        assertContentEquals(entries.getValue("first.bin"), extracted.first().bytes)
        assertTrue(source.totalReadBytes > bytesReadAfterFirstReplay)
    }

    @Test
    fun compressedEntryHardCapFailsBeforeInflation() {
        val archive = zip(
            mapOf("large.bin" to ByteArray(4 * 1024) { it.toByte() }),
            storedPaths = setOf("large.bin"),
        )
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            BoundedEpubArchiveExtractor().extract(
                archive,
                EpubArchiveLimits(maximumCompressedEntryBytes = 1024),
            )
        }
    }

    @Test
    fun traversalAndLocalCentralNameMismatchFailClosed() {
        val traversal = zip(mapOf("../secret.xhtml" to CHAPTER.encodeToByteArray()))
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            BoundedEpubArchiveExtractor().extract(traversal, EpubArchiveLimits())
        }

        val mismatched = zip(mapOf("chapter.xhtml" to CHAPTER.encodeToByteArray())).copyOf()
        // First local filename begins at byte 30; changing it leaves the central record intact.
        mismatched[30] = 'C'.code.toByte()
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            BoundedEpubArchiveExtractor().extract(mismatched, EpubArchiveLimits())
        }
    }

    private fun target(): LocalAcquisitionTarget = LocalAcquisitionTarget(
        publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
        publicationTitle = "Production EPUB",
        stableImportId = "bounded-extractor-fixture",
        unitTitle = "Book",
        contentRevision = 1,
    )

    private fun zip(
        entries: Map<String, ByteArray>,
        storedPaths: Set<String> = emptySet(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, bytes) ->
                val entry = ZipEntry(path)
                if (path in storedPaths) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0) { "Raw DEFLATE fixture made no progress" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private class RecordingSource(private val bytes: ByteArray) : EpubArchiveSource {
        var maximumReadBytes: Int = 0
            private set
        var totalReadBytes: Long = 0
            private set
        override val byteSize: Long get() = bytes.size.toLong()
        override fun read(offset: Long, byteCount: Int): ByteArray {
            maximumReadBytes = maxOf(maximumReadBytes, byteCount)
            totalReadBytes += byteCount
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        }
    }

    private companion object {
        const val EPUB_MIME = "application/epub+zip"
        const val CONTAINER =
            "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"OPS/package.opf\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        const val PACKAGE =
            "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" version=\"3.0\" " +
                "unique-identifier=\"book-id\"><metadata>" +
                "<dc:identifier id=\"book-id\">book-1</dc:identifier>" +
                "<dc:title>Production EPUB</dc:title></metadata>" +
                "<manifest><item id=\"chapter\" href=\"Text/chapter.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"css\" href=\"Styles/main.css\" media-type=\"text/css\"/></manifest>" +
                "<spine><itemref idref=\"chapter\"/></spine></package>"
        const val CHAPTER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
                "<link rel=\"stylesheet\" href=\"../Styles/main.css\"/></head><body>Hello EPUB</body></html>"
        const val CSS = "body { color: rgb(1, 2, 3); }"
    }
}
