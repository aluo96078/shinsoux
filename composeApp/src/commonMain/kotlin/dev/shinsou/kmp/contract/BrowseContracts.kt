package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.plugin.PluginLogoutConfirmation
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// R4-1 (docs/architecture/0006): the extension/source bridge the UI talks to.

private val emptySourceLoginRequests = MutableStateFlow<List<SourceLoginRequest>>(emptyList())
private val emptySourceRefreshInvalidations = MutableStateFlow<Map<SourceKey, Long>>(emptyMap())
private val emptyLogoutConfirmations = MutableStateFlow<List<PluginLogoutConfirmation>>(emptyList())
private val emptyExtensionDownloads =
    MutableStateFlow<List<dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2>>(emptyList())

/** Browsing, searching and resolving titles of legacy sources. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface SourceCatalog {
    val state: StateFlow<BrowseSnapshot>

    /** Exact-source generations emitted by admitted plugin refresh events. */
    val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>>
        get() = emptySourceRefreshInvalidations

    suspend fun refresh() = Unit

    suspend fun browseSource(
        sourceId: Long,
        query: String = "",
        page: Int = 1,
        filters: List<BrowseFilter>? = null,
    ): BrowsePage = BrowsePage()

    /** Loads the source's latest-updates catalogue when that capability is advertised. */
    suspend fun browseSourceLatest(
        sourceId: Long,
        page: Int = 1,
    ): BrowsePage = browseSource(sourceId = sourceId, page = page)

    /** Loads a legacy source's account-owned collection without using the local library. */
    suspend fun browseSourceFavorites(
        sourceId: Long,
        page: Int = 1,
    ): BrowsePage = browseSource(sourceId = sourceId, page = page)

    /** True when [sourceId] belongs to an executable plugin source. */
    fun isPluginSource(sourceId: Long): Boolean = false

    fun isPluginSource(sourceKey: SourceKey): Boolean = sourceKey.legacyLongId?.let(::isPluginSource) == true

    /**
     * Fetches a plugin-owned image through the host content plane. Implementations must return
     * bounded bytes only; UI image loaders must never receive a plugin-controlled remote URL.
     */
    suspend fun loadPluginThumbnail(
        sourceId: Long,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? = null

    suspend fun loadPluginThumbnail(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? = sourceKey.legacyLongId?.let { loadPluginThumbnail(it, url, headers) }

    /** Returns the local manga id when the bridge imported or resolved the item. */
    suspend fun resolveManga(item: BrowseManga): Long? = null

    suspend fun migrateManga(mangaId: Long, target: BrowseManga) = Unit
}

/** Enabling sources and saving their preferences. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface SourceSettings {
    suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean) = Unit

    suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean) = Unit

    suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>) = Unit
}

/** Browsing and reading publications of v2 extensions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface ExtensionPublicationCatalog {
    /** Exact, capability-gated v2 source; opaque ids are never projected into legacy Long DTOs. */
    suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? = null

    /** Production v2 browse route. Page numbering is zero-based at the extension contract. */
    suspend fun browseSourceV2(
        sourceKey: SourceKey,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2> =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int = 0,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
    ): PagedResultV2<RemotePublicationV2> =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2> =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): PagedResultV2<RemoteUnitV2> =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    /** Returns every representation supplied for the exact unit; no preferred form is selected. */
    suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 =
        throw IllegalArgumentException("Unknown extension v2 source: ${sourceKey.canonicalId}")

    /** Host-issued details/unit capabilities; raw extension units never become UI authority. */
    suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): ExtensionPublicationPageV2 =
        throw IllegalStateException("Extension v2 content storage is unavailable")

    /**
     * Loads units with catalogue metadata already available, avoiding a duplicate details request
     * before the chapter list can be rendered. Implementations may still refresh full details
     * independently after the initial page becomes visible.
     */
    suspend fun extensionPublicationUnitsPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int = 0,
    ): ExtensionPublicationPageV2 = extensionPublicationPageV2(sourceKey, remotePublicationId, page)

    /** Acquires one explicitly selected representation into the shared content transaction. */
    /** Cached details and full unit list of a library title, as an offline unit page. */
    suspend fun cachedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): ExtensionPublicationPageV2? = null

    /** Records a library title's details and complete unit list for offline and instant display. */
    suspend fun cacheExtensionPublicationV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
        units: List<dev.shinsou.kmp.plugin.v2.RemoteUnitV2>,
    ) = Unit

    suspend fun forgetCachedExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String) = Unit
}

