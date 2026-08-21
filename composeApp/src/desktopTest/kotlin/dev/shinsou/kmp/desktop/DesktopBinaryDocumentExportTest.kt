package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.ui.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.BinaryDocumentExportSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBinaryDocumentExportTest {
    @Test
    fun largeSourceStreamsThroughBoundedChunksToSelectedFile() {
        val directory = Files.createTempDirectory("shinsou-stream-export-")
        try {
            val target = directory.resolve("content.shinsou2")
            val size = 3L * 1024 * 1024 + 17L
            var writeCalls = 0
            var maximumChunk = 0
            val source = patternSource(size) { chunkSize ->
                writeCalls += 1
                maximumChunk = maxOf(maximumChunk, chunkSize)
            }

            assertTrue(writeBinaryDocument(target, source))

            assertEquals(size, Files.size(target))
            assertTrue(writeCalls > 1)
            assertTrue(maximumChunk <= TEST_CHUNK_BYTES)
            Files.newInputStream(target).use { input ->
                assertContentEquals(byteArrayOf(0, 1, 2, 3), input.readNBytes(4))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedStreamDoesNotReplaceExistingDocument() {
        val directory = Files.createTempDirectory("shinsou-stream-export-failure-")
        try {
            val target = directory.resolve("existing.shinsou2")
            val original = "existing-document".encodeToByteArray()
            Files.write(target, original)
            val truncated = object : BinaryDocumentExportSource {
                override val expectedByteSize: Long = 10L
                override fun writeTo(sink: BinaryDocumentExportSink): Long {
                    sink.write(byteArrayOf(1, 2, 3))
                    return 3L
                }
            }

            assertFalse(writeBinaryDocument(target, truncated))
            assertContentEquals(original, Files.readAllBytes(target))
            assertEquals(
                emptyList(),
                Files.list(directory).use { files ->
                    files.filter { it.fileName.toString().endsWith(".tmp") }.toList()
                },
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun patternSource(
        expectedSize: Long,
        onChunk: (Int) -> Unit,
    ): BinaryDocumentExportSource = object : BinaryDocumentExportSource {
        override val expectedByteSize: Long = expectedSize

        override fun writeTo(sink: BinaryDocumentExportSink): Long {
            var written = 0L
            while (written < expectedByteSize) {
                val count = minOf(TEST_CHUNK_BYTES.toLong(), expectedByteSize - written).toInt()
                val chunk = ByteArray(count) { index -> ((written + index) and 0xff).toByte() }
                onChunk(count)
                sink.write(chunk)
                written += count
            }
            return written
        }
    }

    private companion object {
        const val TEST_CHUNK_BYTES: Int = 4 * 1024
    }
}
