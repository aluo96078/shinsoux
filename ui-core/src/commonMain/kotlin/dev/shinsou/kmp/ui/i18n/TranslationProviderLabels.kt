package dev.shinsou.kmp.ui.i18n

/**
 * The name of the translation service whose id is [id] (`TranslationProviderKind.id`), as the
 * translation settings and the readers show it; null for an id that names no service.
 *
 * 0008: keyed by the id, so the design system does not depend on :feature-translation;
 * `ui.translation.TranslationServiceNamesTest` keeps every service named.
 */
fun translationServiceName(id: String, strings: ShinsouStrings): String? = when (id) {
    "offline" -> strings.text(StringKey.OFFLINE_FREE)
    "system" -> strings.text(StringKey.SYSTEM_TRANSLATION_APPLE)
    "deepl" -> "DeepL"
    "google" -> strings.text(StringKey.GOOGLE_TRANSLATE)
    "azure" -> "Azure Translator"
    "llm" -> strings.text(StringKey.AI_MODEL_YOUR_OWN_API)
    else -> null
}
