package dev.shinsou.kmp.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.manga.MangaChapterTranslation
import dev.shinsou.kmp.testing.TestOnlyApi
import dev.shinsou.kmp.translation.PrefetchChapter
import dev.shinsou.kmp.translation.TranslationLabels

/**
 * The app module's tests (which need the app's own ContentFeatures) reach these internal parts of
 * the reader only through here; see [TestOnlyApi].
 */
@TestOnlyApi
public object UiReaderTestAccess {
    public fun mangaTranslationAvailable(session: TypedReaderContentSession, features: ContentFeatures?): Boolean =
        dev.shinsou.kmp.ui.reader.mangaTranslationAvailable(session, features)

    public suspend fun prefetchChapter(
        session: TypedReaderContentSession,
        features: ContentFeatures,
        labels: TranslationLabels?,
        manga: MangaChapterTranslation? = null,
    ): PrefetchChapter = dev.shinsou.kmp.ui.reader.prefetchChapter(session, features, labels, manga)

    /** The unified reader with the arguments a test sets; the rest keep their defaults. */
    @Composable
    public fun UnifiedContentReader(
        session: TypedReaderContentSession,
        features: ContentFeatures,
        settings: ReaderSettings,
        translationRequestSerial: Long,
        mangaTranslation: MangaChapterTranslation?,
        modifier: Modifier = Modifier,
    ) {
        dev.shinsou.kmp.ui.reader.UnifiedContentReader(
            session = session,
            features = features,
            copyText = { _, _ -> true },
            settings = settings,
            translationRequestSerial = translationRequestSerial,
            mangaTranslation = mangaTranslation,
            modifier = modifier,
        )
    }
}
