package dev.shinsou.build

import app.cash.sqldelight.gradle.SqlDelightExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
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
 * the build when one falls inside the registry's range or two are the same.
 */
public class SqlDelightPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("app.cash.sqldelight")
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
        val problems = buildList {
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
        if (problems.isNotEmpty()) throw GradleException("SQL statement identifiers collide:\n" + problems.joinToString("\n"))
    }

    private fun registryConstant(source: String, name: String): Long =
        Regex("""const val $name(?::\s*Int)?\s*=\s*([0-9_]+)""").find(source)?.groupValues?.get(1)?.replace("_", "")?.toLong()
            ?: throw GradleException("$name not found in ${registrySource.get().asFile}")
}

/** `driver.execute(id, ...)`, `driver.executeQuery(id, ...)` and the parameterless `Query(id, ...)`. */
private val STATEMENT = Regex("""(?:\.execute|\.executeQuery|\bQuery)\(\s*(-?[0-9][0-9_]*)\s*,""")
private const val REGISTRY_PROJECT = ":data-content"
private const val REGISTRY_SOURCE = "src/commonMain/kotlin/dev/shinsou/kmp/content/SqlStatementIdentifiers.kt"
private const val CHECK_TASK = "checkSqlStatementIdentifiers"
private const val SQ_SOURCES = "src/commonMain/sqldelight"
private const val GENERATED_SOURCES = "build/generated/sqldelight/code"
