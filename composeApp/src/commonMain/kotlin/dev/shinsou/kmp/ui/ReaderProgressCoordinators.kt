package dev.shinsou.kmp.ui

import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.History
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.local.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.reader.readerStoryOrderComparator
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

// R4-4 (docs/architecture/0006): moved out of ShinsouApp.kt without changing it.

internal data class ReaderLocalProgressEvent(
    val chapterId: Long,
    val pageIndex: Int,
    val read: Boolean,
    val readAt: Long,
    val locator: ReadingLocator? = null,
    val pageCount: Int? = null,
) {
    init {
        require(chapterId >= 0) { "Reader chapter id must be non-negative" }
        require(pageIndex >= 0) { "Reader page index must be non-negative" }
        require(readAt >= 0) { "Reader history time must be non-negative" }
        require(pageCount == null || pageCount > pageIndex) {
            "Reader page must be inside its page count"
        }
    }
}

/** Serializes local reader writes so closing or changing chapters can await an exact disk barrier. */
internal class ReaderLocalProgressCoordinator(
    private val repository: ShinsouRepository,
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private sealed interface Command {
        data class Progress(val event: ReaderLocalProgressEvent) : Command
        data class Flush(val completion: kotlinx.coroutines.CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    fun enqueue(event: ReaderLocalProgressEvent) {
        commands.trySend(Command.Progress(event)).getOrThrow()
    }

    suspend fun flushLocal() {
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        commands.send(Command.Flush(completion))
        completion.await()
    }

    suspend fun run() {
        for (command in commands) {
            when (command) {
                is Command.Progress -> {
                    try {
                        commit(command.event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        onFailure(failure)
                    }
                }
                is Command.Flush -> {
                    try {
                        repository.flushPersistence()
                        command.completion.complete(Unit)
                    } catch (failure: Throwable) {
                        command.completion.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    internal suspend fun commit(event: ReaderLocalProgressEvent) {
        repository.markChapterProgress(
            chapterId = event.chapterId,
            lastPageRead = event.pageIndex,
            read = event.read,
            readAt = event.readAt,
            lastLocator = event.locator,
            lastPageCount = event.pageCount,
        )
    }
}

internal data class V2ReaderProgressEvent(
    val title: String,
    val unitTitle: String,
    val locator: ReadingLocator,
    val pageIndex: Int,
    val pageCount: Int? = null,
    val progressSessionId: String,
    val readAt: Long,
) {
    init {
        require(pageIndex >= 0) { "Reader page index must be non-negative" }
        require(pageCount == null || pageCount > pageIndex) { "Reader page must be inside its page count" }
        require(readAt >= 0) { "Reader history time must be non-negative" }
        require(progressSessionId.isNotBlank()) { "Reader progress session id must not be blank" }
    }
}

/**
 * App-owned progress lane for extension readers. Local compatibility writes preserve every
 * observation in order.
 */
internal class V2ReaderProgressCoordinator(
    private val repository: ShinsouRepository,
) {
    private sealed interface LocalCommand {
        data class Progress(val event: V2ReaderProgressEvent) : LocalCommand
        data class Flush(val completion: kotlinx.coroutines.CompletableDeferred<Unit>) : LocalCommand
    }

    private val events = Channel<LocalCommand>(Channel.UNLIMITED)
    private val lastCommittedAtByUnit = mutableMapOf<String, Long>()

    fun enqueue(event: V2ReaderProgressEvent) {
        events.trySend(LocalCommand.Progress(event)).getOrThrow()
    }

    /** Waits until every previously observed local progress event is committed and on disk. */
    suspend fun flushLocal() {
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        events.send(LocalCommand.Flush(completion))
        completion.await()
    }

    suspend fun run() {
        for (command in events) {
            when (command) {
                is LocalCommand.Progress -> commitLocal(command.event)
                is LocalCommand.Flush -> {
                    try {
                        repository.flushPersistence()
                        command.completion.complete(Unit)
                    } catch (failure: Throwable) {
                        command.completion.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    internal suspend fun commit(event: V2ReaderProgressEvent) {
        commitLocal(event)
    }

    internal suspend fun commitLocal(event: V2ReaderProgressEvent): Boolean {
        val unitKey = event.locator.scope.let { scope ->
            "${scope.publicationId.value}:${scope.acquisitionId}:${scope.unitId.value}"
        }
        val scope = event.locator.scope
        val chapterUrl = encodeTypedLocalChapterUrl(
            publicationKey = scope.publicationId,
            acquisitionId = scope.acquisitionId,
            unitKey = scope.unitId,
        )
        val initialSnapshot = repository.currentSnapshot
        val persistedChapterId = initialSnapshot.chapters.firstOrNull { chapter ->
            chapter.url == chapterUrl
        }?.id
        val persistedReadAt = initialSnapshot.histories.firstOrNull { history ->
            history.chapterId == persistedChapterId
        }?.lastRead
        val previousReadAt = maxOf(lastCommittedAtByUnit[unitKey] ?: 0L, persistedReadAt ?: 0L)
        if (event.readAt < previousReadAt) return false

        val completed = (event.locator.progression ?: 0.0) >= 0.995
        val publicationUrl = encodeTypedLocalPublicationUrl(scope.publicationId)
        val current = repository.currentSnapshot
        val manga = current.mangas.firstOrNull {
            it.source == LOCAL_SOURCE_ID &&
                (it.url == publicationUrl ||
                    decodeExtensionLibraryPublicationUrl(it.url)?.publicationKey == scope.publicationId)
        } ?: repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                favorite = false,
                dateAdded = event.readAt,
                url = publicationUrl,
                title = event.title,
                description = "Extension publication",
                genre = listOf("Extension"),
                updateStrategy = 1,
                initialized = true,
                lastModifiedAt = event.readAt,
                version = 1,
            ),
        )
        val chapter = repository.currentSnapshot.chapters.firstOrNull {
            it.mangaId == manga.id && it.url == chapterUrl
        } ?: repository.upsertChapter(
            Chapter(
                mangaId = manga.id,
                url = chapterUrl,
                name = event.unitTitle,
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = event.readAt,
                dateUpload = event.readAt,
                lastModifiedAt = event.readAt,
                version = 1,
            ),
        )
        repository.markChapterProgress(
            chapterId = chapter.id,
            lastPageRead = event.pageIndex,
            read = completed,
            readAt = event.readAt,
            lastLocator = event.locator,
            lastPageCount = event.pageCount,
        )
        lastCommittedAtByUnit[unitKey] = event.readAt
        return true
    }
}

internal fun latestHistoryChapter(
    snapshot: AppSnapshot,
    allowedMangaIds: Set<Long>? = null,
): Chapter? {
    val chapterById = snapshot.chapters.associateBy(Chapter::id)
    return snapshot.histories
        .asSequence()
        .mapNotNull { history -> chapterById[history.chapterId]?.let { chapter -> history to chapter } }
        .filter { (_, chapter) -> allowedMangaIds == null || chapter.mangaId in allowedMangaIds }
        .maxWithOrNull(compareBy<Pair<dev.shinsou.kmp.domain.model.History, Chapter>> { it.first.lastRead }
            .thenBy { it.first.id })
        ?.second
}

internal fun continueChapter(snapshot: AppSnapshot, mangaId: Long): Chapter? {
    latestHistoryChapter(snapshot, setOf(mangaId))?.let { return it }
    val chapters = snapshot.chapters.filter { it.mangaId == mangaId }
        .sortedWith(readerStoryOrderComparator)
    return chapters.firstOrNull { !it.read } ?: chapters.lastOrNull()
}
