package dev.shinsou.kmp.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/** C1 (0007): from ReaderHistoryPresentationTest (:ui); the flush wait is the extension reader's. */
class ReaderProgressFlushTest {
    @Test
    fun aProgressWriteHeldUpDoesNotKeepTheReaderOpen() = runTest {
        // A flush that never ends (a write stuck behind other work) is waited for only so long.
        awaitProgressFlush { awaitCancellation() }
        assertEquals(READER_PROGRESS_FLUSH_WAIT_MS, currentTime)
    }

    @Test
    fun aFinishedProgressWriteIsWaitedFor() = runTest {
        var flushed = false
        awaitProgressFlush { kotlinx.coroutines.delay(500); flushed = true }
        assertEquals(true, flushed)
        assertEquals(500L, currentTime)
    }
}
