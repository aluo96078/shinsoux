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
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.persistence.HotRows
import dev.shinsou.kmp.persistence.SnapshotStorage
import dev.shinsou.kmp.persistence.WholeJsonSnapshotStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
 * Mutations commit to [snapshot] immediately. When a [SnapshotStorage] is present, a single
 * background writer validates and encodes the latest state after a short coalescing window.
 * Intermediate states are deliberately coalesced, while [flushPersistence] provides an explicit
 * durability boundary for lifecycle transitions and orderly shutdown.
 */
public class ShinsouRepository(
    initial: AppSnapshot = AppSnapshot(),
    storage: SnapshotStorage? = null,
    /** Where the background writer encodes and writes snapshots (a test's dispatcher in tests). */
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Every write replaces the whole snapshot JSON through [persist] ([WholeJsonSnapshotStorage]). */
    public constructor(
        initial: AppSnapshot = AppSnapshot(),
        persist: ((String) -> Unit)?,
    ) : this(initial, persist?.let(::WholeJsonSnapshotStorage))

    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(initial.withRequiredDefaults().validate())
    private var persistenceClosed = false
    private var mutationObserver: SnapshotMutationObserver? = null
    private var replacementGuard: SnapshotReplacementGuard? = null
    private val persistence = storage?.let {
        SnapshotPersistenceWorker(
            initial = mutableSnapshot.value,
            storage = it,
            current = { mutableSnapshot.value },
            dispatcher = persistenceDispatcher,
        )
    }

    public val snapshot: StateFlow<AppSnapshot> = mutableSnapshot.asStateFlow()
    public val currentSnapshot: AppSnapshot get() = snapshot.value

    /** Most recent asynchronous persistence error. A successful write clears it. */
    public val persistenceFailure: StateFlow<Throwable?>
        get() = persistence?.failure ?: NO_PERSISTENCE_FAILURE

    public fun exportSnapshot(): String = AppSnapshotJson.encode(snapshot.value)

    public suspend fun importSnapshot(encoded: String): AppSnapshot = replaceSnapshot(decodeSnapshot(encoded))

    /** Explicit bulk replacement whose synced portion must be published to every v2 device. */
    internal suspend fun importSnapshotAcrossSyncedDevices(encoded: String): AppSnapshot =
        replaceSnapshotAcrossSyncedDevices(decodeSnapshot(encoded))

    public suspend fun replaceSnapshot(imported: AppSnapshot): AppSnapshot = replaceSnapshot(
        imported = imported,
        notifyMutationObserver = true,
        origin = SnapshotReplacementOrigin.DIRECT,
    )

    /**
     * Explicit restore/reset path for Cloudflare v2. The observer first commits a selection-aware
     * mutation/tombstone diff and durable drafts; only then may the replacement become visible.
     */
    internal suspend fun replaceSnapshotAcrossSyncedDevices(imported: AppSnapshot): AppSnapshot = replaceSnapshot(
        imported = imported,
        notifyMutationObserver = true,
        origin = SnapshotReplacementOrigin.SYNCHRONIZED_BULK,
    )

    /**
     * Installs the v2 local-first transaction boundary. The observer runs while the repository
     * mutex is held and before a new projection becomes visible or is queued for persistence.
     * Consequently, a failed replica/outbox commit cannot leave an unsynchronised domain write.
     */
    public suspend fun setMutationObserver(observer: SnapshotMutationObserver?): Unit = mutex.withLock {
        ensurePersistenceOpen()
        mutationObserver = observer
    }

    public suspend fun setSnapshotReplacementGuard(guard: SnapshotReplacementGuard?): Unit = mutex.withLock {
        ensurePersistenceOpen()
        replacementGuard = guard
    }

    /** Installs/removes both halves without exposing a window where bulk replacement can bypass sync. */
    public suspend fun configureSyncMutationBoundary(
        observer: SnapshotMutationObserver?,
        guard: SnapshotReplacementGuard?,
    ): Unit = mutex.withLock {
        ensurePersistenceOpen()
        mutationObserver = observer
        replacementGuard = guard
    }

    /**
     * Publishes a projection materialised by the sync replica without feeding it back into the
     * local mutation/outbox path. This is the only replacement entry point remote catch-up and
     * checkpoint installation should use.
     */
    internal suspend fun replaceSnapshotFromSync(imported: AppSnapshot): AppSnapshot = replaceSnapshot(
        imported = imported,
        notifyMutationObserver = false,
        origin = SnapshotReplacementOrigin.SYNC_MATERIALIZER,
    )

    private suspend fun replaceSnapshot(
        imported: AppSnapshot,
        notifyMutationObserver: Boolean,
        origin: SnapshotReplacementOrigin,
    ): AppSnapshot = mutex.withLock {
        ensurePersistenceOpen()
        val current = mutableSnapshot.value
        replacementGuard?.beforeReplace(origin, current, imported)
        if (origin == SnapshotReplacementOrigin.SYNCHRONIZED_BULK && mutationObserver == null) {
            throw IllegalStateException("Cloudflare bulk replacement requires an installed sync mutation observer")
        }
        val next = imported.withRequiredDefaults()
            .copy(revision = nextRevision(current.revision, imported.revision))
            .validate()
        if (notifyMutationObserver) mutationObserver?.beforeCommit(current, next)
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        next
    }

    /** Optimistic replacement used by sync so a concurrent local edit is never overwritten. */
    public suspend fun replaceSnapshotIfRevision(expectedRevision: Long, imported: AppSnapshot): AppSnapshot? =
        replaceSnapshotIfRevision(
            expectedRevision,
            imported,
            notifyMutationObserver = true,
            origin = SnapshotReplacementOrigin.DIRECT,
        )

    /** Remote replica projection CAS that cannot feed materialized fields back into the outbox. */
    public suspend fun replaceSnapshotFromSyncIfRevision(
        expectedRevision: Long,
        imported: AppSnapshot,
    ): AppSnapshot? = replaceSnapshotIfRevision(
        expectedRevision = expectedRevision,
        imported = imported,
        notifyMutationObserver = false,
        origin = SnapshotReplacementOrigin.SYNC_MATERIALIZER,
    )

    /**
     * Publishes legacy rows derived from the already-durable typed content authority. This CAS
     * intentionally bypasses [mutationObserver]: the enclosing content transaction (or a restored
     * portable graph) already owns the corresponding sync events, so observing this compatibility
     * projection would emit a second, lossy Manga/Chapter mutation stream.
     */
    public suspend fun materializeContentAuthorityProjectionIfRevision(
        expectedRevision: Long,
        requested: AppSnapshot,
    ): AppSnapshot? = replaceSnapshotIfRevision(
        expectedRevision = expectedRevision,
        imported = requested,
        notifyMutationObserver = false,
        origin = SnapshotReplacementOrigin.CONTENT_AUTHORITY_MATERIALIZER,
    )

    private suspend fun replaceSnapshotIfRevision(
        expectedRevision: Long,
        imported: AppSnapshot,
        notifyMutationObserver: Boolean,
        origin: SnapshotReplacementOrigin,
    ): AppSnapshot? = mutex.withLock {
        ensurePersistenceOpen()
        val current = mutableSnapshot.value
        if (current.revision != expectedRevision) return@withLock null
        replacementGuard?.beforeReplace(origin, current, imported)
        if (origin == SnapshotReplacementOrigin.SYNCHRONIZED_BULK && mutationObserver == null) {
            throw IllegalStateException("Cloudflare bulk replacement requires an installed sync mutation observer")
        }
        val next = imported.withRequiredDefaults()
            .copy(revision = nextRevision(current.revision, imported.revision))
            .validate()
        if (notifyMutationObserver) mutationObserver?.beforeCommit(current, next)
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        next
    }

    public suspend fun reset(): AppSnapshot = replaceSnapshot(AppSnapshot())

    internal suspend fun resetAcrossSyncedDevices(): AppSnapshot = replaceSnapshotAcrossSyncedDevices(AppSnapshot())

    /**
     * Persist the latest state before returning. This bypasses the debounce and propagates a disk
     * failure to the caller. Mutations that begin after this method captures the state belong to a
     * subsequent flush.
     *
     * When the storage keeps the hot rows apart (C3), a flush writes them and, only if the cold
     * part changed, the JSON; [wholeSnapshot] (the lifecycle flush on going to the background)
     * also rewrites the JSON with every hot field, which is what an older version or Android's
     * backup of the file would read.
     */
    public suspend fun flushPersistence(wholeSnapshot: Boolean = false) {
        persistence?.flush(mutableSnapshot.value, wholeSnapshot)
    }

    /** Flush the latest state, the whole JSON included, and stop this repository's background writer. */
    public suspend fun closePersistence(): Unit = withContext(NonCancellable) {
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

    public suspend fun upsertManga(manga: Manga): Manga = mutate { state ->
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

    public suspend fun patchManga(
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
    public suspend fun updateManga(mangaId: Long, transform: (Manga) -> Manga): Manga = mutate { state ->
        val index = state.mangas.indexOfFirst { it.id == mangaId }
        if (index < 0) missing("Manga", mangaId)
        val current = state.mangas[index]
        replaceMangaAt(state, index, transform(current).copy(id = current.id))
    }

    /**
     * Toggle library membership atomically so rapid input and an in-flight source refresh cannot
     * both decide from the same stale UI snapshot. Conflict metadata changes in the same commit.
     */
    public suspend fun toggleMangaFavorite(
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

    public suspend fun deleteManga(mangaId: Long) {
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

    public suspend fun upsertChapter(chapter: Chapter): Chapter = mutate { state ->
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
        val histories = state.histories.fittingPages(mapOf(resolved.id to resolved.lastPageRead))
        Mutation(state.copy(chapters = chapters, histories = histories), resolved)
    }

    public suspend fun upsertChapters(chapters: List<Chapter>): List<Chapter> = mutate { state ->
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
        val histories = state.histories.fittingPages(resolved.associate { it.id to it.lastPageRead })
        Mutation(state.copy(chapters = mutable, histories = histories), resolved)
    }

    public suspend fun patchChapter(chapterId: Long, patch: ChapterPatch): Chapter = mutate { state ->
        val index = state.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) missing("Chapter", chapterId)
        val updated = state.chapters[index].applying(patch)
        validateChapter(updated)
        Mutation(
            state.copy(
                chapters = state.chapters.toMutableList().apply { this[index] = updated },
                histories = state.histories.fittingPages(mapOf(chapterId to updated.lastPageRead)),
            ),
            updated,
        )
    }

    public suspend fun deleteChapter(chapterId: Long) {
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
    public suspend fun createCategory(name: String): Category = mutate { state ->
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

    public suspend fun upsertCategory(category: Category): Category = mutate { state ->
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
    public suspend fun renameCategory(categoryId: Long, name: String): Category = mutate { state ->
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

    public suspend fun deleteCategory(categoryId: Long) {
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

    public suspend fun reorderCategories(categoryIds: List<Long>) {
        mutate { state ->
            val existingIds = state.categories.mapTo(mutableSetOf()) { it.id }
            if (!existingIds.containsAll(categoryIds)) throw RepositoryConstraintException("Cannot reorder unknown categories")
            val completeOrder = (categoryIds + state.categories.map { it.id }).distinct()
            val sortById = completeOrder.withIndex().associate { it.value to it.index }
            Mutation(state.copy(categories = state.categories.map { it.copy(sort = sortById.getValue(it.id)) }), Unit)
        }
    }

    public suspend fun setMangaCategories(mangaId: Long, categoryIds: Collection<Long>) {
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

    public suspend fun recordHistory(chapterId: Long, lastRead: Long, timeRead: Long = 0): History = mutate { state ->
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

    public suspend fun markChapterProgress(
        chapterId: Long,
        lastPageRead: Int,
        read: Boolean,
        readAt: Long,
        timeRead: Long = 0,
        lastLocator: ReadingLocator? = null,
        lastPageCount: Int? = null,
    ): Chapter = mutate { state ->
        val chapterIndex = state.chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) missing("Chapter", chapterId)
        if (lastPageRead < 0 || timeRead < 0 || lastPageCount != null && lastPageCount <= lastPageRead) {
            throw RepositoryConstraintException("Reading progress cannot be negative or outside its page count")
        }
        val currentChapter = state.chapters[chapterIndex]
        val historyIndex = state.histories.indexOfFirst { it.chapterId == chapterId }
        val currentHistory = state.histories.getOrNull(historyIndex)
        // A page callback records its observation time before any reporter or disk work. If an
        // older suspended callback resumes later, preserve the newer local page/history instead
        // of letting coroutine completion order rewrite the user's final position.
        val stale = currentHistory != null && readAt < currentHistory.lastRead
        // Reader progress is monotonic with respect to completion. Moving back a page after
        // reaching the end (or a late coroutine write) must not silently mark a chapter unread;
        // explicit unread actions continue to use patchChapter.
        val updatedChapter = currentChapter.copy(
            lastPageRead = if (stale) currentChapter.lastPageRead else lastPageRead,
            read = currentChapter.read || read,
        )
        val history = if (historyIndex >= 0) {
            requireNotNull(currentHistory).copy(
                lastRead = if (stale) currentHistory.lastRead else readAt,
                timeRead = if (stale) {
                    currentHistory.timeRead
                } else {
                    accumulatedReadingTime(currentHistory.timeRead, timeRead)
                },
                lastLocator = if (stale) currentHistory.lastLocator else lastLocator ?: currentHistory.lastLocator,
                // A renderer can report its page before the freshly reflowed page count. Keep an
                // older count only while it still contains the new page; otherwise clear the
                // stale rendition hint instead of rejecting the newer position at validation.
                lastPageCount = if (stale) {
                    currentHistory.lastPageCount
                } else {
                    lastPageCount ?: currentHistory.lastPageCount?.takeIf { lastPageRead < it }
                },
            )
        } else {
            History(
                id = nextId(state.histories.map { it.id }),
                chapterId = chapterId,
                lastRead = readAt,
                timeRead = timeRead,
                lastLocator = lastLocator,
                lastPageCount = lastPageCount,
            )
        }
        // Every other history of the chapter (there may be several) keeps a page count that holds
        // the new page, or none: a hot write is not validated whole (C3).
        val histories = state.histories.toMutableList().apply {
            if (historyIndex >= 0) this[historyIndex] = history else add(history)
        }.fittingPages(mapOf(chapterId to updatedChapter.lastPageRead))
        Mutation(
            state.copy(
                chapters = state.chapters.toMutableList().apply { this[chapterIndex] = updatedChapter },
                histories = histories,
            ),
            updatedChapter,
        )
    }

    internal suspend fun deleteHistoryByManga(mangaId: Long) {
        mutate { state ->
            requireManga(state, mangaId)
            val chapterIds = state.chapters.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.id }
            Mutation(state.copy(histories = state.histories.filterNot { it.chapterId in chapterIds }), Unit)
        }
    }

    public suspend fun deleteHistory(chapterId: Long) {
        mutate { state ->
            Mutation(state.copy(histories = state.histories.filterNot { it.chapterId == chapterId }), Unit)
        }
    }

    public suspend fun clearHistory() {
        mutate { state -> Mutation(state.copy(histories = emptyList()), Unit) }
    }

    public suspend fun upsertUpdate(update: LibraryUpdate): LibraryUpdate = mutate { state ->
        val chapter = requireChapter(state, update.chapterId)
        if (chapter.mangaId != update.mangaId) throw RepositoryConstraintException("Update manga/chapter relationship does not match")
        val index = state.updates.indexOfFirst { it.chapterId == update.chapterId }
        val updates = state.updates.toMutableList().apply {
            if (index >= 0) this[index] = update else add(update)
        }
        Mutation(state.copy(updates = updates), update)
    }

    internal suspend fun removeUpdate(chapterId: Long) {
        mutate { state -> Mutation(state.copy(updates = state.updates.filterNot { it.chapterId == chapterId }), Unit) }
    }

    public suspend fun enqueueDownload(mangaId: Long, chapterId: Long, queuedAt: Long = 0): DownloadQueueItem = mutate { state ->
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

    internal suspend fun upsertDownload(item: DownloadQueueItem): DownloadQueueItem = mutate { state ->
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

    public suspend fun setDownloadState(
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

    public suspend fun removeDownload(id: String) {
        mutate { state -> Mutation(state.copy(downloadQueue = state.downloadQueue.filterNot { it.id == id }), Unit) }
    }

    public suspend fun reorderDownloads(orderedIds: List<String>) {
        mutate { state ->
            val known = state.downloadQueue.mapTo(mutableSetOf()) { it.id }
            if (orderedIds.any { it !in known }) throw RepositoryConstraintException("Cannot reorder unknown downloads")
            val completeOrder = (orderedIds + state.downloadQueue.sortedBy { it.position }.map { it.id }).distinct()
            val positions = completeOrder.withIndex().associate { it.value to it.index }
            Mutation(state.copy(downloadQueue = state.downloadQueue.map { it.copy(position = positions.getValue(it.id)) }), Unit)
        }
    }

    public suspend fun clearCompletedDownloads() {
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

    public suspend fun upsertTrack(track: Track): Track = mutate { state ->
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

    public suspend fun deleteTrack(mangaId: Long, trackerId: Int) {
        mutate { state ->
            Mutation(state.copy(tracks = state.tracks.filterNot { it.mangaId == mangaId && it.trackerId == trackerId }), Unit)
        }
    }

    public suspend fun upsertTrackerAccount(account: TrackerAccountState): TrackerAccountState = mutate { state ->
        val index = state.trackerAccounts.indexOfFirst { it.trackerId == account.trackerId }
        val accounts = state.trackerAccounts.toMutableList().apply {
            if (index >= 0) this[index] = account else add(account)
        }
        Mutation(state.copy(trackerAccounts = accounts), account)
    }

    internal suspend fun deleteTrackerAccount(trackerId: Int) {
        mutate { state ->
            Mutation(state.copy(trackerAccounts = state.trackerAccounts.filterNot { it.trackerId == trackerId }), Unit)
        }
    }

    public suspend fun upsertExtensionRepository(repository: ExtensionRepo): ExtensionRepo = mutate { state ->
        val index = state.extensionRepositories.indexOfFirst { it.baseUrl == repository.baseUrl }
        val repositories = state.extensionRepositories.toMutableList().apply {
            if (index >= 0) this[index] = repository else add(repository)
        }
        Mutation(state.copy(extensionRepositories = repositories), repository)
    }

    public suspend fun deleteExtensionRepository(baseUrl: String) {
        mutate { state ->
            Mutation(state.copy(extensionRepositories = state.extensionRepositories.filterNot { it.baseUrl == baseUrl }), Unit)
        }
    }

    internal suspend fun setSettings(settings: AppSettings): AppSettings = mutate { state ->
        validateSettings(settings)
        Mutation(state.copy(settings = settings), settings)
    }

    public suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutate { state ->
        val updated = transform(state.settings)
        validateSettings(updated)
        Mutation(state.copy(settings = updated), updated)
    }

    public suspend fun setBackupState(backupState: BackupState): BackupState = mutate { state ->
        validateBackupState(backupState)
        Mutation(state.copy(backupState = backupState), backupState)
    }

    /** Read-modify-write of the backup state under the repository lock: a field edit keeps the others current. */
    public suspend fun updateBackupState(transform: (BackupState) -> BackupState): BackupState = mutate { state ->
        val updated = transform(state.backupState)
        validateBackupState(updated)
        Mutation(state.copy(backupState = updated), updated)
    }

    public fun manga(mangaId: Long): Manga? = snapshot.value.mangas.firstOrNull { it.id == mangaId }

    public fun manga(sourceId: Long, url: String): Manga? = snapshot.value.mangas.firstOrNull { it.source == sourceId && it.url == url }

    public fun chapter(chapterId: Long): Chapter? = snapshot.value.chapters.firstOrNull { it.id == chapterId }

    internal fun chaptersForManga(mangaId: Long): List<Chapter> = snapshot.value.chapters.filter { it.mangaId == mangaId }

    public fun categoriesForManga(mangaId: Long): List<Category> {
        val state = snapshot.value
        val ids = state.mangaCategories.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.categoryId }
            .ifEmpty { mutableSetOf(Category.Default.id) }
        return state.categories.filter { it.id in ids }.sortedBy { it.sort }
    }

    public fun tracksForManga(mangaId: Long): List<Track> = snapshot.value.tracks.filter { it.mangaId == mangaId }

    internal fun downloadsForManga(mangaId: Long): List<DownloadQueueItem> = snapshot.value.downloadQueue
        .filter { it.mangaId == mangaId }
        .sortedBy { it.position }

    public fun library(): List<LibraryManga> {
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

    public fun libraryItems(): List<LibraryItem> {
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

    public fun history(query: String = ""): List<HistoryItem> {
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

    public fun recentUpdates(limit: Int = Int.MAX_VALUE): List<UpdateItem> {
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

    public companion object {
        public fun decodeSnapshot(encoded: String): AppSnapshot = AppSnapshotJson.decode(encoded)
        internal fun encodeSnapshot(snapshot: AppSnapshot): String = AppSnapshotJson.encode(snapshot.withRequiredDefaults().validate())
    }

    /**
     * Commits a host-coordinated portable-state mutation without using the legacy direct snapshot
     * replacement path.
     *
     * The host callback owns the enclosing durable transaction and must invoke [commitSyncJournal]
     * inside it. The repository projection is published, and queued for persistence, only after
     * that transaction returns successfully. This is the boundary used by content-backup v2 so a
     * failed body/annotation/content-outbox write cannot expose a partially restored AppSnapshot.
     * The callback must not perform network I/O while its durable transaction is open.
     */
    public suspend fun <T> commitSnapshotMutationAtomically(
        requested: AppSnapshot,
        origin: SnapshotReplacementOrigin,
        hostCommit: suspend (
            previous: AppSnapshot,
            next: AppSnapshot,
            commitSyncJournal: suspend () -> Unit,
        ) -> T,
    ): AtomicSnapshotMutationResult<T> = mutex.withLock {
        ensurePersistenceOpen()
        val previous = mutableSnapshot.value
        replacementGuard?.beforeReplace(origin, previous, requested)
        if (origin == SnapshotReplacementOrigin.SYNCHRONIZED_BULK && mutationObserver == null) {
            throw IllegalStateException(
                "Cloudflare bulk mutation requires an installed sync mutation observer",
            )
        }
        val next = requested.withRequiredDefaults()
            .copy(revision = nextRevision(previous.revision, requested.revision))
            .validate()
        var journalCommitted = false
        val result = try {
            // C3-5 (docs/architecture/0007 §6.3.4): the hot rows are written inside the host's
            // transaction too, so a restore that fails later rolls them back with everything else.
            joinHostTransaction(persistence) { writeHotRows ->
                hostCommit(previous, next) {
                    check(!journalCommitted) { "The sync journal callback may be committed only once" }
                    mutationObserver?.beforeAtomicHostCommit(previous, next)
                    writeHotRows(next)
                    journalCommitted = true
                }
            }
        } catch (failure: Throwable) {
            if (journalCommitted) {
                try {
                    mutationObserver?.afterAtomicHostRollback(previous, next)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
        check(journalCommitted) { "The host transaction did not commit the sync journal boundary" }
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        AtomicSnapshotMutationResult(snapshot = next, result = result)
    }

    /**
     * Re-binds one library title to another source in a single commit: the row, its rewritten
     * chapters and the chapters without a counterpart change together or not at all. The plan was
     * made from an earlier snapshot, so it is refused when the row's URL or its chapter set has
     * changed since ([expectedUrl], and [rewrittenChapters] plus [removedChapterIds] must be
     * exactly the title's chapters).
     */
    public suspend fun rebindLibraryTitle(
        mangaId: Long,
        expectedUrl: String,
        transform: (Manga) -> Manga,
        rewrittenChapters: List<Chapter>,
        removedChapterIds: Collection<Long>,
    ): Manga = mutate { state ->
        val index = state.mangas.indexOfFirst { it.id == mangaId }
        if (index < 0) missing("Manga", mangaId)
        val current = state.mangas[index]
        if (current.url != expectedUrl) {
            throw RepositoryConstraintException("Manga $mangaId changed while it was being migrated")
        }
        val removed = removedChapterIds.toSet()
        val rewritten = rewrittenChapters.associateBy { it.id }
        if (rewritten.size != rewrittenChapters.size || rewritten.keys.any { it in removed }) {
            throw RepositoryConstraintException("A migrated chapter is planned twice")
        }
        val existing = state.chapters.filter { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.id }
        if (existing != rewritten.keys + removed) {
            throw RepositoryConstraintException("The chapters of manga $mangaId changed while it was being migrated")
        }
        rewrittenChapters.forEach { chapter ->
            if (chapter.mangaId != mangaId) throw RepositoryConstraintException("Chapter ${chapter.id} belongs to another manga")
            validateChapter(chapter)
        }
        val updated = transform(current).copy(id = current.id)
        if (state.mangas.any { it.id != mangaId && it.source == updated.source && it.url == updated.url }) {
            throw RepositoryConstraintException("The migration target is already in the library")
        }
        val rebound = replaceMangaAt(state, index, updated)
        val chapters = rebound.snapshot.chapters.mapNotNull { chapter ->
            if (chapter.id in removed) null else rewritten[chapter.id] ?: chapter
        }
        if (chapters.filter { it.mangaId == mangaId }.map { it.url }.let { it.size != it.toSet().size }) {
            throw RepositoryConstraintException("Two migrated chapters share one URL")
        }
        Mutation(
            rebound.snapshot.copy(
                chapters = chapters,
                histories = rebound.snapshot.histories.filterNot { it.chapterId in removed }
                    .fittingPages(rewrittenChapters.associate { it.id to it.lastPageRead }),
                updates = rebound.snapshot.updates.filterNot { it.chapterId in removed },
                downloadQueue = rebound.snapshot.downloadQueue.filterNot { it.chapterId in removed },
            ),
            rebound.result,
        )
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
        mutationObserver?.beforeCommit(current, next)
        mutableSnapshot.value = next
        persistence?.enqueue(next)
        mutation.result
    }

    private data class Mutation<T>(val snapshot: AppSnapshot, val result: T)

    private fun ensurePersistenceOpen() {
        check(!persistenceClosed) { "Repository persistence is closed" }
    }
}

public data class AtomicSnapshotMutationResult<T>(
    val snapshot: AppSnapshot,
    val result: T,
)

/**
 * Hook that lets a host persist its own durable state (the sync dirty set, a restore journal)
 * atomically with a local [AppSnapshot] change, before the new snapshot is published. Implementations must be idempotent and
 * must not call back into this repository while [beforeCommit] is running.
 */
public fun interface SnapshotMutationObserver {
    public suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot)

    /**
     * Joins a host-owned durable transaction. Implementations that normally switch dispatchers
     * must override this callback and remain on the enclosing SQLDelight transaction context.
     */
    public suspend fun beforeAtomicHostCommit(previous: AppSnapshot, next: AppSnapshot): Unit =
        beforeCommit(previous, next)

    /**
     * Reconciles observer-owned memory after [beforeAtomicHostCommit] joined a host SQLite
     * transaction that subsequently rolled back. Ordinary repository mutations never need this
     * callback.
     */
    public suspend fun afterAtomicHostRollback(previous: AppSnapshot, next: AppSnapshot): Unit = Unit
}

/** Identifies the only replacement paths that may cross the v2 synchronization boundary. */
public enum class SnapshotReplacementOrigin {
    /** Legacy import/restore/reset. A Cloudflare-ready guard must reject this path. */
    DIRECT,

    /** User explicitly chose to publish a bulk diff/tombstones to every synced device. */
    SYNCHRONIZED_BULK,

    /** Projection produced from the already-authoritative local replica. */
    SYNC_MATERIALIZER,

    /** Legacy compatibility rows derived from the durable typed content graph. */
    CONTENT_AUTHORITY_MATERIALIZER,
}

public fun interface SnapshotReplacementGuard {
    public suspend fun beforeReplace(
        origin: SnapshotReplacementOrigin,
        previous: AppSnapshot,
        requested: AppSnapshot,
    )
}

private val NO_PERSISTENCE_FAILURE: StateFlow<Throwable?> = MutableStateFlow(null).asStateFlow()

private suspend fun <T> joinHostTransaction(
    persistence: SnapshotPersistenceWorker?,
    block: suspend (writeHotRows: (AppSnapshot) -> Unit) -> T,
): T = persistence?.joiningHostTransaction(block) ?: block {}

/**
 * Latest-wins persistence worker. The conflated channel prevents a burst of reader/download
 * progress updates from retaining multiple multi-megabyte snapshots. Disk writes are serialized
 * with explicit flushes, and revision checks prevent an older delayed write from overwriting a
 * newer flushed state.
 *
 * C3-3 (docs/architecture/0007 §6.3.3): when the storage keeps the hot rows apart, a change of hot
 * rows only (a page turn, a downloaded page) writes just those rows; the JSON is encoded and
 * written, after a whole validate(), only when the cold part changed, on a whole flush, or when the
 * storage asks for a refresh.
 */
private class SnapshotPersistenceWorker(
    initial: AppSnapshot,
    private val storage: SnapshotStorage,
    /** The repository's latest snapshot, for a checkpoint the writer starts on its own. */
    private val current: () -> AppSnapshot,
    /** Where snapshots are encoded and written. */
    private val dispatcher: CoroutineDispatcher,
) {
    private val scopeJob: Job = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + dispatcher)
    private val updates = Channel<AppSnapshot>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private val mutableFailure = MutableStateFlow<Throwable?>(null)

    /** The revision written last, as far as it needed writing. */
    private var writtenRevision = initial.revision

    /** The snapshot whose rows the storage keeps apart; null when unknown (a write failed half-way). */
    private var hotRows: AppSnapshot? = initial

    /**
     * A snapshot whose cold part is that of the JSON on disk; null when unknown (a write failed
     * half-way): the next write is then the whole JSON with every row, a consistent baseline.
     */
    private var jsonCold: AppSnapshot? = initial

    /** The revision of the snapshot whose JSON, hot fields included, is on disk. */
    private var jsonRevision: Long = storage.revisionOnDisk ?: initial.revision

    /** The whole write the writer scheduled on its own while the JSON is behind the rows. */
    private var checkpoint: Job? = null

    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()

    init {
        launchPersistenceLoop()
        // Hot rows written in an earlier run that no JSON has yet (C3): an Android backup or an
        // older version reads the JSON alone, so it is written whole soon, not at the next change.
        if (storage.keepsHotApart && jsonRevision < initial.revision) scheduleCheckpoint(atOpen = true)
    }

    fun enqueue(snapshot: AppSnapshot) {
        updates.trySend(snapshot).getOrThrow()
    }

    suspend fun flush(snapshot: AppSnapshot, wholeSnapshot: Boolean) {
        try {
            persistIfNewer(snapshot, wholeSnapshot)
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
                flush(snapshot, wholeSnapshot = true)
            } finally {
                updates.close()
                scopeJob.cancelAndJoin()
            }
        }
    }

    /**
     * C3-5: runs [block] with the writer held, handing it a function that writes a snapshot's hot
     * rows on the caller's thread, inside the SQL transaction the caller has open. Those rows are
     * taken as written only once [block] returns; when it throws, its transaction rolled back.
     */
    suspend fun <T> joiningHostTransaction(block: suspend (writeHotRows: (AppSnapshot) -> Unit) -> T): T =
        writeMutex.withLock {
            if (!storage.keepsHotApart) return@withLock block {}
            var joined: AppSnapshot? = null
            var committed = false
            try {
                block { next ->
                    // A cold part the JSON on disk does not have (a restore) is recorded with the
                    // rows: a process death before the next whole write must not pair the rows with
                    // the older JSON.
                    val cold = jsonCold
                    val json = if (cold == null || coldChanged(cold, next)) encodeJson(next) else null
                    storage.writeInHostTransaction(next.revision, json, hotRowsFor(next))
                    joined = next
                }.also { committed = true }
            } finally {
                storage.hostTransactionEnded(committed && joined != null)
                // When the host failed, its transaction rolled back as far as anyone knows: the
                // next write puts every row in place rather than trusting either state.
                if (joined != null) hotRows = if (committed) joined else null
            }
        }

    private fun launchPersistenceLoop() {
        scope.launch {
            for (first in updates) {
                val latest = awaitDebouncedSnapshot(first, updates)
                try {
                    persistIfNewer(latest, wholeSnapshot = false)
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

    private suspend fun persistIfNewer(snapshot: AppSnapshot, wholeSnapshot: Boolean) = withContext(dispatcher) {
        writeMutex.withLock {
            if (alreadyWritten(snapshot, wholeSnapshot)) return@withLock
            val split = storage.keepsHotApart
            val cold = jsonCold
            val writeJson = !split || wholeSnapshot || cold == null || coldChanged(cold, snapshot) || storage.jsonRefreshDue()
            // Written even when only the revision moved: it is recorded with the rows, so a restart
            // never hands out a revision again.
            write(snapshot, writeJson, if (split) hotRowsFor(snapshot) else HotRows.Unchanged)
            writtenRevision = snapshot.revision
            mutableFailure.value = null
            if (split && jsonRevision < snapshot.revision) scheduleCheckpoint(atOpen = false)
        }
    }

    /**
     * Writes the whole JSON after the storage's delay unless one is scheduled already: hot rows are
     * then never more than that delay ahead of the JSON, even when no change follows them.
     */
    private fun scheduleCheckpoint(atOpen: Boolean) {
        if (checkpoint?.isActive == true) return
        val after = storage.jsonCheckpointDelay(atOpen) ?: return
        checkpoint = scope.launch {
            delay(after)
            try {
                persistIfNewer(current(), wholeSnapshot = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // As the loop does: the next write or flush retries, the failure is exposed.
                mutableFailure.value = error
            }
        }
    }

    /** Whether [snapshot] has nothing left to write (a [wholeSnapshot] flush still wants its JSON). */
    private fun alreadyWritten(snapshot: AppSnapshot, wholeSnapshot: Boolean): Boolean {
        // Older than the rows a host transaction already wrote (C3-5): its snapshot is queued next.
        val behindRows = (hotRows?.revision ?: 0L) > snapshot.revision
        val jsonBehind = wholeSnapshot && jsonRevision != snapshot.revision
        return when {
            behindRows || snapshot.revision < writtenRevision -> true
            snapshot.revision == writtenRevision -> !jsonBehind
            else -> false
        }
    }

    private fun write(snapshot: AppSnapshot, writeJson: Boolean, hot: HotRows) {
        // validate() runs on the whole snapshot only when it is written whole; the hot rows were
        // checked one by one when they were changed.
        val json = if (writeJson) encodeJson(snapshot) else null
        var written = false
        try {
            storage.write(snapshot.revision, json, hot)
            written = true
        } finally {
            // A write that failed may have failed half-way (the JSON written, its commit not): the
            // next one writes the whole JSON and puts every row in place, under a new pairing.
            if (storage.keepsHotApart) {
                hotRows = if (written) snapshot else null
                if (!written) jsonCold = null
            }
        }
        if (json != null) {
            jsonCold = snapshot
            jsonRevision = snapshot.revision
        }
    }

    /** [snapshot] encoded whole, after a whole validate(), with the marker of the storage's next JSON. */
    private fun encodeJson(snapshot: AppSnapshot): String =
        AppSnapshotJson.encode(snapshot.withHotStateMarker(storage.jsonMarker).validate())

    /** The rows to write so the storage holds [snapshot]'s. */
    private fun hotRowsFor(snapshot: AppSnapshot): HotRows {
        val written = hotRows ?: return HotRows.All(snapshot.hotState())
        val delta = hotDelta(written, snapshot)
        return if (delta.isEmpty) HotRows.Unchanged else HotRows.Changed(delta)
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

internal class RepositoryConstraintException(message: String) : IllegalArgumentException(message)

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
