package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.model.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
enum class MaterializationIssueKind {
    ORPHAN,
    IDENTITY_COLLISION,
    INVALID_FIELD,
}

@Serializable
data class MaterializationIssue(
    val kind: MaterializationIssueKind,
    val key: SyncEntityKey?,
    val message: String,
)

@Serializable
enum class RepositoryTrustConfirmationStatus {
    PENDING,
    REJECTED,
    ACCEPTED,
}

/**
 * Device-local review of one exact repository signing-key transition. Neither fingerprints nor a
 * decision are secrets, but they must never be reduced into a sync event: accepting trust on one
 * device cannot silently grant it on another.
 */
@Serializable
data class RepositoryTrustConfirmation(
    val repositoryKey: SyncEntityKey,
    val baseUrl: String,
    val trustedFingerprint: String,
    val proposedFingerprint: String,
    val status: RepositoryTrustConfirmationStatus = RepositoryTrustConfirmationStatus.PENDING,
) {
    init {
        require(repositoryKey.entityType == SyncEntityType.EXTENSION_REPOSITORY) {
            "Repository trust confirmation has the wrong entity type"
        }
        require(baseUrl == repositoryKey.canonicalValue) {
            "Repository trust confirmation does not match its stable identity"
        }
        require(proposedFingerprint.isNotBlank()) { "A proposed repository fingerprint cannot be blank" }
        require(proposedFingerprint != trustedFingerprint) {
            "Repository trust confirmation must describe a fingerprint change"
        }
    }

    internal fun sameTransition(other: RepositoryTrustConfirmation): Boolean =
        repositoryKey == other.repositoryKey &&
            baseUrl == other.baseUrl &&
            trustedFingerprint == other.trustedFingerprint &&
            proposedFingerprint == other.proposedFingerprint
}

data class SyncMaterializationDiagnostics(
    val issues: List<MaterializationIssue> = emptyList(),
    val repositoryTrustConfirmations: List<RepositoryTrustConfirmation> = emptyList(),
) {
    val requiresAttention: Boolean
        get() = issues.isNotEmpty() || repositoryTrustConfirmations.isNotEmpty()
}

data class SnapshotMaterializationResult(
    val snapshot: AppSnapshot,
    val identityMap: SyncIdentityMap,
    val issues: List<MaterializationIssue>,
    val localMangaShellIds: Set<Long>,
    val localChapterShellIds: Set<Long>,
    val repositoryTrustConfirmations: List<RepositoryTrustConfirmation>,
) {
    val repositoriesRequiringTrustConfirmation: Set<String>
        get() = repositoryTrustConfirmations.mapTo(linkedSetOf()) { it.baseUrl }
}

class SnapshotMaterializationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private const val CONTENT_SOURCE_NAMESPACE_PREFIX = "source:"

