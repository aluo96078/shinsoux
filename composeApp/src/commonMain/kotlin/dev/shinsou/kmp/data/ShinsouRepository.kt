package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.HistoryItem
import dev.shinsou.kmp.domain.model.LibraryItem
import dev.shinsou.kmp.domain.model.LibraryManga
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackerAccountState
import dev.shinsou.kmp.domain.model.UpdateItem
import dev.shinsou.kmp.domain.model.applying
import dev.shinsou.kmp.domain.model.normalizeMangaCategorySelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Shared state repository used by every platform.
 *
 * Mutations commit to [snapshot] immediately. When [persist] is present, a single background
 * writer validates and encodes the latest state after a short coalescing window. Intermediate
 * states are deliberately coalesced, while [flushPersistence] provides an explicit durability
 * boundary for lifecycle transitions and orderly shutdown.
 */
class ShinsouRepository(
    initial: AppSnapshot = AppSnapshot(),
    persist: ((String) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(initial.withRequiredDefaults().validate())
    private var persistenceClosed = false
    private val persistence = persist?.let {
        SnapshotPersistenceWorker(
            initialRevision = mutableSnapshot.value.revision,
            persist = it,
        )
    }

    val snapshot: StateFlow<AppSnapshot> = mutableSnapshot.asStateFlow()
    val currentSnapshot: AppSnapshot get() = snapshot.value

    /** Most recent asynchronous persistence error. A successful write clears it. */
    val persistenceFailure: StateFlow<Throwable?>
        get() = persistence?.failure ?: NO_PERSISTENCE_FAILURE

    fun exportSnapshot(): String = AppSnapshotJson.encode(snapshot.value)

    suspend fun importSnapshot(encoded: String): AppSnapshot = replaceSnapshot(decodeSnapshot(encoded))

    suspend fun replaceSnapshot(imported: AppSnapshot): AppSnapshot = mutex.withLock {
        ensurePersistenceOpen()
        val next = imported.withRequiredDefaults()
            .copy(revision = nextRevision(mutableSnapshot.value.revision, imported.revision))
            .validate()
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        next
    }

    /** Optimistic replacement used by sync so a concurrent local edit is never overwritten. */
    suspend fun replaceSnapshotIfRevision(expectedRevision: Long, imported: AppSnapshot): AppSnapshot? = mutex.withLock {
        ensurePersistenceOpen()
        val current = mutableSnapshot.value
        if (current.revision != expectedRevision) return@withLock null
        val next = imported.withRequiredDefaults()
            .copy(revision = nextRevision(current.revision, imported.revision))
            .validate()
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        next
    }

    suspend fun reset(): AppSnapshot = replaceSnapshot(AppSnapshot())

    /**
     * Persist the latest state before returning. This bypasses the debounce and propagates a disk
     * failure to the caller. Mutations that begin after this method captures the state belong to a
     * subsequent flush.
     */
    suspend fun flushPersistence() {
        persistence?.flush(mutableSnapshot.value)
    }

    /** Flush the latest state and stop this repository's background writer. */
    suspend fun closePersistence(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (persistenceClosed) return@withLock
            // Keep mutation publication, the final snapshot capture, and channel closure in one
            // repository critical section. Otherwise a revision can enqueue during close and be
            // discarded when the worker is cancelled immediately afterwards.
            try {
                persistence?.close(mutableSnapshot.value)
            } finally {
                persistenceClosed = true
            }
        }
    }

    suspend fun upsertManga(manga: Manga): Manga = mutate { state ->
        val existingIndex = when {
            manga.id > 0 -> state.mangas.indexOfFirst { it.id == manga.id }
            manga.url.isNotBlank() -> state.mangas.indexOfFirst { it.source == manga.source && it.url == manga.url }
            else -> -1
        }
        val resolved = manga.copy(
            id = when {
                existingIndex >= 0 -> state.mangas[existingIndex].id
                manga.id > 0 -> manga.id
                else -> nextId(state.mangas.map { it.id })
            },
        )
        val mangas = state.mangas.toMutableList().apply {
            if (existingIndex >= 0) this[existingIndex] = resolved else add(resolved)
        }
        var links = state.mangaCategories
        if (!resolved.favorite) {
            links = links.filterNot { it.mangaId == resolved.id }
        } else if (links.none { it.mangaId == resolved.id }) {
            links = links + MangaCategory(resolved.id, state.defaultFavoriteCategoryId())
        }
        Mutation(state.copy(mangas = mangas, mangaCategories = links), resolved)
    }

    suspend fun patchManga(
        mangaId: Long,
        patch: MangaPatch,
        modifiedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ): Manga = mutate { state ->
        val index = state.mangas.indexOfFirst { it.id == mangaId }
        if (index < 0) missing("Manga", mangaId)
        val current = state.mangas[index]
        val patched = current.applying(patch)
        val updated = if (patch.favorite != null && patched.favorite != current.favorite) {
            patched.withFavoriteMutationMetadata(current, modifiedAt)
        } else {
            patched
        }
        replaceMangaAt(state, index, updated)
    }

    /** Apply a read-modify-write manga update while holding the repository mutex. */
    suspend fun updateManga(mangaId: Long, transform: (Manga) -> Manga): Manga = mutate { state ->
        val index = state.mangas.indexOfFirst { it.id == mangaId }
        if (index < 0) missing("Manga", mangaId)
        val current = state.mangas[index]
        replaceMangaAt(state, index, transform(current).copy(id = current.id))
    }

    /**
     * Toggle library membership atomically so rapid input and an in-flight source refresh cannot
     * both decide from the same stale UI snapshot. Conflict metadata changes in the same commit.
     */
    suspend fun toggleMangaFavorite(
        mangaId: Long,
        modifiedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ): Manga = mutate { state ->
        val index = state.mangas.indexOfFirst { it.id == mangaId }
        if (index < 0) missing("Manga", mangaId)
        val current = state.mangas[index]
        val updated = current.copy(favorite = !current.favorite)
            .withFavoriteMutationMetadata(current, modifiedAt)
        replaceMangaAt(state, index, updated)
    }

    suspend fun deleteManga(mangaId: Long) {
        mutate { state ->
            if (state.mangas.none { it.id == mangaId }) missing("Manga", mangaId)
            val chapterIds = state.chapters.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.id }
            Mutation(
                state.copy(
                    mangas = state.mangas.filterNot { it.id == mangaId },
                    chapters = state.chapters.filterNot { it.mangaId == mangaId },
                    mangaCategories = state.mangaCategories.filterNot { it.mangaId == mangaId },
                    histories = state.histories.filterNot { it.chapterId in chapterIds },
                    updates = state.updates.filterNot { it.mangaId == mangaId || it.chapterId in chapterIds },
                    downloadQueue = state.downloadQueue.filterNot { it.mangaId == mangaId || it.chapterId in chapterIds },
                    tracks = state.tracks.filterNot { it.mangaId == mangaId },
                ),
                Unit,
            )
        }
    }

    suspend fun upsertChapter(chapter: Chapter): Chapter = mutate { state ->
        requireManga(state, chapter.mangaId)
        validateChapter(chapter)
        val existingIndex = when {
            chapter.id > 0 -> state.chapters.indexOfFirst { it.id == chapter.id }
            chapter.url.isNotBlank() -> state.chapters.indexOfFirst { it.mangaId == chapter.mangaId && it.url == chapter.url }
            else -> -1
        }
        val resolved = chapter.copy(
            id = when {
                existingIndex >= 0 -> state.chapters[existingIndex].id
                chapter.id > 0 -> chapter.id
                else -> nextId(state.chapters.map { it.id })
            },
        )
        val chapters = state.chapters.toMutableList().apply {
            if (existingIndex >= 0) this[existingIndex] = resolved else add(resolved)
        }
        Mutation(state.copy(chapters = chapters), resolved)
    }

    suspend fun upsertChapters(chapters: List<Chapter>): List<Chapter> = mutate { state ->
        val mangaIds = state.mangas.mapTo(mutableSetOf()) { it.id }
        chapters.forEach {
            if (it.mangaId !in mangaIds) missing("Manga", it.mangaId)
            validateChapter(it)
        }
        val mutable = state.chapters.toMutableList()
        var next = nextId(mutable.map { it.id })
        val resolved = chapters.map { chapter ->
            val index = when {
                chapter.id > 0 -> mutable.indexOfFirst { it.id == chapter.id }
                chapter.url.isNotBlank() -> mutable.indexOfFirst { it.mangaId == chapter.mangaId && it.url == chapter.url }
                else -> -1
            }
            val item = chapter.copy(
                id = when {
                    index >= 0 -> mutable[index].id
                    chapter.id > 0 -> chapter.id
                    else -> next++
                },
            )
            if (index >= 0) mutable[index] = item else mutable += item
            item
        }
        Mutation(state.copy(chapters = mutable), resolved)
    }

    suspend fun patchChapter(chapterId: Long, patch: ChapterPatch): Chapter = mutate { state ->
        val index = state.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) missing("Chapter", chapterId)
        val updated = state.chapters[index].applying(patch)
        validateChapter(updated)
        Mutation(state.copy(chapters = state.chapters.toMutableList().apply { this[index] = updated }), updated)
    }

    suspend fun deleteChapter(chapterId: Long) {
        mutate { state ->
            if (state.chapters.none { it.id == chapterId }) missing("Chapter", chapterId)
            Mutation(
                state.copy(
                    chapters = state.chapters.filterNot { it.id == chapterId },
                    histories = state.histories.filterNot { it.chapterId == chapterId },
                    updates = state.updates.filterNot { it.chapterId == chapterId },
                    downloadQueue = state.downloadQueue.filterNot { it.chapterId == chapterId },
                ),
                Unit,
            )
        }
    }

    /** Create a custom category with an atomically assigned ID and display order. */
    suspend fun createCategory(name: String): Category = mutate { state ->
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw RepositoryConstraintException("Category name cannot be blank")
        if (state.categories.any { it.name.trim().equals(normalizedName, ignoreCase = true) }) {
            throw RepositoryConstraintException("Category '$normalizedName' already exists")
        }

        val resolved = Category(
            id = nextId(state.categories.map { it.id }),
            name = normalizedName,
            sort = (state.categories.maxOfOrNull { it.sort } ?: -1) + 1,
        )
        Mutation(state.copy(categories = state.categories + resolved), resolved)
    }

    suspend fun upsertCategory(category: Category): Category = mutate { state ->
        val normalizedName = category.name.trim()
        if (normalizedName.isEmpty()) throw RepositoryConstraintException("Category name cannot be blank")
        if (category.id < Category.Default.id) {
            throw RepositoryConstraintException("Custom category IDs must be positive")
        }
        val defaultUpdate = category.id == Category.Default.id && normalizedName == Category.Default.name
        val updatedId = when {
            defaultUpdate -> Category.Default.id
            category.id > 0 -> category.id
            else -> null
        }
        if (state.categories.any {
                it.id != updatedId && it.name.trim().equals(normalizedName, ignoreCase = true)
            }
        ) {
            throw RepositoryConstraintException("Category '$normalizedName' already exists")
        }

        val normalized = category.copy(name = normalizedName)
        val existingIndex = if (defaultUpdate || normalized.id > 0) {
            state.categories.indexOfFirst { it.id == normalized.id }
        } else {
            -1
        }
        val resolved = normalized.copy(
            id = when {
                defaultUpdate -> Category.Default.id
                existingIndex >= 0 -> state.categories[existingIndex].id
                normalized.id > 0 -> normalized.id
                else -> nextId(state.categories.map { it.id })
            },
        )
        val categories = state.categories.toMutableList().apply {
            if (existingIndex >= 0) this[existingIndex] = resolved else add(resolved)
        }
        Mutation(state.copy(categories = categories), resolved)
    }

    /** Rename an existing custom category without changing its order, flags, or identity. */
    suspend fun renameCategory(categoryId: Long, name: String): Category = mutate { state ->
        if (categoryId == Category.Default.id) {
            throw RepositoryConstraintException("The default category cannot be renamed")
        }
        val index = state.categories.indexOfFirst { it.id == categoryId }
        if (index < 0) missing("Category", categoryId)
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw RepositoryConstraintException("Category name cannot be blank")
        if (state.categories.any {
                it.id != categoryId && it.name.trim().equals(normalizedName, ignoreCase = true)
            }
        ) {
            throw RepositoryConstraintException("Category '$normalizedName' already exists")
        }

        val renamed = state.categories[index].copy(name = normalizedName)
        val categories = state.categories.toMutableList().apply { this[index] = renamed }
        Mutation(state.copy(categories = categories), renamed)
    }

    suspend fun deleteCategory(categoryId: Long) {
        if (categoryId == Category.Default.id) throw RepositoryConstraintException("The default category cannot be deleted")
        mutate { state ->
            if (state.categories.none { it.id == categoryId }) missing("Category", categoryId)
            val affectedMangas = state.mangaCategories.filter { it.categoryId == categoryId }.mapTo(mutableSetOf()) { it.mangaId }
            val links = state.mangaCategories.filterNot { it.categoryId == categoryId }.toMutableList()
            affectedMangas.forEach { mangaId ->
                if (links.none { it.mangaId == mangaId }) links += MangaCategory(mangaId, Category.Default.id)
            }
            Mutation(
                state.copy(
                    categories = state.categories.filterNot { it.id == categoryId },
                    mangaCategories = links,
                    settings = if (state.settings.library.defaultCategoryId == categoryId) {
                        state.settings.copy(library = state.settings.library.copy(defaultCategoryId = Category.Default.id))
                    } else {
                        state.settings
                    },
                ),
                Unit,
            )
        }
    }

    suspend fun reorderCategories(categoryIds: List<Long>) {
        mutate { state ->
            val existingIds = state.categories.mapTo(mutableSetOf()) { it.id }
            if (!existingIds.containsAll(categoryIds)) throw RepositoryConstraintException("Cannot reorder unknown categories")
            val completeOrder = (categoryIds + state.categories.map { it.id }).distinct()
            val sortById = completeOrder.withIndex().associate { it.value to it.index }
            Mutation(state.copy(categories = state.categories.map { it.copy(sort = sortById.getValue(it.id)) }), Unit)
        }
    }

    suspend fun setMangaCategories(mangaId: Long, categoryIds: Collection<Long>) {
        mutate { state ->
            requireManga(state, mangaId)
            val normalizedIds = normalizeMangaCategorySelection(categoryIds)
            val known = state.categories.mapTo(mutableSetOf()) { it.id }
            val unknown = normalizedIds.firstOrNull { it !in known }
            if (unknown != null) missing("Category", unknown)
            val categoryOrder = state.categories
                .sortedWith(compareBy<Category> { it.sort }.thenBy { it.id })
                .mapIndexed { index, category -> category.id to index }
                .toMap()
            val resolvedIds = normalizedIds.sortedWith(
                compareBy<Long> { categoryOrder[it] ?: Int.MAX_VALUE }.thenBy { it },
            )
            val retained = state.mangaCategories.filterNot { it.mangaId == mangaId }
            Mutation(state.copy(mangaCategories = retained + resolvedIds.map { MangaCategory(mangaId, it) }), Unit)
        }
    }

    suspend fun recordHistory(chapterId: Long, lastRead: Long, timeRead: Long = 0): History = mutate { state ->
        requireChapter(state, chapterId)
        if (timeRead < 0) throw RepositoryConstraintException("Reading time cannot be negative")
        val index = state.histories.indexOfFirst { it.chapterId == chapterId }
        val resolved = if (index >= 0) {
            state.histories[index].copy(
                lastRead = lastRead,
                timeRead = accumulatedReadingTime(state.histories[index].timeRead, timeRead),
            )
        } else {
            History(id = nextId(state.histories.map { it.id }), chapterId = chapterId, lastRead = lastRead, timeRead = timeRead)
        }
        val histories = state.histories.toMutableList().apply {
            if (index >= 0) this[index] = resolved else add(resolved)
        }
        Mutation(state.copy(histories = histories), resolved)
    }

    suspend fun markChapterProgress(
        chapterId: Long,
        lastPageRead: Int,
        read: Boolean,
        readAt: Long,
        timeRead: Long = 0,
    ): Chapter = mutate { state ->
        val chapterIndex = state.chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) missing("Chapter", chapterId)
        if (lastPageRead < 0 || timeRead < 0) throw RepositoryConstraintException("Reading progress cannot be negative")
        val currentChapter = state.chapters[chapterIndex]
        // Reader progress is monotonic with respect to completion. Moving back a page after
        // reaching the end (or a late coroutine write) must not silently mark a chapter unread;
        // explicit unread actions continue to use patchChapter.
        val updatedChapter = currentChapter.copy(lastPageRead = lastPageRead, read = currentChapter.read || read)
        val historyIndex = state.histories.indexOfFirst { it.chapterId == chapterId }
        val history = if (historyIndex >= 0) {
            state.histories[historyIndex].copy(
                lastRead = readAt,
                timeRead = accumulatedReadingTime(state.histories[historyIndex].timeRead, timeRead),
            )
        } else {
            History(nextId(state.histories.map { it.id }), chapterId, readAt, timeRead)
        }
        val histories = state.histories.toMutableList().apply {
            if (historyIndex >= 0) this[historyIndex] = history else add(history)
        }
        Mutation(
            state.copy(
                chapters = state.chapters.toMutableList().apply { this[chapterIndex] = updatedChapter },
                histories = histories,
            ),
            updatedChapter,
        )
    }

    suspend fun deleteHistoryByManga(mangaId: Long) {
        mutate { state ->
            requireManga(state, mangaId)
            val chapterIds = state.chapters.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.id }
            Mutation(state.copy(histories = state.histories.filterNot { it.chapterId in chapterIds }), Unit)
        }
    }

    suspend fun deleteHistory(chapterId: Long) {
        mutate { state ->
            Mutation(state.copy(histories = state.histories.filterNot { it.chapterId == chapterId }), Unit)
        }
    }

    suspend fun clearHistory() {
        mutate { state -> Mutation(state.copy(histories = emptyList()), Unit) }
    }

    suspend fun upsertUpdate(update: LibraryUpdate): LibraryUpdate = mutate { state ->
        val chapter = requireChapter(state, update.chapterId)
        if (chapter.mangaId != update.mangaId) throw RepositoryConstraintException("Update manga/chapter relationship does not match")
        val index = state.updates.indexOfFirst { it.chapterId == update.chapterId }
        val updates = state.updates.toMutableList().apply {
            if (index >= 0) this[index] = update else add(update)
        }
        Mutation(state.copy(updates = updates), update)
    }

    suspend fun removeUpdate(chapterId: Long) {
        mutate { state -> Mutation(state.copy(updates = state.updates.filterNot { it.chapterId == chapterId }), Unit) }
    }

    suspend fun enqueueDownload(mangaId: Long, chapterId: Long, queuedAt: Long = 0): DownloadQueueItem = mutate { state ->
        requireManga(state, mangaId)
        val chapter = requireChapter(state, chapterId)
        if (chapter.mangaId != mangaId) throw RepositoryConstraintException("Download manga/chapter relationship does not match")
        val id = DownloadQueueItem.id(mangaId, chapterId)
        val existing = state.downloadQueue.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.visibleInQueue) {
                Mutation(state, existing)
            } else {
                val restored = existing.copy(
                    visibleInQueue = true,
                    position = (state.downloadQueue.filter { it.visibleInQueue }.maxOfOrNull { it.position } ?: -1) + 1,
                )
                Mutation(
                    state.copy(downloadQueue = state.downloadQueue.map { if (it.id == id) restored else it }),
                    restored,
                )
            }
        } else {
            val item = DownloadQueueItem(
                id = id,
                mangaId = mangaId,
                chapterId = chapterId,
                position = (state.downloadQueue.maxOfOrNull { it.position } ?: -1) + 1,
                queuedAt = queuedAt,
                updatedAt = queuedAt,
            )
            Mutation(state.copy(downloadQueue = state.downloadQueue + item), item)
        }
    }

    suspend fun upsertDownload(item: DownloadQueueItem): DownloadQueueItem = mutate { state ->
        requireManga(state, item.mangaId)
        val chapter = requireChapter(state, item.chapterId)
        if (chapter.mangaId != item.mangaId) throw RepositoryConstraintException("Download manga/chapter relationship does not match")
        if (item.id != DownloadQueueItem.id(item.mangaId, item.chapterId)) {
            throw RepositoryConstraintException("Download id does not match its manga and chapter")
        }
        validateDownload(item)
        val index = state.downloadQueue.indexOfFirst { it.id == item.id }
        val downloads = state.downloadQueue.toMutableList().apply {
            if (index >= 0) this[index] = item else add(item)
        }
        Mutation(state.copy(downloadQueue = downloads), item)
    }

    suspend fun setDownloadState(
        id: String,
        state: DownloadState,
        progress: Double? = null,
        downloadedPages: Int? = null,
        totalPages: Int? = null,
        updatedAt: Long = 0,
        errorMessage: String? = null,
    ): DownloadQueueItem = mutate { snapshot ->
        val index = snapshot.downloadQueue.indexOfFirst { it.id == id }
        if (index < 0) throw RepositoryConstraintException("Download $id does not exist")
        val current = snapshot.downloadQueue[index]
        val updated = current.copy(
            state = state,
            progress = progress ?: current.progress,
            downloadedPages = downloadedPages ?: current.downloadedPages,
            totalPages = totalPages ?: current.totalPages,
            updatedAt = updatedAt,
            errorMessage = if (state == DownloadState.ERROR) errorMessage else null,
        )
        validateDownload(updated)
        Mutation(snapshot.copy(downloadQueue = snapshot.downloadQueue.toMutableList().apply { this[index] = updated }), updated)
    }

    suspend fun removeDownload(id: String) {
        mutate { state -> Mutation(state.copy(downloadQueue = state.downloadQueue.filterNot { it.id == id }), Unit) }
    }

    suspend fun reorderDownloads(orderedIds: List<String>) {
        mutate { state ->
            val known = state.downloadQueue.mapTo(mutableSetOf()) { it.id }
            if (orderedIds.any { it !in known }) throw RepositoryConstraintException("Cannot reorder unknown downloads")
            val completeOrder = (orderedIds + state.downloadQueue.sortedBy { it.position }.map { it.id }).distinct()
            val positions = completeOrder.withIndex().associate { it.value to it.index }
            Mutation(state.copy(downloadQueue = state.downloadQueue.map { it.copy(position = positions.getValue(it.id)) }), Unit)
        }
    }

    suspend fun clearCompletedDownloads() {
        mutate { state ->
            Mutation(
                state.copy(
                    downloadQueue = state.downloadQueue.map { item ->
                        if (item.state == DownloadState.DOWNLOADED) item.copy(visibleInQueue = false) else item
                    },
                ),
                Unit,
            )
        }
    }

    suspend fun upsertTrack(track: Track): Track = mutate { state ->
        requireManga(state, track.mangaId)
        val index = when {
            track.id > 0 -> state.tracks.indexOfFirst { it.id == track.id }
            else -> state.tracks.indexOfFirst { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
        }
        val resolved = track.copy(
            id = when {
                index >= 0 -> state.tracks[index].id
                track.id > 0 -> track.id
                else -> nextId(state.tracks.map { it.id })
            },
        )
        if (state.tracks.any {
                it.id != resolved.id && it.mangaId == resolved.mangaId && it.trackerId == resolved.trackerId
            }
        ) {
            throw RepositoryConstraintException("Manga already has a link for tracker ${resolved.trackerId}")
        }
        val tracks = state.tracks.toMutableList().apply { if (index >= 0) this[index] = resolved else add(resolved) }
        Mutation(state.copy(tracks = tracks), resolved)
    }

    suspend fun deleteTrack(mangaId: Long, trackerId: Int) {
        mutate { state ->
            Mutation(state.copy(tracks = state.tracks.filterNot { it.mangaId == mangaId && it.trackerId == trackerId }), Unit)
        }
    }

    suspend fun upsertTrackerAccount(account: TrackerAccountState): TrackerAccountState = mutate { state ->
        val index = state.trackerAccounts.indexOfFirst { it.trackerId == account.trackerId }
        val accounts = state.trackerAccounts.toMutableList().apply {
            if (index >= 0) this[index] = account else add(account)
        }
        Mutation(state.copy(trackerAccounts = accounts), account)
    }

    suspend fun deleteTrackerAccount(trackerId: Int) {
        mutate { state ->
            Mutation(state.copy(trackerAccounts = state.trackerAccounts.filterNot { it.trackerId == trackerId }), Unit)
        }
    }

    suspend fun upsertExtensionRepository(repository: ExtensionRepo): ExtensionRepo = mutate { state ->
        val index = state.extensionRepositories.indexOfFirst { it.baseUrl == repository.baseUrl }
        val repositories = state.extensionRepositories.toMutableList().apply {
            if (index >= 0) this[index] = repository else add(repository)
        }
        Mutation(state.copy(extensionRepositories = repositories), repository)
    }

    suspend fun deleteExtensionRepository(baseUrl: String) {
        mutate { state ->
            Mutation(state.copy(extensionRepositories = state.extensionRepositories.filterNot { it.baseUrl == baseUrl }), Unit)
        }
    }

    suspend fun setSettings(settings: AppSettings): AppSettings = mutate { state ->
        validateSettings(settings)
        Mutation(state.copy(settings = settings), settings)
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutate { state ->
        val updated = transform(state.settings)
        validateSettings(updated)
        Mutation(state.copy(settings = updated), updated)
    }

    suspend fun setBackupState(backupState: BackupState): BackupState = mutate { state ->
        validateBackupState(backupState)
        Mutation(state.copy(backupState = backupState), backupState)
    }

    fun manga(mangaId: Long): Manga? = snapshot.value.mangas.firstOrNull { it.id == mangaId }

    fun manga(sourceId: Long, url: String): Manga? = snapshot.value.mangas.firstOrNull { it.source == sourceId && it.url == url }

    fun chapter(chapterId: Long): Chapter? = snapshot.value.chapters.firstOrNull { it.id == chapterId }

    fun chaptersForManga(mangaId: Long): List<Chapter> = snapshot.value.chapters.filter { it.mangaId == mangaId }

    fun categoriesForManga(mangaId: Long): List<Category> {
        val state = snapshot.value
        val ids = state.mangaCategories.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.categoryId }
            .ifEmpty { mutableSetOf(Category.Default.id) }
        return state.categories.filter { it.id in ids }.sortedBy { it.sort }
    }

    fun tracksForManga(mangaId: Long): List<Track> = snapshot.value.tracks.filter { it.mangaId == mangaId }

    fun downloadsForManga(mangaId: Long): List<DownloadQueueItem> = snapshot.value.downloadQueue
        .filter { it.mangaId == mangaId }
        .sortedBy { it.position }

    fun library(): List<LibraryManga> {
        val state = snapshot.value
        return state.mangas.asSequence().filter { it.favorite }.flatMap { manga ->
            val chapters = state.chapters.filter { it.mangaId == manga.id }
            val historyByChapter = state.histories.associateBy { it.chapterId }
            val categoryIds = state.mangaCategories.filter { it.mangaId == manga.id }.map { it.categoryId }
                .ifEmpty { listOf(Category.Default.id) }
            categoryIds.asSequence().map { categoryId ->
                LibraryManga(
                    manga = manga,
                    totalChapters = chapters.size,
                    readCount = chapters.count { it.read },
                    bookmarkCount = chapters.count { it.bookmark },
                    latestUpload = chapters.maxOfOrNull { it.dateUpload } ?: 0,
                    chapterFetchedAt = chapters.maxOfOrNull { it.dateFetch } ?: 0,
                    lastRead = chapters.maxOfOrNull { historyByChapter[it.id]?.lastRead ?: 0 } ?: 0,
                    category = categoryId,
                )
            }
        }.toList()
    }

    fun libraryItems(): List<LibraryItem> {
        val state = snapshot.value
        val downloads = state.downloadQueue.filter { it.state == DownloadState.DOWNLOADED }.groupingBy { it.mangaId }.eachCount()
        val trackers = state.tracks.groupBy { it.mangaId }
        return library().map { item ->
            LibraryItem(
                libraryManga = item,
                downloadCount = downloads[item.manga.id]?.toLong() ?: 0,
                isLocal = item.manga.source == 0L,
                trackerIds = trackers[item.manga.id].orEmpty().mapTo(linkedSetOf()) { it.trackerId },
            )
        }
    }

    fun history(query: String = ""): List<HistoryItem> {
        val state = snapshot.value
        val normalized = query.trim().lowercase()
        val mangaById = state.mangas.associateBy { it.id }
        val chapterById = state.chapters.associateBy { it.id }
        return state.histories.mapNotNull { history ->
            val chapter = chapterById[history.chapterId] ?: return@mapNotNull null
            val manga = mangaById[chapter.mangaId] ?: return@mapNotNull null
            if (normalized.isNotEmpty() && !manga.title.lowercase().contains(normalized) &&
                !chapter.name.lowercase().contains(normalized)
            ) {
                return@mapNotNull null
            }
            HistoryItem(manga, chapter, history.lastRead, history.timeRead)
        }.sortedByDescending { it.lastRead }
    }

    fun recentUpdates(limit: Int = Int.MAX_VALUE): List<UpdateItem> {
        if (limit <= 0) return emptyList()
        val state = snapshot.value
        val mangaById = state.mangas.associateBy { it.id }
        val chapterById = state.chapters.associateBy { it.id }
        return state.updates.sortedByDescending { it.discoveredAt }.asSequence().mapNotNull { update ->
            val manga = mangaById[update.mangaId] ?: return@mapNotNull null
            val chapter = chapterById[update.chapterId] ?: return@mapNotNull null
            UpdateItem(manga, chapter, update.discoveredAt)
        }.take(limit).toList()
    }

    companion object {
        fun decodeSnapshot(encoded: String): AppSnapshot = AppSnapshotJson.decode(encoded)
        fun encodeSnapshot(snapshot: AppSnapshot): String = AppSnapshotJson.encode(snapshot.withRequiredDefaults().validate())
    }

    private fun replaceMangaAt(
        state: AppSnapshot,
        index: Int,
        updated: Manga,
    ): Mutation<Manga> {
        val mangaId = state.mangas[index].id
        val resolved = updated.copy(id = mangaId)
        val mangas = state.mangas.toMutableList().apply { this[index] = resolved }
        var links = state.mangaCategories
        if (!resolved.favorite) {
            links = links.filterNot { it.mangaId == mangaId }
        } else if (links.none { it.mangaId == mangaId }) {
            links = links + MangaCategory(mangaId, state.defaultFavoriteCategoryId())
        }
        return Mutation(state.copy(mangas = mangas, mangaCategories = links), resolved)
    }

    private suspend fun <T> mutate(block: (AppSnapshot) -> Mutation<T>): T = mutex.withLock {
        ensurePersistenceOpen()
        val current = mutableSnapshot.value
        val mutation = block(current)
        val next = mutation.snapshot.withRequiredDefaults()
            .copy(revision = nextRevision(current.revision, mutation.snapshot.revision))
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        mutation.result
    }

    private data class Mutation<T>(val snapshot: AppSnapshot, val result: T)

    private fun ensurePersistenceOpen() {
        check(!persistenceClosed) { "Repository persistence is closed" }
    }
}

