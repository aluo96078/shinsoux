package dev.shinsou.kmp.sync.v3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SyncApiEmptyResponseTest {
    private fun api(status: HttpStatusCode, body: String = "") =
        SyncApi(HttpClient(MockEngine { respond(body, status) }))

    @Test
    fun aBodyEndpointAnsweringNoContentFailsAsAClassifiedSyncError() = runBlocking {
        val error = assertFailsWith<SyncApiException> {
            api(HttpStatusCode.NoContent).changes("http://sync.test", "token", since = 0)
        }
        assertEquals(204, error.status)
        assertEquals("empty_response", error.code)
        // A 2xx without a body will not fix itself on retry, and it is not an auth or resync case.
        assertFalse(error.isTransient)
        assertFalse(error.isUnauthorized)
        assertFalse(error.isResyncRequired)
    }

    @Test
    fun aBlankOkBodyIsAlsoAnEmptyResponse() = runBlocking {
        val error = assertFailsWith<SyncApiException> {
            api(HttpStatusCode.OK, "  ").instance("http://sync.test")
        }
        assertEquals("empty_response", error.code)
    }

    @Test
    fun endpointsWithoutABodyAcceptNoContent() = runBlocking {
        api(HttpStatusCode.NoContent).logout("http://sync.test", "token")
        api(HttpStatusCode.NoContent).deleteShare("http://sync.test", "token", "share-1")
    }
}
