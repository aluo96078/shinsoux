package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.navigation.NavigationState
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.screens.ALL_LIBRARY_CATEGORY_ID
import dev.shinsou.kmp.ui.screens.MoreDestination
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellSavedStateTest {
    private val full = ShellSavedState(
        section = MainSection.LIBRARY,
        moreDestination = MoreDestination.Settings,
        detailMangaId = 7L,
        localExtensionMangaId = 8L,
        localExtensionUnit = extensionUnitIdentity("https://source.example/novel/1/chapter/9"),
        reader = ReaderSavedSession(mangaId = 7L, chapterId = 70L, pageIndex = 4),
        libraryIds = setOf(1L, 2L),
        chapterIds = setOf(70L),
        categoryId = 3L,
    )

    @Test
    fun encodingRoundTripsEveryField() {
        assertEquals(full, ShellSavedState.decode(full.encode()))
        val minimal = ShellSavedState(section = MainSection.BROWSE)
        assertEquals(minimal, ShellSavedState.decode(minimal.encode()))
        val readerWithoutPage = full.copy(reader = ReaderSavedSession(7L, 70L, pageIndex = null))
        assertEquals(readerWithoutPage, ShellSavedState.decode(readerWithoutPage.encode()))
    }

    @Test
    fun theTextFormKeptBetweenLaunchesRoundTripsEveryField() {
        assertEquals(full, ShellSavedState.decodeText(full.encodeText()))
        val minimal = ShellSavedState(section = MainSection.BROWSE)
        assertEquals(minimal, ShellSavedState.decodeText(minimal.encodeText()))
        val readerWithoutPage = full.copy(reader = ReaderSavedSession(7L, 70L, pageIndex = null))
        assertEquals(readerWithoutPage, ShellSavedState.decodeText(readerWithoutPage.encodeText()))
    }

    @Test
    fun aKeptStateIsDroppedOnceTheStartingScreenPreferenceChanged() {
        val kept = storedShellStateText(full, startingScreen = MainSection.LIBRARY)
        assertEquals(full, readStoredShellState(kept, MainSection.LIBRARY))
        assertNull(readStoredShellState(kept, MainSection.BROWSE))
        assertNull(readStoredShellState(full.encodeText(), MainSection.LIBRARY))
    }

    @Test
    fun aRestorationStopsOnceTheUserFollowedALink() {
        val calls = mutableListOf<String>()
        var settled: TitleOpenOutcome? = null
        val recording = object : ShellRestoreTarget {
            override fun selectSection(section: MainSection) { calls += "section" }
            override fun openMore(destination: MoreDestination) { calls += "more" }
            override fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit) { calls += "title" }
            override fun openReader(saved: ReaderSavedSession) { calls += "reader" }
            override fun restoreSelection(libraryIds: Set<Long>, categoryId: Long) { calls += "selection" }
            override fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>) { calls += "chapters" }
        }
        var linkFollowed = false
        val guarded = recording.until { linkFollowed }
        guarded.selectSection(MainSection.LIBRARY)
        linkFollowed = true
        guarded.openReader(ReaderSavedSession(7L, 70L, 1))
        guarded.restoreSelection(setOf(1L), 3L)
        guarded.openManga(7L) { settled = it }
        assertEquals(listOf("section"), calls)
        assertEquals(TitleOpenOutcome.ABANDONED, settled)
    }

    @Test
    fun aDamagedTextFormIsNotRead() {
        val text = full.encodeText()
        assertNull(ShellSavedState.decodeText(""))
        assertNull(ShellSavedState.decodeText(text.replace("|l7|", "|lseven|")))
        assertNull(ShellSavedState.decodeText(text.substringBeforeLast('|')))
        assertNull(ShellSavedState.decodeText(text.replace("|a1,2|", "|a1,x|")))
        assertNull(ShellSavedState.decodeText("x$text"))
    }

    @Test
    fun aStateSavedBeforeTheUpdateIsStillRead() {
        val first = full.copy(localExtensionUnit = null).encode().take(11).toMutableList<Any>().also { it[0] = 1L }
        assertEquals(full.copy(localExtensionUnit = null), ShellSavedState.decode(first))
    }

    @Test
    fun aLegacyFavoriteKeepsItsIdentityWhenItsUrlIsRewritten() {
        val source = SourceKey(packageId = "pkg.test", sourceId = "s")
        val key = extensionPublicationKey(source, "r1")
        val legacy = Manga(id = 5L, source = LOCAL_SOURCE_ID, url = encodeTypedLocalPublicationUrl(key), title = "T")
        val migrated = legacy.copy(url = dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl(source, "r1"))
        assertEquals(titleIdentity(legacy), titleIdentity(migrated))
        assertFalse(titleIdentity(legacy) == titleIdentity(legacy.copy(url = encodeTypedLocalPublicationUrl(extensionPublicationKey(source, "r2")))))
    }

    @Test
    fun aHugeSelectionIsLeftOutOfTheBundle() {
        val many = (1L..ShellSavedState.MAX_BUNDLED_SELECTION + 1L).toSet()
        val bounded = full.copy(libraryIds = many).withinBundleLimits()
        assertEquals(emptySet(), bounded.libraryIds)
        assertEquals(full.chapterIds, bounded.chapterIds)
        assertEquals(full, full.withinBundleLimits())
    }

    @Test
    fun aStateOfTheSecondFormatIsStillRead() {
        val second = full.encode().take(12).toMutableList<Any>().also { it[0] = 2L }
        assertEquals(full, ShellSavedState.decode(second))
    }

    @Test
    fun rowsThatReusedTheSavedIdsAreNotReopened() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val saved = ShellSavedState(
            MainSection.LIBRARY,
            detailMangaId = manga.id,
            reader = ReaderSavedSession(manga.id, chapter.id, 3),
            libraryIds = setOf(manga.id),
            chapterIds = setOf(chapter.id),
        ).withIdentitiesFrom(repository.currentSnapshot)
        val decoded = checkNotNull(ShellSavedState.decode(saved.encode()))
        assertEquals(saved, decoded)
        // Same rows: reopened.
        assertEquals(saved, decoded.validatedAgainst(repository.currentSnapshot))
        // A restored backup gave another title and chapter the same ids.
        val replaced = repository.currentSnapshot.let { snapshot ->
            snapshot.copy(
                mangas = snapshot.mangas.map { it.copy(url = "/other") },
                chapters = snapshot.chapters.map { it.copy(url = "/other-chapter") },
            )
        }
        val validated = decoded.validatedAgainst(replaced)
        assertNull(validated.detailMangaId)
        assertNull(validated.reader)
        // Nor are the selections, which bulk actions would apply to other rows.
        assertEquals(emptySet(), validated.libraryIds)
        assertEquals(emptySet(), validated.chapterIds)
        // Only the chapter replaced: the title still opens, the chapter does not.
        val chapterOnly = repository.currentSnapshot.let { it.copy(chapters = it.chapters.map { c -> c.copy(url = "/other-chapter") }) }
        assertEquals(manga.id, decoded.validatedAgainst(chapterOnly).detailMangaId)
        assertNull(decoded.validatedAgainst(chapterOnly).reader)
        assertEquals(setOf(manga.id), decoded.validatedAgainst(chapterOnly).libraryIds)
        assertEquals(emptySet(), decoded.validatedAgainst(chapterOnly).chapterIds)
    }

    @Test
    fun aLocalExtensionChapterIsSavedAsAnIdentityOnly() {
        val unitId = "https://source.example/novel/1/chapter/9"
        assertEquals(extensionUnitIdentity(unitId), extensionUnitIdentity(unitId))
        assertFalse(extensionUnitIdentity(unitId) == extensionUnitIdentity("$unitId/"))
        assertTrue(full.encode().none { it is String && "source.example" in it })
        val navigation = NavigationState(section = MainSection.LIBRARY)
        val selection = LibrarySelection()
        val unit = extensionUnitIdentity(unitId)
        assertEquals(unit, captureShellState(navigation, selection, null, SavedLocalExtension(8L, unit), incognito = false).localExtensionUnit)
        assertNull(captureShellState(navigation, selection, null, SavedLocalExtension(8L, unit), incognito = true).localExtensionUnit)
    }

    @Test
    fun encodingHoldsOnlyPlatformSaveableValues() {
        // Strings, longs and long arrays: what an Android Bundle and every other store accept.
        full.encode().forEach { value ->
            assertTrue(value is String || value is Long || value is LongArray, "unexpected $value")
        }
    }

    @Test
    fun anythingElseDecodesToNothing() {
        assertNull(ShellSavedState.decode(null))
        assertNull(ShellSavedState.decode("LIBRARY"))
        assertNull(ShellSavedState.decode(listOf(1L)))
        assertNull(ShellSavedState.decode(full.encode().toMutableList().also { it[0] = 99L }))
        assertNull(ShellSavedState.decode(full.encode().toMutableList().also { it[1] = "GONE" }))
        // An unknown More page is dropped rather than failing the whole state.
        assertNull(ShellSavedState.decode(full.encode().toMutableList().also { it[2] = "Gone" })?.moreDestination)
    }

    @Test
    fun validationDropsWhatLeftTheLibrary() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        // Unfavorited (on another device) after it was selected: its row stays, the library lost it.
        val unfavorited = repository.upsertManga(Manga(source = 1L, url = "/u", title = "U", favorite = false))
        val saved = ShellSavedState(
            section = MainSection.LIBRARY,
            detailMangaId = manga.id,
            localExtensionMangaId = 999L,
            reader = ReaderSavedSession(manga.id, chapter.id, 2),
            libraryIds = setOf(manga.id, unfavorited.id, 999L),
            chapterIds = setOf(chapter.id, 998L),
        )
        val valid = saved.validatedAgainst(repository.currentSnapshot)
        assertEquals(manga.id, valid.detailMangaId)
        assertNull(valid.localExtensionMangaId)
        assertEquals(saved.reader, valid.reader)
        assertEquals(setOf(manga.id), valid.libraryIds)
        assertEquals(setOf(chapter.id), valid.chapterIds)

        val gone = saved.copy(detailMangaId = 999L, reader = ReaderSavedSession(manga.id, 998L, 0))
            .validatedAgainst(repository.currentSnapshot)
        assertNull(gone.detailMangaId)
        assertNull(gone.reader)
        // Chapter marks belong to the detail page, which is gone.
        assertEquals(emptySet(), gone.chapterIds)
    }

    @Test
    fun captureKeepsIdentitiesOnlyAndNoReaderInIncognito() {
        val pending = BrowseManga(sourceId = 1L, url = "https://user:secret@example.test/m", title = "T")
        val navigation = NavigationState(
            section = MainSection.BROWSE,
            selectedMangaId = 5L,
            pendingBrowseManga = pending,
        )
        val selection = LibrarySelection(libraryIds = setOf(1L), categoryId = 4L)
        val reader = ReaderSavedSession(5L, 50L, 3)
        val captured = captureShellState(navigation, selection, reader, localExtension = null, incognito = false)
        assertEquals(
            ShellSavedState(MainSection.BROWSE, detailMangaId = 5L, reader = reader, libraryIds = setOf(1L), categoryId = 4L),
            captured,
        )
        // Nothing of the source title being resolved (its URL, headers) is kept.
        assertTrue(captured.encode().none { it is String && "example.test" in it })
        assertNull(captureShellState(navigation, selection, reader, null, incognito = true).reader)
    }

    @Test
    fun restorationGoesThroughTheEntryPointsInOrder() {
        val calls = mutableListOf<String>()
        val target = object : ShellRestoreTarget {
            override fun selectSection(section: MainSection) {
                calls += "section:$section"
            }

            override fun openMore(destination: MoreDestination) {
                calls += "more:$destination"
            }

            override fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit) {
                calls += "manga:$mangaId"
                onSettled(TitleOpenOutcome.DETAIL)
            }

            override fun openReader(saved: ReaderSavedSession) {
                calls += "reader:${saved.chapterId}"
            }

            override fun restoreSelection(libraryIds: Set<Long>, categoryId: Long) {
                calls += "selection:$libraryIds:$categoryId"
            }

            override fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>) {
                calls += "chapters:$mangaId:$chapterIds"
            }
        }
        restoreShellState(full.copy(moreDestination = null), target) { calls += "finished" }
        // The local-library extension page wins over a detail page (only one can be open).
        assertEquals(
            listOf("section:LIBRARY", "manga:8", "chapters:8:[70]", "reader:70", "selection:[1, 2]:3", "finished"),
            calls,
        )
    }

    @Test
    fun theExtensionChapterIsReopenedOnceItsTitleOpened() {
        val calls = mutableListOf<String>()
        val target = object : ShellRestoreTarget {
            override fun selectSection(section: MainSection) = Unit

            override fun openMore(destination: MoreDestination) = Unit

            override fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit) {
                calls += "manga:$mangaId"
                onSettled(TitleOpenOutcome.ELSEWHERE)
            }

            override fun openReader(saved: ReaderSavedSession) = Unit

            override fun reopenExtensionUnit(mangaId: Long, identity: Long) {
                calls += "unit:$mangaId:$identity"
            }

            override fun restoreSelection(libraryIds: Set<Long>, categoryId: Long) = Unit

            override fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>) = Unit
        }
        val saved = ShellSavedState(MainSection.LIBRARY, localExtensionMangaId = 8L, localExtensionUnit = 99L)
        restoreShellState(saved, target)
        assertEquals(listOf("manga:8", "unit:8:99"), calls)
    }

    private class ShellFixture(scope: TestScope, val repository: ShinsouRepository) {
        val app = ShinsouAppState(
            repository = repository,
            appServices = ShinsouAppServices.None,
            autoBackupService = null,
            environment = HolderEnvironment(
                scope = scope,
                messages = AppMessages({ shinsouStringsFor("en") }) {},
                workDispatcher = StandardTestDispatcher(scope.testScheduler),
            ),
            start = ShellStart(MainSection.LIBRARY, appLocked = false, downloadsPaused = false, dismissMobileInput = {}),
        )
    }

    @Test
    fun anIncognitoChapterOpenedFromAnotherPageDoesNotSaveItsTitle() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        repository.updateSettings { it.copy(security = it.security.copy(incognitoMode = true)) }
        val app = ShellFixture(this, repository).app
        app.selectSection(MainSection.UPDATES)
        app.openReader(manga.id, chapter.id)
        val saved = app.savedState()
        assertNull(saved.reader)
        assertNull(saved.detailMangaId)
        // A detail page the user opened is kept (only the chapter is not).
        val opened = ShellFixture(this, repository).app
        opened.openManga(manga.id)
        opened.openReader(manga.id, chapter.id)
        assertEquals(manga.id, opened.savedState().detailMangaId)
    }

    @Test
    fun aChapterResumedFromHistoryIsSavedWithoutOpeningItsTitle() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val app = ShellFixture(this, repository).app
        app.selectSection(MainSection.HISTORY)
        app.openReader(manga.id, chapter.id)
        val saved = app.savedState()
        assertEquals(MainSection.HISTORY, saved.section)
        assertNull(saved.detailMangaId)
        assertEquals(chapter.id, saved.reader?.chapterId)
    }

    @Test
    fun aFollowedTitleLinkClosesTheRestoredReaderAndStopsTheRestoration() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val other = repository.upsertManga(Manga(source = 1L, url = "/o", title = "O", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val app = ShellFixture(this, repository).app
        app.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id, reader = ReaderSavedSession(manga.id, chapter.id, 2)))
        advanceUntilIdle()
        assertEquals(chapter.id, app.reader.state.value.session?.chapterId)
        // The reader's progress writer, which the shell's effects run.
        // (Not in backgroundScope: advanceUntilIdle does not wait for what runs there.)
        val progressWriter = launch { app.localProgress.run() }
        app.handleDeepLink(ShinsouDeepLink.OpenManga(other.id))
        advanceUntilIdle()
        assertNull(app.reader.state.value.session)
        assertEquals(other.id, app.navigator.state.value.selectedMangaId)
        // A restoration that had not run yet is given up: the link decided where the app is.
        app.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id))
        advanceUntilIdle()
        assertEquals(other.id, app.navigator.state.value.selectedMangaId)
        progressWriter.cancel()
    }

    @Test
    fun aPendingRestorationIsSavedWithoutItsChaptersInIncognitoMode() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val saved = ShellSavedState(
            MainSection.LIBRARY,
            detailMangaId = manga.id,
            localExtensionMangaId = manga.id,
            localExtensionUnit = 5L,
            reader = ReaderSavedSession(manga.id, chapter.id, 2),
        )
        val app = ShellFixture(this, repository).app
        assertEquals(saved, app.withoutReadingInIncognito(saved))
        repository.updateSettings { it.copy(security = it.security.copy(incognitoMode = true)) }
        val kept = app.withoutReadingInIncognito(saved)
        assertNull(kept.reader)
        assertNull(kept.localExtensionUnit)
        assertEquals(manga.id, kept.detailMangaId)
    }

    @Test
    fun noChapterIsReopenedWhileIncognitoModeIsOn() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        // Saved before incognito mode was turned on.
        val saved = ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id, reader = ReaderSavedSession(manga.id, chapter.id, 2))
        repository.updateSettings { it.copy(security = it.security.copy(incognitoMode = true)) }
        val app = ShellFixture(this, repository).app
        app.restore(saved)
        advanceUntilIdle()
        assertNull(app.reader.state.value.session)
        assertEquals(manga.id, app.navigator.state.value.selectedMangaId)
    }

    @Test
    fun aRepositoryLinkEmittedAgainLeavesTheReaderOpen() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val app = ShellFixture(this, repository).app
        val progressWriter = launch { app.localProgress.run() }
        val link = ShinsouDeepLink.AddRepository("https://repo.example/index.json", fingerprint = null)
        app.handleDeepLink(link)
        advanceUntilIdle()
        // Still asking about it; meanwhile the user reads a chapter, and the queue emits it again.
        app.openReader(manga.id, chapter.id)
        app.handleDeepLink(link)
        advanceUntilIdle()
        assertEquals(chapter.id, app.reader.state.value.session?.chapterId)
        progressWriter.cancel()
    }

    @Test
    fun aSettingsLinkIsShownRatherThanHiddenUnderTheRestoredReader() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val app = ShellFixture(this, repository).app
        val progressWriter = launch { app.localProgress.run() }
        app.restore(ShellSavedState(MainSection.LIBRARY, reader = ReaderSavedSession(manga.id, chapter.id, 2)))
        advanceUntilIdle()
        assertEquals(chapter.id, app.reader.state.value.session?.chapterId)
        app.handleDeepLink(ShinsouDeepLink.OpenSettings)
        advanceUntilIdle()
        assertNull(app.reader.state.value.session)
        assertEquals(MoreDestination.Settings, app.navigator.state.value.moreDestination)
        progressWriter.cancel()
    }

    @Test
    fun aRestorationTheUserMovedAwayFromIsAbandonedAtOnce() = runTest {
        val repository = ShinsouRepository()
        val typed = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "t1")),
                title = "Typed",
                favorite = true,
            ),
        )
        val app = ShellFixture(this, repository).app
        // The typed title's binding is read off the UI thread: the restoration is still under way.
        app.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = typed.id))
        assertFalse(app.restoreAbandoned)
        app.selectSection(MainSection.UPDATES)
        assertTrue(app.restoreAbandoned)
        advanceUntilIdle()
        assertFalse(app.restoreAbandoned)
    }

    @Test
    fun theShellComesBackAfterProcessDeath() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val before = ShellFixture(this, repository).app
        before.selection.selectCategory(ALL_LIBRARY_CATEGORY_ID)
        before.openManga(manga.id)
        before.selection.selectChapters(setOf(chapter.id))
        before.openReader(manga.id, chapter.id)
        val session = checkNotNull(before.reader.state.value.session)
        before.reader.markPositionInitialized(session)
        before.reader.recordPagePosition(session, chapter, pageIndex = 6, pageCount = 10, incognito = false)

        // The platform keeps only the encoded form; the process and every holder are made anew.
        val saved = before.savedState().encode()
        val after = ShellFixture(this, repository).app
        after.restore(checkNotNull(ShellSavedState.decode(saved)))
        advanceUntilIdle()

        assertEquals(manga.id, after.navigator.state.value.selectedMangaId)
        assertEquals(setOf(chapter.id), after.selection.state.value.chapterIds)
        val restored = after.reader.state.value
        assertEquals(manga.id, restored.session?.mangaId)
        assertEquals(chapter.id, restored.session?.chapterId)
        assertEquals(6, restored.restoredPageIndex)
    }

    @Test
    fun chapterSelectionsOfATypedLocalTitleComeBackAfterItsAsynchronousOpen() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val saved = ShellSavedState(
            section = MainSection.LIBRARY,
            detailMangaId = manga.id,
            chapterIds = setOf(chapter.id),
        )
        val app = ShellFixture(this, repository).app
        app.restore(saved)
        advanceUntilIdle()
        assertEquals(manga.id, app.navigator.state.value.selectedMangaId)
        assertEquals(setOf(chapter.id), app.selection.state.value.chapterIds)
    }

    @Test
    fun aTypedLocalTitleItsChapterSelectionsAndTheReaderAllComeBack() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val saved = ShellSavedState(
            section = MainSection.LIBRARY,
            detailMangaId = manga.id,
            reader = ReaderSavedSession(manga.id, chapter.id, pageIndex = 2),
            chapterIds = setOf(chapter.id),
        )
        val app = ShellFixture(this, repository).app
        app.restore(saved)
        advanceUntilIdle()
        assertEquals(manga.id, app.navigator.state.value.selectedMangaId)
        assertEquals(setOf(chapter.id), app.selection.state.value.chapterIds)
        assertEquals(chapter.id, app.reader.state.value.session?.chapterId)
        assertEquals(2, app.reader.state.value.restoredPageIndex)
    }

    @Test
    fun aLibrarySelectionMadeWhileATypedTitleReopensIsKept() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        // Chapters on the device: the title opens on its detail page once its binding is read.
        repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val other = repository.upsertManga(Manga(source = 1L, url = "/o", title = "O", favorite = true))
        val app = ShellFixture(this, repository).app
        app.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id, libraryIds = setOf(manga.id), categoryId = 7L))
        // The library stays usable while the binding is read: the user picks another title.
        app.selection.selectLibraryTitles(setOf(other.id))
        advanceUntilIdle()
        assertEquals(setOf(other.id), app.selection.state.value.libraryIds)
        assertEquals(ALL_LIBRARY_CATEGORY_ID, app.selection.state.value.categoryId)
        // Untouched meanwhile, the saved selection comes back.
        val untouched = ShellFixture(this, repository).app
        untouched.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id, libraryIds = setOf(manga.id), categoryId = 7L))
        advanceUntilIdle()
        assertEquals(setOf(manga.id), untouched.selection.state.value.libraryIds)
        assertEquals(7L, untouched.selection.state.value.categoryId)
    }

    @Test
    fun anExtensionTitleThatLeftTheLibraryIsNotReopenedButOneFromHistoryIs() = runTest {
        val repository = ShinsouRepository()
        val favorite = repository.upsertManga(Manga(source = 1L, url = "/f", title = "F", favorite = true))
        val fromHistory = repository.upsertManga(Manga(source = 1L, url = "/h", title = "H", favorite = false))
        val saved = ShellSavedState(MainSection.LIBRARY, localExtensionMangaId = favorite.id, localExtensionUnit = 5L)
            .withIdentitiesFrom(repository.currentSnapshot)
        val savedFromHistory = ShellSavedState(MainSection.LIBRARY, localExtensionMangaId = fromHistory.id)
            .withIdentitiesFrom(repository.currentSnapshot)
        assertEquals(favorite.id, saved.validatedAgainst(repository.currentSnapshot).localExtensionMangaId)
        // Unfavorited by sync before the restoration: neither the title nor its chapter reopen.
        repository.updateManga(favorite.id) { it.copy(favorite = false) }
        val dropped = saved.validatedAgainst(repository.currentSnapshot)
        assertNull(dropped.localExtensionMangaId)
        assertNull(dropped.localExtensionUnit)
        // Never a favorite (opened from History): still reopened, and after being added as well.
        assertEquals(fromHistory.id, savedFromHistory.validatedAgainst(repository.currentSnapshot).localExtensionMangaId)
        repository.updateManga(fromHistory.id) { it.copy(favorite = true) }
        assertEquals(fromHistory.id, savedFromHistory.validatedAgainst(repository.currentSnapshot).localExtensionMangaId)
    }

    @Test
    fun tappingTheCurrentTabGivesUpATitleStillOpening() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val app = ShellFixture(this, repository).app
        app.openManga(manga.id)
        // The Library tab again: the section and page compare equal, the open still goes.
        app.selectSection(MainSection.LIBRARY)
        advanceUntilIdle()
        assertNull(app.navigator.state.value.selectedMangaId)
    }

    @Test
    fun aChapterOpenedGivesUpTheExtensionChapterARestorationWaitsFor() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val extension = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(SourceKey(packageId = "pkg.test", sourceId = "s"), "r1"),
                title = "Novel",
                favorite = true,
            ),
        )
        val app = ShellFixture(this, repository).app
        val progressWriter = launch { app.localProgress.run() }
        // Restored: the extension title's page opens and waits to reopen its chapter.
        app.restore(ShellSavedState(MainSection.LIBRARY, localExtensionMangaId = extension.id, localExtensionUnit = 5L))
        advanceUntilIdle()
        assertEquals(5L, app.pendingLocalExtensionUnit?.unit)
        // A chapter link (or a tap elsewhere) opens another chapter before that one reopened.
        app.openReader(manga.id, chapter.id)
        assertNull(app.pendingLocalExtensionUnit)
        progressWriter.cancel()
    }

    @Test
    fun continueReadingAsksForTheChapterOnceItsTitleOpened() = runTest {
        val repository = ShinsouRepository()
        val typed = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        repository.upsertChapter(Chapter(mangaId = typed.id, url = "/typed/1", name = "1"))
        val app = ShellFixture(this, repository).app
        // This title opens after a lookup off the UI thread: the chapter is asked for only then.
        app.continueExtensionUnit(typed.id, "u1")
        assertNull(app.pendingLocalExtensionUnit)
        // Given up before it opened: no chapter is asked for either.
        app.selectSection(MainSection.LIBRARY)
        advanceUntilIdle()
        assertNull(app.pendingLocalExtensionUnit)
    }

    @Test
    fun chapterSelectionsAreDroppedWhenTheUserMovedOnBeforeTheOpenFinished() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val app = ShellFixture(this, repository).app
        app.restore(ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id, chapterIds = setOf(chapter.id)))
        app.navigator.selectSection(MainSection.HISTORY)
        advanceUntilIdle()
        assertNull(app.navigator.state.value.selectedMangaId)
        assertEquals(emptySet(), app.selection.state.value.chapterIds)
    }

    @Test
    fun theRestorationIsOverOnlyOnceATypedTitleOpenSettles() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                url = encodeTypedLocalPublicationUrl(
                    extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1"),
                ),
                title = "Typed",
                favorite = true,
            ),
        )
        repository.upsertChapter(Chapter(mangaId = manga.id, url = "/typed/1", name = "1"))
        val saved = ShellSavedState(MainSection.LIBRARY, detailMangaId = manga.id)

        val opened = ShellFixture(this, repository).app
        var openedFinished = false
        opened.restore(saved) { openedFinished = true }
        assertFalse(openedFinished, "not over while the title is being routed")
        advanceUntilIdle()
        assertTrue(openedFinished)

        val abandoned = ShellFixture(this, repository).app
        var abandonedFinished = false
        abandoned.restore(saved) { abandonedFinished = true }
        abandoned.navigator.selectSection(MainSection.HISTORY)
        advanceUntilIdle()
        assertTrue(abandonedFinished, "giving up is the end of the restoration too")

        val superseded = ShellFixture(this, repository).app
        var supersededFinished = false
        superseded.restore(saved) { supersededFinished = true }
        superseded.openManga(manga.id + 1000)
        advanceUntilIdle()
        assertTrue(supersededFinished, "a newer open ends the restoration")
    }

    @Test
    fun aTitleRemovedMeanwhileIsNotReopened() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1L, url = "/m", title = "M", favorite = true))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "1"))
        val saved = ShellSavedState(
            section = MainSection.LIBRARY,
            detailMangaId = manga.id + 100,
            reader = ReaderSavedSession(manga.id, chapter.id + 100, 3),
        )
        val app = ShellFixture(this, repository).app
        app.restore(saved)
        advanceUntilIdle()
        assertNull(app.navigator.state.value.selectedMangaId)
        assertNull(app.reader.state.value.session)
        assertEquals(MainSection.LIBRARY, app.navigator.state.value.section)
    }
}