/** How a v2 publication is linked to the local library. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface ExtensionLibraryBinding {
    /** Recovers the exact extension authority for a legacy UUID-only local-library row. */
    fun extensionLibraryBindingV2(
        publicationKey: PublicationKey,
    ): ExtensionLibraryBindingV2? = null

    /** Local-only chapter completion and exact resume unit for a typed extension publication. */
    fun extensionLocalReadingStateV2(
        publicationKey: PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()

    /** Also resolves progress of listed units never opened here (for example carried by a migration). */
    fun extensionLocalReadingStateV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitIds: Collection<String>,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionLocalReadingStateV2(dev.shinsou.kmp.plugin.v2.extensionPublicationKey(sourceKey, remotePublicationId))

    /** Source unit ids of a typed extension publication whose content is stored offline. */
    fun extensionDownloadedUnitIdsV2(publicationKey: PublicationKey): Set<String> = emptySet()

    /**
     * Best-effort repair for beta-era UUID-only extension favorites. Implementations must return
     * a binding only after the deterministic publication UUID matches an authoritative search row.
     */
    suspend fun recoverExtensionLibraryBindingV2(
        publicationKey: PublicationKey,
        title: String,
    ): ExtensionLibraryBindingV2? = null

    /** Source-owned favorite/library mutation for v2 publications when the capability exists. */
    suspend fun favoriteExtensionPublicationV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    ) = Unit
}

/** The v2 download queue and offline content. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface ContentAcquisition {
    /** App-scoped extension (novel) chapter downloads; they outlive the detail screen. */
    val extensionDownloadsV2: StateFlow<List<dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2>>
        get() = emptyExtensionDownloads

    suspend fun enqueueExtensionDownloadsV2(
        selections: List<dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2>,
    ): Unit = throw UnsupportedOperationException("Extension downloads are unavailable")

    suspend fun retryExtensionDownloadV2(id: String) = Unit

    suspend fun removeExtensionDownloadV2(id: String) = Unit

    suspend fun clearCompletedExtensionDownloadsV2() = Unit

    /** Removes every queued or finished download row of one title (when the title is deleted). */
    suspend fun removeExtensionPublicationDownloadsV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String) = Unit

    /**
     * Deletes the stored chapter bodies of a title that left the library, keeping its reading
     * progress and library binding. Local only: nothing is propagated through sync.
     */
    suspend fun purgeExtensionPublicationContentV2(sourceKey: dev.shinsou.kmp.domain.model.SourceKey, remotePublicationId: String) = Unit

    suspend fun pauseExtensionDownloadsV2(paused: Boolean) = Unit

    suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
    ): ExtensionContentMaterializationV2 =
        throw IllegalStateException("Extension v2 content storage is unavailable")

    /** Opens the exact immutable revision returned by materialization through host rights. */
    suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession =
        throw IllegalStateException("Extension v2 reader content is unavailable")
}

/** Source sign-in, credentials and logout. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface SourceAuth {
    /** FIFO login prompts requested by sources, deduplicated by source id. */
    val loginRequests: StateFlow<List<SourceLoginRequest>>
        get() = emptySourceLoginRequests

    val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>>
        get() = emptyLogoutConfirmations

    suspend fun confirmPluginLogout(eventId: String): Boolean = false

    fun dismissPluginLogout(eventId: String) = Unit

    fun dismissAllPluginLogouts() = Unit

    suspend fun setPluginUiAvailable(available: Boolean) = Unit

    fun dismissSourceLoginRequest(sourceId: Long) = Unit

    fun dismissSourceLoginEvent(eventId: String) = Unit

    /** Invokes the advertised login contract and stores credentials only after a successful login. */
    suspend fun saveSourceCredentials(sourceId: Long, username: String, password: String): Boolean = false

    suspend fun saveSourceCredentialsResult(
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        SourceLoginResult(saveSourceCredentials(sourceId, username, password))
    } catch (_: Throwable) {
        // Runtime exceptions may contain transport details or secrets; the UI uses its stable
        // generic fallback when a source does not provide an explicit safe failure message.
        SourceLoginResult(false, failureStage = SourceLoginFailureStage.UNKNOWN)
    }

    suspend fun saveSourceEventCredentialsResult(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        SourceLoginResult(saveSourceEventCredentials(eventId, sourceId, username, password))
    } catch (_: Throwable) {
        // Runtime exceptions may contain transport details or secrets; the UI uses its stable
        // generic fallback when a source does not provide an explicit safe failure message.
        SourceLoginResult(false, failureStage = SourceLoginFailureStage.UNKNOWN)
    }

    suspend fun saveSourceEventCredentials(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceCredentials(sourceId, username, password)

    /**
     * Decrypts one source's private settings on demand. Catalogue refresh and source discovery
     * must never call this method because protected storage may require interactive OS access.
     */
    suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult = SourceSecretsResult()

    suspend fun logoutSource(sourceId: Long) = Unit
}

