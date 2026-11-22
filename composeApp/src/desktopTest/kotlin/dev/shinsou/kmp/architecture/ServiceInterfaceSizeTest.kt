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
 * 0008: the service interfaces are found, not listed: every interface declared in the main sources
 * of a UI module (:ui and :ui-*), and every interface of a feature module (:feature-*) that a UI
 * module's main sources name. The modules come from settings.gradle.kts, so a new one is covered.
 *
 * The only interfaces allowed over the limit are the [UMBRELLAS]: the bundles the platform hosts
 * implement and the composition root hands over. Code depends on the narrow interfaces instead;
 * the files still naming an umbrella are listed in [UMBRELLA_USERS], which may only shrink (0007 B4).
 */
class ServiceInterfaceSizeTest {
    // R5-1: from the repository root (the tests run in composeApp/); the files are in their modules.
    private val modules: List<String> =
        INCLUDE.findAll(File("../settings.gradle.kts").readText()).map { it.groupValues[1] }.toList()
    private val uiModules = modules.filter { it == "ui" || it.startsWith("ui-") }
    private val featureModules = modules.filter { it.startsWith("feature-") }

    private fun mainSources(module: String): List<File> = File("../$module/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.parentFile.invariantSeparatorsPath.contains("Main/") }
        .toList()

    @Test
    fun noServiceInterfaceHasMoreThanFifteenMembers() {
        val uiDeclared = uiModules.flatMap(::mainSources).flatMap { parse(it.readLines()) }
        val featureDeclared = featureModules.flatMap(::mainSources).flatMap { parse(it.readLines()) }
        val interfaces = (featureDeclared + uiDeclared).associateBy { it.name }
        fun count(name: String, seen: Set<String> = emptySet()): Int {
            val declared = interfaces[name] ?: return 0
            if (name in seen) return 0
            return declared.members + declared.parents.sumOf { count(it, seen + name) }
        }
        val uiText = uiModules.flatMap(::mainSources).joinToString("\n") { it.readText() }
        val services = uiDeclared.map { it.name }.toSet() +
            featureDeclared.map { it.name }.filter { Regex("""\b$it\b""").containsMatchIn(uiText) }
        assertTrue(KNOWN_SERVICES.all { it in services }, "The scan lost a known service: ${KNOWN_SERVICES - services}")
        val tooLarge = services.filter { it !in UMBRELLAS }.mapNotNull { name ->
            count(name).takeIf { it > MAX_MEMBERS }?.let { "$name: $it" }
        }
        assertTrue(tooLarge.isEmpty(), "Split these interfaces (max $MAX_MEMBERS members, inherited included): $tooLarge")
        assertTrue(UMBRELLAS.all { it in services }, "An umbrella is gone; remove it from UMBRELLAS")
    }

    /** Only the listed files name an umbrella; depend on the narrow interface you use instead. */
    @Test
    fun onlyTheListedFilesNameAnUmbrella() {
        val users = (uiModules + featureModules + APP_MODULE).flatMap { module ->
            mainSources(module)
                .filter { file -> UMBRELLAS.any { Regex("""\b$it\b""").containsMatchIn(file.readText()) } }
                .map { it.relativeTo(File("..")).invariantSeparatorsPath }
        }.toSortedSet()
        val added = users - UMBRELLA_USERS - DECLARING_FILES
        val gone = UMBRELLA_USERS - users
        assertTrue(added.isEmpty(), "Depend on a narrow interface instead of an umbrella in: $added")
        assertEquals(emptySet(), gone, "No longer names an umbrella; delete these from UMBRELLA_USERS")
    }

    private class Declared(val name: String, val parents: List<String>, val members: Int)

    /** The top-level interfaces of a file; one without a body (a bundle of others) has no members of its own. */
    private fun parse(lines: List<String>): List<Declared> =
        lines.indices.filter { HEADER.containsMatchIn(lines[it]) }.map { start ->
            val name = HEADER.find(lines[start])!!.groupValues[1]
            val last = (start until lines.size).firstOrNull { lines[it].isBlank() || '{' in lines[it] } ?: lines.lastIndex
            val open = last.takeIf { '{' in lines[it] }
            val header = lines.subList(start, (open ?: last) + 1).joinToString(" ")
            val parents = header.substringAfter(name).substringBefore("{").substringAfter(":", "")
                .split(',').map { it.trim().substringBefore('<') }.filter { it.isNotEmpty() }
            val members = when {
                open == null -> 0
                lines[open].trimEnd().endsWith("}") -> INLINE_MEMBER.findAll(lines[open].substringAfter('{')).count()
                else -> (open + 1 until (open until lines.size).first { lines[it] == "}" }).count { MEMBER.containsMatchIn(lines[it]) }
            }
            Declared(name, parents, members)
        }

    private companion object {
        const val MAX_MEMBERS = 15
        const val APP_MODULE = "composeApp"
        val INCLUDE = Regex("""^include\(":([\w-]+)"\)""", RegexOption.MULTILINE)
        val HEADER = Regex("""^(?:(?:public|internal|private|sealed|fun) )*interface (\w+)""")
        val MODIFIERS = """(?:(?:public|internal|override|abstract|open|suspend|operator|inline) )*"""
        val MEMBER = Regex("""^ {4}$MODIFIERS(?:fun|val|var) """)
        val INLINE_MEMBER = Regex("""\b(?:fun|val|var) """)

        /** Services the scan must find, so it cannot silently stop finding anything. */
        val KNOWN_SERVICES = setOf("SourceCatalog", "SyncAccount", "MangaChapterTranslation", "ContentFeatures", "ClipboardHost")

        val UMBRELLAS = setOf("ShinsouAppServices", "BrowseCallbacks", "SyncClient", "MangaTranslationService")
        val DECLARING_FILES = setOf(
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/AppServices.kt",
            "feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/BrowseContracts.kt",
            "feature-sync/src/commonMain/kotlin/dev/shinsou/kmp/sync/v3/SyncClient.kt",
            "feature-translation/src/commonMain/kotlin/dev/shinsou/kmp/manga/MangaTranslationService.kt",
        )

        /** The files still naming an umbrella besides where it is declared; only shrinks. */
        val UMBRELLA_USERS = sortedSetOf(
            "composeApp/src/androidMain/kotlin/dev/shinsou/kmp/AndroidAppServices.kt",
            "composeApp/src/commonMain/kotlin/dev/shinsou/kmp/app/App.kt",
            "composeApp/src/desktopMain/kotlin/dev/shinsou/kmp/desktop/DesktopAppServices.kt",
            "composeApp/src/iosMain/kotlin/dev/shinsou/kmp/IosAppServices.kt",
            "feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginUiAdapter.kt",
            "feature-sync/src/commonMain/kotlin/dev/shinsou/kmp/sync/v3/SyncV3Service.kt",
            "feature-translation/src/commonMain/kotlin/dev/shinsou/kmp/manga/MangaTranslation.kt",
            "ui-translation/src/commonMain/kotlin/dev/shinsou/kmp/ui/translation/LocalMangaTranslation.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouApp.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouAppPanes.kt",
            "ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouAppState.kt",
        )
    }
}
