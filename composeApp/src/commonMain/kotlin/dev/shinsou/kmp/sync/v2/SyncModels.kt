package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.LibraryFilter
import dev.shinsou.kmp.domain.model.LibrarySort
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderColorFilter
import dev.shinsou.kmp.domain.model.ReadingMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** M6 content metadata/body references are schema/protocol v2. Readers still accept v1 envelopes. */
const val SYNC_STATE_SCHEMA_VERSION: Int = 2
const val SYNC_PROTOCOL_VERSION: Int = 2

class SyncInvariantViolation(message: String) : IllegalStateException(message)

@Serializable
data class LwwRegister<T>(
    val value: T,
    val hlc: HlcTimestamp,
) {
    fun merge(incoming: LwwRegister<T>): LwwRegister<T> = when {
        incoming.hlc > hlc -> incoming
        incoming.hlc < hlc -> this
        incoming.value == value -> this
        else -> throw SyncInvariantViolation("One HLC timestamp was reused for conflicting register values")
    }
}

internal fun <T> LwwRegister<T>?.mergeValue(value: T, hlc: HlcTimestamp): LwwRegister<T> {
    val incoming = LwwRegister(value, hlc)
    return this?.merge(incoming) ?: incoming
}

/** Typed values used by field-level registers. No platform serializer or reflective `Any` is used. */
@Serializable
sealed interface SyncValue {
    @Serializable
    @SerialName("null")
    data object NullValue : SyncValue

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : SyncValue

    @Serializable
    @SerialName("long")
    data class LongValue(val value: Long) : SyncValue

    @Serializable
    @SerialName("double")
    data class DoubleValue(val value: Double) : SyncValue {
        init {
            require(value.isFinite()) { "Non-finite doubles cannot be synchronized" }
        }
    }

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : SyncValue

    @Serializable
    @SerialName("strings")
    data class StringListValue(val value: List<String>) : SyncValue

    @Serializable
    @SerialName("string_set")
    data class StringSetValue(val value: Set<String>) : SyncValue

    @Serializable
    @SerialName("entity_key")
    data class EntityKeyValue(val value: SyncEntityKey) : SyncValue

    @Serializable
    @SerialName("library_sort")
    data class LibrarySortValue(val value: LibrarySort) : SyncValue

    @Serializable
    @SerialName("library_filter")
    data class LibraryFilterValue(val value: LibraryFilter) : SyncValue

    @Serializable
    @SerialName("reader_color_filter")
    data class ReaderColorFilterValue(val value: ReaderColorFilter) : SyncValue

    companion object {
        fun nullable(value: String?): SyncValue = value?.let(::StringValue) ?: NullValue
    }
}

/** Stable field names form part of sync schema v1. */
object SyncFields {
    object Manga {
        const val SOURCE = "source"
        const val FAVORITE = "favorite"
        const val LAST_UPDATE = "lastUpdate"
        const val NEXT_UPDATE = "nextUpdate"
        const val FETCH_INTERVAL = "fetchInterval"
        const val DATE_ADDED = "dateAdded"
        const val VIEWER_FLAGS = "viewerFlags"
        const val CHAPTER_FLAGS = "chapterFlags"
        const val COVER_LAST_MODIFIED = "coverLastModified"
        const val URL = "url"
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val AUTHOR = "author"
        const val DESCRIPTION = "description"
        const val GENRE = "genre"
        const val STATUS = "status"
        const val THUMBNAIL_URL = "thumbnailUrl"
        const val UPDATE_STRATEGY = "updateStrategy"
        const val INITIALIZED = "initialized"
        const val NOTES = "notes"
        const val EXCLUDED_SCANLATORS = "excludedScanlators"
    }

    object Chapter {
        const val MANGA_KEY = "mangaKey"
        const val URL = "url"
        const val NAME = "name"
        const val SCANLATOR = "scanlator"
        const val BOOKMARK = "bookmark"
        const val CHAPTER_NUMBER = "chapterNumber"
        const val SOURCE_ORDER = "sourceOrder"
        const val DATE_FETCH = "dateFetch"
        const val DATE_UPLOAD = "dateUpload"
    }

