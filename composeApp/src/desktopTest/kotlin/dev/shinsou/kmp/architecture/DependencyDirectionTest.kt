package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * R2-2 (docs/architecture/0006): the layering of docs/architecture/0006 §4.1, checked on the
 * sources until the Gradle modules enforce it. A reference that breaks a rule fails unless it is
 * in `config/architecture/dependency-allowlist.txt`; the allowlist may only shrink, so an entry
 * that no longer breaks a rule fails too and must be deleted.
 */
class DependencyDirectionTest {
    private val sourceSets = listOf("commonMain", "jvmCommonMain", "desktopMain", "androidMain", "iosMain")
    private val allowlist = File("../config/architecture/dependency-allowlist.txt")

    @Test
    fun noPackageReachesUpTheLayers() {
        val found = violations()
        val allowed = allowlist.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val added = found - allowed
        val fixed = allowed - found
        if (added.isEmpty() && fixed.isEmpty()) return
        fail(buildString {
            if (added.isNotEmpty()) {
                appendLine("New references against the layering (docs/architecture/0006 §4.1):")
                added.sorted().forEach { appendLine("  $it") }
            }
            if (fixed.isNotEmpty()) {
                appendLine("No longer broken; delete these lines from ${allowlist.path}:")
                fixed.sorted().forEach { appendLine("  $it") }
            }
        })
    }

    /** `<source file>|<package referred to>|<rule>`, one per file and package. */
    private fun violations(): Set<String> = buildSet {
        for (set in sourceSets) {
            val root = File("src/$set/kotlin")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                val from = lines.firstNotNullOfOrNull { PACKAGE.matchEntire(it.trim())?.groupValues?.get(1) } ?: return@forEach
                val layer = from.substringBefore('.').ifEmpty { ROOT }
                val code = lines.filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
                    .map(::codeOf)
                val targets = code.flatMapTo(sortedSetOf()) { line ->
                    // a name straight under the root package (the entry points) is a reference to it
                    REFERENCE.findAll(line).map { it.groupValues[1] } + ROOT_REFERENCE.findAll(line).map { ROOT }
                }
                for (to in targets) {
                    if (to == layer) continue
                    val rule = broken(layer, to) ?: continue
                    add("${file.relativeTo(File("src")).invariantSeparatorsPath}|$to|$rule")
                }
            }
        }
    }

    /** [line] without what only looks like a reference: text in quotes, and comments after code. */
    private fun codeOf(line: String): String =
        line.replace(QUOTED, "\"\"").replace(INLINE_COMMENT, " ").substringBefore("//")

    private fun broken(from: String, to: String): String? = when {
        from == "domain" && to !in CORE -> "domain-depends-only-on-core"
        from in CORE && to !in CORE -> "core-depends-only-on-core"
        to in APP && from !in APP -> "only-entry-points-use-app"
        to in UI && from !in UI && from !in APP -> "only-ui-and-app-use-ui"
        else -> null
    }

    private companion object {
        val PACKAGE = Regex("""package dev\.shinsou\.kmp\.?([\w.]*)""")
        val REFERENCE = Regex("""\bdev\.shinsou\.kmp\.([a-z]\w*)\.""")
        val ROOT_REFERENCE = Regex("""\bdev\.shinsou\.kmp\.[A-Z]\w*""")
        val QUOTED = Regex("""'(?:\\.|[^'\\])'|"(?:\\.|[^"\\])*"""")
        val INLINE_COMMENT = Regex("""/\*.*?\*/""")
        const val ROOT = "(root)"
        val CORE = setOf("domain", "concurrent", "crypto", "files", "network", "logging")
        val APP = setOf("app", "desktop", ROOT)
        val UI = setOf("ui", "navigation")
    }
}
