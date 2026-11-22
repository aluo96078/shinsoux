package dev.shinsou.kmp.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.browse.extensionUnitIdentity
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The reader's loading screen over a publication page while "continue reading" opens [cover]'s
 * chapter: the title, the chapter once known, and Back, which gives the open up and shows the page.
 * A failed open says why and offers to retry. It takes every tap, so nothing on the page beneath is
 * pressed by accident.
 */
@Composable
internal fun ReaderOpeningCover(
    cover: ReaderCover,
    title: String,
    unitTitle: String?,
    /** The shell's Back while the reader shows ([PublicationPaneNavigation.readerBackRequest]). */
    systemBackRequest: Long,
    onLeave: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val leave = rememberUpdatedState(onLeave)
    // Only a Back pressed while this cover shows leaves it.
    val backAtStart = remember(cover) { systemBackRequest }
    LaunchedEffect(cover, systemBackRequest) {
        if (systemBackRequest != backAtStart) leave.value()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            IconButton(onClick = onLeave, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = strings.text(StringKey.BACK))
            }
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unitTitle != null) {
                    Text(
                        unitTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val failure = cover.failure
                if (failure == null) {
                    CircularProgressIndicator(Modifier.padding(top = 8.dp).size(36.dp))
                } else {
                    Text(
                        failure,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onLeave) { Text(strings.text(StringKey.GO_TO_THE_TITLE_PAGE)) }
                        Button(onClick = onRetry) { Text(strings.retry) }
                    }
                }
            }
        }
    }
}

/**
 * Covers the page with the reader's loading screen for the chapter [unit]
 * ([dev.shinsou.kmp.ui.browse.extensionUnitIdentity]) about to open; [onGiveUp] runs if the user
 * leaves it before the chapter shows. The shell treats it as the reader already.
 */
internal fun ExtensionPublicationState.coverForReader(unit: Long, onGiveUp: () -> Unit = {}): ReaderCover =
    ReaderCover(unit, onGiveUp).also {
        readerCover = it
        // What the cover reports on failure is this open's, not an earlier message of the page.
        operationMessage = null
        onReaderVisibilityChanged(true)
    }

/**
 * The open [cover] stood for ended. The reader shows, or a representation is to be chosen on
 * the page: the cover goes. It failed: the cover says why when [reportFailure] (the user asked
 * for the chapter), and otherwise (a restored chapter) simply goes.
 */
internal fun ExtensionPublicationState.coveredOpenSettled(cover: ReaderCover, reportFailure: Boolean) {
    if (readerCover !== cover) return
    val failed = readerSession == null && pendingRepresentation == null
    if (failed && reportFailure) {
        readerCover = ReaderCover(
            cover.unit,
            cover.onGiveUp,
            failure = operationMessage ?: loadError ?: work.strings().text(StringKey.THIS_CHAPTER_IS_NOT_LISTED_AND_NOT),
        )
    } else {
        readerCover = null
        if (readerSession == null) onReaderVisibilityChanged(false)
    }
}

/** The user left the loading screen: the chapter still opening stays stored but does not show. */
internal fun ExtensionPublicationState.leaveReaderCover() {
    val cover = readerCover ?: return
    readerCover = null
    if (openingReaderUnitId != null) readerClosedWhileOpening = true
    cover.onGiveUp()
    if (readerSession == null) onReaderVisibilityChanged(false)
}

/** "Retry" on a failed cover: the chapter opens again, listed or stored, the list asked again first if it failed. */
internal fun ExtensionPublicationState.retryReaderCover() {
    val failed = readerCover?.takeIf { it.failure != null } ?: return
    val cover = coverForReader(failed.unit, failed.onGiveUp)
    work.scope.launch {
        if (loadError != null && !loading) load()
        continueUnit(failed.unit, stillWanted = { readerCover === cover }) { coveredOpenSettled(cover, reportFailure = true) }
    }
}

