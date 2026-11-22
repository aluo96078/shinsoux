package dev.shinsou.kmp.ui.translation

import androidx.compose.runtime.staticCompositionLocalOf
import dev.shinsou.kmp.manga.MangaTranslationService

/** The app's manga translation, for screens that queue chapters ahead (null where unavailable). */
public val LocalMangaTranslation = staticCompositionLocalOf<MangaTranslationService?> { null }