private val NO_PERSISTENCE_FAILURE: StateFlow<Throwable?> = MutableStateFlow(null).asStateFlow()

/**
 * Latest-wins persistence worker. The conflated channel prevents a burst of reader/download
 * progress updates from retaining multiple multi-megabyte snapshots. Disk writes are serialized
 * with explicit flushes, and revision checks prevent an older delayed write from overwriting a
 * newer flushed state.
 */
private class SnapshotPersistenceWorker(
    initialRevision: Long,
    private val persist: (String) -> Unit,
) {
    private val scopeJob: Job = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val updates = Channel<AppSnapshot>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private var lastPersistedRevision = initialRevision
    private val mutableFailure = MutableStateFlow<Throwable?>(null)

    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()

    init {
        launchPersistenceLoop()
    }

    fun enqueue(snapshot: AppSnapshot) {
        updates.trySend(snapshot).getOrThrow()
    }

    suspend fun flush(snapshot: AppSnapshot) {
        try {
            persistIfNewer(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableFailure.value = error
            throw error
        }
    }

    suspend fun close(snapshot: AppSnapshot) {
        // Closing is itself the final durability boundary. Once teardown starts, caller
        // cancellation must not cancel the flush and then terminate the only retrying writer.
        withContext(NonCancellable) {
            try {
                flush(snapshot)
            } finally {
                updates.close()
                scopeJob.cancelAndJoin()
            }
        }
    }

    private fun launchPersistenceLoop() {
        scope.launch {
            for (first in updates) {
                val latest = awaitDebouncedSnapshot(first, updates)
                try {
                    persistIfNewer(latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Keep the worker alive. A later mutation or explicit lifecycle flush retries
                    // with the newest complete snapshot and exposes the failure through [failure].
                    mutableFailure.value = error
                }
            }
        }
    }

    private suspend fun persistIfNewer(snapshot: AppSnapshot) = withContext(Dispatchers.Default) {
        writeMutex.withLock {
            if (snapshot.revision <= lastPersistedRevision) return@withLock
            val encoded = AppSnapshotJson.encode(snapshot.validate())
            persist(encoded)
            lastPersistedRevision = snapshot.revision
            mutableFailure.value = null
        }
    }
}

/**
 * Wait for a quiet window instead of rewriting a multi-megabyte snapshot every fixed 350 ms
 * throughout continuous reader/download progress. The maximum latency still bounds foreground
 * data loss if a process terminates before a quiet window arrives. Nested coroutine timeouts make
 * both boundaries use the caller's scheduler, which also keeps the policy deterministically tested.
 */
internal suspend fun awaitDebouncedSnapshot(
    first: AppSnapshot,
    updates: ReceiveChannel<AppSnapshot>,
    quietMillis: Long = PERSISTENCE_DEBOUNCE_MILLIS,
    maximumLatencyMillis: Long = MAX_PERSISTENCE_LATENCY_MILLIS,
): AppSnapshot {
    var latest = first
    withTimeoutOrNull(maximumLatencyMillis) persistenceWindow@{
        while (true) {
            val newer = withTimeoutOrNull(quietMillis) {
                updates.receiveCatching().getOrNull()
            } ?: return@persistenceWindow
            if (newer.revision > latest.revision) latest = newer
        }
    }
    return latest
}

private const val PERSISTENCE_DEBOUNCE_MILLIS = 350L
private const val MAX_PERSISTENCE_LATENCY_MILLIS = 2_000L

private fun Manga.withFavoriteMutationMetadata(previous: Manga, modifiedAt: Long): Manga {
    val timestamp = maxOf(
        modifiedAt,
        previous.lastModifiedAt,
        previous.favoriteModifiedAt ?: Long.MIN_VALUE,
    )
    return copy(
        lastModifiedAt = timestamp,
        favoriteModifiedAt = timestamp,
        version = previous.version + 1,
    )
}

class RepositoryConstraintException(message: String) : IllegalArgumentException(message)

private fun AppSnapshot.defaultFavoriteCategoryId(): Long {
    val configured = settings.library.defaultCategoryId
    return configured.takeIf { id -> categories.any { it.id == id } } ?: Category.Default.id
}

private fun nextId(ids: Collection<Long>): Long = (ids.asSequence().filter { it > 0 }.maxOrNull() ?: 0L) + 1L

private fun nextRevision(current: Long, proposed: Long): Long {
    if (current < 0L || proposed < 0L || current >= Long.MAX_VALUE - 1L || proposed >= Long.MAX_VALUE - 1L) {
        throw RepositoryConstraintException("Snapshot revision is outside the supported range")
    }
    val next = maxOf(current + 1L, proposed)
    if (next >= Long.MAX_VALUE - 1L) {
        throw RepositoryConstraintException("Snapshot revision is outside the supported range")
    }
    return next
}

private fun validateChapter(chapter: Chapter) {
    if (chapter.lastPageRead < 0) throw RepositoryConstraintException("Chapter page index cannot be negative")
}

private fun accumulatedReadingTime(current: Long, added: Long): Long {
    if (current < 0L || added < 0L || current > Long.MAX_VALUE - added) {
        throw RepositoryConstraintException("Reading time overflowed")
    }
    return current + added
}

private fun validateDownload(item: DownloadQueueItem) {
    if (item.progress !in 0.0..1.0) {
        throw RepositoryConstraintException("Download progress must be between 0 and 1")
    }
    if (item.downloadedPages < 0 || item.totalPages < 0) {
        throw RepositoryConstraintException("Download page counts cannot be negative")
    }
    if (item.downloadedPages > item.totalPages && item.totalPages != 0) {
        throw RepositoryConstraintException("Downloaded pages cannot exceed total pages")
    }
}

private fun validateSettings(settings: AppSettings) {
    if (settings.library.portraitColumns <= 0 || settings.library.landscapeColumns <= 0) {
        throw RepositoryConstraintException("Library columns must be positive")
    }
    if (settings.downloads.parallelDownloads <= 0 || settings.downloads.parallelPages <= 0) {
        throw RepositoryConstraintException("Parallel download counts must be positive")
    }
}

private fun validateBackupState(state: BackupState) {
    if (state.intervalHours <= 0) throw RepositoryConstraintException("Backup interval must be positive")
    if (state.retainedBackupCount < 0) throw RepositoryConstraintException("Retained backup count cannot be negative")
}

private fun requireManga(state: AppSnapshot, mangaId: Long): Manga =
    state.mangas.firstOrNull { it.id == mangaId } ?: missing("Manga", mangaId)

private fun requireChapter(state: AppSnapshot, chapterId: Long): Chapter =
    state.chapters.firstOrNull { it.id == chapterId } ?: missing("Chapter", chapterId)

private fun missing(type: String, id: Any): Nothing = throw RepositoryConstraintException("$type $id does not exist")
