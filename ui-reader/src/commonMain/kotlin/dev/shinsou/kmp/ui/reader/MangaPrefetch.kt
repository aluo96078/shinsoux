package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.manga.MangaChapterRequest
import dev.shinsou.kmp.manga.MangaLanguages
import dev.shinsou.kmp.manga.MangaOrientation
import dev.shinsou.kmp.manga.MangaProgress
import dev.shinsou.kmp.manga.MangaChapterTranslation
import dev.shinsou.kmp.translation.ChineseScript
import dev.shinsou.kmp.translation.OfflineModelCatalog
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationException
import dev.shinsou.kmp.translation.TranslationFailure
import dev.shinsou.kmp.translation.TranslationLabels
import dev.shinsou.kmp.translation.TranslationPrefetchJob
import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.translation.WorkTranslationSettings
import dev.shinsou.kmp.contract.ReaderPage
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.staticCompositionLocalOf

/** The work a page-reader title is translated (and its settings stored) under. */
public fun mangaWorkKey(manga: Manga): String = "page-reader:${manga.source}:${manga.url}"

/** The chapter a page-reader chapter is translated under. */
public fun mangaChapterKey(chapter: Chapter): String = chapter.url.ifBlank { chapter.id.toString() }

/** The translation-queue group of a page-reader title: its work key. */
public fun mangaPrefetchGroup(manga: Manga): String = mangaWorkKey(manga)

/**
 * A manga chapter for the app-wide translation queue (ADR-0005): its pages are read, translated
 * and drawn exactly as the reader would, so the chapter opens translated and nothing is done
 * twice (OCR results, translations and drawn pages are all cached).
 */
public fun mangaPrefetchJob(
    manga: MangaChapterTranslation,
    translation: ReaderTranslation,
    work: Manga,
    chapter: Chapter,
    labels: TranslationLabels?,
    /** The chapter's pages, loaded (and downloaded) on demand. */
    loadPages: suspend () -> List<ReaderPage>,
    /** The work's language as its source gives it, and the app's: how a work with no settings starts by itself. */
    workLanguage: String? = null,
    appLanguage: String? = null,
): TranslationPrefetchJob {
    val workKey = mangaWorkKey(work)
    return TranslationPrefetchJob(
        id = "$workKey|${chapter.id}",
        group = mangaPrefetchGroup(work),
        title = chapter.name,
        unit = chapter.id.toString(),
        load = { throw IllegalStateException("manga chapters run through run") },
        run = { progress ->
            val settings = translation.settings(workKey)
            val pages = loadPages().sortedBy { it.index }
            translateMangaChapterAhead(
                manga, translation, workKey, mangaChapterKey(chapter), to = settings?.to, pageCount = pages.size,
                loadPage = { i -> loadPageBytes(pages[i]) }, labels = labels, progress = progress,
                workLanguage = workLanguage, appLanguage = appLanguage,
            )
        },
    )
}

/**
 * Translates a manga chapter ahead of reading, as the reader would: from the language chosen for
 * the work, or the one it started translating from by itself (its pages read, or [workLanguage]
 * with [appLanguage]), Japanese when there is none; into [to], else the work's target, else the
 * app's language, else Traditional Chinese, refused when that is the language it is in. The work
 * then opens translated. Throws [TranslationException] for what stops the queue.
 */
