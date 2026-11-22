package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackerAccountState
import kotlinx.serialization.Serializable

@Serializable
public data class TrackingProvider(
    val descriptor: TrackerDescriptor,
    val configured: Boolean = true,
    val configurationMessage: String? = null,
)

/** Small seam that keeps the UI-facing coordinator independently testable. */
public interface TrackingManagerGateway {
    public val descriptors: List<TrackerDescriptor>

    public suspend fun isAuthenticated(trackerId: Int): Boolean
    public suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState
    public suspend fun search(trackerId: Int, query: String, limit: Int = 20): List<TrackSearch>
    public suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track
    public suspend fun refresh(mangaId: Long, trackerId: Int): Track
    public suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track
    public suspend fun remove(mangaId: Long, trackerId: Int)
}

/** Adapts the existing queue-aware [TrackerManager] and repository to coordinator operations. */
internal class TrackerManagerAdapter(
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

public interface TrackerAuthenticator {
    public val trackerId: Int
    public fun authorizationUrl(): String
    public suspend fun complete(pastedCallbackOrToken: String, nowEpochMillis: Long): OAuthToken
    public suspend fun logout()
}

/**
 * What the app's screens use of [TrackingCoordinator] (0007 B4): the providers, signing in and
 * out, and binding, refreshing, updating and removing a title's tracks.
 */
public interface TrackingService {
    public val providers: List<TrackingProvider>

    public fun authorizationUrl(trackerId: Int): String?

    public suspend fun isAuthenticated(trackerId: Int): Boolean

    public suspend fun completeAuthentication(trackerId: Int, pastedCallbackOrToken: String, nowEpochMillis: Long): OAuthToken

    public suspend fun logout(trackerId: Int)

    public suspend fun search(trackerId: Int, query: String, limit: Int = 20): List<TrackSearch>

    public suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track

    public suspend fun refresh(mangaId: Long, trackerId: Int): Track

    public suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track

    public suspend fun remove(mangaId: Long, trackerId: Int)
}

/**
 * UI-independent tracking use cases shared by Android, iOS and Desktop.
 *
 * Configured providers are backed by [TrackerManager]; unavailable providers remain visible so
 * clients can explain missing app credentials instead of presenting a login flow that cannot work.
 */
public class TrackingCoordinator(
    private val manager: TrackingManagerGateway,
    providers: List<TrackingProvider>,
    authenticators: List<TrackerAuthenticator>,
) : TrackingService {
    override val providers: List<TrackingProvider> = providers.toList()
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

    override fun authorizationUrl(trackerId: Int): String? {
        requireConfigured(trackerId)
        return authenticatorsById[trackerId]?.authorizationUrl()
    }

    override suspend fun isAuthenticated(trackerId: Int): Boolean {
        if (!provider(trackerId).configured) return false
        return manager.isAuthenticated(trackerId)
    }

    override suspend fun completeAuthentication(
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

    override suspend fun logout(trackerId: Int) {
        requireConfigured(trackerId)
        val authenticator = authenticatorsById[trackerId]
            ?: throw TrackerAuthenticationException("${provider(trackerId).descriptor.name} logout is unavailable")
        authenticator.logout()
        manager.refreshAuthenticationState(trackerId)
    }

    internal suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState {
        requireConfigured(trackerId)
        return manager.refreshAuthenticationState(trackerId)
    }

    override suspend fun search(trackerId: Int, query: String, limit: Int): List<TrackSearch> {
        requireAuthenticated(trackerId)
        return manager.search(trackerId, query, limit)
    }

    override suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track {
        requireAuthenticated(trackerId)
        return manager.bind(mangaId, trackerId, remote)
    }

    override suspend fun refresh(mangaId: Long, trackerId: Int): Track {
        requireAuthenticated(trackerId)
        return manager.refresh(mangaId, trackerId)
    }

    override suspend fun update(mangaId: Long, trackerId: Int, update: TrackUpdate): Track {
        requireAuthenticated(trackerId)
        if (update.isEmpty) return refresh(mangaId, trackerId)
        return manager.update(mangaId, trackerId, update)
    }

    override suspend fun remove(mangaId: Long, trackerId: Int) {
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
