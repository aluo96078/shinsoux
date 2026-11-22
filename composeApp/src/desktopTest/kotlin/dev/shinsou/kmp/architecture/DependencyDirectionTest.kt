package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-2 (docs/architecture/0006): the layering of docs/architecture/0006 §4.1, checked on the
 * sources until the Gradle modules enforce it. A reference that breaks a rule fails unless it is
 * in `config/architecture/dependency-allowlist.txt`; the allowlist may only shrink, so an entry
 * that no longer breaks a rule fails too and must be deleted.
 *
 * R5-1: a package that has become a Gradle module of §4.2 is checked by the compiler instead: the
 * module compiles only against the modules its build script names, and [moduleDependenciesPointDown]
 * keeps those below it. The reference rules therefore only scan what is still in composeApp, and
 * [packagesLiveInTheirModule] keeps every package in the module the table names. Since :ui became a
 * module, composeApp holds only app, desktop and the entry points (the top layer, which may use
 * every module): of the scanned rules only "package-without-a-module" can still fire there (a new
 * top-level package missing from [MODULES]); the others are kept for the allowlists' format.
 *
 * C1 (0007): the UI is split into :ui-core, the feature UI modules and :ui, all under `ui.`; a
 * package belongs to the module of the longest [MODULES] key it starts with (`ui.reader.x` is
 * :ui-reader's, `ui.screens` falls back to `ui`, the shell's), so a package moves with its module.
 * 0008 §7-5: the section UI modules (:ui-browse, :ui-library, :ui-settings) are between the feature
 * UI modules and the shell.
 */
class DependencyDirectionTest {
    private val sourceSets = listOf("commonMain", "jvmCommonMain", "desktopMain", "androidMain", "iosMain")
    private val allowlist = File("../config/architecture/dependency-allowlist.txt")
    private val moduleAllowlist = File("../config/architecture/module-allowlist.txt")

    /** The Gradle modules of settings.gradle.kts other than the app: the extracted ones of §4.2. */
    private val libraryModules: List<String> =
        INCLUDE.findAll(File("../settings.gradle.kts").readText()).map { it.groupValues[1] }.filter { it != APP_MODULE }.toList()

    @Test
    fun noPackageReachesUpTheLayers() = check(allowlist, "docs/architecture/0006 §4.1", ::broken)

    /**
     * The modules of §4.2, before they are Gradle modules: a package refers only to its own
     * module and to modules of the layers below. What breaks that today is listed in
     * `config/architecture/module-allowlist.txt`, which only shrinks (R5-1 needs it empty).
     */
    @Test
    fun noModuleReachesUpOrAcross() = check(moduleAllowlist, "docs/architecture/0006 §4.2", ::acrossModules)

    /** R5-1: every package is in the Gradle module the table names; composeApp holds the rest. */
    @Test
    fun packagesLiveInTheirModule() {
        assertTrue(libraryModules.all { it in MODULES.values.map(Pair<String, Int>::first) }, "Unknown module in $libraryModules")
        val misplaced = (libraryModules + APP_MODULE).flatMap { module ->
            kotlinFiles(module).mapNotNull { (file, pkg) ->
                val owner = moduleOf(pkg)?.first ?: return@mapNotNull "$file: package without a module"
                val expected = if (owner in libraryModules) owner else APP_MODULE
                if (expected == module) null else "$file: belongs to :$expected"
            }
        }
        if (misplaced.isNotEmpty()) fail(misplaced.sorted().joinToString("\n", "Move these files:\n") { "  $it" })
    }

