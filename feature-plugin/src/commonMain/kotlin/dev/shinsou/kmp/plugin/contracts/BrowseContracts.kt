package dev.shinsou.kmp.plugin.contracts

import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.contract.SourceCredential
import dev.shinsou.kmp.contract.SourceLoginFailureStage
import dev.shinsou.kmp.contract.SourceLoginRequest
import dev.shinsou.kmp.contract.SourceLoginResult
import dev.shinsou.kmp.contract.SourcePreference
import dev.shinsou.kmp.contract.SourceSecretsResult
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.shinsou.kmp.plugin.manager.PluginLogoutConfirmation
import dev.shinsou.kmp.plugin.model.PluginContentType

// R4-1 (docs/architecture/0006): the extension/source bridge the UI talks to.

private val emptySourceLoginRequests = MutableStateFlow<List<SourceLoginRequest>>(emptyList())
private val emptySourceRefreshInvalidations = MutableStateFlow<Map<SourceKey, Long>>(emptyMap())
private val emptyLogoutConfirmations = MutableStateFlow<List<PluginLogoutConfirmation>>(emptyList())
private val emptyExtensionDownloads =
    MutableStateFlow<List<dev.shinsou.kmp.domain.model.ExtensionDownloadEntryV2>>(emptyList())

/** Browsing, searching and resolving titles of legacy sources. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface SourceCatalog {
    public val state: StateFlow<BrowseSnapshot>

    /** Exact-source generations emitted by admitted plugin refresh events. */
    public val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>>

    public suspend fun refresh()

    public suspend fun browseSource(
        sourceId: Long,
        query: String = "",
        page: Int = 1,
        filters: List<BrowseFilter>? = null,
    ): BrowsePage

    /** Loads the source's latest-updates catalogue when that capability is advertised. */
    public suspend fun browseSourceLatest(
        sourceId: Long,
        page: Int = 1,
    ): BrowsePage = browseSource(sourceId = sourceId, page = page)

    /** Loads a legacy source's account-owned collection without using the local library. */
    public suspend fun browseSourceFavorites(
        sourceId: Long,
        page: Int = 1,
    ): BrowsePage = browseSource(sourceId = sourceId, page = page)

    /** True when [sourceId] belongs to an executable plugin source. */
    public fun isPluginSource(sourceId: Long): Boolean

    public fun isPluginSource(sourceKey: SourceKey): Boolean = sourceKey.legacyLongId?.let(::isPluginSource) == true

    /**
     * Fetches a plugin-owned image through the host content plane. Implementations must return
     * bounded bytes only; UI image loaders must never receive a plugin-controlled remote URL.
     */
    public suspend fun loadPluginThumbnail(
        sourceId: Long,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray?

    public suspend fun loadPluginThumbnail(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? = sourceKey.legacyLongId?.let { loadPluginThumbnail(it, url, headers) }

    /** Returns the local manga id when the bridge imported or resolved the item. */
    public suspend fun resolveManga(item: BrowseManga): Long?

    public suspend fun migrateManga(mangaId: Long, target: BrowseManga)
}

/** Enabling sources and saving their preferences. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface SourceSettings {
    public suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean)

    public suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean)

    public suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>)
}

/** Browsing and reading publications of v2 extensions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface ExtensionPublicationCatalog {
    /** Exact, capability-gated v2 source; opaque ids are never projected into legacy Long DTOs. */
    public suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2?

    /** Production v2 browse route. Page numbering is zero-based at the extension contract. */
    public suspend fun browseSourceV2(
        sourceKey: SourceKey,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2>

    public suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int = 0,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
    ): PagedResultV2<RemotePublicationV2>

    public suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2>

    public suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2

    public suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): PagedResultV2<RemoteUnitV2>

    /** Returns every representation supplied for the exact unit; no preferred form is selected. */
    public suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2

    /** Host-issued details/unit capabilities; raw extension units never become UI authority. */
    public suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): ExtensionPublicationPageV2

    /**
     * Loads units with catalogue metadata already available, avoiding a duplicate details request
     * before the chapter list can be rendered. Implementations may still refresh full details
     * independently after the initial page becomes visible.
     */
    public suspend fun extensionPublicationUnitsPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int = 0,
    ): ExtensionPublicationPageV2 = extensionPublicationPageV2(sourceKey, remotePublicationId, page)

    /** Acquires one explicitly selected representation into the shared content transaction. */
    /**
     * The units of [publication] stored on this device (downloaded or read), as a unit page; null
     * when none is. They open without the source, listed by it or not.
     */
    public suspend fun storedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
    ): ExtensionPublicationPageV2? = null

    /** Cached details and full unit list of a library title, as an offline unit page. */
    public suspend fun cachedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): ExtensionPublicationPageV2?

    /** Records a library title's details and complete unit list for offline and instant display. */
    public suspend fun cacheExtensionPublicationV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
        units: List<dev.shinsou.kmp.plugin.v2.RemoteUnitV2>,
    )

    public suspend fun forgetCachedExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String)
}

