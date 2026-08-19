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
    @Test
    fun orchestratesAuthenticationSearchBindUpdateRemoveAndLogout() = runTest {
        FakeAuthenticator.authenticated = false
        val gateway = FakeGateway()
        val authenticator = FakeAuthenticator()
        val coordinator = TrackingCoordinator(
            manager = gateway,
            providers = listOf(
                TrackingProvider(TrackerDescriptor(TrackerIds.ANI_LIST, "AniList")),
                TrackingProvider(
                    TrackerDescriptor(TrackerIds.MY_ANIME_LIST, "MyAnimeList"),
                    configured = false,
                    configurationMessage = "Client ID missing",
                ),
            ),
            authenticators = listOf(authenticator),
        )

        assertFalse(coordinator.isAuthenticated(TrackerIds.ANI_LIST))
        assertEquals("https://example.test/auth", coordinator.authorizationUrl(TrackerIds.ANI_LIST))
        assertFailsWith<TrackerAuthenticationException> {
            coordinator.search(TrackerIds.ANI_LIST, "Blue")
        }

        val token = coordinator.completeAuthentication(TrackerIds.ANI_LIST, "pasted", 10_000)
        assertEquals("pasted", token.accessToken)
        assertTrue(coordinator.isAuthenticated(TrackerIds.ANI_LIST))
        assertEquals(1, gateway.authenticationRefreshes)

        val result = coordinator.search(TrackerIds.ANI_LIST, "Blue").single()
        val bound = coordinator.bind(7, TrackerIds.ANI_LIST, result)
        assertEquals(42L, bound.remoteId)
        coordinator.update(7, TrackerIds.ANI_LIST, TrackUpdate(progress = 3.0))
        coordinator.remove(7, TrackerIds.ANI_LIST)
        assertEquals(7L to TrackerIds.ANI_LIST, gateway.removed)

        coordinator.logout(TrackerIds.ANI_LIST)
        assertFalse(coordinator.isAuthenticated(TrackerIds.ANI_LIST))
        assertEquals(2, gateway.authenticationRefreshes)
        assertFailsWith<IllegalStateException> {
            coordinator.authorizationUrl(TrackerIds.MY_ANIME_LIST)
        }
    }

    private class FakeAuthenticator : TrackerAuthenticator {
        override val trackerId: Int = TrackerIds.ANI_LIST
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
        override val descriptors = listOf(TrackerDescriptor(TrackerIds.ANI_LIST, "AniList"))
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
