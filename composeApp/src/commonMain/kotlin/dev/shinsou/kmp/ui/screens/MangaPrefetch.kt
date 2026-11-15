package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.manga.MangaChapterRequest
import dev.shinsou.kmp.manga.MangaOrientation
import dev.shinsou.kmp.manga.MangaProgress
import dev.shinsou.kmp.manga.MangaTranslation
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationException
import dev.shinsou.kmp.translation.TranslationFailure
import dev.shinsou.kmp.translation.TranslationLabels
import dev.shinsou.kmp.translation.TranslationPrefetchJob
import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.translation.WorkTranslationSettings
import dev.shinsou.kmp.ui.ReaderPage
import androidx.compose.runtime.staticCompositionLocalOf

/** The app's manga translation, for screens that queue chapters ahead (null where unavailable). */
public val LocalMangaTranslation = staticCompositionLocalOf<MangaTranslation?> { null }

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
    manga: MangaTranslation,
    translation: ReaderTranslation,
    work: Manga,
    chapter: Chapter,
    labels: TranslationLabels?,
    /** The chapter's pages, loaded (and downloaded) on demand. */
    loadPages: suspend () -> List<ReaderPage>,
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
            )
        },
    )
}

/**
 * Translates a manga chapter ahead of reading, as the reader would: Japanese (what the text
 * recognition reads) into [to], else the work's language, else Traditional Chinese. The work
 * then opens translated. Throws [TranslationException] for what stops the queue.
 */
internal suspend fun translateMangaChapterAhead(
    manga: MangaTranslation,
    translation: ReaderTranslation,
    workKey: String,
    chapterKey: String,
    to: String?,
    pageCount: Int,
    loadPage: suspend (Int) -> ByteArray?,
    labels: TranslationLabels?,
    progress: (done: Int, total: Int) -> Unit,
) {
    if (!manga.deviceSupported) throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "Manga translation is not available on this device")
    // Downloads are shown and started from the reader, where their size is shown first.
    if (!manga.modelsInstalled()) throw TranslationException(TranslationFailure.MODEL_MISSING, "The manga recognition models are not downloaded")
    val settings = translation.settings(workKey)
    val from = MangaReaderTranslationState.SOURCE_LANGUAGE
    val target = to?.takeIf { it != from } ?: settings?.to?.takeIf { it != from } ?: "zh-Hant"
    // A work translated ahead opens translated.
    if (settings == null || !settings.enabled || settings.from != from || settings.to != target) {
        translation.saveSettings(workKey, (settings ?: WorkTranslationSettings(enabled = true, from = from, to = target)).copy(enabled = true, from = from, to = target))
    }
    if (!translation.supports(from, target)) throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "No translation from $from to $target")
    if (translation.activeProvider() == TranslationProviderKind.OFFLINE && translation.missingModels(from, target).isNotEmpty()) {
        throw TranslationException(TranslationFailure.MODEL_MISSING, "The models for $from to $target are not downloaded")
    }
    val complete = manga.translateChapter(
        MangaChapterRequest(
            workKey = workKey, chapterKey = chapterKey, pageCount = pageCount, loadPage = loadPage,
            from = from, to = target, orientation = MangaOrientation.VERTICAL, labels = labels,
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
