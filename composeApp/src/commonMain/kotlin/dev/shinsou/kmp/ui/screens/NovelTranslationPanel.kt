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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.GlossaryEntry
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationDisplayMode
import dev.shinsou.kmp.translation.TranslationException
import dev.shinsou.kmp.translation.ChineseScript
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

    /** The target saved for the work, kept when it starts by itself. */
    private var storedTo: String? = null

    /** Takes the work's own settings. */
    fun restore(stored: WorkTranslationSettings?) {
        if (stored != null) settings = stored
        storedTo = stored?.to
        loaded = true
    }

    /**
     * A work that is off and was never switched off starts translating by itself when the chapter
     * on screen is in [detected], a language translated automatically (see
     * [ReaderTranslation.autoTranslation]); saved only once the reader changes something. Until the
     * reader picks the work's language, it follows each chapter's, so a work opened on a page in
     * the reader's own language still starts on the first chapter that is not, and one that started
     * on a page read as another language is corrected by the next. [detected] is null for a chapter
     * with too little writing to tell (an illustration): nothing starts or changes on its account.
     * Only a chapter with writing enough to be sure of ([settled]) changes the language of a work
     * that is already translating.
     */
    suspend fun autoStart(detected: String?, appLanguage: String, settled: Boolean = true) {
        try {
            decideAutoStart(detected, appLanguage, settled)
        } finally {
            decidedFor = detected
        }
    }

    /** The language of the chapter [autoStart] last decided for, whether or not it started. */
    var decidedFor by mutableStateOf<String?>(null)
        private set

    /** Translating because [autoStart] began it, with nothing changed by the reader since. */
    private var startedByItself = false

    private suspend fun decideAutoStart(detected: String?, appLanguage: String, settled: Boolean) {
        val now = settings
        val from = languageToDecideFor(now, detected, settled) ?: return
        // off while it is decided again for a chapter in another language than it started on
        if (from != now.from) settings = now.copy(from = from, enabled = false)
        val before = settings
        val pair = translation.autoTranslation(from, appLanguage)
        val to = pair?.let { translation.autoTarget(it, storedTo) }
        val current = settings
        // not when changed by the reader meanwhile: a language picked, the target, or switched on or off
        val starts = pair != null && to != null && current == before && !current.enabled && !current.turnedOff
        if (starts) settings = current.copy(enabled = true, from = pair.first, to = to)
        startedByItself = starts
    }

    /**
     * The language [autoStart] decides for now, null when there is nothing to decide: not loaded,
     * switched on or off by the reader, no language to go by, already translating from it, or
     * translating and this chapter too short to change that.
     */
    private fun languageToDecideFor(now: WorkTranslationSettings, detected: String?, settled: Boolean): String? {
        val follows = startedByItself && now.enabled && !now.fromPicked
        val open = loaded && (follows || WorkTranslationSettings.startsByItself(now))
        val from = if (now.fromPicked) now.from else detected
        val kept = follows && (from == now.from || !settled)
        return from.takeIf { open && !kept }
    }

    fun change(change: WorkTranslationSettings) {
        val switchedOff = if (settings.enabled && !change.enabled) change.copy(turnedOff = true) else change
        // a language the reader picks is theirs from then on, not the one each chapter is detected in
        val next = if (switchedOff.from != settings.from) switchedOff.copy(fromPicked = true) else switchedOff
        if (next == settings) return
        startedByItself = false
        settings = next
        // the target saved for the work from then on, kept when it starts by itself
        storedTo = next.to
        scope.launch { translation.saveSettings(workKey, next) }
    }

    fun retry() {
        revision++
    }

    /** Shows the system's own prompt to download the pair's languages, then tries again. */
    fun downloadSystemLanguages() {
        if (download != null) return
        val from = settings.from
        val to = settings.to
        downloadFailed = false
        scope.launch {
            val installed = runCatchingCancellable { translation.downloadSystemLanguages(from, to) }.getOrDefault(false)
            if (installed) revision++ else downloadFailed = true
        }
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
            runCatchingCancellable { translation.putGlossaryTerm(workKey, settings.to, source, target) }
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
    val appLanguage = dev.shinsou.kmp.ui.i18n.LocalAppLanguage.current
    val state = remember(translation, workKey) {
        NovelTranslationState(
            translation = translation,
            workKey = workKey,
            scope = scope,
            detectedLanguage = ReaderTranslation.detectLanguage(canonicalText),
            targetLanguage = ReaderTranslation.targetLanguage(appLanguage),
        )
    }
    LaunchedEffect(state) { state.restore(translation.settings(workKey)) }
    // each chapter's language, until the reader picks the work's or it starts; a target picked
    // for the work while it was off is kept; a language picked by hand may let it start
    LaunchedEffect(state, state.loaded, chapterKey, canonicalText, state.settings.takeIf { it.fromPicked }?.from) {
        state.autoStart(
            ReaderTranslation.detectLanguageOrNull(canonicalText), appLanguage,
            settled = ReaderTranslation.detectLanguageOrNull(canonicalText, ReaderTranslation.SETTLED_LETTERS) != null,
        )
    }
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
            exportable = runCatchingCancellable { exportable() }.getOrDefault(false),
            labels = latestLabels,
        )
        try {
            val stored = translation.stored(request)
            state.result = stored
            state.missingBytes = translation.missingModels(settings.from, settings.to).sumOf { it.downloadBytes }
            if (stored?.complete == true) return@LaunchedEffect
            // Only now: a chapter restored from a backup reads back on a device that cannot run the engine.
            if (state.provider == TranslationProviderKind.OFFLINE && !ChineseScript.converts(settings.from, settings.to) && !translation.deviceSupported) {
                state.failure = TranslationFailure.DEVICE_UNSUPPORTED
                return@LaunchedEffect
            }
            if (state.missingBytes > 0) {
                state.failure = TranslationFailure.MODEL_MISSING
                return@LaunchedEffect
            }
            if (translation.systemLanguagesMissing(settings.from, settings.to)) {
                state.failure = TranslationFailure.SYSTEM_LANGUAGE_MISSING
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
            runCatchingCancellable { translation.stored(request) }.getOrNull()?.let { state.result = it }
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
        // a status that goes away when the translation is done: it must not hold the keyboard
        // focus, or the reader's keys stop when it goes (desktop and iPadOS)
        modifier = modifier.padding(horizontal = 16.dp).testTag("translation-notice")
            .focusProperties { canFocus = false },
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
    TranslationFailure.RATE_LIMITED -> strings.text("the translation service asked to slow down. Try again in a moment.")
    TranslationFailure.DAILY_LIMIT -> strings.text("today's limit for paid translation is reached.")
    TranslationFailure.PROVIDER_UNAVAILABLE -> strings.text("the translation service could not be reached.")
    TranslationFailure.ENGINE_FAILED -> strings.text("the translation stopped. Tap to try again.")
    TranslationFailure.NOT_CONFIGURED -> strings.text("the AI model service needs its address and model first.")
    TranslationFailure.REFUSED -> strings.text("the model declined to translate this text.")
    TranslationFailure.SYSTEM_LANGUAGE_MISSING -> strings.text("the system's languages must be downloaded first. Tap to download.")
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
                            if (ChineseScript.converts(settings.from, settings.to)) {
                                strings.text("Converted between Simplified and Traditional Chinese on this device, word by word. Nothing you read is sent anywhere.")
                            } else when (state.provider) {
                                TranslationProviderKind.OFFLINE ->
                                    strings.text("Machine translation on this device. Nothing you read is sent anywhere; expect mistakes.")
                                TranslationProviderKind.SYSTEM ->
                                    strings.text("Translated by the system's own translation on this device. Nothing you read is sent anywhere.")
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
                            onClick = { state.change(settings.copy(from = language.tag, fromPicked = true)) },
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
            state.failure == TranslationFailure.SYSTEM_LANGUAGE_MISSING -> {
                Text(strings.text("The system translation needs these languages downloaded first. They are kept by the system and can be used by other apps too."))
                if (state.downloadFailed) {
                    Text(strings.text("The languages were not downloaded. Try again, or download them in the system Settings."), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = state::downloadSystemLanguages) { Text(strings.text("Download languages")) }
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
            state.failure == TranslationFailure.RATE_LIMITED -> {
                Text(
                    strings.text("The translation service is receiving too many requests from this key. Wait a moment and try again."),
                    color = MaterialTheme.colorScheme.error,
                )
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