    object Category {
        const val NAME = "name"
        const val SORT = "sort"
        const val FLAGS = "flags"
    }

    object ExtensionRepository {
        const val BASE_URL = "baseUrl"
        const val NAME = "name"
        const val SHORT_NAME = "shortName"
        const val WEBSITE = "website"
        const val SIGNING_KEY_FINGERPRINT = "signingKeyFingerprint"
    }
}

@Serializable
data class SyncEntityRecord(
    val key: SyncEntityKey,
    val fields: Map<String, LwwRegister<SyncValue>> = emptyMap(),
    val presence: LwwRegister<Boolean>? = null,
) {
    val isPresent: Boolean get() = presence?.value == true

    fun mergeFields(patch: Map<String, SyncValue>, hlc: HlcTimestamp): SyncEntityRecord {
        if (patch.isEmpty()) return this
        val merged = fields.toMutableMap()
        patch.entries.sortedBy { it.key }.forEach { (name, value) ->
            require(name.isNotBlank()) { "Field names cannot be blank" }
            merged[name] = merged[name].mergeValue(value, hlc)
        }
        return copy(fields = merged.deterministicallySorted())
    }

    fun setPresence(value: Boolean, hlc: HlcTimestamp): SyncEntityRecord = copy(
        presence = presence.mergeValue(value, hlc),
    )

    fun merge(other: SyncEntityRecord): SyncEntityRecord {
        require(key == other.key) { "Cannot merge records with different identities" }
        var result = this
        other.fields.entries.sortedBy { it.key }.forEach { (name, register) ->
            val current = result.fields[name]
            result = result.copy(fields = result.fields + (name to (current?.merge(register) ?: register)))
        }
        other.presence?.let { incoming ->
            result = result.copy(presence = result.presence?.merge(incoming) ?: incoming)
        }
        return result.copy(fields = result.fields.deterministicallySorted())
    }
}

@Serializable
data class CategoryMembershipKey(
    val mangaKey: SyncEntityKey,
    val categoryKey: SyncEntityKey,
) : Comparable<CategoryMembershipKey> {
    init {
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Membership manga key has the wrong type" }
        require(categoryKey.entityType == SyncEntityType.CATEGORY) { "Membership category key has the wrong type" }
    }

    override fun compareTo(other: CategoryMembershipKey): Int =
        mangaKey.compareTo(other.mangaKey).takeIf { it != 0 } ?: categoryKey.compareTo(other.categoryKey)
}

@Serializable
data class ReaderPosition(
    val readingMode: ReadingMode,
    val pageIndex: Int,
    val normalizedOffsetFraction: Double = 0.0,
    val resetEpoch: Long = 0,
) {
    init {
        require(pageIndex >= 0) { "Reader page index cannot be negative" }
        require(resetEpoch >= 0) { "Reader reset epoch cannot be negative" }
        require(normalizedOffsetFraction.isFinite() && normalizedOffsetFraction in 0.0..1.0) {
            "Reader offset must be a finite value between zero and one"
        }
        if (readingMode != ReadingMode.WEBTOON && readingMode != ReadingMode.CONTINUOUS_VERTICAL) {
            require(normalizedOffsetFraction == 0.0) { "Paged reader positions must use a zero offset" }
        }
    }
}

@Serializable
data class ReadingPositionRegister(
    val position: ReaderPosition,
    val hlc: HlcTimestamp,
    val sessionId: String,
) {
    init {
        require(sessionId.isNotBlank()) { "Reader session id cannot be blank" }
    }

    fun merge(incoming: ReadingPositionRegister): ReadingPositionRegister {
        val epochOrder = incoming.position.resetEpoch.compareTo(position.resetEpoch)
        return when {
            epochOrder > 0 -> incoming
            epochOrder < 0 -> this
            incoming.hlc > hlc -> incoming
            incoming.hlc < hlc -> this
            incoming == this -> this
            else -> throw SyncInvariantViolation("One HLC timestamp was reused for conflicting reader positions")
        }
    }
}