/** Projects sync-owned data while preserving every explicitly device-owned AppSnapshot region. */
object SnapshotMaterializer {
    fun materialize(
        replica: SyncState,
        currentDeviceSnapshot: AppSnapshot,
        initialIdentityMap: SyncIdentityMap,
        acceptedRepositoryTrustChanges: List<RepositoryTrustConfirmation> = emptyList(),
    ): SnapshotMaterializationResult {
        currentDeviceSnapshot.validate()
        var identityMap = initialIdentityMap
        val issues = mutableListOf<MaterializationIssue>()
        replica.keyRemaps.keys.sorted().forEach { oldKey ->
            val finalKey = replica.resolveKey(oldKey)
            if (oldKey == finalKey) return@forEach
            if (oldKey in identityMap.blockedKeys || finalKey in identityMap.blockedKeys) {
                identityMap = identityMap.block(oldKey).block(finalKey)
                issues += MaterializationIssue(
                    MaterializationIssueKind.IDENTITY_COLLISION,
                    finalKey,
                    "A blocked identity remapped from ${oldKey.stableString()} to ${finalKey.stableString()}",
                )
                return@forEach
            }
            if (identityMap.localId(oldKey) == null) return@forEach
            try {
                identityMap = identityMap.relocateCanonicalAlias(oldKey, finalKey)
            } catch (collision: SyncIdentityCollisionException) {
                // Both aliases are blocked until an explicit local repair chooses how to detach
                // the obsolete mapping. Never allocate a third id or merge the two domain rows.
                identityMap = identityMap.block(oldKey).block(finalKey)
                issues += MaterializationIssue(
                    MaterializationIssueKind.IDENTITY_COLLISION,
                    finalKey,
                    collision.message.orEmpty(),
                )
            }
        }
        replica.entities.values
            .asSequence()
            .filter { it.isPresent && it.key in identityMap.blockedKeys }
            .map { it.key }
            .distinct()
            .sorted()
            .filter { blocked ->
                issues.none { it.kind == MaterializationIssueKind.IDENTITY_COLLISION && it.key == blocked }
            }
            .forEach { blocked ->
                issues += MaterializationIssue(
                    MaterializationIssueKind.IDENTITY_COLLISION,
                    blocked,
                    "Sync identity remains blocked until its local mapping is repaired",
                )
            }
        val reservedMangaIds = currentDeviceSnapshot.mangas.mapTo(mutableSetOf()) { it.id }
        val reservedChapterIds = currentDeviceSnapshot.chapters.mapTo(mutableSetOf()) { it.id }
        val reservedCategoryIds = currentDeviceSnapshot.categories.mapTo(mutableSetOf()) { it.id }

        fun allocate(key: SyncEntityKey, reserved: Set<Long>): Long? = try {
            val allocation = identityMap.allocate(key, reserved)
            identityMap = allocation.first
            allocation.second
        } catch (collision: SyncIdentityCollisionException) {
            identityMap = identityMap.block(key)
            issues += MaterializationIssue(MaterializationIssueKind.IDENTITY_COLLISION, key, collision.message.orEmpty())
            null
        }

        if (identityMap.localId(SyncEntityKey.defaultCategory()) == null) {
            try {
                identityMap = identityMap.bind(SyncEntityKey.defaultCategory(), Category.Default.id)
            } catch (collision: SyncIdentityCollisionException) {
                throw SnapshotMaterializationException("Default category identity is corrupt", collision)
            }
        }

        val mangaByKey = linkedMapOf<SyncEntityKey, Manga>()
        replica.entities.values
            .asSequence()
            .filter { it.key.entityType == SyncEntityType.MANGA && it.isPresent && it.key !in identityMap.blockedKeys }
            .sortedBy { it.key }
            .forEach { record ->
                val id = allocate(record.key, reservedMangaIds) ?: return@forEach
                val existing = currentDeviceSnapshot.mangas.firstOrNull { it.id == id }
                try {
                    mangaByKey[record.key] = record.toManga(id, existing)
                } catch (invalid: SnapshotMaterializationException) {
                    issues += MaterializationIssue(MaterializationIssueKind.INVALID_FIELD, record.key, invalid.message.orEmpty())
                }
            }

        val chapterByKey = linkedMapOf<SyncEntityKey, Chapter>()
        val chapterParentByKey = linkedMapOf<SyncEntityKey, SyncEntityKey>()
        replica.entities.values
            .asSequence()
            .filter { it.key.entityType == SyncEntityType.CHAPTER && it.isPresent && it.key !in identityMap.blockedKeys }
            .sortedBy { it.key }
            .forEach { record ->
                val parentKey = record.entityKey(SyncFields.Chapter.MANGA_KEY)
                if (parentKey == null || parentKey !in mangaByKey) {
                    issues += MaterializationIssue(
                        MaterializationIssueKind.ORPHAN,
                        record.key,
                        "Chapter parent is absent or tombstoned",
                    )
                    return@forEach
                }
                val id = allocate(record.key, reservedChapterIds) ?: return@forEach
                val existing = currentDeviceSnapshot.chapters.firstOrNull { it.id == id }
                try {
                    chapterByKey[record.key] = record.toChapter(
                        id = id,
                        mangaId = requireNotNull(mangaByKey[parentKey]).id,
                        parentKey = parentKey,
                        existing = existing,
                    )
                    chapterParentByKey[record.key] = parentKey
                } catch (invalid: SnapshotMaterializationException) {
                    issues += MaterializationIssue(MaterializationIssueKind.INVALID_FIELD, record.key, invalid.message.orEmpty())
                }
            }

        val histories = mutableListOf<History>()
        replica.readingProgress.entries.sortedBy { it.key }.forEach { (chapterKey, progress) ->
            if (progress.presence?.value != true) return@forEach
            val chapter = chapterByKey[chapterKey]
            val expectedParent = chapterParentByKey[chapterKey]
            if (chapter == null || expectedParent == null || expectedParent != progress.mangaKey) {
                issues += MaterializationIssue(
                    MaterializationIssueKind.ORPHAN,
                    chapterKey,
                    "Reading progress parent is absent or does not match its chapter",
                )
                return@forEach
            }
            val localHistory = currentDeviceSnapshot.histories.firstOrNull { it.chapterId == chapter.id }
            val remoteLastRead = progress.historyTouchedAt?.value?.takeIf { it > 0 }
            val keepNewerLocalVisualPage = localHistory != null &&
                (remoteLastRead == null || localHistory.lastRead > remoteLastRead)
            val updated = chapter.copy(
                read = progress.readState?.value ?: false,
                lastPageRead = if (keepNewerLocalVisualPage) {
                    currentDeviceSnapshot.chapters.firstOrNull { it.id == chapter.id }
                        ?.lastPageRead
                        ?: progress.position?.position?.pageIndex
                        ?: 0
                } else {
                    progress.position?.position?.pageIndex ?: 0
                },
                lastModifiedAt = maxOf(chapter.lastModifiedAt, progress.latestMillis()),
            )
            chapterByKey[chapterKey] = updated
            remoteLastRead?.let { lastRead ->
                histories += History(
                    id = updated.id,
                    chapterId = updated.id,
                    lastRead = if (keepNewerLocalVisualPage) {
                        requireNotNull(localHistory).lastRead
                    } else {
                        lastRead
                    },
                    timeRead = 0,
                    // Legacy progress sync carries only a visual page. Never erase the exact typed
                    // cursor retained on this device when rebuilding its compatibility snapshot.
                    lastLocator = localHistory?.lastLocator,
                    lastPageCount = localHistory?.lastPageCount
                        ?.takeIf { updated.lastPageRead in 0 until it },
                )
            }
        }

        val categories = mutableListOf(Category.Default)
        replica.entities.values
            .asSequence()
            .filter {
                it.key.entityType == SyncEntityType.CATEGORY &&
                    it.key != SyncEntityKey.defaultCategory() &&
                    it.isPresent &&
                    it.key !in identityMap.blockedKeys
            }
            .sortedBy { it.key }
            .forEach { record ->
                val id = allocate(record.key, reservedCategoryIds) ?: return@forEach
                val existing = currentDeviceSnapshot.categories.firstOrNull { it.id == id }
                try {
                    categories += record.toCategory(id, existing)
                } catch (invalid: SnapshotMaterializationException) {
                    issues += MaterializationIssue(MaterializationIssueKind.INVALID_FIELD, record.key, invalid.message.orEmpty())
                }
            }
        val categoryByKey = categories.associateBy { category ->
            identityMap.key(SyncEntityType.CATEGORY, category.id) ?: SyncEntityKey.defaultCategory()
        }

        val memberships = replica.categoryMemberships.entries.sortedBy { it.key }.mapNotNull { (key, register) ->
            if (!register.value) return@mapNotNull null
            val manga = mangaByKey[key.mangaKey]
            val category = categoryByKey[key.categoryKey]
            if (manga == null || category == null) {
                issues += MaterializationIssue(
                    MaterializationIssueKind.ORPHAN,
                    manga?.let { key.categoryKey } ?: key.mangaKey,
                    "Category membership parent is absent or tombstoned",
                )
                null
            } else {
                MangaCategory(manga.id, category.id)
            }
        }.distinct().sortedWith(compareBy<MangaCategory> { it.mangaId }.thenBy { it.categoryId })

        val trustConfirmations = mutableListOf<RepositoryTrustConfirmation>()
        val acceptedByUrl = acceptedRepositoryTrustChanges
            .filter { it.status == RepositoryTrustConfirmationStatus.ACCEPTED }
            .associateBy { it.baseUrl }
        val repositories = replica.entities.values
            .asSequence()
            .filter { it.key.entityType == SyncEntityType.EXTENSION_REPOSITORY && it.isPresent }
            .sortedBy { it.key }
            .mapNotNull { record ->
                try {
                    val remote = record.toExtensionRepository()
                    val local = currentDeviceSnapshot.extensionRepositories.firstOrNull {
                        runCatching {
                            SyncEntityKey.normalizeUrl(it.baseUrl, httpsOnly = true, requireAuthority = true)
                        }.getOrNull() == record.key.canonicalValue
                    }
                    val locallyTrustedFingerprint = local?.signingKeyFingerprint.orEmpty()
                    if (local == null && remote.signingKeyFingerprint.isBlank()) {
                        throw SnapshotMaterializationException(
                            "A newly discovered repository must provide a non-blank signing fingerprint",
                        )
                    }
                    val accepted = acceptedByUrl[remote.baseUrl]?.takeIf { approval ->
                        approval.repositoryKey == record.key &&
                            approval.trustedFingerprint == locallyTrustedFingerprint &&
                            approval.proposedFingerprint == remote.signingKeyFingerprint
                    }
                    if (remote.signingKeyFingerprint != locallyTrustedFingerprint) {
                        if (accepted != null) {
                            remote
                        } else if (remote.signingKeyFingerprint.isNotBlank()) {
                            trustConfirmations += RepositoryTrustConfirmation(
                                repositoryKey = record.key,
                                baseUrl = remote.baseUrl,
                                trustedFingerprint = locallyTrustedFingerprint,
                                proposedFingerprint = remote.signingKeyFingerprint,
                            )
                            // A repository learned only from another device is not contacted or
                            // exposed to plugin installation until this device confirms its key.
                            // An already configured repository remains pinned to its old key.
                            local?.let {
                                remote.copy(signingKeyFingerprint = locallyTrustedFingerprint)
                            }
                        } else {
                            // Older clients may publish an empty value, but cannot clear a local
                            // device's established signing trust.
                            remote.copy(signingKeyFingerprint = locallyTrustedFingerprint)
                        }
                    } else {
                        remote
                    }
                } catch (invalid: SnapshotMaterializationException) {
                    issues += MaterializationIssue(MaterializationIssueKind.INVALID_FIELD, record.key, invalid.message.orEmpty())
                    null
                }
            }
            .distinctBy { it.baseUrl }
            .toList()

        val localMangaShellIds = mutableSetOf<Long>()
        val localChapterShellIds = mutableSetOf<Long>()
        val materializedMangas = mangaByKey.values.associateByTo(linkedMapOf()) { it.id }
        val materializedChapters = chapterByKey.values.associateByTo(linkedMapOf()) { it.id }
        val requiredChapterIds = buildSet {
            currentDeviceSnapshot.downloadQueue.forEach { add(it.chapterId) }
            currentDeviceSnapshot.updates.forEach { add(it.chapterId) }
        }
        val requiredMangaIds = buildSet {
            currentDeviceSnapshot.downloadQueue.forEach { add(it.mangaId) }
            currentDeviceSnapshot.updates.forEach { add(it.mangaId) }
            currentDeviceSnapshot.tracks.forEach { add(it.mangaId) }
        }.toMutableSet()

        requiredChapterIds.forEach { chapterId ->
            if (chapterId !in materializedChapters) {
                val local = currentDeviceSnapshot.chapters.firstOrNull { it.id == chapterId }
                    ?: throw SnapshotMaterializationException("A device-local dependency refers to missing chapter $chapterId")
                materializedChapters[chapterId] = local
                localChapterShellIds += chapterId
                requiredMangaIds += local.mangaId
            }
        }
        requiredMangaIds.forEach { mangaId ->
            if (mangaId !in materializedMangas) {
                val local = currentDeviceSnapshot.mangas.firstOrNull { it.id == mangaId }
                    ?: throw SnapshotMaterializationException("A device-local dependency refers to missing manga $mangaId")
                materializedMangas[mangaId] = local.copy(favorite = false)
                localMangaShellIds += mangaId
            }
        }

        val nextRevision = if (currentDeviceSnapshot.revision < Long.MAX_VALUE - 2) {
            currentDeviceSnapshot.revision + 1
        } else {
            currentDeviceSnapshot.revision
        }
        val projected = currentDeviceSnapshot.copy(
            revision = nextRevision,
            settings = PortableSettingProjector.apply(currentDeviceSnapshot.settings, replica.portableSettings),
            mangas = materializedMangas.values.sortedBy { it.id },
            chapters = materializedChapters.values.sortedBy { it.id },
            categories = categories.distinctBy { it.id }.sortedBy { it.id },
            mangaCategories = memberships,
            histories = histories.distinctBy { it.chapterId }.sortedByDescending { it.lastRead },
            extensionRepositories = repositories,
            // backupState, updates, downloads, tracks and trackerAccounts remain device-owned.
        )

        try {
            projected.validate()
        } catch (invalid: Throwable) {
            throw SnapshotMaterializationException("Materialized AppSnapshot failed validation", invalid)
        }
        return SnapshotMaterializationResult(
            snapshot = projected,
            identityMap = identityMap,
            issues = issues,
            localMangaShellIds = localMangaShellIds,
            localChapterShellIds = localChapterShellIds,
            repositoryTrustConfirmations = trustConfirmations.sortedBy { it.repositoryKey },
        )
    }

