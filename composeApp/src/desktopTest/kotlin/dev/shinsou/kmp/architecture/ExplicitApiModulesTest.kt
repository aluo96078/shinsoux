package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0007 §7 follow-up 4: Kotlin's strict explicit API mode (`explicitApi()`) makes every public
 * declaration of a module a decision: `public` for what another module uses, `internal` for the
 * rest. Every Gradle module of settings.gradle.kts that is not UI (`:ui` and `:ui-*`, which
 * still have to be brought to it) or the app (`:composeApp`) must switch it on in its own build
 * script, so a new non-UI module cannot skip it. `explicitApiWarning()` does not count: it never fails a build.
 */
class ExplicitApiModulesTest {
    private val root = File("..")
    private val modules: List<String> =
        INCLUDE.findAll(File(root, "settings.gradle.kts").readText()).map { it.groupValues[1] }.toList()

    @Test
    fun everyNonUiModuleEnablesExplicitApi() {
        val without = modules.filterNot(::isUiModule).filter { module ->
            val script = File(root, "$module/build.gradle.kts")
            !script.isFile || !STRICT.containsMatchIn(withoutLineComments(script.readText()))
        }
        if (without.isNotEmpty()) fail(without.joinToString("\n", "These modules must call explicitApi() in their build script:\n") { "  :$it" })
    }

    /** The guard is not vacuous: the modules that have it today are all found and checked. */
    @Test
    fun theCheckedModulesAreTheKnownNonUiModules() {
        val checked = modules.filterNot(::isUiModule).toSet()
        assertTrue(checked.containsAll(KNOWN_NON_UI), "Missing from settings.gradle.kts or misnamed: ${KNOWN_NON_UI - checked}")
    }

    private fun isUiModule(module: String): Boolean = module == APP_MODULE || module == "ui" || module.startsWith("ui-")

    private fun withoutLineComments(script: String): String = script.lines().joinToString("\n") { it.substringBefore("//") }

    private companion object {
        const val APP_MODULE = "composeApp"
        val INCLUDE = Regex("""^include\(":([\w-]+)"\)""", RegexOption.MULTILINE)
        val STRICT = Regex("""(?m)^\s*explicitApi\(\)\s*$""")
        val KNOWN_NON_UI = setOf(
            "core", "core-network", "domain", "data-persistence-api", "data-content", "data-repository",
            "feature-sync", "feature-library", "feature-reader", "feature-translation", "feature-plugin",
        )
    }
}
