package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.TrackerIds
import kotlinx.coroutines.CoroutineScope

/**
 * The tracking assembly entry point (docs/architecture/0007 B2).
 *
 * The composition root used to build the [TrackerManager], its adapter and the provider list
 * itself, which kept those classes public. It builds this instead and sees only the [coordinator]
 * the UI uses and the shutdown step. Queued tracker updates run on [scope], the app's background
 * scope, which the app keeps owning and cancelling.
 */
public class TrackingSubsystem(
    repository: ShinsouRepository,
    scope: CoroutineScope,
) {
    private val manager = TrackerManager(
        adapters = emptyList(),
        repository = repository,
        scope = scope,
    )

    /** The tracking use cases the UI binds to. */
    public val coordinator: TrackingCoordinator = TrackingCoordinator(
        manager = TrackerManagerAdapter(manager, repository),
        providers = listOf(
            TrackingProvider(
                descriptor = TrackerDescriptor(
                    id = TrackerIds.MY_ANIME_LIST,
                    name = "MyAnimeList",
                    scoreFormat = TrackerScoreFormat.POINT_10,
                ),
                configured = false,
                configurationMessage = "MyAnimeList client ID 尚未設定，暫時無法登入。",
            ),
        ),
        authenticators = emptyList(),
    )

    /** Shutdown: drops the queued tracker updates. */
    public suspend fun cancelPendingUpdates() {
        manager.cancelAll()
    }
}