/** Whether the chapter [unit] ([extensionUnitIdentity]) is the one opening in the reader now. */
internal fun ExtensionPublicationState.isOpeningUnit(unit: Long): Boolean =
    openingReaderUnitId?.let { extensionUnitIdentity(it) == unit } == true

/**
 * The work [cover] stood for stopped before its chapter began opening (its request went, or the
 * page reloads): the cover goes. One whose chapter opens already stays until that open settles.
 */
internal fun ExtensionPublicationState.coveredOpenCancelled(cover: ReaderCover) {
    if (readerCover !== cover || isOpeningUnit(cover.unit)) return
    readerCover = null
    if (readerSession == null) onReaderVisibilityChanged(false)
}

/**
 * Loads the page, and opens the chapter [navigation] asks for under the reader's loading screen:
 * a restored one after a restart, or "continue reading" (also while the page already shows). Each
 * request is handled once; one whose open had not begun when the page reloaded is taken again.
 */
@Composable
internal fun PublicationLoadAndReopenEffects(
    state: ExtensionPublicationState,
    navigation: PublicationPaneNavigation,
    scope: CoroutineScope,
) {
    val reopen = rememberUpdatedState(navigation)
    val claim = remember(state) { ReopenClaim() }
    LaunchedEffect(state, navigation.refreshGeneration) {
        // A pane replaced while it opens (the layout changed, the title stayed) leaves the chapter
        // pending for the pane that replaces it; leaving the title gives it up elsewhere.
        val pending = reopen.value.reopen
        if (pending == null || !claim.takeForLoad(pending, state)) {
            state.load()
        } else {
            coveredOpen(state, pending, scope) { settled ->
                state.loadAndReopen(pending.unit, pending.stillWanted, pending.userRequested, settled)
            }
        }
    }
    // "Continue reading" of this title while its page already shows: the load above ran for an
    // earlier request (or none), so the chapter asked for now opens here.
    val requested = navigation.reopen
    LaunchedEffect(requested) {
        if (requested != null && claim.take(requested)) {
            coveredOpen(state, requested, scope) { settled -> state.continueUnit(requested.unit, requested.stillWanted, settled) }
        }
    }
}

/** The reopen request a page has taken on (main thread only), and whether its load took it. */
private class ReopenClaim {
    private var request: PublicationReaderReopen? = null
    private var byLoad = false

    fun take(next: PublicationReaderReopen): Boolean = (request !== next).also {
        if (it) {
            request = next
            byLoad = false
        }
    }

    /** As [take]; the reloading page also takes again the request its earlier load gave up unopened. */
    fun takeForLoad(next: PublicationReaderReopen, state: ExtensionPublicationState): Boolean {
        if (take(next)) {
            byLoad = true
            return true
        }
        return byLoad && state.readerSession == null && !state.isOpeningUnit(next.unit)
    }
}

/** [open]s [request]'s chapter under the reader's loading screen, which goes however the open ends. */
private suspend fun coveredOpen(
    state: ExtensionPublicationState,
    request: PublicationReaderReopen,
    scope: CoroutineScope,
    open: suspend (onSettled: () -> Unit) -> Unit,
) {
    // The reader's loading screen from the start: the page is not shown, nor tapped, first.
    val cover = state.coverForReader(request.unit, request.onHandled)
    try {
        open { if (scope.isActive) settle(state, request, cover) }
    } finally {
        // A page that went (its scope with it) leaves the request to the page that replaces it.
        if (scope.isActive) state.coveredOpenCancelled(cover)
    }
}

/** The covered open of [request] ended: it is no longer pending, and the cover shows how it went. */
private fun settle(state: ExtensionPublicationState, request: PublicationReaderReopen, cover: ReaderCover) {
    // One asked for no longer (another title or chapter was asked for since) reports nothing.
    val report = request.userRequested && request.stillWanted()
    request.onHandled()
    state.coveredOpenSettled(cover, reportFailure = report)
}
