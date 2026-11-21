package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TrackerManagerAdapterTest {
    @Test
    fun delegatesAuthenticationBindingImmediateUpdatesAndLocalRemoval() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, url = "/manga", title = "Local title"))
        val tracker = FakeTrackerAdapter()
        val manager = TrackerManager(listOf(tracker), repository, backgroundScope)
        val adapter = TrackerManagerAdapter(manager, repository)

        assertTrue(adapter.isAuthenticated(TrackerIds.MY_ANIME_LIST))
        assertTrue(adapter.refreshAuthenticationState(TrackerIds.MY_ANIME_LIST).loggedIn)
        val result = adapter.search(TrackerIds.MY_ANIME_LIST, "remote").single()
        val bound = adapter.bind(manga.id, TrackerIds.MY_ANIME_LIST, result)
        assertEquals(99L, bound.remoteId)
        assertEquals(bound, repository.tracksForManga(manga.id).single())

        val updated = adapter.update(
            manga.id,
            TrackerIds.MY_ANIME_LIST,
            TrackUpdate(progress = 4.0, status = TrackStatus.READING, score = 8.5),
        )
        assertEquals(4.0, updated.lastChapterRead)
        assertEquals(TrackStatus.READING.rawValue, updated.status)
        assertEquals(8.5, repository.tracksForManga(manga.id).single().score)

        adapter.remove(manga.id, TrackerIds.MY_ANIME_LIST)
        assertTrue(repository.tracksForManga(manga.id).isEmpty())
    }

    private class FakeTrackerAdapter : TrackerAdapter {
        override val descriptor = TrackerDescriptor(TrackerIds.MY_ANIME_LIST, "MyAnimeList")

        override suspend fun isAuthenticated(): Boolean = true

        override suspend fun search(query: String, limit: Int): List<TrackSearch> =
            listOf(TrackSearch(id = 99, title = "Remote $query", totalChapters = 12))

        override suspend fun bind(mangaId: Long, remote: TrackSearch): Track = Track(
            mangaId = mangaId,
            trackerId = descriptor.id,
            remoteId = remote.id,
            title = remote.title,
            totalChapters = remote.totalChapters,
            status = TrackStatus.PLAN_TO_READ.rawValue,
        )

        override suspend fun refresh(track: Track): Track = track

        override suspend fun update(track: Track, update: TrackUpdate): Track = update.applyTo(track)
    }
}