/** How a v2 publication is linked to the local library. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface ExtensionLibraryBinding {
    /** Recovers the exact extension authority for a legacy UUID-only local-library row. */
    public fun extensionLibraryBindingV2(
        publicationKey: PublicationKey,
    ): ExtensionLibraryBindingV2?

    /** Local-only chapter completion and exact resume unit for a typed extension publication. */
    public fun extensionLocalReadingStateV2(
        publicationKey: PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2

    /** Also resolves progress of listed units never opened here (for example carried by a migration). */
    public fun extensionLocalReadingStateV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitIds: Collection<String>,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionLocalReadingStateV2(dev.shinsou.kmp.domain.model.extensionPublicationKey(sourceKey, remotePublicationId))

    /** Source unit ids of a typed extension publication whose content is stored offline. */
    public fun extensionDownloadedUnitIdsV2(publicationKey: PublicationKey): Set<String>

    /**
     * Best-effort repair for beta-era UUID-only extension favorites. Implementations must return
     * a binding only after the deterministic publication UUID matches an authoritative search row.
     */
    public suspend fun recoverExtensionLibraryBindingV2(
        publicationKey: PublicationKey,
        title: String,
    ): ExtensionLibraryBindingV2? = null

    /** Source-owned favorite/library mutation for v2 publications when the capability exists. */
    public suspend fun favoriteExtensionPublicationV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    )
}

/** The v2 download queue and offline content. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface ContentAcquisition {
    /** App-scoped extension (novel) chapter downloads; they outlive the detail screen. */
    public val extensionDownloadsV2: StateFlow<List<dev.shinsou.kmp.domain.model.ExtensionDownloadEntryV2>>

    public suspend fun enqueueExtensionDownloadsV2(
        selections: List<dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2>,
    ): Unit

    public suspend fun retryExtensionDownloadV2(id: String)

    public suspend fun removeExtensionDownloadV2(id: String)

    public suspend fun clearCompletedExtensionDownloadsV2()

    /** Removes every queued or finished download row of one title (when the title is deleted). */
    public suspend fun removeExtensionPublicationDownloadsV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String)

    /**
     * Deletes the stored chapter bodies of a title that left the library, keeping its reading
     * progress and library binding. Local only: nothing is propagated through sync.
     */
    public suspend fun purgeExtensionPublicationContentV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String)

    public suspend fun pauseExtensionDownloadsV2(paused: Boolean)

    public suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
    ): ExtensionContentMaterializationV2

    /** Opens the exact immutable revision returned by materialization through host rights. */
    public suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession
}

