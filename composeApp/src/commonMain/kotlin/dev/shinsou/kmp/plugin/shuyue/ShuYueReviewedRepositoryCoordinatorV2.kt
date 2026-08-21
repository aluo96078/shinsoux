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
                )
            }
        }
        val entry = requireNotNull(index.entries.singleOrNull { it.id == packageId && matchesReviewedMetadata(it) }) {
            "Unknown reviewed ShuYue package '$packageId'"
        }
        installer.stage(loader.downloadScript(index, entry))
    }

    private fun matchesReviewedMetadata(entry: ShuYueRepositoryEntry): Boolean {
        val profile = ShuYueReviewedPluginCatalogV2.profiles.singleOrNull { candidate ->
            candidate.identity.packageId == entry.id &&
                candidate.identity.version == entry.version &&
                candidate.identity.versionCode == entry.versionCode
        } ?: return false
        return entry.sources.map(ShuYueRepositorySource::id) == listOf(profile.sourceId)
    }

    public companion object {
        public const val DEFAULT_REVIEWED_SHUYUE_INDEX_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/index.json"
        public const val DEFAULT_REVIEWED_SHUYUE_ARTIFACT_ORIGIN: String =
            "https://raw.githubusercontent.com"
    }
}