    private fun SyncEntityRecord.toManga(id: Long, existing: Manga?): Manga {
        val source = long(SyncFields.Manga.SOURCE, existing?.source ?: -1)
        val url = string(SyncFields.Manga.URL, existing?.url ?: key.canonicalValue)
        val expectedKey = try {
            SyncEntityKey.manga(source.toString(), url, version = key.version)
        } catch (invalid: IllegalArgumentException) {
            throw SnapshotMaterializationException("Manga source/URL identity is invalid", invalid)
        }
        if (expectedKey.namespace != key.namespace || expectedKey.canonicalValue != key.canonicalValue) {
            throw SnapshotMaterializationException("Manga source/URL fields do not match its stable identity")
        }
        return Manga(
            id = id,
            source = source,
            favorite = boolean(SyncFields.Manga.FAVORITE, existing?.favorite ?: false),
            lastUpdate = long(SyncFields.Manga.LAST_UPDATE, existing?.lastUpdate ?: 0),
            nextUpdate = long(SyncFields.Manga.NEXT_UPDATE, existing?.nextUpdate ?: 0),
            fetchInterval = long(
                SyncFields.Manga.FETCH_INTERVAL,
                existing?.fetchInterval?.toLong() ?: 0,
            ).toIntChecked(),
            dateAdded = long(SyncFields.Manga.DATE_ADDED, existing?.dateAdded ?: 0),
            viewerFlags = long(SyncFields.Manga.VIEWER_FLAGS, existing?.viewerFlags ?: 0),
            chapterFlags = long(SyncFields.Manga.CHAPTER_FLAGS, existing?.chapterFlags ?: 0),
            coverLastModified = long(SyncFields.Manga.COVER_LAST_MODIFIED, existing?.coverLastModified ?: 0),
            url = url,
            title = string(SyncFields.Manga.TITLE, existing?.title ?: ""),
            artist = nullableString(SyncFields.Manga.ARTIST, existing?.artist),
            author = nullableString(SyncFields.Manga.AUTHOR, existing?.author),
            description = nullableString(SyncFields.Manga.DESCRIPTION, existing?.description),
            genre = nullableStringList(SyncFields.Manga.GENRE, existing?.genre),
            status = long(SyncFields.Manga.STATUS, existing?.status ?: 0),
            thumbnailUrl = nullableString(SyncFields.Manga.THUMBNAIL_URL, existing?.thumbnailUrl),
            updateStrategy = long(
                SyncFields.Manga.UPDATE_STRATEGY,
                existing?.updateStrategy?.toLong() ?: 0,
            ).toIntChecked(),
            initialized = boolean(SyncFields.Manga.INITIALIZED, existing?.initialized ?: false),
            lastModifiedAt = latestMillis(),
            favoriteModifiedAt = fields[SyncFields.Manga.FAVORITE]?.hlc?.millis,
            version = existing?.version ?: 0,
            notes = string(SyncFields.Manga.NOTES, existing?.notes ?: ""),
            excludedScanlators = stringSet(
                SyncFields.Manga.EXCLUDED_SCANLATORS,
                existing?.excludedScanlators ?: emptySet(),
            ),
        )
    }