@Serializable
data class ReadingProgressState(
    val chapterKey: SyncEntityKey,
    val mangaKey: SyncEntityKey,
    val position: ReadingPositionRegister? = null,
    val readState: LwwRegister<Boolean>? = null,
    val historyTouchedAt: LwwRegister<Long>? = null,
    val presence: LwwRegister<Boolean>? = null,
) {
    init {
        require(chapterKey.entityType == SyncEntityType.CHAPTER) { "Progress chapter key has the wrong type" }
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Progress manga key has the wrong type" }
    }

    fun merge(other: ReadingProgressState): ReadingProgressState {
        require(chapterKey == other.chapterKey && mangaKey == other.mangaKey) {
            "Cannot merge progress for different content"
        }
        return copy(
            position = when {
                position == null -> other.position
                other.position == null -> position
                else -> position.merge(other.position)
            },
            readState = mergeNullable(readState, other.readState),
            historyTouchedAt = mergeNullable(historyTouchedAt, other.historyTouchedAt),
            presence = mergeNullable(presence, other.presence),
        )
    }

    private fun <T> mergeNullable(local: LwwRegister<T>?, incoming: LwwRegister<T>?): LwwRegister<T>? = when {
        local == null -> incoming
        incoming == null -> local
        else -> local.merge(incoming)
    }
}

@Serializable
sealed interface SyncMutation

@Serializable
@SerialName("library_entry_patch")
data class LibraryEntryPatch(
    val key: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.MANGA) { "Library entry patch requires a manga key" }
    }
}

@Serializable
@SerialName("chapter_state_patch")
data class ChapterStatePatch(
    val key: SyncEntityKey,
    val mangaKey: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.CHAPTER) { "Chapter patch requires a chapter key" }
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Chapter parent requires a manga key" }
    }
}

@Serializable
@SerialName("category_patch")
data class CategoryPatch(
    val key: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.CATEGORY) { "Category patch requires a category key" }
    }
}

@Serializable
@SerialName("category_membership_set")
data class CategoryMembershipSet(
    val mangaKey: SyncEntityKey,
    val categoryKey: SyncEntityKey,
    val present: Boolean,
) : SyncMutation {
    init {
        CategoryMembershipKey(mangaKey, categoryKey)
    }
}

@Serializable
@SerialName("extension_repository_patch")
data class ExtensionRepositoryPatch(
    val key: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.EXTENSION_REPOSITORY) {
            "Extension repository patch requires a repository key"
        }
    }
}

@Serializable
@SerialName("extension_repository_presence_set")
data class ExtensionRepositoryPresenceSet(
    val key: SyncEntityKey,
    val present: Boolean,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.EXTENSION_REPOSITORY) {
            "Extension repository presence requires a repository key"
        }
    }
}

@Serializable
@SerialName("entity_presence_set")
data class EntityPresenceSet(
    val key: SyncEntityKey,
    val present: Boolean,
) : SyncMutation

@Serializable
@SerialName("reading_progress_set")
data class ReadingProgressSet(
    val chapterKey: SyncEntityKey,
    val mangaKey: SyncEntityKey,
    val position: ReaderPosition? = null,
    val readState: Boolean? = null,
    val historyTouchedAt: Long? = null,
    val sessionId: String? = null,
) : SyncMutation {
    init {
        require(chapterKey.entityType == SyncEntityType.CHAPTER) { "Progress requires a chapter key" }
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Progress requires a manga key" }
        require(position != null || readState != null || historyTouchedAt != null) { "Progress mutation is empty" }
        if (position != null) require(!sessionId.isNullOrBlank()) { "A position requires a reader session id" }
        historyTouchedAt?.let { require(it >= 0) { "History timestamp cannot be negative" } }
    }
}

@Serializable
@SerialName("reading_progress_presence_set")
data class ReadingProgressPresenceSet(
    val chapterKey: SyncEntityKey,
    val mangaKey: SyncEntityKey,
    val present: Boolean,
) : SyncMutation {
    init {
        require(chapterKey.entityType == SyncEntityType.CHAPTER) { "Progress presence requires a chapter key" }
        require(mangaKey.entityType == SyncEntityType.MANGA) { "Progress presence requires a manga key" }
    }
}

