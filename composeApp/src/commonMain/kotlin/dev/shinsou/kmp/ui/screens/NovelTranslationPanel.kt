package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.GlossaryEntry
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationDisplayMode
import dev.shinsou.kmp.translation.TranslationException
import dev.shinsou.kmp.translation.TranslationFailure
import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.translation.WorkTranslationSettings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Translation of the chapter on screen: what the reader shows and what the panel edits. */
@Stable
internal class NovelTranslationState(
    private val translation: ReaderTranslation,
    private val workKey: String,
    private val scope: CoroutineScope,
    detectedLanguage: String,
    targetLanguage: String,
) {
    var settings by mutableStateOf(WorkTranslationSettings(enabled = false, from = detectedLanguage, to = targetLanguage))
        private set
    var loaded by mutableStateOf(false)
    var result by mutableStateOf<ChapterTranslation?>(null)
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var failure by mutableStateOf<TranslationFailure?>(null)
    var missingBytes by mutableStateOf(0L)
    var download by mutableStateOf<Pair<Long, Long>?>(null)
    var downloadFailed by mutableStateOf(false)
    var glossary by mutableStateOf<List<GlossaryEntry>>(emptyList())

    /** The chapter [result] belongs to. */
    var shownFor: Pair<String, Int>? = null

    /** Bumped when something the translation depends on changed (models installed, glossary edited, retry). */
    var revision by mutableIntStateOf(0)
        private set

    val languages get() = translation.languages

    /** Where this chapter's text goes; shown in the panel. */
    var provider by mutableStateOf(TranslationProviderKind.OFFLINE)

    fun restore(stored: WorkTranslationSettings?) {
        stored?.let { settings = it }
        loaded = true
    }

    fun change(next: WorkTranslationSettings) {
        if (next == settings) return
        settings = next
        scope.launch { translation.saveSettings(workKey, next) }
    }

    fun retry() {
        revision++
    }

    fun downloadModels() {
        if (download != null) return
        val from = settings.from
        val to = settings.to
        download = 0L to missingBytes.coerceAtLeast(1L)
        downloadFailed = false
        scope.launch {
            try {
                translation.downloadModels(from, to) { received, total -> download = received to total.coerceAtLeast(1L) }
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                downloadFailed = true
            } finally {
                download = null
            }
        }
    }

    fun putTerm(source: String, target: String) {
        scope.launch {
            runCatching { translation.putGlossaryTerm(workKey, settings.to, source, target) }
            glossary = translation.glossary(workKey, settings.to)
            revision++
        }
    }

    fun removeTerm(id: Int) {
        scope.launch {
            translation.removeGlossaryTerm(workKey, settings.to, id)
            glossary = translation.glossary(workKey, settings.to)
            revision++
        }
    }
}

/**
 * Loads the work's translation settings and keeps the chapter translated while they are on.
 * Null when this build or device has no translation.
 */
