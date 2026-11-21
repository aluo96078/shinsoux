package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncV3DiffAndPolicyTest {
    private var tick = 0
    private val env = object : SyncDiffEnv {
        override fun nextClock(): String = "${1000 + tick++}:0:dev"
        override fun tombstoneWork(manga: Manga) = "key-${manga.id}" to "hash-${manga.id}"
        override fun categoryUuid(localId: Long): String? = "uuid-$localId"
        override fun forgetCategory(localId: Long) = Unit
    }

    private val m1 = Manga(id = 1, favorite = true, title = "A", dateAdded = 5)
    private val m2 = Manga(id = 2, favorite = false, title = "B")
    private val c1 = Chapter(id = 10, mangaId = 1, url = "u1")
    private val c2 = Chapter(id = 11, mangaId = 1, url = "u2")
    private val base = AppSnapshot(mangas = listOf(m1, m2), chapters = listOf(c1, c2))

    private fun fields(entries: List<DirtyEntry>) = entries.map { "${it.kind}/${it.ref}/${it.field}${it.aux?.let { a -> "=$a" } ?: ""}" }.sorted()

    @Test
    fun unchangedSnapshotsProduceNothingAndSharedListsAreNotScanned() {
        assertTrue(SyncDiff.diff(base, base, env).isEmpty())
        // Only an unrelated part changed: same list instances, zero work.
        val next = base.copy(revision = 1)
        assertSame(base.mangas, next.mangas)
        assertTrue(SyncDiff.diff(base, next, env).isEmpty())
        // Metadata the sync does not carry (title, cover) is not an edit.
        val renamed = base.copy(mangas = listOf(m1.copy(title = "A2", description = "d"), m2))
        assertTrue(SyncDiff.diff(base, renamed, env).isEmpty())
    }

    @Test
    fun chapterAndLibraryEditsMarkOnlyTheChangedFields() {
        val read = base.copy(chapters = listOf(c1.copy(read = true), c2))
        assertEquals(listOf("unit/c:10/read"), fields(SyncDiff.diff(base, read, env)))

        val page = base.copy(chapters = listOf(c1, c2.copy(lastPageRead = 4, bookmark = true)))
        assertEquals(listOf("unit/c:11/bookmarked", "unit/c:11/lastPageIndex"), fields(SyncDiff.diff(base, page, env)))

        val unfav = base.copy(mangas = listOf(m1.copy(favorite = false), m2))
        assertEquals(listOf("library/m:1/~deleted=true"), fields(SyncDiff.diff(base, unfav, env)))

        val fav = base.copy(mangas = listOf(m1, m2.copy(favorite = true)))
        assertEquals(
            listOf("library/m:2/addedAt", "library/m:2/categoryIds", "library/m:2/~deleted=false"),
            fields(SyncDiff.diff(base, fav, env)),
        )
        val clocks = SyncDiff.diff(base, fav, env).map { it.clock }
        assertEquals(clocks.size, clocks.toSet().size, "every marked field gets its own fresh clock")
    }

    @Test
    fun insertionsAndRemovalsAreDetectedEvenWhenListsShift() {
        val c3 = Chapter(id = 12, mangaId = 1, url = "u3", read = true)
        val appended = base.copy(chapters = base.chapters + c3)
        assertEquals(listOf("unit/c:12/read"), fields(SyncDiff.diff(base, appended, env)))
        // Removing a chapter from the middle shifts positions; unrelated rows must not be reported.
        val removedMiddle = appended.copy(chapters = listOf(c1, c3))
        assertTrue(SyncDiff.diff(appended, removedMiddle, env).isEmpty(), "chapter removals are not synced")
        // Removing a favourite title tombstones its library record with the key known at that moment.
        val gone = base.copy(mangas = listOf(m2), chapters = emptyList())
        val tomb = SyncDiff.diff(base, gone, env).single()
        assertEquals("library", tomb.kind)
        assertEquals("key-1", tomb.tombKey)
        assertEquals("hash-1", tomb.tombWork)
        assertEquals("true", tomb.aux)
    }

    @Test
    fun categoryChangesAndLinks() {
        val cat = Category(id = 3, name = "Reading", sort = 1)
        val withCat = base.copy(categories = base.categories + cat)
        assertEquals(
            listOf("category/k:3/flags", "category/k:3/name", "category/k:3/order", "category/k:3/~deleted=false"),
            fields(SyncDiff.diff(base, withCat, env)),
        )
        val renamed = withCat.copy(categories = listOf(Category.Default, cat.copy(name = "R2", sort = 4)))
        assertEquals(listOf("category/k:3/name", "category/k:3/order"), fields(SyncDiff.diff(withCat, renamed, env)))
        val linked = withCat.copy(mangaCategories = listOf(MangaCategory(1, 3)))
        assertEquals(listOf("library/m:1/categoryIds"), fields(SyncDiff.diff(withCat, linked, env)))
        val nonFavLinked = withCat.copy(mangaCategories = listOf(MangaCategory(2, 3)))
        assertTrue(SyncDiff.diff(withCat, nonFavLinked, env).isEmpty(), "links of titles outside the library are not synced")
        val removed = linked.copy(categories = listOf(Category.Default), mangaCategories = listOf(MangaCategory(1, 0)))
        val tomb = SyncDiff.diff(linked, removed, env).first { it.field == DirtyEntry.DELETED_FIELD && it.kind == "category" }
        assertEquals("uuid-3", tomb.tombKey)
    }

    @Test
    fun historyEditsMarkProgressAndEmptyingTombstonesIt() {
        val h = History(id = 1, chapterId = 10, lastRead = 100)
        val withHistory = base.copy(histories = listOf(h))
        assertEquals(
            listOf(
                "history/m:1/lastReadAt", "history/m:1/unitRemoteId", "history/m:1/~deleted=false",
                "progress/m:1/unitRemoteId", "progress/m:1/~deleted=false",
            ),
            fields(SyncDiff.diff(base, withHistory, env)),
        )
        // timeRead changes on every page turn and must not be treated as progress.
        val timeOnly = withHistory.copy(histories = listOf(h.copy(timeRead = 50)))
        assertTrue(SyncDiff.diff(withHistory, timeOnly, env).isEmpty())
        val cleared = withHistory.copy(histories = emptyList())
        assertEquals(
            listOf("history/m:1/~deleted=true", "progress/m:1/~deleted=true"),
            fields(SyncDiff.diff(withHistory, cleared, env)),
        )
        // Another chapter of the same title keeps the work's history alive.
        val two = withHistory.copy(histories = listOf(h, History(id = 2, chapterId = 11, lastRead = 200)))
        val oneLeft = two.copy(histories = listOf(History(id = 2, chapterId = 11, lastRead = 200)))
        assertTrue(SyncDiff.diff(two, oneLeft, env).none { it.aux == "true" })
    }

    @Test
    fun markAllCoversEverythingWithKnownOrZeroTimes() {
        val snapshot = base.copy(
            mangas = listOf(m1.copy(favoriteModifiedAt = 777), m2),
            chapters = listOf(c1.copy(read = true, lastModifiedAt = 888), c2.copy(bookmark = true)),
            histories = listOf(History(id = 1, chapterId = 10, lastRead = 999)),
        )
        val rows = SyncDiff.markAll(snapshot) { millis -> "$millis:0:dev" }.associateBy { "${it.kind}/${it.ref}/${it.field}" }
        assertEquals("777:0:dev", rows.getValue("library/m:1/addedAt").clock)
        assertEquals("888:0:dev", rows.getValue("unit/c:10/read").clock)
        assertEquals("0:0:dev", rows.getValue("unit/c:11/bookmarked").clock)
        assertEquals("999:0:dev", rows.getValue("history/m:1/lastReadAt").clock)
        assertFalse(rows.keys.any { it.startsWith("library/m:2") }, "titles outside the library have no library record")
        assertTrue(rows.keys.any { it == "category/k:0/flags" })
    }

    @Test
    fun serverAddressPolicyAllowsPlainHttpOnlyOnLocalNetworks() {
        assertEquals("https://sync.example.com", SyncNetworkPolicy.parse("sync.example.com").baseUrl)
        assertEquals("https://sync.example.com/base", SyncNetworkPolicy.parse(" https://sync.example.com/base/ ").baseUrl)
        listOf("http://192.168.1.20:8787", "http://10.0.0.5", "http://172.20.1.1:80", "http://localhost:8787", "http://127.0.0.1:8787", "http://nas.local:8787", "http://[::1]:8787", "http://macbook:8787").forEach {
            val address = SyncNetworkPolicy.parse(it)
            assertTrue(address.insecureLan, it)
        }
        assertEquals("http://192.168.1.20:8787", SyncNetworkPolicy.parse("192.168.1.20:8787").baseUrl)
        assertFalse(SyncNetworkPolicy.parse("https://192.168.1.20").insecureLan)
        listOf("http://sync.example.com", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.0.1").forEach {
            val error = assertFailsWith<SyncServerAddressException>(it) { SyncNetworkPolicy.parse(it) }
            assertEquals(SyncServerAddressException.Reason.INSECURE_PUBLIC_HOST, error.reason)
        }
        assertFailsWith<SyncServerAddressException> { SyncNetworkPolicy.parse("") }
        assertFailsWith<SyncServerAddressException> { SyncNetworkPolicy.parse("ftp://host") }
        assertFailsWith<SyncServerAddressException> { SyncNetworkPolicy.parse("https://user:pw@host") }
        assertFailsWith<SyncServerAddressException> { SyncNetworkPolicy.parse("https://host:99999") }
    }
}