/** Source sign-in, credentials and logout. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface SourceAuth {
    /** FIFO login prompts requested by sources, deduplicated by source id. */
    public val loginRequests: StateFlow<List<SourceLoginRequest>>

    public val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>>

    public suspend fun confirmPluginLogout(eventId: String): Boolean

    public fun dismissPluginLogout(eventId: String)

    public fun dismissAllPluginLogouts()

    public suspend fun setPluginUiAvailable(available: Boolean)

    public fun dismissSourceLoginRequest(sourceId: Long)

    public fun dismissSourceLoginEvent(eventId: String)

    /** Invokes the advertised login contract and stores credentials only after a successful login. */
    public suspend fun saveSourceCredentials(sourceId: Long, username: String, password: String): Boolean

    public suspend fun saveSourceCredentialsResult(
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        SourceLoginResult(saveSourceCredentials(sourceId, username, password))
    } catch (logged: Throwable) {
        // Runtime exceptions may contain transport details or secrets; the UI uses its stable
        // generic fallback when a source does not provide an explicit safe failure message.
        // Only the exception type: its message may carry transport details or secrets.
        Log.warn("Login") { "Saving source credentials failed (${logged::class.simpleName}); using the generic failure result" }
        SourceLoginResult(false, failureStage = SourceLoginFailureStage.UNKNOWN)
    }

    public suspend fun saveSourceEventCredentialsResult(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        SourceLoginResult(saveSourceEventCredentials(eventId, sourceId, username, password))
    } catch (logged: Throwable) {
        // Runtime exceptions may contain transport details or secrets; the UI uses its stable
        // generic fallback when a source does not provide an explicit safe failure message.
        // Only the exception type: its message may carry transport details or secrets.
        Log.warn("Login") { "Saving source event credentials failed (${logged::class.simpleName}); using the generic failure result" }
        SourceLoginResult(false, failureStage = SourceLoginFailureStage.UNKNOWN)
    }

    public suspend fun saveSourceEventCredentials(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceCredentials(sourceId, username, password)

    /**
     * Decrypts one source's private settings on demand. Catalogue refresh and source discovery
     * must never call this method because protected storage may require interactive OS access.
     */
    public suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult

    public suspend fun logoutSource(sourceId: Long)
}

/** Source cookies and browser challenges. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface SourceWebSession {
    public suspend fun setSourceCookie(sourceId: Long, cookie: SourceCookie)

    /** Persists one isolated browser session and its browser-bound User-Agent together. */
    public suspend fun importSourceWebChallengeSession(
        sourceId: Long,
        capability: SourceWebChallengeCapability,
        cookies: List<SourceCookie>,
        userAgent: String,
        localStorage: Map<String, String> = emptyMap(),
    )

    /** Invalidates a browser capability when its isolated browser is dismissed without import. */
    public suspend fun cancelSourceWebChallenge(capability: SourceWebChallengeCapability)

    public suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String)

    public suspend fun clearSourceCookies(sourceId: Long)

    /**
     * Reports whether the exact live source artifact may start a host-reviewed Web challenge.
     * This is an availability check only: it must not issue a capability or read source secrets.
     */
    public suspend fun isSourceWebChallengeAvailable(sourceId: Long): Boolean

    /**
     * Builds the browser challenge request with the exact source URL and source-isolated cookies.
     * A platform browser may need its native User-Agent to complete anti-bot verification; the
     * actual value captured with the resulting cookies is then reused by Browse/Reader/Download.
     */
    public suspend fun sourceWebChallenge(
        sourceId: Long,
        username: String? = null,
        password: String? = null,
    ): SourceWebChallengeRequest?
}

/** Repositories, installing and updating extensions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface ExtensionManagement {
    public suspend fun addRepository(url: String): BrowseRepository?

    /**
     * ADR-0002 S5: a signed repository's key, for the user to confirm before [trustAndAddRepository].
     * Null when the repository answered but is not signed ([addRepository] takes the URL as it is);
     * throws when it could not be reached, so a caller can try again later.
     */
    public suspend fun inspectRepositoryTrust(url: String): RepositoryTrustPrompt?

    /**
     * Trusts exactly the key [prompt] showed (refused if the repository presented another one since)
     * — with [fingerprint] when the user entered one — and adds the repository.
     */
    public suspend fun trustAndAddRepository(prompt: RepositoryTrustPrompt, fingerprint: String?): BrowseRepository?

    public suspend fun removeRepository(repositoryId: String): Unit = removeRepository(repositoryId, fromAccount = false)

    /**
     * Removes a repository from this device. Deleting is local by default; with [fromAccount] the
     * repository also stops being suggested to the account's other devices (those that already
     * added it keep it).
     */
    public suspend fun removeRepository(repositoryId: String, fromAccount: Boolean)

    /** Hides a repository suggested by the account on this device only; it is never suggested here again. */
    public suspend fun dismissRepositorySuggestion(url: String)

    public suspend fun selectRepository(repositoryId: String?)

    public suspend fun installExtension(extensionId: String)

    /**
     * ADR-0002 §6.3: what an update of a running plugin would ask for, when it asks for something
     * new — reviewed before updating, so the installed version keeps running until accepted.
     */
    public suspend fun updatePermissionPreview(extensionId: String): UpdatePermissionPreview?

    /**
     * Installs the update [preview] showed and grants [selection] to it. Returns the update's own
     * review when it turned out to ask for something else than the preview (it is then not granted).
     */
    public suspend fun installReviewedUpdate(
        extensionId: String,
        preview: UpdatePermissionPreview,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): PluginEventGrantReview?

    public suspend fun uninstallExtension(extensionId: String)

    public suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean)
}

