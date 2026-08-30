package dev.shinsou.kmp.sync

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackerAccountState
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotReplica(
    val snapshot: AppSnapshot,
    val modifiedAt: Long,
    val deviceId: String = "",
)

@Serializable
enum class ConflictWinner {
    LOCAL,
    REMOTE,
    MERGED,
}

@Serializable
data class MergeConflict(
    val entity: String,
    val key: String,
    val winner: ConflictWinner,
    val resolution: String = "last-write-wins",
)

data class MergeSummary(
    val localRevision: Long,
    val remoteRevision: Long,
    val mergedRevision: Long,
    val conflictCount: Int,
    val remappedMangaIds: Int,
    val remappedChapterIds: Int,
)

data class SnapshotMergeResult(
    val snapshot: AppSnapshot,
    val conflicts: List<MergeConflict>,
    val summary: MergeSummary,
)

/**
 * Merges two complete replicas. Records absent on one side are retained because snapshots do not
 * yet carry deletion tombstones. Conflicting fields use deterministic LWW, except:
 * - chapter.read is logical OR;
 * - chapter.lastPageRead follows the newest matching history timestamp when available;
 * - track.lastChapterRead is max.
 */
object SnapshotConflictResolver {
    fun merge(
        local: AppSnapshot,
        remote: AppSnapshot,
        localModifiedAt: Long = local.revision,
        remoteModifiedAt: Long = remote.revision,
        localDeviceId: String = "local",
        remoteDeviceId: String = "remote",
    ): SnapshotMergeResult = merge(
        SnapshotReplica(local, localModifiedAt, localDeviceId),
        SnapshotReplica(remote, remoteModifiedAt, remoteDeviceId),
    )

