package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoTranslationTest {
    private fun preferences(
        provider: TranslationProviderKind = TranslationProviderKind.OFFLINE,
        auto: Boolean = true,
        paid: Boolean = false,
        skipped: Set<String> = emptySet(),
        otherScript: Boolean = false,
    ) = TranslationPreferences(
        provider = provider,
        dailyCharacterLimit = 0,
        charactersUsedToday = 0,
        deepLKeySet = false,
        googleKeySet = false,
        cloudAvailable = true,
        autoTranslate = auto,
        autoTranslatePaid = paid,
        autoTranslateSkipped = skipped,
        autoTranslateOtherScript = otherScript,
    )

    @Test
    fun aWorkInAnotherLanguageIsTranslatedIntoTheAppsLanguage() {
        assertEquals("ja" to "zh-Hant", ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences()))
        assertEquals("en" to "zh-Hans", ReaderTranslation.autoTranslationPair("en-US", "zh-CN", preferences()))
        assertEquals("ko" to "en", ReaderTranslation.autoTranslationPair("ko", "en", preferences(TranslationProviderKind.SYSTEM)))
    }

    @Test
    fun aWorkAlreadyInTheReadersLanguageIsLeftAlone() {
        assertNull(ReaderTranslation.autoTranslationPair("zh-Hant", "zh-TW", preferences()))
        assertNull(ReaderTranslation.autoTranslationPair("ja-JP", "ja", preferences()))
    }

    @Test
    fun anUnknownOrUnsupportedLanguageIsLeftAlone() {
        assertNull(ReaderTranslation.autoTranslationPair(null, "zh-TW", preferences()))
        assertNull(ReaderTranslation.autoTranslationPair("", "zh-TW", preferences()))
        assertNull(ReaderTranslation.autoTranslationPair("fr", "zh-TW", preferences()))
        assertNull(ReaderTranslation.autoTranslationPair("ja", "fr", preferences()))
    }

    @Test
    fun nothingStartsWhenTurnedOff() {
        assertNull(ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences(auto = false)))
        assertNull(ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences(TranslationProviderKind.DEEPL, auto = false, paid = true)))
    }

    @Test
    fun aPaidServiceStartsOnlyWhenAllowed() {
        for (provider in ReaderTranslation.PAID_PROVIDERS) {
            assertNull(ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences(provider)))
            assertEquals("ja" to "zh-Hant", ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences(provider, paid = true)))
        }
    }

    @Test
    fun aReaderOfChineseIsNotConvertedIntoTheOtherScriptUntilTheyAsk() {
        assertNull(ReaderTranslation.autoTranslationPair("zh-CN", "zh-TW", preferences()))
        assertNull(ReaderTranslation.autoTranslationPair("zh-Hant", "zh-CN", preferences()))
        // other languages still start for a reader of Chinese
        assertEquals("ja" to "zh-Hant", ReaderTranslation.autoTranslationPair("ja", "zh-TW", preferences()))
        // the setting alone decides, whatever an older list of skipped languages says
        assertEquals("zh-Hans" to "zh-Hant", ReaderTranslation.autoTranslationPair("zh-CN", "zh-TW", preferences(skipped = setOf("zh-Hans"), otherScript = true)))
    }

    @Test
    fun theOtherScriptOfChineseIsConvertedEvenWhenTheServiceCharges() {
        for (provider in ReaderTranslation.PAID_PROVIDERS) {
            assertEquals("zh-Hans" to "zh-Hant", ReaderTranslation.autoTranslationPair("zh-CN", "zh-TW", preferences(provider, otherScript = true)))
            assertEquals("zh-Hant" to "zh-Hans", ReaderTranslation.autoTranslationPair("zh-Hant", "zh-CN", preferences(provider, otherScript = true)))
        }
        assertNull(ReaderTranslation.autoTranslationPair("zh-CN", "zh-TW", preferences(TranslationProviderKind.DEEPL, auto = false, otherScript = true)))
    }

    @Test
    fun aLanguageTheReaderLeftOutStaysUntranslated() {
        val noEnglish = preferences(skipped = setOf("en"))
        assertNull(ReaderTranslation.autoTranslationPair("en-US", "zh-TW", noEnglish))
        assertEquals("ja" to "zh-Hant", ReaderTranslation.autoTranslationPair("ja", "zh-TW", noEnglish))
        // a Chinese work is skipped by its script, not by "zh"
        val noSimplified = preferences(skipped = setOf("zh-Hans"))
        assertNull(ReaderTranslation.autoTranslationPair("zh-CN", "ja", noSimplified))
        assertEquals("zh-Hant" to "ja", ReaderTranslation.autoTranslationPair("zh-TW", "ja", noSimplified))
    }
}
