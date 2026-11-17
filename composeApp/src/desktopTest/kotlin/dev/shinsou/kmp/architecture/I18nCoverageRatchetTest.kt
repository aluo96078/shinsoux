package dev.shinsou.kmp.architecture

import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * R4-6 (docs/architecture/0006): Traditional Chinese is the app's own language, so a new
 * `text("…")` key without a zh-TW entry, or a zh-TW entry whose English key no source uses any
 * more, fails here. The keys already in that state are listed in `config/i18n/`; the lists may
 * only shrink: fix an entry, then delete its line.
 */
class I18nCoverageRatchetTest {
    private val sourceSets = listOf("commonMain", "jvmCommonMain", "androidMain", "desktopMain", "iosMain")
    private val i18nPackage = "dev/shinsou/kmp/ui/i18n/"
    private val textCall = Regex("""\btext\(\s*"((?:[^"\\$]|\\.)*)"""")

    private fun mainSources(): List<File> = sourceSets.flatMap { set ->
        File("src/$set/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun unescape(literal: String): String = literal
        .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    private fun escape(key: String): String = key
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")

    private fun baseline(name: String): Set<String> =
        File("../config/i18n/$name").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

    private fun ratchet(name: String, current: Set<String>) {
        val known = baseline(name)
        val added = current - known
        val fixed = known - current
        if (added.isEmpty() && fixed.isEmpty()) return
        fail(
            buildString {
                if (added.isNotEmpty()) {
                    appendLine("New entries for config/i18n/$name (add the zh-TW text instead):")
                    added.sorted().forEach { appendLine("  $it") }
                }
                if (fixed.isNotEmpty()) {
                    appendLine("Fixed; delete these lines from config/i18n/$name:")
                    fixed.sorted().forEach { appendLine("  $it") }
                }
            },
        )
    }

    @Test
    fun everyTextKeyHasTraditionalChinese() {
        val zhTw = shinsouStringsFor("zh-TW")
        val keys = mainSources()
            .flatMap { file -> textCall.findAll(file.readText()).map { unescape(it.groupValues[1]) }.toList() }
            .filter { key -> key.any(Char::isLetter) }
            .toSet()
        ratchet("zh-TW-missing.txt", keys.filter { zhTw.text(it) == it }.map(::escape).toSet())
    }

    @Test
    fun everyTraditionalChineseEntryIsUsed() {
        val code = mainSources()
            .filterNot { it.invariantSeparatorsPath.contains(i18nPackage) }
            .joinToString("\n") { it.readText() }
        val orphans = shinsouStringsFor("zh-TW").translations.keys
            .map(::escape)
            .filterNot { key -> "\"$key\"" in code }
            .toSet()
        ratchet("zh-TW-orphans.txt", orphans)
    }
}