    fun merge(local: SnapshotReplica, remote: SnapshotReplica): SnapshotMergeResult {
        local.snapshot.validate()
        remote.snapshot.validate()

        val aligned = alignRemoteIds(local.snapshot, remote.snapshot)
        val remoteSnapshot = aligned.snapshot
        val remoteWinsReplica = remoteWins(local, remote)
        val conflicts = mutableListOf<MergeConflict>()
        val localHistoryByChapter = local.snapshot.histories.associateBy(History::chapterId)
        val remoteHistoryByChapter = remoteSnapshot.histories.associateBy(History::chapterId)

        val mangas = mergeByKey(local.snapshot.mangas, remoteSnapshot.mangas, Manga::id) { left, right ->
            val remoteWins = mangaRemoteWins(left, right, remoteWinsReplica)
            recordConflict(conflicts, "manga", left.id, left, right, remoteWins)
            if (remoteWins) right else left
        }

        val chapters = mergeByKey(local.snapshot.chapters, remoteSnapshot.chapters, Chapter::id) { left, right ->
            val metadataRemoteWins = chapterRemoteWins(left, right, remoteWinsReplica)
            val localHistory = localHistoryByChapter[left.id]
            val remoteHistory = remoteHistoryByChapter[right.id]
            val pageRemoteWins = when {
                localHistory == null && remoteHistory == null -> metadataRemoteWins
                localHistory?.lastRead != remoteHistory?.lastRead ->
                    (remoteHistory?.lastRead ?: Long.MIN_VALUE) >
                        (localHistory?.lastRead ?: Long.MIN_VALUE)
                else -> remoteWinsReplica
            }
            val metadataWinner = if (metadataRemoteWins) right else left
            val pageWinner = if (pageRemoteWins) right else left
            val merged = metadataWinner.copy(
                read = left.read || right.read,
                // Reading position is a cursor, not a monotonic counter: returning to an earlier
                // page is a valid final position. Its history timestamp owns only this field.
                lastPageRead = pageWinner.lastPageRead,
            )
            if (left != right) {
                val special = left.read != right.read || left.lastPageRead != right.lastPageRead
                conflicts += MergeConflict(
                    entity = "chapter",
                    key = left.id.toString(),
                    winner = if (special) ConflictWinner.MERGED
                    else if (metadataRemoteWins) ConflictWinner.REMOTE else ConflictWinner.LOCAL,
                    resolution = if (special) "read=OR,lastPageRead=LWW,remaining=LWW" else "last-write-wins",
                )
            }
            merged
        }

        val categories = mergeByKey(local.snapshot.categories, remoteSnapshot.categories, Category::id) { left, right ->
            recordConflict(conflicts, "category", left.id, left, right, remoteWinsReplica)
            if (remoteWinsReplica) right else left
        }.ensureDefaultCategory()

        val histories = mergeByKey(local.snapshot.histories, remoteSnapshot.histories, History::chapterId) { left, right ->
            val remoteWins = when {
                right.lastRead != left.lastRead -> right.lastRead > left.lastRead
                else -> remoteWinsReplica
            }
            recordConflict(conflicts, "history", left.chapterId, left, right, remoteWins)
            if (remoteWins) right else left
        }
        val compatibleHistories = histories.map { history ->
            val chapter = chapters.firstOrNull { it.id == history.chapterId }
            if (history.lastPageCount == null || chapter == null || chapter.lastPageRead < history.lastPageCount) {
                history
            } else {
                // A count is only a rendition hint. Preserve the newest cursor and discard an
                // incompatible hint instead of resetting the user's reading position.
                history.copy(lastPageCount = null)
            }
        }

        val tracks = mergeByKey(local.snapshot.tracks, remoteSnapshot.tracks, { it.mangaId to it.trackerId }) { left, right ->
            val winner = if (remoteWinsReplica) right else left
            val merged = winner.copy(lastChapterRead = maxOf(left.lastChapterRead, right.lastChapterRead))
            if (left != right) {
                conflicts += MergeConflict(
                    entity = "track",
                    key = "${left.mangaId}:${left.trackerId}",
                    winner = if (left.lastChapterRead != right.lastChapterRead) ConflictWinner.MERGED
                    else if (remoteWinsReplica) ConflictWinner.REMOTE else ConflictWinner.LOCAL,
                    resolution = "lastChapterRead=max,remaining=LWW",
                )
            }
            merged
        }

        val updates = mergeByKey(local.snapshot.updates, remoteSnapshot.updates, LibraryUpdate::chapterId) { left, right ->
            val remoteWins = if (left.discoveredAt != right.discoveredAt) right.discoveredAt > left.discoveredAt else remoteWinsReplica
            recordConflict(conflicts, "update", left.chapterId, left, right, remoteWins)
            if (remoteWins) right else left
        }

        val trackerAccounts = mergeByKey(
            local.snapshot.trackerAccounts,
            remoteSnapshot.trackerAccounts,
            TrackerAccountState::trackerId,
        ) { left, right ->
            val remoteWins = when {
                left.lastSyncAt != right.lastSyncAt -> (right.lastSyncAt ?: Long.MIN_VALUE) > (left.lastSyncAt ?: Long.MIN_VALUE)
                else -> remoteWinsReplica
            }
            recordConflict(conflicts, "trackerAccount", left.trackerId, left, right, remoteWins)
            if (remoteWins) right else left
        }

        val repositories = mergeByKey(
            local.snapshot.extensionRepositories,
            remoteSnapshot.extensionRepositories,
            ExtensionRepo::baseUrl,
        ) { left, right ->
            recordConflict(conflicts, "extensionRepository", left.baseUrl, left, right, remoteWinsReplica)
            if (remoteWinsReplica) right else left
        }

        val links = mergeCategoryLinks(
            local.snapshot,
            remoteSnapshot,
            mangas.mapTo(mutableSetOf()) { it.id },
            categories.mapTo(mutableSetOf()) { it.id },
            remoteWinsReplica,
        )

        val winner = if (remoteWinsReplica) remoteSnapshot else local.snapshot
        val mergedSettings = winner.settings.copy(
            advanced = winner.settings.advanced.copy(
                // The proxy secret is backed by this device's secure store and never syncs.
                proxyApiKey = local.snapshot.settings.advanced.proxyApiKey,
            ),
        )
        val merged = winner.copy(
            schemaVersion = maxOf(local.snapshot.schemaVersion, remoteSnapshot.schemaVersion),
            revision = maxOf(local.snapshot.revision, remoteSnapshot.revision) + 1,
            settings = mergedSettings,
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = links,
            histories = compatibleHistories,
            updates = updates,
            // Queue rows and their downloaded bytes are device-local runtime state. Importing a
            // remote queue would advertise files that do not exist here and could start its work.
            downloadQueue = local.snapshot.downloadQueue,
            tracks = tracks,
            trackerAccounts = trackerAccounts,
            extensionRepositories = repositories,
        ).validate()

        return SnapshotMergeResult(
            snapshot = merged,
            conflicts = conflicts,
            summary = MergeSummary(
                localRevision = local.snapshot.revision,
                remoteRevision = remote.snapshot.revision,
                mergedRevision = merged.revision,
                conflictCount = conflicts.size,
                remappedMangaIds = aligned.remappedMangaIds,
                remappedChapterIds = aligned.remappedChapterIds,
            ),
        )
    }

