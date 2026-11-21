package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.ChapterPatch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncV3ConvergenceTest {
    private fun TestDevice.view(): Map<String, String> {
        val snapshot = repository.currentSnapshot
        val result = sortedMapOf<String, String>()
        snapshot.mangas.forEach { m -> result["fav:${m.title}"] = m.favorite.toString() }
        snapshot.chapters.forEach { c ->
            val title = snapshot.mangas.first { it.id == c.mangaId }.title
            result["ch:$title:${c.name}"] = "${c.read}/${c.bookmark}/${c.lastPageRead}"
        }
        return result
    }

    @Test
    fun threeDevicesWithRandomEditsAndRandomSyncOrderConverge() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val devices = listOf("A", "B", "C").map { TestDevice(server, it) }
        try {
            // Every device knows the same ten titles (not yet in the library) with five chapters each.
            devices.forEach { d ->
                repeat(10) { t ->
                    val manga = d.repository.upsertManga(d.fixtures.manga("t$t", "Title $t", favorite = false))
                    d.repository.upsertChapters(d.fixtures.chapters(manga.id, "t$t", (1..5).map { "u$it" }))
                }
            }
            devices[0].signIn(register = true)
            devices.drop(1).forEach { it.signIn() }
            devices.forEach { assertTrue(it.sync()) }

            val random = Random(20261110)
            repeat(300) {
                val d = devices[random.nextInt(devices.size)]
                val snapshot = d.repository.currentSnapshot
                when (random.nextInt(5)) {
                    0 -> d.repository.toggleMangaFavorite(snapshot.mangas.random(random).id)
                    1 -> snapshot.chapters.random(random).let { c -> d.repository.patchChapter(c.id, ChapterPatch(read = !c.read)) }
                    2 -> snapshot.chapters.random(random).let { c -> d.repository.patchChapter(c.id, ChapterPatch(bookmark = !c.bookmark)) }
                    3 -> snapshot.chapters.random(random).let { c -> d.repository.patchChapter(c.id, ChapterPatch(lastPageRead = random.nextInt(0, 30))) }
                    else -> assertTrue(d.sync()) // sync at random moments, i.e. random interleavings
                }
            }
            // Quiesce: everyone pushes, then everyone pulls; a second round absorbs late arrivals.
            repeat(3) { devices.forEach { assertTrue(it.sync()) } }

            val reference = devices[0].view()
            devices.drop(1).forEach { assertEquals(reference, it.view()) }
            devices.forEach { assertEquals(0, it.store.dirtyRefCount(), "no leftover edits on ${it.name}") }
            assertTrue(reference.values.any { it == "true" }, "the random run did change something")
        } finally {
            devices.forEach { it.close() }
        }
    }

    @Test
    fun largePushesAreSplitIntoBatchesOfAtMostFiveHundredRecords() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("big", "Big"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "big", (1..1_300).map { "u$it" }))
            a.signIn(register = true)
            chapters.forEach { a.repository.patchChapter(it.id, ChapterPatch(read = true)) }
            assertTrue(a.sync())
            assertTrue(server.maxBatch in 1..500, "max batch ${server.maxBatch}")
            assertTrue(server.pushCount >= 3)
            assertEquals(1_300, server.accountRecords("alice").keys.count { it.first == SyncKinds.UNIT })
            assertEquals(0, a.store.dirtyRecordCount())
        } finally {
            a.close()
        }
    }
}