    private fun SyncEntityRecord.toChapter(
        id: Long,
        mangaId: Long,
        parentKey: SyncEntityKey,
        existing: Chapter?,
    ): Chapter {
        val url = string(SyncFields.Chapter.URL, existing?.url ?: key.canonicalValue)
        val sourceIdentity = parentKey.namespace.removePrefix(CONTENT_SOURCE_NAMESPACE_PREFIX)
        if (sourceIdentity == parentKey.namespace || key.namespace != parentKey.namespace) {
            throw SnapshotMaterializationException("Chapter source namespace does not match its manga parent")
        }
        val expectedKey = try {
            SyncEntityKey.chapter(sourceIdentity, url, version = key.version)
        } catch (invalid: IllegalArgumentException) {
            throw SnapshotMaterializationException("Chapter URL identity is invalid", invalid)
        }
        if (expectedKey.namespace != key.namespace || expectedKey.canonicalValue != key.canonicalValue) {
            throw SnapshotMaterializationException("Chapter URL field does not match its stable identity")
        }
        return Chapter(
            id = id,
            mangaId = mangaId,
            url = url,
            name = string(SyncFields.Chapter.NAME, existing?.name ?: ""),
            scanlator = nullableString(SyncFields.Chapter.SCANLATOR, existing?.scanlator),
            read = false,
            bookmark = boolean(SyncFields.Chapter.BOOKMARK, existing?.bookmark ?: false),
            lastPageRead = 0,
            chapterNumber = double(SyncFields.Chapter.CHAPTER_NUMBER, existing?.chapterNumber ?: -1.0),
            sourceOrder = long(
                SyncFields.Chapter.SOURCE_ORDER,
                existing?.sourceOrder?.toLong() ?: 0,
            ).toIntChecked(),
            dateFetch = long(SyncFields.Chapter.DATE_FETCH, existing?.dateFetch ?: 0),
            dateUpload = long(SyncFields.Chapter.DATE_UPLOAD, existing?.dateUpload ?: 0),
            lastModifiedAt = latestMillis(),
            version = existing?.version ?: 1,
        )
    }

