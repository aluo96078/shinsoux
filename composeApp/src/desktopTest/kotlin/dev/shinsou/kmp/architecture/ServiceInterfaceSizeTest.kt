package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R4-2 (docs/architecture/0006): the UI's service interfaces stay small enough to depend on one at a
 * time. 0007 A4: an interface counts what it inherits from the others here as well, so combining
 * small interfaces into one does not slip past the limit.
 *
 * The only interfaces allowed over the limit are the [UMBRELLAS]: the bundles the platform hosts
 * implement and the composition root hands over. Code depends on the narrow interfaces instead;
 * the files still naming an umbrella are listed in [UMBRELLA_USERS], which may only shrink (0007 B4).
 */
class ServiceInterfaceSizeTest {
    // R5-1: from the repository root (the tests run in composeApp/); the files are in their modules.
    private val files = listOf(
        "../ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/AppServices.kt",
        "../feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/BrowseContracts.kt",
    )

    @Test
    fun noServiceInterfaceHasMoreThanFifteenMembers() {
        val interfaces = files.flatMap { parse(File(it).readLines()) }.associateBy { it.name }
        fun count(name: String, seen: Set<String> = emptySet()): Int {
            val declared = interfaces[name] ?: return 0
            if (name in seen) return 0
            return declared.members + declared.parents.sumOf { count(it, seen + name) }
        }
        val tooLarge = interfaces.keys.filter { it !in UMBRELLAS }.mapNotNull { name ->
            count(name).takeIf { it > MAX_MEMBERS }?.let { "$name: $it" }
        }
        assertTrue(tooLarge.isEmpty(), "Split these interfaces (max $MAX_MEMBERS members, inherited included): $tooLarge")
        assertTrue(UMBRELLAS.all { it in interfaces }, "An umbrella is gone; remove it from UMBRELLAS")
    }

    /** Only the listed files name an umbrella; depend on the narrow interface you use instead. */
    @Test
    fun onlyTheListedFilesNameAnUmbrella() {
        val users = MODULES.flatMap { module ->
            File("../$module/src").walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.parentFile.path.contains("Main/") }
                .filter { file -> UMBRELLAS.any { Regex("""\b$it\b""").containsMatchIn(file.readText()) } }
                .map { it.relativeTo(File("..")).invariantSeparatorsPath }
                .toList()
        }.toSortedSet()
        val added = users - UMBRELLA_USERS - DECLARING_FILES
        val gone = UMBRELLA_USERS - users
        assertTrue(added.isEmpty(), "Depend on a narrow interface instead of an umbrella in: $added")
        assertEquals(emptySet(), gone, "No longer names an umbrella; delete these from UMBRELLA_USERS")
    }

    private class Declared(val name: String, val parents: List<String>, val members: Int)

    private fun parse(lines: List<String>): List<Declared> =
        lines.indices.filter { HEADER.containsMatchIn(lines[it]) }.map { start ->
            val open = (start until lines.size).first { lines[it].trimEnd().endsWith("{") }
            val header = lines.subList(start, open + 1).joinToString(" ")
            val name = HEADER.find(lines[start])!!.groupValues[1]
            val parents = header.substringAfter(name).substringBefore("{").substringAfter(":", "")
                .split(',').map { it.trim().substringBefore('<') }.filter { it.isNotEmpty() }
            val end = (open until lines.size).first { lines[it] == "}" }
            Declared(name, parents, (open + 1 until end).count { MEMBER.containsMatchIn(lines[it]) })
        }

    private companion object {
        const val MAX_MEMBERS = 15
        val HEADER = Regex("""^interface (\w+)""")
        val MEMBER = Regex("""^ {4}(?:suspend )?(?:fun|val|var) """)
        // C1 (0007): every UI module; the umbrellas are the shell's, the feature UI modules never name them.
        val MODULES = listOf("ui-core", "ui-reader", "ui-translation", "ui-sync", "ui", "feature-plugin", "composeApp")
        val UMBRELLAS = setOf("ShinsouAppServices", "BrowseCallbacks")
        val DECLARING_FILES = setOf(
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/AppServices.kt",
            "feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/BrowseContracts.kt",
        )

        /** The files still naming an umbrella besides where it is declared; only shrinks. */
        val UMBRELLA_USERS = sortedSetOf(
            "composeApp/src/androidMain/kotlin/dev/shinsou/kmp/AndroidAppServices.kt",
            "composeApp/src/commonMain/kotlin/dev/shinsou/kmp/app/App.kt",
            "composeApp/src/desktopMain/kotlin/dev/shinsou/kmp/desktop/DesktopAppServices.kt",
            "composeApp/src/iosMain/kotlin/dev/shinsou/kmp/IosAppServices.kt",
            "feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginUiAdapter.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouApp.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouAppPanes.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouAppState.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/screens/BrowseScreen.kt",
        )
    }
}
