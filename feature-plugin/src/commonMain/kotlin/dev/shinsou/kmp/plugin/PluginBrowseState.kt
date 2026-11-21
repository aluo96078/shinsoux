package dev.shinsou.kmp.plugin

import kotlin.concurrent.Volatile
import dev.shinsou.kmp.contract.AccountPackageDirectory
import dev.shinsou.kmp.contract.AccountRepositoryDirectory
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.files.KeyValueStore
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

internal data class BrowseSourceProjection(
    val source: CatalogueSource,
    val value: BrowseSource,
)

/** What [PluginBrowseAdapter] was constructed with, handed unchanged to its parts. */
internal data class PluginBrowseDependencies(
    val manager: PluginManager,
    val repositoryClient: ExtensionRepositoryClient,
    val repositoryStore: ExtensionRepositoryStore,
    val keyValueStore: KeyValueStore,
    val trustStore: PluginTrustStore,
    val mangaResolver: PluginMangaResolver,
    val migrationHandler: PluginMigrationHandler,
    val migrationProvider: PluginMigrationProvider,
    val requestBuilder: PluginRequestBuilder,
    val portableRepository: ShinsouRepository?,
    val loginRequestCoordinator: PluginLoginRequestCoordinator,
    val logoutRequestCoordinator: PluginLogoutRequestCoordinator?,
    val exactSourceRefreshInvalidations: ExactSourceRefreshInvalidations,
    val extensionGatewayV2: ExtensionBrowseContentGatewayV2?,
    val extensionContentConsumerV2: ExtensionContentConsumerV2?,
    val fileSystem: AppFileSystem?,
    val packageSecurity: PluginPackageSecurity?,
    val developerMode: PluginDeveloperModeStore?,
    val accountRepositories: AccountRepositoryDirectory?,
    val accountPackages: AccountPackageDirectory?,
    val defaultDispatcher: CoroutineDispatcher,
)

/**
 * The mutable state of one [PluginBrowseAdapter], shared by its parts and owned by nobody else.
 * [operationMutex] serializes browse operations; [PluginBrowseCore]'s installed-load lock is
 * independent of it.
 */
internal class PluginBrowseState {

    val operationMutex = Mutex()
    val mutableState = MutableStateFlow(BrowseSnapshot())
    @Volatile
    var descriptors: Map<String, ExtensionDescriptor> = emptyMap()
    /**
     * Exact process-local authenticated index paired with the displayed repository rows. Written
     * under [operationMutex]; the account pre-install reads it outside the lock, hence volatile.
     */
    @Volatile
    var repositorySnapshots: Map<String, RepositoryIndexSnapshot> = emptyMap()
    /** Repositories whose new signed publisher the user has not trusted yet (ADR-0002 S8). */
    @Volatile
    var repositoriesNeedingTrust: Set<String> = emptySet()
    var sourceProjections: Map<Long, BrowseSourceProjection> = emptyMap()
    private val v2SourceRowIds = linkedMapOf<SourceKey, Long>()
    private val v2SourceKeysByRowId = linkedMapOf<Long, SourceKey>()
    private val v2SourceKeysByStorageId = linkedMapOf<Long, SourceKey>()


    /** Conflated: one pending pre-install pass is enough however many things asked for it. */
    val preinstallSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Allocates a process-local row id only; every extension operation still uses SourceKey. */
    fun v2UiRowId(sourceKey: SourceKey, occupied: Set<Long>): Long {
        v2SourceRowIds[sourceKey]?.takeIf { it !in occupied }?.let {
            v2SourceKeysByRowId[it] = sourceKey
            v2StorageSourceId(sourceKey)?.let { storageId -> v2SourceKeysByStorageId[storageId] = sourceKey }
            return it
        }
        var candidate = Long.MIN_VALUE
        val allocated = v2SourceRowIds.values.toHashSet()
        while (candidate in occupied || candidate in allocated) {
            check(candidate != Long.MAX_VALUE) { "Unable to allocate an extension v2 UI row id" }
            candidate++
        }
        v2SourceRowIds[sourceKey] = candidate
        v2SourceKeysByRowId[candidate] = sourceKey
        v2StorageSourceId(sourceKey)?.let { v2SourceKeysByStorageId[it] = sourceKey }
        return candidate
    }

    /** The host-assigned storage namespace of a v2 source: its legacy numeric id. */
    fun v2StorageSourceId(sourceKey: SourceKey): Long? = sourceKey.legacyLongId

    fun v2SourceKeyFor(sourceId: Long): SourceKey? =
        v2SourceKeysByRowId[sourceId] ?: v2SourceKeysByStorageId[sourceId]

    fun requireV2SourceScope(sourceId: Long): Pair<SourceKey, Long>? {
        val sourceKey = v2SourceKeyFor(sourceId) ?: return null
        val storageId = v2StorageSourceId(sourceKey)
            ?: throw IllegalArgumentException("Source settings are unavailable for ${sourceKey.canonicalId}")
        return sourceKey to storageId
    }

    fun pruneV2SourceMappings(liveSourceKeys: Set<SourceKey>) {
        v2SourceRowIds.keys.retainAll(liveSourceKeys)
        v2SourceKeysByRowId.entries.removeAll { it.value !in liveSourceKeys }
        v2SourceKeysByStorageId.entries.removeAll { it.value !in liveSourceKeys }
    }
}
