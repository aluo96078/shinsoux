package dev.shinsou.kmp.ui.translation

import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.translationServiceName

/** The name of a translation service, as the translation settings show it. */
internal fun TranslationProviderKind.label(strings: ShinsouStrings): String = translationServiceName(id, strings) ?: id
