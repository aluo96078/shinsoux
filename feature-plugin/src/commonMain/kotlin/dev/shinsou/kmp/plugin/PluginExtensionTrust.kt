package dev.shinsou.kmp.plugin

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/** [ExtensionTrust] of [PluginBrowseAdapter]: publisher trust, blocks, developer mode and permission grants. */
internal class PluginExtensionTrust(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
    private val repositoryConfig: PluginBrowseRepositories,
) : ExtensionTrust {
    private val manager = deps.manager
    private val packageSecurity = deps.packageSecurity
    private val developerMode = deps.developerMode
    private val defaultDispatcher = deps.defaultDispatcher

    override suspend fun packageSecurity(): PackageSecuritySnapshot {
        val developer = developerMode?.state?.value?.let { dev.shinsou.kmp.plugin.PluginDeveloperModeSettings(it.enabled, it.lanHosts) }
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
    private suspend fun developerModeChange(change: suspend (PluginDeveloperModeStore) -> Unit): Unit = shared.operationMutex.withLock {
        val store = checkNotNull(developerMode) { "Developer mode is unavailable" }
        try {
            change(store)
        } finally {
            withContext(NonCancellable) {
                manager.loadInstalled()
                core.rebuildSnapshot(errorMessage = null)
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
    private suspend fun securityChange(change: suspend (PluginPackageSecurity) -> Unit): Unit = shared.operationMutex.withLock {
        val security = checkNotNull(packageSecurity) { "Publisher trust is unavailable" }
        try {
            change(security)
        } finally {
            withContext(NonCancellable) {
                manager.loadInstalled()
                core.rebuildSnapshot(errorMessage = null)
            }
        }
    }

    override suspend fun pendingPluginEventGrantReview(extensionId: String) =
        withContext(defaultDispatcher) {
            manager.pendingEventGrantReview(extensionId)
        }

    override suspend fun approvePluginEventGrantReview(
        extensionId: String,
        expectedReview: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
        permissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission>,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection?,
    ): Unit = withContext(defaultDispatcher) {
        // Approval can synchronously read durable package/admission state and construct the
        // platform runtime. Keep that work off the UI dispatcher, and share the adapter's
        // operation lock so a queued uninstall cannot revoke the artifact halfway through its
        // exact-digest approval/reload transaction.
        shared.operationMutex.withLock {
            manager.approveEventGrantReview(extensionId, expectedReview, permissions, selection)
            repositoryConfig.updatePackageList(PREINSTALLED_KEY) { it - extensionId }
            core.rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun pluginPermissionDetails(
        extensionId: String,
        review: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
    ): dev.shinsou.kmp.plugin.PluginPermissionDetails? = withContext(defaultDispatcher) {
        val stored = manager.installedPlugins().firstOrNull { it.manifest.id == extensionId } ?: return@withContext null
        if (stored.metadata.installedSha256 != review.artifact.sha256) return@withContext null
        permissionDetails(stored)
    }

    override suspend fun installedPluginPermissions(extensionId: String): dev.shinsou.kmp.plugin.InstalledPluginPermissions? =
        withContext(defaultDispatcher) {
            val stored = manager.installedPlugins().firstOrNull { it.manifest.id == extensionId } ?: return@withContext null
            val granted = manager.grantedSelection(extensionId) ?: return@withContext null
            val review = manager.currentEventGrantReview(extensionId) ?: return@withContext null
            dev.shinsou.kmp.plugin.InstalledPluginPermissions(review, granted, permissionDetails(stored))
        }

    override suspend fun narrowPluginPermissions(
        extensionId: String,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): Unit = withContext(defaultDispatcher) {
        shared.operationMutex.withLock {
            manager.narrowGrant(extensionId, selection)
            core.rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    internal suspend fun permissionDetails(stored: StoredPlugin): dev.shinsou.kmp.plugin.PluginPermissionDetails {
        val sources = stored.manifest.sources.orEmpty()
        val policies = sources.map { it.networkPolicy() }
        val publisher = stored.metadata.publisherId?.let { id -> packageSecurity?.publishers?.publisher(id) }
        val mode = packageSecurity?.publishers?.globalMode()
        return dev.shinsou.kmp.plugin.PluginPermissionDetails(
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
            browserLoginOrigins = sources.flatMap { it.browserLoginOrigins }.distinct().sorted(),
            inDevelopment = stored.metadata.publisherId == null && PluginInstallKeys.isDeveloperKey(stored.manifest.id),
        )
    }
}
