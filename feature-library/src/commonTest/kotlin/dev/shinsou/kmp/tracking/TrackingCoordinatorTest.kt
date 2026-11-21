package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackerAccountState
import dev.shinsou.kmp.domain.model.TrackerIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TrackingCoordinatorTest {
    private companion object {
        const val TEST_TRACKER_ID = 42
    }

    @Test
    fun orchestratesAuthenticationSearchBindUpdateRemoveAndLogout() = runTest {
        FakeAuthenticator.authenticated = false
        val gateway = FakeGateway()
        val authenticator = FakeAuthenticator()
        val coordinator = TrackingCoordinator(
            manager = gateway,
            providers = listOf(
                TrackingProvider(TrackerDescriptor(TEST_TRACKER_ID, "Test tracker")),
                TrackingProvider(
                    TrackerDescriptor(TrackerIds.MY_ANIME_LIST, "MyAnimeList"),
                    configured = false,
                    configurationMessage = "Client ID missing",
                ),
            ),
            authenticators = listOf(authenticator),
        )

        assertFalse(coordinator.isAuthenticated(TEST_TRACKER_ID))
        assertEquals("https://example.test/auth", coordinator.authorizationUrl(TEST_TRACKER_ID))
        assertFailsWith<TrackerAuthenticationException> {
            coordinator.search(TEST_TRACKER_ID, "Blue")
        }

        val token = coordinator.completeAuthentication(TEST_TRACKER_ID, "pasted", 10_000)
        assertEquals("pasted", token.accessToken)
        assertTrue(coordinator.isAuthenticated(TEST_TRACKER_ID))
        assertEquals(1, gateway.authenticationRefreshes)

        val result = coordinator.search(TEST_TRACKER_ID, "Blue").single()
        val bound = coordinator.bind(7, TEST_TRACKER_ID, result)
        assertEquals(42L, bound.remoteId)
        coordinator.update(7, TEST_TRACKER_ID, TrackUpdate(progress = 3.0))
        coordinator.remove(7, TEST_TRACKER_ID)
        assertEquals(7L to TEST_TRACKER_ID, gateway.removed)

        coordinator.logout(TEST_TRACKER_ID)
        assertFalse(coordinator.isAuthenticated(TEST_TRACKER_ID))
        assertEquals(2, gateway.authenticationRefreshes)
        assertFailsWith<IllegalStateException> {
            coordinator.authorizationUrl(TrackerIds.MY_ANIME_LIST)
        }
    }

    private class FakeAuthenticator : TrackerAuthenticator {
        override val trackerId: Int = TEST_TRACKER_ID
        override fun authorizationUrl(): String = "https://example.test/auth"

        override suspend fun complete(pastedCallbackOrToken: String, nowEpochMillis: Long): OAuthToken =
            OAuthToken(pastedCallbackOrToken).also { authenticated = true }

        override suspend fun logout() {
            authenticated = false
        }

        companion object {
            var authenticated: Boolean = false
        }
    }

    private class FakeGateway : TrackingManagerGateway {
        override val descriptors = listOf(TrackerDescriptor(TEST_TRACKER_ID, "Test tracker"))
        var authenticationRefreshes = 0
        var removed: Pair<Long, Int>? = null

        override suspend fun isAuthenticated(trackerId: Int): Boolean = FakeAuthenticator.authenticated

        override suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState {
            authenticationRefreshes++
            return TrackerAccountState(trackerId, loggedIn = isAuthenticated(trackerId))
        }

        override suspend fun search(trackerId: Int, query: String, limit: Int): List<TrackSearch> =
            listOf(TrackSearch(42, query))

        override suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track =
            Track(mangaId = mangaId, trackerId = trackerId, remoteId = remote.id, title = remote.title)

        override suspend fun refresh(mangaId: Long, trackerId: Int): Track =
            Track(mangaId = mangaId, trackerId = trackerId, remoteId = 42)

        override suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track =
            update.applyTo(Track(mangaId = mangaId, trackerId = trackerId, remoteId = 42))

        override suspend fun remove(mangaId: Long, trackerId: Int) {
            removed = mangaId to trackerId
        }
    }
}