@Serializable
@SerialName("portable_setting_patch")
data class PortableSettingPatch(
    val fields: Map<String, SyncValue>,
) : SyncMutation {
    init {
        require(fields.isNotEmpty()) { "Portable setting patch is empty" }
        val forbidden = fields.keys - PortableSettingsV1.allowedFields
        require(forbidden.isEmpty()) { "Setting fields are not portable-settings-v1: ${forbidden.sorted()}" }
    }
}

@Serializable
@SerialName("entity_key_remap")
data class EntityKeyRemap(
    val oldKey: SyncEntityKey,
    val newKey: SyncEntityKey,
) : SyncMutation {
    init {
        require(oldKey.entityType == newKey.entityType) { "Cannot remap across entity types" }
        require(oldKey != newKey) { "Remap source and destination are identical" }
        require(newKey.version > oldKey.version) { "A remap must increase the entity key version" }
    }
}

@Serializable
data class SyncEvent(
    val opId: String,
    val hlc: HlcTimestamp,
    val mutations: List<SyncMutation>,
    val schemaVersion: Int = SYNC_STATE_SCHEMA_VERSION,
) {
    init {
        require(opId.isNotBlank()) { "Sync operation id cannot be blank" }
        require(mutations.isNotEmpty()) { "Sync event cannot be empty" }
        require(schemaVersion > 0) { "Sync event schema version must be positive" }
    }
}

@Serializable
data class CommittedSyncEvent(
    val workspaceSeq: Long,
    val event: SyncEvent,
) {
    init {
        require(workspaceSeq > 0) { "Workspace sequence must be positive" }
    }
}

@Serializable
data class SyncState(
    val schemaVersion: Int = SYNC_STATE_SCHEMA_VERSION,
    val keyEpoch: Int = 1,
    val throughWorkspaceSeq: Long = 0,
    val previousStableCheckpointHash: String? = null,
    val entities: Map<SyncEntityKey, SyncEntityRecord> = emptyMap(),
    val categoryMemberships: Map<CategoryMembershipKey, LwwRegister<Boolean>> = emptyMap(),
    val readingProgress: Map<SyncEntityKey, ReadingProgressState> = emptyMap(),
    val contentCategoryMemberships: Map<PublicationCategoryMembershipKeyV2, LwwRegister<Boolean>> = emptyMap(),
    val contentReadingProgress: Map<ContentProgressKeyV2, ContentReadingProgressRecordV2> = emptyMap(),
    val contentAnnotations: Map<String, SyncedAnnotationRecord> = emptyMap(),
    val blobReferences: Map<String, SyncedBlobReferenceRecord> = emptyMap(),
    val portableSettings: Map<String, LwwRegister<SyncValue>> = emptyMap(),
    val keyRemaps: Map<SyncEntityKey, SyncEntityKey> = emptyMap(),
    val appliedOpIds: Set<String> = emptySet(),
) {
    init {
        require(schemaVersion > 0) { "Sync state schema version must be positive" }
        require(keyEpoch > 0) { "Key epoch must be positive" }
        require(throughWorkspaceSeq >= 0) { "Workspace cursor cannot be negative" }
    }

    fun resolveKey(input: SyncEntityKey): SyncEntityKey {
        var current = input
        val visited = mutableSetOf<SyncEntityKey>()
        while (true) {
            if (!visited.add(current)) throw SyncInvariantViolation("Entity remap cycle detected")
            current = keyRemaps[current] ?: return current
        }
    }

    fun normalized(): SyncState = copy(
        entities = entities.deterministicallySorted(),
        categoryMemberships = categoryMemberships.deterministicallySorted(),
        readingProgress = readingProgress.deterministicallySorted(),
        contentCategoryMemberships = contentCategoryMemberships.deterministicallySorted(),
        contentReadingProgress = contentReadingProgress.deterministicallySorted(),
        contentAnnotations = contentAnnotations.deterministicallySorted(),
        blobReferences = blobReferences.deterministicallySorted(),
        portableSettings = portableSettings.deterministicallySorted(),
        keyRemaps = keyRemaps.deterministicallySorted(),
        appliedOpIds = appliedOpIds.sorted().toCollection(linkedSetOf()),
    )
}

