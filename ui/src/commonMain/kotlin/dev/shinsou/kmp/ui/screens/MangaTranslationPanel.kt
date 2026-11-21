package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.manga.MangaChapterRequest
import dev.shinsou.kmp.manga.MangaLanguages
import dev.shinsou.kmp.translation.ChineseScript
import dev.shinsou.kmp.translation.TranslationFailure
import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.manga.MangaOrientation
import dev.shinsou.kmp.manga.MangaProgress
import dev.shinsou.kmp.manga.MangaTranslationService
import dev.shinsou.kmp.translation.OfflineModelCatalog
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationDisplayMode
import dev.shinsou.kmp.translation.TranslationException
import dev.shinsou.kmp.translation.TranslationLabels
import dev.shinsou.kmp.translation.WorkTranslationSettings
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Manga translation in the page reader (ADR-0005): the work's settings, the model download, the
 * chapter's translated pages and where the translation stands. The reader shows [displayPages].
 */
@Stable
public class MangaReaderTranslationState internal constructor(
    private val manga: MangaTranslationService,
    private val translation: ReaderTranslation,
    private val workKey: String,
) {
    public var settings: WorkTranslationSettings? by mutableStateOf(null)
        internal set
    public var orientation: MangaOrientation by mutableStateOf(MangaOrientation.VERTICAL)
    public var progress: MangaProgress? by mutableStateOf(null)
        internal set
    public var failure: String? by mutableStateOf(null)
        internal set
    public var download: Pair<Long, Long>? by mutableStateOf(null)
        internal set
    public var modelsInstalled: Boolean by mutableStateOf(manga.modelsInstalled())
        internal set
    public var finished: Boolean by mutableStateOf(false)
        internal set
    /** The optional LaMa pack: text drawn over the artwork is translated too. */
    public var inpaintInstalled: Boolean by mutableStateOf(manga.inpaintInstalled())
        internal set
    public var inpaintDownload: Pair<Long, Long>? by mutableStateOf(null)
        internal set
    /** The offline engine's models this language pair still needs, in bytes (0 when none). */
    public var textModelBytes: Long by mutableStateOf(0L)
        internal set
    public var textModelDownload: Pair<Long, Long>? by mutableStateOf(null)
        internal set
    public var textModelDownloadFailed: Boolean by mutableStateOf(false)
        internal set
    /** The system translation (Apple's, on iOS) needs the pair's languages downloaded first. */
    public var systemLanguagesMissing: Boolean by mutableStateOf(false)
        internal set
    public var systemLanguagesDownloading: Boolean by mutableStateOf(false)
        internal set
    /** What reading the work's language and drawing its translation still need, in bytes (0 when none). */
    public var languagePackBytes: Long by mutableStateOf(0L)
        internal set
    public var languagePackDownload: Pair<Long, Long>? by mutableStateOf(null)
        internal set
    internal var runSerial by mutableStateOf(0)
    /** Counts the language packs downloaded: a Chinese work's script can be told from its pages after one. */
    internal var languagePackSerial by mutableStateOf(0)
    internal val translated = mutableStateMapOf<Int, ByteArray>()

    public val deviceSupported: Boolean get() = manga.deviceSupported
    public val missingModelBytes: Long get() = manga.missingModelBytes()
    public val missingInpaintBytes: Long get() = manga.missingInpaintBytes()
    /** How this device shares the models, and how the last chapter went (developer diagnostics). */
    public val tuning: dev.shinsou.kmp.manga.MangaTuning? get() = manga.deviceTuning
    public var lastRun: dev.shinsou.kmp.manga.MangaRunStats? by mutableStateOf(null)
        internal set
    public val languages: List<dev.shinsou.kmp.translation.TranslationLanguage> get() = translation.languages

    /** True while the chapter's pages are being read, translated or drawn. */
    public val working: Boolean get() = progress != null && !finished && failure == null

    /** Page [key]'s translated image while the translation is on and shown; null shows the source. */
    public fun translatedPage(key: Int): ByteArray? {
        val s = settings ?: return null
        if (!s.enabled || s.mode == TranslationDisplayMode.ORIGINAL) return null
        return translated[key]
    }

    /** The pages to show: translated ones in place of the source while the translation is on. */
    public fun displayPages(pages: List<ReaderPage>): List<ReaderPage> {
        val s = settings ?: return pages
        if (!s.enabled || s.mode == TranslationDisplayMode.ORIGINAL || translated.isEmpty()) return pages
        return pages.map { page ->
            translated[page.index]?.let { bytes ->
                page.copy(imageBytes = bytes, imageUrl = "", local = false, imageTransform = null, imageNaturalHeight = null, imageResolver = null)
            } ?: page
        }
    }

    /** The work was never switched off, and may still start translating by itself. */
    private var autoPending = false

    /** A language the reader picked for the work's pages while it was off; kept when it starts by itself. */
    private var pickedFrom: String? = null

    /** The target saved for the work or picked by the reader, not the default [load] fills in. */
    private var storedTo: String? = null

    internal suspend fun load(appLanguage: String) {
        val stored = translation.settings(workKey)
        autoPending = WorkTranslationSettings.startsByItself(stored)
        pickedFrom = MangaReaderTranslationState.pickedFrom(stored)
        storedTo = stored?.to
        settings = stored?.let { if (MangaLanguages.readable(it.from)) it else it.copy(from = SOURCE_LANGUAGE) }
            ?: WorkTranslationSettings(enabled = false, from = SOURCE_LANGUAGE, to = targetFor(SOURCE_LANGUAGE, appLanguage))
    }

    /**
     * Starts translating a work that was never switched off when its language is one pages can be
     * read in ([MangaLanguages]) and is translated automatically (see [ReaderTranslation.autoTranslation]);
     * a target language picked meanwhile is kept, and nothing is saved until the reader changes something. [workLanguage] is what its source says,
     * blank when it does not: its first pages are read then, once the models are there, which tells
     * Japanese only. A source that says Chinese but not the script leaves the work with the script
     * its pages are read in ([readsChineseScript]), and nothing starts while it is not known: for a
     * reader of Chinese the other one is converted and the reader's own left as it is; for any
     * other reader it is translated by that script's model.
     *
     * Whether or not it starts, a work that is off and whose language the reader has not picked
     * takes the one its source says, so turning it on reads its pages in that language. A Chinese
     * one whose script cannot be told yet (the Chinese recogniser is not downloaded) is taken to be
     * in the script the reader does not use, so turning it on offers that download; once the
     * script is told ([languagePackSerial]), the work takes it, on or off, and is left off when it
     * is the reader's own.
     */
    internal suspend fun autoStart(
        workLanguage: String,
        appLanguage: String,
        readsJapanese: suspend () -> Boolean?,
        readsChineseScript: suspend () -> String? = { null },
    ) {
        val picked = pickedFrom
        val known = picked != null || workLanguage.isNotBlank()
        val target = ReaderTranslation.targetLanguage(appLanguage)
        // the script decides the model translating it too, not only whether a reader of Chinese needs it
        val scriptUnnamed = ChineseScript.unspecified(workLanguage)
        // the script of a Chinese work that is on may only have been taken until its pages could tell
        val retains = picked == null && workLanguage.isNotBlank() && (settings?.enabled == false || scriptUnnamed)
        if (!autoPending && !retains) return
        val named = when {
            picked != null -> picked
            !known -> null
            scriptUnnamed -> {
                // only worth reading the pages when the other script would be converted by itself, or kept;
                // for a reader of another language, when the script it is taken to be in would be translated
                val other = when (target) {
                    ChineseScript.TRADITIONAL -> ChineseScript.SIMPLIFIED
                    ChineseScript.SIMPLIFIED -> ChineseScript.TRADITIONAL
                    else -> OfflineModelCatalog.language(workLanguage) ?: ChineseScript.SIMPLIFIED
                }
                if (autoPending && translation.autoTranslation(other, appLanguage) == null) autoPending = false
                if (!autoPending && !retains) return
                readsChineseScript() ?: run {
                    settings?.let { now ->
                        if (retains && !now.enabled && pickedFrom == null && MangaLanguages.readable(other)) settings = now.copy(from = other, to = if (now.to == other) targetFor(other, appLanguage) else now.to)
                    }
                    return
                }
            }
            else -> OfflineModelCatalog.language(workLanguage)
        }?.takeIf(MangaLanguages::readable)
        // a language the reader picked while the pages were being read is theirs
        if (pickedFrom != picked) return
        if (retains && named != null) settings?.let { now ->
            if (pickedFrom != null || now.from == named) return@let
            if (!now.enabled) settings = now.copy(from = named, to = if (now.to == named) targetFor(named, appLanguage) else now.to)
            else {
                // on in a script it was only taken to be in: the one its pages are in, left off when it is the reader's own
                val next = if (named == now.to) now.copy(enabled = false, from = named, to = targetFor(named, appLanguage)) else now.copy(from = named)
                settings = next
                translation.saveSettings(workKey, next)
                retry()
            }
        }
        if (!autoPending) return
        val from = if (known) named else SOURCE_LANGUAGE
        // a language not known yet (its source not loaded) may still start once it is
        val pair = from?.let { translation.autoTranslation(it, appLanguage) }
            ?: run { if (known) autoPending = false; return }
        val before = settings
        val start = known || (readsJapanese() ?: return)
        // changed by hand meanwhile: a language picked, or the translation switched on or off
        if (!autoPending || settings != before || pickedFrom != picked) return
        // pages not read as Japanese while the source has not named the language: still waits for it
        if (!start) return
        autoPending = false
        val current = settings ?: return
        val to = translation.autoTarget(pair, storedTo)
        // changed by hand while the target was resolved: the reader's choice stands
        if (settings != current || pickedFrom != picked) return
        if (start && !current.enabled) {
            settings = current.copy(enabled = true, from = pair.first, to = to)
            translation.startedByItself(workKey, pair.first, to)
        }
    }

    public suspend fun update(change: WorkTranslationSettings) {
        val switchedOff = settings?.let { if (it.enabled && !change.enabled) change.copy(turnedOff = true) else change } ?: change
        // a language the reader picks is theirs from then on, kept over what the source says
        val next = if (switchedOff.from != settings?.from) switchedOff.copy(fromPicked = true) else switchedOff
        // a language or display picked while it is off still lets it start by itself
        // (a guess passed over before, such as the reader's own language, is reconsidered)
        autoPending = WorkTranslationSettings.startsByItself(next)
        if (next.fromPicked) pickedFrom = next.from
        if (next.to != settings?.to) storedTo = next.to
        val restart = next.enabled != settings?.enabled || next.to != settings?.to || next.from != settings?.from
        settings = next
        translation.saveSettings(workKey, next)
        if (restart) retry()
    }

    public fun retry() {
        failure = null; finished = false; runSerial++
    }

    public suspend fun downloadModels() {
        failure = null
        download = 0L to manga.missingModelBytes()
        try {
            manga.downloadModels { received, total -> download = received to total }
            modelsInstalled = manga.modelsInstalled()
            if (modelsInstalled) retry()
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Downloading the manga recognition models failed" }
            failure = logged.message ?: logged.toString()
        } finally {
            download = null
        }
    }

    /** Downloads what reading the work's language and drawing its translation need, then translates. */
    public suspend fun downloadLanguagePacks() {
        val s = settings ?: return
        failure = null
        languagePackDownload = 0L to languagePackBytes.coerceAtLeast(1L)
        try {
            manga.downloadLanguage(s.from, s.to) { received, total -> languagePackDownload = received to total.coerceAtLeast(1L) }
            languagePackBytes = manga.missingLanguageBytes(s.from, s.to)
            if (languagePackBytes == 0L) { languagePackSerial++; retry() }
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Downloading the manga language packs failed" }
            failure = logged.message ?: logged.toString()
        } finally {
            languagePackDownload = null
        }
    }

    /** Downloads the offline engine's models for the chosen pair, then translates the chapter. */
    public suspend fun downloadTextModels() {
        val s = settings ?: return
        textModelDownloadFailed = false
        textModelDownload = 0L to textModelBytes.coerceAtLeast(1L)
        try {
            translation.downloadModels(s.from, s.to) { received, total -> textModelDownload = received to total.coerceAtLeast(1L) }
            textModelBytes = translation.missingModels(s.from, s.to).sumOf { it.downloadBytes }
            if (textModelBytes == 0L) retry()
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Downloading the offline translation models failed" }
            textModelDownloadFailed = true
        } finally {
            textModelDownload = null
        }
    }

    /** Asks the system to download the pair's languages (it shows its own prompt), then translates. */
    public suspend fun downloadSystemLanguages() {
        val s = settings ?: return
        textModelDownloadFailed = false
        systemLanguagesDownloading = true
        try {
            val installed = runCatchingCancellable { translation.downloadSystemLanguages(s.from, s.to) }
                .onFailure { Log.warn("Manga", it) { "Downloading the system translation languages failed" } }
                .getOrDefault(false)
            if (installed && !translation.systemLanguagesMissing(s.from, s.to)) { systemLanguagesMissing = false; retry() } else textModelDownloadFailed = true
        } finally {
            systemLanguagesDownloading = false
        }
    }

    /** Downloads the LaMa pack and draws the chapter again with it (its reading is cached). */
    public suspend fun downloadInpaint() {
        failure = null
        inpaintDownload = 0L to manga.missingInpaintBytes()
        try {
            manga.downloadInpaint { received, total -> inpaintDownload = received to total }
            inpaintInstalled = manga.inpaintInstalled()
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Downloading the inpainting pack failed" }
            failure = logged.message ?: logged.toString()
        } finally {
            inpaintDownload = null
        }
    }

    public suspend fun removeInpaint() {
        manga.removeInpaint()
        inpaintInstalled = manga.inpaintInstalled()
    }

    internal companion object {
        /** The language a work is taken to be in until the reader chooses (manga-ocr reads Japanese, ADR-0005 §2). */
        const val SOURCE_LANGUAGE = "ja"

        /** The app's language to translate [from] into, or another when the work is already in it. */
        internal fun targetFor(from: String, appLanguage: String): String =
            listOf(ReaderTranslation.targetLanguage(appLanguage), "en", "zh-Hant").first { it != from }

        /**
         * The language the reader picked for a work's pages in its [stored] settings, Japanese
         * included; null while it is the one taken until they choose, or that the work was taken to be in.
         */
        fun pickedFrom(stored: WorkTranslationSettings?): String? =
            stored?.takeIf { it.fromPicked }?.from?.takeIf(MangaLanguages::readable)
    }
}