    /** R5-1: a module's build script depends only on modules of lower layers. */
    @Test
    fun moduleDependenciesPointDown() {
        val layers = MODULES.values.toMap() + (APP_MODULE to MODULES.getValue(ROOT).second)
        val upward = (libraryModules + APP_MODULE).flatMap { module ->
            val script = File("../$module/build.gradle.kts").readText()
            PROJECT_DEPENDENCY.findAll(script).map { it.groupValues[1] }
                .filter { layers.getValue(it) >= layers.getValue(module) }
                .map { ":$module -> :$it" }
                .toList()
        }
        if (upward.isNotEmpty()) fail(upward.joinToString("\n", "Module dependencies against the layers (§4.2):\n") { "  $it" })
    }

    /**
     * 0007 §7 follow-up 3: the bottom modules use no other module of the app at all. :core never
     * did; :domain once did for one lock, so a layer-1 module could have depended on :core again.
     */
    @Test
    fun bottomModulesDependOnNoModule() {
        val dependencies = BOTTOM_MODULES.flatMap { module ->
            PROJECT_DEPENDENCY.findAll(File("../$module/build.gradle.kts").readText()).map { ":$module -> :${it.groupValues[1]}" }.toList()
        }
        if (dependencies.isNotEmpty()) fail(dependencies.joinToString("\n", "These modules must depend on no other module:\n") { "  $it" })
    }

    /**
     * 0008: the design system (:ui-core) depends on no feature module, although the layers would
     * allow it: every UI module depends on :ui-core, so a feature's ABI change would recompile all
     * of them. A feature type a shared component needs comes through a small interface or a plain
     * value declared in :ui-core (`PluginImageSource`, `translationServiceName`); the labels and
     * composition locals of a feature live in the UI module that shows it.
     */
    @Test
    fun designSystemDependsOnNoFeatureModule() {
        val features = DESIGN_SYSTEM_MODULES.flatMap { module ->
            PROJECT_DEPENDENCY.findAll(File("../$module/build.gradle.kts").readText()).map { it.groupValues[1] }
                .filter { it.startsWith(FEATURE_MODULE_PREFIX) }
                .map { ":$module -> :$it" }
                .toList()
        }
        if (features.isNotEmpty()) fail(features.joinToString("\n", "The design system depends on a feature module:\n") { "  $it" })
    }

    /** The module of [pkg] (below `dev.shinsou.kmp`): the entry of its longest prefix in [MODULES]. */
    private fun moduleOf(pkg: String): Pair<String, Int>? =
        generateSequence(pkg) { it.substringBeforeLast('.', "").ifEmpty { null } }.firstNotNullOfOrNull { MODULES[it] }

    /** `(<module>/src/<path>, package below dev.shinsou.kmp)` of every product source file of [module]. */
    private fun kotlinFiles(module: String): List<Pair<String, String>> = sourceSets.flatMap { set ->
        File("../$module/src/$set/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
            val from = file.useLines { lines -> lines.firstNotNullOfOrNull { PACKAGE.matchEntire(it.trim())?.groupValues?.get(1) } }
                ?: return@mapNotNull null
            "$module/src/$set/kotlin/${file.relativeTo(File("../$module/src/$set/kotlin")).invariantSeparatorsPath}" to
                from.ifEmpty { ROOT }
        }.toList()
    }

    private fun check(allowlist: File, rules: String, broken: (String, String) -> String?) {
        val found = violations(broken)
        val allowed = allowlist.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val added = found - allowed
        val fixed = allowed - found
        if (added.isEmpty() && fixed.isEmpty()) return
        fail(buildString {
            if (added.isNotEmpty()) {
                appendLine("New references against the layering ($rules):")
                added.sorted().forEach { appendLine("  $it") }
            }
            if (fixed.isNotEmpty()) {
                appendLine("No longer broken; delete these lines from ${allowlist.path}:")
                fixed.sorted().forEach { appendLine("  $it") }
            }
        })
    }

