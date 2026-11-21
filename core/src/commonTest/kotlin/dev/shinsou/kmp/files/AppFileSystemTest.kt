package dev.shinsou.kmp.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppFileSystemTest {
    @Test
    fun normalizesSafeRelativePaths() {
        assertEquals("downloads/42/page-1.jpg", validatedRelativePath("/downloads/42/page-1.jpg/"))
    }

    @Test
    fun rejectsTraversalAndUrls() {
        assertFailsWith<IllegalArgumentException> { validatedRelativePath("downloads/../secret") }
        assertFailsWith<IllegalArgumentException> { validatedRelativePath("file://secret") }
    }
}