/**
 * Translates the chapter in the background while its settings ask for it, collecting each page
 * as it is drawn. Pages behind a scrambling transform, or that cannot be loaded, stay as they are.
 */
@Composable
public fun rememberMangaReaderTranslation(
    manga: MangaTranslationService,
    translation: ReaderTranslation,
    workKey: String,
    chapterKey: String,
    pages: List<ReaderPage>,
    exportable: Boolean,
    labels: TranslationLabels?,
    workLanguage: String? = null,
): MangaReaderTranslationState {
    val snapshot = remember(pages) { pages.sortedBy { it.index } }
    return rememberMangaReaderTranslation(
        manga, translation, workKey, chapterKey,
        pageCount = snapshot.size, pagesKey = pages,
        loadPage = { i -> loadPageBytes(snapshot[i]) },
        pageKey = { i -> snapshot[i].index },
        exportable = exportable, labels = labels, workLanguage = workLanguage,
    )
}

/**
 * The same for any image chapter: [loadPage] gives page n's encoded image (null leaves it as it
 * is), and translated pages are kept under [pageKey] of their position.
 */
@Composable
public fun rememberMangaReaderTranslation(
    manga: MangaTranslationService,
    translation: ReaderTranslation,
    workKey: String,
    chapterKey: String,
    pageCount: Int,
    /** Changes when the chapter's pages do; restarts the translation. */
    pagesKey: Any?,
    loadPage: suspend (Int) -> ByteArray?,
    pageKey: (Int) -> Int = { it },
    exportable: Boolean,
    labels: TranslationLabels?,
    /**
     * The work's language as its source gives it, blank when it does not say (its first pages are
     * then read to tell); a work with no settings of its own in Japanese starts translating by
     * itself. Null never starts one.
     */
    workLanguage: String? = null,
): MangaReaderTranslationState {
    val state = remember(manga, translation, workKey) { MangaReaderTranslationState(manga, translation, workKey) }
    val appLanguage = dev.shinsou.kmp.ui.i18n.LocalAppLanguage.current
    LaunchedEffect(state) { state.load(appLanguage) }
    if (workLanguage != null) {
        val currentLoadPage by androidx.compose.runtime.rememberUpdatedState(loadPage)
        // a recogniser that fails to load or run leaves the language unknown: nothing starts
        suspend fun <T> probe(read: suspend () -> T?): T? =
            if (pageCount == 0 || !state.modelsInstalled) null
            else try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (logged: Exception) {
                Log.warn("Manga", logged) { "A language probe on the chapter's pages failed" }
                null
            }
        // a chapter too short to tell the language by leaves it to the next one
        // a source language picked by hand may let a work whose pages told nothing start by itself
        LaunchedEffect(state, state.settings != null, chapterKey, pagesKey, pageCount > 0, state.modelsInstalled, workLanguage, state.languagePackSerial, state.settings?.takeIf { it.fromPicked }?.from) {
            if (state.settings == null) return@LaunchedEffect
            state.autoStart(
                workLanguage, appLanguage,
                readsJapanese = { probe { manga.readsJapanese(workKey, pageCount) { i -> currentLoadPage(i) } } },
                readsChineseScript = { probe { manga.readsChineseScript(workKey, pageCount) { i -> currentLoadPage(i) } } },
            )
        }
    }
    val settings = state.settings
    val incomplete = dev.shinsou.kmp.ui.i18n.LocalShinsouStrings.current.text(StringKey.SOME_LINES_OR_PAGES_WERE_LEFT_UNTRANSLATED)
    LaunchedEffect(state, chapterKey, pagesKey, pageCount, settings?.enabled, settings?.from, settings?.to, state.orientation, state.modelsInstalled, state.inpaintInstalled, state.runSerial) {
        state.translated.clear(); state.progress = null; state.finished = false; state.languagePackBytes = 0
        // what the pair before needed: found again below for this one
        state.textModelBytes = 0; state.systemLanguagesMissing = false
        if (settings == null || !settings.enabled || !state.deviceSupported || !state.modelsInstalled || pageCount == 0) return@LaunchedEffect
        state.failure = null
        val run = currentCoroutineContext().job
        val shown = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Main.immediate
        try {
            // The recogniser for the work's language (and the font for its translation) come first.
            state.languagePackBytes = manga.missingLanguageBytes(settings.from, settings.to)
            if (state.languagePackBytes > 0) return@LaunchedEffect
            // The offline engine's own models come next: shown with their size, downloaded on a tap.
            if (translation.activeProvider() == TranslationProviderKind.OFFLINE && !ChineseScript.converts(settings.from, settings.to)) {
                if (!translation.deviceSupported) throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "This device cannot run the offline engine")
                state.textModelBytes = translation.missingModels(settings.from, settings.to).sumOf { it.downloadBytes }
                if (state.textModelBytes > 0) return@LaunchedEffect
            } else {
                state.textModelBytes = 0
            }
            state.systemLanguagesMissing = translation.systemLanguagesMissing(settings.from, settings.to)
            if (state.systemLanguagesMissing) return@LaunchedEffect
            val complete = manga.translateChapter(
                MangaChapterRequest(
                    workKey = workKey, chapterKey = chapterKey, pageCount = pageCount,
                    loadPage = loadPage,
                    from = settings.from, to = settings.to, orientation = state.orientation,
                    direction = MangaLanguages.direction(settings.from),
                    exportable = exportable, labels = labels,
                ),
                onProgress = { state.progress = it },
            ) { i, jpeg ->
                // On the thread that clears the pages for the next chapter, and only while this is
                // still the chapter shown: a page drawn after the reader moved on is the old chapter's.
                if (jpeg != null) withContext(NonCancellable + shown) { if (run.isActive) state.translated[pageKey(i)] = jpeg }
            }
            state.lastRun = manga.lastRun
            // The pages drawn so far stay shown; the chapter is not done until every line is
            // translated (a provider can stop short, an LLM's answer be cut off), and says so.
            if (complete) state.finished = true else state.failure = incomplete
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            // A typed translation failure (model missing, quota, unsupported pair) is shown to the reader.
            state.failure = e.message ?: e.failure.name
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Translating a manga chapter failed" }
            state.failure = logged.message ?: logged.toString()
        }
    }
    return state
}

