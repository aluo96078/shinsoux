package dev.shinsou.kmp.ui

import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class PickedDocumentHandOverTest {
    private class CountingSource : ImportedDocumentSource {
        var closes = 0
        override val byteSize: Long = 1
        override fun read(offset: Long, byteCount: Int): ByteArray = ByteArray(byteCount)
        override fun close() {
            closes++
        }
    }

    @Test
    fun documentsAreClosedWhenCancellationPreventsTheHandOver() = runTest {
        val source = CountingSource()
        var imported = false
        val work = StandardTestDispatcher(testScheduler)
        val shell = launch(start = CoroutineStart.UNDISPATCHED) {
            // The shell is cancelled after the picker returned and before the import starts.
            coroutineContext.cancel()
            handOverPickedDocuments(listOf(ImportedDocument("a.epub", source)), work) { imported = true }
        }
        shell.join()
        assertFalse(imported)
        assertEquals(1, source.closes)
    }

    @Test
    fun documentsTheImportTookOverAreLeftToIt() = runTest {
        val source = CountingSource()
        val result = handOverPickedDocuments(
            listOf(ImportedDocument("a.epub", source)),
            StandardTestDispatcher(testScheduler),
        ) { "imported" }
        assertEquals("imported", result)
        assertEquals(0, source.closes, "the import closes what it was handed")
    }
}
