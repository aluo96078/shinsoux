package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.plugin.v2.CachedExtensionPublicationV2
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationCacheV2
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadQueueFileStoreV2
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadQueueV2
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.PackageBlock
import dev.shinsou.kmp.ui.PackagePublisher
import dev.shinsou.kmp.ui.PackageSecuritySnapshot
import dev.shinsou.kmp.ui.PublisherTrustState
import dev.shinsou.kmp.ui.RepositoryTrustPrompt
import dev.shinsou.kmp.ui.BrowseExtension
import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseRepositorySuggestion
import dev.shinsou.kmp.ui.BrowsePage
import dev.shinsou.kmp.ui.BrowseRepository
import dev.shinsou.kmp.ui.BrowseSnapshot
import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.MigrationCandidate
import dev.shinsou.kmp.ui.ReaderChapter
import dev.shinsou.kmp.ui.ReaderPage
import dev.shinsou.kmp.ui.SourceLoginRequest
import dev.shinsou.kmp.ui.SourceLoginFailureStage
import dev.shinsou.kmp.ui.SourceLoginResult
import dev.shinsou.kmp.ui.SourceSecrets
import dev.shinsou.kmp.ui.SourceSecretsResult
import dev.shinsou.kmp.ui.SourceCookie as BrowseSourceCookie
import dev.shinsou.kmp.ui.SourceCredential as BrowseSourceCredential
import dev.shinsou.kmp.ui.SourcePreference as BrowseSourcePreference
import dev.shinsou.kmp.ui.SourcePreferenceKind
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.TypedReaderContentSession
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import io.ktor.http.Url
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.ui.resolvedAgainst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

/** Startup/refresh discovery must never leave the browse surface behind a dead network host. */
private const val BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS: Long = 8_000L
/** Upper bound on target chapter pages read while migrating one title. */
private const val MAX_MIGRATION_UNIT_PAGES: Int = 200

/** Cooperative bound layered over the desktop protected-store caller timeout. */
private const val SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS: Long = 12_000L

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

private data class BrowseSourceProjection(
    val source: CatalogueSource,
    val value: BrowseSource,
)

/**
 * Adapts the executable plugin subsystem to the UI's stable browse DTOs. Packages, trust tokens,
 * and credentials use the plugin KV boundary; when [portableRepository] is supplied, configured
 * repositories use its portable snapshot and KV is retained only as a runtime compatibility mirror.
 */
