package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import dev.shinsou.kmp.manga.MangaAccelerator
import dev.shinsou.kmp.manga.MangaCpuMode
import dev.shinsou.kmp.manga.MangaTranslationFlow
import dev.shinsou.kmp.manga.MangaTranslationService
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.authoredOrExternal
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch

/** A download in progress: its share done, or null while the NPU is being readied. */
private class AcceleratorWork(val fraction: Float?)

/**
 * Where manga text detection runs (docs/research/manga-gpu-npu.md): the CPU, GPU and NPU of this
 * device, each with how it compares and what it needs downloaded. Several may be chosen; they then
 * share a chapter's pages. Recognition, translation and drawing stay on the CPU.
 */
@Composable
internal fun MangaAcceleratorSettings(manga: MangaTranslationService) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var states by remember { mutableStateOf(emptyList<MangaTranslationService.AcceleratorState>()) }
    val work = remember { mutableStateMapOf<MangaAccelerator, AcceleratorWork>() }
    var failure by remember { mutableStateOf<String?>(null) }
    var cpuMode by remember { mutableStateOf<MangaCpuMode?>(null) }
    var flow by remember { mutableStateOf<MangaTranslationFlow?>(null) }
    LaunchedEffect(manga, revision) { states = manga.acceleratorStates(); cpuMode = manga.cpuMode(); flow = manga.translationFlow() }
    if (states.isEmpty()) return
    val chosen = states.filter { it.selected && it.installed }.map { it.support.accelerator }.toSet()

    fun choose(accelerator: MangaAccelerator, on: Boolean) {
        val next = if (on) chosen + accelerator else chosen - accelerator
        if (next.isEmpty()) return // at least one
        scope.launch { manga.selectAccelerators(next); revision++ }
    }

    fun download(accelerator: MangaAccelerator) {
        failure = null
        work[accelerator] = AcceleratorWork(0f)
        scope.launch {
            runCatchingCancellable {
                manga.downloadAccelerator(
                    accelerator,
                    onProgress = { received, total -> work[accelerator] = AcceleratorWork(if (total > 0) received.toFloat() / total else 0f) },
                    onPreparing = { work[accelerator] = AcceleratorWork(null) },
                )
                // downloading one is choosing it
                manga.selectAccelerators(chosen + accelerator)
            }.onFailure { failure = it.message ?: it::class.simpleName }
            work.remove(accelerator)
            revision++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.text(StringKey.CHOOSE_WHERE_MANGA_TEXT_IS_FOUND_ON),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (state in states) {
            val a = state.support.accelerator
            val running = work[a]
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("manga-accelerator-${a.name.lowercase()}"),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = a in chosen,
                            onCheckedChange = { choose(a, it) },
                            enabled = state.installed && running == null && !(a in chosen && chosen.size == 1),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(label(a), style = MaterialTheme.typography.titleMedium)
                            val reason = state.support.unsupportedReason
                            val lines = if (reason != null) listOf(strings.authoredOrExternal(reason)) else state.support.notes.map(strings::authoredOrExternal)
                            for (line in lines) {
                                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            !state.support.supported || running != null -> Unit
                            !state.installed -> OutlinedButton(onClick = { download(a) }) {
                                Text(strings.text(StringKey.DOWNLOAD_X_MB, megabytes(state.missingBytes)))
                            }
                            state.support.packs.isNotEmpty() || state.support.runtime != null ->
                                TextButton(onClick = { scope.launch { manga.removeAccelerator(a); revision++ } }) { Text(strings.text(StringKey.REMOVE)) }
                        }
                    }
                    if (running != null) {
                        val fraction = running.fraction
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
        flow?.let { current ->
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("manga-translation-flow"),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(strings.text(StringKey.WHILE_READING), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        for (option in MangaTranslationFlow.entries) {
                            FilterChip(
                                selected = option == current,
                                onClick = { flow = option; scope.launch { manga.setTranslationFlow(option); revision++ } },
                                label = { Text(strings.text(flowLabel(option))) },
                                modifier = Modifier.testTag("manga-translation-flow-${option.name.lowercase()}"),
                            )
                        }
                    }
                    Text(strings.text(flowNote(current)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        cpuMode?.let { mode ->
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("manga-cpu-mode"),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(strings.text(StringKey.CPU_PERFORMANCE), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.text(StringKey.FOR_TEXT_RECOGNITION_AND_DETECTION_AND_REDRAWING),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        for (option in MangaCpuMode.entries) {
                            FilterChip(
                                selected = option == mode,
                                onClick = { cpuMode = option; scope.launch { manga.setCpuMode(option); revision++ } },
                                label = { Text(strings.text(cpuModeLabel(option))) },
                                modifier = Modifier.testTag("manga-cpu-mode-${option.name.lowercase()}"),
                            )
                        }
                    }
                    Text(strings.text(cpuModeNote(mode)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun flowLabel(flow: MangaTranslationFlow): StringKey = when (flow) {
    MangaTranslationFlow.CHAPTER -> StringKey.WHOLE_CHAPTER
    MangaTranslationFlow.PROGRESSIVE -> StringKey.PAGE_BY_PAGE
}

private fun flowNote(flow: MangaTranslationFlow): StringKey = when (flow) {
    MangaTranslationFlow.CHAPTER ->
        StringKey.EVERY_PAGE_IS_READ_FIRST_THEN_THE
    MangaTranslationFlow.PROGRESSIVE ->
        StringKey.PAGES_ARE_READ_TRANSLATED_AND_DRAWN_IN
}

private fun cpuModeLabel(mode: MangaCpuMode): StringKey = when (mode) {
    MangaCpuMode.SAVER -> StringKey.POWER_SAVER
    MangaCpuMode.BALANCED -> StringKey.BALANCED
    MangaCpuMode.FULL -> StringKey.FULL_SPEED
}

private fun cpuModeNote(mode: MangaCpuMode): StringKey = when (mode) {
    MangaCpuMode.SAVER -> StringKey.ONE_CORE_AN_EFFICIENCY_CORE_WHERE_THE
    MangaCpuMode.BALANCED -> StringKey.A_STANDARD_LOAD_ON_ABOUT_HALF_THE
    MangaCpuMode.FULL -> StringKey.EVERY_CORE_AT_FULL_SPEED_FASTEST_USES
}

private fun label(accelerator: MangaAccelerator): String = when (accelerator) {
    MangaAccelerator.CPU -> "CPU"
    MangaAccelerator.GPU -> "GPU"
    MangaAccelerator.NPU -> "NPU"
}

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()
