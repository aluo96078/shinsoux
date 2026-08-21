package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PortableCategoryId
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackerAccountState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val APP_SNAPSHOT_SCHEMA_VERSION: Int = 1

/**
 * Portable, complete application state. It is deliberately platform-neutral so the same JSON can
 * back Android, iOS and desktop persistence and can also serve as a lossless backup payload.
 */
@Serializable
data class AppSnapshot(
    val schemaVersion: Int = APP_SNAPSHOT_SCHEMA_VERSION,
    val revision: Long = 0,
    val settings: AppSettings = AppSettings(),
    val backupState: BackupState = BackupState(),
    val mangas: List<Manga> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val categories: List<Category> = listOf(Category.Default),
    val mangaCategories: List<MangaCategory> = emptyList(),
    val histories: List<History> = emptyList(),
    val updates: List<LibraryUpdate> = emptyList(),
    val downloadQueue: List<DownloadQueueItem> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val trackerAccounts: List<TrackerAccountState> = emptyList(),
    val extensionRepositories: List<ExtensionRepo> = emptyList(),
    /**
     * Local durability receipts for one-shot projections derived from typed content authority.
     * They live in the same atomic snapshot as the projected settings, so a process death can
     * lose both (safe replay) or neither (preserve subsequent user edits).
     */
    val contentAuthorityProjectionMarkers: Set<String> = emptySet(),
    /** Stable portable-category to legacy numeric-id bindings owned by the ShuYue projection. */
    val shuyueCategoryProjectionBindings: Map<String, Long> = emptyMap(),
) {
    fun validate(): AppSnapshot {
        if (schemaVersion !in 1..APP_SNAPSHOT_SCHEMA_VERSION) {
            throw SnapshotValidationException("Unsupported snapshot schema version: $schemaVersion")
        }
        ensure(revision >= 0L && revision < Long.MAX_VALUE - 1L) {
            "Snapshot revision is outside the supported range"
        }

        // Reuse these uniqueness indexes for relationship validation below. Building a temporary
        // set first and then an identical lookup set/map doubled allocation on every large-state
        // persistence pass.
        val mangaIds = uniqueKeys("manga id", mangas) { it.id }
        val chapterById = uniqueMap("chapter id", chapters) { it.id }
        val categoryIds = uniqueKeys("category id", categories) { it.id }
        requireUnique("history id", histories) { it.id }
        requireUnique("download id", downloadQueue) { it.id }
        requireUnique("track id", tracks) { it.id }
        requireUnique("tracker account", trackerAccounts) { it.trackerId }
        requireUnique("extension repository URL", extensionRepositories) { it.baseUrl }
        requireUnique("manga/category link", mangaCategories) { it }
        requireUnique("update chapter", updates) { it.chapterId }
        requireUnique("manga/tracker link", tracks) { it.mangaId to it.trackerId }
        ensure(contentAuthorityProjectionMarkers.size <= MAX_CONTENT_PROJECTION_MARKERS) {
            "Too many content authority projection markers"
        }
        contentAuthorityProjectionMarkers.forEach { marker ->
            ensure(marker.isNotBlank() && marker.length <= MAX_CONTENT_PROJECTION_MARKER_LENGTH) {
                "Content authority projection marker is invalid"
            }
            ensure(marker.none(Char::isISOControl)) {
                "Content authority projection marker contains control characters"
            }
        }
        ensure(shuyueCategoryProjectionBindings.size <= MAX_CONTENT_PROJECTION_MARKERS) {
            "Too many ShuYue category projection bindings"
        }
        shuyueCategoryProjectionBindings.forEach { (portableId, legacyId) ->
            ensure(
                PublicationKey.isPortableUuid(portableId) &&
                    (if (portableId == PortableCategoryId.DEFAULT.value) legacyId == 0L else legacyId > 0L),
            ) {
                "ShuYue category projection binding is invalid"
            }
        }

        chapters.forEach { chapter ->
            ensure(chapter.mangaId in mangaIds) { "Chapter ${chapter.id} refers to missing manga ${chapter.mangaId}" }
            ensure(chapter.lastPageRead >= 0) { "Chapter ${chapter.id} has a negative page index" }
        }
        mangaCategories.forEach { link ->
            ensure(link.mangaId in mangaIds) { "Category link refers to missing manga ${link.mangaId}" }
            ensure(link.categoryId in categoryIds) { "Category link refers to missing category ${link.categoryId}" }
        }
        histories.forEach { history ->
            ensure(history.chapterId in chapterById) { "History ${history.id} refers to missing chapter ${history.chapterId}" }
            ensure(history.timeRead >= 0) { "History ${history.id} has negative reading time" }
        }
        updates.forEach { update ->
            val chapter = chapterById[update.chapterId]
            ensure(chapter != null) { "Update refers to missing chapter ${update.chapterId}" }
            ensure(chapter?.mangaId == update.mangaId) { "Update manga/chapter relationship does not match" }
        }
        downloadQueue.forEach { download ->
            val chapter = chapterById[download.chapterId]
            ensure(download.mangaId in mangaIds) { "Download ${download.id} refers to missing manga ${download.mangaId}" }
            ensure(chapter != null) { "Download ${download.id} refers to missing chapter ${download.chapterId}" }
            ensure(chapter?.mangaId == download.mangaId) { "Download ${download.id} manga/chapter relationship does not match" }
            ensure(download.progress in 0.0..1.0) { "Download ${download.id} progress must be between 0 and 1" }
            ensure(download.downloadedPages >= 0 && download.totalPages >= 0) { "Download ${download.id} has invalid page counts" }
            ensure(download.downloadedPages <= download.totalPages || download.totalPages == 0) {
                "Download ${download.id} has more downloaded pages than total pages"
            }
        }
        tracks.forEach { track ->
            ensure(track.mangaId in mangaIds) { "Track ${track.id} refers to missing manga ${track.mangaId}" }
        }

        ensure(settings.library.portraitColumns > 0) { "Portrait library columns must be positive" }
        ensure(settings.library.landscapeColumns > 0) { "Landscape library columns must be positive" }
        ensure(settings.downloads.parallelDownloads > 0) { "Parallel downloads must be positive" }
        ensure(settings.downloads.parallelPages > 0) { "Parallel page downloads must be positive" }
        ensure(backupState.intervalHours > 0) { "Backup interval must be positive" }
        ensure(backupState.retainedBackupCount >= 0) { "Retained backup count cannot be negative" }
        return this
    }

    internal fun withRequiredDefaults(): AppSnapshot = if (categories.any { it.id == Category.Default.id }) {
        this
    } else {
        copy(categories = listOf(Category.Default) + categories)
    }
}

