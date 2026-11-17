package dev.shinsou.kmp.ui.screens

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
import dev.shinsou.kmp.manga.MangaModelCatalog
import dev.shinsou.kmp.manga.MangaResource
import dev.shinsou.kmp.manga.MangaResourceKind
import dev.shinsou.kmp.manga.MangaTranslation
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything manga translation downloaded on this device ([MangaTranslation.resources]): what each
 * is for and takes, with its download or removal. [onChange] is told after either.
 */
@Composable
internal fun MangaResourceSettings(manga: MangaTranslation, onChange: () -> Unit = {}) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var resources by remember { mutableStateOf<List<MangaResource>?>(null) }
    val work = remember { mutableStateMapOf<String, Float?>() }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manga, revision) { resources = withContext(Dispatchers.Default) { manga.resources() } }
    val list = resources ?: return
    if (list.isEmpty()) return

    fun done() { revision++; onChange() }

    fun download(resource: MangaResource) {
        failure = null
        work[resource.id] = 0f
        scope.launch {
            runCatching {
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.text("Manga translation"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            strings.text("{0} on this device. A chapter that needs something removed here offers to download it again.", storedSize(list.sumOf { it.diskBytes })),
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
                                    Text(strings.text("Download {0} MB", megabytes(resource.missingBytes)))
                                }
                            }
                            if (resource.diskBytes > 0) {
                                TextButton(
                                    onClick = { scope.launch { manga.removeResource(resource); done() } },
                                    modifier = Modifier.testTag("manga-resource-remove-${resource.id}"),
                                ) { Text(strings.text("Remove")) }
                            }
                        }
                    }
                    if (running) {
                        val fraction = work[resource.id]
                        if (fraction == null) {
                            Text(strings.text("Preparing it for this device…"), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        } else {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        failure?.let {
            Text(strings.text("The download failed: {0}", it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun resourceTitle(resource: MangaResource, strings: ShinsouStrings): String = when (resource.kind) {
    MangaResourceKind.RECOGNITION -> strings.text("Manga recognition models")
    MangaResourceKind.INPAINT -> strings.text("Redrawing the artwork")
    MangaResourceKind.LANGUAGE -> when (resource.id) {
        MangaModelCatalog.OCR_LINES.id -> strings.text("Line finder for Chinese and English pages")
        MangaModelCatalog.OCR_ZH.id -> strings.text("Chinese page recogniser")
        else -> strings.text("English page recogniser")
    }
    MangaResourceKind.FONT -> strings.text("Simplified Chinese font")
    MangaResourceKind.ACCELERATOR -> strings.text("Detection on the {0}", resource.accelerators.joinToString(" / ") { it.name })
    MangaResourceKind.LEFTOVER -> strings.text("Left over from an older version")
}

private fun resourceNote(resource: MangaResource, strings: ShinsouStrings): String = when (resource.kind) {
    MangaResourceKind.RECOGNITION -> strings.text("Finds and reads the text on Japanese pages, and draws Traditional Chinese. Every manga translation needs it.")
    MangaResourceKind.INPAINT -> strings.text("Text drawn over the artwork is translated too.")
    MangaResourceKind.LANGUAGE -> strings.text("For manga in Chinese or English.")
    MangaResourceKind.FONT -> strings.text("For translations into Simplified Chinese.")
    MangaResourceKind.ACCELERATOR -> strings.text("Faster text detection. Downloading it also chooses it; removing it stops using it.")
    MangaResourceKind.LEFTOVER -> strings.text("No longer used: {0}", resource.id)
}

private fun resourceState(resource: MangaResource, strings: ShinsouStrings): String = when {
    resource.installed || resource.kind == MangaResourceKind.LEFTOVER -> storedSize(resource.diskBytes)
    resource.diskBytes > 0 -> strings.text("Partly downloaded · {0}", storedSize(resource.diskBytes))
    else -> strings.text("Not downloaded")
}

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()
