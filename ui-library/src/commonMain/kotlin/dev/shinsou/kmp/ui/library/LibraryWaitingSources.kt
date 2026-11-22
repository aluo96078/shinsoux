package dev.shinsou.kmp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.sync.v3.SyncMissingSource
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

/** How many waiting titles a source lists by name before the rest are left to its count. */
private const val NAMED_TITLES_PER_SOURCE = 3

/**
 * Says in the library that titles from the account's other devices exist but cannot join it yet:
 * which sources they wait for, which titles, and from which repository. Without it a new device's
 * library is just empty while sync holds those titles back.
 */
@Composable
internal fun LibraryWaitingSourcesCard(
    sources: List<SyncMissingSource>,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    strings.text(StringKey.X_TITLES_ARE_WAITING_FOR_THEIR_EXTENSIONS, sources.sumOf { it.titleCount }.toString()),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                strings.text(StringKey.THESE_TITLES_ARE_IN_YOUR_LIBRARY_ON),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sources.forEach { source -> WaitingSourceRow(source) }
            TextButton(onClick = onOpenDetails, modifier = Modifier.align(Alignment.End)) {
                Text(strings.text(StringKey.SYNC_DETAILS))
            }
        }
    }
}

@Composable
private fun WaitingSourceRow(source: SyncMissingSource) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            source.name + " · " + strings.text(StringKey.X_TITLES, source.titleCount.toString()),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (source.titles.isNotEmpty()) {
            val named = source.titles.take(NAMED_TITLES_PER_SOURCE).joinToString(" · ")
            Text(
                if (source.titles.size > NAMED_TITLES_PER_SOURCE) "$named…" else named,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            source.repositoryUrl?.let { strings.text(StringKey.FROM_REPOSITORY_X, it) }
                ?: strings.text(StringKey.PACKAGE_X, source.packageId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