    /**
     * `<source file>|<package referred to>|<rule>`, one per file and package, for the sources still
     * in composeApp (the library modules' references are the compiler's to check).
     */
    private fun violations(broken: (String, String) -> String?): Set<String> = buildSet {
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

    // R5-1 removed "domain-depends-only-on-core" and "core-depends-only-on-core": those packages
    // are the :core and :domain modules, which compile against nothing above them.
    private fun broken(from: String, to: String): String? = when {
        to in APP && from !in APP -> "only-entry-points-use-app"
        to in UI && from !in UI && from !in APP -> "only-ui-and-app-use-ui"
        else -> null
    }

    private fun acrossModules(from: String, to: String): String? {
        val (fromModule, fromLayer) = MODULES[from] ?: return "package-without-a-module"
        val (toModule, toLayer) = MODULES[to] ?: return "package-without-a-module"
        return when {
            fromModule == toModule || toLayer < fromLayer -> null
            toLayer == fromLayer -> "$fromModule-reaches-across-to-$toModule"
            else -> "$fromModule-reaches-up-to-$toModule"
        }
    }

    private companion object {
        val PACKAGE = Regex("""package dev\.shinsou\.kmp\.?([\w.]*)""")
        val REFERENCE = Regex("""\bdev\.shinsou\.kmp\.([a-z]\w*)\.""")
        val ROOT_REFERENCE = Regex("""\bdev\.shinsou\.kmp\.[A-Z]\w*""")
        val INCLUDE = Regex("""^include\(":([\w-]+)"\)""", RegexOption.MULTILINE)
        val PROJECT_DEPENDENCY = Regex("""\bproject\(":([\w-]+)"\)""")
        const val APP_MODULE = "composeApp"
        val BOTTOM_MODULES = listOf("core", "domain")
        const val FEATURE_MODULE_PREFIX = "feature-"
        val DESIGN_SYSTEM_MODULES = listOf("ui-core")
        val QUOTED = Regex("""'(?:\\.|[^'\\])'|"(?:\\.|[^"\\])*"""")
        val INLINE_COMMENT = Regex("""/\*.*?\*/""")
        const val ROOT = "(root)"
        val APP = setOf("app", "desktop", ROOT)
        val UI = setOf("ui", "navigation")

        /** Package to (module, layer) of §4.2; a higher layer may use a lower one, never the reverse. */
        val MODULES: Map<String, Pair<String, Int>> = buildMap {
            fun module(name: String, layer: Int, vararg packages: String) = packages.forEach { put(it, name to layer) }
            module("core", 0, "concurrent", "crypto", "files", "logging", "buildinfo", "testing")
            module("core-network", 1, "network")
            module("domain", 1, "domain", "rights", "contract")
            // 0007 §7 follow-up 3: the C3 persistence ports, between the models and their implementers.
            module("data-persistence-api", 2, "persistence")
            module("data-content", 3, "content", "annotation", "search")
            module("data-repository", 3, "data", "migration")
            module("feature-plugin", 4, "plugin")
            module("feature-sync", 4, "sync")
            module("feature-translation", 4, "translation", "manga")
            module("feature-reader", 4, "reader", "acquisition", "local", "tts")
            module("feature-library", 4, "backup", "download", "tracking", "statistics", "update")
            // C1 (0007): the design system, then the feature UI modules (which never use each other),
            // then the shell, which wires them.
            module("ui-core", 5, "ui.core", "ui.theme", "ui.components", "ui.i18n", "ui.markdown")
            module("ui-reader", 6, "ui.reader")
            module("ui-translation", 6, "ui.translation")
            module("ui-sync", 6, "ui.sync")
            // 0008 §7-5: the section UI modules, one per tab of the shell. They embed the feature UI
            // modules (the publication page opens the reader, its reviews and sharing; More opens the
            // sync settings), so they sit a layer above them, and never use each other or the shell.
            module("ui-browse", 7, "ui.browse", "ui.challenge")
            module("ui-library", 7, "ui.library")
            module("ui-settings", 7, "ui.settings", "ui.portability")
            module("ui", 8, "ui", "navigation")
            module("app", 9, "app", "desktop", ROOT)
        }
    }
}
