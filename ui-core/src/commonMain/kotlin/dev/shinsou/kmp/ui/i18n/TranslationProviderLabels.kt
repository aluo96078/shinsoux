package dev.shinsou.kmp.ui.i18n

import dev.shinsou.kmp.translation.TranslationProviderKind

/** The name of a translation service, as the translation settings and the readers show it. */
fun TranslationProviderKind.label(strings: ShinsouStrings): String = when (this) {
    TranslationProviderKind.OFFLINE -> strings.text(StringKey.OFFLINE_FREE)
    TranslationProviderKind.SYSTEM -> strings.text(StringKey.SYSTEM_TRANSLATION_APPLE)
    TranslationProviderKind.DEEPL -> "DeepL"
    TranslationProviderKind.GOOGLE -> strings.text(StringKey.GOOGLE_TRANSLATE)
    TranslationProviderKind.AZURE -> "Azure Translator"
    TranslationProviderKind.LLM -> strings.text(StringKey.AI_MODEL_YOUR_OWN_API)
}