@Composable
internal fun rememberNovelTranslation(
    translation: ReaderTranslation?,
    workKey: String,
    chapterKey: String,
    canonicalText: String,
    /** Whether this chapter's grant allows export; its translation may then go into backups. */
    exportable: suspend () -> Boolean = { false },
    /** The work and chapter as the reader names them; listed with the stored translation. */
    labels: dev.shinsou.kmp.translation.TranslationLabels? = null,
): NovelTranslationState? {
    if (translation == null) return null
    val scope = rememberCoroutineScope()
    val systemLanguage = Locale.current.toLanguageTag()
    val state = remember(translation, workKey) {
        NovelTranslationState(
            translation = translation,
            workKey = workKey,
            scope = scope,
            detectedLanguage = ReaderTranslation.detectLanguage(canonicalText),
            targetLanguage = ReaderTranslation.targetLanguage(systemLanguage),
        )
    }
    LaunchedEffect(state) { state.restore(translation.settings(workKey)) }
    DisposableEffect(translation) { onDispose { translation.release() } }

    val settings = state.settings
    // Read when the chapter is translated, not a reason to translate it again.
    val latestLabels by androidx.compose.runtime.rememberUpdatedState(labels)
    LaunchedEffect(state, state.loaded, settings.enabled, settings.from, settings.to, chapterKey, canonicalText, state.revision) {
        state.progress = null
        state.failure = null
        if (!state.loaded || !settings.enabled) {
            state.result = null
            return@LaunchedEffect
        }
        state.glossary = translation.glossary(workKey, settings.to)
        state.provider = translation.activeProvider()
        if (!translation.supports(settings.from, settings.to)) {
            state.result = null
            state.failure = TranslationFailure.UNSUPPORTED_PAIR
            return@LaunchedEffect
        }
        // Another chapter's translation must never be shown over this one's text, not even
        // for the moment the stored lookup takes.
        val shownFor = chapterKey to canonicalText.hashCode()
        if (state.shownFor != shownFor) {
            state.result = null
            state.shownFor = shownFor
        }
        val request = ChapterTranslationRequest(
            workKey = workKey,
            chapterKey = chapterKey,
            text = canonicalText,
            from = settings.from,
            to = settings.to,
            // Illustration markers are not prose.
            keep = { line -> parseNovelReaderSegments(line).any { it is NovelReaderSegment.Image } },
            exportable = runCatching { exportable() }.getOrDefault(false),
            labels = latestLabels,
        )
        try {
            val stored = translation.stored(request)
            state.result = stored
            state.missingBytes = translation.missingModels(settings.from, settings.to).sumOf { it.downloadBytes }
            if (stored?.complete == true) return@LaunchedEffect
            // Only now: a chapter restored from a backup reads back on a device that cannot run the engine.
            if (state.provider == TranslationProviderKind.OFFLINE && !translation.deviceSupported) {
                state.failure = TranslationFailure.DEVICE_UNSUPPORTED
                return@LaunchedEffect
            }
            if (state.missingBytes > 0) {
                state.failure = TranslationFailure.MODEL_MISSING
                return@LaunchedEffect
            }
            state.progress = 0 to 1
            state.result = translation.translate(request) { done, total -> state.progress = done to total.coerceAtLeast(1) }
            state.progress = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TranslationException) {
            state.progress = null
            state.failure = error.failure
            // Whatever was finished before the failure is still worth showing.
            runCatching { translation.stored(request) }.getOrNull()?.let { state.result = it }
        } catch (_: Exception) {
            state.progress = null
            state.failure = TranslationFailure.ENGINE_FAILED
        }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelTranslationSheet(state: NovelTranslationState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.dismissKeyboardOnMobileBlankTap()) {
        NovelTranslationPanel(state)
    }
}

/**
 * What the translation is doing, shown over the page: without it a reader who switched
 * translation on cannot tell "working", "done" and "could not" apart. Problems stay until they
 * are dealt with; "translated" shows briefly. Tapping opens the panel.
 */
@Composable
internal fun NovelTranslationNotice(state: NovelTranslationState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalShinsouStrings.current
    if (!state.settings.enabled) return
    val download = state.download
    val progress = state.progress
    val failure = state.failure
    val complete = state.result?.complete == true
    var showDone by remember { mutableStateOf(false) }
    LaunchedEffect(complete, state.result) {
        if (!complete) {
            showDone = false
            return@LaunchedEffect
        }
        showDone = true
        kotlinx.coroutines.delay(3_000)
        showDone = false
    }
    val problem = failure != null && download == null
    val text = when {
        download != null -> strings.text("Downloading translation models… {0} of {1} MB", megabytes(download.first), megabytes(download.second))
        progress != null -> strings.text("Translating… {0} of {1} lines", progress.first, progress.second)
        failure != null -> strings.text("Not translated: {0}", translationFailureText(strings, state, failure))
        showDone -> strings.text("Translated")
        else -> return
    }
    Surface(
        onClick = onOpen,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier.padding(horizontal = 16.dp).testTag("translation-notice"),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** One short line for [failure]; the panel says more and offers what to do about it. */
private fun translationFailureText(
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
    state: NovelTranslationState,
    failure: TranslationFailure,
): String = when (failure) {
    TranslationFailure.MODEL_MISSING -> strings.text("translation models must be downloaded first ({0} MB). Tap to download.", megabytes(state.missingBytes))
    TranslationFailure.UNSUPPORTED_PAIR -> strings.text("these two languages are not supported. Tap to change them.")
    TranslationFailure.DEVICE_UNSUPPORTED -> strings.text("offline translation is not available on this device.")
    TranslationFailure.LOW_MEMORY -> strings.text("not enough free memory right now.")
    TranslationFailure.INVALID_KEY -> strings.text("the API key is missing or was refused.")
    TranslationFailure.QUOTA_EXCEEDED -> strings.text("the quota of the API key is used up.")
    TranslationFailure.DAILY_LIMIT -> strings.text("today's limit for paid translation is reached.")
    TranslationFailure.PROVIDER_UNAVAILABLE -> strings.text("the translation service could not be reached.")
    TranslationFailure.ENGINE_FAILED -> strings.text("the translation stopped. Tap to try again.")
    TranslationFailure.NOT_CONFIGURED -> strings.text("the AI model service needs its address and model first.")
    TranslationFailure.REFUSED -> strings.text("the model declined to translate this text.")
}

/** The panel's content, without the sheet around it. */
@Composable
internal fun NovelTranslationPanel(state: NovelTranslationState, modifier: Modifier = Modifier) {
    val strings = LocalShinsouStrings.current
    val settings = state.settings
    run {
        LazyColumn(
            modifier.fillMaxWidth().testTag("translation-panel"),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.text("Translation"), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            when (state.provider) {
                                TranslationProviderKind.OFFLINE ->
                                    strings.text("Machine translation on this device. Nothing you read is sent anywhere; expect mistakes.")
                                TranslationProviderKind.DEEPL ->
                                    strings.text("Translated by {0} with your API key. The text of this chapter is sent to {0}.", "DeepL")
                                TranslationProviderKind.GOOGLE ->
                                    strings.text("Translated by {0} with your API key. The text of this chapter is sent to {0}.", "Google")
                                TranslationProviderKind.AZURE ->
                                    strings.text("Translated by {0} with your API key. The text of this chapter is sent to {0}.", "Microsoft Azure")
                                TranslationProviderKind.LLM ->
                                    strings.text("Translated by the AI model you set up. The text of this chapter is sent to that service.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { state.change(settings.copy(enabled = it)) })
                }
            }
            item {
                Text(strings.text("Original language"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(state.languages, key = { it.tag }) { language ->
                        FilterChip(
                            selected = settings.from == language.tag,
                            onClick = { state.change(settings.copy(from = language.tag)) },
                            label = { Text(language.name) },
                        )
                    }
                }
            }
            item {
                Text(strings.text("Translate into"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(state.languages, key = { it.tag }) { language ->
                        FilterChip(
                            selected = settings.to == language.tag,
                            onClick = { state.change(settings.copy(to = language.tag)) },
                            label = { Text(language.name) },
                        )
                    }
                }
            }
            item {
                Text(strings.text("Show"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val modes = listOf(
                        TranslationDisplayMode.TRANSLATION to strings.text("Translation only"),
                        TranslationDisplayMode.BILINGUAL to strings.text("Original and translation"),
                        TranslationDisplayMode.ORIGINAL to strings.text("Original only"),
                    )
                    items(modes, key = { it.first.name }) { (mode, label) ->
                        FilterChip(
                            selected = settings.mode == mode,
                            onClick = { state.change(settings.copy(mode = mode)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Text(
                    strings.text("The translation service and API keys are in Settings → Translation."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.enabled) {
                item { NovelTranslationStatus(state) }
                item { HorizontalDivider() }
                item { NovelGlossaryEditor(state) }
                items(state.glossary, key = { it.id }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${entry.source}  →  ${entry.target}", Modifier.weight(1f))
                        IconButton(onClick = { state.removeTerm(entry.id) }) {
                            Icon(Icons.Outlined.Close, strings.text("Remove"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelTranslationStatus(state: NovelTranslationState) {
    val strings = LocalShinsouStrings.current
    val download = state.download
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            download != null -> {
                Text(strings.text("Downloading translation models… {0} of {1} MB", megabytes(download.first), megabytes(download.second)))
                LinearProgressIndicator(
                    progress = { (download.first.toFloat() / download.second.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.failure == TranslationFailure.MODEL_MISSING -> {
                Text(strings.text("This language pair needs translation models ({0} MB). They are downloaded once and kept on this device.", megabytes(state.missingBytes)))
                if (state.downloadFailed) {
                    Text(strings.text("The download failed. Check the connection and try again."), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = state::downloadModels) { Text(strings.text("Download models")) }
            }
            state.failure == TranslationFailure.UNSUPPORTED_PAIR -> Text(
                if (state.provider == TranslationProviderKind.OFFLINE) {
                    strings.text("Offline translation is not available between these two languages.")
                } else {
                    strings.text("This translation service cannot translate between these two languages.")
                },
                color = MaterialTheme.colorScheme.error,
            )
            state.failure == TranslationFailure.INVALID_KEY -> {
                Text(
                    strings.text("The API key is missing or was refused. Check it in Settings → Translation."),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure == TranslationFailure.QUOTA_EXCEEDED -> {
                Text(strings.text("The translation service reports that the quota of this API key is used up."), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure == TranslationFailure.NOT_CONFIGURED -> Text(
                strings.text("The AI model service is not set up yet. Enter its address and model in Settings → Translation."),
                color = MaterialTheme.colorScheme.error,
            )
            state.failure == TranslationFailure.REFUSED -> {
                Text(
                    strings.text("The model declined to translate this chapter under its provider's content rules. Another service or model may translate it."),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure == TranslationFailure.DAILY_LIMIT -> {
                Text(
                    strings.text("Today's limit for paid translation is reached. It can be raised in Settings → Translation."),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure == TranslationFailure.PROVIDER_UNAVAILABLE -> {
                Text(
                    strings.text("The translation service could not be reached. Check the connection and try again."),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure == TranslationFailure.DEVICE_UNSUPPORTED ->
                Text(strings.text("Offline translation is not available on this device."), color = MaterialTheme.colorScheme.error)
            state.failure == TranslationFailure.LOW_MEMORY -> {
                Text(
                    strings.text("Not enough free memory to translate right now. Close other apps and try again."),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            state.failure != null -> {
                Text(strings.text("The translation stopped before it finished."), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = state::retry) { Text(strings.retry) }
            }
            progress != null -> {
                Text(strings.text("Translating… {0} of {1} lines", progress.first, progress.second))
                LinearProgressIndicator(
                    progress = { (progress.first.toFloat() / progress.second.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.result?.complete == true -> Text(
                strings.text("This chapter is translated and kept on this device."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NovelGlossaryEditor(state: NovelTranslationState) {
    val strings = LocalShinsouStrings.current
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.text("Names and terms"), style = MaterialTheme.typography.titleMedium)
        Text(
            strings.text("Machine translation renders one name several ways. List a name as it appears in the original and how it should read; it is then kept the same in every chapter of this work."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = source,
                onValueChange = { source = it.take(64) },
                label = { Text(strings.text("In the original")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = target,
                onValueChange = { target = it.take(64) },
                label = { Text(strings.text("Shown as")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                state.putTerm(source, target)
                source = ""
                target = ""
            },
            enabled = source.isNotBlank() && target.isNotBlank(),
        ) { Text(strings.text("Add")) }
        Spacer(Modifier.height(2.dp))
    }
}

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()
