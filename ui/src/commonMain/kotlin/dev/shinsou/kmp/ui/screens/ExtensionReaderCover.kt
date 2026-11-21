package dev.shinsou.kmp.ui.screens

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
import dev.shinsou.kmp.ui.i18n.text

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
 * ([dev.shinsou.kmp.ui.extensionUnitIdentity]) about to open; [onGiveUp] runs if the user
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

/** "Retry" on a failed cover: the chapter opens again, as the user's choice. */
internal fun ExtensionPublicationState.retryReaderCover() {
    val failed = readerCover?.takeIf { it.failure != null } ?: return
    val selection = units.firstOrNull { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == failed.unit } ?: return
    val cover = coverForReader(failed.unit)
    operationMessage = null
    if (!materialize(selection, onSettled = { coveredOpenSettled(cover, reportFailure = true) })) {
        coveredOpenSettled(cover, reportFailure = true)
    }
}