/** Publisher trust, blocks, developer mode and permissions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
public interface ExtensionTrust {
    public suspend fun packageSecurity(): PackageSecuritySnapshot

    public suspend fun setStrictPublisherTrust(strict: Boolean)

    public suspend fun setPluginDeveloperMode(enabled: Boolean)

    /** Throws when an address is not on a private network. */
    public suspend fun setPluginDeveloperHosts(hosts: List<String>)

    public suspend fun confirmPublisherFingerprint(publisherId: String, fingerprint: String)

    public suspend fun setPublisherBlocked(publisherId: String, blocked: Boolean)

    public suspend fun setExtensionBlocked(extensionId: String, blocked: Boolean)

    public suspend fun unblockPackageEntry(key: String)

    public suspend fun pendingPluginEventGrantReview(extensionId: String): PluginEventGrantReview?

    public suspend fun approvePluginEventGrantReview(
        extensionId: String,
        expectedReview: PluginEventGrantReview,
        permissions: Set<PluginHostPermission>,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection? = null,
    )

    /** Who published [review]'s artifact and where it connects: what the permission page shows (ADR-0002 §6.5). */
    public suspend fun pluginPermissionDetails(extensionId: String, review: PluginEventGrantReview): PluginPermissionDetails?

    /** The granted plugin's current permissions, for source settings; null when not granted. */
    public suspend fun installedPluginPermissions(extensionId: String): InstalledPluginPermissions?

    /** Withdraws sensitive items from a granted plugin and reloads it. */
    public suspend fun narrowPluginPermissions(extensionId: String, selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection)
}

/** Extension/source bridge. Plugin modules adapt their own models to these DTOs. */
public interface BrowseCallbacks :
    SourceCatalog,
    SourceSettings,
    ExtensionPublicationCatalog,
    ExtensionLibraryBinding,
    ContentAcquisition,
    SourceAuth,
    SourceWebSession,
    ExtensionManagement,
    ExtensionTrust {
    public companion object {
        public val None: BrowseCallbacks = EmptyBrowseCallbacks()
    }
}

private val emptyBrowseState = MutableStateFlow(BrowseSnapshot())

/**
 * A host without extension support: nothing to browse, every action a no-op and every v2 call
 * refused. The members carry no defaults in the interfaces (R4-2, docs/architecture/0006) so a
 * platform bridge cannot leave one out by accident; previews, [BrowseCallbacks.None] and test
 * fakes extend this class instead and override only what they use.
 */
