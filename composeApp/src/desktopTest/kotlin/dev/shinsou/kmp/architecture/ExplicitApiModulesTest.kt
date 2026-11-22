package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0007 §7 follow-up 4: Kotlin's strict explicit API mode (`explicitApi()`) makes every public
 * declaration of a module a decision: `public` for what another module uses, `internal` for the
 * rest. Every Gradle module of settings.gradle.kts, the UI modules (`:ui`, `:ui-*`) included, must
 * switch it on in its own build script, so a new module cannot skip it. The one exception is the
 * app (`:composeApp`): it is the composition root, nothing depends on it, so it has no API to
 * keep narrow. `explicitApiWarning()` does not count: it never fails a build.
 */
class ExplicitApiModulesTest {
    private val root = File("..")
    private val modules: List<String> =
        INCLUDE.findAll(File(root, "settings.gradle.kts").readText()).map { it.groupValues[1] }.toList()

    @Test
    fun everyLibraryModuleEnablesExplicitApi() {
        val without = modules.filterNot(::isApp).filter { module ->
            val script = File(root, "$module/build.gradle.kts")
            !script.isFile || !STRICT.containsMatchIn(withoutLineComments(script.readText()))
        }
        if (without.isNotEmpty()) fail(without.joinToString("\n", "These modules must call explicitApi() in their build script:\n") { "  :$it" })
    }

    /** The guard is not vacuous: the modules that have it today are all found and checked. */
    @Test
    fun theCheckedModulesAreTheKnownLibraryModules() {
        val checked = modules.filterNot(::isApp).toSet()
        assertTrue(checked.containsAll(KNOWN_MODULES), "Missing from settings.gradle.kts or misnamed: ${KNOWN_MODULES - checked}")
    }

    private fun isApp(module: String): Boolean = module == APP_MODULE

    private fun withoutLineComments(script: String): String = script.lines().joinToString("\n") { it.substringBefore("//") }

    private companion object {
        const val APP_MODULE = "composeApp"
        val INCLUDE = Regex("""^include\(":([\w-]+)"\)""", RegexOption.MULTILINE)
        val STRICT = Regex("""(?m)^\s*explicitApi\(\)\s*$""")
        val KNOWN_MODULES = setOf(
            "core", "core-network", "domain", "data-persistence-api", "data-content", "data-repository",
            "feature-sync", "feature-library", "feature-reader", "feature-translation", "feature-plugin",
            "ui-core", "ui-reader", "ui-translation", "ui-sync", "ui-browse", "ui-library", "ui-settings", "ui",
        )
    }
}