/**
 * Returns the greatest causal timestamp retained by a checkpoint replica.
 *
 * Checkpoints intentionally compact the event tail, so their field, tombstone, membership,
 * progress and portable-setting registers are the durable remote-clock recovery boundary. A
 * client installing one must observe this value before allocating another local HLC.
 */
internal fun SyncState.maxRegisterHlc(): HlcTimestamp? {
    var maximum: HlcTimestamp? = null
    fun observe(timestamp: HlcTimestamp?) {
        if (timestamp == null) return
        val current = maximum
        if (current == null || timestamp > current) maximum = timestamp
    }

    entities.values.forEach { record ->
        record.fields.values.forEach { observe(it.hlc) }
        observe(record.presence?.hlc)
    }
    categoryMemberships.values.forEach { observe(it.hlc) }
    contentCategoryMemberships.values.forEach { observe(it.hlc) }
    contentReadingProgress.values.forEach { progress ->
        observe(progress.locator?.hlc)
        observe(progress.readState?.hlc)
        observe(progress.historyTouchedAtEpochMillis?.hlc)
        observe(progress.presence?.hlc)
    }
    contentAnnotations.values.forEach { record ->
        observe(record.annotation?.hlc)
        observe(record.presence?.hlc)
    }
    blobReferences.values.forEach { record ->
        observe(record.blob?.hlc)
        observe(record.remoteManifest?.hlc)
        record.dekEnvelopes.values.forEach { observe(it.hlc) }
        observe(record.presence?.hlc)
    }
    readingProgress.values.forEach { progress ->
        observe(progress.position?.hlc)
        observe(progress.readState?.hlc)
        observe(progress.historyTouchedAt?.hlc)
        observe(progress.presence?.hlc)
    }
    portableSettings.values.forEach { observe(it.hlc) }
    return maximum
}

internal fun <K : Comparable<K>, V> Map<K, V>.deterministicallySorted(): Map<K, V> =
    entries.sortedBy { it.key }.associateTo(linkedMapOf()) { it.key to it.value }

/** The only AppSettings paths permitted in a v1 event/checkpoint. */
object PortableSettingsV1 {
    val allowedFields: Set<String> = setOf(
        "general.languagePreference",
        "general.dateFormat",
        "general.defaultStartingScreen",
        "appearance.theme",
        "appearance.amoledDark",
        "appearance.tintColor",
        "appearance.timestampFormat",
        "appearance.relativeTimestamps",
        "library.sort",
        "library.filter",
        "library.categoryUpdateBehaviour",
        "library.globalUpdateRestrictions",
        "library.autoRefreshMetadata",
        "reader.readingMode",
        "reader.doubleTapToZoom",
        "reader.animatePageTransitions",
        "reader.showPageNumber",
        "reader.skipFilteredChapters",
        "reader.skipReadChapters",
        "reader.skipDuplicateChapters",
        "reader.colorFilter",
        "reader.splitTallImages",
        "reader.webtoonSidePadding",
        "tracking.autoSyncAfterRead",
        "tracking.updateProgressAfterRead",
        "browse.checkExtensionUpdates",
        "browse.showNsfwSources",
        "browse.enabledLanguages",
    )
}

