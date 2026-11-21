package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.BrowseCallbacks
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.navigation.AppNavigator
import dev.shinsou.kmp.ui.i18n.StringKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a state holder runs its work and reports failures. */
internal class HolderEnvironment(
    /** The shell's scope (the UI thread); results are applied there. */
    val scope: CoroutineScope,
    val messages: AppMessages,
    /** Where content-store, plugin and network work runs: never the UI thread. */
    val workDispatcher: CoroutineDispatcher,
)

/**
 * R4-3 (docs/architecture/0006): opening a title from anywhere in the app. Library rows of
 * extension publications open their publication page (after a first-access permission review),
 * beta-era rows are repaired first, source titles are resolved into library rows, and everything
 * else opens the ordinary detail page.
 */
internal class TitleNavigationController(
    private val navigator: AppNavigator,
    private val repository: ShinsouRepository,
    private val browse: BrowseCallbacks,
    private val reviews: PluginReviewCoordinator,
    private val localLibrary: ExtensionLocalLibrary,
    private val environment: HolderEnvironment,
) {
    private val messages get() = environment.messages
    private var bindingLookup: Job? = null

    /**
     * Opens the title [mangaId]. The resolver persists a title before returning, but the screen's
     * snapshot can be a frame behind: the detail page opens anyway and renders once it catches up.
     */
    fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit = {}): Long {
        val request = ++openRequest
        // A newer open supersedes a lookup still in flight.
        bindingLookup?.cancel()
        bindingLookup = null
        // ...and a legacy recovery still searching: its result would replace this title's page.
        if (navigator.state.value.recoveringLocalLibraryMangaId != null) navigator.cancelRecovery()
        val manga = repository.currentSnapshot.mangas.firstOrNull { it.id == mangaId }
        navigator.forgetBrowseOwnedDetail()
        when {
            manga == null -> openDetail(mangaId, favorite = null, onSettled)
            // The exact extension binding of a typed row is in the content store: read off the UI thread.
            needsBindingLookup(manga) -> bindingLookup = openAfterBindingLookup(manga, onSettled)
            else -> openRouted(routeOf(manga), manga, onSettled)
        }
        return request
    }

    /** Counts title opens: only the latest may still navigate once its review is over. */
    private var openRequest = 0L

    /**
     * Gives up the title open [request] (every open still under way when null): its binding lookup
     * and legacy recovery stop, and a permission review it is waiting on goes away, so it can no
     * longer navigate.
     */
    fun cancelOpen(request: Long? = null) {
        if (request != null && request != openRequest) return
        openRequest++
        bindingLookup?.cancel()
        bindingLookup = null
        reviews.dropStaleReview()
        // A legacy favorite still being recovered neither keeps its screen nor opens later.
        if (navigator.state.value.recoveringLocalLibraryMangaId != null) navigator.cancelRecovery()
    }

    private fun openRouted(route: LocalLibraryExtensionRoute, manga: Manga, onSettled: (TitleOpenOutcome) -> Unit) {
        if (!follow(route, manga, onSettled)) openDetail(manga.id, manga.favorite, onSettled)
    }

    private fun openDetail(mangaId: Long, favorite: Boolean?, onSettled: (TitleOpenOutcome) -> Unit) {
        navigator.openMangaDetail(mangaId, favorite)
        onSettled(TitleOpenOutcome.DETAIL)
    }

    /**
     * Opens [manga] once its binding is read. The user may navigate while the lookup runs: the
     * result is applied only if the visible page never changed meanwhile (leaving and coming back
     * to it counts as a change).
     */
    private fun openAfterBindingLookup(manga: Manga, onSettled: (TitleOpenOutcome) -> Unit): Job {
        val origin = navigator.pageChanges
        var settled = false
        // Once: a review or a recovery handed the open on may still end it after this job.
        val settle = { outcome: TitleOpenOutcome ->
            if (!settled) {
                settled = true
                onSettled(outcome)
            }
        }
        return environment.scope.launch {
            val route = runCatchingCancellable {
                withContext(environment.workDispatcher) { routeOf(manga) }
            }.onFailure { Log.warn("Library", it) { "Reading a library title's extension binding failed" } }
                .getOrDefault(LocalLibraryExtensionRoute.LocalDetail)
            // The page changed, or the row is another title now (a restored backup reused its id).
            if (navigator.pageChanges != origin || !stillTheRow(manga)) {
                settle(TitleOpenOutcome.ABANDONED)
                return@launch
            }
            openRouted(route, manga, settle)
        }.also { job ->
            // Superseded by a newer open (or the shell went away) before the lookup finished.
            job.invokeOnCompletion { cause -> if (cause != null) settle(TitleOpenOutcome.ABANDONED) }
        }
    }

    /** Resolves the pending source title [item] of [request] into a library row and opens it. */
    suspend fun resolvePending(item: BrowseManga, request: Long) {
        val result = runCatchingCancellable { withContext(environment.workDispatcher) { browse.resolveManga(item) } }
        if (!navigator.isPendingBrowse(item, request)) return
        result.onSuccess { mangaId ->
            navigator.finishPendingBrowse(item, request, mangaId)
            if (mangaId == null) messages.show(StringKey.UNABLE_TO_OPEN_THIS_TITLE)
        }.onFailure { error ->
            navigator.finishPendingBrowse(item, request, mangaId = null)
            messages.showFailure(error, StringKey.UNABLE_TO_OPEN_THIS_TITLE)
        }
    }

    /** [mangaIds] left the library: pages showing them close. */
    fun closeRemovedLibraryTitles(mangaIds: Set<Long>) {
        val open = navigator.state.value.localLibraryPublication
        val openRemoved = open != null && repository.currentSnapshot.mangas.any { manga ->
            manga.id in mangaIds && localLibrary.isRowOf(manga, open)
        }
        navigator.closeRemovedTitles(mangaIds, openRemoved)
    }

    /** Whether [manga]'s row is still that title (a restored backup can give its id to another). */
    private fun stillTheRow(manga: Manga): Boolean =
        repository.currentSnapshot.mangas.firstOrNull { it.id == manga.id }?.let { sameTitle(it, manga) } == true

    private fun sameTitle(stored: Manga, manga: Manga): Boolean = titleIdentity(stored) == titleIdentity(manga)

    private fun routeOf(manga: Manga): LocalLibraryExtensionRoute = localLibraryExtensionRoute(
        manga = manga,
        hasLocalChapters = repository.currentSnapshot.chapters.any { it.mangaId == manga.id },
        legacyBinding = browse::extensionLibraryBindingV2,
    )

    /**
     * Follows [route]; false when [manga] belongs on the ordinary detail page. [onSettled] runs
     * once the extension page opened (ELSEWHERE) or the attempt ended without it (ABANDONED):
     * after a permission review or a legacy recovery, which can take long.
     */
    private fun follow(route: LocalLibraryExtensionRoute, manga: Manga, onSettled: (TitleOpenOutcome) -> Unit): Boolean =
        when (route) {
            LocalLibraryExtensionRoute.LocalDetail -> false
            is LocalLibraryExtensionRoute.Open -> {
                // The review's lookup and the user's answer take time: if the visible page changed
                // meanwhile, neither the prompt nor the title is shown over the new page.
                // A newer title open supersedes it as well, even on the same page.
                val origin = navigator.pageChanges
                val request = openRequest
                val current = { navigator.pageChanges == origin && openRequest == request && stillTheRow(manga) }
                reviews.afterFirstAccessReview(
                    packageId = route.binding.sourceKey.packageId,
                    abandon = { onSettled(TitleOpenOutcome.ABANDONED) },
                    stillWanted = current,
                ) {
                    if (current()) {
                        openExtension(route, manga)
                        onSettled(TitleOpenOutcome.ELSEWHERE)
                    } else {
                        onSettled(TitleOpenOutcome.ABANDONED)
                    }
                }
                true
            }
            is LocalLibraryExtensionRoute.RecoverLegacy -> {
                recover(route, manga, onSettled)
                true
            }
        }

    private fun openExtension(route: LocalLibraryExtensionRoute.Open, manga: Manga) {
        if (route.migrateLegacyUrl) {
            environment.scope.launch {
                runCatchingCancellable {
                    withContext(environment.workDispatcher) {
                        repository.updateManga(manga.id) { stored ->
                            if (sameTitle(stored, manga)) stored.copy(url = route.binding.localUrl()) else stored
                        }
                    }
                }
                    .onFailure { messages.showFailure(it, StringKey.THE_OPERATION_COULD_NOT_BE_COMPLETED) }
            }
        }
        navigator.openLocalExtension(route.binding.toBrowseManga(manga), localLibraryExtensionHostSection())
    }

    /**
     * UUID-only beta rows cannot be reversed directly: enabled sources are searched, and only a
     * candidate whose deterministic publication id matches exactly is accepted. The request token
     * keeps a recovery cancelled by Back from navigating later.
     */
    private fun recover(route: LocalLibraryExtensionRoute.RecoverLegacy, manga: Manga, onSettled: (TitleOpenOutcome) -> Unit) {
        val hostSection = localLibraryExtensionHostSection()
        val request = navigator.beginRecovery(manga.id)
        var opened = false
        // Shown once the open has settled: a waiting link is not held up by the message.
        var message: (suspend () -> Unit)? = null
        var searching = true
        val search = environment.scope.launch {
            try {
                val searched = withContext(environment.workDispatcher) {
                    browse.recoverExtensionLibraryBindingV2(route.publicationKey, manga.title)
                }
                searching = false
                if (navigator.isRecovering(request, manga.id)) {
                    if (searched == null) {
                        navigator.endRecovery(request, manga.id)
                        message = { messages.show(StringKey.UNABLE_TO_RECOVER_THIS_LEGACY_EXTENSION_FAVORITE) }
                    } else {
                        val outcome = repairAndOpen(searched, manga, request, hostSection)
                        opened = outcome.opened
                        if (outcome.repairFailed) message = { messages.show(StringKey.THE_TITLE_OPENED_BUT_ITS_REPAIRED_EXTENSION) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                if (navigator.isRecovering(request, manga.id)) {
                    navigator.endRecovery(request, manga.id)
                    message = { messages.showFailure(logged, StringKey.UNABLE_TO_RECOVER_THIS_LEGACY_EXTENSION_FAVORITE) }
                }
            } finally {
                searching = false
                navigator.endRecovery(request, manga.id)
                onSettled(if (opened) TitleOpenOutcome.ELSEWHERE else TitleOpenOutcome.ABANDONED)
            }
            message?.invoke()
        }
        // Back, another tab, a newer recovery or open: the search stops at once, so the open settles
        // (and a link waiting on it moves on) instead of waiting for every source to answer.
        environment.scope.launch {
            navigator.state.first { !navigator.isRecovering(request, manga.id) }
            if (searching) search.cancel()
        }.also { watcher -> search.invokeOnCompletion { watcher.cancel() } }
    }

    private class RecoveryOutcome(val opened: Boolean, val repairFailed: Boolean)

    private suspend fun repairAndOpen(
        searched: ExtensionLibraryBindingV2,
        manga: Manga,
        request: Long,
        hostSection: MainSection,
    ): RecoveryOutcome {
        val repaired = runCatchingCancellable {
            withContext(environment.workDispatcher) {
                repository.updateManga(manga.id) { stored -> if (sameTitle(stored, manga)) stored.copy(url = searched.localUrl()) else stored }
            }
        }
        // Repaired, the row's identity is the repaired URL's (a UUID-only row had none to keep).
        val stillThere = stillTheRow(manga) || stillTheRow(manga.copy(url = searched.localUrl()))
        if (!navigator.isRecovering(request, manga.id) || !stillThere) return RecoveryOutcome(opened = false, repairFailed = false)
        navigator.openLocalExtension(searched.toBrowseManga(repository.manga(manga.id) ?: manga), hostSection)
        // Set before the recovery ends: ending it must not cancel the open that just happened.
        val outcome = RecoveryOutcome(opened = true, repairFailed = repaired.isFailure)
        navigator.endRecovery(request, manga.id)
        return outcome
    }

    private fun needsBindingLookup(manga: Manga): Boolean =
        manga.source == LOCAL_SOURCE_ID &&
            decodeExtensionLibraryPublicationUrl(manga.url) == null &&
            decodeTypedLocalPublicationUrl(manga.url) != null
}

private fun ExtensionLibraryBindingV2.localUrl(): String =
    encodeExtensionLibraryPublicationUrl(sourceKey, remotePublicationId)

internal fun ExtensionLibraryBindingV2.toBrowseManga(manga: Manga): BrowseManga = BrowseManga(
    sourceId = sourceKey.legacyLongId ?: Long.MIN_VALUE,
    url = "",
    title = manga.title,
    thumbnailUrl = manga.thumbnailUrl,
    author = manga.author,
    artist = manga.artist,
    description = manga.description,
    genre = manga.genre,
    sourceKey = sourceKey,
    remotePublicationId = remotePublicationId,
)

/** How a title open ended: on its detail page, elsewhere (an extension page, a recovery), or not at all. */
internal enum class TitleOpenOutcome { DETAIL, ELSEWHERE, ABANDONED }