internal suspend fun translateMangaChapterAhead(
    manga: MangaChapterTranslation,
    translation: ReaderTranslation,
    workKey: String,
    chapterKey: String,
    to: String?,
    pageCount: Int,
    loadPage: suspend (Int) -> ByteArray?,
    labels: TranslationLabels?,
    progress: (done: Int, total: Int) -> Unit,
    workLanguage: String? = null,
    appLanguage: String? = null,
    /** The language picked when the chapter was queued: kept over all of the above. */
    chosenFrom: String? = null,
) {
    if (!manga.deviceSupported) throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "Manga translation is not available on this device")
    // Downloads are shown and started from the reader, where their size is shown first.
    if (!manga.modelsInstalled()) throw TranslationException(TranslationFailure.MODEL_MISSING, "The manga recognition models are not downloaded")
    val settings = translation.settings(workKey)
    // a work with no settings of its own translates as it did, or would, by itself
    // the language picked for the work's pages, else the one its source names, whether or not the
    // work may start by itself; a source naming Chinese but not the script has its pages read, as the
    // reader does, rather than be read as Japanese
    val named = workLanguage?.takeIf { it.isNotBlank() }
    val chineseUnnamed = named != null && ChineseScript.unspecified(named)
    val chosen = chosenFrom?.takeIf(MangaLanguages::readable)
    val picked = chosen ?: MangaReaderTranslationState.pickedFrom(settings)
    var source: String? = null
    var resolved = false
    suspend fun known(): String? = if (resolved) source else (picked ?: when {
        named == null -> null
        chineseUnnamed -> try {
            manga.readsChineseScript(workKey, pageCount, loadPage)
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Telling the Chinese script for a prefetch failed" }
            null
        }
        else -> OfflineModelCatalog.language(named)?.takeIf(MangaLanguages::readable)
    }).also { source = it; resolved = true }
    val auto = if (chosen != null || !WorkTranslationSettings.startsByItself(settings)) null
    else translation.startedByItself(workKey) ?: run {
        val pair = known()?.let { k -> appLanguage?.let { translation.autoTranslation(k, it) } } ?: return@run null
        // as the reader does: a language picked for the work while it was off is kept when allowed
        pair.first to translation.autoTarget(pair, settings?.to)
    }
    val from = auto?.first ?: picked ?: settings?.takeIf { it.enabled }?.from?.takeIf(MangaLanguages::readable) ?: known()
        ?: settings?.from?.takeIf { MangaLanguages.readable(it) && !chineseUnnamed }
        ?: if (chineseUnnamed) {
            throw TranslationException(TranslationFailure.MODEL_MISSING, "The script of this Chinese work could not be told from its pages")
        } else MangaReaderTranslationState.SOURCE_LANGUAGE
    // the target asked for is kept even when it is the work's own language, which is then refused
    // rather than swapped for another the reader did not ask for
    val target = to ?: auto?.second ?: settings?.to ?: appLanguage?.let(ReaderTranslation::targetLanguage) ?: "zh-Hant"
    // a work already in the reader's language is left as it is, and not saved as translated
    if (target == from) throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "The work is already in $target")
    // A work translated ahead opens translated.
    if (settings == null || !settings.enabled || settings.from != from || settings.to != target || (chosen != null && !settings.fromPicked)) {
        val base = settings ?: WorkTranslationSettings(enabled = true, from = from, to = target)
        // a language picked when queueing is the reader's, as one picked in the reader is
        translation.saveSettings(workKey, base.copy(enabled = true, from = from, to = target, fromPicked = base.fromPicked || chosen != null))
    }
    if (manga.missingLanguageBytes(from, target) > 0) {
        throw TranslationException(TranslationFailure.MODEL_MISSING, "The text recognition models or font for $from to $target are not downloaded")
    }
    if (!translation.supports(from, target)) throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "No translation from $from to $target")
    if (translation.activeProvider() == TranslationProviderKind.OFFLINE && translation.missingModels(from, target).isNotEmpty()) {
        throw TranslationException(TranslationFailure.MODEL_MISSING, "The models for $from to $target are not downloaded")
    }
    val complete = manga.translateChapter(
        MangaChapterRequest(
            workKey = workKey, chapterKey = chapterKey, pageCount = pageCount, loadPage = loadPage,
            from = from, to = target, orientation = MangaOrientation.VERTICAL, direction = MangaLanguages.direction(from), labels = labels,
            // translated ahead, nobody waits for its first page: the whole chapter in one script
            reading = false,
        ),
        onProgress = { p ->
            when (p) {
                is MangaProgress.Reading -> progress(p.page, p.pages)
                is MangaProgress.Loading -> progress(p.page, p.pages)
                is MangaProgress.Translating -> progress(p.done, p.total)
                is MangaProgress.Drawing -> progress(p.page, p.pages)
            }
        },
    ) { _, _ -> }
    // A chapter with untranslated lines or unread pages is not kept as done: it is tried again.
    if (!complete) throw TranslationException(TranslationFailure.ENGINE_FAILED, "Some lines or pages were left untranslated")
}