/**
 * The page's encoded image: its bytes, its resolved bytes, or its local file. Null for a page left
 * as it is on purpose (scrambled, or with nothing to read); throws when it should load and did not
 * (the network, a missing file), so the chapter is not taken as translated in full.
 */
internal suspend fun loadPageBytes(page: ReaderPage, workDispatcher: CoroutineDispatcher = Dispatchers.Default): ByteArray? {
    if (page.imageTransform != null) return null   // scrambled pages: the transform is applied at display time
    page.imageBytes?.let { return it }
    page.imageResolver?.let { resolve ->
        // A page resolved on demand (JM, online) carries its transform only once resolved: its
        // bytes are scrambled then, and are left to the reader, which descrambles them as shown.
        val resolved = resolve()
        return if (resolved.imageTransform != null) null
        else resolved.imageBytes ?: throw IllegalStateException("Page ${page.index} could not be loaded")
    }
    if (page.local && page.imageUrl.startsWith("file://")) {
        val path = page.imageUrl.removePrefix("file://").let { if (it.startsWith("/")) it else "/$it" }
        return withContext(workDispatcher) { FileSystem.SYSTEM.read(path.toPath()) { readByteArray() } }
    }
    return null
}

/** The chip over the page: where the translation stands; tapping it opens the panel. */
@Composable
public fun MangaTranslationNotice(state: MangaReaderTranslationState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalShinsouStrings.current
    val settings = state.settings ?: return
    if (!settings.enabled) return
    val text = when {
        state.languagePackBytes > 0 && state.failure == null ->
            strings.text(StringKey.READING_THIS_LANGUAGE_OR_DRAWING_ITS_TRANSLATION, (state.languagePackBytes + 999_999) / 1_000_000) +
                strings.text(StringKey.TAP_TO_DOWNLOAD)
        state.systemLanguagesMissing && state.failure == null -> strings.text(StringKey.THE_SYSTEM_S_LANGUAGES_MUST_BE_DOWNLOADED)
        state.textModelBytes > 0 && state.failure == null ->
            strings.text(StringKey.TRANSLATION_MODELS_MUST_BE_DOWNLOADED_FIRST_X, (state.textModelBytes + 999_999) / 1_000_000)
        state.failure != null -> strings.text(StringKey.MANGA_TRANSLATION_FAILED)
        !state.modelsInstalled -> strings.text(StringKey.DOWNLOAD_THE_MANGA_RECOGNITION_MODELS_TO_TRANSLATE)
        state.finished -> return
        else -> when (val p = state.progress) {
            is MangaProgress.Reading -> strings.text(StringKey.READING_PAGE_X_OF_X, p.page, p.pages)
            is MangaProgress.Loading -> strings.text(StringKey.LOADING_PAGE_X_OF_X, p.page, p.pages)
            is MangaProgress.Translating -> strings.text(StringKey.TRANSLATING_X_OF_X_LINES, p.done, p.total)
            is MangaProgress.Drawing -> strings.text(StringKey.DRAWING_PAGE_X_OF_X, p.page, p.pages)
            null -> strings.text(StringKey.TRANSLATING)
        }
    }
    Box(modifier.statusBarsPadding().padding(top = 56.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 2.dp,
            // a status that goes away when the translation is done: it must not hold the keyboard
            // focus, or the reader's keys stop when it goes (desktop and iPadOS)
            modifier = Modifier.focusProperties { canFocus = false }.clickable(onClick = onOpen),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MangaTranslationSheet(state: MangaReaderTranslationState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MangaTranslationPanel(state, Modifier.navigationBarsPadding())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun MangaTranslationPanel(state: MangaReaderTranslationState, modifier: Modifier = Modifier) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val settings = state.settings ?: return
    fun update(next: WorkTranslationSettings) { scope.launch { state.update(next) } }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MangaPanelHeader(settings.enabled, state.deviceSupported) { update(settings.copy(enabled = it)) }
        if (!state.deviceSupported) {
            Text(strings.text(StringKey.MANGA_TRANSLATION_IS_NOT_AVAILABLE_ON_THIS), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (!state.modelsInstalled) {
            val download = state.download
            Text(strings.text(StringKey.RECOGNISING_MANGA_TEXT_NEEDS_A_ONE_TIME, (state.missingModelBytes + 999_999) / 1_000_000))
            if (download != null) {
                LinearProgressIndicator(progress = { if (download.second > 0) download.first.toFloat() / download.second else 0f }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = { scope.launch { state.downloadModels() } }) { Text(strings.text(StringKey.DOWNLOAD)) }
            }
        }
        Text(strings.text(StringKey.ORIGINAL_LANGUAGE), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.languages.filter { MangaLanguages.readable(it.tag) }.forEach { language ->
                FilterChip(selected = settings.from == language.tag, onClick = {
                    // the target cannot be the source: it moves to the first other language
                    val to = settings.to.takeIf { it != language.tag } ?: state.languages.first { it.tag != language.tag }.tag
                    update(settings.copy(from = language.tag, to = to, fromPicked = true))
                }, label = { Text(language.name) })
            }
        }
        if (state.languagePackBytes > 0) {
            Text(strings.text(StringKey.READING_THIS_LANGUAGE_OR_DRAWING_ITS_TRANSLATION, (state.languagePackBytes + 999_999) / 1_000_000))
            val packDownload = state.languagePackDownload
            if (packDownload != null) {
                LinearProgressIndicator(progress = { packDownload.first.toFloat() / packDownload.second }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = { scope.launch { state.downloadLanguagePacks() } }) { Text(strings.text(StringKey.DOWNLOAD)) }
            }
        }
        Text(strings.text(StringKey.TRANSLATE_INTO), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.languages.filter { it.tag != settings.from }.forEach { language ->
                FilterChip(selected = settings.to == language.tag, onClick = { update(settings.copy(to = language.tag)) }, label = { Text(language.name) })
            }
        }
        Text(strings.text(StringKey.TEXT_DIRECTION), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                MangaOrientation.VERTICAL to StringKey.VERTICAL,
                MangaOrientation.PREFER_HORIZONTAL to StringKey.HORIZONTAL_WHERE_IT_FITS,
                MangaOrientation.AUTO to StringKey.FOLLOW_THE_ORIGINAL,
            ).forEach { (value, label) ->
                FilterChip(selected = state.orientation == value, onClick = { state.orientation = value }, label = { Text(strings.text(label)) })
            }
        }
        Text(strings.text(StringKey.SHOW), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TranslationDisplayMode.TRANSLATION to StringKey.TRANSLATION, TranslationDisplayMode.ORIGINAL to StringKey.ORIGINAL).forEach { (mode, label) ->
                FilterChip(selected = settings.mode == mode || (mode == TranslationDisplayMode.TRANSLATION && settings.mode == TranslationDisplayMode.BILINGUAL),
                    onClick = { update(settings.copy(mode = mode)) }, label = { Text(strings.text(label)) })
            }
        }
        MangaPanelTextModels(state, scope)
        Text(strings.text(StringKey.TEXT_DRAWN_OVER_THE_ARTWORK), style = MaterialTheme.typography.labelLarge)
        MangaPanelInpaint(state, scope)
        state.failure?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { state.retry() }) { Text(strings.text(StringKey.RETRY)) }
        }
        // developer diagnostics: how this device is used, and where the last chapter's time went
        MangaPanelDiagnostics(state)
        Text(
            strings.text(StringKey.THE_TRANSLATION_SERVICE_AND_ITS_KEYS_ARE) + " " +
                strings.text(StringKey.WHERE_TEXT_IS_DETECTED_CPU_GPU_OR),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The panel's title, what translating does, and the switch that turns it on for this work. */
@Composable
private fun MangaPanelHeader(enabled: Boolean, deviceSupported: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val strings = LocalShinsouStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(strings.text(StringKey.TRANSLATE_MANGA_PAGES), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text(StringKey.TEXT_ON_EACH_PAGE_IS_RECOGNISED_ON),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = deviceSupported)
    }
}

/** The translation-model and system-language downloads the chosen language pair needs. */
@Composable
private fun MangaPanelTextModels(state: MangaReaderTranslationState, scope: CoroutineScope) {
    val strings = LocalShinsouStrings.current
    if (state.textModelBytes > 0) {
        Text(strings.text(StringKey.THIS_LANGUAGE_PAIR_NEEDS_TRANSLATION_MODELS_X, (state.textModelBytes + 999_999) / 1_000_000))
        if (state.textModelDownloadFailed) {
            Text(strings.text(StringKey.THE_DOWNLOAD_FAILED_CHECK_THE_CONNECTION_AND), color = MaterialTheme.colorScheme.error)
        }
        val textDownload = state.textModelDownload
        if (textDownload != null) {
            LinearProgressIndicator(progress = { textDownload.first.toFloat() / textDownload.second }, modifier = Modifier.fillMaxWidth())
        } else {
            Button(onClick = { scope.launch { state.downloadTextModels() } }) { Text(strings.text(StringKey.DOWNLOAD_MODELS)) }
        }
    }
    if (state.systemLanguagesMissing) {
        Text(strings.text(StringKey.THE_SYSTEM_TRANSLATION_NEEDS_THESE_LANGUAGES_DOWNLOADED))
        if (state.textModelDownloadFailed) {
            Text(strings.text(StringKey.THE_LANGUAGES_WERE_NOT_DOWNLOADED_TRY_AGAIN), color = MaterialTheme.colorScheme.error)
        }
        if (state.systemLanguagesDownloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Button(onClick = { scope.launch { state.downloadSystemLanguages() } }) { Text(strings.text(StringKey.DOWNLOAD_LANGUAGES)) }
        }
    }
}

/** The optional model that redraws the art beneath translated text: its download, or removal. */
@Composable
private fun MangaPanelInpaint(state: MangaReaderTranslationState, scope: CoroutineScope) {
    val strings = LocalShinsouStrings.current
    val inpaintDownload = state.inpaintDownload
    when {
        inpaintDownload != null -> LinearProgressIndicator(
            progress = { if (inpaintDownload.second > 0) inpaintDownload.first.toFloat() / inpaintDownload.second else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        state.inpaintInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.text(StringKey.TRANSLATED_TOO_THE_ART_BENEATH_IS_REDRAWN), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { scope.launch { state.removeInpaint() } }) { Text(strings.text(StringKey.REMOVE)) }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.text(StringKey.KEPT_AS_DRAWN_AN_OPTIONAL_X_MB, (state.missingInpaintBytes + 999_999) / 1_000_000),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { scope.launch { state.downloadInpaint() } }, enabled = state.modelsInstalled) { Text(strings.text(StringKey.DOWNLOAD)) }
        }
    }
}

/** Developer diagnostics: how this device is used, and where the last chapter's time went. */
@Composable
private fun MangaPanelDiagnostics(state: MangaReaderTranslationState) {
    val strings = LocalShinsouStrings.current
    val tuning = state.tuning
    if (dev.shinsou.kmp.ui.components.LocalDeveloperDiagnostics.current.showLoadingElapsed && tuning != null) {
        Text(
            strings.text(StringKey.DEVICE_TUNING_DETECTION_X_THREADS_OCR_X, tuning.detectorThreads, tuning.ocrWorkers, tuning.ocrThreads, tuning.pageLoadsAhead),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.lastRun?.let { run ->
            Text(
                if (run.fromCache) strings.text(StringKey.LAST_CHAPTER_X_PAGES_SHOWN_AS_DRAWN, run.pages)
                else strings.text(StringKey.LAST_CHAPTER_X_PAGES_LOADING_AND_READING, run.pages, seconds(run.readMs), seconds(run.translateMs), seconds(run.drawMs)) +
                    (if (run.fromCache) "" else " · " + strings.text(StringKey.FIRST_PAGE_X_S, seconds(run.firstPageMs))) +
                    " · " + run.detector,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}"

