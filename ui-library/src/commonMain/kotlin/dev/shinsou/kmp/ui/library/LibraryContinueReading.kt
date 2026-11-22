package dev.shinsou.kmp.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.LibraryItem
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.contracts.ExtensionLibraryBinding
import dev.shinsou.kmp.plugin.contracts.ExtensionPublicationCatalog
import dev.shinsou.kmp.sync.v3.SyncWorks
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.reader.extensionContinueReadingDetail
import dev.shinsou.kmp.ui.reader.extensionContinueUnitId
import dev.shinsou.kmp.ui.reader.readingProgressPageLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What the library's "continue reading" banner shows: the title read last on any device, the
 * chapter it continues with, and for an extension title the source's id of that chapter, which
 * opens through the title's page (it may not be stored here yet).
 */
public class LibraryContinueReading internal constructor(
    public val item: LibraryItem,
    internal val detail: String?,
    public val extensionUnitId: String?,
)

/** The extension title [manga] is, by its own URL or (for a typed local row) its stored binding. */
public fun extensionBindingOf(
    manga: Manga,
    typedBindings: Map<PublicationKey, ExtensionLibraryBindingV2>,
): ExtensionLibraryBindingV2? =
    decodeExtensionLibraryPublicationUrl(manga.url)
        ?: decodeTypedLocalPublicationUrl(manga.url)?.let(typedBindings::get)

/**
 * The banner's title, read off the UI thread: the library title read last, here or on another
 * device whose reading waits for chapters not stored here. Null when nothing is being read.
 */
@Composable
public fun <B> rememberLibraryContinueReading(
    snapshot: AppSnapshot,
    libraryItems: List<LibraryItem>,
    typedBindings: Map<PublicationKey, ExtensionLibraryBindingV2>,
    sync: SyncWorks?,
    browse: B,
    workDispatcher: CoroutineDispatcher,
): LibraryContinueReading? where B : ExtensionPublicationCatalog, B : ExtensionLibraryBinding {
    val strings = LocalShinsouStrings.current
    // A sync that ends may bring reading from other devices without changing the library.
    val syncState = sync?.state?.collectAsState()?.value
    var result by remember { mutableStateOf<LibraryContinueReading?>(null) }
    LaunchedEffect(snapshot.revision, libraryItems, typedBindings, syncState, strings) {
        result = runCatchingCancellable {
            withContext(workDispatcher) {
                val synced = sync?.pendingLastReadTimes(libraryItems.map { it.libraryManga.manga }).orEmpty()
                val latest = libraryItems
                    .map { item -> item to maxOf(item.libraryManga.lastRead, synced[item.id] ?: 0L) }
                    .filter { (_, at) -> at > 0 }
                    .maxByOrNull { (_, at) -> at }
                    ?.first
                latest?.let { continueReadingOf(it, snapshot, typedBindings, browse, strings) }
            }
        }.onFailure { Log.warn("Library", it) { "Reading the title to continue failed" } }.getOrNull()
    }
    return result
}

/**
 * Where "continue reading" takes [item]; null when there is nothing left to continue. Blocks on
 * the content store: call off the UI thread.
 */
public suspend fun <B> continueReadingOf(
    item: LibraryItem,
    snapshot: AppSnapshot,
    typedBindings: Map<PublicationKey, ExtensionLibraryBindingV2>,
    browse: B,
    strings: ShinsouStrings,
): LibraryContinueReading? where B : ExtensionPublicationCatalog, B : ExtensionLibraryBinding {
    val binding = extensionBindingOf(item.libraryManga.manga, typedBindings)
        ?: return localContinueReading(item, snapshot, strings)
    // The saved list of a library title names its chapters without asking the source.
    val units = browse.cachedExtensionPublicationPageV2(binding.sourceKey, binding.remotePublicationId)
        ?.units.orEmpty()
    val unitIds = units.map { it.unit.remoteId }
    val reading = browse.extensionLocalReadingStateV2(binding.sourceKey, binding.remotePublicationId, unitIds)
    val completed = reading.completedRemoteUnitIds
    val finished = unitIds.isNotEmpty() && unitIds.all { it in completed }
    val unitId = (extensionContinueUnitId(unitIds, completed, reading.lastReadRemoteUnitId) ?: reading.lastReadRemoteUnitId)
        ?.takeUnless { finished }
    return unitId?.let { id ->
        val title = units.firstOrNull { it.unit.remoteId == id }?.unit?.title
        val resumed = id == reading.lastReadRemoteUnitId || id in reading.inProgressRemoteUnits
        val detail = title?.takeIf { resumed }?.let {
            extensionContinueReadingDetail(
                strings = strings,
                unitTitle = it,
                progress = reading.inProgressRemoteUnits[id]?.takeIf { id !in completed },
            )
        }
        LibraryContinueReading(item, detail, id)
    }
}

private fun localContinueReading(
    item: LibraryItem,
    snapshot: AppSnapshot,
    strings: ShinsouStrings,
): LibraryContinueReading? =
    continueChapter(snapshot, item.id)?.takeIf { item.unreadCount > 0 }?.let { chapter ->
        val detail = if (!chapter.read && chapter.lastPageRead > 0) {
            "${chapter.name} · ${readingProgressPageLabel(strings, chapter.lastPageRead, null)}"
        } else {
            chapter.name
        }
        LibraryContinueReading(item, strings.text(StringKey.LAST_READ_X, detail), extensionUnitId = null)
    }