    private fun SyncEntityRecord.toCategory(id: Long, existing: Category?): Category = Category(
        id = id,
        name = string(SyncFields.Category.NAME, existing?.name ?: ""),
        sort = long(SyncFields.Category.SORT, existing?.sort?.toLong() ?: 0).toIntChecked(),
        flags = long(SyncFields.Category.FLAGS, existing?.flags ?: 0),
    )

    private fun SyncEntityRecord.toExtensionRepository(): ExtensionRepo {
        val baseUrl = string(SyncFields.ExtensionRepository.BASE_URL, key.canonicalValue)
        val normalized = try {
            SyncEntityKey.normalizeUrl(baseUrl, httpsOnly = true, requireAuthority = true)
        } catch (invalid: IllegalArgumentException) {
            throw SnapshotMaterializationException("Repository base URL is invalid", invalid)
        }
        if (normalized != key.canonicalValue) {
            throw SnapshotMaterializationException("Repository base URL does not match its stable identity")
        }
        return ExtensionRepo(
            baseUrl = normalized,
            name = string(SyncFields.ExtensionRepository.NAME, ""),
            shortName = nullableString(SyncFields.ExtensionRepository.SHORT_NAME, null),
            website = string(SyncFields.ExtensionRepository.WEBSITE, ""),
            signingKeyFingerprint = string(SyncFields.ExtensionRepository.SIGNING_KEY_FINGERPRINT, ""),
        )
    }

    private fun SyncEntityRecord.latestMillis(): Long = buildList {
        fields.values.forEach { add(it.hlc.millis) }
        presence?.let { add(it.hlc.millis) }
    }.maxOrNull() ?: 0

