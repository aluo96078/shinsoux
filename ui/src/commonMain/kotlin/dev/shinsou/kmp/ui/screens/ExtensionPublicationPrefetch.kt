package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.extensionUnitKey
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.translation.OfflineModelCatalog
import dev.shinsou.kmp.translation.PrefetchLanguages
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationLabels
import dev.shinsou.kmp.translation.TranslationPrefetchJob
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.reader.prefetchChapter

// Translation ahead of reading for one publication page ([ExtensionPublicationState]).

/** Queues [selections] for translation from [from] into [to] through the app-wide queue. */
internal fun ExtensionPublicationState.startPrefetch(selections: List<ExtensionUnitSelectionV2>, languages: PrefetchLanguages, context: PrefetchContext) {
    val features = reader.contentFeatures ?: return
    val translation = features.translation ?: return
    val source = services.catalog.state.value.sources.firstOrNull { it.sourceKey == sourceKey }
    val workLanguage = source?.language?.takeIf { OfflineModelCatalog.language(it) != null }.orEmpty()
    val workTitle = publicationPage?.publication?.title ?: item.title
    val jobs = selections.map { selection ->
        TranslationPrefetchJob(
            id = prefetchGroup + "|" + selection.unit.remoteId,
            group = prefetchGroup,
            title = selection.unit.title,
            languages = languages,
            unit = selection.unit.remoteId,
            load = {
                // Fetching the chapter stores it, exactly as a download does.
                val materialization = try {
                    services.acquisition.materializeExtensionContentV2(selection, null)
                } catch (choice: ExtensionContentConsumerException.RepresentationSelectionRequired) {
                    services.acquisition.materializeExtensionContentV2(selection, choice.availableIds.firstOrNull() ?: throw choice)
                }
                prefetchChapter(
                    services.acquisition.openMaterializedExtensionContentV2(materialization),
                    features,
                    TranslationLabels(workTitle, selection.unit.title, source?.name),
                    manga = context.manga,
                    workLanguage = workLanguage,
                    appLanguage = context.appLanguage,
                )
            },
        )
    }
    work.scope.launch { enqueuePrefetch(translation, jobs, languages) }
}

private suspend fun ExtensionPublicationState.enqueuePrefetch(translation: ReaderTranslation, jobs: List<TranslationPrefetchJob>, languages: PrefetchLanguages) {
    // Chapters already whole in this language, from the service in use, need nothing.
    val done = runCatchingCancellable { translation.translatedChapterKeys(workKey, languages.to, languages.from) }
        .onFailure { Log.warn("Translation", it) { "Reading the translated chapter list failed; translating every selected chapter" } }
        .getOrNull().orEmpty()
    val pending = if (done.isEmpty()) {
        jobs
    } else {
        withContext(work.workDispatcher) {
            jobs.filterNot { extensionUnitKey(sourceKey, remotePublicationId, it.unit).value in done }
        }
    }
    val strings = work.strings()
    operationMessage = when {
        pending.isEmpty() -> strings.text(StringKey.THE_SELECTED_CHAPTERS_ARE_ALREADY_TRANSLATED)
        else -> {
            val added = translation.prefetch.enqueue(pending)
            if (added > 0) {
                strings.text(StringKey.X_CHAPTERS_ADDED_TO_THE_TRANSLATION_QUEUE, added)
            } else {
                strings.text(StringKey.THE_SELECTED_CHAPTERS_ARE_ALREADY_TRANSLATED_OR)
            }
        }
    }
}

