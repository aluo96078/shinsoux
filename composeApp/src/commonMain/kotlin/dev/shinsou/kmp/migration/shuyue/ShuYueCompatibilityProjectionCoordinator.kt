package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentPortableAuxiliaryState
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.PortableCategoryId
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.local.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Counts only rows/fields actually inserted by one successful compatibility CAS. */
internal data class ShuYueCompatibilityProjectionRepairResult(
    val mangasCreated: Int = 0,
    val chaptersCreated: Int = 0,
    val categoriesCreated: Int = 0,
    val membershipsCreated: Int = 0,
    val historiesCreated: Int = 0,
    val settingsFieldsApplied: Int = 0,
    val projectionMarkersCreated: Int = 0,
    val categoryBindingsCreated: Int = 0,
) {
    val changed: Boolean
        get() = mangasCreated + chaptersCreated + categoriesCreated + membershipsCreated +
            historiesCreated + settingsFieldsApplied + projectionMarkersCreated +
            categoryBindingsCreated > 0
}

internal class ShuYueCompatibilityProjectionConflictException(
    portableCategoryId: String,
    legacyCategoryId: Long,
) : IllegalStateException(
    "ShuYue category $portableCategoryId cannot claim occupied legacy id $legacyCategoryId",
)

/**
 * Rebuilds the body-free Manga/Chapter view after ShuYue's typed transaction is durable.
 *
 * The same entry point is used immediately after import and during startup. A typed URL is the
 * compatibility identity, so an existing row is never rewritten: favorite state, category
 * removal/rename, reader progress and every other user edit win over a later repair. Missing rows
 * are published together through the repository's observer-free content-authority CAS.
 */
internal class ShuYueCompatibilityProjectionCoordinator(
    private val repository: ShinsouRepository,
    private val publications: () -> List<Publication>,
    private val auxiliaryState: () -> ContentPortableAuxiliaryState,
) {
    constructor(
        repository: ShinsouRepository,
        foundation: ContentFoundationRuntime,
    ) : this(
        repository = repository,
        publications = foundation.publications::all,
        auxiliaryState = foundation::portableAuxiliaryState,
    )

    suspend fun repair(): ShuYueCompatibilityProjectionRepairResult {
        val authority = parseAuthority(publications(), auxiliaryState().validate())
        if (authority.publications.isEmpty() && authority.settings.isEmpty()) {
            return ShuYueCompatibilityProjectionRepairResult()
        }

        repeat(MAX_CAS_ATTEMPTS) {
            val current = repository.currentSnapshot
            val projected = materialize(current, authority)
            if (!projected.result.changed) return projected.result
            val committed = repository.materializeContentAuthorityProjectionIfRevision(
                expectedRevision = current.revision,
                requested = projected.snapshot,
            )
            if (committed != null) return projected.result
        }
        throw IllegalStateException("ShuYue compatibility projection remained concurrently busy")
    }
}

private data class ShuYueProjectionAuthority(
    val publications: List<Publication>,
    val categories: Map<String, ShuYueImportedCategory>,
    val membershipsByPublication: Map<String, List<ShuYueImportedCategoryMembership>>,
    val progressByUnit: Map<String, ShuYueImportedReadingProgress>,
    val settings: List<OneShotSettingsProjection>,
)

private data class OneShotSettingsProjection(
    val marker: String,
    val settings: ShuYueImportedReaderSettings,
)

private data class MaterializedProjection(
    val snapshot: AppSnapshot,
    val result: ShuYueCompatibilityProjectionRepairResult,
)

private data class UnitProjection(
    val acquisitionId: String,
    val acquiredAtEpochMillis: Long,
    val unit: PublicationUnit,
)