    private fun remoteWins(local: SnapshotReplica, remote: SnapshotReplica): Boolean = when {
        remote.modifiedAt != local.modifiedAt -> remote.modifiedAt > local.modifiedAt
        remote.deviceId != local.deviceId -> remote.deviceId > local.deviceId
        else -> false
    }

    private fun mangaRemoteWins(local: Manga, remote: Manga, fallback: Boolean): Boolean = when {
        local.lastModifiedAt != remote.lastModifiedAt -> remote.lastModifiedAt > local.lastModifiedAt
        local.favoriteModifiedAt != remote.favoriteModifiedAt ->
            (remote.favoriteModifiedAt ?: Long.MIN_VALUE) > (local.favoriteModifiedAt ?: Long.MIN_VALUE)
        local.version != remote.version -> remote.version > local.version
        else -> fallback
    }

    private fun chapterRemoteWins(local: Chapter, remote: Chapter, fallback: Boolean): Boolean = when {
        local.lastModifiedAt != remote.lastModifiedAt -> remote.lastModifiedAt > local.lastModifiedAt
        local.version != remote.version -> remote.version > local.version
        else -> fallback
    }

    private fun mergeCategoryLinks(
        local: AppSnapshot,
        remote: AppSnapshot,
        validMangaIds: Set<Long>,
        validCategoryIds: Set<Long>,
        remoteWins: Boolean,
    ): List<MangaCategory> {
        val localMangas = local.mangas.mapTo(mutableSetOf()) { it.id }
        val remoteMangas = remote.mangas.mapTo(mutableSetOf()) { it.id }
        val localLinks = local.mangaCategories.groupBy { it.mangaId }
        val remoteLinks = remote.mangaCategories.groupBy { it.mangaId }
        return validMangaIds.flatMap { mangaId ->
            val chosen = when {
                mangaId in localMangas && mangaId in remoteMangas -> if (remoteWins) remoteLinks[mangaId].orEmpty() else localLinks[mangaId].orEmpty()
                mangaId in remoteMangas -> remoteLinks[mangaId].orEmpty()
                else -> localLinks[mangaId].orEmpty()
            }
            chosen.filter { it.categoryId in validCategoryIds }
        }.distinct()
    }

    private data class AlignedRemote(
        val snapshot: AppSnapshot,
        val remappedMangaIds: Int,
        val remappedChapterIds: Int,
    )