    private fun ReadingProgressState.latestMillis(): Long = buildList {
        position?.let { add(it.hlc.millis) }
        readState?.let { add(it.hlc.millis) }
        historyTouchedAt?.let { add(it.hlc.millis) }
        presence?.let { add(it.hlc.millis) }
    }.maxOrNull() ?: 0

    private fun SyncEntityRecord.value(name: String): SyncValue? = fields[name]?.value

    private fun SyncEntityRecord.string(name: String, default: String): String = when (val value = value(name)) {
        null -> default
        is SyncValue.StringValue -> value.value
        else -> invalidType(name, "string", value)
    }

    private fun SyncEntityRecord.nullableString(name: String, default: String?): String? = when (val value = value(name)) {
        null -> default
        SyncValue.NullValue -> null
        is SyncValue.StringValue -> value.value
        else -> invalidType(name, "nullable string", value)
    }

    private fun SyncEntityRecord.long(name: String, default: Long): Long = when (val value = value(name)) {
        null -> default
        is SyncValue.LongValue -> value.value
        else -> invalidType(name, "long", value)
    }

    private fun SyncEntityRecord.double(name: String, default: Double): Double = when (val value = value(name)) {
        null -> default
        is SyncValue.DoubleValue -> value.value
        else -> invalidType(name, "double", value)
    }

    private fun SyncEntityRecord.boolean(name: String, default: Boolean): Boolean = when (val value = value(name)) {
        null -> default
        is SyncValue.BooleanValue -> value.value
        else -> invalidType(name, "boolean", value)
    }

    private fun SyncEntityRecord.nullableStringList(name: String, default: List<String>?): List<String>? =
        when (val value = value(name)) {
            null -> default
            SyncValue.NullValue -> null
            is SyncValue.StringListValue -> value.value
            else -> invalidType(name, "nullable string list", value)
        }

    private fun SyncEntityRecord.stringSet(name: String, default: Set<String>): Set<String> = when (val value = value(name)) {
        null -> default
        is SyncValue.StringSetValue -> value.value
        else -> invalidType(name, "string set", value)
    }

    private fun SyncEntityRecord.entityKey(name: String): SyncEntityKey? = when (val value = value(name)) {
        null -> null
        is SyncValue.EntityKeyValue -> value.value
        else -> invalidType(name, "entity key", value)
    }

    private fun <T> SyncEntityRecord.invalidType(name: String, expected: String, actual: SyncValue): T =
        throw SnapshotMaterializationException("Field $name expected $expected but was ${actual::class.simpleName}")

    private fun Long.toIntChecked(): Int {
        if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw SnapshotMaterializationException("Integer field is out of range: $this")
        }
        return toInt()
    }
}

object PortableSettingProjector {
    fun encode(settings: AppSettings): Map<String, SyncValue> = mapOf(
        "general.languagePreference" to SyncValue.nullable(settings.general.languagePreference),
        "general.dateFormat" to SyncValue.StringValue(settings.general.dateFormat),
        "general.defaultStartingScreen" to SyncValue.StringValue(settings.general.defaultStartingScreen.name),
        "appearance.theme" to SyncValue.StringValue(settings.appearance.theme.name),
        "appearance.amoledDark" to SyncValue.BooleanValue(settings.appearance.amoledDark),
        "appearance.tintColor" to SyncValue.StringValue(settings.appearance.tintColor),
        "appearance.timestampFormat" to SyncValue.StringValue(settings.appearance.timestampFormat),
        "appearance.relativeTimestamps" to SyncValue.BooleanValue(settings.appearance.relativeTimestamps),
        "library.sort" to SyncValue.LibrarySortValue(settings.library.sort),
        "library.filter" to SyncValue.LibraryFilterValue(settings.library.filter),
        "library.categoryUpdateBehaviour" to SyncValue.StringValue(settings.library.categoryUpdateBehaviour),
        "library.globalUpdateRestrictions" to SyncValue.StringSetValue(settings.library.globalUpdateRestrictions),
        "library.autoRefreshMetadata" to SyncValue.BooleanValue(settings.library.autoRefreshMetadata),
        "reader.readingMode" to SyncValue.StringValue(settings.reader.readingMode.name),
        "reader.novelFontSizeSp" to SyncValue.DoubleValue(settings.reader.novelFontSizeSp.toDouble()),
        "reader.novelLineHeightMultiplier" to
            SyncValue.DoubleValue(settings.reader.novelLineHeightMultiplier.toDouble()),
        "reader.novelMaxWidthDp" to SyncValue.DoubleValue(settings.reader.novelMaxWidthDp.toDouble()),
        "reader.doubleTapToZoom" to SyncValue.BooleanValue(settings.reader.doubleTapToZoom),
        "reader.animatePageTransitions" to SyncValue.BooleanValue(settings.reader.animatePageTransitions),
        "reader.showPageNumber" to SyncValue.BooleanValue(settings.reader.showPageNumber),
        "reader.skipFilteredChapters" to SyncValue.BooleanValue(settings.reader.skipFilteredChapters),
        "reader.skipReadChapters" to SyncValue.BooleanValue(settings.reader.skipReadChapters),
        "reader.skipDuplicateChapters" to SyncValue.BooleanValue(settings.reader.skipDuplicateChapters),
        "reader.colorFilter" to SyncValue.ReaderColorFilterValue(settings.reader.colorFilter),
        "reader.splitTallImages" to SyncValue.BooleanValue(settings.reader.splitTallImages),
        "reader.webtoonSidePadding" to SyncValue.DoubleValue(settings.reader.webtoonSidePadding),
        "tracking.autoSyncAfterRead" to SyncValue.BooleanValue(settings.tracking.autoSyncAfterRead),
        "tracking.updateProgressAfterRead" to SyncValue.BooleanValue(settings.tracking.updateProgressAfterRead),
        "browse.checkExtensionUpdates" to SyncValue.BooleanValue(settings.browse.checkExtensionUpdates),
        "browse.showNsfwSources" to SyncValue.BooleanValue(settings.browse.showNsfwSources),
        "browse.enabledLanguages" to SyncValue.StringSetValue(settings.browse.enabledLanguages),
    )

