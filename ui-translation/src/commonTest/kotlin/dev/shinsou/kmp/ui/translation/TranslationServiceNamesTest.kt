package dev.shinsou.kmp.ui.translation

import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.translationServiceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 0008: :ui-core names the services by id; every service of :feature-translation has a name. */
class TranslationServiceNamesTest {
    @Test
    fun everyTranslationServiceHasAName() {
        val strings = shinsouStringsFor("en-US")
        TranslationProviderKind.entries.forEach { kind ->
            assertNotNull(translationServiceName(kind.id, strings), "${kind.id} has no name")
        }
        assertEquals("DeepL", TranslationProviderKind.DEEPL.label(strings))
        assertNull(translationServiceName("chinese-script", strings))
    }
}
