package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `dev.shinsou.kmp.testing.TestOnlyApi` lets the tests of a higher module reach an `internal`
 * declaration of a lower one through that module's `<Module>TestAccess` object. Only test source
 * sets may opt in; this keeps product code from doing so:
 *
 * - no product source set (any `src/<set>` whose name has no `Test`) names the marker or a
 *   `TestAccess` object at all, outside the marker's own file and the `TestAccess` objects;
 * - the marker annotates nothing but `public object <Name>TestAccess` declarations;
 * - no build script or convention plugin mentions the marker (a compiler `-opt-in` would reach the
 *   product compilations too).
 */
class TestOnlyApiOptInTest {
    private val root = File("..")
    private val modules: List<String> =
        INCLUDE.findAll(File(root, "settings.gradle.kts").readText()).map { it.groupValues[1] }.toList()

    /**
     * By name, not by syntax: outside the marker's own file and the `TestAccess` objects, product
     * code never names the marker or a `TestAccess` object at all, however it is written (an
     * annotation on the same line or fully qualified, an opt-in spread over lines, a callable
     * reference, an import alias, `with(...)`).
     */
    @Test
    fun productSourceSetsNeverOptIn() {
        val found = productFiles().filterNot { it.name == MARKER_FILE || ACCESS_FILE.matches(it.name) }.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            withoutComments(file.readText()).lines().mapIndexedNotNull { index, code ->
                when {
                    MARKER.containsMatchIn(code) -> "$path:${index + 1}: names TestOnlyApi"
                    ACCESS_NAME.containsMatchIn(code) -> "$path:${index + 1}: names a TestAccess object"
                    else -> null
                }
            }
        }
        if (found.isNotEmpty()) fail(found.joinToString("\n", "Only test source sets may opt in to TestOnlyApi:\n") { "  $it" })
    }

    /** A `TestAccess` file names the marker only to import it and to mark its one object. */
    @Test
    fun theMarkerOnlyAnnotatesTestAccessObjects() {
        val accessFiles = productFiles().filter { ACCESS_FILE.matches(it.name) }
        assertTrue(accessFiles.isNotEmpty(), "No TestAccess object found; is the scan looking in the right place?")
        val wrong = accessFiles.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val lines = withoutComments(file.readText()).lines()
            val marks = lines.indices.filter { MARKER.containsMatchIn(lines[it]) && lines[it].trim() != MARKER_IMPORT }
            val objectName = file.nameWithoutExtension
            buildList {
                if (marks.size != 1) add("$path: names TestOnlyApi ${marks.size} times besides its import")
                marks.forEach { index ->
                    val marked = lines[index].trim() == "@TestOnlyApi" &&
                        lines.getOrNull(index + 1)?.trim() == "public object $objectName {"
                    if (!marked) add("$path:${index + 1}: ${lines[index].trim()}")
                }
            }
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n", "@TestOnlyApi may only mark a `public object <Name>TestAccess`:\n") { "  $it" })
    }

    @Test
    fun noBuildScriptOptsIn() {
        val scripts = (modules + APP_MODULE).map { File(root, "$it/build.gradle.kts") } +
            File(root, "build.gradle.kts") +
            File(root, "settings.gradle.kts") +
            File(root, "build-logic/settings.gradle.kts") +
            File(root, "build-logic/build.gradle.kts") +
            File(root, "build-logic/src").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts") }
        val found = scripts.filter { it.isFile && "TestOnlyApi" in it.readText() }.map { it.relativeTo(root).invariantSeparatorsPath }
        if (found.isNotEmpty()) fail(found.joinToString("\n", "Build scripts may not opt in to TestOnlyApi:\n") { "  $it" })
    }

    private fun productFiles(): List<File> = (modules + APP_MODULE).distinct().flatMap { module ->
        File(root, "$module/src").listFiles().orEmpty()
            .filter { it.isDirectory && "Test" !in it.name }
            .flatMap { set -> File(set, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private companion object {
        const val APP_MODULE = "composeApp"
        val INCLUDE = Regex("""^include\(":([\w-]+)"\)""", RegexOption.MULTILINE)
        const val MARKER_FILE = "TestOnlyApi.kt"
        const val MARKER_IMPORT = "import dev.shinsou.kmp.testing.TestOnlyApi"
        val MARKER = Regex("""TestOnlyApi""")
        val ACCESS_NAME = Regex("""\w*TestAccess\b""")
        val ACCESS_FILE = Regex("""\w+TestAccess\.kt""")

        /**
         * [text] without comments (KDoc may mention the marker), keeping its line breaks. String
         * literals are kept as they are (naming the marker in one counts), and a comment opener
         * inside one does not start a comment.
         */
        fun withoutComments(text: String): String = buildString {
            var i = 0
            var depth = 0
            var inLineComment = false
            var quote: String? = null
            while (i < text.length) {
                val rest = text.substring(i, minOf(i + 3, text.length))
                val c = text[i]
                when {
                    inLineComment -> {
                        if (c == '\n') {
                            inLineComment = false
                            append(c)
                        }
                        i++
                    }
                    depth > 0 -> {
                        when {
                            rest.startsWith("/*") -> { depth++; i += 2 }
                            rest.startsWith("*/") -> { depth--; i += 2 }
                            else -> { if (c == '\n') append(c); i++ }
                        }
                    }
                    quote != null -> {
                        val open = quote
                        when {
                            open == "\"" && c == '\\' && i + 1 < text.length -> { append(c).append(text[i + 1]); i += 2 }
                            text.startsWith(open, i) -> { append(open); i += open.length; quote = null }
                            else -> { append(c); i++ }
                        }
                    }
                    rest.startsWith("//") -> { inLineComment = true; i += 2 }
                    rest.startsWith("/*") -> { depth = 1; i += 2 }
                    rest == "\"\"\"" -> { quote = rest; append(rest); i += 3 }
                    c == '"' -> { quote = "\""; append(c); i++ }
                    // A character literal ('"', '\'', '/') is copied whole: it opens nothing.
                    c == '\'' -> {
                        val end = text.indexOf('\'', if (text.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                        val stop = if (end < 0) text.length else end + 1
                        append(text, i, stop)
                        i = stop
                    }
                    else -> { append(c); i++ }
                }
            }
        }
    }
}
