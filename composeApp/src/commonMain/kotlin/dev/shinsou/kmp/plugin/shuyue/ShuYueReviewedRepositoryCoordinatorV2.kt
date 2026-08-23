package dev.shinsou.kmp.plugin.shuyue

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Body-free reviewed package metadata shown by the production extension manager. */
public data class ShuYueReviewedRepositoryPackageV2(
    val packageId: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val languageTag: String,
    val isNsfw: Boolean,
    val sourceIds: List<String>,
    val description: String?,
    val sha256: String? = null,
    val contentType: dev.shinsou.kmp.plugin.PluginContentType = dev.shinsou.kmp.plugin.PluginContentType.BOTH,
)

/**
 * Production repository path: refresh inert metadata, download one exact entry, then hand its
 * bytes to the existing quarantine/review boundary. No method here grants trust or executes code.
 */
public class ShuYueReviewedRepositoryCoordinatorV2(
    private val loader: ShuYueRepositoryIndexLoader,
    private val installer: ShuYueReviewedInstallCoordinatorV2,
    private val location: ShuYueRepositoryLocation = ShuYueRepositoryLocation.IndexUrl(
        DEFAULT_REVIEWED_SHUYUE_INDEX_URL,
    ),
) {
    private val mutex = Mutex()
    private var loadedIndex: ShuYueRepositoryIndex? = null
    private var packages: List<ShuYueReviewedRepositoryPackageV2> = emptyList()

    public suspend fun refresh(): List<ShuYueReviewedRepositoryPackageV2> = mutex.withLock {
        val index = loader.load(location)
        val reviewedEntries = index.entries.filter(::matchesReviewedMetadata)
        loadedIndex = index
        packages = reviewedEntries.map { entry ->
            ShuYueReviewedRepositoryPackageV2(
                packageId = entry.id,
                name = entry.name,
                version = entry.version,
                versionCode = entry.versionCode,
                languageTag = entry.lang,
                isNsfw = entry.nsfw == 1,
                sourceIds = entry.sources.map(ShuYueRepositorySource::id),
                description = entry.description,
                sha256 = entry.sha256,
                contentType = entry.resolvedContentType,
            )
        }
        packages
    }

    public suspend fun cachedPackages(): List<ShuYueReviewedRepositoryPackageV2> =
        mutex.withLock { packages.toList() }

    public suspend fun stage(packageId: String): ShuYueQuarantineReviewV2 = mutex.withLock {
        val index = loadedIndex ?: loader.load(location).also { loaded ->
            loadedIndex = loaded
            packages = loaded.entries.filter(::matchesReviewedMetadata).map { entry ->
                ShuYueReviewedRepositoryPackageV2(
                    packageId = entry.id,
                    name = entry.name,
                    version = entry.version,
                    versionCode = entry.versionCode,
                    languageTag = entry.lang,
                    isNsfw = entry.nsfw == 1,
                    sourceIds = entry.sources.map(ShuYueRepositorySource::id),
                    description = entry.description,
                    sha256 = entry.sha256,
                    contentType = entry.resolvedContentType,
                )
            }
        }
        val entry = requireNotNull(index.entries.singleOrNull { it.id == packageId && matchesReviewedMetadata(it) }) {
            "Unknown reviewed ShuYue package '$packageId'"
        }
        installer.stage(loader.downloadScript(index, entry))
    }

    private fun matchesReviewedMetadata(entry: ShuYueRepositoryEntry): Boolean {
        // Compatibility-only packages remain in the admission catalogue for existing installs,
        // but a repository refresh must not offer them as new sources.
        if (!entry.installable || entry.referenceOnly || entry.legacyCompatibilityOnly) return false
        val profile = ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
            packageId = entry.id,
            version = entry.version,
            versionCode = entry.versionCode,
            sha256 = entry.sha256,
        ) ?: return false
        val indexedSources = entry.sources.associateBy { it.id }
        if (indexedSources.size != entry.sources.size || indexedSources.keys != profile.sourceIds.toSet()) return false
        return entry.name == profile.displayName &&
            entry.lang == profile.languageTag &&
            profile.sourceProfiles.all { sourceProfile ->
                val source = indexedSources[sourceProfile.sourceId] ?: return@all false
                source.name == sourceProfile.sourceName &&
                    source.lang == sourceProfile.languageTag &&
                    source.baseUrl == sourceProfile.baseUrl
            } &&
            (entry.sha256 == null || entry.sha256 == profile.identity.sha256) &&
            eventDeclarationsMatch(entry.systemEvents, profile.systemEvents) &&
            (entry.requestedHostPermissions.isEmpty() || entry.requestedHostPermissions ==
                if (dev.shinsou.kmp.plugin.v2.ExtensionCapability.LOGIN in profile.capabilities) {
                    setOf(dev.shinsou.kmp.plugin.events.PluginHostPermission.REQUEST_LOGIN_UI)
                } else {
                    emptySet()
                })
    }

    /**
     * V2 indexes require a system-events object even for sources that expose no events. Treat an
     * empty declaration as equivalent to the reviewed profile's `null` declaration; otherwise all
     * non-login ShuYue packages are silently dropped during repository refresh.
     */
    private fun eventDeclarationsMatch(
        indexed: dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration?,
        reviewed: dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration?,
    ): Boolean {
        // Legacy ShuYue indexes predate the v2 event declaration and omit the field entirely.
        // Their capability metadata is still reviewed elsewhere, so an absent declaration is a
        // compatible wildcard rather than a reason to hide an otherwise valid package.
        if (indexed == null) return true
        val indexedMeaningful = indexed.takeIf { it.required.isNotEmpty() || it.optional.isNotEmpty() }
        val reviewedMeaningful = reviewed?.takeIf { it.required.isNotEmpty() || it.optional.isNotEmpty() }
        return indexedMeaningful == reviewedMeaningful
    }

    public companion object {
        public const val DEFAULT_REVIEWED_SHUYUE_INDEX_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master/index.json"
        /** Preserved for existing repository records and legacy-format compatibility tests. */
        public const val LEGACY_REVIEWED_SHUYUE_INDEX_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/index.json"
        public const val DEFAULT_REVIEWED_SHUYUE_ARTIFACT_ORIGIN: String =
            "https://raw.githubusercontent.com"
    }
}
