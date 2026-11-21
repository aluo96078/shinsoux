package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerIds
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class MyAnimeListTrackerTest {
    @Test
    fun configurationRejectsBlankClientId() {
        assertFailsWith<IllegalArgumentException> { MyAnimeListTrackerConfig("") }
    }

    @Test
    fun searchUsesInjectedClientIdAndMapsRestResponse() = runTest {
        val engine = MockEngine { request ->
            assertEquals("real-configured-id", request.headers["X-MAL-CLIENT-ID"])
            assertEquals("Blue", request.url.parameters["q"])
            respond(
                content = """{"data":[{"node":{"id":77,"title":"Blue","main_picture":{"large":"https://img"},"start_date":"2020-03-04","synopsis":"Summary","media_type":"manga","status":"finished","num_chapters":20}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tracker = MyAnimeListTracker(
            HttpClient(engine),
            InMemoryTokenStore(),
            MyAnimeListTrackerConfig(clientId = "real-configured-id"),
        )

        val result = tracker.search("Blue")

        assertEquals(77L, result.single().id)
        assertEquals(20, result.single().totalChapters)
        assertEquals("https://img", result.single().coverUrl)
    }

    @Test
    fun updateUsesBearerTokenAndPatchStatusFields() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("Bearer mal-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"status":"completed","score":9,"num_chapters_read":20,"is_rereading":false,"start_date":"2024-01-01","finish_date":"2024-02-01"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val store = InMemoryTokenStore(mapOf(trackerTokenKey(TrackerIds.MY_ANIME_LIST) to OAuthToken("mal-token")))
        val tracker = MyAnimeListTracker(
            HttpClient(engine),
            store,
            MyAnimeListTrackerConfig(clientId = "configured-id"),
        )

        val updated = tracker.update(
            Track(mangaId = 1, trackerId = TrackerIds.MY_ANIME_LIST, remoteId = 77, title = "Blue"),
            TrackUpdate(progress = 20.0, status = TrackStatus.COMPLETED, score = 9.0),
        )

        assertEquals(20.0, updated.lastChapterRead)
        assertEquals(TrackStatus.COMPLETED.rawValue, updated.status)
        assertEquals(9.0, updated.score)
    }
}