public open class EmptyBrowseCallbacks : BrowseCallbacks {
    override val state: StateFlow<BrowseSnapshot> get() = emptyBrowseState
    override val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>> get() = emptySourceRefreshInvalidations
    override val extensionDownloadsV2: StateFlow<List<dev.shinsou.kmp.domain.model.ExtensionDownloadEntryV2>> get() = emptyExtensionDownloads
    override val loginRequests: StateFlow<List<SourceLoginRequest>> get() = emptySourceLoginRequests
    override val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>> get() = emptyLogoutConfirmations
    override suspend fun refresh(): Unit = Unit
    override suspend fun browseSource(sourceId: Long, query: String, page: Int, filters: List<BrowseFilter>?): BrowsePage = BrowsePage()
    override fun isPluginSource(sourceId: Long): Boolean = false
    override suspend fun loadPluginThumbnail(sourceId: Long, url: String, headers: Map<String, String>): ByteArray? = null
    override suspend fun resolveManga(item: BrowseManga): Long? = null
    override suspend fun migrateManga(mangaId: Long, target: BrowseManga): Unit = Unit
    override suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): Unit = Unit
    override suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean): Unit = Unit
    override suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>): Unit = Unit
    override suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? = null
    override suspend fun browseSourceV2(sourceKey: SourceKey, options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun searchSourceV2(sourceKey: SourceKey, query: String, page: Int, options: BrowseOptionsV2): PagedResultV2<RemotePublicationV2> = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun latestSourceV2(sourceKey: SourceKey, page: Int): PagedResultV2<RemotePublicationV2> = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun extensionDetailsV2(sourceKey: SourceKey, remotePublicationId: String): RemotePublicationV2 = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun extensionUnitsV2(sourceKey: SourceKey, remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun extensionContentV2(sourceKey: SourceKey, remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 = throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")
    override suspend fun extensionPublicationPageV2(sourceKey: SourceKey, remotePublicationId: String, page: Int): ExtensionPublicationPageV2 = error("Extension v2 content storage is unavailable")
    override suspend fun cachedExtensionPublicationPageV2(sourceKey: SourceKey, remotePublicationId: String): ExtensionPublicationPageV2? = null
    override suspend fun cacheExtensionPublicationV2(sourceKey: SourceKey, publication: RemotePublicationV2, units: List<dev.shinsou.kmp.plugin.v2.RemoteUnitV2>): Unit = Unit
    override suspend fun forgetCachedExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String): Unit = Unit
    override fun extensionLibraryBindingV2(publicationKey: PublicationKey): ExtensionLibraryBindingV2? = null
    override fun extensionLocalReadingStateV2(publicationKey: PublicationKey): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 = dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()
    override fun extensionDownloadedUnitIdsV2(publicationKey: PublicationKey): Set<String> = emptySet()
    override suspend fun favoriteExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String, favorite: Boolean): Unit = Unit
    override suspend fun enqueueExtensionDownloadsV2(selections: List<dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2>): Unit = throw UnsupportedOperationException("Extension downloads are unavailable")
    override suspend fun retryExtensionDownloadV2(id: String): Unit = Unit
    override suspend fun removeExtensionDownloadV2(id: String): Unit = Unit
    override suspend fun clearCompletedExtensionDownloadsV2(): Unit = Unit
    override suspend fun removeExtensionPublicationDownloadsV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String): Unit = Unit
    override suspend fun purgeExtensionPublicationContentV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String): Unit = Unit
    override suspend fun pauseExtensionDownloadsV2(paused: Boolean): Unit = Unit
    override suspend fun materializeExtensionContentV2(selection: ExtensionUnitSelectionV2, representationId: String?): ExtensionContentMaterializationV2 = error("Extension v2 content storage is unavailable")
    override suspend fun openMaterializedExtensionContentV2(materialization: ExtensionContentMaterializationV2): TypedReaderContentSession = error("Extension v2 reader content is unavailable")
    override suspend fun confirmPluginLogout(eventId: String): Boolean = false
    override fun dismissPluginLogout(eventId: String): Unit = Unit
    override fun dismissAllPluginLogouts(): Unit = Unit
    override suspend fun setPluginUiAvailable(available: Boolean): Unit = Unit
    override fun dismissSourceLoginRequest(sourceId: Long): Unit = Unit
    override fun dismissSourceLoginEvent(eventId: String): Unit = Unit
    override suspend fun saveSourceCredentials(sourceId: Long, username: String, password: String): Boolean = false
    override suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult = SourceSecretsResult()
    override suspend fun logoutSource(sourceId: Long): Unit = Unit
    override suspend fun setSourceCookie(sourceId: Long, cookie: SourceCookie): Unit = Unit
    override suspend fun importSourceWebChallengeSession(sourceId: Long, capability: SourceWebChallengeCapability, cookies: List<SourceCookie>, userAgent: String, localStorage: Map<String, String>): Unit = Unit
    override suspend fun cancelSourceWebChallenge(capability: SourceWebChallengeCapability): Unit = Unit
    override suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String): Unit = Unit
    override suspend fun clearSourceCookies(sourceId: Long): Unit = Unit
    override suspend fun isSourceWebChallengeAvailable(sourceId: Long): Boolean = false
    override suspend fun sourceWebChallenge(sourceId: Long, username: String?, password: String?): SourceWebChallengeRequest? = null
    override suspend fun addRepository(url: String): BrowseRepository? = null
    override suspend fun inspectRepositoryTrust(url: String): RepositoryTrustPrompt? = null
    override suspend fun trustAndAddRepository(prompt: RepositoryTrustPrompt, fingerprint: String?): BrowseRepository? = null
    override suspend fun removeRepository(repositoryId: String, fromAccount: Boolean): Unit = Unit
    override suspend fun dismissRepositorySuggestion(url: String): Unit = Unit
    override suspend fun selectRepository(repositoryId: String?): Unit = Unit
    override suspend fun installExtension(extensionId: String): Unit = Unit
    override suspend fun updatePermissionPreview(extensionId: String): UpdatePermissionPreview? = null
    override suspend fun installReviewedUpdate(extensionId: String, preview: UpdatePermissionPreview, selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection): PluginEventGrantReview? = null
    override suspend fun uninstallExtension(extensionId: String): Unit = Unit
    override suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean): Unit = Unit
    override suspend fun packageSecurity(): PackageSecuritySnapshot = PackageSecuritySnapshot()
    override suspend fun setStrictPublisherTrust(strict: Boolean): Unit = Unit
    override suspend fun setPluginDeveloperMode(enabled: Boolean): Unit = Unit
    override suspend fun setPluginDeveloperHosts(hosts: List<String>): Unit = Unit
    override suspend fun confirmPublisherFingerprint(publisherId: String, fingerprint: String): Unit = Unit
    override suspend fun setPublisherBlocked(publisherId: String, blocked: Boolean): Unit = Unit
    override suspend fun setExtensionBlocked(extensionId: String, blocked: Boolean): Unit = Unit
    override suspend fun unblockPackageEntry(key: String): Unit = Unit
    override suspend fun pendingPluginEventGrantReview(extensionId: String): PluginEventGrantReview? = null
    override suspend fun approvePluginEventGrantReview(extensionId: String, expectedReview: PluginEventGrantReview, permissions: Set<PluginHostPermission>, selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection?): Unit = Unit
    override suspend fun pluginPermissionDetails(extensionId: String, review: PluginEventGrantReview): PluginPermissionDetails? = null
    override suspend fun installedPluginPermissions(extensionId: String): InstalledPluginPermissions? = null
    override suspend fun narrowPluginPermissions(extensionId: String, selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection): Unit = Unit
}

