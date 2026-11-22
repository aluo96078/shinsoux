package dev.shinsou.build

import app.cash.sqldelight.gradle.SqlDelightExtension
import app.cash.sqldelight.gradle.SqlDelightTask
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * C4 (docs/architecture/0007 §6.2): SQLDelight writes the fixed queries of a module's table groups
 * (`src/commonMain/sqldelight`, one database per table group, declared in the module script).
 * Only the query code is used: the tables and their versions stay with `SchemaMigrator`, whose
 * create step may call the generated `Schema.create`; the generated `Schema` is never given to a
 * driver (it would rewrite `user_version`). Synchronous code, the SQLite 3.18 dialect (no newer
 * than Android 10's 3.22: no UPSERT), no migration files. The generated code is left out of
 * detekt and Kover (QualityPlugin).
 *
 * Generated statements carry hashed cache identifiers on the same driver as the numbered
 * registry of `:data-content` (`SqlStatementIdentifiers`): [CheckSqlStatementIdentifiers] fails
 * the build when one falls inside the registry's range or two are the same. It also fails when
 * product code (any module's `src/<target>Main`) hands a driver a literal null identifier, which would
 * silently turn the prepared-statement cache off: statements take their identifier from the
 * registry (`executeStatement` / `queryStatement`), and `executeUncached` / `queryUncached` are the
 * explicit, greppable exemption for DDL, PRAGMA and statements with unbounded text.
 *
 * SQLDelight 2.1 writes every generated top-level declaration `public` and has no setting for its
 * visibility. A module whose public API reaches another module's headers (the iOS framework
 * exports `:feature-translation`) can set `shinsouSqlDelight { internalGeneratedCode.set(true) }`:
 * the generator task then rewrites the top-level `public` of its output to `internal`
 * ([ShinsouSqlDelightExtension]), and the module wraps what others need.
 */
public class SqlDelightPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("app.cash.sqldelight")
        val settings = extensions.create("shinsouSqlDelight", ShinsouSqlDelightExtension::class.java)
        settings.internalGeneratedCode.convention(false)
        val internalize = settings.internalGeneratedCode
        tasks.withType(SqlDelightTask::class.java).configureEach {
            doLast {
                if (internalize.get()) {
                    outputs.files.asFileTree.matching { include("**/*.kt") }.forEach { file ->
                        file.writeText(file.readText().replace(TOP_LEVEL_PUBLIC, "internal "))
                    }
                }
            }
        }
        val sqldelightVersion = catalogVersion("sqldelight")
        extensions.getByType(SqlDelightExtension::class.java).apply {
            // KmpPlugin links every native binary with -lsqlite3 already.
            linkSqlite.set(false)
            databases.configureEach {
                dialect("app.cash.sqldelight:sqlite-3-18-dialect:$sqldelightVersion")
                generateAsync.set(false)
                deriveSchemaFromMigrations.set(false)
                verifyMigrations.set(false)
            }
        }

        val check = registerIdentifierCheck()
        // Every module with generated queries is checked by its own tests and `check`.
        tasks.matching { it.name == "check" || it.name == "desktopTest" }.configureEach { dependsOn(check) }
    }
}

/** `shinsouSqlDelight { ... }` of a module with SQLDelight sources. */
public abstract class ShinsouSqlDelightExtension {
    /** Whether the generated top-level declarations are `internal` (SQLDelight writes them `public`). */
    public abstract val internalGeneratedCode: Property<Boolean>
}

/** `public ` at the start of a line: the top-level declarations (members are indented). */
private val TOP_LEVEL_PUBLIC = Regex("""^public """, RegexOption.MULTILINE)

/** The check, in the module that owns the registry; every SQLDelight module depends on it. */
private fun Project.registerIdentifierCheck(): String {
    val owner = rootProject.project(REGISTRY_PROJECT)
    val path = "$REGISTRY_PROJECT:$CHECK_TASK"
    if (this != owner) return path
    tasks.register(CHECK_TASK, CheckSqlStatementIdentifiers::class.java) {
        group = "verification"
        description = "Fails when a SQLDelight statement identifier collides with another or with the registry's range."
        // Every module with .sq sources, including databases kept in their own folder (sqldelight-hotstate).
        val modules = rootProject.subprojects.filter { project ->
            File(project.projectDir, SQ_SOURCES).parentFile.listFiles().orEmpty()
                .any { it.isDirectory && it.name.startsWith(File(SQ_SOURCES).name) }
        }
        modules.forEach { module ->
            generatedSources.from(File(module.projectDir, GENERATED_SOURCES))
            dependsOn("${module.path}:generateSqlDelightInterface")
        }
        rootProject.subprojects.forEach { module ->
            productSources.from(module.fileTree(File(module.projectDir, "src")) { include("*Main/**/*.kt") })
        }
        registrySource.set(owner.file(REGISTRY_SOURCE))
        report.set(layout.buildDirectory.file("reports/sqlStatementIdentifiers.txt"))
    }
    return path
}

