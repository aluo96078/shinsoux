package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BinaryDocumentExportTest {
    @Test
    fun legacyHostMaterializesOnlySmallStreamingDocuments() = runTest {
        var exportedName: String? = null
        var exportedBytes: ByteArray? = null
        val host: ShinsouAppServices = object : ShinsouAppServices {
            override suspend fun exportBinaryDocument(
                suggestedName: String,
                contents: ByteArray,
            ): Boolean {
                exportedName = suggestedName
                exportedBytes = contents.copyOf()
                return true
            }
        }

        val accepted = host.exportBinaryDocument(
            "small.bin",
            ByteArrayBinaryDocumentExportSource("streamed".encodeToByteArray()),
        )

        assertTrue(accepted)
        assertEquals("small.bin", exportedName)
        assertContentEquals("streamed".encodeToByteArray(), exportedBytes)

        var largeSourceWrites = 0
        val rejected = host.exportBinaryDocument(
            "large.bin",
            object : BinaryDocumentExportSource {
                override val expectedByteSize: Long =
                    DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES + 1L

                override fun writeTo(sink: BinaryDocumentExportSink): Long {
                    largeSourceWrites += 1
                    return 0L
                }
            },
        )

        assertFalse(rejected)
        assertEquals(0, largeSourceWrites)
        assertEquals("small.bin", exportedName)
    }

    @Test
    fun checkedWriterRejectsDeclaredLengthMismatch() {
        val source = object : BinaryDocumentExportSource {
            override val expectedByteSize: Long = 5L
            override fun writeTo(sink: BinaryDocumentExportSink): Long {
                sink.write(byteArrayOf(1, 2, 3))
                return 3L
            }
        }

        assertFailsWith<IllegalStateException> {
            source.writeCheckedTo(BinaryDocumentExportSink { })
        }
    }

    @Test
    fun failedProviderWriteDiscardsPartialDocument() {
        var discardCalls = 0
        val truncated = object : BinaryDocumentExportSource {
            override val expectedByteSize: Long = 5L
            override fun writeTo(sink: BinaryDocumentExportSink): Long {
                sink.write(byteArrayOf(1, 2, 3))
                return 3L
            }
        }

        val saved = writeBinaryDocumentWithFailureCleanup(
            source = truncated,
            write = { source -> source.writeCheckedTo(BinaryDocumentExportSink { }) },
            discardPartial = { discardCalls += 1 },
        )

        assertFalse(saved)
        assertEquals(1, discardCalls)

        val complete = writeBinaryDocumentWithFailureCleanup(
            source = ByteArrayBinaryDocumentExportSource(byteArrayOf(1, 2, 3)),
            write = { source -> source.writeCheckedTo(BinaryDocumentExportSink { }) },
            discardPartial = { discardCalls += 1 },
        )
        assertTrue(complete)
        assertEquals(1, discardCalls)
    }
}