public class PluginBrowseAdapter(
    private val manager: PluginManager,
    private val repositoryClient: ExtensionRepositoryClient,
    private val repositoryStore: ExtensionRepositoryStore,
    private val pluginStorage: PluginStorage,
    private val keyValueStore: PluginKeyValueStore,
    private val trustStore: PluginTrustStore,
    private val mangaResolver: PluginMangaResolver = PluginMangaResolver.None,
    private val migrationHandler: PluginMigrationHandler = PluginMigrationHandler.None,
    private val migrationProvider: PluginMigrationProvider = PluginMigrationProvider.None,
    private val requestBuilder: PluginRequestBuilder = PluginRequestBuilder(pluginStorage),
    private val portableRepository: ShinsouRepository? = null,
    private val loginRequestCoordinator: PluginLoginRequestCoordinator = PluginLoginRequestCoordinator(),
    private val logoutRequestCoordinator: PluginLogoutRequestCoordinator? = null,
    private val exactSourceRefreshInvalidations: ExactSourceRefreshInvalidations = ExactSourceRefreshInvalidations(),
    extensionGatewayV2: ExtensionBrowseContentGatewayV2? = null,
    /** Null only in previews/tests that intentionally omit the shared content foundation. */
    private val extensionContentConsumerV2: ExtensionContentConsumerV2? = null,
    private val fileSystem: AppFileSystem? = null,
    /** ADR-0002 S5: publisher trust, withdrawals and the block list. Null in previews and older tests. */
    private val packageSecurity: PluginPackageSecurity? = null,
    /** ADR-0002 §8.1: device-local developer mode. Null in previews and older tests. */
    private val developerMode: PluginDeveloperModeStore? = null,
    /**
     * The sync account's repository list, offered here as suggestions (never applied on its own).
     * Null when sync is unavailable.
     */
    private val accountRepositories: AccountRepositoryDirectory? = null,
    /** Keeps the suggestions in step with [accountRepositories]; without it they refresh with the next snapshot rebuild. */
    suggestionScope: CoroutineScope? = null,
) : BrowseCallbacks {
    /** Keys shown to the user by [inspectRepositoryTrust], by normalized URL, until trusted or replaced. */
    private val inspectedRepositoryKeys = mutableMapOf<String, RepositoryKeyPresentation>()
    private val extensionGatewayV2: ExtensionBrowseContentGatewayV2 = extensionGatewayV2
        ?: ExtensionBrowseContentGatewayV2(
            ExtensionSourceResolverV2 { sourceKey ->
                ensureInstalledLoaded()
                manager.extensionSourceV2(sourceKey)
            },
        )
    override val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>> =
        exactSourceRefreshInvalidations.generations
    override val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>> =
        logoutRequestCoordinator?.requests
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override fun dismissPluginLogout(eventId: String) {
        logoutRequestCoordinator?.dismiss(eventId)
    }

    override fun dismissAllPluginLogouts() {
        logoutRequestCoordinator?.clear()
    }

    override suspend fun setPluginUiAvailable(available: Boolean) {
        manager.setPluginUiAvailable(available)
        if (!available) {
            loginRequestCoordinator.retainPresentedRequest()
            logoutRequestCoordinator?.clear()
        }
    }

    override suspend fun confirmPluginLogout(eventId: String): Boolean = withContext(Dispatchers.Default) {
        val request = logoutRequestCoordinator?.take(eventId) ?: return@withContext false
        val sourceKey = request.target.sourceKey
        val storageId = exactSessionStorageId(request) ?: return@withContext false
        val exact = manager.exactExtensionSource(request.target)
        val legacy = manager.exactLegacySource(request.target) as? LoginSource
        if (exact == null && legacy == null) return@withContext false
        // Remote logout is bounded. Host policy is fail-closed: timeout/failure preserves the
        // local session namespace so the user can retry and no unrelated credentials are erased.
        val remoteSucceeded = withTimeoutOrNull(10_000) {
            runCatching {
                if (exact != null) exact.logout() else legacy?.logout()
            }.isSuccess
        } == true
        if (!remoteSucceeded) return@withContext false
        // Credentials and cookies are the complete host-owned session namespace for this exact
        // execution/storage scope. Preferences remain source configuration, not session state.
        val ownerKey = ExactPluginSessionOwnership.ownerKey(request.target)
        if (!ExactPluginSessionOwnership.authorizesCleanup(keyValueStore.getString(ownerKey), request.target)) {
            return@withContext false
        }
        val storage = storageForSourceUi(request.target.sourceKey.legacyLongId ?: storageId)
        storage.clearCredential(storageId)
        storage.clearCookies(storageId)
        keyValueStore.remove(ownerKey)
        true
    }

    override suspend fun saveSourceEventCredentials(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceEventCredentialsResult(eventId, sourceId, username, password).succeeded

    override suspend fun saveSourceEventCredentialsResult(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        val request = loginRequestCoordinator.event(eventId) ?: return SourceLoginResult(false)
        val target = request.exactTarget ?: return SourceLoginResult(false)
        if (request.sourceId != sourceId) return SourceLoginResult(false)
        val result = saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = false)
        if (result.succeeded) {
            keyValueStore.putString(
                ExactPluginSessionOwnership.ownerKey(target),
                ExactPluginSessionOwnership.targetKey(target),
            )
            exactSourceRefreshInvalidations.invalidate(target)
            loginRequestCoordinator.dismissEvent(eventId)
        }
        return result
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun exactSessionStorageId(request: PluginLogoutConfirmation): Long? {
        val target = request.target
        val sourceKey = target.sourceKey
        val artifact = target.artifactIdentity
        val installed = manager.installedPlugins().singleOrNull { stored ->
            stored.manifest.id == artifact.packageId &&
                stored.manifest.version == artifact.version &&
                (stored.manifest.versionCode ?: PluginVerifier.versionInt(stored.manifest.version)) == artifact.versionCode &&
                stored.metadata.installedSha256 == artifact.sha256 &&
                stored.manifest.sources.orEmpty().any { it.id == sourceKey.legacyLongId }
        }
        if (installed != null && manager.extensionSourceV2(sourceKey) != null) {
            return sourceKey.legacyLongId
        }
        return null
    }
    private val operationMutex = Mutex()
    /**
     * Source calls can arrive from the local library before the background Browse refresh. Keep
     * installation hydration independently serialized so those calls wait for the exact reviewed
     * runtime (and its fixed credential/cookie storage scope) without deadlocking operationMutex.
     */
    private val installedLoadMutex = Mutex()
    private val mutableState = MutableStateFlow(BrowseSnapshot())
    private var loadedInstalled = false
    private var descriptors: Map<String, ExtensionDescriptor> = emptyMap()
    /** Exact process-local authenticated index paired with the displayed repository rows. */
    private var repositorySnapshots: Map<String, RepositoryIndexSnapshot> = emptyMap()
    /** Repositories whose new signed publisher the user has not trusted yet (ADR-0002 S8). */
    private var repositoriesNeedingTrust: Set<String> = emptySet()
    private var sourceProjections: Map<Long, BrowseSourceProjection> = emptyMap()
    private val v2SourceRowIds = linkedMapOf<SourceKey, Long>()
    private val v2SourceKeysByRowId = linkedMapOf<Long, SourceKey>()
    private val v2SourceKeysByStorageId = linkedMapOf<Long, SourceKey>()

    override val state: StateFlow<BrowseSnapshot> = mutableState
    private val dismissedSuggestionsMutex = Mutex()

    init {
        val directory = accountRepositories
        if (directory != null && suggestionScope != null) {
            suggestionScope.launch {
                directory.repositories.collect { publishRepositorySuggestions() }
            }
        }
    }
    override val loginRequests: StateFlow<List<SourceLoginRequest>> = loginRequestCoordinator.loginRequests

    override fun dismissSourceLoginRequest(sourceId: Long) {
        loginRequestCoordinator.dismiss(sourceId)
    }

    override fun dismissSourceLoginEvent(eventId: String) {
        loginRequestCoordinator.dismissEvent(eventId)
    }

    override suspend fun refresh(): Unit = operationMutex.withLock {
        refreshLocked()
    }

    /**
     * Shows the installed sources at once. Startup runs content migrations before the full
     * [refresh], and on a large library those take long enough that the Sources page stayed
     * empty until the user refreshed it by hand.
     */
    public suspend fun showInstalledSources(): Unit = operationMutex.withLock {
        withContext(Dispatchers.Default) {
            ensureInstalledLoaded()
            rebuildSnapshot(errorMessage = null)
        }
    }

    override suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): Unit = operationMutex.withLock {
        manager.setEventSourceEnabled(sourceId, enabled)
        keyValueStore.putString(sourceEnabledKey(sourceId), enabled.toString())
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean): Unit =
        operationMutex.withLock {
            check(manager.extensionSourceV2(sourceKey) != null) {
                "Unknown extension v2 source: ${sourceKey.canonicalId}"
            }
            manager.setEventSourceEnabled(sourceKey, enabled)
            keyValueStore.putString(sourceEnabledV2Key(sourceKey), enabled.toString())
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun addRepository(url: String): BrowseRepository? = operationMutex.withLock {
        reconcilePortableRepositories()
        val input = url.trim()
        val repository = repositoryClient.fetchRepository(normalizeRepositoryInput(input))
        val hadRepositories = repositoryStore.list().isNotEmpty()
        saveRepository(repository)
        if (!hadRepositories || repositoryStore.selected() == null) repositoryStore.select(repository.baseUrl)
        refreshLocked()
        repository.toBrowseRepository()
    }

    override suspend fun inspectRepositoryTrust(url: String): RepositoryTrustPrompt? {
        val security = packageSecurity ?: return null
        val input = normalizeRepositoryInput(url.trim())
        val presentation = operationMutex.withLock {
            // Anything the regular path can already take (pinned, trusted publisher)
            // or that is not a signed repository goes through addRepository as before.
            // Null only when the repository answered with something that is not a signed repo.json
            // (an unsigned repository); a failure to reach it is an error, so a caller can tell
            // "unsigned" from "try again".
            val inspected = try {
                repositoryClient.inspectRepositoryKey(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ExtensionRepositoryException.InvalidDocument) {
                return@withLock null
            } catch (_: ExtensionRepositoryException.InvalidUrl) {
                return@withLock null // not a generic repository URL: addRepository decides
            } catch (missing: ExtensionRepositoryException.Http) {
                if (missing.status == 404) return@withLock null // no repo.json: not a signed repository
                throw missing
            }
            inspected.also { inspectedRepositoryKeys[it.normalizedBaseUrl] = it }
        } ?: return null
        val known = security.publishers.publisherForRepository(presentation.normalizedBaseUrl)
        return RepositoryTrustPrompt(
            url = presentation.normalizedBaseUrl,
            name = presentation.name,
            displayFingerprint = presentation.displayFingerprint,
            fingerprintRequired = security.publishers.globalMode() == PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH,
            keyFingerprint = presentation.key.fingerprint,
            alreadyTrusted = known?.currentFingerprint == presentation.key.fingerprint,
        )
    }

    override suspend fun trustAndAddRepository(prompt: RepositoryTrustPrompt, fingerprint: String?): BrowseRepository? {
        val security = checkNotNull(packageSecurity) { "Publisher trust is unavailable" }
        operationMutex.withLock {
            val presentation = inspectedRepositoryKeys[prompt.url]
            // The key this prompt showed, exactly: a later inspection presenting another key (or none)
            // makes the prompt stale. Trusting re-reads nothing from the network.
            check(presentation != null && presentation.key.fingerprint == prompt.keyFingerprint) {
                "The repository's key changed; inspect it again before trusting it"
            }
            // No publisher is official; one that says so is not trusted.
            PluginNamePolicy.requireNotMisleading(presentation.name)
            // A link's fingerprint binds the trust whatever the dialog sent: it cannot be cleared
            // back to trust-on-first-use, and a different typed value is refused.
            val typed = fingerprint?.takeIf(String::isNotBlank)
            val linked = prompt.linkedFingerprint
            if (linked != null && typed != null && PublisherFingerprint.parse(typed) != PublisherFingerprint.parse(linked)) {
                throw PublisherTrustException.FingerprintMismatch()
            }
            // A URL that is no longer one of the user's repositories (removed before removal
            // released its mapping) is not bound to the publisher it used to serve: the key shown
            // in this prompt is what the user now confirms. A configured repository presenting
            // another key is still a key substitution.
            val mapped = security.publishers.publisherForRepository(presentation.normalizedBaseUrl)
            if (mapped != null && mapped.currentFingerprint != presentation.key.fingerprint &&
                !isConfiguredRepository(presentation.normalizedBaseUrl)
            ) {
                security.publishers.forgetRepository(presentation.normalizedBaseUrl)
            }
            security.publishers.trustRepository(
                presentation.normalizedBaseUrl,
                presentation.key,
                expectedFingerprint = linked ?: typed,
            )
            inspectedRepositoryKeys.remove(prompt.url)
        }
        return addRepository(prompt.url)
    }

    override suspend fun packageSecurity(): PackageSecuritySnapshot {
        val developer = developerMode?.state?.value?.let { dev.shinsou.kmp.ui.PluginDeveloperModeSettings(it.enabled, it.lanHosts) }
        val security = packageSecurity ?: return PackageSecuritySnapshot(developerMode = developer)
        val mode = security.publishers.globalMode()
        val blocks = security.blocklist.entries()
        return PackageSecuritySnapshot(
            developerMode = developer,
            strictMode = mode == PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH,
            publishers = security.publishers.publishers().map { record ->
                PackagePublisher(
                    id = record.publisherId,
                    displayFingerprint = PublisherFingerprint.display(record.currentFingerprint),
                    state = when (record.status(mode)) {
                        PublisherTrustStatus.TRUSTED -> PublisherTrustState.TRUSTED
                        PublisherTrustStatus.PENDING_FINGERPRINT_MATCH -> PublisherTrustState.PENDING_FINGERPRINT
                        PublisherTrustStatus.KEY_REVOKED -> PublisherTrustState.KEY_REVOKED
                        PublisherTrustStatus.KEY_REPLACED -> PublisherTrustState.KEY_REPLACED
                    },
                    fingerprintVerified = record.verification != PublisherVerification.TOFU,
                    official = record.verification == PublisherVerification.OFFICIAL_BUILT_IN,
                    blocked = PluginBlockEntry.Publisher(record.publisherId) in blocks,
                )
            },
            blocks = blocks.map { entry ->
                when (entry) {
                    is PluginBlockEntry.Package -> PackageBlock(
                        "package:${entry.publisherId}:${entry.packageId}",
                        if (entry.publisherId == PluginBlockEntry.Package.NO_PUBLISHER) entry.packageId
                        else "${entry.packageId} · ${PublisherFingerprint.display(entry.publisherId).take(9)}",
                    )
                    is PluginBlockEntry.Artifact -> PackageBlock("artifact:${entry.sha256}", "sha256 ${entry.sha256.take(12)}…")
                    is PluginBlockEntry.Publisher -> PackageBlock("publisher:${entry.publisherId}", PublisherFingerprint.display(entry.publisherId))
                }
            },
        )
    }

    override suspend fun setStrictPublisherTrust(strict: Boolean): Unit = securityChange {
        it.publishers.setGlobalMode(if (strict) PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH else PublisherTrustMode.TOFU_WITH_OPTIONAL_MATCH)
    }

    override suspend fun setPluginDeveloperMode(enabled: Boolean): Unit = developerModeChange { store ->
        // Down first: a development build must not outlive the mode, even for a moment.
        if (!enabled) manager.unloadDeveloperRuntimes()
        store.setEnabled(enabled)
    }

    override suspend fun setPluginDeveloperHosts(hosts: List<String>): Unit = developerModeChange { it.setLanHosts(hosts) }

    /** A developer-mode change starts or stops the `~dev` packages, so their runtimes are re-resolved. */
    private suspend fun developerModeChange(change: suspend (PluginDeveloperModeStore) -> Unit): Unit = operationMutex.withLock {
        val store = checkNotNull(developerMode) { "Developer mode is unavailable" }
        try {
            change(store)
        } finally {
            withContext(NonCancellable) {
                manager.loadInstalled()
                rebuildSnapshot(errorMessage = null)
            }
        }
    }

    override suspend fun confirmPublisherFingerprint(publisherId: String, fingerprint: String): Unit = securityChange {
        it.publishers.confirmFingerprint(publisherId, fingerprint)
    }

    override suspend fun setPublisherBlocked(publisherId: String, blocked: Boolean): Unit = securityChange {
        val entry = PluginBlockEntry.Publisher(publisherId)
        if (blocked) it.blocklist.block(entry) else it.blocklist.unblock(entry)
    }

    override suspend fun setExtensionBlocked(extensionId: String, blocked: Boolean): Unit = securityChange { security ->
        // Packages without a publisher (unsigned, developer) are blocked by id.
        val publisherId = manager.installedPlugins().firstOrNull { it.manifest.id == extensionId }?.metadata?.publisherId
        val entry = PluginBlockEntry.Package(
            PluginInstallKeys.publishedPackageId(extensionId),
            publisherId ?: PluginBlockEntry.Package.NO_PUBLISHER,
        )
        if (blocked) security.blocklist.block(entry) else security.blocklist.unblock(entry)
    }

    override suspend fun unblockPackageEntry(key: String): Unit = securityChange { security ->
        val entry = when {
            key.startsWith("artifact:") -> PluginBlockEntry.Artifact(key.removePrefix("artifact:"))
            key.startsWith("publisher:") -> PluginBlockEntry.Publisher(key.removePrefix("publisher:"))
            key.startsWith("package::") ->
                PluginBlockEntry.Package(key.removePrefix("package::"), PluginBlockEntry.Package.NO_PUBLISHER)
            key.startsWith("package:") -> {
                // "package:<publisherId>:<packageId>"; publisher ids contain one colon ("sha256:…").
                val rest = key.removePrefix("package:")
                val split = rest.indexOf(':', startIndex = rest.indexOf(':') + 1)
                PluginBlockEntry.Package(rest.substring(split + 1), rest.substring(0, split))
            }
            else -> error("Unknown block entry")
        }
        security.blocklist.unblock(entry)
    }

    /**
     * A trust change takes effect at once: every installed package's tier is decided again. The
     * re-evaluation runs even when the change is cancelled after it was stored (the screen closed
     * mid-write), so a block never waits for a restart to take a running package down.
     */
    private suspend fun securityChange(change: suspend (PluginPackageSecurity) -> Unit): Unit = operationMutex.withLock {
        val security = checkNotNull(packageSecurity) { "Publisher trust is unavailable" }
        try {
            change(security)
        } finally {
            withContext(NonCancellable) {
                manager.loadInstalled()
                rebuildSnapshot(errorMessage = null)
            }
        }
    }

    override suspend fun removeRepository(repositoryId: String, fromAccount: Boolean): Unit = operationMutex.withLock {
        // Deletion is local maintenance. Never load scripts, migrate remote indexes, or refresh
        // another repository as a prerequisite (or after the durable removal has succeeded).
        reconcilePortableRepositoryStorage()
        val base = normalizeRepositoryInput(repositoryId)
        fun belongsToRemovedRepository(value: String): Boolean =
            value == repositoryId || runCatching { normalizeRepositoryInput(value) == base }.getOrDefault(false)
        deleteRepository(base)
        // Deleting here must not bring the repository straight back as a suggestion from the account.
        updateDismissedSuggestions { it + base }
        // Removing a repository releases its URL -> publisher mapping (the publisher, its other
        // URLs and all watermarks stay), so the URL can later be trusted under another key.
        packageSecurity?.publishers?.forgetRepository(base)
        val remaining = repositoryStore.list()
        val selected = repositoryStore.selected().takeIf { id -> remaining.any { it.baseUrl == id } }
            ?: remaining.firstOrNull()?.baseUrl
        repositoryStore.select(selected)

        repositorySnapshots = repositorySnapshots.filterKeys { !belongsToRemovedRepository(it) }
        manager.retainRepositoryPackages(remaining.map { it.baseUrl })
        val removedGenericIds = descriptors.values.filter {
            it.repositoryBaseUrl?.let(::belongsToRemovedRepository) == true && it.state == ExtensionState.AVAILABLE
        }.map { it.id }.toSet()
        descriptors = descriptors - removedGenericIds
        // Reuse already-rendered source/settings state: rebuilding it can invoke a broken plugin.
        val current = mutableState.value
        mutableState.value = current.copy(
            repositories = remaining.map { it.toBrowseRepository() }.distinctBy(BrowseRepository::url),
            selectedRepositoryId = selected,
            extensions = current.extensions.filterNot {
                !it.installed && (it.id in removedGenericIds)
            }.map { if (descriptors[it.id]?.repositoryBaseUrl == base) {
                it.copy(updateAvailable = false)
            } else it },
            isRefreshing = false,
            errorMessage = null,
        )
        if (fromAccount) accountRepositories?.forget(base)
    }

    override suspend fun dismissRepositorySuggestion(url: String) {
        val key = suggestionKey(url)
        updateDismissedSuggestions { it + key }
        publishRepositorySuggestions()
    }

    override suspend fun selectRepository(repositoryId: String?): Unit = operationMutex.withLock {
        reconcilePortableRepositories()
        val repositories = repositoryStore.list()
        require(repositoryId == null || repositories.any { it.baseUrl == repositoryId }) {
            "Unknown extension repository: $repositoryId"
        }
        repositoryStore.select(repositoryId)
        refreshLocked()
    }

    override suspend fun browseSource(
        sourceId: Long,
        query: String,
        page: Int,
        filters: List<BrowseFilter>?,
    ): BrowsePage = withContext(Dispatchers.Default) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        val pluginPage = (page.coerceAtLeast(1) - 1)
        val result = manager.withUserInteractionContext(sourceId) {
            if (query.isBlank() && filters == null) {
                source.getPopularManga(pluginPage)
            } else {
                source.getSearchManga(
                    pluginPage,
                    query,
                    filters?.map { it.toPluginFilter() } ?: source.getFilterList(),
                )
            }
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceLatest(sourceId: Long, page: Int): BrowsePage =
        withContext(Dispatchers.Default) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        check(source.supportsLatest) { "Source '$sourceId' does not support latest updates" }
        val result = manager.withUserInteractionContext(sourceId) {
            source.getLatestUpdates(page.coerceAtLeast(1) - 1)
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceFavorites(sourceId: Long, page: Int): BrowsePage =
        withContext(Dispatchers.Default) {
            val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
            check(enabled) { "Source '$sourceId' is disabled" }
            check(source.supportsFavorites) { "Source '$sourceId' does not support favorites" }
            val result = manager.withUserInteractionContext(sourceId) {
                source.getFavoriteManga(page.coerceAtLeast(1) - 1)
            }
            BrowsePage(
                items = mapBrowseManga(source, result.mangas),
                hasNextPage = result.hasNextPage,
            )
        }

    override fun isPluginSource(sourceId: Long): Boolean =
        sourceId in sourceProjections || v2SourceKeyFor(sourceId) != null

    override fun isPluginSource(sourceKey: SourceKey): Boolean =
        mutableState.value.sources.any { it.sourceKey == sourceKey }

    override suspend fun loadPluginThumbnail(
        sourceId: Long,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val sourceKey = v2SourceKeyFor(sourceId)
        if (sourceKey != null) return loadPluginThumbnail(sourceKey, url, headers)
        val source = manager.source(sourceId) ?: return null
        val metadata = PageRequestMetadata.parse(url)
        val resolved = resolveSourceHttpUrl(source.baseUrl, metadata.cleanUrl) ?: return null
        val content = manager.contentNetworkForSource(sourceId) ?: return null
        return fetchPluginThumbnail(
            content = content,
            sourceId = sourceId,
            url = resolved,
            headers = metadata.headers + headers,
            referer = source.headers.header("Referer") ?: source.baseUrl,
        )
    }

    override suspend fun loadPluginThumbnail(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val live = try {
            fetchExtensionThumbnailV2(sourceKey, url, headers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            publicationCacheV2?.readCover(sourceKey, url)?.let { return it }
            throw error
        }
        if (live == null) return publicationCacheV2?.readCover(sourceKey, url)
        // A library cover kept for offline use follows the source when its artwork changes.
        publicationCacheV2?.let { cache ->
            if (cache.hasCover(sourceKey, url)) runCatching { cache.writeCover(sourceKey, url, live) }
        }
        return live
    }

    private suspend fun fetchExtensionThumbnailV2(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val descriptor = manager.extensionSourceV2(sourceKey)?.descriptor ?: return null
        val metadata = PageRequestMetadata.parse(url)
        val resolved = resolveSourceHttpUrl(descriptor.baseUrl, metadata.cleanUrl) ?: return null
        // Use the exact live artifact scope. Looking the id up another way can select an
        // older version when several admitted ones share a package id, causing the content
        // request (and its source-scoped UA/preferences) to use the wrong namespace.
        val contentScope = manager.contentNetworkScopeForSource(sourceKey) ?: return null
        return fetchPluginThumbnail(
            content = contentScope.network,
            sourceId = contentScope.sourceId,
            url = resolved,
            headers = metadata.headers + headers,
            referer = descriptor.baseUrl,
        )
    }

    private suspend fun fetchPluginThumbnail(
        content: PluginContentNetworkClient,
        sourceId: Long,
        url: String,
        headers: Map<String, String>,
        referer: String?,
    ): ByteArray? = try {
        val response = content.get(
            sourceId = sourceId,
            url = url,
            headers = linkedMapOf<String, String>().apply {
                // Metadata headers are extension-controlled. Content fetches must discard
                // credential-bearing values instead of letting PluginContentNetworkClient reject
                // the whole cover request (which the UI otherwise observes only as a null image).
                putAll(sanitizePluginContentHeaders(headers))
                if (!referer.isNullOrBlank() && keys.none { it.equals("Referer", ignoreCase = true) }) {
                    put("Referer", referer)
                }
            },
        )
        response.imageBodyForDecoderOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? =
        extensionGatewayV2.source(sourceKey)

    override suspend fun browseSourceV2(
        sourceKey: SourceKey,
        options: BrowseOptionsV2,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) { extensionGatewayV2.browse(sourceKey, options, page) }
    }

    override suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            extensionGatewayV2.search(sourceKey, query, page, options)
        }
    }

    override suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) { extensionGatewayV2.latest(sourceKey, page) }
    }

    override suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 = withContext(Dispatchers.Default) {
        // Always the source's answer: the detail screen shows (and keeps) the cached copy itself,
        // and must be able to tell fresh details from cached ones before saving them.
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.details(sourceKey, remotePublicationId)
            }
        }.withoutScriptObjectText()
    }

    private val publicationCacheV2 = fileSystem?.let(::ExtensionPublicationCacheV2)

    override suspend fun cachedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): ExtensionPublicationPageV2? = withContext(Dispatchers.Default) {
        val consumer = extensionContentConsumerV2 ?: return@withContext null
        publicationCacheV2?.read(sourceKey, remotePublicationId)?.let { consumer.cachedPublicationPage(sourceKey, it) }
    }

    override suspend fun cacheExtensionPublicationV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
        units: List<RemoteUnitV2>,
    ) {
        val cache = publicationCacheV2 ?: return
        withContext(Dispatchers.Default) {
            cache.write(
                sourceKey,
                CachedExtensionPublicationV2(
                    sourceCanonicalId = sourceKey.canonicalId,
                    publication = publication,
                    units = units,
                    updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            // Keep the cover too: offline the library would otherwise show a blank placeholder.
            val cover = publication.thumbnailUrl?.takeIf(String::isNotBlank) ?: return@withContext
            if (!cache.hasCover(sourceKey, cover)) {
                runCatching { fetchExtensionThumbnailV2(sourceKey, cover, emptyMap()) }
                    .getOrNull()
                    ?.let { cache.writeCover(sourceKey, cover, it) }
            }
        }
    }

    override suspend fun forgetCachedExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String) {
        val cache = publicationCacheV2 ?: return
        withContext(Dispatchers.Default) { cache.delete(sourceKey, remotePublicationId) }
    }

    override fun extensionLibraryBindingV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2? =
        extensionContentConsumerV2?.extensionLibraryBinding(publicationKey)

    override fun extensionLocalReadingStateV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionContentConsumerV2?.localReadingState(publicationKey)
            ?: dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()

    override fun extensionLocalReadingStateV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitIds: Collection<String>,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionContentConsumerV2?.localReadingState(sourceKey, remotePublicationId, remoteUnitIds)
            ?: dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()

    override fun extensionDownloadedUnitIdsV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): Set<String> = extensionContentConsumerV2?.downloadedRemoteUnitIds(publicationKey).orEmpty()

    override suspend fun favoriteExtensionPublicationV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    ): Unit = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            // A favorite mutation is still a visible publication invocation.  Keeping the
            // publication identity bound here is important for plugins that emit an
            // ACTIVE_CONTEXT refresh (and also keeps the event context consistent with details,
            // units, and content requests).
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.favorite(sourceKey, remotePublicationId, favorite)
            }
        }
    }

    override suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.units(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId, remoteUnitId) {
                extensionGatewayV2.content(sourceKey, remotePublicationId, remoteUnitId)
            }
        }
    }

    override suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(Dispatchers.Default) {
        ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            manager.withUserInteractionContext(sourceKey) {
                consumer.publicationPage(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionPublicationUnitsPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(Dispatchers.Default) {
        ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            // Offline (or a source that comes back empty), the first page falls back to the
            // library cache, then to the stored chapters, so downloaded content stays reachable.
            val stored: suspend () -> ExtensionPublicationPageV2? = {
                if (page != 0) {
                    null
                } else {
                    publicationCacheV2?.read(sourceKey, remotePublicationId)
                        ?.let { consumer.cachedPublicationPage(sourceKey, it) }
                        ?: consumer.storedPublicationPage(sourceKey, publication)
                }
            }
            val remote = try {
                manager.withUserInteractionContext(sourceKey) {
                    consumer.publicationUnitsPage(sourceKey, remotePublicationId, publication, page)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@let stored() ?: throw error
            }
            if (remote.units.isEmpty()) stored() ?: remote else remote
        }
    }

    private val extensionDownloadQueueV2 by lazy {
        ExtensionDownloadQueueV2(
            materialize = { selection ->
                try {
                    materializeExtensionContentV2(selection, null)
                } catch (choice: dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException.RepresentationSelectionRequired) {
                    // A background download has nobody to ask; keep the source's first offering,
                    // which is also what offline reading opens by default.
                    materializeExtensionContentV2(selection, choice.availableIds.firstOrNull() ?: throw choice)
                }
            },
            storedUnitIds = { selection ->
                extensionContentConsumerV2
                    ?.downloadedRemoteUnitIds(
                        dev.shinsou.kmp.plugin.v2.extensionPublicationKey(
                            selection.sourceKey,
                            selection.remotePublicationId,
                        ),
                    )
                    .orEmpty()
            },
            store = fileSystem?.let(::ExtensionDownloadQueueFileStoreV2),
            // Sources load during startup; downloads begin once [openExtensionDownloadsV2] runs.
            startOpen = false,
        )
    }

    /**
     * Brings back novel downloads left by the previous run. Called early in startup so the
     * queue (and the Android download service) is visible at once; the work itself waits for
     * [openExtensionDownloadsV2].
     */
    public suspend fun restoreExtensionDownloadsV2() {
        val consumer = extensionContentConsumerV2 ?: return
        extensionDownloadQueueV2.restore(consumer::restoredSelection)
    }

    /** Sources are loaded: queued novel downloads may run. */
    public fun openExtensionDownloadsV2() {
        extensionDownloadQueueV2.open()
    }

    /** The app is back on screen: retry downloads that stopped on repeated (network) failures. */
    public suspend fun resumeExtensionDownloadsAfterFailuresV2() {
        extensionDownloadQueueV2.resumeAfterFailures()
    }

    override val extensionDownloadsV2: StateFlow<List<ExtensionDownloadEntryV2>>
        get() = extensionDownloadQueueV2.entries

    override suspend fun enqueueExtensionDownloadsV2(selections: List<ExtensionUnitSelectionV2>) {
        requireNotNull(extensionContentConsumerV2) { "Extension v2 content storage is unavailable" }
        extensionDownloadQueueV2.enqueue(selections)
    }

    /** System pause of exactly [ids] in the novel queue. */
    public suspend fun pauseExtensionDownloadsForSystemV2(ids: Set<String>): Unit =
        extensionDownloadQueueV2.pauseForSystem(ids)

    /** Resumes only [ids] (null: all) after a system pause. */
    public suspend fun resumeExtensionDownloadsAfterSystemV2(ids: Set<String>?): Unit =
        extensionDownloadQueueV2.resumeAfterSystem(ids)

    /** Writes the novel queue to disk now; throws if it could not be saved. */
    public suspend fun flushExtensionDownloadsV2(): Unit = extensionDownloadQueueV2.flush()

    override suspend fun retryExtensionDownloadV2(id: String) = extensionDownloadQueueV2.retry(id)

    override suspend fun removeExtensionDownloadV2(id: String) = extensionDownloadQueueV2.remove(id)

    override suspend fun clearCompletedExtensionDownloadsV2() = extensionDownloadQueueV2.clearCompleted()

    override suspend fun removeExtensionPublicationDownloadsV2(sourceKey: SourceKey, remotePublicationId: String) =
        extensionDownloadQueueV2.removePublication(sourceKey, remotePublicationId)

    override suspend fun purgeExtensionPublicationContentV2(sourceKey: SourceKey, remotePublicationId: String) {
        val consumer = extensionContentConsumerV2 ?: return
        withContext(Dispatchers.Default) { consumer.purgeStoredContent(sourceKey, remotePublicationId) }
    }

    override suspend fun pauseExtensionDownloadsV2(paused: Boolean) = extensionDownloadQueueV2.setPaused(paused)

    override suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String?,
    ): ExtensionContentMaterializationV2 = withContext(Dispatchers.Default) {
        ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            // A stored chapter opens from disk, so downloads read offline and skip the network.
            consumer.storedMaterialization(selection, representationId)
                ?: manager.withUserInteractionContext(selection.sourceKey) {
                    consumer.materialize(selection, representationId)
                }
        }
    }

    override suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession = withContext(Dispatchers.Default) {
        val readable = requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.open(materialization)
        val localChapterUrl = encodeTypedLocalChapterUrl(
            publicationKey = materialization.publicationKey,
            acquisitionId = materialization.acquisitionId,
            unitKey = materialization.unitKey,
        )
        val localSnapshot = portableRepository?.currentSnapshot
        val localChapter = localSnapshot?.chapters?.firstOrNull { it.url == localChapterUrl }
        val localLocator = localChapter?.let { chapter ->
            localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastLocator
        }?.takeIf { locator -> readable.content.navigation.indexOf(locator) != null }
        TypedReaderContentSession(
            content = readable.content.copy(
                initialLocator = localLocator ?: readable.content.initialLocator,
            ),
            canonicalText = readable.canonicalText,
            access = readable.access,
            initialVisualPageIndex = localChapter?.lastPageRead,
            initialVisualPageCount = localChapter?.let { chapter ->
                localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastPageCount
            },
            remoteAssetScope = manager.contentNetworkScopeForSource(materialization.sourceKey),
        )
    }

    override suspend fun resolveManga(item: BrowseManga): Long? {
        require(item.sourceKey == null) {
            "Extension v2 publications must use the exact details/unit/content workflow"
        }
        return mangaResolver.resolve(item)
    }

    /** The current index entry of a generic package [extensionId] and its repository; null for legacy indexes. */
    private suspend fun indexEntryFor(extensionId: String): Pair<ExtensionRepository, PluginIndexEntry>? {
        val descriptor = descriptors[extensionId] ?: return null
        val repository = repositoryStore.list().firstOrNull { it.baseUrl == descriptor.repositoryBaseUrl } ?: return null
        val entries = when (val index = repositorySnapshots[repository.baseUrl]?.index) {
            is RepositoryIndex.Plugins -> index.entries
            is RepositoryIndex.Combined -> index.plugins
            else -> return null
        }
        val entry = entries.firstOrNull { manager.installKeyFor(repository, it) == extensionId } ?: return null
        return (repository to entry).takeIf { entry.matchesDescriptor(descriptor) }
    }

    override suspend fun updatePermissionPreview(extensionId: String): dev.shinsou.kmp.ui.UpdatePermissionPreview? =
        withContext(Dispatchers.Default) {
            operationMutex.withLock {
                val (repository, entry) = indexEntryFor(extensionId) ?: return@withLock null
                val preview = manager.updateReviewPreview(repository, entry) ?: return@withLock null
                dev.shinsou.kmp.ui.UpdatePermissionPreview(preview.review, permissionDetails(preview.stored))
            }
        }

    override suspend fun installReviewedUpdate(
        extensionId: String,
        preview: dev.shinsou.kmp.ui.UpdatePermissionPreview,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): dev.shinsou.kmp.plugin.events.PluginEventGrantReview? = withContext(Dispatchers.Default) {
        installExtension(extensionId)
        val pending = manager.pendingEventGrantReview(extensionId) ?: return@withContext null
        val expected = preview.review
        // The artifact downloaded must ask for exactly what the user just reviewed.
        val same = pending.artifact.packageId == expected.artifact.packageId &&
            pending.artifact.version == expected.artifact.version &&
            pending.artifact.sha256 == expected.artifact.sha256 &&
            pending.requestedPermissions == expected.requestedPermissions &&
            pending.requestedRuntimePermissions == expected.requestedRuntimePermissions &&
            pending.requestedContentHostSuffixes == expected.requestedContentHostSuffixes &&
            pending.sourceKeys == expected.sourceKeys
        if (!same || !selection.isWithin(pending)) return@withContext pending
        approvePluginEventGrantReview(extensionId, pending, selection.hostPermissions, selection)
        null
    }

    override suspend fun installExtension(extensionId: String): Unit = withContext(Dispatchers.Default) {
        operationMutex.withLock installOperation@{
        ensureInstalledLoaded()
        val descriptor = descriptors[extensionId]
            ?: throw IllegalArgumentException("Unknown extension: $extensionId")
        // A second tap can queue behind an in-flight install. Once the first succeeds, make the
        // queued call a no-op instead of downloading and installing the same package again.
        if (descriptor.state == ExtensionState.INSTALLED) return@installOperation
        val repositoryBaseUrl = descriptor.repositoryBaseUrl
            ?: throw IllegalStateException("Extension '$extensionId' has no repository")
        val repository = repositoryStore.list().firstOrNull { it.baseUrl == repositoryBaseUrl }
            ?: throw IllegalStateException("Repository is no longer configured: $repositoryBaseUrl")
        val displayed = repositorySnapshots[repository.baseUrl]
            ?: throw IllegalStateException("Extension repository must be refreshed before installing")
        val installedVersion = when (val index = displayed.index) {
            is RepositoryIndex.Plugins -> {
                val entry = index.entries.firstOrNull { manager.installKeyFor(repository, it) == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                require(entry.matchesDescriptor(descriptor)) {
                    "Extension '$extensionId' changed in the repository; refresh before installing"
                }
                manager.install(repository, entry, displayed.admission)
                entry.version
            }

            is RepositoryIndex.Legacy -> {
                val entry = index.entries.firstOrNull { it.pkg == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                require(entry.matchesDescriptor(descriptor)) {
                    "Extension '$extensionId' changed in the repository; refresh before installing"
                }
                manager.installLegacy(repository, entry)
                entry.version
            }

            is RepositoryIndex.Combined -> {
                val entry = index.plugins.firstOrNull { manager.installKeyFor(repository, it) == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                require(entry.matchesDescriptor(descriptor)) {
                    "Extension '$extensionId' changed in the repository; refresh before installing"
                }
                manager.install(repository, entry, displayed.admission)
                entry.version
            }
        }
        // The index used above is already authoritative for this operation. Reusing the current
        // descriptor avoids fetching the same index again and rescanning every installed package.
        descriptors = descriptors + (
            extensionId to descriptor.copy(
                state = ExtensionState.INSTALLED,
                installedVersion = installedVersion,
            )
        )
        // Installing one runtime cannot change the UI settings of every other live runtime. Reuse
        // projections only when the exact CatalogueSource instance is still installed; an update,
        // trust change, source-id collision, or replacement therefore rebuilds the affected source.
        rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun pendingPluginEventGrantReview(extensionId: String) =
        withContext(Dispatchers.Default) {
            manager.pendingEventGrantReview(extensionId)
        }

    override suspend fun approvePluginEventGrantReview(
        extensionId: String,
        expectedReview: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
        permissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission>,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection?,
    ): Unit = withContext(Dispatchers.Default) {
        // Approval can synchronously read durable package/admission state and construct the
        // platform runtime. Keep that work off the UI dispatcher, and share the adapter's
        // operation lock so a queued uninstall cannot revoke the artifact halfway through its
        // exact-digest approval/reload transaction.
        operationMutex.withLock {
            manager.approveEventGrantReview(extensionId, expectedReview, permissions, selection)
            rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun pluginPermissionDetails(
        extensionId: String,
        review: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
    ): dev.shinsou.kmp.ui.PluginPermissionDetails? = withContext(Dispatchers.Default) {
        val stored = manager.installedPlugins().firstOrNull { it.manifest.id == extensionId } ?: return@withContext null
        if (stored.metadata.installedSha256 != review.artifact.sha256) return@withContext null
        permissionDetails(stored)
    }

    override suspend fun installedPluginPermissions(extensionId: String): dev.shinsou.kmp.ui.InstalledPluginPermissions? =
        withContext(Dispatchers.Default) {
            val stored = manager.installedPlugins().firstOrNull { it.manifest.id == extensionId } ?: return@withContext null
            val granted = manager.grantedSelection(extensionId) ?: return@withContext null
            val review = manager.currentEventGrantReview(extensionId) ?: return@withContext null
            dev.shinsou.kmp.ui.InstalledPluginPermissions(review, granted, permissionDetails(stored))
        }

    override suspend fun narrowPluginPermissions(
        extensionId: String,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): Unit = withContext(Dispatchers.Default) {
        operationMutex.withLock {
            manager.narrowGrant(extensionId, selection)
            rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    private suspend fun permissionDetails(stored: StoredPlugin): dev.shinsou.kmp.ui.PluginPermissionDetails {
        val sources = stored.manifest.sources.orEmpty()
        val policies = sources.map { it.networkPolicy() }
        val publisher = stored.metadata.publisherId?.let { id -> packageSecurity?.publishers?.publisher(id) }
        val mode = packageSecurity?.publishers?.globalMode()
        return dev.shinsou.kmp.ui.PluginPermissionDetails(
            packageName = stored.manifest.name,
            version = stored.manifest.version,
            publisherDisplayFingerprint = publisher?.let { PublisherFingerprint.display(it.currentFingerprint) },
            publisherState = if (publisher == null || mode == null) null else when (publisher.status(mode)) {
                PublisherTrustStatus.TRUSTED -> PublisherTrustState.TRUSTED
                PublisherTrustStatus.PENDING_FINGERPRINT_MATCH -> PublisherTrustState.PENDING_FINGERPRINT
                PublisherTrustStatus.KEY_REVOKED -> PublisherTrustState.KEY_REVOKED
                PublisherTrustStatus.KEY_REPLACED -> PublisherTrustState.KEY_REPLACED
            },
            fingerprintVerified = publisher != null && publisher.verification != PublisherVerification.TOFU,
            repositoryUrl = stored.metadata.repositoryBaseUrl,
            requestOrigins = sources.flatMap { it.declaredRequestOrigins() }.distinct().sorted(),
            contentOrigins = policies.flatMap { it.contentOrigins }.distinct().sorted(),
            credentialOrigins = policies.flatMap { it.credentialOrigins }.distinct().sorted(),
            browserSessionOrigins = policies.flatMap { it.browserSessionOrigins }.distinct().sorted(),
            browserImageOrigins = sources.flatMap { it.browserImageOrigins }.distinct().sorted(),
            inDevelopment = stored.metadata.publisherId == null && PluginInstallKeys.isDeveloperKey(stored.manifest.id),
        )
    }

    override suspend fun uninstallExtension(extensionId: String): Unit = withContext(Dispatchers.Default) {
        operationMutex.withLock {
            manager.uninstall(extensionId)
            trustStore.revokeAll(extensionId)
            // The authenticated index already backing the visible row is authoritative for this
            // local mutation. Do not refetch every repository (or rescan unrelated packages): a
            // repository-backed row becomes available, while an installed-only row disappears.
            val descriptor = descriptors[extensionId]
            val repositories = repositoryStore.list().associateBy { it.baseUrl }
            descriptors = if (descriptor != null && repositorySnapshots.any { (baseUrl, snapshot) ->
                    repositories[baseUrl]?.let { repository -> snapshot.containsExtension(repository, extensionId) } == true
                }
            ) {
                descriptors + (extensionId to descriptor.copy(
                    state = ExtensionState.AVAILABLE,
                    installedVersion = null,
                ))
            } else {
                descriptors - extensionId
            }
            rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean): Unit =
        operationMutex.withLock {
            // `trusted` is an execution grant, not a claim about repository authorship. Even a
            // package with a matching digest must be unloaded when this grant is revoked.
            manager.setPluginTrusted(extensionId, trusted)
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun migrateManga(mangaId: Long, target: BrowseManga) {
        if (target.sourceKey == null) {
            migrationHandler.migrate(mangaId, target)
        } else {
            migrateExtensionPublicationV2(mangaId, target)
        }
        operationMutex.withLock { rebuildSnapshot(errorMessage = null) }
    }

    /**
     * Moves an extension-backed library title to another exact source publication: the same row
     * (categories, tracking, notes) is re-bound, and every chapter that has a counterpart keeps
     * its read mark, page and history under the target's unit identity.
     */
    private suspend fun migrateExtensionPublicationV2(mangaId: Long, target: BrowseManga) {
        val repository = checkNotNull(portableRepository) { "Library storage is unavailable" }
        val targetKey = requireNotNull(target.sourceKey)
        val targetRemoteId = requireNotNull(target.remotePublicationId) { "The target has no publication id" }
        val before = repository.currentSnapshot
        val manga = before.mangas.firstOrNull { it.id == mangaId }
            ?: throw IllegalArgumentException("Unknown manga: $mangaId")
        val current = decodeExtensionLibraryPublicationUrl(manga.url)
        require(manga.source == dev.shinsou.kmp.local.LOCAL_SOURCE_ID && current != null) {
            "Only an extension-backed title can migrate to an extension source"
        }
        require(current.sourceKey != targetKey || current.remotePublicationId != targetRemoteId) {
            "The target is the title's current source publication"
        }
        val units = mutableListOf<dev.shinsou.kmp.plugin.v2.RemoteUnitV2>()
        var page = 0
        while (true) {
            val result = extensionUnitsV2(targetKey, targetRemoteId, page)
            units += result.items
            if (!result.hasNextPage || result.items.isEmpty()) break
            // An incomplete chapter list would read as "no counterpart" and drop read chapters.
            check(++page < MAX_MIGRATION_UNIT_PAGES) {
                "The target source lists more chapter pages than migration reads; nothing was changed"
            }
        }
        check(units.isNotEmpty()) { "The target source returned no chapters" }
        val details = try {
            extensionDetailsV2(targetKey, targetRemoteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null // the catalogue row the user picked still names and pictures the title
        }
        val latest = repository.currentSnapshot
        val plan = dev.shinsou.kmp.plugin.v2.planExtensionLibraryMigrationV2(
            oldChapters = latest.chapters.filter { it.mangaId == mangaId },
            targetSourceKey = targetKey,
            targetRemotePublicationId = targetRemoteId,
            targetUnits = units,
        )
        val targetPublication = dev.shinsou.kmp.plugin.v2.extensionPublicationKey(targetKey, targetRemoteId)
        check(
            latest.mangas.none { row ->
                row.id != mangaId && row.source == manga.source && (
                    row.url == plan.libraryUrl ||
                        decodeExtensionLibraryPublicationUrl(row.url)?.publicationKey == targetPublication ||
                        dev.shinsou.kmp.local.decodeTypedLocalPublicationUrl(row.url) == targetPublication
                    )
            },
        ) { "The target title is already in the library" }
        repository.rebindLibraryTitle(
            mangaId = mangaId,
            expectedUrl = manga.url,
            rewrittenChapters = plan.moved,
            removedChapterIds = plan.droppedChapterIds,
            transform = { row ->
            row.copy(
                url = plan.libraryUrl,
                title = details?.title?.takeIf(String::isNotBlank) ?: target.title,
                thumbnailUrl = details?.thumbnailUrl ?: target.thumbnailUrl ?: row.thumbnailUrl,
                author = details?.author ?: target.author,
                artist = details?.artist ?: target.artist,
                description = details?.description ?: target.description,
                genre = details?.genre ?: target.genre,
            )
            },
        )
    }

    override suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>): Unit =
        operationMutex.withLock {
            val normalizedValues = values.mapValues { (key, value) ->
                if (key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE) {
                    SourceNetworkOverride.fromStored(value, SourceNetworkOverride.GLOBAL).storedValue
                } else {
                    value
                }
            }
            val v2Scope = requireV2SourceScope(sourceId)
            val storage = storageForSourceUi(sourceId)
            if (v2Scope != null) {
                normalizedValues.forEach { (key, value) ->
                    storage.setPreference(v2Scope.second, key, value)
                }
            } else {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                normalizedValues.forEach { (key, value) ->
                    storage.setPreference(sourceId, key, value)
                }
            }
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun saveSourceCredentials(
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceCredentialsResult(sourceId, username, password).succeeded

    override suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult = try {
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            requireV2SourceScope(sourceId)
        }
        val storageId = v2Scope?.second ?: sourceId
        val supportsLogin = if (v2Scope != null) {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
            }
            ExtensionCapability.LOGIN in source.descriptor.capabilities
        } else {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            }
            (source as? LoginSource)?.supportsLogin == true
        }
        val credential = if (supportsLogin) {
            secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                storageForSourceUi(sourceId).getCredential(storageId)
            }
        } else {
            null
        }
        val cookies = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
            storageForSourceUi(sourceId).getCookies(storageId)
        }
        SourceSecretsResult(
            secrets = SourceSecrets(
                credential = credential?.let { BrowseSourceCredential(it.username, it.password) },
                cookies = cookies.map(::toBrowseCookie),
            ),
        )
    } catch (failure: SourceLoginStageException) {
        SourceSecretsResult(failureStage = failure.stage)
    }

    override suspend fun saveSourceCredentialsResult(
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = true)
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun saveSourceCredentialsInternal(
        sourceId: Long,
        username: String,
        password: String,
        dismissLegacy: Boolean,
    ): SourceLoginResult = operationMutex.withLock {
        require(username.isNotBlank()) { "Username cannot be blank" }
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            requireV2SourceScope(sourceId)
        }
        val result = if (v2Scope != null) {
            val (sourceKey, storageId) = v2Scope
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(sourceKey)) {
                    "Unknown extension v2 source: ${sourceKey.canonicalId}"
                }
            }
            loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                check(ExtensionCapability.LOGIN in source.descriptor.capabilities) {
                    "Source does not implement the login contract: ${sourceKey.canonicalId}"
                }
            }
            val previous = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                storageForSourceUi(sourceId).getCredential(storageId)
            }
            secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                storageForSourceUi(sourceId).setCredential(storageId, PluginCredential(username, password))
            }
            val loginResult = try {
                loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                    manager.withUserInteractionContext(sourceKey) {
                        source.login(v2LoginCredentials(storageId))
                    }
                }
            } catch (failure: SourceLoginStageException) {
                restoreCredential(storageId, previous, failure)
                throw failure
            }
            if (!loginResult.loggedIn) {
                restoreCredential(storageId, previous)
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage)
        } else {
            val loginSource = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                val source = manager.source(sourceId)
                    ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }
                    ?: error("Source does not implement the login contract: $sourceId")
            }
            val loginResult = loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                manager.withUserInteractionContext(sourceId) {
                    loginSource.loginResult(username, password)
                }
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage?.boundedLoginError())
        }
        if (result.succeeded) {
            val storageId = v2Scope?.second ?: sourceId
            // v2 login needs the credential in storage before the call; v1 stores it only after
            // success. Both paths converge here so the settings projection stays consistent.
            if (v2Scope == null) secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                storageForSourceUi(sourceId).setCredential(storageId, PluginCredential(username, password))
            }
            if (dismissLegacy) loginRequestCoordinator.dismiss(sourceId)
        }
        loginStage(SourceLoginFailureStage.REFRESH_SOURCE_STATE) {
            rebuildSnapshot(errorMessage = null)
        }
        result
    }

    private suspend fun restoreCredential(
        storageId: Long,
        previous: PluginCredential?,
        originalFailure: SourceLoginStageException? = null,
    ) {
        try {
            secureStorageStage(SourceLoginFailureStage.RESTORE_CREDENTIALS) {
                val storage = storageForSourceUi(storageId)
                if (previous == null) storage.clearCredential(storageId)
                else storage.setCredential(storageId, previous)
            }
        } catch (restoreFailure: SourceLoginStageException) {
            originalFailure?.let(restoreFailure::addSuppressed)
            throw restoreFailure
        }
    }

    private suspend inline fun <T> loginStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private suspend inline fun <T> secureStorageStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        withTimeout(SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw SourceLoginStageException(stage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private fun String.boundedLoginError(): String? =
        filterNot(Char::isISOControl).trim().take(512).takeIf(String::isNotBlank)

    override suspend fun logoutSource(sourceId: Long): Unit = operationMutex.withLock {
        val v2Scope = requireV2SourceScope(sourceId)
        val result = runCatching {
            if (v2Scope != null) {
                val source = requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
                if (ExtensionCapability.LOGIN in source.descriptor.capabilities) source.logout()
            } else {
                val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }?.logout()
            }
        }
        val storageId = v2Scope?.second ?: sourceId
        storageForSourceUi(sourceId).clearCredential(storageId)
        rebuildSnapshot(errorMessage = null)
        result.getOrThrow()
    }

    override suspend fun setSourceCookie(sourceId: Long, cookie: BrowseSourceCookie): Unit =
        operationMutex.withLock {
            val storageId = requireV2SourceScope(sourceId)?.second ?: run {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                sourceId
            }
            require(cookie.name.isNotBlank()) { "Cookie name cannot be blank" }
            require(cookie.domain.isNotBlank()) { "Cookie domain cannot be blank" }
            storageForSourceUi(sourceId).setCookie(
                storageId,
                PluginCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path.ifBlank { "/" },
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                ),
            )
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun importSourceWebChallengeSession(
        sourceId: Long,
        capability: dev.shinsou.kmp.ui.SourceWebChallengeCapability,
        cookies: List<BrowseSourceCookie>,
        userAgent: String,
        localStorage: Map<String, String>,
    ): Unit = operationMutex.withLock {
        val sourceKey = requireNotNull(v2SourceKeyFor(sourceId) ?: manager.exactSourceKeyForLegacyId(sourceId)) {
            "Generic sources cannot import an embedded browser session"
        }
        manager.importWebChallengeSession(sourceKey, capability, cookies, userAgent, localStorage)
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun cancelSourceWebChallenge(
        capability: dev.shinsou.kmp.ui.SourceWebChallengeCapability,
    ) {
        manager.cancelWebChallenge(capability)
    }

    override suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String): Unit =
        operationMutex.withLock {
            val storageId = requireV2SourceScope(sourceId)?.second ?: run {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                sourceId
            }
            storageForSourceUi(sourceId).deleteCookie(storageId, name, domain)
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun clearSourceCookies(sourceId: Long): Unit = operationMutex.withLock {
        val storageId = requireV2SourceScope(sourceId)?.second ?: run {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            sourceId
        }
        storageForSourceUi(sourceId).clearCookies(storageId)
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun isSourceWebChallengeAvailable(sourceId: Long): Boolean {
        val sourceKey = v2SourceKeyFor(sourceId)
            ?: manager.exactSourceKeyForLegacyId(sourceId)
            ?: return false
        return manager.authorizeWebChallenge(sourceKey)
    }

    override suspend fun sourceWebChallenge(
        sourceId: Long,
        username: String?,
        password: String?,
    ): SourceWebChallengeRequest? {
        val sourceKey = requireNotNull(v2SourceKeyFor(sourceId) ?: manager.exactSourceKeyForLegacyId(sourceId)) {
            "Generic sources cannot receive an embedded browser challenge"
        }
        val authorization = requireNotNull(manager.issueWebChallenge(sourceKey)) {
            "Browser challenge is not authorized for this exact plugin artifact"
        }
        var requestBuilt = false
        return try {
            val source = authorization.source
            val storageId = authorization.storageId
            val sourceName = source.descriptor.displayName
            val baseUrl = source.descriptor.baseUrl.orEmpty()
            val challengeUrl = source.webChallengeUrl() ?: baseUrl
            // Read browser state only after the manager has proven the exact reviewed artifact.
            // The authorization never exposes a raw storage handle: this host adapter uses its
            // already-injected storage with the manager-issued fixed reviewed scope.
            val boundStorage = authorization.storage
            val sourceHeaders = boundStorage.getWebChallengeUserAgent(storageId)
                ?.let(::normalizePluginUserAgent)
                ?.let { mapOf("User-Agent" to it) }
                ?: source.webChallengeUserAgent()
                ?.let { mapOf("User-Agent" to it) }
                .orEmpty()
            val referer = baseUrl
            val supportsLogin = ExtensionCapability.LOGIN in source.descriptor.capabilities
            val localStorageKeys = authorization.localStorageKeys
            val requiredLocalStorageKeys = authorization.requiredLocalStorageKeys
            val sourceOrigin = runCatching { Url(baseUrl.trim()) }.getOrNull() ?: return null
            val target = runCatching { Url(challengeUrl.trim()) }.getOrNull() ?: return null
            if (!target.protocol.name.equals("https", ignoreCase = true) || target.host.isBlank()) return null
            require(target.sameOrigin(sourceOrigin)) {
                "Web challenge URL must use the source's exact origin"
            }
            require(target.sameOrigin(Url(authorization.canonicalOrigin))) {
                "Web challenge URL is outside the issued origin"
            }
            val built = requestBuilder.build(
                sourceId = storageId,
                request = PluginHttpRequest("GET", target.toString()),
                sourceHeaders = sourceHeaders,
                referer = referer,
            )
            val userAgent = built.transportRequest.headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                .orEmpty()
            // This method is called only from the explicit Web challenge action. Decrypt credentials
            // on demand here; catalogue discovery and ordinary snapshot refreshes never receive them.
            val hasSuppliedCredentialFields = username != null || password != null
            val suppliedCredential = username
                ?.takeIf(String::isNotBlank)
                ?.let { suppliedUsername ->
                    password
                        ?.takeIf(String::isNotEmpty)
                        ?.let { suppliedPassword -> PluginCredential(suppliedUsername, suppliedPassword) }
                }
            val credential = if (supportsLogin) {
                if (hasSuppliedCredentialFields) {
                    suppliedCredential
                } else {
                    boundStorage.getCredential(storageId)
                        ?.takeIf { it.username.isNotBlank() && it.password.isNotEmpty() }
                }
            } else {
                null
            }
            SourceWebChallengeRequest(
                capability = authorization.capability,
                sourceId = sourceId,
                sourceName = sourceName,
                url = target.toString(),
                userAgent = userAgent,
                cookies = boundStorage.getCookies(storageId).map(::toBrowseCookie),
                requiredCookieName = "cf_clearance".takeIf {
                    requiresCloudflareClearance(sourceKey)
                },
                localStorageKeys = localStorageKeys.sorted(),
                requiredLocalStorageKeys = requiredLocalStorageKeys,
                username = credential?.username,
                password = credential?.password,
                embeddedPolicy = dev.shinsou.kmp.ui.WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS,
                allowedNavigationOrigins = setOf(authorization.canonicalOrigin),
                allowedSubresourceOrigins = authorization.allowedSubresourceOrigins,
            ).also { requestBuilt = true }
        } finally {
            // Parsing, secure-storage reads, and request construction remain fallible. Do not
            // leave a capability live unless a complete request is actually returned to the UI.
            if (!requestBuilt) withContext(NonCancellable) {
                manager.cancelWebChallenge(authorization.capability)
            }
        }
    }

    private fun normalizeWebChallengeStorageKeyDeclaration(values: Iterable<String>): Set<String> =
        values.asSequence()
            .map(String::trim)
            .onEach { key ->
                require(key.length in 1..64 && key.all { it.isLetterOrDigit() || it in "._-" }) {
                    "Invalid browser storage key declaration"
                }
            }
            .distinct()
            .take(9)
            .toSet()
            .also { require(it.size <= 8) { "Too many browser storage keys were declared" } }

    private fun requiresCloudflareClearance(sourceKey: SourceKey): Boolean =
        sourceKey.packageId == "zh.bilimanga" && sourceKey.sourceId == "zh.bilimanga.manga" ||
            sourceKey.packageId == "zh.bilimanga.manga" &&
            sourceKey.sourceId == BILIMANGA_MANGA_SOURCE_ID.toString()

    private fun Url.sameOrigin(other: Url): Boolean =
        protocol.name.equals(other.protocol.name, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port

    private suspend fun refreshLocked(): Unit =
        withContext(Dispatchers.Default) {
        mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
        try {
            ensureInstalledLoaded()
            reconcilePortableRepositories()
            // Publish installed/local sources before touching remote repositories. A cold launch
            // can therefore render immediately while discovery continues in the background.
            rebuildSnapshot(errorMessage = null, isRefreshing = true)
            val repositories = repositoryStore.list()
            manager.retainRepositoryPackages(repositories.map { it.baseUrl })
            val selected = repositoryStore.selected().takeIf { selected ->
                repositories.any { it.baseUrl == selected }
            }
            if (selected != repositoryStore.selected()) repositoryStore.select(selected)
            val visibleRepositories = selected?.let { selectedId ->
                repositories.filter { it.baseUrl == selectedId }
            } ?: repositories
            // A repository is optional metadata; it must not hold the reader/browse UI hostage
            // when a host is offline, a GitHub branch moved, or a LAN server is unreachable.
            withTimeoutOrNull(BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS) {
                // Publishers' rotations, withdrawals and key revocations first, so this refresh
                // already sees the current key and an installed package goes inert at once.
                packageSecurity?.let { security ->
                    for (repository in repositories) {
                        // Re-evaluated as soon as a change is stored, even if a later fetch times out.
                        security.syncPublisherDocuments(repository.baseUrl) { manager.loadInstalled() }
                    }
                }
                val refreshed = manager.refreshWithAdmissions(visibleRepositories)
                descriptors = refreshed.descriptors.associateBy { it.id }
                repositorySnapshots = refreshed.snapshots
                repositoriesNeedingTrust = refreshed.repositoriesNeedingTrust
            }
            rebuildSnapshot(errorMessage = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            rebuildSnapshot(errorMessage = error.message ?: "Unable to refresh extensions")
            throw error
        } finally {
            // Cancellation makes further suspension unsafe, but this synchronous StateFlow update
            // must still release the loading scrim. A successful rebuild already cleared the flag.
            val current = mutableState.value
            if (current.isRefreshing) mutableState.value = current.copy(isRefreshing = false)
        }
    }

    /**
     * On upgraded installs, imports the legacy KV list once when the portable snapshot has never
     * owned repository state. After that one-time bridge, the snapshot is authoritative: every
     * refresh replaces the KV mirror exactly, including removals made by restore or sync.
     */
    private suspend fun reconcilePortableRepositories() {
        reconcilePortableRepositoryStorage()
    }

    private suspend fun reconcilePortableRepositoryStorage() {
        portableRepository?.let { portable ->
            val migrationComplete = keyValueStore.getString(PORTABLE_REPOSITORY_MIGRATION_KEY) == "true"
            if (!migrationComplete) {
                val legacyRepositories = repositoryStore.list()
                    .distinctBy { it.baseUrl }
                    .map { it.toPortableRepository() }
                while (legacyRepositories.isNotEmpty()) {
                    val current = portable.currentSnapshot
                    if (current.extensionRepositories.isNotEmpty()) break
                    val migrated = portable.replaceSnapshotIfRevision(
                        expectedRevision = current.revision,
                        imported = current.copy(extensionRepositories = legacyRepositories),
                    )
                    if (migrated != null) break
                }
                keyValueStore.putString(PORTABLE_REPOSITORY_MIGRATION_KEY, "true")
            }
            removeLegacyBundledRepository(portable)
            mirrorPortableRepositoriesToKeyValue(portable)
        }
    }

    /**
     * Versions before the repository became user-configured silently seeded this URL. Remove that
     * one-time seed from upgraded portable snapshots; repositories added by the user afterwards are
     * not touched because the migration marker is persisted.
     */
    private suspend fun removeLegacyBundledRepository(portable: ShinsouRepository) {
        if (keyValueStore.getString(LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY) == "true") return
        val bundledUrl = normalizeRepositoryInput(LEGACY_BUNDLED_REPOSITORY_URL)
        if (portable.currentSnapshot.extensionRepositories.any { it.baseUrl == bundledUrl }) {
            portable.deleteExtensionRepository(bundledUrl)
        }
        keyValueStore.putString(LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY, "true")
    }

    private suspend fun saveRepository(repository: ExtensionRepository) {
        val portable = portableRepository
        if (portable == null) {
            repositoryStore.put(repository)
        } else {
            portable.upsertExtensionRepository(repository.toPortableRepository())
            mirrorPortableRepositoriesToKeyValue(portable)
        }
        // Added on purpose (possibly after an earlier dismissal): it is no longer a suggestion to hide.
        val added = suggestionKey(repository.baseUrl)
        updateDismissedSuggestions { it - added }
    }

    // ---- repository suggestions from the account ---------------------------------------------

    private fun suggestionKey(url: String): String = runCatching { normalizeRepositoryInput(url) }.getOrDefault(url.trim())

    /** URLs the user removed or dismissed on this device. Device-local: not synced, kept across sign-out. */
    private suspend fun dismissedSuggestions(): Set<String> =
        keyValueStore.getString(DISMISSED_SUGGESTIONS_KEY)
            ?.let { raw ->
                runCatching { Json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
            }
            .orEmpty()
            .toSet()

    private suspend fun updateDismissedSuggestions(change: (Set<String>) -> Set<String>) {
        dismissedSuggestionsMutex.withLock {
            val current = dismissedSuggestions()
            val updated = change(current)
            if (updated != current) {
                keyValueStore.putString(
                    DISMISSED_SUGGESTIONS_KEY,
                    Json.encodeToString(ListSerializer(String.serializer()), updated.sorted()),
                )
            }
        }
    }

    /** The account's repositories minus those this device already has and those it dismissed. */
    private suspend fun repositorySuggestions(): List<BrowseRepositorySuggestion> {
        val directory = accountRepositories ?: return emptyList()
        val suggested = directory.repositories.value
        if (suggested.isEmpty()) return emptyList()
        val local = (portableRepository?.currentSnapshot?.extensionRepositories?.map { it.baseUrl }
            ?: repositoryStore.list().map { it.baseUrl })
            .mapTo(hashSetOf(), ::suggestionKey)
        val dismissed = dismissedSuggestions()
        return suggested
            .filter { suggestionKey(it.url).let { key -> key !in local && key !in dismissed } }
            .distinctBy { suggestionKey(it.url) }
            .map { BrowseRepositorySuggestion(it.url, it.name, it.signingKeyFingerprint.takeIf(String::isNotBlank)) }
    }

    private suspend fun publishRepositorySuggestions() {
        val suggestions = repositorySuggestions()
        // Atomic, so a concurrent snapshot rebuild is not overwritten with stale rows.
        mutableState.update { current ->
            if (current.repositorySuggestions == suggestions) current else current.copy(repositorySuggestions = suggestions)
        }
    }

    private suspend fun isConfiguredRepository(normalizedBaseUrl: String): Boolean =
        repositoryStore.list().any { stored ->
            stored.baseUrl == normalizedBaseUrl ||
                runCatching { normalizeRepositoryInput(stored.baseUrl) == normalizedBaseUrl }.getOrDefault(false)
        }

    private suspend fun deleteRepository(baseUrl: String) {
        val portable = portableRepository
        if (portable == null) {
            repositoryStore.remove(baseUrl)
            return
        }
        portable.deleteExtensionRepository(baseUrl)
        mirrorPortableRepositoriesToKeyValue(portable)
    }

    private suspend fun mirrorPortableRepositoriesToKeyValue(portable: ShinsouRepository) {
        while (true) {
            val snapshot = portable.currentSnapshot
            val repositories = snapshot.extensionRepositories.map { it.toPluginRepository() }
            val stored = repositoryStore.list()
            if (stored != repositories) {
                val selected = repositoryStore.selected()
                stored.forEach { repositoryStore.remove(it.baseUrl) }
                repositories.forEach { repositoryStore.put(it) }
                repositoryStore.select(
                    selected?.takeIf { selectedId -> repositories.any { it.baseUrl == selectedId } },
                )
            }
            if (portable.currentSnapshot.revision == snapshot.revision) return
        }
    }

    private suspend fun ensureInstalledLoaded(): Unit = installedLoadMutex.withLock {
        if (!loadedInstalled) {
            manager.loadInstalled()
            removeLegacyShuYueKeys()
            loadedInstalled = true
        }
    }

    /**
     * Best-effort cleanup of the only fixed keys the removed reviewed ShuYue runtime left on
     * devices. Unknown keys are deliberately not enumerated; failure is irrelevant.
     */
    private suspend fun removeLegacyShuYueKeys() {
        for (key in LEGACY_SHUYUE_KV_KEYS) {
            try {
                keyValueStore.remove(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    /** Whether [extensionId] (an install key, D6) is still offered by this repository's index. */
    private suspend fun RepositoryIndexSnapshot.containsExtension(repository: ExtensionRepository, extensionId: String): Boolean =
        when (val displayed = index) {
            is RepositoryIndex.Plugins -> displayed.entries.any { manager.installKeyFor(repository, it) == extensionId }
            is RepositoryIndex.Legacy -> displayed.entries.any { it.pkg == extensionId }
            is RepositoryIndex.Combined -> displayed.plugins.any { manager.installKeyFor(repository, it) == extensionId }
        }

    private suspend fun rebuildSnapshot(
        errorMessage: String?,
        isRefreshing: Boolean = false,
        reuseUnchangedSources: Boolean = false,
    ): Unit = withContext(Dispatchers.Default) {
        val repositories = repositoryStore.list()
        val repositoryRows = repositories.map { it.toBrowseRepository() }
            .distinctBy(BrowseRepository::url)
        val selectedRowId = repositoryStore.selected()
        val installed = manager.installedPlugins().associateBy { it.manifest.id }
        val extensionDescriptorsV2 = manager.extensionDescriptorsV2()
        val liveV2SourceKeys = extensionDescriptorsV2.flatMap { it.sources }
            .mapTo(linkedSetOf()) { it.sourceKey }
        pruneV2SourceMappings(liveV2SourceKeys)
        val sourceDescriptors = descriptors.values.flatMap { descriptor ->
            descriptor.sources.map { source -> source.id to descriptor }
        }.toMap()
        val rebuiltSourceProjections = linkedMapOf<Long, BrowseSourceProjection>()
        // Text-chapter script sources (A5) are listed with the V2 sources below instead: exactly
        // those the V2 list really contains, so no source can drop out of both lists.
        val scriptTextSourceIds = extensionDescriptorsV2
            .flatMap { it.sources }
            .filter { ContentKind.PLAIN_TEXT in it.supportedContentKinds }
            .mapNotNullTo(hashSetOf()) { it.sourceKey.legacyLongId }
        val legacySources = manager.catalogueSources()
            .filterNot { (it as? TextChapterSource)?.supportsChapterText == true && it.id in scriptTextSourceIds }
            .map { source ->
            val reused = sourceProjections[source.id]
                ?.takeIf { reuseUnchangedSources && it.source === source }
                ?.value
            val projected = reused ?: run {
            val descriptor = sourceDescriptors[source.id]
            val supportsLogin = (source as? LoginSource)?.supportsLogin == true
            val filters = try {
                source.getFilterList().map { it.toBrowseFilter() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val pluginPreferences = (source as? ConfigurableSource)
                ?.let { configurableSource ->
                    try {
                        configurableSource.getPreferenceDefinitions()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                .orEmpty()
                .filterNot { it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE }
                .map { preference -> preference.toBrowsePreference(source.id) }
            val preferences = listOf(networkProxyPreference(source.id)) + pluginPreferences
            BrowseSource(
                id = source.id,
                name = source.name,
                language = source.lang,
                baseUrl = source.baseUrl,
                iconUrl = descriptor?.iconUrl,
                enabled = keyValueStore.getString(sourceEnabledKey(source.id))?.toBooleanStrictOrNull() ?: true,
                isNsfw = descriptor?.nsfw ?: false,
                supportsLatest = source.supportsLatest,
                supportsLogin = supportsLogin,
                requiresBrowserSessionLogin = source.requiredWebChallengeLocalStorageKeys.isNotEmpty(),
                supportsFavorites = source.supportsFavorites,
                // Credentials and cookies are intentionally not decrypted while rebuilding the
                // public source catalogue. Desktop protected storage may need interactive OS
                // authorization; making that part of startup used to hold the complete browse
                // refresh mutex indefinitely. Secret state is accessed only by explicit login,
                // cookie-management, challenge, and network operations.
                credential = null,
                cookies = emptyList(),
                preferences = preferences,
                filters = filters,
                contentType = descriptor?.contentType
                    ?: installed.values.firstOrNull { stored ->
                        stored.manifest.sources.orEmpty().any { it.id == source.id }
                    }?.manifest?.installedContentType()
                    ?: PluginContentType.BOTH,
            )
            }
            rebuiltSourceProjections[source.id] = BrowseSourceProjection(source, projected)
            projected
        }
        val occupiedSourceIds = legacySources.mapTo(hashSetOf(), BrowseSource::id)
        val nativeSources = buildList {
            extensionDescriptorsV2
                .flatMap { it.sources }
                .filterNot { descriptor -> legacySources.any { it.sourceKey == descriptor.sourceKey } }
                .forEach { descriptor ->
                    val sourceKey = descriptor.sourceKey
                    val rowId = v2UiRowId(sourceKey, occupiedSourceIds)
                    val storageId = v2StorageSourceId(sourceKey)
                    val hostSource = manager.extensionSourceV2(sourceKey)
                    val supportsLogin = ExtensionCapability.LOGIN in descriptor.capabilities
                    val preferences = buildList {
                        storageId?.let { add(networkProxyPreference(it)) }
                        if (hostSource != null && ExtensionCapability.PREFERENCES in descriptor.capabilities) {
                            try {
                                hostSource.preferences()
                                    .filterNot {
                                        it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE
                                    }
                                    .forEach { preference ->
                                    storageId?.let { add(preference.toBrowsePreference(it)) }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                // A source's optional preference endpoint must not make the
                                // source list disappear; the raw proxy setting remains usable.
                            }
                        }
                    }
                    val filters = if (hostSource != null && ExtensionCapability.BROWSE in descriptor.capabilities) {
                        try {
                            hostSource.browseOptions().filters.map { it.toBrowseFilter() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Optional filter discovery must not hide an otherwise valid source.
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    add(
                        BrowseSource(
                            id = rowId,
                            name = descriptor.displayName,
                            language = descriptor.languageTag,
                            baseUrl = descriptor.baseUrl.orEmpty(),
                            enabled = keyValueStore.getString(sourceEnabledV2Key(sourceKey))
                                ?.toBooleanStrictOrNull() ?: true,
                            supportsLatest = ExtensionCapability.LATEST in descriptor.capabilities,
                            supportsLogin = supportsLogin,
                            requiresBrowserSessionLogin = hostSource
                                ?.requiredWebChallengeLocalStorageKeys()
                                ?.isNotEmpty() == true,
                            supportsFavorites = ExtensionCapability.FAVORITE in descriptor.capabilities,
                            favoriteBrowseOptionKey = if (ExtensionCapability.FAVORITE in descriptor.capabilities) {
                                DEFAULT_FAVORITE_BROWSE_OPTION_KEY
                            } else {
                                null
                            },
                            // See the legacy-source projection above. Never make repository/source
                            // discovery depend on unlocking protected credential storage.
                            credential = null,
                            cookies = emptyList(),
                            preferences = preferences,
                            filters = filters,
                            sourceKey = sourceKey,
                            contentType = descriptor.supportedContentKinds.toPluginContentType(),
                        ),
                    )
                }
        }
        // Which repository provided each source: the installed package that declares it.
        val repositoryBySourceId = installed.values.flatMap { stored ->
            val repository = stored.metadata.repositoryBaseUrl ?: return@flatMap emptyList<Pair<Long, String>>()
            stored.manifest.sources.orEmpty().map { it.id to repository }
        }.toMap()
        fun BrowseSource.withRepository(): BrowseSource = copy(
            repositoryId = repositoryId
                ?: sourceKey?.legacyLongId?.let(repositoryBySourceId::get)
                ?: sourceKey?.sourceId?.toLongOrNull()?.let(repositoryBySourceId::get)
                ?: (if (sourceKey == null) repositoryBySourceId[id] ?: sourceDescriptors[id]?.repositoryBaseUrl else null),
        )
        val sources = (legacySources + nativeSources)
            .map { it.withRepository() }
            .distinctBy(BrowseSource::identityKey)
            .sortedWith(compareBy<BrowseSource> { it.language }.thenBy { it.name.lowercase() })
        val extensions = descriptors.values.map { descriptor ->
            val local = installed[descriptor.id]
            // A verified digest is integrity state, not permission to execute sensitive host
            // services. The UI's execution switch reflects the exact-artifact approval gate.
            val trusted = local != null && manager.hasExecutionApproval(descriptor.id)
            BrowseExtension(
                id = descriptor.id,
                name = descriptor.name,
                version = descriptor.version,
                language = descriptor.lang,
                iconUrl = descriptor.iconUrl,
                installed = descriptor.state != ExtensionState.AVAILABLE,
                updateAvailable = descriptor.state == ExtensionState.UPDATE_AVAILABLE,
                trusted = trusted,
                isNsfw = descriptor.nsfw,
                sourceIds = descriptor.sources.map { it.id },
                contentType = descriptor.contentType,
                inDevelopment = PluginInstallKeys.isDeveloperKey(descriptor.id),
                repositoryId = descriptor.repositoryBaseUrl,
            )
        }
        sourceProjections = rebuiltSourceProjections
        mutableState.value = BrowseSnapshot(
            repositories = repositoryRows,
            selectedRepositoryId = selectedRowId,
            repositorySuggestions = repositorySuggestions(),
            sources = sources,
            extensions = extensions,
            migrations = migrationProvider.candidates().map { it.resolvedAgainst(sources) },
            isRefreshing = isRefreshing,
            errorMessage = errorMessage,
        )
    }

    private fun ExtensionRepository.toBrowseRepository(): BrowseRepository = BrowseRepository(
        id = baseUrl,
        url = baseUrl,
        name = name,
        website = website,
        signingFingerprint = signingKeyFingerprint.takeIf { it.isNotBlank() },
        needsPublisherTrust = baseUrl in repositoriesNeedingTrust,
    )

    private fun ExtensionRepository.toPortableRepository(): ExtensionRepo = ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
    )

    private fun ExtensionRepo.toPluginRepository(): ExtensionRepository = ExtensionRepository(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
    )

    private suspend fun mapBrowseManga(source: CatalogueSource, mangas: List<SManga>): List<BrowseManga> {
        val mapped = ArrayList<BrowseManga>(mangas.size)
        for (manga in mangas) mapped += manga.toBrowseManga(source)
        return mapped
    }

    private suspend fun SManga.toBrowseManga(source: CatalogueSource): BrowseManga {
        val resolvedThumbnail = thumbnailUrl?.let { resolveSourceAsset(source.baseUrl, it) }
        return BrowseManga(
            sourceId = source.id,
            url = url,
            title = title,
            // Keep the source URL inert. The UI resolves it only through loadPluginThumbnail,
            // whose exact-source content policy strips cookies, authorization and proxy secrets.
            thumbnailUrl = resolvedThumbnail,
            thumbnailHeaders = emptyMap(),
            author = author ?: artist,
        )
    }

    private fun normalizeRepositoryInput(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val pathWithoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val fileName = pathWithoutQuery.substringAfterLast('/')
        val normalized = if (fileName == "index.json" || fileName == "index.min.json" || fileName == "repo.json") {
            pathWithoutQuery.substringBeforeLast('/').trimEnd('/')
        } else {
            trimmed
        }
        return normalized
    }

    /** Reconciles the immutable row rendered by refresh with the exact candidate being installed. */
    private fun PluginIndexEntry.matchesDescriptor(descriptor: ExtensionDescriptor): Boolean =
        descriptor.admissionFingerprint != null &&
            admissionFingerprint() == descriptor.admissionFingerprint

    private fun LegacyExtensionIndexEntry.matchesDescriptor(descriptor: ExtensionDescriptor): Boolean =
        descriptor.admissionFingerprint != null &&
            admissionFingerprint() == descriptor.admissionFingerprint

    private fun resolveSourceAsset(baseUrl: String, value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> baseUrl.substringBefore(':') + ":" + value
        value.startsWith('/') -> {
            val scheme = baseUrl.substringBefore("://", "https")
            val authority = baseUrl.substringAfter("://", baseUrl).substringBefore('/')
            "$scheme://$authority$value"
        }
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun sourceEnabledKey(sourceId: Long): String = "source.$sourceId.enabled"

    private fun sourceEnabledV2Key(sourceKey: SourceKey): String =
        "source.v2.${Sha256.hex(sourceKey.canonicalId.encodeToByteArray())}.enabled"

    /** Allocates a process-local row id only; every extension operation still uses SourceKey. */
    private fun v2UiRowId(sourceKey: SourceKey, occupied: Set<Long>): Long {
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
    private fun v2StorageSourceId(sourceKey: SourceKey): Long? = sourceKey.legacyLongId

    private fun v2SourceKeyFor(sourceId: Long): SourceKey? =
        v2SourceKeysByRowId[sourceId] ?: v2SourceKeysByStorageId[sourceId]

    private fun requireV2SourceScope(sourceId: Long): Pair<SourceKey, Long>? {
        val sourceKey = v2SourceKeyFor(sourceId) ?: return null
        val storageId = v2StorageSourceId(sourceKey)
            ?: throw IllegalArgumentException("Source settings are unavailable for ${sourceKey.canonicalId}")
        return sourceKey to storageId
    }

    /** Resolve UI state through the manager's exact package/source ownership map. */
    private suspend fun storageForSourceUi(sourceId: Long): PluginStorage {
        val scope = requireV2SourceScope(sourceId)
        if (scope != null) {
            val sourceKey = scope.first
            val ownedSourceId = requireNotNull(sourceKey.legacyLongId) {
                "Generic source has no host-assigned storage id: ${sourceKey.canonicalId}"
            }
            return requireNotNull(manager.storageForSource(ownedSourceId)) {
                "No host-owned storage is available for ${sourceKey.canonicalId}"
            }
        }
        return requireNotNull(manager.storageForSource(sourceId)) {
            "No host-owned storage is available for source $sourceId"
        }
    }

    private fun v2LoginCredentials(scopeId: Long): LoginCredentialsV2 = LoginCredentialsV2(
        usernameReference = "$V2_USERNAME_REFERENCE_PREFIX$scopeId",
        passwordReference = "$V2_PASSWORD_REFERENCE_PREFIX$scopeId",
    )

    private fun pruneV2SourceMappings(liveSourceKeys: Set<SourceKey>) {
        v2SourceRowIds.keys.retainAll(liveSourceKeys)
        v2SourceKeysByRowId.entries.removeAll { it.value !in liveSourceKeys }
        v2SourceKeysByStorageId.entries.removeAll { it.value !in liveSourceKeys }
    }

    private fun toBrowseCookie(cookie: PluginCookie): BrowseSourceCookie = BrowseSourceCookie(
        name = cookie.name,
        value = cookie.value,
        domain = cookie.domain,
        path = cookie.path,
        expiresAtEpochMillis = cookie.expiresAtEpochMillis,
        secure = cookie.secure,
        httpOnly = cookie.httpOnly,
        hostOnly = cookie.hostOnly,
    )

    private suspend fun networkProxyPreference(sourceId: Long): BrowseSourcePreference {
        val stored = storageForSourceUi(sourceId).getPreference(
            sourceId,
            ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
        )
        val value = SourceNetworkOverride.fromStored(
            stored,
            SourceNetworkOverride.GLOBAL,
        ).storedValue
        return BrowseSourcePreference(
            key = ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
            title = "Cloudflare Worker proxy",
            summary = "Follow global uses the Advanced setting. Force enable and Force disable override it for this source.",
            value = value,
            choices = listOf("Follow global", "Force enable", "Force disable"),
            choiceValues = listOf("global", "on", "off"),
            kind = SourcePreferenceKind.Choice,
        )
    }

    private suspend fun SourcePreference.toBrowsePreference(sourceId: Long): BrowseSourcePreference = when (this) {
        is SourcePreference.TextField -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue,
            kind = SourcePreferenceKind.Text,
        )
        is SourcePreference.Toggle -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue.toString(),
            kind = SourcePreferenceKind.Toggle,
        )
        is SourcePreference.Select -> BrowseSourcePreference(
            key = key,
            title = title,
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue,
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.Choice,
        )
        is SourcePreference.MultiSelect -> BrowseSourcePreference(
            key = key,
            title = title,
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValues.sorted().joinToString(","),
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.MultiChoice,
        )
    }

    private suspend fun PreferenceV2.toBrowsePreference(sourceId: Long): BrowseSourcePreference {
        val stored = storageForSourceUi(sourceId).getPreference(sourceId, key)
        return BrowseSourcePreference(
            key = key,
            title = label,
            value = stored ?: value.orEmpty(),
            kind = SourcePreferenceKind.Text,
        )
    }

    public companion object {
        /** Keys the removed reviewed ShuYue runtime stored; deleted best-effort on first load. */
        private val LEGACY_SHUYUE_KV_KEYS: List<String> = listOf(
            "plugin.shuyue.v2.installations",
            "plugin.shuyue.v2.repository-urls",
        )
        private const val V2_USERNAME_REFERENCE_PREFIX: String = "shinsou-v2-username-"
        private const val V2_PASSWORD_REFERENCE_PREFIX: String = "shinsou-v2-password-"
        private const val PORTABLE_REPOSITORY_MIGRATION_KEY: String =
            "plugin.repositories.portable-migration.v1"

        private const val DISMISSED_SUGGESTIONS_KEY: String =
            "plugin.repositories.dismissed-suggestions.v1"

        private const val LEGACY_BUNDLED_REPOSITORY_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/master"
        private const val LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY: String =
            "plugin.repositories.remove-bundled-seed.v1"
    }
}

public data class PluginReaderChapterReference(
    val sourceId: Long,
    val chapter: SChapter,
)

public fun interface PluginReaderChapterResolver {
    public suspend fun resolve(mangaId: Long, chapterId: Long): PluginReaderChapterReference?
}

/** Builds reader requests through the same request builder used by the JS bridge and downloader. */
public class PluginContentAdapter(
    private val manager: PluginManager,
    private val chapterResolver: PluginReaderChapterResolver,
    private val requestBuilder: PluginRequestBuilder,
    private val delegate: ContentCallbacks = ContentCallbacks.None,
) : ContentCallbacks {
    override suspend fun refreshLibrary(mangaIds: Set<Long>) = delegate.refreshLibrary(mangaIds)
    override suspend fun refreshManga(mangaId: Long) = delegate.refreshManga(mangaId)
    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? =
        delegate.resolveMangaOriginalUrl(mangaId)
    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? =
        delegate.resolveChapterOriginalUrl(mangaId, chapterId)
    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long) =
        delegate.enqueueDownload(mangaId, chapterId)
    override suspend fun retryDownload(itemId: String) = delegate.retryDownload(itemId)
    override suspend fun removeDownload(itemId: String) = delegate.removeDownload(itemId)
    override suspend fun removeUnfinishedDownload(itemId: String) = delegate.removeUnfinishedDownload(itemId)
    override suspend fun reorderDownloads(orderedIds: List<String>) = delegate.reorderDownloads(orderedIds)
    override suspend fun clearCompletedDownloads() = delegate.clearCompletedDownloads()
    override suspend fun pauseDownloads(paused: Boolean) = delegate.pauseDownloads(paused)

    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        val reference = chapterResolver.resolve(mangaId, chapterId)
            ?: return delegate.loadReaderChapter(mangaId, chapterId)
        val source = manager.source(reference.sourceId)
            ?: throw IllegalStateException("Reader source '${reference.sourceId}' is not loaded")
        val referer = source.headers.header("Referer") ?: reference.chapter.url.takeIf { it.isNotBlank() }
        val contentNetwork = requireNotNull(manager.contentNetworkForSource(source.id)) {
            "No content network policy is registered for source ${source.id}"
        }
        val pages = source.getPageList(reference.chapter).mapIndexed { fallbackIndex, page ->
            ReaderPage(
                index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                // Compatibility readers remain lazy, but the resolver now returns bytes acquired
                // through the same credential-free, origin-pinned content plane as production.
                imageResolver = {
                    // Viewer-backed sources may perform an authenticated network request while
                    // resolving the final image URL. Keep that work inside the page resolver so
                    // opening a chapter does not eagerly fetch every viewer page (or fail the
                    // entire chapter because one later page is temporarily unavailable).
                    val remoteImage = page.imageUrl
                        ?.takeIf(String::isNotBlank)
                        ?: source.resolveImageUrl(page.url)?.takeIf(String::isNotBlank)
                        ?: page.url
                    val metadata = PageRequestMetadata.parse(remoteImage)
                    val resolvedUrl = requireNotNull(resolveSourceHttpUrl(source.baseUrl, metadata.cleanUrl)) {
                        "Reader page URL is not a safe HTTP(S) source URL"
                    }
                    val requestHeaders = metadata.headers.filterKeys(::isSafeCompatibilityContentHeader)
                    val response = contentNetwork.execute(
                        sourceId = source.id,
                        request = PluginHttpRequest(
                            method = "GET",
                            url = resolvedUrl,
                            headers = requestHeaders.withFallbackHeader("Referer", referer),
                        ),
                    )
                    check(response.status in 200..299) {
                        "Unable to load reader page: HTTP ${response.status}"
                    }
                    require(response.body.isNotEmpty()) { "Reader page returned no data" }
                    val contentType = response.normalizedPluginMediaType()
                    val imageBytes = requireNotNull(response.imageBodyForDecoderOrNull()) {
                        "Reader page returned non-image content '$contentType'"
                    }
                    ReaderPage(
                        index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                        imageBytes = imageBytes,
                        imageTransform = metadata.imageTransform(source),
                    )
                },
            )
        }
        validateReaderPageIndexes(pages)
        return ReaderChapter(pages = pages, referer = referer, sourceHeaders = source.headers)
    }
}

private fun isSafeCompatibilityContentHeader(name: String): Boolean = when (name.trim().lowercase()) {
    "accept", "accept-language", "cache-control", "if-modified-since", "if-none-match", "range",
    // Komiic issues a short-lived ticket in the page URL fragment. It is safe only as a
    // credential-free content-plane hint; PluginContentNetworkClient strips it from every
    // cross-origin redirect and never combines it with cookies or authorization.
    "referer", "user-agent", "x-image-ticket" -> true
    else -> false
}

private fun mapOfNotNullHeader(name: String, value: String?): Map<String, String> =
    value?.takeIf(String::isNotBlank)?.let { mapOf(name to it) }.orEmpty()

private fun Map<String, String>.withFallbackHeader(name: String, value: String?): Map<String, String> =
    if (keys.any { it.equals(name, ignoreCase = true) }) this else this + mapOfNotNullHeader(name, value)

private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