private const val MAX_CONTENT_PROJECTION_MARKERS: Int = 10_000
private const val MAX_CONTENT_PROJECTION_MARKER_LENGTH: Int = 256

class SnapshotValidationException(message: String) : IllegalArgumentException(message)

object AppSnapshotJson {
    val format: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    // App state is rewritten much more frequently than an exported backup. Keep the durable state
    // payload compact while retaining the human-readable [format] for backup envelopes.
    private val persistenceFormat: Json = Json(format) {
        prettyPrint = false
    }

    fun encode(snapshot: AppSnapshot): String = persistenceFormat.encodeToString(snapshot.withoutPortableSecrets())

    fun decode(encoded: String): AppSnapshot = persistenceFormat.decodeFromString<AppSnapshot>(encoded)
        .withRequiredDefaults()
        .validate()
}

/** Values that must remain device-local even though the UI keeps them in its in-memory settings. */
internal fun AppSnapshot.withoutPortableSecrets(): AppSnapshot =
    if (settings.advanced.proxyApiKey.isEmpty()) this
    else copy(
        settings = settings.copy(
            advanced = settings.advanced.copy(proxyApiKey = ""),
        ),
    )

private fun ensure(condition: Boolean, message: () -> String) {
    if (!condition) throw SnapshotValidationException(message())
}

private inline fun <T, K> requireUnique(label: String, values: Collection<T>, key: (T) -> K) {
    val seen = HashSet<K>(hashCapacity(values.size))
    values.forEach { value ->
        if (!seen.add(key(value))) throw SnapshotValidationException("Duplicate $label in snapshot")
    }
}

private inline fun <T, K> uniqueKeys(label: String, values: Collection<T>, key: (T) -> K): Set<K> {
    val seen = HashSet<K>(hashCapacity(values.size))
    values.forEach { value ->
        if (!seen.add(key(value))) throw SnapshotValidationException("Duplicate $label in snapshot")
    }
    return seen
}

private inline fun <T, K> uniqueMap(label: String, values: Collection<T>, key: (T) -> K): Map<K, T> {
    val indexed = HashMap<K, T>(hashCapacity(values.size))
    values.forEach { value ->
        val itemKey = key(value)
        if (indexed.containsKey(itemKey)) throw SnapshotValidationException("Duplicate $label in snapshot")
        indexed[itemKey] = value
    }
    return indexed
}

private fun hashCapacity(size: Int): Int = when {
    size < 3 -> size + 1
    size < Int.MAX_VALUE / 4 -> size * 4 / 3 + 1
    else -> Int.MAX_VALUE
}
