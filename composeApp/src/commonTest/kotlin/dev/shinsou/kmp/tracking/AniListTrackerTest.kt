package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerIds
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AniListTrackerTest {
    @Test
    fun searchUsesGraphQlAndMapsMedia() = runTest {
        val engine = MockEngine { request ->
            assertEquals("graphql.anilist.co", request.url.host)
            respond(
                content = """{"data":{"Page":{"media":[{"id":42,"title":{"userPreferred":"Blue"},"chapters":12,"coverImage":{"large":"https://img"},"description":"Summary","status":"FINISHED","format":"MANGA","startDate":{"year":2024,"month":2,"day":3},"siteUrl":"https://anilist.co/manga/42"}]}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tracker = AniListTracker(HttpClient(engine), InMemoryTokenStore(), AniListTrackerConfig("client"))

        val result = tracker.search("Blue")

        assertEquals(1, result.size)
        assertEquals(42L, result.single().id)
        assertEquals("Blue", result.single().title)
        assertEquals(12, result.single().totalChapters)
        assertEquals("2024-02-03", result.single().startDate)
    }

    @Test
    fun updateUsesInjectedTokenAndMapsCanonicalEntry() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"data":{"SaveMediaListEntry":{"id":9,"mediaId":42,"status":"CURRENT","progress":7,"score":8.5,"startedAt":{"year":2024,"month":1,"day":2},"completedAt":null,"media":{"id":42,"title":{"userPreferred":"Blue"},"chapters":12,"siteUrl":"https://anilist.co/manga/42"}}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val store = InMemoryTokenStore(mapOf(trackerTokenKey(TrackerIds.ANI_LIST) to OAuthToken("secret")))
        val tracker = AniListTracker(HttpClient(engine), store, AniListTrackerConfig("client"))

        val updated = tracker.update(
            Track(mangaId = 1, trackerId = TrackerIds.ANI_LIST, remoteId = 42, title = "Old"),
            TrackUpdate(progress = 7.0, status = TrackStatus.READING, score = 8.5),
        )

        assertEquals("Blue", updated.title)
        assertEquals(7.0, updated.lastChapterRead)
        assertEquals(TrackStatus.READING.rawValue, updated.status)
        assertEquals(8.5, updated.score)
        assertTrue(updated.startDate > 0)
    }
}
