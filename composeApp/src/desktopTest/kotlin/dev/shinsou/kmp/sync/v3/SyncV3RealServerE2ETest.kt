package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.network.createPlatformHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end against a real shinsou-server (registration must be open). Skipped unless
 * SHINSOU_SYNC_E2E_URL is set, for example http://127.0.0.1:8787. Every run registers its own
 * `e2e_<random>` account; remove those afterwards with
 * `DELETE FROM users WHERE username LIKE 'e2e\_%'` (the server cascades).
 */
class SyncV3RealServerE2ETest {
    private val url: String? = System.getenv("SHINSOU_SYNC_E2E_URL")?.takeIf { it.isNotBlank() }

    private suspend fun <T> eventually(timeoutMillis: Long = 15_000, check: () -> T?): T? =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                check()?.let { return@withTimeoutOrNull it }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    @Test
    fun twoIndependentClientsConvergeThroughARealServer() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue("SHINSOU_SYNC_E2E_URL is not set", url != null)
        val baseUrl = url!!
        val user = "e2e_" + Random.nextBytes(4).joinToString("") { "%02x".format(it) }
        val password = "e2e-password-" + Random.nextInt(100_000, 999_999)
        val a = TestDevice(baseUrl, "e2e-A", createPlatformHttpClient(), pushDebounceMillis = 300)
        val b = TestDevice(baseUrl, "e2e-B", createPlatformHttpClient(), pushDebounceMillis = 300)
        try {
            // Server discovery and the version header.
            val (_, instance) = a.service.discover(baseUrl).getOrThrow()
            assertEquals("open", instance.registration, "start the server with SHINSOU_REGISTRATION=open")

            // Device A: a library built before signing in, with a category and reading state.
            val manga = a.repository.upsertManga(a.fixtures.manga("e2e-book-1", "E2E One"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "e2e-book-1", listOf("c1", "c2", "c3")))
            val category = a.repository.createCategory("E2E Shelf")
            a.repository.setMangaCategories(manga.id, listOf(category.id))
            a.repository.patchChapter(chapters[0].id, ChapterPatch(read = true, lastPageRead = 7))
            a.repository.recordHistory(chapters[0].id, lastRead = System.currentTimeMillis() - 5_000)
            a.signIn(register = true, user = user, password = password)
            assertTrue(a.sync(), a.service.state.value.lastError.orEmpty())
            assertEquals(0, a.service.state.value.pendingCount)

            // Device B: separate repository and database, same account.
            b.signIn(user = user, password = password)
            assertTrue(b.sync(), b.service.state.value.lastError.orEmpty())
            val stub = b.repository.currentSnapshot.mangas.single()
            assertEquals("E2E One", stub.title)
            assertTrue(stub.favorite)
            assertEquals("E2E Shelf", b.repository.currentSnapshot.categories.single { it.id != 0L }.name)
            val chaptersB = b.repository.upsertChapters(b.fixtures.chapters(stub.id, "e2e-book-1", listOf("c1", "c2", "c3")))
            b.service.retryPendingRemote()
            val afterRetry = b.repository.currentSnapshot.chapters.filter { it.mangaId == stub.id }
            assertTrue(afterRetry[0].read)
            assertEquals(7, afterRetry[0].lastPageRead)
            assertEquals(1, b.repository.currentSnapshot.histories.size)
            assertEquals(0, b.store.dirtyRefCount())

            // B reads chapter 2 and bookmarks chapter 3; A converges after a sync.
            b.repository.patchChapter(chaptersB[1].id, ChapterPatch(read = true))
            b.repository.patchChapter(chaptersB[2].id, ChapterPatch(bookmark = true))
            assertTrue(b.sync())
            assertTrue(a.sync())
            val chaptersA = a.repository.currentSnapshot.chapters.filter { it.mangaId == manga.id }
            assertTrue(chaptersA[1].read)
            assertTrue(chaptersA[2].bookmark)

            // Concurrent edits to the same field: the later HLC wins on both devices.
            a.repository.patchChapter(chaptersA[1].id, ChapterPatch(read = false))
            delay(20)
            b.repository.patchChapter(chaptersB[1].id, ChapterPatch(read = true, lastPageRead = 3))
            repeat(2) { assertTrue(a.sync()); assertTrue(b.sync()) }
            assertEquals(
                a.repository.currentSnapshot.chapters.filter { it.mangaId == manga.id }.map { it.read to it.lastPageRead },
                b.repository.currentSnapshot.chapters.filter { it.mangaId == stub.id }.map { it.read to it.lastPageRead },
            )

            // Realtime: B opens its stream; A's later change reaches B without B syncing.
            b.service.onForeground()
            val connected = eventually { b.service.state.value.streamConnected.takeIf { it } }
            assertTrue(connected == true, "stream connected")
            delay(500)
            a.repository.patchChapter(chaptersA[0].id, ChapterPatch(bookmark = true))
            assertTrue(a.sync())
            val pushed = eventually(20_000) {
                b.repository.currentSnapshot.chapters.first { it.id == chaptersB[0].id }.bookmark.takeIf { it }
            }
            assertTrue(pushed == true, "B received A's change through the WebSocket stream")
            b.service.onBackground()

            // A delete propagates.
            a.repository.toggleMangaFavorite(manga.id)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertFalse(b.repository.currentSnapshot.mangas.single().favorite)

            // Device management against the real server.
            val devices = a.service.listDevices().getOrThrow()
            assertEquals(2, devices.size)
            a.service.renameDevice(devices.single { it.current }.id, "Renamed A").getOrThrow()
            assertEquals("Renamed A", a.service.listDevices().getOrThrow().single { it.current }.name)
            b.service.logout()
            assertEquals(1, a.service.listDevices().getOrThrow().size)
            println("E2E account used: $user")
        } finally {
            a.close(); b.close()
        }
    }
}