    fun apply(
        local: AppSettings,
        registers: Map<String, LwwRegister<SyncValue>>,
    ): AppSettings {
        val unknown = registers.keys - PortableSettingsV1.allowedFields
        if (unknown.isNotEmpty()) throw SnapshotMaterializationException("Unknown portable setting fields: ${unknown.sorted()}")

        fun value(path: String): SyncValue? = registers[path]?.value
        fun string(path: String, default: String): String = when (val item = value(path)) {
            null -> default
            is SyncValue.StringValue -> item.value
            else -> badSetting(path, "string", item)
        }
        fun nullableString(path: String, default: String?): String? = when (val item = value(path)) {
            null -> default
            SyncValue.NullValue -> null
            is SyncValue.StringValue -> item.value
            else -> badSetting(path, "nullable string", item)
        }
        fun boolean(path: String, default: Boolean): Boolean = when (val item = value(path)) {
            null -> default
            is SyncValue.BooleanValue -> item.value
            else -> badSetting(path, "boolean", item)
        }
        fun double(path: String, default: Double): Double = when (val item = value(path)) {
            null -> default
            is SyncValue.DoubleValue -> item.value
            else -> badSetting(path, "double", item)
        }

        return local.copy(
            general = local.general.copy(
                languagePreference = nullableString("general.languagePreference", local.general.languagePreference),
                dateFormat = string("general.dateFormat", local.general.dateFormat),
                defaultStartingScreen = enumValue(
                    "general.defaultStartingScreen",
                    string("general.defaultStartingScreen", local.general.defaultStartingScreen.name),
                    MainSection.entries,
                ),
            ),
            appearance = local.appearance.copy(
                theme = enumValue("appearance.theme", string("appearance.theme", local.appearance.theme.name), ThemeMode.entries),
                amoledDark = boolean("appearance.amoledDark", local.appearance.amoledDark),
                tintColor = string("appearance.tintColor", local.appearance.tintColor),
                timestampFormat = string("appearance.timestampFormat", local.appearance.timestampFormat),
                relativeTimestamps = boolean("appearance.relativeTimestamps", local.appearance.relativeTimestamps),
            ),
            library = local.library.copy(
                sort = when (val item = value("library.sort")) {
                    null -> local.library.sort
                    is SyncValue.LibrarySortValue -> item.value
                    else -> badSetting("library.sort", "library sort", item)
                },
                filter = when (val item = value("library.filter")) {
                    null -> local.library.filter
                    is SyncValue.LibraryFilterValue -> item.value
                    else -> badSetting("library.filter", "library filter", item)
                },
                categoryUpdateBehaviour = string(
                    "library.categoryUpdateBehaviour",
                    local.library.categoryUpdateBehaviour,
                ),
                globalUpdateRestrictions = when (val item = value("library.globalUpdateRestrictions")) {
                    null -> local.library.globalUpdateRestrictions
                    is SyncValue.StringSetValue -> item.value
                    else -> badSetting("library.globalUpdateRestrictions", "string set", item)
                },
                autoRefreshMetadata = boolean("library.autoRefreshMetadata", local.library.autoRefreshMetadata),
            ),
            reader = local.reader.copy(
                readingMode = enumValue(
                    "reader.readingMode",
                    string("reader.readingMode", local.reader.readingMode.name),
                    ReadingMode.entries,
                ),
                novelFontSizeSp = double(
                    "reader.novelFontSizeSp",
                    local.reader.novelFontSizeSp.toDouble(),
                ).toFloat().coerceIn(12f, 36f),
                novelLineHeightMultiplier = double(
                    "reader.novelLineHeightMultiplier",
                    local.reader.novelLineHeightMultiplier.toDouble(),
                ).toFloat().coerceIn(1.15f, 2.4f),
                novelMaxWidthDp = double(
                    "reader.novelMaxWidthDp",
                    local.reader.novelMaxWidthDp.toDouble(),
                ).toFloat().coerceIn(480f, 1000f),
                doubleTapToZoom = boolean("reader.doubleTapToZoom", local.reader.doubleTapToZoom),
                animatePageTransitions = boolean(
                    "reader.animatePageTransitions",
                    local.reader.animatePageTransitions,
                ),
                showPageNumber = boolean("reader.showPageNumber", local.reader.showPageNumber),
                skipFilteredChapters = boolean("reader.skipFilteredChapters", local.reader.skipFilteredChapters),
                skipReadChapters = boolean("reader.skipReadChapters", local.reader.skipReadChapters),
                skipDuplicateChapters = boolean("reader.skipDuplicateChapters", local.reader.skipDuplicateChapters),
                colorFilter = when (val item = value("reader.colorFilter")) {
                    null -> local.reader.colorFilter
                    is SyncValue.ReaderColorFilterValue -> item.value
                    else -> badSetting("reader.colorFilter", "reader color filter", item)
                },
                splitTallImages = boolean("reader.splitTallImages", local.reader.splitTallImages),
                webtoonSidePadding = double("reader.webtoonSidePadding", local.reader.webtoonSidePadding),
            ),
            tracking = local.tracking.copy(
                autoSyncAfterRead = boolean("tracking.autoSyncAfterRead", local.tracking.autoSyncAfterRead),
                updateProgressAfterRead = boolean(
                    "tracking.updateProgressAfterRead",
                    local.tracking.updateProgressAfterRead,
                ),
            ),
            browse = local.browse.copy(
                checkExtensionUpdates = boolean("browse.checkExtensionUpdates", local.browse.checkExtensionUpdates),
                showNsfwSources = boolean("browse.showNsfwSources", local.browse.showNsfwSources),
                enabledLanguages = when (val item = value("browse.enabledLanguages")) {
                    null -> local.browse.enabledLanguages
                    is SyncValue.StringSetValue -> item.value
                    else -> badSetting("browse.enabledLanguages", "string set", item)
                },
            ),
        )
    }