public data class BrowseSnapshot(
    val repositories: List<BrowseRepository> = emptyList(),
    val selectedRepositoryId: String? = null,
    /** Repositories the account knows that this device neither has nor dismissed. */
    val repositorySuggestions: List<BrowseRepositorySuggestion> = emptyList(),
    val sources: List<BrowseSource> = emptyList(),
    val extensions: List<BrowseExtension> = emptyList(),
    val migrations: List<MigrationCandidate> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

public data class BrowseRepository(
    val id: String,
    val url: String,
    val name: String,
    val website: String? = null,
    val signingFingerprint: String? = null,
    /** It now publishes signed documents; confirm its publisher to refresh it again. */
    val needsPublisherTrust: Boolean = false,
)

/** A repository another device added to the account; adding it here goes through the usual trust flow. */
public data class BrowseRepositorySuggestion(
    val url: String,
    val name: String,
    val signingFingerprint: String? = null,
)

public data class BrowseSource(
    val id: Long,
    val name: String,
    val language: String,
    val baseUrl: String = "",
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val isNsfw: Boolean = false,
    val supportsLatest: Boolean = true,
    val supportsLogin: Boolean = false,
    /** The source can hold a sign-in: a login hook, or cookies kept from a browser sign-in or an import. */
    val canSignIn: Boolean = supportsLogin,
    /** The user signed in here (a login, a browser sign-in or a cookie they added) and has not signed out. */
    val signedIn: Boolean = false,
    /** Login must complete in the source's browser because credentials alone cannot mint a session. */
    val requiresBrowserSessionLogin: Boolean = false,
    /** The source exposes an account-owned remote collection (for example Wenku8 bookcase). */
    val supportsFavorites: Boolean = false,
    /** v2 browse option key used to open the source-owned collection. */
    val favoriteBrowseOptionKey: String? = null,
    /** v2 browse option value used to open the source-owned collection. */
    val favoriteBrowseOptionValue: String = "bookcase",
    val credential: SourceCredential? = null,
    val cookies: List<SourceCookie> = emptyList(),
    val preferences: List<SourcePreference> = emptyList(),
    val filters: List<BrowseFilter> = emptyList(),
    /** Exact v2 authority. [id] is only a process-local UI row key when this is non-null. */
    val sourceKey: SourceKey? = null,
    /** Resolved from repository metadata; older sources intentionally default to BOTH. */
    val contentType: PluginContentType = PluginContentType.BOTH,
    /** The repository ([BrowseRepository.id]) the providing package was installed from, when known. */
    val repositoryId: String? = null,
) {
    val identityKey: String get() = sourceKey?.canonicalId ?: "legacy:$id"
}

/** Stable UI-side representation of executable source filters, preserving recursive order/state. */
public sealed interface BrowseFilter {
    public val name: String

    public data class Header(override val name: String) : BrowseFilter

    public data object Separator : BrowseFilter {
        override val name: String = ""
    }

    public data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : BrowseFilter

    public data class Text(
        override val name: String,
        val state: String,
    ) : BrowseFilter

    public data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : BrowseFilter

    public data class TriState(
        override val name: String,
        val state: BrowseTriState,
    ) : BrowseFilter

    public data class Group(
        override val name: String,
        val filters: List<BrowseFilter>,
    ) : BrowseFilter

    public data class Sort(
        override val name: String,
        val values: List<String>,
        val selection: BrowseSortSelection?,
    ) : BrowseFilter
}

public enum class BrowseTriState {
    Ignore,
    Include,
    Exclude,
}

public data class BrowseSortSelection(
    val index: Int,
    val ascending: Boolean,
)


public data class BrowseExtension(
    val id: String,
    val name: String,
    val version: String,
    val language: String,
    val iconUrl: String? = null,
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
    val trusted: Boolean = false,
    /** User blocklist state; exposed so the row can offer an immediate unblock action. */
    val blockedByUser: Boolean = false,
    val blockEntryKey: String? = null,
    val isNsfw: Boolean = false,
    val sourceIds: List<Long> = emptyList(),
    val description: String? = null,
    val contentType: PluginContentType = PluginContentType.BOTH,
    /** ADR-0002 §8.1: an unsigned package loaded from a developer-mode repository. */
    val inDevelopment: Boolean = false,
    /** The repository (its [BrowseRepository.id]) the package is listed in or was installed from. */
    val repositoryId: String? = null,
    /**
     * Installed from the account's package list on this device's own and still waiting for the
     * user's permission review: nothing of it runs until then.
     */
    val preinstalledFromAccount: Boolean = false,
)

public data class BrowseManga(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    /** Headers prepared by the source request pipeline for the cover image. */
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
    /** Exact v2 authority; legacy consumers must reject this item instead of using [sourceId]. */
    val sourceKey: SourceKey? = null,
    val remotePublicationId: String? = null,
) {
    val identityKey: String
        get() = sourceKey?.let { "${it.canonicalId}:${requireNotNull(remotePublicationId)}" }
            ?: "legacy:$sourceId:$url"
}

public data class BrowsePage(
    val items: List<BrowseManga> = emptyList(),
    val hasNextPage: Boolean = false,
)

public data class MigrationCandidate(
    val mangaId: Long,
    val title: String,
    val thumbnailUrl: String? = null,
    val currentSourceName: String,
    val suggestions: List<BrowseManga> = emptyList(),
    /** Legacy source of the library row; [LOCAL_SOURCE_ID] for an extension-backed title. */
    val sourceId: Long = -1,
    /** Exact v2 source of an extension-backed title; such a title migrates only to v2 sources. */
    val sourceKey: SourceKey? = null,
    val remotePublicationId: String? = null,
    /** Manga or novel, from the current source; [PluginContentType.BOTH] when it is unknown. */
    val contentType: PluginContentType = PluginContentType.BOTH,
)

/** Fills the current source's name and content type from the installed source list. */
internal fun MigrationCandidate.resolvedAgainst(sources: List<BrowseSource>): MigrationCandidate {
    val current = if (sourceKey != null) {
        sources.firstOrNull { it.sourceKey == sourceKey }
    } else {
        sources.firstOrNull { it.sourceKey == null && it.id == sourceId }
    }
    return copy(
        currentSourceName = current?.name ?: currentSourceName,
        contentType = current?.contentType ?: contentType,
    )
}

/** ADR-0002 S5: a repository presenting a publisher key the user has not trusted yet. */
public data class RepositoryTrustPrompt(
    val url: String,
    /** As the repository names itself: not verified. */
    val name: String,
    /** First 128 bits, grouped for reading; the full value is what gets compared. */
    val displayFingerprint: String,
    /** The user chose to require a full fingerprint match (mode b). */
    val fingerprintRequired: Boolean,
    /** From an add-repository link: the fingerprint the publisher handed out, to be matched. */
    val linkedFingerprint: String? = null,
    /** The full fingerprint of the key shown: confirming trusts this key and no other. */
    val keyFingerprint: String = "",
    /** The repository's publisher is already trusted with this key. */
    val alreadyTrusted: Boolean = false,
)

public enum class PublisherTrustState { TRUSTED, PENDING_FINGERPRINT, KEY_REVOKED, KEY_REPLACED }

/**
 * Everything the permission page shows for one exact artifact (ADR-0002 §6.5). The publisher's
 * key is the identity: a package name can be anything, so the page puts the publisher first.
 */
public data class PluginPermissionDetails(
    val packageName: String,
    val version: String,
    /** Null for a package without a publisher key (it cannot run, unless [inDevelopment]). */
    val publisherDisplayFingerprint: String?,
    val publisherState: PublisherTrustState?,
    val fingerprintVerified: Boolean,
    val repositoryUrl: String?,
    val requestOrigins: List<String>,
    val contentOrigins: List<String>,
    /** Where cookies and, for login, the account password may be sent. */
    val credentialOrigins: List<String>,
    val browserSessionOrigins: List<String>,
    /** Where the isolated browser may load images from, with BROWSER_IMAGES. */
    val browserImageOrigins: List<String> = emptyList(),
    /** Sign-in sites the browser challenge may visit besides the source itself, with BROWSER_CHALLENGE. */
    val browserLoginOrigins: List<String> = emptyList(),
    /** ADR-0002 §8.1: unsigned, from a developer-mode repository; runs only while the mode is on. */
    val inDevelopment: Boolean = false,
)

/** An update's permission page, from the repository's index (nothing downloaded yet). */
public data class UpdatePermissionPreview(val review: PluginEventGrantReview, val details: PluginPermissionDetails)

/** A granted plugin's review and the items currently granted from it (source settings → permissions). */
public data class InstalledPluginPermissions(
    val review: PluginEventGrantReview,
    val granted: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    val details: PluginPermissionDetails,
)

public data class PackagePublisher(
    val id: String,
    val displayFingerprint: String,
    val state: PublisherTrustState,
    /** Trusted by a matched fingerprint or built into the app, rather than on first use. */
    val fingerprintVerified: Boolean,
    val official: Boolean,
    val blocked: Boolean,
)

public data class PackageBlock(val key: String, val label: String)

public data class PackageSecuritySnapshot(
    val strictMode: Boolean = false,
    val publishers: List<PackagePublisher> = emptyList(),
    val blocks: List<PackageBlock> = emptyList(),
    /** Null where developer mode is unavailable (previews). */
    val developerMode: PluginDeveloperModeSettings? = null,
)

/** ADR-0002 §8.1: loading unsigned packages from this device or the LAN addresses entered here. */
public data class PluginDeveloperModeSettings(val enabled: Boolean, val lanHosts: List<String>)