/** Source cookies and browser challenges. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface SourceWebSession {
    suspend fun setSourceCookie(sourceId: Long, cookie: SourceCookie) = Unit

    /** Persists one isolated browser session and its browser-bound User-Agent together. */
    suspend fun importSourceWebChallengeSession(
        sourceId: Long,
        capability: SourceWebChallengeCapability,
        cookies: List<SourceCookie>,
        userAgent: String,
        localStorage: Map<String, String> = emptyMap(),
    ) = Unit

    /** Invalidates a browser capability when its isolated browser is dismissed without import. */
    suspend fun cancelSourceWebChallenge(capability: SourceWebChallengeCapability) = Unit

    suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String) = Unit

    suspend fun clearSourceCookies(sourceId: Long) = Unit

    /**
     * Reports whether the exact live source artifact may start a host-reviewed Web challenge.
     * This is an availability check only: it must not issue a capability or read source secrets.
     */
    suspend fun isSourceWebChallengeAvailable(sourceId: Long): Boolean = false

    /**
     * Builds the browser challenge request with the exact source URL and source-isolated cookies.
     * A platform browser may need its native User-Agent to complete anti-bot verification; the
     * actual value captured with the resulting cookies is then reused by Browse/Reader/Download.
     */
    suspend fun sourceWebChallenge(
        sourceId: Long,
        username: String? = null,
        password: String? = null,
    ): SourceWebChallengeRequest? = null
}

/** Repositories, installing and updating extensions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface ExtensionManagement {
    suspend fun addRepository(url: String): BrowseRepository? = null

    /**
     * ADR-0002 S5: a signed repository's key, for the user to confirm before [trustAndAddRepository].
     * Null when the repository answered but is not signed ([addRepository] takes the URL as it is);
     * throws when it could not be reached, so a caller can try again later.
     */
    suspend fun inspectRepositoryTrust(url: String): RepositoryTrustPrompt? = null

    /**
     * Trusts exactly the key [prompt] showed (refused if the repository presented another one since)
     * — with [fingerprint] when the user entered one — and adds the repository.
     */
    suspend fun trustAndAddRepository(prompt: RepositoryTrustPrompt, fingerprint: String?): BrowseRepository? = null

    suspend fun removeRepository(repositoryId: String) = removeRepository(repositoryId, fromAccount = false)

    /**
     * Removes a repository from this device. Deleting is local by default; with [fromAccount] the
     * repository also stops being suggested to the account's other devices (those that already
     * added it keep it).
     */
    suspend fun removeRepository(repositoryId: String, fromAccount: Boolean) = Unit

    /** Hides a repository suggested by the account on this device only; it is never suggested here again. */
    suspend fun dismissRepositorySuggestion(url: String) = Unit

    suspend fun selectRepository(repositoryId: String?) = Unit

    suspend fun installExtension(extensionId: String) = Unit

    /**
     * ADR-0002 §6.3: what an update of a running plugin would ask for, when it asks for something
     * new — reviewed before updating, so the installed version keeps running until accepted.
     */
    suspend fun updatePermissionPreview(extensionId: String): UpdatePermissionPreview? = null

    /**
     * Installs the update [preview] showed and grants [selection] to it. Returns the update's own
     * review when it turned out to ask for something else than the preview (it is then not granted).
     */
    suspend fun installReviewedUpdate(
        extensionId: String,
        preview: UpdatePermissionPreview,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    ): PluginEventGrantReview? = null

    suspend fun uninstallExtension(extensionId: String) = Unit

    suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean) = Unit
}

/** Publisher trust, blocks, developer mode and permissions. Part of [BrowseCallbacks] (R4-2, docs/architecture/0006). */
interface ExtensionTrust {
    suspend fun packageSecurity(): PackageSecuritySnapshot = PackageSecuritySnapshot()

    suspend fun setStrictPublisherTrust(strict: Boolean) = Unit

    suspend fun setPluginDeveloperMode(enabled: Boolean) = Unit

    /** Throws when an address is not on a private network. */
    suspend fun setPluginDeveloperHosts(hosts: List<String>) = Unit

    suspend fun confirmPublisherFingerprint(publisherId: String, fingerprint: String) = Unit

    suspend fun setPublisherBlocked(publisherId: String, blocked: Boolean) = Unit

    suspend fun setExtensionBlocked(extensionId: String, blocked: Boolean) = Unit

    suspend fun unblockPackageEntry(key: String) = Unit

    suspend fun pendingPluginEventGrantReview(extensionId: String): PluginEventGrantReview? = null