private fun parseAuthority(
    publications: List<Publication>,
    auxiliary: ContentPortableAuxiliaryState,
): ShuYueProjectionAuthority {
    val hasShuYueLedger = auxiliary.migrations.any { it.namespace == SHUYUE_MIGRATION_LEDGER_NAMESPACE }
    if (!hasShuYueLedger) {
        return ShuYueProjectionAuthority(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
    }

    val publicationIds = auxiliary.aliases.asSequence()
        .filter { it.alias.startsWith(SHUYUE_BOOK_ALIAS_PREFIX) }
        .mapNotNull { alias -> alias.target.takeIf { PublicationKey.isPortableUuid(it) } }
        .toSet()
    val selectedPublications = publications.asSequence()
        .filter { it.key.value in publicationIds }
        .onEach { it.validate() }
        .sortedBy { it.key.value }
        .toList()
    val selectedIds = selectedPublications.mapTo(hashSetOf()) { it.key.value }
    val selectedUnitIds = selectedPublications.asSequence()
        .flatMap { it.units.asSequence() }
        .mapTo(hashSetOf()) { it.key.value }

    val categories = auxiliary.metadata.asSequence()
        .filter { it.key.startsWith(SHUYUE_CATEGORY_METADATA_PREFIX) }
        .mapNotNull { row ->
            decodeMetadata<ShuYueImportedCategory>(row.value)?.takeIf { category ->
                row.key == "$SHUYUE_CATEGORY_METADATA_PREFIX${category.categoryId}"
            }
        }
        .associateBy(ShuYueImportedCategory::categoryId)

    val memberships = auxiliary.metadata.asSequence()
        .filter { it.key.startsWith(SHUYUE_CATEGORY_MEMBERSHIP_METADATA_PREFIX) }
        .mapNotNull { row ->
            decodeMetadata<ShuYueImportedCategoryMembership>(row.value)?.takeIf { membership ->
                row.key == shuyueCategoryMembershipMetadataKey(membership) &&
                    membership.publicationId in selectedIds && membership.categoryId in categories
            }
        }
        .distinct()
        .groupBy(ShuYueImportedCategoryMembership::publicationId)

    val progress = auxiliary.metadata.asSequence()
        .filter { it.key.startsWith(SHUYUE_PROGRESS_METADATA_PREFIX) }
        .mapNotNull { row ->
            decodeMetadata<ShuYueImportedReadingProgress>(row.value)?.takeIf { item ->
                val scope = item.locator.scope
                row.key == "$SHUYUE_PROGRESS_METADATA_PREFIX${scope.unitId.value}" &&
                    scope.publicationId.value in selectedIds && scope.unitId.value in selectedUnitIds
            }
        }
        .associateBy { it.locator.scope.unitId.value }

    val migrationDigests = auxiliary.migrations.asSequence()
        .filter { it.namespace == SHUYUE_MIGRATION_LEDGER_NAMESPACE }
        .mapTo(hashSetOf()) { it.sourceDigestSha256 }
    val settings = auxiliary.metadata.asSequence()
        .filter { it.key.startsWith(SHUYUE_READER_SETTINGS_METADATA_PREFIX) }
        .sortedBy { it.key }
        .mapNotNull { row ->
            val digest = row.key.removePrefix(SHUYUE_READER_SETTINGS_METADATA_PREFIX)
            decodeMetadata<ShuYueImportedReaderSettings>(row.value)?.takeIf {
                digest in migrationDigests
            }?.let { OneShotSettingsProjection(row.key, it) }
        }
        .toList()

    return ShuYueProjectionAuthority(
        publications = selectedPublications,
        categories = categories,
        membershipsByPublication = memberships,
        progressByUnit = progress,
        settings = settings,
    )
}

private inline fun <reified T> decodeMetadata(value: String): T? =
    runCatching { SHUYUE_PROJECTION_JSON.decodeFromString<T>(value) }.getOrNull()

private fun materialize(
    current: AppSnapshot,
    authority: ShuYueProjectionAuthority,
): MaterializedProjection {
    val mangas = current.mangas.toMutableList()
    val chapters = current.chapters.toMutableList()
    val categories = current.categories.toMutableList()
    val memberships = current.mangaCategories.toMutableList()
    val histories = current.histories.toMutableList()
    val projectedMangaIds = mutableMapOf<String, Long>()
    val newlyCreatedPublicationIds = linkedSetOf<String>()

    var nextMangaId = nextLegacyId(mangas.map(Manga::id))
    var nextChapterId = nextLegacyId(chapters.map(Chapter::id))
    var nextHistoryId = nextLegacyId(histories.map(History::id))
    var mangasCreated = 0
    var chaptersCreated = 0
    var categoriesCreated = 0
    var membershipsCreated = 0
    var historiesCreated = 0
    var categoryBindingsCreated = 0

    authority.publications.forEach { publication ->
        val existing = findProjectedManga(current.copy(mangas = mangas, chapters = chapters), publication.key)
        val manga = existing ?: publication.toLegacyManga(nextMangaId++).also {
            mangas += it
            mangasCreated++
            newlyCreatedPublicationIds += publication.key.value
        }
        projectedMangaIds[publication.key.value] = manga.id

        publication.unitProjections().forEachIndexed { index, projectedUnit ->
            val chapterUrl = encodeTypedLocalChapterUrl(
                publication.key,
                projectedUnit.acquisitionId,
                projectedUnit.unit.key,
            )
            if (chapters.any { it.mangaId == manga.id && it.url == chapterUrl }) return@forEachIndexed

            val importedProgress = authority.progressByUnit[projectedUnit.unit.key.value]
            val chapter = projectedUnit.toLegacyChapter(
                id = nextChapterId++,
                mangaId = manga.id,
                fallbackOrder = index,
                progress = importedProgress,
            )
            chapters += chapter
            chaptersCreated++
            if (importedProgress?.hasMeaningfulProgress() == true) {
                histories += History(
                    id = nextHistoryId++,
                    chapterId = chapter.id,
                    lastRead = importedProgress.updatedAtEpochMillis,
                    timeRead = 0,
                )
                historiesCreated++
            }
        }
    }

    val categoryBindings = current.shuyueCategoryProjectionBindings.toMutableMap()
    val categoryIdsByPortableId = mutableMapOf(PortableCategoryId.DEFAULT.value to Category.Default.id)
    newlyCreatedPublicationIds.forEach { publicationId ->
        val mangaId = requireNotNull(projectedMangaIds[publicationId])
        val portableMemberships = authority.membershipsByPublication[publicationId].orEmpty()
        val resolvedCategoryIds = portableMemberships.mapNotNullTo(linkedSetOf()) { membership ->
            val imported = authority.categories[membership.categoryId] ?: return@mapNotNullTo null
            categoryIdsByPortableId[imported.categoryId] ?: run {
                val boundId = categoryBindings[imported.categoryId]
                val boundCategory = boundId?.let { id -> categories.firstOrNull { it.id == id } }
                if (boundId != null && boundCategory == null) return@mapNotNullTo null
                val category = boundCategory ?: run {
                    val stableId = shuyueLegacyCategoryId(imported.categoryId)
                    if (categories.any { it.id == stableId }) {
                        throw ShuYueCompatibilityProjectionConflictException(imported.categoryId, stableId)
                    }
                    categories.firstOrNull {
                        it.name.trim().equals(imported.name.trim(), ignoreCase = true)
                    } ?: Category(
                        id = stableId,
                        name = imported.name.trim().ifBlank { "Imported" },
                        sort = nextCategorySort(categories),
                    ).also {
                        categories += it
                        categoriesCreated++
                    }
                }
                if (imported.categoryId !in categoryBindings) {
                    categoryBindings[imported.categoryId] = category.id
                    categoryBindingsCreated++
                }
                categoryIdsByPortableId[imported.categoryId] = category.id
                category.id
            }
        }.ifEmpty { linkedSetOf(Category.Default.id) }
        resolvedCategoryIds.forEach { categoryId ->
            val link = MangaCategory(mangaId, categoryId)
            if (link !in memberships) {
                memberships += link
                membershipsCreated++
            }
        }
    }

    var projectedSettings = current.settings
    val projectionMarkers = current.contentAuthorityProjectionMarkers.toMutableSet()
    var settingsFieldsApplied = 0
    var projectionMarkersCreated = 0
    authority.settings.forEach { pending ->
        if (pending.marker !in projectionMarkers) {
            val next = applyImportedSettings(projectedSettings, pending.settings)
            settingsFieldsApplied += settingsDifferenceCount(projectedSettings, next)
            projectedSettings = next
            projectionMarkers += pending.marker
            projectionMarkersCreated++
        }
    }
    val result = ShuYueCompatibilityProjectionRepairResult(
        mangasCreated = mangasCreated,
        chaptersCreated = chaptersCreated,
        categoriesCreated = categoriesCreated,
        membershipsCreated = membershipsCreated,
        historiesCreated = historiesCreated,
        settingsFieldsApplied = settingsFieldsApplied,
        projectionMarkersCreated = projectionMarkersCreated,
        categoryBindingsCreated = categoryBindingsCreated,
    )
    return MaterializedProjection(
        snapshot = current.copy(
            settings = projectedSettings,
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = memberships,
            histories = histories,
            contentAuthorityProjectionMarkers = projectionMarkers,
            shuyueCategoryProjectionBindings = categoryBindings,
        ),
        result = result,
    )
}

private fun findProjectedManga(snapshot: AppSnapshot, publicationKey: PublicationKey): Manga? {
    val canonicalUrl = encodeTypedLocalPublicationUrl(publicationKey)
    snapshot.mangas.firstOrNull { it.source == LOCAL_SOURCE_ID && it.url == canonicalUrl }?.let { return it }
    val typedUnitPrefix = "$canonicalUrl/"
    val oldProjectionMangaIds = snapshot.chapters.asSequence()
        .filter { it.url.startsWith(typedUnitPrefix) }
        .map(Chapter::mangaId)
        .toSet()
    return snapshot.mangas.firstOrNull { it.source == LOCAL_SOURCE_ID && it.id in oldProjectionMangaIds }
}

private fun Publication.toLegacyManga(id: Long): Manga {
    val timestamp = acquisitions.mapNotNull { it.acquiredAtEpochMillis }.minOrNull() ?: 0L
    return Manga(
        id = id,
        source = LOCAL_SOURCE_ID,
        favorite = true,
        dateAdded = timestamp,
        url = encodeTypedLocalPublicationUrl(key),
        title = title.ifBlank { "Imported ShuYue publication" },
        author = authors.firstOrNull(),
        description = description,
        genre = listOf("ShuYue"),
        updateStrategy = 1,
        initialized = true,
        lastModifiedAt = timestamp,
        favoriteModifiedAt = timestamp,
        version = 1,
    )
}

private fun Publication.unitProjections(): List<UnitProjection> = acquisitions.asSequence()
    .flatMap { acquisition ->
        acquisition.units.asSequence().map { unit ->
            UnitProjection(
                acquisitionId = acquisition.id,
                acquiredAtEpochMillis = acquisition.acquiredAtEpochMillis ?: 0L,
                unit = unit,
            )
        }
    }
    .sortedWith(
        compareBy<UnitProjection> { it.unit.ordinal ?: Int.MAX_VALUE }
            .thenBy(UnitProjection::acquisitionId)
            .thenBy { it.unit.key.value },
    )
    .toList()

private fun UnitProjection.toLegacyChapter(
    id: Long,
    mangaId: Long,
    fallbackOrder: Int,
    progress: ShuYueImportedReadingProgress?,
): Chapter {
    val order = unit.ordinal ?: fallbackOrder
    val timestamp = unit.publishedAtEpochMillis ?: acquiredAtEpochMillis
    val pageIndex = progress?.let { imported ->
        val blockId = imported.locator.blockId
        unit.latestManifest?.representations.orEmpty().asSequence()
            .filterIsInstance<dev.shinsou.kmp.content.ContentRepresentation.PlainText>()
            .flatMap { it.blocks.asSequence() }
            .indexOfFirst { it.blockId == blockId }
            .coerceAtLeast(0)
    } ?: 0
    return Chapter(
        id = id,
        mangaId = mangaId,
        url = encodeTypedLocalChapterUrl(unit.key.publicationKey, acquisitionId, unit.key),
        name = unit.title.ifBlank { "Chapter ${order + 1}" },
        read = progress?.locator?.progression?.let { it >= 1.0 } == true,
        lastPageRead = pageIndex,
        chapterNumber = (order + 1).toDouble(),
        sourceOrder = order,
        dateFetch = timestamp,
        dateUpload = unit.publishedAtEpochMillis ?: timestamp,
        lastModifiedAt = progress?.updatedAtEpochMillis?.takeIf { it > 0 } ?: timestamp,
        version = 1,
    )
}

private fun ShuYueImportedReadingProgress.hasMeaningfulProgress(): Boolean =
    updatedAtEpochMillis > 0 || locator.offset > 0 || (locator.progression ?: 0.0) > 0.0

internal fun shuyueLegacyCategoryId(portableId: String): Long {
    if (portableId == PortableCategoryId.DEFAULT.value) return Category.Default.id
    val lowBits = Sha256.hex(portableId.encodeToByteArray()).take(15).fold(0L) { value, char ->
        (value shl 4) or char.digitToInt(16).toLong()
    }
    return LEGACY_CATEGORY_ID_PREFIX or lowBits
}

private fun nextCategorySort(categories: List<Category>): Int {
    val maximum = categories.maxOfOrNull(Category::sort) ?: -1
    return if (maximum == Int.MAX_VALUE) maximum else maximum + 1
}

private fun nextLegacyId(ids: List<Long>): Long {
    val maximum = ids.filter { it > 0 }.maxOrNull() ?: 0L
    check(maximum < Long.MAX_VALUE) { "Legacy compatibility identity space is exhausted" }
    return maximum + 1
}

private fun applyImportedSettings(
    current: AppSettings,
    imported: ShuYueImportedReaderSettings,
): AppSettings {
    val language = when (imported.language) {
        "SYSTEM" -> null
        "ENGLISH" -> "en"
        "TRADITIONAL_CHINESE" -> "zh-Hant"
        "SIMPLIFIED_CHINESE" -> "zh-Hans"
        "JAPANESE" -> "ja"
        else -> current.general.languagePreference
    }
    val importedTheme = when (imported.theme) {
        "SYSTEM" -> dev.shinsou.kmp.domain.model.ThemeMode.SYSTEM
        "LIGHT", "PAPER" -> dev.shinsou.kmp.domain.model.ThemeMode.LIGHT
        "DARK", "OLED" -> dev.shinsou.kmp.domain.model.ThemeMode.DARK
        else -> null
    }
    val importedTint = imported.accentColor.lowercase().takeIf { it in SHINSOU_ACCENT_COLORS }
        ?: current.appearance.tintColor

    val general = current.general.copy(
        languagePreference = language,
    )
    val appearance = current.appearance.copy(
        theme = importedTheme ?: current.appearance.theme,
        amoledDark = if (importedTheme == null) current.appearance.amoledDark else imported.theme == "OLED",
        tintColor = importedTint,
    )
    val reader = current.reader.copy(
        keepScreenOn = imported.keepScreenOn,
        volumeKeys = imported.volumeKeysEnabled,
    )
    val sync = current.sync.copy(
        syncOnForeground = imported.syncOnLaunch,
    )
    val browse = current.browse.copy(
        showNsfwSources = imported.showNsfwSources,
    )
    val security = current.security.copy(
        appLockEnabled = imported.appLockEnabled,
        secureScreen = imported.secureScreen,
        incognitoMode = imported.incognitoMode,
    )
    return current.copy(
        general = general,
        appearance = appearance,
        reader = reader,
        sync = sync,
        browse = browse,
        security = security,
    )
}

private fun settingsDifferenceCount(previous: AppSettings, next: AppSettings): Int = listOf(
    previous.general.languagePreference != next.general.languagePreference,
    previous.appearance.theme != next.appearance.theme,
    previous.appearance.amoledDark != next.appearance.amoledDark,
    previous.appearance.tintColor != next.appearance.tintColor,
    previous.reader.keepScreenOn != next.reader.keepScreenOn,
    previous.reader.volumeKeys != next.reader.volumeKeys,
    previous.sync.syncOnForeground != next.sync.syncOnForeground,
    previous.browse.showNsfwSources != next.browse.showNsfwSources,
    previous.security.appLockEnabled != next.security.appLockEnabled,
    previous.security.secureScreen != next.security.secureScreen,
    previous.security.incognitoMode != next.security.incognitoMode,
).count { it }

private val SHUYUE_PROJECTION_JSON = Json { ignoreUnknownKeys = true }
private val SHINSOU_ACCENT_COLORS = setOf(
    "blue",
    "indigo",
    "purple",
    "pink",
    "red",
    "orange",
    "yellow",
    "green",
    "teal",
    "cyan",
)
private const val SHUYUE_MIGRATION_LEDGER_NAMESPACE: String = "shuyue.backup.v1"
private const val SHUYUE_BOOK_ALIAS_PREFIX: String = "shuyue-v1-book:"
private const val SHUYUE_CATEGORY_METADATA_PREFIX: String = "migration.shuyue.category."
private const val SHUYUE_PROGRESS_METADATA_PREFIX: String = "migration.shuyue.progress."
private const val SHUYUE_READER_SETTINGS_METADATA_PREFIX: String =
    "migration.shuyue.reader-settings."
private const val LEGACY_CATEGORY_ID_PREFIX: Long = 0x6000_0000_0000_0000L
private const val MAX_CAS_ATTEMPTS: Int = 8
