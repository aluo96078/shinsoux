package dev.shinsou.kmp.content.acquisition

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

/** The JVM raw-deflate inflater of EPUB archives (from BoundedEpubArchiveExtractorTest). */
class EpubArchiveInflaterTest {
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
}
