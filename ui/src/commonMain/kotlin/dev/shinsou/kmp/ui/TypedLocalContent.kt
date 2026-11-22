package dev.shinsou.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.ContentAcquisition
import dev.shinsou.kmp.plugin.ExtensionLibraryBinding
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.sync.v3.SyncWorks
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.core.PluginImageLoader
import dev.shinsou.kmp.ui.reader.ContentFeatures

/*
 * R3-4 (docs/architecture/0006): what the library screens need from the content store about
 * typed local rows, read on the work dispatcher instead of in composition. Until a read finishes
 * the screens show what they know without it (the source's own cover, an unknown content type).
 */

/**
 * The exact extension bindings of the typed local rows of the library, used by every cover. Read
 * only while [active] (a section that shows covers): each lookup loads a publication graph under
 * the content-store lock. What was read stays while another section shows.
 */
@Composable
internal fun rememberTypedLocalBindings(
    snapshot: AppSnapshot,
    library: ExtensionLibraryBinding,
    acquisition: ContentAcquisition,
    backup: PortableContentBackupV2UiController?,
    workDispatcher: CoroutineDispatcher,
    active: Boolean = true,
): Map<PublicationKey, ExtensionLibraryBindingV2> {
    val keys = rememberTypedLocalKeys(snapshot)
    var bindings by remember { mutableStateOf<Map<PublicationKey, ExtensionLibraryBindingV2>>(emptyMap()) }
    suspend fun read(of: Collection<PublicationKey>) = runCatchingCancellable {
        withContext(workDispatcher) { of.mapNotNull { key -> library.extensionLibraryBindingV2(key)?.let { key to it } }.toMap() }
    }.onFailure { Log.warn("Library", it) { "Reading the extension bindings of library titles failed" } }.getOrNull()
    // A row's binding can appear without its key changing (a beta-era favorite gains one when a
    // chapter is first downloaded or read): when a download finishes, a chapter is first read or
    // the typed rows' chapters change, the rows still without one are looked up again. Not on
    // every library write: imported books never gain one, and reading progress writes often.
    val downloads by acquisition.extensionDownloadsV2.collectAsState()
    val downloaded = remember(downloads) { downloads.count { it.state == DownloadState.DOWNLOADED } }
    val bindingChances = remember(snapshot.mangas, snapshot.chapters, snapshot.histories) { bindingChances(snapshot) }
    val chances = bindingChances to downloaded
    // A content restore replaces the bindings under unchanged keys (one can lose its binding):
    // every key is read again, as when the keys change.
    val restoreResult = backup?.state?.collectAsState()?.value?.restoreResult
    val wholeRead = Triple(keys, library, restoreResult)
    var readFor by remember { mutableStateOf<Any?>(null) }
    var checkedChances by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(wholeRead, active) {
        if (!active || readFor == wholeRead) return@LaunchedEffect
        read(keys)?.let { bindings = it }
        readFor = wholeRead
        // That read covered the chances seen so far: no second pass over the same rows.
        checkedChances = chances
    }
    LaunchedEffect(chances, active, readFor) {
        if (!active || readFor == null || checkedChances == chances) return@LaunchedEffect
        checkedChances = chances
        val unbound = keys.filterNot(bindings::containsKey)
        if (unbound.isEmpty()) return@LaunchedEffect
        read(unbound)?.takeIf { it.isNotEmpty() }?.let { found -> bindings = bindings + found }
    }
    return bindings
}

/**
 * The content kinds of the typed local rows, for the Library screen only: reading them loads every
 * publication graph under the content-store lock. They are read again when the screen opens and
 * when the typed rows or their chapters change, not on every snapshot write (reading progress).
 */
@Composable
internal fun rememberTypedPublicationKinds(
    snapshot: AppSnapshot,
    features: ContentFeatures?,
    workDispatcher: CoroutineDispatcher,
): Map<PublicationKey, Set<ContentKind>> {
    val keys = rememberTypedLocalKeys(snapshot)
    val typedChapters = remember(snapshot.mangas, snapshot.chapters) { typedChapterIdentities(snapshot) }
    var kinds by remember { mutableStateOf<Map<PublicationKey, Set<ContentKind>>>(emptyMap()) }
    LaunchedEffect(keys, typedChapters, features) {
        kinds = if (features == null || keys.isEmpty()) {
            emptyMap()
        } else {
            runCatchingCancellable { withContext(workDispatcher) { features.publicationContentKinds(keys) } }
                .onFailure { Log.warn("Library", it) { "Reading the content kinds of library titles failed" } }
                .getOrDefault(kinds)
        }
    }
    return kinds
}