/**
 * Reads the generated statement identifiers ([STATEMENT]) of every SQLDelight module and the registry's first identifier and size from its source; any
 * identifier in `[FIRST_IDENTIFIER, FIRST_IDENTIFIER + DEFAULT_MAX_ENTRIES)` or used by two
 * statements fails the build.
 */
public abstract class CheckSqlStatementIdentifiers : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val generatedSources: ConfigurableFileCollection

    /** Every module's `src/<target>Main` sources, scanned for a literal null identifier. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val productSources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val registrySource: RegularFileProperty

    @get:OutputFile
    public abstract val report: RegularFileProperty

    @TaskAction
    public fun check() {
        val registry = registrySource.get().asFile.readText()
        val first = registryConstant(registry, "FIRST_IDENTIFIER")
        val size = registryConstant(registry, "DEFAULT_MAX_ENTRIES")
        val reserved = first until first + size

        val uses = HashMap<Long, MutableList<String>>()
        generatedSources.asFileTree.matching { include("**/*.kt") }.files.sortedBy { it.path }.forEach { file ->
            STATEMENT.findAll(file.readText()).forEach { match ->
                uses.getOrPut(match.groupValues[1].replace("_", "").toLong(), ::mutableListOf) += file.name
            }
        }
        val nullIdentifiers = nullIdentifierUses()
        val problems = buildList {
            nullIdentifiers.forEach {
                add("$it passes a null statement identifier; use executeStatement/queryStatement, or executeUncached/queryUncached for DDL, PRAGMA and unbounded text")
            }
            uses.forEach { (id, files) ->
                if (id in reserved) add("$id (${files.joinToString()}) is in the registry's range $reserved")
                if (files.size > 1) add("$id is used by ${files.size} statements (${files.joinToString()})")
            }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(uses.keys.sorted().joinToString("\n", postfix = "\n") { "$it ${uses.getValue(it).joinToString()}" })
        }
        if (uses.isEmpty()) throw GradleException("No SQLDelight statement identifiers found in ${generatedSources.files}")
        if (problems.isNotEmpty()) throw GradleException("SQL statement identifiers are not usable:\n" + problems.joinToString("\n"))
    }

    /** `file:line` of every `execute(null, ...)`, `executeQuery(null, ...)` or `identifier = null` in product code. */
    private fun nullIdentifierUses(): List<String> {
        // The registry's own file holds the exemption helpers, which are the one place that passes null.
        val exempt = registrySource.get().asFile
        return productSources.files.filter { it != exempt }.sortedBy { it.path }.flatMap { file ->
            val text = file.readText()
            NULL_IDENTIFIER.findAll(text).filterNot { isInComment(text, it.range.first) }.map { match ->
                "${file.name}:${text.substring(0, match.range.first).count { it == '\n' } + 1}"
            }.toList()
        }
    }

    /** True when [index] lies in a `//` line comment or a block comment (KDoc included). */
    private fun isInComment(text: String, index: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', index) + 1
        if (text.substring(lineStart, index).contains("//")) return true
        val open = text.lastIndexOf("/*", index)
        return open >= 0 && text.lastIndexOf("*/", index).let { it < open }
    }

    private fun registryConstant(source: String, name: String): Long =
        Regex("""const val $name(?::\s*Int)?\s*=\s*([0-9_]+)""").find(source)?.groupValues?.get(1)?.replace("_", "")?.toLong()
            ?: throw GradleException("$name not found in ${registrySource.get().asFile}")
}

/** `driver.execute(id, ...)`, `driver.executeQuery(id, ...)` and the parameterless `Query(id, ...)`. */
private val STATEMENT = Regex("""(?:\.execute|\.executeQuery|\bQuery)\(\s*(-?[0-9][0-9_]*)\s*,""")
/** A driver call with a literal null identifier (the call's first argument, positional or named). */
private val NULL_IDENTIFIER = Regex("""(?:\.execute|\.executeQuery)\(\s*(?:identifier\s*=\s*)?null\s*,|\bidentifier\s*=\s*null\b""")
private const val REGISTRY_PROJECT = ":data-content"
private const val REGISTRY_SOURCE = "src/commonMain/kotlin/dev/shinsou/kmp/content/SqlStatementIdentifiers.kt"
private const val CHECK_TASK = "checkSqlStatementIdentifiers"
private const val SQ_SOURCES = "src/commonMain/sqldelight"
private const val GENERATED_SOURCES = "build/generated/sqldelight/code"
