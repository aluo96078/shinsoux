package dev.shinsou.kmp.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.manga.MangaModelCatalog
import dev.shinsou.kmp.manga.MangaResource
import dev.shinsou.kmp.manga.MangaResourceKind
import dev.shinsou.kmp.manga.MangaTranslationService
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything manga translation downloaded on this device ([MangaTranslationService.resources]): what each
 * is for and takes, with its download or removal. [onChange] is told after either.
 */
@Composable
internal fun MangaResourceSettings(manga: MangaTranslationService, workDispatcher: CoroutineDispatcher = Dispatchers.Default, onChange: () -> Unit = {}) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var resources by remember { mutableStateOf<List<MangaResource>?>(null) }
    val work = remember { mutableStateMapOf<String, Float?>() }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manga, revision) { resources = withContext(workDispatcher) { manga.resources() } }
    val list = resources ?: return
    if (list.isEmpty()) return

    fun done() { revision++; onChange() }

    fun download(resource: MangaResource) {
        failure = null
        work[resource.id] = 0f
        scope.launch {
            runCatchingCancellable {
                manga.downloadResource(
                    resource,
                    onProgress = { received, total -> work[resource.id] = if (total > 0) received.toFloat() / total else 0f },
                    onPreparing = { work[resource.id] = null },
                )
            }.onFailure { failure = it.message ?: it::class.simpleName }
            work.remove(resource.id)
            done()
        }
    }

    /** Removing waits for a chapter being translated to end, and a file in use can refuse to go. */
    fun remove(resource: MangaResource) {
        failure = null
        work[resource.id] = null
        scope.launch {
            runCatchingCancellable { manga.removeResource(resource) }
                .onFailure { failure = it.message ?: it::class.simpleName }
            work.remove(resource.id)
            done()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.text(StringKey.MANGA_TRANSLATION),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            strings.text(StringKey.X_ON_THIS_DEVICE_A_CHAPTER_THAT, storedSize(list.sumOf { it.diskBytes })),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (resource in list) {
            val running = resource.id in work
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("manga-resource-${resource.id}"),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(resourceTitle(resource, strings), style = MaterialTheme.typography.titleMedium)
                            Text(
                                resourceNote(resource, strings),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                resourceState(resource, strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!running) {
                            if (resource.missingBytes > 0 && resource.kind != MangaResourceKind.LEFTOVER) {
                                OutlinedButton(onClick = { download(resource) }) {
                                    Text(strings.text(StringKey.DOWNLOAD_X_MB, megabytes(resource.missingBytes)))
                                }
                            }
                            if (resource.diskBytes > 0) {
                                TextButton(
                                    onClick = { remove(resource) },
                                    modifier = Modifier.testTag("manga-resource-remove-${resource.id}"),
                                ) { Text(strings.text(StringKey.REMOVE)) }
                            }
                        }
                    }
                    if (running) {
                        val fraction = work[resource.id]
                        if (fraction == null) {
                            Text(strings.text(StringKey.PREPARING_IT_FOR_THIS_DEVICE), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        } else {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        failure?.let {
            Text(strings.text(StringKey.THE_DOWNLOAD_FAILED_X, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun resourceTitle(resource: MangaResource, strings: ShinsouStrings): String = when (resource.kind) {
    MangaResourceKind.RECOGNITION -> strings.text(StringKey.MANGA_RECOGNITION_MODELS)
    MangaResourceKind.INPAINT -> strings.text(StringKey.REDRAWING_THE_ARTWORK)
    MangaResourceKind.LANGUAGE -> when (resource.id) {
        MangaModelCatalog.OCR_LINES.id -> strings.text(StringKey.LINE_FINDER_FOR_CHINESE_AND_ENGLISH_PAGES)
        MangaModelCatalog.OCR_ZH.id -> strings.text(StringKey.CHINESE_PAGE_RECOGNISER)
        else -> strings.text(StringKey.ENGLISH_PAGE_RECOGNISER)
    }
    MangaResourceKind.FONT -> strings.text(StringKey.SIMPLIFIED_CHINESE_FONT)
    MangaResourceKind.ACCELERATOR -> strings.text(StringKey.DETECTION_ON_THE_X, resource.accelerators.joinToString(" / ") { it.name })
    MangaResourceKind.LEFTOVER -> strings.text(StringKey.LEFT_OVER_FROM_AN_OLDER_VERSION)
}

private fun resourceNote(resource: MangaResource, strings: ShinsouStrings): String = when (resource.kind) {
    MangaResourceKind.RECOGNITION -> strings.text(StringKey.FINDS_AND_READS_THE_TEXT_ON_JAPANESE)
    MangaResourceKind.INPAINT -> strings.text(StringKey.TEXT_DRAWN_OVER_THE_ARTWORK_IS_TRANSLATED)
    MangaResourceKind.LANGUAGE -> strings.text(StringKey.FOR_MANGA_IN_CHINESE_OR_ENGLISH)
    MangaResourceKind.FONT -> strings.text(StringKey.FOR_TRANSLATIONS_INTO_SIMPLIFIED_CHINESE)
    MangaResourceKind.ACCELERATOR -> strings.text(StringKey.FASTER_TEXT_DETECTION_DOWNLOADING_IT_ALSO_CHOOSES)
    MangaResourceKind.LEFTOVER -> strings.text(StringKey.NO_LONGER_USED_X, resource.id)
}

private fun resourceState(resource: MangaResource, strings: ShinsouStrings): String = when {
    resource.installed || resource.kind == MangaResourceKind.LEFTOVER -> storedSize(resource.diskBytes)
    resource.diskBytes > 0 -> strings.text(StringKey.PARTLY_DOWNLOADED_X, storedSize(resource.diskBytes))
    else -> strings.text(StringKey.NOT_DOWNLOADED)
}

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()