@Composable
private fun rememberTypedLocalKeys(snapshot: AppSnapshot): Set<PublicationKey> = remember(snapshot.mangas) {
    snapshot.mangas.mapNotNullTo(linkedSetOf()) { manga -> decodeTypedLocalPublicationUrl(manga.url) }
}

/**
 * What changes when a typed row can gain an extension binding: its chapters, which of them were
 * started, and the reading history's size (a first read adds an entry). Page progress does not.
 */
private fun bindingChances(snapshot: AppSnapshot): Any {
    val typed = snapshot.mangas.mapNotNullTo(hashSetOf()) { manga ->
        manga.id.takeIf { decodeTypedLocalPublicationUrl(manga.url) != null }
    }
    val started = snapshot.chapters.count { it.mangaId in typed && (it.read || it.lastPageRead > 0) }
    return Triple(typedChapterIdentities(snapshot), started, snapshot.histories.size)
}

/** Which chapters the typed rows have (ids and addresses), without their reading state. */
internal fun typedChapterIdentities(snapshot: AppSnapshot): List<Pair<Long, String>> {
    val typed = snapshot.mangas.mapNotNullTo(hashSetOf()) { manga ->
        manga.id.takeIf { decodeTypedLocalPublicationUrl(manga.url) != null }
    }
    if (typed.isEmpty()) return emptyList()
    return snapshot.chapters.mapNotNull { chapter -> (chapter.id to chapter.url).takeIf { chapter.mangaId in typed } }
}

/** The cover of [manga]: through its extension source when it is bound to one, else its own source. */
internal fun PluginImageLoader.libraryCover(
    manga: Manga,
    bindings: Map<PublicationKey, ExtensionLibraryBindingV2>,
): (suspend () -> ByteArray?)? {
    val binding = decodeExtensionLibraryPublicationUrl(manga.url)
        ?: decodeTypedLocalPublicationUrl(manga.url)?.let(bindings::get)
    return binding?.let { load(it.sourceKey, manga.thumbnailUrl) } ?: load(manga.source, manga.thumbnailUrl)
}

/** The exact extension binding of one library row [url]; a typed row's is read off the UI thread. */
@Composable
internal fun rememberExtensionBinding(
    url: String,
    library: ExtensionLibraryBinding,
    workDispatcher: CoroutineDispatcher,
): ExtensionLibraryBindingV2? {
    val direct = remember(url) { decodeExtensionLibraryPublicationUrl(url) }
    var typed by remember(url) { mutableStateOf<ExtensionLibraryBindingV2?>(null) }
    LaunchedEffect(url, library) {
        if (direct != null) return@LaunchedEffect
        val key = decodeTypedLocalPublicationUrl(url) ?: return@LaunchedEffect
        typed = runCatchingCancellable { withContext(workDispatcher) { library.extensionLibraryBindingV2(key) } }
            .onFailure { Log.warn("Library", it) { "Reading a title's extension binding failed" } }
            .getOrNull()
    }
    return direct ?: typed
}

/** What [rememberSyncWorkHashes] read the hashes for. */
private data class SyncWorkKey(val manga: Manga, val sync: SyncWorks?, val sharing: Boolean, val reviews: Boolean)

/** The sync identities of a title for sharing and reviews; null where they are not offered. */
internal data class SyncWorkHashes(val share: String? = null, val reviews: String? = null)

@Composable
internal fun rememberSyncWorkHashes(
    manga: Manga,
    sync: SyncWorks?,
    workDispatcher: CoroutineDispatcher,
): SyncWorkHashes {
    val syncState = sync?.state?.collectAsState()?.value
    val sharing = syncState != null && syncState.signedIn && syncState.sharingEnabled
    val reviews = syncState != null && syncState.signedIn && syncState.reviewsEnabled
    // The hashes belong to the title as it was when they were read, with the features then on:
    // while a changed title (a refreshed URL) or a feature change is read again, none is offered.
    val key = SyncWorkKey(manga, sync, sharing, reviews)
    var computed by remember { mutableStateOf<Pair<SyncWorkKey, SyncWorkHashes>?>(null) }
    LaunchedEffect(key) {
        if (sync == null || (!sharing && !reviews)) {
            computed = key to SyncWorkHashes()
            return@LaunchedEffect
        }
        // A typed row's identity is read from the content store.
        val read = runCatchingCancellable {
            withContext(workDispatcher) {
                SyncWorkHashes(
                    share = if (sharing) sync.shareableWork(manga) else null,
                    reviews = if (reviews) sync.reviewableWork(manga) else null,
                )
            }
        }.onFailure { Log.warn("Sync", it) { "Reading a title's sync identity failed" } }
            .getOrDefault(SyncWorkHashes())
        computed = key to read
    }
    return computed?.takeIf { (readFor, _) -> readFor == key }?.second ?: SyncWorkHashes()
}
