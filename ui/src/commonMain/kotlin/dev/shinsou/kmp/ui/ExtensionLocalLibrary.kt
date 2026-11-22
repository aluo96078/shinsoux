package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.ContentAcquisition
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.isPlaceholderPublicationTitle
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * R4-3 (docs/architecture/0006): extension publications kept in the app's own library, as a
 * local row whose URL names the exact extension publication.
 */
internal class ExtensionLocalLibrary(
    private val repository: ShinsouRepository,
    private val acquisition: ContentAcquisition,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Whether [item] is a favorite of the local library. Reads the in-memory snapshot only. */
    fun isFavorite(item: BrowseManga): Boolean {
        val publicationKey = item.publicationKey() ?: return false
        return repository.currentSnapshot.mangas.any { it.isLocalRowOf(publicationKey) && it.favorite }
    }

    /** Whether [manga] is the local row of the publication [item] (any favorite state). */
    fun isRowOf(manga: Manga, item: BrowseManga): Boolean {
        val publicationKey = item.publicationKey() ?: return false
        return manga.isLocalRowOf(publicationKey)
    }

    /**
     * Adds [publication] to the library or removes it. A title leaving the library also leaves the
     * download queue and frees its offline copy (a failed purge does not undo the removal).
     */
    suspend fun setFavorite(item: BrowseManga, publication: RemotePublicationV2, favorite: Boolean) {
        val cleaned = publication.withoutScriptObjectText()
        val sourceKey = requireNotNull(item.sourceKey) { "Publication is missing its source identity" }
        val remoteId = requireNotNull(item.remotePublicationId) { "Publication is missing its remote identity" }
        val publicationKey = extensionPublicationKey(sourceKey, remoteId)
        val localUrl = encodeExtensionLibraryPublicationUrl(sourceKey, remoteId)
        val timestamp = now()
        val current = repository.currentSnapshot.mangas.firstOrNull { it.isLocalRowOf(publicationKey) }
        if (current == null) {
            if (!favorite) return
            repository.upsertManga(newLocalRow(cleaned, localUrl, timestamp))
        } else {
            repository.updateManga(current.id) { stored ->
                stored.updatedFrom(cleaned, remoteId, localUrl, favorite, timestamp)
            }
        }
        if (!favorite) {
            withContext(workDispatcher) {
                acquisition.removeExtensionPublicationDownloadsV2(sourceKey, remoteId)
                runCatchingCancellable { acquisition.purgeExtensionPublicationContentV2(sourceKey, remoteId) }
                    .onFailure { Log.warn("Library", it) { "Purging the offline copy of an unfavorited title failed" } }
            }
        }
    }

    private fun newLocalRow(publication: RemotePublicationV2, localUrl: String, timestamp: Long) = Manga(
        source = LOCAL_SOURCE_ID,
        favorite = true,
        dateAdded = timestamp,
        url = localUrl,
        title = publication.title,
        author = publication.author,
        artist = publication.artist,
        description = publication.description,
        genre = publication.genre ?: listOf("Extension"),
        thumbnailUrl = publication.thumbnailUrl,
        updateStrategy = 1,
        initialized = true,
        lastModifiedAt = timestamp,
        favoriteModifiedAt = timestamp,
        version = 1,
    )
}

private fun BrowseManga.publicationKey(): PublicationKey? =
    sourceKey?.let { key -> remotePublicationId?.let { remoteId -> extensionPublicationKey(key, remoteId) } }

private fun Manga.isLocalRowOf(publicationKey: PublicationKey): Boolean =
    source == LOCAL_SOURCE_ID &&
        (decodeExtensionLibraryPublicationUrl(url)?.publicationKey == publicationKey ||
            decodeTypedLocalPublicationUrl(url) == publicationKey)

private fun Manga.updatedFrom(
    publication: RemotePublicationV2,
    remoteId: String,
    localUrl: String,
    favorite: Boolean,
    timestamp: Long,
): Manga {
    val favoriteChanged = favorite != this.favorite
    val modifiedAt = maxOf(timestamp, lastModifiedAt, favoriteModifiedAt ?: Long.MIN_VALUE)
    // Never replace a real title with a source's "Name #id" stand-in.
    val keepTitle = isPlaceholderPublicationTitle(publication.title, remoteId) &&
        !isPlaceholderPublicationTitle(title, remoteId)
    return copy(
        url = localUrl,
        favorite = favorite,
        title = if (keepTitle) title else publication.title,
        author = publication.author ?: author,
        artist = publication.artist ?: artist,
        description = publication.description ?: description.withoutScriptObjectText(),
        genre = publication.genre ?: genre,
        thumbnailUrl = publication.thumbnailUrl ?: thumbnailUrl,
        initialized = true,
        lastModifiedAt = if (favoriteChanged) modifiedAt else timestamp,
        favoriteModifiedAt = if (favoriteChanged) modifiedAt else favoriteModifiedAt,
        version = version + 1,
    )
}
