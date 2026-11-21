package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
/** [ExtensionManagement] of [PluginBrowseAdapter]: repositories and installing, updating and removing packages. */
internal class PluginExtensionManagement(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
    private val repositoryConfig: PluginBrowseRepositories,
    private val trust: PluginExtensionTrust,
) : ExtensionManagement {
    private val manager = deps.manager
    private val repositoryClient = deps.repositoryClient
    private val repositoryStore = deps.repositoryStore
    private val trustStore = deps.trustStore
    private val packageSecurity = deps.packageSecurity
    private val developerMode = deps.developerMode
    private val accountRepositories = deps.accountRepositories
    private val accountPackages = deps.accountPackages
    private val defaultDispatcher = deps.defaultDispatcher

    /** Keys shown to the user by [ExtensionManagement.inspectRepositoryTrust], by normalized URL, until trusted or replaced. */
    private val inspectedRepositoryKeys = mutableMapOf<String, RepositoryKeyPresentation>()

    /** Keeps suggestions and pre-installs in step with the sync account; without [suggestionScope] nothing is watched. */
    fun watchAccount(suggestionScope: CoroutineScope?) {
        val directory = accountRepositories
        if (directory != null && suggestionScope != null) {
            suggestionScope.launch {
                directory.repositories.collect { repositoryConfig.publishRepositorySuggestions() }
            }
        }
        val packages = accountPackages
        if (packages != null && suggestionScope != null) {
            suggestionScope.launch { packages.packages.collect { shared.preinstallSignal.trySend(Unit) } }
            suggestionScope.launch {
                for (request in shared.preinstallSignal) {
                    try {
                        preinstallAccountPackages()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (logged: Throwable) {
                        // Best effort: the next trigger (a refresh, a new list) tries again.
                        Log.warn("Plugin", logged) {
                            "Pre-installing account plugin packages failed; retrying on the next trigger"
                        }
                    }
                }
            }
        }
    }

    /** Where [preinstallAccountPackages] gets an index it has not displayed; replaced by tests. */
    var indexSnapshotFetcher: suspend (ExtensionRepository) -> RepositoryIndexSnapshot =
        { repository -> repositoryClient.fetchIndexSnapshot(repository.baseUrl) }

    private fun ExtensionRepository.toBrowseRepository(): BrowseRepository = with(core) { toBrowseRepository() }

    override suspend fun addRepository(url: String): BrowseRepository? = shared.operationMutex.withLock {
        repositoryConfig.reconcilePortableRepositories()
        val input = url.trim()
        val repository = repositoryClient.fetchRepository(repositoryConfig.normalizeRepositoryInput(input))
        val hadRepositories = repositoryStore.list().isNotEmpty()
        repositoryConfig.saveRepository(repository)
        if (!hadRepositories || repositoryStore.selected() == null) repositoryStore.select(repository.baseUrl)
        core.refreshLocked()
        repository.toBrowseRepository()
    }

    override suspend fun inspectRepositoryTrust(url: String): RepositoryTrustPrompt? {
        val security = packageSecurity ?: return null
        val input = repositoryConfig.normalizeRepositoryInput(url.trim())
        // A development server that developer mode admits is unsigned by definition and may be
        // plain HTTP: there is no key to show, and the strict inspection transport (HTTPS only,
        // public addresses only) would refuse it before addRepository could take it. A URL already
        // bound to a publisher is still inspected: that binding wins over developer mode.
        if (developerMode?.admitsRepository(input) == true && security.publishers.publisherForRepository(input) == null) {
            return null
        }
        // Anything the regular path can already take (pinned, trusted publisher)
        // or that is not a signed repository goes through addRepository as before.
        // Null only when the repository answered with something that is not a signed repo.json
        // (an unsigned repository); a failure to reach it is an error, so a caller can tell
        // "unsigned" from "try again". Reading the key is only a fetch: it runs outside
        // operationMutex so the key dialog never waits for an install or a refresh.
        val inspected = try {
            repositoryClient.inspectRepositoryKey(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ExtensionRepositoryException.InvalidDocument) {
            null
        } catch (_: ExtensionRepositoryException.InvalidUrl) {
            null // not a generic repository URL: addRepository decides
        } catch (missing: ExtensionRepositoryException.Http) {
            if (missing.status == 404) null else throw missing // no repo.json: not a signed repository
        }
        val presentation = inspected
            ?.also { shared.operationMutex.withLock { inspectedRepositoryKeys[it.normalizedBaseUrl] = it } }
            ?: return null
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
        shared.operationMutex.withLock {
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
                !repositoryConfig.isConfiguredRepository(presentation.normalizedBaseUrl)
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

    override suspend fun removeRepository(repositoryId: String, fromAccount: Boolean): Unit = shared.operationMutex.withLock {
        // Deletion is local maintenance. Never load scripts, migrate remote indexes, or refresh
        // another repository as a prerequisite (or after the durable removal has succeeded).
        repositoryConfig.reconcilePortableRepositoryStorage()
        val base = repositoryConfig.normalizeRepositoryInput(repositoryId)
        fun belongsToRemovedRepository(value: String): Boolean =
            value == repositoryId || runCatching { repositoryConfig.normalizeRepositoryInput(value) == base }.getOrDefault(false)
        repositoryConfig.deleteRepository(base)
        // Deleting here must not bring the repository straight back as a suggestion from the account.
        repositoryConfig.updateDismissedSuggestions { it + base }
        // Removing a repository releases its URL -> publisher mapping (the publisher, its other
        // URLs and all watermarks stay), so the URL can later be trusted under another key.
        packageSecurity?.publishers?.forgetRepository(base)
        val remaining = repositoryStore.list()
        val selected = repositoryStore.selected().takeIf { id -> remaining.any { it.baseUrl == id } }
            ?: remaining.firstOrNull()?.baseUrl
        repositoryStore.select(selected)

        shared.repositorySnapshots = shared.repositorySnapshots.filterKeys { !belongsToRemovedRepository(it) }
        manager.retainRepositoryPackages(remaining.map { it.baseUrl })
        val removedGenericIds = shared.descriptors.values.filter {
            it.repositoryBaseUrl?.let(::belongsToRemovedRepository) == true && it.state == ExtensionState.AVAILABLE
        }.map { it.id }.toSet()
        shared.descriptors = shared.descriptors - removedGenericIds
        // Reuse already-rendered source/settings state: rebuilding it can invoke a broken plugin.
        val current = shared.mutableState.value
        shared.mutableState.value = current.copy(
            repositories = remaining.map { it.toBrowseRepository() }.distinctBy(BrowseRepository::url),
            selectedRepositoryId = selected,
            extensions = current.extensions.filterNot {
                !it.installed && (it.id in removedGenericIds)
            }.map { if (shared.descriptors[it.id]?.repositoryBaseUrl == base) {
                it.copy(updateAvailable = false)
            } else it },
            isRefreshing = false,
            errorMessage = null,
        )
        if (fromAccount) accountRepositories?.forget(base)
    }

    override suspend fun dismissRepositorySuggestion(url: String) {
        val key = repositoryConfig.suggestionKey(url)
        repositoryConfig.updateDismissedSuggestions { it + key }
        repositoryConfig.publishRepositorySuggestions()
    }

    override suspend fun selectRepository(repositoryId: String?): Unit = shared.operationMutex.withLock {
        repositoryConfig.reconcilePortableRepositories()
        val repositories = repositoryStore.list()
        require(repositoryId == null || repositories.any { it.baseUrl == repositoryId }) {
            "Unknown extension repository: $repositoryId"
        }
        repositoryStore.select(repositoryId)
        core.refreshLocked()
    }

    /** The current index entry of a generic package [extensionId] and its repository; null for legacy indexes. */
    private suspend fun indexEntryFor(extensionId: String): Pair<ExtensionRepository, PluginIndexEntry>? {
        val descriptor = shared.descriptors[extensionId] ?: return null
        val repository = repositoryStore.list().firstOrNull { it.baseUrl == descriptor.repositoryBaseUrl } ?: return null
        val entries = when (val index = shared.repositorySnapshots[repository.baseUrl]?.index) {
            is RepositoryIndex.Plugins -> index.entries
            is RepositoryIndex.Combined -> index.plugins
            else -> return null
        }
        val entry = entries.firstOrNull { manager.installKeyFor(repository, it) == extensionId } ?: return null
        return (repository to entry).takeIf { entry.matchesDescriptor(descriptor) }
    }

    override suspend fun updatePermissionPreview(extensionId: String): dev.shinsou.kmp.plugin.UpdatePermissionPreview? =
        withContext(defaultDispatcher) {
            shared.operationMutex.withLock {
                val (repository, entry) = indexEntryFor(extensionId) ?: return@withLock null
                val preview = manager.updateReviewPreview(repository, entry) ?: return@withLock null
                dev.shinsou.kmp.plugin.UpdatePermissionPreview(preview.review, trust.permissionDetails(preview.stored))
            }
        }

    override suspend fun installReviewedUpdate(
        extensionId: String,
        preview: dev.shinsou.kmp.plugin.UpdatePermissionPreview,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): dev.shinsou.kmp.plugin.events.PluginEventGrantReview? = withContext(defaultDispatcher) {
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
        trust.approvePluginEventGrantReview(extensionId, pending, selection.hostPermissions, selection)
        null
    }

    override suspend fun installExtension(extensionId: String): Unit = withContext(defaultDispatcher) {
        shared.operationMutex.withLock installOperation@{
        core.ensureInstalledLoaded()
        val descriptor = shared.descriptors[extensionId]
            ?: throw IllegalArgumentException("Unknown extension: $extensionId")
        // A second tap can queue behind an in-flight install. Once the first succeeds, make the
        // queued call a no-op instead of downloading and installing the same package again.
        if (descriptor.state == ExtensionState.INSTALLED) return@installOperation
        val repositoryBaseUrl = descriptor.repositoryBaseUrl
            ?: throw IllegalStateException("Extension '$extensionId' has no repository")
        val repository = repositoryStore.list().firstOrNull { it.baseUrl == repositoryBaseUrl }
            ?: throw IllegalStateException("Repository is no longer configured: $repositoryBaseUrl")
        val displayed = shared.repositorySnapshots[repository.baseUrl]
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
        // Installing by hand is the opposite of declining a pre-install.
        repositoryConfig.updatePackageList(DECLINED_PREINSTALL_KEY) { it - extensionId }
        // The index used above is already authoritative for this operation. Reusing the current
        // descriptor avoids fetching the same index again and rescanning every installed package.
        shared.descriptors = shared.descriptors + (
            extensionId to descriptor.copy(
                state = ExtensionState.INSTALLED,
                installedVersion = installedVersion,
            )
        )
        // Installing one runtime cannot change the UI settings of every other live runtime. Reuse
        // projections only when the exact CatalogueSource instance is still installed; an update,
        // trust change, source-id collision, or replacement therefore rebuilds the affected source.
        core.rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun uninstallExtension(extensionId: String): Unit = withContext(defaultDispatcher) {
        shared.operationMutex.withLock {
            manager.uninstall(extensionId)
            trustStore.revokeAll(extensionId)
            // Removed on purpose: the account's list must not bring it straight back.
            repositoryConfig.updatePackageList(DECLINED_PREINSTALL_KEY) { it + extensionId }
            repositoryConfig.updatePackageList(PREINSTALLED_KEY) { it - extensionId }
            // The authenticated index already backing the visible row is authoritative for this
            // local mutation. Do not refetch every repository (or rescan unrelated packages): a
            // repository-backed row becomes available, while an installed-only row disappears.
            val descriptor = shared.descriptors[extensionId]
            val repositories = repositoryStore.list().associateBy { it.baseUrl }
            shared.descriptors = if (descriptor != null && shared.repositorySnapshots.any { (baseUrl, snapshot) ->
                    repositories[baseUrl]?.let { repository -> snapshot.containsExtension(repository, extensionId) } == true
                }
            ) {
                shared.descriptors + (extensionId to descriptor.copy(
                    state = ExtensionState.AVAILABLE,
                    installedVersion = null,
                ))
            } else {
                shared.descriptors - extensionId
            }
            core.rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean): Unit =
        shared.operationMutex.withLock {
            // `trusted` is an execution grant, not a claim about repository authorship. Even a
            // package with a matching digest must be unloaded when this grant is revoked.
            manager.setPluginTrusted(extensionId, trusted)
            core.rebuildSnapshot(errorMessage = null)
        }

    /**
     * Installs, without approving, the packages the sync account has used that this device lacks.
     * A package is only taken from a repository the user has added here and whose publisher is
     * trusted on this device: the account's list says what to look for, never whom to trust.
     * Anything else (an unknown or unsigned repository, a package already installed or removed
     * here on purpose, one the repository no longer lists) is skipped. The install stops at the
     * permission review (pending, nothing runs) and is remembered so the UI can say so; the review
     * comes when the user first opens the package or one of its titles.
     */
    suspend fun preinstallAccountPackages(): Unit = withContext(defaultDispatcher) {
        val wanted = accountPackages?.packages?.value.orEmpty()
        if (wanted.isEmpty()) return@withContext
        // One pass at a time; a pass never holds operationMutex for longer than one package's
        // install, so repository management (adding, inspecting, switching) is not starved by
        // a long list of downloads.
        preinstallPassMutex.withLock {
            core.ensureInstalledLoaded()
            // Indexes this pass fetched for repositories not on screen, by base URL.
            val fetched = HashMap<String, RepositoryIndexSnapshot>()
            var installedAny = false
            for (pkg in wanted) {
                if (pkg.repositoryUrl.isBlank() || coolingDown(pkg.packageId) || !wantsPreinstall(pkg)) continue
                if (configuredRepositoryFor(pkg) == null) continue
                try {
                    // One package at a time under the lock: switching or adding a repository waits
                    // for at most this one install, never for the whole list.
                    val installed = shared.operationMutex.withLock { preinstallLocked(pkg, fetched) }
                    if (installed) {
                        installedAny = true
                        preinstallFailures.remove(pkg.packageId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (logged: Throwable) {
                    preinstallFailures[pkg.packageId] = preinstallClock.markNow()
                    Log.warn("Plugin", logged) {
                        "Pre-installing a plugin package failed; trying it again after $preinstallRetryDelay"
                    }
                }
            }
            if (installedAny) {
                try {
                    shared.operationMutex.withLock { core.refreshLocked() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (logged: Throwable) {
                    // The browse state already carries the refresh logged.
                    Log.warn("Plugin", logged) {
                        "Refreshing the browse state after pre-install failed; the state shows the logged"
                    }
                }
            }
        }
    }

    /**
     * Installs [pkg] when it is still wanted; true when it was installed. Runs under operationMutex:
     * the index is read here, not before taking the lock, because an install is admitted only from
     * the repository's current authenticated index, and a refresh between the read and the
     * install (adding or switching a repository) replaces it.
     */
    private suspend fun preinstallLocked(
        pkg: AccountPackage,
        fetched: MutableMap<String, RepositoryIndexSnapshot>,
    ): Boolean {
        // Meanwhile the user may have installed or removed the package or removed its repository.
        val repository = configuredRepositoryFor(pkg)?.takeIf { wantsPreinstall(pkg) } ?: return false
        // The displayed index when there is one; otherwise this repository's own fetch (the user
        // may have another repository selected), once per pass.
        val displayed = shared.repositorySnapshots[repository.baseUrl]
        val snapshot = displayed ?: fetched.getOrPut(repository.baseUrl) { indexSnapshotFetcher(repository) }
        val installed = try {
            installFrom(repository, snapshot, pkg)
        } catch (stale: RepositoryTrustException.UnauthenticatedArtifact) {
            // An index this pass fetched earlier was replaced by a later fetch of the same
            // repository: fetch it again once. A displayed index is current by construction.
            if (displayed != null) throw stale
            installFrom(repository, indexSnapshotFetcher(repository).also { fetched[repository.baseUrl] = it }, pkg)
        }
        if (installed) repositoryConfig.updatePackageList(PREINSTALLED_KEY) { it + pkg.packageId }
        return installed
    }

    /** Installs [pkg] from [snapshot] when its publisher is trusted and it lists the package. */
    private suspend fun installFrom(repository: ExtensionRepository, snapshot: RepositoryIndexSnapshot, pkg: AccountPackage): Boolean {
        val entry = installableEntry(repository, snapshot, pkg.packageId)
            ?.takeIf { trustedForPreinstall(repository, snapshot) }
        // The latest version the repository publishes, not the account's: the repository decides.
        entry?.let { manager.install(repository, it, snapshot.admission) }
        return entry != null
    }

    /** Serializes pre-install passes; also guards [preinstallFailures]. */
    private val preinstallPassMutex = Mutex()

    /** When each package last failed to pre-install; it is not tried again before [preinstallRetryDelay]. */
    private val preinstallFailures = HashMap<String, TimeMark>()

    /** How long a package that failed to pre-install waits before the next attempt; replaced by tests. */
    var preinstallRetryDelay: Duration = 10.minutes

    /** The clock of [preinstallRetryDelay]; replaced by tests. */
    var preinstallClock: TimeSource = TimeSource.Monotonic

    private fun coolingDown(packageId: String): Boolean =
        preinstallFailures[packageId]?.let { it.elapsedNow() < preinstallRetryDelay } == true

    /** Not installed here and not removed here on purpose. */
    private suspend fun wantsPreinstall(pkg: AccountPackage): Boolean =
        manager.installedPlugins().none { it.manifest.id == pkg.packageId } &&
            pkg.packageId !in repositoryConfig.packageList(DECLINED_PREINSTALL_KEY)

    /** The repository of [pkg] when the user has added it on this device. */
    private suspend fun configuredRepositoryFor(pkg: AccountPackage): ExtensionRepository? {
        val wantedKey = repositoryConfig.suggestionKey(pkg.repositoryUrl)
        return repositoryStore.list().firstOrNull { repositoryConfig.suggestionKey(it.baseUrl) == wantedKey }
    }

    /** The entry of [snapshot] installing as [packageId]; null for a legacy index or one no longer listing it. */
    private suspend fun installableEntry(
        repository: ExtensionRepository,
        snapshot: RepositoryIndexSnapshot,
        packageId: String,
    ): PluginIndexEntry? {
        val entries = when (val index = snapshot.index) {
            is RepositoryIndex.Plugins -> index.entries
            is RepositoryIndex.Combined -> index.plugins
            is RepositoryIndex.Legacy -> return null
        }
        return entries.firstOrNull { manager.installKeyFor(repository, it) == packageId }
    }

    /**
     * True when [snapshot] came from a signed index whose publisher this device trusts for
     * [repository]. An unsigned repository (no admission) or one whose publisher is unknown,
     * pending confirmation, replaced or revoked is never installed from automatically: without a
     * publisher the install would be unsigned.
     */
    private suspend fun trustedForPreinstall(repository: ExtensionRepository, snapshot: RepositoryIndexSnapshot): Boolean {
        if (snapshot.admission == null) return false
        if (repository.baseUrl in shared.repositoriesNeedingTrust) return false
        val security = packageSecurity ?: return false
        val publisher = security.publishers.publisherForRepository(repositoryClient.canonicalBaseUrl(repository.baseUrl))
            ?: return false
        return publisher.status(security.publishers.globalMode()) == PublisherTrustStatus.TRUSTED
    }

    /** Whether [extensionId] (an install key, D6) is still offered by this repository's index. */
    private suspend fun RepositoryIndexSnapshot.containsExtension(repository: ExtensionRepository, extensionId: String): Boolean =
        when (val displayed = index) {
            is RepositoryIndex.Plugins -> displayed.entries.any { manager.installKeyFor(repository, it) == extensionId }
            is RepositoryIndex.Legacy -> displayed.entries.any { it.pkg == extensionId }
            is RepositoryIndex.Combined -> displayed.plugins.any { manager.installKeyFor(repository, it) == extensionId }
        }

    /** Reconciles the immutable row rendered by refresh with the exact candidate being installed. */
    private fun PluginIndexEntry.matchesDescriptor(descriptor: ExtensionDescriptor): Boolean =
        descriptor.admissionFingerprint != null &&
            admissionFingerprint() == descriptor.admissionFingerprint

    private fun LegacyExtensionIndexEntry.matchesDescriptor(descriptor: ExtensionDescriptor): Boolean =
        descriptor.admissionFingerprint != null &&
            admissionFingerprint() == descriptor.admissionFingerprint
}