    /** Align stable `(source,url)` and `(manga,url)` keys before resolving numeric-id conflicts. */
    private fun alignRemoteIds(local: AppSnapshot, remote: AppSnapshot): AlignedRemote {
        val localMangaByKey = local.mangas.filter { it.url.isNotBlank() }.associateBy { it.source to it.url }
        val localMangaById = local.mangas.associateBy { it.id }
        val usedMangaIds = local.mangas.mapTo(mutableSetOf()) { it.id }
        var nextMangaId = nextPositiveId(usedMangaIds)
        val mangaIdMap = mutableMapOf<Long, Long>()
        var remappedMangas = 0
        val mangas = remote.mangas.map { manga ->
            val naturalMatch = if (manga.url.isNotBlank()) localMangaByKey[manga.source to manga.url] else null
            val idCollision = localMangaById[manga.id]
            val resolvedId = when {
                naturalMatch != null -> naturalMatch.id
                idCollision == null -> manga.id
                manga.url.isBlank() || idCollision.url.isBlank() -> manga.id
                else -> nextUnusedId(usedMangaIds, nextMangaId).also { nextMangaId = it + 1 }
            }
            usedMangaIds += resolvedId
            mangaIdMap[manga.id] = resolvedId
            if (resolvedId != manga.id) remappedMangas++
            manga.copy(id = resolvedId)
        }

        val localChapterByKey = local.chapters.filter { it.url.isNotBlank() }.associateBy { it.mangaId to it.url }
        val localChapterById = local.chapters.associateBy { it.id }
        val usedChapterIds = local.chapters.mapTo(mutableSetOf()) { it.id }
        var nextChapterId = nextPositiveId(usedChapterIds)
        val chapterIdMap = mutableMapOf<Long, Long>()
        var remappedChapters = 0
        val chapters = remote.chapters.map { original ->
            val chapter = original.copy(mangaId = mangaIdMap[original.mangaId] ?: original.mangaId)
            val naturalMatch = if (chapter.url.isNotBlank()) localChapterByKey[chapter.mangaId to chapter.url] else null
            val idCollision = localChapterById[chapter.id]
            val resolvedId = when {
                naturalMatch != null -> naturalMatch.id
                idCollision == null -> chapter.id
                chapter.url.isBlank() || idCollision.url.isBlank() -> chapter.id
                else -> nextUnusedId(usedChapterIds, nextChapterId).also { nextChapterId = it + 1 }
            }
            usedChapterIds += resolvedId
            chapterIdMap[original.id] = resolvedId
            if (resolvedId != original.id) remappedChapters++
            chapter.copy(id = resolvedId)
        }

        val localCategoriesByName = local.categories.associateBy { it.name.trim().lowercase() }
        val localCategoryIds = local.categories.mapTo(mutableSetOf()) { it.id }
        var nextCategoryId = nextPositiveId(localCategoryIds)
        val categoryIdMap = mutableMapOf<Long, Long>()
        val categories = remote.categories.map { category ->
            val resolvedId = when {
                local.categories.any { it.id == category.id } -> category.id
                localCategoriesByName[category.name.trim().lowercase()] != null ->
                    localCategoriesByName.getValue(category.name.trim().lowercase()).id
                category.id !in localCategoryIds -> category.id
                else -> nextUnusedId(localCategoryIds, nextCategoryId).also { nextCategoryId = it + 1 }
            }
            localCategoryIds += resolvedId
            categoryIdMap[category.id] = resolvedId
            category.copy(id = resolvedId)
        }

        val aligned = remote.copy(
            settings = remote.settings.copy(
                library = remote.settings.library.copy(
                    defaultCategoryId = categoryIdMap[remote.settings.library.defaultCategoryId]
                        ?: remote.settings.library.defaultCategoryId,
                ),
            ),
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = remote.mangaCategories.map {
                it.copy(
                    mangaId = mangaIdMap[it.mangaId] ?: it.mangaId,
                    categoryId = categoryIdMap[it.categoryId] ?: it.categoryId,
                )
            }.distinct(),
            histories = remote.histories.map { it.copy(chapterId = chapterIdMap[it.chapterId] ?: it.chapterId) },
            updates = remote.updates.map {
                it.copy(
                    mangaId = mangaIdMap[it.mangaId] ?: it.mangaId,
                    chapterId = chapterIdMap[it.chapterId] ?: it.chapterId,
                )
            },
            // Download queue IDs are intentionally not remapped because the queue is never
            // imported from a remote replica.
            downloadQueue = emptyList(),
            tracks = remote.tracks.map { it.copy(mangaId = mangaIdMap[it.mangaId] ?: it.mangaId) },
        ).validate()
        return AlignedRemote(aligned, remappedMangas, remappedChapters)
    }
}

private inline fun <T, K> mergeByKey(
    local: List<T>,
    remote: List<T>,
    key: (T) -> K,
    resolve: (T, T) -> T,
): List<T> {
    val result = LinkedHashMap<K, T>()
    local.forEach { result[key(it)] = it }
    remote.forEach { incoming ->
        val itemKey = key(incoming)
        val current = result[itemKey]
        result[itemKey] = if (current == null) incoming else resolve(current, incoming)
    }
    return result.values.toList()
}

private fun List<Category>.ensureDefaultCategory(): List<Category> =
    if (any { it.id == Category.Default.id }) this else listOf(Category.Default) + this

private fun <T> recordConflict(
    conflicts: MutableList<MergeConflict>,
    entity: String,
    key: Any,
    local: T,
    remote: T,
    remoteWins: Boolean,
) {
    if (local != remote) {
        conflicts += MergeConflict(
            entity = entity,
            key = key.toString(),
            winner = if (remoteWins) ConflictWinner.REMOTE else ConflictWinner.LOCAL,
        )
    }
}

private fun nextPositiveId(ids: Collection<Long>): Long = (ids.filter { it > 0 }.maxOrNull() ?: 0L) + 1L

private fun nextUnusedId(used: Set<Long>, start: Long): Long {
    var candidate = start.coerceAtLeast(1L)
    while (candidate in used) candidate++
    return candidate
}