    private fun <T> badSetting(path: String, expected: String, actual: SyncValue): T =
        throw SnapshotMaterializationException("Setting $path expected $expected but was ${actual::class.simpleName}")

    private fun <T : Enum<T>> enumValue(path: String, name: String, entries: List<T>): T =
        entries.firstOrNull { it.name == name }
            ?: throw SnapshotMaterializationException("Setting $path has unknown enum value $name")
}

/** Produces the explicit cascade batch required by the architecture; reducer arrival order is irrelevant. */
object CascadeMutationPlanner {
    fun deleteManga(state: SyncState, rawMangaKey: SyncEntityKey): List<SyncMutation> {
        val mangaKey = state.resolveKey(rawMangaKey)
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Manga delete requires a manga key" }
        val chapters = state.entities.values.filter { record ->
            record.key.entityType == SyncEntityType.CHAPTER &&
                (record.fields[SyncFields.Chapter.MANGA_KEY]?.value as? SyncValue.EntityKeyValue)?.value == mangaKey
        }.map { it.key }.sorted()
        return buildList {
            add(EntityPresenceSet(mangaKey, false))
            chapters.forEach { chapterKey ->
                add(EntityPresenceSet(chapterKey, false))
                add(ReadingProgressPresenceSet(chapterKey, mangaKey, false))
            }
            state.categoryMemberships.keys
                .filter { it.mangaKey == mangaKey }
                .sorted()
                .forEach { add(CategoryMembershipSet(it.mangaKey, it.categoryKey, false)) }
        }
    }

    fun deleteChapter(state: SyncState, rawChapterKey: SyncEntityKey): List<SyncMutation> {
        val chapterKey = state.resolveKey(rawChapterKey)
        val record = state.entities[chapterKey] ?: throw NoSuchElementException("Unknown chapter key")
        val mangaKey = (record.fields[SyncFields.Chapter.MANGA_KEY]?.value as? SyncValue.EntityKeyValue)?.value
            ?: throw SyncInvariantViolation("Chapter has no manga parent")
        return listOf(
            EntityPresenceSet(chapterKey, false),
            ReadingProgressPresenceSet(chapterKey, mangaKey, false),
        )
    }

    fun deleteCategory(state: SyncState, rawCategoryKey: SyncEntityKey): List<SyncMutation> {
        val categoryKey = state.resolveKey(rawCategoryKey)
        require(categoryKey != SyncEntityKey.defaultCategory()) { "Default category cannot be deleted" }
        return buildList {
            add(EntityPresenceSet(categoryKey, false))
            state.categoryMemberships.keys
                .filter { it.categoryKey == categoryKey }
                .sorted()
                .forEach { add(CategoryMembershipSet(it.mangaKey, it.categoryKey, false)) }
        }
    }
}
