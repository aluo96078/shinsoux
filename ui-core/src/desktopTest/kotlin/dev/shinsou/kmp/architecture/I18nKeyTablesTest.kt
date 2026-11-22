package dev.shinsou.kmp.architecture

import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R4-6 (docs/architecture/0006): UI strings are looked up by [StringKey], one table per language.
 *
 * - Traditional and Simplified Chinese are complete: their tables hold exactly the keys of [StringKey].
 *   The other languages are partial by design and show [StringKey.english] for a key without an entry.
 * - Every key is used by product code; an unused key is deleted rather than kept.
 * - A translation has the same `{n}` placeholders as its English text.
 */
class I18nKeyTablesTest {
    private val completeLanguages = listOf("zh-TW", "zh-CN")
    private val partialLanguages = listOf("ja", "ko", "fr", "de", "es", "pt")
    private val sourceSets = listOf("commonMain", "jvmCommonMain", "androidMain", "desktopMain", "iosMain")
    private val placeholder = Regex("""\{(\d+)\}""")
    private val keyReference = Regex("""\bStringKey\.([A-Z0-9_]+)\b""")
    private val languageTable = Regex("""Map<StringKey, String> = mapOf\(""")

    @Test
    fun completeLanguagesHaveExactlyEveryKey() {
        val problems = completeLanguages.flatMap { language ->
            val keys = shinsouStringsFor(language).table.keys
            (StringKey.entries - keys).map { "$language is missing $it (\"${it.english}\")" } +
                (keys - StringKey.entries.toSet()).map { "$language has an extra key $it" }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n", "Translate these keys:\n"))
    }

    @Test
    fun partialLanguagesAreStillLoaded() {
        partialLanguages.forEach { language ->
            assertTrue(shinsouStringsFor(language).table.isNotEmpty(), "$language has no table")
        }
    }

    @Test
    fun everyKeyIsUsedByProductCode() {
        // The enum and the language tables name every key; only the remaining sources count as use.
        val used = sourceSets
            .flatMap { set ->
                // R5-1: the keys are this module's; the app module's entry points use some too. C1 (0007):
                // so do the feature UI modules and the shell (the build script makes their sources inputs).
                USING_MODULES.map { File("../$it/src/$set/kotlin") }
                    .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            }
            .filterNot { it.name == "StringKey.kt" }
            .map { it.readText() }
            .filterNot { languageTable.containsMatchIn(it) }
            .flatMap { text -> keyReference.findAll(text).map { it.groupValues[1] }.toList() }
            .toSet()
        val unused = StringKey.entries.filterNot { it.name in used }
        if (unused.isNotEmpty()) {
            fail(unused.joinToString("\n", "Delete these keys (and their table entries); nothing uses them:\n") { "  $it" })
        }
    }

    @Test
    fun translationsKeepTheEnglishPlaceholders() {
        val problems = (completeLanguages + partialLanguages).flatMap { language ->
            shinsouStringsFor(language).table.mapNotNull { (key, translation) ->
                val expected = placeholders(key.english)
                val actual = placeholders(translation)
                if (expected == actual) null else "$language $key: English has $expected, translation has $actual"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.groupValues[1] }.toSet()

    private companion object {
        val USING_MODULES = listOf("ui-core", "ui-reader", "ui-translation", "ui-sync", "ui-browse", "ui-library", "ui-settings", "ui", "composeApp")
    }
}
