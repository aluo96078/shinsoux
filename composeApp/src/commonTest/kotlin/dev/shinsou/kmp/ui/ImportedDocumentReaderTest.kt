package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedDocumentReaderTest {
    @Test
    fun readsExactlyTheDeclaredPayloadAcrossPartialChunks() {
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        val source = ByteArraySource(payload, maxChunkBytes = 7_001)

        val result = readBoundedImportedBytes(
            name = "backup.json",
            declaredSize = payload.size.toLong(),
            limits = ImportedDocumentLimits(maxBytesPerFile = 200_000),
            read = source::read,
        )

        assertContentEquals(payload, result)
        assertEquals(payload.size, source.offset)
    }

    @Test
    fun rejectsUnknownMetadataBeforeReadingOrAllocatingThePayload() {
        var readCalled = false

        val error = assertFailsWith<ImportedDocumentReadException> {
            readBoundedImportedBytes(
                name = "cloud.cbz",
                declaredSize = null,
                limits = ImportedDocumentLimits(maxBytesPerFile = 1024),
            ) { _, _, _ ->
                readCalled = true
                -1
            }
        }

        assertFalse(readCalled)
        assertTrue(error.message.orEmpty().contains("determine the size"))
    }

    @Test
    fun appliesNormalizedExtensionAndTotalLimitsBeforeReading() {
        val limits = ImportedDocumentLimits(
            maxBytesPerFile = 512,
            maxTotalBytes = 600,
            maxBytesByExtension = mapOf(".JPG" to 64),
        )
        assertEquals(64, limits.maxBytesFor("Page.JpG"))
        assertEquals(512, limits.maxBytesFor("book.cbz"))

        val imageError = assertFailsWith<ImportedDocumentReadException> {
            readBoundedImportedBytes("page.jpg", 65, limits) { _, _, _ -> -1 }
        }
        assertTrue(imageError.message.orEmpty().contains("64 bytes per-file"))

        val totalError = assertFailsWith<ImportedDocumentReadException> {
            readBoundedImportedBytes(
                name = "book.cbz",
                declaredSize = 101,
                limits = limits,
                previouslyAcceptedBytes = 500,
            ) { _, _, _ -> -1 }
        }
        assertTrue(totalError.message.orEmpty().contains("600 bytes total"))
    }

    @Test
    fun rejectsFilesThatShrinkOrGrowAfterMetadataLookup() {
        val shrinkError = assertFailsWith<ImportedDocumentReadException> {
            val source = ByteArraySource(byteArrayOf(1, 2))
            readBoundedImportedBytes(
                name = "short.zip",
                declaredSize = 3,
                limits = ImportedDocumentLimits(maxBytesPerFile = 10),
                read = source::read,
            )
        }
        assertTrue(shrinkError.message.orEmpty().contains("changed while it was being read"))

        val growError = assertFailsWith<ImportedDocumentReadException> {
            val source = ByteArraySource(byteArrayOf(1, 2, 3))
            readBoundedImportedBytes(
                name = "growing.zip",
                declaredSize = 2,
                limits = ImportedDocumentLimits(maxBytesPerFile = 10),
                read = source::read,
            )
        }
        assertTrue(growError.message.orEmpty().contains("changed while it was being read"))
    }

    @Test
    fun wrapsProviderFailuresWithAnUnderstandableDocumentName() {
        val error = assertFailsWith<ImportedDocumentReadException> {
            readBoundedImportedBytes(
                name = "locked.json",
                declaredSize = 1,
                limits = ImportedDocumentLimits(maxBytesPerFile = 10),
            ) { _, _, _ -> error("permission denied") }
        }

        assertEquals("Unable to read “locked.json”.", error.message)
        assertEquals("permission denied", error.cause?.message)
    }
}

private class ByteArraySource(
    private val payload: ByteArray,
    private val maxChunkBytes: Int = Int.MAX_VALUE,
) {
    var offset: Int = 0
        private set

    fun read(destination: ByteArray, destinationOffset: Int, requested: Int): Int {
        if (offset >= payload.size) return -1
        val count = minOf(requested, maxChunkBytes, payload.size - offset)
        payload.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = offset,
            endIndex = offset + count,
        )
        offset += count
        return count
    }
}
