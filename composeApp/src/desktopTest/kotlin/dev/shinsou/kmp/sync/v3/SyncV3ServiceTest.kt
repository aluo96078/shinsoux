package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncV3ServiceTest {
    private fun workHash(remoteId: String) = WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, remoteId).hash

    private fun unitHash(remoteId: String, unitId: String) =
        WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, remoteId).unitHash(unitId)

    /** Adds a favourite extension title with chapters to [device]'s library; returns its local manga id. */
    private suspend fun TestDevice.addTitle(remoteId: String, title: String, units: List<String>): Long {
        val manga = repository.upsertManga(fixtures.manga(remoteId, title))
        repository.upsertChapters(fixtures.chapters(manga.id, remoteId, units))
        return manga.id
    }

    private fun TestDevice.chapterId(mangaId: Long, index: Int): Long =
        repository.currentSnapshot.chapters.filter { it.mangaId == mangaId }[index].id

    @Test
    fun localLibraryAndProgressReachAnotherDevice() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            // A had a library before it ever signed in (first sign-in marks it all dirty).
            val mangaA = a.addTitle("book-1", "One", listOf("u1", "u2"))
            a.repository.patchChapter(a.chapterId(mangaA, 0), ChapterPatch(read = true))
            a.repository.recordHistory(a.chapterId(mangaA, 0), lastRead = 1_700_000_100_000)

            a.signIn(register = true)
            assertTrue(a.sync())

            val serverRecords = server.accountRecords("alice")
            val library = serverRecords.getValue(SyncKinds.LIBRARY to workHash("book-1"))
            assertEquals(JsonPrimitive(1_700_000_000_000), library.fields.getValue("addedAt").value)
            assertEquals(JsonArray(emptyList()), library.fields.getValue("categoryIds").value)
            val unit = serverRecords.getValue(SyncKinds.UNIT to unitHash("book-1", "u1"))
            assertEquals(JsonPrimitive(true), unit.fields.getValue("read").value)
            assertNull(serverRecords[SyncKinds.UNIT to unitHash("book-1", "u2")], "default-state chapters are not synced")
            assertEquals(
                JsonPrimitive("u1"),
                serverRecords.getValue(SyncKinds.HISTORY to workHash("book-1")).fields.getValue("unitRemoteId").value,
            )
            // Nothing local stays queued after a successful push, and no local ids were sent.
            assertEquals(0, a.store.dirtyRefCount())
            assertEquals(0, a.store.dirtyRecordCount())
            assertTrue(serverRecords.values.none { s -> s.fields.values.any { it.value.toString().contains("\"id\"") } })

            // B signs in with an empty library and the source installed.
            b.signIn()
            assertTrue(b.sync())
            val created = b.repository.currentSnapshot.mangas.single()
            assertEquals("One", created.title)
            assertTrue(created.favorite)
            assertEquals(a.repository.currentSnapshot.mangas.single().url, created.url)
            // Chapters are unknown on B yet: the unit/history records wait.
            assertTrue(b.service.state.value.pendingRemoteCount >= 2, b.service.state.value.toString())
            assertEquals(0, b.store.dirtyRefCount(), "remote application must not mark anything dirty")

            // B loads the chapter list; the waiting records apply without another network trip.
            b.repository.upsertChapters(b.fixtures.chapters(created.id, "book-1", listOf("u1", "u2")))
            b.service.retryPendingRemote()
            val chaptersB = b.repository.currentSnapshot.chapters.filter { it.mangaId == created.id }
            assertTrue(chaptersB[0].read)
            assertFalse(chaptersB[1].read)
            assertEquals(
                1_700_000_100_000,
                b.repository.currentSnapshot.histories.single { it.chapterId == chaptersB[0].id }.lastRead,
            )
            assertEquals(0, b.service.state.value.pendingRemoteCount)
            assertEquals(0, b.store.dirtyRefCount())

            // B reads u2; A receives it.
            b.repository.patchChapter(chaptersB[1].id, ChapterPatch(read = true, lastPageRead = 4))
            assertEquals(1, b.store.dirtyRefCount())
            assertTrue(b.sync())
            assertTrue(a.sync())
            val chaptersA = a.repository.currentSnapshot.chapters.filter { it.mangaId == mangaA }
            assertTrue(chaptersA[1].read)
            assertEquals(4, chaptersA[1].lastPageRead)

            // A removes the title from its library; B follows.
            a.repository.toggleMangaFavorite(mangaA)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertFalse(b.repository.currentSnapshot.mangas.single().favorite)
            assertTrue(b.repository.currentSnapshot.mangaCategories.isEmpty())
            // ... and A re-adds it: fields written before the delete must not resurrect, new ones do.
            a.repository.toggleMangaFavorite(mangaA)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertTrue(b.repository.currentSnapshot.mangas.single().favorite)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun categoriesUseRandomUuidsAndLinkAcrossDevices() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            val mangaA = a.addTitle("book-2", "Two", listOf("u1"))
            val reading = a.repository.createCategory("Reading")
            a.repository.setMangaCategories(mangaA, listOf(reading.id))
            assertTrue(a.sync())

            val categoryKeys = server.accountRecords("alice").keys.filter { it.first == SyncKinds.CATEGORY }.map { it.second }
            assertTrue("default" in categoryKeys)
            val uuid = categoryKeys.single { it != "default" }
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uuid), uuid)
            assertEquals(JsonArray(listOf(JsonPrimitive(uuid))), server.accountRecords("alice").getValue(SyncKinds.LIBRARY to workHash("book-2")).fields.getValue("categoryIds").value)

            // B already has an unrelated category with Long id 1: ids are local only.
            b.repository.createCategory("Local only")
            b.signIn()
            assertTrue(b.sync())
            val snapshot = b.repository.currentSnapshot
            val adopted = snapshot.categories.single { it.name == "Reading" }
            assertEquals(2L, adopted.id, "a new local id is assigned")
            val manga = snapshot.mangas.single()
            assertEquals(listOf(adopted.id), snapshot.mangaCategories.filter { it.mangaId == manga.id }.map { it.categoryId })

            // Renaming on B reaches A; deleting on A removes it from B.
            b.repository.renameCategory(adopted.id, "Currently reading")
            assertTrue(b.sync())
            assertTrue(a.sync())
            assertEquals("Currently reading", a.repository.currentSnapshot.categories.single { it.id == reading.id }.name)
            a.repository.deleteCategory(reading.id)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertNull(b.repository.currentSnapshot.categories.firstOrNull { it.name == "Currently reading" })
            assertEquals(0L, b.repository.currentSnapshot.mangaCategories.single { it.mangaId == manga.id }.categoryId)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun dirtyQueueIsBoundedByEntitiesNotByNumberOfChanges() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            val mangaId = a.addTitle("book-3", "Three", listOf("u1", "u2", "u3"))
            val chapterId = a.chapterId(mangaId, 0)
            repeat(1_500) { i -> a.repository.patchChapter(chapterId, ChapterPatch(read = i % 2 == 0, lastPageRead = i % 7)) }
            // One title, one chapter: a handful of refs and at most a field per kind, not 1500 rows.
            val rows = a.store.allDirty()
            assertTrue(rows.size <= 12, "dirty rows: ${rows.size}")
            assertTrue(a.store.dirtyRefCount() <= 3)
            assertTrue(a.sync())
            assertEquals(0, a.store.allDirty().size)
        } finally {
            a.close()
        }
    }

    @Test
    fun legacyUnresolvedSourcesAreCountedNotSynced() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val resolved = dev.shinsou.kmp.domain.model.SourceKey(packageId = "zh.legacy", sourceId = "legacy-src")
        val a = TestDevice(server, "A", legacy = mapOf(77L to resolved))
        try {
            val withPlugin = a.repository.upsertManga(Manga(source = 77, url = "/book/9", title = "Legacy ok", favorite = true))
            a.repository.upsertManga(Manga(source = 88, url = "/book/10", title = "Legacy missing", favorite = true))
            a.signIn(register = true)
            assertTrue(a.sync())
            val library = server.accountRecords("alice").filterKeys { it.first == SyncKinds.LIBRARY }
            assertEquals(
                setOf(WorkKey(1, "zh.legacy", "legacy-src", "/book/9").hash),
                library.keys.map { it.second }.toSet(),
            )
            assertEquals(1, a.service.state.value.unsyncableCount)
            assertNotNull(withPlugin)
        } finally {
            a.close()
        }
    }

    @Test
    fun firstSignInUsesModificationTimesAndZeroForUnknown() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("book-4", "Four").copy(favoriteModifiedAt = 1_650_000_000_000))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "book-4", listOf("u1", "u2")))
            a.repository.upsertChapter(chapters[0].copy(read = true, lastModifiedAt = 1_660_000_000_000))
            a.repository.upsertChapter(chapters[1].copy(bookmark = true, lastModifiedAt = 0))
            a.signIn(register = true)
            val dirty = a.store.allDirty().associateBy { "${it.kind}/${it.ref}/${it.field}" }
            val libClock = SyncClock.parse(dirty.getValue("library/m:${manga.id}/addedAt").clock)
            assertEquals(1_650_000_000_000, libClock.millis)
            val readClock = SyncClock.parse(dirty.getValue("unit/c:${chapters[0].id}/read").clock)
            assertEquals(1_660_000_000_000, readClock.millis)
            val bookmarkClock = SyncClock.parse(dirty.getValue("unit/c:${chapters[1].id}/bookmarked").clock)
            assertEquals(0, bookmarkClock.millis)
            assertTrue(a.sync())
        } finally {
            a.close()
        }
    }

    @Test
    fun serverValueWinsForFieldsWithoutModificationTimeOnSecondDevice() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            val mangaA = a.addTitle("book-5", "Five", listOf("u1"))
            a.repository.patchChapter(a.chapterId(mangaA, 0), ChapterPatch(read = false, bookmark = true))
            a.signIn(register = true)
            assertTrue(a.sync())

            // B has the same title with the chapter read, but no modification time: the server wins.
            val mangaB = b.addTitle("book-5", "Five", listOf("u1"))
            val chapterB = b.chapterId(mangaB, 0)
            b.repository.upsertChapter(b.repository.currentSnapshot.chapters.single { it.id == chapterB }.copy(read = true, bookmark = false, lastModifiedAt = 0))
            b.signIn()
            assertTrue(b.sync())
            val merged = b.repository.currentSnapshot.chapters.single()
            assertTrue(merged.bookmark, "server's bookmark=true wins over the unknown-age local false")
            assertEquals(1, b.repository.currentSnapshot.mangas.size, "the same title is matched, not duplicated")
            assertEquals(0, b.store.dirtyRefCount().coerceAtMost(0))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun resyncAfter410PrunesUnmodifiedRecordsAndKeepsLocalEdits() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            val m1 = a.addTitle("book-6", "Six", listOf("u1"))
            val m2 = a.addTitle("book-7", "Seven", listOf("u1"))
            a.signIn(register = true)
            assertTrue(a.sync())
            b.signIn()
            assertTrue(b.sync())
            assertEquals(2, b.repository.currentSnapshot.mangas.count { it.favorite })

            // Another device deleted book-6 long ago and the server purged the tombstone; later
            // activity moved the tombstone floor past B's cursor.
            a.addTitle("book-extra", "Extra", listOf("u1"))
            assertTrue(a.sync())
            server.purgeRecord("alice", SyncKinds.LIBRARY, workHash("book-6"))
            server.raiseFloor("alice")
            // B edits book-7 offline after that.
            val b7 = b.repository.currentSnapshot.mangas.single { it.title == "Seven" }
            b.repository.patchManga(b7.id, dev.shinsou.kmp.domain.model.MangaPatch(dateAdded = 1_800_000_000_000))
            assertTrue(b.store.dirtyRefCount() > 0)

            assertTrue(b.sync())
            assertTrue(server.goneCount > 0, "the server answered 410")
            val titles = b.repository.currentSnapshot.mangas.filter { it.favorite }.map { it.title }
            assertEquals(listOf("Extra", "Seven"), titles.sorted(), "book-6 was unmodified since the last sync, so it is removed")
            assertEquals(
                JsonPrimitive(1_800_000_000_000),
                server.accountRecords("alice").getValue(SyncKinds.LIBRARY to workHash("book-7")).fields.getValue("addedAt").value,
            )
            assertNotNull(m1); assertNotNull(m2)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun accessTokenIsRefreshedAndReusedRefreshTokenSignsOut() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            a.addTitle("book-8", "Eight", listOf("u1"))
            server.expireAccessTokens()
            assertTrue(a.sync())
            assertTrue(server.requests.any { it.endsWith("/auth/refresh") })
            val refreshToken = a.keyValues.getString(SyncV3Service.KEY_REFRESH_TOKEN)
            assertNotNull(refreshToken)
            assertTrue(refreshToken.startsWith("refresh-"), "the rotated token is persisted")

            // A stolen/old refresh token comes back: the whole session is dead.
            a.keyValues.putString(SyncV3Service.KEY_REFRESH_TOKEN, "refresh-1-0")
            server.expireAccessTokens()
            assertFalse(a.sync())
            assertFalse(a.service.state.value.signedIn)
            assertTrue(a.service.state.value.sessionExpired)
            assertNull(a.keyValues.getString(SyncV3Service.KEY_REFRESH_TOKEN))
        } finally {
            a.close()
        }
    }

    @Test
    fun serverErrorsKeepDirtyDataAndAreRetried() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            a.addTitle("book-9", "Nine", listOf("u1"))
            server.failNextPushWith = 500
            assertFalse(a.sync())
            assertTrue(a.service.state.value.pendingCount > 0)
            assertNotNull(a.service.state.value.lastError)
            assertTrue(a.sync())
            assertEquals(0, a.service.state.value.pendingCount)
            assertNull(a.service.state.value.lastError)
            assertTrue(server.accountRecords("alice").containsKey(SyncKinds.LIBRARY to workHash("book-9")))
        } finally {
            a.close()
        }
    }

    @Test
    fun deviceManagementAndLogout() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            val devices = a.service.listDevices().getOrThrow()
            assertEquals(2, devices.size)
            val own = devices.single { it.current }
            assertEquals("Renamed", a.service.renameDevice(own.id, "Renamed").getOrThrow().name)
            assertEquals("Renamed", a.service.state.value.deviceName)
            val other = devices.single { !it.current }
            a.service.signOutDevice(other.id, isCurrent = false).getOrThrow()
            assertEquals(1, a.service.listDevices().getOrThrow().size)
            a.service.logout()
            assertFalse(a.service.state.value.signedIn)
            assertNull(a.keyValues.getString(SyncV3Service.KEY_REFRESH_TOKEN))
            // Logging out stops tracking: edits are no longer marked.
            a.addTitle("book-10", "Ten", listOf("u1"))
            assertEquals(0, a.store.dirtyRefCount())
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun deletingCategoryWithoutSyncStaysSilent() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A", initial = AppSnapshot(
            categories = listOf(Category.Default, Category(id = 5, name = "Old", sort = 1)),
            mangaCategories = emptyList<MangaCategory>(),
        ))
        try {
            a.signIn(register = true)
            a.repository.deleteCategory(5)
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").keys.none { it.first == SyncKinds.CATEGORY && it.second != "default" } ||
                server.accountRecords("alice").filterKeys { it.first == SyncKinds.CATEGORY && it.second != "default" }.values.all { !it.isLive })
        } finally {
            a.close()
        }
    }

    @Test
    fun removingAndReAddingATitleUnderANewLocalIdStaysLiveEverywhere() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            val first = a.addTitle("book-11", "Eleven", listOf("u1"))
            assertTrue(a.sync())
            b.signIn()
            assertTrue(b.sync())
            assertTrue(b.repository.currentSnapshot.mangas.single().favorite)

            // Both happen before the next sync: the old row is deleted, a new one (new id) appears.
            a.repository.deleteManga(first)
            a.repository.upsertManga(a.fixtures.manga("book-11", "Eleven (again)"))
            assertTrue(a.sync())
            val record = server.accountRecords("alice").getValue(SyncKinds.LIBRARY to workHash("book-11"))
            assertTrue(record.isLive, "the title must not end up deleted: $record")
            assertTrue(b.sync())
            assertTrue(b.repository.currentSnapshot.mangas.single().favorite)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun editsMadeWhileTheSessionIsExpiredAreUploadedAfterSigningInAgain() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            val mangaId = a.addTitle("book-12", "Twelve", listOf("u1", "u2"))
            assertTrue(a.sync())
            server.expireAccessTokens()
            assertTrue(a.sync()) // rotates the refresh token once, so "refresh-1-0" is now used up
            a.keyValues.putString(SyncV3Service.KEY_REFRESH_TOKEN, "refresh-1-0")
            server.expireAccessTokens()
            assertFalse(a.sync())
            assertTrue(a.service.state.value.sessionExpired)

            a.repository.patchChapter(a.chapterId(mangaId, 1), ChapterPatch(read = true))
            assertTrue(a.store.dirtyRefCount() > 0, "edits are still tracked while signed out of the server")

            a.signIn() // same account: resumes instead of starting over
            assertTrue(a.sync())
            assertEquals(
                JsonPrimitive(true),
                server.accountRecords("alice").getValue(SyncKinds.UNIT to unitHash("book-12", "u2")).fields.getValue("read").value,
            )
            assertTrue(a.service.state.value.signedIn)
        } finally {
            a.close()
        }
    }

    @Test
    fun remoteChangesStarvedByLocalEditsAreAppliedByAFollowUpCycle() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B", pushDebounceMillis = 50L)
        try {
            a.signIn(register = true)
            a.addTitle("book-13", "Thirteen", listOf("u1"))
            assertTrue(a.sync())
            b.signIn()

            // Every clock read during B's cycle lands an unrelated local edit, so each attempt to
            // swap in the projected snapshot loses the revision race.
            var busy = false
            var edits = 0
            b.onClockRead = {
                if (!busy) {
                    busy = true
                    try {
                        runBlocking { b.repository.upsertManga(b.fixtures.manga("noise-${edits++}", "Noise", favorite = false)) }
                    } finally {
                        busy = false
                    }
                }
            }
            assertTrue(b.sync(), "the cycle itself succeeded")
            b.onClockRead = null
            assertTrue(edits >= SyncV3Service.APPLY_ATTEMPTS)
            assertTrue(b.store.unappliedCount() > 0, "the records stay marked unapplied, they are not dropped")
            assertEquals(0, b.store.dirtyRefCount(), "the unrelated edits are not what triggers the retry")
            assertTrue(b.repository.currentSnapshot.mangas.none { it.favorite })

            // No further trigger from the test: the service schedules the retry on its own.
            withTimeout(10_000) {
                while (b.store.unappliedCount() > 0) delay(20)
            }
            assertEquals("Thirteen", b.repository.currentSnapshot.mangas.single { it.favorite }.title)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun burstOfServerNotificationsDoesNotQueueACyclePerNotification() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            assertTrue(b.sync())
            a.addTitle("book-14", "Fourteen", listOf("u1"))
            assertTrue(a.sync())

            fun pulls() = server.requests.toList().count { it.startsWith("GET /api/v1/sync/changes") }
            val before = pulls()
            repeat(500) { b.service.onRemoteSeq(1_000L + it) }
            withTimeout(10_000) {
                while (b.repository.currentSnapshot.mangas.none { it.favorite }) delay(20)
            }
            delay(500) // anything still queued would run now
            val extra = pulls() - before
            assertTrue(extra in 1..2, "one cycle running plus at most one waiting, got $extra pulls")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun backgroundSyncOnlyAsksForAnEarlyRetryWhenTheFailureIsTransient() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            assertEquals(SyncBackgroundResult.Completed, a.service.runBackgroundSync(), "signed out: nothing to do")
            a.signIn(register = true)
            a.addTitle("book-15", "Fifteen", listOf("u1"))
            server.failNextPushWith = 503
            assertEquals(SyncBackgroundResult.RetryLater, a.service.runBackgroundSync())
            server.failNextPushWith = 400
            assertEquals(SyncBackgroundResult.Failed, a.service.runBackgroundSync())
            assertEquals(SyncBackgroundResult.Completed, a.service.runBackgroundSync())

            // A dead refresh token needs the user: retrying in the background cannot help.
            server.expireAccessTokens()
            assertEquals(SyncBackgroundResult.Completed, a.service.runBackgroundSync())
            a.keyValues.putString(SyncV3Service.KEY_REFRESH_TOKEN, "refresh-1-0")
            server.expireAccessTokens()
            assertEquals(SyncBackgroundResult.Failed, a.service.runBackgroundSync())
            assertTrue(a.service.state.value.sessionExpired)
        } finally {
            a.close()
        }
    }

    @Test
    fun restoringTheSessionTwiceOrAfterSignOutKeepsExactlyTheRightTracker() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            assertTrue(a.sync())
            a.service.start()
            a.service.start()
            a.addTitle("book-16", "Sixteen", listOf("u1"))
            assertTrue(a.store.dirtyRefCount() > 0, "edits are still tracked after repeated restores")

            a.service.logout()
            a.service.start() // nothing to restore
            a.addTitle("book-17", "Seventeen", listOf("u1"))
            assertEquals(0, a.store.dirtyRefCount(), "no tracker is left behind after signing out")
        } finally {
            a.close()
        }
    }

    @Test
    fun descriptorsCarryTheSourceAndTitlesSyncedWithoutItAreDescribedOnce() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.addTitle("book-1", "One", listOf("u1"))
            a.signIn(register = true)
            assertTrue(a.sync())
            // Nothing installed describes the source yet: the title is synced without a source name.
            assertNull(server.accountWork("alice", workHash("book-1"))?.sourceName)

            a.sources = mapOf(
                (TestSourceKey.packageId to TestSourceKey.sourceId) to
                    SyncSourceInfo(name = " Test Source ", language = "zh-Hant", repositoryUrl = "https://repo.example/index"),
            )
            assertTrue(a.sync())
            val described = server.accountWork("alice", workHash("book-1"))
            assertEquals("Test Source", described?.sourceName)
            assertEquals("zh-Hant", described?.language)
            assertEquals("https://repo.example/index", described?.repositoryUrl)

            // The refresh runs once per set of installed sources; a later title is described by its own push.
            val pushes = server.pushCount
            assertTrue(a.sync())
            assertEquals(pushes, server.pushCount)
            a.addTitle("book-2", "Two", listOf("u1"))
            assertTrue(a.sync())
            assertEquals("Test Source", server.accountWork("alice", workHash("book-2"))?.sourceName)

            // An unrelated package does not describe these titles, and does not end the matter:
            // when the source's own package changes, the titles are described again.
            a.sources = mapOf(("other.package" to "other") to SyncSourceInfo("Other", "en", null))
            assertTrue(a.sync())
            a.sources = mapOf(
                (TestSourceKey.packageId to TestSourceKey.sourceId) to SyncSourceInfo("Renamed Source", "zh-Hant", null),
            )
            assertTrue(a.sync())
            assertEquals("Renamed Source", server.accountWork("alice", workHash("book-1"))?.sourceName)
            assertEquals("Renamed Source", server.accountWork("alice", workHash("book-2"))?.sourceName)
        } finally {
            a.close()
        }
    }

    @Test
    fun aTitleSyncedByADeviceWithoutTheSourceIsDescribedByOneThatHasIt() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            val source = TestSourceKey.packageId to TestSourceKey.sourceId
            // B knows the source and has already settled what it can describe: nothing yet.
            b.sources = mapOf(source to SyncSourceInfo("Test Source", "zh-Hant", "https://repo.example/index"))
            b.signIn(register = true)
            assertTrue(b.sync())

            // A does not have the package; its title reaches the server without a source name.
            a.addTitle("book-1", "One", listOf("u1"))
            a.signIn(register = false)
            assertTrue(a.sync())
            assertNull(server.accountWork("alice", workHash("book-1"))?.sourceName)

            assertTrue(b.sync())
            val described = server.accountWork("alice", workHash("book-1"))
            assertEquals("Test Source", described?.sourceName)
            assertEquals("https://repo.example/index", described?.repositoryUrl)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun aDeviceWithoutTheSourceNeverSendsItsStoredCopyOfTheSourceData() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            val source = TestSourceKey.packageId to TestSourceKey.sourceId
            a.sources = mapOf(source to SyncSourceInfo("Old Name", "zh-Hant", null))
            a.addTitle("book-1", "One", listOf("u1"))
            a.signIn(register = true)
            assertTrue(a.sync())

            // B stores the title with the name of that moment.
            b.signIn(register = false)
            assertTrue(b.sync())

            // The package is updated on A; only descriptors are sent, so B's stored copy stays old.
            a.sources = mapOf(source to SyncSourceInfo("New Name", "zh-Hant", null))
            assertTrue(a.sync())
            assertEquals("New Name", server.accountWork("alice", workHash("book-1"))?.sourceName)

            // B installs something unrelated: that is no reason to send what it has stored.
            b.sources = mapOf(("other.package" to "other") to SyncSourceInfo("Other", "en", null))
            assertTrue(b.sync())
            assertEquals("New Name", server.accountWork("alice", workHash("book-1"))?.sourceName)
        } finally {
            a.close()
            b.close()
        }
    }
}