    suspend fun approvePluginEventGrantReview(
        extensionId: String,
        expectedReview: PluginEventGrantReview,
        permissions: Set<PluginHostPermission>,
        selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection? = null,
    ) = Unit

    /** Who published [review]'s artifact and where it connects: what the permission page shows (ADR-0002 §6.5). */
    suspend fun pluginPermissionDetails(extensionId: String, review: PluginEventGrantReview): PluginPermissionDetails? = null

    /** The granted plugin's current permissions, for source settings; null when not granted. */
    suspend fun installedPluginPermissions(extensionId: String): InstalledPluginPermissions? = null

    /** Withdraws sensitive items from a granted plugin and reloads it. */
    suspend fun narrowPluginPermissions(extensionId: String, selection: dev.shinsou.kmp.plugin.events.PluginGrantSelection) = Unit
}

/** Extension/source bridge. Plugin modules adapt their own models to these DTOs. */
interface BrowseCallbacks :
    SourceCatalog,
    SourceSettings,
    ExtensionPublicationCatalog,
    ExtensionLibraryBinding,
    ContentAcquisition,
    SourceAuth,
    SourceWebSession,
    ExtensionManagement,
    ExtensionTrust {
    companion object {
        private val emptyState = MutableStateFlow(BrowseSnapshot())
        val None: BrowseCallbacks = object : BrowseCallbacks {
            override val state: StateFlow<BrowseSnapshot> = emptyState
        }
    }
}

data class BrowseSnapshot(
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

data class BrowseRepository(
    val id: String,
    val url: String,
    val name: String,
    val website: String? = null,
    val signingFingerprint: String? = null,
    /** It now publishes signed documents; confirm its publisher to refresh it again. */
    val needsPublisherTrust: Boolean = false,
)

/** A repository another device added to the account; adding it here goes through the usual trust flow. */
data class BrowseRepositorySuggestion(
    val url: String,
    val name: String,
    val signingFingerprint: String? = null,
)

data class BrowseSource(
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
sealed interface BrowseFilter {
    val name: String

    data class Header(override val name: String) : BrowseFilter

    data object Separator : BrowseFilter {
        override val name: String = ""
    }

    data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : BrowseFilter

    data class Text(
        override val name: String,
        val state: String,
    ) : BrowseFilter

    data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : BrowseFilter

    data class TriState(
        override val name: String,
        val state: BrowseTriState,
    ) : BrowseFilter

    data class Group(
        override val name: String,
        val filters: List<BrowseFilter>,
    ) : BrowseFilter

    data class Sort(
        override val name: String,
        val values: List<String>,
        val selection: BrowseSortSelection?,
    ) : BrowseFilter
}

enum class BrowseTriState {
    Ignore,
    Include,
    Exclude,
}

data class BrowseSortSelection(
    val index: Int,
    val ascending: Boolean,
)


data class BrowseExtension(
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

data class BrowseManga(
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

data class BrowsePage(
    val items: List<BrowseManga> = emptyList(),
    val hasNextPage: Boolean = false,
)

data class MigrationCandidate(
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
data class RepositoryTrustPrompt(
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

enum class PublisherTrustState { TRUSTED, PENDING_FINGERPRINT, KEY_REVOKED, KEY_REPLACED }

/**
 * Everything the permission page shows for one exact artifact (ADR-0002 §6.5). The publisher's
 * key is the identity: a package name can be anything, so the page puts the publisher first.
 */
data class PluginPermissionDetails(
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
data class UpdatePermissionPreview(val review: PluginEventGrantReview, val details: PluginPermissionDetails)

/** A granted plugin's review and the items currently granted from it (source settings → permissions). */
data class InstalledPluginPermissions(
    val review: PluginEventGrantReview,
    val granted: dev.shinsou.kmp.plugin.events.PluginGrantSelection,
    val details: PluginPermissionDetails,
)

data class PackagePublisher(
    val id: String,
    val displayFingerprint: String,
    val state: PublisherTrustState,
    /** Trusted by a matched fingerprint or built into the app, rather than on first use. */
    val fingerprintVerified: Boolean,
    val official: Boolean,
    val blocked: Boolean,
)

data class PackageBlock(val key: String, val label: String)

data class PackageSecuritySnapshot(
    val strictMode: Boolean = false,
    val publishers: List<PackagePublisher> = emptyList(),
    val blocks: List<PackageBlock> = emptyList(),
    /** Null where developer mode is unavailable (previews). */
    val developerMode: PluginDeveloperModeSettings? = null,
)

/** ADR-0002 §8.1: loading unsigned packages from this device or the LAN addresses entered here. */
data class PluginDeveloperModeSettings(val enabled: Boolean, val lanHosts: List<String>)
