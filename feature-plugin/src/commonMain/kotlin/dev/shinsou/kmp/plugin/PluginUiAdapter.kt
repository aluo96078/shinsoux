package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.contract.AccountPackageDirectory
import dev.shinsou.kmp.contract.AccountRepositoryDirectory
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.files.PluginKeyValueStore
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import dev.shinsou.kmp.contract.SourceLoginFailureStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
/** Stable v2 option key used by account-owned collection views (account bookcase). */
internal const val DEFAULT_FAVORITE_BROWSE_OPTION_KEY: String = "option"

/** Internal wrapper carrying only a stable stage to the UI boundary; its cause is never rendered. */
internal class SourceLoginStageException(
    val stage: SourceLoginFailureStage,
    cause: Throwable,
) : RuntimeException(cause)

public fun interface PluginMangaResolver {
    public suspend fun resolve(manga: BrowseManga): Long?

    public companion object {
        public val None: PluginMangaResolver = PluginMangaResolver { null }
    }
}

public fun interface PluginMigrationHandler {
    public suspend fun migrate(mangaId: Long, target: BrowseManga)

    public companion object {
        public val None: PluginMigrationHandler = PluginMigrationHandler { _, _ -> }
    }
}

public fun interface PluginMigrationProvider {
    public suspend fun candidates(): List<MigrationCandidate>

    public companion object {
        public val None: PluginMigrationProvider = PluginMigrationProvider { emptyList() }
    }
}

/** Builds the parts of one [PluginBrowseAdapter] around a single [PluginBrowseState]. */
internal class PluginBrowseParts(deps: PluginBrowseDependencies) {
    val shared = PluginBrowseState()
    val repositories = PluginBrowseRepositories(shared, deps)
    val core = PluginBrowseCore(shared, deps, repositories)
    val publications = PluginExtensionPublicationCatalog(deps, core)
    val catalog = PluginSourceCatalog(shared, deps, core, publications)
    val settings = PluginSourceSettings(shared, deps, core)
    val binding = PluginExtensionLibraryBinding(deps, core)
    val acquisition = PluginContentAcquisition(deps, core)
    val auth = PluginSourceAuth(shared, deps, core)
    val webSession = PluginSourceWebSession(shared, deps, core)
    val trust = PluginExtensionTrust(shared, deps, core, repositories)
    val management = PluginExtensionManagement(shared, deps, core, repositories, trust)
}

/**
 * Adapts the executable plugin subsystem to the UI's stable browse DTOs. Packages, trust tokens,
 * and credentials use the plugin KV boundary; when [portableRepository] is supplied, configured
 * repositories use its portable snapshot and KV is retained only as a runtime compatibility mirror.
 *
 * A facade: each [BrowseCallbacks] family is implemented by its own part ([PluginSourceCatalog],
 * [PluginSourceSettings], [PluginExtensionPublicationCatalog], [PluginExtensionLibraryBinding],
 * [PluginContentAcquisition], [PluginSourceAuth], [PluginSourceWebSession],
 * [PluginExtensionManagement], [PluginExtensionTrust]); they share one [PluginBrowseState].
 */
