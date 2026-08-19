package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackerIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerManagerTest {
    @Test
    fun queueCoalescesUpdatesAndDispatchesAfterThreeSecondDebounce() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, url = "/m", title = "M"))
        repository.upsertTrack(Track(mangaId = manga.id, trackerId = TrackerIds.ANI_LIST, remoteId = 10))
        val adapter = RecordingTrackerAdapter()
        val manager = TrackerManager(listOf(adapter), repository, backgroundScope)

        manager.updateProgress(manga.id, TrackerIds.ANI_LIST, 4.0)
        manager.updateScore(manga.id, TrackerIds.ANI_LIST, 8.5)
        assertEquals(1, manager.pendingUpdateCount())

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, adapter.updates.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, adapter.updates.size)
        assertEquals(4.0, adapter.updates.single().progress)
        assertEquals(8.5, adapter.updates.single().score)
        assertEquals(4.0, repository.tracksForManga(manga.id).single().lastChapterRead)
        assertEquals(8.5, repository.tracksForManga(manga.id).single().score)
    }

    private class RecordingTrackerAdapter : TrackerAdapter {
        override val descriptor = TrackerDescriptor(TrackerIds.ANI_LIST, "Fake")
        val updates = mutableListOf<TrackUpdate>()

        override suspend fun isAuthenticated(): Boolean = true
        override suspend fun search(query: String, limit: Int): List<TrackSearch> = emptyList()
        override suspend fun bind(mangaId: Long, remote: TrackSearch): Track =
            Track(mangaId = mangaId, trackerId = descriptor.id, remoteId = remote.id, title = remote.title)
        override suspend fun refresh(track: Track): Track = track
        override suspend fun update(track: Track, update: TrackUpdate): Track {
            updates += update
            return update.applyTo(track)
        }
    }
}
