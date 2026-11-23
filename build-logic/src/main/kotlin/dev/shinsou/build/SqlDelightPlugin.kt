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
            // The rewrite changes the task's output, so the setting is part of its cache key.
            inputs.property("shinsouInternalGeneratedCode", internalize)
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
        // Kover leaves out generated code by package (QualityPlugin): a database in another one
        // would be measured as hand-written code.
        afterEvaluate {
            extensions.getByType(SqlDelightExtension::class.java).databases.forEach { database ->
                val generated = database.packageName.get()
                if (generated !in SQLDELIGHT_GENERATED_PACKAGES) {
                    throw GradleException("$path: SQLDelight database ${database.name} generates into $generated; add it to SQLDELIGHT_GENERATED_PACKAGES (QualityPlugin)")
                }
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

    /** `file:line` of every [nullIdentifierCalls] in product code but the registry's own file. */
    private fun nullIdentifierUses(): List<String> {
        // The registry's own file holds the exemption helpers, which are the one place that passes null.
        val exempt = registrySource.get().asFile
        return productSources.files.filter { it != exempt }.sortedBy { it.path }.flatMap { file ->
            val text = file.readText()
            nullIdentifierCalls(text).map { offset -> "${file.name}:${text.substring(0, offset).count { it == '\n' } + 1}" }
        }
    }


    private fun registryConstant(source: String, name: String): Long =
        Regex("""const val $name(?::\s*Int)?\s*=\s*([0-9_]+)""").find(source)?.groupValues?.get(1)?.replace("_", "")?.toLong()
            ?: throw GradleException("$name not found in ${registrySource.get().asFile}")
}

/** `driver.execute(id, ...)`, `driver.executeQuery(id, ...)` and the parameterless `Query(id, ...)`. */
private val STATEMENT = Regex("""(?:\.execute|\.executeQuery|\bQuery)\(\s*(-?[0-9][0-9_]*)\s*,""")
/** The start of a call to `execute` or `executeQuery`, with or without a receiver, after optional type arguments. */
private val DRIVER_CALL = Regex("""(?<![\w$])(?:execute|executeQuery)\s*(?:<[^()]*>)?\s*\(""")
/** `fun` (and type parameters) right before a name: a declaration, not a call. */
private val DECLARATION = Regex("""\bfun\s*(?:<[^()]*>\s*)?(?:[\w.]+\.)?$""")
private val NAMED_NULL_IDENTIFIER = Regex("""^identifier\s*=\s*null$""")

/**
 * The offsets of the driver calls in [source] that pass a literal null identifier: the first
 * argument a positional `null` (`execute(null, sql, 0)`, `executeQuery<T>(null, sql, mapper, 0)`,
 * with or without a receiver), or `identifier = null` in any position. String literals and
 * comments are masked first ([maskStringsAndComments]), so neither an SQL text nor a KDoc example
 * counts.
 *
 * A positional null counts only when the SQL follows it: a statement identifier is never a call's
 * only argument (`SqlDriver.execute(identifier, sql, parameters)`, and the wrappers around it,
 * take the SQL next). So an ordinary function that happens to be named `execute` and takes one
 * nullable argument (`execute(null)`) is not a finding.
 */
internal fun nullIdentifierCalls(source: String): List<Int> {
    val text = maskStringsAndComments(source)
    return DRIVER_CALL.findAll(text).filter { call ->
        !DECLARATION.containsMatchIn(text.substring(maxOf(0, call.range.first - 80), call.range.first)) &&
            callArguments(text, call.range.last + 1).let { arguments ->
                arguments.firstOrNull() == "null" && arguments.size > 1 ||
                    arguments.any { NAMED_NULL_IDENTIFIER.matches(it) }
            }
    }.map { it.range.first }.toList()
}

/**
 * The top-level arguments of the call whose `(` is just before [from], trimmed, by position. A
 * trailing lambda's braces and nested calls are skipped.
 */
private fun callArguments(text: String, from: Int): List<String> {
    val arguments = mutableListOf<String>()
    var depth = 0
    var start = from
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth == 0) break else depth--
            ',' -> if (depth == 0) {
                arguments += text.substring(start, i).trim()
                start = i + 1
            }
        }
        i++
    }
    arguments += text.substring(start, minOf(i, text.length)).trim()
    // Every slot is kept, a masked string literal's blank one too, so a null further along is
    // never taken for the first argument; only the slot after a trailing comma (or of `()`) goes.
    if (arguments.last().isEmpty()) arguments.removeAt(arguments.lastIndex)
    return arguments
}

/**
 * [source] with the contents of its string and character literals and its comments replaced by
 * spaces (line breaks kept, so offsets and line numbers stay the same). Templates inside strings
 * are masked too; that only hides code from the null-identifier check, never adds a match.
 */
internal fun maskStringsAndComments(source: String): String {
    val out = StringBuilder(source)
    fun blank(from: Int, to: Int) {
        for (i in from until minOf(to, out.length)) if (out[i] != '\n') out[i] = ' '
    }
    var i = 0
    while (i < source.length) {
        val end = when {
            source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
            source.startsWith("/*", i) -> closingComment(source, i)
            source.startsWith("\"\"\"", i) -> closingRawString(source, i)
            source[i] == '"' || source[i] == '\'' -> closingQuote(source, i)
            else -> -1
        }
        if (end < 0) {
            i++
        } else {
            blank(i, end)
            i = end
        }
    }
    return out.toString()
}

/** The index after the end of the block comment opened at [start]; Kotlin block comments nest. */
private fun closingComment(source: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < source.length) {
        when {
            source.startsWith("/*", i) -> { depth++; i += 2 }
            source.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return i }
            else -> i++
        }
    }
    return source.length
}

/** The index after the raw string opened at [start]: its closing `"""` and any quotes right before it (`""""` ends in a quote). */
private fun closingRawString(source: String, start: Int): Int {
    val close = source.indexOf("\"\"\"", start + 3)
    if (close < 0) return source.length
    var end = close + 3
    while (end < source.length && source[end] == '"') end++
    return end
}

/** The index after the quote that closes the one-line string or char literal opened at [start]. */
private fun closingQuote(source: String, start: Int): Int {
    val quote = source[start]
    var i = start + 1
    while (i < source.length && source[i] != quote && source[i] != '\n') {
        i += if (source[i] == '\\') 2 else 1
    }
    return minOf(i + 1, source.length)
}

private const val REGISTRY_PROJECT = ":data-content"
private const val REGISTRY_SOURCE = "src/commonMain/kotlin/dev/shinsou/kmp/content/sql/SqlStatementIdentifiers.kt"
private const val CHECK_TASK = "checkSqlStatementIdentifiers"
private const val SQ_SOURCES = "src/commonMain/sqldelight"
private const val GENERATED_SOURCES = "build/generated/sqldelight/code"