public class PluginBrowseAdapter private constructor(
    private val parts: PluginBrowseParts,
) : BrowseCallbacks,
    SourceCatalog by parts.catalog,
    SourceSettings by parts.settings,
    ExtensionPublicationCatalog by parts.publications,
    ExtensionLibraryBinding by parts.binding,
    ContentAcquisition by parts.acquisition,
    SourceAuth by parts.auth,
    SourceWebSession by parts.webSession,
    ExtensionManagement by parts.management,
    ExtensionTrust by parts.trust {

    public constructor(
        manager: PluginManager,
        repositoryClient: ExtensionRepositoryClient,
        repositoryStore: ExtensionRepositoryStore,
        pluginStorage: PluginStorage,
        keyValueStore: PluginKeyValueStore,
        trustStore: PluginTrustStore,
        mangaResolver: PluginMangaResolver = PluginMangaResolver.None,
        migrationHandler: PluginMigrationHandler = PluginMigrationHandler.None,
        migrationProvider: PluginMigrationProvider = PluginMigrationProvider.None,
        requestBuilder: PluginRequestBuilder = PluginRequestBuilder(pluginStorage),
        portableRepository: ShinsouRepository? = null,
        loginRequestCoordinator: PluginLoginRequestCoordinator = PluginLoginRequestCoordinator(),
        logoutRequestCoordinator: PluginLogoutRequestCoordinator? = null,
        exactSourceRefreshInvalidations: ExactSourceRefreshInvalidations = ExactSourceRefreshInvalidations(),
        extensionGatewayV2: ExtensionBrowseContentGatewayV2? = null,
        /** Null only in previews/tests that intentionally omit the shared content foundation. */
        extensionContentConsumerV2: ExtensionContentConsumerV2? = null,
        fileSystem: AppFileSystem? = null,
        /** ADR-0002 S5: publisher trust, withdrawals and the block list. Null in previews and older tests. */
        packageSecurity: PluginPackageSecurity?,
        /** ADR-0002 §8.1: device-local developer mode. Null in previews and older tests. */
        developerMode: PluginDeveloperModeStore? = null,
        /**
         * The sync account's repository list, offered here as suggestions (never applied on its own).
         * Null when sync is unavailable.
         */
        accountRepositories: AccountRepositoryDirectory? = null,
        /**
         * The sync account's installed packages. Each one that a repository this device trusts
         * publishes is installed here without being approved ([preinstallAccountPackages]). Null when
         * sync is unavailable.
         */
        accountPackages: AccountPackageDirectory? = null,
        /** Keeps the suggestions in step with [accountRepositories]; without it they refresh with the next snapshot rebuild. */
        suggestionScope: CoroutineScope? = null,
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        PluginBrowseParts(
            PluginBrowseDependencies(
                manager = manager,
                repositoryClient = repositoryClient,
                repositoryStore = repositoryStore,
                keyValueStore = keyValueStore,
                trustStore = trustStore,
                mangaResolver = mangaResolver,
                migrationHandler = migrationHandler,
                migrationProvider = migrationProvider,
                requestBuilder = requestBuilder,
                portableRepository = portableRepository,
                loginRequestCoordinator = loginRequestCoordinator,
                logoutRequestCoordinator = logoutRequestCoordinator,
                exactSourceRefreshInvalidations = exactSourceRefreshInvalidations,
                extensionGatewayV2 = extensionGatewayV2,
                extensionContentConsumerV2 = extensionContentConsumerV2,
                fileSystem = fileSystem,
                packageSecurity = packageSecurity,
                developerMode = developerMode,
                accountRepositories = accountRepositories,
                accountPackages = accountPackages,
                defaultDispatcher = defaultDispatcher,
            ),
        ),
    ) {
        parts.management.watchAccount(suggestionScope)
    }

    /** Where [preinstallAccountPackages] gets an index it has not displayed; replaced by tests. */
    internal var indexSnapshotFetcher: suspend (ExtensionRepository) -> RepositoryIndexSnapshot
        get() = parts.management.indexSnapshotFetcher
        set(value) {
            parts.management.indexSnapshotFetcher = value
        }

    /** How long a package that failed to pre-install waits before the next attempt; replaced by tests. */
    internal var preinstallRetryDelay: kotlin.time.Duration
        get() = parts.management.preinstallRetryDelay
        set(value) {
            parts.management.preinstallRetryDelay = value
        }

    /** The clock of [preinstallRetryDelay]; replaced by tests. */
    internal var preinstallClock: kotlin.time.TimeSource
        get() = parts.management.preinstallClock
        set(value) {
            parts.management.preinstallClock = value
        }

    /**
     * Shows the installed sources at once. Startup runs content migrations before the full
     * [refresh], and on a large library those take long enough that the Sources page stayed
     * empty until the user refreshed it by hand.
     */
    public suspend fun showInstalledSources(): Unit = parts.core.showInstalledSources()

    /**
     * Returns once the installed sources are loaded (loading them if nothing has yet): a chapter
     * the shell restores at launch can be asked for before startup has loaded its source.
     */
    public suspend fun awaitInstalledSources(): Unit = parts.core.ensureInstalledLoaded()

    /** Saves and stops the extension download queue when the app shuts down (R4-8). */
    public suspend fun close(): Unit = parts.acquisition.close()

    /**
     * Brings back novel downloads left by the previous run. Called early in startup so the
     * queue (and the Android download service) is visible at once; the work itself waits for
     * [openExtensionDownloadsV2].
     */
    public suspend fun restoreExtensionDownloadsV2(): Unit = parts.acquisition.restoreExtensionDownloadsV2()

    /** Sources are loaded: queued novel downloads may run. */
    public fun openExtensionDownloadsV2(): Unit = parts.acquisition.openExtensionDownloadsV2()

    /** The app is back on screen: retry downloads that stopped on repeated (network) failures. */
    public suspend fun resumeExtensionDownloadsAfterFailuresV2(): Unit =
        parts.acquisition.resumeExtensionDownloadsAfterFailuresV2()

    /** System pause of exactly [ids] in the novel queue. */
    public suspend fun pauseExtensionDownloadsForSystemV2(ids: Set<String>): Unit =
        parts.acquisition.pauseExtensionDownloadsForSystemV2(ids)

    /** Resumes only [ids] (null: all) after a system pause. */
    public suspend fun resumeExtensionDownloadsAfterSystemV2(ids: Set<String>?): Unit =
        parts.acquisition.resumeExtensionDownloadsAfterSystemV2(ids)

    /** Writes the novel queue to disk now; throws if it could not be saved. */
    public suspend fun flushExtensionDownloadsV2(): Unit = parts.acquisition.flushExtensionDownloadsV2()

    /**
     * Installs, without approving, the packages the sync account has used that this device lacks.
     * A package is only taken from a repository the user has added here and whose publisher is
     * trusted on this device: the account's list says what to look for, never whom to trust.
     * Anything else (an unknown or unsigned repository, a package already installed or removed
     * here on purpose, one the repository no longer lists) is skipped. The install stops at the
     * permission review (pending, nothing runs) and is remembered so the UI can say so; the review
     * comes when the user first opens the package or one of its titles.
     */
    public suspend fun preinstallAccountPackages(): Unit = parts.management.preinstallAccountPackages()

    public companion object
}
