package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.domain.model.Category
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPersistenceTest {
    @Test
    fun aMissingStateFileStartsFresh() {
        val directory = Files.createTempDirectory("shinsou-state")
        assertEquals(AppSnapshot(), DesktopPersistence.loadSnapshot(directory.resolve("shinsou-state.json")))
    }

    @Test
    fun aSavedStateIsLoadedBack() {
        val directory = Files.createTempDirectory("shinsou-state")
        val file = directory.resolve("shinsou-state.json")
        val saved = AppSnapshot(categories = listOf(Category.Default, Category(id = 7, name = "Reading", sort = 1)))
        file.writeText(AppSnapshotJson.encode(saved))
        assertEquals(saved, DesktopPersistence.loadSnapshot(file))
    }

    @Test
    fun anUndecodableStateFileIsKeptAsideInsteadOfBeingOverwritten() {
        val directory = Files.createTempDirectory("shinsou-state")
        val file = directory.resolve("shinsou-state.json")
        file.writeText("{ truncated")

        assertEquals(AppSnapshot(), DesktopPersistence.loadSnapshot(file))

        assertFalse(Files.exists(file), "the next save must not replace the only copy")
        val kept = directory.listDirectoryEntries().single()
        assertTrue(kept.name.startsWith("shinsou-state.corrupt-"), kept.name)
        assertEquals("{ truncated", kept.readText())
    }

    @Test
    fun anUnreadableStateFileFailsTheStartInsteadOfStartingEmpty() {
        val directory = Files.createTempDirectory("shinsou-state")
        // A directory under the state file's name exists but cannot be read as a file.
        val file = Files.createDirectory(directory.resolve("shinsou-state.json"))
        assertFailsWith<IllegalStateException> { DesktopPersistence.loadSnapshot(file) }
        assertTrue(Files.exists(file))
    }
}
