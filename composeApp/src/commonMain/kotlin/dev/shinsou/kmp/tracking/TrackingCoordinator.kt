package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackerAccountState
import kotlinx.serialization.Serializable

@Serializable
data class TrackingProvider(
    val descriptor: TrackerDescriptor,
    val configured: Boolean = true,
    val configurationMessage: String? = null,
)

/** Small seam that keeps the UI-facing coordinator independently testable. */
interface TrackingManagerGateway {
    val descriptors: List<TrackerDescriptor>

    suspend fun isAuthenticated(trackerId: Int): Boolean
    suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState
    suspend fun search(trackerId: Int, query: String, limit: Int = 20): List<TrackSearch>
    suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track
    suspend fun refresh(mangaId: Long, trackerId: Int): Track
    suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track
    suspend fun remove(mangaId: Long, trackerId: Int)
}

/** Adapts the existing queue-aware [TrackerManager] and repository to coordinator operations. */
class TrackerManagerAdapter(
    private val manager: TrackerManager,
    private val repository: ShinsouRepository,
) : TrackingManagerGateway {
    override val descriptors: List<TrackerDescriptor> = manager.descriptors

    override suspend fun isAuthenticated(trackerId: Int): Boolean = manager.adapter(trackerId).isAuthenticated()

    override suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState =
        manager.refreshAuthenticationState(trackerId)

    override suspend fun search(trackerId: Int, query: String, limit: Int): List<TrackSearch> =
        manager.search(trackerId, query, limit)

    override suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track =
        manager.bind(mangaId, trackerId, remote)

    override suspend fun refresh(mangaId: Long, trackerId: Int): Track = manager.refresh(mangaId, trackerId)

    override suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track =
        manager.updateNow(mangaId, trackerId, update)

    override suspend fun remove(mangaId: Long, trackerId: Int) = repository.deleteTrack(mangaId, trackerId)
}

interface TrackerAuthenticator {
    val trackerId: Int
    fun authorizationUrl(): String
    suspend fun complete(pastedCallbackOrToken: String, nowEpochMillis: Long): OAuthToken
    suspend fun logout()
}

class AniListAuthenticator(
    private val tracker: AniListTracker,
) : TrackerAuthenticator {
    override val trackerId: Int = tracker.descriptor.id

    override fun authorizationUrl(): String = tracker.authorizationUrl()

    override suspend fun complete(pastedCallbackOrToken: String, nowEpochMillis: Long): OAuthToken {
        val token = AniListOAuthCallbackParser.parse(pastedCallbackOrToken, nowEpochMillis)
        tracker.storeToken(token)
        return token
    }

    override suspend fun logout() = tracker.logout()
}

/**
 * UI-independent tracking use cases shared by Android, iOS and Desktop.
 *
 * Configured providers are backed by [TrackerManager]; unavailable providers remain visible so
 * clients can explain missing app credentials instead of presenting a login flow that cannot work.
 */
class TrackingCoordinator(
    private val manager: TrackingManagerGateway,
    providers: List<TrackingProvider>,
    authenticators: List<TrackerAuthenticator>,
) {
    val providers: List<TrackingProvider> = providers.toList()
    private val providersById = this.providers.associateBy { it.descriptor.id }
    private val authenticatorsById = authenticators.associateBy(TrackerAuthenticator::trackerId)

    init {
        require(providersById.size == providers.size) { "Tracking provider IDs must be unique" }
        require(authenticatorsById.size == authenticators.size) { "Tracker authenticator IDs must be unique" }
        val registeredIds = manager.descriptors.mapTo(mutableSetOf()) { it.id }
        val missing = this.providers.filter { it.configured && it.descriptor.id !in registeredIds }
        require(missing.isEmpty()) {
            "Configured tracking providers are not registered: ${missing.joinToString { it.descriptor.name }}"
        }
        require(authenticatorsById.keys.all { it in registeredIds }) {
            "Every tracker authenticator must have a registered adapter"
        }
    }

    fun authorizationUrl(trackerId: Int): String? {
        requireConfigured(trackerId)
        return authenticatorsById[trackerId]?.authorizationUrl()
    }

    suspend fun isAuthenticated(trackerId: Int): Boolean {
        if (!provider(trackerId).configured) return false
        return manager.isAuthenticated(trackerId)
    }

    suspend fun completeAuthentication(
        trackerId: Int,
        pastedCallbackOrToken: String,
        nowEpochMillis: Long,
    ): OAuthToken {
        requireConfigured(trackerId)
        val authenticator = authenticatorsById[trackerId]
            ?: throw TrackerAuthenticationException("${provider(trackerId).descriptor.name} login is unavailable")
        val token = authenticator.complete(pastedCallbackOrToken, nowEpochMillis)
        manager.refreshAuthenticationState(trackerId)
        return token
    }

    suspend fun logout(trackerId: Int) {
        requireConfigured(trackerId)
        val authenticator = authenticatorsById[trackerId]
            ?: throw TrackerAuthenticationException("${provider(trackerId).descriptor.name} logout is unavailable")
        authenticator.logout()
        manager.refreshAuthenticationState(trackerId)
    }

    suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState {
        requireConfigured(trackerId)
        return manager.refreshAuthenticationState(trackerId)
    }

    suspend fun search(trackerId: Int, query: String, limit: Int = 20): List<TrackSearch> {
        requireAuthenticated(trackerId)
        return manager.search(trackerId, query, limit)
    }

    suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track {
        requireAuthenticated(trackerId)
        return manager.bind(mangaId, trackerId, remote)
    }

    suspend fun refresh(mangaId: Long, trackerId: Int): Track {
        requireAuthenticated(trackerId)
        return manager.refresh(mangaId, trackerId)
    }

    suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track {
        requireAuthenticated(trackerId)
        if (update.isEmpty) return refresh(mangaId, trackerId)
        return manager.update(mangaId, trackerId, update)
    }

    suspend fun remove(mangaId: Long, trackerId: Int) {
        requireConfigured(trackerId)
        manager.remove(mangaId, trackerId)
    }

    private suspend fun requireAuthenticated(trackerId: Int) {
        requireConfigured(trackerId)
        if (!manager.isAuthenticated(trackerId)) {
            throw TrackerAuthenticationException("Sign in to ${provider(trackerId).descriptor.name} first")
        }
    }

    private fun requireConfigured(trackerId: Int): TrackingProvider {
        val provider = provider(trackerId)
        if (!provider.configured) {
            throw IllegalStateException(
                provider.configurationMessage ?: "${provider.descriptor.name} is not configured",
            )
        }
        return provider
    }

    private fun provider(trackerId: Int): TrackingProvider = providersById[trackerId]
        ?: throw TrackerNotFoundException(trackerId)
}