/** Conversion helpers used by repository adapters without exposing local ids to the wire model. */
object SyncMutationFactory {
    fun libraryEntry(key: SyncEntityKey, manga: Manga, ensurePresent: Boolean = true): LibraryEntryPatch =
        LibraryEntryPatch(
            key = key,
            ensurePresent = ensurePresent,
            fields = mapOf(
                SyncFields.Manga.SOURCE to SyncValue.LongValue(manga.source),
                SyncFields.Manga.FAVORITE to SyncValue.BooleanValue(manga.favorite),
                SyncFields.Manga.LAST_UPDATE to SyncValue.LongValue(manga.lastUpdate),
                SyncFields.Manga.NEXT_UPDATE to SyncValue.LongValue(manga.nextUpdate),
                SyncFields.Manga.FETCH_INTERVAL to SyncValue.LongValue(manga.fetchInterval.toLong()),
                SyncFields.Manga.DATE_ADDED to SyncValue.LongValue(manga.dateAdded),
                SyncFields.Manga.VIEWER_FLAGS to SyncValue.LongValue(manga.viewerFlags),
                SyncFields.Manga.CHAPTER_FLAGS to SyncValue.LongValue(manga.chapterFlags),
                SyncFields.Manga.COVER_LAST_MODIFIED to SyncValue.LongValue(manga.coverLastModified),
                SyncFields.Manga.URL to SyncValue.StringValue(manga.url),
                SyncFields.Manga.TITLE to SyncValue.StringValue(manga.title),
                SyncFields.Manga.ARTIST to SyncValue.nullable(manga.artist),
                SyncFields.Manga.AUTHOR to SyncValue.nullable(manga.author),
                SyncFields.Manga.DESCRIPTION to SyncValue.nullable(manga.description),
                SyncFields.Manga.GENRE to (manga.genre?.let(SyncValue::StringListValue) ?: SyncValue.NullValue),
                SyncFields.Manga.STATUS to SyncValue.LongValue(manga.status),
                SyncFields.Manga.THUMBNAIL_URL to SyncValue.nullable(manga.thumbnailUrl),
                SyncFields.Manga.UPDATE_STRATEGY to SyncValue.LongValue(manga.updateStrategy.toLong()),
                SyncFields.Manga.INITIALIZED to SyncValue.BooleanValue(manga.initialized),
                SyncFields.Manga.NOTES to SyncValue.StringValue(manga.notes),
                SyncFields.Manga.EXCLUDED_SCANLATORS to SyncValue.StringSetValue(manga.excludedScanlators),
            ),
        )

    fun chapter(
        key: SyncEntityKey,
        mangaKey: SyncEntityKey,
        chapter: Chapter,
        ensurePresent: Boolean = true,
    ): ChapterStatePatch = ChapterStatePatch(
        key = key,
        mangaKey = mangaKey,
        ensurePresent = ensurePresent,
        fields = mapOf(
            SyncFields.Chapter.URL to SyncValue.StringValue(chapter.url),
            SyncFields.Chapter.NAME to SyncValue.StringValue(chapter.name),
            SyncFields.Chapter.SCANLATOR to SyncValue.nullable(chapter.scanlator),
            SyncFields.Chapter.BOOKMARK to SyncValue.BooleanValue(chapter.bookmark),
            SyncFields.Chapter.CHAPTER_NUMBER to SyncValue.DoubleValue(chapter.chapterNumber),
            SyncFields.Chapter.SOURCE_ORDER to SyncValue.LongValue(chapter.sourceOrder.toLong()),
            SyncFields.Chapter.DATE_FETCH to SyncValue.LongValue(chapter.dateFetch),
            SyncFields.Chapter.DATE_UPLOAD to SyncValue.LongValue(chapter.dateUpload),
        ),
    )

    fun category(key: SyncEntityKey, category: Category, ensurePresent: Boolean = true): CategoryPatch = CategoryPatch(
        key = key,
        ensurePresent = ensurePresent,
        fields = mapOf(
            SyncFields.Category.NAME to SyncValue.StringValue(category.name),
            SyncFields.Category.SORT to SyncValue.LongValue(category.sort.toLong()),
            SyncFields.Category.FLAGS to SyncValue.LongValue(category.flags),
        ),
    )

    fun extensionRepository(
        key: SyncEntityKey,
        repository: ExtensionRepo,
        ensurePresent: Boolean = true,
    ): ExtensionRepositoryPatch = ExtensionRepositoryPatch(
        key = key,
        ensurePresent = ensurePresent,
        fields = mapOf(
            SyncFields.ExtensionRepository.BASE_URL to SyncValue.StringValue(key.canonicalValue),
            SyncFields.ExtensionRepository.NAME to SyncValue.StringValue(repository.name),
            SyncFields.ExtensionRepository.SHORT_NAME to SyncValue.nullable(repository.shortName),
            SyncFields.ExtensionRepository.WEBSITE to SyncValue.StringValue(repository.website),
            SyncFields.ExtensionRepository.SIGNING_KEY_FINGERPRINT to
                SyncValue.StringValue(repository.signingKeyFingerprint),
        ),
    )
}
